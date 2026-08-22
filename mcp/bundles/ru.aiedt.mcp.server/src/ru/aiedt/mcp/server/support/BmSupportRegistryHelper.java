/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.wiring.ServiceAccess;
import com.e1c.g5.v8.dt.distribution.IDistributionSupportManager;
import com.e1c.g5.v8.dt.distribution.model.DistributionSupport;
import com.e1c.g5.v8.dt.distribution.model.ParentConfigurationInfo;
import com.e1c.g5.v8.dt.distribution.model.ParentConfigurationInfoItem;
import com.e1c.g5.v8.dt.distribution.model.ParentSupportMode;
import com.e1c.g5.v8.dt.distribution.model.UserSupportMode;
import com.e1c.g5.v8.dt.distribution.model.UserSupportModeRules;

import ru.aiedt.mcp.server.Activator;

/**
 * Reads the vendor support state of a configuration: which vendor configurations it descends from,
 * what each object is allowed to have done to it, and what the environment will do to each object
 * on the next update.
 * <p>
 * <b>Two registries answer different questions and both live here.</b> The per-object mode says what
 * was DECLARED - locked, editable, or taken off support. Whether an object was actually modified is
 * a different fact, answered by comparison against the vendor delivery. This class reports the first
 * one only, and says so, because reporting a declaration as if it were a measurement is how an
 * update quietly overwrites work somebody did.
 * </p>
 * <p>
 * <b>Only the version-stable part of the EDT API is used.</b>
 * {@code com.e1c.g5.v8.dt.distribution} carries a different major version in every recent EDT
 * release - 2.0.0 on 2025.2, 3.0.1 on 2026.1, 4.0.0 on 2026.2 - and several manager methods differ
 * in arity between them. Everything read here comes either from the EMF model, whose interfaces are
 * stable, or from the three manager methods whose signature is identical across the supported
 * releases: {@link IDistributionSupportManager#getDistributionSupport(IProject)},
 * {@link IDistributionSupportManager#getUserSupportMode(MdObject)} and
 * {@link IDistributionSupportManager#getDependentMdObjects(MdObject)}. Parent-side modes are taken
 * from {@link ParentConfigurationInfoItem#getParentMode()} rather than from the manager, precisely
 * because the manager method that returns both modes changed arity.
 * </p>
 */
public final class BmSupportRegistryHelper
{
    /** Bundle that owns the support service. Named by string: the import is optional. */
    private static final String DISTRIBUTION_BUNDLE = "com.e1c.g5.v8.dt.distribution"; //$NON-NLS-1$

    /** Activator of that bundle, which owns the injector. Its package is not exported. */
    private static final String DISTRIBUTION_PLUGIN =
        "com.e1c.g5.v8.dt.internal.distribution.DistributionPlugin"; //$NON-NLS-1$

    /** How many objects one page of the object listing may carry. */
    public static final int PAGE_LIMIT = 500;

    private BmSupportRegistryHelper()
    {
        // Static helper.
    }

    /** One vendor configuration this one descends from. */
    public static final class Parent
    {
        /** Identity of the vendor configuration inside the support file. */
        public String id;

        /** Who published it. */
        public String providerName;

        /** Its configuration name. */
        public String configName;

        /** Its release, as the vendor numbers releases. */
        public String configRelease;

        /** Identity of the exact delivered version. */
        public String configVersion;

        /** FILE when the vendor configuration is kept as a file, SELF when it is this one. */
        public String storeMode;

        /** How many objects this vendor configuration accounts for. */
        public int itemCount;

        /** How many objects sit in each user mode. Keys are the mode literals. */
        public final Map<String, Integer> byUserMode = new TreeMap<>();

        /** How many objects sit in each vendor mode. Keys are the mode literals. */
        public final Map<String, Integer> byParentMode = new TreeMap<>();

        /** What the environment will do to objects on the next update, by category. */
        public final Map<String, String> updateRules = new LinkedHashMap<>();
    }

    /** The support state of one project. */
    public static final class Registry
    {
        /** Why nothing could be said. Present only when the answer is a refusal. */
        public String cannotTell;

        /**
         * How the support service was reached.
         * <p>
         * Reported rather than kept private: the service is bound through Guice and published to
         * the OSGi registry by the environment, and if that ever stops happening the fallback route
         * takes over silently. A caller comparing two runs should be able to see that the route
         * changed, instead of guessing why the answers did.
         * </p>
         */
        public String serviceRoute;

        /** False when the project carries no support information at all. */
        public boolean onSupport;

        /** Whether the environment considers an update to be waiting. */
        public boolean updateAvailable;

        /** Whether the configuration file is a normal one or a distributive. */
        public String fileState;

        /** Version counter the environment keeps on the support model. */
        public int version;

        /** The vendor configurations this one descends from. */
        public final List<Parent> parents = new ArrayList<>();
    }

    /** The support state of one metadata object. */
    public static final class ObjectState
    {
        /** Why nothing could be said. Present only when the answer is a refusal. */
        public String cannotTell;

        /** How the support service was reached. */
        public String serviceRoute;

        /** The object as it was named in the request. */
        public String object;

        /**
         * What the environment reports for the object across every vendor configuration at once.
         * <p>
         * Aggregated by the environment, not by this code, and therefore ambiguous the moment a
         * configuration descends from more than one vendor. Read {@link #perParent} to see which
         * vendor a mode belongs to.
         * </p>
         */
        public String userMode;

        /**
         * The mode recorded against each vendor configuration separately.
         * <p>
         * Present because a configuration can sit on several supports at once - a typical
         * application on the vendor's support, which itself sits on a library's. One aggregate
         * answer would name no vendor, and a caller about to change a mode has to know whose.
         * </p>
         */
        public final List<ParentModes> perParent = new ArrayList<>();

