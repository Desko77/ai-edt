/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The answer to a comparison is bounded, and says when the bound cut it.
 * <p>
 * An export of a large configuration differs in tens of thousands of files. Every one of them named
 * is bytes the client pays for before it reads the counts it asked for, so the lists stop at a cap -
 * and the counts keep saying what the comparison found, with {@code truncated} saying the lists are
 * shorter than them. A cut list that did not say so would read as a comparison that covered exactly
 * that much.
 * </p>
 */
public class ABoundedAnswerSaysItWasCutTest
{
    /** More files than the answer may name, so the cut is certain whatever the cap is set to. */
    private static final int TOO_MANY = 2100;

    private Path first;

    private Path second;

    @Before
    public void twoExports() throws Exception
    {
        first = Files.createTempDirectory("aiedt-compare-bound-a"); //$NON-NLS-1$
        second = Files.createTempDirectory("aiedt-compare-bound-b"); //$NON-NLS-1$
    }

    @After
    public void removeThem() throws Exception
    {
        for (Path top : new Path[]{ first, second })
        {
            try (Stream<Path> entries = Files.walk(top))
            {
                entries.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void write(Path export, String relative, byte[] content) throws Exception
    {
        Path target = export.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    private String compare()
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "files"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", first.toString()); //$NON-NLS-1$
        params.put("target", second.toString()); //$NON-NLS-1$
        return new CompareConfigurationsTool().execute(params);
    }

    /** A cut list of added files keeps the true count and says the list was cut. */
    @Test
    public void aCutListKeepsTheCountAndSaysSo() throws Exception
    {
        for (int i = 0; i < TOO_MANY; i++)
        {
            // Into the target export, so the files are the ones the comparison finds added.
            write(second, String.format("Catalogs/Goods/%04d.mdo", Integer.valueOf(i)), //$NON-NLS-1$
                "<mdo/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        }

        String answer = compare();

        assertTrue("the count is of what was found, not of what was listed: " + answer, //$NON-NLS-1$
            answer.contains("\"addedCount\":" + TOO_MANY)); //$NON-NLS-1$
        assertTrue("a list that was cut has to say so: " + answer, //$NON-NLS-1$
            answer.contains("\"truncated\":true")); //$NON-NLS-1$
        assertFalse("the files past the cap are not named: " + answer, //$NON-NLS-1$
            answer.contains("2099.mdo")); //$NON-NLS-1$
    }

    /** The control: a comparison under the cap names everything and says nothing was cut. */
    @Test
    public void aWholeListIsNotMarkedAsCut() throws Exception
    {
        write(second, "Catalogs/Goods/Goods.mdo", "<mdo/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = compare();

        assertTrue(answer, answer.contains("\"addedCount\":1")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("\"truncated\":false")); //$NON-NLS-1$
    }

    /** A line preview is not built from files larger than the cap, and the answer says so. */
    @Test
    public void aPreviewIsLeftOutAboveTheCapAndNamed() throws Exception
    {
        byte[] line = "Процедура А() КонецПроцедуры\n".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        byte[] big = new byte[5 * 1024 * 1024];
        for (int at = 0; at + line.length <= big.length; at += line.length)
        {
            System.arraycopy(line, 0, big, at, line.length);
        }
        byte[] other = Arrays.copyOf(big, big.length);
        other[0] = 'X';
        write(first, "CommonModules/Util/Module.bsl", big); //$NON-NLS-1$
        write(second, "CommonModules/Util/Module.bsl", other); //$NON-NLS-1$

        Map<String, String> params = new HashMap<>();
        params.put("mode", "files"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", first.resolve("CommonModules/Util/Module.bsl").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("target", second.resolve("CommonModules/Util/Module.bsl").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new CompareConfigurationsTool().execute(params);

        assertTrue("the two differ and the answer has to say that: " + answer, //$NON-NLS-1$
            answer.contains("\"identical\":false")); //$NON-NLS-1$
        assertTrue("the preview is left out, and the answer names the reason: " + answer, //$NON-NLS-1$
            answer.contains("previewOmitted")); //$NON-NLS-1$
        assertFalse("no preview is built from a file that size: " + answer, //$NON-NLS-1$
            answer.contains("\"preview\"")); //$NON-NLS-1$
    }

    /** Two files larger than one read block are still compared as equal when they are. */
    @Test
    public void filesLargerThanABlockAreComparedByContent() throws Exception
    {
        // Whole blocks, so the comparison reaches the point where both streams return nothing:
        // a comparison that read the block rather than the bytes read would call these different.
        byte[] content = new byte[128 * 1024];
        java.util.Arrays.fill(content, (byte)0x41);
        write(first, "CommonModules/Util/Module.bsl", content); //$NON-NLS-1$
        write(second, "CommonModules/Util/Module.bsl", content); //$NON-NLS-1$

        Map<String, String> params = new HashMap<>();
        params.put("mode", "files"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", first.resolve("CommonModules/Util/Module.bsl").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("target", second.resolve("CommonModules/Util/Module.bsl").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new CompareConfigurationsTool().execute(params);

        assertTrue("two files of the same bytes are the same file: " + answer, //$NON-NLS-1$
            answer.contains("\"identical\":true")); //$NON-NLS-1$
    }

    /** Two files of different length differ without either being read. */
    @Test
    public void filesOfDifferentLengthDifferWithoutARead() throws Exception
    {
        write(first, "Catalogs/Goods/Goods.mdo", "<mdo version='one'/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
        write(second, "Catalogs/Goods/Goods.mdo", "<mdo version='one'/> ".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        // The two files themselves: the answer naming the sizes is the two-file one.
        Map<String, String> params = new HashMap<>();
        params.put("mode", "files"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", first.resolve("Catalogs/Goods/Goods.mdo").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("target", second.resolve("Catalogs/Goods/Goods.mdo").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new CompareConfigurationsTool().execute(params);

        assertTrue(answer, answer.contains("\"identical\":false")); //$NON-NLS-1$
        assertTrue("the sizes are named so a caller can see which grew: " + answer, //$NON-NLS-1$
            answer.contains("\"firstSize\":20") && answer.contains("\"secondSize\":21")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
