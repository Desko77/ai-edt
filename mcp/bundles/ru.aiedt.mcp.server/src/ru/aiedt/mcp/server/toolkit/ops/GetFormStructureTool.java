/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmFormHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tool to extract a managed-form structure as JSON.
 * <p>
 * Complements {@code get_form_screenshot} which returns a PNG. This tool walks
 * the form's BM model via reflection (reusing {@link BmFormHelper}) and
 * produces a tree of {@code {name, type, title, items, properties}} that AI
 * agents can navigate without parsing raw XML.
 * <p>
 * Supports pagination for large forms:
 * <ul>
 *   <li>{@code depth} - maximum recursion depth (0 = unlimited)</li>
 *   <li>{@code subtree} - element name to start the walk from</li>
 *   <li>{@code maxElements} - hard cap on total emitted nodes</li>
 * </ul>
 */
public class GetFormStructureTool implements IMcpTool
{
    public static final String NAME = "get_form_structure"; //$NON-NLS-1$

    private static final int DEFAULT_DEPTH = 5;
    private static final int DEFAULT_MAX_ELEMENTS = 500;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Extract a managed form structure as a JSON tree. " //$NON-NLS-1$
            + "Walks Form.getItems() recursively and emits per-element " //$NON-NLS-1$
            + "{name, type, title, items, properties} including dataPath / commandName / " //$NON-NLS-1$
            + "kind / visible / enabled when available. Use depth/subtree/maxElements " //$NON-NLS-1$
            + "for large forms. Pair with get_form_screenshot for visual rendering."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the EDT project to work in", true) //$NON-NLS-1$
            .stringProperty("formPath", //$NON-NLS-1$
                "Metadata FQN of the form. " //$NON-NLS-1$
                    + "Examples: 'Catalog.Products.Forms.ItemForm', " //$NON-NLS-1$
                    + "'Document.SalesOrder.Forms.DocumentForm', 'CommonForm.MyForm'.", true) //$NON-NLS-1$
            .integerProperty("depth", //$NON-NLS-1$
                "Maximum recursion depth (0 = unlimited). Default: 5.") //$NON-NLS-1$
            .stringProperty("subtree", //$NON-NLS-1$
                "Name of a child element to start the walk from. " //$NON-NLS-1$
                    + "Returns the subtree rooted at the first match (depth-first). " //$NON-NLS-1$
                    + "Useful to drill into a single Group/Page on a large form.") //$NON-NLS-1$
            .integerProperty("maxElements", //$NON-NLS-1$
                "Hard cap on total emitted nodes. Default: 500. " //$NON-NLS-1$
                    + "When the cap is reached, the walk stops and 'truncated': true is set.") //$NON-NLS-1$
            .booleanProperty("includeCommandInterface", //$NON-NLS-1$
                "Include the form's command interface block " //$NON-NLS-1$
                    + "(navigationPanel + commandBar references to commands). Default: false.") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formPath = JsonUtils.extractStringArgument(params, "formPath"); //$NON-NLS-1$
        int depth = JsonUtils.extractIntArgument(params, "depth", DEFAULT_DEPTH); //$NON-NLS-1$
        String subtree = JsonUtils.extractStringArgument(params, "subtree"); //$NON-NLS-1$
        int maxElements = JsonUtils.extractIntArgument(params, "maxElements", DEFAULT_MAX_ELEMENTS); //$NON-NLS-1$
        boolean includeCommandInterface = JsonUtils.extractBooleanArgument(params, //$NON-NLS-1$
            "includeCommandInterface", false); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (formPath == null || formPath.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("formPath", //$NON-NLS-1$
                "Catalog.Products.Forms.ItemForm")).toJson(); //$NON-NLS-1$
        }

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        BmFormHelper helper = new BmFormHelper();
        if (!helper.init())
        {
            return ToolResult.error("BmFormHelper init failed - incompatible EDT version") //$NON-NLS-1$
                .toJson();
        }

        // Form FQN in BM uses the trailing ".Form" segment that comes from the
        // Form.form file name. Translate "Type.Object.Forms.Name" -> the
        // BM-canonical form FQN as documented in BmFormHelper.
        String fqn = toBmFormFqn(formPath);

        int finalDepth = depth;
        int finalMax = Math.max(1, maxElements);
        String finalSubtree = subtree;

        AtomicInteger counter = new AtomicInteger(0);
        StringBuilder errorRef = new StringBuilder();
        JsonObject[] resultRoot = new JsonObject[1];
        JsonObject[] commandInterfaceObj = new JsonObject[1];
        // M1: dynamic-list data attributes (query + main table) - read from
        // Form.getAttributes(), not the UI items tree.
        final com.google.gson.JsonArray[] dynamicListsRef = new com.google.gson.JsonArray[1];
        final boolean includeCi = includeCommandInterface;

        String operationError = helper.executeFormOperation(project, fqn, (transaction, form) -> {
            try
            {
                Object root = form;
                if (finalSubtree != null && !finalSubtree.isEmpty())
                {
                    Object found = findItemByName(form, finalSubtree);
                    if (found == null)
                    {
                        errorRef.append("Subtree element not found by name: " + finalSubtree); //$NON-NLS-1$
                        return null;
                    }
                    root = found;
                }
                resultRoot[0] = walk(root, 0, finalDepth, finalMax, counter);
                if (includeCi)
                {
                    commandInterfaceObj[0] = collectCommandInterface(form);
                }
                dynamicListsRef[0] = collectDynamicLists(form);
            }
            catch (Throwable t)
            {
                errorRef.append("Walk failed: ").append(t.getClass().getSimpleName()) //$NON-NLS-1$
                    .append(": ").append(t.getMessage()); //$NON-NLS-1$
            }
            return null;
        });

        if (errorRef.length() > 0)
        {
            return ToolResult.error(errorRef.toString()).toJson();
        }
        // Guard on the "Error:" prefix like every other executeFormOperation
        // caller: a non-error, non-null return is a success message (Row 42: it
        // may be a plain disk-flush note appended by the helper). This is a
        // read-only structure walk - a spurious pending-flush note must NOT be
        // reported as a failure that discards the already-built structure.
        if (operationError != null && operationError.startsWith("Error:")) //$NON-NLS-1$
        {
            return ToolResult.error(operationError.substring(6).trim()).toJson();
        }
        if (resultRoot[0] == null)
        {
            return ToolResult.error("No structure produced").toJson(); //$NON-NLS-1$
        }

        JsonObject envelope = new JsonObject();
        envelope.addProperty("formPath", formPath); //$NON-NLS-1$
        envelope.addProperty("formFqn", fqn); //$NON-NLS-1$
        envelope.addProperty("emitted", counter.get()); //$NON-NLS-1$
        envelope.addProperty("limit", finalMax); //$NON-NLS-1$
        envelope.addProperty("depth", finalDepth); //$NON-NLS-1$
        if (counter.get() >= finalMax)
        {
            envelope.addProperty("truncated", true); //$NON-NLS-1$
        }
        envelope.add("root", resultRoot[0]); //$NON-NLS-1$
        if (commandInterfaceObj[0] != null)
        {
            envelope.add("commandInterface", commandInterfaceObj[0]); //$NON-NLS-1$
        }
        if (dynamicListsRef[0] != null && dynamicListsRef[0].size() > 0)
        {
            envelope.add("dynamicLists", dynamicListsRef[0]); //$NON-NLS-1$
        }
        return envelope.toString();
    }

    /**
     * 1.42 (RSV 4.2 parity): emits the form's command interface block as JSON
     * with two arrays: {@code navigationPanel} and {@code commandBar}. Each
     * entry has {@code commandFqn}, {@code group}, {@code visible},
     * {@code index}, and {@code type} when reflection can resolve them.
     *
     * <p>Returns {@code null} when the form has no command interface or the
     * EDT runtime does not expose {@code Form.getCommandInterface()}.
     */
    private static JsonObject collectCommandInterface(Object form)
    {
        Object ci;
        try
        {
            ci = form.getClass().getMethod("getCommandInterface").invoke(form); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return null;
        }
        if (ci == null)
        {
            return null;
        }
        JsonObject result = new JsonObject();
        result.add("navigationPanel", collectPanelItems(ci, "getNavigationPanel")); //$NON-NLS-1$ //$NON-NLS-2$
        result.add("commandBar", collectPanelItems(ci, "getCommandBar")); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    /**
     * M1: lists the form's DynamicList data attributes (from
     * {@code Form.getAttributes()}, not the UI items tree) with their main table
     * and query, so an agent can read the current query before modifying it.
     * A very long query is truncated (with {@code queryLength} / {@code queryTruncated})
     * to keep the response bounded. Each list also carries its composition settings under
     * {@code settings} - order, filter, grouping and appearance, named the way the operations that
     * write them name their arguments. Returns an empty array when the form has no
     * dynamic lists or reflection is unavailable.
     */
    private static com.google.gson.JsonArray collectDynamicLists(Object form)
    {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        try
        {
            Object attrs = form.getClass().getMethod("getAttributes").invoke(form); //$NON-NLS-1$
            if (!(attrs instanceof Iterable))
            {
                return arr;
            }
            for (Object attr : (Iterable<?>) attrs)
            {
                Object extInfo;
                try
                {
                    extInfo = attr.getClass().getMethod("getExtInfo").invoke(attr); //$NON-NLS-1$
                }
                catch (Exception noExt)
                {
                    continue;
                }
                if (extInfo == null
                    || !extInfo.getClass().getSimpleName().contains("DynamicList")) //$NON-NLS-1$
                {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("name", probeStringGetter(attr, new String[] { "getName" })); //$NON-NLS-1$ //$NON-NLS-2$
                Object mainTable = null;
                try
                {
                    mainTable = extInfo.getClass().getMethod("getMainTable").invoke(extInfo); //$NON-NLS-1$
                }
                catch (Exception ignored)
                {
                    // no main table getter
                }
                if (mainTable != null)
                {
                    o.addProperty("mainTable", //$NON-NLS-1$
                        probeStringGetter(mainTable, new String[] { "getName", "getNameRu" })); //$NON-NLS-1$ //$NON-NLS-2$
                }
                Boolean custom = probeBooleanGetter(extInfo, "isCustomQuery"); //$NON-NLS-1$
                if (custom != null)
                {
                    o.addProperty("customQuery", custom); //$NON-NLS-1$
                }
                String q = probeStringGetter(extInfo, new String[] { "getQueryText" }); //$NON-NLS-1$
                if (q != null && !q.isEmpty())
                {
                    o.addProperty("queryLength", q.length()); //$NON-NLS-1$
                    if (q.length() > 4000)
                    {
                        o.addProperty("queryText", q.substring(0, 4000)); //$NON-NLS-1$
                        o.addProperty("queryTruncated", true); //$NON-NLS-1$
                    }
                    else
                    {
                        o.addProperty("queryText", q); //$NON-NLS-1$
                    }
                }
                o.add("settings", readListSettings(extInfo)); //$NON-NLS-1$
                arr.add(o);
            }
        }
        catch (Exception ignored)
        {
            // form exposes no getAttributes / reflection failure - return what we have
        }
        return arr;
    }

    /**
     * The list's composition settings - its order, filter, grouping and appearance.
     * <p>
     * Reached through the model feature rather than a transaction: the settings are a top object of
     * their own, and this is a read. When they are there and resolved they are read; when they are
     * a proxy the answer says so, because reporting an unread setting as an empty one would say the
     * list is unsorted and unfiltered when nobody has looked.
     * </p>
     *
     * @param extInfo the attribute's dynamic-list ext info.
     * @return what the settings hold, or why they were not read
     */
    private static JsonObject readListSettings(Object extInfo)
    {
        try
        {
            if (!(extInfo instanceof org.eclipse.emf.ecore.EObject))
            {
                return ru.aiedt.mcp.server.support.DynamicListSettingsReader.read(null,
                    "the list's ext info is not a model object"); //$NON-NLS-1$
            }
            org.eclipse.emf.ecore.EObject held = (org.eclipse.emf.ecore.EObject)extInfo;
            org.eclipse.emf.ecore.EStructuralFeature feature =
                held.eClass().getEStructuralFeature("listSettings"); //$NON-NLS-1$
            if (feature == null)
            {
                // A model without the feature is not a list without settings, and saying the
                // second about the first sends the reader looking at the list.
                return ru.aiedt.mcp.server.support.DynamicListSettingsReader.read(null,
                    "this model has no listSettings feature"); //$NON-NLS-1$
            }
            return ru.aiedt.mcp.server.support.DynamicListSettingsReader.read(held.eGet(feature));
        }
        catch (Exception reading)
        {
            // Contained here rather than left to the caller's catch: resolving a settings proxy
            // can throw, and the loop that calls this would then drop every list after this one
            // as well as this one. The rest of the form is still worth answering with.
            JsonObject failed = new JsonObject();
            failed.addProperty("settingsRead", false); //$NON-NLS-1$
            failed.addProperty("why", "reading the settings failed: " //$NON-NLS-1$
                + ru.aiedt.mcp.server.support.TextSuggest.safeMessage(reading));
            return failed;
        }
    }

    private static com.google.gson.JsonArray collectPanelItems(Object ci, String panelGetter)
    {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        try
        {
            Object panel = ci.getClass().getMethod(panelGetter).invoke(ci);
            if (panel == null)
            {
                return arr;
            }
            Object items = panel.getClass().getMethod("getItems").invoke(panel); //$NON-NLS-1$
            if (!(items instanceof Iterable))
            {
                return arr;
            }
            int idx = 0;
            for (Object item : (Iterable<?>) items)
            {
                JsonObject obj = new JsonObject();
                obj.addProperty("index", idx++); //$NON-NLS-1$
                obj.addProperty("commandFqn", probeStringGetter(item, //$NON-NLS-1$
                    new String[] { "getCommandFqn", "getCommand" })); //$NON-NLS-1$ //$NON-NLS-2$
                obj.addProperty("group", probeStringGetter(item, //$NON-NLS-1$
                    new String[] { "getGroup" })); //$NON-NLS-1$
                obj.addProperty("type", probeStringGetter(item, //$NON-NLS-1$
                    new String[] { "getType" })); //$NON-NLS-1$
                Boolean visible = probeBooleanGetter(item, "isVisible"); //$NON-NLS-1$
                if (visible != null)
                {
                    obj.addProperty("visible", visible); //$NON-NLS-1$
                }
                arr.add(obj);
            }
        }
        catch (Exception ignored)
        {
            // Older EDT - leave the array partially filled.
        }
        return arr;
    }

    private static String probeStringGetter(Object item, String[] candidates)
    {
        for (String name : candidates)
        {
            try
            {
                Object v = item.getClass().getMethod(name).invoke(item);
                if (v != null)
                {
                    return v.toString();
                }
            }
            catch (Exception ignored)
            {
                // Try next.
            }
        }
        return null;
    }

    private static Boolean probeBooleanGetter(Object item, String getter)
    {
        try
        {
            Object v = item.getClass().getMethod(getter).invoke(item);
            return (v instanceof Boolean) ? (Boolean) v : null;
        }
        catch (Exception ignored)
        {
            // The receiver is an Object whose shape this helper does not control, so a
            // missing member is an answer, not a failure - the caller reads the null as
            // "this element has no such property".
            return null;
        }
    }

    /**
     * Translates the user-facing form path to the BM-canonical FQN.
     * <p>
     * "Catalog.Products.Forms.ItemForm" -> "Catalog.Products.Form.ItemForm.Form"
     * "CommonForm.MyForm" -> "CommonForm.MyForm.Form"
     */
    private String toBmFormFqn(String formPath)
    {
        // CommonForm.X -> CommonForm.X.Form
        if (formPath.startsWith("CommonForm.")) //$NON-NLS-1$
        {
            return formPath.endsWith(".Form") ? formPath : formPath + ".Form"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        // Type.Object.Forms.Name -> Type.Object.Form.Name.Form
        String normalized = formPath.replace(".Forms.", ".Form."); //$NON-NLS-1$ //$NON-NLS-2$
        return normalized.endsWith(".Form") ? normalized : normalized + ".Form"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Recursively builds a JSON tree node for the given form item.
     * Honors the depth and global element-count limits.
     */
    private JsonObject walk(Object item, int currentDepth, int maxDepth, int maxElements,
        AtomicInteger counter)
    {
        if (counter.get() >= maxElements || item == null)
        {
            return null;
        }
        counter.incrementAndGet();

        JsonObject node = new JsonObject();
        node.addProperty("type", classNameOf(item)); //$NON-NLS-1$

        String name = invokeStringNoArg(item, "getName"); //$NON-NLS-1$
        if (name != null && !name.isEmpty())
        {
            node.addProperty("name", name); //$NON-NLS-1$
        }

        String title = extractTitle(item);
        if (title != null && !title.isEmpty())
        {
            node.addProperty("title", title); //$NON-NLS-1$
        }

        Integer id = invokeIntNoArg(item, "getId"); //$NON-NLS-1$
        if (id != null)
        {
            node.addProperty("id", id.intValue()); //$NON-NLS-1$
        }

        JsonObject properties = collectProperties(item);
        if (properties != null && properties.size() > 0)
        {
            node.add("properties", properties); //$NON-NLS-1$
        }

        // Descend into FormItemContainer.getItems() when within depth budget.
        boolean canDescend = maxDepth == 0 || currentDepth < maxDepth;
        if (canDescend)
        {
            List<Object> children = readChildItems(item);
            if (!children.isEmpty())
            {
                JsonArray itemsArr = new JsonArray();
                for (Object child : children)
                {
                    if (counter.get() >= maxElements)
                    {
                        break;
                    }
                    JsonObject childNode = walk(child, currentDepth + 1, maxDepth,
                        maxElements, counter);
                    if (childNode != null)
                    {
                        itemsArr.add(childNode);
                    }
                }
                if (itemsArr.size() > 0)
                {
                    node.add("items", itemsArr); //$NON-NLS-1$
                }
            }
        }

        return node;
    }

    /**
     * Collects optional per-element properties via best-effort reflection.
     * Each lookup is wrapped in try/catch so that missing methods on a particular
     * EClass do not abort the walk.
     */
    private JsonObject collectProperties(Object item)
    {
        JsonObject props = new JsonObject();

        Boolean visible = invokeBooleanNoArg(item, "isVisible"); //$NON-NLS-1$
        if (visible != null)
        {
            props.addProperty("visible", visible.booleanValue()); //$NON-NLS-1$
        }

        Boolean enabled = invokeBooleanNoArg(item, "isEnabled"); //$NON-NLS-1$
        if (enabled != null)
        {
            props.addProperty("enabled", enabled.booleanValue()); //$NON-NLS-1$
        }

        // FormField, ContextMenu, ColumnGroup etc. - the user-facing dataPath
        String dataPath = extractDataPath(item);
        if (dataPath != null && !dataPath.isEmpty())
        {
            props.addProperty("dataPath", dataPath); //$NON-NLS-1$
        }

        // FormGroup type / kind
        String kind = invokeStringFromEnumNoArg(item, "getKind"); //$NON-NLS-1$
        if (kind != null && !kind.isEmpty())
        {
            props.addProperty("kind", kind); //$NON-NLS-1$
        }

        // Button standard or user command name
        String commandName = extractCommandName(item);
        if (commandName != null && !commandName.isEmpty())
        {
            props.addProperty("commandName", commandName); //$NON-NLS-1$
        }

        // ChildrenGroup / ChildrenAlign / Representation - direction & rendering hints
        String childrenGroup = invokeStringFromEnumNoArg(item, "getChildrenGroup"); //$NON-NLS-1$
        if (childrenGroup != null && !childrenGroup.isEmpty())
        {
            props.addProperty("childrenGroup", childrenGroup); //$NON-NLS-1$
        }

        String representation = invokeStringFromEnumNoArg(item, "getRepresentation"); //$NON-NLS-1$
        if (representation != null && !representation.isEmpty())
        {
            props.addProperty("representation", representation); //$NON-NLS-1$
        }

        return props.size() > 0 ? props : null;
    }

    private String extractTitle(Object item)
    {
        // Form / FormItem / Decoration use getTitle() returning a localized string holder
        try
        {
            Method m = item.getClass().getMethod("getTitle"); //$NON-NLS-1$
            Object v = m.invoke(item);
            // title is a LocalString (getContent() -> EMap<lang,text>); the
            // shared helper unwraps it and drops object-identity toString junk.
            return ru.aiedt.mcp.server.support.LocalizedStringUtils.text(v);
        }
        catch (NoSuchMethodException nsme)
        {
            return null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private String extractDataPath(Object item)
    {
        String[] candidates = { "getDataPath", "getDataPathString" }; //$NON-NLS-1$ //$NON-NLS-2$
        for (String name : candidates)
        {
            try
            {
                Method m = item.getClass().getMethod(name);
                Object v = m.invoke(item);
                if (v == null)
                {
                    continue;
                }
                if (v instanceof String)
                {
                    return (String) v;
                }
                String s = invokeStringNoArg(v, "getSegmentsAsString"); //$NON-NLS-1$
                if (s != null && !s.isEmpty())
                {
                    return s;
                }
                return v.toString();
            }
            catch (NoSuchMethodException nsme)
            {
                // try next candidate
            }
            catch (Exception ignored)
            {
                // ignore - dataPath is best-effort
            }
        }
        return null;
    }

    private String extractCommandName(Object item)
    {
        // Button.getCommandName / Button.getStandardCommand.getName / Button.getCommand.getName
        String name = invokeStringNoArg(item, "getCommandName"); //$NON-NLS-1$
        if (name != null && !name.isEmpty())
        {
            return name;
        }
        try
        {
            Method m = item.getClass().getMethod("getStandardCommand"); //$NON-NLS-1$
            Object cmd = m.invoke(item);
            if (cmd != null)
            {
                String n = invokeStringNoArg(cmd, "getName"); //$NON-NLS-1$
                if (n != null && !n.isEmpty())
                {
                    return n;
                }
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // not a button or different EDT version
        }
        catch (Exception ignored)
        {
            // best-effort
        }
        return null;
    }

    /**
     * Returns the children of a FormItemContainer or empty list otherwise.
     */
    @SuppressWarnings("unchecked")
    private List<Object> readChildItems(Object item)
    {
        List<Object> result = new ArrayList<>();
        try
        {
            Method m = item.getClass().getMethod("getItems"); //$NON-NLS-1$
            Object v = m.invoke(item);
            if (v instanceof List)
            {
                result.addAll((List<Object>) v);
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // not a container
        }
        catch (Exception ignored)
        {
            // best-effort
        }
        // L66: a table's AutoCommandBar holds buttons in a separate reference (not in
        // getItems()); include its items so get_form_structure shows the buttons a user
        // added to a table command panel - otherwise the form looks empty there even
        // though the buttons exist. This also makes the subtree resolver find them.
        collectAutoCommandBarItems(item, result);
        return result;
    }

    /**
     * Adds the items of a {@code CommandBarHolder.getAutoCommandBar()} (a table's auto
     * command bar) when the item is a command-bar holder, so the buttons a user placed
     * in a table command panel are visible / resolvable. Best-effort: anything that is
     * not a command-bar holder has no {@code getAutoCommandBar()} and is left as-is.
     */
    @SuppressWarnings("unchecked")
    private void collectAutoCommandBarItems(Object item, List<Object> result)
    {
        try
        {
            Method getBar = item.getClass().getMethod("getAutoCommandBar"); //$NON-NLS-1$
            Object bar = getBar.invoke(item);
            if (bar != null)
            {
                Method getItems = bar.getClass().getMethod("getItems"); //$NON-NLS-1$
                Object v = getItems.invoke(bar);
                if (v instanceof List)
                {
                    result.addAll((List<Object>) v);
                }
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // not a CommandBarHolder (most items)
        }
        catch (Exception ignored)
        {
            // best-effort
        }
    }

    /**
     * Depth-first search for a FormItem with matching getName().
     * Used to resolve the {@code subtree} parameter.
     */
    private Object findItemByName(Object root, String targetName)
    {
        if (root == null)
        {
            return null;
        }
        String name = invokeStringNoArg(root, "getName"); //$NON-NLS-1$
        if (targetName.equals(name))
        {
            return root;
        }
        for (Object child : readChildItems(root))
        {
            Object found = findItemByName(child, targetName);
            if (found != null)
            {
                return found;
            }
        }
        return null;
    }

    private String classNameOf(Object o)
    {
        if (o instanceof EObject)
        {
            return ((EObject) o).eClass().getName();
        }
        return o.getClass().getSimpleName();
    }

    private String invokeStringNoArg(Object target, String methodName)
    {
        try
        {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return v != null ? v.toString() : null;
        }
        catch (Exception ignored)
        {
            // The receiver is an Object whose shape this helper does not control, so a
            // missing member is an answer, not a failure - the caller reads the null as
            // "this element has no such property".
            return null;
        }
    }

    private String invokeStringFromEnumNoArg(Object target, String methodName)
    {
        try
        {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return v != null ? v.toString() : null;
        }
        catch (Exception ignored)
        {
            // The receiver is an Object whose shape this helper does not control, so a
            // missing member is an answer, not a failure - the caller reads the null as
            // "this element has no such property".
            return null;
        }
    }

    private Integer invokeIntNoArg(Object target, String methodName)
    {
        try
        {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof Number)
            {
                return Integer.valueOf(((Number) v).intValue());
            }
        }
        catch (Exception ignored)
        {
            // best-effort
        }
        return null;
    }

    private Boolean invokeBooleanNoArg(Object target, String methodName)
    {
        try
        {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof Boolean)
            {
                return (Boolean) v;
            }
        }
        catch (Exception ignored)
        {
            // best-effort
        }
        return null;
    }
}