        /** Whether the environment lets this object be edited right now. */
        public boolean canEdit;

        /** Whether the environment lets this object be deleted right now. */
        public boolean canDelete;

        /** Objects the environment would require to change mode alongside this one. */
        public final List<String> dependents = new ArrayList<>();
    }

    /**
     * Takes a snapshot of every support mode in a project.
     *
     * @param projectName the project.
     * @return the snapshot, or one carrying a refusal
     */
    public static SupportSnapshot snapshot(String projectName)
    {
        SupportSnapshot snapshot = new SupportSnapshot();
        snapshot.projectName = projectName;
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            snapshot.cannotTell = ProjectResolver.describeNotFound(projectName);
            return snapshot;
        }
        Service service = findService();
        if (service.manager == null)
        {
            snapshot.cannotTell = service.failure;
            return snapshot;
        }
        DistributionSupport support = service.manager.getDistributionSupport(project);
        if (support == null)
        {
            snapshot.cannotTell = projectName + " is not on support, so there are no modes to take"; //$NON-NLS-1$
            return snapshot;
        }
        Configuration configuration = configurationOf(project);
        Map<UUID, String> names =
            configuration == null ? Collections.emptyMap() : indexNames(configuration);
        for (ParentConfigurationInfo info : support.getParentConfigurationInfos())
        {
            SupportSnapshot.Parent parent = new SupportSnapshot.Parent(
                info.getId() == null ? info.getConfigName() : info.getId().toString(),
                info.getConfigName(), info.getConfigRelease());
            for (ParentConfigurationInfoItem item : info.getItems())
            {
                if (item.getUserId() == null)
                {
                    continue;
                }
                parent.modes.put(item.getUserId(),
                    item.getUserMode() == null ? null : item.getUserMode().getName());
                if (!names.containsKey(item.getUserId()))
                {
                    snapshot.unresolved++;
                }
            }
            snapshot.parents.add(parent);
        }
        return snapshot;
    }

    /** What a restore did, or would have done. */
    public static final class Restore
    {
        /** Why nothing could be done. Present only when the answer is a refusal. */
        public String cannotTell;

        /** How the project differs from the snapshot. */
        public SupportSnapshot.Drift drift;

        /** True when modes were written rather than only reported. */
        public boolean applied;

        /** How many objects were put back to the mode the snapshot recorded. */
        public int restored;

        /**
         * Objects whose mode the environment would not take, and why.
         * <p>
         * Named rather than counted. A restore that reports a number and not the objects leaves a
         * person unable to tell which of their work is still unprotected.
         * </p>
         */
        public final List<String> refused = new ArrayList<>();

        /** Objects the snapshot names that the configuration no longer has. */
        public int missing;

        /** Which form of the environment's write method was found, for the report. */
        public String writeRoute;

        /**
         * Where the modes as they stood BEFORE this restore were written down.
         * <p>
         * A restore is itself a write, and a restore from the wrong file is a way to lose the
         * modes just as thoroughly as the update it was undoing. So the state it is about to
         * replace is recorded first, and the path comes back with the answer: without it a restore
         * that turns out to be wrong has nowhere to go.
         * </p>
         */
        public String undoSnapshotFile;

        /** Why the state before the restore could not be recorded. Present only on failure. */
        public String undoSnapshotNote;
    }

    /**
     * Puts support modes back the way a snapshot recorded them.
     * <p>
     * <b>Writing is a separate decision from measuring.</b> Reporting the drift costs nothing and
     * is always done; changing the support model is done only when the caller asks for it, because
     * the only way back from a wrong restore is another snapshot.
     * </p>
     * <p>
     * Vendor configurations before and after are matched on {@link ParentConfigurationInfo#getId()}
     * and not on the version. An update is expected to change the version and keep the identity, so
     * matching on version would find no vendor configuration at all after exactly the update this
     * exists for.
     * </p>
     *
     * @param projectName the project.
     * @param before the snapshot to restore to.
     * @param apply <code>true</code> to write, <code>false</code> to report what would be written.
     * @return what was done, or a refusal
     */
    public static Restore restore(String projectName, SupportSnapshot before, boolean apply)
    {
        Restore restore = new Restore();
        if (before == null || before.isEmpty())
        {
            restore.cannotTell = "there is no snapshot to restore from"; //$NON-NLS-1$
            return restore;
        }
        if (before.projectName != null && !before.projectName.equals(projectName))
        {
            // Checked by name because the uuids cannot tell them apart. Two projects descended
            // from the same vendor share parent and object identities, so a snapshot of one
            // applies cleanly to the other and overwrites its modes with a stranger's - the
            // failure looks like a successful restore.
            restore.cannotTell = "this snapshot was taken from '" + before.projectName //$NON-NLS-1$
                + "' and would be applied to '" + projectName + "'. Two projects from the same " //$NON-NLS-1$
                + "vendor share object identities, so it would apply and be wrong. Take a " //$NON-NLS-1$
                + "snapshot of this project, or name the project the snapshot came from."; //$NON-NLS-1$
            return restore;
        }
        SupportSnapshot now = snapshot(projectName);
        if (now.cannotTell != null)
        {
            restore.cannotTell = now.cannotTell;
            return restore;
        }
        restore.drift = SupportSnapshot.compare(before, now);
        // Asked BEFORE isClean, because they are different questions. isClean means "no surviving
        // object changed its mode", and it is TRUE when the vendor configuration was replaced
        // wholesale - there is no surviving object to compare against, so nothing lands in changed.
        // Leaning on it here returned early from the one recovery a snapshot exists for, and the
        // answer beside it said the support model had been written.
        if (apply && restore.drift.vendorReplaced())
        {
            // Not a failure to report as zero. A mode belongs to a vendor configuration, and this
            // project is no longer on the one the snapshot was taken from - so there is nothing to
            // write the modes onto. Measured: a merge that took a whole configuration from another
            // delivery moved the project between vendors, and a restore that answered "0 restored"
            // read as "nothing needed doing".
            restore.cannotTell = "the project is no longer on support from "  //$NON-NLS-1$
                + String.join(", ", restore.drift.parentsGone) //$NON-NLS-1$
                + ", which is where this snapshot's modes belong. It is on support from " //$NON-NLS-1$
                + (restore.drift.parentsNew.isEmpty() ? "no vendor at all" //$NON-NLS-1$
                    : String.join(", ", restore.drift.parentsNew)) //$NON-NLS-1$
                + ", so there is nothing here to put them back onto. The support model was " //$NON-NLS-1$
                + "replaced rather than edited - putting it back means restoring the " //$NON-NLS-1$
                + "configuration, not the modes."; //$NON-NLS-1$
            return restore;
        }
        if (!apply || restore.drift.isClean())
        {
            return restore;
        }
        IProject project = ProjectResolver.resolve(projectName);
        Configuration configuration = configurationOf(project);
        if (configuration == null)
        {
            restore.cannotTell = "the configuration of " + projectName + " could not be read, so " //$NON-NLS-1$ //$NON-NLS-2$
                + "there is nothing to write modes onto"; //$NON-NLS-1$
            return restore;
        }
        // Recorded before anything is written, and a failure to record refuses the restore. A
        // partial restore with no note of what it replaced is a state with no way out, which is
        // the outcome this whole stage exists to prevent.
        keepUndo(projectName, now, before, restore);
        if (restore.undoSnapshotNote != null)
        {
            restore.cannotTell = "nothing was written: " + restore.undoSnapshotNote; //$NON-NLS-1$
            return restore;
        }
        write(project, before, now, restore);
        return restore;
    }

    /**
     * Writes down what the restore is about to replace, beside the snapshot it restores from.
     *
     * @param projectName the project.
     * @param now the modes as they stand.
     * @param from the snapshot being restored, whose path decides where this one goes.
     * @param restore where to record the path or the reason there is none.
     */
    private static void keepUndo(String projectName, SupportSnapshot now, SupportSnapshot from,
        Restore restore)
    {
        if (now == null || now.isEmpty())
        {
            return;
        }
        try
        {
            java.nio.file.Path beside = from != null && from.sourcePath != null
                ? java.nio.file.Paths.get(from.sourcePath)
                : java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), //$NON-NLS-1$
                    projectName + "-modes.tsv"); //$NON-NLS-1$
            String name = beside.getFileName().toString();
            // Numbered, because the second restore from one snapshot used to overwrite the only
            // record of what the first one replaced - and that record is the sole way back.
            java.nio.file.Path undo = beside.resolveSibling(name + ".before-restore.tsv"); //$NON-NLS-1$
            for (int attempt = 2; java.nio.file.Files.exists(undo) && attempt < 1000; attempt++)
            {
                undo = beside.resolveSibling(name + ".before-restore." + attempt + ".tsv"); //$NON-NLS-1$
            }
            now.write(undo);
            restore.undoSnapshotFile = undo.toString();
        }
        catch (java.io.IOException | RuntimeException cannotKeep)
        {
            restore.undoSnapshotNote = "the modes as they stand could not be written down, so " //$NON-NLS-1$
                + "this restore would have no way back: " + cannotKeep; //$NON-NLS-1$
        }
    }

    /**
     * Writes the recorded modes back, inside one transaction.
     *
     * @param project the project.
     * @param before the snapshot to restore to.
     * @param now what the project holds, already read.
     * @param restore where to record what happened.
     */
    private static void write(IProject project, SupportSnapshot before, SupportSnapshot now,
        Restore restore)
    {
        IBmModelManager models = Activator.getDefault().getBmModelManager();
        IBmModel model = models == null ? null : models.getModel(project);
        if (model == null)
        {
            restore.cannotTell = "the object model is not loaded for " + project.getName(); //$NON-NLS-1$
            return;
        }
        Method setter = findWriteMethod();
        if (setter == null)
        {
            // Reflection because the arity differs between releases: three arguments through
            // 2026.1 and four from 2026.2, which added the support model as a parameter. A direct
            // call would compile to whichever the build saw and fail with NoSuchMethodError on the
            // other. Measured against both bundles rather than assumed.
            restore.cannotTell = "this EDT has no setObjectSupportModeForUser in a shape this " //$NON-NLS-1$
                + "plugin can call - looked for three and four argument forms on " //$NON-NLS-1$
                + IDistributionSupportManager.class.getName();
            return;
        }
        restore.writeRoute = setter.getParameterCount() + "-argument setObjectSupportModeForUser"; //$NON-NLS-1$
        model.execute(new AbstractBmTask<Void>("BmSupportRegistryHelper.restore") //$NON-NLS-1$
        {
            @Override
            public Void execute(IBmTransaction tx, IProgressMonitor monitor)
            {
                applyInTransaction(project, before, now, restore, setter);
                return null;
            }
        });
        restore.applied = restore.restored > 0;
    }

    /**
     * Sets each recorded mode that the project no longer holds.
     *
     * @param project the project.
     * @param before the snapshot to restore to.
     * @param now what the project holds.
     * @param restore where to record what happened.
     * @param setter the write method this release offers.
     */
    private static void applyInTransaction(IProject project, SupportSnapshot before,
        SupportSnapshot now, Restore restore, Method setter)
    {
        Service service = findService();
        if (service.manager == null)
        {
            restore.cannotTell = service.failure;
            return;
        }
        DistributionSupport support = service.manager.getDistributionSupport(project);
        Configuration configuration = configurationOf(project);
        if (support == null || configuration == null)
        {
            restore.cannotTell = "the support model could not be read inside the transaction"; //$NON-NLS-1$
            return;
        }
        Map<UUID, MdObject> objects = indexObjects(configuration);
        for (ParentConfigurationInfo info : support.getParentConfigurationInfos())
        {
            String id = info.getId() == null ? info.getConfigName() : info.getId().toString();
            SupportSnapshot.Parent was = before.parentById(id);
            SupportSnapshot.Parent is = now.parentById(id);
            if (was == null || is == null)
            {
                continue;
            }
            for (Map.Entry<UUID, String> recorded : was.modes.entrySet())
            {
                String held = is.modes.get(recorded.getKey());
                if (!is.modes.containsKey(recorded.getKey()))
                {
                    restore.missing++;
                    continue;
                }
                if (recorded.getValue() == null || recorded.getValue().equals(held))
                {
                    continue;
                }
                MdObject object = objects.get(recorded.getKey());
                if (object == null)
                {
                    restore.missing++;
                    continue;
                }
                setOne(service, setter, info, object, recorded, support, restore);
            }
        }
    }

    /**
     * Sets one object's mode, recording a refusal rather than throwing.
     *
     * @param service the support manager.
     * @param setter the write method this release offers.
     * @param info the vendor configuration.
     * @param object the object.
     * @param recorded its identity and the mode to put back.
     * @param support the support model, which the four argument form wants.
     * @param restore where to record what happened.
     */
    private static void setOne(Service service, Method setter, ParentConfigurationInfo info,
        MdObject object, Map.Entry<UUID, String> recorded, DistributionSupport support,
        Restore restore)
    {
        UserSupportMode mode = UserSupportMode.getByName(recorded.getValue());
        if (mode == null)
        {
            mode = UserSupportMode.get(recorded.getValue());
        }
        if (mode == null)
        {
            restore.refused.add(name(object, recorded.getKey()) + ": the snapshot records mode \"" //$NON-NLS-1$
                + recorded.getValue() + "\", which this EDT does not have"); //$NON-NLS-1$
            return;
        }
        try
        {
            if (setter.getParameterCount() == 4)
            {
                setter.invoke(service.manager, info, object, mode, support);
            }
            else
            {
                setter.invoke(service.manager, info, object, mode);
            }
            restore.restored++;
        }
        catch (ReflectiveOperationException | RuntimeException refused)
        {
            // Named, not swallowed. A restore that counts only its successes reports a complete run
            // over a configuration where half the work is still unprotected.
            Throwable cause = refused instanceof java.lang.reflect.InvocationTargetException
                && refused.getCause() != null ? refused.getCause() : refused;
            restore.refused.add(name(object, recorded.getKey()) + ": " //$NON-NLS-1$
                + cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : " " + cause.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Finds the write method in whichever shape this release offers.
     *
     * @return the method, or <code>null</code> when neither shape is there
     */
    private static Method findWriteMethod()
    {
        Method four = null;
        Method three = null;
        for (Method candidate : IDistributionSupportManager.class.getMethods())
        {
            if (!"setObjectSupportModeForUser".equals(candidate.getName())) //$NON-NLS-1$
            {
                continue;
            }
            if (candidate.getParameterCount() == 4)
            {
                four = candidate;
            }
            else if (candidate.getParameterCount() == 3)
            {
                three = candidate;
            }
        }
        return four != null ? four : three;
    }

    /**
     * Names an object for a report, falling back to its identity.
     *
     * @param object the object.
     * @param identity its identity.
     * @return a name a person can search the configuration for
     */
    private static String name(MdObject object, UUID identity)
    {
        String named = object == null ? null : object.getName();
        return named == null || named.isEmpty() ? String.valueOf(identity) : named;
    }

    /** What one vendor configuration records about one object. */
    public static final class ParentModes
    {
        /** Identity of the vendor configuration the two modes below belong to. */
        public String parentId;

        /** Its configuration name, so the identity does not have to be looked up. */
        public String parentName;

        /** What the vendor allowed for the object. */
        public String parentMode;

        /** What was declared here against that vendor. */
        public String userMode;

        /** Whether the support file marks the entry as used. */
        public boolean used;
    }

    /** One entry of the object listing. */
    public static final class Entry
    {
        /**
         * Fully qualified name, when the identity could be resolved to an object.
         * <p>
         * Absent for a support record whose object is gone from this configuration, and for
         * subordinate entities the name index does not cover. Both cases are counted in
         * {@link Listing#unnamed}; neither is dropped, because a listing that quietly skipped
         * records would stop adding up to the support file's own count.
         * </p>
         */
        public String object;

        /** Identity as the support file records it. Always present, even when the name is not. */
        public String id;

        /** What was declared for the object. */
        public String userMode;

        /** What the vendor allowed. */
        public String parentMode;

        /** Whether the support file marks the entry as used. */
        public boolean used;
    }

    /** A page of the object listing. */
    public static final class Listing
    {
        /** Why nothing could be said. Present only when the answer is a refusal. */
        public String cannotTell;

        /** How the support service was reached. */
        public String serviceRoute;

        /** Identity of the vendor configuration the vendor modes below belong to. */
        public String parentId;

        /** Its configuration name. */
        public String parentName;

        /** Entries matching the filter, cut to one page. */
        public final List<Entry> entries = new ArrayList<>();

        /** How many entries matched the filter in total, before the page was cut. */
        public int matched;

        /** Where this page starts inside the matching set. */
        public int offset;

        /** True when entries beyond this page matched and were not returned. */
        public boolean more;

        /**
         * How many entries could not be given a name.
         * <p>
         * Counted rather than hidden. The support file records identities, not names, and an
         * identity that no longer resolves means the object is gone from this configuration - which
         * is itself worth seeing, and would be invisible if such entries were quietly dropped.
         * </p>
         */
        public int unnamed;
    }

    /** What the service lookup produced, and by which route. */
    private static final class Service
    {
        IDistributionSupportManager manager;

        String route;

        String failure;
    }

    /**
     * Finds the support service.
     * <p>
     * Two routes, tried in order. The environment binds the service through Guice and publishes it
     * to the OSGi registry, so the ordinary lookup should answer. When it does not - a binding that
     * was never published, or an EDT that arranges this differently - the injector on the owning
     * bundle's activator is asked directly. That activator sits in a package the bundle does not
     * export, which is why the second route goes through the bundle class loader and reflection
     * rather than through an import.
     * </p>
     * <p>
     * Neither failure is swallowed. When both routes come back empty the reason is carried out in
     * {@link Service#failure}, because a tool that answers "no support information" when the truth
     * is "the service could not be reached" sends its caller to look in the wrong place.
     * </p>
     *
     * @return what was found, never <code>null</code>
     */
    private static Service findService()
    {
        Service found = new Service();
        try
        {
            found.manager = ServiceAccess.get(IDistributionSupportManager.class);
            if (found.manager != null)
            {
                found.route = "OSGi service registry"; //$NON-NLS-1$
                return found;
            }
        }
        catch (RuntimeException | LinkageError registryRefused)
        {
            Activator.logDebug("support registry: service lookup failed: " + registryRefused); //$NON-NLS-1$
        }
        try
        {
            Bundle bundle = Platform.getBundle(DISTRIBUTION_BUNDLE);
            if (bundle == null)
            {
                found.failure = "this EDT install carries no support subsystem (" //$NON-NLS-1$
                    + DISTRIBUTION_BUNDLE + " is not installed)"; //$NON-NLS-1$
                return found;
            }
            Class<?> plugin = bundle.loadClass(DISTRIBUTION_PLUGIN);
            Object instance = plugin.getMethod("getDefault").invoke(null); //$NON-NLS-1$
            if (instance == null)
            {
                found.failure = "the support subsystem is installed but not started"; //$NON-NLS-1$
                return found;
            }
            Object injector = plugin.getMethod("getInjector").invoke(instance); //$NON-NLS-1$
            if (injector == null)
            {
                found.failure = "the support subsystem started without an injector"; //$NON-NLS-1$
                return found;
            }
            Method getInstance = injector.getClass().getMethod("getInstance", Class.class); //$NON-NLS-1$
            getInstance.setAccessible(true);
            Object manager = getInstance.invoke(injector, IDistributionSupportManager.class);
            if (manager instanceof IDistributionSupportManager)
            {
                found.manager = (IDistributionSupportManager)manager;
                found.route = "bundle injector"; //$NON-NLS-1$
                return found;
            }
            found.failure = "the support subsystem returned no manager"; //$NON-NLS-1$
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unreachable)
        {
            // Named, not swallowed: the shape of this bundle changes between EDT releases, and a
            // silent empty answer here would look exactly like a project that is not on support.
            found.failure = "the support subsystem could not be reached: " + unreachable; //$NON-NLS-1$
        }
        return found;
    }

    /**
     * Reads the support state of a project.
     *
     * @param projectName the project to read; must be open in the workspace.
     * @return what was found, or a refusal saying why nothing could be
     */
    public static Registry read(String projectName)
    {
        Registry registry = new Registry();
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            registry.cannotTell = ProjectResolver.describeNotFound(projectName);
            return registry;
        }
        Service service = findService();
        if (service.manager == null)
        {
            registry.cannotTell = service.failure;
            return registry;
        }
        registry.serviceRoute = service.route;
        DistributionSupport support = service.manager.getDistributionSupport(project);
        if (support == null)
        {
            // Not a failure: a configuration written from scratch is on nobody's support. Said
            // plainly so the caller can tell it apart from a service that would not answer.
            registry.onSupport = false;
            return registry;
        }
        registry.onSupport = true;
        registry.updateAvailable = support.isUpdateAvailable();
        registry.version = support.getVersion();
        registry.fileState = support.getFileState() == null ? null : support.getFileState().getName();
        for (ParentConfigurationInfo info : support.getParentConfigurationInfos())
        {
            registry.parents.add(describeParent(info));
        }
        return registry;
    }

    /**
     * Describes one vendor configuration and counts its objects by mode.
     *
     * @param info the vendor configuration as the support model holds it.
     * @return the description
     */
    private static Parent describeParent(ParentConfigurationInfo info)
    {
        Parent parent = new Parent();
        parent.id = info.getId() == null ? null : info.getId().toString();
        parent.providerName = info.getProviderName();
        parent.configName = info.getConfigName();
        parent.configRelease = info.getConfigRelease();
        parent.configVersion = info.getConfigVersion() == null ? null : info.getConfigVersion().toString();
        parent.storeMode = info.getStoreMode() == null ? null : info.getStoreMode().getName();
        List<ParentConfigurationInfoItem> items = info.getItems();
        parent.itemCount = items.size();
        for (ParentConfigurationInfoItem item : items)
        {
            countMode(parent.byUserMode, item.getUserMode() == null ? null : item.getUserMode().getName());
            countMode(parent.byParentMode,
                item.getParentMode() == null ? null : item.getParentMode().getName());
        }
        describeRules(info.getRules(), parent.updateRules);
        return parent;
    }

    /**
     * Adds one to the count of a mode.
     * <p>
     * A mode the model leaves unset is counted under its own key rather than skipped: the support
     * file omits an attribute equal to the model default, so unset is the majority case and
     * dropping it would make the counts fail to add up to the number of objects.
     * </p>
     *
     * @param census where to count.
     * @param mode the mode literal, or <code>null</code> when the model left it unset.
     */
    private static void countMode(Map<String, Integer> census, String mode)
    {
        String key = mode == null ? "(default)" : mode; //$NON-NLS-1$
        census.merge(key, Integer.valueOf(1), (was, one) -> Integer.valueOf(was.intValue() + 1));
    }

    /**
     * Reads the rules the environment applies to objects on the next update.
     * <p>
     * These are what actually decides the outcome of an update for an object nobody looked at, so
     * they belong in the answer next to the counts rather than behind another call.
     * </p>
     *
     * @param rules the rules as the support model holds them; may be <code>null</code>.
     * @param into where to write the description.
     */
    private static void describeRules(UserSupportModeRules rules, Map<String, String> into)
    {
        if (rules == null)
        {
            return;
        }
        into.put("newObjectsWithFreeMode", literal(rules.getNewObjectsWithFreeMode())); //$NON-NLS-1$
        into.put("newObjectsWithWarningMode", literal(rules.getNewObjectsWithWarningMode())); //$NON-NLS-1$
        into.put("existingIdenticalObjectsWithFreeMode", //$NON-NLS-1$
            rules.isExistingIdenticalObjectsWithFreeModeKeepMode() ? "keep current mode" //$NON-NLS-1$
                : literal(rules.getExistingIdenticalObjectsWithFreeMode()));
        into.put("existingIdenticalObjectsWithWarningMode", //$NON-NLS-1$
            rules.isExistingIdenticalObjectsWithWarningModeKeepMode() ? "keep current mode" //$NON-NLS-1$
                : literal(rules.getExistingIdenticalObjectsWithWarningMode()));
        into.put("existingDifferentObjectsWithFreeMode", //$NON-NLS-1$
            rules.isExistingDifferentObjectsWithFreeModeKeepMode() ? "keep current mode" //$NON-NLS-1$
                : literal(rules.getExistingDifferentObjectsWithFreeMode()));
        into.put("existingDifferentObjectsWithWarningMode", //$NON-NLS-1$
            rules.isExistingDifferentObjectsWithWarningModeKeepMode() ? "keep current mode" //$NON-NLS-1$
                : literal(rules.getExistingDifferentObjectsWithWarningMode()));
    }

    /**
     * Names a user mode.
     *
     * @param mode the mode; may be <code>null</code>.
     * @return its literal, or <code>null</code>
     */
    private static String literal(UserSupportMode mode)
    {
        return mode == null ? null : mode.getName();
    }

    /**
     * Reads the support state of one metadata object.
     *
     * @param projectName the project holding it.
     * @param fqn the object, named as the rest of this server names metadata objects.
     * @return what was found, or a refusal saying why nothing could be
     */
    public static ObjectState objectMode(String projectName, String fqn)
    {
        ObjectState state = new ObjectState();
        state.object = fqn;
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            state.cannotTell = ProjectResolver.describeNotFound(projectName);
            return state;
        }
        Service service = findService();
        if (service.manager == null)
        {
            state.cannotTell = service.failure;
            return state;
        }
        state.serviceRoute = service.route;
        Configuration configuration = configurationOf(project);
        if (configuration == null)
        {
            state.cannotTell = "no configuration is loaded for project " + projectName; //$NON-NLS-1$
            return state;
        }
        MdObject object = resolve(configuration, fqn);
        if (object == null)
        {
            state.cannotTell = fqn + " was not found in " + projectName; //$NON-NLS-1$
            return state;
        }
        DistributionSupport support = service.manager.getDistributionSupport(project);
        if (support == null)
        {
            state.cannotTell = projectName + " is not on support, so no object has a support mode"; //$NON-NLS-1$
            return state;
        }
        UserSupportMode mode = service.manager.getUserSupportMode(object);
        state.userMode = literal(mode);
        collectPerParent(support, object.getUuid(), state.perParent);
        state.canEdit = service.manager.canEdit(object);
        state.canDelete = service.manager.canDelete(object);
        for (MdObject dependent : service.manager.getDependentMdObjects(object))
        {
            if (dependent != null)
            {
                state.dependents.add(nameOf(dependent));
            }
        }
        return state;
    }

    /**
     * Collects what every vendor configuration records about one object.
     * <p>
     * Taken from the support model rather than from the manager on purpose, twice over. The manager
     * method that returns the vendor mode changed arity between EDT 2026.1 and 2026.2, and the
     * model did not; and the manager's single-object method aggregates across vendors, which
     * silently loses the answer to "whose support" as soon as there is more than one.
     * </p>
     *
     * @param support the support model of the project.
     * @param uuid identity of the object; may be <code>null</code>.
     * @param into where to add one entry per vendor configuration that mentions the object.
     */
    private static void collectPerParent(DistributionSupport support, UUID uuid, List<ParentModes> into)
    {
        if (uuid == null)
        {
            return;
        }
        for (ParentConfigurationInfo info : support.getParentConfigurationInfos())
        {
            for (ParentConfigurationInfoItem item : info.getItems())
            {
                if (!uuid.equals(item.getUserId()))
                {
                    continue;
                }
                ParentModes modes = new ParentModes();
                modes.parentId = info.getId() == null ? null : info.getId().toString();
                modes.parentName = info.getConfigName();
                ParentSupportMode vendor = item.getParentMode();
                modes.parentMode = vendor == null ? null : vendor.getName();
                modes.userMode = item.getUserMode() == null ? null : item.getUserMode().getName();
                modes.used = item.isUsed();
                into.add(modes);
                break;
            }
        }
    }

    /**
     * Lists objects by declared support mode.
     * <p>
     * The listing walks the metadata objects of the configuration rather than the entries of the
     * support file, and asks the environment for the mode of each. The support file records
     * identities without names, and an answer full of identities is of no use to whoever has to
     * decide what to do with the objects.
     * </p>
     *
     * @param projectName the project to list.
     * @param userModeFilter list only objects in this user mode; all modes when <code>null</code>.
     * @param parentId which vendor configuration the vendor modes should come from; may be
     *            <code>null</code> only when the project descends from exactly one.
     * @param offset how many matching objects to skip.
     * @param limit how many to return, capped at {@link #PAGE_LIMIT}.
     * @return one page, or a refusal saying why nothing could be listed
     */
    public static Listing listObjects(String projectName, String userModeFilter, String parentId,
        int offset, int limit)
    {
        Listing listing = new Listing();
        listing.offset = Math.max(0, offset);
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            listing.cannotTell = ProjectResolver.describeNotFound(projectName);
            return listing;
        }
        Service service = findService();
        if (service.manager == null)
        {
            listing.cannotTell = service.failure;
            return listing;
        }
        listing.serviceRoute = service.route;
        Configuration configuration = configurationOf(project);
        if (configuration == null)
        {
            listing.cannotTell = "no configuration is loaded for project " + projectName; //$NON-NLS-1$
            return listing;
        }
        DistributionSupport support = service.manager.getDistributionSupport(project);
        if (support == null)
        {
            listing.cannotTell = projectName + " is not on support, so no object has a support mode"; //$NON-NLS-1$
            return listing;
        }
        ParentConfigurationInfo scope = scopeTo(support, parentId);
        if (scope == null)
        {
            listing.cannotTell = describeScopeRefusal(support, parentId);
            return listing;
        }
        listing.parentId = scope.getId() == null ? null : scope.getId().toString();
        listing.parentName = scope.getConfigName();
        Map<UUID, String> names = indexNames(configuration);
        int page = limit <= 0 ? PAGE_LIMIT : Math.min(limit, PAGE_LIMIT);
        int seen = 0;
        for (ParentConfigurationInfoItem item : scope.getItems())
        {
            String userMode = item.getUserMode() == null ? null : item.getUserMode().getName();
            if (userModeFilter != null && !userModeFilter.equalsIgnoreCase(userMode))
            {
                continue;
            }
            listing.matched++;
            if (seen++ < listing.offset)
            {
                continue;
            }
            if (listing.entries.size() >= page)
            {
                listing.more = true;
                continue;
            }
            Entry entry = new Entry();
            entry.id = item.getUserId() == null ? null : item.getUserId().toString();
            entry.object = item.getUserId() == null ? null : names.get(item.getUserId());
            entry.userMode = userMode;
            entry.parentMode = item.getParentMode() == null ? null : item.getParentMode().getName();
            entry.used = item.isUsed();
            if (entry.object == null)
            {
                listing.unnamed++;
            }
            listing.entries.add(entry);
        }
        return listing;
    }

    /**
     * Builds a lookup from object identity to fully qualified name.
     * <p>
     * The support file records identities and no names, so this is what turns a listing into
     * something a person can act on. Subordinate entities - attributes, forms, templates - carry
     * their own support records and outnumber the objects several times over; they are not indexed
     * here, and the entries that answer to them come back named as identities and counted under
     * {@code unnamed}. That is deliberate: walking every object's contents to name them would load
     * the whole model for a listing, and an identity reported as an identity is honest, whereas an
     * entry silently dropped would make the listing disagree with the support file's own count.
     * </p>
     *
     * @param configuration the configuration to index.
     * @return identity to name, including the configuration root
     */
    private static Map<UUID, String> indexNames(Configuration configuration)
    {
        Map<UUID, String> names = new LinkedHashMap<>();
        if (configuration.getUuid() != null)
        {
            // The root is an ordinary entry of the registry and the one whose mode has to be open
            // before any other can be, so a listing that omitted it would hide the answer people
            // look for first.
            names.put(configuration.getUuid(), "Configuration"); //$NON-NLS-1$
        }
        for (String type : MetadataTypeCatalog.getAllEnglishSingularNames())
        {
            List<? extends MdObject> objects;
            try
            {
                objects = MetadataTypeCatalog.getObjects(configuration, type);
            }
            catch (RuntimeException noSuchCollection)
            {
                continue;
            }
            if (objects == null)
            {
                continue;
            }
            for (MdObject object : objects)
            {
                if (object != null && object.getUuid() != null)
                {
                    names.put(object.getUuid(), type + "." + object.getName()); //$NON-NLS-1$
                }
            }
        }
        return names;
    }

    /**
     * Indexes the objects themselves, for a caller that has to hand one to the environment.
     * <p>
     * Unlike {@link #indexNames(Configuration)} this one goes under each object as well, because
     * what it feeds is a write: an entry with no object to set a mode on cannot be restored at all.
     * See {@link #indexSubordinates}.
     * </p>
     *
     * @param configuration the configuration to index.
     * @return identity to object
     */
    private static Map<UUID, MdObject> indexObjects(Configuration configuration)
    {
        Map<UUID, MdObject> objects = new LinkedHashMap<>();
        if (configuration.getUuid() != null)
        {
            objects.put(configuration.getUuid(), configuration);
        }
        for (String type : MetadataTypeCatalog.getAllEnglishSingularNames())
        {
            List<? extends MdObject> found;
            try
            {
                found = MetadataTypeCatalog.getObjects(configuration, type);
            }
            catch (RuntimeException noSuchCollection)
            {
                continue;
            }
            if (found == null)
            {
                continue;
            }
            for (MdObject object : found)
            {
                if (object != null && object.getUuid() != null)
                {
                    objects.put(object.getUuid(), object);
                    indexSubordinates(object, objects);
                }
            }
        }
        return objects;
    }

    /**
     * Adds the attributes, tabular sections, forms, templates and commands under one object.
     * <p>
     * <b>The registry keeps modes for these, so a restore that cannot reach them restores half of
     * what it took.</b> A support mode belongs to any object the vendor delivered, subordinate ones
     * included, and the snapshot copies the registry entry for entry. Indexing only the top level
     * therefore produced a snapshot that could be written and not read back: every subordinate
     * entry came out of {@code restore_modes} as missing, which is exactly the state the snapshot
     * exists to recover from.
     * </p>
     * <p>
     * Walked rather than enumerated by kind. The model knows what an object contains; a list of
     * kinds written here would go stale the first time the platform adds one, and go stale
     * silently, because a missing kind looks the same as an object that has none.
     * </p>
     *
     * @param owner the object to walk under.
     * @param objects the index to add to.
     */
    private static void indexSubordinates(MdObject owner, Map<UUID, MdObject> objects)
    {
        try
        {
            java.util.Iterator<org.eclipse.emf.ecore.EObject> inside = owner.eAllContents();
            while (inside.hasNext())
            {
                org.eclipse.emf.ecore.EObject child = inside.next();
                if (child instanceof MdObject)
                {
                    MdObject subordinate = (MdObject)child;
                    if (subordinate.getUuid() != null)
                    {
                        objects.put(subordinate.getUuid(), subordinate);
                    }
                }
            }
        }
        catch (RuntimeException | LinkageError refused)
        {
            // One object that will not open its contents costs its subordinates, not the index.
            Activator.logDebug("support: could not walk under an object: " + refused); //$NON-NLS-1$
        }
    }

    /**
     * Chooses which vendor configuration the listing reports against.
     * <p>
     * One vendor and no argument is the ordinary case and needs no ceremony. Several vendors and no
     * argument is refused rather than answered from whichever came first: a mode belongs to a
     * vendor, and an answer that does not say which vendor is worse than no answer, because it
     * looks like one.
     * </p>
     *
     * @param support the support model of the project.
     * @param parentId the vendor asked for; may be <code>null</code>.
     * @return the vendor configuration to report against, or <code>null</code> when the request is
     *         ambiguous or names a vendor this project does not descend from
     */
    private static ParentConfigurationInfo scopeTo(DistributionSupport support, String parentId)
    {
        List<ParentConfigurationInfo> parents = support.getParentConfigurationInfos();
        if (parentId == null || parentId.trim().isEmpty())
        {
            return parents.size() == 1 ? parents.get(0) : null;
        }
        String wanted = parentId.trim();
        for (ParentConfigurationInfo info : parents)
        {
            if (info.getId() != null && wanted.equalsIgnoreCase(info.getId().toString())
                || wanted.equalsIgnoreCase(info.getConfigName()))
            {
                return info;
            }
        }
        return null;
    }

    /**
     * Says why a vendor configuration could not be chosen.
     *
     * @param support the support model of the project.
     * @param parentId the vendor asked for; may be <code>null</code>.
     * @return the reason, naming the vendors that are actually there
     */
    private static String describeScopeRefusal(DistributionSupport support, String parentId)
    {
        StringBuilder names = new StringBuilder();
        for (ParentConfigurationInfo info : support.getParentConfigurationInfos())
        {
            if (names.length() > 0)
            {
                names.append(", "); //$NON-NLS-1$
            }
            names.append(info.getConfigName()).append(" (") //$NON-NLS-1$
                .append(info.getId() == null ? "no id" : info.getId().toString()).append(')'); //$NON-NLS-1$
        }
        if (parentId == null || parentId.trim().isEmpty())
        {
            return "this configuration descends from more than one vendor configuration, so a " //$NON-NLS-1$
                + "support mode belongs to one of them rather than to the object outright. Name " //$NON-NLS-1$
                + "one in parentId: " + names; //$NON-NLS-1$
        }
        return parentId + " is not a vendor configuration this one descends from. Available: " //$NON-NLS-1$
            + names;
    }

    /**
     * Reads the configuration of a project.
     *
     * @param project the project.
     * @return its configuration, or <code>null</code> when none is loaded
     */
    private static Configuration configurationOf(IProject project)
    {
        try
        {
            com._1c.g5.v8.dt.core.platform.IConfigurationProvider provider =
                Activator.getDefault().getConfigurationProvider();
            return provider == null ? null : provider.getConfiguration(project);
        }
        catch (RuntimeException notLoaded)
        {
            Activator.logDebug("support registry: no configuration for " //$NON-NLS-1$
                + project.getName() + ": " + notLoaded); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Finds a metadata object by its fully qualified name.
     *
     * @param configuration the configuration to look in.
     * @param fqn the name.
     * @return the object, or <code>null</code> when the configuration has no such object
     */
    private static MdObject resolve(Configuration configuration, String fqn)
    {
        if (fqn == null || fqn.trim().isEmpty())
        {
            return null;
        }
        String wanted = fqn.trim();
        if ("Configuration".equalsIgnoreCase(wanted)) //$NON-NLS-1$
        {
            // The configuration root is an ordinary entry of the support registry and the one whose
            // mode has to be set before any other can be, so it must be addressable by name.
            return configuration;
        }
        return BmSubsystemHelper.resolveByFqn(configuration, wanted);
    }

    /**
     * Names a metadata object for the answer.
     *
     * @param object the object.
     * @return its name, or <code>null</code> when it will not name itself
     */
    private static String nameOf(MdObject object)
    {
        try
        {
            if (object instanceof Configuration)
            {
                return "Configuration"; //$NON-NLS-1$
            }
            // Qualified, not bare. A listing that answers "Products" leaves the caller to guess
            // whether that is a catalog or a document, and the name cannot be handed back to
            // object_mode, which is the one thing a caller will want to do with it.
            String type = object.eClass() == null ? null : object.eClass().getName();
            String name = object.getName();
            if (name == null)
            {
                return null;
            }
            return type == null || type.isEmpty() ? name : type + "." + name; //$NON-NLS-1$
        }
        catch (RuntimeException unnamed)
        {
            return null;
        }
    }
}
