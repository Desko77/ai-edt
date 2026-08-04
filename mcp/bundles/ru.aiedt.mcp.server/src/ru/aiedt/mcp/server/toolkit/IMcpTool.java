/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A capability the server offers to a connected agent.
 * <p>
 * An implementation is a plain object with a no-argument constructor: the server creates one of each
 * at startup and keeps it for the lifetime of the workbench. The same instance answers calls from
 * several threads at once, so an implementation must keep no per-call state.
 * </p>
 * <p>
 * Arguments arrive already flattened to strings by the protocol layer. A whole number reaches
 * {@link #execute(Map)} as {@code "10"} and a fractional one as {@code "10.5"}; an argument
 * explicitly sent as JSON {@code null} does not arrive at all. Read arguments through the extractors
 * in {@code JsonUtils}, which are written for exactly this: they parse through {@code double}, so
 * they also accept the {@code "10.0"} that older builds produced, and they treat a missing key as
 * "not supplied".
 * </p>
 */
public interface IMcpTool
{
    /** The kind of content a tool produces, which decides the shape of its MCP result. */
    enum ResponseType
    {
        /** A plain text block. */
        TEXT,
        /** A JSON document, delivered as structured content. */
        JSON,
        /** Markdown, delivered as an embedded text resource. */
        MARKDOWN,
        /** A base64 PNG, delivered as an embedded binary resource. */
        IMAGE
    }

    /**
     * Returns the name the agent calls this tool by, in snake_case, for example
     * {@code get_edt_version}. It is also the primary name the tool is registered under, so it must be
     * unique across the registry.
     *
     * @return the tool name, never <code>null</code>
     */
    String getName();

    /**
     * Returns the stable identity presets, the call gate and the preference store key on.
     * <p>
     * The capability id is frozen for the life of the tool. It is the value the disabled and unlisted
     * sets carry, the value {@link ru.aiedt.mcp.server.settings.ToolCategory} lists a tool under, and
     * the value a preset's disabled set names - so renaming the wire name a client calls this tool by
     * does not change whether a preset that switched this capability off still blocks it. Without this
     * seam a rename would silently re-enable a tool a cautious preset had disabled, because the preset's
     * literal would still name the old wire name while the gate judged the new one.
     * </p>
     * <p>
     * The default is {@link #getName()}, which is the right answer for every tool whose wire name has
     * not changed: the capability id and the wire name are the same string. Override this to return the
     * <em>frozen</em> name and override {@link #getName()} to return the new wire name when renaming a
     * tool; list the old wire name in {@link #getAliases()} so callers that still use it keep working.
     * </p>
     *
     * @return the stable capability id, never <code>null</code> or empty for a registered tool
     */
    default String getCapabilityId()
    {
        return getName();
    }

    /**
     * Returns the other names a client may call this tool by, beyond {@link #getName()}.
     * <p>
     * An alias is callable but not advertised: {@code tools/list} shows only the primary wire name,
     * while {@code tools/call} accepts an alias and resolves it to the same tool under the same
     * capability id, so an old wire name keeps working - and stays gated the same way - after a rename.
     * The advertised-surface cap some clients enforce is the reason aliases are unlisted: every
     * advertised name costs a slot, a callable alias costs none.
     * </p>
     * <p>
     * The default is empty, which is correct for a tool that has not been renamed. An alias must not
     * collide with another tool's wire name or alias; the registry logs a clash and the first binding
     * wins.
     * </p>
     *
     * @return the alias names, never <code>null</code>; empty when the tool has only one callable name
     */
    default List<String> getAliases()
    {
        return Collections.emptyList();
    }

    /**
     * Returns what the tool does, in the words the agent will read when it decides whether to call
     * it. Surfaced both in the tool catalogue and in the plugin preference pages.
     *
     * @return the description, never <code>null</code>
     */
    String getDescription();

    /**
     * Returns the JSON Schema of this tool's arguments, as a JSON <em>string</em>.
     * <p>
     * The protocol layer parses it and embeds the resulting tree in the catalogue, so a schema that
     * does not parse takes the whole catalogue down with it. Build it with {@code SchemaComposer}
     * unless the schema needs something that builder cannot express.
     * </p>
     *
     * @return the input schema as a JSON string, never <code>null</code>
     */
    String getInputSchema();

    /**
     * Runs the tool.
     * <p>
     * The result is interpreted according to {@link #getResponseType()}. Declaring no checked
     * exception is deliberate: a tool reports failure in the document it returns. An unchecked
     * exception that escapes is caught by the protocol layer and reported to the agent as an
     * internal error, which is a worse answer than a tool result carrying the reason.
     * </p>
     *
     * @param params the arguments, flattened to strings; never <code>null</code>, possibly empty
     * @return the result document; <code>null</code> is read as the empty string, not as a failure
     */
    String execute(Map<String, String> params);

    /**
     * Returns the kind of content {@link #execute(Map)} produces. Override this: the default suits
     * a tool that writes a report for a human to read, and most tools answer with JSON.
     *
     * @return the response type, never <code>null</code>
     */
    default ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    /**
     * Names the resource a {@link ResponseType#MARKDOWN} or {@link ResponseType#IMAGE} result is
     * delivered as. Override it to derive a name from the arguments - a client shows this name, and
     * {@code source-CommonModule-Foo.md} tells the reader more than {@code read_module_source.md}.
     *
     * @param params the arguments the tool ran with
     * @return the file name, never <code>null</code>
     */
    default String getResultFileName(Map<String, String> params)
    {
        return getName() + ".md"; //$NON-NLS-1$
    }
}
