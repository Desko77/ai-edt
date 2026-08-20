/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

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
            + "counts, how many differ, how many exist on one side only, and the problems the " //$NON-NLS-1$
            + "environment raises, marking which of them block a merge. Reads only: it never " //$NON-NLS-1$
            + "merges."; //$NON-NLS-1$
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
        String otherPath = JsonUtils.extractStringArgument(params, "otherPath"); //$NON-NLS-1$
        String ancestorPath = JsonUtils.extractStringArgument(params, "ancestorPath"); //$NON-NLS-1$

        BmComparisonHelper.Outcome outcome =
            BmComparisonHelper.compare(projectName, otherPath, ancestorPath);
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
            .toJson();
    }
}
