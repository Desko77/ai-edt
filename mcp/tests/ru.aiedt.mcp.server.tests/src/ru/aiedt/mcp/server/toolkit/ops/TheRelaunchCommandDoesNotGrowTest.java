/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * A relaunch hands the launcher the same command it received, not a longer one.
 *
 * <p>The command used to ask for {@code --launcher.appendVmargs} while already carrying the
 * launcher's {@code .ini} block - the block the arguments were read from in the first place - so
 * every restart added a copy. Measured on an instance after a series of restarts: a launcher line
 * of 12009 characters carrying 10 {@code -Xmx} and a VM line of 27313 characters carrying 22,
 * against the 32767-character Windows command-line limit. The command now asks for
 * {@code --launcher.overrideVmargs}, which makes the relaunched instance's VM arguments exactly
 * the ones the command carries, and the {@code .ini} block is placed there once - every copy that
 * an earlier restart left in the arguments being dropped.
 *
 * <p>What the launcher does with the command is modelled here rather than assumed, so the tests
 * hold the command to the outcome of the mode it names instead of to its own text.
 */
public class TheRelaunchCommandDoesNotGrowTest
{
    private static final String LAUNCHER = "C:/EDT/1cedt.exe"; //$NON-NLS-1$
    private static final String WORKSPACE = "C:/EDT/ws"; //$NON-NLS-1$
    private static final String VM = "C:/EDT/jdk/bin/javaw.exe"; //$NON-NLS-1$
    private static final String CALLER_ARGUMENT = "-Xmx8192m"; //$NON-NLS-1$

    /** The VM arguments a launcher reads from its own {@code .ini}. */
    private static final List<String> INI = Collections.unmodifiableList(Arrays.asList(
        "-Xmx4g", //$NON-NLS-1$
        "--add-opens=java.base/java.lang=ALL-UNNAMED", //$NON-NLS-1$
        "-Dfile.encoding=UTF-8")); //$NON-NLS-1$

    /**
     * What the launcher makes of the command, by the mode the command names.
     *
     * @param command the command line handed to the launcher.
     * @return the VM arguments the relaunched instance would run with.
     */
    private static List<String> effectiveVmArgumentsOf(List<String> command)
    {
        boolean append = command.contains("--launcher.appendVmargs"); //$NON-NLS-1$
        boolean override = command.contains("--launcher.overrideVmargs"); //$NON-NLS-1$
        assertTrue("the command must name one of the two, not both and not neither: " + command, //$NON-NLS-1$
            append ^ override);
        assertEquals("the launcher takes one -vmargs and one only: " + command, //$NON-NLS-1$
            1, Collections.frequency(command, "-vmargs")); //$NON-NLS-1$
        int at = command.indexOf("-vmargs"); //$NON-NLS-1$
        List<String> effective = new ArrayList<>();
        if (append)
        {
            effective.addAll(INI);
        }
        effective.addAll(command.subList(at + 1, command.size()));
        return effective;
    }

    private static List<String> relaunchOf(List<String> inputArguments)
    {
        return RestartEdtTool.relaunchCommandOf(LAUNCHER, WORKSPACE, VM, inputArguments, INI);
    }

    /**
     * Every line of the block, and the caller's own argument, is carried exactly once.
     *
     * @param effective the VM arguments the relaunched instance would run with.
     */
    private static void assertCarriesEachOnce(List<String> effective)
    {
        for (String iniArgument : INI)
        {
            assertEquals(iniArgument + " once: " + effective, //$NON-NLS-1$
                1, Collections.frequency(effective, iniArgument));
        }
        assertEquals(CALLER_ARGUMENT + " once: " + effective, //$NON-NLS-1$
            1, Collections.frequency(effective, CALLER_ARGUMENT));
    }

