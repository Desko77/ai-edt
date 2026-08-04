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
     * Minimal {@code Module.bsl} payload - empty content. EDT accepts a
     * zero-byte module file but a trailing newline keeps file editors and
     * git happy.
     */
    private static final String EMPTY_MODULE_CONTENT = "\n"; //$NON-NLS-1$

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
            return "Cannot resolve form directory for " + ownerFqn //$NON-NLS-1$
                + "/Forms/" + formName //$NON-NLS-1$
                + " (project location is not on the local filesystem)"; //$NON-NLS-1$
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
                Files.write(moduleFile, EMPTY_MODULE_CONTENT.getBytes(StandardCharsets.UTF_8));
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
            return "Cannot resolve form directory for " + ownerFqn //$NON-NLS-1$
                + "/Forms/" + formName //$NON-NLS-1$
                + " (project location is not on the local filesystem)"; //$NON-NLS-1$
        }
        Path moduleFile = formDir.resolve("Module.bsl"); //$NON-NLS-1$
        try
        {
            Files.createDirectories(formDir);
            if (!Files.exists(moduleFile))
            {
                Files.write(moduleFile, EMPTY_MODULE_CONTENT.getBytes(StandardCharsets.UTF_8));
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
     * Resolves the on-disk form directory based on the owner FQN.
     */
    private static Path resolveFormDir(IProject project, String ownerFqn, String formName)
    {
        if (project.getLocation() == null)
        {
            return null;
        }
        Path projectRoot = project.getLocation().toFile().toPath();
        String[] parts = ownerFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length != 2 || parts[1].isEmpty())
        {
            return null;
        }
        if ("CommonForm".equals(parts[0]) || "ОбщаяФорма".equals(parts[0])) //$NON-NLS-1$ //$NON-NLS-2$
        {
            // CommonForm.X is a top-level metadata; the disk folder is the
            // CommonForm itself, not a wrapper Forms subfolder. The owner
            // name (parts[1]) IS the form name in this case.
            return projectRoot.resolve("src").resolve("CommonForms").resolve(parts[1]); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String typePlural = englishTypePlural(parts[0]);
        return projectRoot.resolve("src").resolve(typePlural).resolve(parts[1]) //$NON-NLS-1$
            .resolve("Forms").resolve(formName); //$NON-NLS-1$
    }

    /**
     * Locates the form folder as an Eclipse {@link IFolder} so we can
     * call {@code refreshLocal} on it. Returns null when the layout cannot
     * be matched (caller falls back to project-level refresh).
     */
    private static IFolder locateFormFolder(IProject project, String ownerFqn, String formName)
    {
        String[] parts = ownerFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length != 2 || parts[1].isEmpty())
        {
            return null;
        }
        if ("CommonForm".equals(parts[0]) || "ОбщаяФорма".equals(parts[0])) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return project.getFolder("src").getFolder("CommonForms").getFolder(parts[1]); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return project.getFolder("src") //$NON-NLS-1$
            .getFolder(englishTypePlural(parts[0]))
            .getFolder(parts[1])
            .getFolder("Forms") //$NON-NLS-1$
            .getFolder(formName);
    }

    /**
     * Maps an English-singular metadata type prefix to the plural folder
     * name EDT uses on disk. Falls back to {@code prefix + "s"} when no
     * special case applies. Mirrors the table in
     * {@code BmTemplateHelper.englishTypePlural} - kept local to avoid a
     * cross-helper coupling for a private helper.
     */
    private static String englishTypePlural(String typePrefix)
    {
        if (typePrefix == null || typePrefix.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        switch (typePrefix)
        {
            case "Catalog": //$NON-NLS-1$
                return "Catalogs"; //$NON-NLS-1$
            case "Document": //$NON-NLS-1$
                return "Documents"; //$NON-NLS-1$
            case "DataProcessor": //$NON-NLS-1$
                return "DataProcessors"; //$NON-NLS-1$
            case "Report": //$NON-NLS-1$
                return "Reports"; //$NON-NLS-1$
            case "ChartOfAccounts": //$NON-NLS-1$
                return "ChartsOfAccounts"; //$NON-NLS-1$
            case "ChartOfCalculationTypes": //$NON-NLS-1$
                return "ChartsOfCalculationTypes"; //$NON-NLS-1$
            case "ChartOfCharacteristicTypes": //$NON-NLS-1$
                return "ChartsOfCharacteristicTypes"; //$NON-NLS-1$
            case "BusinessProcess": //$NON-NLS-1$
                return "BusinessProcesses"; //$NON-NLS-1$
            case "ExchangePlan": //$NON-NLS-1$
                return "ExchangePlans"; //$NON-NLS-1$
            case "InformationRegister": //$NON-NLS-1$
                return "InformationRegisters"; //$NON-NLS-1$
            case "AccumulationRegister": //$NON-NLS-1$
                return "AccumulationRegisters"; //$NON-NLS-1$
            case "AccountingRegister": //$NON-NLS-1$
                return "AccountingRegisters"; //$NON-NLS-1$
            case "CalculationRegister": //$NON-NLS-1$
                return "CalculationRegisters"; //$NON-NLS-1$
            case "Task": //$NON-NLS-1$
                return "Tasks"; //$NON-NLS-1$
            case "Enum": //$NON-NLS-1$
            case "Enumeration": //$NON-NLS-1$
                return "Enums"; //$NON-NLS-1$
            case "ExternalDataProcessor": //$NON-NLS-1$
                // An empty folder here used to collapse the path segment, so the module landed in
                // src/<Name>/ while EDT itself writes the .mdo and the form to
                // src/ExternalDataProcessors/<Name>/ - the object ended up split across two places.
                return "ExternalDataProcessors"; //$NON-NLS-1$
            case "ExternalReport": //$NON-NLS-1$
                return "ExternalReports"; //$NON-NLS-1$
            default:
                return typePrefix + "s"; //$NON-NLS-1$
        }
    }
}
