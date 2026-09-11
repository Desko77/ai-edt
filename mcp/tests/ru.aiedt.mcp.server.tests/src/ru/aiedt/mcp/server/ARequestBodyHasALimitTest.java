/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * The body is capped by the bytes read, not by what the request claims to be sending.
 * <p>
 * {@code Content-Length} decides nothing here on purpose: a request may arrive without the header,
 * may arrive chunked, and may declare a smaller number than it sends. A reader that trusts the
 * header can still be handed a body of any size, which is the same as having no limit.
 * </p>
 */
public class ARequestBodyHasALimitTest
{
    private static InputStream streamOf(String text)
    {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void aBodyUnderTheLimitIsRead() throws IOException
    {
        assertEquals("{\"jsonrpc\":\"2.0\"}", //$NON-NLS-1$
            McpHttpEndpoint.readBody(streamOf("{\"jsonrpc\":\"2.0\"}"), 1024)); //$NON-NLS-1$
    }

    @Test
    public void aBodyExactlyAtTheLimitIsRead() throws IOException
    {
        String body = "0123456789"; //$NON-NLS-1$
        assertEquals(body, McpHttpEndpoint.readBody(streamOf(body), 10));
    }

    @Test
    public void aBodyOneByteOverTheLimitIsRefused()
    {
        try
        {
            McpHttpEndpoint.readBody(streamOf("01234567890"), 10); //$NON-NLS-1$
            fail("a body past the limit has to be refused"); //$NON-NLS-1$
        }
        catch (IOException refused)
        {
            assertTrue(refused instanceof McpHttpEndpoint.BodyTooLarge);
            assertEquals(10L, ((McpHttpEndpoint.BodyTooLarge)refused).limit());
        }
    }

    @Test
    public void aStreamThatNeverEndsIsStoppedAtTheLimit()
    {
        // What a declared length cannot protect against: the stream simply keeps going. This one
        // would never end on its own, so the test finishing at all IS the assertion.
        InputStream endless = new InputStream()
        {
            @Override
            public int read()
            {
                return 'x';
            }

            @Override
            public int read(byte[] into, int off, int len)
            {
                java.util.Arrays.fill(into, off, off + len, (byte)'x');
                return len;
            }
        };

        try
        {
            McpHttpEndpoint.readBody(endless, 64L * 1024L);
            fail("an endless body has to be refused"); //$NON-NLS-1$
        }
        catch (IOException refused)
        {
            assertTrue(refused instanceof McpHttpEndpoint.BodyTooLarge);
        }
    }

    @Test
    public void anEmptyBodyIsEmptyRatherThanARefusal() throws IOException
    {
        assertEquals("", McpHttpEndpoint.readBody(streamOf(""), 10)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theBodyKeepsItsNewlinesAndNonAsciiText() throws IOException
    {
        // The previous reader joined lines and dropped every newline, so a body was not what was
        // sent. A JSON document survived that; a module source inside one does not.
        String body = "{\"code\":\"Процедура Тест()\nКонецПроцедуры\"}"; //$NON-NLS-1$
        assertEquals(body, McpHttpEndpoint.readBody(streamOf(body), 4096));
    }
}
