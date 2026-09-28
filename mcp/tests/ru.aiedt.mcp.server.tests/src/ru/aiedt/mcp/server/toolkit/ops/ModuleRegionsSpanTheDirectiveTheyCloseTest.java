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
 * A region ends on the line of the {@code #КонецОбласти} that closes it, an open region ends on the
 * last line of the module, and the region list follows the document: the region that opens first
 * prints first, outer before inner.
 *
 * <p>The module lives with an in-memory provider, so the outline comes from the text and every
 * number below is decided by this test. The model path takes its spans from the same calculation;
 * the live answer of that path is the operator's to check.</p>
 */
public class ModuleRegionsSpanTheDirectiveTheyCloseTest
{
    private static final String PROJECT = "AiEdtRegionSpanProbe"; //$NON-NLS-1$

    private static final String ADDRESS = "Catalogs/Products/Forms/ItemForm/Module.bsl"; //$NON-NLS-1$

    /** A provider whose one module lives in memory; each test writes the lines it needs into it. */
    private static final class Probe implements IModuleSourceProvider, IModuleSource
    {
        final List<String> lines = new ArrayList<>();

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
        root = Files.createTempDirectory("aiedt-region-span"); //$NON-NLS-1$
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

    /**
     * Lays out the directive pairs of the module the defect was measured on: an outer region, an
     * inner one inside it, and a sibling after both.
     *
     * @return the module lines
     */
    private static List<String> moduleWithNestedRegions()
    {
        List<String> lines = new ArrayList<>();
        pad(lines, 10);
        lines.add("#Область ПрограммныйИнтерфейс"); //$NON-NLS-1$
        lines.add(""); //$NON-NLS-1$
        lines.add("#Область ДляВызоваИзДругихПодсистем"); //$NON-NLS-1$
        lines.add(""); //$NON-NLS-1$
        lines.add("Процедура Внутренняя(Парам)"); //$NON-NLS-1$
        lines.add("\tВозврат;"); //$NON-NLS-1$
        lines.add("КонецПроцедуры"); //$NON-NLS-1$
        pad(lines, 35);
        lines.add("#КонецОбласти"); //$NON-NLS-1$
        lines.add(""); //$NON-NLS-1$
        lines.add("#КонецОбласти"); //$NON-NLS-1$
        lines.add(""); //$NON-NLS-1$
        lines.add("#Область СлужебныеПроцедурыИФункции"); //$NON-NLS-1$
        lines.add(""); //$NON-NLS-1$
        lines.add("Функция Служебная()"); //$NON-NLS-1$
        lines.add("\tВозврат 1;"); //$NON-NLS-1$
        lines.add("КонецФункции"); //$NON-NLS-1$
        pad(lines, 58);
        lines.add("#КонецОбласти"); //$NON-NLS-1$
        lines.add(""); //$NON-NLS-1$
        return lines;
    }

    /**
     * Pads the lines with empty ones up to a 1-based line number.
     *
     * @param lines the lines to pad
     * @param upToLine the 1-based line the list must reach
     */
    private static void pad(List<String> lines, int upToLine)
    {
        while (lines.size() < upToLine)
        {
            lines.add(""); //$NON-NLS-1$
        }
    }

    /**
     * Writes the given lines into the probe module and reads its outline.
     *
     * @param lines the module lines
     * @return the outline the tool answers
     */
    private static String outlineOf(List<String> lines)
    {
        PROBE.lines.clear();
        PROBE.lines.addAll(lines);
        return new ModuleOutlineReader()
            .execute(args("projectName", PROJECT, "modulePath", ADDRESS)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Nested regions end on their own closing directive, not past the last thing inside them. */
    @Test
    public void nestedRegionsEndOnTheirOwnDirective()
    {
        String outline = outlineOf(moduleWithNestedRegions());
        assertTrue(outline, outline.contains("- ПрограммныйИнтерфейс (line 11-38)")); //$NON-NLS-1$
        assertTrue(outline, outline.contains("- ДляВызоваИзДругихПодсистем (line 13-36)")); //$NON-NLS-1$
        assertTrue(outline, outline.contains("- СлужебныеПроцедурыИФункции (line 40-59)")); //$NON-NLS-1$
    }

    /**
     * A region with no methods in it still ends on its own closing directive.
     *
     * <p>On the model path such a region has no children to measure, and a span built from children
     * would answer the line after the directive that opens it.</p>
     */
    @Test
    public void aRegionWithoutMethodsEndsOnItsOwnDirective()
    {
        String outline = outlineOf(List.of("#Область Пустая", "", "", "#КонецОбласти", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "Процедура Вне()", "\tВозврат;", "КонецПроцедуры")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(outline, outline.contains("- Пустая (line 1-4)")); //$NON-NLS-1$
    }

    /** A region the source never closes ends on the last line of the module. */
    @Test
    public void anUnclosedRegionEndsOnTheLastLine()
    {
        String outline = outlineOf(List.of("#Область Открытая", "", "Процедура Внутри()", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\tВозврат;", "КонецПроцедуры")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(outline, outline.contains("- Открытая (line 1-5)")); //$NON-NLS-1$
    }

    /**
     * The region list prints in document order: the outer region before the inner one it encloses,
     * and both before the sibling that follows them.
     */
    @Test
    public void theRegionListFollowsTheDocumentOrder()
    {
        String outline = outlineOf(moduleWithNestedRegions());
        int outer = outline.indexOf("ПрограммныйИнтерфейс (line 11-38)"); //$NON-NLS-1$
        int inner = outline.indexOf("ДляВызоваИзДругихПодсистем (line 13-36)"); //$NON-NLS-1$
        int sibling = outline.indexOf("СлужебныеПроцедурыИФункции (line 40-59)"); //$NON-NLS-1$
        assertTrue(outline, outer >= 0 && inner >= 0 && sibling >= 0);
        assertTrue("the outer region prints before the inner one: " + outline, outer < inner); //$NON-NLS-1$
        assertTrue("the sibling prints after both: " + outline, inner < sibling); //$NON-NLS-1$
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
