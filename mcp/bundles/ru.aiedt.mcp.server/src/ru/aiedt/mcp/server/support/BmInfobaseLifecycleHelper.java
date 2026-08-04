/*
 * Licensed under AGPL-3.0-or-later.
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */
package ru.aiedt.mcp.server.support;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;

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
    }

    /** Result of associate. */
    public static final class AssocResult
    {
        public boolean ok;
        public String error;
        public String failureKind;   // projectNotFound / managerUnavailable / infobaseNotFound / associateFailed
        public String infobaseName;
        public String applicationId;
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
            classifyCreate(r, e);
            return r;
        }
        r.ok = true;
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
     * Associates an existing infobase (by name) to a project - the "launch
     * configuration" step that makes it appear in get_applications. New empty
     * infobases are bound as not-synchronized (so get_applications reports an
     * update is required).
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
        try
        {
            am.associate(project, ref.get(), InfobaseAssociationSettings.notSynchronized());
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
        if (projectName != null && !projectName.isEmpty())
        {
            IProject project = ProjectResolver.resolve(projectName);
            IInfobaseAssociationManager am = Activator.getDefault() != null
                ? Activator.getDefault().getInfobaseAssociationManager() : null;
            if (project != null && am != null)
            {
                try
                {
                    am.dissociate(project, ref.get(), InfobaseAssociationContext.empty());
                    r.dissociated = true;
                }
                catch (Throwable e)
                {
                    // Non-fatal: the infobase may simply not have been associated.
                    // Surface a warning (deletion still proceeds) so a genuine
                    // dissociate failure that leaves a dangling launch config is
                    // visible to the caller.
                    r.dissociateWarning = "could not dissociate from '" + projectName //$NON-NLS-1$
                        + "': " + msg(e) + " (deletion proceeded; the project's launch " //$NON-NLS-1$ //$NON-NLS-2$
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
            r.error = "Failed to delete the infobase: " + msg(e); //$NON-NLS-1$
            r.failureKind = ErrorTags.DELETE_FAILED.wire();
            return r;
        }
        r.ok = true;
        return r;
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
