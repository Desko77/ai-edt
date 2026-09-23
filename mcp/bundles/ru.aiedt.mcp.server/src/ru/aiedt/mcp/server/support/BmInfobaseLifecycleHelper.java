/*
 * Licensed under AGPL-3.0-or-later.
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */
package ru.aiedt.mcp.server.support;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.core.operations.IInfobaseCreationOperation;
import com._1c.g5.v8.dt.platform.services.core.operations.ISectionDeleteOperation;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com._1c.g5.wiring.ServiceAccess;

import ru.aiedt.mcp.server.Activator;

/**
 * Infobase lifecycle over EDT's applications framework: CREATE an infobase
 * ({@code IInfobaseCreationOperation}), ASSOCIATE it to a project (=the "launch
 * configuration" that makes it show up in get_applications, via
 * {@code IInfobaseAssociationManager}), and DELETE it
 * ({@code ISectionDeleteOperation}).
 * <p>
 * Physical FILE-infobase creation runs the 1C:Enterprise thick client
 * (CREATEINFOBASE batch child process), so it needs a resolvable platform
 * runtime - absent one, perform throws and is surfaced as a clean error, not a
 * hang. Association and deletion of a reference are pure in-process. Never
 * throws out; all failures land in the returned result holders.
 */
public final class BmInfobaseLifecycleHelper
{
    private BmInfobaseLifecycleHelper()
    {
    }

    /** Result of createInfobase. */
    public static final class CreateResult
    {
        public boolean ok;
        public String error;
        public String failureKind;   // managerUnavailable / alreadyExists / runtimeNotFound / createFailed
        public String infobaseName;
        public String uuid;
        public String path;
        public boolean associated;
        public String applicationId;
        public String associateWarning;
        public String launchApplicationIds;
    }

    /** Result of associate. */
    public static final class AssocResult
    {
        public boolean ok;
        public String error;
        public String failureKind;   // projectNotFound / managerUnavailable / infobaseNotFound / associateFailed
        public String infobaseName;
        public String applicationId;
        /** The association context the infobase was bound in: a branch ref, or default. */
        public String associationContext;
    }

    /** Result of deleteInfobase. */
    public static final class DeleteResult
    {
        public boolean ok;
        public String error;
        public String failureKind;   // managerUnavailable / infobaseNotFound / deleteFailed
        public boolean dissociated;
        public boolean contentDeleted;
        public String dissociateWarning;
        public String launchApplicationIds;
    }

    /**
     * Creates a FILE infobase in {@code filePath} named {@code name} (optionally
     * loading {@code cfPath} as a template), registering it in EDT's infobase
     * list. When {@code associateProjectName} is set, also associates it to that
     * project.
     */
    public static CreateResult createInfobase(String name, String filePath, String platform,
        String cfPath, String associateProjectName)
    {
        CreateResult r = new CreateResult();
        r.infobaseName = name;
        r.path = filePath;

        IInfobaseCreationOperation op = Activator.getDefault() != null
            ? Activator.getDefault().getInfobaseCreationOperation() : null;
        IInfobaseManager mgr = Activator.getDefault() != null
            ? Activator.getDefault().getInfobaseManager() : null;
        if (op == null)
        {
            r.error = "IInfobaseCreationOperation is not available on this EDT runtime."; //$NON-NLS-1$
            r.failureKind = ErrorTags.MANAGER_UNAVAILABLE.wire();
            return r;
        }
        if (mgr != null && mgr.findInfobaseByName(name).isPresent())
        {
            r.error = "An infobase named '" + name + "' already exists. Pick another name " //$NON-NLS-1$ //$NON-NLS-2$
                + "or delete it first (delete_infobase)."; //$NON-NLS-1$
            r.failureKind = ErrorTags.ALREADY_EXISTS.wire();
            return r;
        }
        // The snapshot, the write and the restore run under the one write lock: a second
        // infobase-list write at once would snapshot the ids this write already stripped.
        ILaunchManager manager = PRODUCT.launchManager();
        LaunchApplicationIds.Access access = LaunchConfigAccess.applicationIdAccess(manager);
        LaunchApplicationIds.underWriteLock(access, snapshot -> {
            LaunchIds launchIds = new LaunchIds(manager, access, snapshot);
            try
            {
                IInfobaseCreationOperation.Builder b = new IInfobaseCreationOperation.Builder()
                    .infobaseName(name)
                    .infobaseFile(Paths.get(filePath))
                    .platform(platform != null ? platform : ""); //$NON-NLS-1$
                if (cfPath != null && !cfPath.isEmpty())
                {
                    b.cfFile(Paths.get(cfPath));
                }
                op.perform(b.build(), new NullProgressMonitor());
            }
            catch (Throwable e)
            {
                r.launchApplicationIds = restoreLaunchApplicationIds(launchIds,
                    DeletedBaseConfigurations.none(), "create_infobase"); //$NON-NLS-1$
                classifyCreate(r, e);
                return r;
            }
            r.launchApplicationIds = restoreLaunchApplicationIds(launchIds,
                DeletedBaseConfigurations.none(), "create_infobase"); //$NON-NLS-1$
            r.ok = true;
            return r;
        });
        if (!r.ok)
        {
            return r;
        }
        if (mgr != null)
        {
            Optional<InfobaseReference> ref = mgr.findInfobaseByName(name);
            if (ref.isPresent() && ref.get().getUuid() != null)
            {
                r.uuid = ref.get().getUuid().toString();
            }
        }
        if (associateProjectName != null && !associateProjectName.isEmpty())
        {
            AssocResult ar = associate(associateProjectName, name);
            r.associated = ar.ok;
            r.applicationId = ar.applicationId;
            if (!ar.ok)
            {
                r.associateWarning = "infobase created but association to '" //$NON-NLS-1$
                    + associateProjectName + "' failed: " + ar.error; //$NON-NLS-1$
            }
        }
        return r;
    }