    /** An instance started the way it is meant to be started. */
    @Test
    public void aFreshInstanceRelaunchesWithEveryArgumentOnce()
    {
        List<String> input = new ArrayList<>(INI);
        input.add(CALLER_ARGUMENT);

        assertCarriesEachOnce(effectiveVmArgumentsOf(relaunchOf(input)));
    }

    /** An instance that has already been restarted: the block is in its arguments five times. */
    @Test
    public void aGrownInstanceComesBackToEveryArgumentOnce()
    {
        List<String> input = new ArrayList<>();
        for (int copy = 0; copy < 5; copy++)
        {
            input.addAll(INI);
        }
        input.add(CALLER_ARGUMENT);

        assertCarriesEachOnce(effectiveVmArgumentsOf(relaunchOf(input)));
    }

    /** Handing the command's own outcome back produces the same command. */
    @Test
    public void theCommandIsAFixedPoint()
    {
        List<String> input = new ArrayList<>(INI);
        input.add(CALLER_ARGUMENT);
        List<String> first = relaunchOf(input);

        List<String> second = relaunchOf(effectiveVmArgumentsOf(first));

        assertEquals(first, second);
        assertCarriesEachOnce(effectiveVmArgumentsOf(second));
    }

    /**
     * An argument of the caller's own that happens to equal a line of the block is kept: only a run
     * that reproduces the whole block, line for line, is read as a copy of it.
     */
    @Test
    public void anArgumentThatMatchesOneIniLineIsKept()
    {
        List<String> input = new ArrayList<>(INI);
        input.add(INI.get(0));

        List<String> effective = effectiveVmArgumentsOf(relaunchOf(input));

        assertEquals("the caller asked for it, so it is there beside the block's own line: " //$NON-NLS-1$
            + effective, 2, Collections.frequency(effective, INI.get(0)));
    }

    /** The command starts the launcher, opens the workspace and names the VM it is to use. */
    @Test
    public void theCommandNamesTheLauncherTheWorkspaceAndTheVm()
    {
        List<String> command = relaunchOf(new ArrayList<>(INI));

        assertEquals(LAUNCHER, command.get(0));
        assertEquals("-data", command.get(1)); //$NON-NLS-1$
        assertEquals(WORKSPACE, command.get(2));
        assertEquals("-vm", command.get(3)); //$NON-NLS-1$
        assertEquals(VM, command.get(4));
    }

    /**
     * The block is what follows {@code -vmargs} in the file beside the executable, and nothing is
     * read from a file that is not there.
     *
     * @throws Exception when the temporary file cannot be written.
     */
    @Test
    public void theBlockIsReadFromTheFileBesideTheLauncher()
        throws Exception
    {
        Path dir = Files.createTempDirectory("aiedt-ini"); //$NON-NLS-1$
        try
        {
            Files.write(dir.resolve("1cedt.ini"), Arrays.asList( //$NON-NLS-1$
                "-startup", //$NON-NLS-1$
                "plugins/org.eclipse.equinox.launcher_1.6.0.jar", //$NON-NLS-1$
                "-vmargs", //$NON-NLS-1$
                "# a comment is not an argument", //$NON-NLS-1$
                "-Xmx4g", //$NON-NLS-1$
                "--add-opens=java.base/java.lang=ALL-UNNAMED", //$NON-NLS-1$
                "", //$NON-NLS-1$
                "-Dfile.encoding=UTF-8"), StandardCharsets.UTF_8); //$NON-NLS-1$

            assertEquals(INI, RestartEdtTool.iniVmArgumentsOf(
                new File(dir.toFile(), "1cedt.exe"))); //$NON-NLS-1$

            assertTrue("an .ini that is not there holds no arguments", //$NON-NLS-1$
                RestartEdtTool
                    .iniVmArgumentsOf(new File(dir.toFile(), "eclipse")).isEmpty()); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(dir.resolve("1cedt.ini")); //$NON-NLS-1$
            Files.deleteIfExists(dir);
        }
    }
}
