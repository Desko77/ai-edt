/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.repository;

/**
 * What one save of a project's clusters did.
 * <p>
 * A save either wrote the file ({@link #OK}), found nothing to change ({@link #NO_CHANGE}), or
 * refused and left the file as it was. A refusal carries one of the reason codes below.
 * {@link #NO_CHANGE} is not a refusal: the caller asked for a state the file already had.
 * </p>
 */
public final class ClusterSaveOutcome
{
    /** The file was written, or an empty set deleted it. */
    public static final String OK = "ok"; //$NON-NLS-1$

    /** The file already held the bytes that would have been written, or there was no file to delete. */
    public static final String NO_CHANGE = "noChange"; //$NON-NLS-1$

    /** The file's bytes are not the bytes this store read. */
    public static final String CHANGED_ON_DISK = "changedOnDisk"; //$NON-NLS-1$

    /** The file is not valid YAML. {@link #getDetail()} is the path of the {@code .bak} copy. */
    public static final String UNREADABLE_FILE = "unreadableFile"; //$NON-NLS-1$

    /** A file is on disk and this store has not read it. */
    public static final String NOT_READ_BY_THIS_STORE = "notReadByThisStore"; //$NON-NLS-1$

    /** The file could not be read before writing. {@link #getDetail()} is the exception text. */
    public static final String READ_FAILED = "readFailed"; //$NON-NLS-1$

    /** An exclusive lock could not be taken because the file is locked. */
    public static final String LOCK_REFUSED = "lockRefused"; //$NON-NLS-1$

    /** The file could not be opened for writing because it is read-only. */
    public static final String ACCESS_DENIED = "accessDenied"; //$NON-NLS-1$

    /**
     * The bytes could not be written. {@link #getDetail()} is the exception text.
     */
    public static final String WRITE_FAILED = "writeFailed"; //$NON-NLS-1$

    private final String code;

    private final String detail;

    private final boolean refused;

    /**
     * An outcome that wrote the file.
     *
     * @return the outcome
     */
    public static ClusterSaveOutcome ok()
    {
        return new ClusterSaveOutcome(OK, null, false);
    }

    /**
     * An outcome that changed nothing and is not a refusal.
     *
     * @return the outcome
     */
    public static ClusterSaveOutcome noChange()
    {
        return new ClusterSaveOutcome(NO_CHANGE, null, false);
    }

    /**
     * A refusal with no further text.
     *
     * @param code one of the refusal codes
     * @return the outcome
     */
    public static ClusterSaveOutcome refused(String code)
    {
        return refused(code, null);
    }

    /**
     * A refusal. {@code detail} is the {@code .bak} path for {@link #UNREADABLE_FILE} and the
     * exception text for {@link #READ_FAILED} and {@link #WRITE_FAILED}.
     *
     * @param code one of the refusal codes
     * @param detail extra text; may be {@code null}
     * @return the outcome
     */
    public static ClusterSaveOutcome refused(String code, String detail)
    {
        if (!isRefusalCode(code))
        {
            throw new IllegalArgumentException("not a cluster save refusal: " + code); //$NON-NLS-1$
        }
        return new ClusterSaveOutcome(code, detail, true);
    }

    /**
     * @param code the outcome code
     * @param detail extra text; may be {@code null}
     * @param refused whether the save left the file untouched because it refused
     */
    private ClusterSaveOutcome(String code, String detail, boolean refused)
    {
        this.code = code;
        this.detail = detail;
        this.refused = refused;
    }

    /**
     * The outcome code: {@link #OK}, {@link #NO_CHANGE}, or a refusal code.
     *
     * @return the code
     */
    public String getCode()
    {
        return code;
    }

    /**
     * Extra text carried with a refusal, or {@code null} when there is none.
     *
     * @return the {@code .bak} path or the exception text
     */
    public String getDetail()
    {
        return detail;
    }

    /**
     * Whether the file was written.
     *
     * @return {@code true} for {@link #OK}
     */
    public boolean isOk()
    {
        return OK.equals(code);
    }

    /**
     * Whether there was nothing to change. This is not a refusal.
     *
     * @return {@code true} for {@link #NO_CHANGE}
     */
    public boolean isNoChange()
    {
        return NO_CHANGE.equals(code);
    }

    /**
     * Whether the save left the file as it was because it refused.
     *
     * @return {@code true} when {@link #getCode()} is a refusal code
     */
    public boolean isRefused()
    {
        return refused;
    }

    /**
     * Whether the caller can treat the save as done: written, or already in the asked state.
     *
     * @return {@code true} for {@link #OK} and {@link #NO_CHANGE}
     */
    public boolean succeeded()
    {
        return !refused;
    }

    /**
     * A sentence naming this outcome, for a person reading a dialog.
     *
     * @return the sentence
     */
    public String explanation()
    {
        if (OK.equals(code))
        {
            return "The clusters were saved."; //$NON-NLS-1$
        }
        if (NO_CHANGE.equals(code))
        {
            return "There was nothing to change."; //$NON-NLS-1$
        }
        if (CHANGED_ON_DISK.equals(code))
        {
            return "The clusters file changed on disk after it was read."; //$NON-NLS-1$
        }
        if (UNREADABLE_FILE.equals(code))
        {
            if (detail == null)
            {
                return "The clusters file could not be read and was left unchanged."; //$NON-NLS-1$
            }
            return "The clusters file could not be read and was left unchanged. A copy is at " //$NON-NLS-1$
                + detail + "."; //$NON-NLS-1$
        }
        if (NOT_READ_BY_THIS_STORE.equals(code))
        {
            return "The clusters file is on disk and has not been read."; //$NON-NLS-1$
        }
        if (READ_FAILED.equals(code))
        {
            if (detail == null)
            {
                return "The clusters file could not be read before writing."; //$NON-NLS-1$
            }
            return "The clusters file could not be read before writing: " + detail; //$NON-NLS-1$
        }
        if (LOCK_REFUSED.equals(code))
        {
            return "The clusters file is locked by another process."; //$NON-NLS-1$
        }
        if (ACCESS_DENIED.equals(code))
        {
            return "The clusters file is read-only."; //$NON-NLS-1$
        }
        if (WRITE_FAILED.equals(code))
        {
            if (detail == null)
            {
                return "The clusters file could not be written."; //$NON-NLS-1$
            }
            return "The clusters file could not be written: " + detail; //$NON-NLS-1$
        }
        return "The clusters were not saved."; //$NON-NLS-1$
    }

    /**
     * @return the code, and the detail when there is one
     */
    @Override
    public String toString()
    {
        return detail == null ? code : code + "(" + detail + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Tells whether {@code code} is one of the refusal codes.
     *
     * @param code the code to test; may be {@code null}
     * @return {@code true} when a refusal may carry it
     */
    private static boolean isRefusalCode(String code)
    {
        return CHANGED_ON_DISK.equals(code)
            || UNREADABLE_FILE.equals(code)
            || NOT_READ_BY_THIS_STORE.equals(code)
            || READ_FAILED.equals(code)
            || LOCK_REFUSED.equals(code)
            || ACCESS_DENIED.equals(code)
            || WRITE_FAILED.equals(code);
    }
}
