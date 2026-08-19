/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.core.resources.IProject;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import ru.aiedt.mcp.server.Activator;

/**
 * Which infobase belongs to which branch, remembered per project.
 * <p>
 * The problem it exists for is one-directional and expensive. Switching a branch changes the
 * metadata; updating the infobase then restructures it to match, and restructuring is not a thing
 * you undo. Someone who switches to a colleague's branch and runs the update they always run has
 * just rebuilt the tables of the infobase they were using for something else - and nothing warned
 * them, because from the tool's side nothing unusual happened.
 * </p>
 * <p>
 * So the binding is a note to that effect, kept beside the project in {@code .settings} like the
 * markers and the clusters, and committed or not as the team prefers. It is deliberately not an
 * automatic switch: choosing an infobase for somebody is a bigger promise than this can keep, and
 * the failure mode of getting it wrong is the very thing being guarded against.
 * </p>
 */
public final class BranchInfobaseBook
{
    /** The file bindings live in, beside the other things this plugin keeps per project. */
    public static final String FILE = "aiedt-branch-infobases.yaml"; //$NON-NLS-1$

    private static final String SETTINGS = ".settings"; //$NON-NLS-1$

    private static final String ROOT = "bindings"; //$NON-NLS-1$

    /**
     * Files that exist but could not be read, remembered from the last read of each.
     * <p>
     * A set rather than a return value because {@link #all} is called from places that want the
     * bindings and nothing else. What it buys is the distinction between "no rules" and "rules that
     * cannot be read", which a guard in front of a destructive operation must not confuse.
     * </p>
     */
    private static final java.util.Set<String> UNREADABLE =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private BranchInfobaseBook()
    {
    }

    /**
     * Every binding a project holds.
     *
     * @param project the project, possibly {@code null}.
     * @return branch to application id, sorted by branch; empty when there are none
     */
    public static Map<String, String> all(IProject project)
    {
        return read(fileOf(project));
    }

    /**
     * Every binding held under a path, for callers that have no workspace project - the tests.
     *
     * @param projectDirectory the project directory.
     * @return branch to application id
     */
    public static Map<String, String> allAt(Path projectDirectory)
    {
        return read(projectDirectory == null ? null : projectDirectory.resolve(SETTINGS).resolve(FILE));
    }

    /**
     * Whether a project's bindings could be read the last time they were asked for.
     *
     * @param project the project.
     * @return {@code false} when the file is there but unreadable - the one state in which an empty
     *         answer from {@link #all} means nothing
     */
    public static boolean readable(IProject project)
    {
        Path file = fileOf(project);
        if (file == null)
        {
            return true;
        }
        all(project);
        return !UNREADABLE.contains(file.toString());
    }

    /**
     * The application bound to one branch.
     *
     * @param project the project.
     * @param branch the branch name.
     * @return the application id, or {@code null} when that branch is not spoken for
     */
    public static String boundTo(IProject project, String branch)
    {
        if (branch == null || branch.isEmpty())
        {
            return null;
        }
        return all(project).get(branch);
    }

    /**
     * Binds a branch to an application.
     *
     * @param project the project.
     * @param branch the branch name.
     * @param applicationId the application to use on it.
     * @return {@code null} on success, or why it could not be written
     */
    public static String bind(IProject project, String branch, String applicationId)
    {
        if (branch == null || branch.isEmpty() || applicationId == null || applicationId.isEmpty())
        {
            return "a binding needs both a branch and an applicationId"; //$NON-NLS-1$
        }
        if (project == null || project.getLocation() == null)
        {
            return "the project has no location on disk, so nothing can be remembered about it"; //$NON-NLS-1$
        }
        return bindAt(project.getLocation().toFile().toPath(), branch, applicationId);
    }

    /**
     * Binds a branch under a directory, for callers holding a path rather than a project.
     *
     * @param projectDirectory the project directory.
     * @param branch the branch name.
     * @param applicationId the application to use on it.
     * @return {@code null} on success, or why it could not be written
     */
    public static synchronized String bindAt(Path projectDirectory, String branch, String applicationId)
    {
        if (projectDirectory == null)
        {
            return "no project directory"; //$NON-NLS-1$
        }
        if (branch == null || branch.isEmpty() || applicationId == null || applicationId.isEmpty())
        {
            return "a binding needs both a branch and an applicationId"; //$NON-NLS-1$
        }
        Path file = projectDirectory.resolve(SETTINGS).resolve(FILE);
        Map<String, String> bindings = read(file);
        bindings.put(branch, applicationId);
        return write(file, bindings);
    }

