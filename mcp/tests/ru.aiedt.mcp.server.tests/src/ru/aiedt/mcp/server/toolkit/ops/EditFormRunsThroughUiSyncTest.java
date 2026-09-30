/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * {@code edit_form} reaches its work through {@code UiSync.call}, not a raw
 * {@code Display.syncExec}.
 * <p>
 * The raw call blocks the tool for as long as the UI thread is busy - a modal dialog, an
 * operation stuck on the same thread - and a burst of such calls exhausts the request pool.
 * {@code UiSync.call} waits a bounded time and raises {@code UiBusyException}, which the route
 * answers as an error. The busy branch itself needs a wedged UI thread, so what is pinned here is
 * the route's source: the bounded wrapper is the one entry, and the raw call is gone.
 * </p>
 */
public class EditFormRunsThroughUiSyncTest
{
    /**
     * The execution entry goes through the bounded wrapper.
     *
     * @throws IOException when the source cannot be read
     */
    @Test
    public void theExecutionGoesThroughUiSyncCall() throws IOException
    {
        String source = sourceOfEditFormTool();

        assertTrue(source, source.contains("UiSync.call(")); //$NON-NLS-1$
        assertFalse("a raw syncExec blocks the call for as long as the UI thread is busy", //$NON-NLS-1$
            source.contains(".syncExec(")); //$NON-NLS-1$
    }

    /**
     * The source of {@link EditFormTool}, read from the bundle project this test runs against.
     *
     * @return the file text, UTF-8
     * @throws IOException when the file cannot be read
     */
    private static String sourceOfEditFormTool() throws IOException
    {
        Path bundle = bundleProject();
        assertNotNull("the bundle project not found from " + System.getProperty("user.dir"), bundle); //$NON-NLS-1$ //$NON-NLS-2$
        return Files.readString(
            bundle.resolve("src/ru/aiedt/mcp/server/toolkit/ops/EditFormTool.java"), //$NON-NLS-1$
            StandardCharsets.UTF_8);
    }

    /**
     * The bundle project directory, walking up from the working directory.
     *
     * @return the directory, or {@code null} when this process is not standing in the tree
     */
    private static Path bundleProject()
    {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath(); //$NON-NLS-1$
        for (int i = 0; i < 16 && dir != null; i++)
        {
            Path nested = dir.resolve("mcp/bundles/ru.aiedt.mcp.server/build.properties"); //$NON-NLS-1$
            if (Files.isRegularFile(nested))
            {
                return nested.getParent();
            }
            Path beside = dir.resolve("bundles/ru.aiedt.mcp.server/build.properties"); //$NON-NLS-1$
            if (Files.isRegularFile(beside))
            {
                return beside.getParent();
            }
            if (dir.getFileName() != null
                && "ru.aiedt.mcp.server".equals(dir.getFileName().toString()) //$NON-NLS-1$
                && Files.isRegularFile(dir.resolve("build.properties"))) //$NON-NLS-1$
            {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
