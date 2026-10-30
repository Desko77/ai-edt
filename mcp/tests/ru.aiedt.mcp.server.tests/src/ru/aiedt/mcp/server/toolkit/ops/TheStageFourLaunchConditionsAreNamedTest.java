/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.Test;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.settings.ToolSettingsStore;
import ru.aiedt.mcp.server.support.guidance.AgentGuidance;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * The launch conditions a caller reads from a schema or from help, without starting a client.
 *
 * <p>The strings are the contract. A schema that still says the client starts empty, or a help topic
 * that omits the refusal, sends the caller down a route the current code does not take.
 */
public class TheStageFourLaunchConditionsAreNamedTest
{
    /**
     * Both client schemas say startupOption is /C only and that a running client is answered first.
     */
    @Test
    public void theClientSchemasNameTheStartupStringAndTheRunningClient()
    {
        String facade = new InfobaseAdminFacadeTool().getInputSchema();
        String client = new ClientSessionStarter().getInputSchema();
        for (String schema : new String[] { facade, client })
        {
            require(schema, "saved configuration is not changed", "/Execute",
                "external-object binding", "alreadyRunning", "Explicit true is refused",
                "allowSecondSession");
        }
        require(facade, "/C only"); //$NON-NLS-1$
        require(client, "/C startup string only"); //$NON-NLS-1$
        require(new ClientSessionStarter().getDescription(), "1cv8.exe", "launch_debugger",
            "saved EDT launch configuration", "attributes", "Dump readiness is not checked",
            "externalObjectName", "alreadyRunning");
    }

    /**
     * The debugger schemas name the external-object route and the refusals the launch actually returns.
     */
    @Test
    public void theDebuggerSchemasNameTheExternalObjectRoute()
    {
        String facade = new LaunchDebuggerTool().getInputSchema();
        require(facade, "/C only", "/Execute is set by the environment from externalObjectName",
            "saved configuration is not changed", "Nothing was launched", "Attach",
            "client launch configuration", "external-object project", "import_external_object",
            "SKIPPED_BY_PRESET", "default true", "write door of a preset that blocks writing");
        assertFalse("the facade schema must say the launch is refused, not that the client starts empty", //$NON-NLS-1$
            facade.contains("starts empty")); //$NON-NLS-1$

        String standalone = new DebugSessionStarter().getInputSchema();
        require(standalone, "/Execute", "write door of a preset that blocks writing",
            "external_object_workshop", "Nothing was launched", "unreachable dump service",
            "launch is refused", "rather than starting a client with nothing open",
            "SKIPPED_BY_PRESET", "default true");
        assertFalse("the standalone schema must not describe a client that starts without the object", //$NON-NLS-1$
            standalone.contains("the client starts with nothing open")); //$NON-NLS-1$
        assertFalse("the standalone schema must not promise an empty client", //$NON-NLS-1$
            standalone.contains("starts empty")); //$NON-NLS-1$
    }

