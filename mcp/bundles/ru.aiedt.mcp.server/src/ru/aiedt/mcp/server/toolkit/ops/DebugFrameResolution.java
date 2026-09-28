/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Resolves the stack frame a debug tool was asked to work on, and says why it could not.
 * <p>
 * The reading and the writing tool reach a frame the same three ways - the reference an earlier
 * {@code wait_for_break} handed out, a thread id with a frame index, or the lone suspended
 * application - and the same input has to be answered with the same words whichever of them asks.
 * A reference the session has left behind, a thread the session has left behind and an index the
 * stack does not reach are three different answers, and none of them is "no frame was named".
 * </p>
 * <p>
 * An index is never clamped into range: a caller that counted wrong is told so, because reading or
 * writing the frame the index would have landed on is work nobody asked for and, for a write, work
 * at the wrong place.
 * </p>
 */
final class DebugFrameResolution
{
    /** What a caller is told when the reference names a frame the session has left behind. */
    private static final String STALE_FRAME_REF =
        "frameRef is no longer valid - call wait_for_break again"; //$NON-NLS-1$

    /** What a caller is told when the thread id names a thread the session has left behind. */
    private static final String STALE_THREAD_ID =
        "threadId is no longer valid - call wait_for_break again"; //$NON-NLS-1$

    /** What a caller that named neither a reference nor a thread id is told. */
    private static final String NO_LONE_LAUNCH =
        "Pass frameRef or threadId - there is no single suspended debug launch to auto-resolve to. "
            + "Call wait_for_break first."; //$NON-NLS-1$

    /** What a caller is told when the suspended thread turns out to have no stack. */
    private static final String EMPTY_STACK =
        "the suspended thread has no stack frames"; //$NON-NLS-1$

    private DebugFrameResolution()
    {
        // utility
    }

    /**
     * A frame the caller can be given, or the reason there is none.
     */
    static final class Resolution
    {
        /** The frame, or <code>null</code> when none could be resolved. */
        final IStackFrame frame;

        /** Why no frame could be resolved; <code>null</code> when one was. */
        final String refusal;

        /** The reference the caller passed, or -1 when none was passed. */
        final long frameRef;

        /** The thread the frame belongs to, or -1 when the caller named none. */
        final long threadId;

        /** Where the frame sits in that thread's stack, or -1 when the caller named none. */
        final int index;

        private Resolution(IStackFrame frame, String refusal, long frameRef, long threadId, int index)
        {
            this.frame = frame;
            this.refusal = refusal;
            this.frameRef = frameRef;
            this.threadId = threadId;
            this.index = index;
        }

        /**
         * @param frame the resolved frame
         * @param frameRef the reference it was reached by, or -1
         * @param threadId the thread it belongs to, or -1 when the caller named none
         * @param index its position in that thread's stack, or -1 when the caller named none
         * @return the resolution
         */
        static Resolution of(IStackFrame frame, long frameRef, long threadId, int index)
        {
            return new Resolution(frame, null, frameRef, threadId, index);
        }

        /**
         * @param refusal what the caller has to be told
         * @return the resolution, carrying no frame
         */
        static Resolution refused(String refusal)
        {
            return new Resolution(null, refusal, -1L, -1L, -1);
        }
    }

    /**
     * Resolves the frame the caller named, or the lone suspended one when it named none.
     *
     * @param registry the debug session registry
     * @param frameRef the frame reference the caller passed, or a value at most zero
     * @param threadId the thread id the caller passed, or a value at most zero
     * @param frameIndex the frame index the caller passed, counted from zero
     * @return the frame with the address it came from, or the refusal to report instead
     */
    static Resolution resolve(DebugSessionBook registry, long frameRef, long threadId, int frameIndex)
    {
        try
        {
            if (frameRef > 0)
            {
                IStackFrame frame = registry.getFrame(frameRef);
                return frame == null ? Resolution.refused(STALE_FRAME_REF)
                    : Resolution.of(frame, frameRef, -1L, -1);
            }

            if (threadId > 0)
            {
                IThread thread = registry.getThread(threadId);
                if (thread == null)
                {
                    return Resolution.refused(STALE_THREAD_ID);
                }
                return inStack(thread.getStackFrames(), frameIndex, threadId);
            }

            return against(registry, DebugSessionBook.findLoneActiveApplicationId(), frameIndex);
        }
        catch (Exception e)
        {
            return Resolution.refused("the frame could not be read: " + TextSuggest.safeMessage(e)); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the frame of an application's current stop.
     * <p>
     * The third way in, with the application given rather than discovered: the tools are told the lone
     * suspended application by the launch manager, and a caller that already knows which session it
     * means - and a test, which has no launch manager to be told by - can name it instead.
     * </p>
     *
     * @param registry the debug session registry
     * @param applicationId the application, or <code>null</code> when none was found
     * @param frameIndex the frame index the caller passed, counted from zero
     * @return the frame with the address it came from, or the refusal to report instead
     * @throws DebugException if the thread's stack cannot be read at all
     */
    static Resolution against(DebugSessionBook registry, String applicationId, int frameIndex)
        throws DebugException
    {
        DebugSessionBook.SuspendSnapshot snapshot =
            applicationId == null ? null : registry.getSnapshot(applicationId);
        if (snapshot == null)
        {
            return Resolution.refused(NO_LONE_LAUNCH);
        }
        return inStack(snapshot.thread.getStackFrames(), frameIndex, snapshot.threadId);
    }

    /**
     * Takes the frame at a position in a stack, refusing a position the stack does not reach.
     *
     * @param stack the frames the suspended thread holds right now
     * @param frameIndex the position the caller asked for, counted from zero
     * @param threadId the thread the stack belongs to
     * @return the frame at that position, or the refusal to report instead
     */
    private static Resolution inStack(IStackFrame[] stack, int frameIndex, long threadId)
    {
        if (stack.length == 0)
        {
            return Resolution.refused(EMPTY_STACK);
        }
        int live = DebugSessionBook.pickIndex(stack.length, frameIndex);
        return live < 0 ? Resolution.refused("frameIndex is out of range (0.." + (stack.length - 1) + ")") //$NON-NLS-1$ //$NON-NLS-2$
            : Resolution.of(stack[live], -1L, threadId, live);
    }
}
