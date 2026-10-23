/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;
import org.osgi.util.promise.Promise;
import org.osgi.util.tracker.ServiceTracker;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.modules.IModuleSourceProvider;
import ru.aiedt.mcp.server.support.modules.ModuleSources;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.IToolRoad;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ToolWhiteboard;

/**
 * The ordinary forms bundle's lifecycle ends in one agreed state whatever the order around it:
 * exactly one registered ordinary_forms tool service and exactly one registered module source
 * provider of this bundle, with the module registry and the tool catalogue holding that very
 * service object once each - both when the bundle publishes into a whiteboard that is already
 * open, and when the server bundle's whiteboard opens after the bundle has already published.
 * Taking a service away and activating it again re-registers it and leaves no duplicate behind.
 *
 * <p>Everything runs against the real OSGi runtime of this test launch. The registry is read
 * through the framework bundle's own context and through ServiceTrackers this class opens on it,
 * the components are switched through the runtime's own ServiceComponentRuntime service, and the
 * two orders are produced by stopping and starting the real bundles. No production callback is
 * invoked directly and no substitute registry is built.</p>
 *
 * <p>The two order passes and the component pass stop and start real bundles of the running
 * framework. Each of them refuses - fails, not skips - unless the launch is the headless surefire
 * one, so this class cannot tear a working EDT down. The After method puts every bundle and
 * component it touched back and closes only the trackers this class opened, so no effect reaches
 * past the surefire JVM. The road service of the server bundle, which its whiteboard opener
 * publishes after that opener's open has returned, is read as the observable sign that the opener
 * finished; the thread the opener ran on is package private to the server bundle and outside this
 * class's reach, and nothing is claimed about it. What is proven here is the state of this OSGi
 * runtime; nothing is claimed about a running EDT, its UI or its workspace.</p>
 */
public class BothActivationOrdersLeaveExactlyOneOrdinaryFormsTest
{
    /** The bundle whose services the passes follow. */
    private static final String OFORM_BUNDLE = "ru.aiedt.mcp.oform"; //$NON-NLS-1$

    /** The bundle whose whiteboard the passes exercise. */
    private static final String SERVER_BUNDLE = "ru.aiedt.mcp.server"; //$NON-NLS-1$

    /** The tool component's name, as the bundle's descriptor declares it. */
    private static final String TOOL_COMPONENT = "ru.aiedt.mcp.oform.ordinary-forms-tool"; //$NON-NLS-1$

    /** The module source provider component's name, as the bundle's descriptor declares it. */
    private static final String PROVIDER_COMPONENT =
        "ru.aiedt.mcp.oform.module-source-provider"; //$NON-NLS-1$

    /** The wire name of the ordinary forms tool. */
    private static final String ORDINARY_FORMS = "ordinary_forms"; //$NON-NLS-1$

    /** A class of the server bundle, loaded after a restart to trigger its lazy activation. */
    private static final String A_SERVER_CLASS =
        "ru.aiedt.mcp.server.toolkit.ops.ModuleSourceWriter"; //$NON-NLS-1$

    /** A class of this bundle, loaded after a restart to trigger its lazy activation. */
    private static final String A_FORMS_CLASS = "ru.aiedt.mcp.oform.OrdinaryFormsTool"; //$NON-NLS-1$

    /** How long a single wait for the runtime to move may take. */
    private static final long TIMEOUT_MS = 30_000;

    /** How often a wait looks again. */
    private static final int POLL_MS = 100;

    /** The framework's own context, which stays valid while the tested bundles stop and start. */
    private BundleContext frameworkContext;

    /** The host bundle this test fragment lives in. */
    private Bundle oformBundle;

    /** The server bundle whose whiteboard is exercised. */
    private Bundle serverBundle;

    /** The tracker this class opens on the tool services. */
    private ServiceTracker<IMcpTool, IMcpTool> toolTracker;

    /** The tracker this class opens on the module source provider services. */
    private ServiceTracker<IModuleSourceProvider, IModuleSourceProvider> providerTracker;

