/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.support.PendingEnvelope;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ToolCallScope;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.support.naparnik.BundleCopy;
import ru.aiedt.mcp.server.support.naparnik.NaparnikAccessException;
import ru.aiedt.mcp.server.support.naparnik.NaparnikHost;
import ru.aiedt.mcp.server.support.naparnik.NaparnikHost.Question;
import ru.aiedt.mcp.server.support.naparnik.NaparnikHost.RunningQuestion;
import ru.aiedt.mcp.server.support.naparnik.OsgiNaparnikHost;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Reports whether 1C:Naparnik is installed, and asks it one question.
 * <p>
 * {@code status} without {@code probe} only lists bundles. {@code probe=true} starts the Naparnik UI
 * bundle when it is merely resolved and walks each link to the facade. {@code ask} sends one
 * question. The question, and whatever Naparnik's own tools read, goes to the 1C:Naparnik service.
 * The bridge preference is off by default; {@code status} answers either way. With the bridge on,
 * the question allows only the read set unless {@code mcpNaparnikAllToolsEnabled} is on, in which
 * case Naparnik may change metadata, write files and execute code in EDT. A service knowledge-base
 * tool is in that read set when its name is {@code mcp__knowledge-hub__} followed by
 * {@code Search_}, {@code Fetch_}, {@code Diff_} or {@code Get_}. Other {@code mcp__} names are
 * not. Read-only presets disable the tool by name in both modes.
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
     * reports this list as {@code tools.allowed}. {@code ask} sends this list, intersected with
     * the names Naparnik publishes, unless the full-set preference is on.
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

    /**
     * Read-only tools of the 1C:Naparnik service knowledge base. A called name is in the read set
     * when it starts with one of these. They are not local EDT tools, so they are not sent in
     * {@code allowedTools}: the installation rejects a name it does not publish, and these run in
     * the service. The veto allows a matching name. Any other {@code mcp__} name stays outside
     * the read set. {@code status} and {@code ask} report this list as {@code allowedServiceTools}.
     */
    static final List<String> ALLOWED_SERVICE_TOOLS = List.of(
        "mcp__knowledge-hub__Search_", //$NON-NLS-1$
        "mcp__knowledge-hub__Fetch_", //$NON-NLS-1$
        "mcp__knowledge-hub__Diff_", //$NON-NLS-1$
        "mcp__knowledge-hub__Get_"); //$NON-NLS-1$

    /** What {@code DevAutopilot} writes for an unset skill before the 9-argument constructor. */
    static final String SKILL_NAME = "custom"; //$NON-NLS-1$

    /** What {@code DevAutopilot} writes for an unset chat flag. */
    static final Boolean CHAT = Boolean.TRUE;

    private static final int QUESTION_LIMIT = 20000;

    private static final int ROUNDS_DEFAULT = 10;

    private static final int ROUNDS_MIN = 1;

    private static final int ROUNDS_MAX = 30;

    private static final int TIMEOUT_DEFAULT = 300;

    private static final int TIMEOUT_MIN = 30;

    private static final int TIMEOUT_MAX = 1800;

    private static final int WAIT_DEFAULT = 30;

    private static final int WAIT_MIN = 1;

    private static final int WAIT_MAX = 120;

    /** How long to wait for the future after the token is cancelled. */
    private static final long GRACE_MS = 2000L;

    private static final String POLICY_READ = "read"; //$NON-NLS-1$

    private static final String POLICY_ALL = "all"; //$NON-NLS-1$

    private static final Object ADMISSION = new Object();

    private static final Map<String, LiveAsk> LIVE = new ConcurrentHashMap<>();

    static
    {
        // tasks/cancel reaches this domain only through the stopper. Without it the registry
        // drops the entry and the question keeps running.
        PendingWorkRegistry.NAPARNIK.stopsWith(NaparnikTool::stopTheQuestion);
    }

    private final NaparnikHost host;

    private final AskControls controls;

    /**
     * The installation in this runtime, and the bridge preference as the store holds it.
     */
    public NaparnikTool()
    {
        this(new OsgiNaparnikHost());
    }

    /**
     * @param host the installation to ask; tests pass a fake
     */
    public NaparnikTool(NaparnikHost host)
    {
        this(host, AskControls.bridgeOnly(null));
    }

    /**
     * @param host the installation to ask
     * @param bridgeEnabled the preference value to report; {@code null} reads the store
     */
    public NaparnikTool(NaparnikHost host, Boolean bridgeEnabled)
    {
        this(host, AskControls.bridgeOnly(bridgeEnabled));
    }

    /**
     * @param host the installation to ask
     * @param controls live answers {@code ask} re-reads, including from a tool-call notice
     */
    NaparnikTool(NaparnikHost host, AskControls controls)
    {
        this.host = host;
        this.controls = controls;
    }

    @Override
    public String getName()
    {
        return "naparnik"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Asks 1C:Naparnik 1.0.7 a question, and reports whether that version is installed. " //$NON-NLS-1$
            + "operation=status only lists bundles. probe=true starts the Naparnik UI bundle if it " //$NON-NLS-1$
            + "is not already running. operation=ask sends the question. The question, and whatever " //$NON-NLS-1$
            + "Naparnik's tools read, goes to the 1C:Naparnik service. The bridge " //$NON-NLS-1$
            + "(mcpNaparnikBridgeEnabled) is off by default. With it on, ask allows only read tools " //$NON-NLS-1$
            + "unless mcpNaparnikAllToolsEnabled is on, in which case Naparnik may change metadata, " //$NON-NLS-1$
            + "write files and execute code in EDT. Read-only presets disable this tool either way."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "status lists the installation. ask sends one question. help describes this tool.", //$NON-NLS-1$
                true)
            .booleanProperty("probe", //$NON-NLS-1$
                "With status, walk each link to the facade. This starts the Naparnik UI bundle " //$NON-NLS-1$
                    + "and creates its injector when the user has not opened Naparnik yet. Default false.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Open EDT project the question is about. Required for ask.") //$NON-NLS-1$
            .stringProperty("question", //$NON-NLS-1$
                "The question, up to 20000 characters. Required for ask.") //$NON-NLS-1$
            .stringProperty("conversationId", //$NON-NLS-1$
                "Continue this conversation. Omit to start one.") //$NON-NLS-1$
            .stringProperty("replyTo", //$NON-NLS-1$
                "With conversationId: the message to reply to, taken from a previous answer.") //$NON-NLS-1$
            .integerProperty("maxToolRounds", //$NON-NLS-1$
                "Tool rounds, from 1 to 30. Default 10. Sent as a positive number.") //$NON-NLS-1$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "Overall limit in seconds, from 30 to 1800. Default 300. Cancels the question.") //$NON-NLS-1$
            .integerProperty("waitSeconds", //$NON-NLS-1$
                "Seconds to wait before answering Pending, from 1 to 120. Default 30.") //$NON-NLS-1$
            .stringProperty("runKey", //$NON-NLS-1$
                "Collect or cancel a question that answered Pending.") //$NON-NLS-1$
            .booleanProperty("cancel", //$NON-NLS-1$
                "With runKey: cancel that question. Default false.") //$NON-NLS-1$
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
                "Missing operation. This tool answers status, ask and help.").toJson(); //$NON-NLS-1$
        }
        switch (operation)
        {
            case "help": //$NON-NLS-1$
                return buildHelp();
            case "status": //$NON-NLS-1$
                return status(params);
            case "ask": //$NON-NLS-1$
                return ask(params);
            default:
                return ToolResult.error("Unknown operation '" + operation //$NON-NLS-1$
                    + "'. This tool answers status, ask and help.").toJson(); //$NON-NLS-1$
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
            + "names. ask sends one question to 1C:Naparnik " + SUPPORTED_VERSION //$NON-NLS-1$
            + ". The question, and whatever Naparnik's tools read, goes to the 1C:Naparnik service. " //$NON-NLS-1$
            + "ask arguments: projectName, question (up to 20000 characters), conversationId, " //$NON-NLS-1$
            + "replyTo (only with conversationId), maxToolRounds (1..30, default 10), " //$NON-NLS-1$
            + "timeoutSeconds (30..1800, default 300), waitSeconds (1..120, default 30), runKey, " //$NON-NLS-1$
            + "cancel (only with runKey). With the bridge on and mcpNaparnikAllToolsEnabled off " //$NON-NLS-1$
            + "(the default), ask allows only the read tools. With mcpNaparnikAllToolsEnabled on, " //$NON-NLS-1$
            + "the question is sent with no tool filter, and Naparnik may change metadata, write " //$NON-NLS-1$
            + "files and execute code in EDT. One question runs at a time. A question that outlives " //$NON-NLS-1$
            + "waitSeconds answers Pending with a runKey; come back with that runKey, or with " //$NON-NLS-1$
            + "cancel=true and the runKey to stop it. The bridge setting does not change what " //$NON-NLS-1$
            + "status reports. Read-only presets disable naparnik in both modes."; //$NON-NLS-1$
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
            .put("bundles", survey.bundles) //$NON-NLS-1$
            .put("allowedServiceTools", ALLOWED_SERVICE_TOOLS); //$NON-NLS-1$
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
        if (controls.bridge != null)
        {
            return controls.bridge.getAsBoolean();
        }
        Activator plugin = Activator.getDefault();
        if (plugin == null)
        {
            return PrefKeys.DEFAULT_NAPARNIK_BRIDGE_ENABLED;
        }
        return plugin.getPreferenceStore().getBoolean(PrefKeys.PREF_NAPARNIK_BRIDGE_ENABLED);
    }

    private boolean allToolsEnabled()
    {
        if (controls.allTools != null)
        {
            return controls.allTools.getAsBoolean();
        }
        Activator plugin = Activator.getDefault();
        if (plugin == null)
        {
            return PrefKeys.DEFAULT_NAPARNIK_ALL_TOOLS_ENABLED;
        }
        return plugin.getPreferenceStore().getBoolean(PrefKeys.PREF_NAPARNIK_ALL_TOOLS_ENABLED);
    }

    private boolean naparnikCallable()
    {
        if (controls.callable != null)
        {
            return controls.callable.getAsBoolean();
        }
        return ToolGate.gateOrNull(getName()) == null;
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
        tools.put("allowedServiceTools", ALLOWED_SERVICE_TOOLS); //$NON-NLS-1$
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

    private String ask(Map<String, String> params)
    {
        boolean cancel = JsonUtils.extractBooleanArgument(params, "cancel", false); //$NON-NLS-1$
        String runKey = trimmed(JsonUtils.extractStringArgument(params, "runKey")); //$NON-NLS-1$
        if (cancel && runKey == null)
        {
            return ToolResult.error("cancel requires runKey").toJson(); //$NON-NLS-1$
        }
        Integer wait = limit(JsonUtils.extractStringArgument(params, "waitSeconds"), //$NON-NLS-1$
            JsonUtils.extractIntegerArgument(params, "waitSeconds"), WAIT_MIN, WAIT_MAX, //$NON-NLS-1$
            WAIT_DEFAULT);
        if (wait == null)
        {
            return outOfRange("waitSeconds", WAIT_MIN, WAIT_MAX, params); //$NON-NLS-1$
        }
        if (runKey != null)
        {
            return cancel ? cancelRun(runKey, wait.intValue()) : collect(runKey, wait.intValue());
        }
        String replyTo = trimmed(JsonUtils.extractStringArgument(params, "replyTo")); //$NON-NLS-1$
        String conversationId = trimmed(JsonUtils.extractStringArgument(params, "conversationId")); //$NON-NLS-1$
        if (replyTo != null && conversationId == null)
        {
            return ToolResult.error("replyTo requires conversationId").toJson(); //$NON-NLS-1$
        }
        if (!bridgeEnabled())
        {
            return ToolResult.error("The 1C:Naparnik bridge is off (mcpNaparnikBridgeEnabled). " //$NON-NLS-1$
                + "Turn it on in EDT Preferences > AI-EDT. status still answers.").toJson(); //$NON-NLS-1$
        }
        if (!naparnikCallable())
        {
            return ToolResult.error(ToolGate.disabledMessage(getName())).toJson();
        }
        Integer rounds = limit(JsonUtils.extractStringArgument(params, "maxToolRounds"), //$NON-NLS-1$
            JsonUtils.extractIntegerArgument(params, "maxToolRounds"), ROUNDS_MIN, ROUNDS_MAX, //$NON-NLS-1$
            ROUNDS_DEFAULT);
        if (rounds == null)
        {
            return outOfRange("maxToolRounds", ROUNDS_MIN, ROUNDS_MAX, params); //$NON-NLS-1$
        }
        Integer timeout = limit(JsonUtils.extractStringArgument(params, "timeoutSeconds"), //$NON-NLS-1$
            JsonUtils.extractIntegerArgument(params, "timeoutSeconds"), TIMEOUT_MIN, TIMEOUT_MAX, //$NON-NLS-1$
            TIMEOUT_DEFAULT);
        if (timeout == null)
        {
            return outOfRange("timeoutSeconds", TIMEOUT_MIN, TIMEOUT_MAX, params); //$NON-NLS-1$
        }
        String question = JsonUtils.extractStringArgument(params, "question"); //$NON-NLS-1$
        String trimmedQuestion = question == null ? "" : question.trim(); //$NON-NLS-1$
        if (trimmedQuestion.isEmpty())
        {
            return ToolResult.error("question is empty").toJson(); //$NON-NLS-1$
        }
        if (trimmedQuestion.length() > QUESTION_LIMIT)
        {
            return ToolResult.error("question is longer than " + QUESTION_LIMIT + " characters") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        String projectName = trimmed(JsonUtils.extractStringArgument(params, "projectName")); //$NON-NLS-1$
        if (projectName == null)
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        ProjectDoor projects = controls.projects == null ? WORKSPACE : controls.projects;
        Object project = projects.open(projectName);
        if (project == null)
        {
            return ToolResult.error(projects.refusal(projectName)).toJson();
        }
        Survey survey = survey();
        if (!survey.inPolicy)
        {
            return ToolResult.error(survey.refusal == null
                ? "1C:Naparnik is not installed" : survey.refusal).toJson(); //$NON-NLS-1$
        }
        Prepared prepared;
        try
        {
            prepared = open(survey);
        }
        catch (NaparnikAccessException failure)
        {
            return ToolResult.error(failure.link() + ": " + failure.getMessage()).toJson(); //$NON-NLS-1$
        }
        boolean allTools = allToolsEnabled();
        List<String> intersection = intersection(prepared.toolNames);
        if (!allTools && intersection.isEmpty())
        {
            return ToolResult.error("The read set intersected with Naparnik's published tools is " //$NON-NLS-1$
                + "empty, so the question was not sent. An empty allowedTools list is a different " //$NON-NLS-1$
                + "mode. Turn on mcpNaparnikAllToolsEnabled only if 1C:Naparnik may change " //$NON-NLS-1$
                + "metadata, write files and execute code in EDT.").toJson(); //$NON-NLS-1$
        }
        Set<String> allowed = allTools ? null : new LinkedHashSet<>(intersection);
        String policy = allTools ? POLICY_ALL : POLICY_READ;
        String version = survey.chosen.get(BUNDLE_AI).version();
        boolean forceNew = conversationId == null;
        String run = "naparnik-" + UUID.randomUUID(); //$NON-NLS-1$
        LiveAsk live = new LiveAsk(allTools ? null : intersection, policy, version);
        PendingWorkRegistry registry = PendingWorkRegistry.NAPARNIK;
        registry.pruneExpired();
        PendingWorkRegistry.PendingEntry entry;
        synchronized (ADMISSION)
        {
            List<String> going = registry.unfinishedKeys();
            if (!going.isEmpty())
            {
                return ToolResult.error("Another Naparnik question is already in progress, runKey=" //$NON-NLS-1$
                    + going.get(0) + ". Wait for it, or stop it with cancel=true and that runKey.") //$NON-NLS-1$
                    .toJson();
            }
            LIVE.put(run, live);
            entry = registry.getOrStart(run, pending -> runQuestion(pending, run, live, prepared.facade,
                prepared.source, project, trimmedQuestion, conversationId, replyTo, forceNew,
                rounds.intValue(), allowed, timeout.intValue()));
        }
        String done = entry.await(wait.longValue() * 1000L);
        if (done != null)
        {
            registry.remove(run, entry);
            LIVE.remove(run);
            return done;
        }
        return PendingEnvelope.mark(ToolResult.success()
            .put("operation", "ask") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", run) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("hint", "The question is still running. Come back with runKey=\"" + run //$NON-NLS-1$ //$NON-NLS-2$
                + "\", or stop it with cancel=true and that runKey.")).toJson(); //$NON-NLS-1$
    }

    private String collect(String runKey, int waitSeconds)
    {
        PendingWorkRegistry registry = PendingWorkRegistry.NAPARNIK;
        PendingWorkRegistry.PendingEntry entry = registry.get(runKey);
        if (entry == null)
        {
            return unknownRun(runKey);
        }
        String done = entry.await(waitSeconds * 1000L);
        if (done == null)
        {
            return PendingEnvelope.mark(ToolResult.success()
                .put("operation", "ask") //$NON-NLS-1$ //$NON-NLS-2$
                .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
                .put("runKey", runKey) //$NON-NLS-1$
                .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
                .put("hint", "Still running. Come back with the same runKey, or stop it with " //$NON-NLS-1$ //$NON-NLS-2$
                    + "cancel=true.")).toJson(); //$NON-NLS-1$
        }
        registry.remove(runKey, entry);
        LIVE.remove(runKey);
        return done;
    }

    private String cancelRun(String runKey, int waitSeconds)
    {
        PendingWorkRegistry registry = PendingWorkRegistry.NAPARNIK;
        LiveAsk live = LIVE.get(runKey);
        PendingWorkRegistry.PendingEntry entry = registry.get(runKey);
        if (live == null && entry == null)
        {
            return unknownRun(runKey);
        }
        if (live != null)
        {
            live.userCancel = true;
            RunningQuestion question = live.question;
            if (question != null)
            {
                question.cancel();
            }
        }
        if (entry == null)
        {
            return unknownRun(runKey);
        }
        String done = entry.await(Math.max(waitSeconds * 1000L, GRACE_MS));
        if (done == null)
        {
            done = entry.await(GRACE_MS);
        }
        if (done == null)
        {
            registry.remove(runKey, entry);
            LIVE.remove(runKey);
            return ToolResult.error("cancelled").put("runKey", runKey).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        registry.remove(runKey, entry);
        LIVE.remove(runKey);
        return done;
    }

    /**
     * Stops the question a registry cancel names.
     * <p>
     * {@code tasks/cancel} asks this and nothing else. The call moves Naparnik's own token. It does
     * not wait the future out: a tool call that already started can still be running, and saying it
     * had stopped would be the wrong one of the three answers.
     * </p>
     *
     * @param runKey the question's key
     * @return {@link PendingWorkRegistry.StopOutcome#NOTHING_TO_STOP} when no question is live,
     *         otherwise {@link PendingWorkRegistry.StopOutcome#STILL_RUNNING}
     */
    private static PendingWorkRegistry.StopOutcome stopTheQuestion(String runKey)
    {
        LiveAsk live = LIVE.get(runKey);
        if (live == null)
        {
            return PendingWorkRegistry.StopOutcome.NOTHING_TO_STOP;
        }
        live.userCancel = true;
        RunningQuestion question = live.question;
        if (question == null)
        {
            return PendingWorkRegistry.StopOutcome.STILL_RUNNING;
        }
        question.cancel();
        return PendingWorkRegistry.StopOutcome.STILL_RUNNING;
    }

    /**
     * Whether the client has withdrawn this question.
     * <p>
     * The tool's own {@code cancel} sets {@link LiveAsk#userCancel}. {@code notifications/cancelled}
     * only raises the flag the registry stored on the entry. Either one has to reach
     * {@link RunningQuestion#cancel()}.
     * </p>
     *
     * @param entry the registry entry, which holds the flag
     * @param live the question's own marks
     * @return whether the question should stop
     */
    private static boolean withdrawn(PendingWorkRegistry.PendingEntry entry, LiveAsk live)
    {
        if (live.userCancel)
        {
            return true;
        }
        ToolCallScope.Cancellation flag = entry == null ? null : entry.cancellation;
        return flag != null && flag.isCancelled();
    }

    /**
     * Watches the withdrawal flag while {@link RunningQuestion#await(long)} is blocked.
     * <p>
     * The wait is one call, and the flag arrives on another thread. Polling here is what makes a
     * withdrawal during that call cancel the question instead of waiting out the timeout.
     * </p>
     *
     * @param watching cleared when the wait is over
     * @param entry the registry entry
     * @param live the question's own marks; a withdrawal is written here so the answer says cancelled
     * @param question the question to cancel
     */
    private static void watchForWithdrawal(AtomicBoolean watching, PendingWorkRegistry.PendingEntry entry,
        LiveAsk live, RunningQuestion question)
    {
        while (watching.get())
        {
            if (withdrawn(entry, live))
            {
                live.userCancel = true;
                question.cancel();
                return;
            }
            try
            {
                Thread.sleep(20L);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String runQuestion(PendingWorkRegistry.PendingEntry entry, String runKey, LiveAsk live,
        Object facade, BundleCopy source, Object project, String text, String conversationId,
        String replyTo, boolean forceNew, int maxToolRounds, Set<String> allowed, int timeoutSeconds)
    {
        long started = System.currentTimeMillis();
        try
        {
            if (withdrawn(entry, live))
            {
                live.userCancel = true;
                return cancelled(live, System.currentTimeMillis() - started, List.of());
            }
            RunningQuestion question = host.ask(facade, source, new Question(project, text,
                conversationId, replyTo, forceNew, SKILL_NAME, CHAT, maxToolRounds, allowed,
                names -> notice(live, names, allowed)));
            live.question = question;
            AtomicBoolean watching = new AtomicBoolean(true);
            Thread watcher = new Thread(
                () -> watchForWithdrawal(watching, entry, live, question), "naparnik-cancel-watch"); //$NON-NLS-1$
            watcher.setDaemon(true);
            watcher.start();
            boolean finished;
            try
            {
                if (withdrawn(entry, live))
                {
                    live.userCancel = true;
                    question.cancel();
                }
                finished = question.await(timeoutSeconds * 1000L);
                if (!finished)
                {
                    live.timedOut = true;
                    question.cancel();
                    finished = question.await(GRACE_MS);
                }
                // The grace is how long a cancel is given to finish the future. When it does not,
                // this call stays here, so the registry entry stays unfinished and the next
                // question is refused, until the future has actually stopped.
                boolean stopAsked = false;
                // An interrupt of this thread (a shutdown of its executor) makes every wait below
                // return at once, so it is taken here and put back after the loop: the wait
                // keeps its pace instead of spinning.
                boolean interrupted = false;
                while (!finished)
                {
                    if (!stopAsked && withdrawn(entry, live))
                    {
                        live.userCancel = true;
                        question.cancel();
                        stopAsked = true;
                    }
                    interrupted |= Thread.interrupted();
                    finished = question.await(GRACE_MS);
                    if (!finished)
                    {
                        interrupted |= Thread.interrupted();
                        try
                        {
                            Thread.sleep(20L);
                        }
                        catch (InterruptedException e)
                        {
                            interrupted = true;
                        }
                    }
                }
                if (interrupted)
                {
                    Thread.currentThread().interrupt();
                }
            }
            finally
            {
                watching.set(false);
            }
            if (withdrawn(entry, live))
            {
                live.userCancel = true;
            }
            long elapsed = System.currentTimeMillis() - started;
            List<String> called = question.toolsCalled();
            if (live.userCancel)
            {
                return cancelled(live, elapsed, called);
            }
            if (live.timedOut)
            {
                return timedOut(live, elapsed, timeoutSeconds, called);
            }
            if (live.veto != null)
            {
                return vetoed(live, elapsed, called);
            }
            Throwable failure = unwrap(question.failure());
            if (failure != null)
            {
                return failed(live, elapsed, called, failure, maxToolRounds);
            }
            return answered(live, runKey, question, elapsed, called);
        }
        catch (NaparnikAccessException failure)
        {
            return ToolResult.error(failure.link() + ": " + failure.getMessage()) //$NON-NLS-1$
                .put("toolPolicy", live.policy) //$NON-NLS-1$
                .put("naparnikVersion", live.version) //$NON-NLS-1$
                .toJson();
        }
        catch (RuntimeException failure)
        {
            Throwable cause = unwrap(failure);
            return failed(live, System.currentTimeMillis() - started, List.of(), cause, maxToolRounds);
        }
        finally
        {
            LIVE.remove(runKey, live);
        }
    }

    /**
     * Records the names and decides whether later rounds must stop. The call that already started
     * is not stopped by this; the token only keeps the next round from beginning.
     */
    private String notice(LiveAsk live, List<String> names, Set<String> allowed)
    {
        if (live.userCancel)
        {
            live.veto = "cancelled"; //$NON-NLS-1$
            return live.veto;
        }
        if (POLICY_READ.equals(live.policy) && allowed != null)
        {
            for (String name : names)
            {
                if (!allowed.contains(name) && !serviceRead(name))
                {
                    live.veto = name;
                    return name;
                }
            }
        }
        if (!bridgeEnabled())
        {
            live.veto = "bridge"; //$NON-NLS-1$
            return live.veto;
        }
        if (!naparnikCallable())
        {
            live.veto = "preset"; //$NON-NLS-1$
            return live.veto;
        }
        return null;
    }

    /**
     * Whether {@code name} is a read of the service knowledge base. The verb is the part after
     * {@code mcp__knowledge-hub__}, and only {@code Search_}, {@code Fetch_}, {@code Diff_} and
     * {@code Get_} read. A different server, or a different verb on this server, is not.
     */
    private static boolean serviceRead(String name)
    {
        if (name == null)
        {
            return false;
        }
        for (String prefix : ALLOWED_SERVICE_TOOLS)
        {
            if (name.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }

    private String answered(LiveAsk live, String runKey, RunningQuestion question, long elapsed,
        List<String> called)
    {
        ToolResult result = ToolResult.success()
            .put("operation", "ask") //$NON-NLS-1$ //$NON-NLS-2$
            .put("answer", question.text()) //$NON-NLS-1$
            .put("conversationId", question.conversationId()) //$NON-NLS-1$
            .put("replyTo", question.replyTo()) //$NON-NLS-1$
            .put("assistantMessages", question.assistantMessages()) //$NON-NLS-1$
            .put("toolsCalled", called) //$NON-NLS-1$
            .put("elapsedMs", elapsed) //$NON-NLS-1$
            .put("naparnikVersion", live.version) //$NON-NLS-1$
            .put("toolPolicy", live.policy) //$NON-NLS-1$
            .put("runKey", runKey); //$NON-NLS-1$
        if (POLICY_READ.equals(live.policy))
        {
            result.put("allowedTools", live.allowedSent); //$NON-NLS-1$
        }
        result.put("allowedServiceTools", ALLOWED_SERVICE_TOOLS); //$NON-NLS-1$
        return result.toJson();
    }

    private static String cancelled(LiveAsk live, long elapsed, List<String> called)
    {
        return refusal(live, "cancelled", elapsed, called); //$NON-NLS-1$
    }

    private static String timedOut(LiveAsk live, long elapsed, int timeoutSeconds, List<String> called)
    {
        String names = called.isEmpty() ? "none" : String.join(", ", called); //$NON-NLS-1$ //$NON-NLS-2$
        return refusal(live, "timed out after " + timeoutSeconds + "s (" + elapsed + " ms), tools called: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + names, elapsed, called);
    }

    private static String vetoed(LiveAsk live, long elapsed, List<String> called)
    {
        String reason;
        if ("preset".equals(live.veto)) //$NON-NLS-1$
        {
            reason = ToolGate.disabledMessage("naparnik") //$NON-NLS-1$
                + " The question was cancelled."; //$NON-NLS-1$
        }
        else if ("bridge".equals(live.veto)) //$NON-NLS-1$
        {
            reason = "The 1C:Naparnik bridge was turned off (mcpNaparnikBridgeEnabled) while the " //$NON-NLS-1$
                + "question was running. The question was cancelled."; //$NON-NLS-1$
        }
        else
        {
            reason = "Naparnik called " + live.veto //$NON-NLS-1$
                + ", which is outside the read set. The question was cancelled; that call may " //$NON-NLS-1$
                + "already have run. Turn on mcpNaparnikAllToolsEnabled to lift the read set."; //$NON-NLS-1$
        }
        return refusal(live, reason, elapsed, called);
    }

    private static String failed(LiveAsk live, long elapsed, List<String> called, Throwable failure,
        int maxToolRounds)
    {
        String message = failure.getMessage() == null ? "" : failure.getMessage(); //$NON-NLS-1$
        String text;
        if (failure instanceof IllegalStateException && message.contains("Too many tool rounds")) //$NON-NLS-1$
        {
            text = "tool round limit exhausted, maxToolRounds=" + maxToolRounds //$NON-NLS-1$
                + " (" + failure.getClass().getSimpleName() + ": " + message + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        else
        {
            text = failure.getClass().getName() + ": " + message; //$NON-NLS-1$
        }
        return refusal(live, text, elapsed, called);
    }

    private static String refusal(LiveAsk live, String message, long elapsed, List<String> called)
    {
        ToolResult result = ToolResult.error(message)
            .put("operation", "ask") //$NON-NLS-1$ //$NON-NLS-2$
            .put("toolsCalled", called) //$NON-NLS-1$
            .put("elapsedMs", elapsed) //$NON-NLS-1$
            .put("naparnikVersion", live.version) //$NON-NLS-1$
            .put("toolPolicy", live.policy); //$NON-NLS-1$
        if (POLICY_READ.equals(live.policy) && live.allowedSent != null)
        {
            result.put("allowedTools", live.allowedSent); //$NON-NLS-1$
        }
        result.put("allowedServiceTools", ALLOWED_SERVICE_TOOLS); //$NON-NLS-1$
        return result.toJson();
    }

    private static String unknownRun(String runKey)
    {
        return ToolResult.error("runKey not found: " + runKey).toJson(); //$NON-NLS-1$
    }

    private Prepared open(Survey survey)
        throws NaparnikAccessException
    {
        BundleCopy ai = survey.chosen.get(BUNDLE_AI);
        BundleCopy ui = survey.chosen.get(BUNDLE_UI);
        BundleCopy common = survey.chosen.get(BUNDLE_UI_COMMON);
        Class<?> facadeType = host.loadClass(ai, FACADE_CLASS);
        Class<?> toolsType = host.loadClass(ai, TOOLS_CLASS);
        Class<?> activatorType = host.loadClass(common, ACTIVATOR_CLASS);
        NaparnikHost.InjectorDoor injector = host.openInjector(ui, activatorType);
        if (!ui.sameBundle(injector.activator()))
        {
            throw new NaparnikAccessException(NaparnikHost.LINK_INJECTOR,
                foreign("injector activator", injector.activator(), ui), null); //$NON-NLS-1$
        }
        NaparnikHost.FacadeDoor facade = host.openFacade(injector.injector(), facadeType);
        if (!ai.sameBundle(facade.owner()))
        {
            throw new NaparnikAccessException(NaparnikHost.LINK_FACADE,
                foreign("facade", facade.owner(), ai), null); //$NON-NLS-1$
        }
        List<String> names = host.toolNames(injector.injector(), toolsType);
        return new Prepared(facade.facade(), ai, names);
    }

    private static List<String> intersection(List<String> published)
    {
        List<String> names = new ArrayList<>();
        for (String allowed : ALLOWED_TOOLS)
        {
            if (published.contains(allowed))
            {
                names.add(allowed);
            }
        }
        return names;
    }

    private static Integer limit(String raw, Integer value, int min, int max, int fallback)
    {
        if (raw == null || raw.isBlank())
        {
            return Integer.valueOf(fallback);
        }
        if (value == null || value.intValue() < min || value.intValue() > max)
        {
            return null;
        }
        return value;
    }

    private static String outOfRange(String name, int min, int max, Map<String, String> params)
    {
        String raw = JsonUtils.extractStringArgument(params, name);
        return ToolResult.error(name + " must be from " + min + " to " + max + ", not '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + raw + "'").toJson(); //$NON-NLS-1$
    }

    private static String trimmed(String value)
    {
        if (value == null)
        {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static Throwable unwrap(Throwable failure)
    {
        Throwable current = failure;
        while (current != null && current.getCause() != null
            && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException
                || current instanceof java.lang.reflect.InvocationTargetException))
        {
            current = current.getCause();
        }
        return current;
    }

    private static final class Survey
    {
        private boolean inPolicy;

        private String refusal;

        private List<Map<String, Object>> bundles = List.of();

        private Map<String, BundleCopy> chosen = Map.of();
    }

    private static final class Prepared
    {
        private final Object facade;

        private final BundleCopy source;

        private final List<String> toolNames;

        private Prepared(Object facade, BundleCopy source, List<String> toolNames)
        {
            this.facade = facade;
            this.source = source;
            this.toolNames = toolNames;
        }
    }

    private static final class LiveAsk
    {
        private final List<String> allowedSent;

        private final String policy;

        private final String version;

        private volatile RunningQuestion question;

        private volatile String veto;

        private volatile boolean userCancel;

        private volatile boolean timedOut;

        private LiveAsk(List<String> allowedSent, String policy, String version)
        {
            this.allowedSent = allowedSent;
            this.policy = policy;
            this.version = version;
        }
    }

    /**
     * Where an open project comes from. Tests hand a stand-in; the workspace is the default.
     */
    interface ProjectDoor
    {
        /**
         * @param name the project name
         * @return the open project, or {@code null} when it is missing or closed
         */
        Object open(String name);

        /**
         * @param name the project name that {@link #open} refused
         * @return the refusal sentence
         */
        String refusal(String name);
    }

    private static final ProjectDoor WORKSPACE = new ProjectDoor()
    {
        @Override
        public Object open(String name)
        {
            return ProjectResolver.resolve(name);
        }

        @Override
        public String refusal(String name)
        {
            return ProjectResolver.describeNotFound(name);
        }
    };

    /**
     * Live answers {@code ask} reads again from the tool-call notice.
     * <p>
     * A null supplier reads the preference store, or the shipped default when there is no plugin.
     * </p>
     */
    static final class AskControls
    {
        private final BooleanSupplier bridge;

        private final BooleanSupplier allTools;

        private final BooleanSupplier callable;

        private final ProjectDoor projects;

        /**
         * @param bridge whether the bridge preference is on; {@code null} reads the store
         * @param allTools whether the full tool set is on; {@code null} reads the store
         * @param callable whether a preset still allows {@code naparnik}; {@code null} reads the
         *            catalog
         * @param projects where projects are resolved; {@code null} uses the workspace
         */
        AskControls(BooleanSupplier bridge, BooleanSupplier allTools, BooleanSupplier callable,
            ProjectDoor projects)
        {
            this.bridge = bridge;
            this.allTools = allTools;
            this.callable = callable;
            this.projects = projects;
        }

        private static AskControls bridgeOnly(Boolean bridgeEnabled)
        {
            return new AskControls(bridgeEnabled == null ? null : bridgeEnabled::booleanValue, null,
                null, null);
        }
    }
}
