/*
 * Licensed under AGPL-3.0-or-later.
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */
package ru.aiedt.mcp.server.support;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.provider.IProviderHints;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.aiedt.mcp.server.Activator;

/**
 * Sets the connection credentials (user + password, or OS auth) for an infobase
 * application, stored in EDT's own encrypted secure storage so that later
 * operations ({@code update_database}, base-linked external-object build,
 * launch) connect without popping an interactive login dialog.
 * <p>
 * Path: resolve the {@code IInfobaseApplication} -&gt; {@code InfobaseReference},
 * then write via {@code IInfobaseAccessManager.updateSettings(...)} which stores
 * to the Equinox secure node
 * {@code com._1c.g5.v8.dt.platform.services.core/infobaseBinding/&lt;uuid&gt;}.
 * <p>
 * The one non-obvious step is {@link #primeSecureStorage()}: EDT is a UI RCP
 * app, so Equinox does NOT auto-suppress the secure-storage master-password
 * dialog. Reading/writing the encrypted node from a background MCP worker would
 * post that modal dialog and hang the call. Priming the shared secure-storage
 * root with {@code PROMPT_USER=false} and a throwaway encrypt forces the key to
 * be acquired silently (Windows DPAPI / WinCrypto), after which the manager's
 * own {@code getDefault()} hits the cached key. If no non-UI key provider is
 * available the prime throws a caught exception - a clean error, never a hang.
 * <p>
 * <b>Security:</b> the password is only ever written to the encrypted store; it
 * is never logged and never returned. Callers report {@code passwordStored} as a
 * boolean, not the value.
 */
public final class BmInfobaseCredentialsHelper
{
    /** Throwaway node used to force silent key acquisition on the shared root. */
    private static final String PRIME_NODE = "ru/aiedt/mcp/probe"; //$NON-NLS-1$

    private BmInfobaseCredentialsHelper()
    {
    }

    /** Outcome of a set-credentials operation (never carries the password). */
    public static final class CredentialResult
    {
        public boolean ok;
        public String error;
        /** managerUnavailable / notInfobase / resolveFailed / storageLocked / writeFailed / readbackFailed. */
        public String failureKind;
        public String applicationId;
        public String infobaseName;
        /** From the in-process readback: OS / INFOBASE. */
        public String access;
        /** From the readback (not secret). */
        public String userName;
        /** True when a non-empty password is stored (the value is never exposed). */
        public boolean passwordStored;
        public String additionalParameters;
    }

    /** Internal infobase resolution. */
    private static final class InfobaseResolution
    {
        InfobaseReference infobase;
        String applicationId;
        String infobaseName;
        String error;
        boolean notInfobaseApp;
    }

    /**
     * Resolves the infobase, primes secure storage, writes the credentials, and
     * reads them back for confirmation. Never throws; all failures land in the
     * returned {@link CredentialResult}.
     *
     * @param project       the EDT project owning the application
     * @param applicationId application id, or null/empty to auto-pick the single one
     * @param accessMode    "OS" or "INFOBASE" (case-insensitive); null defaults to
     *                      INFOBASE when a userName is given, else OS
     * @param userName      infobase user (may be null for OS access)
     * @param password      infobase password (may be null; never logged/returned)
     */
    public static CredentialResult setCredentials(IProject project, String applicationId,
        String accessMode, String userName, String password)
    {
        CredentialResult r = new CredentialResult();

        InfobaseResolution res = resolveInfobase(project, applicationId);
        if (res.error != null)
        {
            r.error = res.error;
            r.failureKind = res.notInfobaseApp ? ErrorTags.NOT_INFOBASE.wire() : ErrorTags.RESOLVE_FAILED.wire();
            return r;
        }
        r.applicationId = res.applicationId;
        r.infobaseName = res.infobaseName;

        IInfobaseAccessManager mgr = resolveManager();
        if (mgr == null)
        {
            r.error = "IInfobaseAccessManager is not available on this EDT runtime."; //$NON-NLS-1$
            r.failureKind = ErrorTags.MANAGER_UNAVAILABLE.wire();
            return r;
        }

        // Prime the shared secure-storage root so the read/write below cannot pop
        // a modal master-password dialog on this background thread.
        String primeErr = primeSecureStorage();
        if (primeErr != null)
        {
            r.error = primeErr;
            r.failureKind = ErrorTags.STORAGE_LOCKED.wire();
            return r;
        }

        InfobaseAccess access = "OS".equalsIgnoreCase(accessMode) //$NON-NLS-1$
            ? InfobaseAccess.OS
            : InfobaseAccess.INFOBASE;
        // OS authentication does not use a user/password - do not persist unused
        // (encrypted) credentials under it.
        if (access == InfobaseAccess.OS)
        {
            userName = null;
            password = null;
        }

        // Preserve the current additionalParameters so updateSettings (which also
        // persists them back to the infobase model) does not clobber them.
        String additionalParams = null;
        try
        {
            IInfobaseAccessSettings current = mgr.resolveSettings(res.infobase);
            if (current != null)
            {
                additionalParams = current.additionalProperties();
            }
        }
        catch (Throwable e)
        {
            Activator.logWarning("set_infobase_credentials pre-read resolveSettings failed: " //$NON-NLS-1$
                + msg(e));
        }

        try
        {
            mgr.updateSettings(res.infobase,
                new InfobaseAccessSettings(access, userName, password, additionalParams));
        }
        catch (Throwable e)
        {
            r.error = "Failed to store the credentials in secure storage: " + msg(e); //$NON-NLS-1$
            r.failureKind = ErrorTags.WRITE_FAILED.wire();
            return r;
        }

        // In-process readback to confirm what was persisted.
        try
        {
            IInfobaseAccessSettings back = mgr.resolveSettings(res.infobase);
            r.ok = true;
            r.access = back.access() != null ? back.access().getName() : null;
            r.userName = back.userName();
            r.passwordStored = back.password() != null && !back.password().isEmpty();
            r.additionalParameters = back.additionalProperties();
        }
        catch (Throwable e)
        {
            // The write succeeded; only the confirmation read failed.
            r.ok = true;
            r.failureKind = ErrorTags.READBACK_FAILED.wire();
            Activator.logWarning("set_infobase_credentials readback resolveSettings failed: " //$NON-NLS-1$
                + msg(e));
        }
        return r;
    }