    /** The reference under which this class holds the component runtime service. */
    private ServiceReference<ServiceComponentRuntime> runtimeReference;

    /** The runtime's own component runtime service. */
    private ServiceComponentRuntime componentRuntime;

    /** Whether the headless launch and original active/enabled state permit lifecycle restoration. */
    private boolean restoreRuntime;

    /**
     * Takes hold of the real runtime: the framework bundle's context, which stays valid while the
     * tested bundles are stopped, the two bundles themselves, the runtime's component runtime
     * service, and one tracker per tracked service.
     */
    @Before
    public void theFrameworkAndItsBundles()
    {
        refuseUnlessHeadless();
        oformBundle = FrameworkUtil.getBundle(OrdinaryFormModuleProvider.class);
        assertNotNull("the fragment must be attached to its host", oformBundle); //$NON-NLS-1$
        BundleContext hostContext = oformBundle.getBundleContext();
        assertNotNull("the host bundle must be started for this class to run", hostContext); //$NON-NLS-1$
        frameworkContext = hostContext.getBundle(0).getBundleContext();
        assertNotNull("the framework must offer its own context", frameworkContext); //$NON-NLS-1$
        serverBundle = bundleNamed(SERVER_BUNDLE);
        runtimeReference = frameworkContext.getServiceReference(ServiceComponentRuntime.class);
        assertNotNull("the runtime publishes no ServiceComponentRuntime service", runtimeReference); //$NON-NLS-1$
        componentRuntime = frameworkContext.getService(runtimeReference);
        assertNotNull("the component runtime service must have an object", componentRuntime); //$NON-NLS-1$
        toolTracker = new ServiceTracker<>(frameworkContext, IMcpTool.class, null);
        toolTracker.open();
        providerTracker = new ServiceTracker<>(frameworkContext, IModuleSourceProvider.class, null);
        providerTracker.open();
        assertEquals("the original form bundle must be active", Bundle.ACTIVE, oformBundle.getState());
        assertEquals("the original server bundle must be active", Bundle.ACTIVE, serverBundle.getState());
        assertTrue("the tool component must start enabled", componentRuntime.isComponentEnabled(theDescription(TOOL_COMPONENT)));
        assertTrue("the provider component must start enabled", componentRuntime.isComponentEnabled(theDescription(PROVIDER_COMPONENT)));
        within(TIMEOUT_MS, "the original steady state", () ->
            serviceCount(IMcpTool.class, OFORM_BUNDLE) == 1
                && serviceCount(IModuleSourceProvider.class, OFORM_BUNDLE) == 1
                && ordinaryProviderCount() == 1
                && McpToolCatalog.getInstance().getTool(ORDINARY_FORMS) != null);
        restoreRuntime = true;
    }

    /**
     * Puts the runtime back whatever the pass did, and lets go of only what this class had taken:
     * the components are enabled, the bundles are started, the steady state of one tool and one
     * provider is waited for, and then the two trackers this class opened are closed and the
     * component runtime service is released.
     */
    @After
    public void theSteadyStateReturns()
    {
        try
        {
            if (restoreRuntime && componentRuntime != null)
            {
                backToExactlyOneOrdinaryFormsEach();
            }
        }
        finally
        {
            if (toolTracker != null)
            {
                toolTracker.close();
            }
            if (providerTracker != null)
            {
                providerTracker.close();
            }
            if (frameworkContext != null && runtimeReference != null)
            {
                frameworkContext.ungetService(runtimeReference);
            }
        }
    }

    /**
     * The launch this class runs in is the headless one of the surefire configuration: the real
     * pom runs the tests without the UI harness and without the UI thread, and the server bundle's
     * own notion of a headless runtime agrees. This says nothing about any EDT: no claim is made
     * that one is running or absent.
     */
    @Test
    public void theLaunchOfTheRealConfigurationIsHeadless()
    {
        assertTrue("the passes of this class stop and start real bundles and belong to the" //$NON-NLS-1$
            + " headless surefire launch only", Activator.isHeadlessRuntime()); //$NON-NLS-1$
    }

