/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * What a request leaves behind in the log.
 * <p>
 * The masking used to match the text as it arrived, and that lost twice: a member named with a JSON
 * escape is the same member once the document is read, and a value that is an object rather than a
 * string was never matched at all. Both were found by review rather than by use, because a request
 * that leaks a password looks exactly like one that does not.
 * </p>
 */
public class ASecretNeverReachesTheLogTest
{
    @Test
    public void aPasswordInAConnectionStringIsReplaced()
    {
        String masked = McpHttpEndpoint.redactSecrets(
            "{\"params\":{\"arguments\":{\"connectionString\":\"File=\\\"C:\\\\ib\\\";Pwd=s3cret\"}}}"); //$NON-NLS-1$

        assertFalse(masked, masked.contains("s3cret")); //$NON-NLS-1$
    }

    @Test
    public void aMemberNamedWithAnEscapeIsTheSameMember()
    {
        // "connection\u0053tring" reads as connectionString once the document is parsed. Matching
        // the text as it arrived did not see it.
        String masked = McpHttpEndpoint.redactSecrets(
            "{\"arguments\":{\"connection\\u0053tring\":\"File=x;Pwd=s3cret\"}}"); //$NON-NLS-1$

        assertFalse(masked, masked.contains("s3cret")); //$NON-NLS-1$
    }

    @Test
    public void aValueThatIsAnObjectIsReplacedToo()
    {
        String masked = McpHttpEndpoint.redactSecrets(
            "{\"arguments\":{\"vanessaParams\":{\"ПарольПользователя\":\"s3cret\"}}}"); //$NON-NLS-1$

        assertFalse(masked, masked.contains("s3cret")); //$NON-NLS-1$
    }

    @Test
    public void aSecretNestedDeeperIsStillFound()
    {
        String masked = McpHttpEndpoint.redactSecrets(
            "{\"a\":{\"b\":[{\"password\":\"s3cret\"}]}}"); //$NON-NLS-1$

        assertFalse(masked, masked.contains("s3cret")); //$NON-NLS-1$
    }

    @Test
    public void whatCarriesNoSecretIsLoggedExactlyAsItArrived()
    {
        // A malformed or surprising request is read to find out what arrived, so a document that
        // needs no masking must not be reshaped on the way to the log.
        String body = "{\"method\":\"tools/call\",  \"params\":{\"name\":\"vanessa\"}}"; //$NON-NLS-1$

        assertEquals(body, McpHttpEndpoint.redactSecrets(body));
    }

    @Test
    public void somethingThatIsNotADocumentIsNotLoggedEither()
    {
        String masked = McpHttpEndpoint.redactSecrets("not json at all \"password\": \"s3cret\""); //$NON-NLS-1$

        assertFalse(masked, masked.contains("s3cret")); //$NON-NLS-1$
    }

    @Test
    public void aBodyThatCannotBeReadIsNotWrittenDown()
    {
        // Masking it by pattern lost three times running - an escaped name, an object value, and
        // both in a body too broken to parse. What cannot be parsed cannot be known, so it is not
        // logged; its length says a request arrived.
        String masked = McpHttpEndpoint.redactSecrets(
            "{\"arguments\":{\"connectionString\":{\"Pwd\":\"s3cret\"}}"); //$NON-NLS-1$

        assertFalse(masked, masked.contains("s3cret")); //$NON-NLS-1$
        assertTrue("the length is what tells someone a request arrived: " + masked, //$NON-NLS-1$
            masked.contains("characters")); //$NON-NLS-1$
    }

    @Test
    public void nothingIsNothing()
    {
        assertEquals(null, McpHttpEndpoint.redactSecrets(null));
        assertEquals("", McpHttpEndpoint.redactSecrets("")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anOrdinaryValueSurvives()
    {
        String masked = McpHttpEndpoint.redactSecrets(
            "{\"arguments\":{\"projectName\":\"Demo\",\"password\":\"s3cret\"}}"); //$NON-NLS-1$

        assertTrue("the project name is not a secret", masked.contains("Demo")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(masked.contains("s3cret")); //$NON-NLS-1$
    }
}
