/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import java.security.SecureRandom;

import org.eclipse.jface.preference.IPreferenceStore;

import ru.aiedt.mcp.server.Activator;

/**
 * Optional bearer-token gate for the MCP HTTP server.
 *
 * <p>Disabled by default: when {@link PrefKeys#PREF_AUTH_ENABLED} is
 * false or no token is configured, {@link #activeToken()} returns {@code null}
 * and the server behaves exactly as before (unauthenticated localhost). When a
 * token is configured the server requires {@code Authorization: Bearer <token>}.
 *
 * <p>The token is stored in the plugin preference store. A future hardening can
 * move it to Equinox secure storage ({@code org.eclipse.equinox.security}); the
 * gate logic here is storage-agnostic.
 */
public final class McpAuth
{
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray(); //$NON-NLS-1$

    private McpAuth()
    {
        // utility
    }

    /**
     * The token the server currently requires, or {@code null} when authentication
     * is off (disabled preference, or enabled but no token set). A {@code null}
     * result means "allow all" - the default, zero-behaviour-change state.
     */
    public static String activeToken()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return null;
        }
        IPreferenceStore store = activator.getPreferenceStore();
        if (store == null || !store.getBoolean(PrefKeys.PREF_AUTH_ENABLED))
        {
            return null;
        }
        String token = store.getString(PrefKeys.PREF_AUTH_TOKEN);
        if (token == null || token.trim().isEmpty())
        {
            return null;
        }
        return token.trim();
    }

    /**
     * Extracts the token from an {@code Authorization: Bearer <token>} header value
     * (case-insensitive scheme), or {@code null} when absent / malformed.
     */
    public static String extractBearer(String authorizationHeader)
    {
        if (authorizationHeader == null)
        {
            return null;
        }
        String h = authorizationHeader.trim();
        if (h.length() <= 7 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) //$NON-NLS-1$
        {
            return null;
        }
        String token = h.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Constant-time string comparison (length-independent decision is leaked, but
     * not character positions). Used so a wrong token cannot be guessed by timing.
     */
    public static boolean constantTimeEquals(String a, String b)
    {
        if (a == null || b == null)
        {
            return false;
        }
        byte[] ba = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (ba.length != bb.length)
        {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < ba.length; i++)
        {
            diff |= ba[i] ^ bb[i];
        }
        return diff == 0;
    }

    /** Generates a fresh 256-bit token as a 64-char lowercase hex string. */
    public static String generateToken()
    {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
        {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }
}
