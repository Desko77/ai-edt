/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels;

import ru.aiedt.mcp.server.labels.model.Marker;

/**
 * What one edit of a project's markers did: the stored change, or why it was refused.
 * <p>
 * The boolean and marker-shaped methods of {@link MarkerManager} collapse every refusal into one
 * answer, which is all a dialog needs. A caller that reports the refusal to someone reading it -
 * the {@code tag_admin} facade over MCP - also needs the reason, so the edit runs through this
 * outcome instead. A refusal leaves the file and the live storage as they were; nothing here is
 * remembered between calls.
 * </p>
 */
public final class MarkerWriteOutcome
{
    /** A marker with that name is already defined. */
    public static final String TAG_EXISTS = "tagExists"; //$NON-NLS-1$

    /** No marker with that name is defined. */
    public static final String TAG_NOT_FOUND = "tagNotFound"; //$NON-NLS-1$

    /** Another marker already uses the requested new name. */
    public static final String NAME_TAKEN = "nameTaken"; //$NON-NLS-1$

    /** The marker file is present and does not parse. */
    public static final String UNREADABLE_FILE = "unreadableFile"; //$NON-NLS-1$

    /** The marker file cannot be written: it is read-only. */
    public static final String READ_ONLY_FILE = "readOnlyFile"; //$NON-NLS-1$

    /** The marker file changed on disk after it was read, so the edit was not written over it. */
    public static final String CHANGED_ON_DISK = "changedOnDisk"; //$NON-NLS-1$

    /** The marker file could not be read or written for another reason. */
    public static final String SAVE_FAILED = "saveFailed"; //$NON-NLS-1$

    private final Marker marker;

    private final int changedAssignments;

    private final String code;

    private final String detail;

    /**
     * @param marker the marker the edit stored or changed, or {@code null} when there is none
     * @param changedAssignments how many assignments the edit added, moved or removed
     * @param code the refusal code, or {@code null} when the edit was stored
     * @param detail a sentence for a person, never {@code null}
     */
    private MarkerWriteOutcome(Marker marker, int changedAssignments, String code, String detail)
    {
        this.marker = marker;
        this.changedAssignments = changedAssignments;
        this.code = code;
        this.detail = detail;
    }

    /**
     * An edit that was stored.
     *
     * @param marker the marker it created or changed, or {@code null} when the edit was not about one
     * @param changedAssignments how many assignments it added, moved or removed
     * @return the outcome
     */
    public static MarkerWriteOutcome stored(Marker marker, int changedAssignments)
    {
        return new MarkerWriteOutcome(marker, changedAssignments, null,
            "the marker file was written"); //$NON-NLS-1$
    }

    /**
     * An edit that was refused and changed nothing.
     *
     * @param code one of the {@code public static final String} codes of this class
     * @param detail a sentence for a person, never {@code null}
     * @return the outcome
     */
    public static MarkerWriteOutcome refused(String code, String detail)
    {
        if (code == null || detail == null)
        {
            throw new IllegalArgumentException("a refusal needs a code and a detail"); //$NON-NLS-1$
        }
        return new MarkerWriteOutcome(null, 0, code, detail);
    }

    /**
     * Tells whether the edit was refused.
     *
     * @return {@code true} when nothing was stored
     */
    public boolean isRefused()
    {
        return code != null;
    }

    /**
     * The refusal code, or {@code null} when the edit was stored.
     *
     * @return the code
     */
    public String getCode()
    {
        return code;
    }

    /**
     * A sentence describing the refusal, or what was stored.
     *
     * @return the detail, never {@code null}
     */
    public String getDetail()
    {
        return detail;
    }

    /**
     * The marker a create stored, or the marker an update changed; {@code null} otherwise.
     *
     * @return the marker, or {@code null}
     */
    public Marker getMarker()
    {
        return marker;
    }

    /**
     * How many assignments the edit added, moved or removed. A delete counts the assignments it
     * took off; an assign counts the assignments it added; an update counts the objects whose
     * assignment a rename moved.
     *
     * @return the number of changed assignments
     */
    public int getChangedAssignments()
    {
        return changedAssignments;
    }
}