    /**
     * The association context of a project - the one EDT reads a project's applications from.
     *
     * <p>A project under version control is bound per branch: its applications live in the
     * context of the current branch ({@code refs/heads/<branch>}), and an association made in the
     * default context is not among them. EDT's own deploy wizard binds in the context this
     * provider names, so a binding made here goes to the same place.</p>
     *
     * @param project the project
     * @return the context; the default one when the provider is unavailable or fails
     */
    public static InfobaseAssociationContext associationContextOf(IProject project)
    {
        try
        {
            IInfobaseAssociationContextProvider provider = ServiceAccess.get(IInfobaseAssociationContextProvider.class);
            InfobaseAssociationContext context = provider == null ? null : provider.get(project);
            return context == null ? InfobaseAssociationContext.empty() : context;
        }
        catch (Exception e)
        {
            Activator.logWarning("The association context of " + project.getName() + " was not read: " //$NON-NLS-1$ //$NON-NLS-2$
                + msg(e));
            return InfobaseAssociationContext.empty();
        }
    }

    /**
     * The name of an association context for an answer.
     *
     * @param context the context
     * @return its name, or {@code default}
     */
    public static String describe(InfobaseAssociationContext context)
    {
        return context.getContext().orElse("default"); //$NON-NLS-1$
    }

