/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.GsonHolder;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.support.UnreadArguments;
import ru.aiedt.mcp.server.support.MetadataMutationLock;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmCommonFormPostCreate;
import ru.aiedt.mcp.server.support.BmCommonModuleGuards;
import ru.aiedt.mcp.server.support.BmDcsHelper;
import ru.aiedt.mcp.server.support.BmDefinedTypeHelper;
import ru.aiedt.mcp.server.support.BmEventSubscriptionHelper;
import ru.aiedt.mcp.server.support.BmExtensionHelper;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.BmFormGeneratorHelper;
import ru.aiedt.mcp.server.support.BmFormHelper;
import ru.aiedt.mcp.server.support.BmFormResourceHelper;
import ru.aiedt.mcp.server.support.BmHelpHelper;
import ru.aiedt.mcp.server.support.BmCommandInterfaceHelper;
import ru.aiedt.mcp.server.support.BmExportHelper;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.BmRightsHelper;
import ru.aiedt.mcp.server.support.BmSubsystemHelper;
import ru.aiedt.mcp.server.support.BmRouteMapHelper;
import ru.aiedt.mcp.server.support.BmTemplateHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.EventStubGenerator;
import ru.aiedt.mcp.server.support.FormBaseSetup;
import ru.aiedt.mcp.server.support.FormEventRegistry;
import ru.aiedt.mcp.server.support.MetadataGuards;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.PictureValidator;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.ToolGate;

/**
 * Single-entry constructor for metadata, forms, DCS, templates and extensions
 * - a unified constructor for edit_metadata operations.
 * <p>
 * Design rationale: a single tool with an {@code operation} discriminator
 * keeps the AI surface small (~59 operations behind one schema) and unifies
 * dryRun, batch and help semantics. Each operation routes to a dedicated
 * helper - {@link BmObjectHelper}, {@link BmDcsHelper}, {@link BmTemplateHelper},
 * {@link BmExtensionHelper}, plus the existing {@code BmFormHelper}.
 * <p>
 * Existing focused tools ({@code add_metadata_attribute},
 * {@code rename_metadata_object}, {@code delete_metadata_object},
 * {@code edit_form}) are kept as ergonomic shortcuts.
 * <p>
 * <b>Status (1.33):</b> dispatcher skeleton with the most-used object
 * operations wired (createObject, setObjectProperty, addObjectAttribute,
 * removeObjectAttribute, addTabularSection, removeTabularSection,
 * addTabularSectionAttribute, removeTabularSectionAttribute). DCS / Template /
 * Extension groups return precise "deferred" messages with API-availability
 * diagnostics; operations land in subsequent commits up to 1.39.
 */
public class EditMetadataTool implements IMcpTool
{
    public static final String NAME = "edit_metadata"; //$NON-NLS-1$

    /**
     * Single source of truth: operation name -> {group, help text, handler}.
     * Drives the allowlist (execute / batch existence gate + suggest), the
     * dispatch (op -> handler) and the help catalog, so those three can never
     * drift apart - the recurring "advertised but not dispatched / not
     * documented" bug class this registry was introduced to kill.
     */
    private final Map<String, OpEntry> registry = buildRegistry();

    /** BusinessProcess route-map ops, extracted as the first cluster of the Inc4 god-class split. */
    private final RouteMapOps routeMapOps = new RouteMapOps();
    private final TemplateOps templateOps = new TemplateOps();
    private final RoleOps roleOps = new RoleOps();
    private final CommandInterfaceOps commandInterfaceOps = new CommandInterfaceOps();
    private final PredefinedOps predefinedOps = new PredefinedOps();
    private final ContentOps contentOps = new ContentOps();
    private final ServiceOps serviceOps = new ServiceOps();
    private final ObjectOps objectOps = new ObjectOps();
    private final SpecializedOps specializedOps = new SpecializedOps();
    private final FormEventOps formEventOps = new FormEventOps();
    private final FormCommandInterfaceOps formCommandInterfaceOps = new FormCommandInterfaceOps();
    private final FormCreateOps formCreateOps = new FormCreateOps();
    private final FormItemsOps formItemsOps = new FormItemsOps();
    private final MiscOps miscOps = new MiscOps();

    /** Handler for one operation. */
    @FunctionalInterface
    private interface OpHandler
    {
        String apply(Map<String, String> params);
    }

    /** One registry entry: help group, short help text (may be empty), handler. */
    private static final class OpEntry
    {
        final String group;
        final String help;
        final OpHandler handler;

        OpEntry(String group, String help, OpHandler handler)
        {
            this.group = group;
            this.help = help;
            this.handler = handler;
        }
    }

    /** Help-catalog group order - the only place operation groups are named. */
    private static final String[] OP_GROUP_ORDER = {
        "Objects", "Specialized", "Command interface", "Services HTTP/SOAP", "Forms", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "Templates", "BusinessProcess route map", "Extensions", "DCS", "Common" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    };

    /**
     * Parameters a batch operation takes from the outer call when it does not carry its own.
     * <p>
     * Named in one place because the facade's description promises inheritance, and this list is
     * what the promise means. What identifies WHERE an operation acts belongs here; what says WHAT
     * it writes does not.
     * </p>
     */
    static final String[] SHARED_BATCH_PARAMS = {
        "projectName", "ownerFqn", "formFqn", "dryRun" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    };

    /** How many failed operations a batch names in its message before it says how many are left. */
    private static final int FAILURES_NAMED = 5;

