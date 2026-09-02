/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.List;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

import ru.aiedt.mcp.server.Activator;

/**
 * Where a project's infobase is, in the words a 1C client understands.
 * <p>
 * A caller that has EDT open already told it which infobase the project belongs to - it is what
 * update_database writes into and what start_client launches. Asking that caller to type a
 * connection string as well makes them repeat what the environment knows, and a typed string can
 * name a different infobase than the one the project is bound to.
 * </p>
 */
public final class InfobaseAddress
{
    private InfobaseAddress() {}

    /**
     * The connection string of a project's infobase.
     *
     * @param project the project, possibly <code>null</code>.
     * @return the connection string for a file infobase, or <code>null</code> when the project has
     *         no infobase application, when EDT is not up, or when the infobase is not a file one -
     *         a server infobase carries credentials this cannot supply
     */
    public static String ofProject(IProject project)
    {
        InfobaseReference infobase = infobaseOf(project);
        if (infobase == null)
        {
            return null;
        }
        IConnectionString connection = infobase.getConnectionString();
        if (!(connection instanceof FileConnectionString))
        {
            return null;
        }
        String file = ((FileConnectionString)connection).getFile();
        if (file == null || file.trim().isEmpty())
        {
            return null;
        }
        return "File=\"" + file.trim() + "\";"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The name of a project's infobase, for an answer that says which one was used.
     *
     * @param project the project, possibly <code>null</code>.
     * @return the name, or <code>null</code>
     */
    public static String nameOfProjectInfobase(IProject project)
    {
        InfobaseReference infobase = infobaseOf(project);
        return infobase == null ? null : infobase.getName();
    }

    private static InfobaseReference infobaseOf(IProject project)
    {
        if (project == null || Activator.getDefault() == null)
        {
            return null;
        }
        IApplicationManager manager = Activator.getDefault().getApplicationManager();
        if (manager == null)
        {
            return null;
        }
        try
        {
            List<IApplication> applications = manager.getApplications(project);
            if (applications == null)
            {
                return null;
            }
            for (IApplication application : applications)
            {
                if (application instanceof IInfobaseApplication)
                {
                    InfobaseReference infobase = ((IInfobaseApplication)application).getInfobase();
                    if (infobase != null)
                    {
                        return infobase;
                    }
                }
            }
        }
        catch (RuntimeException wontSay)
        {
            Activator.logDebug("infobase address: " + wontSay); //$NON-NLS-1$
        }
        return null;
    }
}
