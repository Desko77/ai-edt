/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * A cancel that arrives before the update's launch boundary leaves the base untouched, but the
 * client sessions the call had stopped to free it stay stopped, and the answer says so instead of
 * calling everything as it was.
 */
public class ACancelBeforeTheLaunchNamesTheStoppedClientsTest
{
    /**
     * @param terminated whether the launch was stopped
     * @return one row as the client freeing reports it
     */
    private static Map<String, Object> client(boolean terminated)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("launchConfiguration", "Demo client"); //$NON-NLS-1$ //$NON-NLS-2$
        row.put("terminated", Boolean.valueOf(terminated)); //$NON-NLS-1$
        return row;
    }

    /** With no client stopped, the base is as it was and the sentence says so. */
    @Test
    public void withNoClientStoppedTheBaseIsAsItWas()
    {
        assertTrue(DatabaseUpdater.cancelledBeforeTheLaunch(null).contains("the base is as it was")); //$NON-NLS-1$
        assertTrue(DatabaseUpdater.cancelledBeforeTheLaunch(List.of(client(false)))
            .contains("the base is as it was")); //$NON-NLS-1$
    }

    /** A stopped client is counted, and the sentence does not call everything as it was. */
    @Test
    public void aStoppedClientIsNamedAsStillStopped()
    {
        String sentence = DatabaseUpdater.cancelledBeforeTheLaunch(List.of(client(true), client(false)));

        assertFalse(sentence, sentence.contains("as it was")); //$NON-NLS-1$
        assertTrue(sentence, sentence.contains("1 client session(s)")); //$NON-NLS-1$
        assertTrue(sentence, sentence.contains("freedClients")); //$NON-NLS-1$
    }
}
