/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.ToolCallScope;

/**
 * What a comparison of exports does not read, and where it stops.
 * <p>
 * A Windows directory junction is not a symbolic link to {@code Files.isSymbolicLink}, so an export
 * named by one was walked as the directory it points at. Two single files were read to the end
 * whatever the operator asked. A module pair above the preview limit was decoded whole.
 * </p>
 */
public class AComparisonFollowsNoJunctionAndStopsInsideAFileTest
{
    private Path root;

    @Before
    public void aRoot() throws Exception
    {
        root = Files.createTempDirectory("aiedt-compare-limits"); //$NON-NLS-1$
    }

    @After
    public void removeIt() throws Exception
    {
        // A junction is removed as a link: deleting it never reaches the directory it points at.
        try (Stream<Path> entries = Files.walk(root))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static boolean junction(Path link, Path target) throws Exception
    {
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .redirectErrorStream(true).start();
        mklink.getInputStream().readAllBytes();
        return mklink.waitFor(30, TimeUnit.SECONDS) && mklink.exitValue() == 0 && Files.isDirectory(link);
    }

    private static String compare(Path first, Path second)
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "files"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", first.toString()); //$NON-NLS-1$
        params.put("target", second.toString()); //$NON-NLS-1$
        return new CompareConfigurationsTool().execute(params);
    }

    @Test
    public void anExportNamedByAJunctionIsRefused() throws Exception
    {
        Assume.assumeTrue("a directory junction exists only on Windows", //$NON-NLS-1$
            System.getProperty("os.name", "").toLowerCase().contains("win")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Path real = Files.createDirectories(root.resolve("real")); //$NON-NLS-1$
        Path other = Files.createDirectories(root.resolve("other")); //$NON-NLS-1$
        Path link = root.resolve("linked"); //$NON-NLS-1$
        Assume.assumeTrue("this machine will not create a junction", junction(link, real)); //$NON-NLS-1$

        String answer = compare(link, other);

        assertTrue("a junction is a link, not a directory to walk: " + answer, //$NON-NLS-1$
            answer.contains("junction")); //$NON-NLS-1$
        assertTrue(CompareConfigurationsTool.isLink(link));
        assertFalse(CompareConfigurationsTool.isLink(real));
    }

    @Test
    public void twoFilesAreNotReadToTheEndAfterTheOperatorCancelled() throws Exception
    {
        byte[] content = new byte[256 * 1024];
        Path first = Files.write(root.resolve("a.bin"), content); //$NON-NLS-1$
        Path second = Files.write(root.resolve("b.bin"), content); //$NON-NLS-1$
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        ToolCallScope.enter(ToolCallScope.forCancellation(flag));
        try
        {
            flag.cancel("the operator asked to stop"); //$NON-NLS-1$
            String answer = compare(first, second);

            assertTrue("a comparison stopped during the read has to say so: " + answer, //$NON-NLS-1$
                answer.contains("cancelled")); //$NON-NLS-1$
            assertFalse("and must not claim the files are identical: " + answer, //$NON-NLS-1$
                answer.contains("\"identical\"")); //$NON-NLS-1$
        }
        finally
        {
            ToolCallScope.exit();
        }
    }

    @Test
    public void aPairAboveThePreviewLimitSaysWhyItHasNoPreview()
    {
        String omitted = CompareConfigurationsTool.previewOmitted(5L * 1024 * 1024, 10, "modules"); //$NON-NLS-1$

        assertTrue(omitted, omitted.contains("modules")); //$NON-NLS-1$
        assertTrue(omitted, omitted.contains("5242880")); //$NON-NLS-1$
        assertNull(CompareConfigurationsTool.previewOmitted(10, 10, "modules")); //$NON-NLS-1$
    }
}
