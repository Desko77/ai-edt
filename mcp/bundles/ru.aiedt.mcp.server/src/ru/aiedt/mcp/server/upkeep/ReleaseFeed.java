/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.upkeep;

import java.net.URI;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.Iterator;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.IProvisioningAgentProvider;
import org.eclipse.equinox.p2.core.UIServices;
import org.eclipse.equinox.p2.engine.IProfile;
import org.eclipse.equinox.p2.engine.IProfileRegistry;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import ru.aiedt.mcp.server.Activator;

/**
 * Answers what this plugin currently is and what a configured site offers.
 * <p>
 * <b>Two agents, and the roles never swap.</b> p2 keeps one provisioning agent per data area, and
 * which one is used decides both correctness and blast radius.
 * </p>
 * <ul>
 * <li><b>The current agent</b> - the one registered as an OSGi service marked
 * {@link IProvisioningAgent#SERVICE_CURRENT} - owns the running IDE's profile, so only it can say
 * what is installed. A privately created agent has its own data area and therefore no profile for
 * this IDE at all; asking it would report the plugin as unmanaged on every machine.</li>
 * <li><b>A private agent</b> reads the remote site. Loading a repository adds its address to the
 * manager's list of known repositories, and that list is persisted in the profile and consulted by
 * the default context of every other p2 operation in the IDE. On a private agent there is nothing
 * to clean up afterwards, not even if the check dies half way. It also lets a silent
 * {@link UIServices} be registered, so a background check physically cannot raise a modal dialog -
 * an agent hands out its own registered services before it looks for a factory.</li>
 * </ul>
 * <p>
 * <b>Which version counts as installed.</b> Not the version this plugin advertises on the wire:
 * that value is deliberately reduced to major.minor.micro, and an empty qualifier sorts before any
 * real one. Comparing it against a repository version would report an update from 3.1.0 to
 * 3.1.0.202608011200 - the very build already running - and would keep doing so for ever. The
 * profile carries the full version, so the profile is the only honest source.
 * </p>
 */
public final class ReleaseFeed
{
    /** The p2 installable unit this plugin is delivered as. */
    public static final String FEATURE_IU = "ru.aiedt.mcp.server.feature.feature.group"; //$NON-NLS-1$

    /** Directory under the plugin state location holding the private agent's data area. */
    private static final String PRIVATE_AGENT_AREA = "upkeep-agent"; //$NON-NLS-1$

    private ReleaseFeed()
    {
    }

    /**
     * Reads the configured site and reports what it means for this installation.
     * <p>
     * The local question is settled first, deliberately: an installation p2 does not manage cannot
     * act on any answer, so there is no reason to send it a single network request.
     * </p>
     *
     * @param site normalized site URI, already accepted by {@link UpkeepPolicy#examineSite}
     * @param monitor progress monitor for the network part, may be <code>null</code>
     * @return the resulting snapshot, never <code>null</code>
     * @throws OperationCanceledException when the monitor was cancelled; the caller abandons its
     *             lease rather than publishing a failure, because nothing failed
     */
    public static ReleaseOffer inspect(URI site, IProgressMonitor monitor)
    {
        if (site == null)
        {
            return ReleaseOffer.dormant();
        }
        long now = System.currentTimeMillis();
        Version installed = installedVersion();
        if (installed == null)
        {
            return ReleaseOffer.unmanaged(site,
                "this installation is not managed by p2, so it cannot be updated from a site"); //$NON-NLS-1$
        }
        IProvisioningAgent agent = createPrivateAgent();
        if (agent == null)
        {
            return ReleaseOffer.failed(site, installed,
                "provisioning is unavailable in this installation", now); //$NON-NLS-1$
        }
        try
        {
            Version offered = offeredVersionVia(agent, site, monitor);
            if (offered == null)
            {
                return ReleaseOffer.failed(site, installed,
                    "the site does not publish " + FEATURE_IU, now); //$NON-NLS-1$
            }
            return UpkeepPolicy.isNewer(installed, offered)
                ? ReleaseOffer.available(site, installed, offered, now)
                : ReleaseOffer.upToDate(site, installed, now);
        }
        catch (OperationCanceledException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            // Everything else becomes a reported failure rather than an escaping stack trace: an
            // unreachable or malformed site is an ordinary outcome of a background check, and the
            // message is the only thing that tells the user which of the two it was.
            Activator.logDebug("upkeep: check of " + site + " failed: " + describe(e)); //$NON-NLS-1$ //$NON-NLS-2$
            return ReleaseOffer.failed(site, installed, describe(e), now);
        }
        finally
        {
            agent.stop();
        }
    }

