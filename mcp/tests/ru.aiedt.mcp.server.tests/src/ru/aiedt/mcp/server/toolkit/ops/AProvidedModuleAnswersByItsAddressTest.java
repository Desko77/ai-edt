/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
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
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.modules.IModuleSource;
import ru.aiedt.mcp.server.support.modules.IModuleSourceProvider;
import ru.aiedt.mcp.server.support.modules.ModuleSources;

/**
 * A module another bundle provides answers by its address though no file backs it: the readers
 * read it through the provider, the writer writes it back and carries what the provider says,
 * the lister and the text search find it, the index-built answers end with the provider's
 * coverage line, and a provider that refuses a write is obeyed.
 *
 * <p>The provider here keeps its module in memory under a workspace project over a temporary
 * directory that holds one real module beside it, so every answer is decided by this test.</p>
 */
public class AProvidedModuleAnswersByItsAddressTest
{
    private static final String PROJECT = "AiEdtProvidedModuleProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String ADDRESS = "Catalogs/Products/Forms/ItemForm/Module.bsl"; //$NON-NLS-1$

    private static final String HOLDER = "Catalogs/Products/Forms/ItemForm/Form.probe"; //$NON-NLS-1$

    /** A provider whose one module lives in memory and whose checks follow the request's {@code probe}. */
    private static final class Probe implements IModuleSourceProvider, IModuleSource
    {
        final List<String> lines = new ArrayList<>(List.of("Процедура ПриОткрытии()", "\tВозврат;", //$NON-NLS-1$ //$NON-NLS-2$
            "КонецПроцедуры", "", "Функция Служебная() Экспорт", "\tВозврат 1;", "КонецФункции")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        int writes;

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
        public String coverage(Collection<IProject> projects, String what)
        {
            return projects.contains(project) ? "Coverage: 1 probe module outside the index, " + what + " not counted" : null; //$NON-NLS-1$ //$NON-NLS-2$
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
            return "Catalog.Products.Form.ItemForm"; //$NON-NLS-1$
        }

        @Override
        public List<String> lines()
        {
            return new ArrayList<>(lines);
        }

        @Override
        public WriteCheck check(List<String> before, List<String> after, Map<String, String> options)
        {
            WriteCheck check = WriteCheck.clear();
            String probe = options.get("probe"); //$NON-NLS-1$
            if (probe != null)
            {
                check.notes.put("probe", probe); //$NON-NLS-1$
                check.warning = "WARNING: the probe says " + probe; //$NON-NLS-1$
                if ("refuse".equals(probe)) //$NON-NLS-1$
                {
                    check.refusal = "the probe refuses this write"; //$NON-NLS-1$
                }
            }
            return check;
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
            writes++;
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

    @BeforeClass
    public static void aProjectWithAProvidedModuleAndARealOne() throws Exception
    {
        root = Files.createTempDirectory("aiedt-provided-module"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path managed = projectDir.resolve("src/Catalogs/Products/Forms/Managed"); //$NON-NLS-1$
        Files.createDirectories(projectDir.resolve("src/Catalogs/Products/Forms/ItemForm")); //$NON-NLS-1$
        Files.createDirectories(managed);
        Files.write(managed.resolve("Module.bsl"), //$NON-NLS-1$
            ("﻿&НаКлиенте" + CRLF + "Процедура Управляемая()" + CRLF + "КонецПроцедуры" + CRLF) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .getBytes(StandardCharsets.UTF_8));
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
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

    /** The registry resolves the address through the provider and nothing else. */
    @Test
    public void theRegistryResolvesTheAddress()
    {
        assertEquals(PROBE, ModuleSources.locate(project, ADDRESS));
        assertEquals(PROBE, ModuleSources.locate(project, "src/" + ADDRESS)); //$NON-NLS-1$
        assertNull(ModuleSources.locate(project, "Catalogs/Products/Forms/Managed/Module.bsl")); //$NON-NLS-1$
        // An FQN resolves to the address only when something answers there: the provider does.
        BslModuleAccess.ModulePathResolution byFqn = BslModuleAccess.resolveModulePath(project, "Catalog.Products.Form.ItemForm"); //$NON-NLS-1$
        assertTrue(byFqn.getHint(), byFqn.isResolved());
        assertEquals(ADDRESS, byFqn.getPath());
        assertFalse(BslModuleAccess.resolveModulePath(project, "Catalog.Nothing.Form.ItemForm").isResolved()); //$NON-NLS-1$
    }

    /** The reader, the outline and the method reader read through the provider and say so. */
    @Test
    public void theReadersReadThroughTheProvider()
    {
        String text = new ModuleSourceReader().execute(args("projectName", PROJECT, "modulePath", ADDRESS)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(text, text.contains("Процедура ПриОткрытии()")); //$NON-NLS-1$
        assertTrue(text, text.contains("**Source:** probe")); //$NON-NLS-1$
        assertTrue(text, text.contains("src/" + HOLDER)); //$NON-NLS-1$

        String outline = new ModuleOutlineReader().execute(args("projectName", PROJECT, "modulePath", ADDRESS)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(outline, outline.contains("1 procedures, 1 functions")); //$NON-NLS-1$
        assertTrue(outline, outline.contains("source: probe")); //$NON-NLS-1$

        String method = new MethodSourceReader().execute(args("projectName", PROJECT, "modulePath", ADDRESS, //$NON-NLS-1$ //$NON-NLS-2$
            "methodName", "Служебная")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(method, method.contains("Возврат 1;")); //$NON-NLS-1$
        assertTrue(method, method.contains("source: probe")); //$NON-NLS-1$
        assertTrue(method, method.contains("container: " + HOLDER)); //$NON-NLS-1$

        String real = new ModuleSourceReader().execute(args("projectName", PROJECT, "modulePath", //$NON-NLS-1$ //$NON-NLS-2$
            "Catalogs/Products/Forms/Managed/Module.bsl")); //$NON-NLS-1$
        assertTrue(real, real.contains("Управляемая") && !real.contains("**Source:**")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The writer writes through the provider, carries its notes and its delivery line, and obeys a refusal. */
    @Test
    public void theWriterWritesThroughTheProviderAndObeysIt()
    {
        int before = PROBE.writes;
        String appended = new ModuleSourceWriter().execute(args("projectName", PROJECT, "modulePath", ADDRESS, //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "append", "source", "Процедура Новая()" + CRLF + "КонецПроцедуры", "probe", "warn")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(appended, appended.contains("status: success")); //$NON-NLS-1$
        assertTrue(appended, appended.contains("source: probe")); //$NON-NLS-1$
        assertTrue(appended, appended.contains("container: " + HOLDER)); //$NON-NLS-1$
        assertTrue(appended, appended.contains("probe: warn")); //$NON-NLS-1$
        assertTrue(appended, appended.contains("delivery: the probe delivered it")); //$NON-NLS-1$
        assertTrue(appended, appended.contains("validation: \"not available: the probe validates nothing\"") //$NON-NLS-1$
            || appended.contains("validation: not available: the probe validates nothing")); //$NON-NLS-1$
        assertTrue(appended, appended.contains("WARNING: the probe says warn")); //$NON-NLS-1$
        assertTrue(appended, appended.contains("persistenceSyncOk: true")); //$NON-NLS-1$
        assertEquals(before + 1, PROBE.writes);
        assertTrue(String.join("|", PROBE.lines), PROBE.lines.contains("Процедура Новая()")); //$NON-NLS-1$ //$NON-NLS-2$

        String refused = new ModuleSourceWriter().execute(args("projectName", PROJECT, "modulePath", ADDRESS, //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "append", "source", "Процедура Лишняя()" + CRLF + "КонецПроцедуры", "probe", "refuse")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(refused, refused.startsWith("Error: the write was refused")); //$NON-NLS-1$
        assertTrue(refused, refused.contains("the probe refuses this write")); //$NON-NLS-1$
        assertEquals(before + 1, PROBE.writes);

        String preview = new ModuleSourceWriter().execute(args("projectName", PROJECT, "modulePath", ADDRESS, //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "append", "source", "Процедура Ещё()" + CRLF + "КонецПроцедуры", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(preview, preview.contains("status: preview") && preview.contains("source: probe")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(before + 1, PROBE.writes);
    }

    /** The lister names the module under its kind, and the text search finds its text under the address. */
    @Test
    public void theListerAndTheSearchFindIt()
    {
        String listed = ModulesLister.listModules(PROJECT, "all", "Products", null, 100); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(listed, listed.contains("| " + ADDRESS + " | ProbeModule | Catalog | Products |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(listed, listed.contains("Catalogs/Products/Forms/Managed/Module.bsl")); //$NON-NLS-1$
        assertTrue(listed, listed.contains("of kind ProbeModule have no file of their own")); //$NON-NLS-1$

        String found = new CodeTextSearcher().execute(args("projectName", PROJECT, "query", "Служебная")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(found, found.contains(ADDRESS));
    }

    /** An answer built from the index ends with the provider's coverage line. */
    @Test
    public void theCoverageLineClosesAnIndexAnswer()
    {
        String answer = ModuleSources.appendCoverage(project, "callers", "# Callers\n\nnone\n"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer, answer.endsWith("**Coverage: 1 probe module outside the index, callers not counted**\n")); //$NON-NLS-1$
        assertEquals("Error: no", ModuleSources.appendCoverage(project, "callers", "Error: no")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        IProject other = ResourcesPlugin.getWorkspace().getRoot().getProject("AiEdtNoSuchProject"); //$NON-NLS-1$
        assertEquals("# x\n", ModuleSources.appendCoverage(other, "callers", "# x\n")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
