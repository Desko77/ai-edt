/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code typeApplication} tag, and the one question that goes with it: when a caller
 * asked for a type and did not get it, did the operation succeed?
 * <p>
 * Six operations create something that carries a type - an object attribute, a tabular
 * section attribute, a form attribute, a form parameter, a register field, an external
 * data source field. Each built this tag itself and each answered the question its own
 * way, which is to say none of them answered it: all six reported {@code success:true}
 * while leaving behind an element with no type on it. A typeless attribute is not a
 * lesser success, it is metadata the platform rejects on the next database update, and
 * the caller who read the top-level flag had no reason to look further.
 * </p>
 * <p>
 * Two neighbours already answered it the other way - {@code set_object_type} throws and
 * {@code set_defined_type_types} returns an error with a {@code partialMutation} tag - so
 * the inconsistency was inside one file, not across a boundary. The rule lives here now.
 * </p>
 */
public final class TypeApplication
{
    private TypeApplication()
    {
        // static helpers only
    }

    /**
     * Builds the {@code typeApplication} tag.
     *
     * @param requested the type as the caller spelled it (non-null - the tag exists only
     *            because a type was asked for)
     * @param applied whether the type reached the created element
     * @param resolved type names that resolved (nullable / empty is omitted)
     * @param unresolved type names that did not (nullable / empty is omitted)
     * @param error the resolution error, when there was one (nullable)
     * @return an insertion-ordered map ready to be attached to a response
     */
    public static Map<String, Object> tag(String requested, boolean applied,
        List<String> resolved, List<String> unresolved, String error)
    {
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("requested", requested); //$NON-NLS-1$
        tag.put("applied", Boolean.valueOf(applied)); //$NON-NLS-1$
        if (resolved != null && !resolved.isEmpty())
        {
            tag.put("resolved", resolved); //$NON-NLS-1$
        }
        if (unresolved != null && !unresolved.isEmpty())
        {
            tag.put("unresolved", unresolved); //$NON-NLS-1$
        }
        if (error != null)
        {
            tag.put("error", error); //$NON-NLS-1$
        }
        return tag;
    }

    /**
     * The verdict: a requested type that did not fully land makes the operation a failure.
     * <p>
     * Callers only reach this on the creation path - an idempotent skip reports what the
     * existing element is typed as and never builds the tag - so {@code applied == false}
     * here always means "created, and not typed as asked".
     * </p>
     * <p>
     * A COMPOSITE type can half-land, and that is why {@code applied} alone will not do.
     * Asked for {@code String,CatalogRef.Missing}, the type machinery writes the part it
     * resolved, reports success for the write, and lists the rest as unresolved. The
     * element then carries a type narrower than the one asked for - which is a different
     * defect from carrying none, and just as invisible if the verdict only reads the flag.
     * </p>
     *
     * @param applied whether the type reached the created element
     * @param unresolved the parts that did not resolve (nullable)
     * @return {@code true} when the response must not read as a success
     */
    public static boolean failed(boolean applied, List<String> unresolved)
    {
        return !applied || (unresolved != null && !unresolved.isEmpty());
    }

    /**
     * The message for a failed application, naming what was left behind so the caller can
     * repair it rather than guess.
     * <p>
     * A preview leaves nothing behind - its transaction rolls back - so it must not be
     * told to go and remove anything. Sending a caller after an element that was never
     * written is at best a wasted call and at worst a destructive one against something
     * else of the same name.
     * </p>
     *
     * @param created what the operation was making, e.g. {@code "attribute 'Сумма'"}
     * @param requested the type as the caller spelled it
     * @param error the resolution error, when there was one (nullable)
     * @param dryRun whether this was a preview, whose transaction rolled back
     * @param applied whether the write itself reported success - pass the same flag given
     *            to {@link #failed}. Reaching this method with it set means a COMPOSITE
     *            type half-landed: the resolved part was written and the rest was not, so
     *            the element is narrower than asked for rather than typeless.
     * @return a message in the terms the calling agent can act on
     */
    public static String failureMessage(String created, String requested, String error,
        boolean dryRun, boolean applied)
    {
        StringBuilder message = new StringBuilder();
        message.append(created);
        if (applied)
        {
            // Narrower than asked for, not typeless: saying "carries no type" here would
            // send the caller looking for a defect that is not the one in front of them.
            message.append(dryRun ? " would carry only part of type '" //$NON-NLS-1$
                : " was created carrying only part of type '"); //$NON-NLS-1$
            message.append(requested).append("'"); //$NON-NLS-1$
        }
        else
        {
            message.append(dryRun ? " would carry no type: '" : " was created but type '"); //$NON-NLS-1$ //$NON-NLS-2$
            message.append(requested);
            message.append(dryRun ? "' could not be applied" //$NON-NLS-1$
                : "' was not applied, so it carries no type"); //$NON-NLS-1$
        }
        if (error != null)
        {
            message.append(": ").append(error); //$NON-NLS-1$
        }
        message.append(dryRun
            ? ". This was a preview and nothing was written - fix the type name and run it again." //$NON-NLS-1$
            : applied
                ? ". Fix the unresolved part and set the type again, or remove what was created." //$NON-NLS-1$
                : ". A typeless element fails validation and database update - " //$NON-NLS-1$
                    + "fix the type name, or remove what was created."); //$NON-NLS-1$
        return message.toString();
    }
}
