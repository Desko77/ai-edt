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
 * <b>Reading is the default; merging is possible and deliberate.</b> A merge happens only when the
 * caller names the intent, has supplied decisions to apply, and - past a problem the environment
 * itself called blocking - asks again in different words. A merge writes into a configuration and a
 * wrong one is not undone by a button, so nothing about it is a default, a flag, or a shorthand.
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
            + "back when a person runs the merge, and a file written earlier can be read back in " //$NON-NLS-1$
            + "through decisionsFrom. Reading is the default and changes nothing. Passing " //$NON-NLS-1$
            + "intent=MERGE applies the decisions to the project, which is IRREVERSIBLE and is " //$NON-NLS-1$
            + "refused when the environment raises a blocking problem or when no decisions were " //$NON-NLS-1$
            + "given; after a merge the touched objects are revalidated and the errors standing " //$NON-NLS-1$
            + "against them are reported."; //$NON-NLS-1$
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
                    + "Recorded on the comparison, and applied only when intent says to.") //$NON-NLS-1$
            .stringProperty("decisionsPath", //$NON-NLS-1$
                "Absolute path to write the recorded decisions to, in the format EDT reads back " //$NON-NLS-1$
                    + "when a person runs the merge. Must end in .zip. Without it the decisions " //$NON-NLS-1$
                    + "die with the comparison.") //$NON-NLS-1$
            .stringProperty("decisionsFrom", //$NON-NLS-1$
                "Absolute path to a settings file (.zip) written earlier - by this tool or by a " //$NON-NLS-1$
                    + "person in EDT - whose decisions and hand-made object correspondences are " //$NON-NLS-1$
                    + "applied to this comparison before anything else. This is how work decided " //$NON-NLS-1$
                    + "by eye comes back to be carried out.") //$NON-NLS-1$
            .stringProperty("intent", //$NON-NLS-1$
                "REPORT (default) reads and changes nothing. MERGE applies the decisions to the " //$NON-NLS-1$
                    + "project - IRREVERSIBLE. The environment validates first and stops before " //$NON-NLS-1$
                    + "writing when it raises a blocking problem; merged says what actually " //$NON-NLS-1$
                    + "happened, not what was asked for. MERGE_IGNORING_PROBLEMS proceeds past " //$NON-NLS-1$
                    + "those problems; it is a " //$NON-NLS-1$
                    + "separate value and not a flag, because overriding the environment's own " //$NON-NLS-1$
                    + "objection should not share a word with ordinary merging. A merge needs " //$NON-NLS-1$
                    + "decisions: without them there is nothing to apply.") //$NON-NLS-1$
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

    /**
     * Reads what the caller asked to happen.
     * <p>
     * An unrecognised value is refused rather than read as REPORT. Defaulting would be the safe
     * direction and still the wrong one: somebody who wrote MERGE and mistyped it would be told
     * their configuration is unchanged only by reading the answer closely, and would try again
     * with the same word.
     * </p>
     *
     * @param argument the value as written; may be <code>null</code>.
     * @return the intent, REPORT when nothing was asked for
     * @throws IllegalArgumentException when the value is not one of the three
     */
    private static BmComparisonHelper.Intent readIntent(String argument)
    {
        if (argument == null || argument.trim().isEmpty())
        {
            return BmComparisonHelper.Intent.REPORT;
        }
        for (BmComparisonHelper.Intent intent : BmComparisonHelper.Intent.values())
        {
            if (intent.name().equalsIgnoreCase(argument.trim()))
            {
                return intent;
            }
        }
        throw new IllegalArgumentException(argument + " is not an intent. Use REPORT, MERGE or " //$NON-NLS-1$
            + "MERGE_IGNORING_PROBLEMS."); //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String otherPath = JsonUtils.extractStringArgument(params, "otherPath"); //$NON-NLS-1$
        String ancestorPath = JsonUtils.extractStringArgument(params, "ancestorPath"); //$NON-NLS-1$

        String decisionsJson = JsonUtils.extractStringArgument(params, "decisions"); //$NON-NLS-1$
        String decisionsPath = JsonUtils.extractStringArgument(params, "decisionsPath"); //$NON-NLS-1$
        String decisionsFrom = JsonUtils.extractStringArgument(params, "decisionsFrom"); //$NON-NLS-1$
        List<BmComparisonHelper.Decision> decisions;
        try
        {
            decisions = readDecisions(decisionsJson);
        }
        catch (IllegalArgumentException malformed)
        {
            return ToolResult.error(malformed.getMessage()).toJson();
        }

        String intentArgument = JsonUtils.extractStringArgument(params, "intent"); //$NON-NLS-1$
        BmComparisonHelper.Intent intent;
        try
        {
            intent = readIntent(intentArgument);
        }
        catch (IllegalArgumentException unknown)
        {
            return ToolResult.error(unknown.getMessage()).toJson();
        }
        if (intent != BmComparisonHelper.Intent.REPORT)
        {
            // A merge writes into the configuration, so a preset that forbids writing forbids this
            // - checked before anything is compared, not after the work is done.
            String forbidden = ru.aiedt.mcp.server.support.ToolGate
                .gateIfPresetDisabled("write_module_source"); //$NON-NLS-1$
            if (forbidden != null)
            {
                return forbidden;
            }
        }

        BmComparisonHelper.Outcome outcome = BmComparisonHelper.compare(projectName, otherPath,
            ancestorPath, decisions, decisionsPath, decisionsFrom, intent);
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
            // Present only when decisions were restored from a file, so a caller who named
            // one can tell it was read from a run where the argument was quietly ignored.
            .put("decisionsReadFrom", outcome.decisionsReadFrom) //$NON-NLS-1$
            .put("decisionsRestored", outcome.decisionsRestored) //$NON-NLS-1$
            .put("merged", outcome.merged) //$NON-NLS-1$
            .put("mergeStatus", outcome.mergeStatus) //$NON-NLS-1$
            // Present whenever a merge was asked for and did not happen. Silence here would leave
            // the caller to infer from merged:false, which is also what a failed merge says.
            .put("mergeRefused", outcome.mergeRefused) //$NON-NLS-1$
            // What the project looks like after being written to. A merge that succeeds and
            // leaves the configuration broken is ordinary, not exceptional.
            .put("errorsAfterMerge", outcome.errorsAfterMerge) //$NON-NLS-1$
            .put("revalidatedAfterMerge", outcome.revalidatedAfterMerge) //$NON-NLS-1$
            .toJson();
    }
}
