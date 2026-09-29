/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.HunkHeader;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;

import ru.aiedt.mcp.server.toolkit.ops.BslModuleAccess;

/**
 * A line diff between two revisions of a repository, and the same diff cut into methods for a
 * BSL module.
 *
 * <p>The file list and the hunks come from JGit's {@link DiffFormatter}. Line counts come from the
 * {@link EditList} of each file. Method names are read from the module text with the same header
 * patterns the module tools use; the module diff tool keeps that parse private, so the cut lives
 * here rather than reaching into it.</p>
 */
public final class GitFileDiff
{
    /** The side that is the work tree rather than a commit. */
    public static final String WORK_TREE = "WORKTREE"; //$NON-NLS-1$

    /** One changed line range, the default. */
    public static final String LINE = "line"; //$NON-NLS-1$

    /** One block per changed procedure or function. Only a {@code .bsl} file. */
    public static final String METHOD = "method"; //$NON-NLS-1$

    private GitFileDiff()
    {
        // utility
    }

    /**
     * What differed, or why the diff could not be read.
     */
    public static final class Answer
    {
        /** The refusal, or {@code null} when {@link #files} is the diff. */
        public final String error;

        /** One map per file. Empty when {@link #error} is set. */
        public final List<Map<String, Object>> files;

        private Answer(String error, List<Map<String, Object>> files)
        {
            this.error = error;
            this.files = files;
        }

        /**
         * A diff that was read.
         *
         * @param files the files, possibly empty when nothing differs
         * @return the answer
         */
        public static Answer ok(List<Map<String, Object>> files)
        {
            return new Answer(null, files);
        }

        /**
         * A diff that was not read.
         *
         * @param error what stopped it
         * @return the answer
         */
        public static Answer failure(String error)
        {
            return new Answer(error, List.of());
        }
    }

    /**
     * The files that differ between two revisions.
     *
     * <p>Hunks are included only when {@code repoPath} names one file. {@code method} granularity
     * groups those hunks by procedure and function; the caller has already refused it for any
     * other file.</p>
     *
     * @param repository the repository
     * @param fromRef the commit to read as the old side (a SHA, a branch or {@code HEAD})
     * @param toRef the commit to read as the new side, or {@link #WORK_TREE}
     * @param repoPath one work-tree-relative path, or {@code null} for every changed file
     * @param granularity {@link #LINE} or {@link #METHOD}
     * @return the files, or a refusal
     */
    public static Answer between(Repository repository, String fromRef, String toRef, String repoPath,
        String granularity)
    {
        try
        {
            return diff(repository, fromRef, toRef, repoPath, granularity);
        }
        catch (IOException e)
        {
            String message = e.getMessage();
            return Answer.failure("The diff could not be read: " //$NON-NLS-1$
                + (message == null || message.isEmpty() ? e.getClass().getName() : message));
        }
    }

    /**
     * Reads the diff. Failures of the repository leave as {@link IOException}.
     *
     * @param repository the repository
     * @param fromRef the old side
     * @param toRef the new side
     * @param repoPath one file, or {@code null}
     * @param granularity {@link #LINE} or {@link #METHOD}
     * @return the files, or a refusal when the named file is in neither side
     * @throws IOException when a revision cannot be read
     */
    private static Answer diff(Repository repository, String fromRef, String toRef, String repoPath,
        String granularity)
        throws IOException
    {
        boolean toWorkTree = WORK_TREE.equals(toRef);
        try (DiffFormatter formatter = new DiffFormatter(OutputStream.nullOutputStream()))
        {
            formatter.setRepository(repository);
            List<DiffEntry> entries = formatter.scan(iterator(repository, fromRef),
                iterator(repository, toRef));
            List<Map<String, Object>> files = new ArrayList<>();
            boolean found = false;
            for (DiffEntry entry : entries)
            {
                if (repoPath != null && !samePath(entry, repoPath))
                {
                    continue;
                }
                found = true;
                files.add(oneFile(repository, formatter, entry, toWorkTree, repoPath != null, granularity));
            }
            if (repoPath != null && !found)
            {
                if (!held(repository, fromRef, repoPath) && !held(repository, toRef, repoPath))
                {
                    return Answer.failure(repoPath + " is not in either revision."); //$NON-NLS-1$
                }
                files.add(unchanged(repoPath));
            }
            return Answer.ok(files);
        }
    }

