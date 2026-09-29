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
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The files mode takes an export directory, as its description says it does.
 * <p>
 * A real export arrives as a directory of files, and reading the path as a single file answered
 * with a read error for exactly that call. The narrowing arguments are part of the same honesty:
 * each one either acts on the comparison or is refused by name, never accepted and dropped.
 * </p>
 * <p>
 * These calls also hold the threading contract: nothing in the files mode touches the UI thread,
 * and this runtime runs no event loop for one to run on.
 * </p>
 */
public class AFilesModeTakesAnExportDirectoryTest
{
    private Path first;

    private Path second;

    @Before
    public void makeTwoExports() throws Exception
    {
        first = Files.createTempDirectory("aiedt-compare-files-a"); //$NON-NLS-1$
        second = Files.createTempDirectory("aiedt-compare-files-b"); //$NON-NLS-1$
        // A rewritten module, a rewritten object file, one that only the second export carries,
        // and a template that only the level filters in and out.
        write(first, "CommonModules/Util/Module.bsl", "Процедура А() КонецПроцедуры"); //$NON-NLS-1$ //$NON-NLS-2$
        write(second, "CommonModules/Util/Module.bsl", "Процедура А()\n\tВозврат; // правка\nКонецПроцедуры"); //$NON-NLS-1$ //$NON-NLS-2$
        write(first, "Catalogs/Goods/Goods.mdo", "<mdo version='one'/>"); //$NON-NLS-1$ //$NON-NLS-2$
        write(second, "Catalogs/Goods/Goods.mdo", "<mdo version='two'/>"); //$NON-NLS-1$ //$NON-NLS-2$
        write(second, "Documents/Order/Order.mdo", "<mdo/>"); //$NON-NLS-1$ //$NON-NLS-2$
        write(first, "Catalogs/Goods/Templates/Print.mxl", "old"); //$NON-NLS-1$ //$NON-NLS-2$
        write(second, "Catalogs/Goods/Templates/Print.mxl", "new"); //$NON-NLS-1$ //$NON-NLS-2$
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

    private static void write(Path export, String relative, String content) throws Exception
    {
        Path target = export.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private String compare(Map<String, String> arguments)
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "files"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", first.toString()); //$NON-NLS-1$
        params.put("target", second.toString()); //$NON-NLS-1$
        params.putAll(arguments);
        return new CompareConfigurationsTool().execute(params);
    }

    /** Two export directories are compared file by file. */
    @Test
    public void twoExportDirectoriesAreComparedFileByFile()
    {
        String answer = compare(Map.of());

        assertTrue(answer, answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Documents/Order/Order.mdo")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Catalogs/Goods/Goods.mdo")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Catalogs/Goods/Templates/Print.mxl")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("\"failedCount\":0")); //$NON-NLS-1$
    }

    /** level=module keeps the module files and drops everything else from the walk. */
    @Test
    public void moduleLevelKeepsOnlyModuleFiles()
    {
        String answer = compare(Map.of("level", "module")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("CommonModules/Util/Module.bsl")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("Catalogs/Goods/Goods.mdo")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("Print.mxl")); //$NON-NLS-1$
    }

    /** level=template keeps the template files and drops the modules and metadata. */
    @Test
    public void templateLevelKeepsOnlyTemplateFiles()
    {
        String answer = compare(Map.of("level", "template")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("Print.mxl")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("Module.bsl")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("Goods.mdo")); //$NON-NLS-1$
    }

    /** A file against a directory is refused by name, not read as a failed file. */
    @Test
    public void aFileAgainstADirectoryIsRefused()
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "files"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", first.resolve("CommonModules/Util/Module.bsl").toString()); //$NON-NLS-1$
        params.put("target", second.toString()); //$NON-NLS-1$
        String answer = new CompareConfigurationsTool().execute(params);

        assertTrue(answer, answer.contains("one of these is a file and the other is a directory")); //$NON-NLS-1$
    }

    /** level=attribute needs the metadata model and is refused in the files mode. */
    @Test
    public void attributeLevelIsRefusedInFilesMode()
    {
        String answer = compare(Map.of("level", "attribute")); //$NON-NLS-1$ //$NON-NLS-2$

        // The JSON escapes the '=' of the refusal, so the assertion keeps to words that survive.
        assertTrue(answer, answer.contains("needs the metadata model")); //$NON-NLS-1$
    }

    /** A scope word this comparison does not take is refused, not accepted and ignored. */
    @Test
    public void aScopeWordThisComparisonDoesNotTakeIsRefused()
    {
        String answer = compare(Map.of("scope", "objectType")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("Invalid scope")); //$NON-NLS-1$
    }

    /** scope=objectFqn with objectFqn narrows the walk to that object's directory. */
    @Test
    public void objectFqnNarrowsTheWalkToThatObject()
    {
        String answer = compare(Map.of("scope", "objectFqn", "objectFqn", "Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue(answer, answer.contains("Catalogs/Goods/Goods.mdo")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("\"narrowedTo\":\"Catalog.Goods\"")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("CommonModules/Util/Module.bsl")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("Documents/Order/Order.mdo")); //$NON-NLS-1$
    }

    /** scope=objectFqn without the object it names is refused. */
    @Test
    public void objectFqnScopeWithoutAnObjectIsRefused()
    {
        String answer = compare(Map.of("scope", "objectFqn")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("requires objectFqn")); //$NON-NLS-1$
    }

    /** Two single files keep the byte comparison they always had. */
    @Test
    public void twoFilesKeepTheirByteComparison()
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "files"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", first.resolve("Catalogs/Goods/Goods.mdo").toString()); //$NON-NLS-1$
        params.put("target", second.resolve("Catalogs/Goods/Goods.mdo").toString()); //$NON-NLS-1$
        String answer = new CompareConfigurationsTool().execute(params);

        assertTrue(answer, answer.contains("\"identical\":false")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("preview")); //$NON-NLS-1$
    }
}
