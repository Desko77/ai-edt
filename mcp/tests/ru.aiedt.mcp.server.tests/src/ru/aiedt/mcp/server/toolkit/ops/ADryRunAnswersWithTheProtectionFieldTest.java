/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * A preview that would strip a large part of a module answers with the {@code protection} field, the
 * way the write itself always has.
 * <p>
 * The module is held by a provider rather than by a file, so the preview goes through the whole path
 * - address resolution, the mode, the shrink measurement, the response - with no live 1C:EDT project
 * behind it.
 * </p>
 */
public class ADryRunAnswersWithTheProtectionFieldTest
{
    private static final String PROJECT = "AiEdtDryRunProbe"; //$NON-NLS-1$

    private static final String ADDRESS = "CommonModules/Probe/Module.bsl"; //$NON-NLS-1$

    /** A provider whose one module lives in memory and counts writes. */
    private static final class Probe implements IModuleSourceProvider, IModuleSource
    {
        final List<String> lines = new ArrayList<>();

        int writes;

        @Override
        public String kind()
        {
            return "DryRunProbeModule"; //$NON-NLS-1$
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
            return "CommonModules/Probe/Module.probe"; //$NON-NLS-1$
        }

        @Override
        public String fqn()
        {
            return "CommonModule.Probe"; //$NON-NLS-1$
        }

        @Override
        public List<String> lines()
        {
            return new ArrayList<>(lines);
        }

        @Override
        public boolean write(List<String> newLines)
        {
            writes++;
            return true;
        }

        @Override
        public String afterWrite(boolean written)
        {
            return null;
        }

        @Override
        public String validationNote()
        {
            return null;
        }
    }

    private static Path root;

    private static IProject project;

    private static final Probe PROBE = new Probe();

    @BeforeClass
    public static void aProjectWithAProvidedModule() throws Exception
    {
        root = Files.createTempDirectory("aiedt-dryrun-probe"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve("src/CommonModules/Probe")); //$NON-NLS-1$
        Files.write(projectDir.resolve("src/CommonModules/Probe/holder.probe"), //$NON-NLS-1$
            "probe".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
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

    @Before
    public void theModuleIsWhatItWas()
    {
        PROBE.lines.clear();
        for (int i = 1; i <= 40; i++)
        {
            PROBE.lines.add("// строка " + i); //$NON-NLS-1$
        }
    }

    /**
     * A preview that strips more than thirty percent of the module answers with the
     * {@code protection} field, and writes nothing.
     */
    @Test
    public void aPreviewThatStripsNearlyHalfAnswersWithProtection()
    {
        int before = PROBE.writes;
        String answer = new ModuleSourceWriter().execute(args(
            "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "modulePath", ADDRESS, //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "replaceLines", //$NON-NLS-1$ //$NON-NLS-2$
            "lineFrom", "1", //$NON-NLS-1$ //$NON-NLS-2$
            "lineTo", "18", //$NON-NLS-1$ //$NON-NLS-2$
            "dryRun", "true", //$NON-NLS-1$ //$NON-NLS-2$
            "source", "// замена")); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: preview")); //$NON-NLS-1$
        assertTrue("the warning is a field of its own", //$NON-NLS-1$
            answer.contains("protection: \"WARNING: this edit strips 43% of the module (from 40 to 23 lines)\"")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("linesBefore: 40")); //$NON-NLS-1$
        assertEquals("a preview writes nothing", before, PROBE.writes); //$NON-NLS-1$
    }

    /** A preview that takes nothing away answers with no protection field. */
    @Test
    public void aPreviewThatRemovesNothingHasNoProtectionField()
    {
        String answer = new ModuleSourceWriter().execute(args(
            "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "modulePath", ADDRESS, //$NON-NLS-1$ //$NON-NLS-2$
            "mode", "replaceLines", //$NON-NLS-1$ //$NON-NLS-2$
            "lineFrom", "1", //$NON-NLS-1$ //$NON-NLS-2$
            "lineTo", "18", //$NON-NLS-1$ //$NON-NLS-2$
            "dryRun", "true", //$NON-NLS-1$ //$NON-NLS-2$
            "source", new17Lines())); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: preview")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("protection:")); //$NON-NLS-1$
    }

    /**
     * Seventeen replacement lines, so the second preview swaps eighteen lines for seventeen of the
     * same shape and removes one.
     *
     * @return the lines, joined into one source text
     */
    private static String new17Lines()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 17; i++)
        {
            if (sb.length() > 0)
            {
                sb.append('\n');
            }
            sb.append("// строка ").append(i); //$NON-NLS-1$
        }
        return sb.toString();
    }

    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
