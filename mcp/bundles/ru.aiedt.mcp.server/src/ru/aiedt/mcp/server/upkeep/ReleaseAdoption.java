/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.upkeep;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.engine.IProvisioningPlan;
import org.eclipse.equinox.p2.engine.ProvisioningContext;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.operations.ProvisioningJob;
import org.eclipse.equinox.p2.operations.ProvisioningSession;
import org.eclipse.equinox.p2.operations.UpdateOperation;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;

import ru.aiedt.mcp.server.Activator;

/**
 * Puts a newer build of this plugin into the running IDE's profile.
 * <p>
 * <b>Everything here is narrowed to this plugin and this site, deliberately and more than once.</b>
 * The update is expressed against the one unit the profile holds for us; the context is pinned to
 * the configured address; both kinds of repository reference are switched off; the resulting plan is
 * read back and refused if it would touch anything outside a fixed list; and the version about to be
 * installed is compared against the one the caller approved. Any of those alone would leave a gap -
 * a bare update operation takes every root in the profile, which here means EDT itself.
 * </p>
 * <p>
 * <b>What this is not.</b> None of it is a barrier against a hostile site. The one real boundary is
 * the user's choice of address: whoever controls it decides what would be installed. The narrowing
 * above limits surprise and accidental reach - an update that quietly pulled in something else, or a
 * site that changed between the answer and the install - and nothing more.
 * </p>
 * <p>
 * <b>Trust is not touched here.</b> p2 asks the IDE's own trust service about anything it cannot
 * verify, and this class leaves that alone: substituting a silent one would answer for every other
 * provisioning operation in the IDE, and setting the engine's trust-always flag writes a persisted,
 * profile-wide setting that a crash mid-install would leave switched on for good. Until releases are
 * signed by a key this plugin vouches for, an install of unverifiable artifacts reaches the IDE's
 * ordinary trust prompt and waits for a person. Installing from the status bar, where somebody is
 * looking, is therefore the route that works today.
 * </p>
 * <p>
 * <b>There is a launch-time setting that removes the prompt, and it is not ours to set.</b> p2 reads
 * {@code eclipse.p2.unsignedPolicy} from the framework at startup - {@code allow} short-circuits the
 * whole check - so an IDE owner who wants unattended installs can put it in {@code eclipse.ini}. It
 * cannot be set from here even in principle: it is read once, at launch, from the framework rather
 * than from anything this code can reach. That is the right shape for it. Switching off signature
 * checking for every provisioning operation in an IDE is a decision for whoever owns that IDE, taken
 * in their own configuration file, not something a plugin arranges on their behalf.
 * </p>
 * <p>
 * <b>The shared repository list is left as it was found.</b> Checking runs on a private agent and
 * never touches it, but installing has to go through the current agent's managers, and those
 * remember every address they load. Whether the site was already known and enabled is recorded
 * before the operation and restored after it, on every exit. An address the user had added
 * themselves is never removed.
 * </p>
 */
