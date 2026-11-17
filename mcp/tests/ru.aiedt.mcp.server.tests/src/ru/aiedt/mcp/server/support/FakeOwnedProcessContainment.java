/*
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A containment whose liveness, release and establishment the test drives directly.
 * <p>
 * It exists so the production wiring - {@code VanessaTool.ownRun} - can be exercised without a
 * real client: the point under test is that the run asks the containment and keeps the run owned
 * when a release is not confirmed.
 * </p>
 */
public final class FakeOwnedProcessContainment implements OwnedProcessContainment
{
    /** What {@link #isTreeAlive()} answers. */
    public final AtomicBoolean treeAlive = new AtomicBoolean(true);

    /** How many times a stop was requested. */
    public final AtomicInteger terminates = new AtomicInteger();

    /** How many confirmed releases happened. */
    public final AtomicInteger closes = new AtomicInteger();

    /** Whether a stop is allowed to confirm that the tree is gone. */
    public boolean terminateSucceeds = true;

    /** Whether a release is allowed to confirm that the containment is closed. */
    public boolean closeSucceeds = true;

    /** Whether this containment was established at spawn. */
    public boolean established = true;

    /** The recorded terminal proof. */
    public Proof proof = Proof.NOT_PROVEN;

    /** How many times liveness was asked, so a proof reader can be shown not to ask it. */
    public final AtomicInteger treeAliveCalls = new AtomicInteger();

    /** The answer of {@link #exitCode()}. */
    public int exitCode;

    /** The answer of {@link #pid()}. */
    public long pid = 4242L;

    private boolean closed;

    @Override
    public long pid()
    {
        return pid;
    }

    @Override
    public boolean established()
    {
        return established;
    }

    @Override
    public Proof proof()
    {
        return proof;
    }

    @Override
    public boolean isTreeAlive()
    {
        treeAliveCalls.incrementAndGet();
        return treeAlive.get();
    }

    @Override
    public int exitCode()
    {
        return exitCode;
    }

    @Override
    public boolean terminateAndAwait(long waitMs)
    {
        terminates.incrementAndGet();
        if (terminateSucceeds)
        {
            treeAlive.set(false);
        }
        return terminateSucceeds;
    }

    @Override
    public boolean tryClose()
    {
        if (closed)
        {
            return true;
        }
        if (!closeSucceeds)
        {
            return false;
        }
        closed = true;
        closes.incrementAndGet();
        treeAlive.set(false);
        return true;
    }

    @Override
    public void close()
    {
        if (!tryClose())
        {
            throw new IllegalStateException("The fake containment is retained."); //$NON-NLS-1$
        }
    }

    @Override
    public String backend()
    {
        return "fakeContainment"; //$NON-NLS-1$
    }
}
