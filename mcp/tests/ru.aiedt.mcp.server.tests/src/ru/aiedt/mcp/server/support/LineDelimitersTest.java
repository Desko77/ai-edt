/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.junit.Test;

/**
 * Covers the rewrite half of {@link LineDelimiters}, which is where the bug this class was written
 * for actually lived: a module read in, normalised to LF for editing, and then written back still
 * normalised, converting it while its twenty-odd thousand neighbours stayed CRLF.
 * <p>
 * Choosing the delimiter needs a workspace file and is exercised through the writers instead. What
 * is pinned here is that the conversion itself is total - every form in, one form out - because a
 * conversion that misses a case produces a file with mixed endings, which is worse than the
 * uniformly wrong file it replaced.
 * </p>
 */
public class LineDelimitersTest
{
    @Test
    public void lfBecomesTheRequestedEnding()
    {
        assertEquals("a\r\nb\r\nc", LineDelimiters.rewrite("a\nb\nc", LineDelimiters.CRLF)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void crlfBecomesTheRequestedEnding()
    {
        assertEquals("a\nb\nc", LineDelimiters.rewrite("a\r\nb\r\nc", LineDelimiters.LF)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aLoneCarriageReturnCountsAsALineBreakToo()
    {
        // Old Mac endings are not something the plugin writes, but content assembled from an
        // outside source can carry them, and leaving them alone would strand a line.
        assertEquals("a\r\nb", LineDelimiters.rewrite("a\rb", LineDelimiters.CRLF)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a\nb", LineDelimiters.rewrite("a\rb", LineDelimiters.LF)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aMixtureIsSettledOnOneEnding()
    {
        // The case the service-module writers produce: an LF stub appended to a CRLF module. Every
        // break has to end up the same, or the file is left in a state neither form describes.
        String mixed = "crlf\r\nlf\ncr\rend"; //$NON-NLS-1$
        assertEquals("crlf\r\nlf\r\ncr\r\nend", LineDelimiters.rewrite(mixed, LineDelimiters.CRLF)); //$NON-NLS-1$
        assertEquals("crlf\nlf\ncr\nend", LineDelimiters.rewrite(mixed, LineDelimiters.LF)); //$NON-NLS-1$
    }

    @Test
    public void convertingToTheEndingAlreadyThereChangesNothing()
    {
        String crlf = "Процедура Тест()\r\n\tВозврат;\r\nКонецПроцедуры\r\n"; //$NON-NLS-1$
        assertEquals(crlf, LineDelimiters.rewrite(crlf, LineDelimiters.CRLF));
        String lf = "Процедура Тест()\n\tВозврат;\nКонецПроцедуры\n"; //$NON-NLS-1$
        assertEquals(lf, LineDelimiters.rewrite(lf, LineDelimiters.LF));
    }

    @Test
    public void rewritingTwiceGivesTheSameAnswerAsRewritingOnce()
    {
        // Several writers rewrite content that a caller may already have rewritten. If that were
        // not idempotent, a second pass would double every break.
        String once = LineDelimiters.rewrite("a\nb\r\nc", LineDelimiters.CRLF); //$NON-NLS-1$
        assertEquals(once, LineDelimiters.rewrite(once, LineDelimiters.CRLF));
    }

    @Test
    public void aTrailingBreakSurvives()
    {
        // The writers rely on this: they guarantee the file ends with a break, and the conversion
        // must not eat it.
        assertEquals("a\r\n", LineDelimiters.rewrite("a\n", LineDelimiters.CRLF)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a\n", LineDelimiters.rewrite("a\r\n", LineDelimiters.LF)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void textWithNoBreakAtAllIsReturnedUntouched()
    {
        String single = "Возврат Истина;"; //$NON-NLS-1$
        assertEquals(single, LineDelimiters.rewrite(single, LineDelimiters.CRLF));
    }

    @Test
    public void nothingInGivesNothingOutRatherThanAFailure()
    {
        assertNull(LineDelimiters.rewrite(null, LineDelimiters.CRLF));
        assertEquals("", LineDelimiters.rewrite("", LineDelimiters.CRLF)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anExistingFileDecidesByWhatIsAlreadyInIt() throws Exception
    {
        // The half the bug actually lived in. rewrite() being correct proves nothing if the
        // delimiter handed to it is picked from a preference nobody set, so read it off a real
        // file - CRLF, LF, and the awkward shapes in between.
        IProject project = scratchProject();
        try
        {
            assertEquals(LineDelimiters.CRLF,
                LineDelimiters.of(fileWith(project, "crlf.bsl", "a\r\nb\r\n"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(LineDelimiters.LF,
                LineDelimiters.of(fileWith(project, "lf.bsl", "a\nb\n"))); //$NON-NLS-1$ //$NON-NLS-2$
            // First break wins: this one is CRLF even though LF is in the majority.
            assertEquals(LineDelimiters.CRLF,
                LineDelimiters.of(fileWith(project, "mixed.bsl", "a\r\nb\nc\n"))); //$NON-NLS-1$ //$NON-NLS-2$
            // A BOM precedes the content and must not be mistaken for the answer.
            assertEquals(LineDelimiters.CRLF,
                LineDelimiters.of(fileWith(project, "bom.bsl", "﻿a\r\nb"))); //$NON-NLS-1$ //$NON-NLS-2$
            // Nothing to copy from, so this falls through to the preference rather than failing.
            assertNotNull(LineDelimiters.of(fileWith(project, "oneline.bsl", "Возврат;"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull(LineDelimiters.of(fileWith(project, "empty.bsl", ""))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            project.delete(true, true, null);
        }
    }

    private static IProject scratchProject() throws Exception
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot()
            .getProject("aiedt-line-delimiters-test"); //$NON-NLS-1$
        if (project.exists())
        {
            project.delete(true, true, null);
        }
        project.create(null);
        project.open(null);
        return project;
    }

    private static IFile fileWith(IProject project, String name, String content) throws Exception
    {
        IFile file = project.getFile(name);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (file.exists())
        {
            file.setContents(new ByteArrayInputStream(bytes), true, false, null);
        }
        else
        {
            file.create(new ByteArrayInputStream(bytes), true, null);
        }
        return file;
    }

    @Test
    public void aMissingFileStillYieldsAnEndingToWriteWith()
    {
        // of(null) is the new-file path with nothing to copy from. It has to answer, because the
        // caller is about to join lines with whatever it returns.
        String delimiter = LineDelimiters.of(null);
        assertNotNull("a writer cannot proceed without a delimiter", delimiter); //$NON-NLS-1$
        assertEquals("the fallback should be a real line ending", //$NON-NLS-1$
            true, LineDelimiters.CRLF.equals(delimiter) || LineDelimiters.LF.equals(delimiter));
    }
}
