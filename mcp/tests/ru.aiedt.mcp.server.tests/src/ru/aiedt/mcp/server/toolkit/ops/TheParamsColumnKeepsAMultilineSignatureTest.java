/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.modules.IModuleSource;
import ru.aiedt.mcp.server.support.modules.IModuleSourceProvider;
import ru.aiedt.mcp.server.support.modules.ModuleSources;

/**
 * The Params column of a module outline carries the whole parameter list. A signature written
 * across several lines is joined into the cell, and a default value with brackets of its own is not
 * cut at the first closing bracket of the line.
 *
 * <p>The module lives with an in-memory provider, so the outline comes from the text and the two
 * cells below are decided by this test.</p>
 */
public class TheParamsColumnKeepsAMultilineSignatureTest
{
    private static final String PROJECT = "AiEdtMultilineParamsProbe"; //$NON-NLS-1$

    private static final String ADDRESS = "Catalogs/Products/Forms/ItemForm/Module.bsl"; //$NON-NLS-1$

    /** A provider whose one module lives in memory, holding the two signatures under test. */
    private static final class Probe implements IModuleSourceProvider, IModuleSource
    {
        final List<String> lines = new ArrayList<>(List.of("#Область ПрограммныйИнтерфейс", "", //$NON-NLS-1$ //$NON-NLS-2$
            "Функция Расчёт(Парам1,", "\tПарам2)", "\tВозврат 1;", "КонецФункции", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "Функция Отчёт(Дата = НачалоДня(ТекущаяДата()))", "\tВозврат Дата;", "КонецФункции", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "", "#КонецОбласти")); //$NON-NLS-1$ //$NON-NLS-2$

        @Override
        public String kind()
        {
            return "ProbeModule"; //$NON-NLS-1$
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
            return null;
        }

        @Override
        public String fqn()
        {
            return "Catalog.Products.Form.ItemForm"; //$NON-NLS-1$
        }

        @Override
        public List<String> lines()
        {
            return new ArrayList<>(lines);
        }

        @Override
        public boolean write(List<String> newLines)
        {
            return false;
        }
    }

    private static Path root;

    private static IProject project;

    private static final Probe PROBE = new Probe();

    @BeforeClass
    public static void aProjectWithAProvidedModule() throws Exception
    {
        root = Files.createTempDirectory("aiedt-multiline-params"); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(root.resolve(PROJECT).toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        ModuleSources.register(PROBE);
    }

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

    /** A signature that continues on the next line fills the cell with both of its lines. */
    @Test
    public void aSignatureSpanningLinesIsJoined()
    {
        String outline = new ModuleOutlineReader()
            .execute(args("projectName", PROJECT, "modulePath", ADDRESS)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(outline, outline.contains("| 3-6 | Парам1, Парам2 |")); //$NON-NLS-1$
    }

    /** A default value with brackets of its own keeps them: the list ends at its own bracket. */
    @Test
    public void aDefaultValueKeepsItsOwnBrackets()
    {
        String outline = new ModuleOutlineReader()
            .execute(args("projectName", PROJECT, "modulePath", ADDRESS)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(outline, outline.contains("| 8-10 | Дата = НачалоДня(ТекущаяДата()) |")); //$NON-NLS-1$
    }

    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
