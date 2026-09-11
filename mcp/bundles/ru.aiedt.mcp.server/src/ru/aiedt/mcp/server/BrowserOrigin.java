/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a browser page may talk to this server, decided from the origin it sends.
 * <p>
 * A serialized origin is a scheme, a host and at most a port, nothing else. It is read as exactly
 * that: the host has to be a loopback name in full, not begin with one. A prefix test let
 * {@code http://localhost.evil.example} through, because it begins with {@code http://localhost}.
 * </p>
 * <p>
 * {@code null} is what a page sends when it has no origin to speak of: a page opened from a file,
 * and also a sandboxed frame that any site can create. It is therefore accepted only when the
 * operator says so.
 * </p>
 */
final class BrowserOrigin
{
    /** What a page without an origin sends, in as many letters. */
    static final String NULL_ORIGIN = "null"; //$NON-NLS-1$

    private static final String SCHEME_HTTP = "http"; //$NON-NLS-1$

    private static final String SCHEME_HTTPS = "https"; //$NON-NLS-1$

    /** The scheme of a VS Code webview; its host is an opaque id the editor makes up. */
    private static final String SCHEME_VSCODE_WEBVIEW = "vscode-webview"; //$NON-NLS-1$

    /** The loopback hosts, as {@link URI#getHost()} spells them - the IPv6 one keeps its brackets. */
    private static final Set<String> LOOPBACK_HOSTS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("localhost", "127.0.0.1", "[::1]"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final int MAX_PORT = 65535;

    private BrowserOrigin()
    {
        // Static entry point only.
    }

    /**
     * Tells whether an origin is one this server talks to.
     *
     * @param origin the value of the Origin header; must not be <code>null</code>
     * @param nullOriginAllowed whether the literal {@code null} origin is accepted
     * @return <code>true</code> when the page may talk to this server
     */
    static boolean accepted(String origin, boolean nullOriginAllowed)
    {
        if (NULL_ORIGIN.equals(origin))
        {
            return nullOriginAllowed;
        }
        URI uri;
        try
        {
            uri = new URI(origin);
        }
        catch (URISyntaxException | RuntimeException notAnOrigin)
        {
            // A port too long for an int comes out of the parser as a NumberFormatException rather
            // than a syntax error; neither is an origin.
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || uri.isOpaque())
        {
            return false;
        }
        // An origin carries no user, path, query or fragment; a value that does is not an origin
        // and is not a page this server knows.
        if (uri.getRawUserInfo() != null || !isEmpty(uri.getRawPath()) || uri.getRawQuery() != null
            || uri.getRawFragment() != null)
        {
            return false;
        }
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (SCHEME_HTTP.equals(lowerScheme) || SCHEME_HTTPS.equals(lowerScheme))
        {
            String host = uri.getHost();
            if (host == null || !LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT)))
            {
                return false;
            }
            int port = uri.getPort();
            return port == -1 || port >= 1 && port <= MAX_PORT;
        }
        if (SCHEME_VSCODE_WEBVIEW.equals(lowerScheme))
        {
            // The id the editor makes up is the host, and the whole of the authority.
            String host = uri.getHost();
            return host != null && !host.isEmpty() && uri.getPort() == -1;
        }
        return false;
    }

    private static boolean isEmpty(String text)
    {
        return text == null || text.isEmpty();
    }
}