public final class ReleaseAdoption
{
    /**
     * The units an update of this plugin may add or remove.
     * <p>
     * Three, not one: naming only the feature group would refuse the ordinary shape of our own
     * update, because the group requires its jar and both bring the bundle.
     * </p>
     */
    static final Set<String> ALLOWED_UNITS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        ReleaseFeed.FEATURE_IU, "ru.aiedt.mcp.server.feature.feature.jar", //$NON-NLS-1$
        "ru.aiedt.mcp.server"))); //$NON-NLS-1$

    /**
     * Reference-following for artifact repositories. p2 has no public constant for it - only
     * {@code FOLLOW_REPOSITORY_REFERENCES} and {@code CHECK_AUTHORITIES} are published - so the key
     * is written out here. Both default to off in the p2 that ships with EDT 2026.1; setting them
     * explicitly costs nothing and keeps the intent true if a later version flips a default.
     */
    private static final String FOLLOW_ARTIFACT_REPOSITORY_REFERENCES =
        "org.eclipse.equinox.p2.director.followArtifactRepositoryReferences"; //$NON-NLS-1$

    private static final String FALSE = "false"; //$NON-NLS-1$

    private ReleaseAdoption()
    {
    }

    /**
     * The result of an attempt, in the terms the caller reports.
     */
    public static final class Outcome
    {
        private final boolean applied;

        private final String problem;

        private final Version version;

        private Outcome(boolean applied, String problem, Version version)
        {
            this.applied = applied;
            this.problem = problem;
            this.version = version;
        }

        static Outcome applied(Version version)
        {
            return new Outcome(true, null, version);
        }

        static Outcome refused(String problem)
        {
            return new Outcome(false, problem, null);
        }

        /**
         * @return <code>true</code> when the profile now holds the new version
         */
        public boolean applied()
        {
            return applied;
        }

        /**
         * @return why nothing was installed, or <code>null</code> when something was
         */
        public String problem()
        {
            return problem;
        }

        /**
         * @return the version now in the profile, or <code>null</code> when nothing was installed
         */
        public Version version()
        {
            return version;
        }
    }

    /**
     * Installs the given version of this plugin from the given site.
     *
     * @param site the site the offer came from, already accepted by {@link UpkeepPolicy}
     * @param expected the exact version the caller approved
     * @param monitor progress monitor, may be <code>null</code>
     * @return what happened, never <code>null</code>
     * @throws OperationCanceledException when the monitor was cancelled
     */
    public static Outcome install(URI site, Version expected, IProgressMonitor monitor)
    {
        if (site == null || expected == null)
        {
            return Outcome.refused("no site or no version to install"); //$NON-NLS-1$
        }
        IProvisioningAgent agent = ReleaseFeed.currentAgent();
        IInstallableUnit installed = ReleaseFeed.installedUnit();
        if (agent == null || installed == null)
        {
            return Outcome.refused(
                "this installation is not managed by p2, so it cannot be updated from a site"); //$NON-NLS-1$
        }

        IMetadataRepositoryManager metadata = agent.getService(IMetadataRepositoryManager.class);
        IArtifactRepositoryManager artifacts = agent.getService(IArtifactRepositoryManager.class);
        if (metadata == null || artifacts == null)
        {
            return Outcome.refused("provisioning is unavailable in this installation"); //$NON-NLS-1$
        }

        KnownState metadataState = KnownState.of(metadata, site);
        KnownState artifactState = KnownState.of(artifacts, site);
        try
        {
            // Read the site afresh. Pinning the context to one address does not make its contents
            // current: a long-lived manager can answer from a copy loaded earlier in the session,
            // and then the check would see a new version while the install insists there is nothing
            // to do.
            metadata.refreshRepository(site, monitor);
            artifacts.refreshRepository(site, monitor);

            UpdateOperation operation =
                new UpdateOperation(new ProvisioningSession(agent), List.of(installed));
            operation.setProvisioningContext(pinnedContext(agent, site));

            IStatus resolution = operation.resolveModal(monitor);
            if (resolution == null || resolution.getSeverity() == IStatus.ERROR)
            {
                // Verbatim: this text is the only thing that tells the user which of the many
                // reasons a resolution can fail applies to them.
                return Outcome.refused("the update could not be worked out: " //$NON-NLS-1$
                    + operation.getResolutionDetails());
            }

            IProvisioningPlan plan = operation.getProvisioningPlan();
            if (plan == null)
            {
                return Outcome.refused("the update produced no plan to carry out"); //$NON-NLS-1$
            }
            String strays = inspect(plan);
            if (strays != null)
            {
                return Outcome.refused(strays);
            }
            Version offered = versionBeingInstalled(plan);
            if (offered == null)
            {
                return Outcome.refused("the plan does not install " + ReleaseFeed.FEATURE_IU); //$NON-NLS-1$
            }
            if (!offered.equals(expected))
            {
                // The site changed between the answer and the confirmation. Approving one version
                // and installing another is not a detail - the whole point of confirming is that
                // the user chose what goes in.
                return Outcome.refused("the site now offers " + offered + " rather than the " //$NON-NLS-1$ //$NON-NLS-2$
                    + expected + " that was approved; nothing was installed"); //$NON-NLS-1$
            }

            ProvisioningJob job = operation.getProvisioningJob(monitor);
            if (job == null)
            {
                return Outcome.refused("the update could not be turned into work to carry out"); //$NON-NLS-1$
            }
            // Modally, on this thread, rather than scheduled: a job of p2's own making belongs to no
            // family of ours and could outlive the bundle with nothing able to cancel it.
            IStatus result = job.runModal(monitor);
            if (result != null && result.getSeverity() == IStatus.ERROR)
            {
                return Outcome.refused("the update failed to apply: " + result.getMessage()); //$NON-NLS-1$
            }
            if (result != null && result.getSeverity() == IStatus.CANCEL)
            {
                // Cancelled is not applied, and the difference matters more here than anywhere
                // else: reporting it as done publishes a pending restart and restarts the IDE for
                // an update that may never have reached the profile.
                return Outcome.refused("the update was cancelled; nothing was installed"); //$NON-NLS-1$
            }
            // A warning is left to stand. Provisioning routinely reports one for things that did
            // not stop the work, and refusing on them would fail installs that actually succeeded.
            return Outcome.applied(offered);
        }
        catch (OperationCanceledException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            Activator.logError("AI-EDT update from " + site + " failed", e); //$NON-NLS-1$ //$NON-NLS-2$
            return Outcome.refused(describe(e));
        }
        finally
        {
            metadataState.restore(metadata);
            artifactState.restore(artifacts);
        }
    }

    /**
     * Builds a context that can only reach the configured address.
     *
     * @param agent the current agent
     * @param site the configured site
     * @return the context
     */
    private static ProvisioningContext pinnedContext(IProvisioningAgent agent, URI site)
    {
        ProvisioningContext context = new ProvisioningContext(agent);
        context.setMetadataRepositories(site);
        context.setArtifactRepositories(site);
        // Both, not one. Metadata references and artifact-repository references are separate
        // mechanisms: leaving the second alone would let a site move the download of what actually
        // gets installed somewhere else while the address restriction still looked to be in force.
        context.setProperty(ProvisioningContext.FOLLOW_REPOSITORY_REFERENCES, FALSE);
        context.setProperty(FOLLOW_ARTIFACT_REPOSITORY_REFERENCES, FALSE);
        return context;
    }

    /**
     * Checks that a plan changes nothing beyond this plugin.
     * <p>
     * The list of units handed to the operation is only the root; p2 works out the full transitive
     * plan from there, and that is where something unexpected would appear. The nested installer
     * plan is examined too - p2 carries it out separately, so leaving it unread would be a hole in
     * exactly the place this is closing.
     * </p>
     *
     * @param plan the resolved plan
     * @return a description of what it wanted to change, or <code>null</code> when it is confined
     *         to this plugin
     */
    static String inspect(IProvisioningPlan plan)
    {
        Set<String> strays = new LinkedHashSet<>();
        collectStrays(plan, strays);
        if (strays.isEmpty())
        {
            return null;
        }
        List<String> named = new ArrayList<>(strays);
        Collections.sort(named);
        return "the update would also change " + String.join(", ", named) //$NON-NLS-1$ //$NON-NLS-2$
            + ", which is outside this plugin; nothing was installed"; //$NON-NLS-1$
    }

    private static void collectStrays(IProvisioningPlan plan, Set<String> strays)
    {
        if (plan == null)
        {
            return;
        }
        collectStrays(plan.getAdditions(), strays);
        collectStrays(plan.getRemovals(), strays);
        collectStrays(plan.getInstallerPlan(), strays);
    }

    static void collectStrays(org.eclipse.equinox.p2.query.IQueryable<IInstallableUnit> units,
        Set<String> strays)
    {
        if (units == null)
        {
            return;
        }
        IQueryResult<IInstallableUnit> found = units.query(QueryUtil.createIUAnyQuery(), null);
        for (Iterator<IInstallableUnit> it = found.iterator(); it.hasNext();)
        {
            IInstallableUnit unit = it.next();
            if (!ALLOWED_UNITS.contains(unit.getId()))
            {
                strays.add(unit.getId() + " " + unit.getVersion()); //$NON-NLS-1$
            }
        }
    }

    /**
     * The version of this plugin's feature that the plan adds.
     *
     * @param plan the resolved plan
     * @return the version, or <code>null</code> when the plan adds no such unit
     */
    private static Version versionBeingInstalled(IProvisioningPlan plan)
    {
        if (plan.getAdditions() == null)
        {
            return null;
        }
        IQueryResult<IInstallableUnit> added =
            plan.getAdditions().query(QueryUtil.createIUQuery(ReleaseFeed.FEATURE_IU), null);
        Iterator<IInstallableUnit> units = added.iterator();
        return units.hasNext() ? units.next().getVersion() : null;
    }

    /**
     * Whether a repository manager already knew an address, and whether it was switched on.
     * <p>
     * Recorded before the operation and put back after it. Without this, the first install would
     * leave our site in the list every other p2 operation in the IDE consults - including Oomph's.
     * A site the user had added themselves is left exactly as it was.
     * </p>
     */
    private static final class KnownState
    {
        private final boolean known;

        private final boolean enabled;

        private final URI site;

        private KnownState(URI site, boolean known, boolean enabled)
        {
            this.site = site;
            this.known = known;
            this.enabled = enabled;
        }

        static KnownState of(org.eclipse.equinox.p2.repository.IRepositoryManager<?> manager, URI site)
        {
            boolean known = manager.contains(site);
            return new KnownState(site, known, known && manager.isEnabled(site));
        }

        void restore(org.eclipse.equinox.p2.repository.IRepositoryManager<?> manager)
        {
            try
            {
                if (!known)
                {
                    manager.removeRepository(site);
                    return;
                }
                if (manager.contains(site) && manager.isEnabled(site) != enabled)
                {
                    manager.setEnabled(site, enabled);
                }
            }
            catch (RuntimeException e)
            {
                // Tidying up must not turn a finished install into a failure, nor mask one.
                Activator.logWarning("AI-EDT could not restore the repository list for " + site //$NON-NLS-1$
                    + ": " + e); //$NON-NLS-1$
            }
        }
    }

    private static String describe(Throwable e)
    {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
