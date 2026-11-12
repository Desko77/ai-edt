/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

/**
 * A named refusal of a page request.
 * <p>
 * A page or continuation that cannot be served must say which of the several possible reasons
 * applies, because the caller's next step differs: repeat the original call, narrow it, or stop
 * asking for data that no longer exists. The reason travels as a stable code, so a client or a
 * later operation can branch on it without reading prose.
 * </p>
 * <p>
 * The refusal is raised rather than returned so a caller cannot forget to check it. Operations that
 * expose continuations translate it into the tool's ordinary error shape; this class deliberately
 * does not know about either.
 * </p>
 */
public final class ResultRefusal extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /** Why a continuation could not be served. */
    public enum Reason
    {
        /** The token is not one this server issued. */
        UNKNOWN_RESULT_ID,
        /** The page came from a short-form continuation, which is never rebuilt. */
        SHORT_FORM_NO_LONGER_STORED,
        /** The collection never finished, so a second collection would be a different subset. */
        INCOMPLETE_SELECTION_NO_LONGER_STORED,
        /** The call changes state; re-running it to answer a continuation is forbidden. */
        CHANGING_CALL_NOT_REPLAYED,
        /** A read collection was rebuilt, and its content no longer matches the pinned snapshot. */
        SELECTION_CHANGED,
        /** The continuation would not move forward. */
        POSITION_NOT_ADVANCING,
        /** The response ceiling cannot hold the mandatory members of even one minimal page. */
        MANDATORY_MEMBERS_EXCEED_CEILING,
        /** No way to rebuild the selection is available to the caller. */
        REBUILD_UNAVAILABLE
    }

    private final Reason reason;

    /**
     * Creates a refusal.
     *
     * @param reason which case applies
     * @param message what the caller should be told, in terms it can act on
     */
    public ResultRefusal(Reason reason, String message)
    {
        super(message);
        if (reason == null)
        {
            throw new IllegalArgumentException("a result refusal needs a reason"); //$NON-NLS-1$
        }
        this.reason = reason;
    }

    /**
     * Which case applies.
     *
     * @return the reason
     */
    public Reason reason()
    {
        return reason;
    }

    /**
     * The stable code for the reason.
     *
     * @return a snake_case code
     */
    public String code()
    {
        switch (reason)
        {
        case UNKNOWN_RESULT_ID:
            return "unknown_result_id"; //$NON-NLS-1$
        case SHORT_FORM_NO_LONGER_STORED:
            return "short_form_selection_evicted"; //$NON-NLS-1$
        case INCOMPLETE_SELECTION_NO_LONGER_STORED:
            return "incomplete_selection_evicted"; //$NON-NLS-1$
        case CHANGING_CALL_NOT_REPLAYED:
            return "changing_call_not_replayed"; //$NON-NLS-1$
        case SELECTION_CHANGED:
            return "selection_changed"; //$NON-NLS-1$
        case POSITION_NOT_ADVANCING:
            return "position_not_advancing"; //$NON-NLS-1$
        case MANDATORY_MEMBERS_EXCEED_CEILING:
            return "mandatory_members_exceed_ceiling"; //$NON-NLS-1$
        case REBUILD_UNAVAILABLE:
            return "rebuild_unavailable"; //$NON-NLS-1$
        default:
            return "refused"; //$NON-NLS-1$
        }
    }
}
