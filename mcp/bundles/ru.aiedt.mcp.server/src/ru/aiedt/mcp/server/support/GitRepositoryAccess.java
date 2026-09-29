/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import ru.aiedt.mcp.server.Activator;

/**
 * The git repository a project lives in, opened through JGit.
 *
 * <p>EDT ships JGit in both supported releases (6.8.x), so a git question is answered inside the
 * IDE, not by shelling out to a git.exe that may not be installed and would not know which
 * workspace it was answering about. A project can sit anywhere inside a repository - the nearest
 * ancestor with a .git entry is the answer, the way git itself walks upward. A {@code .git} file
 * (a worktree) is followed to its real directory.</p>
 */
public final class GitRepositoryAccess
{
    private GitRepositoryAccess()
    {
        // utility class
    }

    /**
     * The repository for a project, or the reason there is none.
     */
    public static final class Resolved implements AutoCloseable
    {
        public Git git;

        public Repository repository;

        public String error;

        /** Closes both. */
        @Override
        public void close()
        {
            if (git != null)
            {
                git.close();
            }
            else if (repository != null)
            {
                repository.close();
            }
        }
    }

    /**
     * Opens the repository the project belongs to.
     *
     * @param project the project
     * @return the resolution: either the open repository or the reason there is none
     */
    public static Resolved of(IProject project)
    {
        Resolved answer = new Resolved();
        if (project == null || !project.isAccessible())
        {
            answer.error = "The project is not accessible";
            return answer;
        }
        java.io.File location = project.getLocation() != null
            ? project.getLocation().toFile() : null;
        if (location == null)
        {
            answer.error = "The project has no location on disk";
            return answer;
        }
        try
        {
            // findGitDir walks upward from the project's directory, following .git files of
            // worktrees - the same discovery git itself does.
            FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(location);
            if (builder.getGitDir() == null)
            {
                answer.error = "The project is not inside a git repository: no .git found walking "
                    + "up from " + location.getAbsolutePath();
                return answer;
            }
            Repository repository = builder.build();
            answer.repository = repository;
            answer.git = Git.wrap(repository);
            return answer;
        }
        catch (IOException e)
        {
            answer.error = "Could not open the git repository: " + e.getMessage();
            return answer;
        }
    }

    /**
     * The repository at a path, for a caller that has no project - the same discovery.
     *
     * @param path a path inside the repository's work tree
     * @return the resolution: either the open repository or the reason there is none
     */
    public static Resolved at(String path)
    {
        Resolved answer = new Resolved();
        try
        {
            FileRepositoryBuilder builder = new FileRepositoryBuilder()
                .findGitDir(Path.of(path).toFile());
            if (builder.getGitDir() == null)
            {
                answer.error = "Not inside a git repository: no .git found walking up from "
                    + path;
                return answer;
            }
            Repository repository = builder.build();
            answer.repository = repository;
            answer.git = Git.wrap(repository);
            return answer;
        }
        catch (IOException | RuntimeException e)
        {
            answer.error = "Could not open the git repository: " + e.getMessage();
            return answer;
        }
    }

    /**
     * A commit that holds project files for a later restore and does not move HEAD.
     */
    public static final class RestoreCommit
    {
        /** The commit hash, when one was written. */
        public String hash;

        /** Why no commit was written. */
        public String error;
    }

    /**
     * The ref under which a merge restore commit is kept. It is not a branch, and updating it does
     * not move HEAD.
     *
     * @param pointId the point, already restricted to ref-safe characters
     * @return the full ref name
     */
    public static String restoreRef(String pointId)
    {
        return "refs/aiedt/merge-restore/" + pointId; //$NON-NLS-1$
    }

    /**
     * The work-tree path of one project file.
     *
     * @param project the project
     * @param repository the repository that contains the project
     * @param projectRelative the file relative to the project directory, with forward slashes
     * @return that path relative to the work tree, or {@code null} when it lies outside the work tree
     */
    public static String repoPath(IProject project, Repository repository, String projectRelative)
    {
        if (project == null || repository == null || projectRelative == null
            || project.getLocation() == null)
        {
            return null;
        }
        Path root = repository.getWorkTree().toPath().toAbsolutePath().normalize();
        Path projectDir = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
        Path file = projectDir.resolve(projectRelative).toAbsolutePath().normalize();
        if (!file.startsWith(root))
        {
            return null;
        }
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (relative.isEmpty() || ".".equals(relative) || relative.startsWith("../") //$NON-NLS-1$ //$NON-NLS-2$
            || "..".equals(relative)) //$NON-NLS-1$
        {
            return null;
        }
        return relative;
    }

