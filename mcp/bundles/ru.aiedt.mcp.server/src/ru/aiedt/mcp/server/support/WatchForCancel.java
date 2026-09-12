/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

/**
 * A scan that stops when the operator cancels, and an answer that says it stopped.
 * <p>
 * The flag has been reachable from any tool since the call scope existed, and 36 of the 37 tools
 * this project calls heavy never looked at it. A cancel those tools do not read raises the flag and
 * changes nothing: the call runs to the end, the operator waits for work they asked to stop, and the
 * permit it holds is denied to the next call.
 * </p>
 * <p>
 * Two halves, and the second is the one that is easy to leave out. Stopping early produces an answer
 * built from part of the work, and an answer that does not say so is read as the whole - which is
 * worse than not stopping, because "nothing was found" then means "nothing was looked at". Every
 * user of this asks {@link #stopped()} before it answers.
 * </p>
 */
public final class WatchForCancel
{
    private final ToolCallScope.Cancellation flag;

    /** Written on the thread that walks and read on the one that answers; those are not always the
     * same thread, and a stale read here would drop the note from the answer. */
    private volatile boolean fired;

    private volatile int reached;

    private WatchForCancel(ToolCallScope.Cancellation flag)
    {
        this.flag = flag;
    }

    /**
     * Starts watching the current call's cancel flag.
     * <p>
     * Outside a call - in a test, or on a path the scope does not cover - there is no flag and this
     * never stops anything, which is the same behaviour those paths had before.
     * </p>
     *
     * @return the watch, never <code>null</code>
     */
    public static WatchForCancel begin()
    {
        ToolCallScope scope = ToolCallScope.current();
        return new WatchForCancel(scope == null ? null : scope.cancellation());
    }

    /**
     * Whether to stop here, counting this step as reached.
     * <p>
     * Asked at a boundary where stopping leaves something whole - one object, one module, one file -
     * rather than inside the work on one of them.
     * </p>
     *
     * @return <code>true</code> when the operator cancelled and the caller should stop
     */
    public boolean stopHere()
    {
        if (fired)
        {
            // Counting past the stop would make the note say the scan reached boundaries it never
            // looked at. A caller that keeps asking - a loop that continues to record what it is
            // skipping, a phase entered after another stopped - must not inflate the number the
            // answer reports.
            return true;
        }
        if (flag != null && flag.isCancelled())
        {
            // Counted after the check, not before: this boundary is where the work STOPS, so it is
            // not one the scan got through. Counting first says "after 1 units" when none were read.
            fired = true;
            return true;
        }
        reached++;
        return false;
    }

    /**
     * Whether the operator has cancelled, without counting a boundary.
     * <p>
     * For a walk this code does not own - the reference finder of the editing framework, say -
     * which polls a progress monitor from threads of its own. Counting there would race, and the
     * count is what the note reports, so the boundary count stays with the loops written here.
     * </p>
     *
     * @return <code>true</code> when the operator cancelled and the caller should stop
     */
    public boolean raised()
    {
        if (flag != null && flag.isCancelled())
        {
            fired = true;
        }
        return fired;
    }

    /**
     * Whether the scan was stopped rather than finished.
     *
     * @return <code>true</code> when {@link #stopHere} or {@link #raised} told the caller to stop
     */
    public boolean stopped()
    {
        return fired;
    }

    /**
     * How many boundaries were reached before the scan stopped.
     *
     * @return the count; it stops moving once the scan is stopped, so it names where the work
     *         actually ended rather than how often the caller kept asking
     */
    public int reached()
    {
        return reached;
    }

    /**
     * The sentence an answer carries when the scan was cut short.
     *
     * @param unit what was being walked, in the plural - "modules", "objects", "roles".
     * @return the note, or <code>null</code> when the scan ran to the end
     */
    public String note(String unit)
    {
        if (!fired)
        {
            return null;
        }
        String partial = "; what is below is what had been found by then, not the whole answer"; //$NON-NLS-1$
        if (reached == 0)
        {
            // No boundary was counted: the stop came before the first one, or the work that ran is
            // work this watch does not count - a walk polled through a progress monitor on threads
            // of its own. Naming a count of zero there would read as "nothing was scanned", which
            // is a claim about the work rather than about the stop.
            return "cancelled by the operator" + partial; //$NON-NLS-1$
        }
        return "cancelled by the operator after " + reached + " " + unit + partial; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
