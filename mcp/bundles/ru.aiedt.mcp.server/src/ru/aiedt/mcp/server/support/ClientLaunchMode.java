/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.emf.transaction.util.TransactionUtil;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.common.ClientRunMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.aiedt.mcp.server.Activator;

/**
 * Which client a launch starts and in which run mode, decided from the caller's arguments and
 * the configuration's default run mode.
 *
 * <p>A configuration whose default run mode is the ordinary application shows its forms in the
 * thick client and nowhere else, so its launch takes the thick client unless the caller asks for
 * the managed mode. The thick client is started by EDT with {@code /RunModeManagedApplication}
 * on its command line, and the platform takes the last run-mode flag it is given (measured on
 * 8.3.27: {@code /RunModeManagedApplication /RunModeOrdinaryApplication} opens the ordinary
 * application, the reverse order the managed one). The infobase's additional launch parameters
 * are appended after EDT's own flags, so {@value #ORDINARY_FLAG} there is what makes the thick
 * client open the ordinary application - and its absence what keeps a managed launch managed.
 * {@link #reconcileFlag} keeps the parameter in step with the launch: present for a thick client
 * in the ordinary mode, absent otherwise.</p>
 *
 * <p>The parameter is changed on the infobase reference EDT holds in memory and is not saved to
 * the infobase list on disk. Saving the list ({@code IInfobaseManager.update}) makes EDT reload
 * it, and the reload announces every application as deleted, on which EDT removes the
 * application id from every launch configuration that names it (measured: the configuration
 * files lose {@code ATTR_APPLICATION_ID} and the next launch creates a new configuration). The
 * launch delegate reads the reference in memory, so the change reaches the command line without
 * that. The list on disk, shared with the platform launcher, stays as the user keeps it. The
 * change is made inside a recording command when the list has an editing domain - a change
 * made outside one is refused by the domain - and directly when it has none.</p>
 */
public final class ClientLaunchMode
{
    /** The platform flag that starts the thick client in the ordinary application mode. */
    public static final String ORDINARY_FLAG = "/RunModeOrdinaryApplication"; //$NON-NLS-1$

    /** Run mode: the ordinary application (thick client only). */
    public static final String ORDINARY = "ordinary"; //$NON-NLS-1$

    /** Run mode: the managed application. */
    public static final String MANAGED = "managed"; //$NON-NLS-1$

    /** Client type: the thin client. */
    public static final String THIN = "thin"; //$NON-NLS-1$

    /** Client type: the thick client. */
    public static final String THICK = "thick"; //$NON-NLS-1$

    /** Client type: the web client. */
    public static final String WEB = "web"; //$NON-NLS-1$

    /** Client type reported for a configuration that lets EDT choose. */
    public static final String AUTO = "auto"; //$NON-NLS-1$

    /** The client that starts: {@link #THIN}, {@link #THICK}, {@link #WEB} or {@link #AUTO}. */
    public final String clientType;

    /**
     * The client type attribute to put on the launch, or {@code null} when the configuration's
     * own choice stands.
     */
    public final String clientTypeId;

    /** Where the client came from, for the answer. */
    public final String clientTypeSource;

    /** The run mode: {@link #ORDINARY} or {@link #MANAGED}. */
    public final String runMode;

    /** Where the run mode came from, for the answer. */
    public final String runModeSource;

    /** Why the request cannot be honored, or {@code null}. */
    public final String refusal;

    private ClientLaunchMode(String clientType, String clientTypeId, String clientTypeSource, String runMode,
        String runModeSource, String refusal)
    {
        this.clientType = clientType;
        this.clientTypeId = clientTypeId;
        this.clientTypeSource = clientTypeSource;
        this.runMode = runMode;
        this.runModeSource = runModeSource;
        this.refusal = refusal;
    }

