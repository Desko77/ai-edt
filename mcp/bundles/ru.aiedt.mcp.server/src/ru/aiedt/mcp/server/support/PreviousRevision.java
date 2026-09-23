/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.nio.charset.StandardCharsets;

/**
 * The revision something is compared against, together with what came of looking for it.
 *
 * <p>A comparison needs two texts, and the answer has to say what the second one is. That is the
 * whole reason this carries an outcome beside the bytes: the lookup fails in several ways that
 * look alike from the outside - no repository, no commits, the file never committed, git classes
 * absent, a read that threw - and each of them used to reach the caller as the same {@code null},
 * which a diff then reported as a new file. An outcome that is not {@link Outcome#FOUND} means
 * there is no previous text, and the answer says which one it is.</p>
 */
public final class PreviousRevision
{
    /** The byte order mark a text file may open with, U+FEFF. */
    private static final int BOM = 0xFEFF;

    /** What came of looking for the previous revision. */
    public enum Outcome
    {
        /** The previous text was read. */
        FOUND,

        /** The revision does not hold it: the module is new and nothing was written over it. */
        NOT_IN_HEAD,

        /** No repository holds the file: the project is outside one, or the path escapes it. */
        NOT_UNDER_GIT,

        /** The repository has no commits, so there is no revision to read. */
        NO_HEAD,

        /** The git classes are not on this installation. */
        NO_EGIT,

        /** A provider holds the module but does not read previous revisions. */
        PROVIDER_UNSUPPORTED,

        /** The module has no container file, so no revision of it can be asked. */
        NO_CONTAINER,

        /** Reading the revision failed; the note carries what was thrown. */
        READ_ERROR
    }

    /** Where a found revision came from. */
    public enum Origin
    {
        /** The HEAD commit of the repository. */
        GIT_HEAD("git HEAD"),

        /** Eclipse's local history of the file. */
        LOCAL_HISTORY("local history");

        private final String label;

        Origin(String label)
        {
            this.label = label;
        }

        /** @return the name the answer carries, e.g. {@code git HEAD} */
        public String label()
        {
            return label;
        }
    }

    private final Outcome outcome;

    private final Origin origin;

    private final byte[] bytes;

    private final String note;

    private PreviousRevision(Outcome outcome, Origin origin, byte[] bytes, String note)
    {
        this.outcome = outcome;
        this.origin = origin;
        this.bytes = bytes;
        this.note = note;
    }

    /**
     * A revision that was read.
     *
     * @param bytes the revision's bytes, decoded as UTF-8 with a BOM stripped by {@link #text()}
     * @param origin where it came from
     * @param note what to say about the lookup, or {@code null}
     * @return the revision
     */
    public static PreviousRevision found(byte[] bytes, Origin origin, String note)
    {
        return new PreviousRevision(Outcome.FOUND, origin, bytes, note);
    }

    /**
     * No revision: the outcome says why, and the note carries what is known about the case.
     *
     * @param outcome why there is none; never {@link Outcome#FOUND}
     * @param note the detail the answer names, or {@code null}
     * @return the revision
     */
    public static PreviousRevision missing(Outcome outcome, String note)
    {
        if (outcome == Outcome.FOUND)
        {
            throw new IllegalArgumentException("FOUND means there is a revision to read"); //$NON-NLS-1$
        }
        return new PreviousRevision(outcome, null, null, note);
    }

    /** @return what came of the lookup */
    public Outcome outcome()
    {
        return outcome;
    }

    /** @return {@code true} when there is a previous text to compare with */
    public boolean isFound()
    {
        return outcome == Outcome.FOUND;
    }

    /** @return where the revision came from, or {@code null} when there is none */
    public Origin origin()
    {
        return origin;
    }

    /** @return the name the answer carries for the source of the previous text, or {@code null} */
    public String label()
    {
        return origin == null ? null : origin.label();
    }

    /** @return the revision's bytes, or {@code null} when there is no revision */
    public byte[] bytes()
    {
        return bytes;
    }

    /**
     * The revision as text: UTF-8, with a leading byte order mark stripped - the way a module file
     * is read.
     *
     * @return the previous text, or {@code null} when there is no revision
     */
    public String text()
    {
        if (bytes == null)
        {
            return null;
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (!content.isEmpty() && content.codePointAt(0) == BOM)
        {
            content = content.substring(1);
        }
        return content;
    }

    /** @return the detail the lookup left behind, or {@code null} */
    public String note()
    {
        return note;
    }

    /**
     * The sentence an answer prints when there is nothing to compare with.
     *
     * @return the outcome named and said to leave nothing to compare with, or {@code null} when
     *         there is a revision
     */
    public String explanation()
    {
        if (isFound())
        {
            return null;
        }
        String detail = note == null || note.isEmpty() ? "" : ": " + note; //$NON-NLS-1$ //$NON-NLS-2$
        switch (outcome)
        {
            case NOT_IN_HEAD:
                return "the revision does not hold it, so it is new and nothing was written over it" //$NON-NLS-1$
                    + detail;
            case NOT_UNDER_GIT:
                return "no git repository holds it, so there is nothing to compare with" + detail; //$NON-NLS-1$
            case NO_HEAD:
                return "the repository has no commits, so there is nothing to compare with" + detail; //$NON-NLS-1$
            case NO_EGIT:
                return "the git classes are not available on this installation, so there is nothing " //$NON-NLS-1$
                    + "to compare with" + detail; //$NON-NLS-1$
            case PROVIDER_UNSUPPORTED:
                // The note here is the provider's kind, named in the middle of the sentence.
                return "the provider " + (note == null ? "" : note + " ") //$NON-NLS-1$ //$NON-NLS-2$
                    + "does not read previous revisions, so there is nothing to compare with"; //$NON-NLS-1$
            case NO_CONTAINER:
                return "the module has no container file, so no revision of it can be read" + detail; //$NON-NLS-1$
            case READ_ERROR:
                return "the previous revision could not be read" + detail; //$NON-NLS-1$
            default:
                return "there is nothing to compare with" + detail; //$NON-NLS-1$
        }
    }
}
