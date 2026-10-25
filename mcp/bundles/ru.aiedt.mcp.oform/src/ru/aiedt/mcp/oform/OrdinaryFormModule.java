/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

import ru.aiedt.mcp.server.support.EditorBuffer;
import ru.aiedt.mcp.server.support.LineDelimiters;
import ru.aiedt.mcp.server.support.modules.IModuleSource;

/**
 * The module of an ordinary form, addressed the way every form module is addressed -
 * {@code <dir>/<object>/Forms/<form>/Module.bsl} - and read from and written into the
 * {@code Form.oform} container that lies where that file would.
 *
 * <p>EDT keeps an ordinary form as one container with the layout and the module inside, and
 * writes no {@code Module.bsl} for it. The module tools of the plugin resolve an address to a
 * file; when the file is absent and the container is present, the provider answers with this,
 * so a caller uses one address for both kinds of form. The layout entry is carried through a
 * write untouched, and the container comes back byte for byte when the module did not change.</p>
 *
 * <p>The module this instance was read with is remembered, and a write compares the container with
 * it: a container that no longer holds that module is refused rather than replaced, so a change
 * another writer made between the read and the write survives. Only the entry the write replaces
 * is compared - an entry it does not name keeps whatever the container holds at the write. A write
 * that landed is what the next one is compared against, and a read made in between does not move
 * that. A write is also refused while an editor holds the container with changes the file does not
 * have: the container is written as a whole, so the write would replace what the editor holds, and
 * the editor's save would then replace the write.</p>
 */
