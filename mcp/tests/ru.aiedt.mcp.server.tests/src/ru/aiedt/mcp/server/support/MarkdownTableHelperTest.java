/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins the two escaping rules in {@link MarkdownTableHelper}: one for a table cell, one for free text.
 */
public class MarkdownTableHelperTest
{
    // ---------- escapeForTable ----------

    @Test
    public void tableEscapeNullBecomesEmpty()
    {
        assertEquals("", MarkdownTableHelper.escapeForTable(null));
    }

    @Test
    public void tableEscapeEmptyStaysEmpty()
    {
        assertEquals("", MarkdownTableHelper.escapeForTable(""));
    }

    @Test
    public void tableEscapeLeavesOrdinaryTextAlone()
    {
        assertEquals("Hello world", MarkdownTableHelper.escapeForTable("Hello world"));
    }

    @Test
    public void tableEscapeEscapesOnePipe()
    {
        assertEquals("a \\| b", MarkdownTableHelper.escapeForTable("a | b"));
    }

    @Test
    public void tableEscapeEscapesEveryPipe()
    {
        assertEquals("one \\| two \\| three", MarkdownTableHelper.escapeForTable("one | two | three"));
    }

    @Test
    public void tableEscapeNewlineBecomesSpace()
    {
        assertEquals("line1 line2", MarkdownTableHelper.escapeForTable("line1\nline2"));
    }

    @Test
    public void tableEscapeLoneCarriageReturnDropped()
    {
        assertEquals("text", MarkdownTableHelper.escapeForTable("text\r"));
    }

    @Test
    public void tableEscapeCrlfCollapsesToOneSpace()
    {
        assertEquals("line1 line2", MarkdownTableHelper.escapeForTable("line1\r\nline2"));
    }

    @Test
    public void tableEscapePipeAndNewlineCombined()
    {
        assertEquals("cell \\| inside space", MarkdownTableHelper.escapeForTable("cell | inside\nspace"));
    }

    @Test
    public void tableEscapeLeavesBackslashAlone()
    {
        // Windows paths must survive a table cell undoubled.
        assertEquals("C:\\Projects\\x", MarkdownTableHelper.escapeForTable("C:\\Projects\\x"));
    }

    // ---------- escapeMarkdown ----------

    @Test
    public void markdownEscapeNullBecomesEmpty()
    {
        assertEquals("", MarkdownTableHelper.escapeMarkdown(null));
    }

    @Test
    public void markdownEscapeEmptyStaysEmpty()
    {
        assertEquals("", MarkdownTableHelper.escapeMarkdown(""));
    }

    @Test
    public void markdownEscapeLeavesOrdinaryTextAlone()
    {
        assertEquals("Hello world", MarkdownTableHelper.escapeMarkdown("Hello world"));
    }

    @Test
    public void markdownEscapeDoublesBackslashFirst()
    {
        assertEquals("path\\\\to\\\\file", MarkdownTableHelper.escapeMarkdown("path\\to\\file"));
    }

    @Test
    public void markdownEscapeEscapesAsterisks()
    {
        assertEquals("\\*bold\\*", MarkdownTableHelper.escapeMarkdown("*bold*"));
    }

    @Test
    public void markdownEscapeEscapesUnderscores()
    {
        assertEquals("\\_italic\\_", MarkdownTableHelper.escapeMarkdown("_italic_"));
    }

    @Test
    public void markdownEscapeEscapesBackticks()
    {
        assertEquals("\\`code\\`", MarkdownTableHelper.escapeMarkdown("`code`"));
    }

    @Test
    public void markdownEscapeEscapesSquareBrackets()
    {
        assertEquals("\\[link\\]", MarkdownTableHelper.escapeMarkdown("[link]"));
    }

    @Test
    public void markdownEscapeEscapesAngleBrackets()
    {
        assertEquals("\\<html\\>", MarkdownTableHelper.escapeMarkdown("<html>"));
    }

    @Test
    public void markdownEscapeEscapesAllSpecialsTogether()
    {
        // Backslash is doubled first, then every other special gets its own backslash.
        String input = "\\*_`[]<>";
        String expected = "\\\\\\*\\_\\`\\[\\]\\<\\>";
        assertEquals(expected, MarkdownTableHelper.escapeMarkdown(input));
    }
}
