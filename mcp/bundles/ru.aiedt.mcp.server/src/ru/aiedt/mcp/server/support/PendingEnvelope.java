/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import org.eclipse.swt.widgets.Display;

import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * The one mark that says an answer is a Pending envelope rather than an answer that mentions one.
 * <p>
 * Eight places build this envelope, and the router has to recognise it in order to turn it into a
 * task. It recognised it by looking for the words {@code "Pending"} and {@code "runKey"} in the
 * text, and only in answers shorter than four thousand characters - two guesses, each of which
 * fails in its own direction. An envelope that grows past the limit stops being a task, silently,
 * because it got longer. An ordinary answer that happens to quote those words gets parsed as one.
 * </p>
 * <p>
 * A field nobody else writes settles it. Producers stamp it, the router looks for it, and neither
 * has to reason about length or about which words might appear in somebody's message text.
 * </p>
 */
public final class PendingEnvelope
{
    /** The member that marks the envelope. Written by producers, read by the router. */
    public static final String MARK = "pendingEnvelope"; //$NON-NLS-1$

    /** What the mark looks like in the serialised answer, for a cheap pre-check. */
    public static final String MARK_IN_JSON = "\"pendingEnvelope\":true"; //$NON-NLS-1$

    private PendingEnvelope()
    {
    }

    /**
     * Stamps a result as a Pending envelope.
     *
     * @param result the envelope being built.
     * @return the same result, for chaining
     */
    public static ToolResult mark(ToolResult result)
    {
        result.put(MARK, true);
        return alsoSayWhatIsHoldingIt(result);
    }

    /**
     * Adds the modal dialog that is holding the work, when one is.
     * <p>
     * A Pending answer says the work has outlived its wait budget. It does not say WHY, and the
     * commonest why is a question on screen: an update asking whether to go ahead, a dump asking
     * where to put a file. To a caller those look the same as slow work, so it waits, polls, and
     * waits again while a dialog no agent can see holds everything.
     * </p>
     * <p>
     * Stamped here because this is the one place every producer of an envelope passes through, so
     * every long operation gains it at once rather than each remembering to.
     * </p>
     *
     * @param result the envelope being built.
     * @return the same result, for chaining
     */
    private static ToolResult alsoSayWhatIsHoldingIt(ToolResult result)
    {
        if (Display.getCurrent() != null)
        {
            // Reading the shells means posting to the UI thread and waiting for it. On the UI
            // thread that is a wait on ourselves, and a caller running there is not the one being
            // held by a modal anyway.
            return result;
        }
        try
        {
            ModalDialogWatch.Reading reading = ModalDialogWatch.current();
            if (!reading.isBlocked())
            {
                return result;
            }
            return result.put("blockedByDialog", Boolean.TRUE) //$NON-NLS-1$
                .put("dialogs", reading.getDialogs()) //$NON-NLS-1$
                .put("blockedExplanation", reading.describe() //$NON-NLS-1$
                    + " Press one of the buttons with answer_dialog, or answer it in EDT."); //$NON-NLS-1$
        }
        catch (RuntimeException | LinkageError noUi)
        {
            // Headless, or SWT absent: an envelope without this is the ordinary case, not a failure.
            return result;
        }
    }

    /**
     * Whether an answer could be a Pending envelope, judged before it is parsed.
     * <p>
     * Cheap and exact: the mark is a fixed string no other answer writes, so this rejects
     * everything else without parsing, and rejects nothing that is one - however long it has
     * grown.
     * </p>
     *
     * @param result the serialised answer; may be <code>null</code>.
     * @return true when it carries the mark
     */
    public static boolean isCandidate(String result)
    {
        return result != null && result.contains(MARK_IN_JSON);
    }
}