    /**
     * The steady state of the agreed condition: exactly one tool service and exactly one provider
     * service of this bundle sit in the real registry, the tool answers to the ordinary_forms name
     * alone across the whole registry and declares its writes, exactly one ordinary provider sits
     * in the module registry and is that very registered object, the catalogue holds that very
     * tool as an external one, and both components behind the services are in the active state of
     * the component runtime.
     *
     * @throws Exception when the tracker wait is interrupted
     */
    @Test
    public void theSteadyStateHoldsExactlyOneOrdinaryFormsToolAndProvider() throws Exception
    {
        backToExactlyOneOrdinaryFormsEach();
        assertEquals("the ordinary forms bundle itself must be active", Bundle.ACTIVE, //$NON-NLS-1$
            oformBundle.getState());

        ServiceReference<IMcpTool> toolReference = theOnlyOne(IMcpTool.class, OFORM_BUNDLE);
        assertEquals("the descriptor declares the tool a writer", Boolean.TRUE, //$NON-NLS-1$
            toolReference.getProperty(ToolWhiteboard.WRITES));
        ServiceReference<IModuleSourceProvider> providerReference =
            theOnlyOne(IModuleSourceProvider.class, OFORM_BUNDLE);

        assertEquals("the tool tracker holds exactly one service of the bundle", 1, //$NON-NLS-1$
            trackerCount(toolTracker, OFORM_BUNDLE));
        assertEquals("the provider tracker holds exactly one service of the bundle", 1, //$NON-NLS-1$
            trackerCount(providerTracker, OFORM_BUNDLE));
        assertNotNull("the tracker must hand out a tracked tool service", //$NON-NLS-1$
            toolTracker.waitForService(TIMEOUT_MS));

        IMcpTool tool = serviceObject(toolReference);
        assertEquals(ORDINARY_FORMS, tool.getName());
        assertEquals("the name belongs to exactly one registered tool service", 1, //$NON-NLS-1$
            toolsNamedOrdinaryForms());

        assertSame("the module registry holds the registered provider object, once", //$NON-NLS-1$
            serviceObject(providerReference), theOrdinaryProvider());
        assertSame("the catalogue holds the registered tool object", tool, //$NON-NLS-1$
            McpToolCatalog.getInstance().getTool(ORDINARY_FORMS));
        assertTrue("the catalogue holds the tool as another bundle's", //$NON-NLS-1$
            McpToolCatalog.getInstance().getExternalTools().containsKey(ORDINARY_FORMS));
        assertEquals("the catalogue read the descriptor's write flag", Boolean.TRUE, //$NON-NLS-1$
            McpToolCatalog.getInstance().externalWritesDeclared(ORDINARY_FORMS));

        assertEquals("the tool component is in the active state", //$NON-NLS-1$
            ComponentConfigurationDTO.ACTIVE, theConfiguration(TOOL_COMPONENT).state);
        assertEquals("the provider component is in the active state", //$NON-NLS-1$
            ComponentConfigurationDTO.ACTIVE, theConfiguration(PROVIDER_COMPONENT).state);
    }

