/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.Lock;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessType;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentTypes;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.aiedt.mcp.server.Activator;

/**
 * Resolving what a thick-client call of this server runs against, and classifying what it failed
 * with.
 * <p>
 * One platform resolution serves every call that spawns a 1C:Enterprise thick client - the
 * extension tools, the infobase object export, the dump-info rebuild and the {@code .dt} snapshot
 * runner. It walks project and infobase to the {@code IThickClientLauncher}, its
 * {@link ILaunchableRuntimeComponent} and the {@link RuntimeExecutionArguments} built from the
 * stored infobase access settings, and it never throws out: a failure lands in
 * {@link LauncherContext#error} together with the {@link ErrorTags} kind that names it.
 * </p>
 * <p>
 * This lived in {@code BmInfobaseExtensionHelper} until the {@code .dt} snapshot runner needed the
 * same resolution. Moving it here is what keeps the resolution single-sourced: the alternative was a
 * second copy of the chain, and the two would answer differently the first time one of them was
 * touched.
 * </p>
 */
public final class ThickClientLaunch
{
    /**
     * The platform runtime type the thick client of an infobase resolves against - EDT's own
     * update path resolves the same one.
     */
    static final String RUNTIME_TYPE_ENTERPRISE =
        "com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform"; //$NON-NLS-1$

    private ThickClientLaunch()
    {
        // static utility
    }

    /** The resolved thick-client environment one launcher call runs in. */
    public static final class LauncherContext
    {
        /** The launcher the call goes through. */
        public IThickClientLauncher launcher;

        /** The runtime component that owns the launcher's installation. */
        public ILaunchableRuntimeComponent component;

        /** The infobase the call runs against. */
        public InfobaseReference infobase;

        /** The project that owns the infobase. */
        public IProject project;

        /** The per-infobase lock, or {@code null} when this runtime has none. */
        public Lock lock;

        /** The credentials and access mode the platform call carries. */
        public RuntimeExecutionArguments args;

        /** The infobase's name, for answers that report where the work went. */
        public String infobaseName;

        /** Why the resolution failed, or {@code null} on success. */
        public String error;

        /** The {@link ErrorTags} kind of {@link #error}, or {@code null}. */
        public String failureKind;

        /**
         * The Designer launch boundary of the run this context belongs to, claimed once by whoever
         * crosses it first: the worker under the per-infobase lock, right before it calls the
         * launcher, or the abandonment side when it gives the run up. Fresh for every run - a
         * rebuild asks twice, the quick dump and the fallback - so each launch is claimed or
         * abandoned on its own.
         */
        public java.util.concurrent.atomic.AtomicBoolean launchClaim =
            new java.util.concurrent.atomic.AtomicBoolean();
    }

    /**
     * Resolves the ThickClient launcher + component + execution args for the IB.
     *
     * @param projectName the project that owns the infobase
     * @param applicationId the application naming the infobase; may be {@code null}
     * @return the context, with {@link LauncherContext#error} set when anything failed to resolve
     */
    public static LauncherContext resolveLauncher(String projectName, String applicationId)
    {
        LauncherContext ctx = new LauncherContext();
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            ctx.error = ProjectResolver.describeNotFound(projectName);
            ctx.failureKind = ErrorTags.PROJECT_NOT_FOUND.wire();
            return ctx;
        }
        InfobaseReference infobase = resolveInfobase(project, applicationId, ctx);
        if (infobase == null)
        {
            return ctx; // ctx.error set
        }
        ctx.infobase = infobase;
        ctx.infobaseName = infobase.getName();
        ctx.project = project;

        Activator a = Activator.getDefault();
        IResolvableRuntimeInstallationManager riMgr =
            a != null ? a.getResolvableRuntimeInstallationManager() : null;
        IRuntimeComponentManager compMgr = a != null ? a.getRuntimeComponentManager() : null;
        IInfobaseAccessManager accessMgr = a != null ? a.getInfobaseAccessManager() : null;
        IInfobaseManager infobaseManager = a != null ? a.getInfobaseManager() : null;
        if (riMgr == null || compMgr == null || accessMgr == null || infobaseManager == null)
        {
            ctx.error = "Runtime / infobase managers (incl. the per-infobase lock service) " //$NON-NLS-1$
                + "are not available on this EDT runtime."; //$NON-NLS-1$
            ctx.failureKind = ErrorTags.MANAGER_UNAVAILABLE.wire();
            return ctx;
        }
        // EDT holds the connected infobase through a persistent designer session, so a
        // spawned DESIGNER batch cannot take the platform config lock on its own. EDT's
        // own UI (ExportConfigurationFileService) wraps every thick-client IB call in
        // IInfobaseManager.getLock(infobase) to coordinate with that session; mirror it
        // here. Callers still null-guard ctx.lock for the rare getLock()-returns-null case.
        ctx.lock = infobaseManager.getLock(infobase);