    /**
     * Writes the project's files into a commit on {@link #restoreRef(String)} and leaves HEAD, the
     * index and the work tree as they were. The bytes stored are the work-tree bytes, so a later
     * restore puts back uncommitted edits as well as what HEAD already has.
     *
     * @param git the open repository
     * @param project the project whose files are recorded
     * @param pointId the point id, used as the ref suffix
     * @param projectRelativeFiles the files, relative to the project directory
     * @return the commit hash, or the reason none was written
     */
    public static RestoreCommit recordProjectFiles(Git git, IProject project, String pointId,
        List<String> projectRelativeFiles)
    {
        RestoreCommit answer = new RestoreCommit();
        if (git == null || project == null || pointId == null || projectRelativeFiles == null
            || projectRelativeFiles.isEmpty())
        {
            answer.error = "there are no project files to record"; //$NON-NLS-1$
            return answer;
        }
        Repository repository = git.getRepository();
        try (ObjectInserter inserter = repository.newObjectInserter())
        {
            TreeNode root = new TreeNode();
            for (String relative : projectRelativeFiles)
            {
                String repoPath = repoPath(project, repository, relative);
                if (repoPath == null)
                {
                    answer.error = relative + " is outside the git work tree. No restore point was recorded."; //$NON-NLS-1$
                    return answer;
                }
                Path file = project.getLocation().toFile().toPath().resolve(relative);
                byte[] bytes = Files.readAllBytes(file);
                ObjectId blobId = inserter.insert(Constants.OBJ_BLOB, bytes);
                root.add(repoPath, blobId);
            }
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(root.write(inserter));
            commit.setAuthor(new PersonIdent("AI-EDT", "aiedt@invalid")); //$NON-NLS-1$ //$NON-NLS-2$
            commit.setCommitter(commit.getAuthor());
            commit.setMessage("Merge restore point " + pointId + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
            ObjectId head = repository.resolve(Constants.HEAD);
            if (head != null)
            {
                commit.setParentId(head);
            }
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(restoreRef(pointId));
            update.setNewObjectId(commitId);
            update.setForceUpdate(true);
            RefUpdate.Result result = update.update();
            if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED
                && result != RefUpdate.Result.FAST_FORWARD && result != RefUpdate.Result.NO_CHANGE)
            {
                answer.error = "the restore commit could not be kept (" + result + "). No restore point was recorded."; //$NON-NLS-1$ //$NON-NLS-2$
                return answer;
            }
            answer.hash = commitId.getName();
            return answer;
        }
        catch (IOException | RuntimeException e)
        {
            Activator.logError("merge restore commit failed", e); //$NON-NLS-1$
            answer.error = "the restore commit could not be written (" + e.getMessage() //$NON-NLS-1$
                + "). No restore point was recorded."; //$NON-NLS-1$
            return answer;
        }
    }

