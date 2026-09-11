/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import java.security.SecureRandom;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.osgi.service.prefs.BackingStoreException;

import ru.aiedt.mcp.server.Activator;

/**
 * The bearer token every request to {@code /mcp} has to carry.
 * <p>
 * The token requests are checked against is {@link #activeToken()}, a field of this class, and not
 * the preference store: the store changes the moment a value is written into it, while the value
 * reaches the disk only when the node is flushed, and a flush can fail. A token that started
 * answering requests before it was saved would be gone after the next restart, with every client
 * configured for it. So a token becomes active only after {@link TokenStore#flush()} returned, and
 * a failed flush leaves the previous token in force and puts the previous value back into the
 * store.
 * </p>
 * <p>
 * There is no switch. A server that has no token does not open its socket.
 * </p>
 */
public final class McpAuth
{
    /**
     * Where the token is kept between starts.
     * <p>
     * The preference-backed store is the one the server uses; a test hands in one whose flush can
     * be delayed or made to fail.
     * </p>
     */
    public interface TokenStore
    {
        /**
         * The stored token.
         *
         * @return the token, or an empty string when none is stored
         */
        String read();

        /**
         * Puts a token into the store, in memory.
         *
         * @param token the token; an empty string clears it
         */
        void write(String token);

        /**
         * Carries what was written to the disk.
         *
         * @throws BackingStoreException when the disk did not take it
         */
        void flush() throws BackingStoreException;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final char[] HEX = "0123456789abcdef".toCharArray(); //$NON-NLS-1$

    private static final int TOKEN_BYTES = 32;

    /** The store in use; <code>null</code> means the preference-backed one. */
    private static volatile TokenStore store;

    /** The token requests are checked against; <code>null</code> until one has been adopted. */
    private static volatile String published;

    private McpAuth()
    {
        // utility
    }

    /**
     * Replaces the store, for a test; <code>null</code> restores the preference-backed one.
     *
     * @param replacement the store to use
     */
    public static synchronized void useStore(TokenStore replacement)
    {
        store = replacement;
    }

    /**
     * Forgets the active token, for a test that wants a server to start from nothing.
     */
    public static synchronized void forgetPublished()
    {
        published = null;
    }

    /**
     * The token the server currently requires.
     *
     * @return the token, or <code>null</code> when none has been adopted yet - which turns every
     *         request away
     */
    public static String activeToken()
    {
        return published;
    }

    /**
     * Makes the stored token the active one, creating and saving a token when none is stored.
     * <p>
     * A stored token is adopted as it is, so a second start changes nothing. A created token is
     * written and flushed before it is adopted; when the flush fails the store is put back to what
     * it held and the caller must not open its socket.
     * </p>
     *
     * @return <code>null</code> when a token is active, otherwise why the server may not start
     */
    public static synchronized String adoptStoredToken()
    {
        TokenStore keeper = currentStore();
        String stored = trimmed(keeper.read());
        if (!stored.isEmpty())
        {
            published = stored;
            return null;
        }
        String created = generateToken();
        keeper.write(created);
        try
        {
            keeper.flush();
        }
        catch (BackingStoreException | RuntimeException notSaved)
        {
            return "the bearer token could not be saved to the preference store: " //$NON-NLS-1$
                + describe(notSaved) + ". The server does not open a socket without a saved token." //$NON-NLS-1$
                + putBack(keeper, stored);
        }
        published = created;
        return null;
    }

    /**
     * Saves a token and, once it is saved, makes it the active one.
     *
     * @param token the token to publish; an empty value is replaced by a new token
     * @return <code>null</code> when the token is active, otherwise why the previous one still is
     */
    public static synchronized String publish(String token)
    {
        String candidate = trimmed(token);
        if (candidate.isEmpty())
        {
            candidate = generateToken();
        }
        TokenStore keeper = currentStore();
        String previous = keeper.read();
        keeper.write(candidate);
        try
        {
            keeper.flush();
        }
        catch (BackingStoreException | RuntimeException notSaved)
        {
            return "the bearer token could not be saved to the preference store: " //$NON-NLS-1$
                + describe(notSaved) + ". The previous token stays in force." + putBack(keeper, previous); //$NON-NLS-1$
        }
        published = candidate;
        return null;
    }

    /**
     * Extracts the token from an {@code Authorization: Bearer <token>} header value
     * (case-insensitive scheme), or {@code null} when absent / malformed.
     *
     * @param authorizationHeader the header value
     * @return the token, or <code>null</code>
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
     *
     * @param a one string
     * @param b the other
     * @return <code>true</code> when both are non-null and equal
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

    /**
     * Generates a fresh 256-bit token as a 64-char lowercase hex string.
     *
     * @return the token
     */
    public static String generateToken()
    {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
        {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    /**
     * Puts the previous value back after a flush that failed, and tries to carry that to the disk
     * as well: a flush that failed halfway may have left the new value there, and the store in
     * memory alone would then disagree with what the next start reads.
     *
     * @return an empty string when the previous value reached the disk, otherwise what the caller
     *         has to be told about the next start
     */
    private static String putBack(TokenStore keeper, String previous)
    {
        try
        {
            keeper.write(previous);
            keeper.flush();
            return ""; //$NON-NLS-1$
        }
        catch (BackingStoreException | RuntimeException stillNotSaved)
        {
            Activator.logWarning("the previous bearer token could not be flushed back to the preference store: " //$NON-NLS-1$
                + describe(stillNotSaved));
            return " The previous value could not be flushed back either (" + describe(stillNotSaved) //$NON-NLS-1$
                + "): after a restart the store may hold the rejected token - check the preference page."; //$NON-NLS-1$
        }
    }

    private static TokenStore currentStore()
    {
        TokenStore keeper = store;
        return keeper != null ? keeper : PreferenceTokenStore.INSTANCE;
    }

    private static String trimmed(String text)
    {
        return text == null ? "" : text.trim(); //$NON-NLS-1$
    }

    private static String describe(Throwable failure)
    {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    /**
     * The store the server uses: the plugin's preference store, flushed through its instance node.
     */
    private enum PreferenceTokenStore
        implements TokenStore
    {
        INSTANCE;

        @Override
        public String read()
        {
            IPreferenceStore preferences = preferences();
            return preferences == null ? "" : preferences.getString(PrefKeys.PREF_AUTH_TOKEN); //$NON-NLS-1$
        }

        @Override
        public void write(String token)
        {
            IPreferenceStore preferences = preferences();
            if (preferences != null)
            {
                preferences.setValue(PrefKeys.PREF_AUTH_TOKEN, token);
            }
        }

        @Override
        public void flush() throws BackingStoreException
        {
            if (preferences() == null)
            {
                throw new BackingStoreException("the plugin is not active, so there is no preference store"); //$NON-NLS-1$
            }
            InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID).flush();
        }

        private static IPreferenceStore preferences()
        {
            Activator activator = Activator.getDefault();
            return activator == null ? null : activator.getPreferenceStore();
        }
    }
}
