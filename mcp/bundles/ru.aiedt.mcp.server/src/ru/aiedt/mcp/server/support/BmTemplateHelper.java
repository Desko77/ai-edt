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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Point;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Drawing;
import com._1c.g5.v8.dt.moxel.Format;
import com._1c.g5.v8.dt.moxel.Merge;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.Rect;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.SpreadsheetPoint;
import com._1c.g5.v8.dt.moxel.SpreadsheetRect;
import com._1c.g5.v8.dt.moxel.TextDrawing;
import com._1c.g5.v8.dt.moxel.content.ContentFactory;
import com._1c.g5.v8.dt.moxel.content.LocalString;

import ru.aiedt.mcp.server.Activator;

/**
 * MXL spreadsheet template operations for {@code mxl_workshop}.
 * <p>
 * <b>1.37:</b> probe expanded to multiple EDT layout APIs (ITemplateLayout
 * service + SpreadsheetDocument). {@code create_template} writes a Template
 * MdObject with {@code templateType=SpreadsheetDocument} via
 * {@link BmObjectHelper}. Cell-level mutation (set_cell / merge_cells / draw)
 * relies on the layout service when reachable; otherwise the tool returns a
 * structured error tag {@code mxlApiNotFound} so the AI agent can decide to
 * fall back to GUI workflow.
 */
public final class BmTemplateHelper
{
    private static final String[] CANDIDATE_PACKAGES = {
        // EDT 2025.1+ canonical: com._1c.g5.v8.dt.moxel ("moxel" = MXL spreadsheet model)
        "com._1c.g5.v8.dt.moxel.SpreadsheetDocument", //$NON-NLS-1$
        // Legacy/alternative names probed for older runtimes
        "com._1c.g5.v8.dt.spreadsheet.model.SpreadsheetDocument", //$NON-NLS-1$
        "com._1c.g5.v8.dt.template.model.SpreadsheetDocument", //$NON-NLS-1$
        "com._1c.g5.v8.dt.md.SpreadsheetDocument" //$NON-NLS-1$
    };

    private static final String[] CANDIDATE_FACTORIES = {
        "com._1c.g5.v8.dt.moxel.MoxelFactory", //$NON-NLS-1$
        "com._1c.g5.v8.dt.spreadsheet.model.SpreadsheetFactory", //$NON-NLS-1$
        "com._1c.g5.v8.dt.template.model.TemplateFactory" //$NON-NLS-1$
    };

    private static final String[] CANDIDATE_LAYOUT_SERVICES = {
        "com._1c.g5.v8.dt.form.layout.service.ITemplateLayoutService", //$NON-NLS-1$
        "com._1c.g5.v8.dt.spreadsheet.layout.ISpreadsheetLayoutService", //$NON-NLS-1$
        "com._1c.g5.v8.dt.template.layout.ITemplateLayoutService" //$NON-NLS-1$
    };

    private static volatile String cachedClassName;
    private static volatile String cachedFactoryName;
    private static volatile String cachedLayoutServiceName;
    private static volatile Boolean cachedProbed;

    private BmTemplateHelper()
    {
        // utility class
    }

    /**
     * Returns the resolved spreadsheet-document class name for this EDT runtime,
     * or {@code null} when none of the candidates resolve. Result cached.
     */
    public static String resolvedSpreadsheetClass()
    {
        ensureProbed();
        return cachedClassName;
    }

    /**
     * Returns the resolved spreadsheet/template factory class name, or
     * {@code null} when not present.
     */
    public static String resolvedFactoryClass()
    {
        ensureProbed();
        return cachedFactoryName;
    }

    /**
     * Returns the resolved layout-service interface name, or {@code null}.
     */
    public static String resolvedLayoutServiceClass()
    {
        ensureProbed();
        return cachedLayoutServiceName;
    }

    private static void ensureProbed()
    {
        if (cachedProbed != null)
        {
            return;
        }
        synchronized (BmTemplateHelper.class)
        {
            if (cachedProbed != null)
            {
                return;
            }
            cachedClassName = resolveFirst(CANDIDATE_PACKAGES);
            cachedFactoryName = resolveFirst(CANDIDATE_FACTORIES);
            cachedLayoutServiceName = resolveFirst(CANDIDATE_LAYOUT_SERVICES);
            cachedProbed = Boolean.TRUE;
            if (cachedClassName == null)
            {
                Activator.logWarning(
                    "BmTemplateHelper: spreadsheet-model class not found in any candidate package"); //$NON-NLS-1$
            }
        }
    }

    private static String resolveFirst(String[] candidates)
    {
        for (String candidate : candidates)
        {
            try
            {
                Class.forName(candidate);
                return candidate;
            }
            catch (ClassNotFoundException ignored)
            {
                // try next
            }
        }
        return null;
    }

    public static boolean isAvailable()
    {
        return resolvedSpreadsheetClass() != null;
    }

