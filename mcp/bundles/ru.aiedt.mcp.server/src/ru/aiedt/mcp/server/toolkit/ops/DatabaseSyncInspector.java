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
import java.util.Optional;
import java.util.UUID;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.BmCommonModuleGuards;
import ru.aiedt.mcp.server.support.DataLossPlan;
import ru.aiedt.mcp.server.support.DumpInfoProbe;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ProjectStateGuard;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * What stands between a project and its infobase, read without touching either.
 * <p>
 * The {@code infobase_admin} {@code inspect_database_sync} operation: the update state the
 * environment holds for the application, the infobase's own ask-confirmation-on-restructure
 * preference, and the data an update started now would delete - the same comparison
 * {@code update_database} refuses on, made here without updating anything, so the addresses are
 * readable before the call that would be stopped by them rather than after it.
 * </p>
 * <p>
 * Read-only by construction: what it reads is the update state, the preference and the infobase's
 * synchronization baseline; nothing is claimed, nothing is started, no preference is written, and
 * the answer says so. The object-by-object composition of an update is not among the facts this
 * can read - the application API reports the state, not the objects an update would carry - and the
 * answer names that honestly rather than leaving the caller to infer it.
 * </p>
 */
public final class DatabaseSyncInspector
{
    private DatabaseSyncInspector()
    {
    }

