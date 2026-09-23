/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.support.naparnik.BundleCopy;
import ru.aiedt.mcp.server.support.naparnik.NaparnikAccessException;
import ru.aiedt.mcp.server.support.naparnik.NaparnikHost;
import ru.aiedt.mcp.server.support.naparnik.OsgiNaparnikHost;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Reports whether 1C:Naparnik is installed and whether that installation is one this bridge can call.
 * <p>
 * {@code status} without {@code probe} only lists bundles. {@code probe=true} starts the Naparnik UI
 * bundle when it is merely resolved and walks each link to the facade. A question sent through this
 * tool, and whatever Naparnik's own tools read, goes to the 1C:Naparnik service. The bridge
 * preference is off by default; {@code status} answers either way. Read-only presets disable the
 * tool by name.
 * </p>
 */
public class NaparnikTool
    implements IMcpTool
{
    /** The only version this bridge calls. A qualifier after the micro is accepted. */
    static final String SUPPORTED_VERSION = "1.0.7"; //$NON-NLS-1$

    private static final String BUNDLE_AI = "com.e1c.edt.ai"; //$NON-NLS-1$

    private static final String BUNDLE_CONTEXT = "com.e1c.edt.ai.context"; //$NON-NLS-1$

    private static final String BUNDLE_UI = "com.e1c.edt.ai.ui"; //$NON-NLS-1$

    private static final String BUNDLE_UI_COMMON = "com.e1c.edt.ai.ui.common"; //$NON-NLS-1$

    private static final List<String> BUNDLE_NAMES = List.of(
        BUNDLE_AI, BUNDLE_CONTEXT, BUNDLE_UI, BUNDLE_UI_COMMON);

    /** Version is checked on these three. {@code context} is not a singleton and is not versioned. */
    private static final List<String> VERSIONED = List.of(BUNDLE_AI, BUNDLE_UI, BUNDLE_UI_COMMON);

    private static final String FACADE_CLASS = "com.e1c.edt.ai.IConversationFacade"; //$NON-NLS-1$

    private static final String TOOLS_CLASS = "com.e1c.edt.ai.IMcpTools"; //$NON-NLS-1$

    private static final String ACTIVATOR_CLASS = "com.e1c.edt.ai.ui.BaseActivator"; //$NON-NLS-1$

    /**
     * Naparnik tool names a later question may allow. Each one reads the project or the IDE. The
     * writers and runners are absent on purpose: {@code Execute}, {@code JGit}, {@code Write},
     * {@code Edit}, {@code Delete}, {@code SetMarkers}, {@code DeleteMarkers}, {@code JShellSession},
     * {@code JShell}, {@code JShellManual}, {@code JShellReflection}, {@code Svg},
     * {@code 1C_EditMetadata} and {@code GetVisualContext}. {@code status} with {@code probe=true}
     * reports this list as {@code tools.allowed}. Sending a question is not an operation of this
     * tool.
     */
    static final List<String> ALLOWED_TOOLS = List.of(
        "GetProjects", //$NON-NLS-1$
        "GetCommandCategories", //$NON-NLS-1$
        "GetCommands", //$NON-NLS-1$
        "Read", //$NON-NLS-1$
        "SearchText", //$NON-NLS-1$
        "Glob", //$NON-NLS-1$
        "List", //$NON-NLS-1$
        "LocalHistory", //$NON-NLS-1$
        "LocalChanges", //$NON-NLS-1$
        "NavigationHistory", //$NON-NLS-1$
        "GetMarkers", //$NON-NLS-1$
        "1C_Find", //$NON-NLS-1$
        "1C_GetObject"); //$NON-NLS-1$

    private final NaparnikHost host;

    private final Boolean bridgeEnabledOverride;

    /**
     * The installation in this runtime, and the bridge preference as the store holds it.
     */
    public NaparnikTool()
    {
        this(new OsgiNaparnikHost(), null);
    }

    /**
     * @param host the installation to ask; tests pass a fake
     */
    public NaparnikTool(NaparnikHost host)
    {
        this(host, null);
    }

    /**
     * @param host the installation to ask
     * @param bridgeEnabled the preference value to report; {@code null} reads the store
     */
    public NaparnikTool(NaparnikHost host, Boolean bridgeEnabled)
    {
        this.host = host;
        this.bridgeEnabledOverride = bridgeEnabled;
    }

    @Override
    public String getName()
    {
        return "naparnik"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Reports whether 1C:Naparnik is installed, which version, and whether that version " //$NON-NLS-1$
            + "is the supported 1.0.7. operation=status only lists bundles. probe=true starts the " //$NON-NLS-1$
            + "Naparnik UI bundle if it is not already running, creates its injector, and checks " //$NON-NLS-1$
            + "each link to the facade. A question sent through this tool, and whatever Naparnik's " //$NON-NLS-1$
            + "tools read, goes to the 1C:Naparnik service. The bridge is off by default; status " //$NON-NLS-1$
            + "answers either way. Read-only presets disable this tool."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "status lists the installation. help describes this tool.", true) //$NON-NLS-1$
            .booleanProperty("probe", //$NON-NLS-1$
                "With status, walk each link to the facade. This starts the Naparnik UI bundle " //$NON-NLS-1$
                    + "and creates its injector when the user has not opened Naparnik yet. Default false.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String operation = JsonUtils.normalizeOperationToken(
            JsonUtils.extractStringArgument(params, "operation")); //$NON-NLS-1$
        if (operation == null || operation.isEmpty())
        {
            return ToolResult.error(
                "Missing operation. This tool answers status and help.").toJson(); //$NON-NLS-1$
        }
        switch (operation)
        {
            case "help": //$NON-NLS-1$
                return buildHelp();
            case "status": //$NON-NLS-1$
                return status(params);
            default:
                return ToolResult.error("Unknown operation '" + operation //$NON-NLS-1$
                    + "'. This tool answers status and help.").toJson(); //$NON-NLS-1$
        }
    }

    /**
     * The catalog {@code help} builds. The dispatcher and this text are kept in the same class so a
     * new operation has to be named here before an agent can be told it exists.
     *
     * @return the help document
     */
    private String buildHelp()
    {
        String text = "status reports whether 1C:Naparnik is installed, which copies exist, " //$NON-NLS-1$
            + "whether the chosen version is " + SUPPORTED_VERSION //$NON-NLS-1$
            + ", and the bridge setting (mcpNaparnikBridgeEnabled, off by default). " //$NON-NLS-1$
            + "probe=true starts the Naparnik UI bundle if it is not already running and checks " //$NON-NLS-1$
            + "each link to the facade: class loading, the injector, the facade, and the tool " //$NON-NLS-1$
            + "names. A question sent through this tool, and whatever Naparnik's tools read, goes " //$NON-NLS-1$
            + "to the 1C:Naparnik service. The bridge setting does not change what status reports. " //$NON-NLS-1$
            + "Read-only presets disable naparnik."; //$NON-NLS-1$
        return ToolResult.success()
            .put("operation", "help") //$NON-NLS-1$ //$NON-NLS-2$
            .put("text", text) //$NON-NLS-1$
            .toJson();
    }

    private String status(Map<String, String> params)
    {
        boolean probe = JsonUtils.extractBooleanArgument(params, "probe", false); //$NON-NLS-1$
        Survey survey = survey();
        ToolResult result = ToolResult.success()
            .put("bridgeEnabled", bridgeEnabled()) //$NON-NLS-1$
            .put("supportedVersion", SUPPORTED_VERSION) //$NON-NLS-1$
            .put("inPolicy", survey.inPolicy) //$NON-NLS-1$
            .put("bundles", survey.bundles); //$NON-NLS-1$
        if (survey.refusal != null)
        {
            result.put("refusal", survey.refusal); //$NON-NLS-1$
        }
        // An out-of-policy install is not started. probe walks links only after each singleton
        // has one chosen copy and context has at least one. A second resolved context copy is
        // not a refusal: context is not a singleton, and the bridge loads nothing from it.
        if (probe && survey.inPolicy)
        {
            walkLinks(survey, result);
        }
        return result.toJson();
    }

    private Survey survey()
    {
        Survey survey = new Survey();
        Map<String, List<BundleCopy>> byName = new LinkedHashMap<>();
        boolean any = false;
        for (String name : BUNDLE_NAMES)
        {
            try
            {
                List<BundleCopy> copies = host.copiesOf(name);
                if (copies == null)
                {
                    copies = List.of();
                }
                byName.put(name, copies);
                if (!copies.isEmpty())
                {
                    any = true;
                }
            }
            catch (NaparnikAccessException failure)
            {
                survey.refusal = failure.link() + ": " + failure.getMessage(); //$NON-NLS-1$
                survey.bundles = rows(byName);
                return survey;
            }
        }
        survey.bundles = rows(byName);
        if (!any)
        {
            survey.refusal = "1C:Naparnik is not installed"; //$NON-NLS-1$
            return survey;
        }
        String duplicate = duplicateSingleton(byName);
        if (duplicate != null)
        {
            survey.refusal = duplicate;
            return survey;
        }
        Map<String, BundleCopy> chosen = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String name : BUNDLE_NAMES)
        {
            BundleCopy pick = chosenCopy(name, byName.get(name));
            if (pick == null)
            {
                missing.add(name);
            }
            else
            {
                chosen.put(name, pick);
            }
        }
        if (!missing.isEmpty())
        {
            survey.refusal = notResolved(byName, missing);
            return survey;
        }
        List<String> outside = new ArrayList<>();
        for (String name : VERSIONED)
        {
            BundleCopy pick = chosen.get(name);
            if (!supported(pick.version()))
            {
                outside.add(pick.name() + " " + pick.version()); //$NON-NLS-1$
            }
        }
        if (!outside.isEmpty())
        {
            survey.refusal = "outside the supported version " + SUPPORTED_VERSION + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", outside); //$NON-NLS-1$
            return survey;
        }
        survey.inPolicy = true;
        survey.chosen = chosen;
        return survey;
    }

    private void walkLinks(Survey survey, ToolResult result)
    {
        List<Map<String, Object>> links = new ArrayList<>();
        result.put("links", links); //$NON-NLS-1$
        BundleCopy ai = survey.chosen.get(BUNDLE_AI);
        BundleCopy ui = survey.chosen.get(BUNDLE_UI);
        BundleCopy common = survey.chosen.get(BUNDLE_UI_COMMON);
        try
        {
            Class<?> facadeType = host.loadClass(ai, FACADE_CLASS);
            links.add(link(NaparnikHost.loadClassLink(FACADE_CLASS), true, facadeType.getName()));
            Class<?> toolsType = host.loadClass(ai, TOOLS_CLASS);
            links.add(link(NaparnikHost.loadClassLink(TOOLS_CLASS), true, toolsType.getName()));
            Class<?> activatorType = host.loadClass(common, ACTIVATOR_CLASS);
            links.add(link(NaparnikHost.loadClassLink(ACTIVATOR_CLASS), true, activatorType.getName()));
            NaparnikHost.InjectorDoor injector = host.openInjector(ui, activatorType);
            if (!ui.sameBundle(injector.activator()))
            {
                String detail = foreign("injector activator", injector.activator(), ui); //$NON-NLS-1$
                links.add(link(NaparnikHost.LINK_INJECTOR, false, detail));
                result.put("inPolicy", false); //$NON-NLS-1$
                result.put("refusal", detail); //$NON-NLS-1$
                return;
            }
            links.add(link(NaparnikHost.LINK_INJECTOR, true,
                injector.activator().name() + " " + injector.activator().version())); //$NON-NLS-1$
            NaparnikHost.FacadeDoor facade = host.openFacade(injector.injector(), facadeType);
            if (!ai.sameBundle(facade.owner()))
            {
                String detail = foreign("facade", facade.owner(), ai); //$NON-NLS-1$
                links.add(link(NaparnikHost.LINK_FACADE, false, detail));
                result.put("inPolicy", false); //$NON-NLS-1$
                result.put("refusal", detail); //$NON-NLS-1$
                return;
            }
            links.add(link(NaparnikHost.LINK_FACADE, true,
                facade.owner().name() + " " + facade.owner().version())); //$NON-NLS-1$
            List<String> available = host.toolNames(injector.injector(), toolsType);
            links.add(link(NaparnikHost.LINK_TOOLS, true, Integer.toString(available.size())));
            result.put("tools", toolsReport(available)); //$NON-NLS-1$
        }
        catch (NaparnikAccessException failure)
        {
            links.add(link(failure.link(), false, failure.getMessage()));
            result.put("refusal", failure.link() + ": " + failure.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private boolean bridgeEnabled()
    {
        if (bridgeEnabledOverride != null)
        {
            return bridgeEnabledOverride.booleanValue();
        }
        Activator plugin = Activator.getDefault();
        if (plugin == null)
        {
            return PrefKeys.DEFAULT_NAPARNIK_BRIDGE_ENABLED;
        }
        return plugin.getPreferenceStore().getBoolean(PrefKeys.PREF_NAPARNIK_BRIDGE_ENABLED);
    }

    private static boolean supported(String version)
    {
        return SUPPORTED_VERSION.equals(version)
            || (version != null && version.startsWith(SUPPORTED_VERSION + ".")); //$NON-NLS-1$
    }

    private static String duplicateSingleton(Map<String, List<BundleCopy>> byName)
    {
        for (String name : VERSIONED)
        {
            List<String> versions = new ArrayList<>();
            for (BundleCopy copy : byName.get(name))
            {
                if (copy.chosen())
                {
                    versions.add(copy.version());
                }
            }
            if (versions.size() > 1)
            {
                return "resolved copies of singleton " + name + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + String.join(" and ", versions) //$NON-NLS-1$
                    + "; supported version is " + SUPPORTED_VERSION; //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * The copy this bridge will call. A singleton name may have only one resolved copy.
     * {@code context} is not a singleton, so the first resolved copy is enough.
     */
    private static BundleCopy chosenCopy(String name, List<BundleCopy> copies)
    {
        if (BUNDLE_CONTEXT.equals(name))
        {
            return firstChosen(copies);
        }
        return onlyChosen(copies);
    }

    private static BundleCopy firstChosen(List<BundleCopy> copies)
    {
        for (BundleCopy copy : copies)
        {
            if (copy.chosen())
            {
                return copy;
            }
        }
        return null;
    }

    private static BundleCopy onlyChosen(List<BundleCopy> copies)
    {
        BundleCopy found = null;
        for (BundleCopy copy : copies)
        {
            if (!copy.chosen())
            {
                continue;
            }
            if (found != null)
            {
                return null;
            }
            found = copy;
        }
        return found;
    }

    private static String notResolved(Map<String, List<BundleCopy>> byName, List<String> missing)
    {
        List<String> parts = new ArrayList<>();
        for (String name : missing)
        {
            List<BundleCopy> copies = byName.get(name);
            if (copies == null || copies.isEmpty())
            {
                parts.add(name + " is absent"); //$NON-NLS-1$
            }
            else
            {
                for (BundleCopy copy : copies)
                {
                    parts.add(copy.name() + " " + copy.version() + " (" + copy.state() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }
        }
        return "1C:Naparnik is installed but not resolved: " + String.join("; ", parts) //$NON-NLS-1$ //$NON-NLS-2$
            + "; supported version is " + SUPPORTED_VERSION; //$NON-NLS-1$
    }

    private static List<Map<String, Object>> rows(Map<String, List<BundleCopy>> byName)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<BundleCopy> copies : byName.values())
        {
            for (BundleCopy copy : copies)
            {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", copy.name()); //$NON-NLS-1$
                row.put("version", copy.version()); //$NON-NLS-1$
                row.put("state", copy.state()); //$NON-NLS-1$
                row.put("chosen", Boolean.valueOf(copy.chosen())); //$NON-NLS-1$
                rows.add(row);
            }
        }
        return rows;
    }

    private static String foreign(String what, BundleCopy found, BundleCopy chosen)
    {
        String foundText = found == null ? "none" : found.name() + " " + found.version(); //$NON-NLS-1$ //$NON-NLS-2$
        return what + " comes from " + foundText + ", not the chosen " //$NON-NLS-1$ //$NON-NLS-2$
            + chosen.name() + " " + chosen.version(); //$NON-NLS-1$
    }

    private static Map<String, Object> toolsReport(List<String> available)
    {
        List<String> missing = new ArrayList<>();
        for (String allowed : ALLOWED_TOOLS)
        {
            if (!available.contains(allowed))
            {
                missing.add(allowed);
            }
        }
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("available", available); //$NON-NLS-1$
        tools.put("allowed", ALLOWED_TOOLS); //$NON-NLS-1$
        tools.put("missing", missing); //$NON-NLS-1$
        return tools;
    }

    private static Map<String, Object> link(String name, boolean ok, String detail)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("link", name); //$NON-NLS-1$
        row.put("ok", Boolean.valueOf(ok)); //$NON-NLS-1$
        row.put("detail", detail); //$NON-NLS-1$
        return row;
    }

    private static final class Survey
    {
        private boolean inPolicy;

        private String refusal;

        private List<Map<String, Object>> bundles = List.of();

        private Map<String, BundleCopy> chosen = Map.of();
    }
}
