/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.support.BmDcsHelper;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * 1.43+: composite "everything you need to know about this object" snapshot.
 * Aggregates basic metadata properties (name, synonym, comment), attribute /
 * tabular section / form / command / template counts, references count
 * (delegated to {@code find_references} with {@code skipBsl=true} for speed)
 * and validation error count (delegated to {@code get_project_errors}).
 * <p>
 * One call instead of 4-5 separate tool calls. Useful as a first probe
 * before deeper investigation.
 */
public class ObjectSummaryTool implements IMcpTool
{
    public static final String NAME = "object_summary"; //$NON-NLS-1$

    private static final Pattern TOTAL_PATTERN = Pattern.compile(
        "\\*\\*Total references found:\\*\\*\\s*(\\d+)"); //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `insights` `operation=object_summary`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "One-call snapshot of a metadata object: basic properties, child counts " //$NON-NLS-1$
            + "(attributes / tabular sections / forms / commands / templates), references " //$NON-NLS-1$
            + "count and validation errors count. Useful as a first probe before deeper " //$NON-NLS-1$
            + "investigation. Combines get_metadata_details + find_references + get_project_errors " //$NON-NLS-1$
            + "into a single response."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required.", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the object (e.g. 'Catalog.Products', 'Document.SalesOrder'). " //$NON-NLS-1$
                    + "Russian type names supported.", true) //$NON-NLS-1$
            .booleanProperty("includeReferences", //$NON-NLS-1$
                "Run find_references (skipBsl=true) to count metadata back-references. Default: true.") //$NON-NLS-1$
            .booleanProperty("includeErrors", //$NON-NLS-1$
                "Run get_project_errors to count validation problems. Default: true.") //$NON-NLS-1$
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
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty() || objectFqn == null || objectFqn.isEmpty())
        {
            return ToolResult.error("projectName and objectFqn are required.").toJson(); //$NON-NLS-1$
        }
        objectFqn = MetadataTypeCatalog.normalizeFqn(objectFqn);
        boolean includeRefs = JsonUtils.extractBooleanArgument(params, "includeReferences", true); //$NON-NLS-1$
        boolean includeErrors = JsonUtils.extractBooleanArgument(params, "includeErrors", true); //$NON-NLS-1$

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        IConfigurationProvider cp = Activator.getDefault().getConfigurationProvider();
        Configuration cfg = cp != null ? cp.getConfiguration(project) : null;
        if (cfg == null)
        {
            return ToolResult.error("Configuration not loaded for: " + projectName).toJson(); //$NON-NLS-1$
        }
        String[] parts = objectFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length != 2 || parts[1].isEmpty())
        {
            return ToolResult.error("Expected two-segment FQN like 'Catalog.Products'.").toJson(); //$NON-NLS-1$
        }
        MdObject obj = MetadataTypeCatalog.findObject(cfg, parts[0], parts[1]);
        if (obj == null)
        {
            return ToolResult.error("No such object: " + objectFqn).toJson(); //$NON-NLS-1$
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("objectFqn", objectFqn); //$NON-NLS-1$
        body.put("projectName", projectName); //$NON-NLS-1$
        body.put("type", parts[0]); //$NON-NLS-1$
        body.put("name", invokeString(obj, "getName")); //$NON-NLS-1$ //$NON-NLS-2$
        body.put("eClass", obj.eClass().getName()); //$NON-NLS-1$
        String synonym = extractSynonym(obj);
        if (synonym != null)
        {
            body.put("synonym", synonym); //$NON-NLS-1$
        }
        String comment = invokeString(obj, "getComment"); //$NON-NLS-1$
        if (comment != null && !comment.isEmpty())
        {
            body.put("comment", comment); //$NON-NLS-1$
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        addCount(counts, obj, "getAttributes", "attributes"); //$NON-NLS-1$ //$NON-NLS-2$
        addCount(counts, obj, "getTabularSections", "tabularSections"); //$NON-NLS-1$ //$NON-NLS-2$
        addCount(counts, obj, "getForms", "forms"); //$NON-NLS-1$ //$NON-NLS-2$
        addCount(counts, obj, "getCommands", "commands"); //$NON-NLS-1$ //$NON-NLS-2$
        addCount(counts, obj, "getTemplates", "templates"); //$NON-NLS-1$ //$NON-NLS-2$
        addCount(counts, obj, "getDimensions", "dimensions"); //$NON-NLS-1$ //$NON-NLS-2$
        addCount(counts, obj, "getResources", "resources"); //$NON-NLS-1$ //$NON-NLS-2$
        addCount(counts, obj, "getUrlTemplates", "urlTemplates"); //$NON-NLS-1$ //$NON-NLS-2$
        addCount(counts, obj, "getOperations", "operations"); //$NON-NLS-1$ //$NON-NLS-2$
        body.put("counts", counts); //$NON-NLS-1$

        // H4: compact DCS schema overview for report-like objects, so a large
        // schema (datasets / settings / 45k-char query) does not flood the
        // snapshot. Only Report / DataProcessor host DCS templates; the helper
        // returns null when the owner has no schema, so the block self-skips.
        if ("Report".equals(parts[0]) || "DataProcessor".equals(parts[0])) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Map<String, Object> dcs = BmDcsHelper.summarizeSchema(project, objectFqn, null);
            if (dcs != null)
            {
                body.put("dcsSchema", dcs); //$NON-NLS-1$
            }
        }

        if (includeRefs)
        {
            // delegateReferenceCount returns -1 when the count is unavailable
            // (delegate not registered / threw); surface that as "unknown" so
            // the agent does not read -1 as a real reference count.
            int refs = delegateReferenceCount(projectName, objectFqn);
            body.put("referenceCount", refs >= 0 ? (Object) refs : "unknown"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (includeErrors)
        {
            int err = delegateErrorCount(projectName, objectFqn);
            body.put("errorCount", err >= 0 ? (Object) err : "unknown"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ToolResult.success().put("objectSummary", body).toJson(); //$NON-NLS-1$
    }

    private static void addCount(Map<String, Object> out, MdObject obj, String getter, String key)
    {
        try
        {
            Method m = obj.getClass().getMethod(getter);
            Object v = m.invoke(obj);
            if (v instanceof List)
            {
                out.put(key, ((List<?>) v).size());
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // not all object types expose every collection
        }
        catch (Exception e)
        {
            Activator.logWarning(getter + " failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static String invokeString(Object obj, String getter)
    {
        try
        {
            Object v = obj.getClass().getMethod(getter).invoke(obj);
            return v != null ? v.toString() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Extracts a usable synonym string from {@code MdObject.getSynonym()}.
     * The synonym is an {@code EMap<String,String>} (language code -> value);
     * we prefer the {@code ru} entry, then any non-empty entry.
     */
    private static String extractSynonym(EObject obj)
    {
        try
        {
            Object syn = obj.getClass().getMethod("getSynonym").invoke(obj); //$NON-NLS-1$
            return ru.aiedt.mcp.server.support.LocalizedStringUtils.text(syn);
        }
        catch (Exception ignored)
        {
            // synonym is optional - silent.
        }
        return null;
    }

    private static int delegateReferenceCount(String projectName, String objectFqn)
    {
        IMcpTool findRefs = McpToolCatalog.getInstance().getTool("find_references"); //$NON-NLS-1$
        if (findRefs == null)
        {
            return -1;
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("projectName", projectName); //$NON-NLS-1$
        p.put("objectFqn", objectFqn); //$NON-NLS-1$
        p.put("skipBsl", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        p.put("limit", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            String md = findRefs.execute(p);
            if (md == null)
            {
                return -1;
            }
            Matcher m = TOTAL_PATTERN.matcher(md);
            if (m.find())
            {
                return Integer.parseInt(m.group(1));
            }
        }
        catch (Exception ignored)
        {
            // network/cache error - return -1 so caller knows the count is unknown
        }
        return -1;
    }

    private static int delegateErrorCount(String projectName, String objectFqn)
    {
        IMcpTool errs = McpToolCatalog.getInstance().getTool("get_project_errors"); //$NON-NLS-1$
        if (errs == null)
        {
            return -1;
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("projectName", projectName); //$NON-NLS-1$
        p.put("objects", "[\"" + objectFqn + "\"]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        p.put("limit", "1000"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            String md = errs.execute(p);
            if (md == null)
            {
                return -1;
            }
            // get_project_errors emits "Found: N" or "No Errors Found"
            if (md.contains("No Errors Found")) //$NON-NLS-1$
            {
                return 0;
            }
            Matcher m = Pattern.compile("\\*\\*Found:\\*\\*\\s*(\\d+)").matcher(md); //$NON-NLS-1$
            if (m.find())
            {
                return Integer.parseInt(m.group(1));
            }
            // Fallback: count table rows starting with "|" (after header)
            int count = 0;
            for (String line : md.split("\\n")) //$NON-NLS-1$
            {
                String trim = line.trim();
                if (trim.startsWith("|") && !trim.startsWith("|--") //$NON-NLS-1$ //$NON-NLS-2$
                    && !trim.startsWith("| Description")) //$NON-NLS-1$
                {
                    count++;
                }
            }
            // Subtract 1 row for header pattern mismatch tolerance
            return count > 0 ? count - 1 : 0;
        }
        catch (Exception ignored)
        {
            return -1;
        }
    }

    @SuppressWarnings("unused")
    private static List<String> asList(Object listLike)
    {
        if (listLike instanceof List)
        {
            List<String> names = new ArrayList<>();
            for (Object item : (List<?>) listLike)
            {
                String n = invokeString(item, "getName"); //$NON-NLS-1$
                if (n != null)
                {
                    names.add(n);
                }
            }
            return names;
        }
        return null;
    }
}
