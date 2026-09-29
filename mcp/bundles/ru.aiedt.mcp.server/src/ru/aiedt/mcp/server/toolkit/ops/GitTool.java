/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.eclipse.core.resources.IProject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.GitFileDiff;
import ru.aiedt.mcp.server.support.GitRepositoryAccess;
import ru.aiedt.mcp.server.support.MergeRestorePoint;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * {@code git} - the repository the project lives in, worked inside the IDE: status, branches,
 * log, commit, checkout, a file diff, putting one file back, and a merge restore point.
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
    /** Every operation this tool accepts, in the order a refusal lists them. */
    private static final String KNOWN =
        "status | branches | log | commit | checkout | show_file_changes | revert_file | " //$NON-NLS-1$
            + "create_merge_restore_point | restore_merge_point"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return "git"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Git for the project inside EDT: status, branches, log, commit, checkout, " //$NON-NLS-1$
            + "show_file_changes and revert_file. " //$NON-NLS-1$
            + "show_file_changes reads a line diff, or (granularity=method, one .bsl file) the " //$NON-NLS-1$
            + "changed procedures and functions by name; edits outside any method count in the file " //$NON-NLS-1$
            + "totals and are not a method hunk. " //$NON-NLS-1$
            + "revert_file writes one file's bytes back from a commit, with the line endings a " //$NON-NLS-1$
            + "checkout would give it; dryRun previews and writes nothing. The index is not touched: " //$NON-NLS-1$
            + "afterwards the file matches HEAD, or it is listed as modified. An external edit of " //$NON-NLS-1$
            + ".form, .mdo or .dcs needs revalidate_objects. " //$NON-NLS-1$
            + "create_merge_restore_point records the project files before a merge and does not move " //$NON-NLS-1$
            + "HEAD: a commit and its hash when the project is in git, otherwise a copy of the project " //$NON-NLS-1$
            + "directory. restore_merge_point puts those files back and does not roll back the infobase. " //$NON-NLS-1$
            + "Operations: status (work tree and index vs HEAD, ahead/behind the tracking branch), " //$NON-NLS-1$
            + "branches (local branches, current first), log (recent commits), " //$NON-NLS-1$
            + "commit (stage named paths and commit them - paths by name only, there is no add-all), " //$NON-NLS-1$
            + "checkout (switch branch, or create it), show_file_changes, revert_file, " //$NON-NLS-1$
            + "create_merge_restore_point, restore_merge_point."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public List<String> getGatedWriteNames()
    {
        return List.of("git_commit", "git_checkout", GitFileRestore.DOOR); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                KNOWN + " (required)") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the EDT project; its repository is the one the operation reads.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "log: how many commits to list (default 10, at most 100). " //$NON-NLS-1$
                    + "show_file_changes: how many changed files the answer carries (default 50, at " //$NON-NLS-1$
                    + "most 200); the answer gives the total in totalFileCount either way.") //$NON-NLS-1$
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
            .stringProperty("filePath", //$NON-NLS-1$
                "show_file_changes: one file, relative to the work tree (a project-relative path " //$NON-NLS-1$
                    + "is accepted). Omit it to list the changed files with line counts and no hunks, " //$NON-NLS-1$
                    + "at most limit of them. " //$NON-NLS-1$
                    + "revert_file: the one file to put back (required).") //$NON-NLS-1$
            .stringProperty("fromRef", //$NON-NLS-1$
                "show_file_changes and revert_file: the commit to read (SHA, branch or HEAD). " //$NON-NLS-1$
                    + "Default HEAD.") //$NON-NLS-1$
            .stringProperty("toRef", //$NON-NLS-1$
                "show_file_changes: the commit to compare against. Omit it to compare with the " //$NON-NLS-1$
                    + "work tree.") //$NON-NLS-1$
            .stringProperty("granularity", //$NON-NLS-1$
                "show_file_changes: line (default) or method. method names each changed procedure " //$NON-NLS-1$
                    + "and function and applies only to one .bsl filePath; any other file is refused.") //$NON-NLS-1$
            .booleanProperty("dryRun", //$NON-NLS-1$
                "revert_file: true previews the diff against fromRef and writes nothing " //$NON-NLS-1$
                    + "(default false). A preview matches the file on disk and is answered even " //$NON-NLS-1$
                    + "while an editor holds unsaved changes for it; only the write is refused then.") //$NON-NLS-1$
            .stringProperty("pointId", //$NON-NLS-1$
                "restore_merge_point: the point to put back. Omit it to use the latest point for " //$NON-NLS-1$
                    + "the project.") //$NON-NLS-1$
            .build();
    }

    /**
     * Runs one git operation for the project.
     *
     * @param params the call arguments; {@code operation} and {@code projectName} are required
     * @return the answer JSON
     */
    @Override
    public String execute(Map<String, String> params)
    {
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.trim().isEmpty())
        {
            return ToolResult.error("operation is required: " + KNOWN).toJson(); //$NON-NLS-1$
        }
        String op = operation.trim().toLowerCase(java.util.Locale.ROOT);
        if (!known(op))
        {
            return ToolResult.error("Unknown operation: " + operation + ". Known: " + KNOWN + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
        if (op.equals("revert_file") || op.equals("restore_merge_point")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            // Putting files back writes them. The door is a registered tool, so a read-only preset
            // refuses the call before a file is read. A preview of revert_file is the same door.
            String gate = ToolGate.gateOrNull(GitFileRestore.DOOR);
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
        // The call is handed on under another name so the parameter read for one operation is not
        // attributed to every operation this method handles.
        Map<String, String> arguments = params;
        return route(op, operation, projectName, project, arguments);
    }

    /**
     * Routes one operation after the project is resolved. A merge restore point does not need a
     * repository. The other operations open one and answer from it.
     *
     * @param op the operation, already trimmed and lower-cased
     * @param operation the operation as the caller spelled it, used in the refusal text
     * @param projectName the project name, used in the refusal text
     * @param project the resolved project
     * @param params the call arguments
     * @return the answer JSON
     */
    private String route(String op, String operation, String projectName, IProject project,
        Map<String, String> params)
    {
        boolean mergePoint = "create_merge_restore_point".equals(op) //$NON-NLS-1$
            || "restore_merge_point".equals(op); //$NON-NLS-1$
        GitRepositoryAccess.Resolved resolved = null;
        if (!mergePoint)
        {
            resolved = GitRepositoryAccess.of(project);
            if (resolved.error != null)
            {
                return ToolResult.error(resolved.error)
                    .put("projectName", projectName) //$NON-NLS-1$
                    .toJson();
            }
        }
        try (GitRepositoryAccess.Resolved session = resolved)
        {
            switch (op)
            {
            case "create_merge_restore_point": //$NON-NLS-1$
                return MergeRestorePoint.createJson(project);
            case "restore_merge_point": //$NON-NLS-1$
                return MergeRestorePoint.restoreJson(project,
                    JsonUtils.extractStringArgument(params, "pointId")); //$NON-NLS-1$
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
            case "show_file_changes": //$NON-NLS-1$
                return doShowFileChanges(project, session.git, params);
            case "revert_file": //$NON-NLS-1$
                return doRevertFile(project, session.git, params);
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
     * Whether {@code op} is one of {@link #KNOWN}.
     *
     * @param op the operation, already trimmed and lower-cased
     * @return {@code true} when this tool runs it
     */
    private static boolean known(String op)
    {
        for (String name : KNOWN.split(" \\| ")) //$NON-NLS-1$
        {
            if (name.equals(op))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The line diff between two revisions, or the changed files when no file is named.
     *
     * <p>{@code method} granularity names each changed procedure and function. It applies only to
     * one {@code .bsl} file; any other file is refused rather than answered with a line diff the
     * caller did not ask for.</p>
     *
     * <p>Without a {@code filePath} the answer carries at most {@code limit} files and reports the
     * whole count beside it, so a tree that changed in thousands of files answers with the limit
     * rather than with everything. A file above {@code GitFileDiff.LINE_COUNT_BYTE_LIMIT} is listed
     * by its size instead of its line counts; naming it in {@code filePath} gives its diff.</p>
     *
     * @param project the project whose repository is read
     * @param git the repository
     * @param params the call's arguments
     * @return the answer JSON
     * @throws Exception when the repository cannot be read
     */
    private String doShowFileChanges(IProject project, Git git, Map<String, String> params)
        throws Exception
    {
        String filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$
        String fromRef = JsonUtils.extractStringArgument(params, "fromRef"); //$NON-NLS-1$
        String toRef = JsonUtils.extractStringArgument(params, "toRef"); //$NON-NLS-1$
        String granularity = JsonUtils.extractStringArgument(params, "granularity"); //$NON-NLS-1$
        if (fromRef == null || fromRef.trim().isEmpty())
        {
            fromRef = "HEAD"; //$NON-NLS-1$
        }
        else
        {
            fromRef = fromRef.trim();
        }
        if (toRef == null || toRef.trim().isEmpty())
        {
            toRef = GitFileDiff.WORK_TREE;
        }
        else
        {
            toRef = toRef.trim();
        }
        if (granularity == null || granularity.trim().isEmpty())
        {
            granularity = GitFileDiff.LINE;
        }
        else
        {
            granularity = granularity.trim().toLowerCase(java.util.Locale.ROOT);
        }
        if (!GitFileDiff.LINE.equals(granularity) && !GitFileDiff.METHOD.equals(granularity))
        {
            return ToolResult.error("granularity must be line or method.").toJson(); //$NON-NLS-1$
        }
        String repoPath = null;
        if (filePath != null && !filePath.trim().isEmpty())
        {
            String normalized = normalizePath(filePath);
            String refusal = pathRefusal(normalized);
            if (refusal != null)
            {
                return ToolResult.error(refusal).toJson();
            }
            repoPath = GitFileRestore.toRepoPath(project, git.getRepository(), normalized);
        }
        if (GitFileDiff.METHOD.equals(granularity)
            && (repoPath == null || !repoPath.toLowerCase(java.util.Locale.ROOT).endsWith(".bsl"))) //$NON-NLS-1$
        {
            return ToolResult.error("granularity=method applies only to one .bsl file named in " //$NON-NLS-1$
                + "filePath. Other files are refused; use granularity=line.").toJson(); //$NON-NLS-1$
        }
        int limit = JsonUtils.extractIntArgument(params, "limit", GitFileDiff.DEFAULT_FILE_LIMIT); //$NON-NLS-1$
        limit = Math.max(1, Math.min(GitFileDiff.MAX_FILE_LIMIT, limit));
        GitFileDiff.Answer diff = GitFileDiff.between(git.getRepository(), fromRef, toRef, repoPath,
            granularity, limit);
        if (diff.error != null)
        {
            return ToolResult.error(diff.error).toJson();
        }
        return ToolResult.success()
            .put("operation", "show_file_changes") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("fromRef", fromRef) //$NON-NLS-1$
            .put("toRef", toRef) //$NON-NLS-1$
            .put("granularity", granularity) //$NON-NLS-1$
            .put("fileCount", Integer.valueOf(diff.files.size())) //$NON-NLS-1$
            .put("totalFileCount", Integer.valueOf(diff.total)) //$NON-NLS-1$
            .put("truncated", Boolean.valueOf(diff.truncated)) //$NON-NLS-1$
            .put("files", diff.files) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Puts one file back to the bytes a commit holds. The write itself is {@link GitFileRestore}.
     *
     * @param project the project whose repository is written
     * @param git the repository
     * @param params the call's arguments
     * @return the answer JSON
     * @throws Exception when the repository cannot be read
     */
    private String doRevertFile(IProject project, Git git, Map<String, String> params) throws Exception
    {
        String filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$
        String fromRef = JsonUtils.extractStringArgument(params, "fromRef"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        return GitFileRestore.apply(project, git, filePath, fromRef, dryRun);
    }

    /**
     * Stages the named paths and commits them, by name or not at all.
     * <p>
     * A blanket add is what a review cannot be told apart from - everything that happened to be
     * lying in the work tree lands in the commit, including what nobody looked at. Paths are
     * named, the message is required, and every check - the author, each path - runs before the
     * index is touched. A file that is gone from the work tree but tracked is staged as a deletion;
     * a path that names nothing on disk and nothing in the index is refused. Only the named paths
     * enter the commit, whatever else the index holds. When the commit itself fails, the named paths
     * are unstaged again.
     * </p>
     *
     * @param project the project whose repository is written
     * @param git the repository
     * @param params the call's arguments
     * @return the answer JSON
     * @throws Exception when the repository cannot be read
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
            String path = normalizePath(part);
            if (!path.isEmpty() && !paths.contains(path))
            {
                paths.add(path);
            }
        }
        if (paths.isEmpty())
        {
            return ToolResult.error("commit requires paths - none of the comma-separated entries " //$NON-NLS-1$
                + "names a file.").toJson(); //$NON-NLS-1$
        }
        Repository repository = git.getRepository();
        PersonIdent author = authorOf(repository, params);
        if (author == null)
        {
            return ToolResult.error("The repository's configuration names no author, and " //$NON-NLS-1$
                + "authorName/authorEmail were not given - name one (git config user.name and " //$NON-NLS-1$
                + "user.email, or pass both arguments). Nothing was staged or committed.").toJson(); //$NON-NLS-1$
        }
        java.io.File workTree = repository.getWorkTree();
        Status before = statusOf(git, paths);
        List<String> toAdd = new ArrayList<>();
        List<String> toRemove = new ArrayList<>();
        for (String path : paths)
        {
            String refusal = pathRefusal(path);
            if (refusal != null)
            {
                return ToolResult.error(refusal + " Nothing was staged or committed.") //$NON-NLS-1$
                    .put("path", path).toJson(); //$NON-NLS-1$
            }
            java.io.File file = new java.io.File(workTree, path);
            if (file.isDirectory())
            {
                return ToolResult.error("Path names a directory: " + path + ". Staging a directory " //$NON-NLS-1$ //$NON-NLS-2$
                    + "stages everything under it, which is an add-all by another name - name the " //$NON-NLS-1$
                    + "files. Nothing was staged or committed.").put("path", path).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (file.exists())
            {
                toAdd.add(path);
            }
            else if (before.getMissing().contains(path) || before.getRemoved().contains(path))
            {
                toRemove.add(path);
            }
            else
            {
                return ToolResult.error("Path does not exist in the work tree and is not tracked by " //$NON-NLS-1$
                    + "the repository: " + path + ". Paths are relative to the repository root " //$NON-NLS-1$
                    + workTree.getAbsolutePath() + ". Nothing was staged or committed.") //$NON-NLS-1$
                    .put("path", path).toJson(); //$NON-NLS-1$
            }
        }

        RevCommit commit;
        List<Map<String, String>> changes;
        try
        {
            for (String path : toAdd)
            {
                git.add().addFilepattern(path).call();
            }
            for (String path : toRemove)
            {
                git.rm().setCached(true).addFilepattern(path).call();
            }
            changes = stagedChanges(statusOf(git, paths), paths);
            if (changes.isEmpty())
            {
                unstage(git, paths);
                return ToolResult.error("None of the named paths has a change to commit: " //$NON-NLS-1$
                    + String.join(", ", paths) + ". Nothing was committed.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            org.eclipse.jgit.api.CommitCommand command = git.commit().setAuthor(author)
                .setMessage(message.trim());
            for (Map<String, String> change : changes)
            {
                command.setOnly(change.get("path")); //$NON-NLS-1$
            }
            commit = command.call();
        }
        catch (Exception e)
        {
            unstage(git, paths);
            Activator.logError("git commit failed", e); //$NON-NLS-1$
            return ToolResult.error("The commit failed: " + e.getMessage() //$NON-NLS-1$
                + ". The named paths were unstaged again; nothing was committed.").toJson(); //$NON-NLS-1$
        }

        List<String> committed = new ArrayList<>();
        for (Map<String, String> change : changes)
        {
            committed.add(change.get("path")); //$NON-NLS-1$
        }
        List<String> unchanged = new ArrayList<>(paths);
        unchanged.removeAll(committed);
        ToolResult answer = ToolResult.success()
            .put("operation", "commit") //$NON-NLS-1$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("sha", commit.getName()) //$NON-NLS-1$
            .put("shortSha", commit.getName().substring(0, 8)) //$NON-NLS-1$
            .put("author", author.getName()) //$NON-NLS-1$
            .put("filesCount", Integer.valueOf(committed.size())) //$NON-NLS-1$
            .put("files", committed) //$NON-NLS-1$
            .put("changes", changes) //$NON-NLS-1$
            .put("message", commit.getShortMessage()); //$NON-NLS-1$
        if (!unchanged.isEmpty())
        {
            answer.put("unchanged", unchanged); //$NON-NLS-1$
        }
        String branch = repository.getBranch();
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
     * Brings a path as a caller wrote it to the form the index uses: forward slashes, no leading
     * {@code ./}, no surrounding blanks.
     *
     * @param raw one entry of {@code paths}
     * @return the path, empty when the entry names nothing
     */
    static String normalizePath(String raw)
    {
        String path = raw.trim().replace('\\', '/');
        while (path.startsWith("./")) //$NON-NLS-1$
        {
            path = path.substring(2);
        }
        return path;
    }

    /**
     * Tells why a path cannot be staged by name. Does not look at the disk or the index.
     *
     * @param path a normalized path
     * @return the refusal text, or <code>null</code> when the path is a plain relative file path
     */
    static String pathRefusal(String path)
    {
        if (".".equals(path) || "*".equals(path) || path.endsWith("/")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return "Path '" + path + "' names the whole work tree or a directory, which is an " //$NON-NLS-1$ //$NON-NLS-2$
                + "add-all by another name - name the files."; //$NON-NLS-1$
        }
        if (path.indexOf('*') >= 0 || path.indexOf('?') >= 0 || path.indexOf('[') >= 0)
        {
            return "Path '" + path + "' is a pattern - name the files one by one."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (path.startsWith("/") || (path.length() > 1 && path.charAt(1) == ':')) //$NON-NLS-1$
        {
            return "Path '" + path + "' is absolute - paths are relative to the repository root."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String segment : path.split("/")) //$NON-NLS-1$
        {
            if ("..".equals(segment)) //$NON-NLS-1$
            {
                return "Path '" + path + "' leaves the repository through '..'."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return null;
    }

    /**
     * The author a commit is made under: the repository's configured one, else the one the call
     * names.
     *
     * @param repository the repository
     * @param params the call's arguments
     * @return the author, or <code>null</code> when neither names one completely
     */
    private static PersonIdent authorOf(Repository repository, Map<String, String> params)
    {
        String configuredName = repository.getConfig().getString("user", null, "name"); //$NON-NLS-1$ //$NON-NLS-2$
        String configuredEmail = repository.getConfig().getString("user", null, "email"); //$NON-NLS-1$ //$NON-NLS-2$
        if (configuredName != null && !configuredName.isEmpty() && configuredEmail != null
            && !configuredEmail.isEmpty())
        {
            return new PersonIdent(configuredName, configuredEmail);
        }
        String authorName = JsonUtils.extractStringArgument(params, "authorName"); //$NON-NLS-1$
        String authorEmail = JsonUtils.extractStringArgument(params, "authorEmail"); //$NON-NLS-1$
        if (authorName == null || authorName.isEmpty() || authorEmail == null || authorEmail.isEmpty())
        {
            return null;
        }
        return new PersonIdent(authorName, authorEmail);
    }

    /**
     * The status of the named paths only, so a large work tree is not scanned for a commit of a few
     * files.
     *
     * @param git the repository
     * @param paths the named paths
     * @return their status
     * @throws Exception when the repository cannot be read
     */
    private static Status statusOf(Git git, List<String> paths) throws Exception
    {
        org.eclipse.jgit.api.StatusCommand command = git.status();
        for (String path : paths)
        {
            command.addPath(path);
        }
        return command.call();
    }

    /**
     * The staged change of each named path, in the order the paths were named.
     *
     * @param status the status after staging
     * @param paths the named paths
     * @return one {@code {path, change}} per path with a staged change; {@code change} is
     *         {@code added}, {@code modified} or {@code deleted}
     */
    static List<Map<String, String>> stagedChanges(Status status, List<String> paths)
    {
        List<Map<String, String>> changes = new ArrayList<>();
        for (String path : paths)
        {
            String change = status.getAdded().contains(path) ? "added" //$NON-NLS-1$
                : status.getChanged().contains(path) ? "modified" //$NON-NLS-1$
                : status.getRemoved().contains(path) ? "deleted" : null; //$NON-NLS-1$
            if (change != null)
            {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("path", path); //$NON-NLS-1$
                entry.put("change", change); //$NON-NLS-1$
                changes.add(entry);
            }
        }
        return changes;
    }

    /**
     * Puts the index entries of the named paths back to HEAD. Failures are logged, not thrown: this
     * runs on the way out of a refusal whose own reason matters more.
     *
     * @param git the repository
     * @param paths the named paths
     */
    private static void unstage(Git git, List<String> paths)
    {
        try
        {
            org.eclipse.jgit.api.ResetCommand reset = git.reset();
            for (String path : paths)
            {
                reset.addPath(path);
            }
            reset.call();
        }
        catch (Exception e)
        {
            Activator.logError("git commit: could not unstage " + paths, e); //$NON-NLS-1$
        }
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
