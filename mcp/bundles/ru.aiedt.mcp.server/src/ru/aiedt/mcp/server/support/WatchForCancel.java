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
        reached++;
        if (flag != null && flag.isCancelled())
        {
            fired = true;
        }
        return fired;
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
     * How many boundaries were reached before the answer was built.
     *
     * @return the count, whether or not the scan was cancelled
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
        return fired ? "cancelled by the operator after " + reached + " " + unit //$NON-NLS-1$ //$NON-NLS-2$
            + "; what is below is what had been found by then, not the whole answer" : null; //$NON-NLS-1$
    }
}
