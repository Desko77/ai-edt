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
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * {@code git} - the repository the project lives in, worked inside the IDE: status, branches,
 * log, commit.
 *
 * <p>Development happens in EDT, and the questions git answers - what changed, what branch is
 * this, what is behind - belong to the same window. JGit ships with both supported EDT releases,
 * so the answer comes from the repository itself rather than a git.exe that may not be installed
 * and would not know which workspace it was answering about.</p>
 *
 * <p>Reading changes nothing. Committing names its files one by one, by name or not at all - a
 * blanket add is exactly what a review cannot be told apart from, so there is none.</p>
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
        return "Git for the project inside EDT: status, branches, log, commit. " //$NON-NLS-1$
            + "Operations: status (work tree and index vs HEAD, ahead/behind the tracking branch), " //$NON-NLS-1$
            + "branches (local branches, current first), log (recent commits), " //$NON-NLS-1$
            + "commit (stage named paths and commit them - paths by name only, there is no add-all)."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public List<String> getGatedWriteNames()
    {
        return List.of("git_commit", "git_checkout"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", "status | branches | log | commit (required)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the EDT project; its repository is the one the operation reads.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "log: how many commits to list (default 10, at most 100).") //$NON-NLS-1$
            .stringProperty("paths", //$NON-NLS-1$
                "commit: comma-separated paths to stage and commit, relative to the repository's " //$NON-NLS-1$
                    + "work tree (e.g. 'src/CommonModules/MyModule/Module.bsl,CHANGELOG.md'). " //$NON-NLS-1$
                    + "Required and by name only - a blanket add-all is refused, because a review " //$NON-NLS-1$
                    + "cannot be told apart from one.") //$NON-NLS-1$
            .stringProperty("message", //$NON-NLS-1$
                "commit: the commit message (required).") //$NON-NLS-1$
            .stringProperty("authorName", //$NON-NLS-1$
                "commit: author name when the repository's own configuration names none. " //$NON-NLS-1$
                    + "The configured author is used first.") //$NON-NLS-1$
            .stringProperty("authorEmail", //$NON-NLS-1$
                "commit: author email, paired with authorName.") //$NON-NLS-1$
            .stringProperty("branch", //$NON-NLS-1$
                "checkout: the branch to switch to, or (with createBranch) to create and switch " //$NON-NLS-1$
                    + "to. A switch refused on conflicting files names them and changes nothing.") //$NON-NLS-1$
            .booleanProperty("createBranch", //$NON-NLS-1$
                "checkout: create the branch from the current HEAD instead of switching to an " //$NON-NLS-1$
                    + "existing one (default false).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.trim().isEmpty())
        {
            return ToolResult.error("operation is required: status | branches | log | commit | checkout").toJson(); //$NON-NLS-1$
        }
        String op = operation.trim().toLowerCase(java.util.Locale.ROOT);
        if (!op.equals("status") && !op.equals("branches") && !op.equals("log") && !op.equals("commit") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            && !op.equals("checkout")) //$NON-NLS-1$
        {
            return ToolResult.error("Unknown operation: " + operation //$NON-NLS-1$
                + ". Known: status, branches, log, commit, checkout.").toJson(); //$NON-NLS-1$
        }
        if (op.equals("commit")) //$NON-NLS-1$
        {
            // A commit writes the repository, so it is gated as a write: under a read-only preset
            // it is refused before a single file is staged.
            String gate = ToolGate.gateOrNull("git_commit"); //$NON-NLS-1$
            if (gate != null)
            {
                return ToolResult.error(gate).toJson();
            }
        }
        if (op.equals("checkout")) //$NON-NLS-1$
        {
            // A checkout rewrites the work tree - gated the same way, before anything is read.
            String gate = ToolGate.gateOrNull("git_checkout"); //$NON-NLS-1$
            if (gate != null)
            {
                return ToolResult.error(gate).toJson();
            }
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName must be provided").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
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
            case "commit": //$NON-NLS-1$
                return doCommit(project, session.git, params);
            case "checkout": //$NON-NLS-1$
                return doCheckout(project, session.git, params);
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
     * Stages the named paths and commits them, by name or not at all.
     * <p>
     * A blanket add is what a review cannot be told apart from - everything that happened to be
     * lying in the work tree lands in the commit, including what nobody looked at. Paths are
     * named, the message is required, and a path that names nothing that exists is refused
     * rather than silently staged as nothing.
     * </p>
     */
    private String doCommit(IProject project, Git git, Map<String, String> params)
        throws Exception
    {
        String pathsRaw = JsonUtils.extractStringArgument(params, "paths"); //$NON-NLS-1$
        if (pathsRaw == null || pathsRaw.trim().isEmpty())
        {
            return ToolResult.error("commit requires paths - the files to stage and commit, " //$NON-NLS-1$
                + "comma-separated and by name. There is no add-all here: a commit of everything " //$NON-NLS-1$
                + "lying in the work tree is exactly what a review cannot be told apart from.").toJson(); //$NON-NLS-1$
        }
        String message = JsonUtils.extractStringArgument(params, "message"); //$NON-NLS-1$
        if (message == null || message.trim().isEmpty())
        {
            return ToolResult.error("commit requires a message").toJson(); //$NON-NLS-1$
        }
        List<String> paths = new ArrayList<>();
        for (String part : pathsRaw.split(",")) //$NON-NLS-1$
        {
            String path = part.trim();
            if (!path.isEmpty() && !paths.contains(path))
            {
                paths.add(path);
            }
        }
        java.io.File workTree = git.getRepository().getWorkTree();
        for (String path : paths)
        {
            if (!new java.io.File(workTree, path).exists())
            {
                return ToolResult.error("Path does not exist in the work tree: " + path //$NON-NLS-1$
                    + ". Paths are relative to the repository root " + workTree.getAbsolutePath()).toJson(); //$NON-NLS-1$
            }
            git.add().addFilepattern(path).call();
        }
        org.eclipse.jgit.lib.PersonIdent author;
        String configuredName = git.getRepository().getConfig().getString("user", null, "name"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String configuredEmail = git.getRepository().getConfig().getString("user", null, "email"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (configuredName != null && !configuredName.isEmpty() && configuredEmail != null
            && !configuredEmail.isEmpty())
        {
            author = new org.eclipse.jgit.lib.PersonIdent(configuredName, configuredEmail);
        }
        else
        {
            String authorName = JsonUtils.extractStringArgument(params, "authorName"); //$NON-NLS-1$
            String authorEmail = JsonUtils.extractStringArgument(params, "authorEmail"); //$NON-NLS-1$
            if (authorName == null || authorName.isEmpty() || authorEmail == null || authorEmail.isEmpty())
            {
                return ToolResult.error("The repository's configuration names no author, and " //$NON-NLS-1$
                    + "authorName/authorEmail were not given - name one (git config user.name and " //$NON-NLS-1$
                    + "user.email, or pass both arguments). Nothing was staged or committed.").toJson(); //$NON-NLS-1$
            }
            author = new org.eclipse.jgit.lib.PersonIdent(authorName, authorEmail);
        }
        RevCommit commit = git.commit().setAuthor(author).setMessage(message.trim()).call();
        ToolResult answer = ToolResult.success()
            .put("operation", "commit") //$NON-NLS-1$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("sha", commit.getName()) //$NON-NLS-1$
            .put("shortSha", commit.getName().substring(0, 8)) //$NON-NLS-1$
            .put("author", author.getName()) //$NON-NLS-1$
            .put("filesCount", Integer.valueOf(paths.size())) //$NON-NLS-1$
            .put("files", paths) //$NON-NLS-1$
            .put("message", commit.getShortMessage()); //$NON-NLS-1$
        String branch = git.getRepository().getBranch();
        String bound = ru.aiedt.mcp.server.support.BranchInfobaseBook.boundTo(project, branch);
        if (bound != null)
        {
            // The binding is context, not a stop: a commit on a branch whose infobase is spoken
            // for is exactly the work that branch exists for. Said so the caller knows.
            answer.put("boundInfobase", bound); //$NON-NLS-1$
            answer.put("note", "This branch is bound to the infobase '" + bound //$NON-NLS-1$
                + "' - update_database on it updates that infobase."); //$NON-NLS-1$
        }
        return answer.toJson();
    }

    /**
     * Moves HEAD to another branch, or creates it and moves there.
     * <p>
     * The uncommitted work is the thing a blind switch loses: JGit refuses the switch on files it
     * would overwrite, and the refusal names them. When the work is unrelated to the switch, it
     * carries over - the same rule git itself applies, reported here with what carried and what
     * the branch was before.
     * </p>
     */
    private String doCheckout(IProject project, Git git, Map<String, String> params)
        throws Exception
    {
        String branch = JsonUtils.extractStringArgument(params, "branch"); //$NON-NLS-1$
        if (branch == null || branch.trim().isEmpty())
        {
            return ToolResult.error("checkout requires branch - the branch to switch to, " //$NON-NLS-1$
                + "or (with createBranch=true) to create and switch to.").toJson(); //$NON-NLS-1$
        }
        boolean create = JsonUtils.extractBooleanArgument(params, "createBranch", false); //$NON-NLS-1$
        boolean exists = git.getRepository().findRef("refs/heads/" + branch.trim()) != null; //$NON-NLS-1$
        if (create && exists)
        {
            return ToolResult.error("Branch already exists: " + branch //$NON-NLS-1$
                + ". Switch to it without createBranch.").toJson(); //$NON-NLS-1$
        }
        if (!create && !exists)
        {
            return ToolResult.error("Branch does not exist: " + branch //$NON-NLS-1$
                + ". Pass createBranch=true to create it from the current HEAD.").toJson(); //$NON-NLS-1$
        }
        String previous = git.getRepository().getBranch();
        try
        {
            git.checkout().setName(branch.trim()).setCreateBranch(create).call();
        }
        catch (org.eclipse.jgit.api.errors.CheckoutConflictException conflict)
        {
            return ToolResult.error("The switch was refused: " + conflict.getConflictingPaths().size() //$NON-NLS-1$
                    + " files would be overwritten by it - " //$NON-NLS-1$
                    + conflict.getConflictingPaths() //$NON-NLS-1$
                    + ". Commit them or put them aside first. Nothing was switched.") //$NON-NLS-1$
                .put("conflicting", new ArrayList<>(conflict.getConflictingPaths())) //$NON-NLS-1$
                .put("nothingSwitched", Boolean.TRUE) //$NON-NLS-1$
                .toJson();
        }
        ToolResult answer = ToolResult.success()
            .put("operation", "checkout") //$NON-NLS-1$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("previousBranch", previous) //$NON-NLS-1$
            .put("branch", branch.trim()) //$NON-NLS-1$
            .put("created", Boolean.valueOf(create)); //$NON-NLS-1$
        String bound = ru.aiedt.mcp.server.support.BranchInfobaseBook.boundTo(project, branch.trim());
        if (bound != null)
        {
            answer.put("boundInfobase", bound); //$NON-NLS-1$
            answer.put("note", "This branch is bound to the infobase '" + bound //$NON-NLS-1$
                + "' - update_database on it updates that infobase."); //$NON-NLS-1$
        }
        return answer.toJson();
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
