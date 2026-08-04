/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;

import ru.aiedt.mcp.server.Activator;

/**
 * Utility class for getting previous file version from VCS.
 * Tries EGit first via reflection, then falls back to Eclipse Local History.
 */
public final class GitDiffUtils
{
    private GitDiffUtils()
    {
        // Utility class
    }

    /**
     * Gets the previous version of a file from VCS.
     * Tries EGit (JGit) first via reflection, falls back to Eclipse Local History.
     *
     * @param file the workspace file
     * @param project the containing project
     * @return previous file content as String, or {@code null} if no history available
     */
    public static String getPreviousVersion(IFile file, IProject project)
    {
        // Try EGit first
        String result = getPreviousVersionViaEGit(file, project);
        if (result != null)
        {
            return result;
        }

        // Fallback to Local History
        return getPreviousVersionViaLocalHistory(file);
    }

    /**
     * Gets previous version via EGit (JGit) using reflection.
     * All JGit/EGit classes are loaded via reflection to avoid hard dependency.
     *
     * @param file the workspace file
     * @param project the containing project
     * @return previous file content, or {@code null} if EGit is not available or file has no VCS history
     */
    @SuppressWarnings("resource")
    private static String getPreviousVersionViaEGit(IFile file, IProject project)
    {
        Object revWalk = null;
        Object treeWalk = null;

        try
        {
            // RepositoryMapping.getMapping(project)
            Class<?> mappingClass = Class.forName("org.eclipse.egit.core.project.RepositoryMapping"); //$NON-NLS-1$
            Object mapping = mappingClass.getMethod("getMapping", IProject.class) //$NON-NLS-1$
                .invoke(null, project);
            if (mapping == null)
            {
                return null;
            }

            // mapping.getRepository()
            Object repo = mapping.getClass().getMethod("getRepository").invoke(mapping); //$NON-NLS-1$
            if (repo == null)
            {
                return null;
            }

            // repo.resolve("HEAD")
            Object headId = repo.getClass().getMethod("resolve", String.class) //$NON-NLS-1$
                .invoke(repo, "HEAD"); //$NON-NLS-1$
            if (headId == null)
            {
                return null;
            }

            // RevWalk revWalk = new RevWalk(repo)
            Class<?> revWalkClass = Class.forName("org.eclipse.jgit.revwalk.RevWalk"); //$NON-NLS-1$
            Class<?> repoClass = Class.forName("org.eclipse.jgit.lib.Repository"); //$NON-NLS-1$
            revWalk = revWalkClass.getConstructor(repoClass).newInstance(repo);

            // revWalk.parseCommit(headId)
            Class<?> anyObjectIdClass = Class.forName("org.eclipse.jgit.lib.AnyObjectId"); //$NON-NLS-1$
            Object commit = revWalk.getClass().getMethod("parseCommit", anyObjectIdClass) //$NON-NLS-1$
                .invoke(revWalk, headId);

            // commit.getTree()
            Object tree = commit.getClass().getMethod("getTree").invoke(commit); //$NON-NLS-1$

            // Get repo-relative path
            String relativePath = getRepoRelativePath(file, mapping);
            if (relativePath == null)
            {
                return null;
            }

            // TreeWalk.forPath(repo, relativePath, tree)
            Class<?> treeWalkClass = Class.forName("org.eclipse.jgit.treewalk.TreeWalk"); //$NON-NLS-1$
            Class<?> revTreeClass = Class.forName("org.eclipse.jgit.revwalk.RevTree"); //$NON-NLS-1$
            treeWalk = treeWalkClass.getMethod("forPath", repoClass, String.class, revTreeClass) //$NON-NLS-1$
                .invoke(null, repo, relativePath, tree);
            if (treeWalk == null)
            {
                return null;
            }

            // treeWalk.getObjectId(0)
            Object objectId = treeWalk.getClass().getMethod("getObjectId", int.class) //$NON-NLS-1$
                .invoke(treeWalk, 0);

            // repo.newObjectReader().open(objectId).getBytes()
            Object objectReader = repo.getClass().getMethod("newObjectReader").invoke(repo); //$NON-NLS-1$
            Object objectLoader = objectReader.getClass().getMethod("open", anyObjectIdClass) //$NON-NLS-1$
                .invoke(objectReader, objectId);
            byte[] bytes = (byte[])objectLoader.getClass().getMethod("getBytes").invoke(objectLoader); //$NON-NLS-1$

            String content = new String(bytes, StandardCharsets.UTF_8);

            // Strip BOM if present
            if (content.length() > 0 && content.charAt(0) == '\uFEFF')
            {
                content = content.substring(1);
            }

            return content;
        }
        catch (Exception e)
        {
            Activator.logInfo("EGit not available or failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
        finally
        {
            // Close RevWalk and TreeWalk
            closeReflective(revWalk);
            closeReflective(treeWalk);
        }
    }

    /**
     * Gets repo-relative path for a file via EGit mapping reflection.
     */
    private static String getRepoRelativePath(IFile file, Object mapping)
    {
        try
        {
            Object result = mapping.getClass()
                .getMethod("getRepoRelativePath", IResource.class) //$NON-NLS-1$
                .invoke(mapping, file);
            return result != null ? result.toString() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Gets previous version from Eclipse Local History.
     *
     * @param file the workspace file
     * @return previous file content, or {@code null} if no local history
     */
    private static String getPreviousVersionViaLocalHistory(IFile file)
    {
        try
        {
            IFileState[] history = file.getHistory(null);
            if (history == null || history.length == 0)
            {
                return null;
            }

            try (InputStream is = history[0].getContents();
                 BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8)))
            {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null)
                {
                    if (sb.length() > 0)
                    {
                        sb.append("\n"); //$NON-NLS-1$
                    }
                    sb.append(line);
                }

                String content = sb.toString();

                // Strip BOM if present
                if (content.length() > 0 && content.charAt(0) == '\uFEFF')
                {
                    content = content.substring(1);
                }

                return content;
            }
        }
        catch (Exception e)
        {
            Activator.logInfo("Local History not available: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Closes an object via reflection (for JGit RevWalk/TreeWalk).
     */
    private static void closeReflective(Object obj)
    {
        if (obj == null)
        {
            return;
        }
        try
        {
            obj.getClass().getMethod("close").invoke(obj); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            // Ignore close errors
        }
    }
}
