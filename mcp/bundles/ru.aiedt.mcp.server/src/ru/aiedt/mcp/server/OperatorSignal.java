/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.wire.GsonHolder;

/**
 * Something the user wants to tell the agent while it is working.
 * <p>
 * The user raises a signal from the status bar - typically because a tool call is taking too long,
 * or because they can see it is going nowhere. The signal reaches the agent either as the answer to
 * the call it is waiting on, or folded into the answer of the call it makes next. Either way it is
 * addressed to the <em>agent</em>: whatever text this object carries is what the agent reads.
 * </p>
 * <p>
 * A signal does not stop any work. The 1C:EDT operation behind a tool call runs to completion
 * whatever the user says; the signal only frees the agent from waiting for it. The wording of the
 * default messages says so, and it needs to keep saying so.
 * </p>
 * <p>
 * Instances are immutable and safe to hand between threads.
 * </p>
 */
public class OperatorSignal
{
    /** What the user is telling the agent to do. */
    public enum SignalType
    {
        /** Give up on this operation. */
        CANCEL,
        /** Something went wrong in EDT; try it again. */
        RETRY,
        /** It is still running; get on with something else. */
        BACKGROUND,
        /** Stop and ask a person. */
        EXPERT,
        /** Whatever the user typed. */
        CUSTOM
    }

    private static final String MEMBER_USER_SIGNAL = "userSignal"; //$NON-NLS-1$

    private static final String MEMBER_SIGNAL_TYPE = "signalType"; //$NON-NLS-1$

    private static final String MEMBER_MESSAGE = "message"; //$NON-NLS-1$

    private static final String EMPTY = ""; //$NON-NLS-1$

    private final SignalType type;

    private final String message;

    private final long timestamp;

    /**
     * Raises a signal, stamped with the moment it was raised.
     *
     * @param type what the user is asking for; may be <code>null</code>
     * @param message the text the agent will read; may be <code>null</code>
     */
    public OperatorSignal(SignalType type, String message)
    {
        this.type = type;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Returns what the user asked for.
     *
     * @return the signal type; may be <code>null</code>
     */
    public SignalType getType()
    {
        return type;
    }

    /**
     * Returns the text meant for the agent.
     *
     * @return the message; may be <code>null</code>
     */
    public String getMessage()
    {
        return message;
    }

    /**
     * Returns when the signal was raised.
     *
     * @return the construction time, in milliseconds since the epoch
     */
    public long getTimestamp()
    {
        return timestamp;
    }

    /**
     * Renders this signal as a JSON object.
     * <p>
     * Note that this is <em>not</em> the shape the protocol layer puts on the wire; it builds its own,
     * because a tool result needs the signal spelled out differently depending on the kind of content
     * the tool produced. This method is here for a caller that wants the signal on its own.
     * </p>
     *
     * @return the signal as compact JSON
     */
    public String toJson()
    {
        JsonObject document = new JsonObject();
        document.addProperty(MEMBER_USER_SIGNAL, Boolean.TRUE);
        document.addProperty(MEMBER_SIGNAL_TYPE, type != null ? type.name() : null);
        document.addProperty(MEMBER_MESSAGE, message);
        return GsonHolder.toJson(document);
    }

    /**
     * Returns the text a signal of this kind starts out with.
     * <p>
     * The user sees this in the dialog and may edit it before sending, so it is a suggestion rather
     * than a fixed string - but whatever survives is read by the agent, not by a person, which is why
     * each one is written as an instruction to the agent. {@link SignalType#CUSTOM} starts out blank
     * on purpose: there the user is expected to say something of their own.
     * </p>
     *
     * @param type the kind of signal; may be <code>null</code>
     * @return the default message, never <code>null</code>
     */
    public static String getDefaultMessage(SignalType type)
    {
        if (type == null)
        {
            return EMPTY;
        }
        switch (type)
        {
        case CANCEL:
            return "The user has cancelled this operation. Stop working on it, do not call the tool" //$NON-NLS-1$
                + " again, and wait for further instructions."; //$NON-NLS-1$
        case RETRY:
            return "EDT reported an error on this operation. Retry the call once; if it fails the" //$NON-NLS-1$
                + " same way a second time, stop and report what happened."; //$NON-NLS-1$
        case BACKGROUND:
            return "This operation is slow and is being left to run in the background. Do not wait" //$NON-NLS-1$
                + " for it - carry on with the rest of the task and pick up the result later."; //$NON-NLS-1$
        case EXPERT:
            return "Stop here and consult an expert before going any further. This step needs a" //$NON-NLS-1$
                + " decision that should not be taken automatically."; //$NON-NLS-1$
        case CUSTOM:
        default:
            return EMPTY;
        }
    }
}
