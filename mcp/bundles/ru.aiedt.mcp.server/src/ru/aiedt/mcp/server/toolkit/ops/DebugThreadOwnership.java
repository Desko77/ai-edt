/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import org.eclipse.debug.core.model.IThread;

import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Rejects a thread that belongs to a debug session other than the one the call addressed.
 * <p>
 * Thread ids come from one registry shared by every session, so an id issued by one application
 * resolves fine while the call works on another. Without this check a tool named with the foreign
 * id suspends, steps or reads the stack of a session the caller never meant - and the answer
 * reports it as if it were the session that was addressed.
 * </p>
 * <p>
 * The check refuses only what it can decide: the thread's own application is read from its debug
 * target, and the expected one is the application the call named or, when it named none, the only
 * active launch. When either side cannot be resolved the thread is allowed through - an owner
 * nobody can name is not evidence of a foreign session.
 * </p>
 */
final class DebugThreadOwnership
{
    /** The reason a thread of another session is refused with. */
    static final String REASON = "threadOfAnotherSession"; //$NON-NLS-1$

    private DebugThreadOwnership()
    {
        // utility
    }

    /**
     * The full refusal for a thread that belongs to another session, or <code>null</code> when the
     * thread may be used.
     *
     * @param thread the thread the caller named; may be <code>null</code>, which allows everything
     * @param expectedApplicationId the application the call addressed; <code>null</code> or empty
     *            reads the only active launch, and when there is not exactly one the thread is
     *            allowed
     * @param threadId the id the caller named, for the answer; ignored when negative
     * @return the refusal, or <code>null</code> when the thread belongs to the addressed session
     */
    static ToolResult refusal(IThread thread, String expectedApplicationId, long threadId)
    {
        String owner = foreignOwner(thread, expectedApplicationId);
        if (owner == null)
        {
            return null;
        }
        ToolResult refusal = ToolResult.error("threadId " + (threadId > 0 ? threadId + " " : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "belongs to the debug session of applicationId '" + owner //$NON-NLS-1$
            + "', not to the one this call addressed. A thread id is only valid inside the session " //$NON-NLS-1$
            + "that issued it: call wait_for_break for that application and use the id it answers with.")
            .put("reason", REASON)
            .put("applicationId", owner);
        if (threadId > 0)
        {
            refusal.put("threadId", threadId);
        }
        return refusal;
    }

    /**
     * The application the thread belongs to when that is a session other than the expected one.
     *
     * @param thread the thread the caller named; may be <code>null</code>
     * @param expectedApplicationId the application the call addressed; <code>null</code> or empty
     *            reads the only active launch
     * @return the owning application id of a foreign thread, or <code>null</code> when the thread
     *         belongs to the expected session or the question cannot be decided
     */
    static String foreignOwner(IThread thread, String expectedApplicationId)
    {
        if (thread == null)
        {
            return null;
        }
        String owner = DebugSessionBook.findApplicationIdFor(thread);
        if (owner == null)
        {
            return null;
        }
        String expected = expectedApplicationId == null || expectedApplicationId.isEmpty()
            ? DebugSessionBook.findLoneActiveApplicationId() : expectedApplicationId;
        if (expected == null || expected.equals(owner))
        {
            return null;
        }
        return owner;
    }

    /**
     * The refusal sentence for a caller that answers with plain text rather than a result document.
     *
     * @param owner the owning application id of the foreign thread
     * @return the sentence naming both sessions
     */
    static String describe(String owner)
    {
        return "threadId belongs to the debug session of applicationId '" + owner //$NON-NLS-1$
            + "', not to the one this call addressed - call wait_for_break again for the id of " //$NON-NLS-1$
            + "the session being worked on"; //$NON-NLS-1$
    }

    /**
     * @param params the call arguments, for the application the call addressed
     * @return the application id the call named, or <code>null</code> when it named none
     */
    static String namedApplication(Map<String, String> params)
    {
        String named = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        return named == null || named.isEmpty() ? null : named;
    }
}
