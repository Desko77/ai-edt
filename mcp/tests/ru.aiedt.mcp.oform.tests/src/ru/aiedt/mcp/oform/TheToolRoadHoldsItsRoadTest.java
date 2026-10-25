/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IToolRoad;
import ru.aiedt.mcp.server.toolkit.ToolRoadOutcome;

/**
 * The tool road is held for the whole bundle: a bind holds the service for every later call, a
 * newer bind replaces it, and the unbind of a service that is no longer the one held changes
 * nothing - the active replacement stays where it is. With no road held the bundle answers none,
 * and an unbind against that absence is a no-op. The held road is static, so every test starts
 * from and returns to whatever road the runtime held when it began.
 */
public class TheToolRoadHoldsItsRoadTest
{
    private final ToolRoadHolder holder = new ToolRoadHolder();

    private IToolRoad roadBefore;

    /** Remembers the road the runtime holds, so a test can give it back whatever it did. */
    @Before
    public void theRoadTheRuntimeHolds()
    {
        roadBefore = ToolRoadHolder.road();
    }

    /** Gives back the road the runtime held: a held one is bound again, a held-none is cleared. */
    @After
    public void theRoadGoesBack()
    {
        if (roadBefore != null)
        {
            holder.bind(roadBefore);
        }
        else
        {
            IToolRoad current = ToolRoadHolder.road();
            if (current != null)
            {
                holder.unbind(current);
            }
            assertNull(ToolRoadHolder.road());
        }
    }

    /** A bind holds the road for the bundle's calls, a newer bind replaces it, its own unbind clears it. */
    @Test
    public void aBindHoldsAndASecondBindReplaces()
    {
        theRoadIsCleared();
        ARoad first = new ARoad("first"); //$NON-NLS-1$
        holder.bind(first);
        assertSame(first, ToolRoadHolder.road());
        ARoad second = new ARoad("second"); //$NON-NLS-1$
        holder.bind(second);
        assertSame(second, ToolRoadHolder.road());
        holder.unbind(second);
        assertNull(ToolRoadHolder.road());
    }

    /**
     * The unbind of a service the holder no longer holds leaves the active replacement in place:
     * a component that missed a rebind must not take the road away from the newer service.
     */
    @Test
    public void aStaleUnbindKeepsTheActiveReplacement()
    {
        theRoadIsCleared();
        ARoad first = new ARoad("first"); //$NON-NLS-1$
        ARoad second = new ARoad("second"); //$NON-NLS-1$
        holder.bind(first);
        holder.bind(second);
        holder.unbind(first);
        assertSame("the stale unbind discarded the active replacement", second, ToolRoadHolder.road()); //$NON-NLS-1$
        holder.unbind(second);
        assertNull(ToolRoadHolder.road());
    }

    /** An unbind with no road held, and one of a service never bound, change nothing. */
    @Test
    public void anUnbindWithoutTheHeldServiceChangesNothing()
    {
        theRoadIsCleared();
        assertNull("no road is held at the start", ToolRoadHolder.road()); //$NON-NLS-1$
        holder.unbind(new ARoad("never bound")); //$NON-NLS-1$
        assertNull(ToolRoadHolder.road());
        ARoad held = new ARoad("held"); //$NON-NLS-1$
        holder.bind(held);
        holder.unbind(new ARoad("another")); //$NON-NLS-1$
        assertSame(held, ToolRoadHolder.road());
    }

    /** Clears whatever road is held, so a test starts from a known absence. */
    private void theRoadIsCleared()
    {
        IToolRoad current = ToolRoadHolder.road();
        if (current != null)
        {
            holder.unbind(current);
        }
    }

    /** A road that stands in for the plugin's own: every method answers a plain refusal. */
    private static final class ARoad implements IToolRoad
    {
        private final String name;

        ARoad(String name)
        {
            this.name = name;
        }

        @Override
        public ToolRoadOutcome call(String tool, Map<String, Object> arguments, String origin)
        {
            return ToolRoadOutcome.refused(name);
        }

        @Override
        public ToolRoadOutcome resume(String runKey, long waitMillis)
        {
            return ToolRoadOutcome.refused(name);
        }

        @Override
        public boolean cancel(String runKey)
        {
            return false;
        }
    }
}
