/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.junit.Test;

/**
 * A replacement that cannot be made leaves the line with the breakpoint it had.
 *
 * <p>A line that already carries a working breakpoint used to lose it the moment a replacement
 * was asked for: the old one came off before the new one was made, so a factory that refused
 * answered an error over a line whose breakpoint had gone. The replacement is now made first and
 * the old one comes off afterwards - a factory that refuses changes nothing, and a line whose old
 * breakpoint will not come off keeps it rather than ending up with two.</p>
 */
public class AFailedReplacementKeepsTheBreakpointOnTheLineTest
{
    private static final String PATH = "/aiedt-breakpoint-replacement/src/CommonModules/МойМодуль/Module.bsl"; //$NON-NLS-1$

    private static final int LINE = 42;

    @Test
    public void aReplacementThatCannotBeMadeLeavesTheLineArmed() throws Exception
    {
        IFile file = file();
        FakeBreakpointManager manager = new FakeBreakpointManager();
        IBreakpoint working = BreakpointAccess.replaceLineBreakpoint(file, LINE, manager.asManager(),
            (f, line, mgr) -> manager.register(f, line));

        try
        {
            BreakpointAccess.replaceLineBreakpoint(file, LINE, manager.asManager(),
                (f, line, mgr) -> {
                    throw new IllegalStateException("the factory refused"); //$NON-NLS-1$
                });
            fail("a factory that refuses fails the call"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            // the refusal is what the caller hears
        }

        assertEquals("the line keeps the breakpoint it had", List.of(working), //$NON-NLS-1$
            manager.getBreakpoints());
        assertTrue("and it stays enabled", working.isEnabled()); //$NON-NLS-1$
    }

    @Test
    public void aReplacementWhoseOldOneWillNotComeOffLeavesTheLineAsItWas() throws Exception
    {
        IFile file = file();
        FakeBreakpointManager manager = new FakeBreakpointManager();
        IBreakpoint working = BreakpointAccess.replaceLineBreakpoint(file, LINE, manager.asManager(),
            (f, line, mgr) -> manager.register(f, line));
        manager.refuseFirstRemoval();

        try
        {
            BreakpointAccess.replaceLineBreakpoint(file, LINE, manager.asManager(),
                (f, line, mgr) -> manager.register(f, line));
            fail("a removal that refuses fails the call"); //$NON-NLS-1$
        }
        catch (CoreException expected)
        {
            // the refusal is what the caller hears
        }

        assertEquals("the half-made replacement came back off; the line keeps what it had", //$NON-NLS-1$
            List.of(working), manager.getBreakpoints());
        assertTrue("and it stays enabled", working.isEnabled()); //$NON-NLS-1$
    }

    @Test
    public void aSuccessfulReplacementLeavesOneBreakpointOnTheLine() throws Exception
    {
        IFile file = file();
        FakeBreakpointManager manager = new FakeBreakpointManager();
        BreakpointAccess.replaceLineBreakpoint(file, LINE, manager.asManager(),
            (f, line, mgr) -> manager.register(f, line));
        IBreakpoint second = BreakpointAccess.replaceLineBreakpoint(file, LINE, manager.asManager(),
            (f, line, mgr) -> manager.register(f, line));

        assertEquals("one line, one breakpoint", 1, manager.getBreakpoints().size()); //$NON-NLS-1$
        assertSame("the breakpoint that stays is the one this call asked for", second, //$NON-NLS-1$
            manager.getBreakpoints().get(0));
    }

    /**
     * @return the module file a breakpoint hangs on
     */
    private static IFile file()
    {
        return (IFile)Proxy.newProxyInstance(
            AFailedReplacementKeepsTheBreakpointOnTheLineTest.class.getClassLoader(),
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

        /** When set, the next removal throws it instead of being accepted. */
        private Exception refuseNextRemoval;

        /**
         * Makes the next removal refuse.
         */
        void refuseFirstRemoval()
        {
            refuseNextRemoval = new CoreException(
                new Status(IStatus.ERROR, "ru.aiedt.mcp.server.tests", "the removal was refused")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /**
         * @param file the file the breakpoint hangs on
         * @param line the line it stops on
         * @return the breakpoint, registered with this manager
         */
        IBreakpoint register(IFile file, int line)
        {
            boolean[] enabled = { true };
            IMarker marker = (IMarker)Proxy.newProxyInstance(
                AFailedReplacementKeepsTheBreakpointOnTheLineTest.class.getClassLoader(),
                new Class<?>[] { IMarker.class }, (proxy, method, args) ->
                    "getResource".equals(method.getName()) ? file : null); //$NON-NLS-1$
            IBreakpoint breakpoint = (IBreakpoint)Proxy.newProxyInstance(
                AFailedReplacementKeepsTheBreakpointOnTheLineTest.class.getClassLoader(),
                new Class<?>[] { ILineBreakpoint.class }, (proxy, method, args) ->
                {
                    switch (method.getName())
                    {
                    case "getMarker": //$NON-NLS-1$
                        return marker;
                    case "getLineNumber": //$NON-NLS-1$
                        return Integer.valueOf(line);
                    case "isEnabled": //$NON-NLS-1$
                        return Boolean.valueOf(enabled[0]);
                    case "setEnabled": //$NON-NLS-1$
                        enabled[0] = ((Boolean)args[0]).booleanValue();
                        return null;
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
                AFailedReplacementKeepsTheBreakpointOnTheLineTest.class.getClassLoader(),
                new Class<?>[] { IBreakpointManager.class }, (proxy, method, args) ->
                {
                    switch (method.getName())
                    {
                    case "getBreakpoints": //$NON-NLS-1$
                        return breakpoints.toArray(new IBreakpoint[0]);
                    case "removeBreakpoint": //$NON-NLS-1$
                        if (refuseNextRemoval != null)
                        {
                            Exception refusal = refuseNextRemoval;
                            refuseNextRemoval = null;
                            throw refusal;
                        }
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
