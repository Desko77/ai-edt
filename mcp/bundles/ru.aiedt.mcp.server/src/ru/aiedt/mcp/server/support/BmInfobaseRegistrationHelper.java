/*
 * Licensed under AGPL-3.0-or-later.
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */
package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.debug.core.ILaunchManager;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.BmInfobaseLifecycleHelper.DeletedBaseConfigurations;
import ru.aiedt.mcp.server.support.BmInfobaseLifecycleHelper.LaunchIds;

/**
 * Registers an EXISTING infobase (file or server) in EDT's infobase list and associates it to a
 * project as its application, in one operation.
 * <p>
 * The infobase itself is not created and its files are never touched: only the list entry is
 * written, through {@code IInfobaseManager.add}, which is why
 * {@code IInfobaseCreationOperation} is deliberately not used - it unconditionally runs the thick
 * client to CREATE the physical infobase. When the association fails after the entry was added,
 * the entry is removed again ({@code IInfobaseManager.delete}, which does not delete the infobase
 * files), and the answer names that rollback.
 * </p>
 * <p>
 * A duplicate is searched before any write: the new reference's {@link InfobaseIdentity} is
 * compared against every entry of the list, so the same infobase spelled with a trailing separator
 * or a different letter case (where the file system folds case) is reused, not added twice. The
 * answer names the projects a reused entry was already bound to ({@code alsoAssociatedWith}),
 * naming separately the projects whose applications could not be read, where the binding check
 * did not run ({@code associationCheckFailed}), and
 * a name another entry already carries at another address is refused before the write - the name
 * is how {@code delete_infobase} and {@code create_launch_config} resolve the list.
 * </p>
 * <p>
 * Both the add and a rollback delete save the infobase list, and that save strips
 * {@code ATTR_APPLICATION_ID} from the launch configurations of EVERY project in the workspace.
 * The whole attempt therefore runs under the {@link LaunchApplicationIds} guard, exactly like
 * {@code create_infobase} and {@code delete_infobase}, and the answer carries what the guard
 * repaired in {@code launchApplicationIds}.
 * </p>
 * <p>
 * When the project's configuration defaults to the ordinary application, the infobase's additional
 * launch parameters get {@code /RunModeOrdinaryApplication} for this EDT session (the same
 * in-memory reconciling {@code start_client} does); the answer says whether the flag was set and
 * why not when it was not. Never throws out; all failures land in the returned result.
 * </p>
 */
public final class BmInfobaseRegistrationHelper
{
    private BmInfobaseRegistrationHelper()
    {
    }

    /** Result of {@link #registerInfobase}. */
    public static final class RegisterResult
    {
        public boolean ok;
        public String error;
        public String failureKind;   // projectNotFound / managerUnavailable / writeFailed / associateFailed
        /** The list entry's name - the one added, or the reused one's. */
        public String infobaseName;
        public String uuid;
        /** Whether a new list entry was written; false means an existing entry was reused. */
        public boolean added;
        public String applicationId;
        /** Whether the application is the project's default after the call. */
        public boolean defaultApplication;
        /** The default application that stood before, or {@code null} when there was none. */
        public String previousDefault;
        /** Why the default was left alone, could not be set, or the one it replaced is not named. */
        public String defaultWarning;
        /** Projects a reused entry was already bound to; {@code null} when it was bound nowhere else. */
        public List<String> alsoAssociatedWith;
        /** Other projects whose applications could not be read, so the binding check did not run there. */
        public List<String> associationCheckFailed;
        /** What the ordinary-application flag did: added / present / not set: ... */
        public String ordinaryApplicationFlag;
        /** Whether the list entry added for this call was removed again after a failed binding. */
        public boolean rolledBack;
        /** Why the rollback itself failed, or {@code null}. */
        public String rollbackFailure;
        public String launchApplicationIds;
    }

