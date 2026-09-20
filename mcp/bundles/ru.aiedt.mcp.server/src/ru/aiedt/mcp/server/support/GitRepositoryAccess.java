/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

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
}