    /**
     * One changed file: line counts always, hunks when a single file was asked for.
     *
     * @param repository the repository
     * @param formatter the formatter that produced the entry
     * @param entry the change
     * @param toWorkTree whether the new side is the work tree
     * @param includeHunks whether the answer carries hunks
     * @param granularity {@link #LINE} or {@link #METHOD}
     * @return the file map
     * @throws IOException when a blob cannot be read
     */
    private static Map<String, Object> oneFile(Repository repository, DiffFormatter formatter, DiffEntry entry,
        boolean toWorkTree, boolean includeHunks, String granularity)
        throws IOException
    {
        String path = pathOf(entry);
        FileHeader header = formatter.toFileHeader(entry);
        boolean binary = header.getPatchType() != FileHeader.PatchType.UNIFIED;
        byte[] oldBytes = blob(repository, entry.getOldId(), entry.getOldPath(), false);
        byte[] newBytes = blob(repository, entry.getNewId(), entry.getNewPath(), toWorkTree);
        EditList edits = header.toEditList();
        int added = 0;
        int removed = 0;
        if (!binary)
        {
            for (Edit edit : edits)
            {
                added += edit.getEndB() - edit.getBeginB();
                removed += edit.getEndA() - edit.getBeginA();
            }
        }
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("filePath", path); //$NON-NLS-1$
        file.put("change", changeOf(entry)); //$NON-NLS-1$
        file.put("linesAdded", Integer.valueOf(added)); //$NON-NLS-1$
        file.put("linesRemoved", Integer.valueOf(removed)); //$NON-NLS-1$
        file.put("binary", Boolean.valueOf(binary)); //$NON-NLS-1$
        if (includeHunks)
        {
            file.put("hunks", binary ? List.of() //$NON-NLS-1$
                : METHOD.equals(granularity) ? methodHunks(oldBytes, newBytes, edits)
                    : lineHunks(header, oldBytes, newBytes));
        }
        return file;
    }

    /**
     * A file that is the same on both sides, so a caller who named it still gets an answer.
     *
     * @param repoPath the file
     * @return the file map, with empty hunks
     */
    private static Map<String, Object> unchanged(String repoPath)
    {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("filePath", repoPath); //$NON-NLS-1$
        file.put("change", "unchanged"); //$NON-NLS-1$ //$NON-NLS-2$
        file.put("linesAdded", Integer.valueOf(0)); //$NON-NLS-1$
        file.put("linesRemoved", Integer.valueOf(0)); //$NON-NLS-1$
        file.put("binary", Boolean.FALSE); //$NON-NLS-1$
        file.put("hunks", List.of()); //$NON-NLS-1$
        return file;
    }