    /**
     * Where the registration reads the managers, the project and the launch mode from. The product
     * resolves them through {@link Activator}; a test substitutes its own.
     */
    public interface RegistrationEnvironment
    {
        /** @return EDT's infobase manager, or <code>null</code> when it is unavailable */
        IInfobaseManager infobaseManager();

        /** @return EDT's infobase association manager, or <code>null</code> when unavailable */
        IInfobaseAssociationManager associationManager();

        /** @return EDT's application manager, or <code>null</code> when it is unavailable */
        IApplicationManager applicationManager();

        /** @return Eclipse's launch manager, or <code>null</code> when the debug plugin is down */
        ILaunchManager launchManager();

        /**
         * @param name a project name
         * @return the project, or <code>null</code> when unknown
         */
        IProject resolveProject(String name);

        /**
         * @return every project of the workspace, in the workspace's order
         */
        List<IProject> allProjects();

        /**
         * @param project the project
         * @return the association context its applications are read from
         */
        InfobaseAssociationContext associationContext(IProject project);

        /**
         * @param project the project
         * @return {@link ClientLaunchMode#ORDINARY}, {@link ClientLaunchMode#MANAGED}, or
         *         <code>null</code> when the configuration's default run mode cannot be read
         */
        String projectRunMode(IProject project);

        /**
         * Puts the ordinary-application flag on the application's infobase for this EDT session.
         *
         * @param application the application just associated
         * @param wanted whether the flag has to be present
         * @return what happened: {@code added}, {@code present}, or {@code not applied: ...}
         */
        String applyOrdinaryFlag(IApplication application, boolean wanted);
    }

    /** What the product resolves; a test passes its own environment to the overload. */
    private static final RegistrationEnvironment PRODUCT = new RegistrationEnvironment()
    {
        @Override
        public IInfobaseManager infobaseManager()
        {
            Activator activator = Activator.getDefault();
            return activator == null ? null : activator.getInfobaseManager();
        }

        @Override
        public IInfobaseAssociationManager associationManager()
        {
            Activator activator = Activator.getDefault();
            return activator == null ? null : activator.getInfobaseAssociationManager();
        }

        @Override
        public IApplicationManager applicationManager()
        {
            Activator activator = Activator.getDefault();
            return activator == null ? null : activator.getApplicationManager();
        }

        @Override
        public ILaunchManager launchManager()
        {
            return LaunchConfigAccess.getLaunchManager();
        }

        @Override
        public IProject resolveProject(String name)
        {
            return ProjectResolver.resolve(name);
        }

        @Override
        public List<IProject> allProjects()
        {
            return Arrays.asList(ResourcesPlugin.getWorkspace().getRoot().getProjects());
        }

        @Override
        public InfobaseAssociationContext associationContext(IProject project)
        {
            return BmInfobaseLifecycleHelper.associationContextOf(project);
        }

        @Override
        public String projectRunMode(IProject project)
        {
            return ClientLaunchMode.projectRunMode(project);
        }

        @Override
        public String applyOrdinaryFlag(IApplication application, boolean wanted)
        {
            return ClientLaunchMode.reconcileFlag(application, wanted);
        }
    };

    /**
     * Registers an existing infobase to a project against the product's managers.
     *
     * @param projectName the project to associate the infobase to; required
     * @param path absolute path of an existing FILE infobase directory; exactly one of path /
     *        connectionString
     * @param connectionString a SERVER infobase address, {@code Srvr=...;Ref=...}, without
     *        credentials; exactly one of path / connectionString
     * @param name the name in EDT's list; required for a server infobase, defaults to the
     *        directory name for a file one; {@code null} means "not passed"
     * @param makeDefault whether the application becomes the project's default; {@code null} means
     *        "true when the project has no default application, false otherwise" - and a default
     *        that could not be read counts as standing, not as absent
     * @return what was added or reused, bound and set as default; never <code>null</code>
     */
    public static RegisterResult registerInfobase(String projectName, String path,
        String connectionString, String name, Boolean makeDefault)
    {
        return registerInfobase(projectName, path, connectionString, name, makeDefault, PRODUCT);
    }

