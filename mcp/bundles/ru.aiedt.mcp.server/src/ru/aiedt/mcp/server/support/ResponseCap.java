/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.nio.charset.StandardCharsets;

/**
 * Caps the size of a tool response so one runaway result cannot exhaust the heap
 * through the redact / parse / frame amplification, nor swamp the client.
 * <p>
 * The truncation is done on the result string, at a UTF-8 code-point boundary, so
 * the shortened text is always valid and never ends on half of a multi-byte
 * character. Callers apply this <em>before</em> the response is redacted, parsed
 * or framed, so the giant original becomes eligible for collection instead of
 * being copied several times over.
 */
public final class ResponseCap
{
    /**
     * Absolute ceiling the configured limit is clamped to, so a mistyped
     * preference or a client-supplied override cannot re-open the heap risk this
     * cap exists to close. 32 MB.
     */
    public static final int HARD_CEILING_BYTES = 32 * 1024 * 1024;

    private ResponseCap()
    {
    }

    /**
     * Resolves a raw limit (from the per-call scope or the preference) to the
     * effective byte cap: {@code <= 0} means "no cap" (returns 0); anything larger
     * is clamped to {@link #HARD_CEILING_BYTES}.
     *
     * @param rawLimit the requested limit in bytes
     * @return the effective cap in bytes, or 0 when capping is off
     */
    public static int applyCeiling(long rawLimit)
    {
        if (rawLimit <= 0)
        {
            return 0;
        }
        return (int) Math.min(rawLimit, HARD_CEILING_BYTES);
    }

    /**
     * Whether {@code s} encodes to more than {@code maxBytes} UTF-8 bytes.
     * A non-positive {@code maxBytes} means "no cap" and always returns false.
     * Cheap-exits without encoding when the character count alone proves it fits
     * (UTF-8 never uses more than 3 bytes per {@code char}).
     */
    public static boolean exceeds(String s, int maxBytes)
    {
        if (s == null || maxBytes <= 0)
        {
            return false;
        }
        if ((long) s.length() * 3 <= maxBytes)
        {
            return false;
        }
        return byteLength(s) > maxBytes;
    }

    /**
     * The UTF-8 byte length of {@code s}, computed without materializing the byte
     * array (so it is safe to call on a very large string just to size it).
     * Returned as a {@code long}: a String can hold up to ~2G chars, whose UTF-8
     * form can exceed {@link Integer#MAX_VALUE} bytes, and an {@code int} counter
     * would overflow to a negative value and silently defeat the cap.
     *
     * @param s the string to measure (must not be {@code null})
     * @return its length in UTF-8 bytes
     */
    public static long byteLength(String s)
    {
        long bytes = 0;
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c < 0x80)
            {
                bytes += 1;
            }
            else if (c < 0x800)
            {
                bytes += 2;
            }
            else if (Character.isHighSurrogate(c) && i + 1 < s.length()
                && Character.isLowSurrogate(s.charAt(i + 1)))
            {
                bytes += 4; // a surrogate pair is one 4-byte code point
                i++;
            }
            else
            {
                bytes += 3;
            }
        }
        return bytes;
    }

    /**
     * Returns {@code s} unchanged when it fits in {@code maxBytes} UTF-8 bytes,
     * otherwise the longest prefix that fits, cut at a UTF-8 code-point boundary
     * (never ending on a continuation byte, never splitting a character).
     * A non-positive {@code maxBytes} disables the cap and returns {@code s}.
     *
     * @param s the text to bound (must not be {@code null})
     * @param maxBytes the byte ceiling, or {@code <= 0} for no cap
     * @return {@code s}, or its longest code-point-aligned prefix within the cap
     */
    public static String truncateUtf8(String s, int maxBytes)
    {
        if (maxBytes <= 0 || (long) s.length() * 3 <= maxBytes)
        {
            return s;
        }
        // Cap the transient allocation: at most maxBytes chars is enough to fill
        // maxBytes UTF-8 bytes (a char never encodes to fewer than one byte), so we
        // never encode the whole giant string.
        String head = s.length() > maxBytes ? s.substring(0, maxBytes) : s;
        byte[] bytes = head.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes)
        {
            return head;
        }
        int end = maxBytes;
        // Back off over UTF-8 continuation bytes (10xxxxxx) to the start of the
        // last whole code point, so the cut never lands inside a character.
        while (end > 0 && (bytes[end] & 0xC0) == 0x80)
        {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