        // The credential read below touches encrypted secure storage; prime it so
        // the master-password dialog is not posted on this background thread.
        String primeErr = BmInfobaseCredentialsHelper.primeSecureStorage();
        if (primeErr != null)
        {
            ctx.error = primeErr;
            ctx.failureKind = ErrorTags.STORAGE_LOCKED.wire();
            return ctx;
        }

        try
        {
            IResolvableRuntimeInstallation resolvable = riMgr.resolveByProjectAndInfobase(
                RUNTIME_TYPE_ENTERPRISE, project, infobase, InfobaseAccessType.UPDATE);
            RuntimeInstallation installation = resolvable.resolve(
                Collections.singletonList(IRuntimeComponentTypes.THICK_CLIENT), infobase.getAppArch());
            ComponentExecutorInfo<ILaunchableRuntimeComponent, IThickClientLauncher> info =
                compMgr.resolveExecutor(ILaunchableRuntimeComponent.class, IThickClientLauncher.class,
                    installation, IRuntimeComponentTypes.THICK_CLIENT);
            ctx.launcher = info.getExecutor();
            ctx.component = info.getComponent();
        }
        catch (Throwable e)
        {
            classifyFailure(e, s -> { ctx.error = s.error; ctx.failureKind = s.failureKind; });
            return ctx;
        }