    /**
     * Drops the ref that keeps a restore commit. A failed point must not stay addressable, and
     * deleting the ref does not move HEAD.
     *
     * @param repository the repository
     * @param pointId the point whose ref should go
     */
    public static void deleteRestoreRef(Repository repository, String pointId)
    {
        if (repository == null || pointId == null)
        {
            return;
        }
        try
        {
            RefUpdate update = repository.updateRef(restoreRef(pointId));
            update.setForceUpdate(true);
            RefUpdate.Result result = update.delete();
            if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.FAST_FORWARD
                && result != RefUpdate.Result.NO_CHANGE && result != RefUpdate.Result.NEW)
            {
                Activator.logError("could not drop merge restore ref " + pointId + ": " + result, null); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (IOException e)
        {
            Activator.logError("could not drop merge restore ref " + pointId, e); //$NON-NLS-1$
        }
    }

    /**
     * One directory in the tree written for a restore commit. Children are written first, then this
     * directory, so the parent tree names their ids. Entries are ordered with git's path sort, the
     * order a tree object is required to have.
     */
    private static final class TreeNode
    {
        private final Map<String, TreeNode> directories = new LinkedHashMap<>();

        private final Map<String, ObjectId> files = new LinkedHashMap<>();

        /**
         * Records one blob at a work-tree path. A name that is both a file and a directory is
         * refused, because a tree cannot hold both.
         *
         * @param repoPath the path relative to the work tree, with forward slashes
         * @param blobId the blob already stored in the repository
         */
        void add(String repoPath, ObjectId blobId)
        {
            String[] parts = repoPath.split("/", -1); //$NON-NLS-1$
            TreeNode node = this;
            for (int i = 0; i < parts.length - 1; i++)
            {
                String name = parts[i];
                if (node.files.containsKey(name))
                {
                    throw new IllegalArgumentException(repoPath + " collides with a recorded file"); //$NON-NLS-1$
                }
                TreeNode child = node.directories.get(name);
                if (child == null)
                {
                    child = new TreeNode();
                    node.directories.put(name, child);
                }
                node = child;
            }
            String leaf = parts[parts.length - 1];
            if (node.directories.containsKey(leaf))
            {
                throw new IllegalArgumentException(repoPath + " collides with a recorded directory"); //$NON-NLS-1$
            }
            node.files.put(leaf, blobId);
        }

        /**
         * Writes this directory and every directory under it.
         *
         * @param inserter where the tree objects are stored
         * @return the object id of this directory
         * @throws IOException the object store rejected a tree
         */
        ObjectId write(ObjectInserter inserter) throws IOException
        {
            List<TreeEntry> entries = new ArrayList<>();
            for (Map.Entry<String, TreeNode> directory : directories.entrySet())
            {
                entries.add(new TreeEntry(directory.getKey(), FileMode.TREE,
                    directory.getValue().write(inserter)));
            }
            for (Map.Entry<String, ObjectId> file : files.entrySet())
            {
                entries.add(new TreeEntry(file.getKey(), FileMode.REGULAR_FILE, file.getValue()));
            }
            entries.sort(TreeNode::gitOrder);
            TreeFormatter formatter = new TreeFormatter();
            for (TreeEntry entry : entries)
            {
                formatter.append(entry.name, entry.mode, entry.id);
            }
            return formatter.insertTo(inserter);
        }

        /**
         * Git orders a tree by the raw name, and a directory sorts as if its name ended with
         * {@code /}. Equal names then put a file before a directory.
         *
         * @param left one entry
         * @param right the other entry
         * @return negative when left comes first
         */
        private static int gitOrder(TreeEntry left, TreeEntry right)
        {
            byte[] a = left.name.getBytes(StandardCharsets.UTF_8);
            byte[] b = right.name.getBytes(StandardCharsets.UTF_8);
            int aPos = 0;
            int bPos = 0;
            while (aPos < a.length && bPos < b.length)
            {
                int diff = (a[aPos++] & 0xff) - (b[bPos++] & 0xff);
                if (diff != 0)
                {
                    return diff;
                }
            }
            if (aPos < a.length)
            {
                return (a[aPos] & 0xff) - directoryMark(right.mode);
            }
            if (bPos < b.length)
            {
                return directoryMark(left.mode) - (b[bPos] & 0xff);
            }
            return directoryMark(left.mode) - directoryMark(right.mode);
        }

        /**
         * The extra byte git appends to a directory name while sorting. A file has none.
         *
         * @param mode file or tree
         * @return {@code '/'} for a tree, {@code 0} for a file
         */
        private static int directoryMark(FileMode mode)
        {
            return mode == FileMode.TREE ? '/' : 0;
        }
    }

    /**
     * One name in a tree: a file blob or a child tree.
     */
    private static final class TreeEntry
    {
        private final String name;

        private final FileMode mode;

        private final ObjectId id;

        /**
         * @param name the single path segment, never a slash
         * @param mode file or tree
         * @param id the blob or the child tree
         */
        private TreeEntry(String name, FileMode mode, ObjectId id)
        {
            this.name = name;
            this.mode = mode;
            this.id = id;
        }
    }
}
