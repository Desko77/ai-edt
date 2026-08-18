/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins what a held-infobase report says, and - the part that matters - what it refuses to say.
 * <p>
 * The report exists because a refused update names the platform error and never names the holder,
 * so a held base and a broken tool look the same from outside. The trap in fixing that is the
 * opposite claim: this server sees only the clients EDT launched and the other AI-EDT servers, so
 * an empty answer means "none that I can see" and must never be dressed up as "nobody". Both
 * halves are checked here.
 * </p>
 */
public class InfobaseHoldersTest
{
    /** Where the instance registry writes while this test runs. */
    private static final String DIR_PROPERTY = "aiedt.instances.dir"; //$NON-NLS-1$

    private Path registryDir;

    private String previousProperty;

    /**
     * Points the instance registry at a temporary directory.
     *
     * @throws IOException when the directory cannot be made.
     */
    @Before
    public void redirectRegistry() throws IOException
    {
        registryDir = Files.createTempDirectory("aiedt-holders-test"); //$NON-NLS-1$
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

    /** Asked about nothing, it reports nothing rather than an empty block that reads as an answer. */
    @Test
    public void nothingToAskAboutIsNotAnEmptyAnswer()
    {
        assertNull("a block was built with no application and no projects", //$NON-NLS-1$
            InfobaseHolders.describe(null, null));
        assertNull("a block was built for no projects at all", //$NON-NLS-1$
            InfobaseHolders.describe("app-1", Collections.emptySet())); //$NON-NLS-1$
    }

    /** No visible holder is no block at all - never a block asserting the base is free. */
    @Test
    public void noVisibleHolderReportsNothing()
    {
        Map<String, Object> block =
            InfobaseHolders.describe("no-such-application", singleton("NoSuchProjectHere")); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("a holder block was produced with nothing to report: " + block, block); //$NON-NLS-1$
    }

    /**
     * A neighbouring server with the same project open is named, and the note says plainly that
     * holders outside this server's sight are not covered.
     *
     * @throws IOException when the neighbour record cannot be written.
     */
    @Test
    public void aNeighbourWithTheSameProjectIsNamedAndTheBlindSpotIsStated() throws IOException
    {
        // A real, live process id that is not ours, so the registry accepts the record as live:
        // this JVM's parent. Without one there is no way to fake a second instance honestly.
        Optional<ProcessHandle> parent = ProcessHandle.current().parent();
        Assume.assumeTrue("no parent process to stand in for a second instance", //$NON-NLS-1$
            parent.isPresent() && parent.get().isAlive());
        ProcessHandle neighbour = parent.get();
        String started = neighbour.info().startInstant().map(java.time.Instant::toString).orElse(null);
        Assume.assumeNotNull(started);

        String body = "{\"pid\":" + neighbour.pid() + ",\"processStartedAt\":\"" + started //$NON-NLS-1$ //$NON-NLS-2$
            + "\",\"port\":9999,\"title\":\"AI-EDT @ Neighbour\",\"projects\":[\"SharedProject\"]}"; //$NON-NLS-1$
        Files.write(registryDir.resolve(neighbour.pid() + ".json"), //$NON-NLS-1$
            body.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> block =
            InfobaseHolders.describe("app-1", singleton("SharedProject")); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull("the neighbouring instance was not reported", block); //$NON-NLS-1$
        assertTrue("the block does not list the other instances", //$NON-NLS-1$
            block.containsKey("otherInstances")); //$NON-NLS-1$
        String note = String.valueOf(block.get("note")); //$NON-NLS-1$
        assertTrue("the note does not say another EDT has the project open: " + note, //$NON-NLS-1$
            note.contains("Another EDT")); //$NON-NLS-1$
        assertTrue("the note claims completeness it does not have: " + note, //$NON-NLS-1$
            note.contains("only holders this server can see")); //$NON-NLS-1$
    }

    private static Set<String> singleton(String name)
    {
        Set<String> names = new LinkedHashSet<>();
        names.add(name);
        return names;
    }
}
