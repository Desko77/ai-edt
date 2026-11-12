/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The bounded store of pinned selections behind a {@code resultId}.
 * <p>
 * Separate from {@link PendingWorkRegistry} on purpose. A Pending entry is consumed by the read
 * that collects it; a pinned selection is read repeatedly, page by page, and must outlive the call
 * that produced it. Sharing the registry would either make Pending entries (and their results)
 * linger for the selection's lifetime, or make a second page consume the entry the first page was
 * served from.
 * </p>
 * <p>
 * The store is bounded twice: a record expires after {@link #DEFAULT_TTL_MILLIS} and the sum of all
 * retained content is capped at {@link #DEFAULT_MAX_MEMORY_BYTES}. When the cap is reached, the
 * oldest records go first. The memory figure is an estimate of the retained characters and object
 * allowances, documented on {@link PinnedResult#estimatedBytes()}; it covers the whole retained
 * selection, not only the first page, because that is what the process actually keeps alive.
 * </p>
 * <p>
 * The token is not a bare random key. Two bits that a later call cannot recover any other way -
 * whether the continuation came from a short-form page and whether its collection ever finished -
 * are written into the token itself and sealed with a digest over a random per-process secret.
 * Sealing them is what makes the post-eviction rules enforceable: a caller that cleared the
 * short-form bit would otherwise turn a continuation that must be refused into one that is
 * rebuilt. The store answers honestly after the record is gone without keeping an unbounded list
 * of tombstones; a token whose flags or digest do not match is simply not one this process issued.
 * </p>
 */
public final class PinnedResultCache
{
    /** How long a pinned selection is kept. Fifteen minutes, as approved for the first release. */
    public static final long DEFAULT_TTL_MILLIS = 15L * 60L * 1000L;

    /** The ceiling on everything the cache retains. Sixty-four megabytes, as approved. */
    public static final long DEFAULT_MAX_MEMORY_BYTES = 64L * 1024L * 1024L;

    /** First part of every token this store issues. */
    private static final String TOKEN_PREFIX = "r1."; //$NON-NLS-1$

    private static final int TOKEN_HEX_LENGTH = 32;

    /** The flags segment of a token whose two facts are both clear. */
    private static final String NO_FLAGS = "-"; //$NON-NLS-1$

    /** How many hex characters of the digest seal the token's flags. */
    private static final int TOKEN_MAC_CHARS = 8;

    /** The per-process secret behind the seal, drawn once at class load and never stored anywhere. */
    private static final byte[] TOKEN_SECRET = new byte[16];

    static
    {
        new SecureRandom().nextBytes(TOKEN_SECRET);
    }

    /** A process-wide store, so the project-close and bundle-stop hooks reach one cache. */
    private static final PinnedResultCache SHARED = new PinnedResultCache(
        System::currentTimeMillis, DEFAULT_TTL_MILLIS, DEFAULT_MAX_MEMORY_BYTES);

    private final LongSupplier clock;

    private final long ttlMillis;

    private final long maxMemoryBytes;

    /** Insertion order is oldest-first, which is the eviction order. */
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    private long memoryBytes;

    /**
     * Creates a store.
     *
     * @param clock the time source, so a test never has to wait for a fifteen-minute expiry
     * @param ttlMillis how long a record lives; must be positive
     * @param maxMemoryBytes the ceiling on retained content; must be positive
     */
    public PinnedResultCache(LongSupplier clock, long ttlMillis, long maxMemoryBytes)
    {
        if (clock == null)
        {
            throw new IllegalArgumentException("a cache needs a clock"); //$NON-NLS-1$
        }
        if (ttlMillis <= 0 || maxMemoryBytes <= 0)
        {
            throw new IllegalArgumentException("the cache limits must be positive"); //$NON-NLS-1$
        }
        this.clock = clock;
        this.ttlMillis = ttlMillis;
        this.maxMemoryBytes = maxMemoryBytes;
    }

    /**
     * The store the lifecycle hooks clear.
     *
     * @return the process-wide store
     */
    public static PinnedResultCache shared()
    {
        return SHARED;
    }

    /**
     * Stores a selection under a caller-chosen identity.
     * <p>
     * An identity already present is replaced, so a rebuilt selection may take the same place.
     * A selection whose estimate alone exceeds the whole cap is refused rather than stored: the cap
     * is the promise, and one oversized record would break it for every other caller.
     * </p>
     *
     * @param id the identity
     * @param result the selection
     * @return {@code true} when it was stored
     */
    public synchronized boolean put(UUID id, PinnedResult result)
    {
        if (id == null || result == null)
        {
            throw new IllegalArgumentException("a stored selection needs an identity and a value"); //$NON-NLS-1$
        }
        long now = clock.getAsLong();
        purgeExpired(now);
        long size = result.estimatedBytes();
        if (size > maxMemoryBytes)
        {
            return false;
        }
        Entry replaced = entries.remove(id);
        if (replaced != null)
        {
            memoryBytes -= replaced.result.estimatedBytes();
        }
        while (!entries.isEmpty() && memoryBytes + size > maxMemoryBytes)
        {
            evictOldest();
        }
        entries.put(id, new Entry(result, now));
        memoryBytes += size;
        return true;
    }

    /**
     * Reads a selection, expiring it first when its time is up.
     *
     * @param id the identity
     * @return the selection, or {@code null} when it is absent or expired
     */
    public synchronized PinnedResult get(UUID id)
    {
        if (id == null)
        {
            return null;
        }
        purgeExpired(clock.getAsLong());
        Entry entry = entries.get(id);
        return entry == null ? null : entry.result;
    }

    /**
     * Removes one selection.
     *
     * @param id the identity
     * @return {@code true} when something was removed
     */
    public synchronized boolean remove(UUID id)
    {
        if (id == null)
        {
            return false;
        }
        Entry removed = entries.remove(id);
        if (removed == null)
        {
            return false;
        }
        memoryBytes -= removed.result.estimatedBytes();
        return true;
    }

    /**
     * Drops every selection, as the plugin does when it stops.
     */
    public synchronized void clear()
    {
        entries.clear();
        memoryBytes = 0;
    }

    /**
     * Drops every selection that may hold content from a closing project.
     * <p>
     * A selection that recorded no project is dropped too: without the provenance there is no way
     * to prove it did not read the closing one, and keeping unprovable content is worse than
     * making the caller start over.
     * </p>
     *
     * @param projectName the closing project's name
     * @return how many selections were removed
     */
    public synchronized int clearProject(String projectName)
    {
        if (projectName == null || projectName.trim().isEmpty())
        {
            return 0;
        }
        purgeExpired(clock.getAsLong());
        int removed = 0;
        Iterator<Map.Entry<UUID, Entry>> walk = entries.entrySet().iterator();
        while (walk.hasNext())
        {
            Entry entry = walk.next().getValue();
            if (entry.result.projects().isEmpty() || entry.result.projects().contains(projectName))
            {
                walk.remove();
                memoryBytes -= entry.result.estimatedBytes();
                removed++;
            }
        }
        return removed;
    }

    /**
     * How many selections are held now, after expiring what is due.
     *
     * @return the count
     */
    public synchronized int size()
    {
        purgeExpired(clock.getAsLong());
        return entries.size();
    }

    /**
     * The estimated retained bytes, after expiring what is due.
     *
     * @return the estimate
     */
    public synchronized long memoryBytes()
    {
        purgeExpired(clock.getAsLong());
        return memoryBytes;
    }

    /**
     * How long a record lives.
     *
     * @return the TTL in milliseconds
     */
    public long ttlMillis()
    {
        return ttlMillis;
    }

    /**
     * The ceiling on retained content.
     *
     * @return the cap in bytes
     */
    public long maxMemoryBytes()
    {
        return maxMemoryBytes;
    }

    /**
     * Issues the token for a selection.
     * <p>
     * Two facts are carried inside it, because after eviction there is no record to ask: whether
     * the page that produced this continuation had lost some of its arguments, and whether the
     * collection itself ever finished. A short-form token is never rebuilt - the arguments it
     * carries are not the original call. An incomplete token is never re-collected - a second run
     * constrained the same way would produce a different subset.
     * </p>
     * <p>
     * The form is {@code r1.<identity>.<flags>.<seal>}: the identity is the selection's UUID in
     * hex, the flags segment is {@code i}, {@code s}, both, or {@code -} for neither, and the seal
     * is a digest of the identity and the flags under a random per-process secret. Clearing or
     * altering the flags therefore breaks the seal, and the token is refused as one this process
     * never issued - which is what keeps the two rules above binding after eviction.
     * </p>
     *
     * @param id the selection's identity
     * @param incomplete whether the collection is unfinished
     * @param shortForm whether some arguments were elided from the continuation
     * @return the token
     */
    public static String resultId(UUID id, boolean incomplete, boolean shortForm)
    {
        if (id == null)
        {
            throw new IllegalArgumentException("a result id needs a selection identity"); //$NON-NLS-1$
        }
        StringBuilder flags = new StringBuilder(2);
        if (incomplete)
        {
            flags.append('i');
        }
        if (shortForm)
        {
            flags.append('s');
        }
        String flagsSegment = flags.length() == 0 ? NO_FLAGS : flags.toString();
        String hex = id.toString().replace("-", ""); //$NON-NLS-1$ //$NON-NLS-2$
        return TOKEN_PREFIX + hex + "." + flagsSegment + "." + tokenSeal(hex, flagsSegment); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reads a token this store issued.
     *
     * @param resultId the token; may be {@code null}
     * @return its parts, or {@code null} when it is not one of ours
     */
    public static ParsedResultId parse(String resultId)
    {
        if (resultId == null || !resultId.startsWith(TOKEN_PREFIX))
        {
            return null;
        }
        String[] parts = resultId.substring(TOKEN_PREFIX.length()).split("\\."); //$NON-NLS-1$
        if (parts.length != 3)
        {
            return null;
        }
        String hex = parts[0];
        String flagsSegment = parts[1];
        String seal = parts[2];
        if (hex.length() != TOKEN_HEX_LENGTH || !isHex(hex))
        {
            return null;
        }
        if (seal.length() != TOKEN_MAC_CHARS || !isHex(seal))
        {
            return null;
        }
        if (!MessageDigest.isEqual(seal.getBytes(StandardCharsets.US_ASCII),
            tokenSeal(hex, flagsSegment).getBytes(StandardCharsets.US_ASCII)))
        {
            return null;
        }
        boolean incomplete = false;
        boolean shortForm = false;
        if (!NO_FLAGS.equals(flagsSegment))
        {
            for (int i = 0; i < flagsSegment.length(); i++)
            {
                char flag = flagsSegment.charAt(i);
                if (flag == 'i' && !incomplete)
                {
                    incomplete = true;
                }
                else if (flag == 's' && !shortForm)
                {
                    shortForm = true;
                }
                else
                {
                    return null;
                }
            }
        }
        UUID id = new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16),
            Long.parseUnsignedLong(hex.substring(16), 16));
        return new ParsedResultId(id, incomplete, shortForm);
    }

    /**
     * The digest that seals a token's identity and flags.
     *
     * @param hex the selection identity in hex
     * @param flagsSegment the flags segment exactly as it appears in the token
     * @return the first digest characters in hex
     */
    private static String tokenSeal(String hex, String flagsSegment)
    {
        String authenticated = TOKEN_PREFIX + hex + "." + flagsSegment; //$NON-NLS-1$
        byte[] material = new byte[TOKEN_SECRET.length
            + authenticated.getBytes(StandardCharsets.UTF_8).length];
        System.arraycopy(TOKEN_SECRET, 0, material, 0, TOKEN_SECRET.length);
        byte[] encoded = authenticated.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(encoded, 0, material, TOKEN_SECRET.length, encoded.length);
        byte[] digest;
        try
        {
            digest = MessageDigest.getInstance("SHA-256").digest(material); //$NON-NLS-1$
        }
        catch (NoSuchAlgorithmException absent)
        {
            // SHA-256 is required of every Java runtime; a seal that silently became something
            // else would either reject every token or accept forged ones.
            throw new IllegalStateException("SHA-256 is unavailable", absent); //$NON-NLS-1$
        }
        StringBuilder hexDigest = new StringBuilder(TOKEN_MAC_CHARS);
        for (int i = 0; i < TOKEN_MAC_CHARS / 2; i++)
        {
            hexDigest.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
            hexDigest.append(Character.forDigit(digest[i] & 0xF, 16));
        }
        return hexDigest.toString();
    }

    private static boolean isHex(String text)
    {
        for (int i = 0; i < text.length(); i++)
        {
            char one = text.charAt(i);
            boolean digit = one >= '0' && one <= '9';
            boolean lower = one >= 'a' && one <= 'f';
            boolean upper = one >= 'A' && one <= 'F';
            if (!digit && !lower && !upper)
            {
                return false;
            }
        }
        return true;
    }

    private void purgeExpired(long now)
    {
        Iterator<Map.Entry<UUID, Entry>> walk = entries.entrySet().iterator();
        while (walk.hasNext())
        {
            Entry entry = walk.next().getValue();
            if (now - entry.storedAtMillis >= ttlMillis)
            {
                walk.remove();
                memoryBytes -= entry.result.estimatedBytes();
            }
        }
    }

    private void evictOldest()
    {
        Iterator<Entry> walk = entries.values().iterator();
        if (!walk.hasNext())
        {
            return;
        }
        Entry oldest = walk.next();
        walk.remove();
        memoryBytes -= oldest.result.estimatedBytes();
    }

    /** One retained selection and when it was stored. */
    private static final class Entry
    {
        private final PinnedResult result;

        private final long storedAtMillis;

        private Entry(PinnedResult result, long storedAtMillis)
        {
            this.result = result;
            this.storedAtMillis = storedAtMillis;
        }
    }

    /**
     * The parts of a token: the selection it names and the two facts that have to outlive it.
     */
    public static final class ParsedResultId
    {
        /** The selection identity. */
        public final UUID id;

        /** Whether the collection never finished. */
        public final boolean incomplete;

        /** Whether the page that issued this continuation had elided arguments. */
        public final boolean shortForm;

        private ParsedResultId(UUID id, boolean incomplete, boolean shortForm)
        {
            this.id = id;
            this.incomplete = incomplete;
            this.shortForm = shortForm;
        }
    }
}
