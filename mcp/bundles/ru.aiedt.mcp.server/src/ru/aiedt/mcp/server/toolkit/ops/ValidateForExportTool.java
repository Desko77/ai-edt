/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Pre-export file scanner. Catches XDTO/XML defects in the hand-written
 * metadata source files (.mdo / .form / .dcs / .mxlx) that EDT's
 * {@code get_project_errors} (which validates the in-memory EMF model, not the
 * exported XDTO) silently passes, yet which crash {@code update_database} on
 * the 8.x platform with low-level XDTO / NPE failures.
 *
 * <p>The tool walks the project's {@code src/} tree, reads each applicable file
 * as UTF-8, runs a set of regex scanners, and reports each finding with a
 * project-relative file path, a best-effort metadata FQN, a check id, a
 * severity, a 1-based line number, and a message that states both the defect
 * and the fix. It is strictly read-only.
 *
 * <p>Run this immediately before {@code update_database} on configurations or
 * extensions whose metadata was authored / edited by hand.
 */
public class ValidateForExportTool implements IMcpTool
{
    public static final String NAME = "validate_for_export"; //$NON-NLS-1$

    private static final int DEFAULT_LIMIT = 500;

    // ---- .form scanners -------------------------------------------------

    /** Abstract command-handler container (without "Form") - platform NPE in CommandHandler.getName. */
    private static final Pattern FORM_ABSTRACT_COMMAND_HANDLER =
        Pattern.compile("xsi:type=\"form:CommandHandlerContainer\""); //$NON-NLS-1$

    /** <action ... FormCommandHandlerContainer ...></action> with no <handler> between. */
    private static final Pattern FORM_EMPTY_ACTION =
        Pattern.compile("<action[^>]*FormCommandHandlerContainer[^>]*>\\s*</action>", //$NON-NLS-1$
            Pattern.DOTALL);

    /** Body of each <columns>...</columns> block (a form attribute column - they do not nest). */
    private static final Pattern FORM_ATTR_COLUMN_BLOCK =
        Pattern.compile("<columns>(.*?)</columns>", Pattern.DOTALL); //$NON-NLS-1$

    /**
     * Body of each {@code <handlers>...</handlers>} block (a form event-handler
     * binding). A valid binding carries both {@code <event>} and {@code <name>};
     * a block missing {@code <event>} (which add_form_event_handler used to produce
     * when it set the event as a raw String) crashes update_database with an NPE in
     * {@code SymbolicNameService.generateSymbolicName} (null event target). The
     * generator now writes {@code <event>}, but this catches forms authored / edited
     * by other tools. Handler blocks do not nest.
     */
    private static final Pattern FORM_HANDLERS_BLOCK =
        Pattern.compile("<handlers>(.*?)</handlers>", Pattern.DOTALL); //$NON-NLS-1$

    /**
     * A usual group's ext-info block - scopes the Auto check to the two properties whose platform
     * enum has no Auto literal. The same tag names carry a legitimate Auto elsewhere on a form
     * (a button's representation, a table's search-on-input), so the scope is what keeps this
     * check free of false positives.
     */
    private static final Pattern FORM_USUAL_GROUP_EXTINFO =
        Pattern.compile("<extInfo xsi:type=\"form:UsualGroupExtInfo\">(.*?)</extInfo>", //$NON-NLS-1$
            Pattern.DOTALL);

    /** A usual group's {@code <group>} or {@code <representation>} set to Auto. */
    private static final Pattern FORM_GROUP_AUTO =
        Pattern.compile("<(group|representation)>Auto</\\1>"); //$NON-NLS-1$

    /** A table's {@code <rowSelectionMode>} set to Auto. The tag exists only on a table. */
    private static final Pattern FORM_ROW_SELECTION_AUTO =
        Pattern.compile("<rowSelectionMode>Auto</rowSelectionMode>"); //$NON-NLS-1$

    /** A leaf FormField item block - scopes the check-box detection to one field. */
    private static final Pattern FORM_FIELD_BLOCK =
        Pattern.compile("<items xsi:type=\"form:FormField\">(.*?)</items>", Pattern.DOTALL); //$NON-NLS-1$

    /** Marks a FormField as a check box (the {@code <type>} element or the extInfo subtype). */
    private static final Pattern FORM_CHECKBOX_MARK =
        Pattern.compile("<type>CheckBoxField</type>|CheckBoxFieldExtInfo"); //$NON-NLS-1$

    /** A field's {@code <dataPath>} block. */
    private static final Pattern FORM_DATAPATH_BLOCK =
        Pattern.compile("<dataPath xsi:type=\"form:DataPath\">(.*?)</dataPath>", Pattern.DOTALL); //$NON-NLS-1$

    /** The {@code <segments>PATH</segments>} of a data path (a single, possibly dotted, path string). */
    private static final Pattern FORM_DATAPATH_SEGMENT =
        Pattern.compile("<segments>([^<]+)</segments>"); //$NON-NLS-1$

    /**
     * A TOP-LEVEL form attribute block ({@code \n  <attributes ...>...</attributes>} at
     * 2-space indent = a direct child of the root form). Used to resolve a check box's
     * bound form-attribute type within the same .form (no cross-file lookup).
     */
    private static final Pattern FORM_ATTRIBUTE_BLOCK =
        Pattern.compile("\\n  <attributes\\b[^>]*>(.*?)\\n  </attributes>", Pattern.DOTALL); //$NON-NLS-1$

    /** First {@code <name>NAME</name>} inside an attribute block. */
    private static final Pattern FORM_ATTR_NAME =
        Pattern.compile("<name>([^<]+)</name>"); //$NON-NLS-1$

    /** A form attribute's {@code <valueType>...</valueType>} block. */
    private static final Pattern FORM_VALUE_TYPE_BLOCK =
        Pattern.compile("<valueType>(.*?)</valueType>", Pattern.DOTALL); //$NON-NLS-1$

