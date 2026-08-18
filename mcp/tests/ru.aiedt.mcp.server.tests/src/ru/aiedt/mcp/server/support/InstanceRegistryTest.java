/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;

/**
 * Pins the instance registry: what it announces, and - the half that matters - what it refuses
 * to report.
 * <p>
 * A port that answers nobody is worse than no answer at all, because an agent reads it as a
 * live server refusing to talk. So a record left behind by an EDT that crashed must never be
 * reported as live, and that is checked here against a process id no process has.
 * </p>
 * <p>
 * The records go to a temporary directory rather than the real one under the user's home: a
 * test run on a machine with EDT open must not put a phantom instance in front of whoever is
 * asking who is running.
 * </p>
 */
public class InstanceRegistryTest
{
    /** Where the registry writes while this test runs. */
    private static final String DIR_PROPERTY = "aiedt.instances.dir"; //$NON-NLS-1$

    private Path registryDir;

    private String previousProperty;

    /**
     * Points the registry at a temporary directory.
     *
     * @throws IOException when the directory cannot be made.
     */
    @Before
    public void redirectRegistry() throws IOException
    {
        registryDir = Files.createTempDirectory("aiedt-instances-test"); //$NON-NLS-1$
        previousProperty = System.getProperty(DIR_PROPERTY);
        System.setProperty(DIR_PROPERTY, registryDir.toString());
    }

    /**
     * Puts the property back and clears the directory.
     *
     * @throws IOException when the directory cannot be cleared.
     */
    @After
    public void restoreRegistry() throws IOException
    {
        InstanceRegistry.withdraw();
        if (previousProperty == null)
        {
            System.clearProperty(DIR_PROPERTY);
        }
        else
        {
            System.setProperty(DIR_PROPERTY, previousProperty);
        }
        if (registryDir != null && Files.isDirectory(registryDir))
        {
            try (java.util.stream.Stream<Path> entries = Files.list(registryDir))
            {
                for (Path entry : entries.toArray(Path[]::new))
                {
                    Files.deleteIfExists(entry);
                }
            }
            Files.deleteIfExists(registryDir);
        }
    }

    /** With nothing announced, the answer is an empty list rather than a failure. */
    @Test
    public void anEmptyRegistryReportsNobody()
    {
        List<JsonObject> live = InstanceRegistry.live();
        assertNotNull("live() answered null instead of an empty list", live); //$NON-NLS-1$
        assertTrue("an empty registry reported someone: " + live, live.isEmpty()); //$NON-NLS-1$
    }

    /** What was announced comes back, with the port asked for and marked as this process. */
    @Test
    public void anAnnouncedInstanceComesBackWithItsPort()
    {
        InstanceRegistry.announce(12345);

        List<JsonObject> live = InstanceRegistry.live();
        assertEquals("exactly one instance was announced", 1, live.size()); //$NON-NLS-1$
        JsonObject entry = live.get(0);
        assertEquals("the announced port is not the one reported", //$NON-NLS-1$
            12345, entry.get("port").getAsInt()); //$NON-NLS-1$
        assertEquals("this process is not marked as itself", //$NON-NLS-1$
            ProcessHandle.current().pid(), entry.get("pid").getAsLong()); //$NON-NLS-1$
        assertTrue("the record does not know it is this process", //$NON-NLS-1$
            entry.get("self").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the record carries no title to tell it apart by", //$NON-NLS-1$
            entry.get("title").getAsString().startsWith("AI-EDT")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Withdrawing removes it, so a stopped server is not still advertised. */
    @Test
    public void withdrawingRemovesTheRecord()
    {
        InstanceRegistry.announce(12345);
        assertFalse("nothing was announced to withdraw", InstanceRegistry.live().isEmpty()); //$NON-NLS-1$

        InstanceRegistry.withdraw();

        assertTrue("a withdrawn instance is still reported", //$NON-NLS-1$
            InstanceRegistry.live().isEmpty());
    }

    /**
     * The point of the whole thing: a record whose process is gone is not reported, and does
     * not survive to be misread again.
     *
     * @throws IOException when the fake record cannot be written.
     */
    @Test
    public void aRecordOfADeadProcessIsNeitherReportedNorKept() throws IOException
    {
        // A pid far above any live one. ProcessHandle.of answers empty for it, which is exactly
        // the state a crashed EDT leaves behind.
        long deadPid = 4_000_000_123L;
        Path stale = registryDir.resolve(deadPid + ".json"); //$NON-NLS-1$
        String body = "{\"pid\":" + deadPid + ",\"port\":12250,\"title\":\"AI-EDT @ Gone\"}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Files.write(stale, body.getBytes(StandardCharsets.UTF_8));

        List<JsonObject> live = InstanceRegistry.live();

        assertTrue("a dead instance was reported as live: " + live, live.isEmpty()); //$NON-NLS-1$
        assertFalse("the dead record was left to be misread again", Files.exists(stale)); //$NON-NLS-1$
    }

    /**
     * A record that is not JSON at all is skipped rather than taking the listing down.
     *
     * @throws IOException when the half-written record cannot be created.
     */
    @Test
    public void anUnreadableRecordDoesNotBreakTheListing() throws IOException
    {
        Files.write(registryDir.resolve("999999999.json"), //$NON-NLS-1$
            "{ this is not json".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        InstanceRegistry.announce(12250);

        List<JsonObject> live = InstanceRegistry.live();

        assertEquals("the good record was lost with the bad one", 1, live.size()); //$NON-NLS-1$
        assertEquals(12250, live.get(0).get("port").getAsInt()); //$NON-NLS-1$
    }

    /** The title never comes back null - it is what a client shows to name this server. */
    @Test
    public void theTitleIsAlwaysSomething()
    {
        String title = InstanceRegistry.selfTitle();
        assertNotNull("selfTitle answered null", title); //$NON-NLS-1$
        assertTrue("the title does not name the product: " + title, //$NON-NLS-1$
            title.startsWith("AI-EDT")); //$NON-NLS-1$
    }
}
