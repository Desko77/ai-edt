/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.oform.ops.OperationsCatalog;
import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * Every writing operation of the tool is refused while its write capability is switched off, and
 * refused before the project is resolved: a dryRun included, a bogus project name included, no
 * project name at all included - the refusal is the write door's, not the project lookup's. Every
 * write-blocking preset refuses the same way: Read-only, Code Review and Debug &amp; Test. The
 * reading operations are not stopped by the door: they reach the project resolution, and help
 * answers without one.
 *
 * <p>The door is closed the way each write-blocking preset closes it. The preset names and the
 * preference keys live in the server bundle, which does not export its settings package; the
 * literals here are that bundle's own key and the presets' recorded names, and the first assertion
 * of every pass proves the door is really closed rather than letting a mistyped name pass the
 * checks silently.</p>
 */
public class AWritingOperationIsRefusedBeforeTheProjectIsResolvedTest
{
    /** The server bundle's preference key of the tool preset, not exported for import. */
    private static final String PRESET_KEY = "mcpToolPreset"; //$NON-NLS-1$

    /** The Read-only preset as the store records it. */
    private static final String READ_ONLY = "READ_ONLY"; //$NON-NLS-1$

    /** Every preset that blocks writes, as the store records them. */
    private static final List<String> WRITE_BLOCKING = List.of("READ_ONLY", "CODE_REVIEW", "DEBUG_AND_TEST"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private IPreferenceStore store;

    private String presetBefore;

    @Before
    public void aStoreAndItsPreset()
    {
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PRESET_KEY);
    }

    @After
    public void thePresetGoesBack()
    {
        store.setValue(PRESET_KEY, presetBefore);
    }

    /** Every operation that writes is refused, dryRun included, before the project is resolved. */
    @Test
    public void everyWritingOperationIsRefusedBeforeTheProject()
    {
        for (String preset : WRITE_BLOCKING)
        {
            store.setValue(PRESET_KEY, preset);
            assertTrue("the door is closed by the preset " + preset, //$NON-NLS-1$
                McpToolCatalog.getInstance().isDisabledByPreset(OrdinaryFormsTool.WRITE_DOOR));
            everyWritingOperationIsRefused(preset);
        }
    }

    /**
     * One pass of the inventory under one write-blocking preset: with a dryRun, without a project
     * and with a bogus project name, every writing operation meets the door's refusal and never
     * the project lookup's.
     *
     * @param preset the write-blocking preset the store holds
     */
    private static void everyWritingOperationIsRefused(String preset)
    {
        OrdinaryFormsTool tool = new OrdinaryFormsTool();
        for (String name : OperationsCatalog.writingNames())
        {
            String where = preset + " " + name; //$NON-NLS-1$
            String withDryRun = tool.execute(Map.of("operation", name, "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(where, withDryRun.contains("switched off by the tool settings")); //$NON-NLS-1$
            assertTrue(where, withDryRun.contains("nothing was executed - not even as dryRun")); //$NON-NLS-1$
            assertFalse(where, withDryRun.contains("projectName must be provided")); //$NON-NLS-1$
            String withoutAProject = tool.execute(Map.of("operation", name)); //$NON-NLS-1$
            assertTrue(where, withoutAProject.contains("switched off by the tool settings")); //$NON-NLS-1$
            assertFalse(where, withoutAProject.contains("projectName must be provided")); //$NON-NLS-1$
            String withABogusProject = tool.execute(Map.of("operation", name, "projectName", "no-such-project")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(where, withABogusProject.contains("switched off by the tool settings")); //$NON-NLS-1$
            assertFalse(where, withABogusProject.contains("no-such-project")); //$NON-NLS-1$
        }
    }

    /**
     * The reading operations are not stopped by the door: the project-needing ones reach the
     * project resolution - and name the missing argument, not the door - and help answers without
     * a project at all.
     */
    @Test
    public void theReadingOperationsReachTheProjectResolution()
    {
        store.setValue(PRESET_KEY, READ_ONLY);
        assertTrue(McpToolCatalog.getInstance().isDisabledByPreset(OrdinaryFormsTool.WRITE_DOOR));
        OrdinaryFormsTool tool = new OrdinaryFormsTool();
        for (String name : new String[] { "audit", "layout", "diff_form", "inspect_layout", "describe_owner" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            String answer = tool.execute(Map.of("operation", name)); //$NON-NLS-1$
            assertTrue(name, answer.contains("projectName must be provided")); //$NON-NLS-1$
            assertFalse(name, answer.contains("switched off by the tool settings")); //$NON-NLS-1$
        }
        String help = tool.execute(Map.of("operation", "help")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(help, com.google.gson.JsonParser.parseString(help).getAsJsonObject().get("success") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsBoolean());
        assertTrue(help, help.contains("inspect_layout")); //$NON-NLS-1$
    }
}