    /**
     * Registers an existing infobase to a project; the environment is the seam a test fakes.
     *
     * @param projectName the project to associate the infobase to; required
     * @param path absolute path of an existing FILE infobase directory
     * @param connectionString a SERVER infobase address, {@code Srvr=...;Ref=...}
     * @param name the list name, or {@code null} for the file-infobase default
     * @param makeDefault whether the application becomes the project's default, or {@code null}
     *        for the "only when the project has none" default
     * @param env where the managers and the project are read from
     * @return what was added or reused, bound and set as default; never <code>null</code>
     */
    public static RegisterResult registerInfobase(String projectName, String path,
        String connectionString, String name, Boolean makeDefault, RegistrationEnvironment env)
    {
        RegisterResult r = new RegisterResult();
        r.infobaseName = name;

        String refusal = refusalOf(projectName, path, connectionString, name);
        if (refusal != null)
        {
            r.error = refusal;
            return r;
        }

        IProject project = env.resolveProject(projectName);
        if (project == null)
        {
            r.error = ProjectResolver.describeNotFound(projectName);
            r.failureKind = ErrorTags.PROJECT_NOT_FOUND.wire();
            return r;
        }

        IInfobaseManager mgr = env.infobaseManager();
        IInfobaseAssociationManager am = env.associationManager();
        IApplicationManager appMgr = env.applicationManager();
        if (mgr == null || am == null || appMgr == null)
        {
            r.error = "Infobase managers are not available on this EDT runtime."; //$NON-NLS-1$
            r.failureKind = ErrorTags.MANAGER_UNAVAILABLE.wire();
            return r;
        }

        boolean server = connectionString != null && !connectionString.isBlank();
        InfobaseReference candidate = server
            ? InfobaseReferences.newServerInfobaseReference(serverOf(connectionString),
                referenceOf(connectionString))
            : InfobaseReferences.newFileInfobaseReference(path.trim());
        candidate.setUuid(UUID.randomUUID());
        candidate.setName(name != null && !name.isBlank() ? name.trim() : defaultNameOf(path));

        // The snapshot, the duplicate search, the add, the binding, a rollback delete and the
        // restore run under the one write lock: two calls at once must not both see no entry and
        // both add, a second infobase-list write at once would snapshot the ids this write
        // already stripped, and the rollback delete strips them a second time.
        ILaunchManager launchManager = env.launchManager();
        LaunchApplicationIds.Access access = LaunchConfigAccess.applicationIdAccess(launchManager);
        InfobaseReference[] target = new InfobaseReference[1];
        LaunchApplicationIds.underWriteLock(access, snapshot -> {
            LaunchIds launchIds = new LaunchIds(launchManager, access, snapshot);
            // The duplicate search runs before any write: the same infobase under another
            // spelling is reused, never added twice.
            InfobaseReference found = candidate;
            String identity = InfobaseIdentity.of(candidate);
            for (InfobaseReference existing : InfobaseReferences.asPlainList(mgr.getAll()))
            {
                if (identity.equals(InfobaseIdentity.of(existing)))
                {
                    found = existing;
                    break;
                }
            }
            target[0] = found;
            r.added = found == candidate;
            if (r.added)
            {
                // A second entry under one name would leave delete_infobase and
                // create_launch_config resolving that name to the wrong base, so the collision
                // is refused before the write, the way create_infobase refuses it.
                Optional<InfobaseReference> sameName = mgr.findInfobaseByName(candidate.getName());
                if (sameName.isPresent() && !sameInfobase(sameName.get(), candidate))
                {
                    r.error = "An infobase named '" + candidate.getName() + "' already stands in " //$NON-NLS-1$ //$NON-NLS-2$
                        + "EDT's list at another address. Pass another name, or delete that " //$NON-NLS-1$
                        + "entry first (delete_infobase)."; //$NON-NLS-1$
                    r.failureKind = ErrorTags.ALREADY_EXISTS.wire();
                    return r;
                }
                try
                {
                    mgr.add(found, ""); //$NON-NLS-1$
                }
                catch (Throwable e)
                {
                    r.launchApplicationIds = restore(launchIds);
                    r.error = "Failed to add the infobase to EDT's list: " + msg(e); //$NON-NLS-1$
                    r.failureKind = ErrorTags.WRITE_FAILED.wire();
                    return r;
                }
            }
            else
            {
                OtherProjects others = otherProjectsBoundTo(appMgr, env, project, found);
                r.alsoAssociatedWith = others.bound.isEmpty() ? null : others.bound;
                r.associationCheckFailed = others.checkFailed.isEmpty() ? null : others.checkFailed;
            }
            try
            {
                am.associate(project, found,
                    InfobaseAssociationSettings.notSynchronized(env.associationContext(project)));
            }
            catch (Throwable e)
            {
                if (r.added)
                {
                    try
                    {
                        mgr.delete(found);
                        r.rolledBack = true;
                    }
                    catch (Throwable rollback)
                    {
                        r.rollbackFailure = msg(rollback);
                    }
                }
                r.launchApplicationIds = restore(launchIds);
                r.error = "Failed to associate the infobase to the project: " + msg(e) //$NON-NLS-1$
                    + (r.rolledBack
                        ? " The list entry added for it was removed again; the infobase itself was not touched." //$NON-NLS-1$
                        : r.rollbackFailure != null
                            ? " The list entry added for it could NOT be removed again: " //$NON-NLS-1$
                                + r.rollbackFailure
                            : ""); //$NON-NLS-1$
                r.failureKind = ErrorTags.ASSOCIATE_FAILED.wire();
                return r;
            }
            r.launchApplicationIds = restore(launchIds);
            return r;
        });
        if (r.error != null)
        {
            return r;
        }
        r.ok = true;
        InfobaseReference bound = target[0];
        r.infobaseName = bound.getName();
        r.uuid = bound.getUuid() == null ? null : bound.getUuid().toString();

        IApplication application = findApplication(appMgr, project, bound);
        if (application != null)
        {
            r.applicationId = application.getId();
        }

        DefaultRead defaultRead = defaultApplicationOf(appMgr, project);
        IApplication previousDefault = defaultRead.application;
        if (previousDefault != null)
        {
            r.previousDefault = previousDefault.getName();
        }
        // An unread default is not "there is none": the omit rule must not replace a default
        // the answer never saw.
        boolean makeItDefault = makeDefault != null ? makeDefault.booleanValue()
            : previousDefault == null && defaultRead.failure == null;
        if (makeItDefault)
        {
            if (application != null)
            {
                try
                {
                    appMgr.setDefaultApplication(project, application);
                    r.defaultApplication = true;
                }
                catch (Throwable e)
                {
                    r.defaultWarning = "the application could not be made the project's default: " //$NON-NLS-1$
                        + msg(e);
                }
            }
            else
            {
                r.defaultWarning = "the new application was not found among the project's " //$NON-NLS-1$
                    + "applications, so it could not be made the default"; //$NON-NLS-1$
            }
        }
        else
        {
            // The flag answers "the application is the project's default after the call": a reused
            // entry that already held that state keeps it without a write.
            r.defaultApplication = defaultNamesInfobase(previousDefault, application, bound);
        }
        if (defaultRead.failure != null && r.defaultWarning == null)
        {
            // The text follows the outcome: a set that went through replaced a default the answer
            // never saw, so only the omission of its name is left to say; otherwise the standing
            // default was left alone.
            r.defaultWarning = r.defaultApplication
                ? "the default that stood before could not be read (" + defaultRead.failure //$NON-NLS-1$
                    + "), so the answer does not name it" //$NON-NLS-1$
                : "the project's default application could not be read (" + defaultRead.failure //$NON-NLS-1$
                    + "), so the standing default was left alone"; //$NON-NLS-1$
        }

        String runMode = env.projectRunMode(project);
        if (application == null)
        {
            r.ordinaryApplicationFlag = "not set: the new application was not found among the " //$NON-NLS-1$
                + "project's applications"; //$NON-NLS-1$
        }
        else if (ClientLaunchMode.ORDINARY.equals(runMode))
        {
            r.ordinaryApplicationFlag = env.applyOrdinaryFlag(application, true);
        }
        else if (runMode == null)
        {
            r.ordinaryApplicationFlag = "not set: the configuration's default run mode was not read"; //$NON-NLS-1$
        }
        else
        {
            r.ordinaryApplicationFlag = "not set: the configuration's default run mode is managed"; //$NON-NLS-1$
        }
        return r;
    }

