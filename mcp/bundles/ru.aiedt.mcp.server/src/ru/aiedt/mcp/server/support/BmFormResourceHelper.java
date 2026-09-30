/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import ru.aiedt.mcp.server.Activator;

/**
 * 1.42.5 BUG-1424-B: writes the {@code Form.form} (empty form XML) and the
 * {@code Module.bsl} (empty BSL module) files for a freshly-created form on
 * disk after the BM transaction commits.
 *
 * <p>Without this helper, {@code edit_metadata create_form} only updates the
 * owner's {@code .mdo} with a {@code <forms><name>X</name></forms>} reference
 * and attaches the inner Form as a BM top-object - but no resource files are
 * created. Subsequent operations ({@code get_form_structure},
 * {@code edit_form add_field}, {@code get_form_screenshot}) all fail because
 * the form is not discoverable on disk and the BM index has no resource
 * backing for the inner Form top-object.
 *
 * <p>Mirrors the pattern in {@link BmTemplateHelper#writeEmptyMxlxFile}: the
 * writer runs as a post-commit step in {@code opCreateForm}, writes minimal
 * but well-formed files, then triggers {@code IFolder.refreshLocal} so EDT's
 * validator picks up the new resources on the next pass.
 *
 * <p>Path resolution:
 * <ul>
 *   <li>Object-owned forms (Catalog.X, Document.Y, ...):
 *       {@code <project>/src/<TypePlural>/<OwnerName>/Forms/<FormName>/}</li>
 *   <li>CommonForm.X: {@code <project>/src/CommonForms/<FormName>/}
 *       (the form is itself a top-level metadata - no extra Forms/ wrapper)</li>
 * </ul>
 */
public final class BmFormResourceHelper
{
    /**
     * Minimal empty {@code Form.form} payload. Matches what EDT writes when
     * the user creates an empty form through the editor: a self-closing
     * {@code <form:Form>} root with the three standard namespace declarations.
     * Subsequent {@code edit_metadata add_field / add_button / add_group} ops
     * append items below this root through the EMF model, after which EDT
     * re-serializes the file.
     */
    private static final String EMPTY_FORM_CONTENT =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<form:Form xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"" //$NON-NLS-1$
            + " xmlns:core=\"http://g5.1c.ru/v8/dt/mcore\"" //$NON-NLS-1$
            + " xmlns:form=\"http://g5.1c.ru/v8/dt/form\"/>\n"; //$NON-NLS-1$

    /**
     * Minimal {@code Module.bsl} payload. EDT accepts a zero-byte module file, but a trailing line
     * break keeps file editors and git happy.
     * <p>
     * A placeholder module is one line break, and which break it is matters more than its size: a
     * later write copies whatever ending the file already has, so this seeds the form every write
     * after it takes. See {@link LineDelimiters}.
     * </p>
     *
     * @param project the project the form belongs to
     * @return the placeholder contents
     */
    private static byte[] emptyModuleContent(IProject project)
    {
        return LineDelimiters.forNewContent(project).getBytes(StandardCharsets.UTF_8);
    }

    private BmFormResourceHelper()
    {
        // utility
    }