    /**
     * Returns the provisioning agent of the running IDE, or <code>null</code> when there is none.
     * <p>
     * A missing agent is a legitimate answer rather than a fault: an Equinox launched without a p2
     * data area - a plain test runtime, for instance - registers no agent, and the feature simply
     * has nothing to manage there. The caller reports that state; it does not create an agent of
     * its own for this purpose, because a second agent over the same data area is how profiles get
     * corrupted.
     * </p>
     *
     * @return the current agent, or <code>null</code> when this installation has none
     */
    public static IProvisioningAgent currentAgent()
    {
        BundleContext context = bundleContext();
        if (context == null)
        {
            return null;
        }
        try
        {
            Collection<ServiceReference<IProvisioningAgent>> found = context.getServiceReferences(
                IProvisioningAgent.class,
                "(" + IProvisioningAgent.SERVICE_CURRENT + "=true)"); //$NON-NLS-1$ //$NON-NLS-2$
            if (found == null || found.isEmpty())
            {
                return null;
            }
            return context.getService(found.iterator().next());
        }
        catch (Exception e)
        {
            // An invalid filter cannot happen with a constant filter, and a service that vanished
            // between lookup and get is not worth a stack trace: both mean "no agent right now".
            Activator.logDebug("upkeep: no provisioning agent available: " + describe(e)); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Returns the version of this plugin's feature as recorded in the running IDE's profile.
     * <p>
     * <code>null</code> means the feature is not installed through p2 at all - a PDE launch, a
     * dropins folder, or a different profile. That is not an error and not something to repair: it
     * means updating is out of scope for this installation, and the feature must stay quiet rather
     * than offer an install it cannot perform.
     * </p>
     *
     * @return the installed version with its full qualifier, or <code>null</code> when unmanaged
     */
    public static Version installedVersion()
    {
        IInstallableUnit unit = installedUnit();
        return unit == null ? null : unit.getVersion();
    }

    /**
     * Returns this plugin's feature as the running IDE's profile records it.
     * <p>
     * An update has to be expressed against the unit that is actually in the profile, not against a
     * unit of the same name fetched from a site: the planner works out what to replace from what the
     * profile holds, and a stand-in built from remote metadata is a different object with different
     * properties.
     * </p>
     *
     * @return the installed unit, or <code>null</code> when p2 does not manage this installation
     */
    public static IInstallableUnit installedUnit()
    {
        IProvisioningAgent agent = currentAgent();
        if (agent == null)
        {
            return null;
        }
        IProfileRegistry registry = agent.getService(IProfileRegistry.class);
        if (registry == null)
        {
            return null;
        }
        IProfile profile = registry.getProfile(IProfileRegistry.SELF);
        if (profile == null)
        {
            return null;
        }
        IQueryResult<IInstallableUnit> installed =
            profile.query(QueryUtil.createIUQuery(FEATURE_IU), null);
        Iterator<IInstallableUnit> units = installed.iterator();
        return units.hasNext() ? units.next() : null;
    }

    /**
     * Reads the newest version of this plugin's feature published by a site.
     * <p>
     * The query is composed rather than taken ready-made: the "latest" filter applied to a query
     * for our own unit means "the newest of ours", while the ready-made latest-unit query means
     * "the newest of everything in the repository" and would answer with some unrelated unit.
     * </p>
     * <p>
     * The agent is supplied rather than created here so a test can drive this against a local
     * repository, and so the caller keeps ownership of the agent's lifetime - whoever creates an
     * agent has to stop it.
     * </p>
     * <p>
     * <b>Only the address asked for is checked, not the ones actually reached.</b> A repository may
     * redirect, or be a composite whose children live elsewhere, and p2 follows both while loading.
     * {@link UpkeepPolicy#isWithin} is the rule those addresses are meant to be held to, and it is
     * not applied here yet - constraining them means reaching into p2's transport and child-loading,
     * which cannot be done without deciding how far that reach may extend beyond this operation.
     * Until then the version reported by a check is what some server said, not something this code
     * has authenticated, and it must not be treated as more than that. Nothing is installed from it.
     * </p>
     *
     * @param agent the agent to read through; a private one for every background use
     * @param site repository URI
     * @param monitor progress monitor, may be <code>null</code>
     * @return the newest published version, or <code>null</code> when the site publishes no such
     *         unit
     * @throws Exception whatever the repository manager raises for an unreachable or malformed site
     */
    static Version offeredVersionVia(IProvisioningAgent agent, URI site, IProgressMonitor monitor)
        throws Exception
    {
        silence(agent);
        IMetadataRepositoryManager repositories = agent.getService(IMetadataRepositoryManager.class);
        if (repositories == null)
        {
            return null;
        }
        IMetadataRepository repository = repositories.loadRepository(site, monitor);
        // The monitor covers the load, which is the part that talks to the network; querying an
        // already-loaded repository is a walk over parsed metadata.
        IQueryResult<IInstallableUnit> found =
            repository.query(QueryUtil.createLatestQuery(QueryUtil.createIUQuery(FEATURE_IU)), null);
        Iterator<IInstallableUnit> units = found.iterator();
        return units.hasNext() ? units.next().getVersion() : null;
    }

    /**
     * Creates an agent with its own data area under the plugin state location.
     * <p>
     * Whoever calls this owns the result and must {@link IProvisioningAgent#stop()} it, otherwise
     * the agent keeps its services and its repository list alive for the rest of the session.
     * </p>
     *
     * @return a private agent, or <code>null</code> when provisioning is unavailable
     */
    static IProvisioningAgent createPrivateAgent()
    {
        BundleContext context = bundleContext();
        URI dataArea = privateDataArea();
        if (context == null || dataArea == null)
        {
            return null;
        }
        ServiceReference<IProvisioningAgentProvider> reference =
            context.getServiceReference(IProvisioningAgentProvider.class);
        if (reference == null)
        {
            return null;
        }
        try
        {
            IProvisioningAgentProvider provider = context.getService(reference);
            return provider == null ? null : provider.createAgent(dataArea);
        }
        catch (Exception e)
        {
            Activator.logDebug("upkeep: cannot create a private agent: " + describe(e)); //$NON-NLS-1$
            return null;
        }
        finally
        {
            context.ungetService(reference);
        }
    }

    /**
     * Registers a {@link UIServices} on this agent that never asks a human anything.
     * <p>
     * This is what makes a background check safe to run unattended. Without it a site behind
     * authentication, or one serving a certificate p2 does not recognise, would raise a modal
     * dialog out of a task the user never started. Registering on a private agent keeps that
     * decision inside our own operation instead of changing how the rest of the IDE prompts.
     * </p>
     * <p>
     * Refusing credentials is also the honest answer: none are configured anywhere, so there is
     * nothing to supply, and reporting a cancelled prompt lets p2 fail the load at once rather than
     * retrying.
     * </p>
     *
     * @param agent the private agent to silence
     */
    private static void silence(IProvisioningAgent agent)
    {
        agent.registerService(UIServices.SERVICE_NAME, new UIServices()
        {
            @Override
            public AuthenticationInfo getUsernamePassword(String location)
            {
                return AUTHENTICATION_PROMPT_CANCELED;
            }

            @Override
            public AuthenticationInfo getUsernamePassword(String location,
                AuthenticationInfo previousInfo)
            {
                return AUTHENTICATION_PROMPT_CANCELED;
            }

            @Override
            public TrustInfo getTrustInfo(Certificate[][] untrustedChain, String[] unsignedDetail)
            {
                // Reading metadata needs no trust decision, and a check is not where trust gets
                // granted: nothing is trusted, nothing is remembered. What to do about unsigned
                // artifacts at install time is a separate decision with its own gate.
                return new TrustInfo(new Certificate[0], false, false);
            }
        });
    }

    private static URI privateDataArea()
    {
        Activator plugin = Activator.getDefault();
        if (plugin == null)
        {
            return null;
        }
        try
        {
            return plugin.getStateLocation().append(PRIVATE_AGENT_AREA).toFile().toURI();
        }
        catch (IllegalStateException e)
        {
            // No instance location: a headless runtime without a workspace. Nothing to update.
            return null;
        }
    }

    private static BundleContext bundleContext()
    {
        // Outside OSGi - a plain unit test - there is no bundle and therefore no service registry.
        return FrameworkUtil.getBundle(ReleaseFeed.class) == null ? null
            : FrameworkUtil.getBundle(ReleaseFeed.class).getBundleContext();
    }

    private static String describe(Throwable e)
    {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
