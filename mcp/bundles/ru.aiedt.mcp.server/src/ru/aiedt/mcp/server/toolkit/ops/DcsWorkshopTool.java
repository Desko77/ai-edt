/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmDcsHelper;
import ru.aiedt.mcp.server.support.BmDefinedTypeHelper;
import ru.aiedt.mcp.server.support.BmFormHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.MetadataGuards;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.QlValidator;

/**
 * DCS schema constructor — наш {@code edit_metadata} DCS group,
 * как отдельный per-domain tool (см. план 1.35-1.36).
 * <p>
 * <b>1.35:</b> 10 functional operations + help. Auto-validation запросов через
 * существующий {@code com._1c.g5.v8.dt.ql.dcs.resource} парсер. DCS direct save
 * в расширения через {@link ru.aiedt.mcp.server.support.DcsExtensionExportHelper}
 * автоматически после каждой mutation operation.
 * <p>
 * <b>1.36:</b> +17 ops для settings/appearance/variants + expression validation.
 * Обновление: добавить case в dispatch + handler метод.
 */
public class DcsWorkshopTool implements IMcpTool
{
    /** The dataset property whose value is a whole query. */
    private static final String QUERY_PROPERTY = "query"; //$NON-NLS-1$

    /** A link property whose value is a DCS expression. */
    private static final String SOURCE_EXPRESSION = "sourceExpression"; //$NON-NLS-1$

    /** The other one. */
    private static final String DESTINATION_EXPRESSION = "destinationExpression"; //$NON-NLS-1$

    public static final String NAME = "dcs_workshop"; //$NON-NLS-1$

    /**
     * Single source of truth for schema-mutation operations: op name -> handler
     * applied on the DCS schema inside the BM write transaction. dispatch(), the
     * OPS allowlist and applySchemaMutation() all derive from this, so the three
     * levels can never drift apart (the recurring "OPS-listed but undispatched"
     * bug class). Declared before OPS so the field initializer order builds it first.
     */
    private final Map<String, MutationHandler> mutations = buildMutationRegistry();

