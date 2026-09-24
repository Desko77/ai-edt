/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
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
 * Only synchronous events in the refresh thread count, so another writer cannot enter the answer.
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
     * Measures the platform guarantee on which attribution of a delta to one refresh rests. Every
     * POST_CHANGE event for this project must be delivered synchronously, in the thread executing
     * {@code refreshLocal}, before that call returns.
     *
     * @throws Exception when the disk write or refresh fails
     */
    @Test
    public void postChangeIsDeliveredInTheRefreshThreadBeforeReturn() throws Exception
    {
        Path measured = projectDir.resolve("src/Measured.bsl"); //$NON-NLS-1$
        Files.writeString(measured, "// measured outside the workspace\n", StandardCharsets.UTF_8); //$NON-NLS-1$

        AtomicBoolean refreshReturned = new AtomicBoolean();
        AtomicReference<Thread> refreshThread = new AtomicReference<>();
        AtomicReference<Throwable> refreshFailure = new AtomicReference<>();
        List<RefreshEventMeasurement> events = new CopyOnWriteArrayList<>();
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IResourceChangeListener listener = event -> {
            if (event.getDelta() != null && event.getDelta().findMember(project.getFullPath()) != null)
            {
                events.add(new RefreshEventMeasurement(Thread.currentThread(), refreshReturned.get()));
            }
        };
        workspace.addResourceChangeListener(listener, IResourceChangeEvent.POST_CHANGE);
        Thread worker = new Thread(() -> {
            refreshThread.set(Thread.currentThread());
            try
            {
                project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());
            }
            catch (Throwable failure)
            {
                refreshFailure.set(failure);
            }
            finally
            {
                refreshReturned.set(true);
            }
        }, "aiedt-refresh-measurement"); //$NON-NLS-1$
        try
        {
            worker.start();
            worker.join();
        }
        finally
        {
            workspace.removeResourceChangeListener(listener);
        }

        assertEquals("refresh itself succeeded", null, refreshFailure.get()); //$NON-NLS-1$
        assertTrue("the disk change produced a POST_CHANGE event", !events.isEmpty()); //$NON-NLS-1$
        for (int index = 0; index < events.size(); index++)
        {
            RefreshEventMeasurement event = events.get(index);
            System.out.println("REFRESH_EVENT_MEASUREMENT event=" + (index + 1) //$NON-NLS-1$
                + " thread=" + event.thread.getName() //$NON-NLS-1$
                + " refreshThread=" + refreshThread.get().getName() //$NON-NLS-1$
                + " refreshReturned=" + event.afterReturn); //$NON-NLS-1$
            assertEquals("POST_CHANGE uses the refresh thread", refreshThread.get(), event.thread); //$NON-NLS-1$
            assertTrue("POST_CHANGE arrives before refreshLocal returns", !event.afterReturn); //$NON-NLS-1$
        }
        System.out.println("REFRESH_EVENT_MEASUREMENT total=" + events.size()); //$NON-NLS-1$
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
     * An empty directory is itself the changed leaf when it first appears on disk.
     *
     * @throws Exception when the directory cannot be created
     */
    @Test
    public void anEmptyDirectoryCreatedOnDiskCountsOne() throws Exception
    {
        Files.createDirectory(projectDir.resolve("EmptyCreated")); //$NON-NLS-1$

        JsonObject report = DatabaseUpdater.refreshFromDisk(project, project);

        assertEquals(report.toString(), 1, report.get("changedResources").getAsInt()); //$NON-NLS-1$
    }

    /**
     * An empty directory is itself the changed leaf when it disappears from disk.
     *
     * @throws Exception when the directory cannot be prepared or removed
     */
    @Test
    public void anEmptyDirectoryDeletedOnDiskCountsOne() throws Exception
    {
        Path empty = projectDir.resolve("EmptyDeleted"); //$NON-NLS-1$
        Files.createDirectory(empty);
        project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());
        Files.delete(empty);

        JsonObject report = DatabaseUpdater.refreshFromDisk(project, project);

        assertEquals(report.toString(), 1, report.get("changedResources").getAsInt()); //$NON-NLS-1$
    }

    /**
     * A resource whose disk type changes is reported even when neither side has child deltas.
     *
     * @throws Exception when the file or directory cannot be changed
     */
    @Test
    public void aFileReplacedByADirectoryIsCounted() throws Exception
    {
        IFile original = project.getFile("ReplacedByDirectory"); //$NON-NLS-1$
        original.create(new ByteArrayInputStream(new byte[] { 1 }), true, new NullProgressMonitor());
        Path location = original.getLocation().toFile().toPath();
        Files.delete(location);
        Files.createDirectory(location);

        JsonObject report = DatabaseUpdater.refreshFromDisk(project, project);

        assertTrue(report.toString(), report.get("changedResources").getAsInt() > 0); //$NON-NLS-1$
    }

    /**
     * A directory holding files, replaced on disk by a file with the same name, counts the new
     * file itself: the delta node carries the removals of what the directory held as its
     * children, and only reading the leaves would report the loss without the replacement.
     *
     * @throws Exception when the disk cannot be rearranged
     */
    @Test
    public void aDirectoryReplacedByAFileIsCounted() throws Exception
    {
        IFolder folder = project.getFolder("ReplacedByFile"); //$NON-NLS-1$
        folder.create(true, true, new NullProgressMonitor());
        IFile inside = folder.getFile("Inside.bsl"); //$NON-NLS-1$
        inside.create(new ByteArrayInputStream(new byte[] { 1 }), true, new NullProgressMonitor());
        Path location = folder.getLocation().toFile().toPath();
        try (var walk = Files.walk(location))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
        Files.writeString(location, "// now a file\n", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject report = DatabaseUpdater.refreshFromDisk(project, project);

        assertTrue(report.toString(), report.get("changedResources").getAsInt() >= 2); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Marker deltas carry workspace metadata, not a disk resource change, and do not count.
     *
     * @throws Exception when the marker cannot be changed
     */
    @Test
    public void markerOnlyChangesCountZero() throws Exception
    {
        IProject markerRefresh = projectWhoseRefreshRuns(() -> {
            IMarker marker = project.createMarker(IMarker.PROBLEM);
            marker.setAttribute(IMarker.MESSAGE, "marker-only probe"); //$NON-NLS-1$
            marker.delete();
        });

        JsonObject report = DatabaseUpdater.refreshFromDisk(markerRefresh, markerRefresh);

        assertEquals(report.toString(), 0, report.get("changedResources").getAsInt()); //$NON-NLS-1$
    }

    /**
     * A workspace API write another thread makes while the refresh is registered belongs to that
     * writer and does not enter this call's answer. The write needs the project's scheduling rule
     * the refresh holds, so it lands after the refresh; a counter that kept listening past its own
     * refresh - the settle window it replaced - counted it. The rule of the thread itself is pinned
     * by {@link #anEventDeliveredOnAnotherThreadDuringTheRefreshIsNotCounted()}.
     *
     * @throws Exception when the coordinated write fails
     */
    @Test
    public void anotherThreadsWorkspaceWriteIsNotCounted() throws Exception
    {
        CountDownLatch refreshStarted = new CountDownLatch(1);
        AtomicReference<JsonObject> report = new AtomicReference<>();
        AtomicReference<Throwable> writeFailure = new AtomicReference<>();
        IFile written = project.getFile("WrittenByAnotherThread.txt"); //$NON-NLS-1$

        Thread refresh = new Thread(() -> {
            refreshStarted.countDown();
            report.set(DatabaseUpdater.refreshFromDisk(project, project));
        }, "aiedt-refresh-under-test"); //$NON-NLS-1$
        Thread writer = new Thread(() -> {
            try
            {
                if (!refreshStarted.await(5, TimeUnit.SECONDS))
                {
                    throw new IllegalStateException("refresh did not start"); //$NON-NLS-1$
                }
                // The write goes in only once the refresh call watches the workspace, so it
                // really overlaps the listener instead of racing its registration.
                long deadline = System.currentTimeMillis() + 5_000L;
                while (DatabaseUpdater.activeRefreshCounters() == 0 && System.currentTimeMillis() < deadline)
                {
                    Thread.sleep(5L);
                }
                written.create(new ByteArrayInputStream(new byte[] { 1 }), true, new NullProgressMonitor());
            }
            catch (Throwable failure)
            {
                writeFailure.set(failure);
            }
        }, "aiedt-unrelated-workspace-writer"); //$NON-NLS-1$
        refresh.start();
        writer.start();
        refresh.join();
        writer.join();

        assertEquals("workspace write succeeded", null, writeFailure.get()); //$NON-NLS-1$
        assertTrue("the other thread really wrote the resource", written.exists()); //$NON-NLS-1$
        assertEquals(report.get().toString(), 0, report.get().get("changedResources").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
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

    private static IProject projectWhoseRefreshRuns(CheckedRunnable refresh)
    {
        return (IProject)Proxy.newProxyInstance(
            TheRefreshCountsWhatTheDiskChangedTest.class.getClassLoader(),
            new Class<?>[] { IProject.class },
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "isAccessible": //$NON-NLS-1$
                        return true;
                    case "getName": //$NON-NLS-1$
                        return PROJECT;
                    case "hashCode": //$NON-NLS-1$
                        return System.identityHashCode(proxy);
                    case "equals": //$NON-NLS-1$
                        return proxy == args[0];
                    case "toString": //$NON-NLS-1$
                        return PROJECT;
                    case "refreshLocal": //$NON-NLS-1$
                        refresh.run();
                        return null;
                    default:
                        return null;
                }
            });
    }

    @FunctionalInterface
    private interface CheckedRunnable
    {
        void run() throws Exception;
    }

    private static final class RefreshEventMeasurement
    {
        private final Thread thread;

        private final boolean afterReturn;

        RefreshEventMeasurement(Thread thread, boolean afterReturn)
        {
            this.thread = thread;
            this.afterReturn = afterReturn;
        }
    }

    /**
     * The counter takes an event only on the thread that is inside the refresh: an event for a
     * watched project delivered on another thread while the refresh runs - a second watched project
     * written concurrently, which the project rule does not serialize - is not this call's work. The
     * same event on the refresh thread counts, so the refusal is the thread rule and nothing else.
     *
     * @throws Exception when the other thread cannot be joined
     */
    @Test
    public void anEventDeliveredOnAnotherThreadDuringTheRefreshIsNotCounted() throws Exception
    {
        DatabaseUpdater.RefreshChangeCounter counter =
            new DatabaseUpdater.RefreshChangeCounter(java.util.Collections.singleton(project));
        IResourceChangeEvent event = postChangeAdding(project.getFile("OnAnotherThread.txt")); //$NON-NLS-1$
        counter.beginRefresh();
        try
        {
            Thread other = new Thread(() -> counter.resourceChanged(event), "aiedt-other-writer"); //$NON-NLS-1$
            other.start();
            other.join();
            assertEquals("an event on another thread is not this refresh's", 0, counter.changed()); //$NON-NLS-1$

            counter.resourceChanged(event);
            assertEquals("the same event on the refresh thread counts", 1, counter.changed()); //$NON-NLS-1$
        }
        finally
        {
            counter.endRefresh();
        }
    }

    /**
     * A POST_CHANGE event whose delta adds one file under its project, the way the workspace
     * reports it: the root, the project changed, the file added.
     *
     * @param file the file the delta adds
     * @return the event
     */
    private static IResourceChangeEvent postChangeAdding(IFile file)
    {
        IResourceDelta added = delta(file, IResourceDelta.ADDED);
        IResourceDelta changedProject = delta(file.getProject(), IResourceDelta.CHANGED, added);
        IResourceDelta root = delta(file.getWorkspace().getRoot(), IResourceDelta.CHANGED, changedProject);
        return (IResourceChangeEvent)Proxy.newProxyInstance(
            TheRefreshCountsWhatTheDiskChangedTest.class.getClassLoader(),
            new Class<?>[] { IResourceChangeEvent.class }, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "getDelta": //$NON-NLS-1$
                    return root;
                case "getType": //$NON-NLS-1$
                    return Integer.valueOf(IResourceChangeEvent.POST_CHANGE);
                default:
                    return null;
                }
            });
    }

    /**
     * One node of a delta tree that visits itself and then its children.
     *
     * @param resource the resource of the node
     * @param kind the kind of the node
     * @param children the child nodes
     * @return the node
     */
    private static IResourceDelta delta(IResource resource, int kind, IResourceDelta... children)
    {
        return (IResourceDelta)Proxy.newProxyInstance(
            TheRefreshCountsWhatTheDiskChangedTest.class.getClassLoader(),
            new Class<?>[] { IResourceDelta.class }, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "accept": //$NON-NLS-1$
                    IResourceDeltaVisitor visitor = (IResourceDeltaVisitor)args[0];
                    if (visitor.visit((IResourceDelta)proxy))
                    {
                        for (IResourceDelta child : children)
                        {
                            child.accept(visitor);
                        }
                    }
                    return null;
                case "getResource": //$NON-NLS-1$
                    return resource;
                case "getKind": //$NON-NLS-1$
                    return Integer.valueOf(kind);
                case "getFlags": //$NON-NLS-1$
                    return Integer.valueOf(0);
                case "getAffectedChildren": //$NON-NLS-1$
                    return children;
                default:
                    throw new UnsupportedOperationException(method.getName());
                }
            });
    }
}