    /**
     * Why each failed operation failed, for the message the batch demotes itself with.
     * <p>
     * The message used to point at batchResults[] instead of carrying this. The array is there, in
     * the structured payload - but the text channel gets a summary, and for a failure that summary
     * is the failure text alone. A caller reading text was therefore sent to an array it had not
     * been given, and the only way left was to reissue the operations one at a time, which is what
     * a batch exists to avoid.
     * </p>
     *
     * @param results one entry per operation, as put into batchResults
     * @return the lines naming the failures; never <code>null</code>
     */
    /**
     * The reason one failed batch entry carries.
     * <p>
     * An operation rejected before dispatch puts it in {@code error}; one that ran and refused puts
     * its whole answer in {@code response}, and the reason is that answer's own error member.
     * </p>
     *
     * @param entry the failed entry
     * @return the reason, or a stated absence; never <code>null</code>
     */
    static String reasonOf(Map<String, Object> entry)
    {
        Object direct = entry.get("error"); //$NON-NLS-1$
        if (direct != null && !direct.toString().isEmpty())
        {
            return direct.toString();
        }
        Object response = entry.get("response"); //$NON-NLS-1$
        if (response != null)
        {
            try
            {
                com.google.gson.JsonObject obj =
                    com.google.gson.JsonParser.parseString(response.toString()).getAsJsonObject();
                for (String member : new String[] { "error", "message" }) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    if (obj.has(member) && !obj.get(member).isJsonNull())
                    {
                        String text = obj.get(member).getAsString();
                        if (!text.isEmpty())
                        {
                            return text;
                        }
                    }
                }
            }
            catch (Exception notJson)
            {
                return response.toString();
            }
        }
        return "failed without saying why"; //$NON-NLS-1$
    }

    private static String whyEachFailed(List<Map<String, Object>> results)
    {
        StringBuilder sb = new StringBuilder();
        int named = 0;
        int unnamed = 0;
        for (Map<String, Object> entry : results)
        {
            if (!Boolean.FALSE.equals(entry.get("ok"))) //$NON-NLS-1$
            {
                continue;
            }
            if (named == FAILURES_NAMED)
            {
                unnamed++;
                continue;
            }
            sb.append("  [").append(entry.get("index")).append("] ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(entry.get("operation")).append(": ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(reasonOf(entry)).append('\n');
            named++;
        }
        if (unnamed > 0)
        {
            sb.append("  ...and ").append(unnamed) //$NON-NLS-1$
                .append(" more, each in batchResults[].\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Single constructor for metadata, forms, DCS, templates, extensions, reports. " //$NON-NLS-1$
            + "Pass operation=<name> with operation-specific parameters. " //$NON-NLS-1$
            + "Call operation=help for the full catalog (" + operationSummary() + "). " //$NON-NLS-1$ //$NON-NLS-2$
            + "Add dryRun=true to any operation to preview changes without applying them. " //$NON-NLS-1$
            + "Supports: idempotent skip with propertyMismatch tag, cascade form cleanup " //$NON-NLS-1$
            + "(cascadeForms=true), object commands, form command-interface items, " //$NON-NLS-1$
            + "picture validation, batch operations, and multi-language synonyms."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "Operation name. Use 'help' to list available operations and topics.", true) //$NON-NLS-1$
            .stringProperty("projectName", "EDT project name (most operations).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("ownerFqn", //$NON-NLS-1$
                "FQN of the owning metadata object. Where an operation acts on a child it may address one " //$NON-NLS-1$
                + "directly as Type.Name.Kind.Child. Full text: operation=help topic=parameters.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("property", //$NON-NLS-1$
                "The reference property to change. add / remove_object_reference take a LIST-valued one, set " //$NON-NLS-1$
                + "/ clear_object_reference a SCALAR one. Full text: operation=help topic=parameters.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("valueFqn", //$NON-NLS-1$
                "FQN of the referenced object, for the reference and content operations. Which shape each of " //$NON-NLS-1$
                + "them expects differs. Full text: operation=help topic=parameters.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("autoRecord", //$NON-NLS-1$
                "add_exchange_plan_content: per-item auto change registration - Deny (default) or Allow.") //$NON-NLS-1$
            .stringProperty("use", //$NON-NLS-1$
                "add_common_attribute_content: per-object usage of the common attribute - " //$NON-NLS-1$
                + "Auto (default), Use or DontUse.") //$NON-NLS-1$
            .stringProperty("subsystems", //$NON-NLS-1$
                "set_subsystems_order: comma-separated top-level subsystems in the wanted leading order. " //$NON-NLS-1$
                + "Participating ones not listed are appended after them.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("subsystem", //$NON-NLS-1$
                "The subsystem whose command interface or section visibility to change. Top-level " //$NON-NLS-1$
                + "(Subsystem.X), nested (Subsystem.A.Subsystem.B) and bare dotted (A.B) are all accepted.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("command", //$NON-NLS-1$
                "set_main_section_command_visibility / set_subsystem_command_visibility / " //$NON-NLS-1$
                + "set_command_placement: the command FQN whose visibility/placement to set - a " //$NON-NLS-1$
                + "CommonCommand (CommonCommand.X) or an object command " //$NON-NLS-1$
                + "(e.g. Catalog.X.Command.Y).") //$NON-NLS-1$
            .stringProperty("group", //$NON-NLS-1$
                "set_command_placement / set_command_order: the command-interface group - a friendly name, a " //$NON-NLS-1$
                + "platform token, or CommandGroup.<name>. Full text: operation=help topic=parameters.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("commands", //$NON-NLS-1$
                "set_command_order: JSON array of command FQNs in the wanted leading order. They are " //$NON-NLS-1$
                + "reordered within one group; the rest keep their relative order after them.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("visible", //$NON-NLS-1$
                "true shows, false hides, for the visibility operations. Sets the common value, or one role's " //$NON-NLS-1$
                + "when role is given; per-role exceptions are preserved either way.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("role", //$NON-NLS-1$
                "Optional Role FQN for the visibility operations: sets that one role's value instead of the " //$NON-NLS-1$
                + "common, role-independent one.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("objectType", //$NON-NLS-1$
                "English-singular metadata type for createObject (e.g. Catalog, Document).") //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "Name of the new element (createObject / addObjectAttribute / ...).") //$NON-NLS-1$
            .stringProperty("synonym", //$NON-NLS-1$
                "Synonym for a newly created object or field. Auto-generated from the name when omitted, the " //$NON-NLS-1$
                + "way the EDT wizard does it; a JSON object keyed by language code sets several languages at " //$NON-NLS-1$
                + "once. Full text: operation=help topic=parameters.") //$NON-NLS-1$ //$NON-NLS-1$
            .booleanProperty("server", //$NON-NLS-1$
                "create_object CommonModule: the Server context. Defaults to true when no context is given - " //$NON-NLS-1$
                + "the common-module-type check requires one.") //$NON-NLS-1$ //$NON-NLS-1$
            .booleanProperty("externalConnection", //$NON-NLS-1$
                "create_object CommonModule: the External connection context.") //$NON-NLS-1$
            .booleanProperty("clientOrdinaryApplication", //$NON-NLS-1$
                "create_object CommonModule: the Client (ordinary application) context.") //$NON-NLS-1$
            .booleanProperty("serverCall", //$NON-NLS-1$
                "create_object CommonModule: whether server procedures are callable from the client.") //$NON-NLS-1$
            .booleanProperty("global", //$NON-NLS-1$
                "create_object CommonModule: the Global flag. Not an execution context on its own.") //$NON-NLS-1$
            .booleanProperty("privileged", //$NON-NLS-1$
                "create_object CommonModule: privileged mode.") //$NON-NLS-1$
            .stringProperty("propertyName", //$NON-NLS-1$
                "set_object_property: which property to set. A few names take a shaped value rather than a " //$NON-NLS-1$
                + "plain one - fillValue, inputByString, choiceParameters, choiceParameterLinks. Full text: " //$NON-NLS-1$
                + "operation=help topic=parameters.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("propertyValue", //$NON-NLS-1$
                "Property value for setObjectProperty (string; coerced to setter type).") //$NON-NLS-1$
            .stringProperty("type", //$NON-NLS-1$
                "Type for the attribute-creating operations and set_object_type. A primitive (String / Number " //$NON-NLS-1$
                + "/ Date / Boolean / UUID), a reference (CatalogRef.X), or a composite as one comma-separated " //$NON-NLS-1$
                + "string. Qualifiers are separate parameters. Full text: operation=help topic=parameters.") //$NON-NLS-1$ //$NON-NLS-1$
            .booleanProperty("multiLine", //$NON-NLS-1$
                "Multi-line input mode for the attribute (BasicFeature.multiLine). Optional.") //$NON-NLS-1$
            .integerProperty("length", //$NON-NLS-1$
                "Maximum length for type=String / type=BinaryData (0 = unlimited). Default 0. Optional.") //$NON-NLS-1$
            .integerProperty("precision", //$NON-NLS-1$
                "Total digit count for type=Number. Default 10. Optional.") //$NON-NLS-1$
            .integerProperty("fractionDigits", //$NON-NLS-1$
                "Digits after the decimal point for type=Number. Default 0. Optional.") //$NON-NLS-1$
            .booleanProperty("nonNegative", //$NON-NLS-1$
                "Restrict type=Number to non-negative values (AllowedSign=Nonnegative). Default false. Optional.") //$NON-NLS-1$
            .stringProperty("dateFractions", //$NON-NLS-1$
                "Date / DateTime / Time (DateFractions) for type=Date. Default Date. Optional.") //$NON-NLS-1$
            .stringProperty("allowedLength", //$NON-NLS-1$
                "Variable / Fixed (AllowedLength) for type=String. Default Variable. Optional.") //$NON-NLS-1$
            .stringProperty("fillChecking", //$NON-NLS-1$
                "Fill-check mode for a new field: DontCheck or ShowError. A field kind that does not support " //$NON-NLS-1$
                + "it surfaces in failedProperties.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("fullTextSearch", //$NON-NLS-1$
                "addObjectAttribute / addTabularSectionAttribute / addRegisterField: full-text search usage. DontUse | Use. Optional.") //$NON-NLS-1$
            .stringProperty("indexing", //$NON-NLS-1$
                "addObjectAttribute / addTabularSectionAttribute / addRegisterField: index mode. DontIndex | Index | IndexWithAdditionalOrder. Optional.") //$NON-NLS-1$
            .stringProperty("toolTip", //$NON-NLS-1$
                "addObjectAttribute / addTabularSectionAttribute / addRegisterField: tooltip text (localized, stored under 'ru'). Optional.") //$NON-NLS-1$
            .stringProperty("comment", //$NON-NLS-1$
                "addObjectAttribute / addTabularSectionAttribute / addRegisterField: developer comment. " //$NON-NLS-1$
                + "Also the FormParameter comment for add_form_parameter. Optional.") //$NON-NLS-1$
            .stringProperty("formFqn", //$NON-NLS-1$
                "FQN of the form for form operations (e.g. Catalog.Users.Form.ItemForm, " //$NON-NLS-1$
                + "CommonForm.X.Form). Used by add_form_attribute / add_form_parameter / add_field / ...") //$NON-NLS-1$
            .booleanProperty("keyParameter", //$NON-NLS-1$
                "add_form_parameter: mark the parameter as a key parameter (FormParameter.keyParameter). " //$NON-NLS-1$
                + "Default false.") //$NON-NLS-1$
            .stringProperty("formName", //$NON-NLS-1$
                "Form name for createForm. Falls back to `name` when omitted. " //$NON-NLS-1$
                + "For a CommonForm pass ownerFqn=CommonForm.<Name> (or ownerFqn=CommonForm + formName=<Name>) " //$NON-NLS-1$
                + "- it is created as a top-level common form, not attached to a Forms collection.") //$NON-NLS-1$
            .stringProperty("formType", //$NON-NLS-1$
                "Form type for createForm: ORDINARY or MANAGED (default MANAGED). Legacy purpose values are normalized to MANAGED.") //$NON-NLS-1$
            .stringProperty("layout", //$NON-NLS-1$
                "Form layout for createForm: auto (typed defaults) / empty (manual layout) / standard.") //$NON-NLS-1$
            .stringProperty("purpose", //$NON-NLS-1$
                "create_form: which form the EDT generator should build - ItemForm, ListForm, ChoiceForm, " //$NON-NLS-1$
                + "FolderForm, RecordSetForm, RecordForm, Generic. Derived from the owner type and the form " //$NON-NLS-1$
                + "name when omitted. Full text: operation=help topic=parameters.") //$NON-NLS-1$ //$NON-NLS-1$
            .booleanProperty("setAsDefault", //$NON-NLS-1$
                "Set this form as the owner's default form (createForm).") //$NON-NLS-1$
            .stringProperty("templateName", //$NON-NLS-1$
                "Template name. For addTemplate: the metadata Template name (falls back to `name` when " //$NON-NLS-1$
                + "omitted). For set_restriction_template / remove_restriction_template: the RLS " //$NON-NLS-1$
                + "restriction-template name (required, no fallback; matched case-insensitively).") //$NON-NLS-1$
            .stringProperty("templateType", //$NON-NLS-1$
                "Template type for addTemplate: SpreadsheetDocument / TextDocument / BinaryData / ActiveDocument / GraphicalScheme / DataCompositionSchema / DataCompositionAppearanceTemplate / Geographical Schema / HTMLDocument / AddIn.") //$NON-NLS-1$
            .stringProperty("content", //$NON-NLS-1$
                "Plain-text body of a TextDocument or HTMLDocument template - filled on create, replaced by " //$NON-NLS-1$
                + "set_template_content, returned by get_template_content. Spreadsheets go through mxl_workshop " //$NON-NLS-1$
                + "and DCS through dcs_workshop.") //$NON-NLS-1$ //$NON-NLS-1$
            // Wave F: create_route_map parameters (BusinessProcess Flowchart.scheme).
            .stringProperty("points", //$NON-NLS-1$
                "create_route_map: JSON array of route points, laid out top to bottom. Needs exactly one " //$NON-NLS-1$
                + "Start and at least one Completion. Full text: operation=help topic=parameters.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("transitions", //$NON-NLS-1$
                "create_route_map: JSON array of transitions. Each object: {\"from\":<point name>, \"to\":<point name>, " //$NON-NLS-1$
                    + "\"branch\"? = true|false (REQUIRED when 'from' is a Condition; yes/no, да/нет accepted), \"title\"?}.") //$NON-NLS-1$
            .stringProperty("bpFqn", //$NON-NLS-1$
                "create/get/remove_route_map: BusinessProcess FQN (alias of ownerFqn), e.g. BusinessProcess.Order.") //$NON-NLS-1$
            .booleanProperty("overwrite", //$NON-NLS-1$
                "create_route_map: replace an existing Flowchart.scheme (default false - refuses to clobber).") //$NON-NLS-1$
            .stringProperty("tabularSection", //$NON-NLS-1$
                "Tabular section name for addTabularSectionAttribute / removeTabularSectionAttribute (alias of tabularSectionName).") //$NON-NLS-1$
            .stringProperty("tabularSectionName", //$NON-NLS-1$
                "Tabular section name for TS operations.") //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic name (use with operation=help).") //$NON-NLS-1$
            // 1.43: create_http_service parameters (composite HTTPService creation).
            .stringProperty("rootURL", //$NON-NLS-1$
                "create_http_service: root URL of the service (no leading slashes - platform rejects them). Default = '<name>'.") //$NON-NLS-1$
            .stringProperty("aliases", //$NON-NLS-1$
                "create_http_service: comma-separated alias paths (optional).") //$NON-NLS-1$
            .booleanProperty("reuseSessions", //$NON-NLS-1$
                "create_http_service: reuse HTTP sessions across requests. Maps to the platform " //$NON-NLS-1$
                + "SessionReuseMode enum - true -> Use, false -> DontUse; omitted leaves the platform " //$NON-NLS-1$
                + "default (AutoUse is not exposed by this boolean).") //$NON-NLS-1$
            .integerProperty("sessionMaxAge", //$NON-NLS-1$
                "create_http_service: session max age in seconds (optional).") //$NON-NLS-1$
            .stringProperty("urlTemplateName", //$NON-NLS-1$
                "create_http_service: name of the initial URLTemplate. Default 'Template1'.") //$NON-NLS-1$
            .stringProperty("urlTemplate", //$NON-NLS-1$
                "create_http_service / add_url_template: URL pattern with leading slash (e.g. '/clients/{id}'). Default = '/<urlTemplateName>'. Bare '/' is rejected by platform validator.") //$NON-NLS-1$
            .stringProperty("methodName", //$NON-NLS-1$
                "create_http_service: name of the initial Method. Default 'Get'.") //$NON-NLS-1$
            .stringProperty("httpMethod", //$NON-NLS-1$
                "create_http_service / add_url_template_method: GET/POST/PUT/DELETE/MERGE/PATCH/HEAD/OPTIONS/TRACE/CONNECT/PROPFIND/PROPPATCH/MKCOL/COPY/MOVE/LOCK/UNLOCK/Any. Default GET.") //$NON-NLS-1$
            .stringProperty("handler", //$NON-NLS-1$
                "create_http_service / add_url_template_method: handler procedure name (default '<urlTemplateName><methodName>'). " //$NON-NLS-1$
                + "set_event_subscription: handler reference, normalized to 'CommonModule.<Module>.<Method>' (short '<Module>.<Method>' accepted).") //$NON-NLS-1$
            .stringProperty("source", //$NON-NLS-1$
                "set_event_subscription: comma-separated source value-type FQNs, e.g. " //$NON-NLS-1$
                + "'DocumentObject.X,InformationRegisterRecordSet.Y,ConstantValueManager.Z,DefinedType.W'. Replaces the subscription source.") //$NON-NLS-1$
            .stringProperty("event", //$NON-NLS-1$
                "The event to handle. set_event_subscription takes a platform event (BeforeWrite, OnWrite, " //$NON-NLS-1$
                + "Posting...), the form operations take a form event; Russian names are accepted.") //$NON-NLS-1$ //$NON-NLS-1$
            .booleanProperty("createModule", //$NON-NLS-1$
                "create_http_service: write Module.bsl for the service. Default true.") //$NON-NLS-1$
            .booleanProperty("withHandlerStub", //$NON-NLS-1$
                "create_http_service / create_web_service: insert a stub handler procedure into Module.bsl. Default true.") //$NON-NLS-1$
            .stringProperty("template", //$NON-NLS-1$
                "add_url_template: URL pattern (alias of urlTemplate for older call sites).") //$NON-NLS-1$
            // 1.43: create_web_service parameters
            .stringProperty("namespace", //$NON-NLS-1$
                "create_web_service: XML URI namespace (e.g. 'http://example.com/myservice'). Required - platform rejects empty namespaces.") //$NON-NLS-1$
            .stringProperty("operationName", //$NON-NLS-1$
                "create_web_service: name of the initial Operation (default 'Default'). " //$NON-NLS-1$
                + "add_operation_parameter: name of the existing Operation to add the parameter to.") //$NON-NLS-1$
            .booleanProperty("transactional", //$NON-NLS-1$
                "create_web_service Operation.Transactional flag. Default false.") //$NON-NLS-1$
            // 1.43.x audit A6: typed web-service operation parameters + return type.
            .stringProperty("returningValueType", //$NON-NLS-1$
                "add_web_service_operation: XDTO return value type as a QName - either " //$NON-NLS-1$
                + "'{nsUri}localName' or a bare local name (e.g. 'string') combined with " //$NON-NLS-1$
                + "returningValueTypeNs (default XSD namespace). HTTP services are untyped. Optional.") //$NON-NLS-1$
            .stringProperty("returningValueTypeNs", //$NON-NLS-1$
                "add_web_service_operation: namespace URI for returningValueType when given as a bare " //$NON-NLS-1$
                + "local name. Default 'http://www.w3.org/2001/XMLSchema'. Optional.") //$NON-NLS-1$
            .stringProperty("valueType", //$NON-NLS-1$
                "add_operation_parameter: XDTO parameter value type as a QName - '{nsUri}localName' " //$NON-NLS-1$
                + "or a bare local name + valueTypeNs (default XSD namespace).") //$NON-NLS-1$
            .stringProperty("valueTypeNs", //$NON-NLS-1$
                "add_operation_parameter: namespace URI for valueType when given as a bare local " //$NON-NLS-1$
                + "name. Default 'http://www.w3.org/2001/XMLSchema'. Optional.") //$NON-NLS-1$
            .stringProperty("transferDirection", //$NON-NLS-1$
                "add_operation_parameter: parameter direction IN / OUT / IN_OUT. Default IN. Optional.") //$NON-NLS-1$
            .booleanProperty("nillable", //$NON-NLS-1$
                "add_operation_parameter: whether the parameter accepts a null (xsi:nil) value. " //$NON-NLS-1$
                + "Default false. Optional.") //$NON-NLS-1$
            // 1.43.x audit A3: CalculationRegister recalculations.
            .stringProperty("recalculationName", //$NON-NLS-1$
                "add_recalculation_dimension: name of the existing Recalculation (under the " //$NON-NLS-1$
                + "CalculationRegister given by ownerFqn) to add the dimension to.") //$NON-NLS-1$
            .stringProperty("registerDimension", //$NON-NLS-1$
                "add_recalculation_dimension: name of an existing CalculationRegister dimension that " //$NON-NLS-1$
                + "the recalculation dimension references (add it first via add_register_field kind=Dimension).") //$NON-NLS-1$
            // 1.43.x audit A3: predefined data items.
            .stringProperty("description", //$NON-NLS-1$
                "add_predefined_item: presentation/description of the predefined item (optional).") //$NON-NLS-1$
            .stringProperty("code", //$NON-NLS-1$
                "add_predefined_item: item code (applied only for ChartOfAccounts / " //$NON-NLS-1$
                + "ChartOfCharacteristicTypes where code is a String; Value-typed codes are skipped " //$NON-NLS-1$
                + "with a warning). Optional.") //$NON-NLS-1$
            .booleanProperty("isFolder", //$NON-NLS-1$
                "add_predefined_item: create the item as a group/folder (Catalog / " //$NON-NLS-1$
                + "ChartOfCharacteristicTypes only). Default false. Optional.") //$NON-NLS-1$
            // 1.43.x RSV-5.2 Tier 2: ChartOfAccounts predefined-account fields + subconto.
            .stringProperty("accountType", //$NON-NLS-1$
                "add_predefined_item (ChartOfAccounts): Active / Passive / ActivePassive (RU aliases ok). Optional.") //$NON-NLS-1$
            .booleanProperty("offBalance", //$NON-NLS-1$
                "add_predefined_item (ChartOfAccounts): off-balance account. Optional.") //$NON-NLS-1$
            .stringProperty("order", //$NON-NLS-1$
                "add_predefined_item: the account display order. set_command_placement: 0-based position " //$NON-NLS-1$
                + "within the group - omit to leave the command where it is.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("accountName", //$NON-NLS-1$
                "add/remove_predefined_account_subconto: the predefined account name") //$NON-NLS-1$
            .stringProperty("characteristicType", //$NON-NLS-1$
                "add/remove_predefined_account_subconto: predefined-item name in the linked " //$NON-NLS-1$
                + "ChartOfCharacteristicTypes (the subconto value)") //$NON-NLS-1$
            .booleanProperty("turnover", //$NON-NLS-1$
                "add_predefined_account_subconto: 'turnovers only' flag. Optional.") //$NON-NLS-1$
            // 1.43.1: Extensions group parameters (adopt_object / adopt_objects /
            // adopt_child / adopt_form_item / adopt_module). Previously these
            // were read from params but missing from the schema, so strict MCP
            // clients dropped them.
            .stringProperty("baseProjectName", //$NON-NLS-1$
                "Source configuration project for adopt_* operations (the extension's parent base config).") //$NON-NLS-1$
            .stringProperty("targetFqn", //$NON-NLS-1$
                "adopt_*: the child to adopt, composed from ownerFqn + childKind + name when omitted. " //$NON-NLS-1$
                + "set_role_right / set_role_restriction: the object the right or condition is set on.") //$NON-NLS-1$ //$NON-NLS-1$
            // set_role_right parameters (previously read from params but absent from the
            // schema, so strict MCP clients dropped them and the op was uncallable).
            .stringProperty("rightName", //$NON-NLS-1$
                "The access right on targetFqn - Read, Insert, Update, Delete, View, Edit and the rest; " //$NON-NLS-1$
                + "Russian names are accepted and normalised.") //$NON-NLS-1$ //$NON-NLS-1$
            .booleanProperty("value", //$NON-NLS-1$
                "set_role_right: grant (true) or revoke (false) the right on targetFqn. Default true.") //$NON-NLS-1$
            .booleanProperty("cascadeDependencies", //$NON-NLS-1$
                "set_role_right: also grant the rights this one requires (Update needs Read, Posting needs " //$NON-NLS-1$
                + "Read and Update). Grant-direction only, never revokes; what it added comes back in " //$NON-NLS-1$
                + "cascadedRights. Default false.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("condition", //$NON-NLS-1$
                "The RLS condition body: a restriction template for set_restriction_template, a row-level " //$NON-NLS-1$
                + "condition on the right for set_role_restriction. Other role content is preserved.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "Alias of targetFqn for adopt_* operations.") //$NON-NLS-1$
            .stringProperty("childKind", //$NON-NLS-1$
                "Child kind for adopt_child: Form / Attribute / TabularSection / Template / Command / Dimension / Resource. " //$NON-NLS-1$
                    + "Russian aliases accepted (Форма / Реквизит / ТабличнаяЧасть / Макет / Команда / Измерение / Ресурс).") //$NON-NLS-1$
            .stringProperty("containerFqn", //$NON-NLS-1$
                "remove_item: the form item holding the one named by `name`. move_item: the form itself, with " //$NON-NLS-1$
                + "parentName naming the destination container.") //$NON-NLS-1$ //$NON-NLS-1$
            // ---- Form-operation parameters (add_field / add_button / add_table /
            //      add_decoration / add_dynamic_list_table / set_property / ...) ----
            .stringProperty("itemName", //$NON-NLS-1$
                "Form item name for set_property / add_form_event_handler (the field / " //$NON-NLS-1$
                    + "table / button to act on).") //$NON-NLS-1$
            .stringProperty("attributeName", //$NON-NLS-1$
                "The form attribute to act on - the column's owner for add_form_attribute_column (prefer " //$NON-NLS-1$
                + "parentAttributeName), or the target of set_property on an attribute's extInfo.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("parentAttributeName", //$NON-NLS-1$
                "Parent ValueTable form-attribute name for add_form_attribute_column " //$NON-NLS-1$
                    + "(the column's owner). attributeName is accepted as an alias.") //$NON-NLS-1$
            .stringProperty("tableName", //$NON-NLS-1$
                "UI Table name for add_dynamic_list_table.") //$NON-NLS-1$
            .stringProperty("mainTable", //$NON-NLS-1$
                "Dynamic-list main table FQN (e.g. Catalog.Users) for add_dynamic_list_table.") //$NON-NLS-1$
            .stringProperty("commandName", //$NON-NLS-1$
                "Form command name: for add_command_handler the command to attach the " //$NON-NLS-1$
                    + "handler to; for add_button an EXISTING command to reuse instead of " //$NON-NLS-1$
                    + "creating <name>Command.") //$NON-NLS-1$
            .stringProperty("title", //$NON-NLS-1$
                "Title / caption for a new form element (field / group / button / table).") //$NON-NLS-1$
            .stringProperty("parentName", //$NON-NLS-1$
                "Parent container name for add_field / add_group / add_button / add_table / " //$NON-NLS-1$
                    + "add_decoration (default: form root). A button targeted at a table is " //$NON-NLS-1$
                    + "auto-placed in the table's command bar.") //$NON-NLS-1$
            .stringProperty("elementType", //$NON-NLS-1$
                "Element subtype for add_field, add_group and add_decoration. The accepted values differ per " //$NON-NLS-1$
                + "operation. Full text: operation=help topic=parameters.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("dataPath", //$NON-NLS-1$
                "Data path binding for add_field / add_table (e.g. Object.Name).") //$NON-NLS-1$
            .stringProperty("beforeName", //$NON-NLS-1$
                "Insert the new element before this sibling element (default: append).") //$NON-NLS-1$
            .stringProperty("standardCommand", //$NON-NLS-1$
                "Bind an add_button to a platform stock command (PostAndClose / Write / " //$NON-NLS-1$
                    + "Refresh / ...). Rejected on DataProcessor / ExternalReport forms.") //$NON-NLS-1$
            .stringProperty("handlerName", //$NON-NLS-1$
                "BSL handler procedure name for add_form_event_handler (default derived " //$NON-NLS-1$
                    + "from the event / item).") //$NON-NLS-1$
            .stringProperty("picture", //$NON-NLS-1$
                "Picture reference for add_decoration elementType=Picture (StdPicture.X / " //$NON-NLS-1$
                    + "CommonPicture.X).") //$NON-NLS-1$
            .booleanProperty("hyperlink", //$NON-NLS-1$
                "Render a Label decoration / Label field as a clickable hyperlink. Default false.") //$NON-NLS-1$
            .booleanProperty("autoGenerateColumns", //$NON-NLS-1$
                "add_table with dataPath: auto-create columns for every attribute of the " //$NON-NLS-1$
                    + "underlying tabular section / value table. Default false.") //$NON-NLS-1$
            .booleanProperty("dryRun", //$NON-NLS-1$
                "Preview the operation inside a BM transaction and roll back. Default false.") //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "delete_metadata_object only: applies the deletion rather than returning a preview. " //$NON-NLS-1$
                    + "Call once without confirm to see what would be affected, then call again with " //$NON-NLS-1$
                    + "confirm=true to carry it out. Ignored by every other operation.") //$NON-NLS-1$
            .booleanProperty("batch", //$NON-NLS-1$
                "Run several operations from ONE call, in order, each in its own transaction. NOT atomic: a " //$NON-NLS-1$
                + "failure partway leaves the earlier ones applied. Full text: operation=help topic=parameters.") //$NON-NLS-1$ //$NON-NLS-1$
            .booleanProperty("stopOnError", //$NON-NLS-1$
                "batch only: stop at the first failing operation instead of running the rest. " //$NON-NLS-1$
                + "Already-committed operations are NOT rolled back. Default false.") //$NON-NLS-1$ //$NON-NLS-1$
            .stringProperty("runKey", //$NON-NLS-1$
                "Handle to a run already going, from a previous answer that came back " //$NON-NLS-1$
                + "status=Pending. Re-call with it to collect that run's result; the work is not " //$NON-NLS-1$
                + "restarted and nothing is applied twice. Without it a call is a fresh request.") //$NON-NLS-1$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "How long to wait before answering status=Pending with a runKey instead of the " //$NON-NLS-1$
                + "result. 5 to 120, default 25. The work continues either way - this bounds the " //$NON-NLS-1$
                + "wait, not the operation.") //$NON-NLS-1$
            .stringProperty("operations", //$NON-NLS-1$
                "batch=true payload: a JSON array of operation objects, each {\"operation\":<name>, ...that " //$NON-NLS-1$
                + "op's own params}. Per-item params override the inherited projectName / ownerFqn / formFqn / dryRun. Full text: operation=help " //$NON-NLS-1$
                + "topic=parameters.") //$NON-NLS-1$ //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        boolean batch = JsonUtils.extractBooleanArgument(params, "batch", false); //$NON-NLS-1$
        if (batch)
        {
            return executeBatchPending(params);
        }

        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.isEmpty())
        {
            return ToolResult.error("operation is required. Pass operation=help for the catalog.") //$NON-NLS-1$
                .toJson();
        }
        // Accept camelCase (createObject, setObjectProperty) as well as the
        // canonical snake_case: LLMs frequently emit the camelCase form from the
        // tool description/examples. normalizeOperationToken leaves already
        // snake_case and single-word tokens (help) unchanged.
        String op = JsonUtils.normalizeOperationToken(operation.trim());
        if ("help".equalsIgnoreCase(op)) //$NON-NLS-1$
        {
            return handleHelp(params);
        }

        if (!registry.containsKey(op))
        {
            return ToolResult.error(
                "Unknown operation '" + op + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Call operation=help for the full list. " //$NON-NLS-1$
                    + "Did you mean: " + suggest(op) + "?") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }

        // On the UI thread already - run it here. Handing the work to a pool thread that then needs
        // this same thread would deadlock, and a caller that is on the UI thread is not an MCP
        // client with a patience to run out.
        if (Display.getCurrent() != null)
        {
            return applyOne(op, params);
        }
        return executeSinglePending(op, params);
    }

    /**
     * Runs one operation, handing back a runKey if it outlives the caller's patience.
     * <p>
     * On a configuration of some 13 thousand objects a single write can take longer than a client
     * will wait. What was reported is not a slow answer but a wrong one: the client stopped waiting
     * and said the operation had timed out, while the attribute it asked for was already on disk.
     * Nothing the server writes into a response can reach a caller who has stopped reading, so the
     * answer has to arrive earlier - a runKey, with the work still running behind it.
     * </p>
     *
     * @param op the normalized operation name, never {@code null}
     * @param params the call parameters, never {@code null}
     * @return the operation result when it finishes in time, a Pending answer otherwise
     */
    /** Separator between the key and the value in a call identity. */
    private static final char EQUALS = '=';

    /** Separator between one key-value pair and the next in a call identity. */
    private static final char SEPARATOR = ';';

    private String executeSinglePending(String op, Map<String, String> params)
    {
        long softTimeoutMs = Math.max(5, Math.min(120,
            JsonUtils.extractIntArgument(params, "timeoutSeconds", 25))) * 1000L; //$NON-NLS-1$
        String providedRunKey = JsonUtils.extractStringArgument(params, "runKey"); //$NON-NLS-1$
        PendingWorkRegistry reg = PendingWorkRegistry.UPDATE;
        reg.pruneExpired();
        String runKey = (providedRunKey != null && !providedRunKey.isEmpty())
            ? providedRunKey
            : PendingWorkRegistry.computeRunKey("edit_metadata", op, callIdentity(params)); //$NON-NLS-1$

        PendingWorkRegistry.PendingEntry entry;
        if (providedRunKey != null && !providedRunKey.isEmpty())
        {
            entry = reg.get(runKey);
            if (entry == null)
            {
                return ToolResult.error("runKey not found - the operation either finished and its " //$NON-NLS-1$
                    + "result was already retrieved, or it expired. Re-issue it without runKey.") //$NON-NLS-1$
                    .toJson();
            }
        }
        else
        {
            // The same call issued twice in a row is two requests, not one: the second must run
            // against the project as it stands now and answer for itself. Only a poll by runKey
            // replays what an earlier call started.
            PendingWorkRegistry.PendingEntry prior = reg.get(runKey);
            if (prior != null && prior.isDone())
            {
                reg.remove(runKey);
            }
            entry = reg.getOrStart(runKey, job -> applyOne(op, params));
        }

        String result = entry.await(softTimeoutMs);
        if (result != null)
        {
            reg.remove(runKey);
            return result;
        }
        return ToolResult.success()
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put(ru.aiedt.mcp.server.support.PendingEnvelope.MARK, true)
            .put("operation", op) //$NON-NLS-1$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("message", "The operation is still running. Re-call edit_metadata with this " //$NON-NLS-1$
                + "runKey to collect its result. It has not been abandoned and it has not " //$NON-NLS-1$
                + "failed: whatever it writes is written whether or not this key is collected.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * The parameters that make two calls the same call.
     *
     * @param params the call parameters, never {@code null}
     * @return a stable rendering, never {@code null}
     */
    static String callIdentity(Map<String, String> params)
    {
        StringBuilder sb = new StringBuilder();
        for (String key : new java.util.TreeSet<>(params.keySet()))
        {
            // Neither of these says what is being asked for - one is how long to wait, the other
            // is the handle to a run already going.
            if ("runKey".equals(key) || "timeoutSeconds".equals(key)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                continue;
            }
            sb.append(key).append(EQUALS).append(params.get(key)).append(SEPARATOR);
        }
        return sb.toString();
    }

    /**
     * Applies one operation on the UI thread, under the mutation lock.
     *
     * @param op the normalized operation name, never {@code null}
     * @param params the call parameters, never {@code null}
     * @return the operation result as JSON, never {@code null}
     */
    private String applyOne(String op, Map<String, String> params)
    {
        // All operations require BM access on the UI thread. Hold the mutation lock across it so a
        // single op cannot slip in between two sub-operations of a concurrent batch (and vice versa).
        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        MetadataMutationLock.acquire();
        try
        {
            display.syncExec(() -> {
                try
                {
                    resultRef.set(dispatch(op, params));
                }
                catch (Exception e)
                {
                    Activator.logError("edit_metadata error in operation " + op, e); //$NON-NLS-1$
                    resultRef.set(ToolResult.error(TextSuggest.safeMessage(e)).toJson());
                }
            });
        }
        finally
        {
            MetadataMutationLock.release();
        }
        return resultRef.get();
    }

    /**
     * Sequential batch mode: applies a list of operations one by one. Each
     * sub-operation runs in its own BM transaction; on per-op failure the
     * batch continues by default and records the failure in {@code batchResults}.
     * With {@code stopOnError=true} it stops at the first failure and marks the
     * remaining operations {@code skipped=true}.
     * <p>
     * <b>Not transactional.</b> Successful ops are committed even when a later op
     * fails; there is no rollback of the batch. {@code stopOnError} only bounds
     * how far it gets, it does not undo what already ran.
     * <p>
     * The {@code operations} parameter is expected as a flat string of
     * lines, each {@code "<operation> key1=value1 key2=value2"}. JSON-array
     * parsing is also accepted: {@code operations=[{"operation":"...", ...}]}.
     */
    private String executeBatch(Map<String, String> params,
        PendingWorkRegistry.PendingEntry job)
    {
        String operationsRaw = JsonUtils.extractStringArgument(params, "operations"); //$NON-NLS-1$
        if (operationsRaw == null || operationsRaw.isEmpty())
        {
            return ToolResult.error("batch=true requires `operations` parameter").toJson(); //$NON-NLS-1$
        }
        java.util.List<Map<String, String>> ops = parseBatchOperations(operationsRaw, params);
        if (ops.isEmpty())
        {
            return ToolResult.error("batch operations parsed empty - check format").toJson(); //$NON-NLS-1$
        }
        boolean stopOnError = JsonUtils.extractBooleanArgument(params, "stopOnError", false); //$NON-NLS-1$
        java.util.List<Map<String, Object>> results = new java.util.ArrayList<>();
        int okCount = 0;
        int failCount = 0;
        int stoppedAt = -1;
        // Hold the mutation lock across the whole batch so no other edit applies a change between
        // two of its sub-operations. The lock is reentrant, so this is safe even if the batch runs
        // inside another exclusive section.
        MetadataMutationLock.acquire();
        try
        {
        for (int i = 0; i < ops.size(); i++)
        {
            Map<String, String> opParams = ops.get(i);
            // #4: accept camelCase sub-operations in batch too. The single-op
            // path normalizes at execute(); batch dispatches here directly, so
            // normalize the sub-operation as well.
            String subOp = JsonUtils.normalizeOperationToken(
                JsonUtils.extractStringArgument(opParams, "operation")); //$NON-NLS-1$
            noteProgress(job, i, ops.size(), subOp, okCount, failCount);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", i); //$NON-NLS-1$
            entry.put("operation", subOp); //$NON-NLS-1$
            if (subOp == null || subOp.isEmpty() || !registry.containsKey(subOp))
            {
                entry.put("ok", false); //$NON-NLS-1$
                entry.put("error", "unknown or empty operation"); //$NON-NLS-1$ //$NON-NLS-2$
                failCount++;
                results.add(entry);
                if (stopOnError)
                {
                    stoppedAt = i;
                    break;
                }
                continue;
            }
            // Store the normalized op so downstream dispatch sees snake_case.
            opParams.put("operation", subOp); //$NON-NLS-1$
            // Inherit shared parameters from the outer call when individual ops omit them.
            // formFqn belongs here as much as ownerFqn does: every Forms operation requires it, so
            // without it a batch of them fails whole while the same operations pass one at a time -
            // reported from two workspaces in two days.
            for (String shared : SHARED_BATCH_PARAMS)
            {
                if (!opParams.containsKey(shared) && params.containsKey(shared))
                {
                    opParams.put(shared, params.get(shared));
                }
            }
            try
            {
                AtomicReference<String> ref = new AtomicReference<>();
                Display display = PlatformUI.getWorkbench().getDisplay();
                display.syncExec(() -> {
                    try
                    {
                        ref.set(dispatch(subOp, opParams));
                    }
                    catch (Exception e)
                    {
                        Activator.logError("batch op " + subOp + " failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                        ref.set(ToolResult.error(TextSuggest.safeMessage(e)).toJson());
                    }
                });
                String json = ref.get();
                entry.put("response", json); //$NON-NLS-1$
                boolean isOk = false;
                try
                {
                    com.google.gson.JsonObject obj = com.google.gson.JsonParser
                        .parseString(json).getAsJsonObject();
                    isOk = obj.has("success") && obj.get("success").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
                }
                catch (Exception parseEx)
                {
                    // Fallback to naive substring check if response is non-JSON
                    isOk = json != null && json.contains("\"success\":true"); //$NON-NLS-1$
                }
                entry.put("ok", isOk); //$NON-NLS-1$
                if (isOk)
                {
                    okCount++;
                }
                else
                {
                    failCount++;
                }
            }
            catch (Exception e)
            {
                entry.put("ok", false); //$NON-NLS-1$
                entry.put("error", e.getMessage()); //$NON-NLS-1$
                failCount++;
            }
            results.add(entry);
            if (stopOnError && Boolean.FALSE.equals(entry.get("ok"))) //$NON-NLS-1$
            {
                stoppedAt = i;
                break;
            }
        }
        int skippedCount = 0;
        if (stoppedAt >= 0)
        {
            // Record the operations that did not run, so the caller sees exactly where the batch stopped,
            // which ops were skipped, and that earlier successful ops were NOT rolled back.
            for (int j = stoppedAt + 1; j < ops.size(); j++)
            {
                Map<String, Object> skipped = new LinkedHashMap<>();
                skipped.put("index", j); //$NON-NLS-1$
                skipped.put("operation", JsonUtils.normalizeOperationToken( //$NON-NLS-1$
                    JsonUtils.extractStringArgument(ops.get(j), "operation"))); //$NON-NLS-1$
                skipped.put("ok", false); //$NON-NLS-1$
                skipped.put("skipped", true); //$NON-NLS-1$
                results.add(skipped);
                skippedCount++;
            }
        }
        ToolResult batchResult = ToolResult.success()
            .put("batch", true) //$NON-NLS-1$
            .put("ok", okCount) //$NON-NLS-1$
            .put("fail", failCount) //$NON-NLS-1$
            .put("skipped", skippedCount) //$NON-NLS-1$
            .put("stoppedOnError", stoppedAt >= 0) //$NON-NLS-1$
            .put("batchResults", results); //$NON-NLS-1$
        // The batch used to answer success:true whatever happened inside it - including
        // ok:0, fail:6, every single operation rejected. A caller reading the top-level
        // flag was told the edit went through when nothing had. The flag now means what
        // it says: every operation succeeded. The counts and batchResults[] still carry
        // the detail, so a partial run remains fully inspectable - it just no longer
        // passes for a clean one.
        if (failCount > 0)
        {
            batchResult = batchResult.demote(failCount + " of " //$NON-NLS-1$
                + (okCount + failCount + skippedCount)
                + " operations failed. Operations are NOT rolled back: the ones that succeeded " //$NON-NLS-1$
                + "are already applied.\n" + whyEachFailed(results)); //$NON-NLS-1$
        }
        return batchResult.toJson();
        }
        finally
        {
            MetadataMutationLock.release();
        }
    }

    /**
     * Batch entry point with the async Pending mechanism. A batch of many or slow
     * operations can exceed the MCP HTTP timeout; the ops still apply (on a worker
     * thread) but the response - the batchResults - would be lost. So the batch runs
     * async under a runKey and, if it does not finish within the soft timeout
     * (timeoutSeconds, default 25), returns Pending JSON carrying that runKey. A retry
     * with the same runKey (or an identical re-issue - the key is derived from
     * projectName + operations) returns the final batchResults. Mirrors the Pending
     * pattern of update_database / find_references.
     */
    private String executeBatchPending(Map<String, String> params)
    {
        String operationsRaw = JsonUtils.extractStringArgument(params, "operations"); //$NON-NLS-1$
        if (operationsRaw == null || operationsRaw.isEmpty())
        {
            return ToolResult.error("batch=true requires `operations` parameter").toJson(); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String providedRunKey = JsonUtils.extractStringArgument(params, "runKey"); //$NON-NLS-1$
        long softTimeoutMs = Math.max(5, Math.min(120,
            JsonUtils.extractIntArgument(params, "timeoutSeconds", 25))) * 1000L; //$NON-NLS-1$

        boolean stopOnError = JsonUtils.extractBooleanArgument(params, "stopOnError", false); //$NON-NLS-1$

        PendingWorkRegistry reg = PendingWorkRegistry.UPDATE;
        reg.pruneExpired();
        // Fold every outer-inherited shared param (ownerFqn, dryRun, stopOnError) into the key: a preview
        // (dryRun=true) and a real apply of byte-identical operations - or the same operations against a
        // different ownerFqn target, or with a different stopOnError policy - must NOT coalesce onto one
        // execution.
        String runKey = (providedRunKey != null && !providedRunKey.isEmpty())
            ? providedRunKey
            : PendingWorkRegistry.computeRunKey("batch", projectName, ownerFqn, //$NON-NLS-1$
                String.valueOf(dryRun), String.valueOf(stopOnError), operationsRaw);

        PendingWorkRegistry.PendingEntry entry;
        if (providedRunKey != null && !providedRunKey.isEmpty())
        {
            // Explicit retry - poll the existing entry, do NOT restart the work.
            entry = reg.get(runKey);
            if (entry == null)
            {
                return ToolResult.error("runKey not found - the batch either completed and its result " //$NON-NLS-1$
                    + "was already retrieved, or it expired. Re-issue the batch without runKey.").toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            // A fresh (non-runKey) resubmit of an identical batch that already completed but was
            // never retrieved must RE-RUN (project state may have moved on), not replay the stale
            // cached result - mirrors update_database's evict-then-dispatch.
            PendingWorkRegistry.PendingEntry prior = reg.get(runKey);
            if (prior != null && prior.isDone())
            {
                reg.remove(runKey);
            }
            entry = reg.getOrStart(runKey, job -> executeBatch(params, job));
        }

        String result = entry.await(softTimeoutMs);
        if (result != null)
        {
            reg.remove(runKey);
            return result;
        }
        // Still running past the soft timeout - hand back a runKey to poll with.
        ToolResult pending = ToolResult.success()
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put(ru.aiedt.mcp.server.support.PendingEnvelope.MARK, true)
            .put("batch", true) //$NON-NLS-1$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("message", "Batch still running - ops apply on a worker thread. Re-call edit_metadata " //$NON-NLS-1$
                + "with batch=true and this runKey (same operations) to fetch batchResults. " //$NON-NLS-1$
                + "`progress` says how far it has got; the operations it names as applied are " //$NON-NLS-1$
                + "committed already, because a batch commits each operation separately."); //$NON-NLS-1$
        if (entry.progressNote != null)
        {
            pending.put("progress", entry.progressNote); //$NON-NLS-1$
        }
        return pending.toJson();
    }

    /**
     * Records how far the batch has got, for whoever is holding its runKey.
     * <p>
     * A batch commits each operation on its own, so a run that is still going has already changed
     * the project. Without this the Pending answer carried only elapsed milliseconds, and a caller
     * whose batch outlived the timeout three times over had no way to learn that four of its six
     * operations were on disk - reported from a configuration of some 13 thousand objects, where
     * every batch outlives the timeout.
     * </p>
     *
     * @param job the entry to publish into; {@code null} when the batch runs outside the registry
     * @param index 0-based position of the operation about to run
     * @param total how many operations the batch holds
     * @param operation the one about to run; may be <code>null</code> for a malformed entry
     * @param okCount how many have succeeded so far
     * @param failCount how many have failed so far
     */
    static void noteProgress(PendingWorkRegistry.PendingEntry job, int index, int total,
        String operation, int okCount, int failCount)
    {
        if (job == null)
        {
            return;
        }
        job.progressNote = index + " of " + total + " done (" + okCount + " applied, " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + failCount + " failed); now running " //$NON-NLS-1$
            + (operation != null && !operation.isEmpty() ? operation : "an unnamed operation"); //$NON-NLS-1$
    }

    /**
     * Parses the {@code operations} parameter into a list of per-op parameter
     * maps. Two formats accepted:
     * <ul>
     *   <li>JSON array: {@code [{"operation":"add_object_attribute","name":"X"}, ...]}</li>
     *   <li>Newline-separated flat lines: {@code addObjectAttribute name=X}</li>
     * </ul>
     */
    private java.util.List<Map<String, String>> parseBatchOperations(String raw,
        Map<String, String> outer)
    {
        java.util.List<Map<String, String>> ops = new java.util.ArrayList<>();
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) //$NON-NLS-1$
        {
            // Best-effort JSON-array parse: split on top-level commas + extract
            // key/value pairs by simple regex. A full JSON parser would tie us
            // to Gson here, which is also fine - but the simple parser keeps
            // the dispatcher self-contained.
            try
            {
                com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(trimmed)
                    .getAsJsonArray();
                for (int i = 0; i < arr.size(); i++)
                {
                    com.google.gson.JsonObject o = arr.get(i).getAsJsonObject();
                    Map<String, String> opParams = new LinkedHashMap<>();
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : o.entrySet())
                    {
                        com.google.gson.JsonElement v = entry.getValue();
                        opParams.put(entry.getKey(),
                            v.isJsonPrimitive() ? v.getAsString() : v.toString());
                    }
                    ops.add(opParams);
                }
                return ops;
            }
            catch (Exception jsonEx)
            {
                Activator.logWarning("batch JSON parse failed: " + jsonEx.getMessage()); //$NON-NLS-1$
            }
        }
        // Line-based fallback: each non-empty line is one operation.
        for (String line : trimmed.split("\\r?\\n")) //$NON-NLS-1$
        {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) //$NON-NLS-1$
            {
                continue;
            }
            Map<String, String> opParams = new LinkedHashMap<>();
            String[] tokens = l.split("\\s+"); //$NON-NLS-1$
            opParams.put("operation", tokens[0]); //$NON-NLS-1$
            for (int i = 1; i < tokens.length; i++)
            {
                int eq = tokens[i].indexOf('=');
                if (eq > 0)
                {
                    opParams.put(tokens[i].substring(0, eq), tokens[i].substring(eq + 1));
                }
            }
            ops.add(opParams);
        }
        return ops;
    }

    /**
     * Parses a JSON array of flat objects into a list of string maps - used for
     * structured list-valued properties (choiceParameters {name,value},
     * choiceParameterLinks {name,field}). Non-array / malformed input yields an
     * empty list (the caller reports a clear "requires a JSON array" error).
     */
    static List<Map<String, String>> parseStructArray(String raw)
    {
        List<Map<String, String>> out = new ArrayList<>();
        if (raw == null)
        {
            return out;
        }
        String t = raw.trim();
        if (!t.startsWith("[")) //$NON-NLS-1$
        {
            return out;
        }
        try
        {
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(t).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++)
            {
                com.google.gson.JsonObject o = arr.get(i).getAsJsonObject();
                Map<String, String> m = new LinkedHashMap<>();
                for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet())
                {
                    com.google.gson.JsonElement v = e.getValue();
                    m.put(e.getKey(), v.isJsonPrimitive() ? v.getAsString() : v.toString());
                }
                out.add(m);
            }
        }
        catch (Exception ex)
        {
            Activator.logWarning("parseStructArray failed: " + ex.getMessage()); //$NON-NLS-1$
        }
        return out;
    }

    /**
     * Top-level element count of a JSON array string, or -1 when {@code raw} is
     * null / not a JSON array / unparseable. Lets callers detect that
     * {@link #parseStructArray} silently dropped non-object elements (parsed size
     * &lt; this) rather than reporting a misleading success.
     */
    static int jsonArrayLength(String raw)
    {
        if (raw == null)
        {
            return -1;
        }
        String t = raw.trim();
        if (!t.startsWith("[")) //$NON-NLS-1$
        {
            return -1;
        }
        try
        {
            return com.google.gson.JsonParser.parseString(t).getAsJsonArray().size();
        }
        catch (Exception ex)
        {
            return -1;
        }
    }

    /**
     * Routes to the helper that owns the operation.
     */
    private String dispatch(String op, Map<String, String> params)
    {
        OpEntry entry = registry.get(op);
        if (entry == null)
        {
            return ToolResult.error("Operation routed but not implemented: " + op).toJson(); //$NON-NLS-1$
        }
        // An argument this operation does not read is refused rather than dropped in silence. A
        // typo in a name and a property the operation does not support used to come back the way a
        // correct call did, so the caller could not tell what had been carried out. Where the
        // parameter map has no entry for an operation nothing is checked - the map states its own
        // gaps, and guessing at them would turn working calls away.
        List<String> unread = UnreadArguments.of("EditMetadataTool", op, params); //$NON-NLS-1$
        if (!unread.isEmpty())
        {
            return ToolResult.error(UnreadArguments.refusal(op, unread,
                UnreadArguments.readBy("EditMetadataTool", op))).toJson(); //$NON-NLS-1$
        }
        return entry.handler.apply(params);
    }

    // -----------------------------------------------------------------------
    // Object operations
    // -----------------------------------------------------------------------





    /**
     * Reads a form attribute's or command's {@code getFunctionalOptions()}
     * collection reflectively (an {@code EList<FunctionalOption>}), returning the
     * raw list or {@code null} when the object has no such getter. In the EDT form
     * model only {@code AbstractFormAttribute} (hence FormAttribute) and
     * {@code FormCommand} carry this feature - visual items (fields, tables,
     * buttons, groups, decorations) never do.
     */
    @SuppressWarnings("rawtypes")
    static void addToRawList(EList content, EObject item)
    {
        content.add(item);
    }

    // ---- J2: FunctionalOption content --------------------------------------
    // FunctionalOption.content is a plain reference list (each entry is the
    // referenced object / attribute directly, serialized as
    // <content>Document.X.Attribute.Y</content>), NOT a wrapper item like
    // ExchangePlan/CommonAttribute content. Targets are commonly attribute-
    // level, so resolution must be child-FQN capable. The option's storage
    // (location) and privilegedGetMode are set with the generic ops
    // (set_object_reference property=location / set_object_property
    // property=privilegedGetMode).

    /**
     * Throws a clear error when {@code value} is not assignable to the
     * {@link org.eclipse.emf.ecore.EReference} named {@code feature} on
     * {@code owner}. No-op when the feature is absent or not an EReference, so
     * callers keep their prior behavior for features this cannot reason about.
     */
    static void assertAssignableToRef(EObject owner, String feature, Object value, String fqn)
    {
        org.eclipse.emf.ecore.EStructuralFeature f = owner.eClass().getEStructuralFeature(feature);
        if (f instanceof org.eclipse.emf.ecore.EReference)
        {
            org.eclipse.emf.ecore.EClass rt = ((org.eclipse.emf.ecore.EReference) f).getEReferenceType();
            if (rt != null && !rt.isInstance(value))
            {
                String vt = (value instanceof EObject) ? ((EObject) value).eClass().getName()
                    : value.getClass().getSimpleName();
                throw new RuntimeException("Referenced object " + fqn + " (type " + vt //$NON-NLS-1$ //$NON-NLS-2$
                    + ") is not assignable to '" + feature + "' (expects " + rt.getName() + ")."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
    }

    /**
     * Proxy-aware membership check for a plain reference list: resolves an
     * as-loaded proxy entry against the freshly-resolved {@code value} before
     * comparing, so idempotency holds regardless of load state.
     */
    @SuppressWarnings("rawtypes")
    static boolean listContainsResolved(EList list, Object value)
    {
        for (Object o : list)
        {
            if (o == value)
            {
                return true;
            }
            if (o instanceof EObject && ((EObject) o).eIsProxy() && value instanceof EObject)
            {
                EObject resolved = org.eclipse.emf.ecore.util.EcoreUtil.resolve((EObject) o, (EObject) value);
                if (resolved == value)
                {
                    return true;
                }
            }
        }
        return false;
    }

    /** Removes the first entry of {@code list} equal to {@code value} (proxy-aware). */
    @SuppressWarnings("rawtypes")
    static void removeResolved(EList list, Object value)
    {
        java.util.Iterator it = list.iterator();
        while (it.hasNext())
        {
            Object o = it.next();
            boolean match = (o == value);
            if (!match && o instanceof EObject && ((EObject) o).eIsProxy() && value instanceof EObject)
            {
                match = org.eclipse.emf.ecore.util.EcoreUtil.resolve((EObject) o, (EObject) value) == value;
            }
            if (match)
            {
                it.remove();
                return;
            }
        }
    }

    // ---- J2b: FunctionalOptions on form items ------------------------------
    // <functionalOptions>FunctionalOption.X</functionalOptions> gates a form
    // item's visibility. In the EDT form model this feature exists ONLY on form
    // ATTRIBUTES (AbstractFormAttribute) and form COMMANDS (FormCommand) - never
    // on visual items (fields, tables, buttons, groups, decorations). It is a
    // multi-valued EReference with no setter, mutated via the live EList exactly
    // like FunctionalOption.content (J2 above).

    static java.lang.reflect.Method findSingleArgSetter(Class<?> clazz, String setterName)
    {
        for (java.lang.reflect.Method m : clazz.getMethods())
        {
            if (m.getName().equals(setterName) && m.getParameterCount() == 1)
            {
                return m;
            }
        }
        return null;
    }

    /**
     * Resolves a scalar-reference target FQN to an object inside a BM
     * transaction. A top-level FQN ({@code Type.Name}) resolves directly via
     * {@code tx.getTopObjectByFqn}. A child FQN
     * ({@code Type.Name.Kind.Child} - e.g.
     * {@code Task.X.AddressingAttribute.Y} for a task's mainAddressingAttribute
     * or {@code InformationRegister.X.Dimension.Y} for an addressingDimension)
     * resolves the top object from the first two segments, then navigates the
     * {@code (kind, name)} tail with {@link BmObjectHelper#resolveChildByPath}.
     * The tail is only attempted when its first kind segment is a recognized
     * child kind, so a plain {@code Type.Name} that simply does not exist still
     * returns {@code null} rather than being mistaken for a child path.
     *
     * @param tx the active BM transaction
     * @param normFqn the normalized target FQN
     * @return the resolved top-level or child object, or {@code null}
     */
    static Object resolveReferenceTarget(IBmTransaction tx, String normFqn)
    {
        if (normFqn == null)
        {
            return null;
        }
        Object top = tx.getTopObjectByFqn(normFqn);
        if (top != null)
        {
            return top;
        }
        String[] segs = normFqn.split("\\."); //$NON-NLS-1$
        if (segs.length > 2 && (segs.length % 2) == 0 && BmObjectHelper.isChildKind(segs[2]))
        {
            Object parent = tx.getTopObjectByFqn(segs[0] + "." + segs[1]); //$NON-NLS-1$
            if (parent instanceof EObject)
            {
                return BmObjectHelper.resolveChildByPath((EObject)parent, segs, 2);
            }
        }
        return null;
    }

    /**
     * Invokes a single-argument setter, translating reflection failures into a
     * RuntimeException carrying a readable cause message (so
     * {@code executeWriteOnObject} surfaces "setX() ... threw: &lt;reason&gt;"
     * instead of a bare null). {@code arg} may be {@code null} to clear a
     * reference; it is always passed as exactly one argument.
     */
    static void invokeSetterClearly(java.lang.reflect.Method setter, Object target,
        Object arg, String property)
    {
        try
        {
            setter.invoke(target, new Object[] { arg });
        }
        catch (java.lang.reflect.InvocationTargetException ite)
        {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            throw new RuntimeException(setter.getName() + "() for property '" + property //$NON-NLS-1$
                + "' threw: " + (cause.getMessage() != null ? cause.getMessage() //$NON-NLS-1$
                    : cause.getClass().getSimpleName()), cause);
        }
        catch (IllegalAccessException iae)
        {
            throw new RuntimeException(setter.getName() + "() not accessible for property '" //$NON-NLS-1$
                + property + "'", iae); //$NON-NLS-1$
        }
    }


    /**
     * Optional per-attribute "common feature" properties that
     * add_object_attribute / add_tabular_section_attribute apply in the same
     * call, so a fully specified attribute costs one round-trip instead of a
     * create plus a setObjectProperty per property. Each maps to a direct EMF
     * setter (or localized EMap) reached through {@link BmObjectHelper#setProperty}:
     * <ul>
     *   <li>fillChecking - FillChecking enum (DontCheck | ShowError)</li>
     *   <li>fullTextSearch - FullTextSearchUsing enum (DontUse | Use)</li>
     *   <li>indexing - Indexing enum (DontIndex | Index | IndexWithAdditionalOrder)</li>
     *   <li>toolTip - localized EMap, stored under "ru"</li>
     *   <li>comment - plain String</li>
     * </ul>
     * fillValue is intentionally excluded: it is an mcore.Value EObject
     * (reference fill values need EmptyRef resolution), not a coercible scalar.
     */
    private static final String[] ATTRIBUTE_FEATURE_PROPERTIES =
        { "fillChecking", "fullTextSearch", "indexing", "toolTip", "comment" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /**
     * Applies the supplied {@link #ATTRIBUTE_FEATURE_PROPERTIES} to an attribute
     * inside the active BM transaction, mirroring how multiLine is applied. A
     * property is touched only when the caller actually provided it. Successful
     * names are collected into {@code applied} (surfaced as an
     * {@code appliedProperties} tag); a setter failure (e.g. an invalid enum
     * literal) is non-fatal - it is logged and recorded in {@code failed}
     * (surfaced as a {@code failedProperties} tag) so the caller is not left
     * with a silent partial success. Used both for a freshly created attribute
     * and, on the idempotent path, for an already-existing one (declarative
     * property reconciliation).
     */
    static void applyAttributeFeatureProperties(EObject attribute,
        Map<String, String> params, String opName,
        List<String> applied, Map<String, String> failed)
    {
        if (attribute == null || params == null)
        {
            return;
        }
        for (String prop : ATTRIBUTE_FEATURE_PROPERTIES)
        {
            if (!params.containsKey(prop))
            {
                continue;
            }
            String value = JsonUtils.extractStringArgument(params, prop);
            if (value == null)
            {
                continue;
            }
            String setErr = BmObjectHelper.setProperty(attribute, prop, value);
            if (setErr == null)
            {
                applied.add(prop);
            }
            else
            {
                failed.put(prop, setErr);
                Activator.logWarning(opName + ": " + prop + "='" + value //$NON-NLS-1$ //$NON-NLS-2$
                    + "' not applied: " + setErr); //$NON-NLS-1$
            }
        }
    }









    // -----------------------------------------------------------------------
    // 1.42 (RSV 4.2 parity): object commands
    // -----------------------------------------------------------------------


    /**
     * 1.42 helper: invokes a setter named {@code setterName} with a single
     * String argument when the supplied value is non-null and the setter
     * exists. Silent no-op for absent setters - callers pass optional fields
     * (synonym, tooltip, picture, commandParameterType) without checking
     * each one against EDT's class hierarchy.
     */
    static void applyOptionalString(MdObject target, String setterName, String value)
    {
        if (value == null || value.isEmpty())
        {
            return;
        }
        try
        {
            target.getClass().getMethod(setterName, String.class).invoke(target, value);
        }
        catch (NoSuchMethodException ignored)
        {
            // Setter not present on this MdObject subclass - leave default.
        }
        catch (Exception e)
        {
            Activator.logWarning(setterName + " on " + target.eClass().getName() //$NON-NLS-1$
                + " failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Generates a synonym from a metadata identifier the way the EDT attribute
     * wizard does: split CamelCase into words, keep the first word as-is,
     * lowercase the first letter of each following ordinary word, and preserve
     * acronyms (runs of capitals such as "ЭП" / "НДС"). Examples:
     * "СуммаДокумента" -> "Сумма документа", "АдресЭП" -> "Адрес ЭП",
     * "НомерТелефонаБезКодов" -> "Номер телефона без кодов".
     * <p>Limitation (same as the EDT wizard): two adjacent acronyms cannot be
     * separated without a dictionary - e.g. "НДСЭП" stays "НДСЭП".
     */
    static String generateSynonymFromName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (i > 0 && Character.isUpperCase(c))
            {
                char prev = name.charAt(i - 1);
                char next = (i + 1 < name.length()) ? name.charAt(i + 1) : '\0';
                boolean wordStart = Character.isLowerCase(prev)
                    || (Character.isUpperCase(prev) && next != '\0' && Character.isLowerCase(next));
                if (wordStart)
                {
                    sb.append(' ');
                    // Lowercase the first letter of an ordinary word; keep the
                    // capital when it starts an acronym (next char also capital).
                    if (next != '\0' && Character.isLowerCase(next))
                    {
                        sb.append(Character.toLowerCase(c));
                    }
                    else
                    {
                        sb.append(c);
                    }
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Records the synonym outcome (see {@link #applyMdObjectSynonym}) into the
     * result tags of a write-on-object operation so the agent sees what really
     * happened instead of guessing after a best-effort skip. Idempotent: when
     * {@code sr} is {@code null} or {@link SynonymResult#skipped() skipped} this
     * is a no-op (covers the idempotent-existing-attribute case).
     */
    static void addSynonymTags(BmObjectHelper.Result r, SynonymResult sr)
    {
        if (r == null || !r.ok || sr == null)
        {
            return;
        }
        if (sr.applied)
        {
            r.tags.put("synonym", sr.value); //$NON-NLS-1$
            r.tags.put("synonymApplied", true); //$NON-NLS-1$
        }
        else if (sr.error != null)
        {
            r.tags.put("synonymApplied", false); //$NON-NLS-1$
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("reason", sr.error); //$NON-NLS-1$
            r.tags.put("synonymNotSet", reason); //$NON-NLS-1$
        }
    }

    /**
     * Outcome of {@link #applyMdObjectSynonym}: exposes to the caller what
     * actually happened so the tool can surface it to the agent (no silent skip).
     */
    static final class SynonymResult
    {
        /** {@code true} when {@link MdObject#getSynonym()} accepted the value. */
        final boolean applied;
        /** Applied value (explicit or auto-generated). {@code null} when skipped or error. */
        final String value;
        /** Exception summary when the setter failed. {@code null} otherwise. */
        final String error;

        private SynonymResult(boolean applied, String value, String error)
        {
            this.applied = applied;
            this.value = value;
            this.error = error;
        }
        static SynonymResult skipped() { return new SynonymResult(false, null, null); }
        static SynonymResult ok(String value) { return new SynonymResult(true, value, null); }
        static SynonymResult error(String error) { return new SynonymResult(false, null, error); }
    }

    /**
     * Fills the {@code synonym} (EMap&lt;lang,text&gt;) of a freshly created
     * metadata object (attribute, EventSubscription, Catalog, ...): explicit
     * value when supplied, otherwise auto-generated from the name like the EDT
     * wizard. Language is the configuration default, falling back to {@code ru}.
     * Best-effort - a setter failure (e.g. a type that has no synonym) is logged,
     * never fatal. Returns a {@link SynonymResult} so callers can surface the
     * outcome to the agent instead of letting it be silently lost.
     */
    /**
     * True when {@code mdObject} has no usable synonym (null/empty map, or every
     * localization value blank). Used on idempotent retry paths to decide whether
     * an OMITTED synonym may be auto-generated: a blank existing synonym can be
     * backfilled (picks up fields created by an earlier synonym-less build), but
     * a non-blank one must be preserved so a retry never silently overwrites a
     * manually customized synonym. An explicitly passed synonym is always applied
     * (update intent) regardless of this check.
     */
    static boolean synonymIsBlank(MdObject mdObject)
    {
        if (mdObject == null)
        {
            return true;
        }
        Map<String, String> syn = mdObject.getSynonym().map();
        if (syn == null || syn.isEmpty())
        {
            return true;
        }
        for (String v : syn.values())
        {
            if (v != null && !v.trim().isEmpty())
            {
                return false;
            }
        }
        return true;
    }

    static SynonymResult applyMdObjectSynonym(MdObject mdObject, String explicitSynonym,
        String name, IProject project)
    {
        IConfigurationProvider cp = Activator.getDefault().getConfigurationProvider();
        Configuration config = cp != null ? cp.getConfiguration(project) : null;
        String trimmed = (explicitSynonym != null) ? explicitSynonym.trim() : null;
        // Multi-language synonym: a JSON object {"ru":"...","en":"..."} REPLACES
        // the whole synonym map (one entry per language code). Any non-object or
        // unparseable value falls through to the single-language path below.
        if (trimmed != null && trimmed.length() > 1
            && trimmed.charAt(0) == '{' && trimmed.charAt(trimmed.length() - 1) == '}')
        {
            try
            {
                com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(trimmed)
                    .getAsJsonObject();
                if (obj.size() > 0)
                {
                    mdObject.getSynonym().clear();
                    StringBuilder applied = new StringBuilder();
                    for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet())
                    {
                        String code = e.getKey() != null ? e.getKey().trim() : null;
                        if (code == null || code.isEmpty())
                        {
                            continue;
                        }
                        String val = e.getValue().isJsonNull() ? "" : e.getValue().getAsString(); //$NON-NLS-1$
                        mdObject.getSynonym().put(code, val);
                        if (applied.length() > 0)
                        {
                            applied.append(", "); //$NON-NLS-1$
                        }
                        applied.append(code).append('=').append(val);
                    }
                    return SynonymResult.ok(applied.toString());
                }
            }
            catch (Exception jsonEx)
            {
                // Not a JSON object synonym - treat the literal string as the
                // single-language synonym value below.
            }
        }
        String synonymValue;
        if (trimmed != null && !trimmed.isEmpty())
        {
            synonymValue = trimmed;
        }
        else
        {
            // Strip Configuration.namePrefix (extension prefix like "ВТ_" / "ПСБ_")
            // before auto-generating the synonym - matches the EDT wizard, which
            // would otherwise emit "ВТ_При записи..." instead of "При записи...".
            String nameForGen = name;
            if (config != null && name != null)
            {
                String prefix = config.getNamePrefix();
                if (prefix != null && !prefix.isEmpty()
                    && name.startsWith(prefix) && name.length() > prefix.length())
                {
                    nameForGen = name.substring(prefix.length());
                }
            }
            synonymValue = generateSynonymFromName(nameForGen);
        }
        if (synonymValue == null || synonymValue.isEmpty())
        {
            return SynonymResult.skipped();
        }
        // The synonym EMap is keyed by the language CODE (e.g. "ru"), not the
        // language object's name. In configurations where the Language object is
        // named differently from its code (e.g. name "Русский" / code "ru") using
        // getName() produced a dangling synonym under a non-existent language.
        String lang = "ru"; //$NON-NLS-1$
        if (config != null && config.getDefaultLanguage() != null)
        {
            String code = config.getDefaultLanguage().getLanguageCode();
            lang = (code != null && !code.isEmpty()) ? code
                : config.getDefaultLanguage().getName();
        }
        try
        {
            mdObject.getSynonym().put(lang, synonymValue);
            return SynonymResult.ok(synonymValue);
        }
        catch (Exception synEx)
        {
            String summary = synEx.getClass().getSimpleName() + ": " //$NON-NLS-1$
                + (synEx.getMessage() != null ? synEx.getMessage() : ""); //$NON-NLS-1$
            Activator.logWarning("synonym not set: " + summary); //$NON-NLS-1$
            return SynonymResult.error(summary);
        }
    }

    // -----------------------------------------------------------------------
    // Specialized operations (1.37)
    // -----------------------------------------------------------------------





    /**
     * Prefix BmFormHelper prepends to an "Error:" preview when a dry run rolls the action back,
     * turning {@code "Error: ..."} into {@code "Dry run - action would FAIL: Error: ... (...)"}. A dry
     * run that predicts failure is an error outcome, not a success, so both this prefix and a bare
     * {@code "Error:"} are treated as failure by {@link #isErrorOutcome} / {@link #stripErrorEnvelope}.
     */
    private static final String DRY_RUN_FAIL_PREFIX = "Dry run - action would FAIL:"; //$NON-NLS-1$

    /**
     * 1.41: variant of {@link #formatFormResult} that recognises the
     * {@code formApiNotFound:} prefix used by BmFormHelper helpers when
     * the EDT factory method is missing, and surfaces it as a structured
     * tag instead of a generic error string.
     */
    static String formatFormResultWithApiTag(String helperResult, String op, String formFqn)
    {
        String body = stripErrorEnvelope(helperResult);
        if (body == null)
        {
            return formatFormResult(helperResult, op, formFqn);
        }
        int idx = body.indexOf("formApiNotFound:"); //$NON-NLS-1$
        if (idx < 0)
        {
            return formatFormResult(helperResult, op, formFqn);
        }
        String missing = body.substring(idx + "formApiNotFound:".length()).trim(); //$NON-NLS-1$
        // 1.41: trim trailing closing parens that come from upstream
        // "(cause: ...)" wrapping so the structured tag stays clean.
        while (missing.endsWith(")") && //$NON-NLS-1$
            missing.length() - missing.replace("(", "").length() //$NON-NLS-1$ //$NON-NLS-2$
                < missing.length() - missing.replace(")", "").length()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            missing = missing.substring(0, missing.length() - 1).trim();
        }
        java.util.Map<String, Object> tag = new java.util.LinkedHashMap<>();
        tag.put("missingFactory", missing); //$NON-NLS-1$
        tag.put("hint", //$NON-NLS-1$
            "EDT 2026.1 does not expose this factory method. Use the EDT GUI " //$NON-NLS-1$
                + "form editor or wait for a later EDT release."); //$NON-NLS-1$
        return ToolResult.error(op + " failed: " + body) //$NON-NLS-1$
            .put("operation", op) //$NON-NLS-1$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put(ErrorTags.FORM_API_NOT_FOUND.wire(), tag)
            .toJson();
    }


    static String formatFormResult(String helperResult, String op, String formFqn)
    {
        // Row 42 note: a pending/failed disk flush is appended to helperResult as
        // a plain-text note by BmFormHelper.executeFormOperation, so it flows
        // through the "message" field here (and at every other form-op response
        // builder) with no special handling.
        if (helperResult == null)
        {
            return ToolResult.success()
                .put("operation", op) //$NON-NLS-1$
                .put("formFqn", formFqn) //$NON-NLS-1$
                .put("message", "ok") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        if (isErrorOutcome(helperResult))
        {
            return ToolResult.error(op + " failed: " + stripErrorEnvelope(helperResult)) //$NON-NLS-1$
                .put("operation", op) //$NON-NLS-1$
                .put("formFqn", formFqn) //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("operation", op) //$NON-NLS-1$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("message", helperResult) //$NON-NLS-1$
            .toJson();
    }

    /**
     * True when the helper result signals failure - either a direct {@code "Error:"} or a dry-run
     * preview that BmFormHelper rewrote as {@code "Dry run - action would FAIL: Error: ..."}. A plain
     * {@code "Dry run: form operation previewed..."} (the success preview) does not match and stays a
     * success.
     */
    private static boolean isErrorOutcome(String s)
    {
        return s != null && (s.startsWith("Error:") || s.startsWith(DRY_RUN_FAIL_PREFIX)); //$NON-NLS-1$
    }

    /**
     * Strips the error envelope - a bare {@code "Error:"} or the dry-run wrapper around it - so the
     * inner body (which may carry {@code formApiNotFound:}) is reachable. Returns {@code null} when the
     * input is not an error outcome.
     */
    private static String stripErrorEnvelope(String s)
    {
        if (s == null)
        {
            return null;
        }
        String body = s;
        boolean error = false;
        if (body.startsWith(DRY_RUN_FAIL_PREFIX))
        {
            body = body.substring(DRY_RUN_FAIL_PREFIX.length()).trim();
            error = true;
        }
        if (body.startsWith("Error:")) //$NON-NLS-1$
        {
            body = body.substring("Error:".length()).trim(); //$NON-NLS-1$
            error = true;
        }
        return error ? body : null;
    }

    @SuppressWarnings("unchecked")
    static EList<MdObject> invokeListGetter(MdObject obj, String methodName)
    {
        try
        {
            java.lang.reflect.Method m = obj.getClass().getMethod(methodName);
            Object v = m.invoke(obj);
            if (v instanceof EList)
            {
                return (EList<MdObject>) v;
            }
        }
        catch (Exception ignored)
        {
            // The receiver is an Object whose shape this helper does not control, so a
            // missing member is an answer, not a failure - the caller reads the null as
            // "this element has no such property".
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Help and utilities
    // -----------------------------------------------------------------------

    private String handleHelp(Map<String, String> params)
    {
        String topic = JsonUtils.extractStringArgument(params, "topic"); //$NON-NLS-1$
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder("# edit_metadata\n\n"); //$NON-NLS-1$
            sb.append("Single constructor across 7 operation groups. ") //$NON-NLS-1$
                .append("Pass `operation=<name>` plus operation-specific arguments. ") //$NON-NLS-1$
                .append("Add `dryRun=true` to preview changes inside a BM transaction.\n\n"); //$NON-NLS-1$
            sb.append("**Status (1.43.0):** 7 operation groups + 1.42 RSV 4.2 parity ops. ") //$NON-NLS-1$
                .append("Object enhancements (propertyMismatch idempotency, cascade form cleanup) ") //$NON-NLS-1$
                .append("plus 4 defensive layers (3.8.1-3.8.4) for headless metadata creation. ") //$NON-NLS-1$
                .append("1.42.5 fixes: canonical primitive Type proxy via IEObjectProvider, ") //$NON-NLS-1$
                .append("create_form writes Form.form/Module.bsl on disk, full TypeDescription ") //$NON-NLS-1$
                .append("qualifier wiring (length/precision/fractionDigits/dateFractions/nonNegative). ") //$NON-NLS-1$
                .append("1.43 deferred-block work: deep Type resolution in in-session BM, ") //$NON-NLS-1$
                .append("MXL cell mutation persistence to .mxlx, extension top-level form test.\n\n"); //$NON-NLS-1$

            sb.append(buildOperationCatalogHelp());

            sb.append("\n## Topics\n\n"); //$NON-NLS-1$
            sb.append("- `topic=workflow` - typical createObject -> addObjectAttribute -> Form workflow\n"); //$NON-NLS-1$
            sb.append("- `topic=types` - English-singular metadata type names\n"); //$NON-NLS-1$
            sb.append("- `topic=availability` - which operation groups are wired vs deferred\n"); //$NON-NLS-1$
            sb.append("- `topic=composerWorkflow` - setupSettingsComposerOnForm scenario for reports\n"); //$NON-NLS-1$
            sb.append("- `topic=matrixWorkflow` - matrix-style report scenario (rows x columns)\n"); //$NON-NLS-1$
            sb.append("- `topic=errorTags` - structured error tags reference (1.37)\n"); //$NON-NLS-1$
            sb.append("- `topic=parameters` - the full rules of the parameters whose " //$NON-NLS-1$
                + "schema description is one line\n"); //$NON-NLS-1$
            sb.append("- `topic=<operation>` - the parameters that one operation actually reads, " //$NON-NLS-1$
                + "derived from its handler\n"); //$NON-NLS-1$
            return ToolResult.success().put("help", sb.toString()).toJson(); //$NON-NLS-1$
        }
        switch (topic.toLowerCase())
        {
            case "workflow": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildWorkflowHelp()).toJson(); //$NON-NLS-1$
            case "types": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildTypesHelp()).toJson(); //$NON-NLS-1$
            case "availability": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildAvailabilityHelp()).toJson(); //$NON-NLS-1$
            case "composerworkflow": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildComposerWorkflowHelp()).toJson(); //$NON-NLS-1$
            case "matrixworkflow": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildMatrixWorkflowHelp()).toJson(); //$NON-NLS-1$
            case "errortags": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildErrorTagsHelp()).toJson(); //$NON-NLS-1$
            case "parameters": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", parametersHelp()).toJson(); //$NON-NLS-1$
            default:
                // An operation name is a topic too, and the most useful one: a caller about to make
                // a call wants the handful of parameters THAT operation reads, not the hundred the
                // schema lists for every operation this facade accepts.
                java.util.List<String> parameters = ru.aiedt.mcp.server.support.OperationParameters
                    .of("EditMetadataTool", topic.toLowerCase()); //$NON-NLS-1$
                if (!parameters.isEmpty())
                {
                    return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                        .put("operation", topic.toLowerCase()) //$NON-NLS-1$
                        .put("parameters", parameters) //$NON-NLS-1$
                        .put("text", "Parameters read by operation=" + topic.toLowerCase() //$NON-NLS-1$ //$NON-NLS-2$
                            + ": " + String.join(", ", parameters) //$NON-NLS-1$ //$NON-NLS-2$
                            + ". Derived from the handler's own source, so it is what the operation " //$NON-NLS-1$
                            + "actually reads rather than what the shared schema advertises.") //$NON-NLS-1$
                        .toJson();
                }
                return ToolResult.error("Unknown topic: " + topic //$NON-NLS-1$
                    + ". Available: workflow, types, availability, composerWorkflow, " //$NON-NLS-1$
                    + "matrixWorkflow, errorTags, parameters - or the name of any operation, " //$NON-NLS-1$
                    + "which answers with the parameters that operation reads.") //$NON-NLS-1$
                    .toJson();
        }
    }

    private String buildComposerWorkflowHelp()
    {
        return "Set up a settings composer on a Report form (the recommended way to expose " //$NON-NLS-1$
            + "DCS settings to end users).\n\n" //$NON-NLS-1$
            + "1. Create the report and its DCS schema:\n" //$NON-NLS-1$
            + "   - edit_metadata operation=createObject objectType=Report name=Sales\n" //$NON-NLS-1$
            + "   - dcs_workshop operation=create_schema objectName=Report.Sales\n" //$NON-NLS-1$
            + "2. Build the schema content (datasets, parameters, calc fields):\n" //$NON-NLS-1$
            + "   - dcs_workshop operation=add_dataset objectName=Report.Sales name=Main \\\n" //$NON-NLS-1$
            + "       queryText=\"VYBRAT * IZ Document.Realizatsiya\"\n" //$NON-NLS-1$
            + "   - dcs_workshop operation=add_calculated_field name=Total expression=\"Sum * Qty\"\n" //$NON-NLS-1$
            + "3. Create the form and wire the composer:\n" //$NON-NLS-1$
            + "   - edit_metadata operation=createForm ownerFqn=Report.Sales formType=Form\n" //$NON-NLS-1$
            + "   - edit_metadata operation=setupSettingsComposerOnForm \\\n" //$NON-NLS-1$
            + "       formFqn=Report.Sales.Forms.Form\n" //$NON-NLS-1$
            + "4. Optionally pre-fill default settings:\n" //$NON-NLS-1$
            + "   - dcs_workshop operation=add_grouping field=Manager groupingType=Standard\n" //$NON-NLS-1$
            + "   - dcs_workshop operation=add_filter field=Period comparisonType=Between\n"; //$NON-NLS-1$
    }

    private String buildMatrixWorkflowHelp()
    {
        return "Build a matrix report (rows x columns x values) using DCS structure.\n\n" //$NON-NLS-1$
            + "1. Create the schema and dataset.\n" //$NON-NLS-1$
            + "2. Add a calculated total: dcs_workshop add_total expression=Quantity \\\n" //$NON-NLS-1$
            + "       aggregateFunction=Sum\n" //$NON-NLS-1$
            + "3. Build the structure - one root with a nested table:\n" //$NON-NLS-1$
            + "   - dcs_workshop add_grouping field=Product groupingType=Standard\n" //$NON-NLS-1$
            + "   - dcs_workshop add_settings_table field=Period (deferred to 1.37+)\n" //$NON-NLS-1$
            + "4. Apply conditional appearance for highlighting:\n" //$NON-NLS-1$
            + "   - dcs_workshop add_appearance conditionType=Greater conditionValue=1000 \\\n" //$NON-NLS-1$
            + "       appearance=\"BackColor=#FFFF00\"\n" //$NON-NLS-1$
            + "5. Form: setupSettingsComposerOnForm; the matrix renders automatically.\n"; //$NON-NLS-1$
    }

    private String buildErrorTagsHelp()
    {
        return "Structured error tags surfaced in the JSON response (1.37).\n\n" //$NON-NLS-1$
            + "Top-level fields next to `error`:\n" //$NON-NLS-1$
            + "- `supportLock` { target, ownerType, userSupportMode, discoveredApi, hint } -\n" //$NON-NLS-1$
            + "    object is on vendor support; use an extension instead.\n" //$NON-NLS-1$
            + "- `standardAttributeConflict` { name, conflictsWith, ownerType, source } -\n" //$NON-NLS-1$
            + "    candidate name shadows a platform-standard attribute. Pick another name.\n" //$NON-NLS-1$
            + "- `alreadyExists` { name, ownerFqn, kind } - the child is already present.\n" //$NON-NLS-1$
            + "- `notFound` { name, ownerFqn, kind } - target child does not exist.\n" //$NON-NLS-1$
            + "- `queryValidation` { issues, statistics } - QL/DCS query has parse errors.\n" //$NON-NLS-1$
            + "- `expressionValidation` { issues, statistics } - DCS expression has parse errors.\n" //$NON-NLS-1$
            + "- `fontColorGuard` { appearance, hint } - JSON object/array passed where a string\n" //$NON-NLS-1$
            + "    was expected (use 'Arial,12,bold' / '#RRGGBB' instead).\n" //$NON-NLS-1$
            + "- `autoBorrowed` [fqn, ...] - extension auto-borrowed objects (the owner, its tabular " //$NON-NLS-1$
            + "section, and any referenced metadata targets)\n" //$NON-NLS-1$
            + "    (success path).\n\n" //$NON-NLS-1$
            + "AI agent pattern: branch on `response.alreadyExists` etc. instead of parsing\n" //$NON-NLS-1$
            + "the human-readable `error` text.\n"; //$NON-NLS-1$
    }

    private String buildWorkflowHelp()
    {
        return "1. createObject objectType=Catalog name=Products\n" //$NON-NLS-1$
            + "2. addObjectAttribute ownerFqn=Catalog.Products name=Article\n" //$NON-NLS-1$
            + "3. addTabularSection ownerFqn=Catalog.Products name=Specifications\n" //$NON-NLS-1$
            + "4. addTabularSectionAttribute ownerFqn=Catalog.Products tabularSectionName=Specifications name=Quantity\n" //$NON-NLS-1$
            + "5. setObjectProperty ownerFqn=Catalog.Products propertyName=Synonym propertyValue=Products\n"; //$NON-NLS-1$
    }

    private String buildTypesHelp()
    {
        return "Use English-singular type names: Catalog, Document, ChartOfAccounts, " //$NON-NLS-1$
            + "ChartOfCharacteristicTypes, ChartOfCalculationTypes, BusinessProcess, Task, " //$NON-NLS-1$
            + "ExchangePlan, DataProcessor, Report, InformationRegister, AccumulationRegister, " //$NON-NLS-1$
            + "AccountingRegister, CalculationRegister, Enum, CommonModule, CommonForm, " //$NON-NLS-1$
            + "Subsystem, Role, FunctionalOption, DefinedType, EventSubscription. " //$NON-NLS-1$
            + "Russian equivalents auto-resolve via MetadataTypeCatalog."; //$NON-NLS-1$
    }

    private String buildAvailabilityHelp()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Object group (15): implemented in 1.33, enhanced in 1.40 (propertyMismatch, cascade form cleanup) + 1.43.x A2 (list-valued references / movements, set_object_type) + scalar references (set_object_reference / clear_object_reference).\n"); //$NON-NLS-1$
        sb.append("Specialized group (16): implemented in 1.40 (BmRightsHelper, BmDefinedTypeHelper, BmSubsystemHelper, BmEventSubscriptionHelper) + HTTP/Web service ops + 1.43.x A3 (Task addressing attributes, ChartOfAccounts accounting flags, CalculationRegister recalculations, predefined data items).\n"); //$NON-NLS-1$
        sb.append("Form constructor (19): implemented in 1.40 (replaces edit_form; FormBaseSetup 11 base props for Generic+empty).\n"); //$NON-NLS-1$
        sb.append("Template group (4): implemented in 1.40. Spreadsheet API present? ") //$NON-NLS-1$
            .append(BmTemplateHelper.isAvailable()).append("\n"); //$NON-NLS-1$
        sb.append("Extension group (5): implemented in 1.40. Adopt service present? ") //$NON-NLS-1$
            .append(BmExtensionHelper.isAvailable()).append("\n"); //$NON-NLS-1$
        sb.append("DCS group (27): implemented in 1.40 (replaces dcs_workshop spike). DCS API present? ") //$NON-NLS-1$
            .append(BmDcsHelper.isAvailable()).append("\n"); //$NON-NLS-1$
        sb.append("Rights API present (for setRoleRight)? ") //$NON-NLS-1$
            .append(ru.aiedt.mcp.server.support.BmRightsHelper.isAvailable()).append("\n"); //$NON-NLS-1$
        sb.append("Common group (2): move_item moves a form item between containers; " //$NON-NLS-1$
            + "remove_item routes by FQN shape to the typed remove operation.\n"); //$NON-NLS-1$
        sb.append("\nDefensive layers (1.40):\n"); //$NON-NLS-1$
        sb.append("- 3.8.1 EventSubscription handler auto-prefix CommonModule.\n"); //$NON-NLS-1$
        sb.append("- 3.8.2 Extension CommonModule guards (privileged, global+server).\n"); //$NON-NLS-1$
        sb.append("- 3.8.3 Generic+empty form 11 base properties scaffold.\n"); //$NON-NLS-1$
        sb.append("- 3.8.4 CommonForm createObject auto-creates inner form.\n"); //$NON-NLS-1$
        return sb.toString();
    }

    static String formatResult(BmObjectHelper.Result r, String op)
    {
        if (r.ok)
        {
            ToolResult result = ToolResult.success()
                .put("operation", op) //$NON-NLS-1$
                .put("ownerFqn", r.fqn) //$NON-NLS-1$
                .put("message", r.message != null ? r.message : "ok"); //$NON-NLS-1$ //$NON-NLS-2$
            applyTags(result, r.tags);
            return result.toJson();
        }
        ToolResult err = ToolResult
            .error(op + " failed: " + (r.error != null ? r.error : "unknown error")) //$NON-NLS-1$ //$NON-NLS-2$
            .put("operation", op) //$NON-NLS-1$
            .put("ownerFqn", r.fqn); //$NON-NLS-1$
        applyTags(err, r.tags);
        return err.toJson();
    }

    static void applyTags(ToolResult result, Map<String, Object> tags)
    {
        if (result == null || tags == null || tags.isEmpty())
        {
            return;
        }
        for (Map.Entry<String, Object> entry : tags.entrySet())
        {
            result.put(entry.getKey(), entry.getValue());
        }
    }

    static String requireNonEmpty(String value, String paramName)
    {
        if (value == null || value.isEmpty())
        {
            return paramName + " is required. "; //$NON-NLS-1$
        }
        return ""; //$NON-NLS-1$
    }

    private String suggest(String op)
    {
        // Substring match is the strongest signal (partial name / extra
        // qualifier); otherwise fall back to the closest operation by
        // Levenshtein distance, and suggest nothing for gibberish instead of a
        // random first-by-alphabet entry (which misled agents on a typo).
        String lower = op.toLowerCase();
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String known : registry.keySet())
        {
            String k = known.toLowerCase();
            if (k.contains(lower) || lower.contains(k))
            {
                return known;
            }
            int d = levenshtein(lower, k);
            if (d < bestDist)
            {
                bestDist = d;
                best = known;
            }
        }
        // Only propose when reasonably close (threshold scales with the typed
        // length); otherwise no guess.
        int threshold = Math.max(3, lower.length() / 2);
        return (best != null && bestDist <= threshold) ? best : "(none)"; //$NON-NLS-1$
    }

    /**
     * Levenshtein edit distance (iterative two-row), used by {@link #suggest}
     * to map a mistyped operation name to the closest known one.
     */
    private static int levenshtein(String a, String b)
    {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++)
        {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++)
        {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++)
            {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    /**
     * Builds the single-source operation registry (op name -> group + help +
     * handler). Registered in the help-catalog group order; each handler is the
     * exact call the former dispatch switch made. Aliases (add_form_command /
     * add_command_handler, set_property / set_form_item_property, remove_item /
     * remove_item_universal) are separate entries sharing one handler. DCS and
     * Extensions are uniform delegates.
     */
    @SuppressWarnings("nls")
    private Map<String, OpEntry> buildRegistry()
    {
        Map<String, OpEntry> m = new LinkedHashMap<>();

        // ---- Objects (20) ----
        reg(m, "create_object", "Objects", "", p -> objectOps.opCreateObject(p));
        reg(m, "set_object_property", "Objects", "", p -> objectOps.opSetObjectProperty(p));
        reg(m, "add_object_attribute", "Objects", "", p -> objectOps.opAddObjectAttribute(p));
        reg(m, "remove_object_attribute", "Objects", "", p -> objectOps.opRemoveObjectAttribute(p));
        reg(m, "add_tabular_section", "Objects", "", p -> objectOps.opAddTabularSection(p));
        reg(m, "remove_tabular_section", "Objects", "", p -> objectOps.opRemoveTabularSection(p));
        reg(m, "add_tabular_section_attribute", "Objects", "", p -> objectOps.opAddTabularSectionAttribute(p));
        reg(m, "remove_tabular_section_attribute", "Objects", "", p -> objectOps.opRemoveTabularSectionAttribute(p));
        reg(m, "add_object_reference", "Objects", "list-valued refs / movements", p -> objectOps.opObjectReference(p, true));
        reg(m, "remove_object_reference", "Objects", "", p -> objectOps.opObjectReference(p, false));
        reg(m, "set_object_reference", "Objects", "scalar ref, e.g. ChartOfAccounts.extDimensionTypes", p -> objectOps.opSetObjectReference(p, true));
        reg(m, "clear_object_reference", "Objects", "", p -> objectOps.opSetObjectReference(p, false));
        reg(m, "set_object_type", "Objects", "Constant / SessionParameter", p -> objectOps.opSetObjectType(p));
        reg(m, "extend_object_type", "Objects", "adds types to an ADOPTED object, marked Extended; set_object_type writes nothing there", p -> objectOps.opExtendObjectType(p));
        reg(m, "remove_object", "Objects", "", p -> objectOps.opRemoveObject(p));
        reg(m, "sync_export", "Objects", "", p -> specializedOps.opSyncExport(p));
        reg(m, "set_help", "Objects", "author Help/<lang>.html + <help> ref; format=html|markdown|text", p -> specializedOps.opSetHelp(p, false));
        reg(m, "remove_help", "Objects", "", p -> specializedOps.opSetHelp(p, true));
        reg(m, "delete_metadata_object", "Objects", "delegates to the standalone delete_metadata_object (preset-gated)", p -> {
            String gate = ToolGate.gateOrNull("delete_metadata_object");
            return gate != null ? gatedRejectJson("delete_metadata_object", gate) : new MetadataObjectDeleter().execute(p);
        });
        reg(m, "rename_metadata_object", "Objects", "delegates to the standalone rename_metadata_object (preset-gated)", p -> {
            String gate = ToolGate.gateOrNull("rename_metadata_object");
            return gate != null ? gatedRejectJson("rename_metadata_object", gate)
                : convertRenamerMarkdownToJson(new MetadataObjectRenamer().execute(p), "rename_metadata_object");
        });
        reg(m, "add_metadata_attribute", "Objects", "delegates to the standalone add_metadata_attribute (preset-gated)", p -> {
            String gate = ToolGate.gateOrNull("add_metadata_attribute");
            return gate != null ? gatedRejectJson("add_metadata_attribute", gate) : new AttributeAdder().execute(p);
        });

        // ---- Specialized (29) ----
        reg(m, "add_register_field", "Specialized", "", p -> specializedOps.opAddRegisterField(p));
        reg(m, "remove_register_field", "Specialized", "", p -> specializedOps.opRemoveRegisterField(p));
        reg(m, "add_enum_value", "Specialized", "", p -> specializedOps.opAddEnumValue(p));
        reg(m, "add_addressing_attribute", "Specialized", "Task", p -> specializedOps.opAddAddressingAttribute(p));
        reg(m, "add_accounting_flag", "Specialized", "ChartOfAccounts", p -> specializedOps.opAddAccountingFlag(p));
        reg(m, "add_ext_dimension_accounting_flag", "Specialized", "ChartOfAccounts", p -> specializedOps.opAddExtDimensionAccountingFlag(p));
        reg(m, "add_predefined_account_subconto", "Specialized", "ChartOfAccounts: subconto types", p -> predefinedOps.opAddPredefinedAccountSubconto(p));
        reg(m, "remove_predefined_account_subconto", "Specialized", "", p -> predefinedOps.opRemovePredefinedAccountSubconto(p));
        reg(m, "add_recalculation", "Specialized", "CalculationRegister", p -> specializedOps.opAddRecalculation(p));
        reg(m, "add_recalculation_dimension", "Specialized", "", p -> specializedOps.opAddRecalculationDimension(p));
        reg(m, "add_predefined_item", "Specialized", "Catalog / ChartOf*", p -> predefinedOps.opAddPredefinedItem(p));
        reg(m, "add_subsystem_content", "Specialized", "Subsystem: object composition (<content>)", p -> contentOps.opAddSubsystemContent(p));
        reg(m, "remove_subsystem_content", "Specialized", "Subsystem: object composition (<content>)", p -> contentOps.opRemoveSubsystemContent(p));
        reg(m, "set_role_right", "Specialized", "", p -> roleOps.opSetRoleRight(p));
        reg(m, "set_defined_type_types", "Specialized", "", p -> specializedOps.opSetDefinedTypeTypes(p));
        reg(m, "set_restriction_template", "Specialized", "Role: named RLS condition template", p -> roleOps.opRestrictionTemplate(p, false));
        reg(m, "remove_restriction_template", "Specialized", "", p -> roleOps.opRestrictionTemplate(p, true));
        reg(m, "set_role_restriction", "Specialized", "Role: per-object per-right RLS condition", p -> roleOps.opRoleRestriction(p, false));
        reg(m, "remove_role_restriction", "Specialized", "", p -> roleOps.opRoleRestriction(p, true));
        reg(m, "add_event_subscription_handler", "Specialized", "BSL stub", p -> specializedOps.opAddEventHandler(p));
        reg(m, "set_event_subscription", "Specialized", "source + event + handler", p -> specializedOps.opSetEventSubscription(p));
        reg(m, "create_object_command", "Specialized", "", p -> specializedOps.opCreateObjectCommand(p));
        reg(m, "remove_command", "Specialized", "", p -> specializedOps.opRemoveCommand(p));
        reg(m, "add_exchange_plan_content", "Specialized", "+autoRecord", p -> contentOps.opAddExchangePlanContent(p));
        reg(m, "remove_exchange_plan_content", "Specialized", "", p -> contentOps.opRemoveExchangePlanContent(p));
        reg(m, "add_common_attribute_content", "Specialized", "+use", p -> contentOps.opAddCommonAttributeContent(p));
        reg(m, "remove_common_attribute_content", "Specialized", "", p -> contentOps.opRemoveCommonAttributeContent(p));
        reg(m, "add_functional_option_content", "Specialized", "FunctionalOption object/attribute, or FunctionalOptionsParameter use-list", p -> contentOps.opFunctionalOptionContent(p, true));
        reg(m, "remove_functional_option_content", "Specialized", "", p -> contentOps.opFunctionalOptionContent(p, false));

        // ---- Command interface (5) ----
        reg(m, "set_subsystems_order", "Command interface", "Configuration: order of subsystem sections", p -> commandInterfaceOps.opSetSubsystemsOrder(p));
        reg(m, "set_subsystem_visibility", "Command interface", "Configuration: show/hide a subsystem section; role=<Role FQN> sets one role's per-role view (RSV 5.10)", p -> commandInterfaceOps.opSetSubsystemVisibility(p));
        reg(m, "set_main_section_command_visibility", "Command interface", "Configuration main section: show/hide a command; role=<Role FQN> for per-role (RSV 5.10)", p -> commandInterfaceOps.opSetMainSectionCommandVisibility(p));
        reg(m, "set_subsystem_command_visibility", "Command interface", "subsystem command interface: show/hide a command; role=<Role FQN> for per-role (RSV 5.10)", p -> commandInterfaceOps.opSetSubsystemCommandVisibility(p));
        reg(m, "set_command_placement", "Command interface", "place command into group, optional order", p -> commandInterfaceOps.opSetCommandPlacement(p));
        reg(m, "set_command_order", "Command interface", "batch-reorder commands in one group (commands JSON array)", p -> commandInterfaceOps.opSetCommandOrder(p));

        // ---- Services HTTP/SOAP (9) ----
        reg(m, "create_http_service", "Services HTTP/SOAP", "", p -> serviceOps.opCreateHttpService(p));
        reg(m, "add_url_template", "Services HTTP/SOAP", "", p -> serviceOps.opAddUrlTemplate(p));
        reg(m, "add_url_template_method", "Services HTTP/SOAP", "", p -> serviceOps.opAddUrlTemplateMethod(p));
        reg(m, "remove_url_template", "Services HTTP/SOAP", "", p -> serviceOps.opRemoveUrlTemplate(p));
        reg(m, "remove_url_template_method", "Services HTTP/SOAP", "", p -> serviceOps.opRemoveUrlTemplateMethod(p));
        reg(m, "create_web_service", "Services HTTP/SOAP", "", p -> serviceOps.opCreateWebService(p));
        reg(m, "add_web_service_operation", "Services HTTP/SOAP", "+returningValueType", p -> serviceOps.opAddWebServiceOperation(p));
        reg(m, "remove_web_service_operation", "Services HTTP/SOAP", "", p -> serviceOps.opRemoveWebServiceOperation(p));
        reg(m, "add_operation_parameter", "Services HTTP/SOAP", "typed Web operation parameter", p -> serviceOps.opAddOperationParameter(p));

        // ---- Forms (27) ----
        reg(m, "create_form", "Forms", "", p -> formCreateOps.opCreateForm(p));
        reg(m, "add_form_attribute", "Forms", "", p -> formItemsOps.opAddFormAttribute(p));
        reg(m, "add_form_attribute_column", "Forms", "", p -> formItemsOps.opAddFormAttributeColumn(p));
        reg(m, "add_dynamic_list_table", "Forms", "", p -> formItemsOps.opAddDynamicListTable(p));
        reg(m, "add_field", "Forms", "", p -> formItemsOps.delegateToEditForm("add_field", p));
        reg(m, "add_group", "Forms", "", p -> formItemsOps.delegateToEditForm("add_group", p));
        reg(m, "add_button", "Forms", "", p -> formItemsOps.delegateToEditForm("add_button", p));
        reg(m, "add_table", "Forms", "", p -> formItemsOps.delegateToEditForm("add_table", p));
        reg(m, "add_decoration", "Forms", "", p -> formItemsOps.delegateToEditForm("add_decoration", p));
        reg(m, "add_radio_button", "Forms", "delegates to add_field elementType=RadioButton", p -> formItemsOps.delegateToEditFormAsRadioButton(p));
        reg(m, "set_property", "Forms", "set a form item property", p -> formItemsOps.opSetFormItemProperty(p));
        reg(m, "list_pictures", "Forms", "", p -> formItemsOps.opListPictures(p));
        reg(m, "add_command_handler", "Forms", "", p -> formItemsOps.opAddFormCommand(p));
        reg(m, "add_form_event_handler", "Forms", "", p -> formEventOps.opAddFormEventHandler(p));
        reg(m, "remove_form_event_handler", "Forms", "", p -> formEventOps.opRemoveFormEventHandler(p));
        reg(m, "add_form_parameter", "Forms", "", p -> formItemsOps.opAddFormParameter(p));
        reg(m, "remove_form_parameter", "Forms", "", p -> formItemsOps.opRemoveFormParameter(p));
        reg(m, "add_form_command_interface_item", "Forms", "", p -> formCommandInterfaceOps.opAddFormCommandInterfaceItem(p));
        reg(m, "remove_form_command_interface_item", "Forms", "", p -> formCommandInterfaceOps.opRemoveFormCommandInterfaceItem(p));
        reg(m, "set_form_command_interface_item_property", "Forms", "", p -> formCommandInterfaceOps.opSetFormCommandInterfaceItemProperty(p));
        reg(m, "add_form_item_functional_option", "Forms", "", p -> miscOps.opFormItemFunctionalOption(p, true));
        reg(m, "remove_form_item_functional_option", "Forms", "", p -> miscOps.opFormItemFunctionalOption(p, false));
        reg(m, "setup_settings_composer_on_form", "Forms", "", p -> formItemsOps.opSetupSettingsComposerOnForm(p));
        reg(m, "remove_form_item", "Forms", "", p -> formItemsOps.delegateToEditForm("remove_form_item", p));
        reg(m, "remove_form_attribute", "Forms", "delete a form attribute (+deleteDataItems)", p -> formItemsOps.opRemoveFormAttribute(p));
        reg(m, "add_form_command", "Forms", "alias of add_command_handler", p -> formItemsOps.opAddFormCommand(p));
        reg(m, "remove_form_command", "Forms", "delete a form command (form.getFormCommands), incl. orphans", p -> formItemsOps.opRemoveFormCommand(p));
        reg(m, "set_form_command_property", "Forms", "set a form command display property: title / representation (Auto,Text,Picture,TextPicture) / picture (build-limited)", p -> formItemsOps.opSetFormCommandProperty(p));
        reg(m, "set_form_item_property", "Forms", "alias of set_property", p -> formItemsOps.opSetFormItemProperty(p));

        // ---- Templates (6) ----
        reg(m, "add_template", "Templates", "", p -> templateOps.opAddTemplate(p));
        reg(m, "set_template_content", "Templates", "", p -> templateOps.opSetTemplateContent(p));
        reg(m, "get_template_content", "Templates", "", p -> templateOps.opGetTemplateContent(p));
        reg(m, "set_template_cell", "Templates", "", p -> templateOps.opTemplateCellOp("set_template_cell", p));
        reg(m, "merge_template_cells", "Templates", "", p -> templateOps.opTemplateCellOp("merge_template_cells", p));
        reg(m, "draw_template", "Templates", "", p -> templateOps.opTemplateCellOp("draw_template", p));

        // ---- BusinessProcess route map (3) ----
        reg(m, "create_route_map", "BusinessProcess route map", "", p -> routeMapOps.opCreateRouteMap(p));
        reg(m, "get_route_map", "BusinessProcess route map", "", p -> routeMapOps.opGetRouteMap(p));
        reg(m, "remove_route_map", "BusinessProcess route map", "", p -> routeMapOps.opRemoveRouteMap(p));

        // ---- Extensions (5) - uniform delegate miscOps.opExtensionAdopt(op, params) ----
        for (String adoptOp : Arrays.asList("adopt_object", "adopt_objects", "adopt_child",
            "adopt_form_item", "adopt_module"))
        {
            reg(m, adoptOp, "Extensions", "", p -> miscOps.opExtensionAdopt(adoptOp, p));
        }

        // ---- DCS (51) - uniform delegate to DcsWorkshopTool ----
        for (String dcsOp : Arrays.asList(
            "create_report_schema", "add_data_set", "add_data_set_field",
            "add_schema_parameter", "set_schema_parameter", "remove_schema_parameter",
            "move_schema_parameter", "add_calculated_field", "add_total_field",
            "add_user_field", "remove_data_set", "add_settings_group",
            "add_settings_table", "add_settings_chart", "add_settings_filter",
            "add_settings_filter_group", "add_order", "add_settings_order",
            "add_settings_selected_field", "remove_settings_selected_field",
            "add_settings_variant", "set_settings_parameter", "remove_settings_item",
            "add_conditional_appearance", "remove_conditional_appearance",
            "set_data_set_field_appearance", "set_output_parameter", "repair_report_schema",
            "add_data_set_link", "set_data_set_link_property", "remove_data_set_link",
            "set_data_set_property", "set_data_set_query", "remove_data_set_field",
            "set_calculated_field", "remove_calculated_field", "set_total_field",
            "remove_total_field", "clear_settings_selected_fields", "remove_settings_filter",
            "remove_settings_order", "set_settings_item_user_mode", "remove_settings_variant",
            "clone_settings_variant", "add_query_field", "remove_query_field",
            "add_query_condition", "remove_query_condition", "add_data_source",
            "remove_data_source", "set_data_source_property"))
        {
            reg(m, dcsOp, "DCS", "", p -> miscOps.delegateToDcsWorkshop(dcsOp, p));
        }

        // ---- Common (3) ----
        reg(m, "move_item", "Common", "", p -> miscOps.opMoveItem(p));
        reg(m, "remove_item", "Common", "", p -> miscOps.opRemoveItem(p));
        reg(m, "remove_item_universal", "Common", "alias of remove_item", p -> miscOps.opRemoveItem(p));

        return Collections.unmodifiableMap(m);
    }


    /**
     * The full text of the parameters whose schema description was shortened.
     * <p>
     * Their prose used to sit in the tool schema, where every client pays for it in every session
     * before a single call is made - measured at 12 541 characters across 28 parameters, inside a
     * catalogue of 139 923. It is not gone: every parameter is still declared, with its type and a
     * line saying what it is, so nothing became undiscoverable. What moved is the detail, to here,
     * where it is read by whoever needs it.
     * </p>
     *
     * @return the parameters, each with the text the schema used to carry.
     */
    private static String parametersHelp()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Parameter detail\n\n"); //$NON-NLS-1$
        sb.append("These parameters carry more rules than their one-line schema description " //$NON-NLS-1$
            + "states. Every one of them is still declared in the schema - this is the detail, " //$NON-NLS-1$
            + "not a second list of parameters.\n\n"); //$NON-NLS-1$
        sb.append("### attributeName\n\n"); //$NON-NLS-1$
        sb.append("Form attribute name for add_dynamic_list_table / add_form_attribute_column and for set_property targeting an attribute's extInfo (e.g. a DynamicList's queryText / customQuery). For add_form_attribute_column prefer parentAttributeName; attributeName is accepted as an alias.\n\n"); //$NON-NLS-1$
        sb.append("### batch\n\n"); //$NON-NLS-1$
        sb.append("Run several operations from ONE call. With batch=true the `operations` array runs in order, each op in its own BM transaction; projectName / ownerFqn / formFqn / dryRun are inherited from the outer call when an op omits them. Later ops may depend on earlier ones (create_object then add_object_attribute to the new object). NOT ATOMIC: each op commits on its own, so a failure partway leaves the earlier ops applied - there is no rollback of the batch. Response: batchResults[] (index, operation, ok, response) plus ok / fail counts and stoppedOnError. Use it to author a whole object (attributes + tabular sections + forms) or add many attributes in a single round-trip.\n\n"); //$NON-NLS-1$
        sb.append("### cascadeDependencies\n\n"); //$NON-NLS-1$
        sb.append("set_role_right: when granting (value=true), ALSO grant the rights this one REQUIRES per the platform dependency model (Update->Read, Posting->Read+Update, InteractiveInsert->Insert+View+Edit, ...) so the role stays consistent. Grant-direction only - never revokes, never over-grants (granting Read never implies Update). Auto-added prerequisites are listed in cascadedRights. Default false.\n\n"); //$NON-NLS-1$
        sb.append("### commands\n\n"); //$NON-NLS-1$
        sb.append("set_command_order: a JSON array of command FQNs/names in the desired leading order, e.g. [\"CommonCommand.X\", \"Catalog.Y.Command.Z\"]. Reorders these within the group in one transaction; commands already present but not listed keep their relative order after them.\n\n"); //$NON-NLS-1$
        sb.append("### condition\n\n"); //$NON-NLS-1$
        sb.append("set_restriction_template: the RLS restriction-template condition body (referenced by object RLS via #<templateName>(...)). set_role_restriction: the ROW-LEVEL RLS condition written on targetFqn's <right> (<restrictionByCondition><condition>). Ignored by the remove_* variants. Other role content is preserved.\n\n"); //$NON-NLS-1$
        sb.append("### containerFqn\n\n"); //$NON-NLS-1$
        sb.append("For remove_item: FQN of the form-item container - the item (group / table / command bar) that holds the target named by `name`. For move_item: the form FQN, accepted as an alias of formFqn; the destination container is parentName (omit it to move the item to the form root) and beforeName places it in front of a named sibling.\n\n"); //$NON-NLS-1$
        sb.append("### content\n\n"); //$NON-NLS-1$
        sb.append("Plain-text content for a TextDocument (Template.txt) or HTMLDocument (Template.htmldoc) template. Used by add_template (fill on create), set_template_content (replace whole content, empty string clears), and returned by get_template_content. For SpreadsheetDocument use mxl_workshop, for DataCompositionSchema use dcs_workshop.\n\n"); //$NON-NLS-1$
        sb.append("### elementType\n\n"); //$NON-NLS-1$
        sb.append("Element subtype: add_field (InputField / CheckBox / RadioButton / Label / Image / SpreadSheetDocument / HTMLDocument / TextDocument / FormattedDocument / Calendar / ProgressBar / TrackBar / Period / Planner / PDFDocument / Chart / GanttChart / Dendrogram / Flowchart / GeographicalMap); add_group (UsualGroup / Pages / Page / Column / CommandBar / ButtonGroup / Popup); add_decoration (Label / Picture).\n\n"); //$NON-NLS-1$
        sb.append("### event\n\n"); //$NON-NLS-1$
        sb.append("Event name. set_event_subscription: a platform event (BeforeWrite / OnWrite / BeforeDelete / Filling / Posting / UndoPosting; free string, soft-validated - an unknown value is set with a warning, not rejected). add_form_event_handler / set_form_event_handler: a form event name (OnOpen, BeforeWrite; Russian ПриОткрытии / ПередЗаписью accepted).\n\n"); //$NON-NLS-1$
        sb.append("### fillChecking\n\n"); //$NON-NLS-1$
        sb.append("addObjectAttribute / addTabularSectionAttribute / addRegisterField: fill-check mode. DontCheck | ShowError. Applied in the same call (1 call instead of a follow-up setObjectProperty) where the field kind supports the property; unsupported ones surface in failedProperties. Optional.\n\n"); //$NON-NLS-1$
        sb.append("### group\n\n"); //$NON-NLS-1$
        sb.append("set_command_placement: the target command-interface group. Either a friendly name - Important / Normal / SeeAlso / Create / Reports / Service - a bare or StandardCommandGroup.-prefixed platform token (NavigationPanelImportant / NavigationPanelOrdinary / NavigationPanelSeeAlso / ActionsPanelCreate / ActionsPanelReports / ActionsPanelTools), or a custom group FQN CommandGroup.<name>. set_command_order: same group selection (one group, reordered).\n\n"); //$NON-NLS-1$
        sb.append("### operations\n\n"); //$NON-NLS-1$
        sb.append("batch=true payload: a JSON array of operation objects, each {\"operation\":<name>, ...that op's params}. Per-item params override the inherited projectName / ownerFqn / formFqn / dryRun. Numbers may be JSON numbers or strings. Example - a whole Catalog in one call with outer ownerFqn=Catalog.X: [{\"operation\":\"create_object\",\"objectType\":\"Catalog\",\"name\":\"X\"},{\"operation\":\"add_object_attribute\",\"name\":\"Сумма\",\"type\":\"Number\",\"precision\":15,\"fractionDigits\":2,\"fillChecking\":\"ShowError\"},{\"operation\":\"add_tabular_section\",\"name\":\"Строки\"},{\"operation\":\"add_tabular_section_attribute\",\"tabularSectionName\":\"Строки\",\"name\":\"Товар\",\"type\":\"CatalogRef.Номенклатура\"},{\"operation\":\"create_form\",\"purpose\":\"ListForm\",\"formName\":\"ФормаСписка\"}].\n\n"); //$NON-NLS-1$
        sb.append("### order\n\n"); //$NON-NLS-1$
        sb.append("add_predefined_item (ChartOfAccounts): account display order. set_command_placement: 0-based target position within the group (applied to both the CommandsPlacement fragment and, only when given, the separate CommandsOrder overlay); omit to just append/leave the command's position untouched and skip CommandsOrder entirely. Optional.\n\n"); //$NON-NLS-1$
        sb.append("### ownerFqn\n\n"); //$NON-NLS-1$
        sb.append("FQN of the owning metadata object for object/attribute/TC operations. For set_object_property / set_object_type / set_object_reference it may address a child element (Type.Name.Kind.Child, e.g. Task.X.AddressingAttribute.Y) to target that child rather than the top-level object. set_command_placement: Configuration (main section) or Subsystem.<name>.\n\n"); //$NON-NLS-1$
        sb.append("### points\n\n"); //$NON-NLS-1$
        sb.append("create_route_map: JSON array of route points, laid out top to bottom. Each object: {\"type\":Start|Action|Condition|Completion|NestedBusinessProcess, \"name\":<unique>, \"title\"?, \"taskDescription\"? (Action/Nested), \"subprocess\"? (Nested = a BusinessProcess FQN)}. Action points auto-carry the linked Task's addressing attributes. Points are laid out top to bottom in array order - declare a shared target (e.g. a common Completion) after its sources for cleaner connectors. Needs exactly one Start and at least one Completion.\n\n"); //$NON-NLS-1$
        sb.append("### property\n\n"); //$NON-NLS-1$
        sb.append("Reference property name. For add_object_reference / remove_object_reference it is a LIST-valued reference (registerRecords on a Document = its movements, owners on a Catalog, basedOn, baseCalculationTypes on a ChartOfCalculationTypes; also registeredDocuments on a DocumentJournal, and documents / registerRecords on a Sequence - each takes a top-level object FQN in valueFqn). For set_object_reference / clear_object_reference it is a SCALAR reference (e.g. extDimensionTypes on a ChartOfAccounts = a ChartOfCharacteristicTypes; addressing / currentPerformer on a Task; mainAddressingAttribute on a Task = a child AddressingAttribute).\n\n"); //$NON-NLS-1$
        sb.append("### propertyName\n\n"); //$NON-NLS-1$
        sb.append("Property name for setObjectProperty (coerced from propertyValue: enum literals, booleans, localized synonym / toolTip). Special: propertyName=fillValue builds a type-aware default value - propertyValue is Boolean (true/false) / Number / String per the attribute's type, empty or 'Undefined' clears it (Date / reference defaults not supported). Special: propertyName=inputByString (on the object, not an attribute) sets the input-by-string fields - propertyValue is a comma-separated list of attribute names (e.g. Code,Description). propertyName=choiceParameters / choiceParameterLinks (on an attribute) take a JSON array: [{\"name\":\"Отбор.ЭтоГруппа\",\"value\":\"false\"}] / [{\"name\":\"Отбор.Владелец\",\"field\":\"Owner\"}]. Child FQNs supported (e.g. Catalog.X.Attribute.Y).\n\n"); //$NON-NLS-1$
        sb.append("### purpose\n\n"); //$NON-NLS-1$
        sb.append("create_form: form purpose driving the EDT form generator (renderable form with main attribute + default layout). Values: ItemForm/ObjectForm (OBJECT), ListForm (LIST), ChoiceForm (CHOICE), FolderForm (FOLDER), FolderChoiceForm (FOLDER_CHOICE), RecordSetForm (RECORD_SET), RecordForm (RECORD), Generic (GENERIC); RU synonyms accepted. When omitted the purpose is derived from the owner type and the form name (object-owning types -> OBJECT, registers -> RECORD_SET, a 'Список'/'List' name -> LIST, a 'Выбор'/'Choice' name -> CHOICE, DataProcessor/Report and ExternalDataProcessor/ExternalReport -> OBJECT (main form, Объект attr; pass purpose=Generic for a custom empty form), CommonForm -> GENERIC). Optional. Ignored for ORDINARY forms and when the generator is unavailable (the form is then created empty).\n\n"); //$NON-NLS-1$
        sb.append("### rightName\n\n"); //$NON-NLS-1$
        sb.append("set_role_right / set_role_restriction: the access right on targetFqn (Read / Insert / Update / Delete / View / Edit / ...; Russian Чтение / Добавление / Изменение / Удаление / Просмотр / Редактирование accepted - normalized to the platform right name).\n\n"); //$NON-NLS-1$
        sb.append("### role\n\n"); //$NON-NLS-1$
        sb.append("Optional Role FQN (e.g. Role.FullAccess) for set_subsystem_visibility / set_main_section_command_visibility / set_subsystem_command_visibility: sets ONE role's per-role visibility (AdjustableBoolean.getFor()) instead of the common value. Omit to set the common (role-independent) value.\n\n"); //$NON-NLS-1$
        sb.append("### stopOnError\n\n"); //$NON-NLS-1$
        sb.append("batch only. When true, stop at the first failing operation instead of running the rest; the operations that did not run are listed in batchResults with skipped=true. Already-committed ops are NOT rolled back. Default false (run every op, recording each failure).\n\n"); //$NON-NLS-1$
        sb.append("### subsystem\n\n"); //$NON-NLS-1$
        sb.append("set_subsystem_visibility: the top-level Subsystem FQN/name whose section visibility to set. set_subsystem_command_visibility: the owner Subsystem FQN/name whose command interface to modify. Top-level (Subsystem.X or X), nested (Subsystem.A.Subsystem.B), or bare dotted (A.B) are all accepted; the subsystem must already have a command interface for a hide.\n\n"); //$NON-NLS-1$
        sb.append("### subsystems\n\n"); //$NON-NLS-1$
        sb.append("set_subsystems_order: comma-separated top-level Subsystem FQNs/names in the desired leading order (e.g. Subsystem.Sales,Subsystem.Purchases). Participating subsystems (includeInCommandInterface=true) not listed are appended in configuration order so the written order is complete.\n\n"); //$NON-NLS-1$
        sb.append("### synonym\n\n"); //$NON-NLS-1$
        sb.append("Synonym for create_object / add_object_attribute / add_tabular_section_attribute / add_register_field (Dimension/Resource/Attribute) / add_addressing_attribute / add_accounting_flag / add_ext_dimension_accounting_flag / add_recalculation (optional). When omitted it is auto-generated from the name like the EDT wizard (СуммаДокумента -> 'Сумма документа', АдресЭП -> 'Адрес ЭП'). Configuration.namePrefix (ВТ_, ПСБ_, ...) is stripped before generation - pass synonym explicitly to override. MULTI-LANGUAGE: pass a JSON object keyed by language code to set several languages at once - {\"ru\":\"Контрагент\",\"en\":\"Counterparty\"}. The object REPLACES the whole synonym map (one entry per code). A plain string sets the default language only.\n\n"); //$NON-NLS-1$
        sb.append("### targetFqn\n\n"); //$NON-NLS-1$
        sb.append("Full child-FQN for adopt_* operations (alias of objectFqn). Examples: Catalog.Users, Catalog.Users.Form.UserForm, Document.Order.Attribute.Total. When omitted, adopt_child / adopt_form_item compose the FQN from ownerFqn + childKind + name. Also: set_role_right / set_role_restriction - the metadata object the right / RLS condition is set on (e.g. Catalog.Goods).\n\n"); //$NON-NLS-1$
        sb.append("### type\n\n"); //$NON-NLS-1$
        sb.append("Type for addObjectAttribute / addTabularSectionAttribute / setObjectType. Primitives: String / Number / Date / Boolean / UUID. References: CatalogRef.X / DocumentRef.X / EnumRef.X / DefinedType.X. Composite: list the types comma-separated in one string, e.g. 'CatalogRef.A,DocumentRef.B' - this works when creating the attribute, so there is no need to create it single-typed and widen it afterwards. TypeDescription qualifiers (length / precision / fractionDigits / nonNegative / dateFractions / allowedLength) are wired as separate parameters. Defaults: Number precision=10 / fractionDigits=0, Date dateFractions=Date, String length=0 (unlimited).\n\n"); //$NON-NLS-1$
        sb.append("### valueFqn\n\n"); //$NON-NLS-1$
        sb.append("FQN of the referenced object. For add_object_reference / remove_object_reference a top-level object (e.g. AccumulationRegister.Sales for registerRecords). For set_object_reference the single target: a top-level object OR a child object (Type.Name.Kind.Child, e.g. Task.X.AddressingAttribute.Y for mainAddressingAttribute); omit for clear_object_reference. Also the content target for add/remove_exchange_plan_content (ownerFqn=ExchangePlan.X), add/remove_common_attribute_content (ownerFqn=CommonAttribute.X), add/remove_functional_option_content (ownerFqn=FunctionalOption.X for the controlled object/attribute e.g. Document.X.Attribute.Y, or ownerFqn=FunctionalOptionsParameter.X for a use-list object e.g. Catalog.Y), and add/remove_subsystem_content (ownerFqn=Subsystem.X; the 'name'/'targetFqn' params are aliases for this value).\n\n"); //$NON-NLS-1$
        sb.append("### visible\n\n"); //$NON-NLS-1$
        sb.append("set_subsystem_visibility / set_main_section_command_visibility / set_subsystem_command_visibility: true shows, false hides. Sets the common (role-independent) value; true reverts a plain hide-override back to the default. Per-role visibility exceptions are preserved. When `role` is given, sets that one role's per-role value instead of the common one.\n\n"); //$NON-NLS-1$
        return sb.toString();
    }

    private static void reg(Map<String, OpEntry> m, String name, String group, String help, OpHandler handler)
    {
        // Fail-fast on a duplicate key so the single-source-of-truth guarantee is
        // self-enforcing: a future copy-paste of an existing op name throws at
        // class-load instead of silently overwriting the earlier registration.
        if (m.containsKey(name))
        {
            throw new IllegalStateException("duplicate operation registration: " + name); //$NON-NLS-1$
        }
        m.put(name, new OpEntry(group, help, handler));
    }

    /**
     * Wraps {@link MetadataObjectRenamer}'s raw response into the JSON envelope edit_metadata itself
     * always returns.
     * <p>
     * {@code rename_metadata_object} is {@code ResponseType.MARKDOWN} - a plain {@code "Error: ..."}
     * string on a validation failure, a YamlFrontMatter-prefixed markdown report otherwise - while
     * edit_metadata's own {@link #getResponseType()} is JSON. Passing the markdown straight through
     * would hand the protocol handler a string it cannot parse as JSON: the same bug class
     * {@code FormItemsOps.convertEditFormMarkdownToJson} exists to avoid for the edit_form delegation,
     * kept here as its own small conversion because {@code delete_metadata_object} and
     * {@code add_metadata_attribute} are already {@code ResponseType.JSON} and need none.
     * </p>
     *
     * @param markdown the raw response from {@link MetadataObjectRenamer#execute}
     * @param op the edit_metadata operation name, echoed back for context
     * @return a JSON string in edit_metadata's own response shape
     */
    private static String convertRenamerMarkdownToJson(String markdown, String op)
    {
        if (markdown == null)
        {
            return ToolResult.error(op + " failed: empty response from the delegated standalone") //$NON-NLS-1$
                .put("operation", op) //$NON-NLS-1$
                .toJson();
        }
        if (markdown.startsWith("Error")) //$NON-NLS-1$
        {
            return ToolResult.error(markdown)
                .put("operation", op) //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("operation", op) //$NON-NLS-1$
            .put("message", markdown) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Wraps a {@link ToolGate} disabled-tool rejection into edit_metadata's JSON envelope.
     * <p>
     * The delegated Objects operations are preset-gated: when the standalone they route to is disabled
     * by the active preset, {@link ToolGate#gateOrNull} hands back plain rejection text. edit_metadata is
     * {@code ResponseType.JSON}, so returning that text unwrapped would crash the protocol handler's
     * {@code JsonParser.parseString} exactly as an unwrapped markdown response would - the reason
     * {@link #convertRenamerMarkdownToJson} exists. Wrapping it here keeps the disabled path a parseable
     * JSON document that still carries the router's own wording.
     * </p>
     *
     * @param op the edit_metadata operation name, echoed back for context
     * @param message the gate's rejection text
     * @return a JSON string in edit_metadata's own response shape
     */
    private static String gatedRejectJson(String op, String message)
    {
        return ToolResult.error(message)
            .put("operation", op) //$NON-NLS-1$
            .toJson();
    }

    /** Editorial one-line note per help group (the op list itself is generated). */
    @SuppressWarnings("nls")
    private static String groupNote(String group)
    {
        switch (group)
        {
            case "Objects":
                return "core object + attribute + tabular-section + reference ops";
            case "Specialized":
                return "registers, roles/RLS, subscriptions, content collections, functional options";
            case "Command interface":
                return "configuration + subsystem command interface (read via get_command_interface)";
            case "Services HTTP/SOAP":
                return "HTTP and SOAP web services";
            case "Forms":
                return "managed form structure, attributes, commands, events (replaces edit_form)";
            case "Templates":
                return "template create + content I/O + MXL cell ops";
            case "BusinessProcess route map":
                return "create / read / remove";
            case "Extensions":
                return "adopt (borrow) base-configuration objects into an extension";
            case "DCS":
                return "DataCompositionSchema build + settings (replaces dcs_workshop)";
            case "Common":
                return "universal item routing";
            default:
                return "";
        }
    }

    /** Generates the grouped operation catalog for `help` from the registry. */
    @SuppressWarnings("nls")
    private String buildOperationCatalogHelp()
    {
        StringBuilder sb = new StringBuilder();
        for (String group : OP_GROUP_ORDER)
        {
            java.util.List<Map.Entry<String, OpEntry>> inGroup = new java.util.ArrayList<>();
            for (Map.Entry<String, OpEntry> e : registry.entrySet())
            {
                if (e.getValue().group.equals(group))
                {
                    inGroup.add(e);
                }
            }
            if (inGroup.isEmpty())
            {
                continue;
            }
            sb.append("\n## ").append(group).append(" (").append(inGroup.size()).append(")");
            String note = groupNote(group);
            if (!note.isEmpty())
            {
                sb.append(" - ").append(note);
            }
            sb.append("\n\n");
            for (Map.Entry<String, OpEntry> e : inGroup)
            {
                sb.append("- ").append(e.getKey());
                if (!e.getValue().help.isEmpty())
                {
                    sb.append(" (").append(e.getValue().help).append(")");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /** One-line group summary for getDescription - computed, so it never drifts. */
    @SuppressWarnings("nls")
    private String operationSummary()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(registry.size()).append(" operations across ")
            .append(OP_GROUP_ORDER.length).append(" groups: ");
        for (int i = 0; i < OP_GROUP_ORDER.length; i++)
        {
            String g = OP_GROUP_ORDER[i];
            int c = 0;
            for (OpEntry e : registry.values())
            {
                if (e.group.equals(g))
                {
                    c++;
                }
            }
            if (i > 0)
            {
                sb.append(", ");
            }
            sb.append(g).append(" ").append(c);
        }
        return sb.toString();
    }
}
