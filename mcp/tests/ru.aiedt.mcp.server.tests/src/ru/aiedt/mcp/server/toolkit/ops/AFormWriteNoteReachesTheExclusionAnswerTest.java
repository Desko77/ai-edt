/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * The answer of {@code set_excluded_commands} says what the form write reported about the disk.
 */
public class AFormWriteNoteReachesTheExclusionAnswerTest
{
    /** A note the write returned is carried by the answer. */
    @Test
    public void aNoteIsCarried()
    {
        String json = FormExcludedCommandsOps.withWriteNote(ToolResult.success(),
            "diskFlushPending: the form file was not exported").toJson(); //$NON-NLS-1$

        assertTrue(json, json.contains("persistNote")); //$NON-NLS-1$
        assertTrue(json, json.contains("diskFlushPending")); //$NON-NLS-1$
    }

    /** A write with nothing to say adds no key. */
    @Test
    public void noNoteAddsNothing()
    {
        assertFalse(FormExcludedCommandsOps.withWriteNote(ToolResult.success(), null).toJson()
            .contains("persistNote")); //$NON-NLS-1$
        assertFalse(FormExcludedCommandsOps.withWriteNote(ToolResult.success(), "  ").toJson() //$NON-NLS-1$
            .contains("persistNote")); //$NON-NLS-1$
    }
}
