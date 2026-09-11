/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

/**
 * A connection that records what is written to it, or refuses every write - the agent that hung up.
 */
final class FakeExchange
    extends HttpExchange
{
    private final Headers requestHeaders = new Headers();

    private final Headers responseHeaders = new Headers();

    private final ByteArrayOutputStream written = new ByteArrayOutputStream();

    private final boolean gone;

    private int responseCode = -1;

    private boolean closed;

    private FakeExchange(boolean gone)
    {
        this.gone = gone;
    }

    /**
     * @return a connection that takes what is written to it
     */
    static FakeExchange open()
    {
        return new FakeExchange(false);
    }

    /**
     * @return a connection whose other end has gone: every write fails
     */
    static FakeExchange hungUp()
    {
        return new FakeExchange(true);
    }

    String body()
    {
        return new String(written.toByteArray(), StandardCharsets.UTF_8);
    }

    int responseCode()
    {
        return responseCode;
    }

    boolean isClosed()
    {
        return closed;
    }

    @Override
    public Headers getRequestHeaders()
    {
        return requestHeaders;
    }

    @Override
    public Headers getResponseHeaders()
    {
        return responseHeaders;
    }

    @Override
    public URI getRequestURI()
    {
        return URI.create("/mcp"); //$NON-NLS-1$
    }

    @Override
    public String getRequestMethod()
    {
        return "POST"; //$NON-NLS-1$
    }

    @Override
    public HttpContext getHttpContext()
    {
        return null;
    }

    @Override
    public void close()
    {
        closed = true;
    }

    @Override
    public InputStream getRequestBody()
    {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public OutputStream getResponseBody()
    {
        if (gone)
        {
            return new OutputStream()
            {
                @Override
                public void write(int b) throws IOException
                {
                    throw new IOException("connection reset by peer"); //$NON-NLS-1$
                }
            };
        }
        return written;
    }

    @Override
    public void sendResponseHeaders(int code, long responseLength) throws IOException
    {
        if (gone)
        {
            throw new IOException("connection reset by peer"); //$NON-NLS-1$
        }
        responseCode = code;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return new InetSocketAddress("127.0.0.1", 0); //$NON-NLS-1$
    }

    @Override
    public int getResponseCode()
    {
        return responseCode;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return new InetSocketAddress("127.0.0.1", 0); //$NON-NLS-1$
    }

    @Override
    public String getProtocol()
    {
        return "HTTP/1.1"; //$NON-NLS-1$
    }

    @Override
    public Object getAttribute(String name)
    {
        return null;
    }

    @Override
    public void setAttribute(String name, Object value)
    {
        // Nothing keeps attributes here.
    }

    @Override
    public void setStreams(InputStream i, OutputStream o)
    {
        // The streams are fixed.
    }

    @Override
    public HttpPrincipal getPrincipal()
    {
        return null;
    }
}