    /**
     * The operations that act on composition settings rather than on a schema, and so apply to a
     * form's dynamic list as well as to a schema in a template.
     * <p>
     * Every one of these reaches its target through {@code ensureDefaultSettings}, which is what
     * makes the second address work without a handler being copied. An operation absent from this
     * set is refused on a list by name; an operation added to the tool that belongs here must be
     * added here too, and {@code DynamicListTakesOnlySettingsOperationsTest} holds the set to the
     * shape of the catalog.
     * </p>
     */
    static final Set<String> SETTINGS_OPS = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(
            "add_appearance", "add_chart", "add_filter", "add_filter_group", "add_grouping", "add_order",
            "add_settings_chart", "add_settings_filter_group", "add_settings_order",
            "add_settings_selected_field", "add_settings_table", "add_user_field",
            "clear_settings_selected_fields", "deselect_field", "remove_appearance",
            "remove_conditional_appearance", "remove_settings_filter", "remove_settings_item",
            "remove_settings_order", "remove_settings_selected_field", "remove_user_field",
            "select_field", "set_output_param", "set_output_parameter", "set_param_value",
            "set_settings_item_user_mode", "set_settings_parameter", "set_user_field")));

    /** Advertised operation catalog (allowlist + help + suggest), derived from the registry. */
    private final Map<String, String> OPS = buildOpsCatalog();

    /** One schema-mutation operation, applied on the resolved DCS schema inside the BM tx. */
    @FunctionalInterface
    private interface MutationHandler
    {
        Object apply(Map<String, String> params, EObject schema, IProject project) throws Exception;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "DCS schema constructor for reports / data processors / register macros. " //$NON-NLS-1$
            + "Pass operation=<name>; call operation=help for the catalog. " //$NON-NLS-1$
            + "Auto-validates queryText and expressions before write. " //$NON-NLS-1$
            + "DCS direct save to .dcs disk file is automatic for extension projects."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", "Operation name. Use 'help' for catalog.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", "Name of the EDT project to work in") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("objectName", //$NON-NLS-1$
                "Owner FQN (Report.X / DataProcessor.X) or full schema FQN") //$NON-NLS-1$
            .stringProperty("formFqn", //$NON-NLS-1$
                "Form holding a dynamic list, e.g. Catalog.X.Form.ListForm. Pass with " //$NON-NLS-1$
                    + "attributeName instead of objectName to work on the list's settings") //$NON-NLS-1$
            .stringProperty("attributeName", //$NON-NLS-1$
                "Dynamic-list attribute on formFqn whose settings the operation applies to") //$NON-NLS-1$
            .stringProperty("templateName", //$NON-NLS-1$
                "DCS template name. Default follows the configuration script variant: " //$NON-NLS-1$
                    + "ОсновнаяСхемаКомпоновкиДанных (Russian) / MainDataCompositionSchema (English)") //$NON-NLS-1$
            .stringProperty("name", "Name of the new element (parameter / field / etc.)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("dataSetName", "Target dataset for field/calc operations") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("queryText", "BSL query text for add_dataset (Query type) and set_dataset_query " //$NON-NLS-1$ //$NON-NLS-2$
                + "(replaces the whole query of an existing Query dataset; multi-statement / UNION OK)") //$NON-NLS-1$
            .stringProperty("dataSetType", "Query / Object / Union (default: Query)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("expression", "DCS expression for calculated field / total") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("aggregateFunction", //$NON-NLS-1$
                "Aggregate for add_total: Sum / Count / Min / Max / Avg") //$NON-NLS-1$
            .stringProperty("type", "Parameter type (Date, Number, String, etc.)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("direction", //$NON-NLS-1$
                "Up / Down for move_parameter; Asc / Desc for add_order (alias of orderType, default Asc)") //$NON-NLS-1$
            .integerProperty("newIndex", "Target index for move_parameter (0-based)") //$NON-NLS-1$ //$NON-NLS-2$
            // 1.43.x DCS catch-up wave 2: new param keys
            .stringProperty("sourceDataSet", "Source dataset name for dataset-link ops") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("destinationDataSet", //$NON-NLS-1$
                "Destination dataset name for dataset-link ops") //$NON-NLS-1$
            .stringProperty("sourceExpression", "Source expression for add_dataset_link") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("destinationExpression", //$NON-NLS-1$
                "Destination expression for add_dataset_link") //$NON-NLS-1$
            .stringProperty("property", "Property name for set_*_property ops") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("value", "Property/parameter value for set_* ops") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("index", //$NON-NLS-1$
                "0-based item index for remove/set settings-item ops") //$NON-NLS-1$
            .stringProperty("viewMode", //$NON-NLS-1$
                "Auto / Normal / QuickAccess / Inaccessible (set_settings_item_user_mode)") //$NON-NLS-1$
            .stringProperty("container", //$NON-NLS-1$
                "selection / filter / order (set_settings_item_user_mode)") //$NON-NLS-1$
            .stringProperty("field", //$NON-NLS-1$
                "Field path for grouping / order / selected-field / filter ops and the field-match " //$NON-NLS-1$
                + "branch of remove_settings_filter / remove_settings_order (alternative to index). " //$NON-NLS-1$
                + "Also the SELECT-list field for add_query_field / remove_query_field.") //$NON-NLS-1$
            .stringProperty("condition", //$NON-NLS-1$
                "Query condition expression for add_query_condition / remove_query_condition " //$NON-NLS-1$
                + "(e.g. 'T.Sum > &MinSum'). add splices it into WHERE/ГДЕ; remove matches it " //$NON-NLS-1$
                + "(parenthesised or bare) and drops it with one adjacent AND/И.") //$NON-NLS-1$
            .stringProperty("appearance", //$NON-NLS-1$
                "Appearance spec 'Name=Value;Name=Value' for add_appearance / " //$NON-NLS-1$
                + "set_data_set_field_appearance. Font='Arial,12,bold', colors='#RRGGBB'. " //$NON-NLS-1$
                + "Keys: TextColor/BackColor/BorderColor/Font/Format (or Russian equivalents).") //$NON-NLS-1$
            .stringProperty("title", //$NON-NLS-1$
                "Presentation/title for selected-field / calculated-field / parameter, and what a " //$NON-NLS-1$
                    + "nested schema is called on screen (optional).") //$NON-NLS-1$
            .stringProperty("sourceName", //$NON-NLS-1$
                "Source settings-variant name to copy from (clone_settings_variant).") //$NON-NLS-1$
            .stringProperty("newName", //$NON-NLS-1$
                "New settings-variant name for rename_settings_variant.") //$NON-NLS-1$
            .stringProperty("topic", "Help topic name (use with operation=help)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("dryRun", "Preview changes inside BM transaction (default false)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("validate_query", "Validate queryText before write (default true)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("validate_expression", //$NON-NLS-1$
                "Validate expression before write (default true)") //$NON-NLS-1$
            .stringProperty("comparisonType", //$NON-NLS-1$
                "add_filter: comparison kind (default Equal).") //$NON-NLS-1$
            .stringProperty("conditionType", //$NON-NLS-1$
                "add_appearance: condition kind (default Equal).") //$NON-NLS-1$
            .stringProperty("conditionValue", //$NON-NLS-1$
                "add_appearance: condition value.") //$NON-NLS-1$
            .stringProperty("connectionString", //$NON-NLS-1$
                "add_data_source: connection string (for non-Local data source types).") //$NON-NLS-1$
            .stringProperty("dataSourceType", //$NON-NLS-1$
                "add_data_source: data source type (default Local).") //$NON-NLS-1$
            .stringProperty("dataSet", //$NON-NLS-1$
                "set_data_set_field_appearance: target dataset name (distinct from dataSetName).") //$NON-NLS-1$
            .stringProperty("groupType", //$NON-NLS-1$
                "add_filter_group: group type (default AndGroup).") //$NON-NLS-1$
            .stringProperty("groupingType", //$NON-NLS-1$
                "add_grouping: grouping type (default Items).") //$NON-NLS-1$
            .stringProperty("itemPath", //$NON-NLS-1$
                "remove_settings_item: path of the settings item to remove.") //$NON-NLS-1$
            .stringProperty("orderType", //$NON-NLS-1$
                "add_order: Asc / Desc (default Asc; alias of direction).") //$NON-NLS-1$
            .stringProperty("presentation", //$NON-NLS-1$
                "add_variant: settings-variant presentation / title.") //$NON-NLS-1$
            .stringProperty("url", //$NON-NLS-1$
                "add_nested_schema: where the nested schema reads its data from.") //$NON-NLS-1$
            .stringProperty("dataObjectName", //$NON-NLS-1$
                "add_union_item dataSetType=Object: the object that child dataset reads. Distinct " //$NON-NLS-1$
                    + "from objectName, which names the owner whose schema is being edited.") //$NON-NLS-1$
            .stringProperty("nestedSchemaName", //$NON-NLS-1$
                "Name of a nested schema to work inside. Without it an operation applies to the " //$NON-NLS-1$
                    + "schema itself; with it, to the schema of that name within it.") //$NON-NLS-1$
            .stringProperty("target", //$NON-NLS-1$
                "remove_conditional_appearance: where to remove from - schema (default) / settings.") //$NON-NLS-1$
            .stringProperty("userSettingPresentation", //$NON-NLS-1$
                "add_filter: filter item user presentation.") //$NON-NLS-1$
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
        String op = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (op == null || op.isEmpty())
        {
            return ToolResult.error("operation is required. Pass operation=help for catalog.") //$NON-NLS-1$
                .toJson();
        }
        op = op.trim();
        if ("help".equalsIgnoreCase(op)) //$NON-NLS-1$
        {
            return handleHelp(params);
        }
        if (!OPS.containsKey(op))
        {
            return ToolResult.error("Unknown operation '" + op //$NON-NLS-1$
                + "'. Call operation=help for the full list. Did you mean: " + suggest(op) + "?") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }

        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        final String finalOp = op;
        display.syncExec(() -> {
            try
            {
                resultRef.set(dispatch(finalOp, params));
            }
            catch (Exception e)
            {
                Activator.logError("dcs_workshop error in operation " + finalOp, e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(TextSuggest.safeMessage(e)).toJson());
            }
        });
        return resultRef.get();
    }

    private String dispatch(String op, Map<String, String> params)
    {
        if ("create_schema".equals(op))
        {
            return opCreateSchema(params);
        }
        if ("repair_schema".equals(op))
        {
            return ToolResult.error("repair_schema requires DcsExtensionImportHelper wired " //$NON-NLS-1$
                + "in the dispatcher; activated when full integration test passes against EDT 2026.1") //$NON-NLS-1$
                .toJson();
        }
        // Every other advertised op is a schema mutation; the mutation registry is
        // the single source of which ops exist. opSchemaMutation wraps the BM tx and
        // calls applySchemaMutation, which looks the op up in the same registry.
        if (mutations.containsKey(op))
        {
            return opSchemaMutation(op, params);
        }
        return ToolResult.error(BmDcsHelper.deferredMessage(op)).toJson();
    }

    private String opCreateSchema(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        if (projectName == null || objectName == null)
        {
            return ToolResult.error("projectName and objectName are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmDcsHelper.Result r = BmDcsHelper.createSchemaOnObject(project, objectName, templateName, dryRun);
        return formatResult(r, "create_schema"); //$NON-NLS-1$
    }

    /**
     * Generic schema-mutation dispatch that runs inside BmDcsHelper.executeWriteOnSchema.
     * Per-op semantics are implemented inline so the BM transaction holds for one op.
     * <p>
     * Each op resolves the schema {@link EObject} via reflection, mutates the
     * corresponding child collection (DataSets / Parameters / CalculatedFields /
     * ConditionalAppearance / DefaultSettings.Structure / DefaultSettings.Filter),
     * and records a short message in {@link BmDcsHelper.Result#message}. Errors
     * surface as {@link MetadataGuards.BlockedGuardException} with structured tags.
     */
    /**
     * Whether the call is aimed at a form's dynamic list rather than at a schema in a template.
     *
     * @param formFqn the form, when one was given.
     * @param attributeName the dynamic-list attribute, when one was given.
     * @return true when both halves of the list address are present
     */
    static boolean addressesAList(String formFqn, String attributeName)
    {
        return formFqn != null && !formFqn.isEmpty()
            && attributeName != null && !attributeName.isEmpty();
    }

    /**
     * What is wrong with the target the caller named, if anything.
     * <p>
     * There are two ways to name a target and they do not mix: a schema is an object plus a
     * template, a dynamic list is a form plus an attribute. Half an address, or both addresses at
     * once, is answered by saying so - picking one silently would report on a target the caller did
     * not mean.
     * </p>
     *
     * @param projectName the project, needed either way.
     * @param objectName the schema owner, for the schema address.
     * @param formFqn the form, for the list address.
     * @param attributeName the attribute, for the list address.
     * @return the refusal to answer with, or <code>null</code> when the address is usable
     */
    static String addressRefusal(String projectName, String objectName, String formFqn,
        String attributeName)
    {
        boolean hasForm = formFqn != null && !formFqn.isEmpty();
        boolean hasAttribute = attributeName != null && !attributeName.isEmpty();
        if (hasForm != hasAttribute)
        {
            return "A form's dynamic list is addressed by formFqn AND attributeName together; only " //$NON-NLS-1$
                + (hasForm ? "formFqn" : "attributeName") + " was given"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        boolean hasObject = objectName != null && !objectName.isEmpty();
        if (hasForm && hasObject)
        {
            return "objectName addresses a schema and formFqn + attributeName address a form's " //$NON-NLS-1$
                + "dynamic list; pass one or the other, not both"; //$NON-NLS-1$
        }
        if (projectName == null || projectName.isEmpty())
        {
            return "projectName is required"; //$NON-NLS-1$
        }
        if (!hasForm && !hasObject)
        {
            return "projectName and objectName are required, or formFqn and attributeName to work " //$NON-NLS-1$
                + "on a form's dynamic list instead"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The schema an operation is aimed at.
     * <p>
     * Two coordinates reach a schema in a template; a third reaches a schema nested inside it.
     * Without that third one a nested schema could be created and never written into - and a call
     * meant for it would have gone to the schema around it and reported success.
     * </p>
     *
     * @param root the schema the FQN resolved to.
     * @param nestedSchemaName the nested schema to work inside, or <code>null</code> for the root.
     * @return the schema to write to
     */
    /**
     * The steps of a name that may be a path down a hierarchy.
     * <p>
     * A name is taken whole first by the callers of this method, so this is only reached for a name
     * that is not there as written. Every step must be a name: Java drops a trailing empty segment,
     * so "Outer." would silently become "Outer" and act on the wrong node, and "." would become no
     * steps at all and act on the root.
     * </p>
     *
     * @param path the name as the caller wrote it.
     * @param what the kind of thing being addressed, for the refusal.
     * @return its steps, each non-empty
     */
    /**
     * The text an export has to contain for a named element to be in it.
     * <p>
     * The file is XML, so a name is escaped there. Looking for the raw name would report a write as
     * lost because of an ampersand in it, and the write would then be repeated to no purpose.
     * </p>
     *
     * @param name the element name.
     * @return what to look for in the exported file
     */
    private static String asWrittenInTheFile(String name)
    {
        return ">" + name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "<"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
    }

    /**
     * Refuses an address that reads as a path while some name in the collection carries a dot.
     * <p>
     * Both readings are possible then - a dataset actually called {@code A.B}, or {@code B} inside
     * {@code A} - and choosing one silently would write to a target the caller did not mean.
     * </p>
     *
     * @param owner what holds the collection.
     * @param getter the collection.
     * @param path the address as the caller wrote it.
     */
    private void mustNotBeAmbiguous(EObject owner, String getter, String path)
    {
        if (path.indexOf('.') < 0)
        {
            return;
        }
        EList<EObject> all = BmDcsHelper.getEObjectList(owner, getter);
        if (all == null)
        {
            return;
        }
        for (EObject one : all)
        {
            Object named = invokeGetter(one, "getName"); //$NON-NLS-1$
            if (named != null && String.valueOf(named).indexOf('.') >= 0)
            {
                throw new RuntimeException("'" + path + "' could mean a name or a path, because " //$NON-NLS-1$ //$NON-NLS-2$
                    + "'" + named + "' has a dot in it. Rename it, or address the target without " //$NON-NLS-1$ //$NON-NLS-2$
                    + "a path."); //$NON-NLS-1$
            }
        }
    }

    private static String[] pathSteps(String path, String what)
    {
        String[] steps = path.split("\\.", -1); //$NON-NLS-1$
        for (String step : steps)
        {
            if (step.isEmpty())
            {
                throw new RuntimeException("'" + path + "' is not a " + what + " name or a path " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "of them - every step between the dots has to name something."); //$NON-NLS-1$
            }
        }
        return steps;
    }

    /**
     * Refuses a new name that carries the character used to separate the steps of a path.
     * <p>
     * Such a name can be written and then never addressed again, because the address would read as
     * a hierarchy rather than as the name.
     * </p>
     *
     * @param name the name asked for.
     * @param what the kind of thing being named, for the refusal.
     */
    private static void mustNotLookLikeAPath(String name, String what)
    {
        if (name.indexOf('.') >= 0)
        {
            throw new RuntimeException("a " + what + " named '" + name + "' could not be " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "addressed afterwards: a dot separates the steps of a path."); //$NON-NLS-1$
        }
    }

    private EObject schemaToWorkIn(EObject root, String nestedSchemaName)
    {
        if (nestedSchemaName == null || nestedSchemaName.isEmpty())
        {
            return root;
        }
        // A nested schema may hold one of its own, so the name may be a path down through them:
        // Outer.Inner. A name is taken whole first, so one that contains a dot - written before
        // this refused such names - still addresses itself rather than reading as a hierarchy.
        EObject current = root;
        String[] steps;
        if (BmDcsHelper.findByNameInList(root, "getNestedSchemas", nestedSchemaName) != null) //$NON-NLS-1$
        {
            steps = new String[] {nestedSchemaName};
        }
        else
        {
            mustNotBeAmbiguous(root, "getNestedSchemas", nestedSchemaName); //$NON-NLS-1$
            steps = pathSteps(nestedSchemaName, "nested schema"); //$NON-NLS-1$
        }
        for (String step : steps)
        {
            EObject entry = BmDcsHelper.findByNameInList(current, "getNestedSchemas", step); //$NON-NLS-1$
            if (entry == null)
            {
                throw notFoundTag(step, "nestedSchema"); //$NON-NLS-1$
            }
            Object inner = invokeGetter(entry, "getSchema"); //$NON-NLS-1$
            if (!(inner instanceof EObject))
            {
                // Every nested schema this tool makes gets one; one made elsewhere may not, and
                // writing into the schema around it instead would be worse than saying so.
                throw notFoundTag(step + " (it carries no schema of its own)", //$NON-NLS-1$
                    "nestedSchema"); //$NON-NLS-1$
            }
            current = (EObject)inner;
        }
        return current;
    }

    private String opSchemaMutation(String op, Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String nestedForCheck = JsonUtils.extractStringArgument(params, "nestedSchemaName"); //$NON-NLS-1$
        if (addressesAList(formFqn, attributeName)
            && nestedForCheck != null && !nestedForCheck.isEmpty())
        {
            return ToolResult.error("nestedSchemaName addresses a schema inside a schema, and a " //$NON-NLS-1$
                + "form's dynamic list has no schema - drop one of the two").toJson(); //$NON-NLS-1$
        }
        String badAddress = addressRefusal(projectName, objectName, formFqn, attributeName);
        if (badAddress != null)
        {
            return ToolResult.error(badAddress).toJson();
        }
        boolean onAList = addressesAList(formFqn, attributeName);
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        // Pre-flight: validate queryText / expression BEFORE the BM transaction opens. Cheaper
        // than rolling back the model on a parse error, and it avoids running Xtext validation
        // inside the BM tx. It runs for both addresses: a user field written into a list's settings
        // carries an expression exactly as one written into a schema does.
        String preFlightError = preflightValidate(op, params, project);
        if (preFlightError != null)
        {
            return preFlightError;
        }
        if (onAList)
        {
            return applyToDynamicList(op, params, project, formFqn, attributeName, dryRun);
        }
        String nestedSchemaName = JsonUtils.extractStringArgument(params, "nestedSchemaName"); //$NON-NLS-1$
        BmDcsHelper.Result r = BmDcsHelper.executeWriteOnSchema(project, objectName, templateName,
            dryRun, (tx, schema) -> applySchemaMutation(op, params,
                schemaToWorkIn(schema, nestedSchemaName), project));
        return formatResult(r, op);
    }

    /**
     * Runs a settings operation against a form's dynamic list.
     * <p>
     * A dynamic list carries composition settings of the same shape a schema carries, so every
     * settings handler applies to it unchanged once the settings object is in hand. What differs is
     * only how the target is reached: a schema is one top object resolved by FQN, a list's settings
     * hang off a form attribute. Operations that shape a schema itself - datasets, parameters,
     * variants - have nothing to act on here and are refused by name rather than left to fail on a
     * missing collection.
     * </p>
     *
     * @param op the settings operation, spelled as on the schema route.
     * @param params its arguments.
     * @param project the project holding the form.
     * @param formFqn the form.
     * @param attributeName the dynamic-list attribute on it.
     * @param dryRun whether to discard the change.
     * @return the JSON answer
     */
    private String applyToDynamicList(String op, Map<String, String> params, IProject project,
        String formFqn, String attributeName, boolean dryRun)
    {
        if (!SETTINGS_OPS.contains(op))
        {
            return ToolResult.error("'" + op + "' shapes a composition schema, and a dynamic list " //$NON-NLS-1$ //$NON-NLS-2$
                + "has no schema to shape - it has settings only. Operations that work on a list: " //$NON-NLS-1$
                + String.join(", ", SETTINGS_OPS)) //$NON-NLS-1$
                .put("operation", op) //$NON-NLS-1$
                .put(ErrorTags.NOT_APPLICABLE_HERE.wire(), op)
                .toJson();
        }
        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("EDT form model unavailable in this runtime").toJson(); //$NON-NLS-1$
        }
        String outcome = helper.executeFormOperation(project, formFqn, dryRun, (tx, form) -> {
            Object settings = helper.listSettingsFor(tx, form, attributeName);
            if (settings == null)
            {
                return "Error: '" + attributeName + "' is not a dynamic-list attribute of " //$NON-NLS-1$ //$NON-NLS-2$
                    + formFqn + ", or its settings could not be created."; //$NON-NLS-1$
            }
            Object applied = applySchemaMutation(op, params, (EObject)settings, project);
            return applied == null ? "" : applied.toString(); //$NON-NLS-1$
        });
        if (outcome != null && outcome.startsWith("Error:")) //$NON-NLS-1$
        {
            return ToolResult.error(outcome)
                .put("operation", op) //$NON-NLS-1$
                .put("formFqn", formFqn) //$NON-NLS-1$
                .put("attributeName", attributeName) //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("operation", op) //$NON-NLS-1$
            .put("formFqn", formFqn) //$NON-NLS-1$
            .put("attributeName", attributeName) //$NON-NLS-1$
            .put("message", outcome == null ? "" : outcome) //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Runs Xtext-based validation on the query or expression this call is about to write.
     * <p>
     * <b>What is checked is decided by what the call carries, not by which operation it is.</b>
     * The list of operation names this used to switch on fell behind the operations twice over:
     * {@code set_dataset_property property=query} wrote a whole query through the generic setter
     * and was checked by nothing, and four expression writers - {@code set_calculated_field},
     * {@code set_total_field}, {@code add_user_field}, {@code set_user_field} - were absent from
     * the list because only their {@code add_} counterparts had been added to it. A list of names
     * has to be extended by hand every time an operation is added, and nothing fails when it is
     * not; asking the call what text it carries is right for operations that do not exist yet.
     * </p>
     *
     * @param op the operation being run.
     * @param params its arguments.
     * @param project the project whose model settles the check.
     * @return <code>null</code> on pass, or a JSON error carrying the {@code queryValidation} or
     *         {@code expressionValidation} tag.
     */
    private String preflightValidate(String op, Map<String, String> params, IProject project)
    {
        boolean validateQuery = JsonUtils.extractBooleanArgument(params, "validate_query", true); //$NON-NLS-1$
        boolean validateExpr = JsonUtils.extractBooleanArgument(params, "validate_expression", //$NON-NLS-1$
            true);
        if (validateQuery)
        {
            String queryText = queryThisCallWrites(op, params);
            if (queryText != null && !queryText.isEmpty())
            {
                QlValidator.ValidationResult vr = QlValidator.validateQueryText(project,
                    queryText, true);
                if (vr.unconfirmed)
                {
                    // Not the same as a clean check. Writing here would put text into the schema
                    // that nothing has vouched for. What the check did find still goes back: half
                    // an answer beats a bare reason when the half names the line to fix.
                    return ToolResult.error(op + " not attempted: " + vr.unavailableReason) //$NON-NLS-1$
                        .put("operation", op) //$NON-NLS-1$
                        .put(ErrorTags.QUERY_VALIDATION.wire(), vr.toTagData())
                        .toJson();
                }
                if (vr.hasErrors())
                {
                    return ToolResult
                        .error(op + " failed: the query has " + vr.errorCount //$NON-NLS-1$
                            + " error(s); fix and retry") //$NON-NLS-1$
                        .put("operation", op) //$NON-NLS-1$
                        .put(ErrorTags.QUERY_VALIDATION.wire(), vr.toTagData())
                        .toJson();
                }
            }
        }
        if (validateExpr)
        {
            for (String expression : expressionsThisCallWrites(op, params))
            {
                QlValidator.ValidationResult vr = QlValidator.validateExpression(project,
                    expression);
                if (vr.unconfirmed)
                {
                    return ToolResult.error(op + " not attempted: " + vr.unavailableReason) //$NON-NLS-1$
                        .put("operation", op) //$NON-NLS-1$
                        .put(ErrorTags.EXPRESSION_VALIDATION.wire(), vr.toTagData())
                        .toJson();
                }
                if (vr.hasErrors())
                {
                    return ToolResult
                        .error(op + " failed: expression has " + vr.errorCount //$NON-NLS-1$
                            + " error(s); fix and retry") //$NON-NLS-1$
                        .put("operation", op) //$NON-NLS-1$
                        .put(ErrorTags.EXPRESSION_VALIDATION.wire(), vr.toTagData())
                        .toJson();
                }
            }
        }
        return null;
    }

    /**
     * What an operation writes, and which argument carries it.
     * <p>
     * One schema serves all 63 operations, so a call may carry arguments the operation it names
     * will never look at - a reused argument map is enough. Deciding by argument alone would then
     * refuse an ordinary rename over a stale {@code queryText} beside it. Deciding by operation
     * alone is how this went wrong to begin with. Both, together, is what the handler actually
     * does.
     * </p>
     */
    private enum Written
    {
        /** Neither a query nor an expression. */
        NOTHING,

        /** A query, arriving as {@code queryText}. */
        QUERY_AS_QUERY_TEXT,

        /** A query, arriving as {@code value} - but only when the property named is the query. */
        QUERY_AS_VALUE_OF_THE_QUERY_PROPERTY,

        /** An expression, stored as given. */
        EXPRESSION,

        /** An expression with an aggregate composed into it before it is stored. */
        EXPRESSION_WITH_AN_AGGREGATE,

        /** Two expressions at once - a link joins two datasets and can constrain both ends. */
        EXPRESSIONS_OF_A_LINK,

        /** An expression arriving as {@code value}, when the property named is one of a link's. */
        EXPRESSION_AS_VALUE_OF_A_LINK_PROPERTY
    }

    /**
     * The operations that write a query or an expression, and how.
     * <p>
     * Only the writers are here; everything else answers {@link Written#NOTHING}. That default is
     * safe only because a test reconciles this map against the mutation registry and fails on any
     * operation classified in neither direction - see the test named for what a call writes. An
     * operation added without a decision made about it breaks that test rather than quietly
     * skipping its check, which is exactly how four expression writers and one query writer came
     * to be unchecked here.
     * </p>
     */
    private static final Map<String, Written> WRITES = writesRegistry();

    /**
     * Builds the classification above.
     *
     * @return the writers, by operation name.
     */
    private static Map<String, Written> writesRegistry()
    {
        Map<String, Written> writes = new LinkedHashMap<>();
        writes.put("add_dataset", Written.QUERY_AS_QUERY_TEXT); //$NON-NLS-1$
        writes.put("set_dataset_query", Written.QUERY_AS_QUERY_TEXT); //$NON-NLS-1$
        // A child of a union is a dataset like any other and carries a query of its own.
        writes.put("add_union_item", Written.QUERY_AS_QUERY_TEXT); //$NON-NLS-1$
        writes.put("set_dataset_property", Written.QUERY_AS_VALUE_OF_THE_QUERY_PROPERTY); //$NON-NLS-1$
        writes.put("add_calculated_field", Written.EXPRESSION); //$NON-NLS-1$
        writes.put("set_calculated_field", Written.EXPRESSION); //$NON-NLS-1$
        writes.put("set_total_field", Written.EXPRESSION); //$NON-NLS-1$
        writes.put("add_user_field", Written.EXPRESSION); //$NON-NLS-1$
        writes.put("set_user_field", Written.EXPRESSION); //$NON-NLS-1$
        // Both halves: applyParameterFields writes the expression, and add_parameter reaches it
        // through the same helper set_parameter does.
        writes.put("add_parameter", Written.EXPRESSION); //$NON-NLS-1$
        writes.put("set_parameter", Written.EXPRESSION); //$NON-NLS-1$
        writes.put("add_total", Written.EXPRESSION_WITH_AN_AGGREGATE); //$NON-NLS-1$
        // A link constrains a join between two datasets, and each end can carry an expression.
        // These arrive under their own argument names, which is how they went unnoticed while
        // every other expression writer was being found by searching for the word.
        writes.put("add_dataset_link", Written.EXPRESSIONS_OF_A_LINK); //$NON-NLS-1$
        writes.put("set_dataset_link_property", Written.EXPRESSION_AS_VALUE_OF_A_LINK_PROPERTY); //$NON-NLS-1$
        return writes;
    }

    /**
     * What this operation is classified as writing.
     *
     * @param op the operation being run.
     * @return its classification, never <code>null</code>.
     */
    static String writesForTest(String op)
    {
        return WRITES.getOrDefault(op, Written.NOTHING).name();
    }

    /**
     * The query text this call is about to write, if it writes one.
     *
     * @param op the operation being run.
     * @param params the call's arguments.
     * @return the text, or <code>null</code> when this call writes no query.
     */
    static String queryThisCallWrites(String op, Map<String, String> params)
    {
        switch (WRITES.getOrDefault(op, Written.NOTHING))
        {
        case QUERY_AS_QUERY_TEXT:
            return JsonUtils.extractStringArgument(params, "queryText"); //$NON-NLS-1$
        case QUERY_AS_VALUE_OF_THE_QUERY_PROPERTY:
            // This setter reaches any field by name, and only one of them is the query. A call
            // renaming a dataset carries value too, and checking that as a query would refuse it.
            if (QUERY_PROPERTY.equalsIgnoreCase(
                JsonUtils.extractStringArgument(params, "property"))) //$NON-NLS-1$
            {
                return JsonUtils.extractStringArgument(params, "value"); //$NON-NLS-1$
            }
            return null;
        default:
            return null;
        }
    }

    /**
     * The expressions this call is about to write, as they will be stored.
     * <p>
     * For {@code add_total} that is not the expression as given: an aggregate is composed into the
     * text before it is stored, so checking the argument checked something that was never written.
     * {@code expression=Amount, aggregateFunction=NoSuchFunction} passed while
     * {@code NoSuchFunction(Amount)} went into the schema unchecked.
     * </p>
     * <p>
     * A list rather than one text, because a dataset link can constrain both ends of the join at
     * once and stores an expression for each.
     * </p>
     *
     * @param op the operation being run.
     * @param params the call's arguments.
     * @return the texts, in the order they were found; empty when this call writes none.
     */
    static List<String> expressionsThisCallWrites(String op, Map<String, String> params)
    {
        List<String> written = new ArrayList<>();
        switch (WRITES.getOrDefault(op, Written.NOTHING))
        {
        case EXPRESSION:
            add(written, JsonUtils.extractStringArgument(params, "expression")); //$NON-NLS-1$
            break;
        case EXPRESSION_WITH_AN_AGGREGATE:
            String expression = JsonUtils.extractStringArgument(params, "expression"); //$NON-NLS-1$
            if (expression != null && !expression.isEmpty())
            {
                add(written, totalExpressionOf(expression,
                    JsonUtils.extractStringArgument(params, "aggregateFunction"))); //$NON-NLS-1$
            }
            break;
        case EXPRESSIONS_OF_A_LINK:
            // Both ends, and either may be absent. Checking one and storing two is the shape of
            // defect this whole method exists to close.
            add(written, JsonUtils.extractStringArgument(params, SOURCE_EXPRESSION));
            add(written, JsonUtils.extractStringArgument(params, DESTINATION_EXPRESSION));
            break;
        case EXPRESSION_AS_VALUE_OF_A_LINK_PROPERTY:
            // By the shape of the name, not by a list of them. This setter reaches any property
            // the link has, and the link has more expressions than the two an add_dataset_link
            // call can set - startExpression and linkConditionExpression among them. Naming the
            // ones known today would be one more list to fall behind, which is the whole reason
            // this classification exists.
            String property = JsonUtils.extractStringArgument(params, "property"); //$NON-NLS-1$
            if (property != null && property.toLowerCase().endsWith("expression")) //$NON-NLS-1$
            {
                add(written, JsonUtils.extractStringArgument(params, "value")); //$NON-NLS-1$
            }
            break;
        default:
            break;
        }
        return written;
    }

    /**
     * Adds a text to the list unless there is nothing there.
     *
     * @param texts the list to add to.
     * @param text the text, which may be <code>null</code> or empty.
     */
    private static void add(List<String> texts, String text)
    {
        if (text != null && !text.isEmpty())
        {
            texts.add(text);
        }
    }

    /**
     * Composes what a total field stores from the expression and the aggregate asked for.
     * <p>
     * A total field has no separate aggregate property - only dataPath, expression and groups - so
     * the aggregate is part of the expression text. An expression that already calls something is
     * taken verbatim.
     * </p>
     * <p>
     * One place on purpose: this used to live only inside the write, so the check upstream and the
     * write downstream disagreed about what was going to be stored.
     * </p>
     *
     * @param rawExpression the expression as given.
     * @param aggregateFunction the aggregate to compose in, or <code>null</code>.
     * @return the text that will be stored.
     */
    static String totalExpressionOf(String rawExpression, String aggregateFunction)
    {
        if (aggregateFunction != null && !aggregateFunction.isEmpty()
            && !rawExpression.contains("(")) //$NON-NLS-1$
        {
            return aggregateFunction + "(" + rawExpression + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return rawExpression;
    }

    /**
     * 1.43.x batch 4b: the three heuristic query-editing ops whose edit is a
     * lexical token-splice (flagged via {@code heuristicTextSplice} on success).
     */
    private static boolean isQuerySpliceOp(String op)
    {
        switch (op)
        {
            case "add_query_field": //$NON-NLS-1$
            case "remove_query_field": //$NON-NLS-1$
            case "add_query_condition": //$NON-NLS-1$
            case "remove_query_condition": //$NON-NLS-1$
                return true;
            default:
                return false;
        }
    }

    /**
     * Applies one schema-mutation operation on the resolved DCS schema. Called
     * inside the BM write transaction.
     */
    private Object applySchemaMutation(String op, Map<String, String> params, EObject schema,
        IProject project) throws Exception
    {
        MutationHandler handler = mutations.get(op);
        if (handler == null)
        {
            throw new RuntimeException("Internal: unhandled op '" + op + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return handler.apply(params, schema, project);
    }

    // -----------------------------------------------------------------------
    // DCS mutation handlers (Phase 5.3)
    // -----------------------------------------------------------------------

    /**
     * A DCS query/object dataset must reference a schema-level dataSource,
     * otherwise the report's schema editor does not render the dataset node
     * (and the settings tree won't build on it). Reuses the first existing
     * dataSource, or creates a default local one ("ИсточникДанных1" / Local).
     * Returns the dataSource name to bind, or {@code null} if unavailable.
     */
    private String ensureDefaultDataSource(EObject schema)
    {
        EList<EObject> sources = BmDcsHelper.getEObjectList(schema, "getDataSources"); //$NON-NLS-1$
        if (sources == null)
        {
            return null;
        }
        for (EObject existing : sources)
        {
            Object nm = invokeGetter(existing, "getName"); //$NON-NLS-1$
            if (nm != null && !nm.toString().isEmpty())
            {
                return nm.toString();
            }
        }
        Object src = BmDcsHelper.createElement("createDataCompositionSchemaDataSource"); //$NON-NLS-1$
        if (src == null)
        {
            return null;
        }
        BmDcsHelper.setProperty(src, "name", "ИсточникДанных1"); //$NON-NLS-1$ //$NON-NLS-2$
        BmDcsHelper.setProperty(src, "dataSourceType", "Local"); //$NON-NLS-1$ //$NON-NLS-2$
        sources.add((EObject) src);
        return "ИсточникДанных1"; //$NON-NLS-1$
    }

    /**
     * 1.43.x batch 5: adds a {@code DataCompositionSchemaDataSource}. Required
     * {@code name}; optional {@code dataSourceType} (default "Local") and
     * {@code connectionString}. Idempotent on name (alreadyExists tag). Closes the
     * "DataSource auto-created only" reconciliation gap - enables multi-source schemas.
     */
    private Object doAddDataSource(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String dsType = orDefault(JsonUtils.extractStringArgument(params, "dataSourceType"), "Local"); //$NON-NLS-1$ //$NON-NLS-2$
        String connStr = JsonUtils.extractStringArgument(params, "connectionString"); //$NON-NLS-1$
        if (BmDcsHelper.findByNameInList(schema, "getDataSources", name) != null) //$NON-NLS-1$
        {
            throw alreadyExistsTag(name, "dataSource"); //$NON-NLS-1$
        }
        Object src = BmDcsHelper.createElement("createDataCompositionSchemaDataSource"); //$NON-NLS-1$
        if (src == null)
        {
            throw new RuntimeException("DcsFactory.createDataCompositionSchemaDataSource not available"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(src, "name", name); //$NON-NLS-1$
        BmDcsHelper.setProperty(src, "dataSourceType", dsType); //$NON-NLS-1$
        if (connStr != null && !connStr.isEmpty())
        {
            BmDcsHelper.setProperty(src, "connectionString", connStr); //$NON-NLS-1$
        }
        EList<EObject> sources = BmDcsHelper.getEObjectList(schema, "getDataSources"); //$NON-NLS-1$
        if (sources == null)
        {
            throw new RuntimeException("Schema.getDataSources() not available"); //$NON-NLS-1$
        }
        sources.add((EObject) src);
        return name + " (type=" + dsType + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.43.x batch 5: removes a data source by name (notFound tag when absent).
     * Surfaces a warning in the message when data sets still reference it by name
     * (those would dangle - repoint them via set_data_set_property).
     */
    private Object doRemoveDataSource(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        EList<EObject> sources = BmDcsHelper.getEObjectList(schema, "getDataSources"); //$NON-NLS-1$
        if (sources == null)
        {
            throw new RuntimeException("Schema.getDataSources() not available"); //$NON-NLS-1$
        }
        EObject existing = BmDcsHelper.findByNameInList(schema, "getDataSources", name); //$NON-NLS-1$
        if (existing == null)
        {
            throw notFoundTag(name, "dataSource"); //$NON-NLS-1$
        }
        int referencing = countDataSetsReferencing(schema, name);
        sources.remove(existing);
        return referencing == 0 ? name
            : name + " (WARNING: " + referencing //$NON-NLS-1$
                + " data set(s) still reference it - repoint via set_data_set_property)"; //$NON-NLS-1$
    }

    /**
     * 1.43.x batch 5: updates {@code dataSourceType} and/or {@code connectionString}
     * on an existing data source (notFound tag when absent). Rename is intentionally
     * not supported here - data sets reference the source by name, so a rename would
     * need a cascade; remove + add (then repoint) is the explicit path.
     */
    private Object doSetDataSourceProperty(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        EObject existing = BmDcsHelper.findByNameInList(schema, "getDataSources", name); //$NON-NLS-1$
        if (existing == null)
        {
            throw notFoundTag(name, "dataSource"); //$NON-NLS-1$
        }
        List<String> changed = new java.util.ArrayList<>();
        String dsType = JsonUtils.extractStringArgument(params, "dataSourceType"); //$NON-NLS-1$
        if (dsType != null && !dsType.isEmpty())
        {
            BmDcsHelper.setProperty(existing, "dataSourceType", dsType); //$NON-NLS-1$
            changed.add("dataSourceType"); //$NON-NLS-1$
        }
        String connStr = JsonUtils.extractStringArgument(params, "connectionString"); //$NON-NLS-1$
        if (connStr != null && !connStr.isEmpty())
        {
            BmDcsHelper.setProperty(existing, "connectionString", connStr); //$NON-NLS-1$
            changed.add("connectionString"); //$NON-NLS-1$
        }
        if (changed.isEmpty())
        {
            return name + " (no recognised properties passed - pass dataSourceType / connectionString)"; //$NON-NLS-1$
        }
        return name + " (updated: " + changed + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Counts data sets whose {@code dataSource} property equals {@code dataSourceName}. */
    private int countDataSetsReferencing(EObject schema, String dataSourceName)
    {
        EList<EObject> dataSets = BmDcsHelper.getEObjectList(schema, "getDataSets"); //$NON-NLS-1$
        if (dataSets == null)
        {
            return 0;
        }
        int count = 0;
        for (EObject ds : dataSets)
        {
            Object dsName = invokeGetter(ds, "getDataSource"); //$NON-NLS-1$
            if (dsName != null && dataSourceName.equals(dsName.toString()))
            {
                count++;
            }
        }
        return count;
    }

    private Object doAddDataSet(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String queryText = JsonUtils.extractStringArgument(params, "queryText"); //$NON-NLS-1$
        String dataSetType = orDefault(JsonUtils.extractStringArgument(params, "dataSetType"), //$NON-NLS-1$
            "Query"); //$NON-NLS-1$
        if (BmDcsHelper.findByNameInList(schema, "getDataSets", name) != null) //$NON-NLS-1$
        {
            throw alreadyExistsTag(name, "dataSet"); //$NON-NLS-1$
        }
        String factoryMethod = "createDataCompositionSchemaDataSet" + dataSetType; //$NON-NLS-1$
        Object dataSet = BmDcsHelper.createElement(factoryMethod);
        if (dataSet == null)
        {
            throw new RuntimeException("DcsFactory." + factoryMethod + " not available"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        BmDcsHelper.setProperty(dataSet, "name", name); //$NON-NLS-1$
        if (queryText != null && !queryText.isEmpty())
        {
            BmDcsHelper.setProperty(dataSet, "query", queryText); //$NON-NLS-1$
        }
        // Bind the dataset to a (default local) dataSource so the schema editor
        // renders it. Query/Object datasets carry a dataSource reference; Union
        // datasets don't have the property, so the set is a no-op there.
        String dataSourceName = ensureDefaultDataSource(schema);
        if (dataSourceName != null)
        {
            BmDcsHelper.setProperty(dataSet, "dataSource", dataSourceName); //$NON-NLS-1$
        }
        EList<EObject> dataSets = BmDcsHelper.getEObjectList(schema, "getDataSets"); //$NON-NLS-1$
        if (dataSets == null)
        {
            throw new RuntimeException("Schema.getDataSets() not available"); //$NON-NLS-1$
        }
        dataSets.add((EObject) dataSet);
        return name;
    }

    /**
     * Adds a nested schema to the schema root.
     * <p>
     * A nested schema is a composition schema of its own, named and addressed from the outer one.
     * It is created empty: what goes inside it is written by the same operations that write the
     * outer schema, aimed at it by name.
     * </p>
     *
     * @param params name, and optionally title and url.
     * @param schema the schema root.
     * @return the name written
     */
    /**
     * Writes a property and refuses when it did not take.
     * <p>
     * The setter is found by name, so a name the model spells differently reports back that the
     * property is absent. Discarding that answer leaves a call that says it wrote something it did
     * not.
     * </p>
     *
     * @param target what to write to.
     * @param property the property name as the model spells it.
     * @param value the value.
     */
    private static void mustSet(Object target, String property, Object value)
    {
        String failed = BmDcsHelper.setProperty(target, property, value);
        if (failed != null)
        {
            throw new RuntimeException(failed);
        }
    }

    private Object doAddNestedSchema(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String url = JsonUtils.extractStringArgument(params, "url"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        EList<EObject> nested = BmDcsHelper.getEObjectList(schema, "getNestedSchemas"); //$NON-NLS-1$
        if (nested == null)
        {
            throw new RuntimeException("Schema.getNestedSchemas() not available"); //$NON-NLS-1$
        }
        if (BmDcsHelper.findByNameInList(schema, "getNestedSchemas", name) != null) //$NON-NLS-1$
        {
            throw alreadyExistsTag(name, "nestedSchema"); //$NON-NLS-1$
        }
        mustNotLookLikeAPath(name, "nested schema"); //$NON-NLS-1$
        Object entry = BmDcsHelper.createElement("createNestedDataCompositionSchema"); //$NON-NLS-1$
        if (entry == null)
        {
            throw new RuntimeException(
                "DcsFactory.createNestedDataCompositionSchema not available"); //$NON-NLS-1$
        }
        mustSet(entry, "name", name); //$NON-NLS-1$
        if (url != null && !url.isEmpty())
        {
            // The model spells this one URL, and the setter name is built by upper-casing the
            // first letter alone - so "url" would ask for a setter that is not there.
            mustSet(entry, "URL", url); //$NON-NLS-1$
        }
        if (title != null && !title.isEmpty())
        {
            setPresentationProperty(entry, "title", title); //$NON-NLS-1$
            Object written = invokeGetter(entry, "getTitle"); //$NON-NLS-1$
            // The carrier is built even when the text does not go into it, so its presence proves
            // nothing - the text has to come back.
            Object text = written == null ? null : invokeGetter(written, "getValue"); //$NON-NLS-1$
            if (text == null || !title.equals(String.valueOf(text)))
            {
                throw new RuntimeException("the title could not be written"); //$NON-NLS-1$
            }
        }
        // Without a schema of its own the entry names nothing: the outer schema would carry a
        // nested schema that has no datasets and no fields to address, and nestedSchemaName would
        // refuse it afterwards.
        Object inner = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        if (inner == null)
        {
            throw new RuntimeException(
                "DcsFactory.createDataCompositionSchema not available"); //$NON-NLS-1$
        }
        mustSet(entry, "schema", inner); //$NON-NLS-1$
        nested.add((EObject)entry);
        // What the export must contain for this to have held, and how many the collection came to
        // hold - the write guards read both and refuse a change that did not reach the file.
        return new BmDcsHelper.Wrote(name, asWrittenInTheFile(name), nested.size(),
            "nestedSchemas"); //$NON-NLS-1$
    }

    /**
     * Removes a nested schema by name.
     *
     * @param params the name.
     * @param schema the schema root.
     * @return the name removed
     */
    private Object doRemoveNestedSchema(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        EList<EObject> nested = BmDcsHelper.getEObjectList(schema, "getNestedSchemas"); //$NON-NLS-1$
        if (nested == null)
        {
            throw new RuntimeException("Schema.getNestedSchemas() not available"); //$NON-NLS-1$
        }
        Object found = BmDcsHelper.findByNameInList(schema, "getNestedSchemas", name); //$NON-NLS-1$
        if (found == null)
        {
            throw notFoundTag(name, "nestedSchema"); //$NON-NLS-1$
        }
        nested.remove(found);
        // Nothing has to APPEAR for a removal, so the count is the only thing the guards can check
        // - and without it a removal that did not reach the file reports success.
        return new BmDcsHelper.Wrote(name, null, nested.size(), "nestedSchemas"); //$NON-NLS-1$
    }

    /**
     * Adds a child dataset to a union.
     * <p>
     * A union holds datasets rather than a query of its own, which is why replacing the query of a
     * union is refused and sends the caller here.
     * </p>
     *
     * @param params dataSetName of the union, name of the child, and its type and query.
     * @param schema the schema root.
     * @return what was added, and to what
     */
    private Object doAddUnionItem(Map<String, String> params, EObject schema)
    {
        String unionName = required(params, "dataSetName"); //$NON-NLS-1$
        String name = required(params, "name"); //$NON-NLS-1$
        String queryText = JsonUtils.extractStringArgument(params, "queryText"); //$NON-NLS-1$
        String dataSetType = orDefault(JsonUtils.extractStringArgument(params, "dataSetType"), //$NON-NLS-1$
            "Query"); //$NON-NLS-1$
        EList<EObject> items = unionItemsOf(schema, unionName);
        for (EObject existing : items)
        {
            if (name.equalsIgnoreCase(String.valueOf(invokeGetter(existing, "getName")))) //$NON-NLS-1$
            {
                throw alreadyExistsTag(name, "unionItem"); //$NON-NLS-1$
            }
        }
        mustNotLookLikeAPath(name, "union item"); //$NON-NLS-1$
        String factoryMethod = "createDataCompositionSchemaDataSet" + dataSetType; //$NON-NLS-1$
        Object child = BmDcsHelper.createElement(factoryMethod);
        if (child == null)
        {
            throw new RuntimeException("DcsFactory." + factoryMethod + " not available"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        mustSet(child, "name", name); //$NON-NLS-1$
        if (queryText != null && !queryText.isEmpty())
        {
            mustSet(child, "query", queryText); //$NON-NLS-1$
        }
        else if ("Query".equalsIgnoreCase(dataSetType)) //$NON-NLS-1$
        {
            // set_dataset_query reaches only the datasets of the schema, so a child left without a
            // query here has no way of ever getting one.
            throw new RuntimeException("a Query dataset inside a union cannot be given its query " //$NON-NLS-1$
                + "afterwards - pass queryText"); //$NON-NLS-1$
        }
        // Its own argument, because objectName already names the report or data processor whose
        // schema is being edited - reusing it would write that FQN into the child.
        String dataObjectName = JsonUtils.extractStringArgument(params, "dataObjectName"); //$NON-NLS-1$
        if (dataObjectName != null && !dataObjectName.isEmpty())
        {
            mustSet(child, "objectName", dataObjectName); //$NON-NLS-1$
        }
        else if ("Object".equalsIgnoreCase(dataSetType)) //$NON-NLS-1$
        {
            // An Object dataset reads a named object, and nothing else names it. Created without
            // one it is a child that can never be finished, because a dataset inside a union is
            // not among the datasets the other operations reach.
            throw new RuntimeException(
                "an Object dataset reads a named object - pass dataObjectName"); //$NON-NLS-1$
        }
        String dataSourceName = ensureDefaultDataSource(schema);
        if (dataSourceName != null && !"Union".equalsIgnoreCase(dataSetType)) //$NON-NLS-1$
        {
            // A union has no data source of its own; every other kind needs one, or the schema
            // editor does not render it.
            mustSet(child, "dataSource", dataSourceName); //$NON-NLS-1$
        }
        items.add((EObject)child);
        return new BmDcsHelper.Wrote(name + " in " + unionName, asWrittenInTheFile(name), //$NON-NLS-1$
            items.size(), "items of " + unionName); //$NON-NLS-1$
    }

    /**
     * Removes a child dataset from a union.
     *
     * @param params dataSetName of the union and name of the child.
     * @param schema the schema root.
     * @return what was removed, and from what
     */
    private Object doRemoveUnionItem(Map<String, String> params, EObject schema)
    {
        String unionName = required(params, "dataSetName"); //$NON-NLS-1$
        String name = required(params, "name"); //$NON-NLS-1$
        EList<EObject> items = unionItemsOf(schema, unionName);
        for (EObject existing : items)
        {
            if (name.equalsIgnoreCase(String.valueOf(invokeGetter(existing, "getName")))) //$NON-NLS-1$
            {
                items.remove(existing);
                return new BmDcsHelper.Wrote(name + " from " + unionName, null, items.size(), //$NON-NLS-1$
                    "items of " + unionName); //$NON-NLS-1$
            }
        }
        throw notFoundTag(name, "unionItem"); //$NON-NLS-1$
    }

    /**
     * The child datasets of the named union.
     *
     * @param schema the schema root.
     * @param unionName the dataset that should be a union.
     * @return its children, never <code>null</code>
     */
    private EList<EObject> unionItemsOf(EObject schema, String unionName)
    {
        // A union may hold a union, so the name may be a path down through them: Outer.Inner. A
        // child of a union is not among the datasets of the schema, so without this a union
        // created inside another one could never be filled. The name is taken whole first, so a
        // dataset whose own name contains a dot still addresses itself.
        String[] steps;
        if (BmDcsHelper.findByNameInList(schema, "getDataSets", unionName) != null) //$NON-NLS-1$
        {
            steps = new String[] {unionName};
        }
        else
        {
            mustNotBeAmbiguous(schema, "getDataSets", unionName); //$NON-NLS-1$
            steps = pathSteps(unionName, "dataset"); //$NON-NLS-1$
        }
        Object dataSet = BmDcsHelper.findByNameInList(schema, "getDataSets", steps[0]); //$NON-NLS-1$
        if (dataSet == null)
        {
            throw notFoundTag(steps[0], "dataSet"); //$NON-NLS-1$
        }
        EList<EObject> items = itemsOfUnion(dataSet, steps[0]);
        for (int i = 1; i < steps.length; i++)
        {
            Object child = null;
            for (EObject candidate : items)
            {
                if (steps[i].equalsIgnoreCase(String.valueOf(invokeGetter(candidate, "getName")))) //$NON-NLS-1$
                {
                    child = candidate;
                    break;
                }
            }
            if (child == null)
            {
                throw notFoundTag(steps[i], "unionItem"); //$NON-NLS-1$
            }
            items = itemsOfUnion(child, steps[i]);
        }
        return items;
    }

    /**
     * The datasets the given one holds, or a refusal naming it.
     *
     * @param dataSet the dataset that should be a union.
     * @param name what it was called in the request.
     * @return its children
     */
    private EList<EObject> itemsOfUnion(Object dataSet, String name)
    {
        EList<EObject> items = BmDcsHelper.getEObjectList(dataSet, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            // Only a union holds datasets. Saying which one was asked for beats a message about a
            // method that is missing, which reads as a defect rather than as the wrong target.
            throw new RuntimeException("'" + name + "' is not a Union dataset - only a Union " //$NON-NLS-1$ //$NON-NLS-2$
                + "holds other datasets. Create it with add_dataset dataSetType=Union."); //$NON-NLS-1$
        }
        return items;
    }

    private Object doRemoveDataSet(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        EList<EObject> dataSets = BmDcsHelper.getEObjectList(schema, "getDataSets"); //$NON-NLS-1$
        if (dataSets == null)
        {
            throw new RuntimeException("Schema.getDataSets() not available"); //$NON-NLS-1$
        }
        EObject existing = BmDcsHelper.findByNameInList(schema, "getDataSets", name); //$NON-NLS-1$
        if (existing == null)
        {
            // Idempotent: missing dataset is recorded in tags, success returns.
            // The helper Result.tags surfaces notFound via the catch block; we
            // signal the case by throwing and the helper unwraps verdict.
            throw notFoundTag(name, "dataSet"); //$NON-NLS-1$
        }
        dataSets.remove(existing);
        // Cascade: remove calculated/total fields whose expression starts with "<name>."
        int removedCalc = removeFieldsReferencing(schema, "getCalculatedFields", name); //$NON-NLS-1$
        int removedTotal = removeFieldsReferencing(schema, "getTotalFields", name); //$NON-NLS-1$
        return name + " (cascade: removed " + removedCalc //$NON-NLS-1$
            + " calculated, " + removedTotal + " total)"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private int removeFieldsReferencing(EObject schema, String getter, String dataSetName)
    {
        EList<EObject> list = BmDcsHelper.getEObjectList(schema, getter);
        if (list == null)
        {
            return 0;
        }
        int removed = 0;
        Iterator<EObject> it = list.iterator();
        while (it.hasNext())
        {
            EObject field = it.next();
            try
            {
                java.lang.reflect.Method getExpr = field.getClass().getMethod("getExpression"); //$NON-NLS-1$
                Object expr = getExpr.invoke(field);
                if (expr != null && expr.toString().contains(dataSetName + ".")) //$NON-NLS-1$
                {
                    it.remove();
                    removed++;
                }
            }
            catch (Exception ignored)
            {
                // type does not have getExpression - skip
            }
        }
        return removed;
    }

    /**
     * Finds a dataset field by the path it carries.
     *
     * @param dataSet the dataset to search, never {@code null}
     * @param dataPath the path to look for, never {@code null}
     * @return the field carrying that path, or {@code null} when the dataset has none
     */
    private EObject findFieldByDataPath(EObject dataSet, String dataPath)
    {
        EList<EObject> existing = BmDcsHelper.getEObjectList(dataSet, "getFields"); //$NON-NLS-1$
        if (existing == null)
        {
            return null;
        }
        for (EObject f : existing)
        {
            Object dp = invokeGetter(f, "getDataPath"); //$NON-NLS-1$
            if (dp != null && dataPath.equals(dp.toString()))
            {
                return f;
            }
        }
        return null;
    }

    private Object doAddField(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String dataSetName = required(params, "dataSetName"); //$NON-NLS-1$
        EObject dataSet = BmDcsHelper.findByNameInList(schema, "getDataSets", dataSetName); //$NON-NLS-1$
        if (dataSet == null)
        {
            throw notFoundTag(dataSetName, "dataSet"); //$NON-NLS-1$
        }
        // A dataset field is identified by its dataPath, not by a name: the class has no getName
        // at all. The lookup by name therefore matched nothing and the check never fired, so a
        // repeated call added a second field carrying the same dataPath - measured 31.08, the
        // same name stood twice in the .dcs. A dataset with two identically pathed fields is not
        // valid. The user-field path next door already compares getDataPath.
        if (findFieldByDataPath(dataSet, name) != null)
        {
            throw alreadyExistsTag(name, "field"); //$NON-NLS-1$
        }
        Object field = BmDcsHelper.createElement("createDataCompositionSchemaDataSetField"); //$NON-NLS-1$
        if (field == null)
        {
            throw new RuntimeException("DcsFactory.createDataCompositionSchemaDataSetField not available"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(field, "dataPath", name); //$NON-NLS-1$
        BmDcsHelper.setProperty(field, "field", name); //$NON-NLS-1$
        EList<EObject> fields = BmDcsHelper.getEObjectList(dataSet, "getFields"); //$NON-NLS-1$
        if (fields == null)
        {
            throw new RuntimeException("DataSet.getFields() not available"); //$NON-NLS-1$
        }
        fields.add((EObject) field);
        // The dataPath is what the serialization carries for THIS field, and it is compared with
        // its delimiters so a longer name that contains it does not answer for it.
        return new BmDcsHelper.Wrote(dataSetName + "." + name, ">" + name + "<", fields.size(), //$NON-NLS-1$ //$NON-NLS-2$
            "dataSet:" + dataSetName); //$NON-NLS-1$
    }

    private Object doAddParameter(Map<String, String> params, EObject schema, IProject project)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        if (BmDcsHelper.findByNameInList(schema, "getParameters", name) != null) //$NON-NLS-1$
        {
            throw alreadyExistsTag(name, "parameter"); //$NON-NLS-1$
        }
        Object parameter = BmDcsHelper.createElement("createDataCompositionSchemaParameter"); //$NON-NLS-1$
        if (parameter == null)
        {
            throw new RuntimeException("DcsFactory.createDataCompositionSchemaParameter not available"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(parameter, "name", name); //$NON-NLS-1$
        applyParameterFields(parameter, params, project);
        EList<EObject> parameters = BmDcsHelper.getEObjectList(schema, "getParameters"); //$NON-NLS-1$
        if (parameters == null)
        {
            throw new RuntimeException("Schema.getParameters() not available"); //$NON-NLS-1$
        }
        parameters.add((EObject) parameter);
        return name;
    }

    private Object doSetParameter(Map<String, String> params, EObject schema, IProject project)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        EObject parameter = BmDcsHelper.findByNameInList(schema, "getParameters", name); //$NON-NLS-1$
        if (parameter == null)
        {
            throw notFoundTag(name, "parameter"); //$NON-NLS-1$
        }
        // Name change is intentionally ignored (matching conventional behavior).
        applyParameterFields(parameter, params, project);
        return name + " updated"; //$NON-NLS-1$
    }

    private void applyParameterFields(Object parameter, Map<String, String> params, IProject project)
    {
        // Optional fields: type, length, precision, expression, title, use,
        // valueListAllowed, denyIncompleteValues, useRestriction.
        // title is a Presentation object (not a String) - build via the core factory.
        setPresentationProperty(parameter, "title", JsonUtils.extractStringArgument(params, "title")); //$NON-NLS-1$ //$NON-NLS-2$
        applyOptionalProperty(parameter, "expression", params, "expression"); //$NON-NLS-1$ //$NON-NLS-2$
        applyOptionalProperty(parameter, "use", params, "use"); //$NON-NLS-1$ //$NON-NLS-2$
        applyOptionalProperty(parameter, "valueListAllowed", params, //$NON-NLS-1$
            "valueListAllowed"); //$NON-NLS-1$
        applyOptionalProperty(parameter, "denyIncompleteValues", params, //$NON-NLS-1$
            "denyIncompleteValues"); //$NON-NLS-1$
        applyOptionalProperty(parameter, "length", params, "length"); //$NON-NLS-1$ //$NON-NLS-2$
        applyOptionalProperty(parameter, "precision", params, "precision"); //$NON-NLS-1$ //$NON-NLS-2$
        // valueType (the parameter's value type) is a TypeDescription - wire it like
        // object-attribute types so the editor shows a typed parameter.
        applyParameterType(parameter, JsonUtils.extractStringArgument(params, "type"), project); //$NON-NLS-1$
    }

    /**
     * Sets the parameter's {@code valueType} (a TypeDescription) from a type FQN
     * (Date / Number / String / Boolean / CatalogRef.X / ...), reusing the
     * object-attribute typing machinery ({@code setTypesOnDescription}). No-op when
     * type/project/config is absent or the mcore TypeDescription factory is
     * unavailable - the parameter is still created, just untyped.
     */
    private void applyParameterType(Object parameter, String type, IProject project)
    {
        if (type == null || type.isEmpty() || project == null || !(parameter instanceof EObject))
        {
            return;
        }
        IConfigurationProvider cp = Activator.getDefault().getConfigurationProvider();
        Configuration config = cp != null ? cp.getConfiguration(project) : null;
        if (config == null)
        {
            return;
        }
        Object typeDesc = invokeGetter(parameter, "getValueType"); //$NON-NLS-1$
        if (typeDesc == null)
        {
            typeDesc = BmDcsHelper.createMcoreTypeDescription();
            if (typeDesc == null)
            {
                return;
            }
            BmDcsHelper.setProperty(parameter, "valueType", typeDesc); //$NON-NLS-1$
        }
        BmDefinedTypeHelper.TypesResult tr = BmDefinedTypeHelper.setTypesOnDescription(typeDesc,
            project, config, java.util.Collections.singletonList(type), null, (EObject) parameter);
        if (tr != null && tr.error != null)
        {
            Activator.logWarning("dcs_workshop add_parameter type: " + tr.error); //$NON-NLS-1$
        }
    }

    private void applyOptionalProperty(Object target, String propertyName,
        Map<String, String> params, String paramKey)
    {
        if (params == null || !params.containsKey(paramKey))
        {
            return;
        }
        String value = JsonUtils.extractStringArgument(params, paramKey);
        if (value == null)
        {
            return;
        }
        String err = BmDcsHelper.setProperty(target, propertyName, value);
        if (err != null)
        {
            Activator.logWarning("dcs_workshop: " + err); //$NON-NLS-1$
        }
    }

    /**
     * 1.43.x DCS catch-up: sets a {@code DataCompositionField}-typed property
     * (filter/order/selection/grouping {@code field}). DCS field properties are
     * DataCompositionField value-carriers, not Strings - a String silently fails
     * to persist. Built via the core factory; falls back to a String set when the
     * core factory is unavailable on the runtime.
     */
    private void setFieldProperty(Object target, String property, String fieldPath)
    {
        Object dcField = BmDcsHelper.createDataCompositionField(fieldPath);
        String err = BmDcsHelper.setProperty(target, property, dcField != null ? dcField : fieldPath);
        if (err != null)
        {
            Activator.logWarning("dcs_workshop setField " + property + ": " + err); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * 1.43.x DCS catch-up: extracts the dotted field path from a value stored as a
     * {@code DataCompositionField} (via its {@code getValue()}), falling back to
     * {@code toString()} for legacy String storage. Needed because fields are now
     * stored as DataCompositionField objects - matching a removal request against
     * {@code getField().toString()} would no longer equal the raw path string.
     */
    private String fieldPathOf(Object fieldObj)
    {
        if (fieldObj == null)
        {
            return null;
        }
        Object v = invokeGetter(fieldObj, "getValue"); //$NON-NLS-1$
        return v != null ? v.toString() : fieldObj.toString();
    }

    /**
     * 1.43.x DCS catch-up: sets a {@code Presentation}-typed property
     * ({@code title}/{@code presentation}) via the core factory. No-op for null/empty.
     */
    private void setPresentationProperty(Object target, String property, String text)
    {
        if (text == null || text.isEmpty())
        {
            return;
        }
        Object p = BmDcsHelper.createPresentation(text);
        String err = BmDcsHelper.setProperty(target, property, p != null ? p : text);
        if (err != null)
        {
            Activator.logWarning("dcs_workshop setPresentation " + property + ": " + err); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private Object doRemoveParameter(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        EList<EObject> parameters = BmDcsHelper.getEObjectList(schema, "getParameters"); //$NON-NLS-1$
        if (parameters == null)
        {
            throw new RuntimeException("Schema.getParameters() not available"); //$NON-NLS-1$
        }
        EObject existing = BmDcsHelper.findByNameInList(schema, "getParameters", name); //$NON-NLS-1$
        if (existing == null)
        {
            throw notFoundTag(name, "parameter"); //$NON-NLS-1$
        }
        parameters.remove(existing);
        return name;
    }

    private Object doMoveParameter(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String direction = JsonUtils.extractStringArgument(params, "direction"); //$NON-NLS-1$
        Integer newIndex = extractInteger(params, "newIndex"); //$NON-NLS-1$
        EList<EObject> parameters = BmDcsHelper.getEObjectList(schema, "getParameters"); //$NON-NLS-1$
        if (parameters == null)
        {
            throw new RuntimeException("Schema.getParameters() not available"); //$NON-NLS-1$
        }
        int oldIdx = -1;
        for (int i = 0; i < parameters.size(); i++)
        {
            EObject p = parameters.get(i);
            try
            {
                Object n = p.getClass().getMethod("getName").invoke(p); //$NON-NLS-1$
                if (n != null && name.equalsIgnoreCase(n.toString()))
                {
                    oldIdx = i;
                    break;
                }
            }
            catch (Exception ignored)
            {
                // skip
            }
        }
        if (oldIdx == -1)
        {
            throw notFoundTag(name, "parameter"); //$NON-NLS-1$
        }
        int targetIdx;
        if (newIndex != null)
        {
            targetIdx = newIndex.intValue();
        }
        else if ("Up".equalsIgnoreCase(direction)) //$NON-NLS-1$
        {
            targetIdx = Math.max(0, oldIdx - 1);
        }
        else if ("Down".equalsIgnoreCase(direction)) //$NON-NLS-1$
        {
            targetIdx = Math.min(parameters.size() - 1, oldIdx + 1);
        }
        else
        {
            throw new RuntimeException("Provide direction=Up|Down or newIndex=<int>"); //$NON-NLS-1$
        }
        if (targetIdx < 0 || targetIdx >= parameters.size())
        {
            throw new RuntimeException("newIndex out of range: " + targetIdx); //$NON-NLS-1$
        }
        if (targetIdx != oldIdx)
        {
            parameters.move(targetIdx, oldIdx);
        }
        return name + " moved from " + oldIdx + " to " + targetIdx; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Object doAddCalculatedField(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String expression = required(params, "expression"); //$NON-NLS-1$
        if (BmDcsHelper.findByNameInList(schema, "getCalculatedFields", name) != null) //$NON-NLS-1$
        {
            throw alreadyExistsTag(name, "calculatedField"); //$NON-NLS-1$
        }
        Object field = BmDcsHelper.createElement("createDataCompositionSchemaCalculatedField"); //$NON-NLS-1$
        if (field == null)
        {
            throw new RuntimeException("DcsFactory.createDataCompositionSchemaCalculatedField not available"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(field, "dataPath", name); //$NON-NLS-1$
        BmDcsHelper.setProperty(field, "expression", expression); //$NON-NLS-1$
        setPresentationProperty(field, "title", JsonUtils.extractStringArgument(params, "title")); //$NON-NLS-1$ //$NON-NLS-2$
        EList<EObject> calc = BmDcsHelper.getEObjectList(schema, "getCalculatedFields"); //$NON-NLS-1$
        if (calc == null)
        {
            throw new RuntimeException("Schema.getCalculatedFields() not available"); //$NON-NLS-1$
        }
        calc.add((EObject) field);
        return name;
    }

    private Object doAddTotal(Map<String, String> params, EObject schema)
    {
        String rawExpression = required(params, "expression"); //$NON-NLS-1$
        String aggregateFunction = JsonUtils.extractStringArgument(params, "aggregateFunction"); //$NON-NLS-1$
        Object field = BmDcsHelper.createElement("createDataCompositionSchemaTotalField"); //$NON-NLS-1$
        if (field == null)
        {
            throw new RuntimeException("DcsFactory.createDataCompositionSchemaTotalField not available"); //$NON-NLS-1$
        }
        String expression = totalExpressionOf(rawExpression, aggregateFunction);
        BmDcsHelper.setProperty(field, "expression", expression); //$NON-NLS-1$
        applyOptionalProperty(field, "dataPath", params, "name"); //$NON-NLS-1$ //$NON-NLS-2$
        EList<EObject> totals = BmDcsHelper.getEObjectList(schema, "getTotalFields"); //$NON-NLS-1$
        if (totals == null)
        {
            throw new RuntimeException("Schema.getTotalFields() not available"); //$NON-NLS-1$
        }
        totals.add((EObject) field);
        return "total: " + expression; //$NON-NLS-1$
    }

    private Object doAddAppearance(Map<String, String> params, EObject schema)
    {
        String conditionType = orDefault(
            JsonUtils.extractStringArgument(params, "conditionType"), "Equal"); //$NON-NLS-1$ //$NON-NLS-2$
        String conditionValue = JsonUtils.extractStringArgument(params, "conditionValue"); //$NON-NLS-1$
        // Appearance properties are received as a string in 1.37: "Font=Arial,12,bold;TextColor=#FF0000".
        // The font/color guard rejects values that look like JSON objects/arrays
        // (lesson learned: agents often send {"bold": true} which corrupts MXL).
        String appearanceSpec = JsonUtils.extractStringArgument(params, "appearance"); //$NON-NLS-1$
        String appearanceTrim = appearanceSpec != null ? appearanceSpec.trim() : null;
        if (appearanceTrim != null
            && (appearanceTrim.startsWith("{") || appearanceTrim.startsWith("["))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            throw fontColorGuard(appearanceSpec);
        }
        Object apSettings = ensureDefaultSettings(schema);
        if (apSettings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        Object container = ensureChild(apSettings, "getConditionalAppearance", //$NON-NLS-1$
            "createDataCompositionConditionalAppearance", "conditionalAppearance"); //$NON-NLS-1$ //$NON-NLS-2$
        if (container == null)
        {
            throw new RuntimeException("Could not create ConditionalAppearance container"); //$NON-NLS-1$
        }
        Object item = BmDcsHelper.createElement("createDataCompositionConditionalAppearanceItem"); //$NON-NLS-1$
        if (item == null)
        {
            throw new RuntimeException("DcsFactory.createDataCompositionConditionalAppearanceItem not available"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(item, "useInGrouping", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        BmDcsHelper.setProperty(item, "useInTable", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        BmDcsHelper.setProperty(item, "useInChart", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        if (conditionValue != null)
        {
            // Attach a single filter item to .condition - best-effort via reflection.
            Object condition = invokeGetter(item, "getFilter"); //$NON-NLS-1$
            if (condition != null)
            {
                Object filterItem = BmDcsHelper.createElement("createDataCompositionFilterItem"); //$NON-NLS-1$
                if (filterItem != null)
                {
                    // 1.43.x batch 4a: comparisonType is an enum (works). The right
                    // value is an EList<Value> (no setter) - the legacy rightValue
                    // setter does not exist and was a silent no-op. Append an mcore
                    // StringValue literal. No LEFT field is wired here: this handler
                    // exposes only conditionValue, not a condition field.
                    BmDcsHelper.setProperty(filterItem, "comparisonType", conditionType); //$NON-NLS-1$
                    Object rv = BmDcsHelper.createLiteralValue(conditionValue);
                    EList<EObject> rightList = BmDcsHelper.getEObjectList(filterItem, "getRight"); //$NON-NLS-1$
                    if (rv != null && rightList != null)
                    {
                        rightList.add((EObject) rv);
                    }
                    EList<EObject> items = BmDcsHelper.getEObjectList(condition, "getItems"); //$NON-NLS-1$
                    if (items != null)
                    {
                        items.add((EObject) filterItem);
                    }
                }
            }
        }
        // 1.43.x batch 4b: apply the font/color/format spec to the item's
        // appearance container (DataCompositionAppearance), routing each entry
        // through the appearance-parameter list. Skipped style/system refs are
        // surfaced in the message.
        List<String> skippedAppearance = java.util.Collections.emptyList();
        if (appearanceSpec != null && !appearanceSpec.trim().isEmpty())
        {
            Object itemAppearance = invokeGetter(item, "getAppearance"); //$NON-NLS-1$
            if (itemAppearance != null)
            {
                skippedAppearance = applyAppearanceSpec(itemAppearance, appearanceSpec);
            }
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("ConditionalAppearance.getItems() not available"); //$NON-NLS-1$
        }
        items.add((EObject) item);
        String result = "appearance added (cond=" + conditionType + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        if (!skippedAppearance.isEmpty())
        {
            result = result + " [styleRefNotSupported: " + skippedAppearance + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result;
    }

    /**
     * 1.43.x batch 4b: shared font/color guard - rejects an appearance value
     * that was passed as JSON ({@code {...}} / {@code [...]}) instead of the
     * expected {@code "Name=Value;Name=Value"} string. Reused by
     * {@code add_appearance} and {@code set_data_set_field_appearance}.
     */
    private MetadataGuards.BlockedGuardException fontColorGuard(String appearanceSpec)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appearance", appearanceSpec); //$NON-NLS-1$
        data.put("hint", //$NON-NLS-1$
            "Pass appearance as 'Name=Value;Name=Value' string. " //$NON-NLS-1$
                + "For Font use 'Arial,12,bold'; for colors '#RRGGBB'."); //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "appearance must be a 'Name=Value;Name=Value' string, not JSON", //$NON-NLS-1$
            "Use 'Arial,12,bold' for Font, '#RRGGBB' for colors.", //$NON-NLS-1$
            new MetadataGuards.ErrorTag(ErrorTags.FONT_COLOR_GUARD.wire(), data)));
    }

    private Object doAddGrouping(Map<String, String> params, EObject schema)
    {
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String field = JsonUtils.extractStringArgument(params, "field"); //$NON-NLS-1$
        if (field == null || field.isEmpty())
        {
            throw new RuntimeException("field is required for add_grouping"); //$NON-NLS-1$
        }
        // DataCompositionGroupType literals are Items / Hierarchy / HierarchyOnly
        // (there is no "Standard"); default to Items.
        String groupingType = orDefault(
            JsonUtils.extractStringArgument(params, "groupingType"), "Items"); //$NON-NLS-1$ //$NON-NLS-2$
        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        Object group = BmDcsHelper.createElement("createDataCompositionGroup"); //$NON-NLS-1$
        if (group == null)
        {
            throw new RuntimeException("DcsFactory.createDataCompositionGroup not available"); //$NON-NLS-1$
        }
        if (name != null && !name.isEmpty())
        {
            BmDcsHelper.setProperty(group, "name", name); //$NON-NLS-1$
        }
        // SettingsGroup has groupFields - a structured collection of GroupField.
        // getGroupFields() is null on a freshly created group: create the container
        // (same pattern as add_filter / add_settings_order) so the grouping field is
        // actually written - otherwise the StructureItemGroup is serialized empty.
        Object groupFields = ensureChild(group, "getGroupFields", //$NON-NLS-1$
            "createDataCompositionGroupFields", "groupFields"); //$NON-NLS-1$ //$NON-NLS-2$
        if (groupFields != null)
        {
            Object groupField = BmDcsHelper.createElement("createDataCompositionGroupField"); //$NON-NLS-1$
            if (groupField != null)
            {
                setFieldProperty(groupField, "field", field); //$NON-NLS-1$
                BmDcsHelper.setProperty(groupField, "groupType", groupingType); //$NON-NLS-1$
                EList<EObject> items = BmDcsHelper.getEObjectList(groupFields, "getItems"); //$NON-NLS-1$
                if (items != null)
                {
                    items.add((EObject) groupField);
                }
            }
        }
        // Give the group an Auto order + Auto selection (like the EDT wizard) so its
        // "Сортировка" and "Выбранные поля" tabs show <Авто> instead of being empty.
        addGroupAutoOrderSelection(group);
        // Grouping structure items live directly on DataCompositionSettings.getItems().
        EList<EObject> structureItems = BmDcsHelper.getEObjectList(settings, "getItems"); //$NON-NLS-1$
        if (structureItems == null)
        {
            throw new RuntimeException("DefaultSettings.getItems() not available"); //$NON-NLS-1$
        }
        structureItems.add((EObject) group);
        return "grouping by " + field + " (" + groupingType + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Adds an Auto order item and an Auto selected-field to a structure group,
     * mirroring the EDT wizard so the group's "Сортировка" / "Выбранные поля" tabs
     * show {@code <Авто>} rather than being empty. Best-effort - skips silently when
     * a factory/getter is unavailable.
     */
    private void addGroupAutoOrderSelection(Object group)
    {
        Object order = ensureChild(group, "getOrder", //$NON-NLS-1$
            "createDataCompositionOrder", "order"); //$NON-NLS-1$ //$NON-NLS-2$
        if (order != null)
        {
            EList<EObject> items = BmDcsHelper.getEObjectList(order, "getItems"); //$NON-NLS-1$
            Object autoOrder = BmDcsHelper.createElement("createDataCompositionAutoOrderItem"); //$NON-NLS-1$
            if (items != null && autoOrder != null && items.isEmpty())
            {
                items.add((EObject) autoOrder);
            }
        }
        Object selection = ensureChild(group, "getSelection", //$NON-NLS-1$
            "createDataCompositionSelectedFields", "selection"); //$NON-NLS-1$ //$NON-NLS-2$
        if (selection != null)
        {
            EList<EObject> items = BmDcsHelper.getEObjectList(selection, "getItems"); //$NON-NLS-1$
            Object autoSel = BmDcsHelper.createElement("createDataCompositionAutoSelectedField"); //$NON-NLS-1$
            if (items != null && autoSel != null && items.isEmpty())
            {
                items.add((EObject) autoSel);
            }
        }
    }

    private Object doAddFilter(Map<String, String> params, EObject schema)
    {
        String field = JsonUtils.extractStringArgument(params, "field"); //$NON-NLS-1$
        if (field == null || field.isEmpty())
        {
            throw new RuntimeException("field is required for add_filter"); //$NON-NLS-1$
        }
        String comparisonType = orDefault(
            JsonUtils.extractStringArgument(params, "comparisonType"), "Equal"); //$NON-NLS-1$ //$NON-NLS-2$
        String value = JsonUtils.extractStringArgument(params, "value"); //$NON-NLS-1$
        String userPresentation = JsonUtils.extractStringArgument(params,
            "userSettingPresentation"); //$NON-NLS-1$
        String viewMode = JsonUtils.extractStringArgument(params, "viewMode"); //$NON-NLS-1$

        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        Object filter = ensureChild(settings, "getFilter", //$NON-NLS-1$
            "createDataCompositionFilter", "filter"); //$NON-NLS-1$ //$NON-NLS-2$
        if (filter == null)
        {
            throw new RuntimeException("Could not create Filter container"); //$NON-NLS-1$
        }
        Object filterItem = BmDcsHelper.createElement("createDataCompositionFilterItem"); //$NON-NLS-1$
        if (filterItem == null)
        {
            throw new RuntimeException("DcsFactory.createDataCompositionFilterItem not available"); //$NON-NLS-1$
        }
        // 1.43.x batch 4a: left is an mcore.Value (a DataCompositionField IS one);
        // setFieldProperty builds the DataCompositionField and setProperty passes the
        // EObject through coerceValue's isInstance check. comparisonType is an enum.
        // right is an EList<Value> (no setter) - append an mcore StringValue literal.
        // The legacy leftValue/rightValue setters do not exist on FilterItem and were
        // silently no-ops.
        setFieldProperty(filterItem, "left", field); //$NON-NLS-1$
        BmDcsHelper.setProperty(filterItem, "comparisonType", comparisonType); //$NON-NLS-1$
        if (value != null)
        {
            Object rv = BmDcsHelper.createLiteralValue(value);
            EList<EObject> rightList = BmDcsHelper.getEObjectList(filterItem, "getRight"); //$NON-NLS-1$
            if (rv != null && rightList != null)
            {
                rightList.add((EObject) rv);
            }
        }
        if (userPresentation != null)
        {
            BmDcsHelper.setProperty(filterItem, "userSettingPresentation", userPresentation); //$NON-NLS-1$
        }
        if (viewMode != null)
        {
            BmDcsHelper.setProperty(filterItem, "viewMode", viewMode); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(filter, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Filter has no items collection"); //$NON-NLS-1$
        }
        items.add((EObject) filterItem);
        return "filter " + field + " " + comparisonType //$NON-NLS-1$ //$NON-NLS-2$
            + (value != null ? " " + value : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -----------------------------------------------------------------------
    // 1.41: 13 deferred DCS mutation handlers (Phase 4)
    // -----------------------------------------------------------------------

    /**
     * 1.41 / 4d: adds a user-defined calculated field at Schema-level (NOT
     * DefaultSettings). User fields are calculated expressions evaluated
     * at report runtime; they live in Schema.getUserFields() if exposed,
     * with fallback to Schema.getCalculatedFields().
     */
    private Object doAddUserField(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String expression = required(params, "expression"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        // User fields live on DataCompositionSettings.getUserFields() (a
        // DataCompositionUserFields container), NOT on the schema. The element is
        // a DataCompositionUserFieldExpression carrying dataPath + detailExpression
        // (there is no name/expression property on it).
        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        Object userFieldsContainer = ensureChild(settings, "getUserFields", //$NON-NLS-1$
            "createDataCompositionUserFields", "userFields"); //$NON-NLS-1$ //$NON-NLS-2$
        if (userFieldsContainer == null)
        {
            throw factoryMissingTag("Settings.getUserFields / createDataCompositionUserFields"); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(userFieldsContainer, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("UserFields.getItems() not available"); //$NON-NLS-1$
        }
        for (EObject existing : items)
        {
            Object dp = invokeGetter(existing, "getDataPath"); //$NON-NLS-1$
            if (dp != null && name.equalsIgnoreCase(dp.toString()))
            {
                throw alreadyExistsTag(name, "userField"); //$NON-NLS-1$
            }
        }
        Object userField = BmDcsHelper.createElement("createDataCompositionUserFieldExpression"); //$NON-NLS-1$
        if (userField == null)
        {
            throw factoryMissingTag("createDataCompositionUserFieldExpression"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(userField, "dataPath", name); //$NON-NLS-1$
        BmDcsHelper.setProperty(userField, "detailExpression", expression); //$NON-NLS-1$
        setPresentationProperty(userField, "title", title); //$NON-NLS-1$
        items.add((EObject) userField);
        return "user field '" + name + "' added"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4a: shared implementation for addSettingsTable / addSettingsChart -
     * both append a structure item of the corresponding type to
     * {@code Schema.getDefaultSettings().getStructure().getItems()}.
     *
     * @param kind {@code "Table"} or {@code "Chart"}
     */
    private Object doAddSettingsStructureItem(Map<String, String> params, EObject schema, String kind)
    {
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        Object structure = invokeGetter(settings, "getItems"); //$NON-NLS-1$
        if (structure == null)
        {
            throw new RuntimeException("DefaultSettings.getItems() not available"); //$NON-NLS-1$
        }
        // The settings-factory method is createDataCompositionTable / ...Chart
        // (not createSettingsTable). Keep the older guesses as fallbacks.
        String factoryMethod = "createDataComposition" + kind; //$NON-NLS-1$
        Object item = BmDcsHelper.createElement(factoryMethod);
        if (item == null)
        {
            item = BmDcsHelper.createElement("createSettings" + kind); //$NON-NLS-1$
        }
        if (item == null)
        {
            item = BmDcsHelper.createElement("create" + kind + "StructureItem"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (item == null)
        {
            throw factoryMissingTag(factoryMethod + ", createSettings" + kind //$NON-NLS-1$
                + ", create" + kind + "StructureItem"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (name != null && !name.isEmpty())
        {
            BmDcsHelper.setProperty(item, "name", name); //$NON-NLS-1$
        }
        EList<EObject> items = resolveStructureItems(structure);
        items.add((EObject) item);
        return "settings " + kind.toLowerCase() //$NON-NLS-1$
            + (name != null ? " '" + name + "'" : "") + " added"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * 1.41 / 4b: appends an order item to
     * {@code Settings.getOrder().getItems()}. Also backs the {@code add_order}
     * short-name alias (see the dispatch switch above) - that op spells the
     * sort-direction param {@code direction} instead of {@code orderType};
     * both are accepted here so the two op names stay behaviourally identical.
     */
    private Object doAddSettingsOrder(Map<String, String> params, EObject schema)
    {
        String field = required(params, "field"); //$NON-NLS-1$
        String orderType = orDefault(JsonUtils.extractStringArgument(params, "orderType"), //$NON-NLS-1$
            orDefault(JsonUtils.extractStringArgument(params, "direction"), "Asc")); //$NON-NLS-1$ //$NON-NLS-2$
        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        // The Order sub-container is null on a fresh schema - create it (like
        // add_filter does for the Filter container) instead of failing.
        Object order = ensureChild(settings, "getOrder", //$NON-NLS-1$
            "createDataCompositionOrder", "order"); //$NON-NLS-1$ //$NON-NLS-2$
        if (order == null)
        {
            throw new RuntimeException("Could not create the Order container"); //$NON-NLS-1$
        }
        Object orderItem = BmDcsHelper.createElement("createDataCompositionOrderItem"); //$NON-NLS-1$
        if (orderItem == null)
        {
            orderItem = BmDcsHelper.createElement("createSettingsOrderItem"); //$NON-NLS-1$
        }
        if (orderItem == null)
        {
            throw factoryMissingTag("createDataCompositionOrderItem, createSettingsOrderItem"); //$NON-NLS-1$
        }
        // field is a DataCompositionField value-carrier; orderType is the
        // DataCompositionSortDirection enum (Asc / Desc).
        setFieldProperty(orderItem, "field", field); //$NON-NLS-1$
        BmDcsHelper.setProperty(orderItem, "orderType", orderType); //$NON-NLS-1$
        EList<EObject> items = BmDcsHelper.getEObjectList(order, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Order.getItems() not available"); //$NON-NLS-1$
        }
        items.add((EObject) orderItem);
        return "order by " + field + " (" + orderType + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * 1.41 / 4b: appends a SelectedField to
     * {@code Settings.getSelection().getItems()}.
     */
    private Object doAddSettingsSelectedField(Map<String, String> params, EObject schema)
    {
        String field = required(params, "field"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        // The Selection sub-container is null on a fresh schema - create it.
        Object selection = ensureChild(settings, "getSelection", //$NON-NLS-1$
            "createDataCompositionSelectedFields", "selection"); //$NON-NLS-1$ //$NON-NLS-2$
        if (selection == null)
        {
            throw new RuntimeException("Could not create the Selection container"); //$NON-NLS-1$
        }
        Object item = BmDcsHelper.createElement("createDataCompositionSelectedField"); //$NON-NLS-1$
        if (item == null)
        {
            item = BmDcsHelper.createElement("createSettingsSelectedField"); //$NON-NLS-1$
        }
        if (item == null)
        {
            throw factoryMissingTag("createDataCompositionSelectedField, createSettingsSelectedField"); //$NON-NLS-1$
        }
        setFieldProperty(item, "field", field); //$NON-NLS-1$
        setPresentationProperty(item, "title", title); //$NON-NLS-1$
        EList<EObject> items = BmDcsHelper.getEObjectList(selection, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Selection.getItems() not available"); //$NON-NLS-1$
        }
        items.add((EObject) item);
        return "selected field '" + field + "' added"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4b: removes a SelectedField from
     * {@code Settings.getSelection().getItems()} by field name.
     */
    private Object doRemoveSettingsSelectedField(Map<String, String> params, EObject schema)
    {
        String field = required(params, "field"); //$NON-NLS-1$
        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        Object selection = invokeGetter(settings, "getSelection"); //$NON-NLS-1$
        if (selection == null)
        {
            throw new RuntimeException("DefaultSettings.getSelection() not available"); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(selection, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Selection.getItems() not available"); //$NON-NLS-1$
        }
        EObject toRemove = null;
        for (EObject it : items)
        {
            // Fields are stored as DataCompositionField objects, so match on the
            // extracted path (getValue), not the EObject's toString.
            String fp = fieldPathOf(invokeGetter(it, "getField")); //$NON-NLS-1$
            if (fp != null && field.equalsIgnoreCase(fp))
            {
                toRemove = it;
                break;
            }
        }
        if (toRemove == null)
        {
            throw notFoundTag(field, "selectedField"); //$NON-NLS-1$
        }
        items.remove(toRemove);
        return "selected field '" + field + "' removed"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4a: appends a SettingsVariant to
     * {@code Schema.getVariants().getItems()} (Schema-level, not Settings).
     */
    private Object doAddSettingsVariant(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String presentation = JsonUtils.extractStringArgument(params, "presentation"); //$NON-NLS-1$
        // Variants live directly on Schema.getSettingsVariants() (an
        // EList<SettingsVariant>), NOT a getVariants() container. Each variant
        // carries its own DataCompositionSettings tree.
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        if (variants == null)
        {
            throw new RuntimeException("Schema.getSettingsVariants() not available"); //$NON-NLS-1$
        }
        // Idempotency: silent duplicate variants corrupt the report (UI shows only
        // the first; the second becomes invisible junk uneditable in the editor).
        for (EObject existing : variants)
        {
            Object existingName = invokeGetter(existing, "getName"); //$NON-NLS-1$
            if (existingName != null && name.equalsIgnoreCase(existingName.toString()))
            {
                throw alreadyExistsTag(name, "settingsVariant"); //$NON-NLS-1$
            }
        }
        Object variant = BmDcsHelper.createElement("createSettingsVariant"); //$NON-NLS-1$
        if (variant == null)
        {
            throw factoryMissingTag("createSettingsVariant"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(variant, "name", name); //$NON-NLS-1$
        setPresentationProperty(variant, "presentation", presentation); //$NON-NLS-1$
        // A variant needs its own settings tree, otherwise it opens empty/invalid.
        Object variantSettings = BmDcsHelper.createElement("createDataCompositionSettings"); //$NON-NLS-1$
        if (variantSettings != null)
        {
            BmDcsHelper.setProperty(variant, "settings", variantSettings); //$NON-NLS-1$
        }
        variants.add((EObject) variant);
        return "settings variant '" + name + "' added"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4c: locates an existing parameter in
     * {@code Settings.getDataParameters().getItems()} by name and overwrites
     * its value.
     */
    private Object doSetSettingsParameter(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String value = JsonUtils.extractStringArgument(params, "value"); //$NON-NLS-1$
        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        Object dataParameters = invokeGetter(settings, "getDataParameters"); //$NON-NLS-1$
        if (dataParameters == null)
        {
            throw new RuntimeException("DefaultSettings.getDataParameters() not available"); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(dataParameters, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("DataParameters.getItems() not available"); //$NON-NLS-1$
        }
        // 1.43.x batch 4a: a DataCompositionParameterValue's key is its getParameter()
        // which is itself a value carrier (getValue() yields the parameter name). The
        // value is an EList<Value> (getValues(), no setter); set it by clearing and
        // appending an mcore StringValue literal. The legacy single "value" setter
        // did not exist on this element.
        EObject found = null;
        java.util.List<String> availableKeys = new java.util.ArrayList<>();
        for (EObject it : items)
        {
            Object p = invokeGetter(it, "getParameter"); //$NON-NLS-1$
            String key = p != null ? String.valueOf(invokeGetter(p, "getValue")) : null; //$NON-NLS-1$
            if (key != null)
            {
                availableKeys.add(key);
            }
            if (key != null && key.equalsIgnoreCase(name))
            {
                found = it;
                break;
            }
        }
        if (found == null)
        {
            throw notFoundTag(name + " (available: " + availableKeys + ")", //$NON-NLS-1$ //$NON-NLS-2$
                "settingsParameter"); //$NON-NLS-1$
        }
        EList<EObject> vals = BmDcsHelper.getEObjectList(found, "getValues"); //$NON-NLS-1$
        if (vals == null)
        {
            throw new RuntimeException("DataParameterValue.getValues() not available"); //$NON-NLS-1$
        }
        vals.clear();
        Object lv = BmDcsHelper.createLiteralValue(value);
        if (lv != null)
        {
            vals.add((EObject) lv);
        }
        BmDcsHelper.setProperty(found, "use", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        return "settings parameter '" + name + "' set"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4c: universal cascade-remove operation. {@code itemPath} is a
     * dot-separated path with bracketed indices. Supported roots:
     * {@code Structure[N]}, {@code Filter[N]}, {@code Order[N]},
     * {@code Selection[N]}, {@code ConditionalAppearance[N]},
     * {@code DataParameters[N]}.
     */
    private Object doRemoveSettingsItem(Map<String, String> params, EObject schema)
    {
        String itemPath = required(params, "itemPath"); //$NON-NLS-1$
        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        // Parse the path into root.getter[index] tokens
        String[] parts = itemPath.split("\\."); //$NON-NLS-1$
        if (parts.length == 0)
        {
            throw new RuntimeException("itemPath cannot be empty"); //$NON-NLS-1$
        }
        Object current = settings;
        EList<EObject> parentList = null;
        int parentIndex = -1;
        for (String token : parts)
        {
            int lb = token.indexOf('[');
            int rb = token.indexOf(']');
            String collectionName = lb > 0 ? token.substring(0, lb) : token;
            int index = (lb > 0 && rb > lb)
                ? Integer.parseInt(token.substring(lb + 1, rb)) : -1;
            String getterName = "get" + Character.toUpperCase(collectionName.charAt(0)) //$NON-NLS-1$
                + collectionName.substring(1);
            Object collection = invokeGetter(current, getterName);
            if (collection == null)
            {
                throw new RuntimeException("Path segment '" + token //$NON-NLS-1$
                    + "' not resolvable on " + current.getClass().getSimpleName()); //$NON-NLS-1$
            }
            EList<EObject> items = BmDcsHelper.getEObjectList(collection, "getItems"); //$NON-NLS-1$
            if (items == null && collection instanceof EList)
            {
                @SuppressWarnings({ "unchecked", "rawtypes" })
                EList<EObject> coerced = (EList) collection;
                items = coerced;
            }
            if (items == null)
            {
                throw new RuntimeException(getterName + " has no items collection"); //$NON-NLS-1$
            }
            if (index < 0)
            {
                // Walked into a collection root without a specific index -
                // not a removable target by itself
                throw new RuntimeException("Path '" + token //$NON-NLS-1$
                    + "' requires [index] to remove a specific entry"); //$NON-NLS-1$
            }
            if (index >= items.size())
            {
                throw new RuntimeException(getterName + "[" + index //$NON-NLS-1$
                    + "] out of bounds (size=" + items.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            parentList = items;
            parentIndex = index;
            current = items.get(index);
        }
        if (parentList == null || parentIndex < 0)
        {
            throw new RuntimeException("itemPath did not resolve to a removable item"); //$NON-NLS-1$
        }
        // Cascade-remove: removing the EObject from its parent EList; EMF
        // automatically detaches the contained subtree.
        parentList.remove(parentIndex);
        return "settings item at '" + itemPath + "' removed (cascade)"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4d: removes a ConditionalAppearance item by index from
     * {@code Schema.getConditionalAppearance().getItems()} (or
     * {@code Settings.getConditionalAppearance()} when {@code target=settings}).
     */
    private Object doRemoveConditionalAppearance(Map<String, String> params, EObject schema)
    {
        String indexStr = required(params, "index"); //$NON-NLS-1$
        String target = orDefault(JsonUtils.extractStringArgument(params, "target"), "schema"); //$NON-NLS-1$ //$NON-NLS-2$
        int index;
        try
        {
            index = Integer.parseInt(indexStr);
        }
        catch (NumberFormatException e)
        {
            throw new RuntimeException("index must be an integer"); //$NON-NLS-1$
        }
        Object root = "settings".equalsIgnoreCase(target) //$NON-NLS-1$
            ? ensureDefaultSettings(schema) // the "Основной" variant's settings, where add_appearance writes
            : schema;
        Object ca = invokeGetter(root, "getConditionalAppearance"); //$NON-NLS-1$
        if (ca == null)
        {
            throw new RuntimeException(target + ".ConditionalAppearance not available"); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(ca, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("ConditionalAppearance.getItems() not available"); //$NON-NLS-1$
        }
        if (index < 0 || index >= items.size())
        {
            throw new RuntimeException("index out of bounds (size=" + items.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        items.remove(index);
        return "conditional appearance [" + index + "] removed from " + target; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4d: sets appearance properties (font / color / horizontalAlignment /
     * verticalAlignment / textColor) on a single DataSet field.
     */
    private Object doSetDataSetFieldAppearance(Map<String, String> params, EObject schema)
    {
        String dataSetName = required(params, "dataSet"); //$NON-NLS-1$
        String fieldName = required(params, "field"); //$NON-NLS-1$
        EObject dataSet = BmDcsHelper.findByNameInList(schema, "getDataSets", dataSetName); //$NON-NLS-1$
        if (dataSet == null)
        {
            throw notFoundTag(dataSetName, "dataSet"); //$NON-NLS-1$
        }
        // DataSetField has no getName() - it is identified by its field / dataPath
        // (what add_data_set_field sets), so findByNameInList (getName-based) never
        // matches. Resolve by field / dataPath instead.
        EObject field = null;
        EList<EObject> dsFields = BmDcsHelper.getEObjectList(dataSet, "getFields"); //$NON-NLS-1$
        if (dsFields != null)
        {
            for (EObject f : dsFields)
            {
                Object fv = invokeGetter(f, "getField"); //$NON-NLS-1$
                Object dp = invokeGetter(f, "getDataPath"); //$NON-NLS-1$
                if ((fv != null && fieldName.equalsIgnoreCase(fv.toString()))
                    || (dp != null && fieldName.equalsIgnoreCase(dp.toString())))
                {
                    field = f;
                    break;
                }
            }
        }
        if (field == null)
        {
            throw notFoundTag(fieldName, "dataSetField"); //$NON-NLS-1$
        }
        // The field's Appearance sub-container is null on a fresh field - create it.
        Object appearance = ensureChild(field, "getAppearance", //$NON-NLS-1$
            "createDataCompositionAppearance", "appearance"); //$NON-NLS-1$ //$NON-NLS-2$
        if (appearance == null)
        {
            throw new RuntimeException("Could not create the field's Appearance container"); //$NON-NLS-1$
        }
        // 1.43.x batch 4b: appearance is now a "Name=Value;Name=Value" spec
        // string routed through the appearance-parameter list (the previous
        // setProperty(appearance,"font"/"textColor",...) targeted properties that
        // do not exist on DataCompositionAppearance and silently no-op'd).
        String appearanceSpec = JsonUtils.extractStringArgument(params, "appearance"); //$NON-NLS-1$
        String appearanceTrim = appearanceSpec != null ? appearanceSpec.trim() : null;
        if (appearanceTrim != null
            && (appearanceTrim.startsWith("{") || appearanceTrim.startsWith("["))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            throw fontColorGuard(appearanceSpec);
        }
        if (appearanceTrim == null || appearanceTrim.isEmpty())
        {
            throw new RuntimeException("appearance is required " //$NON-NLS-1$
                + "('Name=Value;Name=Value', e.g. 'TextColor=#FF0000;Font=Arial,12,bold')"); //$NON-NLS-1$
        }
        List<String> skipped = applyAppearanceSpec(appearance, appearanceSpec);
        int touched = parseAppearanceSpec(appearanceSpec).size() - skipped.size();
        String msg = "dataset field '" + dataSetName + "." + fieldName //$NON-NLS-1$ //$NON-NLS-2$
            + "' appearance updated (" + touched + " properties)"; //$NON-NLS-1$ //$NON-NLS-2$
        if (!skipped.isEmpty())
        {
            msg = msg + " [styleRefNotSupported: " + skipped + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return msg;
    }

    /**
     * 1.41 / 4c: sets a value on an existing OutputParameter by name in
     * {@code Schema.getOutputParameters()} (Schema-level).
     */
    private Object doSetOutputParameter(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String value = JsonUtils.extractStringArgument(params, "value"); //$NON-NLS-1$
        // 1.43.x batch 4a: output parameters live on DefaultSettings.getOutputParameters()
        // .getItems() (a DataCompositionOutputParameterValues container), NOT directly on
        // the schema, and each item's key is getParameter().getValue() with the value held
        // in getValues() (EList<Value>, no setter). The previous schema.getOutputParameters()
        // + getName() + setProperty("value") path matched nothing and silently no-op'd.
        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        Object outputParameters = invokeGetter(settings, "getOutputParameters"); //$NON-NLS-1$
        if (outputParameters == null)
        {
            throw new RuntimeException("DefaultSettings.getOutputParameters() not available"); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(outputParameters, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("OutputParameters.getItems() not available"); //$NON-NLS-1$
        }
        EObject found = null;
        java.util.List<String> availableKeys = new java.util.ArrayList<>();
        for (EObject it : items)
        {
            Object p = invokeGetter(it, "getParameter"); //$NON-NLS-1$
            String key = p != null ? String.valueOf(invokeGetter(p, "getValue")) : null; //$NON-NLS-1$
            if (key != null)
            {
                availableKeys.add(key);
            }
            if (key != null && key.equalsIgnoreCase(name))
            {
                found = it;
                break;
            }
        }
        if (found == null)
        {
            throw notFoundTag(name + " (available: " + availableKeys + ")", //$NON-NLS-1$ //$NON-NLS-2$
                "outputParameter"); //$NON-NLS-1$
        }
        EList<EObject> vals = BmDcsHelper.getEObjectList(found, "getValues"); //$NON-NLS-1$
        if (vals == null)
        {
            throw new RuntimeException("OutputParameterValue.getValues() not available"); //$NON-NLS-1$
        }
        vals.clear();
        Object lv = BmDcsHelper.createLiteralValue(value);
        if (lv != null)
        {
            vals.add((EObject) lv);
        }
        BmDcsHelper.setProperty(found, "use", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        return "output parameter '" + name + "' set"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.41 / 4a: appends a FilterItemGroup container to
     * {@code Settings.getFilter().getItems()}, allowing nested AND/OR groups.
     */
    private Object doAddSettingsFilterGroup(Map<String, String> params, EObject schema)
    {
        String groupType = orDefault(JsonUtils.extractStringArgument(params, "groupType"), //$NON-NLS-1$
            "AndGroup"); //$NON-NLS-1$
        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        Object filter = ensureChild(settings, "getFilter", //$NON-NLS-1$
            "createDataCompositionFilter", "filter"); //$NON-NLS-1$ //$NON-NLS-2$
        if (filter == null)
        {
            throw new RuntimeException("Could not create Filter container"); //$NON-NLS-1$
        }
        Object group = BmDcsHelper.createElement("createDataCompositionFilterItemGroup"); //$NON-NLS-1$
        if (group == null)
        {
            group = BmDcsHelper.createElement("createSettingsFilterGroup"); //$NON-NLS-1$
        }
        if (group == null)
        {
            throw factoryMissingTag("createFilterItemGroup, createSettingsFilterGroup"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(group, "groupType", groupType); //$NON-NLS-1$
        EList<EObject> items = BmDcsHelper.getEObjectList(filter, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Filter.getItems() not available"); //$NON-NLS-1$
        }
        items.add((EObject) group);
        return "filter group (" + groupType + ") added"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -----------------------------------------------------------------------
    // 1.43.x DCS catch-up wave 2: 14 mutation handlers
    // -----------------------------------------------------------------------

    /**
     * 1.43.x: adds a {@code DataCompositionSchemaDataSetLink} to
     * {@code Schema.getDataSetLinks()}. Links join a source dataset to a
     * destination dataset (master-detail). Idempotent by the
     * {@code sourceDataSet}+{@code destinationDataSet} pair.
     */
    private Object doAddDataSetLink(Map<String, String> params, EObject schema)
    {
        String source = required(params, "sourceDataSet"); //$NON-NLS-1$
        String dest = required(params, "destinationDataSet"); //$NON-NLS-1$
        String sourceExpression = JsonUtils.extractStringArgument(params, "sourceExpression"); //$NON-NLS-1$
        String destinationExpression = JsonUtils.extractStringArgument(params,
            "destinationExpression"); //$NON-NLS-1$
        EList<EObject> links = BmDcsHelper.getEObjectList(schema, "getDataSetLinks"); //$NON-NLS-1$
        if (links == null)
        {
            throw new RuntimeException("Schema.getDataSetLinks() not available"); //$NON-NLS-1$
        }
        if (findDataSetLink(links, source, dest) != null)
        {
            throw alreadyExistsTag(source + "->" + dest, "dataSetLink"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Object link = BmDcsHelper.createElement("createDataCompositionSchemaDataSetLink"); //$NON-NLS-1$
        if (link == null)
        {
            throw factoryMissingTag("createDataCompositionSchemaDataSetLink"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(link, "sourceDataSet", source); //$NON-NLS-1$
        BmDcsHelper.setProperty(link, "destinationDataSet", dest); //$NON-NLS-1$
        if (sourceExpression != null && !sourceExpression.isEmpty())
        {
            BmDcsHelper.setProperty(link, "sourceExpression", sourceExpression); //$NON-NLS-1$
        }
        if (destinationExpression != null && !destinationExpression.isEmpty())
        {
            BmDcsHelper.setProperty(link, "destinationExpression", destinationExpression); //$NON-NLS-1$
        }
        links.add((EObject) link);
        return "dataset link " + source + " -> " + dest + " added"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * 1.43.x: sets a property on an existing dataset link, located by the
     * {@code sourceDataSet}+{@code destinationDataSet} pair.
     */
    private Object doSetDataSetLinkProperty(Map<String, String> params, EObject schema)
    {
        String source = required(params, "sourceDataSet"); //$NON-NLS-1$
        String dest = required(params, "destinationDataSet"); //$NON-NLS-1$
        String property = required(params, "property"); //$NON-NLS-1$
        String value = JsonUtils.extractStringArgument(params, "value"); //$NON-NLS-1$
        EList<EObject> links = BmDcsHelper.getEObjectList(schema, "getDataSetLinks"); //$NON-NLS-1$
        if (links == null)
        {
            throw new RuntimeException("Schema.getDataSetLinks() not available"); //$NON-NLS-1$
        }
        EObject link = findDataSetLink(links, source, dest);
        if (link == null)
        {
            throw notFoundTag(source + "->" + dest, "dataSetLink"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        BmDcsHelper.setProperty(link, property, value);
        return "dataset link " + source + " -> " + dest + " property '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + property + "' set"; //$NON-NLS-1$
    }

    /**
     * 1.43.x: removes a dataset link located by the
     * {@code sourceDataSet}+{@code destinationDataSet} pair.
     */
    private Object doRemoveDataSetLink(Map<String, String> params, EObject schema)
    {
        String source = required(params, "sourceDataSet"); //$NON-NLS-1$
        String dest = required(params, "destinationDataSet"); //$NON-NLS-1$
        EList<EObject> links = BmDcsHelper.getEObjectList(schema, "getDataSetLinks"); //$NON-NLS-1$
        if (links == null)
        {
            throw new RuntimeException("Schema.getDataSetLinks() not available"); //$NON-NLS-1$
        }
        EObject link = findDataSetLink(links, source, dest);
        if (link == null)
        {
            throw notFoundTag(source + "->" + dest, "dataSetLink"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        links.remove(link);
        return "dataset link " + source + " -> " + dest + " removed"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * 1.43.x: locates a dataset link by source + destination dataset names
     * (via {@code getSourceDataSet} / {@code getDestinationDataSet}).
     */
    private EObject findDataSetLink(EList<EObject> links, String source, String dest)
    {
        for (EObject link : links)
        {
            Object s = invokeGetter(link, "getSourceDataSet"); //$NON-NLS-1$
            Object d = invokeGetter(link, "getDestinationDataSet"); //$NON-NLS-1$
            if (s != null && d != null
                && source.equalsIgnoreCase(s.toString())
                && dest.equalsIgnoreCase(d.toString()))
            {
                return link;
            }
        }
        return null;
    }

    /**
     * 1.43.x: sets a property on an existing dataset (located by name in
     * {@code Schema.getDataSets()}). Common props: query, dataSource,
     * objectName, name, autoFillAvailableFields.
     */
    private Object doSetDataSetProperty(Map<String, String> params, EObject schema)
    {
        String dataSetName = required(params, "dataSetName"); //$NON-NLS-1$
        String property = required(params, "property"); //$NON-NLS-1$
        String value = JsonUtils.extractStringArgument(params, "value"); //$NON-NLS-1$
        EObject dataSet = BmDcsHelper.findByNameInList(schema, "getDataSets", dataSetName); //$NON-NLS-1$
        if (dataSet == null)
        {
            throw notFoundTag(dataSetName, "dataSet"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(dataSet, property, value);
        return "dataset '" + dataSetName + "' property '" + property + "' set"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Row 35: replaces the ENTIRE query text of an existing Query dataset
     * (located by name in {@code Schema.getDataSets()}). Unlike the token-splice
     * {@code add_query_field} / {@code add_query_condition} heuristics - which
     * reject multi-statement and UNION queries - this sets the whole {@code query}
     * string, so batch queries (temp tables) and UNION sections are supported.
     * The {@code queryText} is Xtext-validated up front (see
     * {@link #preflightValidate}), identical to {@code add_dataset}. Only Query
     * datasets carry a {@code query} feature; a Union / Object dataset has no
     * {@code setQuery} setter, so {@link BmDcsHelper#setProperty} returns a
     * "not found" message that is surfaced as a clear, purpose-built error.
     */
    private Object doSetDataSetQuery(Map<String, String> params, EObject schema)
    {
        String dataSetName = required(params, "dataSetName"); //$NON-NLS-1$
        String queryText = required(params, "queryText"); //$NON-NLS-1$
        EObject dataSet = BmDcsHelper.findByNameInList(schema, "getDataSets", dataSetName); //$NON-NLS-1$
        if (dataSet == null)
        {
            throw notFoundTag(dataSetName, "dataSet"); //$NON-NLS-1$
        }
        String setErr = BmDcsHelper.setProperty(dataSet, "query", queryText); //$NON-NLS-1$
        if (setErr != null)
        {
            // A Union / Object dataset lacks a `query` setter -> setProperty
            // returns "Property 'query' not found on <class>". Give the caller a
            // reason instead of the raw reflection message.
            throw new RuntimeException("Cannot set query on dataset '" + dataSetName //$NON-NLS-1$
                + "' (" + dataSet.eClass().getName() + "): only a Query dataset carries " //$NON-NLS-1$ //$NON-NLS-2$
                + "a query text. For a Union dataset edit its item datasets; an Object " //$NON-NLS-1$
                + "dataset has no query. [" + setErr + "]"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "dataset '" + dataSetName + "' query replaced (" + queryText.length() //$NON-NLS-1$ //$NON-NLS-2$
            + " chars)"; //$NON-NLS-1$
    }

    /**
     * 1.43.x: removes a field from a dataset, located by field path
     * ({@code getField} / {@code getDataPath}) inside
     * {@code DataSet.getFields()}.
     */
    private Object doRemoveDataSetField(Map<String, String> params, EObject schema)
    {
        String dataSetName = required(params, "dataSetName"); //$NON-NLS-1$
        String name = required(params, "name"); //$NON-NLS-1$
        EObject dataSet = BmDcsHelper.findByNameInList(schema, "getDataSets", dataSetName); //$NON-NLS-1$
        if (dataSet == null)
        {
            throw notFoundTag(dataSetName, "dataSet"); //$NON-NLS-1$
        }
        EList<EObject> fields = BmDcsHelper.getEObjectList(dataSet, "getFields"); //$NON-NLS-1$
        if (fields == null)
        {
            throw new RuntimeException("DataSet.getFields() not available"); //$NON-NLS-1$
        }
        EObject toRemove = null;
        for (EObject f : fields)
        {
            Object fld = invokeGetter(f, "getField"); //$NON-NLS-1$
            if (fld == null)
            {
                fld = invokeGetter(f, "getDataPath"); //$NON-NLS-1$
            }
            if (fld != null && name.equalsIgnoreCase(fld.toString()))
            {
                toRemove = f;
                break;
            }
        }
        if (toRemove == null)
        {
            throw notFoundTag(dataSetName + "." + name, "dataSetField"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        fields.remove(toRemove);
        return "dataset field '" + dataSetName + "." + name + "' removed"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * 1.43.x: updates an existing calculated field (located by {@code dataPath}
     * in {@code Schema.getCalculatedFields()}). Optionally sets expression and
     * title.
     */
    private Object doSetCalculatedField(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        EObject field = findByDataPath(schema, "getCalculatedFields", name); //$NON-NLS-1$
        if (field == null)
        {
            throw notFoundTag(name, "calculatedField"); //$NON-NLS-1$
        }
        String expression = JsonUtils.extractStringArgument(params, "expression"); //$NON-NLS-1$
        if (expression != null && !expression.isEmpty())
        {
            BmDcsHelper.setProperty(field, "expression", expression); //$NON-NLS-1$
        }
        setPresentationProperty(field, "title", JsonUtils.extractStringArgument(params, "title")); //$NON-NLS-1$ //$NON-NLS-2$
        return "calculated field '" + name + "' updated"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.43.x: removes a calculated field by {@code dataPath} from
     * {@code Schema.getCalculatedFields()}.
     */
    private Object doRemoveCalculatedField(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        EList<EObject> calc = BmDcsHelper.getEObjectList(schema, "getCalculatedFields"); //$NON-NLS-1$
        if (calc == null)
        {
            throw new RuntimeException("Schema.getCalculatedFields() not available"); //$NON-NLS-1$
        }
        EObject field = findByDataPath(schema, "getCalculatedFields", name); //$NON-NLS-1$
        if (field == null)
        {
            throw notFoundTag(name, "calculatedField"); //$NON-NLS-1$
        }
        calc.remove(field);
        return "calculated field '" + name + "' removed"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.43.x: updates an existing total field (located by {@code dataPath} in
     * {@code Schema.getTotalFields()}). Optionally sets a new expression.
     */
    private Object doSetTotalField(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        EObject field = findByDataPath(schema, "getTotalFields", name); //$NON-NLS-1$
        if (field == null)
        {
            throw notFoundTag(name, "totalField"); //$NON-NLS-1$
        }
        String expression = JsonUtils.extractStringArgument(params, "expression"); //$NON-NLS-1$
        if (expression != null && !expression.isEmpty())
        {
            BmDcsHelper.setProperty(field, "expression", expression); //$NON-NLS-1$
        }
        return "total field '" + name + "' updated"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.43.x: removes a total field by {@code dataPath} from
     * {@code Schema.getTotalFields()}.
     */
    private Object doRemoveTotalField(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        EList<EObject> totals = BmDcsHelper.getEObjectList(schema, "getTotalFields"); //$NON-NLS-1$
        if (totals == null)
        {
            throw new RuntimeException("Schema.getTotalFields() not available"); //$NON-NLS-1$
        }
        EObject field = findByDataPath(schema, "getTotalFields", name); //$NON-NLS-1$
        if (field == null)
        {
            throw notFoundTag(name, "totalField"); //$NON-NLS-1$
        }
        totals.remove(field);
        return "total field '" + name + "' removed"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.43.x: locates a calculated/total field by {@code dataPath} in the
     * named schema collection.
     */
    private EObject findByDataPath(EObject schema, String getter, String dataPath)
    {
        EList<EObject> list = BmDcsHelper.getEObjectList(schema, getter);
        if (list == null)
        {
            return null;
        }
        for (EObject field : list)
        {
            Object dp = invokeGetter(field, "getDataPath"); //$NON-NLS-1$
            if (dp != null && dataPath.equalsIgnoreCase(dp.toString()))
            {
                return field;
            }
        }
        return null;
    }

    /**
     * 1.43.x: clears all selected fields from
     * {@code Settings.getSelection().getItems()}.
     */
    private Object doClearSettingsSelectedFields(Map<String, String> params, EObject schema)
    {
        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        Object selection = invokeGetter(settings, "getSelection"); //$NON-NLS-1$
        if (selection == null)
        {
            throw new RuntimeException("DefaultSettings.getSelection() not available"); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(selection, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Selection.getItems() not available"); //$NON-NLS-1$
        }
        int cleared = items.size();
        items.clear();
        return cleared + " selected field(s) cleared"; //$NON-NLS-1$
    }

    /**
     * 1.43.x: removes a single filter item from
     * {@code Settings.getFilter().getItems()}, by {@code index} or by
     * {@code field} (matched via {@code getLeft} / {@code getField} path).
     */
    private Object doRemoveSettingsFilter(Map<String, String> params, EObject schema)
    {
        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        Object filter = invokeGetter(settings, "getFilter"); //$NON-NLS-1$
        if (filter == null)
        {
            throw new RuntimeException("DefaultSettings.getFilter() not available"); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(filter, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Filter.getItems() not available"); //$NON-NLS-1$
        }
        return removeByIndexOrField(items, params, "filterItem"); //$NON-NLS-1$
    }

    /**
     * 1.43.x: removes a single order item from
     * {@code Settings.getOrder().getItems()}, by {@code index} or by
     * {@code field} (matched via {@code getField} path).
     */
    private Object doRemoveSettingsOrder(Map<String, String> params, EObject schema)
    {
        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        Object order = invokeGetter(settings, "getOrder"); //$NON-NLS-1$
        if (order == null)
        {
            throw new RuntimeException("DefaultSettings.getOrder() not available"); //$NON-NLS-1$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(order, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Order.getItems() not available"); //$NON-NLS-1$
        }
        return removeByIndexOrField(items, params, "orderItem"); //$NON-NLS-1$
    }

    /**
     * 1.43.x: shared remove-from-settings-collection logic. Removes by
     * {@code index} (preferred when present) or by {@code field} matched
     * against the item's {@code getLeft} / {@code getField} path. Requires one.
     */
    private Object removeByIndexOrField(EList<EObject> items, Map<String, String> params,
        String kind)
    {
        Integer index = JsonUtils.extractIntegerArgument(params, "index"); //$NON-NLS-1$
        String field = JsonUtils.extractStringArgument(params, "field"); //$NON-NLS-1$
        if (index != null)
        {
            int idx = index.intValue();
            if (idx < 0 || idx >= items.size())
            {
                throw new RuntimeException("index out of range (size=" + items.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            items.remove(idx);
            return kind + " at index " + idx + " removed"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (field != null && !field.isEmpty())
        {
            EObject toRemove = null;
            for (EObject it : items)
            {
                Object fld = invokeGetter(it, "getLeft"); //$NON-NLS-1$
                if (fld == null)
                {
                    fld = invokeGetter(it, "getField"); //$NON-NLS-1$
                }
                String fp = fieldPathOf(fld);
                if (fp != null && field.equalsIgnoreCase(fp))
                {
                    toRemove = it;
                    break;
                }
            }
            if (toRemove == null)
            {
                throw notFoundTag(field, kind);
            }
            items.remove(toRemove);
            return kind + " for field '" + field + "' removed"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        throw new RuntimeException("Provide index=<int> or field=<path> to remove a " + kind); //$NON-NLS-1$
    }

    /**
     * 1.43.x: sets the {@code viewMode} (Auto / Normal / QuickAccess /
     * Inaccessible) on a settings item, scoped by {@code container}
     * (selection / filter / order) and {@code index}.
     */
    private Object doSetSettingsItemUserMode(Map<String, String> params, EObject schema)
    {
        String container = required(params, "container"); //$NON-NLS-1$
        String viewMode = required(params, "viewMode"); //$NON-NLS-1$
        Integer index = JsonUtils.extractIntegerArgument(params, "index"); //$NON-NLS-1$
        if (index == null)
        {
            throw new RuntimeException("index is required (0-based item index)"); //$NON-NLS-1$
        }
        Object settings = ensureDefaultSettings(schema);
        if (settings == null)
        {
            throw new RuntimeException("Could not create DefaultSettings on schema"); //$NON-NLS-1$
        }
        String getter;
        switch (container.toLowerCase())
        {
            case "selection": //$NON-NLS-1$
                getter = "getSelection"; //$NON-NLS-1$
                break;
            case "filter": //$NON-NLS-1$
                getter = "getFilter"; //$NON-NLS-1$
                break;
            case "order": //$NON-NLS-1$
                getter = "getOrder"; //$NON-NLS-1$
                break;
            default:
                throw new RuntimeException(
                    "container must be one of: selection, filter, order"); //$NON-NLS-1$
        }
        Object containerObj = invokeGetter(settings, getter);
        if (containerObj == null)
        {
            throw new RuntimeException("DefaultSettings." + getter + "() not available"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(containerObj, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException(container + ".getItems() not available"); //$NON-NLS-1$
        }
        int idx = index.intValue();
        if (idx < 0 || idx >= items.size())
        {
            throw new RuntimeException("index out of range (size=" + items.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        BmDcsHelper.setProperty(items.get(idx), "viewMode", viewMode); //$NON-NLS-1$
        return container + " item [" + idx + "] viewMode set to " + viewMode; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.43.x: removes a settings variant by name from
     * {@code Schema.getSettingsVariants()}.
     */
    private Object doRemoveSettingsVariant(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        if (variants == null)
        {
            throw new RuntimeException("Schema.getSettingsVariants() not available"); //$NON-NLS-1$
        }
        EObject toRemove = null;
        for (EObject v : variants)
        {
            Object n = invokeGetter(v, "getName"); //$NON-NLS-1$
            if (n != null && name.equalsIgnoreCase(n.toString()))
            {
                toRemove = v;
                break;
            }
        }
        if (toRemove == null)
        {
            throw notFoundTag(name, "settingsVariant"); //$NON-NLS-1$
        }
        variants.remove(toRemove);
        return "settings variant '" + name + "' removed"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.43.x DCS batch 4a: clones an existing settings variant. Copies the
     * source variant's whole {@code DataCompositionSettings} tree (via
     * {@code EcoreUtil.copy}) into a new variant carrying the supplied
     * {@code name} / {@code presentation}, appended to
     * {@code Schema.getSettingsVariants()}. Idempotent: a duplicate target name
     * surfaces {@code alreadyExists}; a missing source surfaces {@code notFound}.
     */
    private Object doCloneSettingsVariant(Map<String, String> params, EObject schema)
    {
        String sourceName = required(params, "sourceName"); //$NON-NLS-1$
        String name = required(params, "name"); //$NON-NLS-1$
        String presentation = JsonUtils.extractStringArgument(params, "presentation"); //$NON-NLS-1$
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        if (variants == null)
        {
            throw new RuntimeException("Schema.getSettingsVariants() not available"); //$NON-NLS-1$
        }
        EObject src = null;
        for (EObject v : variants)
        {
            Object n = invokeGetter(v, "getName"); //$NON-NLS-1$
            if (n != null && sourceName.equalsIgnoreCase(n.toString()))
            {
                src = v;
            }
            if (n != null && name.equalsIgnoreCase(n.toString()))
            {
                throw alreadyExistsTag(name, "settingsVariant"); //$NON-NLS-1$
            }
        }
        if (src == null)
        {
            throw notFoundTag(sourceName, "settingsVariant"); //$NON-NLS-1$
        }
        Object clone = BmDcsHelper.createElement("createSettingsVariant"); //$NON-NLS-1$
        if (clone == null)
        {
            throw factoryMissingTag("createSettingsVariant"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(clone, "name", name); //$NON-NLS-1$
        setPresentationProperty(clone, "presentation", presentation != null ? presentation : name); //$NON-NLS-1$
        // Deep-copy the source variant's settings tree so the clone opens with the
        // same groupings / filters / order rather than an empty/invalid variant.
        Object copied = org.eclipse.emf.ecore.util.EcoreUtil
            .copy((org.eclipse.emf.ecore.EObject) invokeGetter(src, "getSettings")); //$NON-NLS-1$
        if (copied != null)
        {
            BmDcsHelper.setProperty(clone, "settings", copied); //$NON-NLS-1$
        }
        variants.add((EObject) clone);
        return "settings variant '" + name + "' cloned from '" + sourceName + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * 1.43.x: renames an existing settings variant (and its presentation when
     * given). Idempotent guards: missing source -> {@code notFound}; the target
     * name already used by another variant -> {@code alreadyExists}.
     */
    private Object doRenameSettingsVariant(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String newName = required(params, "newName"); //$NON-NLS-1$
        String presentation = JsonUtils.extractStringArgument(params, "presentation"); //$NON-NLS-1$
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        if (variants == null)
        {
            throw new RuntimeException("Schema.getSettingsVariants() not available"); //$NON-NLS-1$
        }
        EObject target = null;
        for (EObject v : variants)
        {
            Object n = invokeGetter(v, "getName"); //$NON-NLS-1$
            String vn = n != null ? n.toString() : ""; //$NON-NLS-1$
            if (name.equalsIgnoreCase(vn))
            {
                target = v;
            }
            else if (newName.equalsIgnoreCase(vn))
            {
                throw alreadyExistsTag(newName, "settingsVariant"); //$NON-NLS-1$
            }
        }
        if (target == null)
        {
            throw notFoundTag(name, "settingsVariant"); //$NON-NLS-1$
        }
        BmDcsHelper.setProperty(target, "name", newName); //$NON-NLS-1$
        if (presentation != null && !presentation.isEmpty())
        {
            setPresentationProperty(target, "presentation", presentation); //$NON-NLS-1$
        }
        return "settings variant '" + name + "' renamed to '" + newName + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * 1.43.x: removes a user field ({@code DataCompositionUserFieldExpression}) by
     * its dataPath/name from the default variant's {@code getUserFields()}.
     * Read-only access (does not create settings); missing -> {@code notFound}.
     */
    private Object doRemoveUserField(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        Object settings = ensureDefaultSettings(schema);
        Object userFieldsContainer =
            settings != null ? invokeGetter(settings, "getUserFields") : null; //$NON-NLS-1$
        EList<EObject> items = userFieldsContainer != null
            ? BmDcsHelper.getEObjectList(userFieldsContainer, "getItems") : null; //$NON-NLS-1$
        if (items == null || items.isEmpty())
        {
            throw notFoundTag(name, "userField"); //$NON-NLS-1$
        }
        EObject toRemove = null;
        for (EObject existing : items)
        {
            Object dp = invokeGetter(existing, "getDataPath"); //$NON-NLS-1$
            if (dp != null && name.equalsIgnoreCase(dp.toString()))
            {
                toRemove = existing;
                break;
            }
        }
        if (toRemove == null)
        {
            throw notFoundTag(name, "userField"); //$NON-NLS-1$
        }
        items.remove(toRemove);
        return "user field '" + name + "' removed"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.43.x: updates an existing user field's expression and/or title (the
     * symmetric update for add_user_field / remove_user_field). Read-only access
     * to the default variant's user fields; missing -> {@code notFound}.
     */
    private Object doSetUserField(Map<String, String> params, EObject schema)
    {
        String name = required(params, "name"); //$NON-NLS-1$
        String expression = JsonUtils.extractStringArgument(params, "expression"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        if ((expression == null || expression.isEmpty()) && title == null)
        {
            throw new RuntimeException("set_user_field: pass expression and/or title to update"); //$NON-NLS-1$
        }
        Object settings = ensureDefaultSettings(schema);
        Object userFieldsContainer =
            settings != null ? invokeGetter(settings, "getUserFields") : null; //$NON-NLS-1$
        EList<EObject> items = userFieldsContainer != null
            ? BmDcsHelper.getEObjectList(userFieldsContainer, "getItems") : null; //$NON-NLS-1$
        if (items == null || items.isEmpty())
        {
            throw notFoundTag(name, "userField"); //$NON-NLS-1$
        }
        EObject target = null;
        for (EObject existing : items)
        {
            Object dp = invokeGetter(existing, "getDataPath"); //$NON-NLS-1$
            if (dp != null && name.equalsIgnoreCase(dp.toString()))
            {
                target = existing;
                break;
            }
        }
        if (target == null)
        {
            throw notFoundTag(name, "userField"); //$NON-NLS-1$
        }
        if (expression != null && !expression.isEmpty())
        {
            BmDcsHelper.setProperty(target, "detailExpression", expression); //$NON-NLS-1$
        }
        if (title != null)
        {
            setPresentationProperty(target, "title", title); //$NON-NLS-1$
        }
        return "user field '" + name + "' updated"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -----------------------------------------------------------------------
    // 1.43.x DCS batch 4b: heuristic query editing (text-splice) handlers
    // -----------------------------------------------------------------------

    /**
     * 1.43.x batch 4b: appends a field to the SELECT list of a dataset's query.
     * Inserts {@code ",\n\t<field>"} immediately before the first top-level
     * FROM/ИЗ. The edit is purely lexical (token-splice); the resulting query is
     * mandatorily re-validated via {@link QlValidator} before it is written, and
     * multi-statement / UNION queries are rejected up front.
     */
    private Object doAddQueryField(Map<String, String> params, EObject schema, IProject project)
    {
        String dataSetName = required(params, "dataSetName"); //$NON-NLS-1$
        String field = required(params, "field"); //$NON-NLS-1$
        EObject ds = resolveQueryDataSet(schema, dataSetName);
        String query = currentQuery(ds, dataSetName);
        gateMultiStatement(query);
        boolean english = isEnglishQuery(query);
        int fromIdx = indexOfKeyword(query, english ? "FROM" : "ИЗ"); //$NON-NLS-1$ //$NON-NLS-2$
        if (fromIdx < 0)
        {
            // No FROM clause - append to the end of the (single) SELECT list.
            fromIdx = query.length();
        }
        String spliced = query.substring(0, fromIdx) + ",\n\t" + field + "\n" //$NON-NLS-1$ //$NON-NLS-2$
            + query.substring(fromIdx);
        validateAndSetQuery(ds, dataSetName, spliced, project);
        return "query field '" + field + "' added to dataset '" + dataSetName + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * 1.43.x batch 4b: removes a field token (with one adjacent comma) from the
     * SELECT..FROM region of a dataset's query. The match is exact on the field
     * token; a missing field surfaces {@code notFound}. Mandatorily re-validated
     * before write; multi-statement / UNION queries rejected.
     */
    private Object doRemoveQueryField(Map<String, String> params, EObject schema, IProject project)
    {
        String dataSetName = required(params, "dataSetName"); //$NON-NLS-1$
        String field = required(params, "field"); //$NON-NLS-1$
        EObject ds = resolveQueryDataSet(schema, dataSetName);
        String query = currentQuery(ds, dataSetName);
        gateMultiStatement(query);
        boolean english = isEnglishQuery(query);
        int selectIdx = indexOfKeyword(query, english ? "SELECT" : "ВЫБРАТЬ"); //$NON-NLS-1$ //$NON-NLS-2$
        int fromIdx = indexOfKeyword(query, english ? "FROM" : "ИЗ"); //$NON-NLS-1$ //$NON-NLS-2$
        int regionStart = selectIdx >= 0 ? selectIdx : 0;
        int regionEnd = fromIdx >= 0 ? fromIdx : query.length();
        String region = query.substring(regionStart, regionEnd);
        boolean present = indexOfKeyword(region, field) >= 0;
        String newRegion = removeFieldToken(region, field);
        if (newRegion == null)
        {
            if (present)
            {
                // Found but not removable: it is the only field in the SELECT list,
                // and removing it would yield an invalid query. Report the real
                // cause instead of a misleading notFound.
                throw new RuntimeException("Cannot remove '" + field //$NON-NLS-1$
                    + "': it is the only field in the SELECT list. Rewrite the query via " //$NON-NLS-1$
                    + "set_data_set_property property=query."); //$NON-NLS-1$
            }
            throw notFoundTag(field, "queryField"); //$NON-NLS-1$
        }
        String spliced = query.substring(0, regionStart) + newRegion
            + query.substring(regionEnd);
        validateAndSetQuery(ds, dataSetName, spliced, project);
        return "query field '" + field + "' removed from dataset '" + dataSetName + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * 1.43.x batch 4b: adds a condition to a dataset's query. When a WHERE/ГДЕ
     * already exists, the condition is appended as {@code "\n\tAND (<cond>)"}
     * right before the first of GROUP BY / ORDER BY / totals (or at end). When no
     * WHERE/ГДЕ exists, a new {@code "\nГДЕ <cond>"} (or {@code WHERE} for English)
     * is inserted after the FROM clause and before any GROUP/ORDER/totals.
     * Mandatorily re-validated before write; multi-statement / UNION rejected.
     */
    private Object doAddQueryCondition(Map<String, String> params, EObject schema, IProject project)
    {
        String dataSetName = required(params, "dataSetName"); //$NON-NLS-1$
        String condition = required(params, "condition"); //$NON-NLS-1$
        EObject ds = resolveQueryDataSet(schema, dataSetName);
        String query = currentQuery(ds, dataSetName);
        gateMultiStatement(query);
        boolean english = isEnglishQuery(query);
        int whereIdx = indexOfKeyword(query, english ? "WHERE" : "ГДЕ"); //$NON-NLS-1$ //$NON-NLS-2$
        // The tail clauses that must stay AFTER the inserted condition.
        int tailIdx = firstTailClause(query, english);
        String spliced;
        if (whereIdx >= 0)
        {
            // Insert "AND (cond)" right before the tail (GROUP/ORDER/totals) or at end.
            int insertAt = tailIdx >= 0 ? tailIdx : query.length();
            spliced = query.substring(0, insertAt) + "\n\tAND (" + condition + ")\n" //$NON-NLS-1$ //$NON-NLS-2$
                + query.substring(insertAt);
        }
        else
        {
            // No WHERE yet: place a fresh WHERE/ГДЕ after FROM, before tail clauses.
            int fromIdx = indexOfKeyword(query, english ? "FROM" : "ИЗ"); //$NON-NLS-1$ //$NON-NLS-2$
            int insertAt;
            if (tailIdx >= 0)
            {
                insertAt = tailIdx;
            }
            else
            {
                insertAt = query.length();
            }
            // Guard: a WHERE must come after FROM. If FROM is missing the query is
            // unusual for a splice - validation below will catch any breakage.
            if (fromIdx >= 0 && insertAt < fromIdx)
            {
                insertAt = query.length();
            }
            String keyword = english ? "WHERE" : "ГДЕ"; //$NON-NLS-1$ //$NON-NLS-2$
            spliced = query.substring(0, insertAt) + "\n" + keyword + " " + condition + "\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + query.substring(insertAt);
        }
        validateAndSetQuery(ds, dataSetName, spliced, project);
        return "query condition added to dataset '" + dataSetName + "'"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.43.x L1 CRUD-symmetry: removes a condition from a dataset's query
     * WHERE/ГДЕ clause - the inverse of {@link #doAddQueryCondition}. The
     * {@code condition} is matched either parenthesised (the form
     * add_query_condition writes, {@code "AND (cond)"}) or bare, dropped together
     * with one adjacent AND/И connector; the whole WHERE/ГДЕ clause is removed
     * when it held the sole condition. Conservative: an unmatched condition (e.g.
     * whitespace differs) yields {@code notFound} rather than a wrong removal, and
     * the result is MANDATORILY re-validated before write (a bad splice is
     * rejected, never written). Multi-statement / UNION rejected. (Like
     * add_query_condition, a tail-clause keyword - ORDER BY / GROUP BY - inside a
     * subquery can truncate the scanned WHERE region, so the condition is then
     * reported notFound rather than mis-spliced; edit such queries via
     * set_data_set_property property=query.)
     */
    private Object doRemoveQueryCondition(Map<String, String> params, EObject schema, IProject project)
    {
        String dataSetName = required(params, "dataSetName"); //$NON-NLS-1$
        String condition = required(params, "condition"); //$NON-NLS-1$
        EObject ds = resolveQueryDataSet(schema, dataSetName);
        String query = currentQuery(ds, dataSetName);
        gateMultiStatement(query);
        boolean english = isEnglishQuery(query);
        int whereIdx = indexOfKeyword(query, english ? "WHERE" : "ГДЕ"); //$NON-NLS-1$ //$NON-NLS-2$
        if (whereIdx < 0)
        {
            throw notFoundTag(condition, "queryCondition"); //$NON-NLS-1$
        }
        int tailIdx = firstTailClause(query, english);
        int whereEnd = (tailIdx > whereIdx) ? tailIdx : query.length();
        String whereRegion = query.substring(whereIdx, whereEnd);
        String newWhere = removeConditionToken(whereRegion, condition, english);
        if (newWhere == null)
        {
            throw notFoundTag(condition, "queryCondition"); //$NON-NLS-1$
        }
        String spliced = query.substring(0, whereIdx) + newWhere + query.substring(whereEnd);
        validateAndSetQuery(ds, dataSetName, spliced, project);
        return "query condition removed from dataset '" + dataSetName + "'"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * 1.43.x L1: removes a condition token (parenthesised {@code "(cond)"} as
     * written by add_query_condition, or bare) from a WHERE/ГДЕ region, together
     * with one adjacent AND/И connector. Returns the rewritten region - the text
     * before the keyword (i.e. the whole WHERE/ГДЕ clause dropped) when the sole
     * condition is removed - or {@code null} when the condition text is absent.
     * The region must start at the WHERE/ГДЕ keyword.
     */
    private static String removeConditionToken(String whereRegion, String condition, boolean english)
    {
        String kw = english ? "WHERE" : "ГДЕ"; //$NON-NLS-1$ //$NON-NLS-2$
        int kwIdx = indexOfKeyword(whereRegion, kw);
        if (kwIdx < 0)
        {
            return null;
        }
        int bodyStart = kwIdx + kw.length();
        String body = whereRegion.substring(bodyStart);
        String cond = condition.trim();
        // Prefer the parenthesised form add_query_condition writes ("(cond)") -
        // its surrounding "(" / ")" make it inherently word-bounded.
        int at = body.indexOf("(" + cond + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        int matchLen = cond.length() + 2;
        if (at < 0)
        {
            // Bare (non-parenthesised) fallback for hand-written conditions.
            // REQUIRE word boundaries: a bare cond "A>1" must NOT match inside
            // "A>10" - that splice would stay valid SQL, pass re-validation and
            // silently corrupt the query. Fail-closed to notFound otherwise.
            int bare = body.indexOf(cond);
            if (bare >= 0
                && (bare == 0 || !isWordChar(body.charAt(bare - 1)))
                && (bare + cond.length() >= body.length()
                    || !isWordChar(body.charAt(bare + cond.length()))))
            {
                at = bare;
                matchLen = cond.length();
            }
        }
        if (at < 0)
        {
            return null;
        }
        int matchEnd = at + matchLen;
        // The connector is AND or И. add_query_condition always writes "AND" (even
        // into a Russian ГДЕ query - 1C accepts mixed keywords), while a hand-written
        // WHERE may use "И", so match BOTH regardless of the query language. (The
        // WHERE/ГДЕ keyword itself is language-correct via `english`.)
        int connBefore = Math.max(lastKeywordBefore(body, "AND", at), //$NON-NLS-1$
            lastKeywordBefore(body, "И", at)); //$NON-NLS-1$
        int connAfter = nearestAfter(indexOfKeyword(body, "AND", matchEnd), //$NON-NLS-1$
            indexOfKeyword(body, "И", matchEnd)); //$NON-NLS-1$
        String newBody;
        if (connBefore >= 0)
        {
            // "... AND (cond)" - drop the connector through the condition.
            newBody = body.substring(0, connBefore) + body.substring(matchEnd);
        }
        else if (connAfter >= 0)
        {
            // Leading "(cond) AND ..." - drop the condition through the connector
            // (length 3 for "AND", 1 for "И").
            int connLen = indexOfKeyword(body, "AND", connAfter) == connAfter ? 3 : 1; //$NON-NLS-1$
            newBody = body.substring(0, at) + body.substring(connAfter + connLen);
        }
        else
        {
            // Sole condition - drop the entire WHERE/ГДЕ clause.
            return whereRegion.substring(0, kwIdx);
        }
        if (newBody.trim().isEmpty())
        {
            return whereRegion.substring(0, kwIdx);
        }
        return whereRegion.substring(0, bodyStart) + newBody;
    }

    /**
     * 1.43.x L1: {@link #indexOfKeyword(String, String)} starting at
     * {@code fromIndex} (word-boundary, case-insensitive; boundaries checked
     * against the original text, not a substring).
     */
    private static int indexOfKeyword(String text, String keyword, int fromIndex)
    {
        if (text == null || keyword == null || keyword.isEmpty())
        {
            return -1;
        }
        String upperText = text.toUpperCase();
        String upperKw = keyword.toUpperCase();
        int from = Math.max(0, fromIndex);
        while (true)
        {
            int idx = upperText.indexOf(upperKw, from);
            if (idx < 0)
            {
                return -1;
            }
            boolean leftOk = idx == 0 || !isWordChar(upperText.charAt(idx - 1));
            int after = idx + upperKw.length();
            boolean rightOk = after >= upperText.length() || !isWordChar(upperText.charAt(after));
            if (leftOk && rightOk)
            {
                return idx;
            }
            from = idx + 1;
        }
    }

    /**
     * 1.43.x L1: index of the last word-boundary occurrence of {@code keyword}
     * strictly before {@code before}, or {@code -1}.
     */
    private static int lastKeywordBefore(String text, String keyword, int before)
    {
        int last = -1;
        int from = 0;
        while (true)
        {
            int idx = indexOfKeyword(text, keyword, from);
            if (idx < 0 || idx >= before)
            {
                break;
            }
            last = idx;
            from = idx + 1;
        }
        return last;
    }

    /**
     * 1.43.x L1: the nearest (smallest) non-negative of two indices, or {@code -1}
     * when both are negative. Picks whichever AND/И connector comes first.
     */
    private static int nearestAfter(int a, int b)
    {
        if (a < 0)
        {
            return b;
        }
        if (b < 0)
        {
            return a;
        }
        return Math.min(a, b);
    }

    /**
     * 1.43.x batch 4b: resolves a {@code DataSetQuery} by name; throws
     * {@code notFound} when absent.
     */
    private EObject resolveQueryDataSet(EObject schema, String dataSetName)
    {
        EObject ds = BmDcsHelper.findByNameInList(schema, "getDataSets", dataSetName); //$NON-NLS-1$
        if (ds == null)
        {
            throw notFoundTag(dataSetName, "dataSet"); //$NON-NLS-1$
        }
        return ds;
    }

    /**
     * 1.43.x batch 4b: reads the dataset's {@code getQuery()} text; throws when
     * the dataset has no query (e.g. an Object/Union dataset).
     */
    private String currentQuery(EObject ds, String dataSetName)
    {
        Object q = invokeGetter(ds, "getQuery"); //$NON-NLS-1$
        if (q == null || q.toString().trim().isEmpty())
        {
            throw new RuntimeException("dataset '" + dataSetName //$NON-NLS-1$
                + "' has no query text (heuristic query editing needs a Query dataset)"); //$NON-NLS-1$
        }
        return q.toString();
    }

    /**
     * 1.43.x batch 4b: detects whether a query is written in English keywords
     * (SELECT ...) vs Russian (ВЫБРАТЬ ...). Decides by the first recognised
     * leading keyword; defaults to Russian (the platform default).
     */
    private boolean isEnglishQuery(String query)
    {
        String trimmed = query == null ? "" : query.trim().toUpperCase(); //$NON-NLS-1$
        if (trimmed.startsWith("SELECT")) //$NON-NLS-1$
        {
            return true;
        }
        if (trimmed.startsWith("ВЫБРАТЬ")) //$NON-NLS-1$
        {
            return false;
        }
        // Fall back to whichever keyword set appears - English SELECT presence
        // anywhere is a strong signal; otherwise Russian.
        return indexOfKeyword(query, "SELECT") >= 0 //$NON-NLS-1$
            && indexOfKeyword(query, "ВЫБРАТЬ") < 0; //$NON-NLS-1$
    }

    /**
     * 1.43.x batch 4b: GATE - rejects queries that token-splice cannot safely
     * edit: more than one top-level SELECT/ВЫБРАТЬ, or any ОБЪЕДИНИТЬ / UNION.
     * Throws a {@code multiStatementUnsupported}-tagged block.
     */
    private void gateMultiStatement(String query)
    {
        String upper = query.toUpperCase();
        boolean union = countKeyword(query, "ОБЪЕДИНИТЬ") > 0 //$NON-NLS-1$
            || countKeyword(query, "UNION") > 0; //$NON-NLS-1$
        int selectCount = countKeyword(query, "ВЫБРАТЬ") + countKeyword(query, "SELECT"); //$NON-NLS-1$ //$NON-NLS-2$
        if (union || selectCount > 1)
        {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("selectCount", selectCount); //$NON-NLS-1$
            data.put("hasUnion", union); //$NON-NLS-1$
            throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                "query has multiple SELECT statements or UNION - " //$NON-NLS-1$
                    + "heuristic token-splice is unsafe here", //$NON-NLS-1$
                "Edit the query text directly (set_data_set_property property=query) " //$NON-NLS-1$
                    + "for multi-statement / UNION queries.", //$NON-NLS-1$
                new MetadataGuards.ErrorTag(ErrorTags.MULTI_STATEMENT_UNSUPPORTED.wire(), data)));
        }
        // 'upper' retained for clarity / potential future checks
        if (upper.isEmpty())
        {
            throw new RuntimeException("query is empty"); //$NON-NLS-1$
        }
    }

    /**
     * 1.43.x batch 4b: MANDATORY post-splice validation. Re-validates the
     * spliced query via {@link QlValidator}; on any error the query is NOT
     * written and a {@code queryValidation}-tagged block is thrown. On success
     * the new query is set on the dataset.
     */
    private void validateAndSetQuery(EObject ds, String dataSetName, String newQuery,
        IProject project)
    {
        QlValidator.ValidationResult vr = QlValidator.validateQueryText(project, newQuery, true);
        if (vr.unconfirmed)
        {
            // This one deliberately writes when validation is UNAVAILABLE - an EDT without query
            // support would otherwise block every splice. Unconfirmed is the other thing: checking
            // was possible and did not settle, so the splice waits.
            Map<String, Object> unsettled = vr.toTagData();
            unsettled.put("dataSet", dataSetName); //$NON-NLS-1$
            unsettled.put("attemptedQuery", newQuery); //$NON-NLS-1$
            throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                "the spliced query could not be checked - edit not applied", //$NON-NLS-1$
                vr.unavailableReason,
                new MetadataGuards.ErrorTag(ErrorTags.QUERY_VALIDATION.wire(), unsettled)));
        }
        if (vr.available && vr.hasErrors())
        {
            Map<String, Object> data = vr.toTagData();
            data.put("dataSet", dataSetName); //$NON-NLS-1$
            data.put("attemptedQuery", newQuery); //$NON-NLS-1$
            throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                "spliced query has " + vr.errorCount //$NON-NLS-1$
                    + " validation error(s) - edit not applied", //$NON-NLS-1$
                "The lexical splice produced invalid query text; check field/condition syntax.", //$NON-NLS-1$
                new MetadataGuards.ErrorTag(ErrorTags.QUERY_VALIDATION.wire(), data)));
        }
        String err = BmDcsHelper.setProperty(ds, "query", newQuery); //$NON-NLS-1$
        if (err != null)
        {
            throw new RuntimeException("could not set query on dataset '" //$NON-NLS-1$
                + dataSetName + "': " + err); //$NON-NLS-1$
        }
    }

    /**
     * 1.43.x batch 4b: case-insensitive, word-boundary index of a query keyword.
     * Returns the index of the first standalone occurrence, or {@code -1}.
     * (Word-boundary so {@code ИЗДЕЛИЕ} does not match {@code ИЗ}, and
     * {@code FROMAGE} does not match {@code FROM}.)
     */
    private static int indexOfKeyword(String text, String keyword)
    {
        if (text == null || keyword == null || keyword.isEmpty())
        {
            return -1;
        }
        String upperText = text.toUpperCase();
        String upperKw = keyword.toUpperCase();
        int from = 0;
        while (true)
        {
            int idx = upperText.indexOf(upperKw, from);
            if (idx < 0)
            {
                return -1;
            }
            boolean leftOk = idx == 0 || !isWordChar(upperText.charAt(idx - 1));
            int after = idx + upperKw.length();
            boolean rightOk = after >= upperText.length() || !isWordChar(upperText.charAt(after));
            if (leftOk && rightOk)
            {
                return idx;
            }
            from = idx + 1;
        }
    }

    /**
     * 1.43.x batch 4b: counts standalone (word-boundary) occurrences of a
     * keyword, case-insensitively.
     */
    private static int countKeyword(String text, String keyword)
    {
        if (text == null || keyword == null || keyword.isEmpty())
        {
            return 0;
        }
        String upperText = text.toUpperCase();
        String upperKw = keyword.toUpperCase();
        int count = 0;
        int from = 0;
        while (true)
        {
            int idx = upperText.indexOf(upperKw, from);
            if (idx < 0)
            {
                return count;
            }
            boolean leftOk = idx == 0 || !isWordChar(upperText.charAt(idx - 1));
            int after = idx + upperKw.length();
            boolean rightOk = after >= upperText.length() || !isWordChar(upperText.charAt(after));
            if (leftOk && rightOk)
            {
                count++;
            }
            from = idx + 1;
        }
    }

    /**
     * 1.43.x batch 4b: index of the first "tail clause" keyword (GROUP BY /
     * ORDER BY / totals / their Russian forms) at or after the FROM clause, or
     * {@code -1} when none. A new condition must be spliced BEFORE these.
     */
    private static int firstTailClause(String query, boolean english)
    {
        String[] keywords = english
            ? new String[] { "GROUP BY", "ORDER BY", "TOTALS", "HAVING" } //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            : new String[] { "СГРУППИРОВАТЬ ПО", "УПОРЯДОЧИТЬ ПО", "ИТОГИ", "ИМЕЮЩИЕ" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        int best = -1;
        for (String kw : keywords)
        {
            int idx = indexOfKeyword(query, kw);
            if (idx >= 0 && (best < 0 || idx < best))
            {
                best = idx;
            }
        }
        return best;
    }

    /**
     * 1.43.x batch 4b: removes an exact field token (case-insensitive,
     * word-boundary) together with one adjacent comma from a SELECT-list region.
     * Returns the rewritten region, or {@code null} when the field token was not
     * found. Handles the three positions: leading ({@code field, ...}), middle
     * ({@code ..., field, ...}) and trailing ({@code ..., field}).
     */
    private static String removeFieldToken(String region, String field)
    {
        int idx = indexOfKeyword(region, field);
        if (idx < 0)
        {
            return null;
        }
        int end = idx + field.length();
        // Look for a comma after the token (skipping whitespace) -> middle/leading.
        int afterComma = -1;
        for (int i = end; i < region.length(); i++)
        {
            char c = region.charAt(i);
            if (Character.isWhitespace(c))
            {
                continue;
            }
            if (c == ',')
            {
                afterComma = i;
            }
            break;
        }
        if (afterComma >= 0)
        {
            // Remove token and the trailing comma: "field," -> ""
            return region.substring(0, idx) + region.substring(afterComma + 1);
        }
        // No trailing comma: trailing field. Remove the preceding comma instead.
        int beforeComma = -1;
        for (int i = idx - 1; i >= 0; i--)
        {
            char c = region.charAt(i);
            if (Character.isWhitespace(c))
            {
                continue;
            }
            if (c == ',')
            {
                beforeComma = i;
            }
            break;
        }
        if (beforeComma >= 0)
        {
            return region.substring(0, beforeComma) + region.substring(end);
        }
        // Sole field in the SELECT list - removing it would break the query.
        // Leave region unchanged but signal not-removable via null so caller
        // reports notFound rather than silently producing an invalid query.
        return null;
    }

    /**
     * 1.43.x batch 4b: identifier character test for keyword word-boundary
     * detection - letters (incl. Cyrillic via {@link Character#isLetter}),
     * digits and underscore.
     */
    private static boolean isWordChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * 1.41: structured "factory not exposed" error for the AI to surface
     * a {@code dcsFactoryMethodNotFound} tag with the tried method names.
     */
    private MetadataGuards.BlockedGuardException factoryMissingTag(String triedMethods)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("triedMethods", triedMethods); //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "dcsFactoryMethodNotFound: " + triedMethods, //$NON-NLS-1$
            "EDT factory does not expose this method - GUI fallback required.", //$NON-NLS-1$
            new MetadataGuards.ErrorTag(ErrorTags.DCS_FACTORY_METHOD_NOT_FOUND.wire(), data)));
    }

    /**
     * 1.41: walks {@code structure}, returning the items collection regardless
     * of whether it's an EList directly or a wrapper with {@code getItems()}.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private EList<EObject> resolveStructureItems(Object structure)
    {
        if (structure instanceof EList)
        {
            return (EList) structure;
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(structure, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            throw new RuntimeException("Structure has no items collection"); //$NON-NLS-1$
        }
        return items;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Object invokeGetter(Object target, String methodName)
    {
        try
        {
            java.lang.reflect.Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        }
        catch (Exception ignored)
        {
            // The receiver is an Object whose shape this helper does not control, so a
            // missing member is an answer, not a failure - the caller reads the null as
            // "this element has no such property".
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // 1.43.x DCS batch 4b: appearance font/color formatting helpers
    // -----------------------------------------------------------------------

    /**
     * 1.43.x batch 4b: parses an appearance spec string of the form
     * {@code "Name=Value;Name=Value"} into an ordered map. Empty / blank
     * entries are skipped; the first {@code =} splits key from value, so the
     * value may itself contain {@code =}. Returns an empty map for {@code null}
     * / blank input.
     */
    private static Map<String, String> parseAppearanceSpec(String spec)
    {
        Map<String, String> out = new LinkedHashMap<>();
        if (spec == null || spec.trim().isEmpty())
        {
            return out;
        }
        for (String pair : spec.split(";")) //$NON-NLS-1$
        {
            String p = pair.trim();
            if (p.isEmpty())
            {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq <= 0)
            {
                continue;
            }
            String key = p.substring(0, eq).trim();
            String value = p.substring(eq + 1).trim();
            if (!key.isEmpty())
            {
                out.put(key, value);
            }
        }
        return out;
    }

    /**
     * 1.43.x batch 4b: maps an incoming appearance key (English or Russian,
     * case-insensitive) to the Russian {@code DataCompositionParameter.getValue()}
     * key that EDT stores in the appearance parameter list. Returns {@code null}
     * for an unrecognised key.
     */
    private static String mapAppearanceKey(String key)
    {
        if (key == null)
        {
            return null;
        }
        switch (key.trim().toLowerCase())
        {
            case "textcolor": //$NON-NLS-1$
            case "цветтекста": //$NON-NLS-1$
                return "ЦветТекста"; //$NON-NLS-1$
            case "backcolor": //$NON-NLS-1$
            case "цветфона": //$NON-NLS-1$
                return "ЦветФона"; //$NON-NLS-1$
            case "bordercolor": //$NON-NLS-1$
            case "цветграницы": //$NON-NLS-1$
                return "ЦветГраницы"; //$NON-NLS-1$
            case "font": //$NON-NLS-1$
            case "шрифт": //$NON-NLS-1$
                return "Шрифт"; //$NON-NLS-1$
            case "format": //$NON-NLS-1$
            case "формат": //$NON-NLS-1$
                return "Формат"; //$NON-NLS-1$
            default:
                return null;
        }
    }

    /**
     * 1.43.x batch 4b: upserts a single appearance parameter on a
     * {@code DataCompositionAppearance} container (which extends
     * {@code ParameterValues}). Locates the existing
     * {@code DataCompositionParameterValue} whose
     * {@code getParameter().getValue()} equals {@code key} (case-insensitive),
     * or creates one; then marks it used and replaces its values with
     * {@code valueObj} (an mcore Value). A {@code null} {@code valueObj} clears
     * the value (leaving the parameter present but empty). Null-guarded
     * throughout.
     */
    private void upsertAppearanceParam(Object appearanceContainer, String key, Object valueObj)
    {
        if (appearanceContainer == null || key == null)
        {
            return;
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(appearanceContainer, "getItems"); //$NON-NLS-1$
        if (items == null)
        {
            return;
        }
        EObject pv = null;
        for (EObject it : items)
        {
            Object param = invokeGetter(it, "getParameter"); //$NON-NLS-1$
            Object paramKey = param != null ? invokeGetter(param, "getValue") : null; //$NON-NLS-1$
            if (paramKey != null && key.equalsIgnoreCase(paramKey.toString()))
            {
                pv = it;
                break;
            }
        }
        if (pv == null)
        {
            Object created = BmDcsHelper.createElement("createDataCompositionParameterValue"); //$NON-NLS-1$
            Object param = BmDcsHelper.createElement("createDataCompositionParameter"); //$NON-NLS-1$
            if (created == null || param == null)
            {
                return;
            }
            BmDcsHelper.setProperty(param, "value", key); //$NON-NLS-1$
            BmDcsHelper.setProperty(created, "parameter", param); //$NON-NLS-1$
            items.add((EObject) created);
            pv = (EObject) created;
        }
        BmDcsHelper.setProperty(pv, "use", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        EList<EObject> vals = BmDcsHelper.getEObjectList(pv, "getValues"); //$NON-NLS-1$
        if (vals != null)
        {
            vals.clear();
            if (valueObj != null)
            {
                vals.add((EObject) valueObj);
            }
        }
    }

    /**
     * 1.43.x batch 4b: applies a parsed appearance spec to a
     * {@code DataCompositionAppearance} container. Each entry is mapped to its
     * Russian parameter key, the value is built by type (color keys -> hex
     * color value; Font -> font value; everything else -> literal string
     * value), and {@link #upsertAppearanceParam} sets it. Style / system
     * references (e.g. {@code "Style.X"} or {@code "sys:..."}, or a color key
     * whose value is not a parseable hex) are skipped and collected into the
     * returned {@code styleRefNotSupported} list rather than throwing. Returns
     * the list of skipped {@code "key=value"} entries (possibly empty).
     */
    private List<String> applyAppearanceSpec(Object appearanceContainer, String appearanceSpec)
    {
        List<String> skipped = new java.util.ArrayList<>();
        Map<String, String> entries = parseAppearanceSpec(appearanceSpec);
        for (Map.Entry<String, String> e : entries.entrySet())
        {
            String russianKey = mapAppearanceKey(e.getKey());
            String value = e.getValue();
            if (russianKey == null)
            {
                skipped.add(e.getKey() + "=" + value); //$NON-NLS-1$
                continue;
            }
            // Style / system references are not buildable as literal values.
            if (isStyleOrSystemRef(value))
            {
                skipped.add(e.getKey() + "=" + value); //$NON-NLS-1$
                continue;
            }
            Object valueObj;
            if ("ЦветТекста".equals(russianKey) || "ЦветФона".equals(russianKey) //$NON-NLS-1$ //$NON-NLS-2$
                || "ЦветГраницы".equals(russianKey)) //$NON-NLS-1$
            {
                valueObj = BmDcsHelper.createColorValueFromHex(value);
                if (valueObj == null)
                {
                    // Non-hex color (e.g. a named/system color) - skip, do not throw.
                    skipped.add(e.getKey() + "=" + value); //$NON-NLS-1$
                    continue;
                }
            }
            else if ("Шрифт".equals(russianKey)) //$NON-NLS-1$
            {
                valueObj = BmDcsHelper.createFontValueFromSpec(value);
                if (valueObj == null)
                {
                    skipped.add(e.getKey() + "=" + value); //$NON-NLS-1$
                    continue;
                }
            }
            else
            {
                // Формат and any other literal-valued key.
                valueObj = BmDcsHelper.createLiteralValue(value);
            }
            upsertAppearanceParam(appearanceContainer, russianKey, valueObj);
        }
        return skipped;
    }

    /**
     * 1.43.x batch 4b: heuristic - a value that refers to a style item or a
     * system value rather than a literal color/font/format. These cannot be
     * built as mcore literal values from a spec string.
     */
    private static boolean isStyleOrSystemRef(String value)
    {
        if (value == null)
        {
            return false;
        }
        String v = value.trim();
        return v.startsWith("Style.") || v.startsWith("sys:") //$NON-NLS-1$ //$NON-NLS-2$
            || v.startsWith("СтильЭлемента.") || v.startsWith("Стиль."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Returns the DataCompositionSettings that all settings operations
     * (grouping/filter/order/selection/appearance/...) must write into.
     * <p>
     * Critically, this is the settings tree of the "Основной" {@code SettingsVariant},
     * NOT {@code Schema.getDefaultSettings()}. The report's schema editor renders
     * settings from VARIANTS; a schema whose settings live only in defaultSettings
     * (with no variant) opens read-only / empty - exactly what the EDT wizard avoids
     * by always creating an "Основной" variant. Reuses the first existing variant or
     * creates "Основной".
     */
    /**
     * Whether this object IS the settings, rather than something holding them.
     * <p>
     * Told apart by shape, not by class name: a settings container carries the filter, order and
     * conditional-appearance children the handlers write into, and has no settings variants above
     * it. Asking for the type by name would tie this to one model version.
     * </p>
     *
     * @param candidate what the caller handed in.
     * @return true when the handlers can write into it directly
     */
    private static boolean alreadyASettingsContainer(EObject candidate)
    {
        if (candidate == null)
        {
            return false;
        }
        boolean hasVariants = false;
        boolean hasSettingsChildren = false;
        for (java.lang.reflect.Method m : candidate.getClass().getMethods())
        {
            if (m.getParameterCount() != 0)
            {
                continue;
            }
            String name = m.getName();
            if ("getSettingsVariants".equals(name)) //$NON-NLS-1$
            {
                hasVariants = true;
            }
            else if ("getConditionalAppearance".equals(name) || "getFilter".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                hasSettingsChildren = true;
            }
        }
        return hasSettingsChildren && !hasVariants;
    }

    private Object ensureDefaultSettings(EObject schema)
    {
        if (alreadyASettingsContainer(schema))
        {
            // Handed the settings themselves rather than a schema to dig them out of. That is how
            // a dynamic list arrives: its settings are their own top object, reached from the form
            // attribute, and there is no schema above them to ask for variants. Every handler
            // funnels through here, so recognising this makes all of them work on a list without
            // one of them being copied.
            return schema;
        }
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        if (variants == null)
        {
            return null;
        }
        Object variant = variants.isEmpty() ? null : variants.get(0);
        if (variant == null)
        {
            variant = BmDcsHelper.createElement("createSettingsVariant"); //$NON-NLS-1$
            if (variant == null)
            {
                return null;
            }
            BmDcsHelper.setProperty(variant, "name", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$
            setPresentationProperty(variant, "presentation", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$
            variants.add((EObject) variant);
        }
        Object settings = invokeGetter(variant, "getSettings"); //$NON-NLS-1$
        if (settings != null)
        {
            return settings;
        }
        settings = BmDcsHelper.createElement("createDataCompositionSettings"); //$NON-NLS-1$
        if (settings == null)
        {
            return null;
        }
        String err = BmDcsHelper.setProperty(variant, "settings", settings); //$NON-NLS-1$
        if (err != null)
        {
            // Setter unavailable (incompatible runtime) - return null so the
            // caller surfaces a clear error instead of an unattached object.
            return null;
        }
        return settings;
    }

    /**
     * Returns {@code parent.<getterName>()}, lazily creating the child via
     * {@code factoryMethod} and attaching it through {@code property} when null.
     * Single-valued settings containers (Filter / Order / Selection /
     * ConditionalAppearance) start null on a freshly created
     * {@code DataCompositionSettings}. Returns {@code null} only if the factory
     * cannot create the child.
     */
    private Object ensureChild(Object parent, String getterName, String factoryMethod,
        String property)
    {
        Object child = invokeGetter(parent, getterName);
        if (child != null)
        {
            return child;
        }
        child = BmDcsHelper.createElement(factoryMethod);
        if (child == null)
        {
            return null;
        }
        String err = BmDcsHelper.setProperty(parent, property, child);
        if (err != null)
        {
            return null;
        }
        return child;
    }

    private MetadataGuards.BlockedGuardException alreadyExistsTag(String name, String kind)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name); //$NON-NLS-1$
        data.put("kind", kind); //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            kind + " already exists: " + name, //$NON-NLS-1$
            "Use a different name or remove the existing element first.", //$NON-NLS-1$
            new MetadataGuards.ErrorTag(ErrorTags.ALREADY_EXISTS.wire(), data)));
    }

    private MetadataGuards.BlockedGuardException notFoundTag(String name, String kind)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name); //$NON-NLS-1$
        data.put("kind", kind); //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            kind + " not found: " + name, //$NON-NLS-1$
            "Verify the name and try again.", //$NON-NLS-1$
            new MetadataGuards.ErrorTag(ErrorTags.NOT_FOUND.wire(), data)));
    }

    private static String required(Map<String, String> params, String key)
    {
        String value = JsonUtils.extractStringArgument(params, key);
        if (value == null || value.isEmpty())
        {
            throw new RuntimeException(key + " is required"); //$NON-NLS-1$
        }
        return value;
    }

    private static String orDefault(String value, String fallback)
    {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    private static Integer extractInteger(Map<String, String> params, String key)
    {
        String s = JsonUtils.extractStringArgument(params, key);
        if (s == null || s.isEmpty())
        {
            return null;
        }
        try
        {
            return Integer.valueOf(s.trim());
        }
        catch (NumberFormatException nfe)
        {
            return null;
        }
    }

    private String handleHelp(Map<String, String> params)
    {
        String topic = JsonUtils.extractStringArgument(params, "topic"); //$NON-NLS-1$
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder("# dcs_workshop\n\n"); //$NON-NLS-1$
            sb.append("DCS schema constructor. ").append(OPS.size()) //$NON-NLS-1$
                .append(" operations across 4 groups (1.35 + 1.37 + 1.41 + 1.43.x).\n\n"); //$NON-NLS-1$
            sb.append("**Implemented (1.37):**\n"); //$NON-NLS-1$
            sb.append("- create_schema\n"); //$NON-NLS-1$
            sb.append("- add_dataset (auto query validation), remove_dataset (cascades calc fields)\n"); //$NON-NLS-1$
            sb.append("- add_field\n"); //$NON-NLS-1$
            sb.append("- add_parameter / set_parameter / remove_parameter / move_parameter\n"); //$NON-NLS-1$
            sb.append("- add_calculated_field / add_total (auto expression validation)\n"); //$NON-NLS-1$
            sb.append("- add_appearance (font/color guard), add_grouping, add_filter\n\n"); //$NON-NLS-1$
            sb.append("**Settings layer (1.41, dispatch-wired - use these exact names):**\n"); //$NON-NLS-1$
            sb.append("- add_user_field, add_settings_table, add_settings_chart\n"); //$NON-NLS-1$
            sb.append("- add_settings_filter_group, add_settings_order (aka add_order)\n"); //$NON-NLS-1$
            sb.append("- add_settings_selected_field, remove_settings_selected_field\n"); //$NON-NLS-1$
            sb.append("- add_settings_variant, set_settings_parameter, remove_settings_item\n"); //$NON-NLS-1$
            sb.append("- remove_conditional_appearance, set_data_set_field_appearance, set_output_parameter\n\n"); //$NON-NLS-1$
            sb.append("**Catch-up wave 2 (1.43.x, dispatch-wired - use these exact names):**\n"); //$NON-NLS-1$
            sb.append("- add_dataset_link, set_dataset_link_property, remove_dataset_link\n"); //$NON-NLS-1$
            sb.append("- set_dataset_property, remove_dataset_field\n"); //$NON-NLS-1$
            sb.append("- set_calculated_field, remove_calculated_field\n"); //$NON-NLS-1$
            sb.append("- set_total_field, remove_total_field\n"); //$NON-NLS-1$
            sb.append("- clear_settings_selected_fields, remove_settings_filter, remove_settings_order\n"); //$NON-NLS-1$
            sb.append("- set_settings_item_user_mode, remove_settings_variant, clone_settings_variant\n"); //$NON-NLS-1$
            sb.append("- rename_settings_variant, remove_user_field, set_user_field\n\n"); //$NON-NLS-1$
            sb.append("**Heuristic query editing (1.43.x batch 4b, dispatch-wired - use these exact names):**\n"); //$NON-NLS-1$
            sb.append("- add_query_field, remove_query_field, add_query_condition, remove_query_condition\n"); //$NON-NLS-1$
            sb.append("  (lexical token-splice of the dataset query; auto-revalidated; "); //$NON-NLS-1$
            sb.append("multi-statement / UNION rejected)\n"); //$NON-NLS-1$
            sb.append("- set_dataset_query (dataSetName + queryText): replaces the WHOLE query of an " //$NON-NLS-1$
                + "existing Query dataset; validated like add_dataset; multi-statement / UNION OK\n\n"); //$NON-NLS-1$
            sb.append("**Compatibility aliases** (same behavior, older names): add_data_source, " //$NON-NLS-1$
                + "remove_data_source, set_data_source_property, add_chart, select_field, " //$NON-NLS-1$
                + "deselect_field, add_variant, set_param_value.\n"); //$NON-NLS-1$
            sb.append("**Schemas inside a schema.** add_nested_schema (name, url, title) and " //$NON-NLS-1$
                + "remove_nested_schema work on the nested schemas of the root; a nested schema is " //$NON-NLS-1$
                + "created with a composition schema of its own. add_union_item and " //$NON-NLS-1$
                + "remove_union_item (dataSetName of the Union, name, dataSetType, queryText) work " //$NON-NLS-1$
                + "on the datasets a Union holds - which is what set_dataset_query on a Union " //$NON-NLS-1$
                + "sends you to.\n\n"); //$NON-NLS-1$
            sb.append("**A form's dynamic list.** Pass formFqn + attributeName instead of " //$NON-NLS-1$
                + "objectName + templateName and the operation applies to that list's settings: " //$NON-NLS-1$
                + "formFqn=Catalog.X.Form.ListForm, attributeName=List. ") //$NON-NLS-1$
                .append(SETTINGS_OPS.size())
                .append(" operations work this way - the settings half of the catalog. ") //$NON-NLS-1$
                .append("An operation that shapes a schema (datasets, parameters, variants) is " //$NON-NLS-1$
                    + "refused with notApplicableHere.\n"); //$NON-NLS-1$
            sb.append("**Topics:** workflow, dcsWorkflow, propertyValues, examples, errorTags\n"); //$NON-NLS-1$
            return ToolResult.success().put("help", sb.toString()).toJson(); //$NON-NLS-1$
        }
        switch (topic.toLowerCase())
        {
            case "workflow": //$NON-NLS-1$
            case "dcsworkflow": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildDcsWorkflowHelp()).toJson(); //$NON-NLS-1$
            case "propertyvalues": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildPropertyValuesHelp()).toJson(); //$NON-NLS-1$
            case "examples": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildExamplesHelp()).toJson(); //$NON-NLS-1$
            case "errortags": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", buildErrorTagsHelp()).toJson(); //$NON-NLS-1$
            default:
                return ToolResult.error("Unknown topic: " + topic //$NON-NLS-1$
                    + ". Available: dcsWorkflow, propertyValues, examples, errorTags.") //$NON-NLS-1$
                    .toJson();
        }
    }

    private String buildDcsWorkflowHelp()
    {
        return "Step-by-step DCS schema construction.\n\n" //$NON-NLS-1$
            + "1. Create the parent object (Report / DataProcessor):\n" //$NON-NLS-1$
            + "   edit_metadata operation=createObject objectType=Report name=Sales\n\n" //$NON-NLS-1$
            + "2. Create the schema (creates the Template + DCS root):\n" //$NON-NLS-1$
            + "   dcs_workshop operation=create_schema objectName=Report.Sales\n\n" //$NON-NLS-1$
            + "3. Add a dataset with a real query (auto-validated before write):\n" //$NON-NLS-1$
            + "   dcs_workshop operation=add_dataset objectName=Report.Sales name=Main \\\n" //$NON-NLS-1$
            + "       queryText=\"VYBRAT T.* IZ Spravochnik.Tovary KAK T " //$NON-NLS-1$
            + "GDE T.Artikul PODOBNO &Artikul\"\n\n" //$NON-NLS-1$
            + "4. Add parameters and calculated fields:\n" //$NON-NLS-1$
            + "   dcs_workshop operation=add_parameter name=Period type=Date\n" //$NON-NLS-1$
            + "   dcs_workshop operation=add_calculated_field name=Total \\\n" //$NON-NLS-1$
            + "       expression=\"Summa(Tsena * Kolichestvo)\"\n\n" //$NON-NLS-1$
            + "5. Build settings (default groupings / filters / appearance):\n" //$NON-NLS-1$
            + "   dcs_workshop operation=add_grouping field=Tovar groupingType=Standard\n" //$NON-NLS-1$
            + "   dcs_workshop operation=add_filter field=Period comparisonType=Between\n" //$NON-NLS-1$
            + "   dcs_workshop operation=add_appearance conditionType=Greater \\\n" //$NON-NLS-1$
            + "       conditionValue=1000 appearance=\"BackColor=#FFFF00;Font=Arial,11,bold\"\n\n" //$NON-NLS-1$
            + "6. Wire the composer on a form (use edit_metadata):\n" //$NON-NLS-1$
            + "   edit_metadata operation=setupSettingsComposerOnForm \\\n" //$NON-NLS-1$
            + "       formFqn=Report.Sales.Forms.Form\n\n" //$NON-NLS-1$
            + "Direct save to .dcs disk file is automatic for extension projects.\n"; //$NON-NLS-1$
    }

    private String buildPropertyValuesHelp()
    {
        return "Allowed values for DCS operation parameters.\n\n" //$NON-NLS-1$
            + "**type** (Parameter.type, Field.valueType):\n" //$NON-NLS-1$
            + "- Date, Number, String, Boolean\n" //$NON-NLS-1$
            + "- Reference types: CatalogRef.X, DocumentRef.X, EnumRef.X,\n" //$NON-NLS-1$
            + "  ChartOfAccountsRef.X, ChartOfCalculationTypesRef.X, ExchangePlanRef.X,\n" //$NON-NLS-1$
            + "  TaskRef.X, BusinessProcessRef.X.\n\n" //$NON-NLS-1$
            + "**aggregateFunction** (add_total):\n" //$NON-NLS-1$
            + "- Sum, Count, CountDistinct, Min, Max, Avg, BeginDate, EndDate, Array.\n\n" //$NON-NLS-1$
            + "**comparisonType** (add_filter, add_appearance condition):\n" //$NON-NLS-1$
            + "- Equal, NotEqual, Greater, GreaterOrEqual, Less, LessOrEqual\n" //$NON-NLS-1$
            + "- InList, NotInList, InHierarchyList, NotInHierarchyList\n" //$NON-NLS-1$
            + "- InHierarchy, NotInHierarchy\n" //$NON-NLS-1$
            + "- Like, NotLike\n" //$NON-NLS-1$
            + "- BeginsWith, NotBeginsWith, Contains, NotContains\n" //$NON-NLS-1$
            + "- Filled, NotFilled\n" //$NON-NLS-1$
            + "- Between, NotBetween.\n\n" //$NON-NLS-1$
            + "**viewMode** (filter, parameter, selected field):\n" //$NON-NLS-1$
            + "- Auto, Normal, QuickAccess, Inaccessible.\n\n" //$NON-NLS-1$
            + "**groupingType** (add_grouping):\n" //$NON-NLS-1$
            + "- Standard (regular grouping)\n" //$NON-NLS-1$
            + "- DetailRecords (no aggregation, raw rows)\n" //$NON-NLS-1$
            + "- Items (hierarchical inside the same field).\n\n" //$NON-NLS-1$
            + "**direction / orderType** (add_order / add_settings_order):\n" //$NON-NLS-1$
            + "- Asc (ascending, default), Desc (descending).\n\n" //$NON-NLS-1$
            + "**appearance names** (add_appearance):\n" //$NON-NLS-1$
            + "- Font, TextColor, BackColor, Border, Format, MinimumWidth,\n" //$NON-NLS-1$
            + "  HorizontalAlign, VerticalAlign.\n" //$NON-NLS-1$
            + "Pass values as strings: 'Arial,12,bold' for Font, '#RRGGBB' for colors.\n"; //$NON-NLS-1$
    }

    private String buildExamplesHelp()
    {
        return "Common dcs_workshop call snippets (JSON-style for quick copy).\n\n" //$NON-NLS-1$
            + "Add a dataset with auto-validated query:\n" //$NON-NLS-1$
            + "{\n" //$NON-NLS-1$
            + "  \"operation\": \"add_dataset\",\n" //$NON-NLS-1$
            + "  \"projectName\": \"MyConfig\",\n" //$NON-NLS-1$
            + "  \"objectName\": \"Report.Sales\",\n" //$NON-NLS-1$
            + "  \"name\": \"Main\",\n" //$NON-NLS-1$
            + "  \"queryText\": \"VYBRAT * IZ Document.Realizatsiya\"\n" //$NON-NLS-1$
            + "}\n\n" //$NON-NLS-1$
            + "Update an existing parameter (only listed fields applied):\n" //$NON-NLS-1$
            + "{\n" //$NON-NLS-1$
            + "  \"operation\": \"set_parameter\",\n" //$NON-NLS-1$
            + "  \"objectName\": \"Report.Sales\",\n" //$NON-NLS-1$
            + "  \"name\": \"Period\",\n" //$NON-NLS-1$
            + "  \"title\": \"Period\",\n" //$NON-NLS-1$
            + "  \"use\": \"Always\"\n" //$NON-NLS-1$
            + "}\n\n" //$NON-NLS-1$
            + "Conditional appearance with font/color guard:\n" //$NON-NLS-1$
            + "{\n" //$NON-NLS-1$
            + "  \"operation\": \"add_appearance\",\n" //$NON-NLS-1$
            + "  \"objectName\": \"Report.Sales\",\n" //$NON-NLS-1$
            + "  \"conditionType\": \"Greater\",\n" //$NON-NLS-1$
            + "  \"conditionValue\": \"100000\",\n" //$NON-NLS-1$
            + "  \"appearance\": \"BackColor=#FFFF00;Font=Arial,11,bold\"\n" //$NON-NLS-1$
            + "}\n\n" //$NON-NLS-1$
            + "Default settings filter Between dates:\n" //$NON-NLS-1$
            + "{\n" //$NON-NLS-1$
            + "  \"operation\": \"add_filter\",\n" //$NON-NLS-1$
            + "  \"objectName\": \"Report.Sales\",\n" //$NON-NLS-1$
            + "  \"field\": \"Period\",\n" //$NON-NLS-1$
            + "  \"comparisonType\": \"Between\",\n" //$NON-NLS-1$
            + "  \"value\": \"BeginOfYear|EndOfYear\"\n" //$NON-NLS-1$
            + "}\n"; //$NON-NLS-1$
    }

    private String buildErrorTagsHelp()
    {
        return "Structured error tags surfaced into the JSON response next to `error`.\n\n" //$NON-NLS-1$
            + "- `notFound` { name, kind } - target child not found.\n" //$NON-NLS-1$
            + "- `alreadyExists` { name, kind } - target child already exists.\n" //$NON-NLS-1$
            + "- `queryValidation` { issues, statistics } - the query text has parse errors\n" //$NON-NLS-1$
            + "    (returned BEFORE the BM transaction opens).\n" //$NON-NLS-1$
            + "- `expressionValidation` { issues, statistics } - DCS expression has errors.\n" //$NON-NLS-1$
            + "- `fontColorGuard` { appearance, hint } - appearance was passed as JSON\n" //$NON-NLS-1$
            + "    instead of 'Name=Value;...' string. Hint shows the expected format.\n" //$NON-NLS-1$
            + "- `multiStatementUnsupported` { selectCount, hasUnion } - query has\n" //$NON-NLS-1$
            + "    multiple SELECT / UNION; heuristic query editing refuses to splice.\n" //$NON-NLS-1$
            + "- `heuristicTextSplice` (success flag, true) - the query edit was a\n" //$NON-NLS-1$
            + "    lexical token-splice (add_query_field / remove_query_field / add_query_condition / remove_query_condition).\n" //$NON-NLS-1$
            + "- `supportLock` - schema parent is on vendor support; use an extension.\n\n" //$NON-NLS-1$
            + "Pass `validate_query=false` or `validate_expression=false` to bypass\n" //$NON-NLS-1$
            + "pre-flight validation (use only for trusted templating).\n"; //$NON-NLS-1$
    }

    private String formatResult(BmDcsHelper.Result r, String op)
    {
        if (r.ok)
        {
            ToolResult result = ToolResult.success()
                .put("operation", op) //$NON-NLS-1$
                .put("schemaFqn", r.schemaFqn) //$NON-NLS-1$
                .put("message", r.message != null ? r.message : "ok"); //$NON-NLS-1$ //$NON-NLS-2$
            if (r.directSave != null && r.directSave.ok)
            {
                result.put("directSavePath", r.directSave.filePath) //$NON-NLS-1$
                    .put("directSaveBytes", r.directSave.bytesWritten) //$NON-NLS-1$
                    .put("directSaveMs", r.directSave.totalMs); //$NON-NLS-1$
            }
            // 1.43.x batch 4b: flag that a query edit was a lexical token-splice
            // (caller should treat the result as best-effort and re-inspect the
            // query if a complex layout was involved).
            if (isQuerySpliceOp(op))
            {
                result.put("heuristicTextSplice", Boolean.TRUE); //$NON-NLS-1$
            }
            applyTags(result, r.tags);
            return result.toJson();
        }
        ToolResult err = ToolResult
            .error(op + " failed: " + (r.error != null ? r.error : "unknown error")) //$NON-NLS-1$ //$NON-NLS-2$
            .put("operation", op) //$NON-NLS-1$
            .put("schemaFqn", r.schemaFqn); //$NON-NLS-1$
        applyTags(err, r.tags);
        return err.toJson();
    }

    private static void applyTags(ToolResult result, Map<String, Object> tags)
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

    private String suggest(String op)
    {
        String lower = op.toLowerCase();
        for (String known : OPS.keySet())
        {
            if (known.toLowerCase().contains(lower) || lower.contains(known.toLowerCase()))
            {
                return known;
            }
        }
        List<String> all = new java.util.ArrayList<>(OPS.keySet());
        Collections.sort(all);
        return all.isEmpty() ? "(none)" : all.get(0); //$NON-NLS-1$
    }

    private Map<String, String> buildOpsCatalog()
    {
        // Derived from the mutation registry (single source) plus the two ops that
        // are not schema mutations (create_schema manages its own tx; repair_schema
        // is deferred). Keeps the allowlist / help / suggest in lockstep with dispatch.
        Map<String, String> m = new LinkedHashMap<>();
        m.put("create_schema", "create_schema"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("repair_schema", "repair_schema"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String op : mutations.keySet())
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }

    /**
     * Builds the schema-mutation registry: op name -> handler applied on the DCS
     * schema inside the BM write transaction. Each handler is the exact call the
     * former applySchemaMutation switch made. Alias pairs (add_chart /
     * add_settings_chart, add_order / add_settings_order, select_field /
     * add_settings_selected_field, ...) are separate entries sharing one handler.
     */
    @SuppressWarnings("nls")
    /**
     * The operations this tool can run.
     *
     * @return their names, for the test that reconciles them against what each is classified as
     *         writing.
     */
    java.util.Set<String> operationNamesForTest()
    {
        return this.mutations.keySet();
    }

    /**
     * Runs one schema operation against a schema object, for a test that builds one itself.
     * <p>
     * The project is not passed: an operation that needs one - a parameter, whose type is resolved
     * against the configuration - cannot be driven this way, and the operations that shape the
     * schema itself do not need one.
     * </p>
     *
     * @param op the operation name.
     * @param params its arguments.
     * @param schema the schema to write into.
     * @return what the operation reports
     * @throws Exception if the operation refuses
     */
    Object applyToSchemaForTest(String op, Map<String, String> params, EObject schema)
        throws Exception
    {
        // nestedSchemaName is resolved here exactly as the public path resolves it. Taking the
        // schema straight would let a test pass while the addressing it depends on is broken.
        // What this cannot do is validate a query or an expression: that needs a project to
        // resolve names against, and there is none here. A test that writes a malformed query
        // through this therefore sees it written, where the public path would refuse it.
        return applySchemaMutation(op, params,
            schemaToWorkIn(schema, JsonUtils.extractStringArgument(params, "nestedSchemaName")), //$NON-NLS-1$
            null);
    }

    private Map<String, MutationHandler> buildMutationRegistry()
    {
        Map<String, MutationHandler> m = new LinkedHashMap<>();
        reg(m, "add_dataset", (p, s, pr) -> doAddDataSet(p, s));
        reg(m, "add_nested_schema", (p, s, pr) -> doAddNestedSchema(p, s));
        reg(m, "remove_nested_schema", (p, s, pr) -> doRemoveNestedSchema(p, s));
        reg(m, "add_union_item", (p, s, pr) -> doAddUnionItem(p, s));
        reg(m, "remove_union_item", (p, s, pr) -> doRemoveUnionItem(p, s));
        reg(m, "remove_dataset", (p, s, pr) -> doRemoveDataSet(p, s));
        reg(m, "add_data_source", (p, s, pr) -> doAddDataSource(p, s));
        reg(m, "remove_data_source", (p, s, pr) -> doRemoveDataSource(p, s));
        reg(m, "set_data_source_property", (p, s, pr) -> doSetDataSourceProperty(p, s));
        reg(m, "add_field", (p, s, pr) -> doAddField(p, s));
        reg(m, "add_parameter", (p, s, pr) -> doAddParameter(p, s, pr));
        reg(m, "set_parameter", (p, s, pr) -> doSetParameter(p, s, pr));
        reg(m, "remove_parameter", (p, s, pr) -> doRemoveParameter(p, s));
        reg(m, "move_parameter", (p, s, pr) -> doMoveParameter(p, s));
        reg(m, "add_calculated_field", (p, s, pr) -> doAddCalculatedField(p, s));
        reg(m, "add_total", (p, s, pr) -> doAddTotal(p, s));
        reg(m, "add_appearance", (p, s, pr) -> doAddAppearance(p, s));
        reg(m, "add_grouping", (p, s, pr) -> doAddGrouping(p, s));
        reg(m, "add_filter", (p, s, pr) -> doAddFilter(p, s));
        reg(m, "add_user_field", (p, s, pr) -> doAddUserField(p, s));
        reg(m, "remove_user_field", (p, s, pr) -> doRemoveUserField(p, s));
        reg(m, "set_user_field", (p, s, pr) -> doSetUserField(p, s));
        reg(m, "add_settings_table", (p, s, pr) -> doAddSettingsStructureItem(p, s, "Table"));
        reg(m, "add_chart", (p, s, pr) -> doAddSettingsStructureItem(p, s, "Chart"));
        reg(m, "add_settings_chart", (p, s, pr) -> doAddSettingsStructureItem(p, s, "Chart"));
        reg(m, "add_order", (p, s, pr) -> doAddSettingsOrder(p, s));
        reg(m, "add_settings_order", (p, s, pr) -> doAddSettingsOrder(p, s));
        reg(m, "select_field", (p, s, pr) -> doAddSettingsSelectedField(p, s));
        reg(m, "add_settings_selected_field", (p, s, pr) -> doAddSettingsSelectedField(p, s));
        reg(m, "deselect_field", (p, s, pr) -> doRemoveSettingsSelectedField(p, s));
        reg(m, "remove_settings_selected_field", (p, s, pr) -> doRemoveSettingsSelectedField(p, s));
        reg(m, "add_variant", (p, s, pr) -> doAddSettingsVariant(p, s));
        reg(m, "add_settings_variant", (p, s, pr) -> doAddSettingsVariant(p, s));
        reg(m, "set_param_value", (p, s, pr) -> doSetSettingsParameter(p, s));
        reg(m, "set_settings_parameter", (p, s, pr) -> doSetSettingsParameter(p, s));
        reg(m, "remove_settings_item", (p, s, pr) -> doRemoveSettingsItem(p, s));
        reg(m, "remove_appearance", (p, s, pr) -> doRemoveConditionalAppearance(p, s));
        reg(m, "remove_conditional_appearance", (p, s, pr) -> doRemoveConditionalAppearance(p, s));
        reg(m, "set_field_appearance", (p, s, pr) -> doSetDataSetFieldAppearance(p, s));
        reg(m, "set_data_set_field_appearance", (p, s, pr) -> doSetDataSetFieldAppearance(p, s));
        reg(m, "set_output_param", (p, s, pr) -> doSetOutputParameter(p, s));
        reg(m, "set_output_parameter", (p, s, pr) -> doSetOutputParameter(p, s));
        reg(m, "add_filter_group", (p, s, pr) -> doAddSettingsFilterGroup(p, s));
        reg(m, "add_settings_filter_group", (p, s, pr) -> doAddSettingsFilterGroup(p, s));
        reg(m, "add_dataset_link", (p, s, pr) -> doAddDataSetLink(p, s));
        reg(m, "set_dataset_link_property", (p, s, pr) -> doSetDataSetLinkProperty(p, s));
        reg(m, "remove_dataset_link", (p, s, pr) -> doRemoveDataSetLink(p, s));
        reg(m, "set_dataset_property", (p, s, pr) -> doSetDataSetProperty(p, s));
        reg(m, "set_dataset_query", (p, s, pr) -> doSetDataSetQuery(p, s));
        reg(m, "remove_dataset_field", (p, s, pr) -> doRemoveDataSetField(p, s));
        reg(m, "set_calculated_field", (p, s, pr) -> doSetCalculatedField(p, s));
        reg(m, "remove_calculated_field", (p, s, pr) -> doRemoveCalculatedField(p, s));
        reg(m, "set_total_field", (p, s, pr) -> doSetTotalField(p, s));
        reg(m, "remove_total_field", (p, s, pr) -> doRemoveTotalField(p, s));
        reg(m, "clear_settings_selected_fields", (p, s, pr) -> doClearSettingsSelectedFields(p, s));
        reg(m, "remove_settings_filter", (p, s, pr) -> doRemoveSettingsFilter(p, s));
        reg(m, "remove_settings_order", (p, s, pr) -> doRemoveSettingsOrder(p, s));
        reg(m, "set_settings_item_user_mode", (p, s, pr) -> doSetSettingsItemUserMode(p, s));
        reg(m, "remove_settings_variant", (p, s, pr) -> doRemoveSettingsVariant(p, s));
        reg(m, "clone_settings_variant", (p, s, pr) -> doCloneSettingsVariant(p, s));
        reg(m, "rename_settings_variant", (p, s, pr) -> doRenameSettingsVariant(p, s));
        reg(m, "add_query_field", (p, s, pr) -> doAddQueryField(p, s, pr));
        reg(m, "remove_query_field", (p, s, pr) -> doRemoveQueryField(p, s, pr));
        reg(m, "add_query_condition", (p, s, pr) -> doAddQueryCondition(p, s, pr));
        reg(m, "remove_query_condition", (p, s, pr) -> doRemoveQueryCondition(p, s, pr));
        return Collections.unmodifiableMap(m);
    }

    private static void reg(Map<String, MutationHandler> m, String name, MutationHandler handler)
    {
        // Fail-fast on a duplicate key so the single source stays self-enforcing.
        if (m.containsKey(name))
        {
            throw new IllegalStateException("duplicate DCS mutation registration: " + name); //$NON-NLS-1$
        }
        m.put(name, handler);
    }
}
