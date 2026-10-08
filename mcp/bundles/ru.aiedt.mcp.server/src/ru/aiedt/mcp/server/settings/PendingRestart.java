/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

/**
 * A tool selection that has been saved but that the running server has not taken up yet.
 * <p>
 * The tools tab writes the store itself, so from the moment its own save runs, "have the tools
 * changed" answers no. A restart that failed after that would be forgotten: the next OK would find
 * no change, skip the restart it still owes, and close the page over a server the failed restart had
 * already stopped. The debt is kept here instead, and only a restart that returned clears it.
 * </p>
 * <p>
 * A server that is not running is the case the debt alone cannot decide. One the user stopped, and
 * one no start has ever brought up, both read the store when they next come up, so the save owes
 * them nothing. One a failed restart left stopped is a different thing: what it was serving is gone
 * and only a start brings it back, so the next OK makes that start rather than close over it.
 * </p>
 */
final class PendingRestart
{
    /** What a save has to do with the server, if anything. */
    enum Action
    {
        /** Nothing is owed, or the server is one this save does not reach. */
        NOTHING,

        /** The server is running, and on a selection older than the one just saved. */
        RESTART,

        /** A restart that failed left the server stopped, and the save has to bring it back. */
        START
    }

    /** Whether a save has been made that the server has not been given. */
    private boolean pending;

    /** Whether the server is stopped because the attempt to restart it failed. */
    private boolean stoppedByFailure;

    /**
     * Says what the save that is running has to do with the server.
     *
     * @param toolsChanged whether the save that just ran wrote a tool selection
     * @param serverRunning whether the endpoint is listening
     * @return the action, never <code>null</code>
     */
    Action actionFor(boolean toolsChanged, boolean serverRunning)
    {
        if (!toolsChanged && !pending)
        {
            return Action.NOTHING;
        }
        if (serverRunning)
        {
            return Action.RESTART;
        }
        return stoppedByFailure ? Action.START : Action.NOTHING;
    }

    /** Records that the start or the restart a save needed failed, leaving the server stopped. */
    void refused()
    {
        pending = true;
        stoppedByFailure = true;
    }

    /** Records that the server is running what the store holds. */
    void applied()
    {
        pending = false;
        stoppedByFailure = false;
    }
}
