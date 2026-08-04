/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.mdreport;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * The way in to the markdown a metadata object is described by.
 * <p>
 * The name is a leftover and says more than it should. There is no registry: nothing registers, nothing
 * is looked up, and there is no second formatter to choose between - every metadata object of every type
 * is written by the same code, because that code reads the model rather than knowing the types. What is
 * left is this one method, and it is kept exactly as it is because the tools that answer
 * {@code get_metadata_details} and {@code ai_context} call it by name.
 * </p>
 * <p>
 * That makes this class the whole of the package's surface. Everything behind it is package private and
 * free to change; this signature is not.
 * </p>
 */
public final class MetadataFormatterHub
{
    private MetadataFormatterHub()
    {
        // utility
    }

    /**
     * Describes a metadata object in markdown.
     *
     * @param object the object; may be <code>null</code>, which is reported rather than thrown
     * @param full <code>true</code> to dump every property the model holds and widen the attribute
     *            tables, <code>false</code> for the name, the synonym and the comment
     * @param language which language to prefer out of a synonym; may be <code>null</code> or unknown, in
     *            which case whichever synonym the object carries is used
     * @return the markdown, never <code>null</code>. It opens at heading level 2: the callers put it under
     *         a heading of their own, so it must not open one
     */
    public static String format(MdObject object, boolean full, String language)
    {
        return MetadataFormatter.format(object, full, language);
    }
}
