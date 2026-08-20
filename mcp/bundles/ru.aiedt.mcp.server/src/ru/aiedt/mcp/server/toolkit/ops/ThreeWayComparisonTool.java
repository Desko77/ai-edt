/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ru.aiedt.mcp.server.support.BmComparisonHelper;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Reports what separates a reworked configuration from a new delivery, against the delivery they
 * both came from.
 * <p>
 * That is an update on support stated exactly: three sides, not two. Two-sided comparison answers
 * "what differs", which is the wrong question when a configuration has been reworked - almost
 * everything differs, and the part that matters is which side changed it. With the common ancestor
 * present the environment can tell a change made by the vendor from a change made here, and that is
 * the difference between a report and a decision.
 * </p>
 * <p>
 * <b>Reads only.</b> No merge is performed, offered or reachable from here. A merge writes into a
 * configuration and a wrong one is not undone by a button.
 * </p>
 */
public class ThreeWayComparisonTool
    implements IMcpTool
{
    @Override
    public String getName()
    {
        return "compare_three_way"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Compares an open project against a new delivery and, optionally, against the " //$NON-NLS-1$
            + "delivery both came from - the three sides of an update on support. Reports node " //$NON-NLS-1$
            + "counts, how many differ, how many exist on one side only, the metadata objects " //$NON-NLS-1$
            + "that moved by name, and the problems the environment raises. Decisions about " //$NON-NLS-1$
            + "individual objects can be recorded and written to a settings file that EDT reads " //$NON-NLS-1$
            + "back when a person runs the merge. Reads only, in the sense that matters: it " //$NON-NLS-1$
            + "never merges, and the only thing it can write is that settings file."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Open project that plays our side (MAIN)", true) //$NON-NLS-1$
            .stringProperty("otherPath", //$NON-NLS-1$
                "Directory holding the configuration to compare against (OTHER)", true) //$NON-NLS-1$
            .stringProperty("ancestorPath", //$NON-NLS-1$
                "Directory holding the common ancestor (COMMON_ANCESTOR). Omit for a two-sided " //$NON-NLS-1$
                    + "comparison.") //$NON-NLS-1$
            .stringProperty("decisions", //$NON-NLS-1$
                "What to do with individual objects, as a JSON array of {\"object\":\"...\"," //$NON-NLS-1$
                    + "\"rule\":\"...\"}. The object is named as this tool names it in changed; " //$NON-NLS-1$
                    + "the rule is one of GET_FROM_OTHER, DO_NOT_MERGE, MERGE_PRIORITIZING_MAIN, " //$NON-NLS-1$
                    + "MERGE_PRIORITIZING_OTHER, CUSTOM_MERGE, MERGE_USING_EXTERNAL_TOOL. " //$NON-NLS-1$
                    + "Recorded on the comparison; NOT applied - no merge is performed.") //$NON-NLS-1$
            .stringProperty("decisionsPath", //$NON-NLS-1$
                "Absolute path to write the recorded decisions to, in the format EDT reads back " //$NON-NLS-1$
                    + "when a person runs the merge. Without it the decisions die with the " //$NON-NLS-1$
                    + "comparison.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    /**
     * Reads the decisions a caller passed.
     * <p>
     * Malformed input is refused with a reason rather than silently treated as no decisions: a
     * caller who wrote decisions and got a comparison back without them would believe they had been
     * recorded.
     * </p>
     *
     * @param json the argument as written; may be <code>null</code>.
     * @return the decisions, empty when none were given
     * @throws IllegalArgumentException when the argument is there but unreadable
     */
    private static List<BmComparisonHelper.Decision> readDecisions(String json)
    {
        List<BmComparisonHelper.Decision> decisions = new ArrayList<>();
        if (json == null || json.trim().isEmpty())
        {
            return decisions;
        }
        try
        {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(json);
            if (!parsed.isJsonArray())
            {
                throw new IllegalArgumentException(
                    "decisions must be a JSON array of {object, rule}"); //$NON-NLS-1$
            }
            for (com.google.gson.JsonElement element : parsed.getAsJsonArray())
            {
                if (!element.isJsonObject())
                {
                    throw new IllegalArgumentException(
                        "every decision must be an object with object and rule"); //$NON-NLS-1$
                }
                com.google.gson.JsonObject entry = element.getAsJsonObject();
                com.google.gson.JsonElement object = entry.get("object"); //$NON-NLS-1$
                com.google.gson.JsonElement rule = entry.get("rule"); //$NON-NLS-1$
                if (object == null || rule == null)
                {
                    throw new IllegalArgumentException(
                        "every decision needs both object and rule: " + entry); //$NON-NLS-1$
                }
                decisions.add(new BmComparisonHelper.Decision(object.getAsString(),
                    rule.getAsString()));
            }
            return decisions;
        }
        catch (com.google.gson.JsonParseException | IllegalStateException malformed)
        {
            throw new IllegalArgumentException("decisions is not readable JSON: " //$NON-NLS-1$
                + malformed.getMessage());
        }
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String otherPath = JsonUtils.extractStringArgument(params, "otherPath"); //$NON-NLS-1$
        String ancestorPath = JsonUtils.extractStringArgument(params, "ancestorPath"); //$NON-NLS-1$

        String decisionsJson = JsonUtils.extractStringArgument(params, "decisions"); //$NON-NLS-1$
        String decisionsPath = JsonUtils.extractStringArgument(params, "decisionsPath"); //$NON-NLS-1$
        List<BmComparisonHelper.Decision> decisions;
        try
        {
            decisions = readDecisions(decisionsJson);
        }
        catch (IllegalArgumentException malformed)
        {
            return ToolResult.error(malformed.getMessage()).toJson();
        }

        BmComparisonHelper.Outcome outcome = BmComparisonHelper.compare(projectName, otherPath,
            ancestorPath, decisions, decisionsPath);
        if (outcome.cannotTell != null)
        {
            return ToolResult.error(outcome.cannotTell)
                .put("threeWay", outcome.threeWay) //$NON-NLS-1$
                .put("status", outcome.status) //$NON-NLS-1$
                .toJson();
        }
        return ToolResult.success()
            .put("threeWay", outcome.threeWay) //$NON-NLS-1$
            .put("status", outcome.status) //$NON-NLS-1$
            .put("nodes", outcome.nodes) //$NON-NLS-1$
            .put("differing", outcome.differing) //$NON-NLS-1$
            .put("oneSided", outcome.oneSided) //$NON-NLS-1$
            // Named, not just counted: an update on support is decided object by object, and a
            // number tells nobody which ones to look at.
            .put("changed", outcome.changed) //$NON-NLS-1$
            .put("changedListComplete", outcome.changed.size() < 500) //$NON-NLS-1$
            .put("blockingProblems", outcome.blockingProblems) //$NON-NLS-1$
            .put("problems", outcome.problems) //$NON-NLS-1$
            // Present only when the list is empty for a reason other than there being no problems.
            .put("problemsNote", outcome.problemsNote) //$NON-NLS-1$
            .put("decided", outcome.decided) //$NON-NLS-1$
            .put("decisionsWrittenTo", outcome.decisionsWrittenTo) //$NON-NLS-1$
            .put("decisionsNote", outcome.decisionsNote) //$NON-NLS-1$
            .toJson();
    }
}
