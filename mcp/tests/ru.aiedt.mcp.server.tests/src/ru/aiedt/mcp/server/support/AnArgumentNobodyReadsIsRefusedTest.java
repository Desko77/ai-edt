/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * An argument the named operation will not read.
 * <p>
 * It used to be accepted in silence. A made-up name and a real form property the operation does not
 * read both came back {@code success: true}, the same answer a correct call gets, so a caller could
 * not tell a request that was carried out from one that was half discarded.
 * </p>
 * <p>
 * The check leans on the map generated from the sources. Where the map records nothing for an
 * operation this says nothing: the map states its own gaps - five tools dispatch without a switch
 * and are absent from it - and refusing on a guess would turn working calls away.
 * </p>
 */
public class AnArgumentNobodyReadsIsRefusedTest
{
    private static final String FACADE = "EditMetadataTool"; //$NON-NLS-1$

    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    public void theMapIsThereToLeanOn()
    {
        // Everything below is worthless if the map did not ship inside the bundle.
        assertTrue("schema/operation-parameters.tsv has to be packaged", //$NON-NLS-1$
            OperationParameters.available());
        assertTrue("create_object is one of the operations it records", //$NON-NLS-1$
            !OperationParameters.of(FACADE, "create_object").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void anArgumentTheOperationReadsPassesThrough()
    {
        List<String> unread = UnreadArguments.of(FACADE, "create_object", //$NON-NLS-1$
            args("projectName", "P", "objectType", "Catalog", "name", "X")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        assertTrue(unread.toString(), unread.isEmpty());
    }

    @Test
    public void aNameTheOperationDoesNotReadIsNamed()
    {
        List<String> unread = UnreadArguments.of(FACADE, "create_object", //$NON-NLS-1$
            args("projectName", "P", "objectType", "Catalog", "name", "X", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "такогоПризнакаНет", "1")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Arrays.asList("такогоПризнакаНет"), unread); //$NON-NLS-1$
    }

    @Test
    public void aServiceKeyIsNotTheOperationsToDeclare()
    {
        // These steer the call itself - dispatch, preview, batching, polling a run already started.
        // The map records what a handler reads, so none of them appear there.
        List<String> unread = UnreadArguments.of(FACADE, "create_object", //$NON-NLS-1$
            args("projectName", "P", "objectType", "Catalog", "name", "X", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "dryRun", "true", "batch", "false", "runKey", "abc", "operation", "create_object")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$

        assertTrue(unread.toString(), unread.isEmpty());
    }

    @Test
    public void anOperationTheMapDoesNotRecordIsLeftAlone()
    {
        List<String> unread = UnreadArguments.of(FACADE, "нет такой операции", //$NON-NLS-1$
            args("чтоУгодно", "1")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("not recorded is not the same as reads nothing", unread.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void anEmptyCallHasNothingToRefuse()
    {
        assertTrue(UnreadArguments.of(FACADE, "create_object", null).isEmpty()); //$NON-NLS-1$
        assertTrue(UnreadArguments.of(FACADE, "create_object", args()).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void theRefusalNamesBothSides()
    {
        List<String> read = UnreadArguments.readBy(FACADE, "create_object"); //$NON-NLS-1$

        String message = UnreadArguments.refusal("create_object", //$NON-NLS-1$
            Arrays.asList("такогоПризнакаНет"), read); //$NON-NLS-1$

        assertTrue(message, message.contains("такогоПризнакаНет")); //$NON-NLS-1$
        assertTrue("a refusal that does not say what to use instead is half an answer", //$NON-NLS-1$
            message.contains("objectType")); //$NON-NLS-1$
        assertTrue("and it has to say nothing was written", //$NON-NLS-1$
            message.contains("Nothing was written")); //$NON-NLS-1$
    }
}
