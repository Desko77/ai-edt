/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;

/**
 * The analyzer is told where its workspace is, and it is the project.
 * <p>
 * bsl-language-server relativizes every source path against its workspace, and without {@code -w}
 * that workspace is the directory the process was started in - the EDT installation. Reported from
 * a workspace on one drive with EDT on another: {@code Path.relativize} across two roots throws
 * "'other' has different root", no report is written, and the call comes back exit 1. Reproduced
 * from a shell: running the analyzer with a working directory on C: and sources on E: printed that
 * exception and wrote no report; adding {@code -w} produced one.
 * </p>
 * <p>
 * The working directory matters too, and for a different reason: the analyzer resolves the paths it
 * writes into the report against it. With {@code -w} given but the working directory left on the
 * EDT installation, the report named files under that installation - eleven entries, none of which
 * exist. Both are checked here.
 * </p>
 */
public class TheAnalyzerIsToldWhereItsWorkspaceIsTest
{
    private static final File JAR = new File("/analyzer/bsl-language-server-exec.jar"); //$NON-NLS-1$
    private static final File OUT = new File("/tmp/report"); //$NON-NLS-1$
    private static final String JAVA = "/jre/bin/java"; //$NON-NLS-1$

    private static List<String> commandFor(File workspace, File src)
    {
        return BslCodeReviewTool.analyzerCommand(JAR, workspace, src, OUT, JAVA);
    }

    private static String valueAfter(List<String> command, String option)
    {
        int at = command.indexOf(option);
        assertTrue(option + " is not in " + command, at >= 0 && at + 1 < command.size()); //$NON-NLS-1$
        return command.get(at + 1);
    }

    /** The workspace is named, and it is the project rather than the source folder. */
    @Test
    public void theWorkspaceIsNamedAndItIsTheProject()
    {
        File project = new File("project").getAbsoluteFile(); //$NON-NLS-1$
        File src = new File(project, "src"); //$NON-NLS-1$
        List<String> command = commandFor(project, src);

        assertEquals(project.getAbsolutePath(), valueAfter(command, "-w")); //$NON-NLS-1$
        assertEquals(src.getAbsolutePath(), valueAfter(command, "-s")); //$NON-NLS-1$
    }

    /** Workspace and sources share a root, which is the whole point on Windows. */
    @Test
    public void theWorkspaceSharesARootWithTheSources()
    {
        File project = new File("project").getAbsoluteFile(); //$NON-NLS-1$
        File src = new File(project, "src"); //$NON-NLS-1$
        List<String> command = commandFor(project, src);

        assertEquals("a workspace on another root is what threw 'other has different root'", //$NON-NLS-1$
            Paths.get(valueAfter(command, "-s")).getRoot(), //$NON-NLS-1$
            Paths.get(valueAfter(command, "-w")).getRoot()); //$NON-NLS-1$
    }

    /** The report still goes where the caller asked, and the analysis is still the quiet one. */
    @Test
    public void theRestOfTheCommandIsUnchanged()
    {
        File project = new File("project").getAbsoluteFile(); //$NON-NLS-1$
        List<String> command = commandFor(project, new File(project, "src")); //$NON-NLS-1$

        assertEquals(JAVA, command.get(0));
        assertTrue("the analysis subcommand has to be there", //$NON-NLS-1$
            command.contains("--analyze")); //$NON-NLS-1$
        assertEquals("json", valueAfter(command, "-r")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(OUT.getAbsolutePath(), valueAfter(command, "-o")); //$NON-NLS-1$
        assertTrue("the silent flag keeps the report parseable", command.contains("-q")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
