/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Covers the built-in YAxUnit help topics.
 * <p>
 * The index is the only way an agent discovers what help exists, so an index that advertises a
 * topic the lookup cannot serve is worse than no index at all - it produces a confident call that
 * returns nothing. That reconciliation, in both directions, is what these tests are for.
 * </p>
 */
public class YaxunitHelpTest
{
    @Test
    public void everyAdvertisedTopicCanBeFetched()
    {
        List<String> topics = YaxunitHelp.availableTopics();

        assertFalse("an empty index means the help is unreachable", topics.isEmpty()); //$NON-NLS-1$
        for (String topic : topics)
        {
            String body = YaxunitHelp.getTopic(topic);
            assertNotNull("advertised but not served: " + topic, body); //$NON-NLS-1$
            assertFalse("served empty: " + topic, body.isBlank()); //$NON-NLS-1$
        }
    }

    @Test
    public void theIndexTopicNamesTheOthers()
    {
        String index = YaxunitHelp.getTopic("topics"); //$NON-NLS-1$

        assertNotNull(index);
        for (String topic : YaxunitHelp.availableTopics())
        {
            assertTrue("the index has to mention " + topic, index.contains(topic)); //$NON-NLS-1$
        }
    }

    @Test
    public void aTopicNameIsMatchedRegardlessOfCaseAndSurroundingSpace()
    {
        String expected = YaxunitHelp.getTopic("assertions"); //$NON-NLS-1$

        assertEquals(expected, YaxunitHelp.getTopic("Assertions")); //$NON-NLS-1$
        assertEquals(expected, YaxunitHelp.getTopic("  ASSERTIONS  ")); //$NON-NLS-1$
    }

    @Test
    public void anUnknownTopicResolvesToNothing()
    {
        assertNull(YaxunitHelp.getTopic("teleportation")); //$NON-NLS-1$
        assertNull(YaxunitHelp.getTopic(null));
        assertNull(YaxunitHelp.getTopic("")); //$NON-NLS-1$
    }

    @Test
    public void theTopicListIsACopyTheCallerCannotBreak()
    {
        // It is handed straight to a tool response; a caller that sorts or clears it must not be
        // able to damage the registry every later call reads.
        List<String> first = YaxunitHelp.availableTopics();
        first.clear();

        assertFalse("the registry was emptied through its own accessor", //$NON-NLS-1$
            YaxunitHelp.availableTopics().isEmpty());
    }
}
