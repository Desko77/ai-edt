/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.Activator;

/**
 * What an export changed on disk, by reading the files before and after.
 * <p>
 * An export writes the model over whatever is in the object's directory. A file edited there by
 * hand is replaced, and the answer said nothing about it - so an edit made to work around something
 * disappeared without a word, and the next read showed the model's version as though the edit had
 * never happened.
 * </p>
 * <p>
 * Files are compared by content rather than by timestamp: an export rewrites every file it owns, so
 * timestamps move whether or not anything is different, and a list of everything would say as
 * little as a list of nothing.
 * </p>
 */
public final class ExportedFiles
{
    /** Above this a file is described by its length and timestamp rather than by its content. */
    private static final long TOO_BIG_TO_HASH = 16L * 1024 * 1024;

    /** Stands for a file that is there and could not be read, which is neither absent nor equal. */
    private static final String NOT_READ = "?"; //$NON-NLS-1$

    private ExportedFiles() {}

    /**
     * What changed between two readings of the same directories.
     *
     * @param written paths whose content is different from before.
     * @param created paths that were not there before.
     * @param removed paths that were there before and are gone.
     */
    public record Changes(List<String> written, List<String> created, List<String> removed)
    {
        /** @return whether anything at all differs */
        public boolean any()
        {
            return !written.isEmpty() || !created.isEmpty() || !removed.isEmpty();
        }
    }

    /**
     * A digest of every file under the given objects' directories.
     *
     * @param project the project whose {@code src} holds them.
     * @param objects top-object FQNs.
     * @return path relative to the project, mapped to a digest of its content; empty when the
     *         directories cannot be read, which is reported as no comparison rather than as no
     *         change
     */
    public static Map<String, String> snapshot(IProject project, List<String> objects)
    {
        Map<String, String> digests = new LinkedHashMap<>();
        if (project == null || project.getLocation() == null || objects == null)
        {
            return digests;
        }
        Path root = project.getLocation().toFile().toPath();
        Path source = root.resolve("src"); //$NON-NLS-1$
        List<Path> directories = new ArrayList<>();
        for (String fqn : objects)
        {
            String relative = BmComparisonHelper.objectDirectoryOf(fqn);
            if (relative != null)
            {
                directories.add(source.resolve(relative));
            }
        }
        digests.putAll(underDirectories(root, directories));
        return digests;
    }

    /**
     * The objects whose directory this cannot place, so the caller knows the report leaves them out.
     * <p>
     * A directory is worked out from the two parts of an FQN. A name of one part - the configuration
     * root - has none, and so contributes nothing to the comparison; saying which is the difference
     * between a report that covers everything asked for and one that quietly covers less.
     * </p>
     *
     * @param objects the FQNs the call named.
     * @return those with no directory, in the order given
     */
    public static List<String> notPlaced(List<String> objects)
    {
        List<String> unplaced = new ArrayList<>();
        if (objects == null)
        {
            return unplaced;
        }
        for (String fqn : objects)
        {
            if (BmComparisonHelper.objectDirectoryOf(fqn) == null)
            {
                unplaced.add(fqn);
            }
        }
        return unplaced;
    }

    /**
     * A digest of every file under the given directories, keyed by its path relative to a root.
     *
     * @param root what the keys are relative to.
     * @param directories the directories to read; one that is not there is skipped.
     * @return path to digest, in the order the files were read
     */
    public static Map<String, String> underDirectories(Path root, List<Path> directories)
    {
        Map<String, String> digests = new LinkedHashMap<>();
        if (root == null || directories == null)
        {
            return digests;
        }
        for (Path directory : directories)
        {
            if (directory != null && Files.isDirectory(directory))
            {
                readInto(digests, root, directory);
            }
        }
        return digests;
    }

    /**
     * The difference between two readings.
     *
     * @param before the earlier reading.
     * @param now the later one.
     * @return the three lists, each sorted
     */
    public static Changes between(Map<String, String> before, Map<String, String> now)
    {
        List<String> written = new ArrayList<>();
        List<String> created = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, String> entry : now.entrySet())
        {
            String was = before.get(entry.getKey());
            if (was == null)
            {
                created.add(entry.getKey());
            }
            else if (NOT_READ.equals(was) || NOT_READ.equals(entry.getValue()))
            {
                // One side could not be read. Saying nothing about this file beats saying it
                // changed when that is not known.
                continue;
            }
            else if (!was.equals(entry.getValue()))
            {
                written.add(entry.getKey());
            }
        }
        for (String path : before.keySet())
        {
            if (!now.containsKey(path))
            {
                removed.add(path);
            }
        }
        written.sort(null);
        created.sort(null);
        removed.sort(null);
        return new Changes(written, created, removed);
    }

    /**
     * The difference between an earlier snapshot and the state now.
     *
     * @param before what {@link #snapshot} returned earlier.
     * @param project the project.
     * @param objects the same objects.
     * @return the three lists, each sorted; empty lists when the earlier snapshot was empty, since
     *         a comparison against nothing states nothing
     */
    public static Changes since(Map<String, String> before, IProject project, List<String> objects)
    {
        Map<String, String> earlier = before == null ? new LinkedHashMap<>() : before;
        return between(earlier, snapshot(project, objects));
    }

    private static void readInto(Map<String, String> digests, Path root, Path directory)
    {
        try (Stream<Path> walk = Files.walk(directory))
        {
            // Iterated rather than collected: an object directory can hold a few thousand files,
            // and a list of them all exists only to be walked once.
            walk.filter(Files::isRegularFile).forEach(file -> digests
                .put(root.relativize(file).toString().replace('\\', '/'), digestOf(file)));
        }
        catch (IOException | RuntimeException e)
        {
            // One unreadable directory leaves that object out of the comparison rather than
            // failing the export it is only describing.
            Activator.logDebug("resync: could not read " + directory + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * A digest of one file's content.
     * <p>
     * Read in blocks: a whole file at once is one allocation the size of the file, and a template
     * carrying images runs to tens of megabytes. Above {@link #TOO_BIG_TO_HASH} the content is not
     * read at all and the file is described by its length and its timestamp instead, which says
     * "different" for a file that grew or was rewritten and costs nothing to obtain.
     * </p>
     *
     * @param file the file.
     * @return the digest, or {@link #NOT_READ} when it could not be read
     */
    private static String digestOf(Path file)
    {
        try
        {
            long size = Files.size(file);
            if (size > TOO_BIG_TO_HASH)
            {
                return "size:" + size + ":" + Files.getLastModifiedTime(file).toMillis(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            byte[] block = new byte[8192];
            try (InputStream in = Files.newInputStream(file))
            {
                int read;
                while ((read = in.read(block)) > 0)
                {
                    digest.update(block, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder text = new StringBuilder(hash.length * 2);
            for (byte b : hash)
            {
                text.append(Character.forDigit((b >> 4) & 0xF, 16));
                text.append(Character.forDigit(b & 0xF, 16));
            }
            return text.toString();
        }
        catch (Exception e)
        {
            // Recorded rather than left out: a file left out of one reading and present in the
            // other would be reported as created or removed, which it is not.
            Activator.logDebug("resync: could not read " + file + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return NOT_READ;
        }
    }
}
