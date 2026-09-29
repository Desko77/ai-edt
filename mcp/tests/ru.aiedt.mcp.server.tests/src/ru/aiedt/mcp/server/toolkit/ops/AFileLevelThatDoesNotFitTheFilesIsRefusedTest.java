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
 * A level has to fit the two files it is given, or the call is refused.
 * <p>
 * Two single files are already as narrow as the comparison gets, and the level says what kind of
 * file the comparison is about. A metadata file named with {@code level=module} was read, compared
 * byte for byte and answered as a module that had not changed - a statement about a .bsl file no
 * side ever opened. The refusal is the honest answer: the level does not describe these files.
 * </p>
 */
public class AFileLevelThatDoesNotFitTheFilesIsRefusedTest
{
    private Path first;

    private Path second;

    @Before
    public void twoOfEveryKindOfFile() throws Exception
    {
        first = Files.createTempDirectory("aiedt-compare-level-a"); //$NON-NLS-1$
        second = Files.createTempDirectory("aiedt-compare-level-b"); //$NON-NLS-1$
        for (Path side : new Path[]{ first, second })
        {
            write(side, "Goods.mdo", "<mdo/>"); //$NON-NLS-1$ //$NON-NLS-2$
            write(side, "Module.bsl", "Процедура А() КонецПроцедуры"); //$NON-NLS-1$ //$NON-NLS-2$
            write(side, "Print.mxl", "template"); //$NON-NLS-1$ //$NON-NLS-2$
        }
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

    private static void write(Path export, String name, String content) throws Exception
    {
        Files.write(export.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private String compare(String firstFile, String secondFile, String level)
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "files"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", first.resolve(firstFile).toString()); //$NON-NLS-1$
        params.put("target", second.resolve(secondFile).toString()); //$NON-NLS-1$
        params.put("level", level); //$NON-NLS-1$
        return new CompareConfigurationsTool().execute(params);
    }

    /** level=module on two metadata files is refused, and the level is named. */
    @Test
    public void moduleLevelOnMetadataFilesIsRefused()
    {
        String answer = compare("Goods.mdo", "Goods.mdo", "module"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("the refusal has to name the level: " + answer, //$NON-NLS-1$
            answer.contains("compares module files")); //$NON-NLS-1$
        assertTrue("and the file that does not fit it: " + answer, //$NON-NLS-1$
            answer.contains("Goods.mdo")); //$NON-NLS-1$
        assertFalse("and not answer with a comparison: " + answer, //$NON-NLS-1$
            answer.contains("\"identical\"")); //$NON-NLS-1$
    }

    /** level=template on two module files is refused the same way. */
    @Test
    public void templateLevelOnModuleFilesIsRefused()
    {
        String answer = compare("Module.bsl", "Module.bsl", "template"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("the refusal has to name the level: " + answer, //$NON-NLS-1$
            answer.contains("compares template files")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("\"identical\"")); //$NON-NLS-1$
    }

    /** One side of the level is enough: the level is applied to both files. */
    @Test
    public void oneSideOfTheWrongKindIsRefusedToo()
    {
        String answer = compare("Module.bsl", "Goods.mdo", "module"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("a level that fits one side and not the other is refused: " + answer, //$NON-NLS-1$
            answer.contains("compares module files")); //$NON-NLS-1$
    }

    /** Two module files under level=module are compared, as they always were. */
    @Test
    public void moduleLevelOnTwoModulesCompares()
    {
        String answer = compare("Module.bsl", "Module.bsl", "module"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("the level fits, so the comparison runs: " + answer, //$NON-NLS-1$
            answer.contains("\"identical\":true")); //$NON-NLS-1$
    }

    /** Two template files under level=template are compared. */
    @Test
    public void templateLevelOnTwoTemplatesCompares()
    {
        String answer = compare("Print.mxl", "Print.mxl", "template"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("the level fits, so the comparison runs: " + answer, //$NON-NLS-1$
            answer.contains("\"identical\":true")); //$NON-NLS-1$
    }

    /** Without a level the two files are compared, whatever kind they are. */
    @Test
    public void withoutALevelAnyTwoFilesAreCompared()
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "files"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", first.resolve("Goods.mdo").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("target", second.resolve("Goods.mdo").toString()); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new CompareConfigurationsTool().execute(params);

        assertTrue("the control: nothing is refused when no level is named: " + answer, //$NON-NLS-1$
            answer.contains("\"identical\":true")); //$NON-NLS-1$
    }
}
