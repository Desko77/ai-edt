/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * An export names the files it wrote over.
 * <p>
 * {@code resync_to_disk} writes the model over the object's directory. A change made there by hand
 * is replaced, and the answer used to say nothing about it, so the edit disappeared without the
 * caller learning it had been there.
 * </p>
 * <p>
 * Content, not timestamps: an export rewrites every file it owns, so a comparison by modification
 * time would name all of them every run and mean nothing.
 * </p>
 */
public class AnExportNamesWhatItWroteOverTest
{
    private Path root;
    private Path directory;

    @Before
    public void makeADirectory() throws Exception
    {
        root = Files.createTempDirectory("aiedt-export-test"); //$NON-NLS-1$
        directory = Files.createDirectories(root.resolve("src").resolve("Catalogs").resolve("Goods")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        write("Goods.mdo", "<mdo/>"); //$NON-NLS-1$ //$NON-NLS-2$
        write("Template.dcs", "<schema/>"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @After
    public void removeIt() throws Exception
    {
        try (Stream<Path> entries = Files.walk(root))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private void write(String name, String content) throws Exception
    {
        Files.write(directory.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, String> reading()
    {
        return ExportedFiles.underDirectories(root, Collections.singletonList(directory));
    }

    @Test
    public void aDirectoryThatDidNotChangeNamesNothing() throws Exception
    {
        Map<String, String> before = reading();

        ExportedFiles.Changes changes = ExportedFiles.between(before, reading());

        assertTrue("nothing moved, so nothing is named", !changes.any()); //$NON-NLS-1$
    }

    @Test
    public void aFileWrittenOverIsNamed() throws Exception
    {
        Map<String, String> before = reading();

        write("Template.dcs", "<schema>edited by hand</schema>"); //$NON-NLS-1$ //$NON-NLS-2$
        ExportedFiles.Changes changes = ExportedFiles.between(before, reading());

        assertEquals("the file whose content differs is the one named", //$NON-NLS-1$
            1, changes.written().size());
        assertTrue("named by its path under the project: " + changes.written(), //$NON-NLS-1$
            changes.written().get(0).endsWith("Catalogs/Goods/Template.dcs")); //$NON-NLS-1$
        assertTrue("and nothing was created", changes.created().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void rewritingTheSameContentIsNotAChange() throws Exception
    {
        Map<String, String> before = reading();

        Files.delete(directory.resolve("Template.dcs")); //$NON-NLS-1$
        write("Template.dcs", "<schema/>"); //$NON-NLS-1$ //$NON-NLS-2$
        ExportedFiles.Changes changes = ExportedFiles.between(before, reading());

        assertTrue("an export rewrites every file it owns; only different content counts", //$NON-NLS-1$
            !changes.any());
    }

    @Test
    public void aFileThatAppearedAndOneThatWentAreTold() throws Exception
    {
        Map<String, String> before = reading();

        write("Form.form", "<form/>"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.delete(directory.resolve("Goods.mdo")); //$NON-NLS-1$
        ExportedFiles.Changes changes = ExportedFiles.between(before, reading());

        assertEquals("the new file is named as created", 1, changes.created().size()); //$NON-NLS-1$
        assertTrue(changes.created().get(0).endsWith("Form.form")); //$NON-NLS-1$
        assertEquals("the one that went is named as removed", 1, changes.removed().size()); //$NON-NLS-1$
        assertTrue(changes.removed().get(0).endsWith("Goods.mdo")); //$NON-NLS-1$
    }

    @Test
    public void anExportIntoAnEmptyPlaceStillNamesWhatItPut() throws Exception
    {
        Map<String, String> before = ExportedFiles.underDirectories(root,
            Collections.singletonList(root.resolve("src").resolve("Catalogs").resolve("Absent"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        ExportedFiles.Changes changes = ExportedFiles.between(before, reading());

        assertEquals("with nothing there before, every file is one the export put there", //$NON-NLS-1$
            2, changes.created().size());
        assertTrue("and none of them counts as written over", changes.written().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void aDirectoryThatIsNotThereIsSkippedRatherThanFailing()
    {
        Map<String, String> reading = ExportedFiles.underDirectories(root,
            Collections.singletonList(root.resolve("src").resolve("Catalogs").resolve("Absent"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("an object with nothing on disk yet contributes nothing", reading.isEmpty()); //$NON-NLS-1$
    }
}