    /**
     * Writes empty {@code Form.form} and {@code Module.bsl} files for the
     * form identified by {@code ownerFqn / formName} into the project's
     * src folder, then refreshes the workspace folder so EDT picks up the
     * new files. Existing files are left untouched (the user / EDT may
     * have populated them since a previous create_form call).
     *
     * @param project   the EDT project (must be open)
     * @param ownerFqn  FQN of the owning metadata object
     *                  (e.g. {@code Catalog.Products}, {@code CommonForm.X}).
     *                  For CommonForm the {@code formName} parameter is
     *                  typically equal to the FQN tail.
     * @param formName  name of the form (the folder name on disk)
     * @return null on success or a descriptive error string on failure
     *     (the caller surfaces it as a tag without aborting the operation,
     *     since the BM-level commit has already succeeded)
     */
    public static String writeEmptyFormResources(IProject project, String ownerFqn,
        String formName)
    {
        if (project == null || ownerFqn == null || formName == null
            || ownerFqn.isEmpty() || formName.isEmpty())
        {
            return "project, ownerFqn and formName are required"; //$NON-NLS-1$
        }
        Path formDir = resolveFormDir(project, ownerFqn, formName);
        if (formDir == null)
        {
            return "Cannot resolve form directory: " //$NON-NLS-1$
                + unresolvableReason(project, ownerFqn, formName);
        }
        Path formFile = formDir.resolve("Form.form"); //$NON-NLS-1$
        Path moduleFile = formDir.resolve("Module.bsl"); //$NON-NLS-1$
        try
        {
            Files.createDirectories(formDir);
            if (!Files.exists(formFile))
            {
                Files.write(formFile, EMPTY_FORM_CONTENT.getBytes(StandardCharsets.UTF_8));
            }
            if (!Files.exists(moduleFile))
            {
                Files.write(moduleFile, emptyModuleContent(project));
            }
        }
        catch (IOException ioe)
        {
            return "Failed to write Form.form / Module.bsl: " + ioe.getMessage(); //$NON-NLS-1$
        }
        // Refresh the form folder so EDT discovers the new files. Without
        // this the validator and BM index keep using the previous (missing)
        // state until the user manually refreshes.
        try
        {
            IFolder folder = locateFormFolder(project, ownerFqn, formName);
            if (folder != null)
            {
                folder.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
            else
            {
                project.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
        }
        catch (CoreException ce)
        {
            Activator.logWarning("Form.form / Module.bsl written but workspace " //$NON-NLS-1$
                + "refresh failed: " + ce.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Ensures only {@code Module.bsl} exists for the form (used by the
     * {@code create_form} generator path). The {@code Form.form} is NOT written
     * here because the EDT form generator already produced a populated inner
     * form, which {@code forceExport} serializes to {@code Form.form} - an empty
     * stub would clobber it. An existing {@code Module.bsl} is left untouched.
     *
     * @param project   the EDT project (must be open)
     * @param ownerFqn  FQN of the owning metadata object
     * @param formName  name of the form (the folder name on disk)
     * @return null on success or a descriptive error string on failure
     */
    public static String writeModuleResourceOnly(IProject project, String ownerFqn,
        String formName)
    {
        if (project == null || ownerFqn == null || formName == null
            || ownerFqn.isEmpty() || formName.isEmpty())
        {
            return "project, ownerFqn and formName are required"; //$NON-NLS-1$
        }
        Path formDir = resolveFormDir(project, ownerFqn, formName);
        if (formDir == null)
        {
            return "Cannot resolve form directory: " //$NON-NLS-1$
                + unresolvableReason(project, ownerFqn, formName);
        }
        Path moduleFile = formDir.resolve("Module.bsl"); //$NON-NLS-1$
        try
        {
            Files.createDirectories(formDir);
            if (!Files.exists(moduleFile))
            {
                Files.write(moduleFile, emptyModuleContent(project));
            }
        }
        catch (IOException ioe)
        {
            return "Failed to write Module.bsl: " + ioe.getMessage(); //$NON-NLS-1$
        }
        // Refresh so EDT discovers the generated Form.form (written by
        // forceExport) and the Module.bsl together.
        try
        {
            IFolder folder = locateFormFolder(project, ownerFqn, formName);
            if (folder != null)
            {
                folder.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
            else
            {
                project.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
        }
        catch (CoreException ce)
        {
            Activator.logWarning("Module.bsl written but workspace refresh failed: " //$NON-NLS-1$
                + ce.getMessage());
        }
        return null;
    }

    /**
     * The form's folder relative to the project root, or {@code null} when the owner FQN or the form
     * name cannot address one.
     * <p>
     * The owner name and the form name are single path elements, and are checked as such before
     * anything is joined: a name carrying a separator or standing for a parent directory would place
     * the form's files outside the folder it names - measured on a live project, a
     * {@code formName} of {@code ../TraversalProbe} wrote {@code Form.form} and {@code Module.bsl}
     * outside {@code Forms/} and the object stopped resolving. The type folder comes from
     * {@link MetadataTypeCatalog}, the one place a metadata type is spelled out, so an owner whose
     * type nobody recognizes is refused rather than pointed at a folder that exists nowhere.
     * </p>
     *
     * @param ownerFqn FQN of the owning metadata object ({@code Catalog.Products},
     *            {@code CommonForm.MyForm}, ...)
     * @param formName name of the form
     * @return the relative folder, for example {@code src/Catalogs/Products/Forms/MainForm}, or
     *         {@code null} when the two cannot address one
     */
    public static Path formDirRelativePath(String ownerFqn, String formName)
    {
        int dot = ownerFqn == null ? -1 : ownerFqn.indexOf('.');
        if (dot <= 0 || dot == ownerFqn.length() - 1)
        {
            return null;
        }
        MetadataTypeCatalog.MetadataTypeInfo type =
            MetadataTypeCatalog.resolve(ownerFqn.substring(0, dot));
        String ownerName = ownerFqn.substring(dot + 1);
        if (type == null || type.getDirectoryName() == null
            || !MetadataGuards.isPlainName(ownerName) || !MetadataGuards.isPlainName(formName))
        {
            return null;
        }
        Path src = Path.of("src").resolve(type.getDirectoryName()); //$NON-NLS-1$
        if (type == MetadataTypeCatalog.MetadataTypeInfo.COMMON_FORM)
        {
            // A common form is the whole object: src/CommonForms/<Name>, with no Forms level - the
            // shape its .mdo lives in.
            return src.resolve(ownerName);
        }
        return src.resolve(ownerName).resolve("Forms").resolve(formName); //$NON-NLS-1$
    }

    /**
     * Why the owner FQN and the form name do not address a form folder, or {@code null} when they do.
     * Both answer from the same rule as {@link #formDirRelativePath}: this one names the reason, that
     * one builds the path.
     *
     * @param ownerFqn FQN of the owning metadata object
     * @param formName name of the form
     * @return the reason, or {@code null} when the two address a folder
     */
    public static String formDirRefusal(String ownerFqn, String formName)
    {
        if (ownerFqn == null || ownerFqn.isEmpty())
        {
            return "ownerFqn is required"; //$NON-NLS-1$
        }
        int dot = ownerFqn.indexOf('.');
        if (dot <= 0 || dot == ownerFqn.length() - 1)
        {
            return "ownerFqn must be <Type>.<Name>, got '" + ownerFqn + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String typePrefix = ownerFqn.substring(0, dot);
        MetadataTypeCatalog.MetadataTypeInfo type = MetadataTypeCatalog.resolve(typePrefix);
        if (type == null || type.getDirectoryName() == null)
        {
            return "ownerFqn names no metadata type that owns forms: '" + typePrefix + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!MetadataGuards.isPlainName(ownerFqn.substring(dot + 1)))
        {
            return "the owner name must be a plain name, got '" + ownerFqn.substring(dot + 1) //$NON-NLS-1$
                + "'"; //$NON-NLS-1$
        }
        if (!MetadataGuards.isPlainName(formName))
        {
            return "the form name must be a plain name, got '" + formName + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Whether the form's {@code Form.form} is on disk.
     *
     * @param project the EDT project
     * @param ownerFqn the owner's FQN
     * @param formName the form's name
     * @return {@code true} when the file exists; {@code false} when it does not or the form
     *     directory does not resolve
     */
    public static boolean formFileExists(IProject project, String ownerFqn, String formName)
    {
        if (project == null || ownerFqn == null || formName == null)
        {
            return false;
        }
        Path formDir = resolveFormDir(project, ownerFqn, formName);
        return formDir != null && Files.exists(formDir.resolve("Form.form")); //$NON-NLS-1$
    }

    /**
     * Resolves the on-disk form directory based on the owner FQN. The result is normalized and
     * checked to be inside the project: a path that would land outside the project the caller named
     * is not resolved at all.
     */
    private static Path resolveFormDir(IProject project, String ownerFqn, String formName)
    {
        if (project.getLocation() == null)
        {
            return null;
        }
        Path relative = formDirRelativePath(ownerFqn, formName);
        if (relative == null)
        {
            return null;
        }
        Path projectRoot = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
        Path resolved = projectRoot.resolve(relative).normalize();
        return resolved.startsWith(projectRoot) ? resolved : null;
    }

    /**
     * Why the form directory could not be resolved: the owner or the name does not address one, or -
     * when both do - the project has no location on the local filesystem.
     *
     * @param project the EDT project
     * @param ownerFqn the owner's FQN
     * @param formName the form's name
     * @return the reason, for the caller's error message
     */
    private static String unresolvableReason(IProject project, String ownerFqn, String formName)
    {
        String refusal = formDirRefusal(ownerFqn, formName);
        if (refusal != null)
        {
            return refusal;
        }
        return project == null || project.getLocation() == null
            ? "the project has no location on the local filesystem" //$NON-NLS-1$
            : "the form path leaves the project"; //$NON-NLS-1$
    }

    /**
     * Locates the form folder as an Eclipse {@link IFolder} so we can
     * call {@code refreshLocal} on it. Returns null when the layout cannot
     * be matched (caller falls back to project-level refresh).
     */
    private static IFolder locateFormFolder(IProject project, String ownerFqn, String formName)
    {
        Path relative = formDirRelativePath(ownerFqn, formName);
        if (relative == null)
        {
            return null;
        }
        IFolder folder = project.getFolder(relative.getName(0).toString());
        for (int index = 1; index < relative.getNameCount(); index++)
        {
            folder = folder.getFolder(relative.getName(index).toString());
        }
        return folder;
    }
}