    /**
     * The order in which the whiteboard is open before the bundle publishes: while the server
     * bundle runs, this bundle is stopped and started again. While it is down the registry and the
     * catalogue hold nothing of it; once it is back there is exactly one tool service and one
     * provider service again, the provider in the module registry and the tool in the catalogue
     * are the newly registered objects, and the provider is a new instance rather than the one
     * that was withdrawn.
     */
    @Test
    public void theWhiteboardThatIsAlreadyOpenSeesTheServicesPublishAgain()
    {
        refuseUnlessHeadless();
        backToExactlyOneOrdinaryFormsEach();
        OrdinaryFormModuleProvider providerBefore = theOrdinaryProvider();

        stop(oformBundle);
        within(TIMEOUT_MS, "the bundle to stop and its services to go", () -> oformBundle //$NON-NLS-1$
            .getState() == Bundle.RESOLVED
                && serviceCount(IMcpTool.class, OFORM_BUNDLE) == 0
                && serviceCount(IModuleSourceProvider.class, OFORM_BUNDLE) == 0
                && trackerCount(toolTracker, OFORM_BUNDLE) == 0
                && ordinaryProviderCount() == 0
                && McpToolCatalog.getInstance().getTool(ORDINARY_FORMS) == null);

        start(oformBundle, A_FORMS_CLASS);
        within(TIMEOUT_MS, "the bundle to come back with exactly one of each", () -> //$NON-NLS-1$
            oformBundle.getState() == Bundle.ACTIVE
                && serviceCount(IMcpTool.class, OFORM_BUNDLE) == 1
                && serviceCount(IModuleSourceProvider.class, OFORM_BUNDLE) == 1
                && trackerCount(toolTracker, OFORM_BUNDLE) == 1
                && ordinaryProviderCount() == 1
                && McpToolCatalog.getInstance().getTool(ORDINARY_FORMS) != null);

        assertNotSame("a restart publishes a new provider instance", providerBefore, //$NON-NLS-1$
            theOrdinaryProvider());
        assertSame("the module registry holds the newly registered provider object", //$NON-NLS-1$
            serviceObject(theOnlyOne(IModuleSourceProvider.class, OFORM_BUNDLE)),
            theOrdinaryProvider());
        assertSame("the catalogue holds the newly registered tool object", //$NON-NLS-1$
            serviceObject(theOnlyOne(IMcpTool.class, OFORM_BUNDLE)),
            McpToolCatalog.getInstance().getTool(ORDINARY_FORMS));
    }

    /**
     * The order in which the bundle has already published and the whiteboard opens after: with
     * this bundle left running, the server bundle is stopped and started again. While it is down
     * the whiteboard is closed - the module registry and the catalogue hold nothing of this
     * bundle and its road is gone - while this bundle's two services stay registered the whole
     * time. Once the server bundle is back, its whiteboard has opened after that publication and
     * picked it up: the module registry and the catalogue hold exactly one entry each, and the
     * entry is the very registration that was published before the whiteboard opened, not a new
     * one.
     */
    @Test
    public void theWhiteboardThatOpensAfterThePublicationPicksItUp()
    {
        refuseUnlessHeadless();
        backToExactlyOneOrdinaryFormsEach();
        OrdinaryFormModuleProvider providerBefore = theOrdinaryProvider();
        ServiceReference<IModuleSourceProvider> referenceBefore =
            theOnlyOne(IModuleSourceProvider.class, OFORM_BUNDLE);

        stop(serverBundle);
        within(TIMEOUT_MS, "the server bundle to stop and its whiteboard to close", () -> //$NON-NLS-1$
            serverBundle.getState() == Bundle.RESOLVED
                && ordinaryProviderCount() == 0
                && McpToolCatalog.getInstance().getTool(ORDINARY_FORMS) == null
                && frameworkContext.getServiceReference(IToolRoad.class) == null);
        assertNull("the catalogue must have let the tool go with the whiteboard", //$NON-NLS-1$
            McpToolCatalog.getInstance().getTool(ORDINARY_FORMS));
        assertEquals("the tool service stays registered while no whiteboard is open", 1, //$NON-NLS-1$
            serviceCount(IMcpTool.class, OFORM_BUNDLE));
        assertEquals("the provider service stays registered while no whiteboard is open", 1, //$NON-NLS-1$
            serviceCount(IModuleSourceProvider.class, OFORM_BUNDLE));

        start(serverBundle, A_SERVER_CLASS);
        within(TIMEOUT_MS, "the server bundle to restart and its whiteboard to open", () -> //$NON-NLS-1$
            serverBundle.getState() == Bundle.ACTIVE
                && ordinaryProviderCount() == 1
                && McpToolCatalog.getInstance().getTool(ORDINARY_FORMS) != null
                && frameworkContext.getServiceReference(IToolRoad.class) != null);

        assertSame("the registration is the one published before the whiteboard opened", //$NON-NLS-1$
            referenceBefore, theOnlyOne(IModuleSourceProvider.class, OFORM_BUNDLE));
        assertSame("the whiteboard picked up that very provider object", providerBefore, //$NON-NLS-1$
            theOrdinaryProvider());
        assertSame("the provider in the module registry is that registered object", providerBefore, //$NON-NLS-1$
            serviceObject(theOnlyOne(IModuleSourceProvider.class, OFORM_BUNDLE)));
        assertEquals("still exactly one tool service of the bundle", 1, //$NON-NLS-1$
            serviceCount(IMcpTool.class, OFORM_BUNDLE));
        assertEquals("the reopened whiteboard read the descriptor's write flag again", //$NON-NLS-1$
            Boolean.TRUE, McpToolCatalog.getInstance().externalWritesDeclared(ORDINARY_FORMS));
    }

