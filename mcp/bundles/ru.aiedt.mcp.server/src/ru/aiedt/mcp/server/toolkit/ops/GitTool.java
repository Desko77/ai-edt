/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.eclipse.core.resources.IProject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.GitRepositoryAccess;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * {@code git} - the repository the project lives in, read inside the IDE: status, branches, log.
 *
 * <p>Development happens in EDT, and the questions git answers - what changed, what branch is
 * this, what is behind - belong to the same window. JGit ships with both supported EDT releases,
 * so the answer comes from the repository itself rather than a git.exe that may not be installed
 * and would not know which workspace it was answering about.</p>
 *
 * <p>This stage reads only: nothing here changes the work tree, the index or the history. Writing
 * (commit, checkout) arrives as its own operations, gated as writes.</p>
 */
public class GitTool
    implements IMcpTool
{
    @Override
    public String getName()
    {
        return "git"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Git for the project inside EDT: status, branches, log. " //$NON-NLS-1$
            + "Operations: status (work tree and index vs HEAD, ahead/behind the tracking branch), " //$NON-NLS-1$
            + "branches (local branches, current first), log (recent commits). " //$NON-NLS-1$
            + "Reads only - nothing here changes the work tree, the index or the history."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", "status | branches | log (required)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the EDT project; its repository is the one the operation reads.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "log: how many commits to list (default 10, at most 100).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.trim().isEmpty())
        {
            return ToolResult.error("operation is required: status | branches | log").toJson(); //$NON-NLS-1$
        }
        String op = operation.trim().toLowerCase(java.util.Locale.ROOT);
        if (!op.equals("status") && !op.equals("branches") && !op.equals("log")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return ToolResult.error("Unknown operation: " + operation //$NON-NLS-1$
                + ". Known: status, branches, log.").toJson(); //$NON-NLS-1$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName must be provided").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        GitRepositoryAccess.Resolved resolved = GitRepositoryAccess.of(project);
        if (resolved.error != null)
        {
            return ToolResult.error(resolved.error)
                .put("projectName", projectName)
                .toJson();
        }
        try (GitRepositoryAccess.Resolved session = resolved)
        {
            switch (op)
            {
            case "status": //$NON-NLS-1$
                return doStatus(project, session.git);
            case "branches": //$NON-NLS-1$
                return doBranches(project, session.git);
            case "log": //$NON-NLS-1$
                return doLog(project, session.git, params);
            default:
                return ToolResult.error("Unknown operation: " + operation).toJson(); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            Activator.logError("git operation " + op + " failed", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("The git operation failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * The work tree and the index, against HEAD and against the tracking branch.
     */
    private String doStatus(IProject project, Git git)
        throws Exception
    {
        Status status = git.status().call();
        ToolResult answer = ToolResult.success()
            .put("operation", "status") //$NON-NLS-1$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("branch", git.getRepository().getBranch()) //$NON-NLS-1$
            .put("isClean", Boolean.valueOf(status.isClean())); //$NON-NLS-1$
        answer.put("modified", new ArrayList<>(status.getModified())); //$NON-NLS-1$
        answer.put("added", new ArrayList<>(status.getAdded())); //$NON-NLS-1$
        answer.put("changed", new ArrayList<>(status.getChanged())); //$NON-NLS-1$
        answer.put("removed", new ArrayList<>(status.getRemoved())); //$NON-NLS-1$
        answer.put("missing", new ArrayList<>(status.getMissing())); //$NON-NLS-1$
        answer.put("untracked", new ArrayList<>(status.getUntracked())); //$NON-NLS-1$
        answer.put("untrackedFolders", new ArrayList<>(status.getUntrackedFolders())); //$NON-NLS-1$
        answer.put("conflicting", new ArrayList<>(status.getConflicting())); //$NON-NLS-1$
        BranchTrackingStatus tracking = BranchTrackingStatus.of(git.getRepository(),
            git.getRepository().getBranch());
        if (tracking != null)
        {
            answer.put("trackingBranch", tracking.getRemoteTrackingBranch()); //$NON-NLS-1$
            answer.put("aheadCount", Integer.valueOf(tracking.getAheadCount())); //$NON-NLS-1$
            answer.put("behindCount", Integer.valueOf(tracking.getBehindCount())); //$NON-NLS-1$
        }
        return answer.toJson();
    }

    /**
     * The local branches, current first.
     */
    private String doBranches(IProject project, Git git)
        throws Exception
    {
        String current = git.getRepository().getBranch();
        List<String> names = new ArrayList<>();
        for (Ref ref : git.branchList().call())
        {
            String shortName = shortRefName(ref);
            names.add(shortName);
        }
        names.remove(current);
        names.add(0, current);
        return ToolResult.success()
            .put("operation", "branches") //$NON-NLS-1$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("current", current) //$NON-NLS-1$
            .put("count", Integer.valueOf(names.size())) //$NON-NLS-1$
            .put("branches", names) //$NON-NLS-1$
            .toJson();
    }

    private static String shortRefName(Ref ref)
    {
        return org.eclipse.jgit.lib.Repository.shortenRefName(ref.getName());
    }

    /**
     * The recent commits, newest first.
     */
    private String doLog(IProject project, Git git, Map<String, String> params)
        throws Exception
    {
        int limit = JsonUtils.extractIntArgument(params, "limit", 10); //$NON-NLS-1$
        limit = Math.max(1, Math.min(100, limit));
        List<Map<String, Object>> commits = new ArrayList<>();
        SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX"); //$NON-NLS-1$
        stamp.setTimeZone(TimeZone.getDefault());
        int seen = 0;
        for (RevCommit commit : git.log().call())
        {
            if (seen >= limit)
            {
                break;
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("sha", commit.getName()); //$NON-NLS-1$
            row.put("shortSha", commit.getName().substring(0, 8)); //$NON-NLS-1$
            row.put("author", commit.getAuthorIdent().getName()); //$NON-NLS-1$
            row.put("time", stamp.format(commit.getAuthorIdent().getWhen())); //$NON-NLS-1$
            row.put("subject", commit.getShortMessage()); //$NON-NLS-1$
            commits.add(row);
            seen++;
        }
        return ToolResult.success()
            .put("operation", "log") //$NON-NLS-1$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("count", Integer.valueOf(commits.size())) //$NON-NLS-1$
            .put("commits", commits) //$NON-NLS-1$
            .toJson();
    }
}
