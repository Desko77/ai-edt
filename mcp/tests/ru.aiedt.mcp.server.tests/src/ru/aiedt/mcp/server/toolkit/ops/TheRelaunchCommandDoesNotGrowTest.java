/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * A relaunch lets the launcher read its current {@code .ini} and carries only user VM arguments.
 *
 * <p>The Java launcher records in {@code eclipse.vmargs}, in order, the VM arguments handed to it
 * after {@code -vmargs}. That includes the complete {@code .ini} block (including {@code -server},
 * which the JVM's input-argument view omits) followed by command-line VM arguments in append mode.
 * The command removes the startup snapshot and old complete copies from that property, then asks
 * the launcher to append only the remainder to its current {@code .ini} block.</p>
 */
public class TheRelaunchCommandDoesNotGrowTest
{
    private static final String LAUNCHER = "C:/EDT/1cedt.exe"; //$NON-NLS-1$
    private static final String WORKSPACE = "C:/EDT/ws"; //$NON-NLS-1$
    private static final String VM = "C:/EDT/jdk/bin/javaw.exe"; //$NON-NLS-1$
    private static final String USER_XMX = "-Xmx8192m"; //$NON-NLS-1$

    /** The 34-line VM block shape measured on EDT, including the launcher-consumed -server. */
    private static final List<String> STARTUP_INI = Collections.unmodifiableList(Arrays.asList(
        "-server", //$NON-NLS-1$
        "-Xms512m", //$NON-NLS-1$
        "-Xmx4096m", //$NON-NLS-1$
        "-XX:+UseG1GC", //$NON-NLS-1$
        "-XX:MaxGCPauseMillis=200", //$NON-NLS-1$
        "-XX:+UseStringDeduplication", //$NON-NLS-1$
        "-Dosgi.requiredJavaVersion=17", //$NON-NLS-1$
        "-Dosgi.instance.area.default=@user.home/1C/1cedt/workspace", //$NON-NLS-1$
        "-Dosgi.dataAreaRequiresExplicitInit=true", //$NON-NLS-1$
        "-Dorg.eclipse.swt.graphics.Resource.reportNonDisposed=true", //$NON-NLS-1$
        "-Declipse.e4.inject.javax.warning=false", //$NON-NLS-1$
        "-Dfile.encoding=UTF-8", //$NON-NLS-1$
        "-Dsun.jnu.encoding=UTF-8", //$NON-NLS-1$
        "-Duser.language=ru", //$NON-NLS-1$
        "-Duser.country=RU", //$NON-NLS-1$
        "--add-modules=ALL-SYSTEM", //$NON-NLS-1$
        "--add-opens=java.base/java.lang=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/java.io=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/java.net=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/java.nio=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/java.util=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/sun.security.util=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.management/sun.management=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED", //$NON-NLS-1$
        "-Djava.net.preferIPv4Stack=true")); //$NON-NLS-1$

    private static String eclipseVmargsOf(List<String> arguments)
    {
        StringBuilder property = new StringBuilder();
        for (String argument : arguments)
        {
            property.append(argument).append('\n');
        }
        return property.toString();
    }

    private static RestartEdtTool.RelaunchPlan relaunchOf(List<String> snapshot,
        List<String> eclipseVmargs)
    {
        return RestartEdtTool.relaunchCommandOf(snapshot, eclipseVmargsOf(eclipseVmargs),
            LAUNCHER, WORKSPACE, VM);
    }

    /** Models append mode: current .ini first, then the VM arguments carried by the command. */
    private static List<String> effectiveVmArgumentsOf(List<String> command,
        List<String> currentIni)
    {
        assertFalse(command.contains("--launcher.overrideVmargs")); //$NON-NLS-1$
        List<String> effective = new ArrayList<>(currentIni);
        if (!command.contains("-vmargs")) //$NON-NLS-1$
        {
            assertFalse(command.contains("--launcher.appendVmargs")); //$NON-NLS-1$
            return effective;
        }
        assertTrue(command.contains("--launcher.appendVmargs")); //$NON-NLS-1$
        assertEquals(1, Collections.frequency(command, "-vmargs")); //$NON-NLS-1$
        effective.addAll(command.subList(command.indexOf("-vmargs") + 1, command.size())); //$NON-NLS-1$
        return effective;
    }

    private static void assertCurrentBlockAndUserOnce(List<String> effective,
        List<String> currentIni)
    {
        for (String iniArgument : currentIni)
        {
            assertEquals(iniArgument + " once: " + effective, 1, //$NON-NLS-1$
                Collections.frequency(effective, iniArgument));
        }
        assertEquals(USER_XMX + " once: " + effective, 1, //$NON-NLS-1$
            Collections.frequency(effective, USER_XMX));
    }

    @Test
    public void aFreshMeasuredInstanceRelaunchesWithEveryArgumentOnce()
    {
        assertEquals(34, STARTUP_INI.size());
        assertTrue(STARTUP_INI.contains("-server")); //$NON-NLS-1$
        List<String> property = new ArrayList<>(STARTUP_INI);
        property.add(USER_XMX);

        List<String> effective = effectiveVmArgumentsOf(
            relaunchOf(STARTUP_INI, property).command(), STARTUP_INI);

        assertCurrentBlockAndUserOnce(effective, STARTUP_INI);
    }

    @Test
    public void aGrownInstanceDropsAllFiveOldBlocks()
    {
        List<String> property = new ArrayList<>();
        for (int copy = 0; copy < 5; copy++)
        {
            property.addAll(STARTUP_INI);
        }
        property.add(USER_XMX);

        List<String> effective = effectiveVmArgumentsOf(
            relaunchOf(STARTUP_INI, property).command(), STARTUP_INI);

        assertCurrentBlockAndUserOnce(effective, STARTUP_INI);
    }

    @Test
    public void theCommandIsAFixedPoint()
    {
        List<String> property = new ArrayList<>(STARTUP_INI);
        property.add(USER_XMX);
        List<String> first = relaunchOf(STARTUP_INI, property).command();
        List<String> firstOutcome = effectiveVmArgumentsOf(first, STARTUP_INI);

        List<String> second = relaunchOf(STARTUP_INI, firstOutcome).command();

        assertEquals(first, second);
        assertCurrentBlockAndUserOnce(effectiveVmArgumentsOf(second, STARTUP_INI), STARTUP_INI);
    }

    @Test
    public void anIniEditMadeAfterStartupWinsBeforeTheUsersOverride()
    {
        List<String> currentIni = new ArrayList<>(STARTUP_INI);
        currentIni.set(currentIni.indexOf("-Xmx4096m"), "-Xmx6144m"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> property = new ArrayList<>(STARTUP_INI);
        property.add(USER_XMX);

        List<String> effective = effectiveVmArgumentsOf(
            relaunchOf(STARTUP_INI, property).command(), currentIni);

        assertFalse(effective.contains("-Xmx4096m")); //$NON-NLS-1$
        assertTrue(effective.indexOf("-Xmx6144m") < effective.indexOf(USER_XMX)); //$NON-NLS-1$
        assertCurrentBlockAndUserOnce(effective, currentIni);
    }

    @Test
    public void aMissingEclipseVmargsPropertyOmitsVmargsAndNamesTheReason()
    {
        RestartEdtTool.RelaunchPlan plan = RestartEdtTool.relaunchCommandOf(STARTUP_INI, null,
            LAUNCHER, WORKSPACE, VM);

        assertFalse(plan.command().contains("-vmargs")); //$NON-NLS-1$
        assertTrue(plan.vmArgumentsOmissionReason().contains("eclipse.vmargs")); //$NON-NLS-1$
        assertTrue(RestartEdtTool.vmArgumentsNoteOf(plan)
            .contains("User VM arguments were not carried over")); //$NON-NLS-1$
    }

    @Test
    public void aDifferentLeadingBlockOmitsVmargsAndNamesTheMismatch()
    {
        List<String> property = new ArrayList<>(STARTUP_INI);
        property.set(0, "-client"); //$NON-NLS-1$

        RestartEdtTool.RelaunchPlan plan = relaunchOf(STARTUP_INI, property);

        assertFalse(plan.command().contains("-vmargs")); //$NON-NLS-1$
        assertTrue(plan.vmArgumentsOmissionReason().contains("does not start")); //$NON-NLS-1$
    }

    @Test
    public void theCommandNamesTheLauncherTheWorkspaceAndTheVm()
    {
        List<String> property = new ArrayList<>(STARTUP_INI);
        property.add(USER_XMX);
        List<String> command = relaunchOf(STARTUP_INI, property).command();

        assertEquals(LAUNCHER, command.get(0));
        assertEquals("-data", command.get(1)); //$NON-NLS-1$
        assertEquals(WORKSPACE, command.get(2));
        assertEquals("-vm", command.get(3)); //$NON-NLS-1$
        assertEquals(VM, command.get(4));
    }

    @Test
    public void theStartupBlockIsReadRawFromTheFileBesideTheLauncher()
        throws Exception
    {
        Path dir = Files.createTempDirectory("aiedt-ini"); //$NON-NLS-1$
        try
        {
            List<String> expected = Arrays.asList("-server", " -Xmx4g", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Files.write(dir.resolve("1cedt.ini"), Arrays.asList( //$NON-NLS-1$
                "-startup", //$NON-NLS-1$
                "plugins/org.eclipse.equinox.launcher_1.6.0.jar", //$NON-NLS-1$
                "-vmargs", //$NON-NLS-1$
                "-server", //$NON-NLS-1$
                " -Xmx4g", //$NON-NLS-1$
                ""), StandardCharsets.UTF_8); //$NON-NLS-1$

            assertEquals(expected, RestartEdtTool.iniVmArgumentsOf(
                new File(dir.toFile(), "1cedt.exe"))); //$NON-NLS-1$
            assertTrue(RestartEdtTool
                .iniVmArgumentsOf(new File(dir.toFile(), "eclipse")).isEmpty()); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(dir.resolve("1cedt.ini")); //$NON-NLS-1$
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void anArgumentEqualToOneIniLineIsStillAUserArgument()
    {
        List<String> property = new ArrayList<>(STARTUP_INI);
        property.add(STARTUP_INI.get(0));

        List<String> effective = effectiveVmArgumentsOf(
            relaunchOf(STARTUP_INI, property).command(), STARTUP_INI);

        assertEquals(2, Collections.frequency(effective, STARTUP_INI.get(0)));
    }
}