    /**
     * The unified hunks of one text file.
     *
     * @param header the file header JGit parsed
     * @param oldBytes the old side
     * @param newBytes the new side
     * @return one map per hunk
     * @throws IOException when a hunk cannot be formatted
     */
    private static List<Map<String, Object>> lineHunks(FileHeader header, byte[] oldBytes, byte[] newBytes)
        throws IOException
    {
        List<Map<String, Object>> hunks = new ArrayList<>();
        RawText oldText = new RawText(oldBytes);
        RawText newText = new RawText(newBytes);
        if (header.getHunks().isEmpty())
        {
            EditList edits = header.toEditList();
            if (!edits.isEmpty())
            {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("linesAdded", Integer.valueOf(countAdded(edits))); //$NON-NLS-1$
                one.put("linesRemoved", Integer.valueOf(countRemoved(edits))); //$NON-NLS-1$
                one.put("text", formatEdits(edits, oldText, newText)); //$NON-NLS-1$
                hunks.add(one);
            }
            return hunks;
        }
        for (HunkHeader hunk : header.getHunks())
        {
            EditList edits = hunk.toEditList();
            HunkHeader.OldImage oldImage = hunk.getOldImage();
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("oldStart", Integer.valueOf(oldImage.getStartLine())); //$NON-NLS-1$
            one.put("oldCount", Integer.valueOf(oldImage.getLineCount())); //$NON-NLS-1$
            one.put("newStart", Integer.valueOf(hunk.getNewStartLine())); //$NON-NLS-1$
            one.put("newCount", Integer.valueOf(hunk.getNewLineCount())); //$NON-NLS-1$
            one.put("linesAdded", Integer.valueOf(countAdded(edits))); //$NON-NLS-1$
            one.put("linesRemoved", Integer.valueOf(countRemoved(edits))); //$NON-NLS-1$
            one.put("text", formatEdits(edits, oldText, newText)); //$NON-NLS-1$
            hunks.add(one);
        }
        return hunks;
    }