    /**
     * The launch workflow carries the whole external-object route, including each refusal.
     */
    @Test
    public void theLaunchWorkflowCarriesTheExternalObjectRoute()
    {
        Map<String, String> call = new HashMap<>();
        call.put("action", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        call.put("topic", "workflow"); //$NON-NLS-1$ //$NON-NLS-2$
        String workflow = new LaunchDebuggerTool().execute(call);

        require(workflow, "externalObjectName", "externalObjectProject", "client configuration", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Attach", "launches nothing", "when the dump service answers", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "unreachable dump service", "Nothing was launched", //$NON-NLS-1$ //$NON-NLS-2$
            "write door of a preset that blocks writing", "changes the project setting", //$NON-NLS-1$ //$NON-NLS-2$
            "import_external_object", "enableExternalObjectDump"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the dump setting is refused only by a preset that blocks writing", //$NON-NLS-1$
            workflow.contains("is refused by the write door.")); //$NON-NLS-1$
    }

    /**
     * Under Canonical the route is on the facade in tools/list and in both help texts.
     */
    @Test
    public void canonicalReadsTheRouteFromTheFacade()
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        String disabledBefore = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
        String unlistedBefore = store.getString(PrefKeys.PREF_UNLISTED_TOOLS);
        registerTheLaunchTools();
        try
        {
            ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);

            assertTrue("start_client stays out of tools/list under Canonical", //$NON-NLS-1$
                listed("start_client") == null); //$NON-NLS-1$
            IMcpTool infobase = listed("infobase_admin"); //$NON-NLS-1$
            assertNotNull("infobase_admin is the listed route under Canonical", infobase); //$NON-NLS-1$
            require(infobase.getInputSchema(), "/C only", "/Execute", "alreadyRunning", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "saved configuration is not changed"); //$NON-NLS-1$

            String catalog = help(infobase, "operation", null); //$NON-NLS-1$
            String workflow = help(infobase, "operation", "workflow"); //$NON-NLS-1$ //$NON-NLS-2$
            for (String text : new String[] { catalog, workflow })
            {
                require(text, "operation picker", "Dump readiness is not checked", //$NON-NLS-1$ //$NON-NLS-2$
                    "externalObjectName", "/C only", "alreadyRunning"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        finally
        {
            store.setValue(PrefKeys.PREF_DISABLED_TOOLS, disabledBefore);
            store.setValue(PrefKeys.PREF_UNLISTED_TOOLS, unlistedBefore);
            store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
        }
    }

    /**
     * Under Debug &amp; Test the same route is the standalone, and the facade is not listed.
     */
    @Test
    public void debugAndTestReadsTheRouteFromTheStandalone()
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        String disabledBefore = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
        String unlistedBefore = store.getString(PrefKeys.PREF_UNLISTED_TOOLS);
        registerTheLaunchTools();
        try
        {
            ToolSettingsStore.getInstance().applyPreset(ToolProfile.DEBUG_AND_TEST);

            assertTrue("infobase_admin is off under Debug & Test", //$NON-NLS-1$
                listed("infobase_admin") == null); //$NON-NLS-1$
            IMcpTool client = listed("start_client"); //$NON-NLS-1$
            assertNotNull("start_client is the listed route under Debug & Test", client); //$NON-NLS-1$
            require(client.getDescription(), "alreadyRunning", "externalObjectName", //$NON-NLS-1$ //$NON-NLS-2$
                "Dump readiness is not checked", "1cv8.exe"); //$NON-NLS-1$ //$NON-NLS-2$
            require(client.getInputSchema(), "/Execute", "/C startup string only", //$NON-NLS-1$ //$NON-NLS-2$
                "alreadyRunning"); //$NON-NLS-1$
        }
        finally
        {
            store.setValue(PrefKeys.PREF_DISABLED_TOOLS, disabledBefore);
            store.setValue(PrefKeys.PREF_UNLISTED_TOOLS, unlistedBefore);
            store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
        }
    }

    /**
     * The workshop and the query check say where they sit, and none of the six descriptions repeats
     * the guidance refusal.
     */
    @Test
    public void theWorkshopAndTheQueryCheckNameTheirPlace()
    {
        String workshop = new ExternalObjectWorkshopTool().getDescription();
        require(workshop, "import_external_object", "V8ExternalObjectsNature", //$NON-NLS-1$ //$NON-NLS-2$
            "launch_debugger action=launch"); //$NON-NLS-1$

        String query = new QueryValidator().getDescription();
        require(query, "before the query is executed", "written into code", "describeResult", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "dcsMode accepts data composition (DCS) query syntax"); //$NON-NLS-1$
        assertFalse("dcsMode parses the query text in DCS mode; it does not check a data " //$NON-NLS-1$
            + "composition schema", query.contains("checks a data composition schema")); //$NON-NLS-1$

        for (String description : new String[] {
            new InfobaseAdminFacadeTool().getDescription(),
            new ClientSessionStarter().getDescription(),
            new LaunchDebuggerTool().getDescription(),
            new DebugSessionStarter().getDescription(),
            workshop,
            query })
        {
            assertFalse(description, description.contains(AgentGuidance.NOT_SERVED));
        }
    }

    /**
     * Registers the three tools the catalogue assertions read, keeping a tool that is already there.
     */
    private static void registerTheLaunchTools()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        catalog.register(new InfobaseAdminFacadeTool());
        catalog.register(new ClientSessionStarter());
        catalog.register(new LaunchDebuggerTool());
    }

    /**
     * The tool of this name in the advertised list, or <code>null</code> when the preset hides it.
     *
     * @param name the wire name
     * @return the listed tool, or <code>null</code>
     */
    private static IMcpTool listed(String name)
    {
        for (IMcpTool tool : McpToolCatalog.getInstance().getEnabledTools())
        {
            if (name.equals(tool.getName()))
            {
                return tool;
            }
        }
        return null;
    }

    /**
     * Asks a facade for help. A missing topic is the catalog.
     *
     * @param tool the facade
     * @param dispatch {@code operation} or {@code action}
     * @param topic the topic, or <code>null</code> for the catalog
     * @return the help text
     */
    private static String help(IMcpTool tool, String dispatch, String topic)
    {
        Map<String, String> call = new HashMap<>();
        call.put(dispatch, "help"); //$NON-NLS-1$
        if (topic != null)
        {
            call.put("topic", topic); //$NON-NLS-1$
        }
        return tool.execute(call);
    }

    /**
     * Fails naming every phrase the text does not contain.
     *
     * @param text the schema, description or help
     * @param phrases the phrases that text has to carry
     */
    private static void require(String text, String... phrases)
    {
        List<String> missing = new ArrayList<>();
        String body = text == null ? "" : text; //$NON-NLS-1$
        for (String phrase : phrases)
        {
            if (!body.contains(phrase))
            {
                missing.add(phrase);
            }
        }
        assertTrue("missing " + missing, missing.isEmpty()); //$NON-NLS-1$
    }
}