    public static String deferredMessage(String operation)
    {
        String resolved = resolvedSpreadsheetClass();
        return "Template operation '" + operation //$NON-NLS-1$
            + "' is not yet implemented in this build. " //$NON-NLS-1$
            + "Use the EDT GUI spreadsheet editor for cell-level changes. " //$NON-NLS-1$
            + (resolved != null
                ? "Spreadsheet API discovered: " + resolved //$NON-NLS-1$
                : "Spreadsheet API NOT reachable in this EDT version."); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // 1.40: Template type resolution + cell-level operations
    // -----------------------------------------------------------------------

    /**
     * Maps an English/Russian template-type alias to its canonical EDT enum
     * literal name. Used by {@code addTemplate} when setting Template.templateType.
     */
    public static String canonicalTemplateType(String alias)
    {
        if (alias == null || alias.isEmpty())
        {
            return "SpreadsheetDocument"; // default - matches upstream
        }
        String key = alias.trim().toLowerCase(java.util.Locale.ROOT);
        switch (key)
        {
            case "spreadsheet":
            case "spreadsheetdocument":
            case "табличный":
            case "табличныйдокумент":
                return "SpreadsheetDocument";
            case "text":
            case "textdocument":
            case "текстовый":
            case "текстовыйдокумент":
                return "TextDocument";
            case "dcs":
            case "datacompositionschema":
            case "скд":
            case "схемакомпоновкиданных":
                return "DataCompositionSchema";
            case "appearancetemplate":
            case "datacompositionappearancetemplate":
            case "макетоформления":
                return "DataCompositionAppearanceTemplate";
            case "binarydata":
            case "binary":
            case "двоичныеданные":
                return "BinaryData";
            case "html":
            case "htmldocument":
                return "HTMLDocument";
            case "geographicschema":
            case "geo":
            case "географическая":
            case "географическаясхема":
                return "GeographicalSchema";
            case "graphicalschema":
            case "graph":
            case "графическая":
            case "графическаясхема":
                return "GraphicalSchema";
            case "activedocument":
            case "active":
            case "активныйдокумент":
                return "ActiveDocument";
            case "addin":
            case "externalcomponent":
            case "внешняякомпонента":
                return "AddIn";
            default:
                return alias; // pass through, EDT will reject if invalid
        }
    }

    /**
     * Cell-level operations status: {@code true} when the moxel factory and
     * spreadsheet model are reachable on this EDT build.
     *
     * <p>1.42.2: cell ops now use {@code com._1c.g5.v8.dt.moxel} model
     * directly instead of going through ITemplateLayoutService - the model
     * is exported as public API and works without the layout service.
     *
     * <p>The moxel package is imported with {@code resolution:=optional} so
     * the bundle still resolves on EDT runtimes that lack it. We additionally
     * defend against {@link NoClassDefFoundError} at first access: the
     * {@code resolveFirst} probe walks the candidates and returns the first
     * available class name, so a missing moxel package shows up as
     * {@code resolvedSpreadsheetClass() == null}.
     */
    public static boolean cellOpsAvailable()
    {
        if (resolvedSpreadsheetClass() == null)
        {
            return false;
        }
        // Confirm the EFactory singleton is usable - covers the case where
        // the package was advertised by the runtime probe but cannot actually
        // be linked (mismatched bundle wiring, etc.).
        try
        {
            return MoxelFactory.eINSTANCE != null;
        }
        catch (Throwable t) // NoClassDefFoundError, LinkageError, etc.
        {
            Activator.logWarning("BmTemplateHelper.cellOpsAvailable: " //$NON-NLS-1$
                + "MoxelFactory.eINSTANCE access failed: " + t); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Builds an {@code mxlApiNotFound} error tag - graceful fallback when
     * cell-level ops are unreachable.
     */
    public static MetadataGuards.BlockedGuardException mxlApiNotFound(String op)
    {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("operation", op);
        data.put("missingApi", "com._1c.g5.v8.dt.moxel SpreadsheetDocument / MoxelFactory");
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "Cell-level template operation '" + op + "' requires the EDT moxel "
                + "spreadsheet model which is not available on this build.",
            "Open the template in the EDT GUI spreadsheet editor for cell-level changes. "
                + "Headless cell ops require com._1c.g5.v8.dt.moxel package.",
            new MetadataGuards.ErrorTag(ErrorTags.MXL_API_NOT_FOUND.wire(), data)));
    }

    // -----------------------------------------------------------------------
    // 1.42.2: native cell-level operations on moxel SpreadsheetDocument
    // -----------------------------------------------------------------------

    /**
     * Returns the {@link SpreadsheetDocument} attached to the given Template
     * MdObject, creating an empty one (and assigning it via {@code setTemplate})
     * when the slot is empty or holds a non-spreadsheet document.
     *
     * <p>EDT stores the actual content in {@code Template.template} - that
     * field's runtime type depends on the {@code templateType} enum
     * (SpreadsheetDocument / DataCompositionSchema / etc.). This helper
     * narrows to the spreadsheet case.
     *
     * @param template the Template MdObject (must have templateType=SpreadsheetDocument)
     * @return existing or freshly-created SpreadsheetDocument; never null
     */
    public static SpreadsheetDocument getOrCreateSpreadsheet(MdObject template)
    {
        if (template == null)
        {
            throw new IllegalArgumentException("template must not be null"); //$NON-NLS-1$
        }
        // Refuse to touch templates whose type is not SpreadsheetDocument -
        // overwriting a DataCompositionSchema or TextDocument would silently
        // destroy user data.
        Object templateTypeValue;
        try
        {
            java.lang.reflect.Method ttGetter = template.getClass().getMethod("getTemplateType"); //$NON-NLS-1$
            templateTypeValue = ttGetter.invoke(template);
        }
        catch (NoSuchMethodException nsme)
        {
            templateTypeValue = null; // older EDT may not expose templateType getter
        }
        catch (Exception e)
        {
            throw new RuntimeException("Cannot read template.templateType: " //$NON-NLS-1$
                + e.getMessage(), e);
        }
        if (templateTypeValue != null)
        {
            String typeName = templateTypeValue.toString(); // EMF enum literal name
            if (!"SpreadsheetDocument".equals(typeName) //$NON-NLS-1$
                && !"SpreadsheetDocumentTemplate".equals(typeName)) //$NON-NLS-1$
            {
                throw new IllegalStateException("Template '" + template.getName() //$NON-NLS-1$
                    + "' has templateType=" + typeName //$NON-NLS-1$
                    + ". Cell-level operations require templateType=SpreadsheetDocument. " //$NON-NLS-1$
                    + "Refusing to overwrite to prevent silent data loss."); //$NON-NLS-1$
            }
        }
        Object current;
        try
        {
            java.lang.reflect.Method getter = template.getClass().getMethod("getTemplate"); //$NON-NLS-1$
            current = getter.invoke(template);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Template has no getTemplate() method: " //$NON-NLS-1$
                + e.getMessage(), e);
        }
        if (current instanceof SpreadsheetDocument)
        {
            return (SpreadsheetDocument) current;
        }
        // current is null OR a foreign type. With templateType=SpreadsheetDocument
        // confirmed above, a foreign content slot is invalid state. Refuse to
        // overwrite a REAL foreign content model (a DataCompositionSchema /
        // TextDocument is a specific generated EClass Impl and would lose data),
        // but a bare EObject still carrying the root Ecore metaclass (zero
        // structural features - what create_template leaves in the content slot
        // of a freshly created Template) holds nothing, so replace it below with
        // a fresh SpreadsheetDocument. Testing the eClass (not the class name)
        // also excludes a dynamic EObject that impersonated a real EClass. This
        // lets set_cell / merge_cells / read_template work on a just-created
        // template without a manual EDT GUI open-and-save first.
        if (current != null
            && !(current instanceof org.eclipse.emf.ecore.EObject
                && ((org.eclipse.emf.ecore.EObject) current).eClass()
                    == org.eclipse.emf.ecore.EcorePackage.Literals.EOBJECT))
        {
            throw new IllegalStateException("Template '" + template.getName() //$NON-NLS-1$
                + "' has templateType=SpreadsheetDocument but the content slot " //$NON-NLS-1$
                + "holds " + current.getClass().getName() //$NON-NLS-1$
                + ". Refusing to overwrite. Open in EDT GUI to repair."); //$NON-NLS-1$
        }
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        try
        {
            // Setter accepts EObject (the slot is a generic content slot).
            java.lang.reflect.Method setter = template.getClass().getMethod("setTemplate", //$NON-NLS-1$
                org.eclipse.emf.ecore.EObject.class);
            setter.invoke(template, doc);
        }
        catch (NoSuchMethodException nsme)
        {
            // Fall back to setProperty reflection: template field on EDT Template
            // is sometimes typed as the concrete superinterface.
            String err = BmObjectHelper.setProperty(template, "template", doc); //$NON-NLS-1$
            if (err != null)
            {
                throw new RuntimeException("Cannot attach SpreadsheetDocument to Template: " //$NON-NLS-1$
                    + err);
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Cannot attach SpreadsheetDocument to Template: " //$NON-NLS-1$
                + e.getMessage(), e);
        }
        return doc;
    }