    /**
     * One hunk per procedure or function whose text differs, named with that method.
     *
     * @param oldBytes the old side
     * @param newBytes the new side
     * @param edits the line edits of the whole file
     * @return the method hunks, in the order the methods appear
     * @throws IOException when a hunk cannot be formatted
     */
    private static List<Map<String, Object>> methodHunks(byte[] oldBytes, byte[] newBytes, EditList edits)
        throws IOException
    {
        String oldSource = new String(oldBytes, StandardCharsets.UTF_8);
        String newSource = new String(newBytes, StandardCharsets.UTF_8);
        List<MethodSpan> oldMethods = methodsOf(oldSource);
        List<MethodSpan> newMethods = methodsOf(newSource);
        List<String> names = new ArrayList<>();
        for (MethodSpan method : newMethods)
        {
            addName(names, method.name);
        }
        for (MethodSpan method : oldMethods)
        {
            addName(names, method.name);
        }
        RawText oldText = new RawText(oldBytes);
        RawText newText = new RawText(newBytes);
        List<Map<String, Object>> hunks = new ArrayList<>();
        for (String name : names)
        {
            MethodSpan older = find(oldMethods, name);
            MethodSpan newer = find(newMethods, name);
            String change = older == null ? "added" //$NON-NLS-1$
                : newer == null ? "removed" //$NON-NLS-1$
                    : slice(oldSource, older).equals(slice(newSource, newer)) ? null : "modified"; //$NON-NLS-1$
            if (change == null)
            {
                continue;
            }
            EditList subset = new EditList();
            for (Edit edit : edits)
            {
                if (overlaps(edit, older, newer))
                {
                    subset.add(edit);
                }
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("method", newer != null ? newer.name : older.name); //$NON-NLS-1$
            one.put("change", change); //$NON-NLS-1$
            one.put("linesAdded", Integer.valueOf(countAdded(subset))); //$NON-NLS-1$
            one.put("linesRemoved", Integer.valueOf(countRemoved(subset))); //$NON-NLS-1$
            one.put("text", formatEdits(subset, oldText, newText)); //$NON-NLS-1$
            hunks.add(one);
        }
        return hunks;
    }

    /**
     * Remembers a method name once, compared without regard to case.
     *
     * @param names the names already kept, in order
     * @param name the name to keep
     */
    private static void addName(List<String> names, String name)
    {
        for (String kept : names)
        {
            if (kept.equalsIgnoreCase(name))
            {
                return;
            }
        }
        names.add(name);
    }

    /**
     * The method of that name, or {@code null}.
     *
     * @param methods the methods of one side
     * @param name the name, any case
     * @return the method
     */
    private static MethodSpan find(List<MethodSpan> methods, String name)
    {
        for (MethodSpan method : methods)
        {
            if (method.name.equalsIgnoreCase(name))
            {
                return method;
            }
        }
        return null;
    }

    /**
     * Whether a line edit touches a method on either side.
     *
     * @param edit the edit, indexes zero-based and end-exclusive
     * @param older the method on the old side, or {@code null}
     * @param newer the method on the new side, or {@code null}
     * @return {@code true} when the edit and a method share a line
     */
    private static boolean overlaps(Edit edit, MethodSpan older, MethodSpan newer)
    {
        return newer != null && rangesOverlap(edit.getBeginB(), edit.getEndB(), newer.start, newer.end)
            || older != null && rangesOverlap(edit.getBeginA(), edit.getEndA(), older.start, older.end);
    }

    /**
     * Whether a zero-based half-open edit range shares a line with a one-based inclusive method.
     *
     * @param begin the first edited line, zero-based
     * @param end the line after the last edited line, zero-based
     * @param start the method's first line, one-based
     * @param methodEnd the method's last line, one-based
     * @return {@code true} when the ranges share a line
     */
    private static boolean rangesOverlap(int begin, int end, int start, int methodEnd)
    {
        if (begin == end)
        {
            // An insertion sits before the line at {@code begin}. It belongs to a method that
            // covers that point.
            return start <= begin + 1 && methodEnd >= begin;
        }
        return begin + 1 <= methodEnd && end >= start;
    }

    /**
     * The lines of a method, joined the way they were split.
     *
     * @param source the file
     * @param span the method
     * @return the method text
     */
    private static String slice(String source, MethodSpan span)
    {
        String[] lines = source.split("\n", -1); //$NON-NLS-1$
        int from = Math.max(0, span.start - 1);
        int to = Math.min(lines.length, span.end);
        StringBuilder body = new StringBuilder();
        for (int i = from; i < to; i++)
        {
            if (i > from)
            {
                body.append('\n');
            }
            body.append(lines[i]);
        }
        return body.toString();
    }

    /**
     * Procedures and functions in the order they appear. A header with no end is left out.
     *
     * @param source the module
     * @return the methods
     */
    private static List<MethodSpan> methodsOf(String source)
    {
        List<MethodSpan> methods = new ArrayList<>();
        String[] lines = source.split("\n", -1); //$NON-NLS-1$
        MethodSpan current = null;
        for (int i = 0; i < lines.length; i++)
        {
            String line = lines[i];
            Matcher start = BslModuleAccess.METHOD_START_PATTERN.matcher(line);
            if (start.find())
            {
                current = new MethodSpan();
                current.name = start.group(1);
                current.start = i + 1;
            }
            else if (current != null && BslModuleAccess.METHOD_END_PATTERN.matcher(line).find())
            {
                current.end = i + 1;
                methods.add(current);
                current = null;
            }
        }
        return methods;
    }

    /**
     * The unified text of a set of edits.
     *
     * @param edits the edits, possibly empty
     * @param oldText the old side
     * @param newText the new side
     * @return the text, empty when there are no edits
     * @throws IOException when the formatter cannot write
     */
    private static String formatEdits(EditList edits, RawText oldText, RawText newText) throws IOException
    {
        if (edits.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(buffer))
        {
            formatter.format(edits, oldText, newText);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * Lines inserted by the edits.
     *
     * @param edits the edits
     * @return the count
     */
    private static int countAdded(EditList edits)
    {
        int added = 0;
        for (Edit edit : edits)
        {
            added += edit.getEndB() - edit.getBeginB();
        }
        return added;
    }

    /**
     * Lines deleted by the edits.
     *
     * @param edits the edits
     * @return the count
     */
    private static int countRemoved(EditList edits)
    {
        int removed = 0;
        for (Edit edit : edits)
        {
            removed += edit.getEndA() - edit.getBeginA();
        }
        return removed;
    }

    /**
     * A tree iterator for a commit, or the work tree.
     *
     * @param repository the repository
     * @param rev a commit-ish, or {@link #WORK_TREE}
     * @return the iterator
     * @throws IOException when the commit does not resolve or cannot be read
     */
    private static AbstractTreeIterator iterator(Repository repository, String rev) throws IOException
    {
        if (WORK_TREE.equals(rev))
        {
            return new FileTreeIterator(repository);
        }
        ObjectId id = repository.resolve(rev);
        if (id == null)
        {
            throw new IOException("commit '" + rev + "' resolves to nothing"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try (RevWalk walk = new RevWalk(repository); ObjectReader reader = repository.newObjectReader())
        {
            CanonicalTreeParser parser = new CanonicalTreeParser();
            parser.reset(reader, walk.parseCommit(id).getTree());
            return parser;
        }
    }

    /**
     * Whether a revision holds the path as a file.
     *
     * @param repository the repository
     * @param rev a commit-ish, or {@link #WORK_TREE}
     * @param repoPath the work-tree-relative path
     * @return {@code true} when the file is there
     * @throws IOException when the commit cannot be read
     */
    private static boolean held(Repository repository, String rev, String repoPath) throws IOException
    {
        if (WORK_TREE.equals(rev))
        {
            return Files.isRegularFile(disk(repository, repoPath));
        }
        return GitDiffUtils.revisionAt(repository, repoPath, rev).isFound();
    }

    /**
     * The blob, from the object database when it is stored there and from the work tree otherwise.
     *
     * @param repository the repository
     * @param id the blob id, possibly incomplete or absent
     * @param path the path, or {@code /dev/null}
     * @param preferWorkTree whether to read the work tree before the object database
     * @return the bytes, empty when that side has no file
     * @throws IOException when a stored blob cannot be read
     */
    private static byte[] blob(Repository repository, AbbreviatedObjectId id, String path, boolean preferWorkTree)
        throws IOException
    {
        if (path == null || DiffEntry.DEV_NULL.equals(path))
        {
            return new byte[0];
        }
        if (preferWorkTree)
        {
            java.nio.file.Path file = disk(repository, path);
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0];
        }
        if (id != null && id.isComplete())
        {
            return repository.open(id.toObjectId()).getBytes();
        }
        return new byte[0];
    }

    /**
     * The work-tree file for a repository path.
     *
     * @param repository the repository
     * @param repoPath the path, with forward slashes
     * @return the file
     */
    private static java.nio.file.Path disk(Repository repository, String repoPath)
    {
        java.nio.file.Path file = repository.getWorkTree().toPath();
        for (String segment : repoPath.split("/")) //$NON-NLS-1$
        {
            file = file.resolve(segment);
        }
        return file;
    }

    /**
     * The path a change is about: the new path, or the old one when the file was deleted.
     *
     * @param entry the change
     * @return the path
     */
    private static String pathOf(DiffEntry entry)
    {
        String path = entry.getNewPath();
        if (path == null || DiffEntry.DEV_NULL.equals(path))
        {
            path = entry.getOldPath();
        }
        return path;
    }

    /**
     * Whether the entry is the file that was asked for.
     *
     * @param entry the change
     * @param repoPath the path
     * @return {@code true} when either side is that path
     */
    private static boolean samePath(DiffEntry entry, String repoPath)
    {
        return repoPath.equals(entry.getOldPath()) || repoPath.equals(entry.getNewPath());
    }

    /**
     * The kind of change, in the words the rest of the git answers use.
     *
     * @param entry the change
     * @return {@code added}, {@code deleted}, {@code renamed}, {@code copied} or {@code modified}
     */
    private static String changeOf(DiffEntry entry)
    {
        switch (entry.getChangeType())
        {
        case ADD:
            return "added"; //$NON-NLS-1$
        case DELETE:
            return "deleted"; //$NON-NLS-1$
        case RENAME:
            return "renamed"; //$NON-NLS-1$
        case COPY:
            return "copied"; //$NON-NLS-1$
        default:
            return "modified"; //$NON-NLS-1$
        }
    }

    /**
     * A procedure or function and the lines it occupies, one-based and inclusive.
     */
    private static final class MethodSpan
    {
        private String name;

        private int start;

        private int end;
    }
}