    /**
     * Taking the two components away and activating them again, twice over: while they are
     * disabled the component runtime holds no configuration of either, the registry holds no
     * service of them, the module registry holds no ordinary provider and the catalogue holds no
     * tool; after each return there is exactly one of each again, the tool component sits in a
     * fresh configuration rather than the one that was disabled, and the provider in the module
     * registry is the registered service object.
     */
    @Test
    public void disablingAndEnablingTwiceReregistersBothWithoutDuplicates()
    {
        refuseUnlessHeadless();
        backToExactlyOneOrdinaryFormsEach();
        long configurationBefore = theConfiguration(TOOL_COMPONENT).id;

        for (int cycle = 1; cycle <= 2; cycle++)
        {
            disable(TOOL_COMPONENT);
            disable(PROVIDER_COMPONENT);
            within(TIMEOUT_MS, "both components to be gone in cycle " + cycle, () -> //$NON-NLS-1$
                theConfigurations(TOOL_COMPONENT).isEmpty()
                    && theConfigurations(PROVIDER_COMPONENT).isEmpty()
                    && serviceCount(IMcpTool.class, OFORM_BUNDLE) == 0
                    && serviceCount(IModuleSourceProvider.class, OFORM_BUNDLE) == 0
                    && trackerCount(toolTracker, OFORM_BUNDLE) == 0
                    && trackerCount(providerTracker, OFORM_BUNDLE) == 0
                    && ordinaryProviderCount() == 0
                    && McpToolCatalog.getInstance().getTool(ORDINARY_FORMS) == null);

            enable(TOOL_COMPONENT);
            enable(PROVIDER_COMPONENT);
            within(TIMEOUT_MS, "both components to be back exactly once in cycle " + cycle, //$NON-NLS-1$
                () -> serviceCount(IMcpTool.class, OFORM_BUNDLE) == 1
                    && serviceCount(IModuleSourceProvider.class, OFORM_BUNDLE) == 1
                    && trackerCount(toolTracker, OFORM_BUNDLE) == 1
                    && trackerCount(providerTracker, OFORM_BUNDLE) == 1
                    && ordinaryProviderCount() == 1
                    && McpToolCatalog.getInstance().getTool(ORDINARY_FORMS) != null
                    && configurationState(TOOL_COMPONENT) != null
                    && configurationState(TOOL_COMPONENT).intValue()
                        == ComponentConfigurationDTO.ACTIVE);
        }

        assertNotEquals("a disable and enable cycle built a fresh configuration", //$NON-NLS-1$
            configurationBefore, theConfiguration(TOOL_COMPONENT).id);
        assertSame("the module registry holds the registered provider object, once", //$NON-NLS-1$
            serviceObject(theOnlyOne(IModuleSourceProvider.class, OFORM_BUNDLE)),
            theOrdinaryProvider());
        assertSame("the catalogue holds the registered tool object", //$NON-NLS-1$
            serviceObject(theOnlyOne(IMcpTool.class, OFORM_BUNDLE)),
            McpToolCatalog.getInstance().getTool(ORDINARY_FORMS));
    }

