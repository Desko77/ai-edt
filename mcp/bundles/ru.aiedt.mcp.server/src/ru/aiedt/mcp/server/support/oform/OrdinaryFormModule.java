/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.oform;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

import ru.aiedt.mcp.server.support.LineDelimiters;

/**
 * The module of an ordinary form, addressed the way every form module is addressed -
 * {@code <dir>/<object>/Forms/<form>/Module.bsl} - and read from and written into the
 * {@code Form.oform} container that lies where that file would.
 *
 * <p>EDT keeps an ordinary form as one container with the layout and the module inside, and
 * writes no {@code Module.bsl} for it. The module tools resolve an address to a file; when the
 * file is absent and the container is present, this is the seam they read and write through, so
 * a caller uses one address for both kinds of form. The layout entry is carried through a write
 * untouched, and the container comes back byte for byte when the module did not change.</p>
 */
public final class OrdinaryFormModule
{
    /** The module kind {@code list_modules} reports for a module read from a container. */
    public static final String KIND = "OrdinaryFormModule"; //$NON-NLS-1$

    /** The value of the {@code source} mark an answer carries when its text came from a container. */
    public static final String SOURCE = "oform"; //$NON-NLS-1$

    private static final String MODULE_FILE = "Module.bsl"; //$NON-NLS-1$

    private static final String SRC = "src"; //$NON-NLS-1$

    private final IProject project;

    private final String modulePath;

    private final IFile container;

    private OrdinaryFormModule(IProject project, String modulePath, IFile container)
    {
        this.project = project;
        this.modulePath = modulePath;
        this.container = container;
    }

    /**
     * Locates the container behind a module address.
     *
     * @param project the project
     * @param modulePath the {@code src}-relative address, a {@code Module.bsl} path
     * @return the module, or {@code null} when the address is not that of an ordinary form's
     *         module: the path does not end in {@code Module.bsl}, the file exists, or no
     *         {@code Form.oform} lies beside it
     */
    public static OrdinaryFormModule locate(IProject project, String modulePath)
    {
        if (project == null || modulePath == null || modulePath.isEmpty())
        {
            return null;
        }
        String normalized = modulePath.replace('\\', '/');
        while (normalized.startsWith("/")) //$NON-NLS-1$
        {
            normalized = normalized.substring(1);
        }
        if (normalized.regionMatches(true, 0, SRC + "/", 0, 4)) //$NON-NLS-1$
        {
            normalized = normalized.substring(4);
        }
        int slash = normalized.lastIndexOf('/');
        String fileName = slash < 0 ? normalized : normalized.substring(slash + 1);
        if (!MODULE_FILE.equalsIgnoreCase(fileName) || slash < 0)
        {
            return null;
        }
        IPath path = new org.eclipse.core.runtime.Path(SRC).append(normalized);
        if (project.getFile(path).exists())
        {
            return null;
        }
        IFile container = project.getFile(path.removeLastSegments(1).append(OrdinaryFormLocator.FILE_NAME));
        if (!container.exists() && !isOnDisk(container))
        {
            return null;
        }
        return new OrdinaryFormModule(project, normalized, container);
    }

    /**
     * Whether an address names an ordinary form's module.
     *
     * @param project the project
     * @param modulePath the {@code src}-relative address
     * @return {@code true} when {@link #locate} would answer
     */
    public static boolean isOne(IProject project, String modulePath)
    {
        return locate(project, modulePath) != null;
    }

    /**
     * The module address a container stands for.
     *
     * @param containerPath the {@code src}-relative path of a {@code Form.oform}
     * @return the {@code Module.bsl} address beside it
     */
    public static String moduleAddressOf(String containerPath)
    {
        String normalized = containerPath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return (slash < 0 ? "" : normalized.substring(0, slash + 1)) + MODULE_FILE; //$NON-NLS-1$
    }

    /** @return the project */
    public IProject getProject()
    {
        return project;
    }

    /** @return the {@code src}-relative module address, forward slashes */
    public String modulePath()
    {
        return modulePath;
    }

    /** @return the container file in the workspace */
    public IFile container()
    {
        return container;
    }

    /** @return the {@code src}-relative path of the container, for an answer */
    public String containerPath()
    {
        return container.getProjectRelativePath().removeFirstSegments(1).toString();
    }