        RuntimeExecutionArguments args = new RuntimeExecutionArguments();
        try
        {
            IInfobaseAccessSettings s = accessMgr.resolveSettings(infobase);
            if (s != null)
            {
                args.setAccess(s.access());
                args.setUsername(emptyToNull(s.userName()));
                args.setPassword(emptyToNull(s.password()));
            }
        }
        catch (Throwable e)
        {
            Activator.logWarning("thick client launch: resolveSettings failed (proceeding without " //$NON-NLS-1$
                + "credentials): " + msg(e)); //$NON-NLS-1$
        }
        ctx.args = args;
        return ctx;
    }

    /**
     * Resolves the infobase an application names, or reports why there is none.
     *
     * @param project the project that owns it
     * @param applicationId the application id a call named; may be {@code null}
     * @param ctx the context the refusal is written into
     * @return the infobase, or {@code null} with {@code ctx.error} set
     */
    private static InfobaseReference resolveInfobase(IProject project, String applicationId,
        LauncherContext ctx)
    {
        IApplicationManager appMgr = Activator.getDefault() != null
            ? Activator.getDefault().getApplicationManager() : null;
        if (appMgr == null)
        {
            ctx.error = "IApplicationManager is not available on this EDT runtime."; //$NON-NLS-1$
            ctx.failureKind = ErrorTags.MANAGER_UNAVAILABLE.wire();
            return null;
        }
        try
        {
            IApplication app;
            if (applicationId != null && !applicationId.isEmpty())
            {
                app = appMgr.getApplication(project, applicationId).orElse(null);
                if (app == null)
                {
                    ctx.error = "No application '" + applicationId + "' in project '" //$NON-NLS-1$ //$NON-NLS-2$
                        + project.getName() + "'. Use get_applications to list ids."; //$NON-NLS-1$
                    ctx.failureKind = ErrorTags.RESOLVE_FAILED.wire();
                    return null;
                }
            }
            else
            {
                List<IApplication> apps = appMgr.getApplications(project);
                if (apps == null || apps.isEmpty())
                {
                    ctx.error = "Project '" + project.getName() + "' has no infobase application."; //$NON-NLS-1$ //$NON-NLS-2$
                    ctx.failureKind = ErrorTags.RESOLVE_FAILED.wire();
                    return null;
                }
                if (apps.size() > 1)
                {
                    ctx.error = "Project '" + project.getName() + "' has multiple applications; " //$NON-NLS-1$ //$NON-NLS-2$
                        + "pass applicationId (see get_applications)."; //$NON-NLS-1$
                    ctx.failureKind = ErrorTags.RESOLVE_FAILED.wire();
                    return null;
                }
                app = apps.get(0);
            }
            if (!(app instanceof IInfobaseApplication))
            {
                ctx.error = "Application is not an infobase application; extension management " //$NON-NLS-1$
                    + "applies only to infobases."; //$NON-NLS-1$
                ctx.failureKind = ErrorTags.NOT_INFOBASE.wire();
                return null;
            }
            InfobaseReference ib = ((IInfobaseApplication) app).getInfobase();
            if (ib == null)
            {
                ctx.error = "The infobase application has no infobase reference."; //$NON-NLS-1$
                ctx.failureKind = ErrorTags.RESOLVE_FAILED.wire();
                return null;
            }
            return ib;
        }
        catch (Exception e)
        {
            ctx.error = "Failed to resolve the infobase: " + msg(e); //$NON-NLS-1$
            ctx.failureKind = ErrorTags.RESOLVE_FAILED.wire();
            return null;
        }
    }

    // --- failure classification ---

    /** What one classification answers: the text, and the kind that names it. */
    public static final class Classified
    {
        /** The failure text a caller can act on. */
        public String error;

        /** The {@link ErrorTags} kind of {@link #error}. */
        public String failureKind;
    }

    /** Where a classification goes, so a caller writes its own fields once. */
    public interface Sink
    {
        /**
         * @param c the classification; never {@code null}
         */
        void accept(Classified c);
    }

    /**
     * Classifies a thick-client failure into the few causes a caller can act on - a platform
     * version the infobase does not have, no resolvable runtime, rejected credentials, or anything
     * else - and names the underlying cause chain in the text.
     *
     * @param cause what the launcher or the resolution threw
     * @param sink what the classification is handed to
     */
    public static void classifyFailure(Throwable cause, Sink sink)
    {
        Classified c = new Classified();
        String chain = causeChainText(cause);
        String lower = chain.toLowerCase(Locale.ROOT);
        if (lower.contains("runtimeversionrequired")) //$NON-NLS-1$
        {
            // The .cfe / operation requires a platform version that the infobase's
            // associated runtime does not match. Public launcher verbs retry on a
            // fallback installation (findFallbackClient); the direct install path
            // cannot, so surface this distinctly instead of as a generic failure.
            c.failureKind = ErrorTags.PLATFORM_VERSION_MISMATCH.wire();
            c.error = "This operation requires a 1C:Enterprise platform version that does not " //$NON-NLS-1$
                + "match the infobase's associated runtime. Associate/install the required " //$NON-NLS-1$
                + "platform version for this infobase, then retry. Underlying: " //$NON-NLS-1$
                + oneLine(chain);
        }
        else if (lower.contains("matchingruntimenotfound") //$NON-NLS-1$
            || (lower.contains("runtime") && (lower.contains("not found") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.contains("no matching") || lower.contains("cannot be resolved")))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            c.failureKind = ErrorTags.RUNTIME_NOT_FOUND.wire();
            c.error = "No resolvable 1C:Enterprise platform runtime (with a thick client) for " //$NON-NLS-1$
                + "this infobase. Install/associate a matching platform version. Underlying: " //$NON-NLS-1$
                + oneLine(chain);
        }
        else if (lower.contains("authentication") || lower.contains("noaccessright") //$NON-NLS-1$ //$NON-NLS-2$
            || lower.contains("access right") || lower.contains("no access") //$NON-NLS-1$ //$NON-NLS-2$
            || lower.contains("аутентификаци") || lower.contains("недостаточно прав") //$NON-NLS-1$ //$NON-NLS-2$
            || lower.contains("прав доступа")) //$NON-NLS-1$
        {
            c.failureKind = ErrorTags.AUTH_FAILED.wire();
            c.error = "The infobase rejected the stored credentials. Set the correct user / " //$NON-NLS-1$
                + "password with set_infobase_credentials, then retry. Underlying: " //$NON-NLS-1$
                + oneLine(chain);
        }
        else
        {
            c.failureKind = ErrorTags.THICK_CLIENT_FAILED.wire();
            c.error = "The thick-client operation failed (the infobase may be locked by a " //$NON-NLS-1$
                + "running 1C client, unreachable, or the extension was not found): " //$NON-NLS-1$
                + oneLine(chain);
        }
        sink.accept(c);
    }

    /**
     * Flattens a failure and its causes into one line, for a message a caller reads.
     *
     * @param t the failure
     * @return the cause chain, joined by {@code " | "}, at most eight links deep
     */
    static String causeChainText(Throwable t)
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

    /**
     * Flattens a text to a single line without losing its tail, for a message that carries it.
     *
     * @param s the text; may be {@code null}
     * @return one line, at most 500 characters, empty for {@code null}
     */
    static String oneLine(String s)
    {
        if (s == null)
        {
            return ""; //$NON-NLS-1$
        }
        // Flatten to a single line, preserving the whole cause chain (joined by
        // " | ") rather than truncating at the first embedded newline.
        String line = s.replace('\n', ' ').replace('\r', ' ');
        return line.length() > 500 ? line.substring(0, 500) + "..." : line; //$NON-NLS-1$
    }

    /**
     * @param s a stored setting; may be {@code null}
     * @return the text, or {@code null} when it is empty
     */
    private static String emptyToNull(String s)
    {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * @param e a failure
     * @return its message, or its class name when it carries none
     */
    static String msg(Throwable e)
    {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