    /**
     * Decides the client and the run mode.
     *
     * @param clientTypeArg the caller's {@code clientType}, or {@code null}
     * @param runModeArg the caller's {@code runMode}, or {@code null}
     * @param projectRunMode the configuration's default run mode as {@link #projectRunMode}
     *        reports it, or {@code null} when it is not known
     * @param configuredClientTypeId the client type of an existing launch configuration, or
     *        {@code null} when the launch configuration is being created or lets EDT choose
     * @return the decision; {@link #refusal} is set when the arguments contradict each other or
     *         name nothing known
     */
    public static ClientLaunchMode decide(String clientTypeArg, String runModeArg, String projectRunMode,
        String configuredClientTypeId)
    {
        String clientArg = normalize(clientTypeArg);
        String modeArg = normalize(runModeArg);
        if (clientArg != null && !THIN.equals(clientArg) && !THICK.equals(clientArg) && !WEB.equals(clientArg))
        {
            return refused("clientType must be thin, thick or web; '" + clientTypeArg + "' is none of them."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (modeArg != null && !ORDINARY.equals(modeArg) && !MANAGED.equals(modeArg))
        {
            return refused("runMode must be ordinary or managed; '" + runModeArg + "' is neither."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String runMode;
        String runModeSource;
        if (modeArg != null)
        {
            runMode = modeArg;
            runModeSource = "the runMode argument"; //$NON-NLS-1$
        }
        else if (ORDINARY.equals(projectRunMode))
        {
            runMode = ORDINARY;
            runModeSource = "the configuration's default run mode"; //$NON-NLS-1$
        }
        else
        {
            runMode = MANAGED;
            runModeSource = MANAGED.equals(projectRunMode) ? "the configuration's default run mode" //$NON-NLS-1$
                : "managed, the run mode of a configuration that names none"; //$NON-NLS-1$
        }
        if (ORDINARY.equals(runMode) && clientArg != null && !THICK.equals(clientArg))
        {
            return refused("The ordinary application runs in the thick client only; clientType=" + clientArg //$NON-NLS-1$
                + " cannot show its forms. Pass clientType=thick, or runMode=managed to start the " //$NON-NLS-1$
                + clientArg + " client in the managed mode. Nothing was launched."); //$NON-NLS-1$
        }
        if (clientArg != null)
        {
            return new ClientLaunchMode(clientArg, idOf(clientArg), "the clientType argument", runMode, //$NON-NLS-1$
                runModeSource, null);
        }
        if (ORDINARY.equals(runMode))
        {
            boolean already = LaunchConfigAccess.CLIENT_TYPE_THICK.equals(configuredClientTypeId);
            return new ClientLaunchMode(THICK, already ? null : LaunchConfigAccess.CLIENT_TYPE_THICK,
                already ? "the launch configuration" : "the ordinary application runs in the thick client", //$NON-NLS-1$ //$NON-NLS-2$
                runMode, runModeSource, null);
        }
        if (configuredClientTypeId != null)
        {
            return new ClientLaunchMode(nameOf(configuredClientTypeId), null, "the launch configuration", //$NON-NLS-1$
                runMode, runModeSource, null);
        }
        return new ClientLaunchMode(THIN, LaunchConfigAccess.CLIENT_TYPE_THIN, "thin, the client of a new launch " //$NON-NLS-1$
            + "configuration", runMode, runModeSource, null); //$NON-NLS-1$
    }

    /**
     * Whether the infobase's additional parameters must carry {@value #ORDINARY_FLAG} for this
     * launch: a thick client in the ordinary mode.
     *
     * @return {@code true} when the flag is wanted
     */
    public boolean wantsOrdinaryFlag()
    {
        return THICK.equals(clientType) && ORDINARY.equals(runMode);
    }

    /**
     * The default run mode of a project's configuration.
     *
     * @param project the project
     * @return {@link #ORDINARY}, {@link #MANAGED} (an {@code Auto} default counts as managed), or
     *         {@code null} when the configuration cannot be read
     */
    public static String projectRunMode(IProject project)
    {
        try
        {
            IConfigurationProvider provider = Activator.getDefault() == null ? null
                : Activator.getDefault().getConfigurationProvider();
            Configuration configuration = provider == null ? null : provider.getConfiguration(project);
            if (configuration == null)
            {
                return null;
            }
            return configuration.getDefaultRunMode() == ClientRunMode.ORDINARY_APPLICATION ? ORDINARY : MANAGED;
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("The default run mode of " + project.getName() + " was not read: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
            return null;
        }
    }

    /**
     * The additional launch parameters with {@value #ORDINARY_FLAG} present or absent, the other
     * parameters untouched.
     *
     * @param parameters the parameters as stored, {@code null} for none
     * @param wanted whether the flag has to be there
     * @return the parameters to store; {@code null} stays {@code null} when nothing is added
     */
    public static String withOrdinaryFlag(String parameters, boolean wanted)
    {
        List<String> tokens = new ArrayList<>();
        boolean present = false;
        if (parameters != null)
        {
            for (String token : parameters.trim().split("\\s+")) //$NON-NLS-1$
            {
                if (token.isEmpty())
                {
                    continue;
                }
                if (token.equalsIgnoreCase(ORDINARY_FLAG))
                {
                    present = true;
                    continue;
                }
                tokens.add(token);
            }
        }
        if (wanted)
        {
            tokens.add(ORDINARY_FLAG);
        }
        else if (!present)
        {
            return parameters;
        }
        return String.join(" ", tokens); //$NON-NLS-1$
    }

    /** Where a change of the flag lives, for the answer. */
    public static final String FLAG_SCOPE = "the infobase reference EDT holds for this session; the infobase list " //$NON-NLS-1$
        + "on disk is untouched"; //$NON-NLS-1$

    /**
     * Puts the infobase's additional launch parameters in step with a launch: the flag present
     * when the launch wants it, absent when it does not. The change is made on the reference EDT
     * holds in memory and lasts for the EDT session; the infobase list on disk is not written.
     *
     * @param application the application the launch starts
     * @param wanted whether the flag has to be there
     * @return what happened: {@code added}, {@code removed}, {@code present}, {@code absent}, or
     *         {@code not applied: <reason>}
     */
    public static String reconcileFlag(IApplication application, boolean wanted)
    {
        if (!(application instanceof IInfobaseApplication))
        {
            return "not applied: the application is not an infobase application"; //$NON-NLS-1$
        }
        InfobaseReference infobase = ((IInfobaseApplication)application).getInfobase();
        if (infobase == null)
        {
            return "not applied: the application has no infobase reference"; //$NON-NLS-1$
        }
        String before = infobase.getAdditionalParameters();
        String after = withOrdinaryFlag(before, wanted);
        if (Objects.equals(before == null ? "" : before.trim(), after == null ? "" : after.trim())) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return wanted ? "present" : "absent"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        IInfobaseManager manager = Activator.getDefault() == null ? null : Activator.getDefault().getInfobaseManager();
        if (manager == null)
        {
            return "not applied: the infobase manager is unavailable"; //$NON-NLS-1$
        }
        try
        {
            Resource list = manager.getInfobasesResource();
            TransactionalEditingDomain domain = list == null ? null : TransactionUtil.getEditingDomain(list);
            if (domain != null)
            {
                RecordingCommand command = new RecordingCommand(domain, "Run mode of " + infobase.getName()) //$NON-NLS-1$
                {
                    @Override
                    protected void doExecute()
                    {
                        infobase.setAdditionalParameters(after);
                    }
                };
                if (!command.canExecute())
                {
                    return "not applied: the infobase list refuses the change"; //$NON-NLS-1$
                }
                domain.getCommandStack().execute(command);
            }
            else
            {
                infobase.setAdditionalParameters(after);
            }
        }
        catch (RuntimeException e)
        {
            Activator.logError("The additional parameters of infobase " + infobase.getName() //$NON-NLS-1$
                + " were not written", e); //$NON-NLS-1$
            return "not applied: " + TextSuggest.safeMessage(e); //$NON-NLS-1$
        }
        // A command stack logs a failure inside the command instead of throwing it, so the
        // outcome is read back rather than assumed.
        String now = infobase.getAdditionalParameters();
        if (!Objects.equals(now == null ? "" : now.trim(), after == null ? "" : after.trim())) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "not applied: the infobase list kept " + (now == null || now.isEmpty() ? "no parameters" : now); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return wanted ? "added" : "removed"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The additional launch parameters of an application's infobase, for the answer.
     *
     * @param application the application
     * @return the parameters, or {@code null} when the application has no infobase
     */
    public static String additionalParametersOf(IApplication application)
    {
        if (!(application instanceof IInfobaseApplication))
        {
            return null;
        }
        InfobaseReference infobase = ((IInfobaseApplication)application).getInfobase();
        return infobase == null ? null : infobase.getAdditionalParameters();
    }

    /**
     * The short name of a client type attribute value.
     *
     * @param clientTypeId the attribute value
     * @return {@link #THIN}, {@link #THICK}, {@link #WEB}, or {@link #AUTO} for anything else
     */
    public static String nameOf(String clientTypeId)
    {
        if (LaunchConfigAccess.CLIENT_TYPE_THIN.equals(clientTypeId))
        {
            return THIN;
        }
        if (LaunchConfigAccess.CLIENT_TYPE_THICK.equals(clientTypeId))
        {
            return THICK;
        }
        if (LaunchConfigAccess.CLIENT_TYPE_WEB.equals(clientTypeId))
        {
            return WEB;
        }
        return AUTO;
    }

    private static String idOf(String clientType)
    {
        switch (clientType)
        {
        case THICK:
            return LaunchConfigAccess.CLIENT_TYPE_THICK;
        case WEB:
            return LaunchConfigAccess.CLIENT_TYPE_WEB;
        default:
            return LaunchConfigAccess.CLIENT_TYPE_THIN;
        }
    }

    private static ClientLaunchMode refused(String refusal)
    {
        return new ClientLaunchMode(null, null, null, null, null, refusal);
    }

    private static String normalize(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