    /** A {@code <types>TOKEN</types>} entry. */
    private static final Pattern FORM_TYPES_TOKEN =
        Pattern.compile("<types>([^<]+)</types>"); //$NON-NLS-1$

    /**
     * Form-attribute value types a check box must NOT bind to. {@code Number} is
     * deliberately excluded: a check box on a Number is a legal 1C pattern (0 = unchecked,
     * non-zero = checked) - 6 such valid cases in the standard BSP demo. {@code Boolean} is
     * the valid type; reference / object / enum types are out of scope. Only String / Date
     * primitives are flagged - false-positive-free across the BSP demo (0 of 419
     * form-attribute check boxes flagged in a full scan).
     */
    private static final java.util.Set<String> CHECKBOX_INVALID_TYPES = new java.util.HashSet<>(
        java.util.Arrays.asList("String", "Date", "DateTime", "Time")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    // ---- .mdo scanners --------------------------------------------------

    /** xmlns:PREFIX= declarations (prefix captured) - used to detect duplicates on the root tag. */
    private static final Pattern XMLNS_PREFIX =
        Pattern.compile("xmlns:(\\w+)="); //$NON-NLS-1$

    /** Empty <fillValue NumberValue/> with no <value> - NPE in FormXmlExporter. */
    private static final Pattern MDO_EMPTY_NUMBER_FILLVALUE =
        Pattern.compile("<fillValue\\s+xsi:type=\"core:NumberValue\"\\s*/>" //$NON-NLS-1$
            + "|<fillValue\\s+xsi:type=\"core:NumberValue\">\\s*</fillValue>", //$NON-NLS-1$
            Pattern.DOTALL);

    /**
     * Empty {@code <fillValue DateValue/>} with no {@code <value>} - NPE in the EDT
     * serializer ({@code DateValue.getValue()} null) at update_database. Confirmed
     * twice in field projects (РС МДМ, рк_ТокеныАвторизации). Mirrors the NumberValue
     * check; an empty Date fill value must carry a {@code <value>} (e.g.
     * {@code 0001-01-01T00:00:00}) or be removed entirely.
     */
    private static final Pattern MDO_EMPTY_DATE_FILLVALUE =
        Pattern.compile("<fillValue\\s+xsi:type=\"core:DateValue\"\\s*/>" //$NON-NLS-1$
            + "|<fillValue\\s+xsi:type=\"core:DateValue\">\\s*</fillValue>", //$NON-NLS-1$
            Pattern.DOTALL);

    /**
     * Object-level {@code <help>} block (2-space indent = a direct child of the
     * root object, so it maps to the object's own {@code Help/} folder). A nested
     * form/attribute help block is deeper-indented and is intentionally skipped.
     * Each {@code <lang>} inside must have a sibling {@code Help/<lang>.html} file
     * or configuration {@code .cf} export crashes on the empty help page.
     */
    private static final Pattern MDO_OBJECT_HELP_BLOCK =
        Pattern.compile("\\n  <help>(.*?)\\n  </help>", Pattern.DOTALL); //$NON-NLS-1$

    /** {@code <lang>CODE</lang>} entry inside a help block. */
    private static final Pattern HELP_LANG =
        Pattern.compile("<lang>(\\w+)</lang>"); //$NON-NLS-1$

    // ---- .dcs scanners --------------------------------------------------

    /** Body of each <query>...</query> block. */
    private static final Pattern DCS_QUERY_BLOCK =
        Pattern.compile("<query>(.*?)</query>", Pattern.DOTALL); //$NON-NLS-1$

    /**
     * Heuristic for an unescaped angle-bracket operator inside a query body that
     * has already had its real entities applied: a literal {@code <>} surrounded
     * by non-bracket characters. Reliable for the documented "&lt;&gt;" case.
     */
    private static final Pattern DCS_QUERY_RAW_NEQ =
        Pattern.compile("[^<>]\\s*<>\\s*[^<>]"); //$NON-NLS-1$

    /**
     * Opening tag of a {@code DataSetFieldField} (a data-set field definition). A
     * {@code <use>} element is only a defect INSIDE such a field (the field type
     * has no {@code use} property); {@code <use>} on a {@code <parameter>}
     * (Always/Auto) or in the settings tree (True/False) is valid, so {@link #scanDcs}
     * scopes the {@code <use>} check to the balanced field block only.
     */
    private static final Pattern DCS_DATASET_FIELD_OPEN =
        Pattern.compile("<field\\b[^>]*xsi:type=\"[^\"]*\\bDataSetFieldField\"[^>]*>"); //$NON-NLS-1$

    /** A {@code <use>} Element with text - flagged only inside a DataSetFieldField. */
    private static final Pattern DCS_USE_ELEMENT =
        Pattern.compile("<use>[^<]*</use>"); //$NON-NLS-1$

    /** A <dcscom:resource> element - the <role> block does not belong on a plain field. */
    private static final Pattern DCS_ROLE_RESOURCE =
        Pattern.compile("<dcscom:resource>"); //$NON-NLS-1$

    /** Undeclared 'cfg:' prefix on a DCS type token. */
    private static final Pattern DCS_CFG_PREFIX_TYPE =
        Pattern.compile(">cfg:"); //$NON-NLS-1$

    /**
     * The abstract settings base type instantiated directly:
     * {@code xsi:type="...:SettingItem"}. Valid DCS serialization always uses a
     * CONCRETE subtype ({@code SettingsParameterValue}, {@code ParameterValue},
     * ...); the abstract {@code SettingItem} base appears only in malformed,
     * workflow-generated settings/appearance and crashes update_database (the
     * runbook Error B: an {@code <appearance>} item carrying {@code <use>} fails
     * with {@code use Тип: anyType}). Verified zero occurrences across a full
     * standard BSP demo configuration (whereas the valid {@code SettingsParameterValue}
     * appears 800+ times) - this is the precise, false-positive-free signature.
     * Deliberately NOT flagging {@code <settingsVariant>} or {@code StructureItemGroup}:
     * both are ubiquitous and valid in standard configurations.
     */
    private static final Pattern DCS_ABSTRACT_SETTING_ITEM =
        Pattern.compile("xsi:type=\"[^\"]*:SettingItem\""); //$NON-NLS-1$

    // ---- .mxlx scanners -------------------------------------------------

    /** Font style as an Element instead of a <font> attribute. */
    private static final Pattern MXLX_BARE_FONT_STYLE =
        Pattern.compile("<(bold|italic|underline|strikeout)>[^<]*</\\1>"); //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `diagnostics` `operation=validate_for_export`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Pre-export scanner: finds XDTO/XML defects in .mdo/.form/.dcs/.mxlx that pass " //$NON-NLS-1$
            + "EDT validation but crash update_database (platform 8.x). Returns file, FQN, " //$NON-NLS-1$
            + "check id, severity, line, message+fix. Run before update_database."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("checkFilter", //$NON-NLS-1$
                "Optional substring filter on the check id (e.g. 'dcs', 'form-empty').") //$NON-NLS-1$
            .stringProperty("pathFilter", //$NON-NLS-1$
                "Optional substring filter on the project-relative file path.") //$NON-NLS-1$
            .integerProperty("limit", "Maximum number of findings (default 500).") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    /** Outcome of a pre-export scan, reused by validate_for_export and the .cf export guard. */
    public static final class ExportScan
    {
        /** Total findings before the limit was applied ({@code -1} when the scan itself failed). */
        public final int findingsCount;
        /** Number of source files scanned. */
        public final int scanned;
        /** Sorted findings, truncated to {@code limit}. */
        public final List<Map<String, Object>> findings;
        /** Whether {@link #findings} was truncated. */
        public final boolean limited;
        /** The limit applied. */
        public final int limit;
        /** Non-null when the scan threw - the scan is then indeterminate, not "clean". */
        public final String error;

        ExportScan(int findingsCount, int scanned, List<Map<String, Object>> findings, boolean limited,
            int limit, String error)
        {
            this.findingsCount = findingsCount;
            this.scanned = scanned;
            this.findings = findings;
            this.limited = limited;
            this.limit = limit;
            this.error = error;
        }
    }

    /**
     * Runs the pre-export scan over the project's EDT source files and returns the findings. Reused by
     * {@code validate_for_export} and by {@code export_configuration_to_cf}, which blocks the dump on a
     * non-empty result. Read-only. {@code checkFilter} / {@code pathFilter} are optional substring
     * filters (lower-cased internally); {@code limit <= 0} falls back to {@link #DEFAULT_LIMIT}.
     *
     * @param project the EDT project whose {@code src/} tree is scanned
     * @return an {@link ExportScan}; {@link ExportScan#error} is non-null if the scan itself failed
     */
    public ExportScan scanForExport(IProject project, String checkFilter, String pathFilter, int limit)
    {
        try
        {
            if (limit <= 0)
            {
                limit = DEFAULT_LIMIT;
            }
            final String checkFilterLower =
                checkFilter != null && !checkFilter.isEmpty() ? checkFilter.toLowerCase() : null;
            final String pathFilterLower =
                pathFilter != null && !pathFilter.isEmpty() ? pathFilter.toLowerCase() : null;

            List<Map<String, Object>> findings = new ArrayList<>();
            int[] scanned = { 0 };

            IFolder src = project.getFolder("src"); //$NON-NLS-1$
            if (src != null && src.exists())
            {
                scanContainer(src, checkFilterLower, pathFilterLower, findings, scanned);
            }
            else
            {
                // Fallback: some projects keep sources at the project root.
                scanContainer(project, checkFilterLower, pathFilterLower, findings, scanned);
            }

            // Deterministic order: by file path, then by line.
            findings.sort(Comparator
                .comparing((Map<String, Object> f) -> String.valueOf(f.get("file"))) //$NON-NLS-1$
                .thenComparingInt(f -> toInt(f.get("line")))); //$NON-NLS-1$

            int total = findings.size();
            boolean wasLimited = total > limit;
            if (wasLimited)
            {
                findings = new ArrayList<>(findings.subList(0, limit));
            }
            return new ExportScan(total, scanned[0], findings, wasLimited, limit, null);
        }
        catch (Exception e)
        {
            Activator.logError("validate_for_export scan error", e); //$NON-NLS-1$
            return new ExportScan(-1, 0, new ArrayList<>(), false, limit, safeScanMessage(e));
        }
    }

    private static String safeScanMessage(Throwable t)
    {
        String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        String checkFilter = JsonUtils.extractStringArgument(params, "checkFilter"); //$NON-NLS-1$
        String pathFilter = JsonUtils.extractStringArgument(params, "pathFilter"); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, "limit", DEFAULT_LIMIT); //$NON-NLS-1$

        ExportScan scan = scanForExport(project, checkFilter, pathFilter, limit);
        if (scan.error != null)
        {
            return ToolResult.error("validate_for_export failed: " + scan.error).toJson(); //$NON-NLS-1$
        }

        ToolResult result = ToolResult.success()
            .put("project", project.getName()) //$NON-NLS-1$
            .put("scanned", scan.scanned) //$NON-NLS-1$
            .put("findingsCount", scan.findingsCount) //$NON-NLS-1$
            .put("findings", scan.findings); //$NON-NLS-1$
        if (scan.limited)
        {
            result.put("limited", true) //$NON-NLS-1$
                .put("limit", scan.limit); //$NON-NLS-1$
        }
        if (scan.findingsCount == 0)
        {
            result.put("message", //$NON-NLS-1$
                "ok - no pre-export XDTO/XML defects found in " + scan.scanned //$NON-NLS-1$
                    + " scanned file(s)."); //$NON-NLS-1$
        }
        return result.toJson();
    }

