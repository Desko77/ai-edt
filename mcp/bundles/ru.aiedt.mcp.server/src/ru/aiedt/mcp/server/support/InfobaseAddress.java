/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Optional;

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
 * A caller with EDT open has already told it which infobase the project belongs to - the one
 * update_database writes into and start_client launches. Asking that caller to type a connection
 * string as well makes them repeat what the environment holds, and a typed string can name a
 * different infobase than the project is bound to.
 * </p>
 */
public final class InfobaseAddress
{
    private InfobaseAddress() {}

    /**
     * A project's infobase, named two ways at once.
     *
     * @param connectionString what a 1C client is started with, or <code>null</code>.
     * @param name what the infobase is called in EDT, or <code>null</code>.
     */
    public record Address(String connectionString, String name)
    {
        /** @return whether an address was found */
        public boolean found()
        {
            return connectionString != null && !connectionString.isEmpty();
        }
    }

    /** Nothing found, so a caller reading this cannot mistake it for an address. */
    private static final Address NOWHERE = new Address(null, null);

    /**
     * The infobase a project is bound to.
     * <p>
     * Resolved once, so the connection string and the name always describe the same infobase: two
     * independent lookups can disagree if the application list changes between them, and an answer
     * naming one infobase while the run went to another is worse than an answer naming none.
     * </p>
     *
     * @param project the project, possibly <code>null</code>.
     * @return the address; {@link Address#found()} is false when the project has no infobase
     *         application, when EDT is not up, or when the infobase is a server one - that carries
     *         credentials this cannot supply, so the caller has to name it
     */
    public static Address ofProject(IProject project)
    {
        InfobaseReference infobase = infobaseOf(project);
        if (infobase == null)
        {
            return NOWHERE;
        }
        IConnectionString connection = infobase.getConnectionString();
        if (!(connection instanceof FileConnectionString))
        {
            return NOWHERE;
        }
        String file = ((FileConnectionString)connection).getFile();
        if (file == null || file.trim().isEmpty())
        {
            return NOWHERE;
        }
        return new Address("File=\"" + file.trim() + "\";", infobase.getName()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The infobase of a project's default application, or of its parent's.
     * <p>
     * The default one, not the first of the list: a project may have several, and the first is not
     * the one the rest of this plugin works with. An extension project usually has none of its own
     * and belongs to the configuration above it, which is the route the event log and the database
     * updater already take.
     * </p>
     *
     * @param project the project.
     * @return the infobase, or <code>null</code>
     */
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
            InfobaseReference own = infobaseOfDefaultApplication(manager, project);
            if (own != null)
            {
                return own;
            }
            IProject parent = BmCommonModuleGuards.parentProjectOf(project);
            if (parent == null || !parent.exists() || !parent.isOpen())
            {
                return null;
            }
            return infobaseOfDefaultApplication(manager, parent);
        }
        catch (Exception wontSay)
        {
            // Logged rather than swallowed: without it the caller is told the project has no
            // infobase, which is a different thing from "the question could not be asked".
            Activator.logWarning("Could not place the infobase of " + project.getName() //$NON-NLS-1$
                + ": " + wontSay.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static InfobaseReference infobaseOfDefaultApplication(IApplicationManager manager,
        IProject project)
    {
        Optional<IApplication> application = manager.getDefaultApplication(project);
        if (application.isEmpty() || !(application.get() instanceof IInfobaseApplication))
        {
            return null;
        }
        return ((IInfobaseApplication)application.get()).getInfobase();
    }
}
