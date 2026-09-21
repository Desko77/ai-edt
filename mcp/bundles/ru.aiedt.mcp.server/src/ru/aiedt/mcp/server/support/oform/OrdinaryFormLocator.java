/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.oform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.support.MetadataTypeCatalog;

/**
 * Where the ordinary forms of a project are on disk, and what each one is called.
 *
 * <p>An ordinary form is a directory with {@code Form.oform} in it: {@code src/<Type
 * directory>/<Object>/Forms/<Form>/} for an object's form and {@code src/CommonForms/<Form>/}
 * for a common form. The FQN is read off that path - {@code Catalog.Products.Form.ItemForm},
 * {@code CommonForm.Selection} - the way the rest of the server names forms.</p>
 */
public final class OrdinaryFormLocator
{
    /** The file name an ordinary form keeps its container under. */
    public static final String FILE_NAME = "Form.oform"; //$NON-NLS-1$

    /** One ordinary form found on disk. */
    public static final class Located
    {
        private final String fqn;

        private final Path file;

        Located(String fqn, Path file)
        {
            this.fqn = fqn;
            this.file = file;
        }

        /** @return the FQN, {@code Type.Object.Form.Name} or {@code CommonForm.Name} */
        public String fqn()
        {
            return fqn;
        }

        /** @return the container file */
        public Path file()
        {
            return file;
        }
    }

    private OrdinaryFormLocator()
    {
        // static utility
    }

    /**
     * Every ordinary form of a project, in path order.
     *
     * @param project the project
     * @return the forms, empty when the project has no {@code src} or no ordinary forms
     * @throws IOException when the tree cannot be walked
     */
    public static List<Located> locate(IProject project) throws IOException
    {
        List<Located> found = new ArrayList<>();
        if (project == null || project.getLocation() == null)
        {
            return found;
        }
        Path src = project.getLocation().toFile().toPath().resolve("src"); //$NON-NLS-1$
        if (!Files.isDirectory(src))
        {
            return found;
        }
        try (Stream<Path> walk = Files.walk(src))
        {
            walk.filter(p -> p.getFileName() != null && FILE_NAME.equals(p.getFileName().toString()))
                .sorted()
                .forEach(p -> {
                    String fqn = fqnOf(src, p);
                    if (fqn != null)
                    {
                        found.add(new Located(fqn, p));
                    }
                });
        }
        return found;
    }

    /**
     * The FQN of a form by where its container lies.
     *
     * @param src the project's {@code src}
     * @param file the container
     * @return the FQN, or {@code null} when the path is not one an ordinary form is stored at
     */
    public static String fqnOf(Path src, Path file)
    {
        Path relative = src.relativize(file.getParent());
        int count = relative.getNameCount();
        if (count == 2 && "CommonForms".equals(relative.getName(0).toString())) //$NON-NLS-1$
        {
            return "CommonForm." + relative.getName(1); //$NON-NLS-1$
        }
        if (count == 4 && "Forms".equals(relative.getName(2).toString())) //$NON-NLS-1$
        {
            String type = MetadataTypeCatalog.getTypeByDirectoryName(relative.getName(0).toString());
            if (type == null)
            {
                return null;
            }
            return type + "." + relative.getName(1) + ".Form." + relative.getName(3); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * The container of a form named by FQN.
     *
     * @param project the project
     * @param formFqn {@code Type.Object.Form.Name} or {@code CommonForm.Name}
     * @return the file, or {@code null} when the FQN has no ordinary-form directory behind it
     */
    public static Path fileOf(IProject project, String formFqn)
    {
        if (project == null || project.getLocation() == null || formFqn == null)
        {
            return null;
        }
        Path src = project.getLocation().toFile().toPath().resolve("src"); //$NON-NLS-1$
        String[] parts = formFqn.split("\\."); //$NON-NLS-1$
        Path dir;
        if (parts.length == 2 && "CommonForm".equals(parts[0])) //$NON-NLS-1$
        {
            dir = src.resolve("CommonForms").resolve(parts[1]); //$NON-NLS-1$
        }
        else if (parts.length == 4 && "Form".equals(parts[2])) //$NON-NLS-1$
        {
            String directory = MetadataTypeCatalog.getDirectoryName(parts[0]);
            if (directory == null)
            {
                return null;
            }
            dir = src.resolve(directory).resolve(parts[1]).resolve("Forms").resolve(parts[3]); //$NON-NLS-1$
        }
        else
        {
            return null;
        }
        Path file = dir.resolve(FILE_NAME);
        return Files.isRegularFile(file) ? file : null;
    }
}