    /**
     * Recursively walks {@code container}, scanning every applicable file. Never
     * throws out of the walk: a read failure on a single file is turned into a
     * {@code scan-error} finding so the rest of the project still gets scanned.
     */
    private void scanContainer(IContainer container, String checkFilterLower, String pathFilterLower,
        List<Map<String, Object>> findings, int[] scanned)
    {
        IResource[] members;
        try
        {
            members = container.members();
        }
        catch (Exception e)
        {
            return;
        }
        for (IResource member : members)
        {
            if (member instanceof IContainer)
            {
                scanContainer((IContainer) member, checkFilterLower, pathFilterLower,
                    findings, scanned);
            }
            else if (member instanceof IFile)
            {
                IFile file = (IFile) member;
                FileKind kind = classify(file.getName());
                if (kind == null)
                {
                    continue;
                }
                String relPath = file.getProjectRelativePath().toString();
                if (pathFilterLower != null && !relPath.toLowerCase().contains(pathFilterLower))
                {
                    continue;
                }
                scanned[0]++;
                try
                {
                    String content = readUtf8(file);
                    scanFile(kind, content, relPath, deriveFqn(relPath),
                        checkFilterLower, findings, file);
                }
                catch (Exception e)
                {
                    add(findings, checkFilterLower, relPath, deriveFqn(relPath),
                        "scan-error", "WARNING", -1, //$NON-NLS-1$ //$NON-NLS-2$
                        "Could not read file for scanning: " + e.getMessage()); //$NON-NLS-1$
                }
            }
        }
    }

