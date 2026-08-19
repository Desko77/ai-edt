/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.support.BranchInfobaseBook;
import ru.aiedt.mcp.server.support.GitBranch;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Says which infobase belongs to the branch a project is on, and remembers the answer.
 * <p>
 * The damage being guarded against is specific: switching to a colleague's branch and running the
 * update that always worked, which restructures the infobase that was being used for something
 * else. Nothing about that call looks unusual from the tool's side, so nothing warned anybody.
 * </p>
 * <p>
 * This does not switch anything. It records what belongs together and lets {@code update_database}
 * notice when the two have come apart - choosing an infobase on somebody's behalf would be a bigger
 * promise than a note in a settings file can keep, and getting that choice wrong is the very thing
 * being prevented.
 * </p>
 */
public class BranchInfobaseTool
    implements IMcpTool
{
    /** The wire name. */
    public static final String NAME = "branch_infobase"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Which infobase belongs to which git branch, per project. actions: current (the " //$NON-NLS-1$
            + "branch this project is on and what is bound to it), list (every binding), bind " //$NON-NLS-1$
            + "(remember an applicationId for a branch), unbind (forget one). update_database " //$NON-NLS-1$
            + "reads these and refuses when the branch says one infobase and the call names " //$NON-NLS-1$
            + "another, which is the accident this exists to prevent: restructuring the wrong " //$NON-NLS-1$
            + "infobase after a branch switch cannot be undone."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "EDT project name.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", //$NON-NLS-1$
                "current (default) / list / bind / unbind.", false) //$NON-NLS-1$
            .stringProperty("branch", //$NON-NLS-1$
                "Branch to bind or unbind. Defaults to the branch the project is on.", false) //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "The application to bind the branch to (see get_applications).", false) //$NON-NLS-1$
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
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        String action = JsonUtils.extractStringArgument(params, "action"); //$NON-NLS-1$
        if (action == null || action.isEmpty())
        {
            action = "current"; //$NON-NLS-1$
        }
        String onDisk = GitBranch.of(project);
        switch (action)
        {
            case "current": //$NON-NLS-1$
                return current(projectName, project, onDisk);
            case "list": //$NON-NLS-1$
                return list(projectName, project, onDisk);
            case "bind": //$NON-NLS-1$
                return bind(params, projectName, project, onDisk);
            case "unbind": //$NON-NLS-1$
                return unbind(params, projectName, project, onDisk);
            default:
                return ToolResult.error("Unknown action: " + action //$NON-NLS-1$
                    + ". Use current, list, bind or unbind.").toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Reports the branch and what is bound to it.
     *
     * @param projectName the project, for the answer.
     * @param project the resolved project.
     * @param branch the branch it is on, possibly {@code null}.
     * @return the answer
     */
    private static String current(String projectName, IProject project, String branch)
    {
        ToolResult result = ToolResult.success().put("projectName", projectName); //$NON-NLS-1$
        if (branch == null)
        {
            result.put("inRepository", false); //$NON-NLS-1$
            result.put("note", "This project is not in a git repository, so there is no branch to " //$NON-NLS-1$ //$NON-NLS-2$
                + "bind anything to."); //$NON-NLS-1$
            return result.toJson();
        }
        result.put("inRepository", true); //$NON-NLS-1$
        result.put("branch", branch); //$NON-NLS-1$
        String bound = BranchInfobaseBook.boundTo(project, branch);
        if (bound != null)
        {
            result.put("boundApplicationId", bound); //$NON-NLS-1$
        }
        if (bound == null)
        {
            result.put("note", GitBranch.isBranch(branch) //$NON-NLS-1$
                ? "Nothing is bound to this branch. update_database will not check anything until " //$NON-NLS-1$
                    + "something is." //$NON-NLS-1$
                : "The head is detached, so there is no branch name to bind. Check out a branch " //$NON-NLS-1$
                    + "first."); //$NON-NLS-1$
        }
        return result.toJson();
    }

    /**
     * Lists every binding.
     *
     * @param projectName the project, for the answer.
     * @param project the resolved project.
     * @param branch the branch it is on.
     * @return the answer
     */
    private static String list(String projectName, IProject project, String branch)
    {
        Map<String, String> bindings = BranchInfobaseBook.all(project);
        ToolResult result = ToolResult.success()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("branch", branch == null ? "" : branch) //$NON-NLS-1$ //$NON-NLS-2$
            .put("bindings", new LinkedHashMap<>(bindings)) //$NON-NLS-1$
            .put("count", bindings.size()); //$NON-NLS-1$
        if (bindings.isEmpty())
        {
            result.put("note", "No branch is bound to an infobase in this project. The file is " //$NON-NLS-1$ //$NON-NLS-2$
                + ".settings/" + BranchInfobaseBook.FILE + " and it is created on the first bind."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result.toJson();
    }

    /**
     * Binds a branch.
     *
     * @param params the call's arguments.
     * @param projectName the project, for the answer.
     * @param project the resolved project.
     * @param onDisk the branch the project is on.
     * @return the answer
     */
    private static String bind(Map<String, String> params, String projectName, IProject project,
        String onDisk)
    {
        String branch = branchArgument(params, onDisk);
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        if (branch == null || branch.isEmpty())
        {
            return ToolResult.error("No branch to bind: pass branch, or run this from a project " //$NON-NLS-1$
                + "that is in a git repository.").toJson(); //$NON-NLS-1$
        }
        if (!GitBranch.isBranch(branch))
        {
            return ToolResult.error("The head is detached (" + branch + "), which is not a branch " //$NON-NLS-1$ //$NON-NLS-2$
                + "and will not come back. Check out a branch, or name one with branch=.").toJson(); //$NON-NLS-1$
        }
        if (applicationId == null || applicationId.isEmpty())
        {
            return ToolResult.error("applicationId is required to bind. See get_applications.").toJson(); //$NON-NLS-1$
        }
        String failure = BranchInfobaseBook.bind(project, branch, applicationId);
        if (failure != null)
        {
            return ToolResult.error("Could not bind: " + failure).toJson(); //$NON-NLS-1$
        }
        return ToolResult.success()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("branch", branch) //$NON-NLS-1$
            .put("applicationId", applicationId) //$NON-NLS-1$
            .put("bound", true) //$NON-NLS-1$
            .put("message", "update_database on this branch will now refuse any other " //$NON-NLS-1$ //$NON-NLS-2$
                + "application unless it is told to go ahead anyway.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Removes a binding.
     *
     * @param params the call's arguments.
     * @param projectName the project, for the answer.
     * @param project the resolved project.
     * @param onDisk the branch the project is on.
     * @return the answer
     */
    private static String unbind(Map<String, String> params, String projectName, IProject project,
        String onDisk)
    {
        String branch = branchArgument(params, onDisk);
        if (branch == null || branch.isEmpty())
        {
            return ToolResult.error("No branch to unbind: pass branch.").toJson(); //$NON-NLS-1$
        }
        boolean existed = BranchInfobaseBook.boundTo(project, branch) != null;
        String failure = BranchInfobaseBook.unbind(project, branch);
        if (failure != null)
        {
            return ToolResult.error("Could not unbind: " + failure).toJson(); //$NON-NLS-1$
        }
        return ToolResult.success()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("branch", branch) //$NON-NLS-1$
            // Said rather than implied: "unbound: false" here means there was nothing bound, not
            // that the removal failed. Reporting plain success for both would leave a caller
            // believing it had undone something it never did.
            .put("unbound", existed) //$NON-NLS-1$
            .put("message", existed ? "The binding is gone." //$NON-NLS-1$ //$NON-NLS-2$
                : "Nothing was bound to that branch, so nothing was removed.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * The branch an action applies to: the one named, or the one the project is on.
     *
     * @param params the call's arguments.
     * @param onDisk the branch the project is on.
     * @return the branch, possibly {@code null}
     */
    private static String branchArgument(Map<String, String> params, String onDisk)
    {
        String named = JsonUtils.extractStringArgument(params, "branch"); //$NON-NLS-1$
        return named != null && !named.isEmpty() ? named : onDisk;
    }
}