    /**
     * Brings the runtime back to the steady state: both components enabled, both bundles started,
     * and exactly one ordinary forms tool and one provider in the registry. A no-op when nothing
     * was touched, and the same repair whatever the passes left behind.
     */
    private void backToExactlyOneOrdinaryFormsEach()
    {
        enable(TOOL_COMPONENT);
        enable(PROVIDER_COMPONENT);
        start(oformBundle, A_FORMS_CLASS);
        start(serverBundle, A_SERVER_CLASS);
        within(TIMEOUT_MS, "the steady state to return", () -> //$NON-NLS-1$
            serviceCount(IMcpTool.class, OFORM_BUNDLE) == 1
                && serviceCount(IModuleSourceProvider.class, OFORM_BUNDLE) == 1
                && ordinaryProviderCount() == 1
                && McpToolCatalog.getInstance().getTool(ORDINARY_FORMS) != null);
    }

    /**
     * Refuses the passes that stop and start real bundles anywhere but the headless surefire
     * launch: the pass fails rather than skips, so this class cannot run its destructive
     * lifecycle against a working EDT. Both the exact Tycho headless test application and the
     * server bundle's own headless-runtime check must agree before fixture setup begins.
     */
    private static void refuseUnlessHeadless()
    {
        assertTrue("this pass stops and starts real bundles and belongs to the headless" //$NON-NLS-1$
            + " surefire runtime only, never to a working EDT", //$NON-NLS-1$
            "org.eclipse.tycho.surefire.osgibooter.headlesstest".equals(System.getProperty("eclipse.application")) //$NON-NLS-1$ //$NON-NLS-2$
                && Activator.isHeadlessRuntime());
    }

    /**
     * The references of the services of the given interface the given bundle registered.
     *
     * @param <S> the service type
     * @param serviceType the service interface
     * @param symbolicName the registering bundle's symbolic name
     * @return the references, possibly none
     */
    private <S> List<ServiceReference<S>> referencesFrom(Class<S> serviceType, String symbolicName)
    {
        Collection<ServiceReference<S>> found;
        try
        {
            found = frameworkContext.getServiceReferences(serviceType, null);
        }
        catch (InvalidSyntaxException e)
        {
            throw new IllegalStateException("a null filter is always valid", e); //$NON-NLS-1$
        }
        List<ServiceReference<S>> ofBundle = new ArrayList<>();
        if (found != null)
        {
            for (ServiceReference<S> reference : found)
            {
                if (symbolicName.equals(reference.getBundle().getSymbolicName()))
                {
                    ofBundle.add(reference);
                }
            }
        }
        return ofBundle;
    }

    /**
     * The number of services of the given interface the given bundle registered.
     *
     * @param <S> the service type
     * @param serviceType the service interface
     * @param symbolicName the registering bundle's symbolic name
     * @return the count
     */
    private <S> int serviceCount(Class<S> serviceType, String symbolicName)
    {
        return referencesFrom(serviceType, symbolicName).size();
    }

    /**
     * The number of services the tracker holds that the given bundle registered.
     *
     * @param tracker one of the trackers this class opened
     * @param symbolicName the registering bundle's symbolic name
     * @return the count
     */
    private static int trackerCount(ServiceTracker<?, ?> tracker, String symbolicName)
    {
        ServiceReference<?>[] references = tracker.getServiceReferences();
        if (references == null)
        {
            return 0;
        }
        int count = 0;
        for (ServiceReference<?> reference : references)
        {
            if (symbolicName.equals(reference.getBundle().getSymbolicName()))
            {
                count++;
            }
        }
        return count;
    }

    /**
     * The number of ordinary forms providers in the module registry.
     *
     * @return the count
     */
    private static int ordinaryProviderCount()
    {
        int count = 0;
        for (IModuleSourceProvider provider : ModuleSources.providers())
        {
            if (provider instanceof OrdinaryFormModuleProvider)
            {
                count++;
            }
        }
        return count;
    }

    /**
     * The one ordinary forms provider of the module registry.
     *
     * @return the provider
     */
    private static OrdinaryFormModuleProvider theOrdinaryProvider()
    {
        List<OrdinaryFormModuleProvider> found = new ArrayList<>();
        for (IModuleSourceProvider provider : ModuleSources.providers())
        {
            if (provider instanceof OrdinaryFormModuleProvider)
            {
                found.add((OrdinaryFormModuleProvider)provider);
            }
        }
        assertEquals("exactly one ordinary forms provider in the module registry", 1, found.size()); //$NON-NLS-1$
        return found.get(0);
    }

