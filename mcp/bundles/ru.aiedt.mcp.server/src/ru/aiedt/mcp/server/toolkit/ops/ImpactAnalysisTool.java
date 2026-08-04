/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * 1.43+: composite tool that runs {@code find_references} with {@code deep=true}
 * and prepends a severity assessment plus a recommendation for AI agents.
 * <p>
 * Use this before destructive operations ({@code delete_metadata_object},
 * {@code rename_metadata_object}, {@code remove_object_attribute},
 * {@code remove_object}, {@code remove_tabular_section}) to size the blast
 * radius and decide whether the change is safe.
 * <p>
 * The tool delegates the actual search to {@code find_references} via the
 * shared {@link McpToolCatalog}, then parses the {@code **Total references
 * found:** N} marker to compute severity.
 */
public class ImpactAnalysisTool implements IMcpTool
{
    public static final String NAME = "impact_analysis"; //$NON-NLS-1$

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
        return "Back-compat alias of `insights` `operation=impact_analysis`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Blast radius analysis for a metadata object. Composes find_references " //$NON-NLS-1$
            + "with deep=true (covers Reference / Manager / Selection / Object / Cache / List " //$NON-NLS-1$
            + "derived types) and prepends a severity tier (LOW / MEDIUM / HIGH) plus an " //$NON-NLS-1$
            + "agent-friendly recommendation. Use this BEFORE destructive operations such as " //$NON-NLS-1$
            + "delete_metadata_object, rename_metadata_object, remove_object_attribute, " //$NON-NLS-1$
            + "remove_object, remove_tabular_section. The full find_references report is appended " //$NON-NLS-1$
            + "below the summary."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the EDT project to work in.", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the object to analyze (e.g. 'Catalog.Products', " //$NON-NLS-1$
                    + "'Document.SalesOrder.Attribute.Total'). Russian type names supported.", true) //$NON-NLS-1$
            .stringProperty("action", //$NON-NLS-1$
                "Planned action: 'delete' / 'rename' / 'modify'. Influences the recommendation text. Optional.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum references per category in the find_references delegate. Default: 100.") //$NON-NLS-1$
            .booleanProperty("skipBsl", //$NON-NLS-1$
                "Skip BSL code references (only metadata back-references). Default: false.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty() || objectFqn == null || objectFqn.isEmpty())
        {
            return "Error: projectName and objectFqn are required."; //$NON-NLS-1$
        }
        String action = JsonUtils.extractStringArgument(params, "action"); //$NON-NLS-1$

        IMcpTool findRefs = McpToolCatalog.getInstance().getTool("find_references"); //$NON-NLS-1$
        if (findRefs == null)
        {
            return "Error: find_references tool is not registered."; //$NON-NLS-1$
        }
        // Build delegate params. Always force deep=true to catch derived types
        // (CatalogRef, CatalogManager, CatalogSelection, etc.) - that is the
        // entire point of impact analysis.
        Map<String, String> delegateParams = new HashMap<>(params);
        delegateParams.put("deep", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        delegateParams.remove("action"); //$NON-NLS-1$
        String findRefsResult;
        try
        {
            findRefsResult = findRefs.execute(delegateParams);
        }
        catch (Exception e)
        {
            return "Error: find_references delegate failed: " //$NON-NLS-1$
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        if (findRefsResult == null)
        {
            findRefsResult = "(no output from find_references)"; //$NON-NLS-1$
        }

        int total = parseTotalReferences(findRefsResult);
        SeverityTier severity = computeSeverity(total);
        String recommendation = buildRecommendation(severity, action);

        StringBuilder sb = new StringBuilder();
        sb.append("# Impact Analysis: `").append(objectFqn).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (action != null && !action.isEmpty())
        {
            sb.append("**Planned action:** ").append(action).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("**Severity:** ").append(severity.label).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Total references (deep):** ").append(total).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Recommendation:** ").append(recommendation).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("---\n\n"); //$NON-NLS-1$
        sb.append("## Detailed references (from find_references deep=true)\n\n"); //$NON-NLS-1$
        sb.append(findRefsResult);
        return sb.toString();
    }

    private static int parseTotalReferences(String md)
    {
        if (md == null)
        {
            return 0;
        }
        Matcher m = TOTAL_PATTERN.matcher(md);
        if (m.find())
        {
            try
            {
                return Integer.parseInt(m.group(1));
            }
            catch (NumberFormatException ignored)
            {
                // fall through
            }
        }
        return 0;
    }

    private static SeverityTier computeSeverity(int total)
    {
        if (total == 0)
        {
            return SeverityTier.LOW;
        }
        if (total <= 10)
        {
            return SeverityTier.MEDIUM;
        }
        return SeverityTier.HIGH;
    }

    private static String buildRecommendation(SeverityTier severity, String action)
    {
        String act = action == null ? "" : action.toLowerCase(); //$NON-NLS-1$
        switch (severity)
        {
            case LOW:
                return "Safe to proceed. No external references detected by deep search."; //$NON-NLS-1$
            case MEDIUM:
                if ("delete".equals(act)) //$NON-NLS-1$
                {
                    return "Review listed references before delete. Consider " //$NON-NLS-1$
                        + "deprecation (rename to *Obsolete suffix and mark deleted in subsystem) " //$NON-NLS-1$
                        + "instead of immediate removal."; //$NON-NLS-1$
                }
                if ("rename".equals(act)) //$NON-NLS-1$
                {
                    return "Rename via rename_metadata_object (full refactoring) - " //$NON-NLS-1$
                        + "do not edit .mdo directly. References will be updated automatically."; //$NON-NLS-1$
                }
                return "Review listed references before proceeding. Verify each call site " //$NON-NLS-1$
                    + "still compiles with the planned change."; //$NON-NLS-1$
            case HIGH:
                if ("delete".equals(act)) //$NON-NLS-1$
                {
                    return "DANGEROUS. Significant blast radius - do not delete without a " //$NON-NLS-1$
                        + "migration plan. Walk through the reference list, refactor each call " //$NON-NLS-1$
                        + "site, then re-run impact_analysis. Consider feature-flagging the " //$NON-NLS-1$
                        + "removal."; //$NON-NLS-1$
                }
                if ("rename".equals(act)) //$NON-NLS-1$
                {
                    return "MUST use rename_metadata_object refactoring (not manual edit). " //$NON-NLS-1$
                        + "Inspect every call site afterwards: a high-reference rename is " //$NON-NLS-1$
                        + "prone to subtle leftover string references that the refactoring " //$NON-NLS-1$
                        + "engine misses."; //$NON-NLS-1$
                }
                return "DANGEROUS. Significant blast radius - the change touches many call " //$NON-NLS-1$
                    + "sites. Review every reference and consider splitting the change into a " //$NON-NLS-1$
                    + "compatibility shim first."; //$NON-NLS-1$
            default:
                return "Unknown severity."; //$NON-NLS-1$
        }
    }

    private enum SeverityTier
    {
        LOW("LOW (no references)"), //$NON-NLS-1$
        MEDIUM("MEDIUM (review needed)"), //$NON-NLS-1$
        HIGH("HIGH (significant blast radius)"); //$NON-NLS-1$

        final String label;

        SeverityTier(String label)
        {
            this.label = label;
        }
    }
}