    /**
     * Dispatches the applicable scanners for one file by its kind.
     */
    private void scanFile(FileKind kind, String content, String relPath, String fqn,
        String checkFilterLower, List<Map<String, Object>> findings, IFile file)
    {
        switch (kind)
        {
            case FORM:
                scanForm(content, relPath, fqn, checkFilterLower, findings);
                break;
            case MDO:
                scanMdo(content, relPath, fqn, checkFilterLower, findings, file);
                break;
            case DCS:
                scanDcs(content, relPath, fqn, checkFilterLower, findings);
                break;
            case MXLX:
                scanMxlx(content, relPath, fqn, checkFilterLower, findings);
                break;
            default:
                break;
        }
    }

    // ---- .form ----------------------------------------------------------

    private void scanForm(String content, String relPath, String fqn, String checkFilterLower,
        List<Map<String, Object>> findings)
    {
        Matcher m = FORM_ABSTRACT_COMMAND_HANDLER.matcher(content);
        while (m.find())
        {
            add(findings, checkFilterLower, relPath, fqn,
                "form-command-handler-type", "ERROR", lineOf(content, m.start()), //$NON-NLS-1$ //$NON-NLS-2$
                "form command action uses abstract CommandHandlerContainer; must be " //$NON-NLS-1$
                + "form:FormCommandHandlerContainer with a <handler> (platform NPE in " //$NON-NLS-1$
                + "CommandHandler.getName)."); //$NON-NLS-1$
        }

        m = FORM_EMPTY_ACTION.matcher(content);
        while (m.find())
        {
            add(findings, checkFilterLower, relPath, fqn,
                "form-empty-action", "ERROR", lineOf(content, m.start()), //$NON-NLS-1$ //$NON-NLS-2$
                "empty <action> without <handler> - platform NPE on export; add the " //$NON-NLS-1$
                + "<handler> element naming the command handler procedure."); //$NON-NLS-1$
        }

        // A form-attribute <columns> block without an <id> - the platform renders
        // the first column's header in every column. Every real column carries one.
        Matcher cm = FORM_ATTR_COLUMN_BLOCK.matcher(content);
        while (cm.find())
        {
            if (!cm.group(1).contains("<id>")) //$NON-NLS-1$
            {
                add(findings, checkFilterLower, relPath, fqn,
                    "form-attribute-column-no-id", "ERROR", lineOf(content, cm.start()), //$NON-NLS-1$ //$NON-NLS-2$
                    "form attribute <columns> block without an <id> - the platform shows " //$NON-NLS-1$
                    + "the first column's header in every column; give each column a " //$NON-NLS-1$
                    + "distinct <id> (1-based within the attribute)."); //$NON-NLS-1$
            }
        }

        // Event-handler binding missing its <event> - update_database NPE in
        // SymbolicNameService.generateSymbolicName (null event target). A valid
        // <handlers> block always carries <event> + <name>.
        Matcher hm = FORM_HANDLERS_BLOCK.matcher(content);
        while (hm.find())
        {
            if (!hm.group(1).contains("<event>")) //$NON-NLS-1$
            {
                add(findings, checkFilterLower, relPath, fqn,
                    "form-handler-no-event", "ERROR", lineOf(content, hm.start()), //$NON-NLS-1$ //$NON-NLS-2$
                    "form <handlers> block without an <event> - update_database NPE in " //$NON-NLS-1$
                    + "SymbolicNameService.generateSymbolicName (null event target); add " //$NON-NLS-1$
                    + "<event>EventName</event> (e.g. OnChange) naming the handled event."); //$NON-NLS-1$
            }
        }

        // An enum value the EDT model accepts and the infobase does not. EDT declares an AUTO
        // literal for a usual group's grouping and representation and for a table's row
        // selection; the XDTO schema of the infobase declares none of the three. So the form
        // validates clean, sits there, and the import fails with an XDTO property mismatch that
        // names the property but not the element carrying it. Static detection is the only cheap
        // warning: nothing else in the toolchain objects.
        Matcher gx = FORM_USUAL_GROUP_EXTINFO.matcher(content);
        while (gx.find())
        {
            Matcher ga = FORM_GROUP_AUTO.matcher(gx.group(1));
            while (ga.find())
            {
                add(findings, checkFilterLower, relPath, fqn,
                    "form-auto-value-rejected-by-infobase", "ERROR", //$NON-NLS-1$ //$NON-NLS-2$
                    lineOf(content, gx.start(1) + ga.start()),
                    "usual group <" + ga.group(1) + "> is Auto, which the infobase XDTO schema " //$NON-NLS-1$ //$NON-NLS-2$
                    + "does not accept - the import fails on this property. Use Vertical / " //$NON-NLS-1$
                    + "HorizontalIfPossible / AlwaysHorizontal for <group>, and for " //$NON-NLS-1$
                    + "<representation> either None / WeakSeparation / NormalSeparation / " //$NON-NLS-1$
                    + "StrongSeparation or no element at all."); //$NON-NLS-1$
            }
        }

        Matcher rs = FORM_ROW_SELECTION_AUTO.matcher(content);
        while (rs.find())
        {
            add(findings, checkFilterLower, relPath, fqn,
                "form-auto-value-rejected-by-infobase", "ERROR", lineOf(content, rs.start()), //$NON-NLS-1$ //$NON-NLS-2$
                "table <rowSelectionMode> is Auto, which the infobase XDTO schema does not " //$NON-NLS-1$
                + "accept - the import fails on this property. Use Row or Cell."); //$NON-NLS-1$
        }

        // CheckBoxField bound to a String/Date form attribute. A check box needs a Boolean
        // (a Number 0/1 is also valid, so Number is NOT flagged). Resolved within this .form:
        // map each top-level form attribute to its single value type, then flag a check box
        // whose 1-part (form-attribute) data-path names a String/Date attribute. Dotted paths
        // (Object.x, dynamic-list, record-set) and composite / reference / Number / Boolean
        // types are skipped - false-positive-free across the BSP demo.
        Map<String, String> formAttrType = new LinkedHashMap<>();
        Matcher fa = FORM_ATTRIBUTE_BLOCK.matcher(content);
        while (fa.find())
        {
            String ab = fa.group(1);
            Matcher nm = FORM_ATTR_NAME.matcher(ab);
            Matcher vt = FORM_VALUE_TYPE_BLOCK.matcher(ab);
            if (!nm.find() || !vt.find())
            {
                continue;
            }
            List<String> toks = new ArrayList<>();
            Matcher tt = FORM_TYPES_TOKEN.matcher(vt.group(1));
            while (tt.find())
            {
                toks.add(tt.group(1).trim());
            }
            if (toks.size() == 1)
            {
                formAttrType.put(nm.group(1), toks.get(0));
            }
        }
        if (!formAttrType.isEmpty())
        {
            Matcher fld = FORM_FIELD_BLOCK.matcher(content);
            while (fld.find())
            {
                String block = fld.group(1);
                if (!FORM_CHECKBOX_MARK.matcher(block).find())
                {
                    continue;
                }
                Matcher dpb = FORM_DATAPATH_BLOCK.matcher(block);
                if (!dpb.find())
                {
                    continue;
                }
                Matcher seg = FORM_DATAPATH_SEGMENT.matcher(dpb.group(1));
                if (!seg.find())
                {
                    continue;
                }
                String path = seg.group(1).trim();
                if (path.isEmpty() || path.indexOf('.') >= 0)
                {
                    continue;   // only a 1-part (form-attribute) path is resolvable single-file
                }
                String type = formAttrType.get(path);
                if (type != null && CHECKBOX_INVALID_TYPES.contains(type))
                {
                    add(findings, checkFilterLower, relPath, fqn,
                        "form-checkbox-nonboolean-attribute", "ERROR", lineOf(content, fld.start()), //$NON-NLS-1$ //$NON-NLS-2$
                        "CheckBoxField is bound to the form attribute '" + path + "' of type " + type //$NON-NLS-1$ //$NON-NLS-2$
                        + " - a check box requires a Boolean (a Number is also valid as 0/1). Use a " //$NON-NLS-1$
                        + "Boolean attribute or a different field kind."); //$NON-NLS-1$
                }
            }
        }
    }

