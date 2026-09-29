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

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A module that cannot be read is named as failed, not passed over in silence.
 * <p>
 * The module diff read both sides of every pair, and a module whose workspace entry no longer
 * opened left the pair out of the answer entirely: an agent reading the result saw a comparison
 * that covered everything, when one module had said nothing at all.
 * </p>
 * <p>
 * The unread module is made by deleting the file behind the workspace's back after it has been
 * refreshed: the tree still lists the file, and reading it fails the same way it does for a file
 * held open by something else - on every platform, without depending on file permissions.
 * </p>
 */
public class AModuleThatCannotBeReadIsFailedNotSkippedTest
{
    private static final String FIRST = "AiEdtCompareModuleFirst"; //$NON-NLS-1$

    private static final String SECOND = "AiEdtCompareModuleSecond"; //$NON-NLS-1$

    private static final String SAME = "CommonModules/Same/Module.bsl"; //$NON-NLS-1$

    private static final String CHANGED = "CommonModules/Changed/Module.bsl"; //$NON-NLS-1$

    private static final String UNREAD = "CommonModules/Unread/Module.bsl"; //$NON-NLS-1$

    private static Path firstRoot;

    private static Path secondRoot;

    private static IProject first;

    private static IProject second;

    @BeforeClass
    public static void twoProjectsWhoseModulesDiffer() throws Exception
    {
        firstRoot = Files.createTempDirectory("aiedt-compare-module-a"); //$NON-NLS-1$
        secondRoot = Files.createTempDirectory("aiedt-compare-module-b"); //$NON-NLS-1$
        write(firstRoot, SAME, "Процедура Общая() КонецПроцедуры"); //$NON-NLS-1$
        write(secondRoot, SAME, "Процедура Общая() КонецПроцедуры"); //$NON-NLS-1$
        write(firstRoot, CHANGED, "Процедура Изменённая() КонецПроцедуры"); //$NON-NLS-1$
        write(secondRoot, CHANGED, "Процедура Изменённая()\n\tВозврат; // правка\nКонецПроцедуры"); //$NON-NLS-1$
        write(firstRoot, UNREAD, "Процедура Нечитаемая() КонецПроцедуры"); //$NON-NLS-1$
        write(secondRoot, UNREAD, "Процедура Нечитаемая()\n\t// другая правка\nКонецПроцедуры"); //$NON-NLS-1$
        first = openProject(FIRST, firstRoot);
        second = openProject(SECOND, secondRoot);
        // The workspace knows the file; the disk no longer has it. Reading it through the
        // workspace fails from here on.
        Files.delete(secondRoot.resolve("src").resolve(UNREAD)); //$NON-NLS-1$
    }

    @AfterClass
    public static void theProjectsGo() throws Exception
    {
        deleteProject(first);
        deleteProject(second);
        deleteTree(firstRoot);
        deleteTree(secondRoot);
    }

    private static void write(Path root, String srcRelative, String text) throws Exception
    {
        Path target = root.resolve("src").resolve(srcRelative); //$NON-NLS-1$
        Files.createDirectories(target.getParent());
        Files.write(target, text.getBytes(StandardCharsets.UTF_8));
    }

    private static IProject openProject(String name, Path location) throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject opened = workspace.getRoot().getProject(name);
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocation(new org.eclipse.core.runtime.Path(location.toString()));
        opened.create(description, new NullProgressMonitor());
        opened.open(new NullProgressMonitor());
        opened.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        return opened;
    }

    private static void deleteProject(IProject project) throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
    }

    private static void deleteTree(Path root) throws Exception
    {
        if (root == null)
        {
            return;
        }
        try (Stream<Path> entries = Files.walk(root))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** The unread module is named in the failed list, with the count beside it. */
    @Test
    public void anUnreadModuleIsNamedAsFailed()
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "projects"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", FIRST); //$NON-NLS-1$
        params.put("target", SECOND); //$NON-NLS-1$
        params.put("level", "module"); //$NON-NLS-1$
        String answer = new CompareConfigurationsTool().execute(params);

        assertTrue(answer, answer.contains("src/" + UNREAD)); //$NON-NLS-1$
        assertTrue(answer, answer.contains("\"failedCount\":1")); //$NON-NLS-1$
    }

    /** The modules that could be read are still compared: same reads as nothing, changed as changed. */
    @Test
    public void readableModulesKeepTheirAnswers()
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "projects"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", FIRST); //$NON-NLS-1$
        params.put("target", SECOND); //$NON-NLS-1$
        params.put("level", "module"); //$NON-NLS-1$
        String answer = new CompareConfigurationsTool().execute(params);

        assertTrue(answer, answer.contains("src/" + CHANGED)); //$NON-NLS-1$
        assertFalse(answer, answer.contains("src/" + SAME)); //$NON-NLS-1$
    }
}
