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
 */
final class PendingRestart
{
    /** Whether a save has been made that the server has not been given. */
    private boolean pending;

    /**
     * Takes a save into account and says whether the server has to be restarted.
     *
     * @param toolsChanged whether the save that just ran wrote a tool selection
     * @return <code>true</code> when a restart is due
     */
    boolean due(boolean toolsChanged)
    {
        return toolsChanged || pending;
    }

    /** Records that the restart a save needed did not happen, so the next save asks again. */
    void refused()
    {
        pending = true;
    }

    /** Records that the server is running what the store holds. */
    void applied()
    {
        pending = false;
    }
}