    /** @return the FQN of the form, or {@code null} when its path is not a form's */
    public String fqn()
    {
        if (project.getLocation() == null || container.getLocation() == null)
        {
            return null;
        }
        Path src = project.getLocation().toFile().toPath().resolve(SRC);
        return OrdinaryFormLocator.fqnOf(src, container.getLocation().toFile().toPath());
    }

    /**
     * Reads the container.
     *
     * @return the form file over the container's bytes
     * @throws IOException when the container cannot be read or is not a container
     */
    public OrdinaryFormFile read() throws IOException
    {
        Path location = container.getLocation() == null ? null : container.getLocation().toFile().toPath();
        try
        {
            return OrdinaryFormFile.of(location, V8Container.read(readBytes(container)));
        }
        catch (IllegalArgumentException e)
        {
            throw new IOException(containerPath() + ": " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    /**
     * The module text, without the byte-order mark.
     *
     * @return the text; empty when the container has no module entry
     * @throws IOException when the container cannot be read
     */
    public String text() throws IOException
    {
        String text = read().moduleText();
        return text == null ? "" : text; //$NON-NLS-1$
    }

    /**
     * The module as lines, line separators dropped - what the file readers give for a
     * {@code Module.bsl}.
     *
     * @return the lines; empty for an empty module
     * @throws IOException when the container cannot be read
     */
    public List<String> lines() throws IOException
    {
        return splitLines(text());
    }

    /**
     * The layout text of the form, for the bindings a module write is checked against.
     *
     * @return the layout text, or {@code null} when the container has no layout entry
     * @throws IOException when the container cannot be read
     */
    public String layoutText() throws IOException
    {
        return read().formText();
    }

    /**
     * Writes the module into the container. The layout entry keeps its bytes; the line ending
     * is the one the module already uses, CRLF for a module that has none; the text ends with a
     * line break as every module the writer produces does.
     *
     * @param lines the new module, line separators dropped
     * @throws IOException when the container cannot be read
     * @throws CoreException when the workspace refuses the write
     */
    public void write(List<String> lines) throws IOException, CoreException
    {
        OrdinaryFormFile file = read();
        String current = file.moduleText();
        String delimiter = delimiterOf(current);
        String content = String.join(LineDelimiters.LF, lines);
        if (!content.endsWith(LineDelimiters.LF))
        {
            content = content + LineDelimiters.LF;
        }
        file.setModuleText(LineDelimiters.rewrite(content, delimiter));
        byte[] bytes = file.toBytes();
        if (!container.exists())
        {
            container.refreshLocal(IResource.DEPTH_ZERO, null);
        }
        try (InputStream stream = new ByteArrayInputStream(bytes))
        {
            container.setContents(stream, IResource.FORCE | IResource.KEEP_HISTORY, null);
        }
    }

    /**
     * Splits text into lines the way a buffered reader does: CRLF, LF and a lone CR end a line,
     * and a trailing separator ends the last line rather than opening an empty one.
     *
     * @param text the text
     * @return the lines
     */
    public static List<String> splitLines(String text)
    {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty())
        {
            return lines;
        }
        int start = 0;
        int length = text.length();
        for (int i = 0; i < length; i++)
        {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n')
            {
                lines.add(text.substring(start, i));
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n')
                {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < length)
        {
            lines.add(text.substring(start));
        }
        return lines;
    }

    private static String delimiterOf(String text)
    {
        if (text == null || text.indexOf(LineDelimiters.CRLF) >= 0)
        {
            return LineDelimiters.CRLF;
        }
        return text.indexOf('\n') >= 0 ? LineDelimiters.LF : LineDelimiters.CRLF;
    }

    private static boolean isOnDisk(IFile file)
    {
        IPath location = file.getLocation();
        return location != null && location.toFile().isFile();
    }

    /**
     * Reads a file's bytes, the workspace copy first and the disk when the workspace has none.
     *
     * @param file the file
     * @return the bytes
     * @throws IOException when neither yields the file
     */
    private static byte[] readBytes(IFile file) throws IOException
    {
        try (InputStream stream = file.getContents(true))
        {
            return stream.readAllBytes();
        }
        catch (CoreException primary)
        {
            IPath location = file.getLocation();
            File osFile = location == null ? null : location.toFile();
            if (osFile == null || !osFile.isFile())
            {
                throw new IOException(primary.getMessage(), primary);
            }
            return Files.readAllBytes(osFile.toPath());
        }
    }
}
