/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.InstanceRegistry;

/**
 * Covers what the server says when it can find no port to listen on.
 * <p>
 * The old message was "could not bind", and an hour goes into that one: nothing says which ports
 * were tried, and nothing says who has them. Every instance that started announced itself to a
 * registry on disk, so the answer can usually name the neighbour rather than leaving somebody to
 * find it with a port scanner.
 * </p>
 */
public class PortSpanMessageTest
{
    private static final String REGISTRY_DIR_PROPERTY = "aiedt.instances.dir"; //$NON-NLS-1$

    private Path registry;

    private String previousDir;

    @Before
    public void useATemporaryRegistry() throws Exception
    {
        registry = Files.createTempDirectory("aiedt-ports"); //$NON-NLS-1$
        previousDir = System.getProperty(REGISTRY_DIR_PROPERTY);
        System.setProperty(REGISTRY_DIR_PROPERTY, registry.toString());
    }

    @After
    public void putTheRegistryBack() throws Exception
    {
        InstanceRegistry.withdraw();
        if (previousDir == null)
        {
            System.clearProperty(REGISTRY_DIR_PROPERTY);
        }
        else
        {
            System.setProperty(REGISTRY_DIR_PROPERTY, previousDir);
        }
        try (java.util.stream.Stream<Path> entries = Files.walk(registry))
        {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /** The message names the whole range that was tried, not just where it started. */
    @Test
    public void theMessageNamesEveryPortItTried()
    {
        String message = McpHttpEndpoint.everyPortTaken(12250, 10);

        assertTrue("the range should be readable at a glance: " + message, //$NON-NLS-1$
            message.contains("12250-12259")); //$NON-NLS-1$
    }

    /**
     * An instance holding one of those ports is named.
     * <p>
     * By its workspace, because that is the thing that differs between several EDTs on one machine
     * - a pid tells the person nothing they can act on.
     * </p>
     */
    @Test
    public void anInstanceHoldingAPortInTheRangeIsNamed()
    {
        InstanceRegistry.announce(12253);

        String message = McpHttpEndpoint.everyPortTaken(12250, 10);

        assertTrue("the holder went unnamed: " + message, message.contains("Held by")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and it should say which port: " + message, message.contains("12253")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and who has it: " + message, message.contains(InstanceRegistry.selfTitle())); //$NON-NLS-1$
    }

    /** An instance outside the range is somebody else's business and is not mentioned. */
    @Test
    public void anInstanceOutsideTheRangeIsNotMentioned()
    {
        InstanceRegistry.announce(19000);

        String message = McpHttpEndpoint.everyPortTaken(12250, 10);

        assertFalse("a port that was never tried has nothing to do with this failure: " + message, //$NON-NLS-1$
            message.contains("19000")); //$NON-NLS-1$
        assertFalse(message.contains("Held by")); //$NON-NLS-1$
    }

    /** With nothing in the registry the message still says what to do about it. */
    @Test
    public void withNoNeighbourItStillSaysWhatToDo()
    {
        String message = McpHttpEndpoint.everyPortTaken(12250, 1);

        assertFalse(message.contains("Held by")); //$NON-NLS-1$
        assertTrue("a failure with no advice is where the hour goes: " + message, //$NON-NLS-1$
            message.contains("preferences")); //$NON-NLS-1$
    }

    /** A span of one names a single port, not a range of one. */
    @Test
    public void aSpanOfOneReadsAsOnePort()
    {
        assertTrue(McpHttpEndpoint.everyPortTaken(12250, 1).contains("12250-12250")); //$NON-NLS-1$
    }
}
