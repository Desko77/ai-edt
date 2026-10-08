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
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.mcore.Color;
import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.Font;
import com._1c.g5.v8.dt.mcore.FontDef;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.MutableFont;
import com._1c.g5.v8.dt.mcore.Picture;
import com._1c.g5.v8.dt.mcore.PictureRef;
import com._1c.g5.v8.dt.mcore.Point;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Drawing;
import com._1c.g5.v8.dt.moxel.DrawingsDataSource;
import com._1c.g5.v8.dt.moxel.Format;
import com._1c.g5.v8.dt.moxel.Merge;
import com._1c.g5.v8.dt.moxel.Column;
import com._1c.g5.v8.dt.moxel.ColumnGroup;
import com._1c.g5.v8.dt.moxel.ColumnMerge;
import com._1c.g5.v8.dt.moxel.Columns;
import com._1c.g5.v8.dt.moxel.PageOrientation;
import com._1c.g5.v8.dt.moxel.PrintSettings;
import com._1c.g5.v8.dt.moxel.content.CellLine;
import com._1c.g5.v8.dt.moxel.content.CellLineStyle;
import com._1c.g5.v8.dt.moxel.content.FillType;
import com._1c.g5.v8.dt.moxel.content.Pattern;
import com._1c.g5.v8.dt.moxel.content.SpreadsheetLine;
import com._1c.g5.v8.dt.moxel.content.TextPlacement;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.Enumerator;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.ColumnsArea;
import com._1c.g5.v8.dt.moxel.NamedItem;
import com._1c.g5.v8.dt.moxel.NamedItemCells;
import com._1c.g5.v8.dt.moxel.NamedItemDataSource;
import com._1c.g5.v8.dt.moxel.NamedItemEmbeddedTable;
import com._1c.g5.v8.dt.moxel.RectArea;
import com._1c.g5.v8.dt.moxel.RowsArea;
import com._1c.g5.v8.dt.moxel.RowGroup;
import com._1c.g5.v8.dt.moxel.RowMerge;
import com._1c.g5.v8.dt.moxel.ViewSettings;
import com._1c.g5.v8.dt.moxel.content.Area;
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
     *
     * <p>An alias nobody recognizes is passed through unchanged rather than refused here: this
     * method only spells a caller's word the way the model spells it. Whether the model has such a
     * literal is a separate question, answered by {@link #resolveTemplateTypeLiteral}, and the
     * caller refuses on {@code null} from that one.
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
            case "geographicalschema":
            case "geographical schema":
            case "geo":
            case "географическая":
            case "географическаясхема":
                return "GeographicalSchema";
            case "graphicalschema":
            case "graphicalscheme":
            case "graphical scheme":
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
     * The model's own literal for a canonical template type.
     * <p>
     * The type is set through reflection, where an unrecognized value is not an exception but a
     * returned error the caller can drop - and dropping it left the template with no type at all
     * while the answer named the one that was asked for. So the caller asks the model itself
     * whether the literal exists before it writes anything.
     * </p>
     * <p>
     * Measured on EDT 2025.2.3: {@code getByName} and {@code get} answer to the literal
     * ({@code SpreadsheetDocument}), not to the Java constant ({@code SPREADSHEET_DOCUMENT}), so the
     * literal is what a caller writes and what this returns.
     * </p>
     *
     * @param canonicalType a name from {@link #canonicalTemplateType}; may be <code>null</code>
     * @return the literal, or <code>null</code> when the model has no such template type
     */
    public static String resolveTemplateTypeLiteral(String canonicalType)
    {
        if (canonicalType == null || canonicalType.isEmpty())
        {
            return null;
        }
        TemplateType byName = TemplateType.getByName(canonicalType);
        return byName == null ? null : byName.getLiteral();
    }

    /**
     * The template types this EDT model has, separated by a comma - the values a caller may ask for.
     * <p>
     * The literal, not the Java constant: a caller writes {@code SpreadsheetDocument}, while
     * {@code SPREADSHEET_DOCUMENT} is refused by {@code getByName} on EDT 2025.2.3, and a list of
     * refused words in a refusal message is worse than no list at all.
     * </p>
     *
     * @return the literals, in the order the model declares them
     */
    public static String templateTypeValues()
    {
        StringBuilder values = new StringBuilder();
        for (TemplateType type : TemplateType.values())
        {
            if (values.length() > 0)
            {
                values.append(", "); //$NON-NLS-1$
            }
            values.append(type.getLiteral());
        }
        return values.toString();
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
     * The spreadsheet document a template already holds in its content slot, without creating one.
     * The get-or-create this class otherwise resolves through attaches a fresh document to an
     * empty slot, and a caller that means to say the model did not move has to know the document
     * was there before the call.
     *
     * @param template the template, may be <code>null</code>
     * @return the attached spreadsheet document, or {@code null} when the slot holds none
     */
    public static SpreadsheetDocument existingSpreadsheetOf(MdObject template)
    {
        if (template == null)
        {
            return null;
        }
        try
        {
            java.lang.reflect.Method getter = template.getClass().getMethod("getTemplate"); //$NON-NLS-1$
            Object current = getter.invoke(template);
            return current instanceof SpreadsheetDocument ? (SpreadsheetDocument) current : null;
        }
        catch (Exception e)
        {
            // A template that will not answer the question holds no document this caller can name.
            return null;
        }
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
        return writeEmptyMxlxFileChecked(project, ownerFqn, templateName, canonicalType);
    }

    /**
     * Whether {@code Template.mxlx} is in the folder of the named template.
     * {@link #writeEmptyMxlxFile} leaves such a file as it is, so a caller that reports what it
     * created asks this first.
     *
     * @param project the EDT project
     * @param ownerFqn FQN of the owning metadata object
     * @param templateName name of the template
     * @return <code>true</code> when the file exists; <code>false</code> when it does not or the
     *         folder cannot be resolved
     */
    public static boolean spreadsheetFileExists(IProject project, String ownerFqn, String templateName)
    {
        if (project == null || ownerFqn == null || templateName == null)
        {
            return false;
        }
        Path templateDir = resolveTemplateDir(project, ownerFqn, templateName);
        return templateDir != null && Files.exists(templateDir.resolve("Template.mxlx")); //$NON-NLS-1$
    }

    private static String writeEmptyMxlxFileChecked(IProject project, String ownerFqn,
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
                + "/Templates/" + templateName + ": " + unresolvableReason(project, ownerFqn, //$NON-NLS-1$ //$NON-NLS-2$
                    templateName);
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
                + "/Templates/" + templateName + ": " + unresolvableReason(project, ownerFqn, //$NON-NLS-1$ //$NON-NLS-2$
                    templateName);
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
                + "/Templates/" + templateName + ": " + unresolvableReason(project, ownerFqn, //$NON-NLS-1$ //$NON-NLS-2$
                    templateName);
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
        if (spreadsheetDocument instanceof SpreadsheetDocument)
        {
            SpreadsheetDocument spreadsheet = (SpreadsheetDocument) spreadsheetDocument;
            // A picture reference the project cannot resolve serializes as ref="v8ui:/", which
            // the platform then refuses to load. Better to refuse the write here, before any
            // byte of the file is touched, and name what is missing. The writing operations ask
            // the same question before the document changes (requireResolvablePictures); this
            // check is the last line of defense for a caller that changed the document first.
            String unresolved = unresolvedPictureRefusal(spreadsheet);
            if (unresolved != null)
            {
                return unresolved + " Template.mxlx was not changed."; //$NON-NLS-1$
            }
            // The moxel serializer dereferences the document's column set, so a document built
            // without one dies inside save() with a NullPointerException. Give it the default
            // empty set - the same shape writeEmptyMxlxFile writes for a fresh template.
            ensureColumnSet(spreadsheet);
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
     * Maps an English or Russian metadata type prefix to the plural folder name EDT uses under
     * {@code src/} - the directory a template of that owner lives in.
     * <p>
     * The name is asked of {@link MetadataTypeCatalog}, the one place a type name is spelled out:
     * a second table beside it is a second answer to the same question, and the two drift. A
     * prefix no type answers to is refused rather than pluralized, which is how an owner FQN in a
     * language this build does not know used to end up as a folder named after the caller's own
     * word - {@code Справочникs} for {@code Справочник.Товары}.
     * </p>
     *
     * @param typePrefix the owner's type, English or Russian, singular or plural; may be
     *            <code>null</code>
     * @return the folder name, for example {@code Catalogs}, or <code>null</code> when no metadata
     *         type answers to that name
     */
    public static String englishTypePlural(String typePrefix)
    {
        MetadataTypeCatalog.MetadataTypeInfo type = MetadataTypeCatalog.resolve(typePrefix);
        return type == null ? null : type.getEnglishPlural();
    }

    /**
     * The owner FQN with the name part spelled the way the model holds it.
     * <p>
     * An FQN resolves to its object whatever case the caller wrote: the name of an object is data,
     * and no caller's spelling of it is a contract. The folder a template is written into is built
     * from the FQN's text, so a name in another case addressed the object and then placed the write
     * in a folder spelled by the caller - a new folder on a case-sensitive file system, and a
     * second name for the same template on any of them. Taking the name from the object the FQN
     * resolved to keeps the write in the folder the object's own files live in.
     * </p>
     * <p>
     * The type prefix stays as the caller wrote it: it carries the language and feeds
     * {@link #englishTypePlural}, and every recognized spelling of a type resolves to one folder.
     * </p>
     *
     * @param ownerFqn the caller's FQN, {@code <Type>.<Name>}
     * @param owner the object the FQN resolved to; a <code>null</code> or unnamed object leaves the
     *            FQN as it came
     * @return the FQN with the owner's own name, or the FQN unchanged when it names no object
     */
    public static String modelOwnerFqn(String ownerFqn, MdObject owner)
    {
        int dot = ownerFqn == null ? -1 : ownerFqn.indexOf('.');
        if (dot <= 0 || dot == ownerFqn.length() - 1 || owner == null)
        {
            return ownerFqn;
        }
        String name = owner.getName();
        if (name == null || name.isEmpty())
        {
            return ownerFqn;
        }
        return ownerFqn.substring(0, dot + 1) + name;
    }

    /**
     * The template's folder relative to the project root, or <code>null</code> when the owner FQN or
     * the template name cannot address one.
     * <p>
     * Both the owner name and the template name are single path elements and are checked as such
     * before anything is joined: a name carrying a separator or standing for a parent directory
     * would otherwise place the write outside the template it names, and an owner type nobody
     * recognizes would place it in a folder that exists nowhere.
     * </p>
     *
     * @param ownerFqn the owner's FQN, {@code <Type>.<Name>} in either language
     * @param templateName the template's name
     * @return the relative folder, for example {@code src/Catalogs/Tovary/Templates/Main}, or
     *         <code>null</code> when the two cannot address one
     */
    public static Path templateDirRelativePath(String ownerFqn, String templateName)
    {
        int dot = ownerFqn == null ? -1 : ownerFqn.indexOf('.');
        if (dot <= 0 || dot == ownerFqn.length() - 1)
        {
            return null;
        }
        if (!isPlainName(templateName))
        {
            return null;
        }
        String typePrefix = ownerFqn.substring(0, dot);
        String ownerName = ownerFqn.substring(dot + 1);
        String typeDirectory = englishTypePlural(typePrefix);
        if (typeDirectory == null || !isPlainName(ownerName))
        {
            return null;
        }
        Path src = Path.of("src"); //$NON-NLS-1$
        if (MetadataTypeCatalog.MetadataTypeInfo.COMMON_TEMPLATE
            == MetadataTypeCatalog.resolve(typePrefix))
        {
            // A common template is the whole object: src/CommonTemplates/<Name>, with no owner
            // folder and no Templates level - the shape its .mdo lives in.
            return src.resolve(typeDirectory).resolve(templateName);
        }
        return src.resolve(typeDirectory).resolve(ownerName).resolve("Templates").resolve(templateName); //$NON-NLS-1$
    }

    /**
     * Why the owner FQN and the template name do not address a template folder, or <code>null</code>
     * when they do. Both answer from the same rule as {@link #templateDirRelativePath}: this one
     * names the reason, that one builds the path.
     *
     * @param ownerFqn the owner's FQN
     * @param templateName the template's name
     * @return the reason, or <code>null</code> when the two address a folder
     */
    public static String templateDirRefusal(String ownerFqn, String templateName)
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
        if (englishTypePlural(typePrefix) == null)
        {
            return "ownerFqn names no metadata type that owns templates: '" + typePrefix + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!isPlainName(ownerFqn.substring(dot + 1)))
        {
            return "the owner name must be a plain name, got '" + ownerFqn.substring(dot + 1) //$NON-NLS-1$
                + "'"; //$NON-NLS-1$
        }
        if (!isPlainName(templateName))
        {
            return "the template name must be a plain name, got '" + templateName + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Whether a name is one path element: not empty, not this or the parent directory, and carrying
     * no separator of either kind. The rule itself lives in {@link MetadataGuards#isPlainName} so
     * that every path joining a caller's name onto {@code src/} - templates and forms alike - gets
     * the same answer.
     *
     * @param name the name to test; may be <code>null</code>
     * @return <code>true</code> when the name is a single, ordinary path element
     */
    private static boolean isPlainName(String name)
    {
        return MetadataGuards.isPlainName(name);
    }

    /**
     * Whether the template has a folder on disk, which is where EDT keeps its content file.
     * <p>
     * Measured on real configurations: a template is declared inline in its owner's {@code .mdo},
     * and the folder under {@code src/.../Templates/} holds the content. So a folder is what a
     * template that exists has, and its absence is what a mistyped name looks like.
     * </p>
     *
     * @param project the EDT project (must be open)
     * @param ownerFqn FQN of the owning metadata object
     * @param templateName name of the template
     * @return <code>true</code> when the template's folder exists
     */
    public static boolean templateExists(IProject project, String ownerFqn, String templateName)
    {
        Path dir = project == null ? null : resolveTemplateDir(project, ownerFqn, templateName);
        return dir != null && Files.isDirectory(dir);
    }

    /**
     * The picture references of the document that point at nothing in this project.
     * <p>
     * A picture drawing keeps its picture in the document's {@code pictures} table, and a
     * reference to a common picture the project does not have stays an EMF proxy there - a
     * stand-in the model never resolved. The moxel serializer writes such a stand-in as
     * {@code ref="v8ui:/"}, a reference the platform refuses to load, so a write carrying one has
     * to be stopped rather than persisted. A reference with no target at all is an empty picture
     * placeholder, not a missing one, and does not stop the write. Embedded pictures carry their
     * bytes and resolve by construction.
     * </p>
     *
     * @param doc the spreadsheet document; must not be <code>null</code>
     * @return the names of the unresolved references, in table order; empty when every reference
     *         resolves
     */
    public static List<String> unresolvedPictureRefs(SpreadsheetDocument doc)
    {
        if (doc == null)
        {
            throw new IllegalArgumentException("doc must not be null"); //$NON-NLS-1$
        }
        List<String> unresolved = new ArrayList<>();
        for (Picture picture : doc.getPictures())
        {
            if (picture == null)
            {
                continue;
            }
            EObject suspect = picture;
            // A PictureRef points at the picture it stands for; follow the chain so a reference
            // to a reference is judged by what it finally names. The bound keeps a cyclic chain
            // from holding the caller forever.
            for (int depth = 0; depth < 8 && suspect instanceof PictureRef
                && !suspect.eIsProxy(); depth++)
            {
                EObject target = ((PictureRef) suspect).getPicture();
                if (target == null)
                {
                    break;
                }
                suspect = target;
            }
            if (suspect.eIsProxy())
            {
                unresolved.add(proxyPictureName((InternalEObject) suspect));
            }
        }
        return unresolved;
    }

    /**
     * The refusal for a document whose picture references the project cannot resolve, or
     * {@code null} when every reference resolves.
     * <p>
     * The moxel serializer writes an unresolved reference as {@code ref="v8ui:/"}, which the
     * platform refuses to load, so a write carrying one is stopped rather than persisted. The
     * sentence names what is missing; whether anything was changed is the caller's to say, because
     * the writing operations refuse before changing anything while the persist step refuses with
     * the file still untouched.
     * </p>
     *
     * @param doc the spreadsheet document; must not be <code>null</code>
     * @return the refusal, or {@code null} when the document holds no unresolved reference
     */
    public static String unresolvedPictureRefusal(SpreadsheetDocument doc)
    {
        List<String> unresolved = unresolvedPictureRefs(doc);
        if (unresolved.isEmpty())
        {
            return null;
        }
        int shown = Math.min(unresolved.size(), 3);
        return "the template holds " + unresolved.size() //$NON-NLS-1$
            + " picture reference(s) that do not resolve in the project (" //$NON-NLS-1$
            + String.join(", ", unresolved.subList(0, shown)) //$NON-NLS-1$
            + (unresolved.size() > shown ? ", ..." : "") //$NON-NLS-1$ //$NON-NLS-2$
            + "). Writing would serialize them as ref=\"v8ui:/\", which the platform " //$NON-NLS-1$
            + "refuses to load. Copy the named common pictures into the project first. " //$NON-NLS-1$
            + "Removing the drawings does not help: the reference stays in the " //$NON-NLS-1$
            + "document's picture table."; //$NON-NLS-1$
    }

    /**
     * The guard a write on a spreadsheet document asks before the document changes.
     * <p>
     * A template with picture references the project cannot resolve cannot be written to
     * Template.mxlx at all, so the write is refused before the operation changes anything: the
     * guard runs inside the write transaction ahead of the mutation, and the refusal it throws
     * rolls that transaction back with the document exactly as it was and the file untouched - a
     * refused write cannot leave the model and the file diverging. A dry run passes through the
     * same guard, so the preview of a write that would not persist answers with the same refusal.
     * </p>
     *
     * @param doc the document the write is about to change; must not be <code>null</code>
     */
    public static void requireResolvablePictures(SpreadsheetDocument doc)
    {
        String refusal = unresolvedPictureRefusal(doc);
        if (refusal == null)
        {
            return;
        }
        MetadataGuards.ErrorTag tag = new MetadataGuards.ErrorTag("unresolvedPictureRefs"); //$NON-NLS-1$
        tag.put("pictures", unresolvedPictureRefs(doc)); //$NON-NLS-1$
        throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            refusal + " Nothing was changed: neither the document nor Template.mxlx.", //$NON-NLS-1$
            "Copy the named common pictures into the project, then write again.", //$NON-NLS-1$
            tag));
    }

    /**
     * The name a proxy picture reference was meant to resolve to, read off its proxy URI.
     * <p>
     * A project picture lives at {@code .../CommonPictures/&lt;Name&gt;/&lt;Name&gt;.mdo}. The
     * folder segment is the name. A URI that spells the type as {@code CommonPicture.&lt;Name&gt;}
     * is read the same way. Anything else gives the URI's last segment, with a trailing
     * {@code .mdo} removed, so the refusal still names something the caller can search for.
     * </p>
     *
     * @param proxy the unresolved proxy object
     * @return the best name the proxy carries
     */
    private static String proxyPictureName(InternalEObject proxy)
    {
        URI uri = proxy.eProxyURI();
        if (uri == null)
        {
            return "(unnamed proxy)"; //$NON-NLS-1$
        }
        String text = uri.toString();
        String fromFolder = nameAfter(text, "CommonPictures/"); //$NON-NLS-1$
        if (fromFolder != null)
        {
            return stripPictureSuffix(fromFolder);
        }
        String fromMarker = nameAfter(text, "CommonPicture."); //$NON-NLS-1$
        if (fromMarker != null)
        {
            return stripPictureSuffix(fromMarker);
        }
        String segment = uri.lastSegment();
        if (segment == null || segment.isEmpty())
        {
            return text;
        }
        return stripPictureSuffix(segment);
    }

    /**
     * The first path segment after {@code marker}, or <code>null</code> when the marker is absent
     * or names nothing.
     *
     * @param text the proxy URI text
     * @param marker the folder or type marker to look for
     * @return the segment, still carrying a {@code .mdo} suffix when the URI had one
     */
    private static String nameAfter(String text, String marker)
    {
        int at = text.indexOf(marker);
        if (at < 0)
        {
            return null;
        }
        String rest = text.substring(at + marker.length());
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++)
        {
            char c = rest.charAt(i);
            if (c == '/' || c == '#' || c == '?' || c == '&' || c == ';')
            {
                end = i;
                break;
            }
        }
        return end > 0 ? rest.substring(0, end) : null;
    }

    /**
     * Removes a trailing {@code .mdo} from a picture name taken out of a URI.
     *
     * @param name the segment
     * @return the name without that suffix
     */
    private static String stripPictureSuffix(String name)
    {
        if (name.endsWith(".mdo")) //$NON-NLS-1$
        {
            return name.substring(0, name.length() - ".mdo".length()); //$NON-NLS-1$
        }
        return name;
    }

    /**
     * Gives the document the default column set when it has none. The moxel serializer
     * dereferences the set on every save, so a document built without one (a spreadsheet attached
     * to a freshly created template) cannot be written at all. The default set declares size 0 -
     * the same shape {@code writeEmptyMxlxFile} writes for a fresh template.
     *
     * @param doc the spreadsheet document; must not be <code>null</code>
     */
    public static void ensureColumnSet(SpreadsheetDocument doc)
    {
        if (doc == null)
        {
            throw new IllegalArgumentException("doc must not be null"); //$NON-NLS-1$
        }
        if (doc.getColumns() == null)
        {
            Columns columns = MoxelFactory.eINSTANCE.createColumns();
            columns.setSize(0);
            doc.setColumns(columns);
        }
    }

    /**
     * Whether the file on disk and the document the model holds diverge: the file carries a real
     * template and the model document is empty.
     * <p>
     * The empty spreadsheet written for a new template is not that divergence. Its bytes are
     * {@link #emptySpreadsheetSkeleton()}, or the same skeleton with {@code indexTo} on the first
     * row after EDT rewrites the file. An unloaded model document has
     * no rows, no column set and no drawings, and refusing a write over that skeleton would block
     * the first {@code set_cell} on a template that was just created.
     * </p>
     *
     * @param project the EDT project
     * @param ownerFqn the template owner's FQN
     * @param templateName the template's name
     * @param document the document the model currently holds; <code>null</code> reads as an
     *        empty model
     * @return a caller-facing mismatch description, or {@code null} when model and file do not
     *         have this signature
     */
    public static String modelFileMismatch(IProject project, String ownerFqn,
        String templateName, SpreadsheetDocument document)
    {
        if (project == null)
        {
            return null;
        }
        Path dir = resolveTemplateDir(project, ownerFqn, templateName);
        Path file = dir == null ? null : dir.resolve("Template.mxlx"); //$NON-NLS-1$
        return modelFileMismatch(document, file);
    }

    /**
     * The same divergence asked directly against the file: the model answers an empty document
     * (no rows, no column set, no drawings - the state of a freshly built SpreadsheetDocument)
     * while the file it would be written over is there and carries a template. A document holding
     * any of those is a model that has read its file. A file that is absent, empty, or only the
     * empty-spreadsheet skeleton is nothing a write could destroy.
     *
     * @param document the document the model currently holds; <code>null</code> reads as an
     *        empty model
     * @param mxlxFile the template's {@code Template.mxlx} on disk
     * @return a caller-facing mismatch description, or {@code null} when there is no divergence
     */
    public static String modelFileMismatch(SpreadsheetDocument document, Path mxlxFile)
    {
        if (document != null && (!document.getRows().isEmpty()
            || document.getColumns() != null || !document.getDrawings().isEmpty()))
        {
            return null;
        }
        try
        {
            if (mxlxFile != null && Files.isRegularFile(mxlxFile) && Files.size(mxlxFile) > 0)
            {
                String content = Files.readString(mxlxFile, StandardCharsets.UTF_8);
                if (isEmptySpreadsheetSkeleton(content))
                {
                    return null;
                }
                return "Template.mxlx is not empty on disk, but the EDT model has no rows, " //$NON-NLS-1$
                    + "columns or drawings. The project model may still be loading; retry after " //$NON-NLS-1$
                    + "the build completes. Writing now is refused to preserve the file."; //$NON-NLS-1$
            }
        }
        catch (IOException e)
        {
            return "Template.mxlx could not be compared with the empty EDT model: " //$NON-NLS-1$
                + e.getMessage() + ". Writing is refused to preserve the file."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The bytes {@link #writeEmptyMxlxFile} writes for a new spreadsheet template.
     *
     * @return the skeleton XML, with {@code \n} line endings
     */
    static String emptySpreadsheetSkeleton()
    {
        return EMPTY_MXLX_CONTENT;
    }

    /**
     * Whether {@code content} is the empty spreadsheet skeleton, newline endings aside.
     * <p>
     * Equal to {@link #emptySpreadsheetSkeleton()}, or to that skeleton with
     * {@code <indexTo>1</indexTo>} on the first row. Comparison is exact after {@code CR LF} and
     * a lone {@code CR} are read as {@code LF}. Surrounding space is not removed: a file that
     * carries anything else is a template, and writing an empty model over it is refused.
     * </p>
     *
     * @param content the file text; <code>null</code> is not a skeleton
     * @return <code>true</code> when the file is only the empty spreadsheet
     */
    static boolean isEmptySpreadsheetSkeleton(String content)
    {
        if (content == null)
        {
            return false;
        }
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (normalized.equals(EMPTY_MXLX_CONTENT))
        {
            return true;
        }
        String withIndexTo = EMPTY_MXLX_CONTENT.replace(
            "\t\t<index>0</index>\n", //$NON-NLS-1$
            "\t\t<index>0</index>\n\t\t<indexTo>1</indexTo>\n"); //$NON-NLS-1$
        return normalized.equals(withIndexTo);
    }

    /**
     * Resolves the on-disk template directory:
     * {@code <project>/src/<OwnerCollection>/<OwnerName>/Templates/<TemplateName>}
     * for object-owned templates, or
     * {@code <project>/src/CommonTemplates/<TemplateName>} for CommonTemplate.
     *
     * <p>The result is normalized and checked to be inside the project: a path that would land
     * outside the project the caller named is not resolved at all.
     */
    private static Path resolveTemplateDir(IProject project, String ownerFqn,
        String templateName)
    {
        if (project.getLocation() == null)
        {
            return null;
        }
        Path relative = templateDirRelativePath(ownerFqn, templateName);
        if (relative == null)
        {
            return null;
        }
        Path projectRoot = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
        Path resolved = projectRoot.resolve(relative).normalize();
        return resolved.startsWith(projectRoot) ? resolved : null;
    }

    /**
     * Why the template directory could not be resolved: the owner or the name does not address one,
     * or - when both do - the project has no location on the local filesystem.
     *
     * @param project the EDT project
     * @param ownerFqn the owner's FQN
     * @param templateName the template's name
     * @return the reason, for the caller's error message
     */
    private static String unresolvableReason(IProject project, String ownerFqn, String templateName)
    {
        String refusal = templateDirRefusal(ownerFqn, templateName);
        if (refusal != null)
        {
            return refusal;
        }
        return project == null || project.getLocation() == null
            ? "the project has no location on the local filesystem" //$NON-NLS-1$
            : "the template path leaves the project"; //$NON-NLS-1$
    }

    /**
     * Locates the template folder as an Eclipse {@link IFolder} so we can
     * call {@code refreshLocal} on it. Returns null when the layout cannot
     * be matched (caller falls back to project-level refresh).
     */
    private static IFolder locateTemplateFolder(IProject project, String ownerFqn,
        String templateName)
    {
        Path relative = templateDirRelativePath(ownerFqn, templateName);
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
        Cell c = cellAt(doc, row, col);
        String lang = (language == null || language.isEmpty()) ? "ru" : language; //$NON-NLS-1$
        LocalString ls = c.getText();
        if (ls == null)
        {
            ls = ContentFactory.eINSTANCE.createLocalString();
            c.setText(ls);
        }
        ls.getContent().put(lang, text == null ? "" : text); //$NON-NLS-1$
        if (text != null && !text.isEmpty())
        {
            // An empty string on an empty cell is a cell the file carries, not a table: a read
            // does not list such a cell, and the extent must not grow for one.
            settleExtent(doc);
        }
    }

    /**
     * The cell at a 1-based position, creating the row and the cell when they are absent.
     * <p>
     * The moxel maps are 0-based. The conversion stays at this boundary so every writer lands on
     * the same physical cell.
     * </p>
     *
     * @param doc the spreadsheet
     * @param row 1-based row index
     * @param col 1-based column index
     * @return the cell
     */
    private static Cell cellAt(SpreadsheetDocument doc, int row, int col)
    {
        if (doc == null)
        {
            throw new IllegalArgumentException("doc must not be null"); //$NON-NLS-1$
        }
        if (row < 1 || col < 1)
        {
            throw new IllegalArgumentException("row and col must be 1-based positive integers"); //$NON-NLS-1$
        }
        int rowKey = row - 1;
        int colKey = col - 1;
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
        return c;
    }

    /**
     * Why a fill request cannot be applied.
     * <p>
     * A parameter cell is how a template is filled from BSL: the cell carries the parameter name and
     * its format says the fill is a parameter. A template cell keeps the text, placeholders and all.
     * Asking for a parameter without a name, for plain text and a name together, or for a part the
     * file does not store on that fill, is refused here so the call does not report success and
     * leave the cell changed in the model alone.
     * </p>
     *
     * @param fillType text, parameter or template; empty means parameter when a name is present
     * @param parameter the parameter name, or {@code null}
     * @param textPassed whether the caller named a text, including an empty one
     * @return the reason, or {@code null} when the request can be applied
     */
    public static String fillProblem(String fillType, String parameter, boolean textPassed)
    {
        boolean named = parameter != null && !parameter.isEmpty();
        if ((fillType == null || fillType.isEmpty()) && !named)
        {
            return null;
        }
        String kind = (fillType == null || fillType.isEmpty()) ? "parameter" : fillType; //$NON-NLS-1$
        FillType resolved = enumNamed(FillType.VALUES, kind);
        if (resolved == null)
        {
            return "fillType must be one of " + enumLiterals(FillType.VALUES) + " - got: " + kind; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (resolved == FillType.PARAMETER && !named)
        {
            return "fillType parameter needs a parameter name"; //$NON-NLS-1$
        }
        if (resolved == FillType.TEXT && named)
        {
            return "fillType text does not take a parameter"; //$NON-NLS-1$
        }
        if (resolved == FillType.PARAMETER && textPassed)
        {
            return "fillType parameter does not store text - the value arrives from BSL when the " //$NON-NLS-1$
                + "template is filled"; //$NON-NLS-1$
        }
        if (resolved == FillType.TEMPLATE && named)
        {
            return "fillType template does not take a parameter - the placeholders in the text name " //$NON-NLS-1$
                + "what BSL fills"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Sets a cell's text, its template parameter, and how the cell is filled.
     * <p>
     * Text is written only when the caller passed it, or when the call is a plain text write. A
     * parameter on its own must not blank the text that was already there. The fill lives on the
     * cell's format, copied the same way as any other presentation change. A fill that asks for a
     * part the file does not store on it - text on a parameter fill, a name on a template fill - is
     * refused before anything is written. A text or template fill also drops the parameter name an
     * earlier call may have left on the cell: the file stores that name only on a parameter fill.
     * </p>
     *
     * @param doc the spreadsheet
     * @param row 1-based row index
     * @param col 1-based column index
     * @param text the text to write
     * @param textPassed whether the caller named a text, including an empty one
     * @param language language tag for the text; {@code null} means {@code ru}
     * @param fillType text, parameter or template, or {@code null} to leave the fill
     * @param parameter the parameter name, or {@code null} to leave it
     * @return {@code null} when applied, or the reason it was refused
     */
    public static String setCellContent(SpreadsheetDocument doc, int row, int col, String text,
        boolean textPassed, String language, String fillType, String parameter)
    {
        String problem = fillProblem(fillType, parameter, textPassed);
        if (problem != null)
        {
            return problem;
        }
        boolean named = parameter != null && !parameter.isEmpty();
        String kind = fillType;
        if ((kind == null || kind.isEmpty()) && named)
        {
            kind = "parameter"; //$NON-NLS-1$
        }
        boolean legacy = !textPassed && (kind == null || kind.isEmpty());
        if (textPassed || legacy)
        {
            setCellText(doc, row, col, text, language);
        }
        else
        {
            cellAt(doc, row, col);
        }
        Cell cell = cellAt(doc, row, col);
        if (named)
        {
            cell.setParameter(parameter);
        }
        else if (kind != null)
        {
            FillType resolved = enumNamed(FillType.VALUES, kind);
            if (resolved == FillType.TEXT || resolved == FillType.TEMPLATE)
            {
                cell.setParameter(null);
            }
        }
        if (kind != null && !kind.isEmpty())
        {
            CellLook look = new CellLook();
            look.fillType = kind;
            FormatOutcome outcome = applyCellFormat(doc, row, col, row, col, null, null, null,
                null, null, null, look);
            if (outcome.error != null)
            {
                return outcome.error;
            }
        }
        if ((text != null && !text.isEmpty()) || named || (kind != null && !kind.isEmpty()))
        {
            // A write that left the cell as empty as it found it grows nothing: an empty string on
            // an empty cell is a cell the file carries, not a row the table gained.
            settleExtent(doc);
        }
        return null;
    }

    /** Smallest rotation a caller may ask for, in degrees. */
    private static final int MIN_TEXT_DEGREES = 0;

    /** Largest rotation a caller may ask for, in degrees. A full circle, and no further. */
    private static final int MAX_TEXT_DEGREES = 360;

    /**
     * How many model units one degree is. Templates store the angle in tenths of a degree: a
     * vertical caption is 900, which the editor shows as 90 degrees.
     */
    private static final int TEXT_ORIENTATION_TENTHS = 10;

    /** The line width a border gets when the caller names a style and no width. */
    private static final int DEFAULT_BORDER_WIDTH = 1;

    /** Face used when a font is asked for and the cell has none to copy. */
    private static final String DEFAULT_FONT_FACE = "Arial"; //$NON-NLS-1$

    /** Height, in the units the template stores, of a font created from nothing. */
    private static final float DEFAULT_FONT_HEIGHT = 10f;

    /** Scale the template stores on an absolute font. */
    private static final int DEFAULT_FONT_SCALE = 100;

    /**
     * What a formatting request changes, and what it left alone.
     */
    public static final class FormatOutcome
    {
        /** Cells whose format index moved. */
        public int cellsChanged;

        /** Columns whose width or auto-width changed. */
        public int columnsChanged;

        /** Properties that were asked for and are not supported by this runtime's model. */
        public final java.util.List<String> unsupported = new java.util.ArrayList<>();

        /** Why nothing happened, when nothing did. */
        public String error;
    }

    /**
     * Presentation and print settings asked of a template, beyond placement, rotation and size.
     * <p>
     * Every field is optional. {@code null} means leave that property alone. Colours are
     * {@code #RRGGBB}. Lengths of margins are millimetres. Font height is the number the template
     * stores, in points.
     * </p>
     */
    public static final class CellLook
    {
        /** text, parameter or template. */
        public String fillType;

        /** Line style applied to every side that has no style of its own. */
        public String border;

        /** Width of the lines this request adds. {@code null} takes the default of 1. */
        public Integer borderWidth;

        /** Line style of the left side, or {@code null} to leave it or to take {@link #border}. */
        public String leftBorder;

        /** Line style of the top side. */
        public String topBorder;

        /** Line style of the right side. */
        public String rightBorder;

        /** Line style of the bottom side. */
        public String bottomBorder;

        /** Font face, or {@code null} to keep the cell's. */
        public String fontName;

        /** Font height in the units the template stores, or {@code null} to keep it. */
        public Float fontSize;

        /** Bold, or {@code null} to leave it. */
        public Boolean fontBold;

        /** Italic, or {@code null} to leave it. */
        public Boolean fontItalic;

        /** Underline, or {@code null} to leave it. */
        public Boolean fontUnderline;

        /** Strikeout, or {@code null} to leave it. */
        public Boolean fontStrikeout;

        /** Text colour {@code #RRGGBB}, or {@code null}. */
        public String textColor;

        /** Background colour {@code #RRGGBB}, or {@code null}. */
        public String backColor;

        /** Border colour {@code #RRGGBB}, or {@code null}. */
        public String borderColor;

        /** Pattern colour {@code #RRGGBB}, or {@code null}. */
        public String patternColor;

        /** Fill pattern, or {@code null} to leave it. */
        public String pattern;

        /** portrait or landscape. */
        public String pageOrientation;

        /** Print scale in percent, or {@code null}. */
        public Integer scale;

        /** Copies, or {@code null}. */
        public Integer copies;

        /** Pages per sheet, or {@code null}. */
        public Integer perPage;

        /** Fit to page, or {@code null} to leave it. */
        public Boolean fitToPage;

        /** Top margin in millimetres, or {@code null}. Fractions allowed. */
        public Float topMargin;

        /** Left margin in millimetres, or {@code null}. Fractions allowed. */
        public Float leftMargin;

        /** Bottom margin in millimetres, or {@code null}. Fractions allowed. */
        public Float bottomMargin;

        /** Right margin in millimetres, or {@code null}. Fractions allowed. */
        public Float rightMargin;

        /**
         * Whether any cell-presentation field is set.
         *
         * @return {@code true} when a cell's format would change
         */
        public boolean changesCells()
        {
            return filled(fillType) || filled(border) || borderWidth != null || filled(leftBorder)
                || filled(topBorder) || filled(rightBorder) || filled(bottomBorder)
                || filled(fontName) || fontSize != null || fontBold != null || fontItalic != null
                || fontUnderline != null || fontStrikeout != null || filled(textColor)
                || filled(backColor) || filled(borderColor) || filled(patternColor)
                || filled(pattern);
        }

        /**
         * Whether any print field is set.
         *
         * @return {@code true} when the document's print settings would change
         */
        public boolean changesPrint()
        {
            return filled(pageOrientation) || scale != null || copies != null || perPage != null
                || fitToPage != null || topMargin != null || leftMargin != null
                || bottomMargin != null || rightMargin != null;
        }

        /**
         * Whether a string field carries a request. Empty and {@code null} both mean "leave it
         * alone", and this is the one place that rule is spelled out for every string field of the
         * request.
         *
         * @param value the field's value
         * @return {@code true} when the field asks for a change
         */
        private static boolean filled(String value)
        {
            return value != null && !value.isEmpty();
        }
    }

    /**
     * The text placement a caller named, in whatever case they wrote it.
     * <p>
     * Asked of the values the enum publishes rather than through {@code getByName} with an
     * upper-cased word: the names in the model are not upper case, so that lookup answered nothing
     * for every value the tool published as allowed.
     * </p>
     *
     * @param wanted the value as the caller wrote it
     * @return the placement, or <code>null</code> when nothing of that name exists
     */
    private static TextPlacement placementNamed(String wanted)
    {
        for (TextPlacement candidate : TextPlacement.VALUES)
        {
            if (candidate.getName().equalsIgnoreCase(wanted)
                || candidate.getLiteral().equalsIgnoreCase(wanted))
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The placements this model has, for the refusal.
     *
     * @return the literals, comma separated
     */
    private static String placementValues()
    {
        StringBuilder values = new StringBuilder();
        for (TextPlacement candidate : TextPlacement.VALUES)
        {
            if (values.length() > 0)
            {
                values.append(", "); //$NON-NLS-1$
            }
            values.append(candidate.getLiteral());
        }
        return values.toString();
    }

    /**
     * Applies presentation properties to a rectangle of cells, and column width to its columns.
     * <p>
     * Formats in a spreadsheet are shared: a cell does not own one, it points at an index in the
     * document's format table. So changing one cell means finding - or adding - a format that
     * differs from its current one in exactly the requested properties, and repointing the cell.
     * Editing the format in place would silently restyle every other cell that happens to share it,
     * and in a platform-authored template that is most of the sheet.
     * </p>
     *
     * @param doc the spreadsheet.
     * @param fromRow first row, 1-based inclusive.
     * @param fromCol first column, 1-based inclusive.
     * @param toRow last row, inclusive.
     * @param toCol last column, inclusive.
     * @param placement text placement (wrap / cut / block / auto), or {@code null} to leave it.
     * @param orientation text rotation in degrees from 0 to 360, or {@code null} to leave it. The
     *            template stores tenths of a degree, so 90 is written as 900.
     * @param rowHeight an explicit row height, or {@code null} to leave it. There is no
     *            auto-height flag in this model: a row whose height is unset and whose cells wrap
     *            is what the platform grows to fit the text.
     * @param autoColumnWidth whether column width follows the content, or {@code null} to leave it.
     * @param columnWidth an explicit column width, or {@code null} to leave it.
     * @param widthWeight the column's share when widths are distributed, or {@code null}.
     * @return what changed
     */
    public static FormatOutcome applyCellFormat(SpreadsheetDocument doc, int fromRow, int fromCol,
        int toRow, int toCol, String placement, Integer orientation, Integer rowHeight,
        Boolean autoColumnWidth, Integer columnWidth, Integer widthWeight)
    {
        return applyCellFormat(doc, fromRow, fromCol, toRow, toCol, placement, orientation,
            rowHeight, autoColumnWidth, columnWidth, widthWeight, null);
    }

    /**
     * Applies presentation properties to a rectangle of cells, and column width to its columns.
     * <p>
     * The same sharing rule as the shorter form. {@code look} carries borders, font, colours, the
     * fill and the pattern; {@code null} leaves all of those alone. A font change rides on the font
     * the cell renders with - its own format's, else the row's, else the column's, else the default
     * format's - and a font the model exposes no setters for is refused before anything changes.
     * Print settings are not applied here - they belong to the document, and
     * {@link #applyPrintSettings} writes them.
     * </p>
     *
     * @param doc the spreadsheet.
     * @param fromRow first row, 1-based inclusive.
     * @param fromCol first column, 1-based inclusive.
     * @param toRow last row, inclusive.
     * @param toCol last column, inclusive.
     * @param placement text placement (wrap / cut / block / auto), or {@code null} to leave it.
     * @param orientation text rotation in degrees from 0 to 360, or {@code null} to leave it. The
     *            template stores tenths of a degree, so 90 is written as 900.
     * @param rowHeight an explicit row height, or {@code null} to leave it.
     * @param autoColumnWidth whether column width follows the content, or {@code null} to leave it.
     * @param columnWidth an explicit column width, or {@code null} to leave it.
     * @param widthWeight the column's share when widths are distributed, or {@code null}.
     * @param look further presentation, or {@code null}.
     * @return what changed
     */
    public static FormatOutcome applyCellFormat(SpreadsheetDocument doc, int fromRow, int fromCol,
        int toRow, int toCol, String placement, Integer orientation, Integer rowHeight,
        Boolean autoColumnWidth, Integer columnWidth, Integer widthWeight, CellLook look)
    {
        FormatOutcome outcome = new FormatOutcome();
        String problem = presentationProblem(orientation, look);
        if (problem != null)
        {
            outcome.error = problem;
            return outcome;
        }
        if (doc == null)
        {
            outcome.error = "no spreadsheet to format"; //$NON-NLS-1$
            return outcome;
        }
        TextPlacement wanted = null;
        if (placement != null && !placement.isEmpty())
        {
            wanted = placementNamed(placement);
            if (wanted == null)
            {
                // The values the enum itself publishes, not a list written beside it: the two had
                // drifted, and every value of the published list was refused.
                outcome.error = "textPlacement must be one of " + placementValues() + " - got: " //$NON-NLS-1$ //$NON-NLS-2$
                    + placement;
                return outcome;
            }
        }
        boolean fontAsked = fontRequestOf(look) != null;
        if (fontAsked)
        {
            // Checked before the line and colour tables grow, so a refusal leaves nothing behind.
            String refusal = uneditableFontProblem(doc, fromRow, fromCol, toRow, toCol);
            if (refusal != null)
            {
                outcome.error = refusal;
                return outcome;
            }
        }
        FormatDelta cellsWanted = cellDelta(doc, wanted, orientation, look);
        FormatDelta heightWanted = new FormatDelta();
        heightWanted.height = rowHeight;
        EMap<Integer, Row> rows = doc.getRows();
        for (int row = fromRow; row <= toRow; row++)
        {
            Row r = rows.get(Integer.valueOf(row - 1));
            if (r == null)
            {
                r = MoxelFactory.eINSTANCE.createRow();
                rows.put(Integer.valueOf(row - 1), r);
            }
            if (rowHeight != null)
            {
                // Height belongs to the ROW's own format, not to the cells in it - a cell cannot
                // make the line it sits on taller by itself.
                r.setFormatIndex(indexOfFormatWith(doc, r.getFormatIndex(), heightWanted, null));
            }
            EMap<Integer, Cell> cells = r.getCells();
            for (int col = fromCol; col <= toCol; col++)
            {
                Cell c = cells.get(Integer.valueOf(col - 1));
                if (c == null)
                {
                    c = MoxelFactory.eINSTANCE.createCell();
                    cells.put(Integer.valueOf(col - 1), c);
                }
                int before = c.getFormatIndex();
                Font inherited = fontAsked ? inheritedCellFont(doc, r, c, col - 1) : null;
                int after = indexOfFormatWith(doc, before, cellsWanted, inherited);
                if (after != before)
                {
                    c.setFormatIndex(after);
                    outcome.cellsChanged++;
                }
            }
        }
        if (autoColumnWidth != null || columnWidth != null || widthWeight != null)
        {
            outcome.columnsChanged =
                applyColumnFormat(doc, fromCol, toCol, autoColumnWidth, columnWidth, widthWeight);
        }
        return outcome;
    }

    /**
     * Points a range of columns at a format carrying the requested width behaviour.
     * <p>
     * A width belongs to a column, and columns live in the document's column set. A template that
     * was authored with cells but never with a column set has none - and returning there left the
     * caller with an answer saying nothing about the width, after a call that named one. The set is
     * therefore made, and its declared size grown to cover the columns being formatted: a set that
     * stopped short of them would put the width outside the set the page is measured by.
     * </p>
     *
     * @param doc the spreadsheet.
     * @param fromCol first column, 1-based inclusive.
     * @param toCol last column, inclusive.
     * @param autoWidth whether the width follows the content, or {@code null} to leave it.
     * @param width an explicit width, or {@code null} to leave it.
     * @param weight the column's share when widths are distributed, or {@code null}.
     * @return how many columns moved
     */
    private static int applyColumnFormat(SpreadsheetDocument doc, int fromCol, int toCol,
        Boolean autoWidth, Integer width, Integer weight)
    {
        Columns columns = doc.getColumns();
        if (columns == null)
        {
            columns = MoxelFactory.eINSTANCE.createColumns();
            doc.setColumns(columns);
        }
        if (columns.getSize() < toCol)
        {
            columns.setSize(toCol);
        }
        EMap<Integer, Column> byIndex = columns.getColumns();
        int changed = 0;
        for (int col = fromCol; col <= toCol; col++)
        {
            Column c = byIndex.get(Integer.valueOf(col - 1));
            if (c == null)
            {
                c = MoxelFactory.eINSTANCE.createColumn();
                byIndex.put(Integer.valueOf(col - 1), c);
            }
            int before = c.getFormatIndex();
            FormatDelta widthWanted = new FormatDelta();
            widthWanted.autoWidth = autoWidth;
            widthWanted.width = width;
            widthWanted.weight = weight;
            int after = indexOfFormatWith(doc, before, widthWanted, null);
            if (after != before)
            {
                c.setFormatIndex(after);
                changed++;
            }
        }
        return changed;
    }

    /**
     * The properties a format should gain. A null field keeps the base format's value.
     */
    private static final class FormatDelta
    {
        private TextPlacement placement;

        /** Degrees, not tenths. Written multiplied by ten. */
        private Integer orientationDegrees;

        private Integer height;

        private Boolean autoWidth;

        private Integer width;

        private Integer weight;

        private FillType fillType;

        private FontRequest font;

        private Integer leftBorder;

        private Integer topBorder;

        private Integer rightBorder;

        private Integer bottomBorder;

        private Integer textColor;

        private Integer backColor;

        private Integer borderColor;

        private Integer patternColor;

        private Pattern pattern;
    }

    /**
     * A font change asked relative to the font the cell already has.
     */
    private static final class FontRequest
    {
        private String face;

        private Float size;

        private Boolean bold;

        private Boolean italic;

        private Boolean underline;

        private Boolean strikeout;
    }

    /**
     * Finds - or adds - a format that differs from an existing one in exactly the given properties.
     * <p>
     * Reuse first. A template that sets the same wrap on two hundred cells should end with one new
     * format, not two hundred: the format table is written into the file, and a table that grows by
     * one entry per formatted cell makes the template larger every time it is touched.
     * </p>
     * <p>
     * Index 0 is the "no format" slot: the serializer skips it when writing and the reader looks at
     * a cell's format only when the index is not 0. A document loaded from a file has an empty
     * placeholder there, so a document whose table is still empty gets one before its first real
     * format - otherwise that format would land at 0 and neither the file nor the base would ever
     * see it. Because the placeholder is empty, a format carrying any property never equals it and
     * this never returns 0 for one.
     * </p>
     *
     * @param doc the spreadsheet holding the format table.
     * @param baseIndex the format the element points at now.
     * @param delta the properties to change. Rotation is in degrees and is stored as tenths.
     * @param inheritedFont the font the element renders with when its own format names none - the
     *            base a font change rides on; {@code null} when the caller has none to offer
     * @return the index to point at
     */
    private static int indexOfFormatWith(SpreadsheetDocument doc, int baseIndex, FormatDelta delta,
        Font inheritedFont)
    {
        EList<Format> formats = doc.getFormats();
        ensureFormatPlaceholder(doc);
        Format base = baseIndex >= 0 && baseIndex < formats.size() ? formats.get(baseIndex) : null;
        Format wanted = MoxelFactory.eINSTANCE.createFormat();
        if (base != null)
        {
            wanted = EcoreUtil.copy(base);
        }
        if (delta.placement != null)
        {
            wanted.setTextPlacement(delta.placement);
        }
        if (delta.orientationDegrees != null)
        {
            wanted.setTextOrientation(delta.orientationDegrees.intValue() * TEXT_ORIENTATION_TENTHS);
        }
        if (delta.height != null)
        {
            wanted.setHeight(delta.height.intValue());
        }
        if (delta.autoWidth != null)
        {
            wanted.setAutoWidthCalculation(delta.autoWidth.booleanValue());
        }
        if (delta.width != null)
        {
            wanted.setWidth(delta.width.intValue());
        }
        if (delta.weight != null)
        {
            wanted.setWidthWeightFactor(delta.weight.intValue());
        }
        if (delta.fillType != null)
        {
            wanted.setFillType(delta.fillType);
        }
        if (delta.pattern != null)
        {
            wanted.setPattern(delta.pattern);
        }
        if (delta.leftBorder != null)
        {
            wanted.setLeftBorder(delta.leftBorder.intValue());
        }
        if (delta.topBorder != null)
        {
            wanted.setTopBorder(delta.topBorder.intValue());
        }
        if (delta.rightBorder != null)
        {
            wanted.setRightBorder(delta.rightBorder.intValue());
        }
        if (delta.bottomBorder != null)
        {
            wanted.setBottomBorder(delta.bottomBorder.intValue());
        }
        if (delta.textColor != null)
        {
            wanted.setTextColor(delta.textColor.intValue());
        }
        if (delta.backColor != null)
        {
            wanted.setBackColor(delta.backColor.intValue());
        }
        if (delta.borderColor != null)
        {
            wanted.setBorderColor(delta.borderColor.intValue());
        }
        if (delta.patternColor != null)
        {
            wanted.setPatternColor(delta.patternColor.intValue());
        }
        if (delta.font != null)
        {
            Font baseFont = fontOfFormat(doc, base);
            if (baseFont == null)
            {
                baseFont = inheritedFont;
            }
            wanted.setFont(indexOfFont(doc, baseFont, delta.font));
        }
        for (int i = 0; i < formats.size(); i++)
        {
            if (EcoreUtil.equals(formats.get(i), wanted))
            {
                return i;
            }
        }
        formats.add(wanted);
        return formats.size() - 1;
    }

    /**
     * Gives a document with no formats the empty placeholder a file-loaded document has at index 0,
     * and points the default format index at it.
     * <p>
     * The serializer and the reader both read index 0 as "no format", so a real format placed there
     * is lost on write and every formatless cell inherits it on read - which is what the first
     * format added to a freshly created document did. Mirrors what
     * {@code SheetAccessor.removeFormats} does to a document it reads from a file.
     * </p>
     *
     * @param doc the spreadsheet; a document that already has formats is left as it is
     */
    private static void ensureFormatPlaceholder(SpreadsheetDocument doc)
    {
        if (!doc.getFormats().isEmpty())
        {
            return;
        }
        doc.getFormats().add(MoxelFactory.eINSTANCE.createFormat());
        doc.setDefaultFormatIndex(0);
    }

    /**
     * The font a format names, or {@code null} when it names none or names one outside the table.
     *
     * @param doc the spreadsheet holding the font table
     * @param format the format, or {@code null}
     * @return the font, or {@code null}
     */
    private static Font fontOfFormat(SpreadsheetDocument doc, Format format)
    {
        if (format == null || !format.isSetFont())
        {
            return null;
        }
        int index = format.getFont();
        if (index < 0 || index >= doc.getFonts().size())
        {
            return null;
        }
        return doc.getFonts().get(index);
    }

    /**
     * The font a cell renders with when its own format names none: the row's, then the column's,
     * then the document default format's. The column is read from the row's own column set when the
     * row carries one, and from the document's otherwise.
     * <p>
     * A platform-authored template carries its look on the row and column formats, and a bold asked
     * of a cell that only inherits that look has to ride on the inherited font - rebuilding the
     * font from defaults would trade Verdana 8 for Arial 10 and nobody asked for that.
     * </p>
     *
     * @param doc the spreadsheet
     * @param row the cell's row
     * @param cell the cell
     * @param colKey the cell's 0-based column key
     * @return the font the cell renders with, or {@code null} when nothing names one
     */
    static Font inheritedCellFont(SpreadsheetDocument doc, Row row, Cell cell, int colKey)
    {
        Font own = fontOfFormat(doc, formatAt(doc, cell.getFormatIndex()));
        if (own != null)
        {
            return own;
        }
        Font rowFont = fontOfFormat(doc, formatAt(doc, row.getFormatIndex()));
        if (rowFont != null)
        {
            return rowFont;
        }
        Columns columns = row.getColumns() != null ? row.getColumns() : doc.getColumns();
        Column column = columns == null ? null : columns.getColumns().get(Integer.valueOf(colKey));
        Font columnFont = fontOfFormat(doc,
            column == null ? null : formatAt(doc, column.getFormatIndex()));
        if (columnFont != null)
        {
            return columnFont;
        }
        return fontOfFormat(doc, formatAt(doc, doc.getDefaultFormatIndex()));
    }

    /**
     * Why a font change cannot ride on the fonts a rectangle of cells renders with, or
     * {@code null} when it can.
     * <p>
     * A font the model exposes no setters for cannot carry a bold or a size change, and quietly
     * replacing it with an absolute font would drop the style it points at. The refusal names the
     * font so the caller knows what to name in full.
     * </p>
     *
     * @param doc the spreadsheet
     * @param fromRow first row, 1-based inclusive
     * @param fromCol first column, 1-based inclusive
     * @param toRow last row, inclusive
     * @param toCol last column, inclusive
     * @return the reason, or {@code null} when every cell's font can carry the change
     */
    private static String uneditableFontProblem(SpreadsheetDocument doc, int fromRow, int fromCol,
        int toRow, int toCol)
    {
        EMap<Integer, Row> rows = doc.getRows();
        for (int row = fromRow; row <= toRow; row++)
        {
            Row r = rows.get(Integer.valueOf(row - 1));
            if (r == null)
            {
                continue;
            }
            for (int col = fromCol; col <= toCol; col++)
            {
                Cell c = r.getCells().get(Integer.valueOf(col - 1));
                if (c == null)
                {
                    continue;
                }
                Font font = inheritedCellFont(doc, r, c, col - 1);
                if (font != null && !(font instanceof MutableFont))
                {
                    return "cannot change the font of a cell rendered by a " //$NON-NLS-1$
                        + font.eClass().getName() + " this runtime exposes no setters for (" //$NON-NLS-1$
                        + font.faceName() + ") - set fontName, fontSize and the flags in full"; //$NON-NLS-1$
                }
            }
        }
        return null;
    }

    /**
     * The cell properties of a request, with borders and colours already placed in their tables.
     *
     * @param doc the spreadsheet the tables belong to
     * @param placement the resolved placement, or {@code null}
     * @param orientation degrees, or {@code null}
     * @param look further presentation, or {@code null}
     * @return the delta the cell loop applies
     */
    private static FormatDelta cellDelta(SpreadsheetDocument doc, TextPlacement placement,
        Integer orientation, CellLook look)
    {
        FormatDelta delta = new FormatDelta();
        delta.placement = placement;
        delta.orientationDegrees = orientation;
        if (look == null)
        {
            return delta;
        }
        if (look.fillType != null && !look.fillType.isEmpty())
        {
            delta.fillType = enumNamed(FillType.VALUES, look.fillType);
        }
        if (look.pattern != null && !look.pattern.isEmpty())
        {
            delta.pattern = enumNamed(Pattern.VALUES, look.pattern);
        }
        delta.leftBorder = borderIndex(doc, look.leftBorder, look.border, look.borderWidth);
        delta.topBorder = borderIndex(doc, look.topBorder, look.border, look.borderWidth);
        delta.rightBorder = borderIndex(doc, look.rightBorder, look.border, look.borderWidth);
        delta.bottomBorder = borderIndex(doc, look.bottomBorder, look.border, look.borderWidth);
        delta.textColor = colorIndex(doc, look.textColor);
        delta.backColor = colorIndex(doc, look.backColor);
        delta.borderColor = colorIndex(doc, look.borderColor);
        delta.patternColor = colorIndex(doc, look.patternColor);
        delta.font = fontRequestOf(look);
        return delta;
    }

    /**
     * The font change a look asks for, or {@code null} when it asks for none.
     * <p>
     * One place decides which look fields make up a font request: the delta builder and the
     * feasibility check ahead of it must agree on what is a font change, or a look could pass the
     * check and still carry a font into the table.
     * </p>
     *
     * @param look the request's further presentation, or {@code null}
     * @return the font change, or {@code null}
     */
    private static FontRequest fontRequestOf(CellLook look)
    {
        if (look == null)
        {
            return null;
        }
        if (look.fontName != null || look.fontSize != null || look.fontBold != null
            || look.fontItalic != null || look.fontUnderline != null || look.fontStrikeout != null)
        {
            FontRequest font = new FontRequest();
            font.face = look.fontName;
            font.size = look.fontSize;
            font.bold = look.fontBold;
            font.italic = look.fontItalic;
            font.underline = look.fontUnderline;
            font.strikeout = look.fontStrikeout;
            return font;
        }
        return null;
    }

    /**
     * The line-table index of a border, or {@code null} when that side is not part of the request.
     * <p>
     * A side of its own wins over the style asked for every side. The line is reused when the
     * document already has one of that style and width, so a sheet of boxed cells shares one line.
     * </p>
     *
     * @param doc the spreadsheet
     * @param side the style of this side, or empty to fall back to {@code all}
     * @param all the style asked for every side, or empty
     * @param width the line width, or {@code null} for the default
     * @return the index, or {@code null} when this side stays as it is
     */
    private static Integer borderIndex(SpreadsheetDocument doc, String side, String all,
        Integer width)
    {
        String styleName = side != null && !side.isEmpty() ? side : all;
        if (styleName == null || styleName.isEmpty())
        {
            return null;
        }
        CellLineStyle style = enumNamed(CellLineStyle.VALUES, styleName);
        int lineWidth = width == null ? DEFAULT_BORDER_WIDTH : width.intValue();
        return Integer.valueOf(indexOfLine(doc, style, lineWidth));
    }

    /**
     * Finds or adds a cell line of the given style and width.
     *
     * @param doc the spreadsheet
     * @param style the line style
     * @param width the line width
     * @return the index in the document's line table
     */
    private static int indexOfLine(SpreadsheetDocument doc, CellLineStyle style, int width)
    {
        EList<SpreadsheetLine> lines = doc.getLines();
        for (int i = 0; i < lines.size(); i++)
        {
            SpreadsheetLine line = lines.get(i);
            if (line instanceof CellLine)
            {
                CellLine cellLine = (CellLine)line;
                if (cellLine.getStyle() == style && cellLine.getWidth() == width && !cellLine.isGap())
                {
                    return i;
                }
            }
        }
        CellLine created = ContentFactory.eINSTANCE.createCellLine();
        created.setStyle(style);
        created.setWidth(width);
        created.setGap(false);
        lines.add(created);
        return lines.size() - 1;
    }

    /**
     * The colour-table index of a {@code #RRGGBB} colour, or {@code null} when none was asked.
     *
     * @param doc the spreadsheet
     * @param hex the colour, or empty
     * @return the index, or {@code null}
     */
    private static Integer colorIndex(SpreadsheetDocument doc, String hex)
    {
        int[] rgb = rgbOf(hex);
        if (rgb == null)
        {
            return null;
        }
        return Integer.valueOf(indexOfColor(doc, rgb[0], rgb[1], rgb[2]));
    }

    /**
     * Finds or adds an absolute colour.
     *
     * @param doc the spreadsheet
     * @param red red, 0..255
     * @param green green, 0..255
     * @param blue blue, 0..255
     * @return the index in the document's colour table
     */
    private static int indexOfColor(SpreadsheetDocument doc, int red, int green, int blue)
    {
        EList<Color> colors = doc.getColors();
        for (int i = 0; i < colors.size(); i++)
        {
            Color color = colors.get(i);
            if (color instanceof ColorDef)
            {
                ColorDef defined = (ColorDef)color;
                if (defined.getRed() == red && defined.getGreen() == green && defined.getBlue() == blue)
                {
                    return i;
                }
            }
        }
        ColorDef created = McoreFactory.eINSTANCE.createColorDef();
        created.setRed(red);
        created.setGreen(green);
        created.setBlue(blue);
        colors.add(created);
        return colors.size() - 1;
    }

    /**
     * Finds or adds the font a request describes, starting from the font the cell already uses.
     *
     * @param doc the spreadsheet
     * @param base the font the cell renders with, or {@code null}
     * @param request what to change
     * @return the index in the document's font table
     */
    private static int indexOfFont(SpreadsheetDocument doc, Font base, FontRequest request)
    {
        Font wanted = fontWith(base, request);
        if (wanted == null)
        {
            throw new IllegalStateException("a font this runtime exposes no setters for cannot " //$NON-NLS-1$
                + "carry a change: " + (base == null ? null : base.eClass().getName())); //$NON-NLS-1$
        }
        EList<Font> fonts = doc.getFonts();
        for (int i = 0; i < fonts.size(); i++)
        {
            if (EcoreUtil.equals(fonts.get(i), wanted))
            {
                return i;
            }
        }
        fonts.add(wanted);
        return fonts.size() - 1;
    }

    /**
     * A font with the requested fields changed and the rest taken from the base.
     * <p>
     * The base is copied, not rebuilt: a style-bound font keeps its binding and only the requested
     * fields move, so a cell that only asks to be bold stays on the face and height it renders
     * with. A cell with no font anywhere to copy starts from Arial at the default height, which is
     * what a new absolute font in a template carries. A base the model exposes no setters for
     * cannot carry a partial change, and {@code null} says so: the caller refuses rather than
     * replace a style binding with an absolute font.
     * </p>
     *
     * @param base the font to copy, or {@code null}
     * @param request the changes
     * @return the font to store, or {@code null} when the base cannot carry the change
     */
    private static Font fontWith(Font base, FontRequest request)
    {
        Font wanted;
        if (base != null)
        {
            wanted = EcoreUtil.copy(base);
        }
        else
        {
            FontDef fresh = McoreFactory.eINSTANCE.createFontDef();
            fresh.setFaceName(DEFAULT_FONT_FACE);
            fresh.setHeight(DEFAULT_FONT_HEIGHT);
            fresh.setScale(DEFAULT_FONT_SCALE);
            wanted = fresh;
        }
        if (wanted instanceof FontDef && ((FontDef)wanted).getScale() == 0)
        {
            // The model's own default for scale is 100; a font table entry read with 0 is not a
            // usable font and the file stores no scale at all for it.
            ((FontDef)wanted).setScale(DEFAULT_FONT_SCALE);
        }
        if (!(wanted instanceof MutableFont))
        {
            return null;
        }
        MutableFont editable = (MutableFont)wanted;
        if (request.face != null && !request.face.isEmpty())
        {
            editable.setFaceName(request.face);
        }
        if (request.size != null)
        {
            editable.setHeight(request.size.floatValue());
        }
        if (request.bold != null)
        {
            editable.setBold(request.bold.booleanValue());
        }
        if (request.italic != null)
        {
            editable.setItalic(request.italic.booleanValue());
        }
        if (request.underline != null)
        {
            editable.setUnderline(request.underline.booleanValue());
        }
        if (request.strikeout != null)
        {
            editable.setStrikeout(request.strikeout.booleanValue());
        }
        return wanted;
    }

    /**
     * Why a presentation request cannot be applied.
     * <p>
     * Checked before any table grows, so a bad colour or a rotation past a full circle leaves the
     * template untouched. Rotation is degrees, 0 through 360.
     * </p>
     *
     * @param orientation degrees, or {@code null} when rotation is not part of the request
     * @param look the rest of the request, or {@code null}
     * @return the reason, or {@code null} when the request can be applied
     */
    public static String presentationProblem(Integer orientation, CellLook look)
    {
        if (orientation != null && (orientation.intValue() < MIN_TEXT_DEGREES
            || orientation.intValue() > MAX_TEXT_DEGREES))
        {
            return "textOrientation must be between " + MIN_TEXT_DEGREES + " and " //$NON-NLS-1$
                + MAX_TEXT_DEGREES + " degrees - got: " + orientation; //$NON-NLS-1$
        }
        if (look == null)
        {
            return null;
        }
        if (look.fillType != null && !look.fillType.isEmpty()
            && enumNamed(FillType.VALUES, look.fillType) == null)
        {
            return "fillType must be one of " + enumLiterals(FillType.VALUES) + " - got: " //$NON-NLS-1$ //$NON-NLS-2$
                + look.fillType;
        }
        String border = borderProblem("border", look.border); //$NON-NLS-1$
        if (border != null)
        {
            return border;
        }
        border = borderProblem("leftBorder", look.leftBorder); //$NON-NLS-1$
        if (border != null)
        {
            return border;
        }
        border = borderProblem("topBorder", look.topBorder); //$NON-NLS-1$
        if (border != null)
        {
            return border;
        }
        border = borderProblem("rightBorder", look.rightBorder); //$NON-NLS-1$
        if (border != null)
        {
            return border;
        }
        border = borderProblem("bottomBorder", look.bottomBorder); //$NON-NLS-1$
        if (border != null)
        {
            return border;
        }
        boolean anySide = filled(look.border) || filled(look.leftBorder) || filled(look.topBorder)
            || filled(look.rightBorder) || filled(look.bottomBorder);
        if (look.borderWidth != null && !anySide)
        {
            return "borderWidth needs a border style"; //$NON-NLS-1$
        }
        if (look.borderWidth != null && look.borderWidth.intValue() < 0)
        {
            return "borderWidth must be 0 or greater - got: " + look.borderWidth; //$NON-NLS-1$
        }
        String color = colorProblem("textColor", look.textColor); //$NON-NLS-1$
        if (color != null)
        {
            return color;
        }
        color = colorProblem("backColor", look.backColor); //$NON-NLS-1$
        if (color != null)
        {
            return color;
        }
        color = colorProblem("borderColor", look.borderColor); //$NON-NLS-1$
        if (color != null)
        {
            return color;
        }
        color = colorProblem("patternColor", look.patternColor); //$NON-NLS-1$
        if (color != null)
        {
            return color;
        }
        if (look.pattern != null && !look.pattern.isEmpty()
            && enumNamed(Pattern.VALUES, look.pattern) == null)
        {
            return "pattern must be one of " + enumLiterals(Pattern.VALUES) + " - got: " //$NON-NLS-1$ //$NON-NLS-2$
                + look.pattern;
        }
        if (look.fontName != null && look.fontName.trim().isEmpty())
        {
            return "fontName must not be blank"; //$NON-NLS-1$
        }
        if (look.fontSize != null && (!Float.isFinite(look.fontSize.floatValue())
            || look.fontSize.floatValue() <= 0f))
        {
            return "fontSize must be a finite number of points greater than 0 - got: " //$NON-NLS-1$
                + look.fontSize;
        }
        if (look.pageOrientation != null && !look.pageOrientation.isEmpty()
            && enumNamed(PageOrientation.VALUES, look.pageOrientation) == null)
        {
            return "pageOrientation must be one of " + enumLiterals(PageOrientation.VALUES) //$NON-NLS-1$
                + " - got: " + look.pageOrientation; //$NON-NLS-1$
        }
        if (look.scale != null && look.scale.intValue() < 1)
        {
            return "scale must be 1 or greater - got: " + look.scale; //$NON-NLS-1$
        }
        if (look.copies != null && look.copies.intValue() < 1)
        {
            return "copies must be 1 or greater - got: " + look.copies; //$NON-NLS-1$
        }
        if (look.perPage != null && look.perPage.intValue() < 1)
        {
            return "perPage must be 1 or greater - got: " + look.perPage; //$NON-NLS-1$
        }
        String margin = marginProblem("topMargin", look.topMargin); //$NON-NLS-1$
        if (margin != null)
        {
            return margin;
        }
        margin = marginProblem("leftMargin", look.leftMargin); //$NON-NLS-1$
        if (margin != null)
        {
            return margin;
        }
        margin = marginProblem("bottomMargin", look.bottomMargin); //$NON-NLS-1$
        if (margin != null)
        {
            return margin;
        }
        return marginProblem("rightMargin", look.rightMargin); //$NON-NLS-1$
    }

    /**
     * Writes the print settings a request names.
     * <p>
     * Margins are millimetres at the boundary and hundredths of a millimetre in the model, which is
     * how a template file stores a 10 mm margin as 1000; a fraction of a millimetre is stored as
     * the nearest whole hundredth. Only the fields that were passed change.
     * </p>
     *
     * @param doc the spreadsheet
     * @param look the request; print fields that are {@code null} are left alone
     * @return {@code null} when applied, or the reason it was refused
     */
    public static String applyPrintSettings(SpreadsheetDocument doc, CellLook look)
    {
        String problem = presentationProblem(null, look);
        if (problem != null)
        {
            return problem;
        }
        if (doc == null)
        {
            return "no spreadsheet to format"; //$NON-NLS-1$
        }
        if (look == null || !look.changesPrint())
        {
            return null;
        }
        PrintSettings settings = doc.getPrintSettings();
        if (settings == null)
        {
            settings = MoxelFactory.eINSTANCE.createPrintSettings();
            doc.setPrintSettings(settings);
        }
        if (look.pageOrientation != null && !look.pageOrientation.isEmpty())
        {
            settings.setPageOrientation(enumNamed(PageOrientation.VALUES, look.pageOrientation));
        }
        if (look.scale != null)
        {
            settings.setScale(look.scale.intValue());
        }
        if (look.copies != null)
        {
            settings.setCopies(look.copies.intValue());
        }
        if (look.perPage != null)
        {
            settings.setPerPage(look.perPage.intValue());
        }
        if (look.fitToPage != null)
        {
            settings.setFitToPage(look.fitToPage.booleanValue());
        }
        if (look.topMargin != null)
        {
            settings.setTopMargin(hundredthsOf(look.topMargin));
        }
        if (look.leftMargin != null)
        {
            settings.setLeftMargin(hundredthsOf(look.leftMargin));
        }
        if (look.bottomMargin != null)
        {
            settings.setBottomMargin(hundredthsOf(look.bottomMargin));
        }
        if (look.rightMargin != null)
        {
            settings.setRightMargin(hundredthsOf(look.rightMargin));
        }
        return null;
    }

    /**
     * Millimetres in the model's unit: hundredths, rounded to the nearest whole one.
     *
     * @param millimetres the margin in millimetres
     * @return the value the model stores
     */
    private static int hundredthsOf(Float millimetres)
    {
        return (int)Math.round(millimetres.doubleValue() * 100.0);
    }

    /**
     * The rotation a format stores, in degrees.
     * <p>
     * The model keeps tenths of a degree. Dividing by ten is the inverse of the write, so a
     * vertical caption stored as 900 is read back as 90.
     * </p>
     *
     * @param format the format, or {@code null}
     * @return degrees, or {@code null} when the format does not set a rotation
     */
    public static Integer textOrientationDegrees(Format format)
    {
        if (format == null || !format.isSetTextOrientation())
        {
            return null;
        }
        return Integer.valueOf(format.getTextOrientation() / TEXT_ORIENTATION_TENTHS);
    }

    /**
     * Why a border style cannot be applied.
     *
     * @param argument the argument's name, for the message
     * @param style the line style the caller wrote, or empty to leave it
     * @return the reason, or {@code null} when the style is one the model has
     */
    private static String borderProblem(String argument, String style)
    {
        if (style == null || style.isEmpty())
        {
            return null;
        }
        if (enumNamed(CellLineStyle.VALUES, style) == null)
        {
            return argument + " must be one of " + enumLiterals(CellLineStyle.VALUES) + " - got: " //$NON-NLS-1$ //$NON-NLS-2$
                + style;
        }
        return null;
    }

    /**
     * Why a colour cannot be applied.
     *
     * @param argument the argument's name, for the message
     * @param hex the colour as {@code #RRGGBB}, or empty to leave it
     * @return the reason, or {@code null} when the colour parses
     */
    private static String colorProblem(String argument, String hex)
    {
        if (hex == null || hex.isEmpty())
        {
            return null;
        }
        if (rgbOf(hex) == null)
        {
            return argument + " must be #RRGGBB - got: " + hex; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Why a margin cannot be applied.
     * <p>
     * The model stores hundredths of a millimetre in an int, so a margin is bounded by what that
     * conversion can hold; fractions of a millimetre are fine, values the int cannot store are not.
     * </p>
     *
     * @param argument the argument's name, for the message
     * @param millimetres the margin in millimetres, or {@code null}
     * @return the reason, or {@code null} when the margin can be applied
     */
    private static String marginProblem(String argument, Float millimetres)
    {
        if (millimetres == null)
        {
            return null;
        }
        if (!Float.isFinite(millimetres.floatValue()))
        {
            return argument + " must be a finite number of millimetres - got: " + millimetres; //$NON-NLS-1$
        }
        if (millimetres.floatValue() < 0f)
        {
            return argument + " must be 0 or greater millimetres - got: " + millimetres; //$NON-NLS-1$
        }
        if (millimetres.doubleValue() * 100.0 > Integer.MAX_VALUE)
        {
            return argument + " must be " + (Integer.MAX_VALUE / 100) + " millimetres or less, " //$NON-NLS-1$ //$NON-NLS-2$
                + "what the model's int can store - got: " + millimetres; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Whether a request's string field asks for anything. The same rule as
     * {@link CellLook}'s own {@code filled}: {@code null} and empty both mean "leave it alone".
     *
     * @param value the field's value
     * @return {@code true} when the field names a value
     */
    private static boolean filled(String value)
    {
        return value != null && !value.isEmpty();
    }

    /**
     * An enumerator by the name or the literal a caller wrote, ignoring case and underscores.
     *
     * @param values the enumerator's published values
     * @param wanted the text the caller wrote
     * @param <T> the enumerator type
     * @return the value, or {@code null} when nothing matches
     */
    private static <T extends Enumerator> T enumNamed(List<T> values, String wanted)
    {
        if (wanted == null)
        {
            return null;
        }
        String folded = wanted.trim().replace("_", "").replace("-", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (T candidate : values)
        {
            String literal = candidate.getLiteral() == null ? "" //$NON-NLS-1$
                : candidate.getLiteral().replace("_", ""); //$NON-NLS-1$
            String name = candidate.getName() == null ? "" //$NON-NLS-1$
                : candidate.getName().replace("_", ""); //$NON-NLS-1$
            if (literal.equalsIgnoreCase(folded) || name.equalsIgnoreCase(folded))
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The literals an enumerator publishes, for a refusal.
     *
     * @param values the enumerator's published values
     * @return the literals, comma separated
     */
    private static String enumLiterals(List<? extends Enumerator> values)
    {
        StringBuilder text = new StringBuilder();
        for (Enumerator candidate : values)
        {
            if (text.length() > 0)
            {
                text.append(", "); //$NON-NLS-1$
            }
            text.append(candidate.getLiteral());
        }
        return text.toString();
    }

    /**
     * {@code #RRGGBB} or {@code RRGGBB} as three channels, or {@code null} when it is not that.
     * <p>
     * Every character must be a hexadecimal digit: the number parser also accepts a sign, and a
     * colour of signed pairs would silently read as a valid dark grey.
     * </p>
     *
     * @param hex the text
     * @return red, green and blue, or {@code null}
     */
    private static int[] rgbOf(String hex)
    {
        if (hex == null)
        {
            return null;
        }
        String text = hex.trim();
        if (text.startsWith("#")) //$NON-NLS-1$
        {
            text = text.substring(1);
        }
        if (text.length() != 6)
        {
            return null;
        }
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean lower = c >= 'a' && c <= 'f';
            boolean upper = c >= 'A' && c <= 'F';
            if (!digit && !lower && !upper)
            {
                return null;
            }
        }
        try
        {
            return new int[] {
                Integer.parseInt(text.substring(0, 2), 16),
                Integer.parseInt(text.substring(2, 4), 16),
                Integer.parseInt(text.substring(4, 6), 16)
            };
        }
        catch (NumberFormatException notHex)
        {
            return null;
        }
    }

    /**
     * The span of a merge as the platform stores it: the cells AFTER the first.
     * <p>
     * The file says which it is. A merge of one cell carries no span at all in the mxl
     * serialization, and that reads back as zero - a count of cells would have been one. Written as
     * a count, a merge of three columns opened in the environment as four.
     * </p>
     *
     * @param from the first cell, 1-based
     * @param to the last cell, 1-based and inclusive
     * @return what goes into Rect width or height
     */
    private static int mergeSpan(int from, int to)
    {
        return to - from;
    }

    /**
     * The last cell of a merge, from the span the platform stored.
     * <p>
     * The reverse of {@link #mergeSpan}, and here so the two cannot drift apart. A negative span is
     * read as none, which is how an absent one arrives.
     * </p>
     *
     * @param from the first cell, 1-based
     * @param span the Rect width or height
     * @return the last cell, 1-based and inclusive
     */
    private static int mergeFarCorner(int from, int span)
    {
        return from + Math.max(0, span);
    }

    /**
     * Merges a rectangle of cells, adding a {@link Merge} with the requested {@link Rect}.
     * <p>
     * Both the {@code from} and {@code to} corners are inclusive, and coordinates are 1-based.
     * Rect carries X=col and Y=row 0-based, and Width and Height as the cells AFTER the first -
     * see {@link #mergeSpan}.
     * </p>
     *
     * @param doc the spreadsheet
     * @param fromRow the first row, 1-based
     * @param fromCol the first column, 1-based
     * @param toRow the last row, 1-based and inclusive
     * @param toCol the last column, 1-based and inclusive
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
        // Rect X=col, Y=row are 0-based in the moxel model (see setCellText); convert the 1-based
        // corners. Width and Height are the cells AFTER the first - see mergeSpan.
        rect.setX(fromCol - 1);
        rect.setY(fromRow - 1);
        rect.setWidth(mergeSpan(fromCol, toCol));
        rect.setHeight(mergeSpan(fromRow, toRow));
        merge.setPosition(rect);
        doc.getMerges().add(merge);
        settleExtent(doc);
    }

    /**
     * The named areas a template carries, in the order the document holds them.
     * <p>
     * A named area is what the "load data from a file" mechanism reads a template by: the area name
     * becomes the name of the loaded column. A template without them cannot be used for loading,
     * whatever its cells say.
     * </p>
     * <p>
     * Bounds are reported 1-based, the way every other coordinate on this class is. An area kind
     * this method does not recognise is reported by its class name without bounds, not dropped.
     * </p>
     *
     * @param doc the document; may be <code>null</code>
     * @return one map per area, with name, kind and bounds; never <code>null</code>
     */
    public static List<Map<String, Object>> listNamedAreas(SpreadsheetDocument doc)
    {
        List<Map<String, Object>> areas = new ArrayList<>();
        if (doc == null)
        {
            return areas;
        }
        for (Map.Entry<String, NamedItem> held : doc.getNamedItems())
        {
            Map<String, Object> area = new LinkedHashMap<>();
            area.put("name", held.getKey()); //$NON-NLS-1$
            describeNamedItem(held.getValue(), area);
            areas.add(area);
        }
        return areas;
    }

    private static void describeNamedItem(NamedItem item, Map<String, Object> into)
    {
        if (!(item instanceof NamedItemCells))
        {
            into.put("kind", item == null ? "empty" //$NON-NLS-1$ //$NON-NLS-2$
                : item.getClass().getSimpleName());
            return;
        }
        Area area = ((NamedItemCells)item).getArea();
        if (area instanceof ColumnsArea)
        {
            ColumnsArea columns = (ColumnsArea)area;
            into.put("kind", "columns"); //$NON-NLS-1$ //$NON-NLS-2$
            into.put("fromCol", Integer.valueOf(columns.getBegin() + 1)); //$NON-NLS-1$
            into.put("toCol", Integer.valueOf(columns.getEnd() + 1)); //$NON-NLS-1$
        }
        else if (area instanceof RowsArea)
        {
            RowsArea rows = (RowsArea)area;
            into.put("kind", "rows"); //$NON-NLS-1$ //$NON-NLS-2$
            into.put("fromRow", Integer.valueOf(rows.getBegin() + 1)); //$NON-NLS-1$
            into.put("toRow", Integer.valueOf(rows.getEnd() + 1)); //$NON-NLS-1$
        }
        else if (area instanceof RectArea && ((RectArea)area).getPosition() != null)
        {
            Rect position = ((RectArea)area).getPosition();
            int[] rows = boundsOf(position, Axis.ROW);
            int[] columns = boundsOf(position, Axis.COLUMN);
            into.put("kind", "rect"); //$NON-NLS-1$ //$NON-NLS-2$
            into.put("fromRow", Integer.valueOf(rows[0] + 1)); //$NON-NLS-1$
            into.put("fromCol", Integer.valueOf(columns[0] + 1)); //$NON-NLS-1$
            into.put("toRow", Integer.valueOf(rows[1] + 1)); //$NON-NLS-1$
            into.put("toCol", Integer.valueOf(columns[1] + 1)); //$NON-NLS-1$
        }
        else
        {
            into.put("kind", area == null ? "empty" //$NON-NLS-1$ //$NON-NLS-2$
                : area.getClass().getSimpleName());
        }
    }

    /**
     * Names an area of the template, replacing an area of that name when there is one.
     *
     * @param doc the document
     * @param name the area name - this is what the loading mechanism reads
     * @param kind columns, rows or rect
     * @param fromRow first row, 1-based; unused for columns
     * @param fromCol first column, 1-based; unused for rows
     * @param toRow last row, 1-based; unused for columns
     * @param toCol last column, 1-based; unused for rows
     */
    public static void addNamedArea(SpreadsheetDocument doc, String name, String kind, int fromRow,
        int fromCol, int toRow, int toCol)
    {
        if (doc == null)
        {
            throw new IllegalArgumentException("doc must not be null"); //$NON-NLS-1$
        }
        if (name == null || name.trim().isEmpty())
        {
            throw new IllegalArgumentException("an area with no name cannot be looked up"); //$NON-NLS-1$
        }
        String lower = kind == null ? "rect" : kind.trim().toLowerCase(); //$NON-NLS-1$
        NamedItemCells item = MoxelFactory.eINSTANCE.createNamedItemCells();
        item.setArea(areaOf(lower, fromRow, fromCol, toRow, toCol));
        // An EMap put replaces the value under a key that is already there, so naming an area twice
        // moves it instead of leaving two areas contending for one name.
        doc.getNamedItems().put(name, item);
        settleExtent(doc);
    }

    private static Area areaOf(String kind, int fromRow, int fromCol, int toRow, int toCol)
    {
        if ("columns".equals(kind)) //$NON-NLS-1$
        {
            requireRange("column", fromCol, toCol); //$NON-NLS-1$
            ColumnsArea columns = MoxelFactory.eINSTANCE.createColumnsArea();
            columns.setBegin(fromCol - 1);
            columns.setEnd(toCol - 1);
            return columns;
        }
        if ("rows".equals(kind)) //$NON-NLS-1$
        {
            requireRange("row", fromRow, toRow); //$NON-NLS-1$
            RowsArea rows = MoxelFactory.eINSTANCE.createRowsArea();
            rows.setBegin(fromRow - 1);
            rows.setEnd(toRow - 1);
            return rows;
        }
        if (!"rect".equals(kind)) //$NON-NLS-1$
        {
            throw new IllegalArgumentException("unknown area kind: " + kind //$NON-NLS-1$
                + ". Allowed: columns, rows, rect."); //$NON-NLS-1$
        }
        requireRange("row", fromRow, toRow); //$NON-NLS-1$
        requireRange("column", fromCol, toCol); //$NON-NLS-1$
        RectArea rect = MoxelFactory.eINSTANCE.createRectArea();
        Rect position = MoxelFactory.eINSTANCE.createRect();
        position.setX(fromCol - 1);
        position.setY(fromRow - 1);
        // The width and height are the cells after the first: the template file carries
        // endRow = y + height and endColumn = x + width.
        position.setWidth(toCol - fromCol);
        position.setHeight(toRow - fromRow);
        rect.setPosition(position);
        return rect;
    }

    private static void requireRange(String what, int from, int to)
    {
        if (from < 1 || to < from)
        {
            throw new IllegalArgumentException("invalid " + what + " range: " + from + ".." + to //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " - 1-based, and the end must not precede the start"); //$NON-NLS-1$
        }
    }

    /**
     * Removes a named area.
     *
     * @param doc the document
     * @param name the area name
     * @return <code>true</code> when an area of that name was there and is gone
     */
    public static boolean removeNamedArea(SpreadsheetDocument doc, String name)
    {
        if (doc == null || name == null)
        {
            return false;
        }
        return doc.getNamedItems().removeKey(name) != null;
    }

    // -----------------------------------------------------------------------
    // Row and column operations: insert_rows / delete_rows / copy_rows and
    // insert_columns / delete_columns / copy_columns
    // -----------------------------------------------------------------------

    /**
     * What a shift over one axis did, and what it left behind.
     * <p>
     * Every counter describes the model the operation reached: merges whose span on the axis grew
     * or shrank, and the merges, named areas and drawings a deletion took entirely. A refusal sets
     * {@code error} instead and leaves the model exactly as it was.
     * </p>
     */
    public static class ShiftOutcome
    {
        /** Merges, whole-range merges included, whose span on the axis grew or shrank. */
        public int resizedMerges;

        /** Merges the deletion took entirely. */
        public int removedMerges;

        /** Named areas whose span on the axis grew or shrank, by name. */
        public final List<String> resizedNamedAreas = new ArrayList<>();

        /** Named areas the deletion took entirely, by name. */
        public final List<String> removedNamedAreas = new ArrayList<>();

        /** Drawings the deletion took entirely, by id. */
        public final List<Integer> removedDrawings = new ArrayList<>();

        /** Drawing data sources the deletion took - with their area or with their drawing. */
        public int removedDataSources;

        /** Why nothing happened, when nothing did. */
        public String error;
    }

    /**
     * What a row operation did, and what it left behind.
     * <p>
     * The shared counters say what the shift resized and removed; the row ones say how many rows
     * changed their number and where the document now ends. {@code lastRow} is 1-based, after the
     * operation.
     * </p>
     */
    public static final class RowOutcome extends ShiftOutcome
    {
        /** Rows that changed their number. */
        public int shiftedRows;

        /** The document's last row, 1-based, after the operation. */
        public int lastRow;
    }

    /**
     * What a column operation did, and what it left behind.
     * <p>
     * The shared counters say what the shift resized and removed; the column ones say how many
     * columns changed their number and where the document now ends. {@code lastColumn} is 1-based,
     * after the operation.
     * </p>
     */
    public static final class ColumnOutcome extends ShiftOutcome
    {
        /** Columns that changed their number: the cells and the column entries that moved. */
        public int shiftedColumns;

        /** The document's last column, 1-based, after the operation. */
        public int lastColumn;
    }

    /**
     * A span an element of the model occupies on one axis: 0-based, both ends inclusive.
     */
    private static final class Span
    {
        private int begin;

        private int end;
    }

    /** The axis a shift runs over: rows move down and up, columns right and left. */
    private enum Axis
    {
        ROW, COLUMN
    }

    /**
     * The canonical spelling of a {@code formatFrom} value, or <code>null</code> for a word the row
     * operations do not know.
     *
     * @param formatFrom the value as the caller wrote it; empty means the default
     * @return {@code above}, {@code below}, {@code none}, or <code>null</code>
     */
    public static String canonicalFormatFrom(String formatFrom)
    {
        return canonicalAxisWord(formatFrom, "above", "below"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The canonical spelling of a {@code formatFrom} value the column operations know, or
     * <code>null</code> for any other word - the neighbours of the row axis included, so a value of
     * the wrong axis is refused rather than quietly read.
     *
     * @param formatFrom the value as the caller wrote it; empty means the default
     * @return {@code left}, {@code right}, {@code none}, or <code>null</code>
     */
    public static String canonicalColumnFormatFrom(String formatFrom)
    {
        return canonicalAxisWord(formatFrom, "left", "right"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The canonical spelling of a {@code formatFrom} word against the two neighbours one axis
     * names: the word before the insertion point and the word at it.
     *
     * @param formatFrom the value as the caller wrote it; empty means the neighbour before
     * @param before the axis's word for the element the new ones land before
     * @param at the axis's word for the element the new ones land at
     * @return the folded word, or <code>null</code> when the axis does not know it
     */
    private static String canonicalAxisWord(String formatFrom, String before, String at)
    {
        if (formatFrom == null || formatFrom.trim().isEmpty())
        {
            return before;
        }
        String folded = formatFrom.trim().toLowerCase(java.util.Locale.ROOT);
        if (before.equals(folded) || at.equals(folded) || "none".equals(folded)) //$NON-NLS-1$
        {
            return folded;
        }
        return null;
    }

    /**
     * Inserts rows before a row, shifting everything from that row down.
     * <p>
     * Everything that carries a row number moves with the shift: the rows with their cells and
     * notes, the merges and whole-row merges, the named areas, the row groups, the drawings and
     * their data source areas, the print and repeat areas, the declared height and the saved view
     * rows. An element the insertion point falls inside - a merge, an area, a group, a drawing -
     * grows by the count instead of moving. The new rows take the row format, the cell formats and
     * the column set of their own from the row {@code formatFrom} names; text, parameters and
     * details are never copied, and a source that has none of the three leaves the new rows empty
     * rather than materialized.
     * </p>
     *
     * @param doc the spreadsheet
     * @param row the row the new rows land before, 1-based; the last row + 1 appends
     * @param count how many rows to insert, 1 or more
     * @param formatFrom above (the default, and none at row 1), below, or none
     * @return what the operation did, or {@code error} saying why it did nothing
     */
    public static RowOutcome insertRows(SpreadsheetDocument doc, int row, int count, String formatFrom)
    {
        RowOutcome outcome = new RowOutcome();
        String canonical = canonicalFormatFrom(formatFrom);
        if (row < 1 || count < 1)
        {
            outcome.error = "row and count must be 1-based positive integers - got row=" //$NON-NLS-1$
                + row + ", count=" + count; //$NON-NLS-1$
            return outcome;
        }
        if (canonical == null)
        {
            outcome.error = "formatFrom must be one of above, below, none - got: " + formatFrom; //$NON-NLS-1$
            return outcome;
        }
        if (doc == null)
        {
            outcome.error = "no spreadsheet to change"; //$NON-NLS-1$
            return outcome;
        }
        int lastRow = lastRowOf(doc);
        if (row > lastRow + 1)
        {
            outcome.error = "row " + row + " is past the end - the document runs to row " + lastRow //$NON-NLS-1$ //$NON-NLS-2$
                + ", and " + (lastRow + 1) + " is the first row past it"; //$NON-NLS-1$ //$NON-NLS-2$
            return outcome;
        }
        int at = row - 1;
        EMap<Integer, Row> rows = doc.getRows();
        Row formatSource = null;
        if ("below".equals(canonical)) //$NON-NLS-1$
        {
            formatSource = rows.get(Integer.valueOf(at));
        }
        else if ("above".equals(canonical) && at > 0) //$NON-NLS-1$
        {
            formatSource = rows.get(Integer.valueOf(at - 1));
        }
        int rowFormat = formatSource == null ? 0 : formatSource.getFormatIndex();
        Columns formatColumns = formatSource == null ? null : formatSource.getColumns();
        List<int[]> cellFormats = new ArrayList<>();
        if (formatSource != null)
        {
            for (Map.Entry<Integer, Cell> held : formatSource.getCells())
            {
                if (held != null && held.getKey() != null && held.getValue() != null
                    && held.getValue().getFormatIndex() != 0)
                {
                    cellFormats.add(new int[] { held.getKey().intValue(),
                        held.getValue().getFormatIndex() });
                }
            }
        }
        outcome.shiftedRows = shiftRowsDown(doc, at, count);
        if (rowFormat != 0 || !cellFormats.isEmpty() || formatColumns != null)
        {
            for (int i = 0; i < count; i++)
            {
                Row fresh = MoxelFactory.eINSTANCE.createRow();
                fresh.setFormatIndex(rowFormat);
                // The set is shared, not copied: it is the same columns the source row reads its
                // column formats from, and one set serves every row that reaches for it.
                fresh.setColumns(formatColumns);
                for (int[] cell : cellFormats)
                {
                    Cell created = MoxelFactory.eINSTANCE.createCell();
                    created.setFormatIndex(cell[1]);
                    fresh.getCells().put(Integer.valueOf(cell[0]), created);
                }
                rows.put(Integer.valueOf(at + i), fresh);
            }
        }
        moveMerges(doc.getMerges(), true, at, count, outcome, Axis.ROW);
        moveMerges(doc.getUnmerges(), true, at, count, null, Axis.ROW);
        moveRowMerges(doc.getRowMerges(), true, at, count, outcome);
        moveRowGroups(doc.getRowGroups(), true, at, count);
        moveNamedItems(doc, true, at, count, outcome, Axis.ROW);
        moveDrawings(doc, true, at, count, outcome, Axis.ROW);
        moveDrawingDataSources(doc, true, at, count, outcome, Axis.ROW,
            Collections.<Integer> emptyList());
        moveDocumentAreas(doc, true, at, count, Axis.ROW);
        moveViewPointers(doc, true, at, count, Axis.ROW);
        moveHeight(doc, true, at, count);
        settleExtent(doc);
        outcome.lastRow = lastRowOf(doc);
        return outcome;
    }

    /**
     * Deletes rows, shifting everything below them up.
     * <p>
     * The rows go with their cells and their notes. A merge, a named area, a row group or a drawing
     * that lies entirely inside the deleted range goes with them and is named in the outcome; one
     * the range cuts in half shrinks by the rows it lost; one below moves up. A drawing data source
     * whose area the range takes entirely goes too, and so does one whose drawing the range took,
     * counted once in the outcome. The print and repeat
     * areas, the declared height and the saved view rows move the same way.
     * </p>
     *
     * @param doc the spreadsheet
     * @param row the first row to delete, 1-based
     * @param count how many rows to delete, 1 or more
     * @return what the operation did, or {@code error} saying why it did nothing
     */
    public static RowOutcome deleteRows(SpreadsheetDocument doc, int row, int count)
    {
        RowOutcome outcome = new RowOutcome();
        if (row < 1 || count < 1)
        {
            outcome.error = "row and count must be 1-based positive integers - got row=" //$NON-NLS-1$
                + row + ", count=" + count; //$NON-NLS-1$
            return outcome;
        }
        if (doc == null)
        {
            outcome.error = "no spreadsheet to change"; //$NON-NLS-1$
            return outcome;
        }
        int lastRow = lastRowOf(doc);
        if (row + count - 1 > lastRow)
        {
            outcome.error = "rows " + row + ".." + (row + count - 1) //$NON-NLS-1$ //$NON-NLS-2$
                + " run past the end - the document runs to row " + lastRow; //$NON-NLS-1$
            return outcome;
        }
        int first = row - 1;
        outcome.shiftedRows = removeRowsAndShiftUp(doc, first, first + count - 1);
        moveMerges(doc.getMerges(), false, first, count, outcome, Axis.ROW);
        moveMerges(doc.getUnmerges(), false, first, count, null, Axis.ROW);
        moveRowMerges(doc.getRowMerges(), false, first, count, outcome);
        moveRowGroups(doc.getRowGroups(), false, first, count);
        moveNamedItems(doc, false, first, count, outcome, Axis.ROW);
        List<Integer> removedDrawingIds = moveDrawings(doc, false, first, count, outcome,
            Axis.ROW);
        moveDrawingDataSources(doc, false, first, count, outcome, Axis.ROW, removedDrawingIds);
        moveDocumentAreas(doc, false, first, count, Axis.ROW);
        moveViewPointers(doc, false, first, count, Axis.ROW);
        moveHeight(doc, false, first, count);
        settleExtent(doc);
        outcome.lastRow = lastRowOf(doc);
        return outcome;
    }

    /**
     * Replaces rows with a copy of other rows, with no shift.
     * <p>
     * A copy carries the row format and the cells whole - text, parameter, detail and format, notes
     * included. Merges that lie entirely inside the source rows repeat over the target, and merges
     * that lie entirely inside the replaced target rows come off first; whole-row merges are
     * included in both; a merge the target range cuts in half stays as it is. The unmerge
     * exceptions ride the same way, uncounted. Named areas are neither copied nor moved. The
     * target may run past the current end of the document, which then grows to hold it.
     * </p>
     *
     * @param doc the spreadsheet
     * @param fromRow the first source row, 1-based
     * @param toRow the first target row, 1-based
     * @param count how many rows replace and are replaced, 1 or more
     * @return what the operation did, or {@code error} saying why it did nothing
     */
    public static RowOutcome copyRows(SpreadsheetDocument doc, int fromRow, int toRow, int count)
    {
        RowOutcome outcome = new RowOutcome();
        if (fromRow < 1 || toRow < 1 || count < 1)
        {
            outcome.error = "fromRow, toRow and count must be 1-based positive integers - got " //$NON-NLS-1$
                + "fromRow=" + fromRow + ", toRow=" + toRow + ", count=" + count; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return outcome;
        }
        if (doc == null)
        {
            outcome.error = "no spreadsheet to change"; //$NON-NLS-1$
            return outcome;
        }
        int lastRow = lastRowOf(doc);
        if (fromRow + count - 1 > lastRow)
        {
            outcome.error = "the source rows " + fromRow + ".." + (fromRow + count - 1) //$NON-NLS-1$ //$NON-NLS-2$
                + " run past the end - the document runs to row " + lastRow; //$NON-NLS-1$
            return outcome;
        }
        if (fromRow <= toRow + count - 1 && toRow <= fromRow + count - 1)
        {
            outcome.error = "the source rows " + fromRow + ".." + (fromRow + count - 1) //$NON-NLS-1$ //$NON-NLS-2$
                + " and the target rows " + toRow + ".." + (toRow + count - 1) //$NON-NLS-1$ //$NON-NLS-2$
                + " overlap - a row cannot be its own replacement"; //$NON-NLS-1$
            return outcome;
        }
        int delta = toRow - fromRow;
        int sourceFirst = fromRow - 1;
        int targetFirst = toRow - 1;
        EMap<Integer, Row> rows = doc.getRows();
        List<Row> copies = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            Row original = rows.get(Integer.valueOf(sourceFirst + i));
            copies.add(original == null ? null : (Row)EcoreUtil.copy(original));
        }
        for (int i = 0; i < count; i++)
        {
            rows.removeKey(Integer.valueOf(targetFirst + i));
        }
        for (int i = 0; i < copies.size(); i++)
        {
            Row copy = copies.get(i);
            if (copy == null)
            {
                // The source row is absent, so the replaced row is absent too: an empty row is
                // what the model shows for a row that was never written.
                continue;
            }
            rows.put(Integer.valueOf(targetFirst + i), copy);
            repointNoteRows(copy, targetFirst + i, delta);
        }
        replaceMergesOverRange(doc, sourceFirst, targetFirst, count, outcome, Axis.ROW);
        if (doc.getHeight() > 0)
        {
            // The target runs the document to its far end whether or not the source rows hold
            // anything: a source row the model never materialized copies as an empty row, and the
            // declared height still has to reach the last row the call names. The extent below is
            // a floor, not the whole answer here.
            doc.setHeight(Math.max(doc.getHeight(), targetFirst + count));
        }
        settleExtent(doc);
        outcome.lastRow = lastRowOf(doc);
        return outcome;
    }

    /**
     * Inserts columns before a column, shifting everything from that column right.
     * <p>
     * Everything that carries a column number moves with the shift: the cells of every row with
     * their notes, the entries of every column set - a set shared by several rows shifts once -
     * with the declared size the set counts to, the merges and whole-column merges, the named
     * areas, the column groups, the drawings and their data source areas, the print and repeat
     * areas and the saved view columns. An element the insertion point falls inside - a merge, an
     * area, a group, a drawing - grows by the count instead of moving. The new columns take the
     * column format and the cell formats from the column {@code formatFrom} names; text, parameters
     * and details are never copied, and a source that has neither a formatted column nor a
     * formatted cell leaves the new columns empty rather than materialized.
     * </p>
     *
     * @param doc the spreadsheet
     * @param col the column the new columns land before, 1-based; the last column + 1 appends
     * @param count how many columns to insert, 1 or more
     * @param formatFrom left (the default, and none at column 1), right, or none
     * @return what the operation did, or {@code error} saying why it did nothing
     */
    public static ColumnOutcome insertColumns(SpreadsheetDocument doc, int col, int count,
        String formatFrom)
    {
        ColumnOutcome outcome = new ColumnOutcome();
        String canonical = canonicalColumnFormatFrom(formatFrom);
        if (col < 1 || count < 1)
        {
            outcome.error = "col and count must be 1-based positive integers - got col=" //$NON-NLS-1$
                + col + ", count=" + count; //$NON-NLS-1$
            return outcome;
        }
        if (canonical == null)
        {
            outcome.error = "formatFrom must be one of left, right, none - got: " + formatFrom; //$NON-NLS-1$
            return outcome;
        }
        if (doc == null)
        {
            outcome.error = "no spreadsheet to change"; //$NON-NLS-1$
            return outcome;
        }
        int lastColumn = lastColumnOf(doc);
        if (col > lastColumn + 1)
        {
            outcome.error = "column " + col + " is past the end - the document runs to column " //$NON-NLS-1$ //$NON-NLS-2$
                + lastColumn + ", and " + (lastColumn + 1) + " is the first column past it"; //$NON-NLS-1$ //$NON-NLS-2$
            return outcome;
        }
        int at = col - 1;
        int sourceKey = sourceColumnOf(canonical, at);
        List<Columns> formatSets = new ArrayList<>();
        List<Integer> formatSetIndexes = new ArrayList<>();
        if (sourceKey >= 0)
        {
            for (Columns set : columnSetsOf(doc))
            {
                Column source = set.getColumns().get(Integer.valueOf(sourceKey));
                if (source != null && source.getFormatIndex() != 0)
                {
                    formatSets.add(set);
                    formatSetIndexes.add(Integer.valueOf(source.getFormatIndex()));
                }
            }
        }
        List<Row> formatRows = new ArrayList<>();
        List<Integer> formatRowIndexes = new ArrayList<>();
        if (sourceKey >= 0)
        {
            for (Row row : doc.getRows().values())
            {
                Cell source = row == null ? null
                    : row.getCells().get(Integer.valueOf(sourceKey));
                if (source != null && source.getFormatIndex() != 0)
                {
                    formatRows.add(row);
                    formatRowIndexes.add(Integer.valueOf(source.getFormatIndex()));
                }
            }
        }
        outcome.shiftedColumns = shiftColumnsRight(doc, at, count);
        for (int i = 0; i < count; i++)
        {
            for (int s = 0; s < formatSets.size(); s++)
            {
                Column created = MoxelFactory.eINSTANCE.createColumn();
                created.setFormatIndex(formatSetIndexes.get(s).intValue());
                formatSets.get(s).getColumns().put(Integer.valueOf(at + i), created);
            }
            for (int r = 0; r < formatRows.size(); r++)
            {
                Cell created = MoxelFactory.eINSTANCE.createCell();
                created.setFormatIndex(formatRowIndexes.get(r).intValue());
                formatRows.get(r).getCells().put(Integer.valueOf(at + i), created);
            }
        }
        moveMerges(doc.getMerges(), true, at, count, outcome, Axis.COLUMN);
        moveMerges(doc.getUnmerges(), true, at, count, null, Axis.COLUMN);
        moveColumnMerges(doc.getColumnMerges(), true, at, count, outcome);
        moveColumnGroups(doc.getColumnGroups(), true, at, count);
        moveNamedItems(doc, true, at, count, outcome, Axis.COLUMN);
        moveDrawings(doc, true, at, count, outcome, Axis.COLUMN);
        moveDrawingDataSources(doc, true, at, count, outcome, Axis.COLUMN,
            Collections.<Integer> emptyList());
        moveDocumentAreas(doc, true, at, count, Axis.COLUMN);
        moveViewPointers(doc, true, at, count, Axis.COLUMN);
        settleExtent(doc);
        outcome.lastColumn = lastColumnOf(doc);
        return outcome;
    }

    /**
     * Deletes columns, shifting everything to the right of them left.
     * <p>
     * The cells of the range go with their notes, and so do the entries the range holds in every
     * column set. A merge, a named area, a column group or a drawing that lies entirely inside the
     * deleted range goes with them and is named in the outcome; one the range cuts in half shrinks
     * by the columns it lost; one to the right moves left. A drawing data source whose area the
     * range takes entirely goes too, and so does one whose drawing the range took, counted once in
     * the outcome. The print and repeat areas, the
     * declared set sizes and the saved view columns move the same way.
     * </p>
     *
     * @param doc the spreadsheet
     * @param col the first column to delete, 1-based
     * @param count how many columns to delete, 1 or more
     * @return what the operation did, or {@code error} saying why it did nothing
     */
    public static ColumnOutcome deleteColumns(SpreadsheetDocument doc, int col, int count)
    {
        ColumnOutcome outcome = new ColumnOutcome();
        if (col < 1 || count < 1)
        {
            outcome.error = "col and count must be 1-based positive integers - got col=" //$NON-NLS-1$
                + col + ", count=" + count; //$NON-NLS-1$
            return outcome;
        }
        if (doc == null)
        {
            outcome.error = "no spreadsheet to change"; //$NON-NLS-1$
            return outcome;
        }
        int lastColumn = lastColumnOf(doc);
        if (col + count - 1 > lastColumn)
        {
            outcome.error = "columns " + col + ".." + (col + count - 1) //$NON-NLS-1$ //$NON-NLS-2$
                + " run past the end - the document runs to column " + lastColumn; //$NON-NLS-1$
            return outcome;
        }
        int first = col - 1;
        outcome.shiftedColumns = removeColumnsAndShiftLeft(doc, first, first + count - 1);
        moveMerges(doc.getMerges(), false, first, count, outcome, Axis.COLUMN);
        moveMerges(doc.getUnmerges(), false, first, count, null, Axis.COLUMN);
        moveColumnMerges(doc.getColumnMerges(), false, first, count, outcome);
        moveColumnGroups(doc.getColumnGroups(), false, first, count);
        moveNamedItems(doc, false, first, count, outcome, Axis.COLUMN);
        List<Integer> removedDrawingIds = moveDrawings(doc, false, first, count, outcome,
            Axis.COLUMN);
        moveDrawingDataSources(doc, false, first, count, outcome, Axis.COLUMN,
            removedDrawingIds);
        moveDocumentAreas(doc, false, first, count, Axis.COLUMN);
        moveViewPointers(doc, false, first, count, Axis.COLUMN);
        settleExtent(doc);
        outcome.lastColumn = lastColumnOf(doc);
        return outcome;
    }

    /**
     * Replaces columns with a copy of other columns, with no shift.
     * <p>
     * A copy carries the column entry of every set whole - the width lives in the format it points
     * at - and the cells of every row whole: text, parameter, detail and format, notes included. An
     * entry the copy writes past a set's declared size grows that size to the last column written;
     * a set that declares no size is left declaring none. Merges that lie entirely inside the
     * source columns repeat over the target, and merges that lie entirely inside the replaced
     * target columns come off first; whole-column merges are included in both; a merge the target
     * range cuts in half stays as it is. The unmerge exceptions ride the same way, uncounted.
     * Named areas are neither copied nor moved. The target may run past the current end of the
     * document, which then grows to hold it.
     * </p>
     *
     * @param doc the spreadsheet
     * @param fromCol the first source column, 1-based
     * @param toCol the first target column, 1-based
     * @param count how many columns replace and are replaced, 1 or more
     * @return what the operation did, or {@code error} saying why it did nothing
     */
    public static ColumnOutcome copyColumns(SpreadsheetDocument doc, int fromCol, int toCol,
        int count)
    {
        ColumnOutcome outcome = new ColumnOutcome();
        if (fromCol < 1 || toCol < 1 || count < 1)
        {
            outcome.error = "fromCol, toCol and count must be 1-based positive integers - got " //$NON-NLS-1$
                + "fromCol=" + fromCol + ", toCol=" + toCol + ", count=" + count; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return outcome;
        }
        if (doc == null)
        {
            outcome.error = "no spreadsheet to change"; //$NON-NLS-1$
            return outcome;
        }
        int lastColumn = lastColumnOf(doc);
        if (fromCol + count - 1 > lastColumn)
        {
            outcome.error = "the source columns " + fromCol + ".." + (fromCol + count - 1) //$NON-NLS-1$ //$NON-NLS-2$
                + " run past the end - the document runs to column " + lastColumn; //$NON-NLS-1$
            return outcome;
        }
        if (fromCol <= toCol + count - 1 && toCol <= fromCol + count - 1)
        {
            outcome.error = "the source columns " + fromCol + ".." + (fromCol + count - 1) //$NON-NLS-1$ //$NON-NLS-2$
                + " and the target columns " + toCol + ".." + (toCol + count - 1) //$NON-NLS-1$ //$NON-NLS-2$
                + " overlap - a column cannot be its own replacement"; //$NON-NLS-1$
            return outcome;
        }
        int delta = toCol - fromCol;
        int sourceFirst = fromCol - 1;
        int targetFirst = toCol - 1;
        for (Columns set : columnSetsOf(doc))
        {
            EMap<Integer, Column> entries = set.getColumns();
            List<Column> copies = new ArrayList<>();
            for (int i = 0; i < count; i++)
            {
                Column original = entries.get(Integer.valueOf(sourceFirst + i));
                copies.add(original == null ? null : (Column)EcoreUtil.copy(original));
            }
            for (int i = 0; i < count; i++)
            {
                entries.removeKey(Integer.valueOf(targetFirst + i));
            }
            int written = -1;
            for (int i = 0; i < copies.size(); i++)
            {
                Column copy = copies.get(i);
                if (copy == null)
                {
                    // The source column holds no entry in this set, so the replaced column holds
                    // none either: an absent entry is what the model shows for a column that was
                    // never given a format of its own.
                    continue;
                }
                entries.put(Integer.valueOf(targetFirst + i), copy);
                written = Math.max(written, targetFirst + i);
            }
            // An entry written past the set's declared extent leaves the set claiming to end
            // before its own entry, so the extent grows to the last column written. A set that
            // declares no size keeps declaring none: 0 reads as not declared, not as empty.
            int size = set.getSize();
            if (size > 0 && written >= size)
            {
                set.setSize(written + 1);
            }
        }
        for (Row row : doc.getRows().values())
        {
            if (row == null)
            {
                continue;
            }
            EMap<Integer, Cell> cells = row.getCells();
            List<Cell> copies = new ArrayList<>();
            for (int i = 0; i < count; i++)
            {
                Cell original = cells.get(Integer.valueOf(sourceFirst + i));
                copies.add(original == null ? null : (Cell)EcoreUtil.copy(original));
            }
            for (int i = 0; i < count; i++)
            {
                cells.removeKey(Integer.valueOf(targetFirst + i));
            }
            for (int i = 0; i < copies.size(); i++)
            {
                Cell copy = copies.get(i);
                if (copy == null)
                {
                    // The source column is absent, so the replaced column is absent too: an empty
                    // column is what the model shows for a column that was never written.
                    continue;
                }
                cells.put(Integer.valueOf(targetFirst + i), copy);
                repointNoteColumns(copy, targetFirst + i, delta);
            }
        }
        replaceMergesOverRange(doc, sourceFirst, targetFirst, count, outcome, Axis.COLUMN);
        settleExtent(doc);
        outcome.lastColumn = lastColumnOf(doc);
        return outcome;
    }

    /**
     * The column a {@code formatFrom} word reads the new columns' formats from, 0-based.
     *
     * @param canonical the canonical word: left, right or none
     * @param at the 0-based column the new columns land before
     * @return the 0-based source column, or -1 when the word names none
     */
    private static int sourceColumnOf(String canonical, int at)
    {
        if ("right".equals(canonical)) //$NON-NLS-1$
        {
            return at;
        }
        if ("left".equals(canonical) && at > 0) //$NON-NLS-1$
        {
            return at - 1;
        }
        return -1;
    }

    /**
     * The document's last row, 1-based: the highest row anything of the model reaches.
     * <p>
     * Every holder of a row number counts - the rows that carry content, merges, named areas, row
     * groups, drawings, the areas the drawings read their data from, the print and repeat areas,
     * and the declared height. A row past the content sits in the model all the same, and an
     * operation that asked about "the last row" has to see it. A bare row entry - no row format,
     * no cells, no column set of its own - is the placeholder an empty template carries, not
     * content, and does not hold the end.
     * </p>
     *
     * @param doc the spreadsheet
     * @return the last row, 1-based; 0 when nothing reaches any row
     */
    private static int lastRowOf(SpreadsheetDocument doc)
    {
        return lastRowOf(doc, false);
    }

    /**
     * The same question asked of the content a reader sees, for the declared extent: the rows
     * whose cells hold a text, a parameter, a parameter or template fill or a turned text, plus
     * every holder of a row number outside the rows themselves. A row a format walked over - no
     * text, a format index and nothing else - is not a row of the table: counting it would declare
     * a table for a font. The declared height is not folded in here either; it is what the caller
     * settles, and reading it back into the measure would make the number its own reason.
     *
     * @param doc the spreadsheet
     * @return the last row a reader reaches, 1-based; 0 when the content reaches none
     */
    private static int extentRowOf(SpreadsheetDocument doc)
    {
        return lastRowOf(doc, true);
    }

    /**
     * The document's last row, 1-based: the highest row anything of the model reaches, by the
     * measure the caller names.
     *
     * @param doc the spreadsheet
     * @param readableOnly whether only the content a reader lists counts, rather than every row
     *     entry the model carries
     * @return the last row, 1-based; 0 when nothing reaches any row
     */
    private static int lastRowOf(SpreadsheetDocument doc, boolean readableOnly)
    {
        int maxKey = -1;
        for (Map.Entry<Integer, Row> held : doc.getRows())
        {
            if (held == null || held.getKey() == null)
            {
                continue;
            }
            boolean counts = readableOnly ? rowCarriesReadableContent(doc, held.getValue())
                : rowCarriesContent(held.getValue());
            if (counts)
            {
                maxKey = Math.max(maxKey, held.getKey().intValue());
            }
        }
        maxKey = Math.max(maxKey, rectFarEdgeOf(doc.getMerges(), Axis.ROW));
        maxKey = Math.max(maxKey, rectFarEdgeOf(doc.getUnmerges(), Axis.ROW));
        for (RowMerge merge : doc.getRowMerges())
        {
            if (merge != null)
            {
                maxKey = Math.max(maxKey, merge.getEnd());
            }
        }
        for (RowGroup group : doc.getRowGroups())
        {
            if (group != null)
            {
                maxKey = Math.max(maxKey, group.getEnd());
            }
        }
        for (Drawing drawing : doc.getDrawings())
        {
            if (drawing == null || drawing.getPosition() == null)
            {
                continue;
            }
            maxKey = Math.max(maxKey, pointAtOf(drawing.getPosition().getBegin(), Axis.ROW));
            maxKey = Math.max(maxKey, pointAtOf(drawing.getPosition().getEnd(), Axis.ROW));
        }
        for (DrawingsDataSource source : doc.getDrawingDataSources())
        {
            Span span = spanOf(source == null ? null : source.getArea(), Axis.ROW);
            if (span != null)
            {
                maxKey = Math.max(maxKey, span.end);
            }
        }
        for (Map.Entry<String, NamedItem> held : doc.getNamedItems())
        {
            Span span = spanOf(areaOf(held == null ? null : held.getValue()), Axis.ROW);
            if (span != null)
            {
                maxKey = Math.max(maxKey, span.end);
            }
        }
        maxKey = Math.max(maxKey, spanFarEdgeOf(doc.getPrintArea(), Axis.ROW));
        maxKey = Math.max(maxKey, spanFarEdgeOf(doc.getRepeatRows(), Axis.ROW));
        if (!readableOnly && doc.getHeight() > maxKey + 1)
        {
            maxKey = doc.getHeight() - 1;
        }
        return maxKey + 1;
    }

    /**
     * Whether a row holds a cell a reader lists: a non-empty text in any language, a parameter
     * name, a parameter or template fill, or a turned text. Presentation alone - a row format, a
     * column set of the row's own - is not content, and neither is a cell whose text is empty.
     *
     * @param doc the spreadsheet, for the format a cell points at
     * @param row the row, or <code>null</code>
     * @return <code>true</code> when a cell of the row holds something readable
     */
    private static boolean rowCarriesReadableContent(SpreadsheetDocument doc, Row row)
    {
        if (row == null)
        {
            return false;
        }
        for (Cell cell : row.getCells().values())
        {
            if (cellCarriesReadableContent(doc, cell))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a cell holds something a reader lists, by the same test the read applies: a
     * non-empty text in any language, a parameter name, a parameter or template fill, or a turned
     * text. A cell that exists in the model but carries none of them is what an empty write or a
     * format left behind, and it is not a table.
     *
     * @param doc the spreadsheet, for the format the cell points at
     * @param cell the cell, or <code>null</code>
     * @return <code>true</code> when the cell holds something readable
     */
    private static boolean cellCarriesReadableContent(SpreadsheetDocument doc, Cell cell)
    {
        if (cell == null)
        {
            return false;
        }
        String text = cellText(cell, null);
        if (text != null && !text.isEmpty())
        {
            return true;
        }
        String parameter = cell.getParameter();
        if (parameter != null && !parameter.isEmpty())
        {
            return true;
        }
        return cellIsReadableByFillOrTurn(doc, cell);
    }

    /**
     * The part of the readable test that reads the cell's format: a parameter or template fill, or
     * a text turned away from the horizontal.
     *
     * @param doc the spreadsheet, for the format the cell points at
     * @param cell the cell
     * @return <code>true</code> when the format makes the cell one a reader lists
     */
    private static boolean cellIsReadableByFillOrTurn(SpreadsheetDocument doc, Cell cell)
    {
        Format format = formatAt(doc, cell.getFormatIndex());
        if (format != null && format.isSetFillType())
        {
            FillType fill = format.getFillType();
            if (fill == FillType.PARAMETER || fill == FillType.TEMPLATE)
            {
                return true;
            }
        }
        Integer degrees = textOrientationDegrees(format);
        return degrees != null && degrees.intValue() != 0;
    }

    /**
     * Whether a row entry holds anything a reader could see: a row format of its own, cells, or
     * a column set with a declared size or entries. A row with none of the three is the
     * placeholder an empty template file carries, not content.
     *
     * @param row the row, or <code>null</code>
     * @return <code>true</code> when the row carries content
     */
    private static boolean rowCarriesContent(Row row)
    {
        if (row == null)
        {
            return false;
        }
        if (row.getFormatIndex() != 0 || !row.getCells().isEmpty())
        {
            return true;
        }
        Columns set = row.getColumns();
        return set != null && (set.getSize() > 0 || !set.getColumns().isEmpty());
    }

    /**
     * The document's last column, 1-based: the highest column anything of the model reaches.
     * <p>
     * Every holder of a column number counts - the cells of every row, the entries and the declared
     * size of every column set, merges, named areas, column groups, drawings, the areas the
     * drawings read their data from, and the print and repeat areas. A column past the content sits
     * in the model all the same, and an operation that asked about "the last column" has to see it.
     * </p>
     *
     * @param doc the spreadsheet
     * @return the last column, 1-based; 0 when nothing reaches any column
     */
    private static int lastColumnOf(SpreadsheetDocument doc)
    {
        return lastColumnOf(doc, false);
    }

    /**
     * The same question asked of the content a reader sees, for the declared extent: the columns
     * whose cells hold a text, a parameter, a parameter or template fill or a turned text, plus
     * every holder of a column number outside the cells. A column set's declared size and its
     * entries are not folded in - a width is presentation, and the set size is what the caller
     * settles, so reading it back would make the number its own reason.
     *
     * @param doc the spreadsheet
     * @return the last column a reader reaches, 1-based; 0 when the content reaches none
     */
    private static int extentColumnOf(SpreadsheetDocument doc)
    {
        return lastColumnOf(doc, true);
    }

    /**
     * The document's last column, 1-based: the highest column anything of the model reaches, by the
     * measure the caller names.
     *
     * @param doc the spreadsheet
     * @param readableOnly whether only the content a reader lists counts, rather than every cell
     *     entry and column set the model carries
     * @return the last column, 1-based; 0 when nothing reaches any column
     */
    private static int lastColumnOf(SpreadsheetDocument doc, boolean readableOnly)
    {
        int maxKey = -1;
        for (Row row : doc.getRows().values())
        {
            if (row == null)
            {
                continue;
            }
            for (Map.Entry<Integer, Cell> held : row.getCells())
            {
                if (held == null || held.getKey() == null)
                {
                    continue;
                }
                if (!readableOnly || cellCarriesReadableContent(doc, held.getValue()))
                {
                    maxKey = Math.max(maxKey, held.getKey().intValue());
                }
            }
        }
        maxKey = Math.max(maxKey, rectFarEdgeOf(doc.getMerges(), Axis.COLUMN));
        maxKey = Math.max(maxKey, rectFarEdgeOf(doc.getUnmerges(), Axis.COLUMN));
        for (ColumnMerge merge : doc.getColumnMerges())
        {
            if (merge != null)
            {
                maxKey = Math.max(maxKey, merge.getEnd());
            }
        }
        for (ColumnGroup group : doc.getColumnGroups())
        {
            if (group != null)
            {
                maxKey = Math.max(maxKey, group.getEnd());
            }
        }
        for (Drawing drawing : doc.getDrawings())
        {
            if (drawing == null || drawing.getPosition() == null)
            {
                continue;
            }
            maxKey = Math.max(maxKey, pointAtOf(drawing.getPosition().getBegin(), Axis.COLUMN));
            maxKey = Math.max(maxKey, pointAtOf(drawing.getPosition().getEnd(), Axis.COLUMN));
        }
        for (DrawingsDataSource source : doc.getDrawingDataSources())
        {
            Span span = spanOf(source == null ? null : source.getArea(), Axis.COLUMN);
            if (span != null)
            {
                maxKey = Math.max(maxKey, span.end);
            }
        }
        for (Map.Entry<String, NamedItem> held : doc.getNamedItems())
        {
            Span span = spanOf(areaOf(held == null ? null : held.getValue()), Axis.COLUMN);
            if (span != null)
            {
                maxKey = Math.max(maxKey, span.end);
            }
        }
        maxKey = Math.max(maxKey, spanFarEdgeOf(doc.getPrintArea(), Axis.COLUMN));
        maxKey = Math.max(maxKey, spanFarEdgeOf(doc.getRepeatColumns(), Axis.COLUMN));
        if (!readableOnly)
        {
            for (Columns set : columnSetsOf(doc))
            {
                maxKey = Math.max(maxKey, set.getSize() - 1);
                for (Map.Entry<Integer, Column> held : set.getColumns())
                {
                    if (held != null && held.getKey() != null)
                    {
                        maxKey = Math.max(maxKey, held.getKey().intValue());
                    }
                }
            }
        }
        return maxKey + 1;
    }

    /**
     * Settles the two numbers a reader takes as the table's size: the document's declared height
     * and the declared size of its own column set.
     * <p>
     * Both are the last row and the last column the content reaches by the measure a reader uses -
     * the cells holding a text, a parameter, a parameter or template fill or a turned text, the
     * merges and whole-row merges, the named areas, the row groups, the drawings and the areas they
     * read from, the print and repeat areas. Presentation is not content: a row the format walked
     * over, a cell whose text is empty, a column width with no cell under it. Counting those would
     * declare a table for a font, and a write of an empty string far down the sheet would declare
     * one the size of the coordinate. The platform reads the height as the table's number of rows
     * and the set size as its number of columns, so a document written without them answers zero
     * rows and zero columns however much it holds.
     * </p>
     * <p>
     * The numbers only grow here. A document that declares more than it holds keeps what it
     * declares, so a file that already answers the platform correctly is left as it was. A removal
     * lowers them where it happens, through the declared numbers the row and column removals carry
     * with them; this is the floor under that, not a replacement for it.
     * </p>
     *
     * @param doc the spreadsheet; may be <code>null</code>
     */
    private static void settleExtent(SpreadsheetDocument doc)
    {
        if (doc == null)
        {
            return;
        }
        int rows = extentRowOf(doc);
        if (rows > doc.getHeight())
        {
            doc.setHeight(rows);
        }
        int columns = extentColumnOf(doc);
        if (columns > 0)
        {
            ensureColumnSet(doc);
            Columns set = doc.getColumns();
            if (set.getSize() < columns)
            {
                set.setSize(columns);
            }
        }
    }

    /**
     * The far edge of the highest merge in a list on an axis, 0-based.
     *
     * @param merges the merges; may hold <code>null</code>s
     * @param axis the axis to measure on
     * @return the highest far edge, or -1 when no merge has a position
     */
    private static int rectFarEdgeOf(EList<Merge> merges, Axis axis)
    {
        int far = -1;
        for (Merge merge : merges)
        {
            if (merge == null || merge.getPosition() == null)
            {
                continue;
            }
            far = Math.max(far, boundsOf(merge.getPosition(), axis)[1]);
        }
        return far;
    }

    /**
     * The row or column a drawing point names, 0-based; a point that names none reads as no
     * position at all.
     *
     * @param point the point, or <code>null</code>
     * @param axis the axis to read
     * @return the position, or -1
     */
    private static int pointAtOf(SpreadsheetPoint point, Axis axis)
    {
        if (point == null || point.getCell() == null)
        {
            return -1;
        }
        return axis == Axis.COLUMN ? point.getCell().getX() : point.getCell().getY();
    }

    /**
     * The offset a drawing point carries inside its position on an axis; a point with no offset
     * recorded reads as 0, the near edge of the position.
     *
     * @param point the anchor, or <code>null</code>
     * @param axis the axis to read
     * @return the offset, or 0
     */
    private static int pointOffsetOf(SpreadsheetPoint point, Axis axis)
    {
        if (point == null || point.getOffset() == null)
        {
            return 0;
        }
        return axis == Axis.COLUMN ? point.getOffset().getX() : point.getOffset().getY();
    }

    /**
     * Sets the offset a drawing point carries inside its position on an axis.
     *
     * @param point the anchor, or <code>null</code>
     * @param offset the offset to write
     * @param axis the axis to write
     */
    private static void setPointOffset(SpreadsheetPoint point, int offset, Axis axis)
    {
        if (point == null)
        {
            return;
        }
        Point off = point.getOffset();
        if (off == null)
        {
            off = McoreFactory.eINSTANCE.createPoint();
            point.setOffset(off);
        }
        if (axis == Axis.COLUMN)
        {
            off.setX(offset);
        }
        else
        {
            off.setY(offset);
        }
    }

    /**
     * The span of an area on an axis, or <code>null</code> when the area holds none there.
     * <p>
     * A columns area holds no rows and a rows area holds no columns, by construction; an area kind
     * this class does not recognize has none because nothing about it can be read.
     * </p>
     *
     * @param area the area, or <code>null</code>
     * @param axis the axis to measure on
     * @return the span, 0-based with both ends inclusive, or <code>null</code>
     */
    private static Span spanOf(Area area, Axis axis)
    {
        if (axis == Axis.COLUMN && area instanceof ColumnsArea)
        {
            Span span = new Span();
            span.begin = ((ColumnsArea)area).getBegin();
            span.end = ((ColumnsArea)area).getEnd();
            return span;
        }
        if (axis == Axis.ROW && area instanceof RowsArea)
        {
            Span span = new Span();
            span.begin = ((RowsArea)area).getBegin();
            span.end = ((RowsArea)area).getEnd();
            return span;
        }
        if (area instanceof RectArea && ((RectArea)area).getPosition() != null)
        {
            int[] bounds = boundsOf(((RectArea)area).getPosition(), axis);
            Span span = new Span();
            span.begin = bounds[0];
            span.end = bounds[1];
            return span;
        }
        return null;
    }

    /**
     * The positions a rectangle spans on an axis, normalized: the model carries a signed extent,
     * and a rectangle written with a negative one means the same positions either way.
     * <p>
     * A merge and a rectangular area read alike. The width and height are the cells after the
     * first, so a rectangle of one cell carries 0 in both, and the template file writes an area as
     * endRow = y + height and endColumn = x + width.
     * </p>
     *
     * @param position the rectangle of a merge or of an area
     * @param axis the axis to measure on
     * @return the near position and the far one, 0-based and inclusive
     */
    private static int[] boundsOf(Rect position, Axis axis)
    {
        int near = axis == Axis.COLUMN ? position.getX() : position.getY();
        int far = near + (axis == Axis.COLUMN ? position.getWidth() : position.getHeight());
        return new int[] { Math.min(near, far), Math.max(near, far) };
    }

    /**
     * Writes a span on an axis back into the rectangle of a merge or of an area, as the cells after
     * the first.
     *
     * @param position the rectangle the span was read from
     * @param span the span to write, 0-based with both ends inclusive
     * @param axis the axis to write on
     */
    private static void writeRect(Rect position, Span span, Axis axis)
    {
        if (axis == Axis.COLUMN)
        {
            position.setX(span.begin);
            position.setWidth(span.end - span.begin);
        }
        else
        {
            position.setY(span.begin);
            position.setHeight(span.end - span.begin);
        }
    }

    /**
     * Moves a rectangle whole along an axis, leaving its extent alone.
     *
     * @param position the rectangle to move
     * @param delta how far to move, in positions on the axis
     * @param axis the axis to move on
     */
    private static void shiftRect(Rect position, int delta, Axis axis)
    {
        if (axis == Axis.COLUMN)
        {
            position.setX(position.getX() + delta);
        }
        else
        {
            position.setY(position.getY() + delta);
        }
    }

    /**
     * The far edge of an area on an axis, for the extent.
     *
     * @param area the area, or <code>null</code>
     * @param axis the axis to measure on
     * @return the far edge, 0-based, or -1 when the area holds nothing on the axis
     */
    private static int spanFarEdgeOf(Area area, Axis axis)
    {
        Span span = spanOf(area, axis);
        return span == null ? -1 : span.end;
    }

    /**
     * Writes a span back into the area it was read from.
     *
     * @param area the area the span was read from
     * @param span the span to write, 0-based with both ends inclusive
     * @param axis the axis to write on
     */
    private static void writeSpan(Area area, Span span, Axis axis)
    {
        if (axis == Axis.COLUMN && area instanceof ColumnsArea)
        {
            ((ColumnsArea)area).setBegin(span.begin);
            ((ColumnsArea)area).setEnd(span.end);
        }
        else if (axis == Axis.ROW && area instanceof RowsArea)
        {
            ((RowsArea)area).setBegin(span.begin);
            ((RowsArea)area).setEnd(span.end);
        }
        else if (area instanceof RectArea && ((RectArea)area).getPosition() != null)
        {
            writeRect(((RectArea)area).getPosition(), span, axis);
        }
    }

    /**
     * The area of a named item, for the item kinds that carry one.
     *
     * @param item the named item, or <code>null</code>
     * @return the area, or <code>null</code> when this kind of item places nothing
     */
    private static Area areaOf(NamedItem item)
    {
        if (item instanceof NamedItemCells)
        {
            return ((NamedItemCells)item).getArea();
        }
        if (item instanceof NamedItemDataSource)
        {
            return ((NamedItemDataSource)item).getArea();
        }
        if (item instanceof NamedItemEmbeddedTable)
        {
            return ((NamedItemEmbeddedTable)item).getArea();
        }
        return null;
    }

    /**
     * Moves every row from a 0-based key down, freeing the keys the move empties.
     *
     * @param doc the spreadsheet
     * @param fromKey the first 0-based key to move
     * @param count how far down each row moves
     * @return how many rows moved
     */
    private static int shiftRowsDown(SpreadsheetDocument doc, int fromKey, int count)
    {
        return shiftEntriesRight(doc.getRows(), fromKey, count,
            (row, newKey) -> repointNoteRows(row, newKey, count));
    }

    /**
     * Removes the rows of a 0-based range and moves every row below it up.
     *
     * @param doc the spreadsheet
     * @param firstKey the first 0-based key to remove
     * @param lastKey the last 0-based key to remove, inclusive
     * @return how many rows below the range moved up
     */
    private static int removeRowsAndShiftUp(SpreadsheetDocument doc, int firstKey, int lastKey)
    {
        int removed = lastKey - firstKey + 1;
        return removeEntriesAndShiftLeft(doc.getRows(), firstKey, lastKey,
            (row, newKey) -> repointNoteRows(row, newKey, -removed));
    }

    /**
     * What to tell a value its map has just re-keyed.
     */
    private interface Rekeyed<V>
    {
        /**
         * Notifies the value of the key it now sits under.
         *
         * @param value the value that moved
         * @param newKey the 0-based key it moved to
         */
        void moved(V value, int newKey);
    }

    /**
     * Moves every entry of a keyed containment map from a 0-based key up by the count.
     *
     * @param <V> the value the map holds
     * @param map the map to re-key
     * @param fromKey the first 0-based key to move
     * @param count how far up each entry moves
     * @param rekeyed told each value its new key, or <code>null</code> when the values carry no
     *            position of their own to repoint
     * @return how many entries moved
     */
    private static <V> int shiftEntriesRight(EMap<Integer, V> map, int fromKey, int count,
        Rekeyed<V> rekeyed)
    {
        List<Integer> moving = new ArrayList<>();
        for (Map.Entry<Integer, V> held : map)
        {
            if (held != null && held.getKey() != null && held.getKey().intValue() >= fromKey)
            {
                moving.add(held.getKey());
            }
        }
        // Highest key first: an entry is out of the map before the key it lands on is read again.
        Collections.sort(moving, Collections.reverseOrder());
        int shifted = 0;
        for (Integer key : moving)
        {
            // The value is read before the removal: an entry taken out of a containment map hands
            // back its value detached, not the object the key held, so removeKey's return is not
            // the value to re-put.
            V value = map.get(key);
            if (value == null)
            {
                continue;
            }
            map.removeKey(key);
            map.put(Integer.valueOf(key.intValue() + count), value);
            if (rekeyed != null)
            {
                rekeyed.moved(value, key.intValue() + count);
            }
            shifted++;
        }
        return shifted;
    }

    /**
     * Removes the entries of a 0-based range from a keyed containment map and moves every entry
     * past it down by the width of the range.
     *
     * @param <V> the value the map holds
     * @param map the map to re-key
     * @param firstKey the first 0-based key to remove
     * @param lastKey the last 0-based key to remove, inclusive
     * @param rekeyed told each value its new key, or <code>null</code> when the values carry no
     *            position of their own to repoint
     * @return how many entries past the range moved down
     */
    private static <V> int removeEntriesAndShiftLeft(EMap<Integer, V> map, int firstKey,
        int lastKey, Rekeyed<V> rekeyed)
    {
        List<Integer> keys = new ArrayList<>();
        for (Map.Entry<Integer, V> held : map)
        {
            if (held != null && held.getKey() != null)
            {
                keys.add(held.getKey());
            }
        }
        Collections.sort(keys);
        int removed = lastKey - firstKey + 1;
        int shifted = 0;
        for (Integer key : keys)
        {
            if (key.intValue() < firstKey)
            {
                continue;
            }
            // The same rule as in shiftEntriesRight: the value is read before the removal, because
            // a containment map's removeKey does not hand back the object the key held.
            V value = map.get(key);
            if (value == null)
            {
                continue;
            }
            map.removeKey(key);
            if (key.intValue() > lastKey)
            {
                map.put(Integer.valueOf(key.intValue() - removed), value);
                if (rekeyed != null)
                {
                    rekeyed.moved(value, key.intValue() - removed);
                }
                shifted++;
            }
            // An entry inside the range is dropped, with everything it contains.
        }
        return shifted;
    }

    /**
     * Moves every column from a 0-based key right: the cells of every row, and the entries of every
     * column set with the size the set declares. A point at the declared size appends past every
     * declared column, so nothing inside the set moves and the size stays: the columns the
     * insertion adds are empty, and an empty column is no wider a table.
     *
     * @param doc the spreadsheet
     * @param fromKey the first 0-based column to move
     * @param count how far right each column moves
     * @return how many cells and column entries moved
     */
    private static int shiftColumnsRight(SpreadsheetDocument doc, int fromKey, int count)
    {
        int shifted = 0;
        for (Columns set : columnSetsOf(doc))
        {
            shifted += shiftEntriesRight(set.getColumns(), fromKey, count, null);
            int size = set.getSize();
            if (size > 0 && fromKey < size)
            {
                set.setSize(size + count);
            }
        }
        for (Row row : doc.getRows().values())
        {
            if (row == null)
            {
                continue;
            }
            shifted += shiftEntriesRight(row.getCells(), fromKey, count,
                (cell, newKey) -> repointNoteColumns(cell, newKey, count));
        }
        return shifted;
    }

    /**
     * Removes the columns of a 0-based range and moves every column to the right of it left: the
     * cells of every row, and the entries of every column set with the size the set declares.
     *
     * @param doc the spreadsheet
     * @param firstKey the first 0-based column to remove
     * @param lastKey the last 0-based column to remove, inclusive
     * @return how many cells and column entries moved
     */
    private static int removeColumnsAndShiftLeft(SpreadsheetDocument doc, int firstKey, int lastKey)
    {
        int removed = lastKey - firstKey + 1;
        int shifted = 0;
        for (Columns set : columnSetsOf(doc))
        {
            shifted += removeEntriesAndShiftLeft(set.getColumns(), firstKey, lastKey, null);
            int size = set.getSize();
            if (size > 0 && firstKey <= size - 1)
            {
                int within = Math.min(lastKey, size - 1) - firstKey + 1;
                set.setSize(Math.max(0, size - within));
            }
        }
        for (Row row : doc.getRows().values())
        {
            if (row == null)
            {
                continue;
            }
            shifted += removeEntriesAndShiftLeft(row.getCells(), firstKey, lastKey,
                (cell, newKey) -> repointNoteColumns(cell, newKey, -removed));
        }
        return shifted;
    }

    /**
     * Every column set the document reaches, each once.
     * <p>
     * A set is reached from the document's own default, from the all-columns list, from the rows
     * that carry their own and from the saved fixation - and one set serves many rows, so a walk
     * that took every reference would move a shared set once per row that reaches it. The entries
     * are collected by identity: two references to one set are one set.
     * </p>
     *
     * @param doc the spreadsheet
     * @return the distinct column sets, in reach order; may be empty
     */
    private static List<Columns> columnSetsOf(SpreadsheetDocument doc)
    {
        List<Columns> sets = new ArrayList<>();
        if (doc.getColumns() != null)
        {
            sets.add(doc.getColumns());
        }
        for (Columns set : doc.getAllColumns())
        {
            if (set != null && !sets.contains(set))
            {
                sets.add(set);
            }
        }
        for (Row row : doc.getRows().values())
        {
            Columns set = row == null ? null : row.getColumns();
            if (set != null && !sets.contains(set))
            {
                sets.add(set);
            }
        }
        Columns fixed = doc.getViewSettings() == null ? null
            : doc.getViewSettings().getFixedColumnColumns();
        if (fixed != null && !sets.contains(fixed))
        {
            sets.add(fixed);
        }
        return sets;
    }

    /**
     * Points a row's cell notes at the row the row now sits on, and moves their own position along.
     *
     * @param row the row that moved
     * @param rowKey the row's new 0-based key
     * @param delta how far the row moved, for the note's own position
     */
    private static void repointNoteRows(Row row, int rowKey, int delta)
    {
        if (row == null)
        {
            return;
        }
        for (Map.Entry<Integer, Cell> held : row.getCells())
        {
            Cell cell = held == null ? null : held.getValue();
            if (cell == null || cell.getNoteDrawing() == null)
            {
                continue;
            }
            cell.getNoteDrawing().setCellRowIndex(rowKey);
            moveNotePosition(cell.getNoteDrawing(), delta, Axis.ROW);
        }
    }

    /**
     * Points a cell's note at the column the cell now sits on, and moves its own position along.
     *
     * @param cell the cell that moved
     * @param colKey the cell's new 0-based column key
     * @param delta how far the cell moved, for the note's own position
     */
    private static void repointNoteColumns(Cell cell, int colKey, int delta)
    {
        if (cell == null || cell.getNoteDrawing() == null)
        {
            return;
        }
        cell.getNoteDrawing().setCellColumnIndex(colKey);
        moveNotePosition(cell.getNoteDrawing(), delta, Axis.COLUMN);
    }

    /**
     * Moves a note drawing's own anchor by a delta on an axis, when it carries one.
     *
     * @param note the note drawing
     * @param delta how far to move, in positions on the axis
     * @param axis the axis to move on
     */
    private static void moveNotePosition(Drawing note, int delta, Axis axis)
    {
        if (delta == 0 || note.getPosition() == null)
        {
            return;
        }
        movePointAt(note.getPosition().getBegin(), delta, axis);
        movePointAt(note.getPosition().getEnd(), delta, axis);
    }

    /**
     * Moves a drawing point's cell by a delta on an axis.
     *
     * @param point the point, or <code>null</code>
     * @param delta how far to move, in positions on the axis
     * @param axis the axis to move on
     */
    private static void movePointAt(SpreadsheetPoint point, int delta, Axis axis)
    {
        if (point == null || point.getCell() == null)
        {
            return;
        }
        if (axis == Axis.COLUMN)
        {
            point.getCell().setX(Math.max(0, point.getCell().getX() + delta));
        }
        else
        {
            point.getCell().setY(Math.max(0, point.getCell().getY() + delta));
        }
    }

    /**
     * Moves the merges of a list over an insertion or a deletion on an axis.
     *
     * @param merges the merges to move
     * @param inserting whether positions were inserted or deleted
     * @param at the 0-based position the change starts at
     * @param count how many positions were inserted or deleted
     * @param outcome where the counters land, or <code>null</code> to move silently
     * @param axis the axis the shift runs over
     */
    private static void moveMerges(EList<Merge> merges, boolean inserting, int at, int count,
        ShiftOutcome outcome, Axis axis)
    {
        java.util.Iterator<Merge> each = merges.iterator();
        while (each.hasNext())
        {
            Merge merge = each.next();
            if (merge == null || merge.getPosition() == null)
            {
                continue;
            }
            Rect position = merge.getPosition();
            int begin = boundsOf(position, axis)[0];
            int end = boundsOf(position, axis)[1];
            Span moved = movedSpan(begin, end, inserting, at, count);
            if (moved == null)
            {
                each.remove();
                if (outcome != null)
                {
                    outcome.removedMerges++;
                }
                continue;
            }
            if (moved.begin != begin || moved.end != end)
            {
                writeRect(position, moved, axis);
                if (outcome != null && spanLengthChanged(begin, end, moved.begin, moved.end))
                {
                    outcome.resizedMerges++;
                }
            }
        }
    }

    /**
     * Moves the whole-row merges over an insertion or a deletion of rows.
     *
     * @param rowMerges the merges to move
     * @param inserting whether rows were inserted or deleted
     * @param at the 0-based row the change starts at
     * @param count how many rows were inserted or deleted
     * @param outcome where the counters land, or <code>null</code> to move silently
     */
    private static void moveRowMerges(EList<RowMerge> rowMerges, boolean inserting, int at,
        int count, ShiftOutcome outcome)
    {
        List<RowMerge> gone = new ArrayList<>();
        for (RowMerge merge : rowMerges)
        {
            if (merge == null)
            {
                continue;
            }
            Span moved = movedSpan(merge.getBegin(), merge.getEnd(), inserting, at, count);
            if (moved == null)
            {
                gone.add(merge);
            }
            else if (moved.begin != merge.getBegin() || moved.end != merge.getEnd())
            {
                boolean lengthChanged = spanLengthChanged(merge.getBegin(), merge.getEnd(),
                    moved.begin, moved.end);
                merge.setBegin(moved.begin);
                merge.setEnd(moved.end);
                if (outcome != null && lengthChanged)
                {
                    outcome.resizedMerges++;
                }
            }
        }
        if (!gone.isEmpty())
        {
            rowMerges.removeAll(gone);
            if (outcome != null)
            {
                outcome.removedMerges += gone.size();
            }
        }
    }

    /**
     * Moves the whole-column merges over an insertion or a deletion of columns.
     *
     * @param columnMerges the merges to move
     * @param inserting whether columns were inserted or deleted
     * @param at the 0-based column the change starts at
     * @param count how many columns were inserted or deleted
     * @param outcome where the counters land, or <code>null</code> to move silently
     */
    private static void moveColumnMerges(EList<ColumnMerge> columnMerges, boolean inserting,
        int at, int count, ShiftOutcome outcome)
    {
        List<ColumnMerge> gone = new ArrayList<>();
        for (ColumnMerge merge : columnMerges)
        {
            if (merge == null)
            {
                continue;
            }
            Span moved = movedSpan(merge.getBegin(), merge.getEnd(), inserting, at, count);
            if (moved == null)
            {
                gone.add(merge);
            }
            else if (moved.begin != merge.getBegin() || moved.end != merge.getEnd())
            {
                boolean lengthChanged = spanLengthChanged(merge.getBegin(), merge.getEnd(),
                    moved.begin, moved.end);
                merge.setBegin(moved.begin);
                merge.setEnd(moved.end);
                if (outcome != null && lengthChanged)
                {
                    outcome.resizedMerges++;
                }
            }
        }
        if (!gone.isEmpty())
        {
            columnMerges.removeAll(gone);
            if (outcome != null)
            {
                outcome.removedMerges += gone.size();
            }
        }
    }

    /**
     * Moves the row groups over an insertion or a deletion of rows.
     *
     * @param rowGroups the groups to move
     * @param inserting whether rows were inserted or deleted
     * @param at the 0-based row the change starts at
     * @param count how many rows were inserted or deleted
     */
    private static void moveRowGroups(EList<RowGroup> rowGroups, boolean inserting, int at, int count)
    {
        List<RowGroup> gone = new ArrayList<>();
        for (RowGroup group : rowGroups)
        {
            if (group == null)
            {
                continue;
            }
            Span moved = movedSpan(group.getBegin(), group.getEnd(), inserting, at, count);
            if (moved == null)
            {
                gone.add(group);
            }
            else if (moved.begin != group.getBegin() || moved.end != group.getEnd())
            {
                group.setBegin(moved.begin);
                group.setEnd(moved.end);
            }
        }
        if (!gone.isEmpty())
        {
            rowGroups.removeAll(gone);
        }
    }

    /**
     * Moves the column groups over an insertion or a deletion of columns.
     *
     * @param columnGroups the groups to move
     * @param inserting whether columns were inserted or deleted
     * @param at the 0-based column the change starts at
     * @param count how many columns were inserted or deleted
     */
    private static void moveColumnGroups(EList<ColumnGroup> columnGroups, boolean inserting,
        int at, int count)
    {
        List<ColumnGroup> gone = new ArrayList<>();
        for (ColumnGroup group : columnGroups)
        {
            if (group == null)
            {
                continue;
            }
            Span moved = movedSpan(group.getBegin(), group.getEnd(), inserting, at, count);
            if (moved == null)
            {
                gone.add(group);
            }
            else if (moved.begin != group.getBegin() || moved.end != group.getEnd())
            {
                group.setBegin(moved.begin);
                group.setEnd(moved.end);
            }
        }
        if (!gone.isEmpty())
        {
            columnGroups.removeAll(gone);
        }
    }

    /**
     * Moves the named items' areas over an insertion or a deletion on an axis.
     *
     * @param doc the spreadsheet holding the named items
     * @param inserting whether positions were inserted or deleted
     * @param at the 0-based position the change starts at
     * @param count how many positions were inserted or deleted
     * @param outcome where the names land, or <code>null</code> to move silently
     * @param axis the axis the shift runs over
     */
    private static void moveNamedItems(SpreadsheetDocument doc, boolean inserting, int at,
        int count, ShiftOutcome outcome, Axis axis)
    {
        EMap<String, NamedItem> items = doc.getNamedItems();
        List<String> gone = new ArrayList<>();
        for (Map.Entry<String, NamedItem> held : items)
        {
            if (held == null || held.getKey() == null)
            {
                continue;
            }
            Area area = areaOf(held.getValue());
            Span span = spanOf(area, axis);
            if (span == null)
            {
                continue;
            }
            Span moved = movedSpan(span.begin, span.end, inserting, at, count);
            if (moved == null)
            {
                gone.add(held.getKey());
            }
            else if (moved.begin != span.begin || moved.end != span.end)
            {
                writeSpan(area, moved, axis);
                if (outcome != null && spanLengthChanged(span.begin, span.end, moved.begin,
                    moved.end))
                {
                    outcome.resizedNamedAreas.add(held.getKey());
                }
            }
        }
        for (String name : gone)
        {
            items.removeKey(name);
            if (outcome != null)
            {
                outcome.removedNamedAreas.add(name);
            }
        }
    }

    /**
     * Moves the drawings over an insertion or a deletion on an axis.
     * <p>
     * A drawing the deleted range swallows goes away and is named by id; one it cuts in half keeps
     * its near anchor and loses the positions that went; one past the range moves toward it. An end
     * anchor with no offset stands on the near edge of its position, so it reaches no position of
     * its own: an end on the first position past the deleted range with an offset of 0 means the
     * drawing lay in the range whole, and it goes away with it. An anchor whose position was
     * deleted re-anchors at the near edge of the first surviving position with an offset of 0, so
     * the begin never ends up past the end. A drawing with no anchor at all is left alone - there
     * is nothing to reason with.
     * </p>
     *
     * @param doc the spreadsheet holding the drawings
     * @param inserting whether positions were inserted or deleted
     * @param at the 0-based position the change starts at
     * @param count how many positions were inserted or deleted
     * @param outcome where the ids land, or <code>null</code> to move silently
     * @param axis the axis the shift runs over
     * @return the ids of the drawings the deletion took, so what feeds them can go with them
     */
    private static List<Integer> moveDrawings(SpreadsheetDocument doc, boolean inserting, int at,
        int count, ShiftOutcome outcome, Axis axis)
    {
        List<Drawing> gone = new ArrayList<>();
        for (Drawing drawing : doc.getDrawings())
        {
            if (drawing == null || drawing.getPosition() == null)
            {
                continue;
            }
            SpreadsheetPoint begin = drawing.getPosition().getBegin();
            SpreadsheetPoint end = drawing.getPosition().getEnd();
            int beginAt = pointAtOf(begin, axis);
            int endAt = pointAtOf(end, axis);
            if (beginAt < 0 && endAt < 0)
            {
                continue;
            }
            int endOffset = pointOffsetOf(end, axis);
            if (!inserting && beginAt >= 0 && beginAt <= endAt
                && (endOffset != 0 || endAt > beginAt))
            {
                moveDrawingOverDeletion(drawing, begin, end, beginAt, endAt, endOffset,
                    at, count, outcome, axis, gone);
                continue;
            }
            int from = beginAt < 0 ? endAt : beginAt;
            int to = endAt < 0 ? beginAt : endAt;
            Span moved = movedSpan(Math.min(from, to), Math.max(from, to), inserting, at, count);
            if (moved == null)
            {
                gone.add(drawing);
                if (outcome != null)
                {
                    outcome.removedDrawings.add(Integer.valueOf(drawing.getDrawingId()));
                }
                continue;
            }
            setPointAt(begin, moved.begin, axis);
            setPointAt(end, moved.end, axis);
        }
        if (!gone.isEmpty())
        {
            doc.getDrawings().removeAll(gone);
        }
        List<Integer> goneIds = new ArrayList<>(gone.size());
        for (Drawing drawing : gone)
        {
            goneIds.add(Integer.valueOf(drawing.getDrawingId()));
        }
        return goneIds;
    }

    /**
     * Moves one drawing over a deletion on an axis, by the positions its anchors occupy rather
     * than the ones they name.
     * <p>
     * An end anchor with an offset of 0 stands on the near edge of its position and occupies none
     * of it, so the last position the drawing occupies is the one before. A drawing whose occupied
     * span the deleted range swallows whole goes away and is named by id; one cut at the begin
     * re-anchors its begin at the near edge of the first surviving position; one cut at the end
     * ends at the near edge of that same position.
     * </p>
     *
     * @param drawing the drawing to move
     * @param begin the drawing's begin anchor
     * @param end the drawing's end anchor
     * @param beginAt the position the begin anchor names on the axis, 0-based
     * @param endAt the position the end anchor names on the axis, 0-based
     * @param endOffset the end anchor's offset inside its position on the axis
     * @param at the 0-based position the deletion starts at
     * @param count how many positions were deleted
     * @param outcome where the id lands, or <code>null</code> to move silently
     * @param axis the axis the shift runs over
     * @param gone where a drawing the range swallowed is collected
     */
    private static void moveDrawingOverDeletion(Drawing drawing,
        SpreadsheetPoint begin, SpreadsheetPoint end, int beginAt, int endAt, int endOffset,
        int at, int count, ShiftOutcome outcome, Axis axis, List<Drawing> gone)
    {
        int last = at + count - 1;
        int occupiedEnd = endOffset == 0 ? endAt - 1 : endAt;
        if (beginAt > last)
        {
            setPointAt(begin, beginAt - count, axis);
            setPointAt(end, endAt - count, axis);
        }
        else if (occupiedEnd < at)
        {
            // The drawing ends at or over the near edge of the first deleted position: nothing
            // of it lay in the range, and nothing moves.
        }
        else if (beginAt >= at && occupiedEnd <= last)
        {
            gone.add(drawing);
            if (outcome != null)
            {
                outcome.removedDrawings.add(Integer.valueOf(drawing.getDrawingId()));
            }
        }
        else if (beginAt >= at)
        {
            // The positions the begin was anchored in are gone: the drawing starts where the
            // first surviving position starts.
            setPointAt(begin, at, axis);
            setPointOffset(begin, 0, axis);
            setPointAt(end, endAt - count, axis);
        }
        else if (occupiedEnd <= last)
        {
            // The positions the end was anchored in are gone: the drawing ends where the first
            // surviving position starts.
            setPointAt(end, at, axis);
            setPointOffset(end, 0, axis);
        }
        else
        {
            setPointAt(end, endAt - count, axis);
        }
    }

    /**
     * Moves the drawing data sources' areas over an insertion or a deletion on an axis.
     * <p>
     * A chart or a pivot reads its data from an area, and that area travels with the rows and
     * columns the operation moves - a source left behind keeps the drawing reading the cells that
     * moved away. A deletion that takes an area entirely takes the source with it: the model
     * requires an area on every source, so none is left behind empty. A source whose drawing the
     * deletion took goes with its drawing, wherever its own area lay - a source pointing at a
     * drawing that no longer exists reads nothing. Such a source is counted once, even when its
     * area was taken too.
     * </p>
     *
     * @param doc the spreadsheet holding the data sources
     * @param inserting whether positions were inserted or deleted
     * @param at the 0-based position the change starts at
     * @param count how many positions were inserted or deleted
     * @param outcome where the counter lands, or <code>null</code> to move silently
     * @param axis the axis the shift runs over
     * @param removedDrawingIds the ids of the drawings the deletion already took
     */
    private static void moveDrawingDataSources(SpreadsheetDocument doc, boolean inserting, int at,
        int count, ShiftOutcome outcome, Axis axis, List<Integer> removedDrawingIds)
    {
        List<DrawingsDataSource> gone = new ArrayList<>();
        for (DrawingsDataSource source : doc.getDrawingDataSources())
        {
            if (source == null)
            {
                continue;
            }
            if (!inserting && removedDrawingIds.contains(Integer.valueOf(source.getDrawingId())))
            {
                gone.add(source);
                continue;
            }
            Area area = source.getArea();
            Span span = spanOf(area, axis);
            if (span == null)
            {
                continue;
            }
            Span moved = movedSpan(span.begin, span.end, inserting, at, count);
            if (moved == null)
            {
                gone.add(source);
            }
            else if (moved.begin != span.begin || moved.end != span.end)
            {
                writeSpan(area, moved, axis);
            }
        }
        if (!gone.isEmpty())
        {
            doc.getDrawingDataSources().removeAll(gone);
            if (outcome != null)
            {
                outcome.removedDataSources += gone.size();
            }
        }
    }

    /**
     * Points a drawing anchor at a position on an axis.
     *
     * @param point the anchor, or <code>null</code>
     * @param at the 0-based position to point at
     * @param axis the axis to write
     */
    private static void setPointAt(SpreadsheetPoint point, int at, Axis axis)
    {
        if (point == null || point.getCell() == null)
        {
            return;
        }
        if (axis == Axis.COLUMN)
        {
            point.getCell().setX(at);
        }
        else
        {
            point.getCell().setY(at);
        }
    }

    /**
     * Moves the document's print and repeat areas over an insertion or a deletion on an axis.
     * <p>
     * An area the deleted range swallows is cleared: the document then prints the way it does with
     * no area named, which is what nothing selected reads as.
     * </p>
     *
     * @param doc the spreadsheet
     * @param inserting whether positions were inserted or deleted
     * @param at the 0-based position the change starts at
     * @param count how many positions were inserted or deleted
     * @param axis the axis the shift runs over; rows repeat, columns repeat
     */
    private static void moveDocumentAreas(SpreadsheetDocument doc, boolean inserting, int at,
        int count, Axis axis)
    {
        if (spanGone(doc.getPrintArea(), inserting, at, count, axis))
        {
            doc.setPrintArea(null);
        }
        Area repeated = axis == Axis.COLUMN ? doc.getRepeatColumns() : doc.getRepeatRows();
        if (spanGone(repeated, inserting, at, count, axis))
        {
            if (axis == Axis.COLUMN)
            {
                doc.setRepeatColumns(null);
            }
            else
            {
                doc.setRepeatRows(null);
            }
        }
    }

    /**
     * Whether a deletion took the area entirely.
     *
     * @param area the area, or <code>null</code>
     * @param inserting whether positions were inserted or deleted
     * @param at the 0-based position the change starts at
     * @param count how many positions were inserted or deleted
     * @param axis the axis to measure on
     * @return <code>true</code> only when the area is gone
     */
    private static boolean spanGone(Area area, boolean inserting, int at, int count, Axis axis)
    {
        Span span = spanOf(area, axis);
        if (span == null)
        {
            return false;
        }
        Span moved = movedSpan(span.begin, span.end, inserting, at, count);
        if (moved == null)
        {
            return true;
        }
        if (moved.begin != span.begin || moved.end != span.end)
        {
            writeSpan(area, moved, axis);
        }
        return false;
    }

    /**
     * Moves the saved view positions over an insertion or a deletion on an axis.
     * <p>
     * These are where the interactive editor looked - the current position, the fixation points,
     * the top of the screen - together with the count of frozen rows or columns and the selection.
     * An operation that leaves them behind would point the editor at content that moved, so they
     * travel with it. A view the document never saved is not there to move.
     * </p>
     *
     * @param doc the spreadsheet
     * @param inserting whether positions were inserted or deleted
     * @param at the 0-based position the change starts at
     * @param count how many positions were inserted or deleted
     * @param axis the axis the shift runs over
     */
    private static void moveViewPointers(SpreadsheetDocument doc, boolean inserting, int at,
        int count, Axis axis)
    {
        ViewSettings view = doc.getViewSettings();
        if (view == null)
        {
            return;
        }
        if (axis == Axis.COLUMN)
        {
            view.setCurrentColumn(movedPointer(view.getCurrentColumn(), inserting, at, count));
            view.setFixedColumn(movedCount(view.getFixedColumn(), inserting, at, count));
            view.setFixationPointColumn(
                movedPointer(view.getFixationPointColumn(), inserting, at, count));
            view.setTopFixationPointColumn(
                movedPointer(view.getTopFixationPointColumn(), inserting, at, count));
            view.setScreenBeginPointColumn(
                movedPointer(view.getScreenBeginPointColumn(), inserting, at, count));
        }
        else
        {
            view.setCurrentRow(movedPointer(view.getCurrentRow(), inserting, at, count));
            view.setFixedRow(movedCount(view.getFixedRow(), inserting, at, count));
            view.setFixationPointRow(movedPointer(view.getFixationPointRow(), inserting, at, count));
            view.setTopFixationPointRow(
                movedPointer(view.getTopFixationPointRow(), inserting, at, count));
            view.setScreenBeginPointRow(
                movedPointer(view.getScreenBeginPointRow(), inserting, at, count));
        }
        EList<Area> selection = view.getSelection();
        if (selection.isEmpty())
        {
            return;
        }
        List<Area> kept = new ArrayList<>();
        for (Area area : selection)
        {
            Span span = spanOf(area, axis);
            if (span == null)
            {
                kept.add(area);
                continue;
            }
            Span moved = movedSpan(span.begin, span.end, inserting, at, count);
            if (moved != null)
            {
                if (moved.begin != span.begin || moved.end != span.end)
                {
                    writeSpan(area, moved, axis);
                }
                kept.add(area);
            }
        }
        if (kept.size() != selection.size())
        {
            selection.clear();
            selection.addAll(kept);
        }
    }

    /**
     * Where a single pointer lands after positions were inserted or deleted on its axis.
     *
     * @param at the pointer, 0-based
     * @param inserting whether positions were inserted or deleted
     * @param from the 0-based position the change starts at
     * @param count how many positions were inserted or deleted
     * @return the position the pointer lands on
     */
    private static int movedPointer(int at, boolean inserting, int from, int count)
    {
        if (inserting)
        {
            return at >= from ? at + count : at;
        }
        if (at < from)
        {
            return at;
        }
        return at > from + count - 1 ? at - count : from;
    }

    /**
     * Where a COUNT of leading positions lands after positions were inserted or deleted on its
     * axis.
     * <p>
     * The saved fixation counts the frozen rows or columns of a prefix that starts at the top or
     * the left; it is not a pointer into the sheet. Nothing frozen stays nothing, and only a
     * change the prefix itself reaches can change the number: a change inside it grows or shrinks
     * it, one past it leaves it alone.
     * </p>
     *
     * @param count the number of frozen positions
     * @param inserting whether positions were inserted or deleted
     * @param from the 0-based position the change starts at
     * @param changed how many positions were inserted or deleted
     * @return the number of frozen positions after the change
     */
    private static int movedCount(int count, boolean inserting, int from, int changed)
    {
        if (count < 1)
        {
            return 0;
        }
        if (inserting)
        {
            return from < count ? count + changed : count;
        }
        if (from < count)
        {
            int within = Math.min(from + changed - 1, count - 1) - from + 1;
            return Math.max(0, count - within);
        }
        return count;
    }

    /**
     * Moves the declared height over an insertion or a deletion of rows.
     * <p>
     * A height of zero is nothing declared, and stays that way: the document then sizes itself by
     * its content, which the operation just changed. An insertion at the declared end moves nothing
     * down: the declared rows all stay above it, and the rows the insertion adds are empty, so the
     * table is no taller. Anything from the declared last row down does move, and the height
     * follows it.
     * </p>
     *
     * @param doc the spreadsheet
     * @param inserting whether rows were inserted or deleted
     * @param at the 0-based row the change starts at
     * @param count how many rows were inserted or deleted
     */
    private static void moveHeight(SpreadsheetDocument doc, boolean inserting, int at, int count)
    {
        int height = doc.getHeight();
        if (height <= 0)
        {
            return;
        }
        if (inserting)
        {
            if (at < height)
            {
                doc.setHeight(height + count);
            }
            return;
        }
        if (at <= height - 1)
        {
            int within = Math.min(at + count - 1, height - 1) - at + 1;
            doc.setHeight(Math.max(0, height - within));
        }
    }

    /**
     * Where a span lands after positions were inserted or deleted on its axis.
     * <p>
     * Insertion: a span at or past the point moves away from the start, a span the point falls
     * inside grows, a span before it stays. Deletion: a span before the range stays, a span past it
     * moves toward the start, a span the range swallows is gone, and a span the range cuts shrinks
     * by the positions it lost.
     * </p>
     *
     * @param begin the span's first position, 0-based inclusive
     * @param end the span's last position, 0-based inclusive
     * @param inserting whether positions were inserted or deleted
     * @param at the 0-based position the change starts at
     * @param count how many positions were inserted or deleted
     * @return where the span landed, or <code>null</code> when a deletion took it entirely
     */
    private static Span movedSpan(int begin, int end, boolean inserting, int at, int count)
    {
        Span moved = new Span();
        if (inserting)
        {
            moved.begin = begin >= at ? begin + count : begin;
            moved.end = end >= at ? end + count : end;
            return moved;
        }
        int last = at + count - 1;
        if (end < at)
        {
            moved.begin = begin;
            moved.end = end;
        }
        else if (begin > last)
        {
            moved.begin = begin - count;
            moved.end = end - count;
        }
        else if (begin >= at && end <= last)
        {
            return null;
        }
        else
        {
            int lost = Math.min(end, last) - Math.max(begin, at) + 1;
            moved.begin = begin < at ? begin : at;
            moved.end = moved.begin + (end - begin - lost);
        }
        return moved;
    }

    /**
     * Whether a move changed how long a span runs, as opposed to where it runs.
     * <p>
     * A shift translates every element past the point by the same distance, so an answer that
     * counted a translated element as a resized one would name everything below an insertion as
     * resized. What the counters report is the change a reader can see: the span now covers more
     * or fewer positions than it did.
     * </p>
     *
     * @param beginBefore the span's first position before the move, 0-based inclusive
     * @param endBefore the span's last position before the move, 0-based inclusive
     * @param beginAfter the span's first position after the move
     * @param endAfter the span's last position after the move
     * @return whether the length the span covers changed
     */
    private static boolean spanLengthChanged(int beginBefore, int endBefore, int beginAfter,
        int endAfter)
    {
        return endAfter - beginAfter != endBefore - beginBefore;
    }

    /**
     * Replaces the merges over the target range of a copy with copies of the source merges.
     * <p>
     * Both kinds of merge take part: the rectangular ones anchored to cells, and the whole-row or
     * whole-column ones. A merge fully inside the replaced target comes off; one fully inside the
     * source repeats over the target; one the target range cuts in half stays as it is. The
     * unmerge exceptions take the same ride over the same ranges, without landing in the
     * outcome's counters: an unmerge is the exception its merge carves out, and a copy that moved
     * the merge leaves the exception claiming cells the merge no longer covers.
     * </p>
     *
     * @param doc the spreadsheet holding the merges
     * @param sourceFirst the source range's first 0-based position on the axis
     * @param targetFirst the target range's first 0-based position on the axis
     * @param count how many positions the ranges hold
     * @param outcome where the removals are counted
     * @param axis the axis the copy runs over
     */
    private static void replaceMergesOverRange(SpreadsheetDocument doc, int sourceFirst,
        int targetFirst, int count, ShiftOutcome outcome, Axis axis)
    {
        replaceRectMergeListOverRange(doc.getMerges(), sourceFirst, targetFirst, count, outcome,
            axis);
        replaceRectMergeListOverRange(doc.getUnmerges(), sourceFirst, targetFirst, count, null,
            axis);
        if (axis == Axis.ROW)
        {
            replaceRowMergesOverRange(doc.getRowMerges(), sourceFirst, targetFirst, count,
                outcome);
        }
        else
        {
            replaceColumnMergesOverRange(doc.getColumnMerges(), sourceFirst, targetFirst, count,
                outcome);
        }
    }

    /**
     * Replaces the rectangular merge entries of one list over the target range of a copy with
     * copies of the source entries.
     * <p>
     * An entry fully inside the replaced target comes off; one fully inside the source repeats
     * over the target; one the target range cuts in half stays as it is. Removals are counted
     * only when the caller passes an outcome: the unmerge exceptions ride the same ranges but the
     * answer's counters speak about merges.
     * </p>
     *
     * @param merges the rectangular merge entries to replace
     * @param sourceFirst the source range's first 0-based position on the axis
     * @param targetFirst the target range's first 0-based position on the axis
     * @param count how many positions the ranges hold
     * @param outcome where the removals are counted, or <code>null</code> to replace silently
     * @param axis the axis the copy runs over
     */
    private static void replaceRectMergeListOverRange(EList<Merge> merges, int sourceFirst,
        int targetFirst, int count, ShiftOutcome outcome, Axis axis)
    {
        int sourceLast = sourceFirst + count - 1;
        int targetLast = targetFirst + count - 1;
        java.util.Iterator<Merge> each = merges.iterator();
        while (each.hasNext())
        {
            Merge merge = each.next();
            if (merge == null || merge.getPosition() == null)
            {
                continue;
            }
            Rect position = merge.getPosition();
            int begin = boundsOf(position, axis)[0];
            int end = boundsOf(position, axis)[1];
            if (begin >= targetFirst && end <= targetLast)
            {
                each.remove();
                if (outcome != null)
                {
                    outcome.removedMerges++;
                }
            }
        }
        for (Merge merge : new ArrayList<>(merges))
        {
            if (merge == null || merge.getPosition() == null)
            {
                continue;
            }
            Rect position = merge.getPosition();
            int begin = boundsOf(position, axis)[0];
            int end = boundsOf(position, axis)[1];
            if (begin >= sourceFirst && end <= sourceLast)
            {
                Merge copy = (Merge)EcoreUtil.copy(merge);
                shiftRect(copy.getPosition(), targetFirst - sourceFirst, axis);
                merges.add(copy);
            }
        }
    }

    /**
     * Replaces the whole-row merges over the target range of a copy with copies of the source ones.
     *
     * @param rowMerges the whole-row merges the document holds
     * @param sourceFirst the source range's first 0-based row
     * @param targetFirst the target range's first 0-based row
     * @param count how many rows the ranges hold
     * @param outcome where the removals are counted
     */
    private static void replaceRowMergesOverRange(EList<RowMerge> rowMerges, int sourceFirst,
        int targetFirst, int count, ShiftOutcome outcome)
    {
        java.util.Iterator<RowMerge> each = rowMerges.iterator();
        while (each.hasNext())
        {
            RowMerge merge = each.next();
            if (merge == null)
            {
                continue;
            }
            if (merge.getBegin() >= targetFirst && merge.getEnd() <= targetFirst + count - 1)
            {
                each.remove();
                outcome.removedMerges++;
            }
        }
        for (RowMerge merge : new ArrayList<>(rowMerges))
        {
            if (merge == null)
            {
                continue;
            }
            if (merge.getBegin() >= sourceFirst && merge.getEnd() <= sourceFirst + count - 1)
            {
                RowMerge copy = MoxelFactory.eINSTANCE.createRowMerge();
                copy.setBegin(merge.getBegin() + targetFirst - sourceFirst);
                copy.setEnd(merge.getEnd() + targetFirst - sourceFirst);
                rowMerges.add(copy);
            }
        }
    }

    /**
     * Replaces the whole-column merges over the target range of a copy with copies of the source
     * ones.
     *
     * @param columnMerges the whole-column merges the document holds
     * @param sourceFirst the source range's first 0-based column
     * @param targetFirst the target range's first 0-based column
     * @param count how many columns the ranges hold
     * @param outcome where the removals are counted
     */
    private static void replaceColumnMergesOverRange(EList<ColumnMerge> columnMerges,
        int sourceFirst, int targetFirst, int count, ShiftOutcome outcome)
    {
        java.util.Iterator<ColumnMerge> each = columnMerges.iterator();
        while (each.hasNext())
        {
            ColumnMerge merge = each.next();
            if (merge == null)
            {
                continue;
            }
            if (merge.getBegin() >= targetFirst && merge.getEnd() <= targetFirst + count - 1)
            {
                each.remove();
                outcome.removedMerges++;
            }
        }
        for (ColumnMerge merge : new ArrayList<>(columnMerges))
        {
            if (merge == null)
            {
                continue;
            }
            if (merge.getBegin() >= sourceFirst && merge.getEnd() <= sourceFirst + count - 1)
            {
                ColumnMerge copy = MoxelFactory.eINSTANCE.createColumnMerge();
                copy.setBegin(merge.getBegin() + targetFirst - sourceFirst);
                copy.setEnd(merge.getEnd() + targetFirst - sourceFirst);
                columnMerges.add(copy);
            }
        }
    }

    /**
     * Reads a {@link SpreadsheetDocument} into a plain map - the inverse of
     * {@link #setCellText} / {@link #mergeCells}. Read-only. The moxel row/cell
     * maps are sparse (only populated rows/cols exist), so {@code rowCount} /
     * {@code colCount} are the maximum populated (or merged) 1-based indices -
     * 0 when the sheet is empty. The declared extent counts as populated: the
     * declared height and the size of the column sets are the row and column
     * counts the platform answers with, so a sheet whose cells are listed short
     * of its declared extent reads as the extent it declares. Indices are
     * reported 1-based (row 1 = the physical top-left), the inverse of
     * {@link #setCellText} /
     * {@link #mergeCells}: the moxel model stores 0-based keys internally and
     * this read adds 1 at the boundary. Populates:
     * <ul>
     * <li>{@code rowCount} / {@code colCount} / {@code cellCount}
     * <li>{@code cells} - array of {@code {row, col, text}} for cells that carry text, a
     * parameter, a parameter or template fill, or a non-zero rotation. A default text fill and a
     * zero rotation are what an untouched cell reads as, so an empty cell carrying only those is
     * not listed and does not grow the counts. {@code textOrientation} is degrees (the model value
     * divided by ten). {@code parameter} and {@code fillType} are present when the cell has them.
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
                    Cell held = ce.getValue();
                    String text = cellText(held, language);
                    String parameter = held.getParameter();
                    boolean named = parameter != null && !parameter.isEmpty();
                    Format format = formatAt(doc, held.getFormatIndex());
                    Integer degrees = textOrientationDegrees(format);
                    boolean turned = degrees != null && degrees.intValue() != 0;
                    FillType fill = format != null && format.isSetFillType()
                        ? format.getFillType() : null;
                    boolean fillToShow = fill == FillType.PARAMETER || fill == FillType.TEMPLATE;
                    boolean hasText = text != null && !text.isEmpty();
                    if (!hasText && !named && !fillToShow && !turned)
                    {
                        continue;
                    }
                    int colIdx = ce.getKey().intValue() + 1;
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("row", Integer.valueOf(rowIdx)); //$NON-NLS-1$
                    cm.put("col", Integer.valueOf(colIdx)); //$NON-NLS-1$
                    if (hasText)
                    {
                        cm.put("text", text); //$NON-NLS-1$
                    }
                    if (named)
                    {
                        cm.put("parameter", parameter); //$NON-NLS-1$
                    }
                    if (fillToShow)
                    {
                        cm.put("fillType", fill.getLiteral()); //$NON-NLS-1$
                    }
                    if (turned)
                    {
                        cm.put("textOrientation", degrees); //$NON-NLS-1$
                    }
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
                int toCol = mergeFarCorner(fromCol, p.getWidth());
                int toRow = mergeFarCorner(fromRow, p.getHeight());
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
            // The declared extent counts too. A sheet answers with the rows and columns it holds,
            // and the platform reads the row count from the declared height and the column count
            // from the declared size of the column set - a template whose cells were written but
            // whose extent was never settled holds rows the listed cells do not name. Reporting the
            // listed cells alone would answer a smaller sheet than the file carries.
            maxRow = Math.max(maxRow, doc.getHeight());
            for (Columns set : columnSetsOf(doc))
            {
                if (set != null)
                {
                    maxCol = Math.max(maxCol, set.getSize());
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rowCount", Integer.valueOf(maxRow)); //$NON-NLS-1$
        out.put("colCount", Integer.valueOf(maxCol)); //$NON-NLS-1$
        out.put("cellCount", Integer.valueOf(cells.size())); //$NON-NLS-1$
        out.put("cells", cells); //$NON-NLS-1$
        out.put("merges", merges); //$NON-NLS-1$
        out.put("drawings", drawings); //$NON-NLS-1$
        // Without these a template written elsewhere cannot be read back and repeated, which is
        // exactly what building a load template from an existing one needs.
        out.put("namedAreas", listNamedAreas(doc)); //$NON-NLS-1$
        return out;
    }

    /**
     * The format a cell points at, or {@code null} when the index is outside the table.
     *
     * @param doc the spreadsheet
     * @param index the format index
     * @return the format, or {@code null}
     */
    private static Format formatAt(SpreadsheetDocument doc, int index)
    {
        if (doc == null || index < 0 || index >= doc.getFormats().size())
        {
            return null;
        }
        return doc.getFormats().get(index);
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
        // gives every drawing its own format. The placeholder comes first, so
        // on a fresh document the drawing's format is not the one the writer
        // reads as "no format".
        int effectiveFormatIndex;
        if (formatIndex < 0)
        {
            ensureFormatPlaceholder(doc);
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
        settleExtent(doc);
        return id;
    }

    /**
     * Whether the spreadsheet holds a drawing with the given id.
     *
     * @param doc the document; may be <code>null</code>
     * @param drawingId the drawing id
     * @return whether a drawing with that id is there
     */
    public static boolean hasDrawing(SpreadsheetDocument doc, int drawingId)
    {
        if (doc == null)
        {
            return false;
        }
        for (Drawing d : doc.getDrawings())
        {
            if (d != null && d.getDrawingId() == drawingId)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the drawing with the given id from the spreadsheet, together with
     * the data sources that fed it - a source pointing at a drawing that no
     * longer exists reads nothing. Returns {@code true} when a drawing was
     * removed, {@code false} when none matched (idempotent).
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
                List<DrawingsDataSource> orphaned = new ArrayList<>();
                for (DrawingsDataSource source : doc.getDrawingDataSources())
                {
                    if (source != null && source.getDrawingId() == drawingId)
                    {
                        orphaned.add(source);
                    }
                }
                doc.getDrawingDataSources().removeAll(orphaned);
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
