/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;

/**
 * The refresh before an update reports what it actually picked up, not a constant zero.
 *
 * <p>The platform's {@code refreshLocal} returns nothing about what it found, so the count the
 * answer promises is measured with a POST_CHANGE listener held for the duration of the refresh.
 * The project here is real - a temporary one with its location on disk - and the change is
 * written outside the workspace with {@code java.nio.file}, which is exactly the traffic the
 * refresh exists to catch: a file tool, a git checkout, a pull.</p>
 */
public class TheRefreshCountsWhatTheDiskChangedTest
{
    private static final String PROJECT = "AiEdtRefreshProbe"; //$NON-NLS-1$

    private static Path projectDir;

    private static IProject project;

    /**
     * A temporary project in the workspace, with one file already on disk.
     *
     * @throws Exception when the project cannot be created
     */
    @BeforeClass
    public static void aProjectWithAFileOnDisk() throws Exception
    {
        projectDir = Files.createTempDirectory("aiedt-refresh-probe"); //$NON-NLS-1$
        Files.createDirectories(projectDir.resolve("src")); //$NON-NLS-1$
        Files.writeString(projectDir.resolve("src/Module.bsl"), "// probe\n", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

    /**
     * The project and the temporary directory go.
     *
     * @throws Exception when either cannot be removed
     */
    @AfterClass
    public static void theProjectAndTheDirectoryGo() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (projectDir != null)
        {
            try (var walk = Files.walk(projectDir))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    /**
     * A file rewritten on disk behind the workspace's back is picked up by the refresh, and the
     * report says so with a number rather than a constant zero. The timestamp is pushed forward
     * explicitly so the out-of-sync detection cannot miss the write on a coarse file clock.
     *
     * @throws Exception when the disk write fails
     */
    @Test
    public void aFileChangedOnDiskIsCounted() throws Exception
    {
        Path module = projectDir.resolve("src/Module.bsl"); //$NON-NLS-1$
        Files.writeString(module, "// changed outside the workspace\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.setLastModifiedTime(module, FileTime.fromMillis(System.currentTimeMillis() + 2_000L));

        JsonObject report = DatabaseUpdater.refreshFromDisk(project, project);

        assertTrue(report.toString(), report.get("changedResources").getAsInt() >= 1); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the listener the refresh used does not outlive it", //$NON-NLS-1$
            0, DatabaseUpdater.activeRefreshCounters());
    }

    /**
     * Once the workspace has heard about everything on disk, another refresh changes nothing,
     * and the report says 0 - which is now a fact about the model rather than a placeholder.
     */
    @Test
    public void aSecondReadWithoutChangesCountsZero()
    {
        DatabaseUpdater.refreshFromDisk(project, project);

        JsonObject report = DatabaseUpdater.refreshFromDisk(project, project);

        assertEquals(report.toString(), 0, report.get("changedResources").getAsInt()); //$NON-NLS-1$
    }

    /**
     * A refresh that fails leaves no listener behind: the counter is taken down in a closing
     * step whatever the refresh did. Checked by the registered-counter count after the call,
     * and by a later refresh that counts this change once - a leaked listener would keep
     * counting into reports nobody reads.
     *
     * @throws Exception when the disk write fails
     */
    @Test
    public void aFailedRefreshTakesItsListenerDown() throws Exception
    {
        IProject broken = (IProject)Proxy.newProxyInstance(
            TheRefreshCountsWhatTheDiskChangedTest.class.getClassLoader(),
            new Class<?>[] { IProject.class },
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "isAccessible": //$NON-NLS-1$
                        return true;
                    case "getName": //$NON-NLS-1$
                        return "broken-probe"; //$NON-NLS-1$
                    case "hashCode": //$NON-NLS-1$
                        return System.identityHashCode(proxy);
                    case "equals": //$NON-NLS-1$
                        return proxy == args[0];
                    case "toString": //$NON-NLS-1$
                        return "broken-probe"; //$NON-NLS-1$
                    case "refreshLocal": //$NON-NLS-1$
                        throw new CoreException(
                            new Status(IStatus.ERROR, "ru.aiedt.mcp.server.tests", "probe failure")); //$NON-NLS-1$ //$NON-NLS-2$
                    default:
                        return null;
                }
            });

        JsonObject failed = DatabaseUpdater.refreshFromDisk(broken, broken);

        assertTrue(failed.toString(), failed.has("refreshError")); //$NON-NLS-1$
        assertEquals("a failed refresh leaves no listener behind", //$NON-NLS-1$
            0, DatabaseUpdater.activeRefreshCounters());

        Path module = projectDir.resolve("src/Module.bsl"); //$NON-NLS-1$
        Files.writeString(module, "// changed after the failed refresh\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.setLastModifiedTime(module, FileTime.fromMillis(System.currentTimeMillis() + 2_000L));

        JsonObject after = DatabaseUpdater.refreshFromDisk(project, project);

        assertTrue(after.toString(), after.get("changedResources").getAsInt() >= 1); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, DatabaseUpdater.activeRefreshCounters());
    }
}