    /**
     * Removes a binding.
     *
     * @param project the project.
     * @param branch the branch to forget.
     * @return {@code null} on success, or why it could not be written
     */
    public static String unbind(IProject project, String branch)
    {
        if (project == null || project.getLocation() == null)
        {
            return "the project has no location on disk"; //$NON-NLS-1$
        }
        return unbindAt(project.getLocation().toFile().toPath(), branch);
    }

    /**
     * Removes a binding under a directory.
     *
     * @param projectDirectory the project directory.
     * @param branch the branch to forget.
     * @return {@code null} on success or when there was nothing to remove, or why it failed
     */
    public static synchronized String unbindAt(Path projectDirectory, String branch)
    {
        if (projectDirectory == null)
        {
            return "no project directory"; //$NON-NLS-1$
        }
        Path file = projectDirectory.resolve(SETTINGS).resolve(FILE);
        Map<String, String> bindings = read(file);
        if (bindings.remove(branch) == null)
        {
            return null;
        }
        return write(file, bindings);
    }

    /**
     * @param project the project.
     * @return where its bindings are kept, or {@code null} when it has no location
     */
    private static Path fileOf(IProject project)
    {
        if (project == null || project.getLocation() == null)
        {
            return null;
        }
        return project.getLocation().toFile().toPath().resolve(SETTINGS).resolve(FILE);
    }

    /**
     * Reads the bindings.
     *
     * @param file where they are, possibly {@code null} or absent.
     * @return a mutable, branch-sorted map; empty rather than {@code null} for anything unreadable
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> read(Path file)
    {
        Map<String, String> bindings = new TreeMap<>();
        if (file == null || !Files.isRegularFile(file))
        {
            return bindings;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
        {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            if (!(loaded instanceof Map))
            {
                return bindings;
            }
            Object root = ((Map<String, Object>)loaded).get(ROOT);
            if (!(root instanceof Map))
            {
                return bindings;
            }
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>)root).entrySet())
            {
                // Both halves must be text. String.valueOf on a nested map or a list produces a
                // key nothing can ever match, which is a binding that silently never fires.
                if (entry.getKey() instanceof String && entry.getValue() instanceof String)
                {
                    bindings.put((String)entry.getKey(), (String)entry.getValue());
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            // Remembered, not swallowed. An unreadable file and an absent one used to be the same
            // answer - no bindings - which turned "somebody's notes got mangled" into "this project
            // has no rules", and switched the guard off at exactly the moment its state was least
            // trustworthy. The caller decides what to do; see readable().
            Activator.logWarning("Could not read " + FILE + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            UNREADABLE.add(file.toString());
            return bindings;
        }
        UNREADABLE.remove(file.toString());
        return bindings;
    }

    /**
     * Writes the bindings.
     *
     * @param file where to put them.
     * @param bindings what to write.
     * @return {@code null} on success, or the reason it failed
     */
    private static String write(Path file, Map<String, String> bindings)
    {
        try
        {
            Files.createDirectories(file.getParent());
            Map<String, Object> document = new LinkedHashMap<>();
            document.put(ROOT, new LinkedHashMap<>(bindings));
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            // Written beside the target and moved over it. Writing in place truncates first, so a
            // reader arriving mid-write saw an empty file - and an empty file is "no bindings",
            // which is the guard switched off for as long as the write takes.
            Path pending = Files.createTempFile(file.getParent(), "bindings-", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                try (Writer writer = Files.newBufferedWriter(pending, StandardCharsets.UTF_8))
                {
                    new Yaml(options).dump(document, writer);
                }
                Files.move(pending, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException | RuntimeException failed)
            {
                Files.deleteIfExists(pending);
                throw failed;
            }
            UNREADABLE.remove(file.toString());
            return null;
        }
        catch (IOException | RuntimeException e)
        {
            return "could not write " + FILE + ": " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