    /**
     * Initializes the {@code template} content slot with a fresh content
     * object matching {@code canonicalType}. Currently supports only
     * SpreadsheetDocument (other template types return without action - their
     * content slots are populated by EDT validators / editors).
     *
     * <p>Background: a freshly created Template object has a bare
     * {@code EObjectImpl} in the {@code template} (content) slot due to the
     * EMF containment default. Subsequent {@code mxl_workshop} operations
     * (set_cell / merge_cells / draw) call {@link #getOrCreateSpreadsheet}
     * which refuses to overwrite a non-null foreign slot. Without this
     * initialization the user has to repair the template through the EDT GUI.
     * Calling this right after {@code addTemplate} ensures the slot holds a
     * valid SpreadsheetDocument from the start.
     *
     * @return null on success or skip; an error string when the spreadsheet
     *     could not be attached to the template.
     */
    public static String initContentForType(MdObject template, String canonicalType)
    {
        if (template == null || canonicalType == null)
        {
            return null;
        }
        if (!"SpreadsheetDocument".equals(canonicalType) //$NON-NLS-1$
            && !"SpreadsheetDocumentTemplate".equals(canonicalType)) //$NON-NLS-1$
        {
            return null;
        }
        SpreadsheetDocument doc;
        try
        {
            doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        }
        catch (Throwable t)
        {
            return "MoxelFactory.createSpreadsheetDocument threw: " + t.getMessage(); //$NON-NLS-1$
        }
        try
        {
            java.lang.reflect.Method setter = template.getClass().getMethod("setTemplate", //$NON-NLS-1$
                org.eclipse.emf.ecore.EObject.class);
            setter.invoke(template, doc);
            return null;
        }
        catch (NoSuchMethodException nsme)
        {
            String err = BmObjectHelper.setProperty(template, "template", doc); //$NON-NLS-1$
            if (err != null)
            {
                return "setProperty fallback: " + err; //$NON-NLS-1$
            }
            return null;
        }
        catch (Exception e)
        {
            return e.getMessage();
        }
    }

    /**
     * Minimal empty SpreadsheetDocument as an MXLX XML payload. EDT writes
     * this exact shape into Template.mxlx files for fresh empty templates
     * (compare with src/CommonForms/.../SpreadsheetData.mxlx in any sample
     * configuration). We embed the literal XML rather than going through
     * the moxel EMF model + Resource API because moxel content slots are
     * non-containment EReferences in the BM transactional context and we
     * cannot create a sibling Resource inside the BM write task.
     */
    private static final String EMPTY_MXLX_CONTENT =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<document xmlns=\"http://v8.1c.ru/8.2/data/spreadsheet\"" //$NON-NLS-1$
            + " xmlns:style=\"http://v8.1c.ru/8.1/data/ui/style\"" //$NON-NLS-1$
            + " xmlns:v8=\"http://v8.1c.ru/8.1/data/core\"" //$NON-NLS-1$
            + " xmlns:v8ui=\"http://v8.1c.ru/8.1/data/ui\"" //$NON-NLS-1$
            + " xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"" //$NON-NLS-1$
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n" //$NON-NLS-1$
            + "\t<columns>\n" //$NON-NLS-1$
            + "\t\t<size>0</size>\n" //$NON-NLS-1$
            + "\t</columns>\n" //$NON-NLS-1$
            + "\t<rowsItem>\n" //$NON-NLS-1$
            + "\t\t<index>0</index>\n" //$NON-NLS-1$
            + "\t\t<row>\n" //$NON-NLS-1$
            + "\t\t\t<empty>true</empty>\n" //$NON-NLS-1$
            + "\t\t</row>\n" //$NON-NLS-1$
            + "\t</rowsItem>\n" //$NON-NLS-1$
            + "\t<vgRows>0</vgRows>\n" //$NON-NLS-1$
            + "</document>"; //$NON-NLS-1$

