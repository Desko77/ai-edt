/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.eclipse.debug.core.model.IStackFrame;
import org.junit.After;
import org.junit.Test;

/**
 * A frame reference stops describing anything the moment the session it named moves on.
 *
 * <p>A reference names an address - a thread and a position in its stack - and the class promises
 * that a later lookup finds nothing once execution has resumed, because the frames the reference
 * named no longer exist. What a resume dropped was the frame objects and their address entries,
 * while the reference-to-address map kept its entry: a reference issued before the stop was looked
 * up again after the next one and answered with whatever frame now sat at that position - somebody
 * else's frame, handed over as the one the caller had asked for.</p>
 *
 * <p>Read against a fake session: the thread, its stack and the launch behind it, with no client.</p>
 */
public class AFrameReferenceDoesNotOutliveTheStopTest
{
    private static final String APP = "b61-frames-app"; //$NON-NLS-1$

    private static final String OTHER_APP = "b61-frames-other-app"; //$NON-NLS-1$

    @After
    public void forgetTheFakeSessions()
    {
        DebugSessionBook.get().forget(APP);
        DebugSessionBook.get().forget(OTHER_APP);
    }

    @Test
    public void aReferenceIssuedBeforeTheStopIsRefusedAfterIt()
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        IStackFrame caller = session.frame("ПередВызовом"); //$NON-NLS-1$
        IStackFrame callee = session.frame("ВВызове"); //$NON-NLS-1$
        session.moveTo(caller, callee);
        DebugSessionBook registry = DebugSessionBook.get();
        registry.injectSuspend(APP, session.thread());

        long frameRef = registry.registerFrame(callee);
        assertSame("the reference has to answer while the stop lasts", //$NON-NLS-1$
            callee, registry.getFrame(frameRef));

        // Execution resumed and stopped somewhere else: the thread holds other frames at the same
        // positions, and the ones the reference named are gone.
        session.moveTo(session.frame("ДругоеМесто"), session.frame("ИЕщёОдноМесто")); //$NON-NLS-1$ //$NON-NLS-2$
        registry.forget(APP);

        assertNull("a frame of the previous stop has to be refused, not answered with a new one", //$NON-NLS-1$
            registry.getFrame(frameRef));
    }

    @Test
    public void aReferenceOfAnotherApplicationSurvivesTheForget()
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        IStackFrame own = session.frame("ПередВызовом"); //$NON-NLS-1$
        session.moveTo(own);
        FakeDebugFrames.Session other = FakeDebugFrames.session(OTHER_APP);
        IStackFrame foreign = other.frame("ДругаяСессия"); //$NON-NLS-1$
        other.moveTo(foreign);

        DebugSessionBook registry = DebugSessionBook.get();
        registry.injectSuspend(APP, session.thread());
        registry.injectSuspend(OTHER_APP, other.thread());
        long forgotten = registry.registerFrame(own);
        long kept = registry.registerFrame(foreign);

        registry.forget(APP);

        assertNull(registry.getFrame(forgotten));
        IStackFrame stillThere = registry.getFrame(kept);
        assertNotNull("the other session did not resume", stillThere); //$NON-NLS-1$
        assertSame(foreign, stillThere);
    }
}
