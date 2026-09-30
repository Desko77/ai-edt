/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.IApplicationType;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmCommonModuleGuards;
import ru.aiedt.mcp.server.support.LaunchConfigAccess;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ProjectStateGuard;

/**
 * Lists the applications - the infobase launch targets - a project can run against.
 * <p>
 * Each application carries the id the update and debug tools address it by, plus its name, type and
 * how far its infobase is from the project. The per-application update-state read is allowed to fail
 * on its own without sinking the rest: a single unreachable infobase is reported in its own row, not
 * as the whole call failing.
 * </p>
 */
public class ApplicationsReader
    implements IMcpTool
{
    @Override
    public String getName()
    {
        return "get_applications"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `infobase_admin` `operation=get_applications`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Lists a project's applications - the infobases it can run against. Returns each one's " //$NON-NLS-1$
            + "ID, name, type, and update state. The ID is what update_database and debug_launch need. " //$NON-NLS-1$
            + "Each entry also names its kind (infobase / server / other), where an infobase lives, the " //$NON-NLS-1$
            + "operations that refuse a non-infobase application, the launch configurations bound to it, " //$NON-NLS-1$
            + "and the next step when it is not up to date."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName must be provided").toJson(); //$NON-NLS-1$
        }

        String stateError = ProjectStateGuard.checkReadyOrError(projectName);
        if (stateError != null)
        {
            return ToolResult.error(stateError).toJson();
        }

        return getApplications(projectName);
    }

    /**
     * Reads a project's applications and renders them.
     *
     * @param projectName the project name, already known to be non-empty
     * @return the JSON document
     */
    private static String getApplications(String projectName)
    {
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }

        Activator activator = Activator.getDefault();
        IApplicationManager applicationManager =
            activator == null ? null : activator.getApplicationManager();
        if (applicationManager == null)
        {
            return ToolResult.error("IApplicationManager service cannot be reached").toJson(); //$NON-NLS-1$
        }

        String name = project.getName();
        try
        {
            List<IApplication> applications = applicationManager.getApplications(project);
            // An extension has no infobase of its own; it shares the one belonging to the
            // configuration it extends. Answering a bare "no applications" here was true and
            // useless - the infobase its code goes into does exist, and the caller had no way
            // from here to find out which one it is.
            IProject infobaseOwner = project;
            boolean viaParent = false;
            if (applications == null || applications.isEmpty())
            {
                IProject parent = BmCommonModuleGuards.parentProjectOf(project);
                if (parent != null && parent.exists() && parent.isOpen())
                {
                    applications = applicationManager.getApplications(parent);
                    if (applications != null && !applications.isEmpty())
                    {
                        infobaseOwner = parent;
                        viaParent = true;
                    }
                }
            }
            if (applications == null || applications.isEmpty())
            {
                return ToolResult.success()
                    .put("project", name) //$NON-NLS-1$
                    .put("applications", new JsonArray()) //$NON-NLS-1$
                    .put("count", 0) //$NON-NLS-1$
                    .put("message", "The project has no applications") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }

            JsonArray array = new JsonArray();
            for (IApplication application : applications)
            {
                array.add(describe(application, applicationManager));
            }

            ToolResult result = ToolResult.success()
                .put("project", name) //$NON-NLS-1$
                .put("applications", array) //$NON-NLS-1$
                .put("count", array.size()); //$NON-NLS-1$
            if (viaParent)
            {
                result.put("infobaseProject", infobaseOwner.getName()); //$NON-NLS-1$
                result.put("note", name + " is an extension and has no infobase of its own. These " //$NON-NLS-1$ //$NON-NLS-2$
                    + "belong to " + infobaseOwner.getName() + ", the configuration it extends - " //$NON-NLS-1$ //$NON-NLS-2$
                    + "update_database on the extension goes to one of them."); //$NON-NLS-1$
            }

            final IProject defaultOwner = infobaseOwner;
            try
            {
                applicationManager.getDefaultApplication(defaultOwner)
                    .ifPresent(application -> result.put("defaultApplicationId", application.getId())); //$NON-NLS-1$
            }
            catch (ApplicationException e)
            {
                Activator.logError("Failed to resolve the default application for " //$NON-NLS-1$
                    + defaultOwner.getName(), e);
            }

            return result.toJson();
        }
        catch (ApplicationException e)
        {
            Activator.logError("Failed to list applications for " + name, e); //$NON-NLS-1$
            return ToolResult.error("Error: the application list could not be read: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /** The interface EDT gives an application of a standalone or WST server. */
    static final String SERVER_APPLICATION_INTERFACE = "com.e1c.g5.dt.applications.wst.IServerApplication"; //$NON-NLS-1$

    /**
     * The operations whose code accepts only an infobase application, as facade and operation.
     * <p>
     * Each one resolves the application as an {@code IInfobaseApplication} and refuses any other:
     * extension management reads the infobase the extension goes into, the credentials write the
     * infobase's user, and {@code sync_control} binds its baseline to the infobase's uuid.
     * </p>
     */
    static final List<String> INFOBASE_ONLY_OPERATIONS = List.of(
        "extension_workshop install_extension", //$NON-NLS-1$
        "extension_workshop uninstall_extension", //$NON-NLS-1$
        "extension_workshop list_extension", //$NON-NLS-1$
        "extension_workshop export_extension", //$NON-NLS-1$
        "infobase_admin set_infobase_credentials", //$NON-NLS-1$
        "infobase_admin sync_control"); //$NON-NLS-1$

    /** What {@code get_applications} says about one application, read from EDT into plain values. */
    static final class ApplicationFacts
    {
        String id;

        String name;

        String typeId;

        String typeName;

        /** {@code infobase}, {@code server} or {@code other}. */
        String kind;

        String updateState;

        String updateStateError;

        String requiredVersion;

        /** {@code file}, {@code server} or {@code other}; {@code null} when the application is not an infobase. */
        String location;

        String path;

        String server;

        String infobaseName;

        String infobaseTitle;

        /** Each entry is the configuration name and its client. */
        final List<String[]> launchConfigurations = new ArrayList<>();

        /** Set when the launch configurations could not be read. */
        String launchConfigurationsError;
    }

    /**
     * Describes one application.
     *
     * @param application the application
     * @param applicationManager the manager, for the update state
     * @return the application as a JSON object
     */
    private static JsonObject describe(IApplication application, IApplicationManager applicationManager)
    {
        ApplicationFacts facts = new ApplicationFacts();
        facts.id = application.getId();
        facts.name = application.getName();
        IApplicationType type = application.getType();
        if (type != null)
        {
            facts.typeId = type.getId();
            facts.typeName = type.getName();
        }
        try
        {
            ApplicationUpdateState state = applicationManager.getUpdateState(application);
            if (state != null)
            {
                facts.updateState = state.name();
            }
        }
        catch (ApplicationException e)
        {
            facts.updateState = "ERROR"; //$NON-NLS-1$
            facts.updateStateError = e.getMessage();
            Activator.logError("Failed to read the update state of application " + application.getId(), e); //$NON-NLS-1$
        }
        facts.requiredVersion = application.getRequiredVersion().orElse(null);
        if (application instanceof IInfobaseApplication)
        {
            facts.kind = "infobase"; //$NON-NLS-1$
            readInfobase(((IInfobaseApplication)application).getInfobase(), facts);
        }
        else
        {
            facts.kind = implementsInterfaceNamed(application.getClass(), SERVER_APPLICATION_INTERFACE)
                ? "server" : "other"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        readLaunchConfigurations(facts);
        return render(facts);
    }

    /**
     * Reads where an infobase lives.
     *
     * @param infobase the infobase reference; may be {@code null}
     * @param facts where the answer goes
     */
    private static void readInfobase(InfobaseReference infobase, ApplicationFacts facts)
    {
        if (infobase == null)
        {
            return;
        }
        facts.infobaseTitle = infobase.getName();
        IConnectionString connection = infobase.getConnectionString();
        if (connection instanceof FileConnectionString)
        {
            facts.location = "file"; //$NON-NLS-1$
            facts.path = ((FileConnectionString)connection).getFile();
        }
        else if (connection instanceof ServerConnectionString)
        {
            ServerConnectionString server = (ServerConnectionString)connection;
            facts.location = "server"; //$NON-NLS-1$
            facts.server = server.getServer();
            facts.infobaseName = server.getReference();
        }
        else
        {
            facts.location = "other"; //$NON-NLS-1$
        }
    }

    /**
     * Collects the EDT launch configurations whose application attribute names this application.
     *
     * @param facts the application, and where the answer goes
     */
    private static void readLaunchConfigurations(ApplicationFacts facts)
    {
        DebugPlugin debug = DebugPlugin.getDefault();
        ILaunchManager manager = debug == null ? null : debug.getLaunchManager();
        ILaunchConfigurationType type = manager == null ? null
            : manager.getLaunchConfigurationType(LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID);
        if (type == null || facts.id == null)
        {
            return;
        }
        try
        {
            for (ILaunchConfiguration config : manager.getLaunchConfigurations(type))
            {
                if (facts.id.equals(config.getAttribute(LaunchConfigAccess.ATTR_APPLICATION_ID, ""))) //$NON-NLS-1$
                {
                    facts.launchConfigurations.add(new String[] { config.getName(),
                        clientOf(config.getAttribute(LaunchConfigAccess.ATTR_CLIENT_AUTO_SELECT, false),
                            config.getAttribute(LaunchConfigAccess.ATTR_CLIENT_TYPE, "")) }); //$NON-NLS-1$
                }
            }
        }
        catch (CoreException e)
        {
            facts.launchConfigurationsError = e.getMessage();
            Activator.logError("Failed to read the launch configurations of application " + facts.id, e); //$NON-NLS-1$
        }
    }

    /**
     * Names the client a launch configuration starts.
     *
     * @param autoSelect whether the configuration lets EDT choose the client
     * @param clientType the client type attribute; may be empty
     * @return {@code auto}, {@code thin}, {@code thick}, {@code web}, or the attribute as it is
     */
    static String clientOf(boolean autoSelect, String clientType)
    {
        if (autoSelect)
        {
            return "auto"; //$NON-NLS-1$
        }
        if (LaunchConfigAccess.CLIENT_TYPE_THIN.equals(clientType))
        {
            return "thin"; //$NON-NLS-1$
        }
        if (LaunchConfigAccess.CLIENT_TYPE_THICK.equals(clientType))
        {
            return "thick"; //$NON-NLS-1$
        }
        if (LaunchConfigAccess.CLIENT_TYPE_WEB.equals(clientType))
        {
            return "web"; //$NON-NLS-1$
        }
        return clientType;
    }

    /**
     * Whether a class, one of its superclasses, or any interface they extend has this name.
     * <p>
     * By name, so this bundle needs no dependency on the bundle that declares the interface.
     * </p>
     *
     * @param type the class to look at; may be {@code null}
     * @param interfaceName the fully qualified interface name
     * @return {@code true} when the interface is found
     */
    static boolean implementsInterfaceNamed(Class<?> type, String interfaceName)
    {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
        {
            if (interfaceName.equals(current.getName()))
            {
                return true;
            }
            for (Class<?> implemented : current.getInterfaces())
            {
                if (implementsInterfaceNamed(implemented, interfaceName))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Renders what was read about one application.
     *
     * @param facts the application
     * @return the application as a JSON object
     */
    static JsonObject render(ApplicationFacts facts)
    {
        JsonObject object = new JsonObject();
        object.addProperty("id", facts.id); //$NON-NLS-1$
        object.addProperty("name", facts.name); //$NON-NLS-1$
        if (facts.typeId != null)
        {
            object.addProperty("type", facts.typeId); //$NON-NLS-1$
        }
        if (facts.typeName != null)
        {
            object.addProperty("typeName", facts.typeName); //$NON-NLS-1$
        }
        object.addProperty("applicationKind", facts.kind); //$NON-NLS-1$
        if (facts.updateState != null)
        {
            object.addProperty("updateState", facts.updateState); //$NON-NLS-1$
            if (facts.updateStateError != null)
            {
                object.addProperty("updateStateError", facts.updateStateError); //$NON-NLS-1$
            }
            else
            {
                object.addProperty("updateStateDescription", describeUpdateState(facts.updateState)); //$NON-NLS-1$
            }
            String next = updateNextStep(facts.updateState, facts.id, facts.kind);
            if (next != null)
            {
                object.addProperty("updateNextStep", next); //$NON-NLS-1$
            }
        }
        if (facts.requiredVersion != null)
        {
            object.addProperty("requiredVersion", facts.requiredVersion); //$NON-NLS-1$
        }
        if (facts.location != null)
        {
            JsonObject infobase = new JsonObject();
            infobase.addProperty("location", facts.location); //$NON-NLS-1$
            if (facts.infobaseTitle != null)
            {
                infobase.addProperty("title", facts.infobaseTitle); //$NON-NLS-1$
            }
            if (facts.path != null)
            {
                infobase.addProperty("path", facts.path); //$NON-NLS-1$
            }
            if (facts.server != null)
            {
                infobase.addProperty("server", facts.server); //$NON-NLS-1$
            }
            if (facts.infobaseName != null)
            {
                infobase.addProperty("infobaseName", facts.infobaseName); //$NON-NLS-1$
            }
            object.add("infobase", infobase); //$NON-NLS-1$
        }
        if (!"infobase".equals(facts.kind)) //$NON-NLS-1$
        {
            JsonArray refused = new JsonArray();
            INFOBASE_ONLY_OPERATIONS.forEach(refused::add);
            object.add("operationsNotAvailable", refused); //$NON-NLS-1$
        }
        JsonArray launches = new JsonArray();
        for (String[] launch : facts.launchConfigurations)
        {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", launch[0]); //$NON-NLS-1$
            entry.addProperty("client", launch[1]); //$NON-NLS-1$
            launches.add(entry);
        }
        object.add("launchConfigurations", launches); //$NON-NLS-1$
        if (facts.launchConfigurationsError != null)
        {
            object.addProperty("launchConfigurationsError", facts.launchConfigurationsError); //$NON-NLS-1$
        }
        return object;
    }

    /**
     * Names what to do about an update state other than up to date.
     *
     * @param state the update state name, {@code ERROR} when it could not be read
     * @param applicationId the application, named in the call
     * @param kind the application kind; {@code sync_control} is named only for an infobase
     * @return the next call, or {@code null} for an application that is up to date
     */
    static String updateNextStep(String state, String applicationId, String kind)
    {
        switch (state)
        {
        case "INCREMENTAL_UPDATE_REQUIRED": //$NON-NLS-1$
        case "FULL_UPDATE_REQUIRED": //$NON-NLS-1$
            return "infobase_admin operation=update_database applicationId=" + applicationId; //$NON-NLS-1$
        case "UNKNOWN": //$NON-NLS-1$
        case "ERROR": //$NON-NLS-1$
            return "infobase".equals(kind) //$NON-NLS-1$
                ? "infobase_admin operation=sync_control syncOperation=diagnose, then update_database " //$NON-NLS-1$
                    + "if it reports a difference" //$NON-NLS-1$
                : "infobase_admin operation=update_database applicationId=" + applicationId; //$NON-NLS-1$
        case "BEING_UPDATED": //$NON-NLS-1$
            return "wait for the running update, then call get_applications again"; //$NON-NLS-1$
        default:
            return null;
        }
    }

    /**
     * Puts a human sentence to an update state.
     *
     * @param state the update state name
     * @return the description
     */
    private static String describeUpdateState(String state)
    {
        switch (state)
        {
        case "UNKNOWN": //$NON-NLS-1$
            return "State is unknown"; //$NON-NLS-1$
        case "INCREMENTAL_UPDATE_REQUIRED": //$NON-NLS-1$
            return "Needs an incremental update"; //$NON-NLS-1$
        case "FULL_UPDATE_REQUIRED": //$NON-NLS-1$
            return "Needs a full update"; //$NON-NLS-1$
        case "UPDATED": //$NON-NLS-1$
            return "Already up to date"; //$NON-NLS-1$
        case "BEING_UPDATED": //$NON-NLS-1$
            return "Update is in progress"; //$NON-NLS-1$
        default:
            return state;
        }
    }
}