    // ---- .mdo -----------------------------------------------------------

    /**
     * Whether a root configuration is missing the internal information the platform demands.
     * <p>
     * Only the ROOT configuration carries contained objects, so an ordinary object's .mdo is not a
     * candidate and must not be flagged. An extension's root is a Configuration too and IS a
     * candidate: all 54 extension roots in the census carry them, same as a configuration.
     * </p>
     * <p>
     * The test is "declares none", never "declares seven": two configurations in the census carry
     * six, both on 8.2.16 compatibility, because the seventh class id appeared in a later platform.
     * Counting would fail those two for having nothing wrong with them.
     * </p>
     *
     * @param rootTag the file's first start tag, possibly <code>null</code>.
     * @param content the whole file.
     * @return <code>true</code> when this is a root configuration declaring none
     */
    static boolean configurationLacksInternalInfo(String rootTag, String content)
    {
        return rootTag != null && rootTag.contains("mdclass:Configuration") //$NON-NLS-1$
            && content != null && !content.contains("<containedObjects"); //$NON-NLS-1$
    }

    private void scanMdo(String content, String relPath, String fqn, String checkFilterLower,
        List<Map<String, Object>> findings, IFile file)
    {
        // Duplicate xmlns:PREFIX on the ROOT element only.
        String rootTag = firstStartTag(content);
        if (rootTag != null)
        {
            Matcher xm = XMLNS_PREFIX.matcher(rootTag);
            java.util.Set<String> seen = new java.util.HashSet<>();
            java.util.Set<String> dup = new java.util.LinkedHashSet<>();
            while (xm.find())
            {
                String prefix = xm.group(1);
                if (!seen.add(prefix))
                {
                    dup.add(prefix);
                }
            }
            for (String prefix : dup)
            {
                add(findings, checkFilterLower, relPath, fqn,
                    "mdo-duplicate-xmlns", "ERROR", 1, //$NON-NLS-1$ //$NON-NLS-2$
                    "duplicate xmlns:" + prefix + " on root tag - EDT drops the object from " //$NON-NLS-1$ //$NON-NLS-2$
                    + "the BM index (md-reference-integrity); remove the redundant declaration."); //$NON-NLS-1$
            }
        }

        // A root configuration with no contained objects. The platform calls these its internal
        // information and refuses to load a configuration without them, naming a
        // "/Configuration.xml" an EDT project does not have - so the message points at the wrong
        // file and the search starts in the wrong place. Nothing else flags it: EDT validates such
        // a project clean, and it is only the infobase update that fails. Census on one machine:
        // 52 real configurations carry exactly seven, and every configuration carrying none was
        // machine-made.
        if (configurationLacksInternalInfo(rootTag, content))
        {
            add(findings, checkFilterLower, relPath, fqn,
                "mdo-configuration-no-contained-objects", "ERROR", 1, //$NON-NLS-1$ //$NON-NLS-2$
                "the root configuration declares no <containedObjects> - the platform refuses it " //$NON-NLS-1$
                + "on update_database with \"Отсутствует внутренняя информация (узел InternalInfo)\" " //$NON-NLS-1$
                + "and names a /Configuration.xml this project does not have. A configuration " //$NON-NLS-1$
                + "carries seven, one per platform class id, each with an objectId of its own."); //$NON-NLS-1$
        }

        Matcher m = MDO_EMPTY_NUMBER_FILLVALUE.matcher(content);
        while (m.find())
        {
            add(findings, checkFilterLower, relPath, fqn,
                "mdo-empty-numbervalue-fillvalue", "ERROR", lineOf(content, m.start()), //$NON-NLS-1$ //$NON-NLS-2$
                "empty <fillValue NumberValue> without <value> - NPE in FormXmlExporter; " //$NON-NLS-1$
                + "remove the fillValue or give it a <value>."); //$NON-NLS-1$
        }

        m = MDO_EMPTY_DATE_FILLVALUE.matcher(content);
        while (m.find())
        {
            add(findings, checkFilterLower, relPath, fqn,
                "mdo-empty-datevalue-fillvalue", "ERROR", lineOf(content, m.start()), //$NON-NLS-1$ //$NON-NLS-2$
                "empty <fillValue DateValue> without <value> - NPE in the EDT serializer " //$NON-NLS-1$
                + "(DateValue.getValue() null) on update_database; remove the fillValue or give " //$NON-NLS-1$
                + "it a <value> (e.g. 0001-01-01T00:00:00)."); //$NON-NLS-1$
        }

        // Object-level <help> declares one help page per language; each <lang> must
        // have a sibling Help/<lang>.html file or EDT's configuration .cf export
        // crashes on the empty page (the EDT editor does not flag this - a known
        // BSP/ZUP migration artifact). Only the object-level block (2-space indent,
        // a direct child of the root object) is checked; nested form/attribute help
        // maps to other folders. 0 false-positives across BSP demo (317 langs) and
        // ZUP demo (740 langs).
        Matcher hh = MDO_OBJECT_HELP_BLOCK.matcher(content);
        if (hh.find() && file != null && file.getLocation() != null)
        {
            org.eclipse.core.runtime.IPath base = file.getLocation().removeLastSegments(1);
            Matcher lm = HELP_LANG.matcher(hh.group(1));
            while (lm.find())
            {
                String lang = lm.group(1);
                if (!base.append("Help").append(lang + ".html").toFile().exists()) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    add(findings, checkFilterLower, relPath, fqn,
                        // +1: the pattern anchors on the '\n' before "  <help>", so
                        // start() is that newline; +1 moves into the help line itself
                        // (correct for both LF and CRLF since the anchor is the '\n').
                        "mdo-help-page-missing-html", "ERROR", lineOf(content, hh.start() + 1), //$NON-NLS-1$ //$NON-NLS-2$
                        "object declares help page <lang>" + lang + "</lang> but its Help/" //$NON-NLS-1$ //$NON-NLS-2$
                        + lang + ".html file is missing - the EDT editor does not flag this, " //$NON-NLS-1$
                        + "yet configuration .cf export fails on the empty <help> page; add the " //$NON-NLS-1$
                        + "Help/" + lang + ".html file or remove <lang>" + lang //$NON-NLS-1$ //$NON-NLS-2$
                        + "</lang> from the object's <help> block."); //$NON-NLS-1$
                }
            }
        }
    }

    // ---- .dcs -----------------------------------------------------------

    private void scanDcs(String content, String relPath, String fqn, String checkFilterLower,
        List<Map<String, Object>> findings)
    {
        // Unescaped operators inside each <query> body.
        Matcher qm = DCS_QUERY_BLOCK.matcher(content);
        while (qm.find())
        {
            String body = qm.group(1);
            if (body.contains("CDATA")) //$NON-NLS-1$
            {
                continue;
            }
            if (DCS_QUERY_RAW_NEQ.matcher(body).find())
            {
                add(findings, checkFilterLower, relPath, fqn,
                    "dcs-query-unescaped", "ERROR", lineOf(content, qm.start()), //$NON-NLS-1$ //$NON-NLS-2$
                    "unescaped <>/</& in DCS <query> - XML parse error; escape as " //$NON-NLS-1$
                    + "&lt;&gt;&amp; or wrap the query text in CDATA."); //$NON-NLS-1$
            }
        }

        // <use> is only a defect INSIDE a DataSetFieldField (that field type has no
        // 'use' property -> XDTO mismatch). <use>Always</use> on a <parameter> and
        // <use>True</use> in the settings tree are valid and ubiquitous, so scope
        // strictly to the balanced field block - never the whole file or dataSet region.
        Matcher fm = DCS_DATASET_FIELD_OPEN.matcher(content);
        while (fm.find())
        {
            if (fm.group().endsWith("/>")) // self-closing field has no body //$NON-NLS-1$
            {
                continue;
            }
            int blockStart = fm.end();
            int blockEnd = balancedFieldEnd(content, blockStart);
            Matcher um = DCS_USE_ELEMENT.matcher(content.substring(blockStart, blockEnd));
            while (um.find())
            {
                add(findings, checkFilterLower, relPath, fqn,
                    "dcs-use-element", "ERROR", lineOf(content, blockStart + um.start()), //$NON-NLS-1$ //$NON-NLS-2$
                    "<use> Element inside a DCS DataSetFieldField - the field type has " //$NON-NLS-1$
                    + "no 'use' property (update_database XDTO mismatch); remove it."); //$NON-NLS-1$
            }
        }

        Matcher m = DCS_ROLE_RESOURCE.matcher(content);
        while (m.find())
        {
            add(findings, checkFilterLower, relPath, fqn,
                "dcs-role-resource", "ERROR", lineOf(content, m.start()), //$NON-NLS-1$ //$NON-NLS-2$
                "<role><dcscom:resource> invalid on a plain DCS field - remove the " //$NON-NLS-1$
                + "<role> block."); //$NON-NLS-1$
        }

        m = DCS_CFG_PREFIX_TYPE.matcher(content);
        while (m.find())
        {
            add(findings, checkFilterLower, relPath, fqn,
                "dcs-cfg-prefix-type", "ERROR", lineOf(content, m.start()), //$NON-NLS-1$ //$NON-NLS-2$
                "undeclared prefix 'cfg:' in a DCS type - use the bare FQN (e.g. " //$NON-NLS-1$
                + "CatalogRef.X), no prefix."); //$NON-NLS-1$
        }

        // Abstract settings base type instantiated (xsi:type="...:SettingItem").
        // EDT validates it clean, but the platform rejects the XDTO at
        // update_database (mcp-edt DCS runbook, Error B). This is the precise,
        // false-positive-free signature: 0 occurrences across a full standard BSP
        // demo, whereas the concrete SettingsParameterValue appears 800+ times. We
        // deliberately do NOT flag <settingsVariant> or StructureItemGroup - both
        // are ubiquitous and valid in standard configs (158 / many in the demo).
        m = DCS_ABSTRACT_SETTING_ITEM.matcher(content);
        while (m.find())
        {
            add(findings, checkFilterLower, relPath, fqn,
                "dcs-abstract-setting-item", "ERROR", lineOf(content, m.start()), //$NON-NLS-1$ //$NON-NLS-2$
                "abstract settings type instantiated (xsi:type=\"...:SettingItem\") - valid DCS " //$NON-NLS-1$
                + "uses a concrete subtype (SettingsParameterValue / ParameterValue / ...). The " //$NON-NLS-1$
                + "abstract base appears only in malformed, workflow-generated settings/appearance " //$NON-NLS-1$
                + "and crashes update_database (e.g. 'use Тип: anyType' in an <appearance> item). " //$NON-NLS-1$
                + "Rewrite the item with its concrete type (dcscor:parameter + dcscor:value, no " //$NON-NLS-1$
                + "<use>), or remove the offending block."); //$NON-NLS-1$
        }
    }

    /**
     * End offset (just past the matching {@code </field>}) of a DataSetFieldField
     * block whose body starts at {@code from}. Balances nested {@code <field ...>}
     * opens - the field-name child element - against {@code </field>} closes.
     * Returns {@code content.length()} if the block is unbalanced.
     */
    private static int balancedFieldEnd(String content, int from)
    {
        int depth = 1;
        int i = from;
        int n = content.length();
        while (i < n && depth > 0)
        {
            int lt = content.indexOf('<', i);
            if (lt < 0)
            {
                return n;
            }
            if (content.startsWith("</field>", lt)) //$NON-NLS-1$
            {
                depth--;
                i = lt + "</field>".length(); //$NON-NLS-1$
            }
            else if (content.startsWith("<field", lt) && lt + 6 < n //$NON-NLS-1$
                && (content.charAt(lt + 6) == ' ' || content.charAt(lt + 6) == '>'))
            {
                int gt = content.indexOf('>', lt);
                if (gt < 0)
                {
                    return n;
                }
                if (content.charAt(gt - 1) != '/') // a self-closing <field .../> needs no close
                {
                    depth++;
                }
                i = gt + 1; // advance past the whole open tag
            }
            else
            {
                i = lt + 1;
            }
        }
        return i;
    }

    // ---- .mxlx ----------------------------------------------------------

    private void scanMxlx(String content, String relPath, String fqn, String checkFilterLower,
        List<Map<String, Object>> findings)
    {
        Matcher m = MXLX_BARE_FONT_STYLE.matcher(content);
        while (m.find())
        {
            add(findings, checkFilterLower, relPath, fqn,
                "mxlx-bare-fontstyle", "ERROR", lineOf(content, m.start()), //$NON-NLS-1$ //$NON-NLS-2$
                "<bold>/<italic>/<underline>/<strikeout> as Element - must be an " //$NON-NLS-1$
                + "attribute of a <font> variant; define the font and reference it by index."); //$NON-NLS-1$
        }
    }

    // ---- helpers --------------------------------------------------------

    /**
     * Adds a finding, honouring the optional check-id substring filter.
     */
    private void add(List<Map<String, Object>> findings, String checkFilterLower, String file,
        String fqn, String check, String severity, int line, String message)
    {
        if (checkFilterLower != null && !check.toLowerCase().contains(checkFilterLower))
        {
            return;
        }
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("file", file); //$NON-NLS-1$
        finding.put("fqn", fqn != null ? fqn : ""); //$NON-NLS-1$ //$NON-NLS-2$
        finding.put("check", check); //$NON-NLS-1$
        finding.put("severity", severity); //$NON-NLS-1$
        finding.put("line", line); //$NON-NLS-1$
        finding.put("message", message); //$NON-NLS-1$
        findings.add(finding);
    }

    /**
     * 1-based line of a character offset: count of {@code '\n'} before the offset
     * plus one. Returns -1 when the offset is out of range.
     */
    private static int lineOf(String content, int offset)
    {
        if (offset < 0 || offset > content.length())
        {
            return -1;
        }
        int line = 1;
        for (int i = 0; i < offset; i++)
        {
            if (content.charAt(i) == '\n')
            {
                line++;
            }
        }
        return line;
    }

    /**
     * Extracts the first start tag (from the first {@code '<'} that opens an
     * element to its closing {@code '>'}), skipping XML prolog / comments /
     * processing instructions. Returns {@code null} when none is found.
     */
    private static String firstStartTag(String content)
    {
        int i = 0;
        int n = content.length();
        while (i < n)
        {
            int lt = content.indexOf('<', i);
            if (lt < 0)
            {
                return null;
            }
            // Skip <?xml ... ?>, <!-- ... -->, <!DOCTYPE ...>
            if (content.startsWith("<!--", lt)) //$NON-NLS-1$
            {
                int close = content.indexOf("-->", lt); // a '>' may appear inside a comment //$NON-NLS-1$
                if (close < 0)
                {
                    return null;
                }
                i = close + "-->".length(); //$NON-NLS-1$
                continue;
            }
            if (lt + 1 < n)
            {
                char next = content.charAt(lt + 1);
                if (next == '?' || next == '!')
                {
                    int close = content.indexOf('>', lt);
                    if (close < 0)
                    {
                        return null;
                    }
                    i = close + 1;
                    continue;
                }
            }
            int gt = content.indexOf('>', lt);
            if (gt < 0)
            {
                return null;
            }
            return content.substring(lt, gt + 1);
        }
        return null;
    }

    private static int toInt(Object value)
    {
        if (value instanceof Number)
        {
            return ((Number) value).intValue();
        }
        return -1;
    }

    /**
     * Reads an {@link IFile} fully as UTF-8.
     */
    private static String readUtf8(IFile file) throws Exception
    {
        try (InputStream in = file.getContents())
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1)
            {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Classifies a file by name into one of the scanned kinds, or {@code null}.
     */
    private static FileKind classify(String name)
    {
        if (name == null)
        {
            return null;
        }
        String lower = name.toLowerCase();
        if (lower.endsWith(".form")) //$NON-NLS-1$
        {
            return FileKind.FORM;
        }
        if (lower.endsWith(".mdo")) //$NON-NLS-1$
        {
            return FileKind.MDO;
        }
        if (lower.endsWith(".dcs")) //$NON-NLS-1$
        {
            return FileKind.DCS;
        }
        if (lower.endsWith(".mxlx")) //$NON-NLS-1$
        {
            return FileKind.MXLX;
        }
        return null;
    }

    /**
     * Best-effort FQN derivation from a project-relative source path.
     *
     * <p>Maps the first {@code src/}-relative folder to a singular metadata type
     * and takes the object name from the next segment, e.g.
     * {@code src/Catalogs/Products/Products.mdo -> Catalog.Products}. For a
     * {@code Form.form} under {@code .../Forms/Y} it appends {@code .Form.Y}; for
     * a {@code Template.dcs} under {@code .../Templates/Y} it appends
     * {@code .Template.Y}. Returns {@code ""} when the path is not recognisable.
     */
    static String deriveFqn(String relPath)
    {
        if (relPath == null || relPath.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        String normalized = relPath.replace('\\', '/');
        // Drop a leading "src/" if present.
        if (normalized.startsWith("src/")) //$NON-NLS-1$
        {
            normalized = normalized.substring(4);
        }
        else
        {
            int srcIdx = normalized.indexOf("/src/"); //$NON-NLS-1$
            if (srcIdx >= 0)
            {
                normalized = normalized.substring(srcIdx + 5);
            }
        }
        String[] parts = normalized.split("/"); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return ""; //$NON-NLS-1$
        }
        String singular = singularType(parts[0]);
        if (singular == null)
        {
            return ""; //$NON-NLS-1$
        }
        String ownerFqn = singular + "." + parts[1]; //$NON-NLS-1$

        // Detect child Form / Template under the object.
        for (int i = 2; i + 1 < parts.length; i++)
        {
            if ("Forms".equals(parts[i])) //$NON-NLS-1$
            {
                return ownerFqn + ".Form." + parts[i + 1]; //$NON-NLS-1$
            }
            if ("Templates".equals(parts[i])) //$NON-NLS-1$
            {
                return ownerFqn + ".Template." + parts[i + 1]; //$NON-NLS-1$
            }
        }
        return ownerFqn;
    }

    /**
     * Maps a metadata collection folder name to its singular type. Falls back to
     * a trailing-"s" strip for unmapped collections; returns {@code null} when
     * the result would be empty.
     */
    private static String singularType(String folder)
    {
        if (folder == null || folder.isEmpty())
        {
            return null;
        }
        switch (folder)
        {
            case "Catalogs": //$NON-NLS-1$
                return "Catalog"; //$NON-NLS-1$
            case "Documents": //$NON-NLS-1$
                return "Document"; //$NON-NLS-1$
            case "Reports": //$NON-NLS-1$
                return "Report"; //$NON-NLS-1$
            case "DataProcessors": //$NON-NLS-1$
                return "DataProcessor"; //$NON-NLS-1$
            case "CommonForms": //$NON-NLS-1$
                return "CommonForm"; //$NON-NLS-1$
            case "ChartsOfCharacteristicTypes": //$NON-NLS-1$
                return "ChartOfCharacteristicTypes"; //$NON-NLS-1$
            case "InformationRegisters": //$NON-NLS-1$
                return "InformationRegister"; //$NON-NLS-1$
            case "AccumulationRegisters": //$NON-NLS-1$
                return "AccumulationRegister"; //$NON-NLS-1$
            case "Enums": //$NON-NLS-1$
                return "Enum"; //$NON-NLS-1$
            case "Constants": //$NON-NLS-1$
                return "Constant"; //$NON-NLS-1$
            default:
                if (folder.endsWith("s") && folder.length() > 1) //$NON-NLS-1$
                {
                    return folder.substring(0, folder.length() - 1);
                }
                return null;
        }
    }

    /**
     * Scanned source-file kinds.
     */
    private enum FileKind
    {
        FORM, MDO, DCS, MXLX
    }
}
