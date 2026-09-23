/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.settings.ToolSettingsStore;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ToolRoadOutcome;
import ru.aiedt.mcp.server.toolkit.ToolWhiteboard;
import ru.aiedt.mcp.server.toolkit.ops.GitTool;

/**
 * The gates a wire call passes refuse an internal call the same way: a tool the user switched off,
 * a writer under a write-blocking preset, and a facade operation that preset switched off.
 */
public class TheRoadRefusesWhatTheGateRefusesTest
{
    private BundleContext context;

    private final java.util.List<ServiceRegistration<?>> registrations = new java.util.ArrayList<>();

    private IPreferenceStore store;

    private String presetBefore;

    private String disabledBefore;

    private ToolRoad road;

    @Before
    public void aRoadAndAStore()
    {
        context = FrameworkUtil.getBundle(TheRoadRefusesWhatTheGateRefusesTest.class).getBundleContext();
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        disabledBefore = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
        road = new ToolRoad(new Semaphore(2));
    }

    @After
    public void theProbesAndThePresetGo()
    {
        for (ServiceRegistration<?> registration : registrations)
        {
            try
            {
                registration.unregister();
            }
            catch (IllegalStateException alreadyGone)
            {
                // unregistered by the test itself
            }
        }
        McpToolCatalog.getInstance().unregister("git"); //$NON-NLS-1$
        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, disabledBefore);
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
    }

    /** A tool the user switched off answers with the exact text the router shows the agent. */
    @Test
    public void aSwitchedOffToolIsRefusedWithTheGateWording()
    {
        String name = "road_gate_disabled_" + System.nanoTime(); //$NON-NLS-1$
        registrations.add(context.registerService(IMcpTool.class, new Probe(name),
            properties(Boolean.FALSE)));
        waitUntilCallable(name);

        ToolSettingsStore.getInstance().setToolEnabled(name, false);
        try
        {
            ToolRoadOutcome outcome = road.call(name, Map.of(), "gate-test"); //$NON-NLS-1$
            assertTrue("a disabled tool is refused, not run", outcome.refused()); //$NON-NLS-1$
            assertEquals(ToolGate.disabledMessage(name), outcome.refusal());
        }
        finally
        {
            ToolSettingsStore.getInstance().setToolEnabled(name, true);
        }
    }

    /** A tool whose bundle says it writes is switched off by a write-blocking preset. */
    @Test
    public void aWritingToolIsRefusedUnderAReadOnlyPreset()
    {
        String name = "road_gate_writer_" + System.nanoTime(); //$NON-NLS-1$
        registrations.add(context.registerService(IMcpTool.class, new Probe(name),
            properties(Boolean.TRUE)));
        waitUntilCallable(name);

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());
        try
        {
            assertFalse("a writer is not callable under Read-only", //$NON-NLS-1$
                McpToolCatalog.getInstance().isToolEnabled(name));
            ToolRoadOutcome outcome = road.call(name, Map.of(), "gate-test"); //$NON-NLS-1$
            assertTrue(outcome.refused());
            assertEquals(ToolGate.disabledMessage(name), outcome.refusal());
        }
        finally
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.ALL_TOOLS.name());
        }
    }

    /**
     * A facade operation the preset switched off is refused by the facade's own gate, which an
     * internal call reaches just as a wire call does.
     */
    @Test
    public void aPresetGatedFacadeOperationRefusesItself()
    {
        McpToolCatalog.getInstance().register(new GitTool());
        assertTrue(McpToolCatalog.getInstance().isToolEnabled("git")); //$NON-NLS-1$

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("operation", "commit"); //$NON-NLS-1$ //$NON-NLS-2$
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());
        try
        {
            ToolRoadOutcome outcome = road.call("git", arguments, "gate-test"); //$NON-NLS-1$ //$NON-NLS-2$
            // The road ran the facade; the facade's gate is the one that answered, and its refusal
            // is a structured tool result carrying the gate wording for the gated write. The
            // wording is compared by its stable parts because the JSON serializer escapes the
            // apostrophes around the tool name.
            assertFalse("the facade itself was callable", outcome.refused()); //$NON-NLS-1$
            assertTrue("the facade's gate answered about the gated write: " + outcome.text(), //$NON-NLS-1$
                outcome.text().contains("git_commit") //$NON-NLS-1$
                    && outcome.text().contains("is disabled and was not executed")); //$NON-NLS-1$
        }
        finally
        {
            store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.ALL_TOOLS.name());
            McpToolCatalog.getInstance().unregister("git"); //$NON-NLS-1$
        }
    }

    private static Hashtable<String, Object> properties(Boolean writes)
    {
        Hashtable<String, Object> properties = new Hashtable<>();
        if (writes != null)
        {
            properties.put(ToolWhiteboard.WRITES, writes);
        }
        return properties;
    }

    private static void waitUntilCallable(String name)
    {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (McpToolCatalog.getInstance().getTool(name) == null)
        {
            if (System.currentTimeMillis() > deadline)
            {
                throw new IllegalStateException("the whiteboard never picked up " + name); //$NON-NLS-1$
            }
            try
            {
                Thread.sleep(20L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for " + name, e); //$NON-NLS-1$
            }
        }
    }

    /** The least a tool can be. */
    private static final class Probe implements IMcpTool
    {
        private final String name;

        Probe(String name)
        {
            this.name = name;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public String getDescription()
        {
            return "a probe"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return "ran"; //$NON-NLS-1$
        }
    }
}
