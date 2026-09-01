/**
 * AI-EDT - 1C AI tools for EDT
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import ru.aiedt.mcp.server.Activator;

/**
 * The snapshots of support modes a project has accumulated, and what may be removed.
 * <p>
 * Every successful merge leaves one - around a megabyte and a half on a configuration of nine
 * thousand objects - and nothing ever removed them: five were lying in one project before an
 * experiment on 25.08. They are not litter, though. A snapshot is the only way to put the support
 * model back, so the question is not how to delete them but which one may go.
 * </p>
 * <p>
 * <b>Unknown counts as protected.</b> A file that cannot be read, or that carries no legible mark,
 * is left alone. The alternative reading - "no mark, so ordinary" - would delete exactly the file a
 * crash left half-published, which is the one case where the snapshot matters most.
 * </p>
 */
public final class SupportSnapshotStore
{
    /** How many ordinary snapshots a project keeps. Protected ones are not counted. */
    public static final int KEPT = 3;

    private static final String PREFIX = "aiedt-support-snapshot-"; //$NON-NLS-1$

    private static final String SUFFIX = ".tsv"; //$NON-NLS-1$

    private SupportSnapshotStore()
    {
    }

    /**
     * The snapshots a project holds, newest name first.
     * <p>
     * Ordered by file name rather than by modification time: the name carries the moment the
     * snapshot was taken, and a copy or a restore rewrites the timestamp while leaving the name
     * alone.
     * </p>
     *
     * @param settings the project's settings directory.
     * @return the snapshot files, or an empty list when there are none or the directory cannot be
     *         read
     */
    public static List<Path> list(Path settings)
    {
        List<Path> found = new ArrayList<>();
        if (settings == null || !Files.isDirectory(settings))
        {
            return found;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(settings, PREFIX + "*" + SUFFIX)) //$NON-NLS-1$
        {
            for (Path entry : entries)
            {
                if (isSnapshotName(entry.getFileName().toString()))
                {
                    found.add(entry);
                }
            }
        }
        catch (IOException | RuntimeException cannotList)
        {
            Activator.logWarning("support snapshots could not be listed in " + settings //$NON-NLS-1$
                + ": " + cannotList.getMessage()); //$NON-NLS-1$
            return new ArrayList<>();
        }
        found.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return found;
    }

