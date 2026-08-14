/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;

import org.eclipse.jface.preference.IPreferenceStore;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;

/**
 * How much of the heap is still occupied once a collection has run, and whether that leaves room to
 * start an expensive tool.
 * <p>
 * The plugin shares one JVM with the whole of EDT, and a long series of expensive calls can walk that
 * heap up to its ceiling. What follows is not a failed call: the heap gives out under whichever thread
 * asks for memory next, the workbench event loop dies with it, and the server stops answering at all -
 * so the agent loses not just the call it made but every call it would have made afterwards. A tool
 * turned away with a reason costs one answer; a tool that tips the heap over costs the session.
 * </p>
 * <p>
 * Two figures are taken, and a refusal needs both: what survived the last collection
 * ({@link MemoryPoolMXBean#getCollectionUsage()}) and what is occupied at this instant. Neither is
 * sound on its own - the reasoning is in {@link #refusesWork(Reading, int)} - and requiring both means
 * the guard fires on a heap that is genuinely full rather than on one that merely looks it.
 * </p>
 * <p>
 * A JVM whose pools do not report a post-collection figure yields an untrustworthy reading, and an
 * untrustworthy reading never refuses anything: the guard exists to prevent a crash, not to invent one.
 * </p>
 */
public final class HeapHeadroom
{
    private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;

    private static final int FULL_PERCENT = 100;

    /**
     * How little room may be left before an expensive call is turned away, whatever the share says.
     * <p>
     * A share is the wrong unit for "is there room for one more call", and shipping it alone was a
     * mistake caught on a live stand: a configuration of ERP size holds 3787 MB of a 4096 MB heap
     * while sitting idle and perfectly usable - 92%, which turned every heavy tool away on a stand
     * that had 309 MB to work with. The heap that actually died had 6 MB. What separates those two is
     * not the proportion, which is nearly the same, but how much is left in absolute terms.
     * </p>
     * <p>
     * Both conditions are required, and each covers what the other gets wrong. The share alone
     * refuses a large configuration that is merely large. This floor alone would refuse a small heap
     * for being small - on {@code -Xmx512m} a quarter gigabyte free is half the heap and entirely
     * healthy - so it only ever applies once the share agrees the heap is nearly full.
     * </p>
     */
    private static final long MIN_FREE_MEGABYTES = 256L;

    private HeapHeadroom()
    {
    }

    /**
     * What the heap looks like right now: what survived the last collection, and what is occupied at
     * this instant.
     */
    public static final class Reading
    {
        private final long retainedBytes;

        private final long liveBytes;

        private final long ceilingBytes;

        private final boolean afterCollection;

        /**
         * @param retainedBytes bytes still occupied when the last collection finished
         * @param liveBytes bytes occupied at this instant, garbage included
         * @param ceilingBytes the largest the heap may grow to
         * @param afterCollection whether {@code retainedBytes} is a complete post-collection figure
         */
        Reading(long retainedBytes, long liveBytes, long ceilingBytes, boolean afterCollection)
        {
            this.retainedBytes = retainedBytes;
            this.liveBytes = liveBytes;
            this.ceilingBytes = ceilingBytes;
            this.afterCollection = afterCollection;
        }

        /**
         * @return bytes still occupied, measured after a collection when {@link #isTrustworthy()}
         */
        public long getRetainedBytes()
        {
            return retainedBytes;
        }

        /**
         * @return bytes occupied at this instant, garbage included
         */
        public long getLiveBytes()
        {
            return liveBytes;
        }

        /**
         * @return how much of the ceiling is occupied at this instant, 0 to 100
         */
        public int getLivePercent()
        {
            return percentOf(liveBytes, ceilingBytes);
        }

        /**
         * @return occupied megabytes at this instant
         */
        public long getLiveMegabytes()
        {
            return liveBytes / BYTES_PER_MEGABYTE;
        }

        /**
         * @return the largest the heap may grow to, in bytes, or 0 when the JVM does not say
         */
        public long getCeilingBytes()
        {
            return ceilingBytes;
        }

        /**
         * Whether this reading may be acted on.
         *
         * @return <code>true</code> when the figure came from a post-collection measurement and there
         *         is a known ceiling to compare it against
         */
        public boolean isTrustworthy()
        {
            return afterCollection && ceilingBytes > 0;
        }

        /**
         * @return how much of the ceiling is occupied, 0 to 100
         */
        public int getPercentUsed()
        {
            return percentOf(retainedBytes, ceilingBytes);
        }

        /**
         * @return occupied megabytes
         */
        public long getRetainedMegabytes()
        {
            return retainedBytes / BYTES_PER_MEGABYTE;
        }

        /**
         * @return the ceiling in megabytes
         */
        public long getCeilingMegabytes()
        {
            return ceilingBytes / BYTES_PER_MEGABYTE;
        }

        /**
         * What is left to work with, measured against what survives collection rather than against
         * the moment's occupancy - garbage still counts as room, because collecting it is what the
         * next allocation will do.
         *
         * @return free megabytes, never negative
         */
        public long getFreeMegabytes()
        {
            long free = (ceilingBytes - retainedBytes) / BYTES_PER_MEGABYTE;
            return free < 0L ? 0L : free;
        }

