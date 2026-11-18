/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.support;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded exclusion for one resolved template owner, through persistence and export acknowledgement. */
public final class TemplateMutationLock
{
    private final Map<String, Entry> entries = new HashMap<>();
    private static final class Entry
    {
        final ReentrantLock lock = new ReentrantLock(true);
        int references;
    }

    public Lease acquire(String identity, long timeoutMs) throws InterruptedException, TimeoutException
    {
        Entry entry;
        synchronized (entries)
        {
            entry = entries.computeIfAbsent(identity, key -> new Entry());
            entry.references++;
        }
        boolean acquired = false;
        try
        {
            acquired = entry.lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired)
            {
                throw new TimeoutException("Another operation still owns this template");
            }
            return new Lease(identity, entry);
        }
        finally
        {
            if (!acquired) releaseReference(identity, entry);
        }
    }

    private void releaseReference(String identity, Entry entry)
    {
        synchronized (entries)
        {
            if (--entry.references == 0) entries.remove(identity, entry);
        }
    }

    int entryCount()
    {
        synchronized (entries) { return entries.size(); }
    }

    public final class Lease implements AutoCloseable
    {
        private final String identity;
        private final Entry entry;
        private boolean closed;
        private Lease(String identity, Entry entry) { this.identity = identity; this.entry = entry; }
        @Override public void close()
        {
            if (!closed)
            {
                closed = true;
                entry.lock.unlock();
                releaseReference(identity, entry);
            }
        }
    }
}