    /**
     * The reference of the one service of the given interface the given bundle registered.
     *
     * @param <S> the service type
     * @param serviceType the service interface
     * @param symbolicName the registering bundle's symbolic name
     * @return the reference of that one service
     */
    private <S> ServiceReference<S> theOnlyOne(Class<S> serviceType, String symbolicName)
    {
        List<ServiceReference<S>> found = referencesFrom(serviceType, symbolicName);
        assertEquals("exactly one " + serviceType.getSimpleName() + " service of " + symbolicName, //$NON-NLS-1$ //$NON-NLS-2$
            1, found.size());
        return found.get(0);
    }

    /**
     * The object of a registered service, taken and released once. Getting the object of a
     * delayed component is what activates it, so this is also what makes the component's state
     * measurable.
     *
     * @param <S> the service type
     * @param reference the service reference
     * @return the service object
     */
    private <S> S serviceObject(ServiceReference<S> reference)
    {
        S service = frameworkContext.getService(reference);
        assertNotNull("the service object must be available", service); //$NON-NLS-1$
        frameworkContext.ungetService(reference);
        return service;
    }

    /**
     * How many registered tool services of the whole registry answer to the ordinary forms name.
     *
     * @return the count over every bundle
     */
    private int toolsNamedOrdinaryForms()
    {
        Collection<ServiceReference<IMcpTool>> references;
        try
        {
            references = frameworkContext.getServiceReferences(IMcpTool.class, null);
        }
        catch (InvalidSyntaxException e)
        {
            throw new IllegalStateException("a null filter is always valid", e); //$NON-NLS-1$
        }
        if (references == null)
        {
            return 0;
        }
        int count = 0;
        for (ServiceReference<IMcpTool> reference : references)
        {
            IMcpTool tool = frameworkContext.getService(reference);
            if (tool != null && ORDINARY_FORMS.equals(tool.getName()))
            {
                count++;
            }
            frameworkContext.ungetService(reference);
        }
        return count;
    }

    /**
     * The description of one of the bundle's components.
     *
     * @param component the component name from the bundle's descriptors
     * @return the description
     */
    private ComponentDescriptionDTO theDescription(String component)
    {
        ComponentDescriptionDTO description =
            componentRuntime.getComponentDescriptionDTO(oformBundle, component);
        assertNotNull("the bundle must hold the component " + component, description); //$NON-NLS-1$
        return description;
    }

    /**
     * The configurations of one of the bundle's components.
     *
     * @param component the component name from the bundle's descriptors
     * @return the configurations, possibly none
     */
    private List<ComponentConfigurationDTO> theConfigurations(String component)
    {
        Collection<ComponentConfigurationDTO> configurations =
            componentRuntime.getComponentConfigurationDTOs(theDescription(component));
        return configurations == null ? new ArrayList<>() : new ArrayList<>(configurations);
    }

    /**
     * The state of a component's single configuration.
     *
     * @param component the component name from the bundle's descriptors
     * @return the state, or null while the component is disabled or holds other than one
     *         configuration
     */
    private Integer configurationState(String component)
    {
        List<ComponentConfigurationDTO> configurations = theConfigurations(component);
        return configurations.size() == 1 ? Integer.valueOf(configurations.get(0).state) : null;
    }

    /**
     * The one configuration of a component.
     *
     * @param component the component name from the bundle's descriptors
     * @return the configuration
     */
    private ComponentConfigurationDTO theConfiguration(String component)
    {
        List<ComponentConfigurationDTO> configurations = theConfigurations(component);
        assertEquals("exactly one configuration of " + component, 1, configurations.size()); //$NON-NLS-1$
        return configurations.get(0);
    }

    /**
     * Enables one of the bundle's components and waits for the operation to settle.
     *
     * @param component the component name from the bundle's descriptors
     */
    private void enable(String component)
    {
        settle(componentRuntime.enableComponent(theDescription(component)), "enabling " + component); //$NON-NLS-1$
    }

