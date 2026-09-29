/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.ToolCallScope;

/**
 * A comparison the operator stopped says it was stopped.
 * <p>
 * The walk and the classification are long enough to be worth interrupting, and stopping without
 * saying so hands the caller part of the work under the name of the whole: "nothing differs" then
 * means "nothing was compared". The note is what keeps a partial answer from reading as a complete
 * one.
 * </p>
 */
public class AComparisonTheOperatorStoppedSaysSoTest
{
    private Path first;

    private Path second;

    @Before
    public void twoExports() throws Exception
    {
        first = Files.createTempDirectory("aiedt-compare-cancel-a"); //$NON-NLS-1$
        second = Files.createTempDirectory("aiedt-compare-cancel-b"); //$NON-NLS-1$
        write(first, "Catalogs/Goods/Goods.mdo", "<mdo version='one'/>"); //$NON-NLS-1$ //$NON-NLS-2$
        write(second, "Catalogs/Goods/Goods.mdo", "<mdo version='two'/>"); //$NON-NLS-1$ //$NON-NLS-2$
        // Into the target export: the file is the one a complete comparison finds added.
        write(second, "Documents/Order/Order.mdo", "<mdo/>"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @After
    public void removeThem() throws Exception
    {
        for (Path top : new Path[]{ first, second })
        {
            try (Stream<Path> entries = Files.walk(top))
            {
                entries.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void write(Path export, String relative, String content) throws Exception
    {
        Path target = export.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private String compare()
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "files"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", first.toString()); //$NON-NLS-1$
        params.put("target", second.toString()); //$NON-NLS-1$
        return new CompareConfigurationsTool().execute(params);
    }

    /** A cancelled walk answers with the note, and with no claim about what it did not reach. */
    @Test
    public void aStoppedWalkSaysItWasStopped()
    {
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        ToolCallScope.enter(ToolCallScope.forCancellation(flag));
        try
        {
            flag.cancel("the operator asked to stop");
            String answer = compare();

            assertTrue("a partial comparison has to say so: " + answer, //$NON-NLS-1$
                answer.contains("cancelled by the operator")); //$NON-NLS-1$
            assertTrue("and say the answer is not the whole of it: " + answer, //$NON-NLS-1$
                answer.contains("not the whole answer")); //$NON-NLS-1$
            assertFalse("nothing was read, so nothing may be named as added: " + answer, //$NON-NLS-1$
                answer.contains("\"added\":[\"Documents/Order/Order.mdo\"]")); //$NON-NLS-1$
        }
        finally
        {
            ToolCallScope.exit();
        }
    }

    /** With no call around it, and so no flag, the comparison runs to the end. */
    @Test
    public void aComparisonOutsideACallIsNotStopped()
    {
        String answer = compare();

        assertNotNull(answer);
        assertFalse("nothing cancelled this run: " + answer, //$NON-NLS-1$
            answer.contains("cancelled")); //$NON-NLS-1$
        assertTrue("and it compared both sides: " + answer, //$NON-NLS-1$
            answer.contains("\"addedCount\":1")); //$NON-NLS-1$
    }

    /** A flag that was never raised stops nothing: the control for the first test. */
    @Test
    public void aQuietFlagStopsNothing()
    {
        ToolCallScope.enter(ToolCallScope.forCancellation(new ToolCallScope.Cancellation()));
        try
        {
            String answer = compare();

            assertFalse("a flag nobody raised must not stop anything: " + answer, //$NON-NLS-1$
                answer.contains("cancelled")); //$NON-NLS-1$
            assertTrue(answer, answer.contains("\"addedCount\":1")); //$NON-NLS-1$
        }
        finally
        {
            ToolCallScope.exit();
        }
    }
}
