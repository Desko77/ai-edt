/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * 1.43+: free-text metadata search across name + synonym + comment.
 * <p>
 * {@code get_metadata_objects} accepts a {@code nameFilter}, but it only
 * matches against {@code getName()}. Agents frequently know an object by
 * its synonym ("Контрагенты") rather than its English name
 * ("_DemoContractors"). This tool walks every top-level reference on
 * {@link Configuration} that yields a list of {@link MdObject}, picks
 * names that match the query case-insensitively, and ranks hits by where
 * the match was found (name &gt; synonym &gt; comment).
 * <p>
 * Returns a JSON list of {@code {fqn, type, name, synonym, comment,
 * matchField, score}}.
 */
public class SemanticMetadataSearchTool implements IMcpTool
{
    public static final String NAME = "semantic_metadata_search"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `insights` `operation=semantic_metadata_search`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Free-text search over metadata objects (name + synonym + comment). " //$NON-NLS-1$
            + "Use when you know an object by its display synonym (e.g. 'Контрагенты') " //$NON-NLS-1$
            + "rather than its English name. Walks every Configuration collection that " //$NON-NLS-1$
            + "exposes MdObject children and ranks hits by match location " //$NON-NLS-1$
            + "(name > synonym > comment). Russian terms supported."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Required.", true) //$NON-NLS-1$
            .stringProperty("query", //$NON-NLS-1$
                "Free-text query. Matched as case-insensitive substring against name, " //$NON-NLS-1$
                    + "synonym and comment of every MdObject in the configuration. Required.", true) //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Optional filter by type (English singular: Catalog / Document / " //$NON-NLS-1$
                    + "InformationRegister / ... or Russian equivalent).") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum results. Default 50.") //$NON-NLS-1$
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
        String query = JsonUtils.extractStringArgument(params, "query"); //$NON-NLS-1$
        String metadataType = JsonUtils.extractStringArgument(params, "metadataType"); //$NON-NLS-1$
        Integer limit = JsonUtils.extractIntegerArgument(params, "limit"); //$NON-NLS-1$
        int max = (limit != null && limit > 0) ? limit : 50;
        if (projectName == null || projectName.isEmpty() || query == null || query.isEmpty())
        {
            return ToolResult.error("projectName and query are required.").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        IConfigurationProvider cp = Activator.getDefault().getConfigurationProvider();
        Configuration cfg = cp != null ? cp.getConfiguration(project) : null;
        if (cfg == null)
        {
            return ToolResult.error("Configuration not loaded for project: " + projectName).toJson(); //$NON-NLS-1$
        }
        String englishType = null;
        if (metadataType != null && !metadataType.isEmpty())
        {
            englishType = MetadataTypeCatalog.toEnglishSingular(metadataType);
            if (englishType == null)
            {
                return ToolResult.error("Unknown metadataType: " + metadataType).toJson(); //$NON-NLS-1$
            }
        }
        String needle = query.toLowerCase();
        List<Map<String, Object>> hits = new ArrayList<>();
        // EMF references on Configuration include all the catalogs / documents / etc collections.
        for (EReference ref : cfg.eClass().getEAllReferences())
        {
            if (hits.size() >= max)
            {
                break;
            }
            Object value = cfg.eGet(ref);
            if (!(value instanceof List))
            {
                continue;
            }
            List<?> list = (List<?>) value;
            if (list.isEmpty())
            {
                continue;
            }
            for (Object item : list)
            {
                if (hits.size() >= max)
                {
                    break;
                }
                if (!(item instanceof MdObject))
                {
                    continue;
                }
                MdObject obj = (MdObject) item;
                String type = obj.eClass().getName();
                if (englishType != null && !englishType.equalsIgnoreCase(type))
                {
                    continue;
                }
                String name = obj.getName();
                String synonym = extractSynonym(obj);
                String comment = invokeString(obj, "getComment"); //$NON-NLS-1$
                String matchField = null;
                int score = 0;
                if (name != null && name.toLowerCase().contains(needle))
                {
                    matchField = "name"; //$NON-NLS-1$
                    score = 100;
                    if (name.equalsIgnoreCase(query))
                    {
                        score = 200;
                    }
                }
                else if (synonym != null && synonym.toLowerCase().contains(needle))
                {
                    matchField = "synonym"; //$NON-NLS-1$
                    score = 50;
                }
                else if (comment != null && comment.toLowerCase().contains(needle))
                {
                    matchField = "comment"; //$NON-NLS-1$
                    score = 20;
                }
                if (matchField == null)
                {
                    continue;
                }
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("fqn", type + "." + name); //$NON-NLS-1$ //$NON-NLS-2$
                hit.put("type", type); //$NON-NLS-1$
                hit.put("name", name); //$NON-NLS-1$
                if (synonym != null)
                {
                    hit.put("synonym", synonym); //$NON-NLS-1$
                }
                if (comment != null && !comment.isEmpty())
                {
                    hit.put("comment", comment); //$NON-NLS-1$
                }
                hit.put("matchField", matchField); //$NON-NLS-1$
                hit.put("score", score); //$NON-NLS-1$
                hits.add(hit);
            }
        }
        // Sort by score desc, then name asc for deterministic output
        Collections.sort(hits, (a, b) -> {
            int sa = ((Integer) a.get("score")).intValue(); //$NON-NLS-1$
            int sb = ((Integer) b.get("score")).intValue(); //$NON-NLS-1$
            if (sa != sb)
            {
                return Integer.compare(sb, sa);
            }
            String na = (String) a.get("name"); //$NON-NLS-1$
            String nb = (String) b.get("name"); //$NON-NLS-1$
            return na.compareToIgnoreCase(nb);
        });

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query); //$NON-NLS-1$
        body.put("matchCount", hits.size()); //$NON-NLS-1$
        body.put("truncated", hits.size() >= max); //$NON-NLS-1$
        body.put("results", hits); //$NON-NLS-1$
        return ToolResult.success().put("semanticMetadataSearch", body).toJson(); //$NON-NLS-1$
    }

    private static String extractSynonym(MdObject obj)
    {
        try
        {
            Object syn = obj.getClass().getMethod("getSynonym").invoke(obj); //$NON-NLS-1$
            return ru.aiedt.mcp.server.support.LocalizedStringUtils.text(syn);
        }
        catch (Exception ignored)
        {
            // optional
        }
        return null;
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
}
