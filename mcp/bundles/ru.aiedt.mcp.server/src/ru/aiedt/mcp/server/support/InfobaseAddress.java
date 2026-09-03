/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com._1c.g5.wiring.ServiceAccess;

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
    public record Address(String connectionString, String name, InfobaseReference infobase,
        IProject owner)
    {
        /** @return whether an address was found */
        public boolean found()
        {
            return connectionString != null && !connectionString.isEmpty();
        }
    }

    /** Nothing found, so a caller reading this cannot mistake it for an address. */
    private static final Address NOWHERE = new Address(null, null, null, null);

    /**
     * An infobase and the project it belongs to.
     * <p>
     * The two travel together because they are not always the same project: an extension has
     * no infobase of its own and belongs to the configuration above it, and EDT does not know
     * the extension paired with the configuration infobase.
     * </p>
     *
     * @param infobase the infobase.
     * @param owner the project it belongs to.
     */
    private record Resolved(InfobaseReference infobase, IProject owner)
    {
    }

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
        Resolved resolved = infobaseOf(project);
        if (resolved == null)
        {
            return NOWHERE;
        }
        InfobaseReference infobase = resolved.infobase();
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
        return new Address("File=\"" + file.trim() + "\";", infobase.getName(), //$NON-NLS-1$ //$NON-NLS-2$
            infobase, resolved.owner());
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
    private static Resolved infobaseOf(IProject project)
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
                return new Resolved(own, project);
            }
            IProject parent = BmCommonModuleGuards.parentProjectOf(project);
            if (parent == null || !parent.exists() || !parent.isOpen())
            {
                return null;
            }
            InfobaseReference above = infobaseOfDefaultApplication(manager, parent);
            return above == null ? null : new Resolved(above, parent);
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

    /**
     * EDT let go of the project infobase, and will take it back when this is closed.
     * <p>
     * A file infobase admits one owner. While EDT holds the designer session on it, a client
     * started against the same infobase does not connect: the process lives out its whole
     * deadline without a single event reaching the infobase log, and what the caller sees is a
     * run that timed out rather than one that never began.
     * </p>
     * <p>
     * The infobase is taken back only when this released it. One the user had already
     * disconnected stays disconnected, because connecting it back would change what they set
     * up, and a disconnect of an already disconnected infobase succeeds silently and so cannot
     * tell the two apart on its own.
     * </p>
     */
    public static final class Hold implements AutoCloseable
    {
        private final IProject project;

        private final InfobaseReference infobase;

        private final boolean released;

        private final String why;

        private boolean takenBack;

        private Hold(IProject project, InfobaseReference infobase, boolean released, String why)
        {
            this.project = project;
            this.infobase = infobase;
            this.released = released;
            this.why = why;
        }

        /**
         * Why nothing was released, for an answer that would otherwise blame the deadline.
         *
         * @return the reason, or <code>null</code> when there is nothing to say
         */
        public String why()
        {
            return why;
        }

        /**
         * Whether EDT actually let go, so a caller can say why a launch may still be blocked.
         *
         * @return <code>true</code> when this released the infobase
         */
        public boolean released()
        {
            return released;
        }

        @Override
        public void close()
        {
            // Once. A second close would connect an infobase the user may have disconnected
            // by hand in between, undoing what they did.
            if (!released || takenBack)
            {
                return;
            }
            takenBack = true;
            IInfobaseSynchronizationManager manager =
                ServiceAccess.get(IInfobaseSynchronizationManager.class);
            if (manager == null)
            {
                return;
            }
            try
            {
                manager.connectInfobase(project, infobase, new NullProgressMonitor());
            }
            catch (Throwable failed)
            {
                Activator.logWarning("the infobase was not taken back and may show as " //$NON-NLS-1$
                    + "disconnected in EDT - reconnect it there: " + failed); //$NON-NLS-1$
            }
        }
    }

    /**
     * Has EDT let go of the project infobase for the duration of a client launch.
     *
     * @param project the project whose infobase the client opens; may be <code>null</code>.
     * @return the hold, which takes the infobase back when closed
     */
    public static Hold release(Address address)
    {
        if (address == null || address.infobase() == null || address.owner() == null)
        {
            return new Hold(null, null, false, null);
        }
        IProject owner = address.owner();
        InfobaseReference infobase = address.infobase();
        IInfobaseSynchronizationManager manager =
            ServiceAccess.get(IInfobaseSynchronizationManager.class);
        if (manager == null)
        {
            return new Hold(owner, infobase, false,
                "EDT has no infobase synchronization service, so the infobase it holds stays " //$NON-NLS-1$
                    + "held"); //$NON-NLS-1$
        }
        try
        {
            boolean held = manager.isConnected(owner, infobase);
            manager.disconnectInfobase(owner, infobase, false, true, new NullProgressMonitor());
            return new Hold(owner, infobase, held, null);
        }
        catch (Throwable failed)
        {
            return new Hold(owner, infobase, false,
                "the infobase EDT holds was not released: " + oneLine(failed)); //$NON-NLS-1$
        }
    }

    /**
     * What went wrong, on one line, for an answer that has room for a clause.
     *
     * @param failed the failure.
     * @return its message
     */
    private static String oneLine(Throwable failed)
    {
        String said = failed.getMessage();
        if (said == null || said.trim().isEmpty())
        {
            said = failed.getClass().getSimpleName();
        }
        return said.replace("\n", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