    /**
     * Whether the file name is a merge snapshot rather than something named after one.
     * <p>
     * <b>The pattern alone is not enough.</b> Restoring support modes writes its undo record beside
     * the snapshot it restored from, as {@code <snapshot>.before-restore.tsv} - which begins with
     * the same prefix and ends with the same extension. That record is the only way to reverse a
     * restore, and cleanup counting it as an ordinary snapshot would delete exactly that.
     * </p>
     * <p>
     * A snapshot's own name carries one dot, the one before the extension. Anything with more has
     * been named after a snapshot by something else.
     * </p>
     *
     * @param fileName the file's name, without a directory.
     * @return <code>true</code> when this is a merge snapshot
     */
    static boolean isSnapshotName(String fileName)
    {
        if (fileName == null || !fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX))
        {
            return false;
        }
        String stamp = fileName.substring(PREFIX.length(), fileName.length() - SUFFIX.length());
        return !stamp.isEmpty() && stamp.indexOf('.') < 0;
    }

    /**
     * Whether this snapshot must survive cleanup.
     *
     * @param file the snapshot.
     * @return <code>true</code> when it is marked, and when it cannot be read at all
     */
    public static boolean isProtected(Path file)
    {
        if (file == null)
        {
            return false;
        }
        try
        {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
            {
                if (SupportSnapshot.PROTECTED.equals(line.trim()))
                {
                    return true;
                }
                if (!line.startsWith("#")) //$NON-NLS-1$
                {
                    // The marks live in the header. Past it there is nothing left to find, and
                    // reading a megabyte of rows to learn that costs more than it tells.
                    return false;
                }
            }
            return false;
        }
        catch (IOException | RuntimeException cannotRead)
        {
            // Unreadable is not "ordinary". A file a crash left mid-publish reads like this, and it
            // is the one that must not be removed.
            Activator.logWarning("support snapshot " + file + " could not be read, so it is " //$NON-NLS-1$ //$NON-NLS-2$
                + "treated as protected: " + cannotRead.getMessage()); //$NON-NLS-1$
            return true;
        }
    }

    /** The snapshots of this project that cleanup will not touch. */
    public static List<Path> protectedIn(Path settings)
    {
        List<Path> held = new ArrayList<>();
        for (Path file : list(settings))
        {
            if (isProtected(file))
            {
                held.add(file);
            }
        }
        return held;
    }

    /**
     * Takes the protection off one snapshot, so it becomes an ordinary one.
     * <p>
     * <b>Written aside and moved into place, and refused when the move cannot be atomic.</b> This
     * rewrites the one file that can put the support model back. Publishing a NEW snapshot tolerates
     * a non-atomic move - either the whole new file arrives or the old state stays, and refusing
     * there would refuse every merge on such a volume. Here the trade is the other way round: a
     * non-atomic replace that fails part-way destroys the only copy, and answering "unreadable
     * counts as protected" does not bring the rows back.
     * </p>
     *
     * @param file the snapshot to release.
     * @return <code>null</code> when it is now ordinary, or the reason it is still protected
     */
    public static String clearProtection(Path file)
    {
        if (file == null || !Files.isRegularFile(file))
        {
            return "there is no snapshot at " + file; //$NON-NLS-1$
        }
        if (!isProtected(file))
        {
            return null;
        }
        Path staging = file.resolveSibling(file.getFileName() + ".writing"); //$NON-NLS-1$
        try
        {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> without = new ArrayList<>(lines.size());
            for (String line : lines)
            {
                if (!SupportSnapshot.PROTECTED.equals(line.trim()))
                {
                    without.add(line);
                }
            }
            Files.write(staging, without, StandardCharsets.UTF_8);
            try
            {
                Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException noAtomicMove)
            {
                Files.deleteIfExists(staging);
                return "this volume does not promise an atomic replace, and replacing the only " //$NON-NLS-1$
                    + "snapshot without one can destroy it. The snapshot stays protected; remove " //$NON-NLS-1$
                    + "it by hand once the merge it belongs to has been dealt with."; //$NON-NLS-1$
            }
            return null;
        }
        catch (IOException | RuntimeException cannotRewrite)
        {
            try
            {
                Files.deleteIfExists(staging);
            }
            catch (IOException | RuntimeException leftBehind)
            {
                Activator.logWarning("a staging file was left at " + staging + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + leftBehind.getMessage());
            }
            return "the snapshot could not be rewritten, so it stays protected: " //$NON-NLS-1$
                + cannotRewrite.getMessage();
        }
    }

    /**
     * Removes the ordinary snapshots a project no longer needs.
     * <p>
     * Runs after a merge has settled and at start-up, never before a snapshot is taken: cleaning
     * ahead of a merge would remove the previous way back for a merge that then refuses before
     * writing anything, leaving the project with neither.
     * </p>
     *
     * @param settings the project's settings directory.
     * @param keep how many ordinary snapshots to leave, newest first.
     * @return how many were removed
     */
    public static int prune(Path settings, int keep)
    {
        int removed = 0;
        int seen = 0;
        for (Path file : list(settings))
        {
            if (isProtected(file))
            {
                continue;
            }
            seen++;
            if (seen <= keep)
            {
                continue;
            }
            try
            {
                Files.deleteIfExists(file);
                removed++;
            }
            catch (IOException | RuntimeException staysOnDisk)
            {
                Activator.logWarning("support snapshot " + file + " could not be removed: " //$NON-NLS-1$ //$NON-NLS-2$
                    + staysOnDisk.getMessage());
            }
        }
        return removed;
    }
}