    /**
     * Primes the shared Equinox secure-storage root so encrypt/decrypt does not
     * post a modal master-password dialog. Returns null on success or an
     * actionable message when the key cannot be acquired without a prompt.
     */
    public static String primeSecureStorage()
    {
        try
        {
            Map<String, Object> options = new HashMap<>();
            options.put(IProviderHints.PROMPT_USER, Boolean.FALSE);
            ISecurePreferences prefs = SecurePreferencesFactory.open(null, options);
            if (prefs == null)
            {
                return "Secure storage is unavailable (SecurePreferencesFactory.open returned null)."; //$NON-NLS-1$
            }
            ISecurePreferences probe = prefs.node(PRIME_NODE);
            // encrypt=true forces the key provider (WinCrypto / DPAPI on Windows)
            // to supply the module key silently and cache it on the shared root.
            // This put+flush IS the priming; everything after is cleanup.
            probe.putBoolean("p", true, true); //$NON-NLS-1$
            probe.flush();
            // Cleanup is best-effort - priming already succeeded above, so a
            // cleanup failure must not turn a working prime into a false error.
            try
            {
                probe.removeNode();
                prefs.flush();
            }
            catch (Throwable cleanup)
            {
                Activator.logWarning("secure-storage prime cleanup failed (harmless): " //$NON-NLS-1$
                    + msg(cleanup));
            }
            return null;
        }
        catch (Throwable e)
        {
            return "Secure storage could not be initialized without an interactive " //$NON-NLS-1$
                + "master-password prompt (" + msg(e) + "). On Windows this uses DPAPI " //$NON-NLS-1$ //$NON-NLS-2$
                + "(WinCrypto); ensure the Eclipse keyring is accessible to this user."; //$NON-NLS-1$
        }
    }

    private static IInfobaseAccessManager resolveManager()
    {
        Activator a = Activator.getDefault();
        return a != null ? a.getInfobaseAccessManager() : null;
    }

    private static InfobaseResolution resolveInfobase(IProject project, String applicationId)
    {
        InfobaseResolution r = new InfobaseResolution();
        Activator a = Activator.getDefault();
        IApplicationManager appMgr = a != null ? a.getApplicationManager() : null;
        if (appMgr == null)
        {
            r.error = "IApplicationManager is not available on this EDT runtime."; //$NON-NLS-1$
            return r;
        }
        IApplication app;
        try
        {
            if (applicationId != null && !applicationId.isEmpty())
            {
                Optional<IApplication> opt = appMgr.getApplication(project, applicationId);
                app = opt != null ? opt.orElse(null) : null;
                if (app == null)
                {
                    r.error = "No application '" + applicationId + "' in project '" //$NON-NLS-1$ //$NON-NLS-2$
                        + project.getName() + "'. Use get_applications to list ids."; //$NON-NLS-1$
                    return r;
                }
            }
            else
            {
                List<IApplication> apps = appMgr.getApplications(project);
                if (apps == null || apps.isEmpty())
                {
                    r.error = "Project '" + project.getName() + "' has no infobase " //$NON-NLS-1$ //$NON-NLS-2$
                        + "application. Configure one in the project's launch settings."; //$NON-NLS-1$
                    return r;
                }
                if (apps.size() > 1)
                {
                    r.error = "Project '" + project.getName() + "' has " + apps.size() //$NON-NLS-1$ //$NON-NLS-2$
                        + " applications; pass applicationId (see get_applications)."; //$NON-NLS-1$
                    return r;
                }
                app = apps.get(0);
                applicationId = app.getId();
            }
            if (!(app instanceof IInfobaseApplication))
            {
                r.notInfobaseApp = true;
                r.error = "No application named '" + applicationId + "' is not an infobase " //$NON-NLS-1$ //$NON-NLS-2$
                    + "application; credentials apply only to infobase applications."; //$NON-NLS-1$
                return r;
            }
            InfobaseReference ib = ((IInfobaseApplication) app).getInfobase();
            if (ib == null)
            {
                r.error = "The infobase application has no infobase reference."; //$NON-NLS-1$
                return r;
            }
            r.infobase = ib;
            r.applicationId = applicationId;
            r.infobaseName = ib.getName();
            return r;
        }
        catch (Exception e)
        {
            r.error = "Failed to resolve the infobase application: " + msg(e); //$NON-NLS-1$
            return r;
        }
    }

    private static String msg(Throwable e)
    {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