        /**
         * The reading in words, for a log line or a refusal an agent has to act on.
         *
         * @return a description such as {@code 3890 MB of 4096 MB (94%) still held after a collection}
         */
        public String describe()
        {
            return getRetainedMegabytes() + " MB of " + getCeilingMegabytes() + " MB (" + getPercentUsed() //$NON-NLS-1$ //$NON-NLS-2$
                + "%) still held" + (afterCollection ? " after a collection" : " (no post-collection figure)") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ", " + getLiveMegabytes() + " MB (" + getLivePercent() + "%) occupied now, " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + getFreeMegabytes() + " MB left to work with"; //$NON-NLS-1$
        }
    }

    /**
     * Measures the heap.
     *
     * @return the current reading, never <code>null</code>
     */
    public static Reading current()
    {
        long ceiling = Runtime.getRuntime().maxMemory();
        if (ceiling == Long.MAX_VALUE)
        {
            // No ceiling was set, so there is no proportion to speak of.
            ceiling = 0L;
        }
        long live = liveOccupancy();
        long retained = 0L;
        boolean anyHeapPool = false;
        boolean everyPoolAnswered = true;
        try
        {
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans())
            {
                if (pool == null || pool.getType() != MemoryType.HEAP)
                {
                    continue;
                }
                anyHeapPool = true;
                MemoryUsage usage = pool.getCollectionUsage();
                if (usage == null)
                {
                    // The share is measured against the ceiling of the whole heap, so a pool that will
                    // not say what it holds is not a pool to leave out of the sum - it would make the
                    // heap look emptier than it is and the guard would stay silent right up to the
                    // exhaustion it exists to prevent. One silent pool disqualifies the whole reading.
                    everyPoolAnswered = false;
                    continue;
                }
                retained += usage.getUsed();
            }
        }
        catch (RuntimeException e)
        {
            // A JVM that will not be asked about its pools is one this guard simply does not act on.
            everyPoolAnswered = false;
        }
        boolean afterCollection = anyHeapPool && everyPoolAnswered;
        if (!afterCollection)
        {
            retained = live;
        }
        return new Reading(retained, live, ceiling, afterCollection);
    }

    /**
     * How full the heap may be before expensive work is turned away.
     * <p>
     * One reader for one setting: the endpoint gates on it, the health probe and {@code self_status}
     * report it, and a second copy of the fallback logic would eventually disagree with this one.
     * </p>
     *
     * @return the threshold as a percentage; a value outside 1..99 switches the guard off
     */
    public static int refusalPercent()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return PrefKeys.DEFAULT_HEAP_REFUSAL_PERCENT;
        }
        IPreferenceStore store = activator.getPreferenceStore();
        if (store == null)
        {
            return PrefKeys.DEFAULT_HEAP_REFUSAL_PERCENT;
        }
        // Read as it stands: a value out of range is how the guard is asked for, not a mistake to
        // correct back to the shipped default.
        return store.getInt(PrefKeys.PREF_HEAP_REFUSAL_PERCENT);
    }

    /**
     * Whether an expensive call should be turned away rather than started.
     * <p>
     * Both figures have to be past the threshold, and each covers the other's blind spot. The
     * post-collection figure alone would refuse work on a healthy heap whenever a tool had just
     * allocated hard, because live occupancy counts garbage that is about to be swept. The live figure
     * alone would refuse on a snapshot that has since gone stale: the last collection ran while a
     * large temporary was still held, the temporary has been dropped, and nothing has collected since
     * to say so - and a guard that then refuses everything suppresses the very allocation that would
     * trigger the next collection and clear the reading. Together they refuse only a heap that is full
     * both by history and by what is in it right now.
     * </p>
     *
     * <p>
     * A third condition joins them: there must also be less than {@link #MIN_FREE_MEGABYTES} left in
     * absolute terms. A share says how full the heap is relative to itself, which is not the same
     * question as whether there is room for one more call - and on a large configuration the two
     * answers differ enough to matter.
     * </p>
     *
     * @param reading the current reading
     * @param thresholdPercent the share of the heap that may be held before work is refused; a value
     *            outside 1..99 switches the guard off
     * @return <code>true</code> when the call should be refused
     */
    public static boolean refusesWork(Reading reading, int thresholdPercent)
    {
        if (reading == null || !reading.isTrustworthy())
        {
            return false;
        }
        if (thresholdPercent <= 0 || thresholdPercent >= FULL_PERCENT)
        {
            return false;
        }
        if (reading.getPercentUsed() < thresholdPercent || reading.getLivePercent() < thresholdPercent)
        {
            return false;
        }
        return reading.getFreeMegabytes() < MIN_FREE_MEGABYTES;
    }

    /**
     * Live heap occupancy, garbage included - the fallback figure, reported but never acted on.
     *
     * @return occupied bytes, or 0 when the JVM will not say
     */
    private static long liveOccupancy()
    {
        try
        {
            return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        }
        catch (RuntimeException e)
        {
            return 0L;
        }
    }

    /**
     * What share of the ceiling the given number of bytes is.
     *
     * @param used occupied bytes
     * @param ceiling the heap ceiling in bytes
     * @return 0 to 100; 0 when there is no ceiling to divide by
     */
    static int percentOf(long used, long ceiling)
    {
        if (ceiling <= 0L || used <= 0L)
        {
            return 0;
        }
        if (used >= ceiling)
        {
            return FULL_PERCENT;
        }
        return (int)(used * FULL_PERCENT / ceiling);
    }
}
