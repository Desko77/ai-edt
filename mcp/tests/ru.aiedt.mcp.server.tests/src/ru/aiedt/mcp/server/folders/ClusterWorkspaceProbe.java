/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;

/**
 * A temporary workspace project whose {@code .settings} folder the cluster store can read and write.
 * <p>
 * The project lives in its own directory outside the workspace metadata, and {@link #close()} removes
 * both. Tests that need a real {@link IProject} share this so each of them does not repeat the
 * create-open-delete sequence.
 * </p>
 */
public final class ClusterWorkspaceProbe
{
    /** The project the store is pointed at. */
    public final IProject project;

    private final Path root;

    private ClusterWorkspaceProbe(IProject project, Path root)
    {
        this.project = project;
        this.root = root;
    }

    /**
     * Creates and opens a project in a fresh temporary directory.
     *
     * @param namePrefix the start of the project name; a unique suffix is appended
     * @return the open probe
     * @throws Exception when the directory or the project cannot be created
     */
    public static ClusterWorkspaceProbe open(String namePrefix) throws Exception
    {
        String name = namePrefix + Long.toUnsignedString(System.nanoTime());
        Path root = Files.createTempDirectory("aiedt-clusters-"); //$NON-NLS-1$
        Path projectDir = root.resolve(name);
        Files.createDirectories(projectDir);
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject project = workspace.getRoot().getProject(name);
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        return new ClusterWorkspaceProbe(project, root);
    }

    /**
     * Returns the clusters file on disk, whether or not it exists yet.
     *
     * @return the path of {@code .settings/aiedt-clusters.yaml}
     */
    public Path clustersFile()
    {
        return project.getLocation().toFile().toPath()
            .resolve(ClusterKeys.SETTINGS_FOLDER)
            .resolve(ClusterKeys.CLUSTERS_FILE);
    }

    /**
     * Writes the clusters file and refreshes the workspace so an {@code IFile} sees those bytes.
     *
     * @param bytes the file contents
     * @throws Exception when the write or the refresh fails
     */
    public void writeClusters(byte[] bytes) throws Exception
    {
        Path file = clustersFile();
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * Deletes the project and the temporary directory.
     *
     * @throws Exception when the project cannot be deleted
     */
    public void close() throws Exception
    {
        if (project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        if (root != null && Files.exists(root))
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try
                    {
                        Files.deleteIfExists(path);
                    }
                    catch (IOException ignored)
                    {
                        // The workspace may still be releasing the directory; the next test uses its own.
                    }
                });
            }
        }
    }
}
