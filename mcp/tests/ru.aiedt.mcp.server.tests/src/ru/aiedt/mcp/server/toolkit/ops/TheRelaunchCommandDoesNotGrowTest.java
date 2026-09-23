/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * after {@code -vmargs}. That is the complete {@code .ini} block (including {@code -server}, which
 * the JVM's input-argument view omits) followed by the command-line VM arguments. A relaunch of
 * the older kind put an input-argument view into {@code -vmargs}, so an instance that had already
 * been restarted carries the block once more per restart, in that view's form. The command removes
 * the startup snapshot and every stale copy in either form, then asks the launcher to append only
 * the remainder to its current {@code .ini} block.</p>
 */
public class TheRelaunchCommandDoesNotGrowTest
{
    private static final String LAUNCHER = "C:/EDT/1cedt.exe"; //$NON-NLS-1$
    private static final String WORKSPACE = "C:/EDT/ws"; //$NON-NLS-1$
    private static final String VM = "C:/EDT/jdk/bin/javaw.exe"; //$NON-NLS-1$
    private static final String USER_XMX = "-Xmx8192m"; //$NON-NLS-1$

    /**
     * The VM block of the installed {@code 1cedt.ini} as measured on EDT 2026.2: 34 lines, with
     * {@code -server} third from the end, {@code -Dosgi.debug=.options} and the block's own
     * {@code -Xmx} after it.
     */
    private static final List<String> STARTUP_INI = Collections.unmodifiableList(Arrays.asList(
        "-XX:CompileCommand=quiet", //$NON-NLS-1$
        "-XX:CompileCommand=exclude org.eclipse.jdt.internal.core.dom.rewrite.ASTRewriteAnalyzer::getExtendedRange", //$NON-NLS-1$
        "-Dosgi.requiredJavaVersion=25", //$NON-NLS-1$
        "--add-modules=ALL-SYSTEM", //$NON-NLS-1$
        "--add-opens=java.base/java.lang.constant=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/java.lang=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/java.lang.ref=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/java.nio=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/java.time=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", //$NON-NLS-1$
        "--add-opens=java.base/java.net=ALL-UNNAMED", //$NON-NLS-1$
        "-XX:+UseG1GC", //$NON-NLS-1$
        "-XX:+UseStringDeduplication", //$NON-NLS-1$
        "-XX:MinHeapFreeRatio=4", //$NON-NLS-1$
        "-XX:MaxHeapFreeRatio=20", //$NON-NLS-1$
        "-Xms80m", //$NON-NLS-1$
        "-Djava.library.path=", //$NON-NLS-1$
        "-Dlog4j.configuration=platform:/plugin/com._1c.g5.v8.dt.logging/log4j.properties", //$NON-NLS-1$
        "-Dlog4j.formatMsgNoLookups=true", //$NON-NLS-1$
        "-Dlog4j2.formatMsgNoLookups=true", //$NON-NLS-1$
        "-Dorg.osgi.framework.bundle.parent=ext", //$NON-NLS-1$
        "-Dosgi.module.lock.timeout=240", //$NON-NLS-1$
        "-Dp2.httpRule=allow", //$NON-NLS-1$
        "-Dp2.ftpRule=allow", //$NON-NLS-1$
        "-Dorg.eclipse.ecf.provider.filetransfer.excludeContributors=org.eclipse.ecf.provider.filetransfer.httpclientjava", //$NON-NLS-1$
        "-De1c.dt.monitoring.host=https://pult.1c.ru/", //$NON-NLS-1$
        "-DmigrationEnabled=true", //$NON-NLS-1$
        "-DmigrationNotificationEnabled=true", //$NON-NLS-1$
        "-De1c.wiring.managedInitialization=true", //$NON-NLS-1$
        "-Dorg.eclipse.e4.ui.css.theme.disableOSDarkThemeInherit=true", //$NON-NLS-1$
        "-Dorg.eclipse.ui.internal.WindowsDefenderConfigurator.skipDefenderCheck=true", //$NON-NLS-1$
        "-server", //$NON-NLS-1$
        "-Dosgi.debug=.options", //$NON-NLS-1$
        "-Xmx8192m")); //$NON-NLS-1$

    /** The same block as a JVM's input-argument view records it: without the launcher's -server. */
    private static final List<String> STARTUP_INI_WITHOUT_SERVER =
        Collections.unmodifiableList(without(STARTUP_INI, "-server")); //$NON-NLS-1$

    private static List<String> without(List<String> block, String argument)
    {
        List<String> reduced = new ArrayList<>();
        for (String line : block)
        {
            if (!argument.equals(line))
            {
                reduced.add(line);
            }
        }
        return reduced;
    }

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

    /**
     * The property of an instance the older relaunch has already restarted three times: the
     * {@code .ini} block as the launcher reports it, then one copy of the block per restart in the
     * input-argument view's form, then the user's own arguments.
     *
     * @param userArguments the arguments the user started the instance with.
     * @return the arguments such an instance records in {@code eclipse.vmargs}.
     */
    private static List<String> grownProperty(String... userArguments)
    {
        List<String> property = new ArrayList<>(STARTUP_INI);
        for (int copy = 0; copy < 3; copy++)
        {
            property.addAll(STARTUP_INI_WITHOUT_SERVER);
        }
        property.addAll(Arrays.asList(userArguments));
        return property;
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

    /**
     * The JVM arguments a relaunch into the unchanged {@code .ini} must end up with: the current
     * block, in order, then the user's own arguments.
     *
     * @param userArguments the arguments the user started the instance with.
     * @return the expected argument list.
     */
    private static List<String> currentBlockThen(String... userArguments)
    {
        List<String> expected = new ArrayList<>(STARTUP_INI);
        expected.addAll(Arrays.asList(userArguments));
        return expected;
    }

    @Test
    public void aFreshMeasuredInstanceRelaunchesWithEveryArgumentOnce()
    {
        assertEquals(34, STARTUP_INI.size());
        assertEquals("-server", STARTUP_INI.get(STARTUP_INI.size() - 3)); //$NON-NLS-1$
        List<String> property = new ArrayList<>(STARTUP_INI);
        property.add(USER_XMX);

        List<String> effective = effectiveVmArgumentsOf(
            relaunchOf(STARTUP_INI, property).command(), STARTUP_INI);

        assertEquals(currentBlockThen(USER_XMX), effective);
    }

    @Test
    public void aGrownInstanceDropsEveryStaleCopyOfTheBlock()
    {
        List<String> effective = effectiveVmArgumentsOf(
            relaunchOf(STARTUP_INI, grownProperty(USER_XMX)).command(), STARTUP_INI);

        // Every line of the current block once, the user's argument once: three stale copies of 33
        // lines each would have added theirs to this list.
        assertEquals(currentBlockThen(USER_XMX), effective);
        assertEquals(STARTUP_INI.size() + 1, effective.size());
        // The block's own last line carries the user's heap value here, so that string stands once
        // for the block and once for the user's argument; the stale copies contributed neither.
        assertEquals(2, Collections.frequency(effective, USER_XMX));
    }

    @Test
    public void theCommandIsAFixedPoint()
    {
        List<String> first = relaunchOf(STARTUP_INI, grownProperty(USER_XMX)).command();
        List<String> firstOutcome = effectiveVmArgumentsOf(first, STARTUP_INI);

        List<String> second = relaunchOf(STARTUP_INI, firstOutcome).command();

        assertEquals(first, second);
        assertEquals(currentBlockThen(USER_XMX),
            effectiveVmArgumentsOf(second, STARTUP_INI));
    }

    @Test
    public void anIniEditMadeAfterStartupWinsOnAGrownInstance()
    {
        List<String> currentIni = new ArrayList<>(STARTUP_INI);
        currentIni.set(currentIni.size() - 1, "-Xmx6144m"); //$NON-NLS-1$

        RestartEdtTool.RelaunchPlan plan = relaunchOf(STARTUP_INI, grownProperty());

        assertFalse(plan.command().contains("-vmargs")); //$NON-NLS-1$
        List<String> effective = effectiveVmArgumentsOf(plan.command(), currentIni);

        // The launcher adds its current block itself, and the stale copies stay out of it.
        assertEquals(currentIni, effective);
        assertTrue(effective.contains("-Xmx6144m")); //$NON-NLS-1$
        assertFalse(effective.contains(USER_XMX));
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
    public void theNoteNamesWhatTheRelaunchCarries()
    {
        RestartEdtTool.RelaunchPlan refused = RestartEdtTool.relaunchCommandOf(STARTUP_INI, null,
            LAUNCHER, WORKSPACE, VM);

        String withoutUserArguments = RestartEdtTool.noteOf("restart", 1000, false, //$NON-NLS-1$
            RestartEdtTool.vmArgumentsNoteOf(refused));
        assertFalse(withoutUserArguments.contains("with the same arguments")); //$NON-NLS-1$
        assertTrue(withoutUserArguments.contains("current .ini VM block")); //$NON-NLS-1$

        String withUserArguments = RestartEdtTool.noteOf("restart", 1000, false, null); //$NON-NLS-1$
        assertTrue(withUserArguments.contains("with the same arguments")); //$NON-NLS-1$
        assertFalse(withUserArguments.contains("current .ini VM block")); //$NON-NLS-1$
    }

    @Test
    public void theCommandNamesTheLauncherTheWorkspaceAndTheVm()
    {
        List<String> command = relaunchOf(STARTUP_INI, grownProperty(USER_XMX)).command();

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
            List<String> expected = Arrays.asList("-server", " -Xmx4g"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.write(dir.resolve("1cedt.ini"), Arrays.asList( //$NON-NLS-1$
                "-startup", //$NON-NLS-1$
                "plugins/org.eclipse.equinox.launcher_1.6.0.jar", //$NON-NLS-1$
                "-vmargs", //$NON-NLS-1$
                "-server", //$NON-NLS-1$
                " -Xmx4g"), StandardCharsets.UTF_8); //$NON-NLS-1$

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
    public void aBlankLineClosingTheIniBlockDoesNotBreakThePrefix()
        throws Exception
    {
        Path dir = Files.createTempDirectory("aiedt-ini-blank"); //$NON-NLS-1$
        try
        {
            Files.write(dir.resolve("1cedt.ini"), Arrays.asList( //$NON-NLS-1$
                "-vmargs", //$NON-NLS-1$
                "-server", //$NON-NLS-1$
                "-Xms80m", //$NON-NLS-1$
                ""), StandardCharsets.UTF_8); //$NON-NLS-1$

            List<String> snapshot = RestartEdtTool.iniVmArgumentsOf(
                new File(dir.toFile(), "1cedt.exe")); //$NON-NLS-1$
            assertEquals(Arrays.asList("-server", "-Xms80m"), snapshot); //$NON-NLS-1$ //$NON-NLS-2$

            // A process started from that file records the block, then its own argument: the
            // blank line cannot stand in the property, and the prefix has to hold without it.
            List<String> property = new ArrayList<>(snapshot);
            property.add(USER_XMX);

            RestartEdtTool.RelaunchPlan plan = relaunchOf(snapshot, property);

            assertNull(plan.vmArgumentsOmissionReason());
            assertEquals(USER_XMX, plan.command().get(plan.command().size() - 1));
            assertEquals(1, Collections.frequency(plan.command(), USER_XMX));
        }
        finally
        {
            Files.deleteIfExists(dir.resolve("1cedt.ini")); //$NON-NLS-1$
            Files.deleteIfExists(dir);
        }
    }

    /**
     * The entry point of a real restart reads the snapshot the bundle captured at activation and
     * the {@code eclipse.vmargs} property of the running instance - not a value handed in by a
     * caller. This test names an installation of its own in those system properties and checks what
     * the command does with them.
     *
     * @throws Exception when the temporary installation cannot be written.
     */
    @Test
    public void theRealRelaunchReadsTheSnapshotAndThePropertyOfTheInstance()
        throws Exception
    {
        Path home = Files.createTempDirectory("aiedt-home"); //$NON-NLS-1$
        Path workspace = Files.createTempDirectory("aiedt-ws"); //$NON-NLS-1$
        String homeProperty = System.getProperty("eclipse.home.location"); //$NON-NLS-1$
        String workspaceProperty = System.getProperty("osgi.instance.area"); //$NON-NLS-1$
        String vmProperty = System.getProperty("eclipse.vm"); //$NON-NLS-1$
        String vmargsProperty = System.getProperty("eclipse.vmargs"); //$NON-NLS-1$
        try
        {
            List<String> ini = new ArrayList<>();
            ini.add("-vmargs"); //$NON-NLS-1$
            ini.addAll(STARTUP_INI);
            Files.write(home.resolve("1cedt.ini"), ini, StandardCharsets.UTF_8); //$NON-NLS-1$
            Files.createFile(home.resolve("1cedt.exe")); //$NON-NLS-1$
            System.setProperty("eclipse.home.location", home.toUri().toString()); //$NON-NLS-1$
            System.setProperty("osgi.instance.area", workspace.toUri().toString()); //$NON-NLS-1$
            System.setProperty("eclipse.vm", VM); //$NON-NLS-1$
            System.setProperty("eclipse.vmargs", //$NON-NLS-1$
                eclipseVmargsOf(grownProperty(USER_XMX)));

            RestartEdtTool.captureIniVmArgumentsAtStartup();
            List<String> command = RestartEdtTool.relaunchCommandOf();

            assertNotNull(command);
            assertEquals(home.resolve("1cedt.exe").toFile().getAbsolutePath(), command.get(0)); //$NON-NLS-1$
            assertEquals(workspace.toFile().getAbsolutePath(), command.get(2));
            assertEquals(VM, command.get(4));
            // The block the instance records stays out of the command; only the user's argument,
            // once, is handed to the launcher.
            assertEquals(Collections.singletonList(USER_XMX),
                command.subList(command.indexOf("-vmargs") + 1, command.size())); //$NON-NLS-1$
        }
        finally
        {
            restore("eclipse.home.location", homeProperty); //$NON-NLS-1$
            restore("osgi.instance.area", workspaceProperty); //$NON-NLS-1$
            restore("eclipse.vm", vmProperty); //$NON-NLS-1$
            restore("eclipse.vmargs", vmargsProperty); //$NON-NLS-1$
            Files.deleteIfExists(home.resolve("1cedt.ini")); //$NON-NLS-1$
            Files.deleteIfExists(home.resolve("1cedt.exe")); //$NON-NLS-1$
            Files.deleteIfExists(home);
            Files.deleteIfExists(workspace);
            // The snapshot is a static of the bundle: leave it naming the installation the test
            // runtime really runs on.
            RestartEdtTool.captureIniVmArgumentsAtStartup();
        }
    }

    private static void restore(String key, String value)
    {
        if (value == null)
        {
            System.clearProperty(key);
        }
        else
        {
            System.setProperty(key, value);
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
        assertEquals(currentBlockThen(STARTUP_INI.get(0)), effective);
    }
}
