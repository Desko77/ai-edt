/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.osgi.service.prefs.BackingStoreException;

import ru.aiedt.mcp.server.settings.McpAuth;

/**
 * A real endpoint on a free port, for tests that need the wire rather than a method call.
 * <p>
 * The instance registry is pointed at a temporary directory for the life of the server, so a test
 * run never leaves a record among the records of the EDT instances on this machine. The token
 * comes from a store the test hands in - by default one in memory holding a token made up for the
 * run - so nothing is written into the preference store of the test workspace unless a test asks
 * for exactly that.
 * </p>
 */
final class LiveServer
    implements AutoCloseable
{
    private static final String REGISTRY_DIR_PROPERTY = "aiedt.instances.dir"; //$NON-NLS-1$

    /** A token store that lives in memory, whose flush a test can delay or break. */
    static class MemoryTokenStore
        implements McpAuth.TokenStore
    {
        private volatile String token;

        MemoryTokenStore(String token)
        {
            this.token = token == null ? "" : token; //$NON-NLS-1$
        }

        @Override
        public String read()
        {
            return token;
        }

        @Override
        public void write(String value)
        {
            token = value == null ? "" : value; //$NON-NLS-1$
        }

        @Override
        public void flush() throws BackingStoreException
        {
            // Memory needs no flush.
        }
    }

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private static final int READ_TIMEOUT_MILLIS = 30000;

    /** What came back over the wire. */
    static final class Response
    {
        final int code;

        final String body;

        final Map<String, List<String>> headers;

        Response(int code, String body, Map<String, List<String>> headers)
        {
            this.code = code;
            this.body = body;
            this.headers = headers;
        }

        /**
         * The first value of a response header, or <code>null</code>.
         *
         * @param name the header, case-insensitive
         * @return its first value
         */
        String header(String name)
        {
            for (Map.Entry<String, List<String>> entry : headers.entrySet())
            {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty())
                {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    private final McpHttpEndpoint endpoint;

    private final int port;

    private final Path registry;

    private final String previousRegistryDir;

    private final McpAuth.TokenStore tokens;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private LiveServer(McpHttpEndpoint endpoint, int port, Path registry, String previousRegistryDir,
        McpAuth.TokenStore tokens)
    {
        this.endpoint = endpoint;
        this.port = port;
        this.registry = registry;
        this.previousRegistryDir = previousRegistryDir;
        this.tokens = tokens;
    }

    /**
     * Starts a server on a port nobody holds, with a token made up for the run.
     *
     * @return the running server
     * @throws IOException when no socket can be opened
     */
    static LiveServer start() throws IOException
    {
        return start(new MemoryTokenStore("live-" + McpAuth.generateToken())); //$NON-NLS-1$
    }

    /**
     * Starts a server on a port nobody holds, taking its token from the given store.
     *
     * @param tokens where the token is; <code>null</code> for the preference store of the workspace
     * @return the running server
     * @throws IOException when no socket can be opened, or the token could not be saved
     */
    static LiveServer start(McpAuth.TokenStore tokens) throws IOException
    {
        Path registry = Files.createTempDirectory("aiedt-live"); //$NON-NLS-1$
        String previous = System.getProperty(REGISTRY_DIR_PROPERTY);
        System.setProperty(REGISTRY_DIR_PROPERTY, registry.toString());
        McpAuth.useStore(tokens);
        McpAuth.forgetPublished();
        McpHttpEndpoint endpoint = new McpHttpEndpoint();
        try
        {
            endpoint.start(freePort());
        }
        catch (IOException | RuntimeException failed)
        {
            McpAuth.useStore(null);
            McpAuth.forgetPublished();
            restoreRegistry(previous);
            deleteQuietly(registry);
            throw failed;
        }
        return new LiveServer(endpoint, endpoint.getPort(), registry, previous, tokens);
    }

    /**
     * The token the server requires right now.
     *
     * @return the active token
     */
    String token()
    {
        return McpAuth.activeToken();
    }

    /**
     * The store the server took its token from.
     *
     * @return the store, or <code>null</code> for the workspace preference store
     */
    McpAuth.TokenStore tokens()
    {
        return tokens;
    }

    /**
     * The Authorization header for the active token.
     *
     * @return the header value
     */
    String bearer()
    {
        return "Bearer " + McpAuth.activeToken(); //$NON-NLS-1$
    }

    /**
     * A port that was free a moment ago.
     *
     * @return the port
     * @throws IOException when no socket can be opened at all
     */
    static int freePort() throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            return socket.getLocalPort();
        }
    }

    McpHttpEndpoint endpoint()
    {
        return endpoint;
    }

    int port()
    {
        return port;
    }

    /**
     * Sends one request and reads the whole answer.
     * <p>
     * Through {@link HttpClient}, not {@code HttpURLConnection}: the latter drops the Origin header
     * on the floor as a restricted one, and a test of the origin gate that never sends an origin
     * proves nothing.
     * </p>
     *
     * @param method the HTTP method
     * @param path the path, starting with a slash
     * @param headers request headers; may be empty
     * @param body the request body, or <code>null</code> for none
     * @return the answer
     * @throws IOException when the connection fails
     */
    Response request(String method, String path, Map<String, String> headers, String body) throws IOException
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)) //$NON-NLS-1$
            .timeout(Duration.ofMillis(READ_TIMEOUT_MILLIS))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        for (Map.Entry<String, String> header : headers.entrySet())
        {
            request.header(header.getKey(), header.getValue());
        }
        try
        {
            HttpResponse<String> answer = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(answer.statusCode(), answer.body(), new LinkedHashMap<>(answer.headers().map()));
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for the answer", interrupted); //$NON-NLS-1$
        }
    }

    /**
     * Headers as a map, in pairs.
     *
     * @param pairs name, value, name, value...
     * @return the map
     */
    static Map<String, String> headers(String... pairs)
    {
        Map<String, String> map = new LinkedHashMap<>();
        for (int at = 0; at + 1 < pairs.length; at += 2)
        {
            map.put(pairs[at], pairs[at + 1]);
        }
        return map;
    }

    static Map<String, String> noHeaders()
    {
        return Collections.emptyMap();
    }

    private static void restoreRegistry(String previous)
    {
        if (previous == null)
        {
            System.clearProperty(REGISTRY_DIR_PROPERTY);
        }
        else
        {
            System.setProperty(REGISTRY_DIR_PROPERTY, previous);
        }
    }

    @Override
    public void close()
    {
        try
        {
            endpoint.stop();
        }
        finally
        {
            McpAuth.useStore(null);
            McpAuth.forgetPublished();
            restoreRegistry(previousRegistryDir);
            deleteQuietly(registry);
        }
    }

    private static void deleteQuietly(Path dir)
    {
        try
        {
            if (Files.isDirectory(dir))
            {
                try (java.util.stream.Stream<Path> entries = Files.list(dir))
                {
                    for (Path entry : (Iterable<Path>)entries::iterator)
                    {
                        Files.deleteIfExists(entry);
                    }
                }
                Files.deleteIfExists(dir);
            }
        }
        catch (IOException ignored)
        {
            // A temporary directory that stays behind costs nothing a test cares about.
        }
    }
}