public final class OrdinaryFormModule
    implements IModuleSource
{
    /** The write option: what to do when the write removes a procedure the form still names. */
    public static final String HANDLER_CHANGES = "handlerChanges"; //$NON-NLS-1$

    /** The module kind {@code list_modules} reports for a module read from a container. */
    public static final String KIND = "OrdinaryFormModule"; //$NON-NLS-1$

    /** The value of the {@code source} mark an answer carries when its text came from a container. */
    public static final String SOURCE = "oform"; //$NON-NLS-1$

    private static final String MODULE_FILE = "Module.bsl"; //$NON-NLS-1$

    private static final String SRC = "src"; //$NON-NLS-1$

    private final IProject project;

    private final String modulePath;

    private final IFile container;

    /** The module text the container held when this instance last read it, and whether it read one
     * at all; the write compares the container with it. */
    private String baseline;

    private boolean hasBaseline;

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

    @Override
    public String kind()
    {
        return KIND;
    }

    @Override
    public String source()
    {
        return SOURCE;
    }

    /** @return the {@code src}-relative module address, forward slashes */
    @Override
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
    @Override
    public String containerPath()
    {
        return container.getProjectRelativePath().removeFirstSegments(1).toString();
    }

    /** @return the FQN of the form, or {@code null} when its path is not a form's */
    @Override
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
     * Reads the container, with the bytes it was read from, so a write of it can tell whether the
     * container still holds what this read found.
     *
     * @return the form file over the container's bytes
     * @throws IOException when the container cannot be read or is not a container
     */
    public OrdinaryFormFile read() throws IOException
    {
        Path location = container.getLocation() == null ? null : container.getLocation().toFile().toPath();
        byte[] bytes = readBytes(container);
        try
        {
            return OrdinaryFormFile.of(location, V8Container.read(bytes), bytes);
        }
        catch (IllegalArgumentException e)
        {
            throw new IOException(containerPath() + ": " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    /**
     * The module text, without the byte-order mark. Reading the text is what a write is computed
     * from, so the container's module is remembered here - once: a later read of this instance,
     * made to inspect the form rather than to compute a change, does not move what the next write
     * is compared against, and only a write that landed does. A container this instance never read
     * is written without a comparison.
     *
     * @return the text; empty when the container has no module entry
     * @throws IOException when the container cannot be read
     */
    public String text() throws IOException
    {
        String text = read().moduleText();
        if (!hasBaseline)
        {
            remembered(text);
        }
        return text == null ? "" : text; //$NON-NLS-1$
    }

    /**
     * Remembers the module the container holds, for the next write to compare against.
     *
     * @param module the module text as read, {@code null} when the container holds no module entry
     */
    private void remembered(String module)
    {
        baseline = module;
        hasBaseline = true;
    }

    /**
     * The module as lines, line separators dropped - what the file readers give for a
     * {@code Module.bsl}.
     *
     * @return the lines; empty for an empty module
     * @throws IOException when the container cannot be read
     */
    @Override
    public List<String> lines() throws IOException
    {
        return splitLines(text());
    }

    /**
     * The module as another revision of this container holds it, split the way {@link #lines()}
     * splits the current text.
     *
     * @param containerRevision the container file of that revision
     * @return the lines, separators dropped; {@code null} when that revision has no module entry
     * @throws IOException when the bytes are not a container
     */
    @Override
    public List<String> linesIn(byte[] containerRevision) throws IOException
    {
        if (containerRevision == null)
        {
            throw new IOException("the container revision has no bytes"); //$NON-NLS-1$
        }
        OrdinaryFormFile file;
        try
        {
            file = OrdinaryFormFile.of(null, V8Container.read(containerRevision));
        }
        catch (IllegalArgumentException e)
        {
            throw new IOException(e.getMessage(), e);
        }
        String text = file.moduleText();
        if (text == null)
        {
            return null;
        }
        return splitLines(text);
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
     * line break as every module the writer produces does. A module that comes out as the text
     * already there is not written: the container keeps its bytes and its timestamp. An empty
     * module over an empty one - no module entry or an empty entry - is not written either, so
     * the write adds no line break to a module that has nothing to end. A container
     * that no longer holds the module this instance read is refused, so a module another writer
     * changed in between survives. A container held by an editor with unsaved changes is refused
     * as well.
     *
     * @param lines the new module, line separators dropped
     * @return whether the container was written
     * @throws IOException when the container cannot be read or the workspace refuses the write,
     *         the container no longer holds the module this instance read, or an editor holds the
     *         container with unsaved changes
     */
    @Override
    public boolean write(List<String> lines) throws IOException
    {
        try
        {
            return writeContainer(lines);
        }
        catch (CoreException e)
        {
            throw new IOException(containerPath() + ": " + e.getMessage(), e); //$NON-NLS-1$
        }
    }

    /**
     * What the write has to say before it happens: the procedures the form still names - in an
     * event binding of its layout or in a string literal of the module - that the write would
     * take away. {@code handlerChanges=warn} (the default) lists them and lets the write through;
     * {@code refuse} keeps the container as it is. EDT resolves neither reference, so nothing
     * else reports the loss before the client fires the event.
     *
     * @param before the lines as they are
     * @param after the lines the write would leave
     * @param options the request's arguments
     * @return the check
     */
    @Override
    public WriteCheck check(List<String> before, List<String> after, Map<String, String> options)
    {
        WriteCheck check = WriteCheck.clear();
        String handlerChanges = options == null ? null : options.get(HANDLER_CHANGES);
        if (handlerChanges == null || handlerChanges.isEmpty())
        {
            handlerChanges = HandlerBindings.WARN;
        }
        handlerChanges = handlerChanges.toLowerCase(Locale.ROOT);
        if (!HandlerBindings.WARN.equals(handlerChanges) && !HandlerBindings.REFUSE.equals(handlerChanges))
        {
            check.refusal = "handlerChanges takes warn or refuse, not '" + handlerChanges + "'."; //$NON-NLS-1$ //$NON-NLS-2$
            return check;
        }
        check.notes.put(HANDLER_CHANGES, handlerChanges);
        List<String> unbound;
        try
        {
            unbound = HandlerBindings.endangered(layoutText(), before, after);
        }
        catch (IOException e)
        {
            check.refusal = "the container could not be read for its bindings: " + e.getMessage(); //$NON-NLS-1$
            return check;
        }
        if (unbound.isEmpty())
        {
            return check;
        }
        check.notes.put("unboundProcedures", String.join(", ", unbound)); //$NON-NLS-1$ //$NON-NLS-2$
        check.warning = "WARNING: the write removes " + unbound.size() //$NON-NLS-1$
            + " procedure(s) the form still names - in an event binding of its layout or in a " //$NON-NLS-1$
            + "string literal of the module: " + String.join(", ", unbound) //$NON-NLS-1$ //$NON-NLS-2$
            + ". EDT resolves neither reference; the client reports the missing procedure when " //$NON-NLS-1$
            + "the event fires."; //$NON-NLS-1$
        if (HandlerBindings.REFUSE.equals(handlerChanges))
        {
            check.refusal = check.warning + " Keep the procedures, rebind the events in the layout first, or pass " //$NON-NLS-1$
                + "handlerChanges=warn to write regardless."; //$NON-NLS-1$
        }
        return check;
    }

    /**
     * Marks the owner of the form as changed in the baselines of the project's infobases, so
     * the next update carries the container: EDT's incremental update does not map a changed
     * {@code Form.oform} to its owner.
     *
     * @param written whether the container was written
     * @return the delivery statement
     */
    @Override
    public String afterWrite(boolean written)
    {
        return written ? OrdinaryFormDelivery.mark(project, this).statement()
            : "nothing changed, nothing to deliver"; //$NON-NLS-1$
    }

    @Override
    public String validationNote()
    {
        return "not available: EDT builds no model of an ordinary form's module; " //$NON-NLS-1$
            + "the platform checks it at update_database"; //$NON-NLS-1$
    }

    @Override
    public Map<String, String> answerFields()
    {
        return new LinkedHashMap<>();
    }

    /**
     * Writes the two entries of the container: the layout, the module, or both. An entry that
     * changed in the container since {@code observed} was read is refused rather than replaced,
     * and an entry the caller does not name keeps whatever the container holds at the write - an
     * entry of a kind this server does not know included.
     *
     * @param observed the container as the caller read it, or {@code null} when the caller read
     *        none and there is nothing to compare the file with
     * @param formText the layout text as {@link BraceTree#serialize} writes it, or {@code null} to keep
     * @param moduleText the module text, or {@code null} to keep
     * @return whether anything was written
     * @throws IOException when the container cannot be read or written, no longer holds an
     *         entry this write would replace, or is held by an editor with unsaved changes
     */
    public boolean writeEntries(OrdinaryFormFile observed, String formText, String moduleText) throws IOException
    {
        OrdinaryFormFile file = read();
        boolean changed = false;
        if (formText != null && !formText.equals(file.formText()))
        {
            if (observed != null && !Objects.equals(observed.observedFormText(), file.formText()))
            {
                throw new IOException("the container changed after it was read: the layout entry is not " //$NON-NLS-1$
                    + "the one this edit was computed from. Read the form again and repeat the edit."); //$NON-NLS-1$
            }
            file.setFormText(formText);
            changed = true;
        }
        if (moduleText != null && !moduleText.equals(file.moduleText()))
        {
            if (observed != null && !Objects.equals(observed.observedModuleText(), file.moduleText()))
            {
                throw new IOException("the container changed after it was read: the module entry is not " //$NON-NLS-1$
                    + "the one this write was computed from. Read the module again and repeat the write."); //$NON-NLS-1$
            }
            file.setModuleText(moduleText);
            changed = true;
        }
        if (!changed)
        {
            return false;
        }
        refuseWhileAnEditorHoldsUnsavedChanges(container);
        byte[] bytes = file.toBytes();
        try
        {
            if (!container.exists())
            {
                container.refreshLocal(IResource.DEPTH_ZERO, null);
            }
            try (InputStream stream = new ByteArrayInputStream(bytes))
            {
                container.setContents(stream, IResource.FORCE | IResource.KEEP_HISTORY, null);
            }
        }
        catch (CoreException e)
        {
            throw new IOException(e.getMessage(), e);
        }
        remembered(file.moduleText());
        return true;
    }

    /**
     * The body of {@link #write(List)}: the module entry is rewritten from the lines and the
     * container is written when the rewrite differs from what it holds. An empty module over an
     * empty one - no module entry or an empty entry - is not written, so no line break is
     * invented for a module that has nothing to end.
     *
     * @param lines the new module, line separators dropped
     * @return whether the container was written
     * @throws IOException when the container cannot be read, no longer holds the module this
     *         instance read, or is held by an editor with unsaved changes
     * @throws CoreException when the workspace refuses the write
     */
    private boolean writeContainer(List<String> lines) throws IOException, CoreException
    {
        OrdinaryFormFile file = read();
        String current = file.moduleText();
        String delimiter = delimiterOf(current);
        String content = String.join(LineDelimiters.LF, lines);
        if (content.isEmpty() && (current == null || current.isEmpty()))
        {
            return false;
        }
        if (!content.isEmpty() && !content.endsWith(LineDelimiters.LF))
        {
            content = content + LineDelimiters.LF;
        }
        String rewritten = LineDelimiters.rewrite(content, delimiter);
        if (rewritten.equals(current))
        {
            return false;
        }
        if (hasBaseline && !Objects.equals(baseline, current))
        {
            throw new IOException("the container changed after it was read: the module entry is not " //$NON-NLS-1$
                + "the one this write was computed from. Read the module again and repeat the write."); //$NON-NLS-1$
        }
        refuseWhileAnEditorHoldsUnsavedChanges(container);
        file.setModuleText(rewritten);
        byte[] bytes = file.toBytes();
        if (!container.exists())
        {
            container.refreshLocal(IResource.DEPTH_ZERO, null);
        }
        try (InputStream stream = new ByteArrayInputStream(bytes))
        {
            container.setContents(stream, IResource.FORCE | IResource.KEEP_HISTORY, null);
        }
        remembered(rewritten);
        return true;
    }

    /**
     * Refuses replacement of a form container with a connected dirty text buffer.
     *
     * @param container the workspace resource containing the layout and module
     * @throws IOException when the connected editor buffer has unsaved changes
     */
    static void refuseWhileAnEditorHoldsUnsavedChanges(IFile container) throws IOException
    {
        if (EditorBuffer.hasUnsavedChanges(container))
        {
            throw new IOException(container.getProjectRelativePath().toPortableString()
                + ": this form container is open in an editor with unsaved changes. " //$NON-NLS-1$
                + "Save or revert the editor and repeat the change. Nothing was written."); //$NON-NLS-1$
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