    /**
     * Writes an empty Template.mxlx file next to the template's .mdo so EDT
     * can populate the content slot on next validation. Bypasses the BM
     * non-containment EReference issue (BasicTemplate.template is a
     * non-containment ref - directly attaching SpreadsheetDocumentImpl in
     * the same transaction crashes commit with "Failed to persist reference
     * value SpreadsheetDocumentImpl@..."). Returns null on success or a
     * descriptive error string on failure.
     *
     * @param project       the EDT project (must be open)
     * @param ownerFqn      FQN of the owning metadata object
     *                      (Catalog.X / Document.X / DataProcessor.X)
     * @param templateName  name of the template (the folder name on disk)
     * @param canonicalType canonical template type (only SpreadsheetDocument
     *                      is handled; other types return null without action)
     */
    public static String writeEmptyMxlxFile(IProject project, String ownerFqn,
        String templateName, String canonicalType)
    {
        if (project == null || ownerFqn == null || templateName == null
            || canonicalType == null)
        {
            return "project, ownerFqn, templateName and canonicalType are required"; //$NON-NLS-1$
        }
        if (!"SpreadsheetDocument".equals(canonicalType)) //$NON-NLS-1$
        {
            // Other template types (BinaryData / TextDocument / DCS / ...)
            // use different file formats which we don't auto-initialize.
            return null;
        }
        Path templateDir = resolveTemplateDir(project, ownerFqn, templateName);
        if (templateDir == null)
        {
            return "Cannot resolve template directory for " + ownerFqn //$NON-NLS-1$
                + "/Templates/" + templateName //$NON-NLS-1$
                + " (project location is not on the local filesystem)"; //$NON-NLS-1$
        }
        Path mxlxFile = templateDir.resolve("Template.mxlx"); //$NON-NLS-1$
        if (Files.exists(mxlxFile))
        {
            // Don't overwrite an existing file - the user / EDT may have
            // populated it after a previous create_template call.
            return null;
        }
        try
        {
            Files.createDirectories(templateDir);
            Files.write(mxlxFile, EMPTY_MXLX_CONTENT.getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException ioe)
        {
            return "Failed to write Template.mxlx: " + ioe.getMessage(); //$NON-NLS-1$
        }
        // Refresh the template folder in the workspace so EDT picks up the
        // new file. Without this the validator and BM index keep using the
        // old (missing) state until the user manually refreshes.
        try
        {
            IFolder folder = locateTemplateFolder(project, ownerFqn, templateName);
            if (folder != null)
            {
                folder.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
            else
            {
                // Fall back to project-level refresh if we couldn't locate
                // the specific folder (rare but possible for unusual owners).
                project.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
        }
        catch (CoreException ce)
        {
            Activator.logWarning("Template.mxlx written but workspace refresh failed: " //$NON-NLS-1$
                + ce.getMessage());
        }
        return null;
    }

    /**
     * Result of {@link #readTextTemplateContent}: the content text and the file
     * it came from, or an {@code error} when the content could not be read.
     */
    public static final class TemplateContent
    {
        public String content;
        public String fileName;
        public String error;
    }

    /**
     * Maps a canonical template type to the on-disk plain-text content file EDT
     * stores it in. Only the text-based template types are supported here
     * (SpreadsheetDocument -&gt; mxl_workshop, DataCompositionSchema -&gt;
     * dcs_workshop, binary / addin / geo formats are not plain text).
     *
     * @param canonicalType canonical template type (from {@link #canonicalTemplateType})
     * @return the content file name (Template.txt / Template.htmldoc), or
     *         {@code null} for non-text template types.
     */
    public static String templateContentFileName(String canonicalType)
    {
        if ("TextDocument".equals(canonicalType)) //$NON-NLS-1$
        {
            return "Template.txt"; //$NON-NLS-1$
        }
        if ("HTMLDocument".equals(canonicalType)) //$NON-NLS-1$
        {
            return "Template.htmldoc"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Returns the name of the first on-disk {@code Template.*} content file for a
     * template (txt / htmldoc / mxlx / dcs / dcsat / bin / addin / geo / mxl), or
     * {@code null} when the folder is missing or has no recognized content file.
     * Lets {@code set_template_content} detect a template's actual kind from disk
     * and refuse to write text into a non-text (spreadsheet / DCS / binary)
     * template or a mistyped name, instead of blindly defaulting to Template.txt.
     */
    public static String existingContentFileName(IProject project, String ownerFqn, String templateName)
    {
        if (project == null || ownerFqn == null || templateName == null)
        {
            return null;
        }
        Path dir = resolveTemplateDir(project, ownerFqn, templateName);
        if (dir == null || !Files.isDirectory(dir))
        {
            return null;
        }
        for (String candidate : new String[] { "Template.txt", "Template.htmldoc", //$NON-NLS-1$ //$NON-NLS-2$
            "Template.mxlx", "Template.dcs", "Template.dcsat", "Template.bin", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Template.addin", "Template.geo", "Template.mxl" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            if (Files.exists(dir.resolve(candidate)))
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Writes plain-text content to a TextDocument (Template.txt) or HTMLDocument
     * (Template.htmldoc) template file next to its .mdo, creating the folder and
     * refreshing the workspace so EDT picks it up. Overwrites any existing
     * content (the caller decides whether that is a create or a replace). Mirrors
     * {@link #writeEmptyMxlxFile} for the text formats.
     *
     * @param content the text to write (a {@code null} is treated as empty).
     * @return {@code null} on success, an error description otherwise.
     */
    public static String writeTextTemplateContent(IProject project, String ownerFqn,
        String templateName, String canonicalType, String content)
    {
        if (project == null || ownerFqn == null || templateName == null || canonicalType == null)
        {
            return "project, ownerFqn, templateName and canonicalType are required"; //$NON-NLS-1$
        }
        String fileName = templateContentFileName(canonicalType);
        if (fileName == null)
        {
            return "Template content text I/O supports only TextDocument (Template.txt) and " //$NON-NLS-1$
                + "HTMLDocument (Template.htmldoc); got '" + canonicalType //$NON-NLS-1$
                + "'. Use mxl_workshop for SpreadsheetDocument, dcs_workshop for DataCompositionSchema."; //$NON-NLS-1$
        }
        Path templateDir = resolveTemplateDir(project, ownerFqn, templateName);
        if (templateDir == null)
        {
            return "Cannot resolve template directory for " + ownerFqn //$NON-NLS-1$
                + "/Templates/" + templateName //$NON-NLS-1$
                + " (project location is not on the local filesystem)"; //$NON-NLS-1$
        }
        try
        {
            Files.createDirectories(templateDir);
            Files.write(templateDir.resolve(fileName),
                (content != null ? content : "").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        }
        catch (IOException ioe)
        {
            return "Failed to write " + fileName + ": " + ioe.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        refreshTemplateFolder(project, ownerFqn, templateName);
        return null;
    }

    /**
     * Reads the plain-text content of a TextDocument / HTMLDocument template.
     * Auto-detects the content file (Template.txt then Template.htmldoc). Returns
     * a {@link TemplateContent} with {@code content}+{@code fileName} on success,
     * or {@code error} set when the folder or a text content file is missing.
     */
    public static TemplateContent readTextTemplateContent(IProject project, String ownerFqn,
        String templateName)
    {
        TemplateContent result = new TemplateContent();
        if (project == null || ownerFqn == null || templateName == null)
        {
            result.error = "project, ownerFqn and templateName are required"; //$NON-NLS-1$
            return result;
        }
        Path templateDir = resolveTemplateDir(project, ownerFqn, templateName);
        if (templateDir == null)
        {
            result.error = "Cannot resolve template directory for " + ownerFqn //$NON-NLS-1$
                + "/Templates/" + templateName; //$NON-NLS-1$
            return result;
        }
        for (String candidate : new String[] { "Template.txt", "Template.htmldoc" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Path file = templateDir.resolve(candidate);
            if (Files.exists(file))
            {
                try
                {
                    result.content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    result.fileName = candidate;
                    return result;
                }
                catch (IOException ioe)
                {
                    result.error = "Failed to read " + candidate + ": " + ioe.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
                    return result;
                }
            }
        }
        result.error = "No text content file (Template.txt / Template.htmldoc) found for " //$NON-NLS-1$
            + ownerFqn + "/Templates/" + templateName //$NON-NLS-1$
            + " - it may be a non-text template (spreadsheet / DCS / binary) or empty."; //$NON-NLS-1$
        return result;
    }

    /**
     * Refreshes the template's workspace folder (falling back to a project-level
     * refresh) so EDT picks up a freshly written content file. Extracted from the
     * inline refresh in {@link #writeEmptyMxlxFile}.
     */
    private static void refreshTemplateFolder(IProject project, String ownerFqn, String templateName)
    {
        try
        {
            IFolder folder = locateTemplateFolder(project, ownerFqn, templateName);
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
            Activator.logWarning("Template content written but workspace refresh failed: " //$NON-NLS-1$
                + ce.getMessage());
        }
    }

    /**
     * Serializes the in-memory SpreadsheetDocument to {@code Template.mxlx}
     * via the EMF Resource API. Used as a post-mutation step in
     * {@code mxl_workshop set_cell / merge_cells / draw}: without it the
     * cell text only lives in the BM in-memory model and is lost on EDT
     * restart, because {@code BasicTemplate.template} is a non-containment
     * EReference and the BM forceExport pipeline does not cover the
     * external moxel resource.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Look up an EMF Resource factory registered for the {@code mxlx}
     *       extension. EDT's moxel bundle registers one at activation; we
     *       only need it to be reachable from this plugin.</li>
     *   <li>Make a deep {@code EcoreUtil.copy} of the SpreadsheetDocument
     *       so we don't detach it from the BM template while saving.</li>
     *   <li>Create a fresh Resource at the {@code Template.mxlx} URI and
     *       stuff the copy into {@code resource.getContents()}.</li>
     *   <li>{@code resource.save(...)} writes the exact moxel XML format
     *       EDT expects ({@code <document xmlns="http://v8.1c.ru/8.2/data/spreadsheet">}).</li>
     *   <li>Refresh the workspace folder so the validator picks up the
     *       updated file on the next pass.</li>
     * </ol>
     *
     * @return null on success or a descriptive error string on failure
     *     (the caller surfaces it as {@code templateMutationPersistWarning}
     *     so the operation is not aborted).
     */
    public static String persistTemplateMxlx(IProject project, String ownerFqn,
        String templateName, EObject spreadsheetDocument)
    {
        if (project == null || ownerFqn == null || templateName == null
            || spreadsheetDocument == null)
        {
            return "project, ownerFqn, templateName and spreadsheetDocument are required"; //$NON-NLS-1$
        }
        Path templateDir = resolveTemplateDir(project, ownerFqn, templateName);
        if (templateDir == null)
        {
            return "Cannot resolve template directory for " + ownerFqn //$NON-NLS-1$
                + "/Templates/" + templateName; //$NON-NLS-1$
        }
        Path mxlxFile = templateDir.resolve("Template.mxlx"); //$NON-NLS-1$
        URI uri = URI.createFileURI(mxlxFile.toAbsolutePath().toString());
        // 1.43 BUG-5 Part 2: try the global Resource.Factory.Registry first
        // (works in environments where moxel bundle pre-registers the
        // factory). When the global registry has no entry for "mxlx",
        // fall back to instantiating MoxelResourceMxlx directly via
        // reflection. The class extends AbstractXmlResource from
        // com._1c.g5.modeling.xml and ships a default constructor + a
        // URI constructor; the inherited save() emits the moxel-specific
        // XML schema EDT can re-parse.
        Resource resource = null;
        Resource.Factory.Registry registry = Resource.Factory.Registry.INSTANCE;
        Object factoryObj = registry.getExtensionToFactoryMap().get("mxlx"); //$NON-NLS-1$
        ResourceSet rs = new ResourceSetImpl();
        if (factoryObj instanceof Resource.Factory)
        {
            Resource.Factory factory = (Resource.Factory) factoryObj;
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Map<String, Object> rsExtMap = rs.getResourceFactoryRegistry()
                .getExtensionToFactoryMap();
            rsExtMap.put("mxlx", factory); //$NON-NLS-1$
            try
            {
                resource = factory.createResource(uri);
            }
            catch (Exception e)
            {
                Activator.logWarning("MoxelResourceFactory.createResource " //$NON-NLS-1$
                    + "failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        if (resource == null)
        {
            // 1.43 second-pass fallback: pull the Resource.Factory from
            // Eclipse's extension registry. The moxel plugin.xml registers
            // its factory via MoxelRuntimeExecutableExtensionFactory which
            // wires up the Guice injector, so createExecutableExtension()
            // returns a fully-initialised MoxelResourceFactory instance
            // (raw new MoxelResourceFactory() / new MoxelResourceMxlx(URI)
            // leaves the @Inject providers null and save() throws an
            // AssertionFailedException).
            try
            {
                org.eclipse.core.runtime.IConfigurationElement[] elements
                    = org.eclipse.core.runtime.Platform.getExtensionRegistry()
                        .getConfigurationElementsFor("org.eclipse.emf.ecore.extension_parser"); //$NON-NLS-1$
                for (org.eclipse.core.runtime.IConfigurationElement el : elements)
                {
                    if (!"mxlx".equals(el.getAttribute("type"))) //$NON-NLS-1$ //$NON-NLS-2$
                    {
                        continue;
                    }
                    Object factoryFromRegistry = el.createExecutableExtension("class"); //$NON-NLS-1$
                    if (factoryFromRegistry instanceof Resource.Factory)
                    {
                        Resource.Factory injectedFactory
                            = (Resource.Factory) factoryFromRegistry;
                        @SuppressWarnings({ "unchecked", "rawtypes" })
                        Map<String, Object> rsExtMap = rs.getResourceFactoryRegistry()
                            .getExtensionToFactoryMap();
                        rsExtMap.put("mxlx", injectedFactory); //$NON-NLS-1$
                        resource = injectedFactory.createResource(uri);
                        if (resource != null && resource.getResourceSet() == null)
                        {
                            rs.getResources().add(resource);
                        }
                        break;
                    }
                }
            }
            catch (Exception e)
            {
                Activator.logWarning("Eclipse extension registry probe for " //$NON-NLS-1$
                    + "mxlx Resource.Factory failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        if (resource == null)
        {
            return "No EMF Resource factory available for .mxlx " //$NON-NLS-1$
                + "(open the template in EDT GUI and Save All to flush " //$NON-NLS-1$
                + "set_cell/merge_cells/draw mutations to disk)"; //$NON-NLS-1$
        }
        // The moxel serializer builds a SheetAccessor whose constructor asserts
        // dtProject is non-null (SheetAccessor.<init> -> Assert.isNotNull). A
        // bare factory-created MoxelResourceMxlx has no dtProject, so EVERY save
        // (set_cell / merge_cells / draw) of a freshly-created template would
        // fail with "AssertionFailedException: null argument". Resolve the
        // IDtProject and attach it via the public IDtProjectAware interface.
        attachDtProjectToResource(resource, project);
        try
        {
            Files.createDirectories(templateDir);
            // Detach via deep copy so the SpreadsheetDocument in the BM
            // template stays put while we serialize a snapshot.
            EObject snapshot = EcoreUtil.copy(spreadsheetDocument);
            if (resource.getResourceSet() == null)
            {
                rs.getResources().add(resource);
            }
            // Clear first: if a factory hands back a URI-cached resource,
            // re-adding would accumulate multiple snapshots in one document.
            resource.getContents().clear();
            resource.getContents().add(snapshot);
            // Serialize to an in-memory buffer first (MoxelResourceMxlx honours
            // the passed OutputStream). If the moxel serializer throws - e.g. an
            // AssertionFailedException on a model value it expects non-null - the
            // on-disk .mxlx is left intact instead of being truncated to a
            // 0-byte file that EDT then cannot import.
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(8192);
            resource.save(buf, Collections.emptyMap());
            Files.write(mxlxFile, buf.toByteArray());
        }
        catch (Exception e)
        {
            // Log the full stack: an AssertionFailedException from the moxel
            // serializer identifies the offending element only in its trace.
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            Activator.logWarning("persistTemplateMxlx save failed for " //$NON-NLS-1$
                + ownerFqn + "/" + templateName + ":\n" + sw); //$NON-NLS-1$ //$NON-NLS-2$
            return "Failed to save Template.mxlx via EMF Resource: " //$NON-NLS-1$
                + e.getClass().getSimpleName() + ": " + e.getMessage(); //$NON-NLS-1$
        }
        // Refresh workspace so validator sees the updated file.
        try
        {
            IFolder folder = locateTemplateFolder(project, ownerFqn, templateName);
            if (folder != null && folder.exists())
            {
                folder.refreshLocal(IResource.DEPTH_ONE, null);
            }
            else
            {
                project.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
        }
        catch (CoreException ce)
        {
            Activator.logWarning("persistTemplateMxlx: workspace refresh failed: " //$NON-NLS-1$
                + ce.getMessage());
        }
        return null;
    }

    /**
     * Resolves the {@code IDtProject} for the project and attaches it to the
     * moxel resource via the public {@code IDtProjectAware} interface. The
     * moxel serializer's {@code SheetAccessor} constructor asserts dtProject is
     * non-null; a factory-created resource has none. All reflective (no compile
     * dependency on the internal resource type / IDtProject) and best-effort -
     * on failure the save proceeds and surfaces the original assertion.
     */
    private static void attachDtProjectToResource(Resource resource, IProject project)
    {
        try
        {
            org.osgi.framework.BundleContext bc = org.osgi.framework.FrameworkUtil
                .getBundle(BmTemplateHelper.class).getBundleContext();
            if (bc == null)
            {
                return;
            }
            org.osgi.framework.ServiceReference<?> ref =
                bc.getServiceReference("com._1c.g5.v8.dt.core.platform.IBmModelManager"); //$NON-NLS-1$
            if (ref == null)
            {
                return;
            }
            try
            {
                Object manager = bc.getService(ref);
                if (manager == null)
                {
                    return;
                }
                Class<?> mmIface = Class.forName("com._1c.g5.v8.dt.core.platform.IBmModelManager"); //$NON-NLS-1$
                Object dtProject = mmIface.getMethod("getDtProject", String.class) //$NON-NLS-1$
                    .invoke(manager, project.getName());
                if (dtProject == null)
                {
                    Activator.logWarning("attachDtProjectToResource: getDtProject " //$NON-NLS-1$
                        + "returned null for '" + project.getName() //$NON-NLS-1$
                        + "' - the moxel save will fail the SheetAccessor null check"); //$NON-NLS-1$
                    return;
                }
                Class<?> aware = Class.forName("com._1c.g5.v8.dt.core.resource.IDtProjectAware"); //$NON-NLS-1$
                Class<?> idt = Class.forName("com._1c.g5.v8.dt.core.platform.IDtProject"); //$NON-NLS-1$
                if (aware.isInstance(resource))
                {
                    aware.getMethod("setDtProject", idt).invoke(resource, dtProject); //$NON-NLS-1$
                }
            }
            finally
            {
                bc.ungetService(ref);
            }
        }
        catch (Throwable t)
        {
            // Includes ClassNotFoundException when an EDT runtime does not export
            // com._1c.g5.v8.dt.core.resource (the optional import) - t.toString()
            // names the class so the cause is diagnosable from the log.
            Activator.logWarning("attachDtProjectToResource failed: " + t); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the on-disk template directory:
     * {@code <project>/src/<OwnerCollection>/<OwnerName>/Templates/<TemplateName>}
     * for object-owned templates, or
     * {@code <project>/src/CommonTemplates/<TemplateName>} for CommonTemplate.
     */
    private static Path resolveTemplateDir(IProject project, String ownerFqn,
        String templateName)
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
        String typePlural = englishTypePlural(parts[0]);
        String ownerName = parts[1];
        if ("CommonTemplate".equals(parts[0]) || "ОбщийМакет".equals(parts[0])) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return projectRoot.resolve("src").resolve("CommonTemplates").resolve(templateName); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return projectRoot.resolve("src").resolve(typePlural).resolve(ownerName) //$NON-NLS-1$
            .resolve("Templates").resolve(templateName); //$NON-NLS-1$
    }

    /**
     * Locates the template folder as an Eclipse {@link IFolder} so we can
     * call {@code refreshLocal} on it. Returns null when the layout cannot
     * be matched (caller falls back to project-level refresh).
     */
    private static IFolder locateTemplateFolder(IProject project, String ownerFqn,
        String templateName)
    {
        String[] parts = ownerFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length != 2 || parts[1].isEmpty())
        {
            return null;
        }
        if ("CommonTemplate".equals(parts[0]) || "ОбщийМакет".equals(parts[0])) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return project.getFolder("src").getFolder("CommonTemplates").getFolder(templateName); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return project.getFolder("src") //$NON-NLS-1$
            .getFolder(englishTypePlural(parts[0]))
            .getFolder(parts[1])
            .getFolder("Templates") //$NON-NLS-1$
            .getFolder(templateName);
    }

    /**
     * Maps an English-singular metadata type prefix to the plural folder
     * name EDT uses on disk. Falls back to {@code prefix + "s"} when no
     * special case applies.
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
            default:
                return typePrefix + "s"; //$NON-NLS-1$
        }
    }

    /**
     * Sets cell text. Creates the row and the cell when they do not exist yet.
     *
     * <p>Coordinates are 1-based (row 1 column 1 = top-left), matching the
     * 1C platform convention.
     *
     * @param doc the spreadsheet document
     * @param row 1-based row index
     * @param col 1-based column index
     * @param text plain text to put into the cell
     * @param language language tag for the LocalString content map
     *     (e.g. {@code "ru"}, {@code "en"}); when null, defaults to {@code "ru"}
     */
    public static void setCellText(SpreadsheetDocument doc, int row, int col, String text,
        String language)
    {
        if (doc == null)
        {
            throw new IllegalArgumentException("doc must not be null"); //$NON-NLS-1$
        }
        if (row < 1 || col < 1)
        {
            throw new IllegalArgumentException("row and col must be 1-based positive integers"); //$NON-NLS-1$
        }
        // The moxel row/cell EMaps are 0-based (platform-authored templates
        // store the top-left cell at key 0), while this API is 1-based. Convert
        // at the boundary so set_cell(1,1) lands on the physical top-left cell,
        // matching the documented contract and the inverse readSpreadsheet.
        int rowKey = row - 1;
        int colKey = col - 1;
        String lang = (language == null || language.isEmpty()) ? "ru" : language; //$NON-NLS-1$
        EMap<Integer, Row> rows = doc.getRows();
        Row r = rows.get(Integer.valueOf(rowKey));
        if (r == null)
        {
            r = MoxelFactory.eINSTANCE.createRow();
            rows.put(Integer.valueOf(rowKey), r);
        }
        EMap<Integer, Cell> cells = r.getCells();
        Cell c = cells.get(Integer.valueOf(colKey));
        if (c == null)
        {
            c = MoxelFactory.eINSTANCE.createCell();
            cells.put(Integer.valueOf(colKey), c);
        }
        LocalString ls = c.getText();
        if (ls == null)
        {
            ls = ContentFactory.eINSTANCE.createLocalString();
            c.setText(ls);
        }
        ls.getContent().put(lang, text == null ? "" : text); //$NON-NLS-1$
    }

    /**
     * Merges a rectangle of cells. Adds a new {@link Merge} entry to the
     * spreadsheet's merge list with the requested {@link Rect}.
     *
     * <p>Both the {@code from} and {@code to} corners are inclusive.
     * Coordinates are 1-based.
     *
     * <p>Uses {@code Rect}'s X/Y/Width/Height: X=col, Y=row, Width=colSpan,
     * Height=rowSpan - matching the platform's mxl serialization layout.
     */
    public static void mergeCells(SpreadsheetDocument doc, int fromRow, int fromCol, int toRow,
        int toCol)
    {
        if (doc == null)
        {
            throw new IllegalArgumentException("doc must not be null"); //$NON-NLS-1$
        }
        if (fromRow < 1 || fromCol < 1 || toRow < fromRow || toCol < fromCol)
        {
            throw new IllegalArgumentException("invalid merge range: from=(" //$NON-NLS-1$
                + fromRow + "," + fromCol + ") to=(" + toRow + "," + toCol + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        Merge merge = MoxelFactory.eINSTANCE.createMerge();
        Rect rect = MoxelFactory.eINSTANCE.createRect();
        // Rect X=col, Y=row are 0-based in the moxel model (see setCellText);
        // convert the 1-based corners. Width/Height are spans (cell counts),
        // not positions, so they are base-invariant.
        rect.setX(fromCol - 1);
        rect.setY(fromRow - 1);
        rect.setWidth(toCol - fromCol + 1);
        rect.setHeight(toRow - fromRow + 1);
        merge.setPosition(rect);
        doc.getMerges().add(merge);
    }

    /**
     * Reads a {@link SpreadsheetDocument} into a plain map - the inverse of
     * {@link #setCellText} / {@link #mergeCells}. Read-only. The moxel row/cell
     * maps are sparse (only populated rows/cols exist), so {@code rowCount} /
     * {@code colCount} are the maximum populated (or merged) 1-based indices -
     * 0 when the sheet is empty. Indices are reported 1-based (row 1 = the
     * physical top-left), the inverse of {@link #setCellText} /
     * {@link #mergeCells}: the moxel model stores 0-based keys internally and
     * this read adds 1 at the boundary. Populates:
     * <ul>
     * <li>{@code rowCount} / {@code colCount} / {@code cellCount}
     * <li>{@code cells} - array of {@code {row, col, text}} for non-empty cells
     * <li>{@code merges} - array of {@code {fromRow, fromCol, toRow, toCol}} (1-based, inclusive)
     * <li>{@code drawings} - array of {@code {id}}
     * </ul>
     *
     * @param doc the spreadsheet (may be null -&gt; empty result)
     * @param language preferred LocalString language for cell text; when null
     *     the {@code ru} entry then the first non-empty entry is used
     */
    public static Map<String, Object> readSpreadsheet(SpreadsheetDocument doc, String language)
    {
        List<Map<String, Object>> cells = new ArrayList<>();
        List<Map<String, Object>> merges = new ArrayList<>();
        List<Map<String, Object>> drawings = new ArrayList<>();
        int maxRow = 0;
        int maxCol = 0;
        if (doc != null)
        {
            for (Map.Entry<Integer, Row> re : doc.getRows())
            {
                if (re == null || re.getKey() == null || re.getValue() == null)
                {
                    continue;
                }
                int rowIdx = re.getKey().intValue() + 1;
                for (Map.Entry<Integer, Cell> ce : re.getValue().getCells())
                {
                    if (ce == null || ce.getKey() == null || ce.getValue() == null)
                    {
                        continue;
                    }
                    String text = cellText(ce.getValue(), language);
                    if (text == null || text.isEmpty())
                    {
                        continue;
                    }
                    int colIdx = ce.getKey().intValue() + 1;
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("row", Integer.valueOf(rowIdx)); //$NON-NLS-1$
                    cm.put("col", Integer.valueOf(colIdx)); //$NON-NLS-1$
                    cm.put("text", text); //$NON-NLS-1$
                    cells.add(cm);
                    maxRow = Math.max(maxRow, rowIdx);
                    maxCol = Math.max(maxCol, colIdx);
                }
            }
            for (Merge m : doc.getMerges())
            {
                if (m == null || m.getPosition() == null)
                {
                    continue;
                }
                Rect p = m.getPosition();
                int fromCol = p.getX() + 1;
                int fromRow = p.getY() + 1;
                // The merge stores its span in width/height. In the mxl
                // serialization an absent <w>/<h> (a single column/row merge)
                // deserializes to 0, so treat a non-positive span as 1.
                int w = p.getWidth() > 0 ? p.getWidth() : 1;
                int h = p.getHeight() > 0 ? p.getHeight() : 1;
                int toCol = fromCol + w - 1;
                int toRow = fromRow + h - 1;
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("fromRow", Integer.valueOf(fromRow)); //$NON-NLS-1$
                mm.put("fromCol", Integer.valueOf(fromCol)); //$NON-NLS-1$
                mm.put("toRow", Integer.valueOf(toRow)); //$NON-NLS-1$
                mm.put("toCol", Integer.valueOf(toCol)); //$NON-NLS-1$
                merges.add(mm);
                maxRow = Math.max(maxRow, toRow);
                maxCol = Math.max(maxCol, toCol);
            }
            for (Drawing d : doc.getDrawings())
            {
                if (d == null)
                {
                    continue;
                }
                Map<String, Object> dm = new LinkedHashMap<>();
                dm.put("id", Integer.valueOf(d.getDrawingId())); //$NON-NLS-1$
                drawings.add(dm);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rowCount", Integer.valueOf(maxRow)); //$NON-NLS-1$
        out.put("colCount", Integer.valueOf(maxCol)); //$NON-NLS-1$
        out.put("cellCount", Integer.valueOf(cells.size())); //$NON-NLS-1$
        out.put("cells", cells); //$NON-NLS-1$
        out.put("merges", merges); //$NON-NLS-1$
        out.put("drawings", drawings); //$NON-NLS-1$
        return out;
    }

    /**
     * Resolves a cell's text from its {@link LocalString} content: the preferred
     * language, then {@code ru}, then the first non-empty entry. Returns null
     * when the cell has no text content.
     */
    private static String cellText(Cell cell, String language)
    {
        LocalString ls = cell.getText();
        if (ls == null)
        {
            return null;
        }
        EMap<String, String> content = ls.getContent();
        if (content == null || content.isEmpty())
        {
            return null;
        }
        if (language != null && !language.isEmpty())
        {
            String v = content.get(language);
            if (v != null && !v.isEmpty())
            {
                return v;
            }
        }
        String ru = content.get("ru"); //$NON-NLS-1$
        if (ru != null && !ru.isEmpty())
        {
            return ru;
        }
        for (Map.Entry<String, String> e : content)
        {
            if (e != null && e.getValue() != null && !e.getValue().isEmpty())
            {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Maps a drawing-type alias (EN or RU, case-insensitive) to the canonical
     * EN type name supported by {@link #addDrawing}. Returns {@code null} for an
     * unknown / unsupported type so the caller can reject it before opening a
     * transaction.
     *
     * <p>Only the geometric / text types are supported - Picture, Chart,
     * Gantt, Control, etc. require external data (picture index, chart data
     * source) and are intentionally out of scope.
     */
    public static String canonicalDrawingType(String type)
    {
        if (type == null)
        {
            return null;
        }
        switch (type.trim().toLowerCase())
        {
            case "line": //$NON-NLS-1$
            case "линия": //$NON-NLS-1$
                return "Line"; //$NON-NLS-1$
            case "rectangle": //$NON-NLS-1$
            case "прямоугольник": //$NON-NLS-1$
                return "Rectangle"; //$NON-NLS-1$
            case "ellipse": //$NON-NLS-1$
            case "овал": //$NON-NLS-1$
            case "эллипс": //$NON-NLS-1$
                return "Ellipse"; //$NON-NLS-1$
            case "text": //$NON-NLS-1$
            case "надпись": //$NON-NLS-1$
            case "текст": //$NON-NLS-1$
                return "Text"; //$NON-NLS-1$
            default:
                return null;
        }
    }

    /**
     * Allocates the next free drawing id (max existing id + 1, minimum 1).
     */
    public static int nextDrawingId(SpreadsheetDocument doc)
    {
        if (doc == null)
        {
            throw new IllegalArgumentException("doc must not be null"); //$NON-NLS-1$
        }
        int max = 0;
        for (Drawing d : doc.getDrawings())
        {
            if (d != null && d.getDrawingId() > max)
            {
                max = d.getDrawingId();
            }
        }
        return max + 1;
    }

    /**
     * Adds a geometric drawing (Line / Rectangle / Ellipse / Text) to the
     * spreadsheet's {@code drawings} collection and returns its allocated id.
     *
     * <p>The drawing is anchored by a begin (top-left) and end (bottom-right)
     * {@link SpreadsheetPoint}. Each point is a cell (1-based row/column) plus
     * an intra-cell offset, matching the platform's flat serialization
     * ({@code <beginRow>}/{@code <beginColumn>}/{@code <beginRowOffset>}/...).
     * Coordinates map to {@code mcore.Point} as {@code x = column},
     * {@code y = row}.
     *
     * <p>Line / Rectangle / Ellipse carry no own content - their stroke and
     * fill come from the format-table entry referenced by {@code formatIndex}.
     * Text drawings additionally receive a {@link LocalString} caption.
     *
     * <p>The drawings collection is a containment, non-transient feature, so
     * {@link #persistTemplateMxlx} serializes it directly (unlike form-level
     * conditional appearance, which is transient and cannot be persisted).
     *
     * @param doc the spreadsheet (must not be null)
     * @param canonicalType canonical type from {@link #canonicalDrawingType}
     * @param beginRow top-left anchor row (1-based)
     * @param beginCol top-left anchor column (1-based)
     * @param endRow bottom-right anchor row (1-based, &gt;= beginRow)
     * @param endCol bottom-right anchor column (1-based, &gt;= beginCol)
     * @param beginRowOffset intra-cell offset for the begin row (&gt;= 0)
     * @param beginColOffset intra-cell offset for the begin column (&gt;= 0)
     * @param endRowOffset intra-cell offset for the end row (&gt;= 0)
     * @param endColOffset intra-cell offset for the end column (&gt;= 0)
     * @param formatIndex format-table index for stroke / fill styling
     * @param zOrder explicit z-order, or {@code null} to default to the id
     * @param text caption for Text drawings (ignored for other types)
     * @param language LocalString language tag (default {@code "ru"})
     * @return the allocated drawing id
     */
    public static int addDrawing(SpreadsheetDocument doc, String canonicalType,
        int beginRow, int beginCol, int endRow, int endCol,
        int beginRowOffset, int beginColOffset, int endRowOffset, int endColOffset,
        int formatIndex, Integer zOrder, String text, String language)
    {
        if (doc == null)
        {
            throw new IllegalArgumentException("doc must not be null"); //$NON-NLS-1$
        }
        Drawing d = createDrawingByType(canonicalType);
        int id = nextDrawingId(doc);
        d.setDrawingId(id);
        // A drawing needs its OWN format entry. Reusing a cell format (e.g.
        // index 0) makes the moxel serializer emit a cell format through the
        // drawing-format path and trip on a value that path does not populate.
        // With no explicit formatIndex (formatIndex < 0) append a fresh empty
        // Format and point the drawing at it (empty -> serialized as <format/>,
        // no stroke/fill until a styled formatIndex is supplied). EDT likewise
        // gives every drawing its own format.
        int effectiveFormatIndex;
        if (formatIndex < 0)
        {
            doc.getFormats().add(MoxelFactory.eINSTANCE.createFormat());
            effectiveFormatIndex = doc.getFormats().size() - 1;
        }
        else if (formatIndex < doc.getFormats().size())
        {
            effectiveFormatIndex = formatIndex;
        }
        else
        {
            // An out-of-range index would serialize silently and corrupt the
            // template on re-open. Reject it (omit formatIndex to auto-create).
            throw new IllegalArgumentException("formatIndex " + formatIndex //$NON-NLS-1$
                + " is out of range - the format table has " //$NON-NLS-1$
                + doc.getFormats().size() + " entries (omit formatIndex to " //$NON-NLS-1$
                + "auto-create an empty drawing format)"); //$NON-NLS-1$
        }
        d.setFormatIndex(effectiveFormatIndex);
        d.setZOrder(zOrder != null ? zOrder.intValue() : id);
        d.setPosition(buildSpreadsheetRect(beginRow, beginCol, endRow, endCol,
            beginRowOffset, beginColOffset, endRowOffset, endColOffset));
        // The moxel serializer's writeValueIfNotUndefined skips only an
        // UndefinedValue, NOT null: a null detailValue makes it call
        // writeValue(null) -> AssertionFailedException "null argument", which
        // aborts the .mxlx save mid-stream and leaves a 0-byte file. EDT's own
        // drawing creation seeds these Value slots with an UndefinedValue, so
        // mirror it here (and the Text drawing's value below).
        d.setDetailValue(McoreFactory.eINSTANCE.createUndefinedValue());
        if (d instanceof TextDrawing)
        {
            String lang = (language == null || language.isEmpty()) ? "ru" : language; //$NON-NLS-1$
            TextDrawing td = (TextDrawing) d;
            LocalString ls = ContentFactory.eINSTANCE.createLocalString();
            ls.getContent().put(lang, text == null ? "" : text); //$NON-NLS-1$
            td.setText(ls);
            td.setAutosize(false);
            td.setValue(McoreFactory.eINSTANCE.createUndefinedValue());
        }
        doc.getDrawings().add(d);
        return id;
    }

    /**
     * Removes the drawing with the given id from the spreadsheet. Returns
     * {@code true} when a drawing was removed, {@code false} when none matched
     * (idempotent).
     */
    public static boolean removeDrawing(SpreadsheetDocument doc, int drawingId)
    {
        if (doc == null)
        {
            throw new IllegalArgumentException("doc must not be null"); //$NON-NLS-1$
        }
        java.util.List<Drawing> drawings = doc.getDrawings();
        for (int i = 0; i < drawings.size(); i++)
        {
            Drawing d = drawings.get(i);
            if (d != null && d.getDrawingId() == drawingId)
            {
                drawings.remove(i);
                return true;
            }
        }
        return false;
    }

    private static Drawing createDrawingByType(String canonicalType)
    {
        MoxelFactory f = MoxelFactory.eINSTANCE;
        if ("Line".equals(canonicalType)) //$NON-NLS-1$
        {
            return f.createLineDrawing();
        }
        if ("Rectangle".equals(canonicalType)) //$NON-NLS-1$
        {
            return f.createRectangleDrawing();
        }
        if ("Ellipse".equals(canonicalType)) //$NON-NLS-1$
        {
            return f.createEllipseDrawing();
        }
        if ("Text".equals(canonicalType)) //$NON-NLS-1$
        {
            return f.createTextDrawing();
        }
        throw new IllegalArgumentException("Unsupported drawingType '" + canonicalType //$NON-NLS-1$
            + "'. Supported: Line, Rectangle, Ellipse, Text"); //$NON-NLS-1$
    }

    private static SpreadsheetRect buildSpreadsheetRect(int beginRow, int beginCol,
        int endRow, int endCol, int beginRowOffset, int beginColOffset,
        int endRowOffset, int endColOffset)
    {
        SpreadsheetRect rect = MoxelFactory.eINSTANCE.createSpreadsheetRect();
        rect.setBegin(buildSpreadsheetPoint(beginRow, beginCol, beginRowOffset, beginColOffset));
        rect.setEnd(buildSpreadsheetPoint(endRow, endCol, endRowOffset, endColOffset));
        return rect;
    }

    private static SpreadsheetPoint buildSpreadsheetPoint(int row, int col, int rowOffset,
        int colOffset)
    {
        SpreadsheetPoint p = MoxelFactory.eINSTANCE.createSpreadsheetPoint();
        // x = column, y = row - matches the <beginColumn>/<beginRow> flat
        // serialization in Template.mxlx. Like the cell/merge keys, the moxel
        // point cell index is 0-based, so convert the 1-based API input. The
        // offset is intra-cell (a pixel shift inside the anchor cell), not a
        // cell index, and stays as-is.
        Point cell = McoreFactory.eINSTANCE.createPoint();
        cell.setX(col - 1);
        cell.setY(row - 1);
        p.setCell(cell);
        Point off = McoreFactory.eINSTANCE.createPoint();
        off.setX(colOffset);
        off.setY(rowOffset);
        p.setOffset(off);
        return p;
    }
}