    /**
     * Associates an existing infobase (by name) to a project - the "launch
     * configuration" step that makes it appear in get_applications. New empty
     * infobases are bound as not-synchronized (so get_applications reports an
     * update is required). The binding goes to the project's current association
     * context, the one its applications are read from.
     */
    public static AssocResult associate(String projectName, String infobaseName)
    {
        AssocResult r = new AssocResult();
        r.infobaseName = infobaseName;
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            r.error = ProjectResolver.describeNotFound(projectName);
            r.failureKind = ErrorTags.PROJECT_NOT_FOUND.wire();
            return r;
        }
        IInfobaseManager mgr = Activator.getDefault() != null
            ? Activator.getDefault().getInfobaseManager() : null;
        IInfobaseAssociationManager am = Activator.getDefault() != null
            ? Activator.getDefault().getInfobaseAssociationManager() : null;
        if (mgr == null || am == null)
        {
            r.error = "Infobase managers are not available on this EDT runtime."; //$NON-NLS-1$
            r.failureKind = ErrorTags.MANAGER_UNAVAILABLE.wire();
            return r;
        }
        Optional<InfobaseReference> ref = mgr.findInfobaseByName(infobaseName);
        if (!ref.isPresent())
        {
            r.error = "No infobase named '" + infobaseName + "' in EDT's infobase list. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Create it first (create_infobase)."; //$NON-NLS-1$
            r.failureKind = ErrorTags.INFOBASE_NOT_FOUND.wire();
            return r;
        }
        InfobaseAssociationContext context = associationContextOf(project);
        r.associationContext = describe(context);
        try
        {
            am.associate(project, ref.get(), InfobaseAssociationSettings.notSynchronized(context));
        }
        catch (Throwable e)
        {
            r.error = "Failed to associate the infobase to the project: " + msg(e); //$NON-NLS-1$
            r.failureKind = ErrorTags.ASSOCIATE_FAILED.wire();
            return r;
        }
        r.ok = true;
        r.applicationId = resolveApplicationId(project, infobaseName);
        return r;
    }

    /**
     * Deletes an infobase from EDT's list (and its {@code .1CD} when
     * {@code deleteContent}). When {@code projectName} is set, dissociates it
     * from that project first.
     */
    public static DeleteResult deleteInfobase(String name, boolean deleteContent,
        String projectName)
    {
        DeleteResult r = new DeleteResult();
        IInfobaseManager mgr = Activator.getDefault() != null
            ? Activator.getDefault().getInfobaseManager() : null;
        ISectionDeleteOperation delOp = Activator.getDefault() != null
            ? Activator.getDefault().getSectionDeleteOperation() : null;
        if (mgr == null || delOp == null)
        {
            r.error = "Infobase managers are not available on this EDT runtime."; //$NON-NLS-1$
            r.failureKind = ErrorTags.MANAGER_UNAVAILABLE.wire();
            return r;
        }
        Optional<InfobaseReference> ref = mgr.findInfobaseByName(name);
        if (!ref.isPresent())
        {
            r.error = "No infobase named '" + name + "' in EDT's infobase list."; //$NON-NLS-1$ //$NON-NLS-2$
            r.failureKind = ErrorTags.INFOBASE_NOT_FOUND.wire();
            return r;
        }
        // The snapshot, the search, the dissociation, the delete and the restore run under the
        // one write lock: a second infobase-list write at once would snapshot the ids this
        // write already stripped.
        ILaunchManager manager = PRODUCT.launchManager();
        LaunchApplicationIds.Access access = LaunchConfigAccess.applicationIdAccess(manager);
        LaunchApplicationIds.underWriteLock(access, snapshot -> {
            LaunchIds launchIds = new LaunchIds(manager, access, snapshot);
            DeletedBaseConfigurations deletedConfigurations =
                deletedBaseConfigurations(launchIds, PRODUCT, ref.get());
            if (projectName != null && !projectName.isEmpty())
            {
                IProject project = ProjectResolver.resolve(projectName);
                IInfobaseAssociationManager am = Activator.getDefault() != null
                    ? Activator.getDefault().getInfobaseAssociationManager() : null;
                if (project != null && am != null)
                {
                    // Every context the project has, the default one included: a binding made by an
                    // earlier build went to the default context whatever the branch, and one left
                    // behind keeps an application on a deleted infobase.
                    java.util.LinkedHashSet<InfobaseAssociationContext> contexts = new java.util.LinkedHashSet<>();
                    contexts.add(associationContextOf(project));
                    contexts.add(InfobaseAssociationContext.empty());
                    try
                    {
                        contexts.addAll(am.getAssociationContexts(project));
                    }
                    catch (Throwable e)
                    {
                        Activator.logWarning("delete_infobase: the association contexts of '" + projectName //$NON-NLS-1$
                            + "' were not listed: " + msg(e)); //$NON-NLS-1$
                    }
                    Throwable failure = null;
                    for (InfobaseAssociationContext context : contexts)
                    {
                        try
                        {
                            Optional<?> association = am.getAssociation(project, context);
                            if (association.isEmpty())
                            {
                                continue;
                            }
                            am.dissociate(project, ref.get(), context);
                            r.dissociated = true;
                        }
                        catch (Throwable e)
                        {
                            // Non-fatal: the infobase may simply not be bound in this context.
                            failure = e;
                        }
                    }
                    if (!r.dissociated && failure != null)
                    {
                        // Surface a warning (deletion still proceeds) so a genuine dissociate failure
                        // that leaves a dangling launch config is visible to the caller.
                        r.dissociateWarning = "could not dissociate from '" + projectName //$NON-NLS-1$
                            + "': " + msg(failure) + " (deletion proceeded; the project's launch " //$NON-NLS-1$ //$NON-NLS-2$
                            + "config may still reference the removed infobase)"; //$NON-NLS-1$
                        Activator.logWarning("delete_infobase " + r.dissociateWarning); //$NON-NLS-1$
                    }
                }
            }
            try
            {
                ISectionDeleteOperation.Descriptor d = new ISectionDeleteOperation.Builder()
                    .infobaseNames(List.of(name))
                    .deleteContent(deleteContent)
                    .build();
                delOp.perform(d, new NullProgressMonitor());
                r.contentDeleted = deleteContent;
            }
            catch (Throwable e)
            {
                r.launchApplicationIds = restoreLaunchApplicationIds(launchIds,
                    DeletedBaseConfigurations.none(), "delete_infobase"); //$NON-NLS-1$
                r.error = "Failed to delete the infobase: " + msg(e); //$NON-NLS-1$
                r.failureKind = ErrorTags.DELETE_FAILED.wire();
                return r;
            }
            r.launchApplicationIds = restoreLaunchApplicationIds(launchIds, deletedConfigurations,
                "delete_infobase"); //$NON-NLS-1$
            r.ok = true;
            return r;
        });
        return r;
    }

    /** The launch-manager state captured immediately before an infobase-list write. */
    static final class LaunchIds
    {
        final ILaunchManager manager;
        final LaunchApplicationIds.Access access;
        final LaunchApplicationIds.SnapshotResult snapshot;

        LaunchIds(ILaunchManager manager, LaunchApplicationIds.Access access,
            LaunchApplicationIds.SnapshotResult snapshot)
        {
            this.manager = manager;
            this.access = access;
            this.snapshot = snapshot;
        }
    }

    /**
     * The launch configurations whose application belongs to the infobase that is going away, and
     * what the search could not establish.
     * <p>
     * A configuration the search could not read or place is not restored: it may belong to the
     * deleted base, and putting its id back could hand the deleted base's binding back. Such a
     * configuration is excluded as unverified, and the answer names it once - in
     * {@link #notIdentified}, not again in the restore's report.
     * </p>
     */
    static final class DeletedBaseConfigurations
    {
        /**
         * Mementos of the configurations of the deleted base itself: their removed application id
         * must stay removed, and the restore's report names them as excluded.
         */
        final Set<String> mementos = new LinkedHashSet<>();

        /**
         * Mementos excluded without being identified: the search could not read the configuration
         * or place its binding, so the id stays removed. The restore leaves them alone and does
         * not name them - {@link #notIdentified} carries the answer's only naming of them.
         */
        final Set<String> unverified = new LinkedHashSet<>();

        /**
         * One line per configuration the search could not read or place, with the reason and the
         * consequence; the answer's only naming of them.
         */
        final List<String> notIdentified = new ArrayList<>();

        /**
         * Why no configuration could be identified at all, or <code>null</code> when the search
         * ran. Nothing is put back when this is set: without the deleted base's configurations
         * the guard cannot tell which bindings must stay removed.
         */
        String searchFailed;

        /** @return a search that excludes nothing, for the writes that delete no application */
        static DeletedBaseConfigurations none()
        {
            return new DeletedBaseConfigurations();
        }

        /** @return what the answer says about the search itself, or <code>null</code> when clean */
        String describeSearch()
        {
            if (searchFailed != null)
            {
                return searchFailed;
            }
            if (notIdentified.isEmpty())
            {
                return null;
            }
            return String.join("; ", notIdentified); //$NON-NLS-1$
        }
    }

    /**
     * Where the guard reads the launch configurations, the applications and the projects from.
     * The product resolves them through {@link Activator}; a test substitutes its own.
     */
    interface LaunchEnvironment
    {
        /** @return Eclipse's launch manager, or <code>null</code> when the debug plugin is down */
        ILaunchManager launchManager();

        /** @return EDT's application manager, or <code>null</code> when it is unavailable */
        IApplicationManager applicationManager();

        /** @param name a project name; @return the project, or <code>null</code> when unknown */
        IProject resolveProject(String name);
    }

    /** What the guard reads in the product. */
    private static final LaunchEnvironment PRODUCT = new LaunchEnvironment()
    {
        @Override
        public ILaunchManager launchManager()
        {
            return LaunchConfigAccess.getLaunchManager();
        }

        @Override
        public IApplicationManager applicationManager()
        {
            Activator activator = Activator.getDefault();
            return activator == null ? null : activator.getApplicationManager();
        }

        @Override
        public IProject resolveProject(String name)
        {
            return ProjectResolver.resolve(name);
        }
    };

    static LaunchIds snapshotLaunchApplicationIds(LaunchEnvironment environment)
    {
        ILaunchManager manager = environment.launchManager();
        LaunchApplicationIds.Access access = LaunchConfigAccess.applicationIdAccess(manager);
        LaunchApplicationIds.SnapshotResult snapshot =
            access == null ? null : LaunchApplicationIds.snapshot(access);
        return new LaunchIds(manager, access, snapshot);
    }

    /**
     * Puts the launch configurations' application ids back after the infobase-list write and
     * composes what the operation answer says about it: what the snapshot could not protect, what
     * the search could not establish, and what the restore did.
     *
     * @param launchIds what the guard captured before the write
     * @param deleted what the search established about the deleted base's configurations
     * @param operation the tool name, for the log
     * @return the sentence for the answer, or <code>null</code> when there is nothing to say
     */
    static String restoreLaunchApplicationIds(LaunchIds launchIds,
        DeletedBaseConfigurations deleted, String operation)
    {
        String search = deleted.describeSearch();
        String snapshot = launchIds == null || launchIds.snapshot == null
            || launchIds.snapshot.isQuiet() ? null : launchIds.snapshot.describe();
        if (deleted.searchFailed != null)
        {
            Activator.logWarning(operation + ": nothing was restored - " + deleted.searchFailed); //$NON-NLS-1$
            return joinNotes(snapshot, search);
        }
        if (launchIds == null || launchIds.access == null || launchIds.snapshot == null)
        {
            return search;
        }
        String report;
        try
        {
            LaunchApplicationIds.RestoreReport restored = LaunchApplicationIds.restore(
                launchIds.access, launchIds.snapshot.held, deleted.mementos, deleted.unverified);
            report = restored.isQuiet() ? null : restored.describe();
        }
        catch (Throwable e)
        {
            // The guard itself failed: the ids are where the write left them, and the caller is
            // told rather than handed silence.
            Activator.logWarning(operation + ": the launch configurations' application ids were not " //$NON-NLS-1$
                + "restored: " + msg(e)); //$NON-NLS-1$
            report = "the launch configurations' application ids were not restored: " + msg(e); //$NON-NLS-1$
        }
        return joinNotes(snapshot, search, report);
    }

    /** Joins the answer's notes, skipping the ones there is nothing to say about. */
    private static String joinNotes(String... notes)
    {
        List<String> present = new ArrayList<>();
        for (String note : notes)
        {
            if (note != null && !note.isEmpty())
            {
                present.add(note);
            }
        }
        return present.isEmpty() ? null : String.join("; ", present); //$NON-NLS-1$
    }

    /**
     * Finds precisely the launch configurations whose application belongs to the deleted base.
     * <p>
     * Each configuration is read on its own: one unreadable {@code .launch} file names itself in
     * the answer and leaves the search running, instead of costing every other configuration its
     * exclusion. A configuration whose project cannot be resolved - closed, or named nowhere -
     * cannot be placed: its binding may be the deleted base's, so it is excluded like the
     * identified ones, named in the answer, and its id stays removed. When nothing can be
     * identified at all - no application manager, or no launch configuration list - the answer
     * says so and the guard puts nothing back.
     * </p>
     *
     * @param launchIds what the guard captured before the write
     * @param environment where the applications and projects are read from
     * @param infobase the infobase that is being deleted
     * @return the configurations to exclude, and what the search could not establish
     */
    static DeletedBaseConfigurations deletedBaseConfigurations(LaunchIds launchIds,
        LaunchEnvironment environment, InfobaseReference infobase)
    {
        DeletedBaseConfigurations found = DeletedBaseConfigurations.none();
        if (launchIds == null || launchIds.manager == null)
        {
            return found;
        }
        IApplicationManager applicationManager = environment.applicationManager();
        if (applicationManager == null)
        {
            found.searchFailed = "nothing was restored: the launch configurations of the deleted " //$NON-NLS-1$
                + "infobase could not be identified, as the application manager is unavailable"; //$NON-NLS-1$
            return found;
        }
        ILaunchConfiguration[] configurations;
        try
        {
            configurations = launchIds.manager.getLaunchConfigurations();
        }
        catch (Throwable e)
        {
            found.searchFailed = "nothing was restored: the launch configurations could not be " //$NON-NLS-1$
                + "listed (" + msg(e) + "), so the ones of the deleted infobase could not be identified"; //$NON-NLS-1$ //$NON-NLS-2$
            return found;
        }
        for (ILaunchConfiguration configuration : configurations)
        {
            String memento = null;
            String mementoFailure = null;
            try
            {
                memento = configuration.getMemento();
            }
            catch (Throwable e)
            {
                mementoFailure = msg(e);
            }
            try
            {
                String projectName = configuration.getAttribute(LaunchConfigAccess.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
                String applicationId = configuration.getAttribute(
                    LaunchConfigAccess.ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
                if (applicationId.isEmpty())
                {
                    continue;
                }
                if (memento == null || memento.isEmpty())
                {
                    // Cannot be addressed: it is not in the snapshot and nothing is restored
                    // to it; the answer names it. When the snapshot did hold a configuration of
                    // this name, its binding is excluded as unverified, so the restore leaves
                    // it off too.
                    String name = nameOf(configuration);
                    found.notIdentified.add("could not address '" + name + "'" //$NON-NLS-1$ //$NON-NLS-2$
                        + (mementoFailure == null ? "" : " (" + mementoFailure + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + " - the application id was left off"); //$NON-NLS-1$
                    excludeHeldOfName(launchIds, found, name);
                    continue;
                }
                IProject project = projectName.isEmpty() ? null : environment.resolveProject(projectName);
                if (project == null)
                {
                    // The binding cannot be placed: it may be the deleted base's. It stays
                    // removed - putting it back could hand the deleted base's id back - and the
                    // answer names it once, here.
                    found.unverified.add(memento);
                    found.notIdentified.add(nameOf(configuration) + ": project '" + projectName //$NON-NLS-1$ //$NON-NLS-2$
                        + "' not resolved - the application id was left off"); //$NON-NLS-1$
                    Activator.logWarning("delete_infobase: the launch configuration '" //$NON-NLS-1$
                        + nameOf(configuration) + "' names project '" + projectName //$NON-NLS-1$
                        + "', which does not resolve; its application id was not put back"); //$NON-NLS-1$
                    continue;
                }
                IApplication application = applicationManager.getApplication(project, applicationId)
                    .orElse(null);
                if (application instanceof IInfobaseApplication
                    && sameInfobase(((IInfobaseApplication)application).getInfobase(), infobase))
                {
                    found.mementos.add(memento);
                }
            }
            catch (Throwable e)
            {
                String name = nameOf(configuration);
                if (memento == null || memento.isEmpty())
                {
                    // Nothing is excluded: without a memento there is no address to exclude.
                    found.notIdentified.add("could not address '" + name + "' (" //$NON-NLS-1$ //$NON-NLS-2$
                        + (mementoFailure != null ? mementoFailure : msg(e))
                        + ") - the application id was left off"); //$NON-NLS-1$
                }
                else
                {
                    // The memento was read, so the configuration is excluded from the restore;
                    // what could not be read is whether it belongs to the deleted base. The
                    // answer names it once, here.
                    found.unverified.add(memento);
                    found.notIdentified.add(name + ": could not be read (" + msg(e) //$NON-NLS-1$ //$NON-NLS-2$
                        + ") - the application id was left off"); //$NON-NLS-1$
                }
                Activator.logWarning("delete_infobase: the launch configuration '" + name //$NON-NLS-1$
                    + "' could not be read, so its application id was left off: " + msg(e)); //$NON-NLS-1$
            }
        }
        return found;
    }

    /**
     * Excludes the snapshot-held configuration of this display name, when there is one: the
     * search could not address the configuration, so it cannot tell whether the binding is the
     * deleted base's, and the binding stays off.
     *
     * @param launchIds what the guard captured before the write
     * @param found what the search has established so far
     * @param name the display name the unaddressable configuration carries
     */
    private static void excludeHeldOfName(LaunchIds launchIds, DeletedBaseConfigurations found,
        String name)
    {
        if (launchIds == null || launchIds.snapshot == null)
        {
            return;
        }
        for (Map.Entry<String, LaunchApplicationIds.SnapshotEntry> entry : launchIds.snapshot.held.entrySet())
        {
            if (entry.getValue().name.equals(name))
            {
                found.unverified.add(entry.getKey());
            }
        }
    }

    /** The display name of a configuration, or a placeholder when even that cannot be read. */
    private static String nameOf(ILaunchConfiguration configuration)
    {
        try
        {
            String name = configuration.getName();
            return name == null ? "(unnamed)" : name; //$NON-NLS-1$
        }
        catch (Throwable e)
        {
            return "(unnamed)"; //$NON-NLS-1$
        }
    }

    private static boolean sameInfobase(InfobaseReference left, InfobaseReference right)
    {
        if (left == right)
        {
            return true;
        }
        if (left == null || right == null)
        {
            return false;
        }
        if (left.getUuid() != null && right.getUuid() != null)
        {
            return left.getUuid().equals(right.getUuid());
        }
        String leftIdentity = InfobaseIdentity.of(left);
        return leftIdentity != null && leftIdentity.equals(InfobaseIdentity.of(right));
    }

    /** Finds the application id for an associated infobase (by matching name). */
    private static String resolveApplicationId(IProject project, String infobaseName)
    {
        IApplicationManager appMgr = Activator.getDefault() != null
            ? Activator.getDefault().getApplicationManager() : null;
        if (appMgr == null)
        {
            return null;
        }
        try
        {
            List<IApplication> apps = appMgr.getApplications(project);
            if (apps != null)
            {
                for (IApplication app : apps)
                {
                    if (app instanceof IInfobaseApplication)
                    {
                        InfobaseReference ib = ((IInfobaseApplication) app).getInfobase();
                        if (ib != null && infobaseName.equals(ib.getName()))
                        {
                            return app.getId();
                        }
                    }
                }
            }
        }
        catch (Throwable e)
        {
            Activator.logWarning("resolveApplicationId failed: " + msg(e)); //$NON-NLS-1$
        }
        return null;
    }

    /** Maps a create failure to an actionable message + tag. */
    private static void classifyCreate(CreateResult r, Throwable cause)
    {
        String chain = causeChainText(cause);
        String lower = chain.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("matchingruntimenotfound") //$NON-NLS-1$
            || (lower.contains("runtime") && (lower.contains("not found") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.contains("no matching") || lower.contains("cannot be resolved")))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            r.failureKind = ErrorTags.RUNTIME_NOT_FOUND.wire();
            r.error = "No resolvable 1C:Enterprise platform runtime for the infobase. " //$NON-NLS-1$
                + "Install/associate a platform version - the thick client performs the " //$NON-NLS-1$
                + "physical infobase creation. Underlying: " + firstLine(chain); //$NON-NLS-1$
            return;
        }
        r.failureKind = ErrorTags.CREATE_FAILED.wire();
        r.error = "Error: the infobase could not be created: " + firstLine(chain); //$NON-NLS-1$
    }

    private static String causeChainText(Throwable t)
    {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        Throwable c = t;
        while (c != null && depth < 8)
        {
            if (sb.length() > 0)
            {
                sb.append(" | "); //$NON-NLS-1$
            }
            sb.append(c.getClass().getSimpleName());
            if (c.getMessage() != null)
            {
                sb.append(": ").append(c.getMessage()); //$NON-NLS-1$
            }
            c = c.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static String firstLine(String s)
    {
        if (s == null)
        {
            return ""; //$NON-NLS-1$
        }
        int nl = s.indexOf('\n');
        String line = nl >= 0 ? s.substring(0, nl) : s;
        return line.length() > 400 ? line.substring(0, 400) + "..." : line; //$NON-NLS-1$
    }

    private static String msg(Throwable e)
    {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
