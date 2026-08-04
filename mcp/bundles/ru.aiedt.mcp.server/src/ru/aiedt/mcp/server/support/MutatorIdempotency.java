/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Map;
import java.util.Set;

/**
 * Decides whether a tool call carries an optional {@code operationId} idempotency
 * key that should route through {@link MutatorIdempotencyStore}, and builds the
 * store key.
 * <p>
 * The key is honoured only for a fixed, explicit allowlist of <em>synchronous</em>
 * mutators - tools that run their write to a terminal result inline. Two calls with
 * the same {@code operationId} then apply the mutation at most once (the store
 * caches the first success and coalesces a concurrent duplicate).
 * <p>
 * Deliberately excluded:
 * <ul>
 *   <li>{@code update_database} and {@code edit_metadata} with {@code batch=true} -
 *       already asynchronous through their own {@code runKey} Pending flow, which
 *       coalesces retries on its own;</li>
 *   <li>{@code extension_workshop} with {@code operation=borrow_objects} - same: a batch
 *       adoption that runs through its own {@code runKey} Pending flow. Caching its Pending
 *       response under an {@code operationId} would replay Pending on every poll and never
 *       let the caller collect the final batch result.</li>
 *   <li>{@code install_extension}, {@code uninstall_extension} and
 *       {@code import_configuration_from_xml} - multi-step operations that can leave
 *       a durable partial change and then report failure (e.g. an extension load
 *       followed by a database configuration update). The allow-retry policy assumes
 *       a failure left no durable effect, which does not hold for these, and
 *       replay-of-failure would be equally wrong - so they are left un-keyed until a
 *       per-tool partial-failure policy exists;</li>
 *   <li>{@code generate_event_handlers} - not a data mutator (it only builds a BSL
 *       string);</li>
 *   <li>any call with {@code dryRun=true} - a dry run does not mutate, so caching
 *       its result under an {@code operationId} would wrongly satisfy a later real
 *       call that reuses the id;</li>
 *   <li>{@code rename_metadata_object} / {@code delete_metadata_object} unless
 *       {@code confirm=true} - both return a cacheable success-shaped PREVIEW when
 *       not confirmed, so (as with {@code dryRun}) they are keyed only when the call
 *       actually mutates.</li>
 * </ul>
 * The kept mutators are each a single BM transaction (or a single file write) that
 * rolls back cleanly on failure, so allow-retry is safe for them.
 * Because {@code operationId} is not sent by any client today, enabling this path
 * changes no existing behaviour: a call without the key is never routed here.
 */
public final class MutatorIdempotency
{
    /**
     * The synchronous mutators whose result is safe to key by {@code operationId}.
     * Verified by reading each tool's {@code execute}: every one runs its write
     * inline to a terminal result (no {@code runKey} Pending response).
     */
    private static final Set<String> SYNC_MUTATORS = Set.of(
        "add_metadata_attribute", //$NON-NLS-1$
        "write_module_source", //$NON-NLS-1$
        "rename_metadata_object", //$NON-NLS-1$
        "delete_metadata_object", //$NON-NLS-1$
        "delete_infobase", //$NON-NLS-1$
        "delete_project", //$NON-NLS-1$
        "edit_form", //$NON-NLS-1$
        "dcs_workshop", //$NON-NLS-1$
        "mxl_workshop", //$NON-NLS-1$
        "xdto_workshop", //$NON-NLS-1$
        "extension_workshop", //$NON-NLS-1$
        "external_object_workshop", //$NON-NLS-1$
        "external_data_source_workshop", //$NON-NLS-1$
        "create_infobase", //$NON-NLS-1$
        "create_project", //$NON-NLS-1$
        "create_launch_config", //$NON-NLS-1$
        "set_infobase_credentials", //$NON-NLS-1$
        "edit_metadata"); //$NON-NLS-1$ - single-op only; batch is gated out below

    private MutatorIdempotency()
    {
    }

    /**
     * Whether this call should be served through the idempotency store.
     *
     * @param toolName the tool wire name
     * @param operationId the client-supplied key, or {@code null}
     * @param params the flattened call arguments (read for {@code dryRun} / {@code batch})
     * @return {@code true} only when a non-blank key is present, the tool is an
     *         allowlisted synchronous mutator, it is not a dry run, and it is not
     *         an {@code edit_metadata} batch
     */
    public static boolean applies(String toolName, String operationId, Map<String, String> params)
    {
        if (toolName == null || operationId == null || operationId.isBlank())
        {
            return false;
        }
        if (!SYNC_MUTATORS.contains(toolName))
        {
            return false;
        }
        if (isTrue(params, "dryRun")) //$NON-NLS-1$
        {
            return false;
        }
        // edit_metadata is mixed: the single-op path is synchronous, but batch=true
        // defers to its own async Pending flow, so only the single-op path is keyed.
        if ("edit_metadata".equals(toolName) && isTrue(params, "batch")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return false;
        }
        // extension_workshop borrow_objects is likewise async through its own runKey Pending flow;
        // its other operations stay keyed. Accept both the snake_case and camelCase token forms.
        if ("extension_workshop".equals(toolName) && params != null) //$NON-NLS-1$
        {
            String op = params.get("operation"); //$NON-NLS-1$
            if ("borrow_objects".equals(op) || "borrowObjects".equals(op)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return false;
            }
        }
        // rename_metadata_object and delete_metadata_object return a cacheable PREVIEW unless
        // confirm=true. Keying a preview would let a later confirm=true reuse the cached preview
        // and never mutate - the same hazard the dryRun bypass closes. Key them only when the
        // call actually mutates (confirm=true).
        if (("rename_metadata_object".equals(toolName) //$NON-NLS-1$
            || "delete_metadata_object".equals(toolName)) //$NON-NLS-1$
            && !isTrue(params, "confirm")) //$NON-NLS-1$
        {
            return false;
        }
        return true;
    }

    /**
     * The store key for a call: an injective, length-prefixed encoding of the tool
     * name and the client key, so distinct (tool, id) pairs never collide.
     *
     * @param toolName the tool wire name
     * @param operationId the client-supplied key (non-blank when this is called)
     * @return the composite store key
     */
    public static String key(String toolName, String operationId)
    {
        return toolName.length() + ":" + toolName + ":" + operationId; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isTrue(Map<String, String> params, String name)
    {
        if (params == null)
        {
            return false;
        }
        String v = params.get(name);
        return v != null && "true".equalsIgnoreCase(v.trim()); //$NON-NLS-1$
    }
}
