/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.junit.Test;

/**
 * Asking for a line twice leaves one breakpoint on it.
 *
 * <p>Setting the same line again used to add a second breakpoint beside the first: both stopped the
 * debugger and both showed in the breakpoints view, and a removal by coordinates took only the first
 * one away, so a line asked for twice kept a breakpoint nobody had asked to keep. The creation point
 * now takes off what is already on that line before it puts the new one there - the same search
 * removal uses, so what a set can create a remove can reach.</p>
 *
 * <p>Every caller goes through that point: the single set, and every item of a batch, which is
 * unpacked and handed to the single set.</p>
 */
public class ASecondBreakpointOnALineReplacesTheFirstTest
{
    private static final String PATH = "/aiedt-breakpoint-dedup/src/CommonModules/МойМодуль/Module.bsl"; //$NON-NLS-1$

    private static final int LINE = 42;

    @Test
    public void aSecondSetOnTheSameLineReplacesTheFirst() throws Exception
    {
        IFile file = file();
        FakeBreakpointManager manager = new FakeBreakpointManager();

        IBreakpoint first = BreakpointAccess.replaceLineBreakpoint(file, LINE, manager.asManager(),
            (f, line, mgr) -> manager.register(f, line));
        IBreakpoint second = BreakpointAccess.replaceLineBreakpoint(file, LINE, manager.asManager(),
            (f, line, mgr) -> manager.register(f, line));

        assertEquals("one line, one breakpoint", 1, manager.getBreakpoints().size()); //$NON-NLS-1$
        assertSame("the breakpoint that stays is the one this call asked for", second, //$NON-NLS-1$
            manager.getBreakpoints().get(0));
        assertEquals("the one that was there was taken off, not lost track of", //$NON-NLS-1$
            List.of(first), manager.removed);
    }

    @Test
    public void anotherLineIsLeftAlone() throws Exception
    {
        IFile file = file();
        FakeBreakpointManager manager = new FakeBreakpointManager();

        IBreakpoint other = BreakpointAccess.replaceLineBreakpoint(file, LINE - 1, manager.asManager(),
            (f, line, mgr) -> manager.register(f, line));
        BreakpointAccess.replaceLineBreakpoint(file, LINE, manager.asManager(),
            (f, line, mgr) -> manager.register(f, line));

        assertEquals("a breakpoint on another line is not in the way", 2, //$NON-NLS-1$
            manager.getBreakpoints().size());
        assertSame(other, manager.getBreakpoints().get(0));
    }

    /**
     * @return the module file a breakpoint hangs on
     */
    private static IFile file()
    {
        return (IFile)Proxy.newProxyInstance(
            ASecondBreakpointOnALineReplacesTheFirstTest.class.getClassLoader(),
            new Class<?>[] { IFile.class }, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                case "equals": //$NON-NLS-1$
                    return Boolean.valueOf(proxy == args[0]);
                case "hashCode": //$NON-NLS-1$
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "toString": //$NON-NLS-1$
                    return PATH;
                default:
                    return null;
                }
            });
    }

    /**
     * A breakpoint manager holding line breakpoints in memory.
     */
    private static final class FakeBreakpointManager
    {
        private final List<IBreakpoint> breakpoints = new ArrayList<>();

        /** Every breakpoint the manager was asked to take off, in the order it was asked. */
        private final List<IBreakpoint> removed = new ArrayList<>();

        /**
         * @param file the file the breakpoint hangs on
         * @param line the line it stops on
         * @return the breakpoint, registered with this manager
         */
        IBreakpoint register(IFile file, int line)
        {
            IMarker marker = (IMarker)Proxy.newProxyInstance(
                ASecondBreakpointOnALineReplacesTheFirstTest.class.getClassLoader(),
                new Class<?>[] { IMarker.class }, (proxy, method, args) ->
                    "getResource".equals(method.getName()) ? file : null); //$NON-NLS-1$
            IBreakpoint breakpoint = (IBreakpoint)Proxy.newProxyInstance(
                ASecondBreakpointOnALineReplacesTheFirstTest.class.getClassLoader(),
                new Class<?>[] { ILineBreakpoint.class }, (proxy, method, args) ->
                {
                    switch (method.getName())
                    {
                    case "getMarker": //$NON-NLS-1$
                        return marker;
                    case "getLineNumber": //$NON-NLS-1$
                        return Integer.valueOf(line);
                    case "equals": //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    case "hashCode": //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    default:
                        return null;
                    }
                });
            breakpoints.add(breakpoint);
            return breakpoint;
        }

        /**
         * @return the breakpoints held right now
         */
        List<IBreakpoint> getBreakpoints()
        {
            return new ArrayList<>(breakpoints);
        }

        /**
         * @return the manager, as the breakpoint code meets it
         */
        IBreakpointManager asManager()
        {
            return (IBreakpointManager)Proxy.newProxyInstance(
                ASecondBreakpointOnALineReplacesTheFirstTest.class.getClassLoader(),
                new Class<?>[] { IBreakpointManager.class }, (proxy, method, args) ->
                {
                    switch (method.getName())
                    {
                    case "getBreakpoints": //$NON-NLS-1$
                        return breakpoints.toArray(new IBreakpoint[0]);
                    case "removeBreakpoint": //$NON-NLS-1$
                        IBreakpoint gone = (IBreakpoint)args[0];
                        removed.add(gone);
                        breakpoints.remove(gone);
                        return null;
                    default:
                        return null;
                    }
                });
        }
    }
}