    /**
     * The facade's entry: resolves the project and application from the call and answers.
     *
     * @param params the call, naming the project and optionally the application
     * @return the JSON answer
     */
    public static String inspect(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required - inspection answers per project, " //$NON-NLS-1$
                + "and applicationId narrows it to one application when the project has several.") //$NON-NLS-1$
                .toJson();
        }
        String notReady = ProjectStateGuard.checkReadyOrError(projectName);
        if (notReady != null)
        {
            return ToolResult.error(notReady).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        Activator activator = Activator.getDefault();
        IApplicationManager appManager = activator == null ? null : activator.getApplicationManager();
        if (appManager == null)
        {
            return ToolResult.error("The IApplicationManager service is currently unavailable").toJson(); //$NON-NLS-1$
        }

        IProject infobaseProject = project;
        boolean viaParent = false;
        Optional<IApplication> found = applicationId == null || applicationId.isEmpty()
            ? defaultApplicationOf(appManager, project)
            : appManager.getApplication(project, applicationId);
        if (found.isEmpty())
        {
            // An extension has no infobase of its own: the base that would be updated is the one
            // belonging to the configuration it extends, and that is whose sync this inspects.
            IProject parent = BmCommonModuleGuards.parentProjectOf(project);
            if (parent != null && parent.exists() && parent.isOpen())
            {
                found = applicationId == null || applicationId.isEmpty()
                    ? defaultApplicationOf(appManager, parent)
                    : appManager.getApplication(parent, applicationId);
                if (found.isPresent())
                {
                    infobaseProject = parent;
                    viaParent = true;
                }
            }
        }
        if (found.isEmpty())
        {
            return ToolResult.error("No application found for this project"
                + (applicationId == null || applicationId.isEmpty() ? "" //$NON-NLS-1$ //$NON-NLS-2$
                    : ": " + applicationId) //$NON-NLS-1$
                + ". get_applications lists what this project can run against.").toJson(); //$NON-NLS-1$
        }
        IApplication application = found.get();
        String resolvedId = applicationId == null || applicationId.isEmpty() ? application.getId()
            : applicationId;

        // The comparison an update would refuse on, made here for the answer alone: same projects,
        // same reader, nothing claimed and nothing started. Its own reading of the model and the
        // baseline is what a plan reports on, so an inspection that could not compare says that
        // rather than answering with an empty list.
        List<IProject> modelProjects = new ArrayList<>();
        modelProjects.add(project);
        if (!project.equals(infobaseProject))
        {
            modelProjects.add(infobaseProject);
        }
        DataLossPlan.Plan pending = DataLossPlan.fromStore.read(infobaseProject, application,
            modelProjects);

        return inspect(appManager, DatabaseUpdater.platformPromptAccess(), pending, application,
            projectName, resolvedId, viaParent, infobaseProject.getName(),
            DatabaseUpdater.dumpInfoOf(application));
    }

    /**
     * The default application of a project, read the way the update reads it.
     *
     * @param appManager the application manager
     * @param project the project
     * @return the default application, or empty
     */
    private static Optional<IApplication> defaultApplicationOf(IApplicationManager appManager,
        IProject project)
    {
        try
        {
            return appManager.getDefaultApplication(project);
        }
        catch (RuntimeException cannotAsk)
        {
            return Optional.empty();
        }
    }

    /**
     * The inspection itself, over services handed in rather than tracked - which is what lets a
     * test prove the read-only part by reading what the stand-ins were asked, and hand in a
     * comparison without a project, a baseline and a model behind it.
     *
     * @param appManager the application manager
     * @param preferences the infobase preference access
     * @param pending the comparison an update started now would refuse on, or {@code null} when
     *            none could be made
     * @param application the application the call resolved to
     * @param projectName the project the call named
     * @param applicationId the application id, as the answer names it
     * @param viaParent whether the infobase belongs to the parent configuration
     * @param infobaseOwnerName the project that owns the infobase
     * @return the JSON answer
     */
    static String inspect(IApplicationManager appManager,
        DatabaseUpdater.PromptAccess preferences, DataLossPlan.Plan pending, IApplication application,
        String projectName, String applicationId, boolean viaParent, String infobaseOwnerName)
    {
        return inspect(appManager, preferences, pending, application, projectName, applicationId,
            viaParent, infobaseOwnerName, null);
    }

    /**
     * As {@link #inspect(IApplicationManager, DatabaseUpdater.PromptAccess, DataLossPlan.Plan, IApplication, String, String, boolean, String)},
     * with the stored-copy reading an update would decide against. The inspection names it and
     * stops nothing: a load recorded on the copy is the same sentence {@code update_database}
     * dryRun carries.
     *
     * @param dumpInfo the stored dump-info reading, or {@code null} when there is nothing to compare
     * @return the JSON answer
     */
    static String inspect(IApplicationManager appManager,
        DatabaseUpdater.PromptAccess preferences, DataLossPlan.Plan pending, IApplication application,
        String projectName, String applicationId, boolean viaParent, String infobaseOwnerName,
        DumpInfoProbe.Reading dumpInfo)
    {
        ApplicationUpdateState state;
        try
        {
            state = appManager.getUpdateState(application);
        }
        catch (RuntimeException | LinkageError cannotRead)
        {
            return ToolResult.error("Could not read the update state of '" + infobaseOwnerName //$NON-NLS-1$
                + "': " + cannotRead).toJson(); //$NON-NLS-1$
        }

        ToolResult answer = ToolResult.success()
            .put("operation", "inspect_database_sync") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("applicationId", applicationId) //$NON-NLS-1$
            .put("applicationName", application.getName()) //$NON-NLS-1$
            .put("updateState", state == null ? "UNKNOWN" : state.name()) //$NON-NLS-1$ //$NON-NLS-2$
            .put("wouldUpdate", Boolean.valueOf(state == ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED //$NON-NLS-1$
                || state == ApplicationUpdateState.FULL_UPDATE_REQUIRED
                || state == ApplicationUpdateState.BEING_UPDATED));

        if (viaParent)
        {
            answer.put("infobaseOwner", infobaseOwnerName); //$NON-NLS-1$
        }

        // The preference is read, never written here: it decides whether the PLATFORM opens its own
        // restructure window during an update of this base, and unknown is a state of the
        // environment rather than a guess. Whether an update deletes data is answered by the
        // comparison below, not by this.
        InfobaseReference infobase =
            application instanceof IInfobaseApplication ? ((IInfobaseApplication)application).getInfobase()
                : null;
        UUID infobaseId = infobase == null ? null : infobase.getUuid();
        Boolean asking = infobaseId == null ? null
            : preferences.promptConfirmationOnRestructure(infobaseId);
        answer.put("restructureConfirmationPrompt", //$NON-NLS-1$
            asking == null ? "unknown" : asking.booleanValue() ? "on" : "off"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // The data an update started now would delete, worked out the way the update works it out:
        // the same baseline, the same model, the same comparison, read here without claiming
        // anything. An update that has NOT been stopped is exactly when a caller can still decide
        // whether to accept the loss, which is why this is read before the call rather than after.
        Map<String, Object> protection = new LinkedHashMap<>();
        protection.put("nextUpdateProtectsData", Boolean.TRUE); //$NON-NLS-1$
        protection.put("acceptDataLossDefault", Boolean.FALSE); //$NON-NLS-1$
        boolean compared = pending != null && pending.compared;
        List<String> pendingDataLoss = compared ? pending.dataLoss : List.of();
        protection.put("dataLossCompared", Boolean.valueOf(compared)); //$NON-NLS-1$
        protection.put("pendingDataLoss", pendingDataLoss); //$NON-NLS-1$
        protection.put("pendingDataLossCount", Integer.valueOf(pendingDataLoss.size())); //$NON-NLS-1$
        if (pending != null && pending.file != null)
        {
            protection.put("baseline", pending.file); //$NON-NLS-1$
        }
        protection.put("dataLossCheck", pending == null //$NON-NLS-1$
            ? "not compared: no comparison was made for this project" //$NON-NLS-1$
            : pending.check());
        if (!pendingDataLoss.isEmpty())
        {
            protection.put("nextStep", "an update_database call on this project would be refused " //$NON-NLS-1$ //$NON-NLS-2$
                + "with these addresses; resend it with acceptDataLoss=true to carry the deletion " //$NON-NLS-1$
                + "through, or restore the missing entities in the configuration"); //$NON-NLS-1$
        }
        answer.put("dataLossProtection", protection); //$NON-NLS-1$

        // The same sentence an update's dry run carries: a load recorded on the stored copy, or
        // which base that copy belongs to. Named here and stopping nothing, which is the whole of
        // what an inspection was asked for.
        String infobaseChangeCheck = DatabaseUpdater.describeInfobaseChangeCheck(dumpInfo);
        if (infobaseChangeCheck != null)
        {
            answer.put("infobaseChangeCheck", infobaseChangeCheck); //$NON-NLS-1$
        }

        // An update this server is still tracking, whose receiver has not collected it yet - the
        // window in which the base is claimed by a run of ours rather than free.
        List<Map<String, Object>> tracked = trackedUpdatesOf(projectName);
        if (!tracked.isEmpty())
        {
            answer.put("updatesTracked", tracked); //$NON-NLS-1$
        }

        return answer
            .put("composition", "not available without running an update - the application API " //$NON-NLS-1$ //$NON-NLS-2$
                + "reports the state, not the objects an update would carry. pendingDataLoss names " //$NON-NLS-1$
                + "the entities that hold data, are in the infobase and are not in the model - the " //$NON-NLS-1$
                + "ones a restructure would drop.") //$NON-NLS-1$
            .put("nothingStarted", true) //$NON-NLS-1$
            .put("note", "This inspection reads the update state, the infobase's " //$NON-NLS-1$
                + "restructure-confirmation preference and its synchronization baseline. It " //$NON-NLS-1$
                + "starts no update, claims no infobase and writes no preference.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * The updates this server is still tracking for one project.
     *
     * @param projectName the project, as the tracked entries name it
     * @return one row per tracked update, empty when none
     */
    private static List<Map<String, Object>> trackedUpdatesOf(String projectName)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        PendingWorkRegistry registry = PendingWorkRegistry.UPDATE;
        try
        {
            registry.pruneExpired();
            for (PendingWorkRegistry.PendingEntry entry : registry.trackedOf(DatabaseUpdater.WORK_KIND))
            {
                if (projectName.equals(entry.subject))
                {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("runKey", entry.runKey); //$NON-NLS-1$
                    row.put("state", entry.isDone() ? "finished" : "running"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    row.put("elapsedMs", entry.elapsedMs()); //$NON-NLS-1$
                    rows.add(row);
                }
            }
        }
        catch (RuntimeException noRegistry)
        {
            // The registry is this server's own bookkeeping; an inspection that cannot read it
            // still answers the two questions the base was asked.
        }
        return rows;
    }
}