    /**
     * The refusal an argument set earns before anything is read or written, or {@code null} when
     * the arguments can proceed.
     *
     * @param projectName the project argument
     * @param path the file-infobase path argument, or {@code null}
     * @param connectionString the server address argument, or {@code null}
     * @param name the list-name argument, or {@code null}
     * @return the refusal text, or <code>null</code> when the arguments hold
     */
    static String refusalOf(String projectName, String path, String connectionString, String name)
    {
        if (projectName == null || projectName.isBlank())
        {
            return "projectName is required - the project the infobase is registered to."; //$NON-NLS-1$
        }
        boolean hasPath = path != null && !path.isBlank();
        boolean hasConnection = connectionString != null && !connectionString.isBlank();
        if (hasPath == hasConnection)
        {
            return "Pass exactly one of path (a file infobase directory) or connectionString " //$NON-NLS-1$
                + "(a server infobase, Srvr=...;Ref=...) - " //$NON-NLS-1$
                + (hasPath ? "both were passed." : "neither was."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (hasConnection)
        {
            String refusal = credentialsRefusal(connectionString);
            if (refusal != null)
            {
                return refusal;
            }
            if (serverOf(connectionString) == null || referenceOf(connectionString) == null)
            {
                return "connectionString must name the server and the infobase: " //$NON-NLS-1$
                    + "Srvr=<server>;Ref=<infobase>."; //$NON-NLS-1$
            }
            if (name == null || name.isBlank())
            {
                return "name is required for a server infobase - it has no directory to take " //$NON-NLS-1$
                    + "one from."; //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * The refusal for a connection string that carries a user or a password: credentials have
     * their own operation, and taking them here would put them into logs and answers.
     *
     * @param connectionString the server address argument
     * @return the refusal text, or <code>null</code> when no credentials are present
     */
    private static String credentialsRefusal(String connectionString)
    {
        for (String part : connectionString.split(";")) //$NON-NLS-1$
        {
            int eq = part.indexOf('=');
            if (eq < 0)
            {
                continue;
            }
            String key = part.substring(0, eq).trim();
            if (key.equalsIgnoreCase("usr") || key.equalsIgnoreCase("pwd")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return "connectionString carries " + key + " - credentials are not taken here. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Store them with set_infobase_credentials and pass a bare " //$NON-NLS-1$
                    + "Srvr=...;Ref=... string."; //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * The server host of a {@code Srvr=...;Ref=...} connection string, or {@code null} when the
     * string names none.
     *
     * @param connectionString the server address argument
     * @return the host, quotes stripped; <code>null</code> when absent
     */
    private static String serverOf(String connectionString)
    {
        return valueOfKey(connectionString, "srvr"); //$NON-NLS-1$
    }

    /**
     * The infobase name of a {@code Srvr=...;Ref=...} connection string, or {@code null} when the
     * string names none.
     *
     * @param connectionString the server address argument
     * @return the infobase name, quotes stripped; <code>null</code> when absent
     */
    private static String referenceOf(String connectionString)
    {
        return valueOfKey(connectionString, "ref"); //$NON-NLS-1$
    }

    /** The value of one key in a semicolon-separated connection string, or {@code null}. */
    private static String valueOfKey(String connectionString, String wantedKey)
    {
        for (String part : connectionString.split(";")) //$NON-NLS-1$
        {
            int eq = part.indexOf('=');
            if (eq < 0)
            {
                continue;
            }
            if (part.substring(0, eq).trim().equalsIgnoreCase(wantedKey))
            {
                String value = part.substring(eq + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * The default list name of a file infobase: its directory's last segment.
     *
     * @param path the infobase directory path
     * @return the last path segment; the whole path when nothing better names it
     */
    static String defaultNameOf(String path)
    {
        String trimmed = path.trim();
        while (trimmed.length() > 1 && (trimmed.endsWith("/") || trimmed.endsWith("\\"))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String name = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        return name.isEmpty() ? trimmed : name;
    }

    /**
     * Puts the stripped launch configurations' application ids back and composes what the answer
     * says about it. The registration deletes no application, so nothing is excluded.
     *
     * @param launchIds what the guard captured before the write
     * @return the sentence for the answer, or <code>null</code> when there is nothing to say
     */
    private static String restore(LaunchIds launchIds)
    {
        return BmInfobaseLifecycleHelper.restoreLaunchApplicationIds(launchIds,
            DeletedBaseConfigurations.none(), "register_infobase"); //$NON-NLS-1$
    }

    /**
     * An application of the project that points at the given infobase: how the new binding
     * materializes, and how a reused entry's other homes are found.
     *
     * @param appMgr the application manager
     * @param project the project
     * @param infobase the reference to match
     * @return the application, or <code>null</code> when it cannot be read
     */
    private static IApplication findApplication(IApplicationManager appMgr, IProject project,
        InfobaseReference infobase)
    {
        return scanApplication(appMgr, project, infobase).application;
    }

    /**
     * What reading a project's applications for one infobase ended with: the application found,
     * or why the read failed. The two must stay apart - a failed read answered as "not bound"
     * would silence the projects the answer never checked.
     */
    private static final class ApplicationScan
    {
        /** The application pointing at the infobase; {@code null} when there is none or the read failed. */
        IApplication application;

        /** Why the read failed, or {@code null} when it succeeded. */
        String failure;
    }

    /**
     * Reads the project's applications and picks the one pointing at the given infobase.
     *
     * @param appMgr the application manager
     * @param project the project
     * @param infobase the reference to match
     * @return what was found, with {@link ApplicationScan#failure} set when the read failed
     */
    private static ApplicationScan scanApplication(IApplicationManager appMgr, IProject project,
        InfobaseReference infobase)
    {
        ApplicationScan scan = new ApplicationScan();
        try
        {
            List<IApplication> applications = appMgr.getApplications(project);
            if (applications != null)
            {
                for (IApplication application : applications)
                {
                    if (application instanceof IInfobaseApplication
                        && sameInfobase(((IInfobaseApplication)application).getInfobase(), infobase))
                    {
                        scan.application = application;
                        return scan;
                    }
                }
            }
        }
        catch (Throwable e)
        {
            scan.failure = msg(e);
            Activator.logWarning("register_infobase: the applications of a project were not read: " //$NON-NLS-1$
                + scan.failure);
        }
        return scan;
    }

    /**
     * What reading the project's default application ended with: the application, or why the read
     * failed. The two must stay apart - a failed read answered as "there is none" lets the omit
     * rule replace a default nobody saw.
     */
    private static final class DefaultRead
    {
        /** The default application; {@code null} when there is none or the read failed. */
        IApplication application;

        /** Why the read failed, or {@code null} when it succeeded. */
        String failure;
    }

    /**
     * The project's current default application.
     *
     * @param appMgr the application manager
     * @param project the project
     * @return what was read; {@link DefaultRead#failure} is set when the read failed
     */
    private static DefaultRead defaultApplicationOf(IApplicationManager appMgr, IProject project)
    {
        DefaultRead read = new DefaultRead();
        try
        {
            read.application = appMgr.getDefaultApplication(project).orElse(null);
        }
        catch (Throwable e)
        {
            read.failure = msg(e);
            Activator.logWarning("register_infobase: the project's default application was not read: " //$NON-NLS-1$
                + read.failure);
        }
        return read;
    }

    /**
     * Whether two application reads name the one application: by id, and for infobase
     * applications by the infobase they point at. A missing read on either side is not a
     * match, and two missing reads are not the one application either.
     *
     * @param left one application, possibly {@code null}
     * @param right the other, possibly {@code null}
     * @return whether they name the same application
     */
    private static boolean sameApplication(IApplication left, IApplication right)
    {
        if (left == null || right == null)
        {
            return false;
        }
        if (left == right)
        {
            return true;
        }
        if (left.getId() != null && right.getId() != null)
        {
            return left.getId().equals(right.getId());
        }
        if (left instanceof IInfobaseApplication && right instanceof IInfobaseApplication)
        {
            return sameInfobase(((IInfobaseApplication)left).getInfobase(),
                ((IInfobaseApplication)right).getInfobase());
        }
        return false;
    }

    /**
     * Whether the default that stood before the call names the infobase this call bound: the
     * application just found, or - when that lookup found nothing - an infobase application
     * pointing at the same list entry. A default the answer never read is not a match.
     *
     * @param previousDefault the default read before the call, or {@code null}
     * @param application the application found after the binding, or {@code null}
     * @param bound the list entry the call bound
     * @return whether the project's default points at the bound infobase
     */
    private static boolean defaultNamesInfobase(IApplication previousDefault,
        IApplication application, InfobaseReference bound)
    {
        if (previousDefault == null)
        {
            return false;
        }
        if (application != null && sameApplication(previousDefault, application))
        {
            return true;
        }
        return previousDefault instanceof IInfobaseApplication
            && sameInfobase(((IInfobaseApplication)previousDefault).getInfobase(), bound);
    }

    /** The outcome of scanning the other projects for bindings to a reused entry. */
    private static final class OtherProjects
    {
        /** Projects whose applications point at the entry. */
        final List<String> bound = new ArrayList<>();

        /** Projects whose applications could not be read, so the check did not run there. */
        final List<String> checkFailed = new ArrayList<>();
    }

    /**
     * The projects a reused entry was already bound to before this call: every workspace project
     * but the one being registered to, whose applications point at the same infobase. A project
     * whose applications could not be read is named apart - there the check did not run.
     *
     * @param appMgr the application manager
     * @param env where the workspace's projects are read from
     * @param registered the project being registered to
     * @param infobase the reused list entry
     * @return the bound projects, and apart the projects whose check failed
     */
    private static OtherProjects otherProjectsBoundTo(IApplicationManager appMgr,
        RegistrationEnvironment env, IProject registered, InfobaseReference infobase)
    {
        OtherProjects result = new OtherProjects();
        for (IProject project : env.allProjects())
        {
            if (registered.getName().equals(project.getName()))
            {
                continue;
            }
            ApplicationScan scan = scanApplication(appMgr, project, infobase);
            if (scan.application != null)
            {
                result.bound.add(project.getName());
            }
            else if (scan.failure != null)
            {
                result.checkFailed.add(project.getName());
            }
        }
        return result;
    }

    /** Whether two references name the one infobase: by uuid, else by normalized identity. */
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

    /** The message of a failure, or its class name when it carries none. */
    private static String msg(Throwable e)
    {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
