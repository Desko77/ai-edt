/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

/**
 * A result whose answer a client may keep for a while instead of asking again.
 * <p>
 * The tool catalogue is the case this exists for. It is sent whole before a client makes its first
 * call - measured on this server at over 130 000 characters - and it is sent again at the start of
 * every session, though what it describes changes only when the plugin is replaced or somebody
 * flips a preset. Shortening the catalogue attacks the size; saying how long it keeps attacks the
 * repetition, and the second is the larger of the two.
 * </p>
 * <p>
 * Implemented as an interface with methods rather than fields on purpose: the serializer writes
 * fields, so a result carrying these does not gain them on the wire until the response layer
 * decides the caller is one that understands them. A caller of an older revision gets the answer it
 * has always got.
 * </p>
 */
public interface CacheableResult
{
    /** Cache scope: any intermediary may hold the answer. */
    String SCOPE_PUBLIC = "public"; //$NON-NLS-1$

    /** Cache scope: only the client that asked may hold the answer. */
    String SCOPE_PRIVATE = "private"; //$NON-NLS-1$

    /**
     * How long the answer stays fresh.
     *
     * @return the lifetime in milliseconds.
     */
    long getTtlMs();

    /**
     * Who may hold the answer.
     *
     * @return {@link #SCOPE_PUBLIC} or {@link #SCOPE_PRIVATE}.
     */
    String getCacheScope();
}
