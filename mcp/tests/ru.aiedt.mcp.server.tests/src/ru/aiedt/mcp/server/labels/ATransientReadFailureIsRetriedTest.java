/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A marker read that fails for a while - a sharing violation, a network drive that went away -
 * disables the markers only while the failure lasts: the next call reads the file again instead
 * of answering from a remembered failure.
 */
public class ATransientReadFailureIsRetriedTest
{
    private static final String PROJECT = "AiEdtMarkerReadRetry"; //$NON-NLS-1$

    private static final String OUTSIDE = "tags:" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "- color: '#112233'" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "  description: written outside" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "  name: external" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "assignments: {}" + "\n"; //$NON-NLS-1$ //$NON-NLS-2$

    private static Path root;

    private static IProject project;

    /**
     * A temporary project the marker file can live in.
     *
     * @throws Exception when the workspace refuses the project
     */
    @BeforeClass
    public static void aTemporaryProject() throws Exception
    {
        root = Files.createTempDirectory("aiedt-marker-retry"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir);
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject existing = workspace.getRoot().getProject(PROJECT);
        if (existing.exists())
        {
            existing.delete(true, true, new NullProgressMonitor());
        }
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

    /**
     * Removes the project and the directory it was created in.
     *
     * @throws Exception when the project cannot be deleted
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        MarkerManager.dispose();
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /**
     * A fresh service and no marker file, so one test cannot leave a cache for the next.
     *
     * @throws Exception when the file cannot be removed
     */
    @Before
    public void aFreshMarkerFile() throws Exception
    {
        MarkerManager.dispose();
        Path yaml = markerFile();
        if (Files.exists(yaml))
        {
            refuseReading(yaml, false);
            Files.deleteIfExists(yaml);
        }
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * Drops the service and gives the file back its ordinary access.
     */
    @After
    public void theServiceStops() throws IOException
    {
        MarkerManager.dispose();
        if (Files.exists(markerFile()))
        {
            refuseReading(markerFile(), false);
        }
    }

    /**
     * A read that failed is tried again by the next call: while the file cannot be read the
     * markers answer empty and a mutation refuses, and once the file can be read the markers
     * come back without the cache being cleared from outside.
     *
     * @throws Exception when the file cannot be written or locked
     */
    @Test
    public void aReadThatFailedIsTriedAgainByTheNextCall() throws Exception
    {
        Files.createDirectories(markerFile().getParent());
        Files.write(markerFile(), OUTSIDE.getBytes(StandardCharsets.UTF_8));
        refuseReading(markerFile(), true);
        try
        {
            Assume.assumeFalse("the file system lets this user read a refused file", //$NON-NLS-1$
                Files.isReadable(markerFile()));
            MarkerManager manager = MarkerManager.getInstance();
            assertTrue("a read that fails answers no markers", manager.getMarkers(project).isEmpty()); //$NON-NLS-1$
            assertFalse("a mutation on a state nobody holds is refused", //$NON-NLS-1$
                manager.assignMarker(project, "Catalog.X", "external")); //$NON-NLS-1$ //$NON-NLS-2$

            refuseReading(markerFile(), false);

            assertTrue("the next call must read the file again", manager.getMarkers(project).stream() //$NON-NLS-1$
                .anyMatch(marker -> "external".equals(marker.getName()))); //$NON-NLS-1$
        }
        finally
        {
            refuseReading(markerFile(), false);
        }
    }

    /**
     * Refuses or allows the file's reading for its owner: an access-control entry on file systems
     * that have them, the owner's read permission where POSIX rules apply.
     *
     * @param file the file to refuse or allow
     * @param refuse true to refuse the owner's reading
     * @throws IOException when the access cannot be changed
     */
    private static void refuseReading(Path file, boolean refuse) throws IOException
    {
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null)
        {
            java.nio.file.attribute.UserPrincipal owner = acl.getOwner();
            List<AclEntry> entries = new ArrayList<>(acl.getAcl());
            if (refuse)
            {
                entries.add(0, AclEntry.newBuilder().setType(AclEntryType.DENY)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA)).build());
            }
            else
            {
                entries.removeIf(entry -> entry.type() == AclEntryType.DENY
                    && owner.equals(entry.principal()));
            }
            acl.setAcl(entries);
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null)
        {
            Set<PosixFilePermission> permissions = new HashSet<>(posix.readAttributes().permissions());
            if (refuse)
            {
                permissions.remove(PosixFilePermission.OWNER_READ);
            }
            else
            {
                permissions.add(PosixFilePermission.OWNER_READ);
            }
            posix.setPermissions(permissions);
        }
    }

    /**
     * Where this project's marker file is.
     *
     * @return the path, which may not exist yet
     */
    private static Path markerFile()
    {
        return project.getLocation().toFile().toPath().resolve(MarkerKeys.SETTINGS_FOLDER)
            .resolve(MarkerKeys.MARKERS_FILE);
    }
}
