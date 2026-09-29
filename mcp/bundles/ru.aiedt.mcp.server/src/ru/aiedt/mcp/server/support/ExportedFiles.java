/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
     * @return path relative to the project, mapped to a digest of its content; a directory that is
     *         not there contributes nothing, and one that is there but could not be read is marked
     *         by a single entry for the directory itself, so the comparison says nothing about the
     *         files under it rather than naming them removed
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
     * <p>
     * A directory that is not there is skipped: it has no files, and nothing about it is uncertain.
     * One that is there but cannot be read - or whose attributes cannot even be asked for, which
     * {@code Files.isDirectory} answers with the same {@code false} as an absent path - is marked
     * as unread instead, because its files are unknown rather than absent.
     * </p>
     *
     * @param root what the keys are relative to.
     * @param directories the directories to read; one that is not there is skipped, and one that
     *                    is there but could not be read is marked as unread instead
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
            if (directory == null)
            {
                continue;
            }
            try
            {
                if (!Files.readAttributes(directory, BasicFileAttributes.class).isDirectory())
                {
                    // A file where a directory was named: it holds no objects, and the files
                    // beside it are not this directory's to report.
                    continue;
                }
            }
            catch (NoSuchFileException absent)
            {
                continue;
            }
            catch (IOException | RuntimeException unreadable)
            {
                // Present and unreadable, or not even statable - which of the two it is cannot be
                // told from here, and the difference is the whole point: an absent directory
                // contributes nothing, while an unread one may hold anything. Marked, so the
                // comparison keeps quiet about everything under it.
                digests.put(keyOf(root, directory), NOT_READ);
                Activator.logDebug("resync: could not read the state of " + directory + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + unreadable);
                continue;
            }
            readInto(digests, root, directory);
        }
        return digests;
    }

    /**
     * The difference between two readings.
     * <p>
     * A reading that could not see a directory marks it rather than leaving it out, and the files
     * under such a directory are named neither created nor removed: one side said nothing about
     * them, which is not the same as saying they are not there.
     * </p>
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
        List<String> unknownBefore = unknownDirectories(before);
        List<String> unknownNow = unknownDirectories(now);
        for (Map.Entry<String, String> entry : now.entrySet())
        {
            if (NOT_READ.equals(entry.getValue()))
            {
                // The entry is a directory that could not be read. It is not a file that appeared.
                continue;
            }
            String was = before.get(entry.getKey());
            if (was == null)
            {
                if (!underAny(entry.getKey(), unknownBefore))
                {
                    created.add(entry.getKey());
                }
            }
            else if (NOT_READ.equals(was))
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
            if (NOT_READ.equals(before.get(path)))
            {
                continue;
            }
            if (!now.containsKey(path) && !underAny(path, unknownNow))
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
     * @return the three lists, each sorted; an empty earlier reading names every file the later
     *         one found as created, since the export put them where nothing was
     */
    public static Changes since(Map<String, String> before, IProject project, List<String> objects)
    {
        Map<String, String> earlier = before == null ? new LinkedHashMap<>() : before;
        return between(earlier, snapshot(project, objects));
    }

    /**
     * The directories a reading marked as unread.
     *
     * @param reading one side of a comparison.
     * @return the keys that stand for a directory that could not be read
     */
    private static List<String> unknownDirectories(Map<String, String> reading)
    {
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, String> entry : reading.entrySet())
        {
            if (NOT_READ.equals(entry.getValue()))
            {
                unknown.add(entry.getKey());
            }
        }
        return unknown;
    }

    /**
     * Whether a path sits under one of the unread directories.
     *
     * @param path the path, with forward separators.
     * @param directories the unread directory keys.
     * @return whether the directory covers the path
     */
    private static boolean underAny(String path, List<String> directories)
    {
        for (String directory : directories)
        {
            if (path.startsWith(directory + "/")) //$NON-NLS-1$
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds the files under one directory to a reading, by digest.
     * <p>
     * The directory itself is not checked first: this is called once its being there has already
     * been established, and the reading it writes into belongs to the caller. A directory that
     * cannot be walked is marked as unread rather than left out, and a file inside it that cannot
     * be read is marked per file by {@link #digestOf}.
     * </p>
     *
     * @param digests the reading to add to, keyed relative to the root.
     * @param root what the keys are relative to.
     * @param directory the directory to read; a failure here marks the directory, not each file
     */
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
            // A directory that is there and could not be read is not one that is not there: its
            // files are unknown, not absent, and a reading that said nothing about them would have
            // the comparison call them removed. The directory itself is marked, and the comparison
            // keeps quiet about everything under it.
            digests.put(keyOf(root, directory), NOT_READ);
            Activator.logDebug("resync: could not read " + directory + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * The key a directory is known by in a reading.
     *
     * @param root what the keys are relative to.
     * @param directory the directory.
     * @return the relative key, or the absolute path when the directory does not sit under the
     *         root and no relative key exists
     */
    private static String keyOf(Path root, Path directory)
    {
        try
        {
            return root.relativize(directory).toString().replace('\\', '/');
        }
        catch (IllegalArgumentException notUnderRoot)
        {
            return directory.toAbsolutePath().toString().replace('\\', '/');
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