    /**
     * Disables one of the bundle's components and waits for the operation to settle.
     *
     * @param component the component name from the bundle's descriptors
     */
    private void disable(String component)
    {
        settle(componentRuntime.disableComponent(theDescription(component)), "disabling " + component); //$NON-NLS-1$
    }

    /**
     * Waits for a component runtime operation to settle and fails when it failed.
     *
     * @param outcome the operation's promise
     * @param what the operation is, for the failure message
     */
    private static void settle(Promise<Void> outcome, String what)
    {
        try
        {
            outcome.getValue();
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            fail("interrupted while " + what); //$NON-NLS-1$
        }
        catch (InvocationTargetException failed)
        {
            fail(what + " failed: " + failed.getCause()); //$NON-NLS-1$
        }
    }

    /**
     * The bundle with the given symbolic name of this runtime.
     *
     * @param symbolicName the symbolic name
     * @return the bundle
     */
    private Bundle bundleNamed(String symbolicName)
    {
        for (Bundle bundle : frameworkContext.getBundles())
        {
            if (symbolicName.equals(bundle.getSymbolicName()))
            {
                return bundle;
            }
        }
        fail("no bundle " + symbolicName + " in this runtime"); //$NON-NLS-1$ //$NON-NLS-2$
        return null;
    }

    /**
     * Starts a bundle that is not active and then triggers a lazy activation policy by loading
     * one of its classes from this - another - bundle. A refusal is not thrown out of here: the
     * passes that follow wait for and assert the state they require.
     *
     * @param bundle the bundle
     * @param triggerClassName a class of the bundle, loaded to trigger its activation
     */
    private static void start(Bundle bundle, String triggerClassName)
    {
        if (bundle.getState() != Bundle.ACTIVE)
        {
            try
            {
                bundle.start();
            }
            catch (BundleException | IllegalStateException e)
            {
                // The pass that follows waits for the state and reports what it sees.
            }
        }
        try
        {
            bundle.loadClass(triggerClassName);
        }
        catch (ClassNotFoundException | IllegalStateException e)
        {
            // A class load of a running bundle needs nothing either; the passes below decide.
        }
    }

    /**
     * Stops a bundle. A stop the framework reports as failed is not thrown out of here on its
     * own: the framework completes the stop of a bundle whose activator threw, and the passes
     * below wait for and assert the state they require.
     *
     * @param bundle the bundle
     */
    private static void stop(Bundle bundle)
    {
        try
        {
            bundle.stop();
        }
        catch (BundleException | IllegalStateException e)
        {
            // The pass that follows waits for the state and reports what it sees.
        }
    }

    /**
     * Polls until the condition holds or the timeout passes, then fails with the runtime's own
     * counts, so a timeout says what the registry looked like when it fired.
     *
     * @param timeoutMs how long to wait
     * @param what is being waited for, for the failure message
     * @param condition the condition, evaluated on the calling thread until it holds
     */
    private void within(long timeoutMs, String what, BooleanSupplier condition)
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true)
        {
            if (condition.getAsBoolean())
            {
                return;
            }
            if (System.currentTimeMillis() >= deadline)
            {
                fail("timed out after " + timeoutMs + " ms waiting for " + what + "; " + snapshot()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            try
            {
                Thread.sleep(POLL_MS);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for " + what); //$NON-NLS-1$
            }
        }
    }

    /**
     * The counts every pass waits for, as one line for a timeout message.
     *
     * @return the tool and provider service counts, the module registry count and the two bundle
     *         states
     */
    private String snapshot()
    {
        return "tool services=" + serviceCount(IMcpTool.class, OFORM_BUNDLE) //$NON-NLS-1$
            + ", provider services=" + serviceCount(IModuleSourceProvider.class, OFORM_BUNDLE) //$NON-NLS-1$
            + ", module registry providers=" + ordinaryProviderCount() //$NON-NLS-1$
            + ", oform state=" + oformBundle.getState() //$NON-NLS-1$
            + ", server state=" + serverBundle.getState(); //$NON-NLS-1$
    }
}

