/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.modules.IModuleSource;
import ru.aiedt.mcp.server.support.modules.IModuleSourceProvider;
import ru.aiedt.mcp.server.support.modules.ModuleSources;

/**
 * A whole method written with {@code append}, {@code insertBefore} or {@code insertAfter} is kept
 * one blank line away from the code beside it, without doubling a blank line already there; lines
 * inserted into a body are written as given.
 * <p>
 * The module is held in memory by a provider, so each case reads the exact lines the write left.
 * </p>
 */
public class AWholeMethodInsertIsSeparatedTest
{
    private static final String PROJECT = "AiEdtSeparatorProbe"; //$NON-NLS-1$

    private static final String ADDRESS = "CommonModules/Separator/Module.bsl"; //$NON-NLS-1$

    private static final String HOLDER = "CommonModules/Separator/Module.probe"; //$NON-NLS-1$

    private static final String NEW_METHOD = "Процедура Новая()\n\tСообщить(3);\nКонецПроцедуры"; //$NON-NLS-1$

    /** A provider whose one module lives in memory. */
    private static final class Probe implements IModuleSourceProvider, IModuleSource
    {
        final List<String> lines = new ArrayList<>();

        @Override
        public String kind()
        {
            return "SeparatorProbe"; //$NON-NLS-1$
        }

        @Override
        public IModuleSource locate(IProject candidate, String modulePath)
        {
            String path = modulePath.startsWith("src/") ? modulePath.substring(4) : modulePath; //$NON-NLS-1$
            return candidate == project && ADDRESS.equals(path) ? this : null;
        }

        @Override
        public List<IModuleSource> list(IProject candidate)
        {
            return candidate == project ? List.of(this) : List.of();
        }

        @Override
        public String coverage(Collection<IProject> projects, String what)
        {
            return null;
        }

