/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;

import ru.aiedt.mcp.oform.OrdinaryFormFile;
import ru.aiedt.mcp.oform.V8Container;

/**
 * One throwaway project with one ordinary form in it, for a test that drives a
 * {@link FormEditingSession}: the fixture's layout and a module go into a {@code Form.oform}
 * under {@code src/Catalogs/Products/Forms/ItemForm}, the way the real tool finds them.
 */
final class SessionProbe
{
    /** The container's project-relative path. */
    private static final String CONTAINER = "src/Catalogs/Products/Forms/ItemForm/Form.oform"; //$NON-NLS-1$

    private final IProject project;

    private final IFile file;

    private final Path root;

    private final byte[] original;

    private SessionProbe(IProject project, IFile file, Path root, byte[] original)
    {
        this.project = project;
        this.file = file;
        this.root = root;
        this.original = original;
    }

    /**
     * Builds a project around one fixture.
     *
     * @param projectName the project's name, unique in the workspace
     * @param layoutText the layout text the container opens with
     * @param module the module text the container opens with
     * @return the probe
     * @throws Exception when the project cannot be built
     */
    static SessionProbe with(String projectName, String layoutText, String module) throws Exception
    {
        V8Container v8 = V8Container.empty();
        v8.add(V8Container.Entry.of(OrdinaryFormFile.FORM_ENTRY, withBom(layoutText)));
        v8.add(V8Container.Entry.of(OrdinaryFormFile.MODULE_ENTRY, withBom(module)));
        byte[] original = v8.write();
        Path root = Files.createTempDirectory("aiedt-oform-session"); //$NON-NLS-1$
        Path form = root.resolve(projectName).resolve("src/Catalogs/Products/Forms/ItemForm"); //$NON-NLS-1$
        Files.createDirectories(form);
        Path container = form.resolve("Form.oform"); //$NON-NLS-1$
        Files.write(container, original);
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject project = workspace.getRoot().getProject(projectName);
        IProjectDescription description = workspace.newProjectDescription(projectName);
        description.setLocation(new org.eclipse.core.runtime.Path(root.resolve(projectName).toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        IFile file = project.getFile(new org.eclipse.core.runtime.Path(CONTAINER));
        return new SessionProbe(project, file, root, original);
    }

    /**
     * A fixture's text.
     *
     * @param name the fixture's resource name, {@code fixtures/...}
     * @return its text
     * @throws IOException when there is no such fixture
     */
    static String fixture(String name) throws IOException
    {
        try (InputStream in = SessionProbe.class.getResourceAsStream("/ru/aiedt/mcp/oform/" + name)) //$NON-NLS-1$
        {
            if (in == null)
            {
                throw new IOException("no fixture " + name); //$NON-NLS-1$
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** @return the container as the workspace holds it */
    IFile file()
    {
        return file;
    }

    /** @return the project the container lies in */
    IProject project()
    {
        return project;
    }

    /** @return the bytes the container was built with */
    byte[] original()
    {
        return original.clone();
    }

    /** @return the container's bytes now */
    byte[] bytes()
    {
        try
        {
            return Files.readAllBytes(location());
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Another writer replaces the container on disk, the way a write outside the editor lands.
     *
     * @param bytes the bytes to write
     * @throws Exception when the write or the refresh fails
     */
    void anotherWriterWrites(byte[] bytes) throws Exception
    {
        Files.write(location(), bytes);
        file.refreshLocal(IResource.DEPTH_ZERO, null);
    }

    /** Puts the container back as it was built. */
    void restore() throws Exception
    {
        anotherWriterWrites(original);
    }

    /** Deletes the project and the directory it was built in. */
    void dispose()
    {
        try
        {
            if (project.exists())
            {
                project.delete(false, true, new NullProgressMonitor());
            }
        }
        catch (Exception e)
        {
            // the temp directory goes anyway
        }
        try (var walk = Files.walk(root))
        {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private Path location()
    {
        return file.getLocation().toFile().toPath();
    }

    private static byte[] withBom(String text)
    {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[body.length + 3];
        data[0] = (byte)0xEF;
        data[1] = (byte)0xBB;
        data[2] = (byte)0xBF;
        System.arraycopy(body, 0, data, 3, body.length);
        return data;
    }
}