        @Override
        public String source()
        {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public String modulePath()
        {
            return ADDRESS;
        }

        @Override
        public String containerPath()
        {
            return HOLDER;
        }

        @Override
        public String fqn()
        {
            return "CommonModule.Separator"; //$NON-NLS-1$
        }

        @Override
        public List<String> lines()
        {
            return new ArrayList<>(lines);
        }

        @Override
        public boolean write(List<String> newLines)
        {
            if (newLines.equals(lines))
            {
                return false;
            }
            lines.clear();
            lines.addAll(newLines);
            return true;
        }

        @Override
        public String afterWrite(boolean written)
        {
            return written ? "the probe delivered it" : "nothing changed"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public String validationNote()
        {
            return "not available: the probe validates nothing"; //$NON-NLS-1$
        }
    }

    private static Path root;

    private static IProject project;

    private static final Probe PROBE = new Probe();

    /**
     * Opens a project whose one module is served by the probe.
     *
     * @throws Exception when the project cannot be created
     */
    @BeforeClass
    public static void aProjectWithAProvidedModule() throws Exception
    {
        root = Files.createTempDirectory("aiedt-separator-probe"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve("src/CommonModules/Separator")); //$NON-NLS-1$
        Files.write(projectDir.resolve("src/CommonModules/Separator/holder.probe"), //$NON-NLS-1$
            "probe".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        ModuleSources.register(PROBE);
    }

    /**
     * Takes the provider back and deletes the project.
     *
     * @throws Exception when the project cannot be deleted
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        ModuleSources.unregister(PROBE);
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /** The module holds one method before each case. */
    @Before
    public void theModuleHoldsOneMethod()
    {
        module("Процедура Первая()", "\tСообщить(1);", "КонецПроцедуры"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * @param lines the lines the module holds
     */
    private static void module(String... lines)
    {
        PROBE.lines.clear();
        PROBE.lines.addAll(List.of(lines));
    }

    /**
     * Writes into the probe module.
     *
     * @param mode the write mode
     * @param line the line argument, or {@code null}
     * @param source the text to write
     */
    private static void write(String mode, String line, String source)
    {
        Map<String, String> args = new HashMap<>();
        args.put("projectName", PROJECT); //$NON-NLS-1$
        args.put("modulePath", ADDRESS); //$NON-NLS-1$
        args.put("mode", mode); //$NON-NLS-1$
        args.put("source", source); //$NON-NLS-1$
        if (line != null)
        {
            args.put("line", line); //$NON-NLS-1$
        }
        String answer = new ModuleSourceWriter().execute(args);
        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
    }

    /** An appended method stands one blank line below the last method, and nothing follows it. */
    @Test
    public void anAppendedMethodIsSeparatedFromTheOneAbove()
    {
        write("append", null, NEW_METHOD); //$NON-NLS-1$

        assertEquals(List.of("Процедура Первая()", "\tСообщить(1);", "КонецПроцедуры", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Процедура Новая()", "\tСообщить(3);", "КонецПроцедуры"), PROBE.lines); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Appending after a blank last line adds no second blank line. */
    @Test
    public void aBlankLineAlreadyThereIsNotDoubled()
    {
        module("Процедура Первая()", "КонецПроцедуры", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        write("append", null, NEW_METHOD); //$NON-NLS-1$

        assertEquals(List.of("Процедура Первая()", "КонецПроцедуры", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Процедура Новая()", "\tСообщить(3);", "КонецПроцедуры"), PROBE.lines); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A method put before the first line is separated below only: the start of the file is free. */
    @Test
    public void aMethodBeforeTheFirstIsSeparatedBelowOnly()
    {
        write("insertBefore", "1", NEW_METHOD); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("Процедура Новая()", "\tСообщить(3);", "КонецПроцедуры", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Процедура Первая()", "\tСообщить(1);", "КонецПроцедуры"), PROBE.lines); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Between two methods already a blank line apart, the new one gets the one blank line it lacks. */
    @Test
    public void aMethodBetweenTwoGetsOnlyTheMissingBlankLine()
    {
        module("Процедура Первая()", "КонецПроцедуры", "", "Процедура Вторая()", "КонецПроцедуры"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        write("insertAfter", "2", NEW_METHOD); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("Процедура Первая()", "КонецПроцедуры", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Процедура Новая()", "\tСообщить(3);", "КонецПроцедуры", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Процедура Вторая()", "КонецПроцедуры"), PROBE.lines); //$NON-NLS-1$ //$NON-NLS-2$

        module("Процедура Первая()", "КонецПроцедуры", "", "Процедура Вторая()", "КонецПроцедуры"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        write("insertBefore", "4", NEW_METHOD); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("Процедура Первая()", "КонецПроцедуры", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Процедура Новая()", "\tСообщить(3);", "КонецПроцедуры", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Процедура Вторая()", "КонецПроцедуры"), PROBE.lines); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A method with a header comment and a directive is a whole method too. */
    @Test
    public void aHeaderAndADirectiveOpenAWholeMethod()
    {
        write("insertAfter", "3", "// Header.\n&НаСервере\nФункция Новая()\n\tВозврат 1;\nКонецФункции"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(List.of("Процедура Первая()", "\tСообщить(1);", "КонецПроцедуры", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "// Header.", "&НаСервере", "Функция Новая()", "\tВозврат 1;", "КонецФункции"), PROBE.lines); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    /** An English asynchronous method is recognised as whole. */
    @Test
    public void anEnglishAsyncMethodIsWhole()
    {
        write("append", null, "Async Procedure Fresh()\nEndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("Процедура Первая()", "\tСообщить(1);", "КонецПроцедуры", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Async Procedure Fresh()", "EndProcedure"), PROBE.lines); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A line put into a body is written as given, with nothing around it. */
    @Test
    public void aLineIntoABodyGetsNoBlankLines()
    {
        write("insertAfter", "2", "\tСообщить(2);"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(List.of("Процедура Первая()", "\tСообщить(1);", "\tСообщить(2);", "КонецПроцедуры"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            PROBE.lines);
    }

    /** A comment alone is not a method, so it takes no blank lines. */
    @Test
    public void aCommentAloneIsNotAMethod()
    {
        assertEquals(List.of("// note"), ModuleSourceWriter.separatedInsert("// note", "x", "y")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
