/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.TimeoutException;

import ru.aiedt.mcp.server.support.modules.IModuleSourceProvider;
import ru.aiedt.mcp.server.support.modules.ModuleSources;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.IToolRoad;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ToolRoadOutcome;

/**
 * The bundle's components followed through the framework's own lifecycle, rather than through the
 * descriptors that declare them: the two components that publish a service are switched off and on
 * through the component runtime of the framework the tests run in, and what the plugin and the
 * framework hold is read from that same live state after every step.
 *
 * <p>Both orders a first call can meet are exercised - the tool arriving before the provider and
 * after it - and each ends with exactly one tool and one provider published and registered. A clear
 * of the catalogue is exercised with both components live and with the tool's component switched
 * off. The reluctant road reference retains its bound service when another service appears,
 * and selects the highest-ranked service when the holder is enabled again. Switching the tool off is
 * also what makes the registration path observable: the service leaves the framework, the catalogue
 * and the module registry lose the tool, and coming back on puts exactly one of each there again.</p>
 *
 * <p>Nothing about the runtime is assumed to survive a pass. Every pass records the enablement of
 * all three components and the road the bundle holds before it touches anything, and gives each of
 * them back afterwards: a component that was switched off stays switched off, the probe road is
 * unregistered before the rest of the cleanup, and the road the bundle held is put back by hand
 * when the framework's own reference has not settled on it by the end. The pass that clears the
 * catalogue records the tools it finds there and registers them again, because a clear forgets the
 * tools this server registered too and only the server's own startup would put them back. No wait
 * here is a sleep taken as a witness - each one reads the state it waits for, and fails naming that
 * state when it has not arrived within the bound.</p>
 */
public class TheOrdinaryComponentsFollowTheFrameworkTest
{
    /** The tool's wire name and the three component names the bundle declares. */
    private static final String TOOL = "ordinary_forms"; //$NON-NLS-1$

    private static final String TOOL_COMPONENT = "ru.aiedt.mcp.oform.ordinary-forms-tool"; //$NON-NLS-1$

    private static final String PROVIDER_COMPONENT = "ru.aiedt.mcp.oform.module-source-provider"; //$NON-NLS-1$

    private static final String HOLDER_COMPONENT = "ru.aiedt.mcp.oform.tool-road-holder"; //$NON-NLS-1$

    /** How long a component switch or a registry update is given to arrive. */
    private static final long SETTLE_MILLIS = 20_000L;

    /** The pause between two reads of the state a pass waits for. */
    private static final long POLL_MILLIS = 25L;

    private BundleContext context;

    private ServiceComponentRuntime scr;

    private ServiceReference<ServiceComponentRuntime> scrReference;

    private boolean toolWasEnabled;

    private boolean providerWasEnabled;

    private boolean holderWasEnabled;

    private IToolRoad roadBefore;

    private ServiceRegistration<IToolRoad> probe;

    private int toolServicesBefore;

    private int providerServicesBefore;

    /**
     * Records the runtime this pass starts from: the component runtime of the running framework,
     * the enablement of the three components and the road the bundle holds.
     */
    @Before
    public void theRuntimeAsItStands()
    {
        context = FrameworkUtil.getBundle(ToolRoadHolder.class).getBundleContext();
        assertNotNull("the bundle of the ordinary tool has no context here", context); //$NON-NLS-1$
        scrReference = context.getServiceReference(ServiceComponentRuntime.class);
        scr = scrReference == null ? null : context.getService(scrReference);
        if (scr != null)
        {
            toolWasEnabled = scr.isComponentEnabled(component(TOOL_COMPONENT));
            providerWasEnabled = scr.isComponentEnabled(component(PROVIDER_COMPONENT));
            holderWasEnabled = scr.isComponentEnabled(component(HOLDER_COMPONENT));
        }
        roadBefore = ToolRoadHolder.road();
    }

    /**
     * Gives the runtime back what it had: the probe road first, then every component in the
     * enablement this pass found, then the road the bundle held - and releases the component runtime
     * last, so a failure on the way cannot strand the service.
     */
    @After
    public void theRuntimeGoesBack()
    {
        if (probe != null)
        {
            probe.unregister();
            probe = null;
        }
        try
        {
            if (scr != null)
            {
                try
                {
                    putBack(holder(), HOLDER_COMPONENT, holderWasEnabled);
                }
                finally
                {
                    try
                    {
                        putBack(provider(), PROVIDER_COMPONENT, providerWasEnabled);
                    }
                    finally
                    {
                        putBack(tool(), TOOL_COMPONENT, toolWasEnabled);
                    }
                }
            }
        }
        finally
        {
            theRoadIsPutBack();
            if (scrReference != null)
            {
                context.ungetService(scrReference);
                scrReference = null;
                scr = null;
            }
        }
    }

    /**
     * The order in which the two publishing components can meet a first call: with both switched
     * off and out of the plugin's registries, the tool comes on first - one service in the
     * framework, one entry in the catalogue, and a call reaches it - while the provider is still
     * off and really absent from both, and the provider is the last to arrive and is counted the
     * same way.
     */
    @Test
    public void theToolComingOnFirstLeavesOneToolAndOneProvider()
    {
        theComponentsAreLive();
        switchOff(provider(), PROVIDER_COMPONENT);
        switchOff(tool(), TOOL_COMPONENT);
        theRegistryIsEmpty();
        switchOn(tool(), TOOL_COMPONENT);
        awaitTheTool();
        theToolIsExactlyOne();
        assertFalse("the provider is still switched off", scr.isComponentEnabled(provider())); //$NON-NLS-1$
        assertEquals("a switched-off component publishes nothing into the registry", 0, ordinaryProviders()); //$NON-NLS-1$
        assertEquals("and nothing into the framework", providerServicesBefore - 1, providerServices()); //$NON-NLS-1$
        switchOn(provider(), PROVIDER_COMPONENT);
        awaitTheProvider();
        theProviderIsExactlyOne();
    }

    /**
     * The other order: the provider comes on while the tool is still switched off, and nothing of
     * the tool is anywhere until its component comes on - then exactly one tool is published and
     * registered, next to the one provider.
     */
    @Test
    public void theProviderComingOnFirstLeavesOneToolAndOneProvider()
    {
        theComponentsAreLive();
        switchOff(tool(), TOOL_COMPONENT);
        switchOff(provider(), PROVIDER_COMPONENT);
        theRegistryIsEmpty();
        switchOn(provider(), PROVIDER_COMPONENT);
        awaitTheProvider();
        theProviderIsExactlyOne();
        assertFalse("the tool is still switched off", scr.isComponentEnabled(tool())); //$NON-NLS-1$
        assertNull("a switched-off component publishes no tool into the catalogue", //$NON-NLS-1$
            McpToolCatalog.getInstance().getTool(TOOL));
        assertEquals("and none into the framework", 0, publishedTools(TOOL).size()); //$NON-NLS-1$
        switchOn(tool(), TOOL_COMPONENT);
        awaitTheTool();
        theToolIsExactlyOne();
    }

    /**
     * A clear of the catalogue neither loses the tool of a live component nor brings back one whose
     * component is gone: the plugin records the tools that arrived through the whiteboard and
     * registers them again, so the same objects are there with their declarations - and with the
     * tool's component switched off there is nothing of it left to register, so the clear leaves
     * the catalogue without it and the tool stays neither listed nor callable.
     *
     * <p>A clear is the server's own step before it registers its fixed list, so it forgets the
     * tools this server put there as well; nothing in a running plugin registers those again outside
     * that startup. The pass therefore records the tools the catalogue holds, which are the ones this
     * server registered, and puts them back in a finally - otherwise every pass of this runtime after
     * it would run against a catalogue missing them.</p>
     */
    @Test
    public void aClearKeepsTheToolsOfLiveComponentsAndBringsBackNone()
    {
        theComponentsAreLive();
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        List<IMcpTool> registered = catalog.getAllTools().stream()
            .filter(one -> !catalog.getExternalTools().containsValue(one)).toList();
        Map<String, IMcpTool> held = Map.copyOf(catalog.getExternalTools());
        Boolean writes = catalog.externalWritesDeclared(TOOL);
        try
        {
            assertTrue("the pass needs an external tool to follow through the clear", //$NON-NLS-1$
                held.containsKey(TOOL));
            catalog.clear();
            await("the catalogue to hold its external tools again", //$NON-NLS-1$
                () -> catalog.getExternalTools().containsKey(TOOL));
            assertEquals("a clear registers the tools that arrived through the whiteboard again", //$NON-NLS-1$
                held.keySet(), catalog.getExternalTools().keySet());
            assertSame("and the same objects", held.get(TOOL), catalog.getExternalTools().get(TOOL)); //$NON-NLS-1$
            assertEquals("with their declarations", writes, catalog.externalWritesDeclared(TOOL)); //$NON-NLS-1$
            theToolIsExactlyOne();
            switchOff(tool(), TOOL_COMPONENT);
            await("the catalogue to lose the tool of the switched-off component", //$NON-NLS-1$
                () -> catalog.getTool(TOOL) == null);
            catalog.clear();
            assertNull("a clear does not bring back a tool whose component is gone", //$NON-NLS-1$
                catalog.getTool(TOOL));
            assertFalse(catalog.getExternalTools().containsKey(TOOL));
            assertFalse(catalog.isToolEnabled(TOOL));
            assertFalse("and nothing of it is left to list", listedNames().contains(TOOL)); //$NON-NLS-1$
        }
        finally
        {
            theRegisteredToolsGoBack(registered);
        }
    }

    /**
     * Puts the tools this server registered back into the catalogue, which is where a clear leaves
     * them out: the same objects, each under the capability and the names it came in with.
     *
     * @param tools the tools the catalogue held before the pass
     */
    private static void theRegisteredToolsGoBack(Collection<IMcpTool> tools)
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        for (IMcpTool tool : tools)
        {
            catalog.register(tool);
        }
    }

    /** A dynamic reluctant reference keeps its bound road when a higher-ranked service appears. */
    @Test
    public void theHolderKeepsItsBoundRoadWhenAnotherServiceAppears()
    {
        theComponentsAreLive();
        var reference = java.util.Arrays.stream(holder().references)
            .filter(one -> "ToolRoad".equals(one.name)).findFirst().orElseThrow(); //$NON-NLS-1$
        assertEquals("dynamic", reference.policy); //$NON-NLS-1$
        assertEquals("reluctant", reference.policyOption); //$NON-NLS-1$
        assertNotNull("the reference is already bound", roadBefore); //$NON-NLS-1$
        long configurationBefore = activeConfiguration(HOLDER_COMPONENT).id;
        IToolRoad arriving = new AProbeRoad("arriving"); //$NON-NLS-1$
        probe = publishRoad(arriving);
        assertSame(arriving, context.getService(probe.getReference()));
        context.ungetService(probe.getReference());
        assertSame("the reluctant reference retains its bound service", roadBefore, ToolRoadHolder.road()); //$NON-NLS-1$
        assertEquals("the component is not restarted", configurationBefore, //$NON-NLS-1$
            activeConfiguration(HOLDER_COMPONENT).id);
        probe.unregister();
        probe = null;
        assertSame(roadBefore, ToolRoadHolder.road());
    }

    /**
     * The road that is already published when the holder comes back: with the holder switched off
     * nothing of it is bound, so the road published above the plugin's own is what the holder takes
     * when it comes up - which is what a reference that may stay unsatisfied has to do.
     */
    @Test
    public void theHolderTakesTheRoadThatIsThereWhenItComesBack()
    {
        theComponentsAreLive();
        switchOff(holder(), HOLDER_COMPONENT);
        IToolRoad present = new AProbeRoad("present"); //$NON-NLS-1$
        probe = publishRoad(present);
        assertNotSame("nothing of the holder is bound while its component is off", present, //$NON-NLS-1$
            ToolRoadHolder.road());
        switchOn(holder(), HOLDER_COMPONENT);
        await("the holder to take the road that was already there", //$NON-NLS-1$
            () -> ToolRoadHolder.road() == present);
    }

    /**
     * The runtime a pass needs: the framework hands out the component runtime, the three components
     * are enabled and active - the two that publish a service are delayed and come up with the
     * first caller, the holder is immediate - one tool and one provider are published, and the
     * counts this pass measures its steps against are taken here.
     */
    private void theComponentsAreLive()
    {
        assertNotNull("this runtime has no component runtime service", scr); //$NON-NLS-1$
        assertTrue("the tool component is not enabled", scr.isComponentEnabled(tool())); //$NON-NLS-1$
        assertTrue("the provider component is not enabled", scr.isComponentEnabled(provider())); //$NON-NLS-1$
        assertTrue("the holder component is not enabled", scr.isComponentEnabled(holder())); //$NON-NLS-1$
        assertFalse("the tool publishes a service and is therefore a delayed component", //$NON-NLS-1$
            tool().immediate);
        assertFalse("the provider publishes a service and is therefore a delayed component", //$NON-NLS-1$
            provider().immediate);
        assertTrue("the holder takes a reference and therefore has to be an immediate component", //$NON-NLS-1$
            holder().immediate);
        activeConfiguration(TOOL_COMPONENT);
        activeConfiguration(PROVIDER_COMPONENT);
        activeConfiguration(HOLDER_COMPONENT);
        toolServicesBefore = publishedTools(TOOL).size();
        providerServicesBefore = providerServices();
        assertEquals("this pass measures the tool count around exactly one tool", 1, toolServicesBefore); //$NON-NLS-1$
        assertTrue("this pass measures the provider count around the component's own service", //$NON-NLS-1$
            providerServicesBefore >= 1);
        awaitTheTool();
        awaitTheProvider();
    }

    /** Both publishing components are off and neither the plugin nor the framework holds anything. */
    private void theRegistryIsEmpty()
    {
        await("the catalogue to lose the tool", () -> McpToolCatalog.getInstance().getTool(TOOL) == null); //$NON-NLS-1$
        await("the framework to publish no tool", () -> publishedTools(TOOL).isEmpty()); //$NON-NLS-1$
        await("the module registry to lose the provider", () -> ordinaryProviders() == 0); //$NON-NLS-1$
        await("the framework to publish the provider's service no more", //$NON-NLS-1$
            () -> providerServices() == providerServicesBefore - 1);
    }

    /** Waits for the tool to be registered and published, so a pass asserts on a settled state. */
    private void awaitTheTool()
    {
        await("the catalogue to hold the tool", //$NON-NLS-1$
            () -> McpToolCatalog.getInstance().getTool(TOOL) != null);
        await("the framework to publish exactly one tool", () -> publishedTools(TOOL).size() == 1); //$NON-NLS-1$
    }

    /** Waits for the provider to be registered and for the framework to publish it once more. */
    private void awaitTheProvider()
    {
        await("the module registry to hold the provider", () -> ordinaryProviders() == 1); //$NON-NLS-1$
        await("the framework to publish the provider's service again", //$NON-NLS-1$
            () -> providerServices() == providerServicesBefore);
    }

    /**
     * The tool is published once and registered once, and the service the framework hands out
     * answers as the tool - which is what the component's own instance is there for.
     */
    private void theToolIsExactlyOne()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        List<IMcpTool> published = publishedTools(TOOL);
        assertEquals("the tool is published exactly once", 1, published.size()); //$NON-NLS-1$
        assertNotNull("the catalogue holds the tool", catalog.getTool(TOOL)); //$NON-NLS-1$
        assertTrue("the catalogue holds it as a tool of another bundle", //$NON-NLS-1$
            catalog.getExternalTools().containsKey(TOOL));
        assertTrue("and answers for its name", catalog.isToolEnabled(TOOL)); //$NON-NLS-1$
        String help = published.get(0).execute(Map.of("operation", "help")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(help, help.contains("inspect_layout")); //$NON-NLS-1$
    }

    /** The provider is registered exactly once, next to whatever the runtime holds of its own. */
    private static void theProviderIsExactlyOne()
    {
        assertEquals("the provider is registered exactly once", 1, ordinaryProviders()); //$NON-NLS-1$
        assertTrue("and the registry holds it while the pass runs", //$NON-NLS-1$
            ModuleSources.providers().stream().anyMatch(OrdinaryFormModuleProvider.class::isInstance));
    }

    /**
     * Switches a component off and waits for the framework to say it is off.
     *
     * @param description the component to switch off
     * @param name the component name, for failure messages
     */
    private void switchOff(ComponentDescriptionDTO description, String name)
    {
        assertTrue(name + " is enabled before it is switched off", scr.isComponentEnabled(description)); //$NON-NLS-1$
        finished(scr.disableComponent(description), "switching off " + name); //$NON-NLS-1$
        assertFalse(name + " is switched off", scr.isComponentEnabled(description)); //$NON-NLS-1$
    }

    /**
     * Switches a component on and waits for the framework to say it is on.
     *
     * @param description the component to switch on
     * @param name the component name, for failure messages
     */
    private void switchOn(ComponentDescriptionDTO description, String name)
    {
        finished(scr.enableComponent(description), "switching on " + name); //$NON-NLS-1$
        assertTrue(name + " is switched on", scr.isComponentEnabled(description)); //$NON-NLS-1$
    }

    /**
     * Puts a component back into the enablement a pass found, and does nothing when it never left
     * it, so a cleanup after a failure is not a state change of its own.
     *
     * @param description the component
     * @param name the component name, for failure messages
     * @param wasEnabled the enablement the pass found
     */
    private void putBack(ComponentDescriptionDTO description, String name, boolean wasEnabled)
    {
        if (wasEnabled == scr.isComponentEnabled(description))
        {
            return;
        }
        if (wasEnabled)
        {
            switchOn(description, name);
        }
        else
        {
            switchOff(description, name);
        }
    }

    /**
     * Waits for what the framework handed back, and fails naming the component when it does not
     * finish or fails.
     *
     * @param operation the promise the framework answered an enable or a disable with
     * @param what the step, for failure messages
     */
    private static void finished(Promise<Void> operation, String what)
    {
        try
        {
            operation.timeout(SETTLE_MILLIS).getValue();
        }
        catch (InvocationTargetException raised)
        {
            Throwable failure = raised.getCause() == null ? raised : raised.getCause();
            if (failure instanceof TimeoutException)
            {
                throw new AssertionError(what + " did not finish within " + SETTLE_MILLIS + "ms", //$NON-NLS-1$ //$NON-NLS-2$
                    failure);
            }
            throw new AssertionError(what + " failed", failure); //$NON-NLS-1$
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            throw new AssertionError(what + " was interrupted", interrupted); //$NON-NLS-1$
        }
    }

    /**
     * Waits for a state to arrive, and fails naming it when the bound passes without it.
     *
     * @param what the awaited state, for the failure message
     * @param state the state, read afresh on every poll
     */
    private static void await(String what, BooleanSupplier state)
    {
        if (!arrived(state, SETTLE_MILLIS))
        {
            throw new AssertionError("waited " + SETTLE_MILLIS + "ms for " + what); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Polls a state until it holds or the time is up, reading it before the first pause, so a state
     * that is already there is never waited for.
     *
     * @param state the state, read afresh on every poll
     * @param millis how long to keep asking
     * @return whether the state was reached
     */
    private static boolean arrived(BooleanSupplier state, long millis)
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (true)
        {
            if (state.getAsBoolean())
            {
                return true;
            }
            if (System.nanoTime() >= deadline)
            {
                return false;
            }
            try
            {
                Thread.sleep(POLL_MILLIS);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Puts the road the bundle held back. The probe has already gone, so the framework's own
     * reference usually answers with the road that was there before - and when it has not done so
     * by the end, the holder is told directly, so the bundle is never left holding a road a test
     * made up.
     */
    private void theRoadIsPutBack()
    {
        if (!arrived(() -> ToolRoadHolder.road() == roadBefore, SETTLE_MILLIS))
        {
            ToolRoadHolder holder = new ToolRoadHolder();
            IToolRoad held = ToolRoadHolder.road();
            if (held != null)
            {
                holder.unbind(held);
            }
            if (roadBefore != null)
            {
                holder.bind(roadBefore);
            }
        }
        assertSame("the road the bundle held was not taken back", roadBefore, ToolRoadHolder.road()); //$NON-NLS-1$
    }

    /**
     * The description of a component of the bundle that owns the tool.
     *
     * @param name the component name
     * @return its description
     */
    private ComponentDescriptionDTO component(String name)
    {
        assertNotNull("this runtime has no component runtime service", scr); //$NON-NLS-1$
        ComponentDescriptionDTO description = scr.getComponentDescriptionDTO(host(), name);
        assertNotNull("the bundle declares no component named " + name, description); //$NON-NLS-1$
        return description;
    }

    /**
     * The active configuration of a component: the state every step of a pass is about, and the
     * identity that shows whether the component was restarted or kept.
     *
     * @param name the component name
     * @return its active configuration
     */
    private ComponentConfigurationDTO activeConfiguration(String name)
    {
        Collection<ComponentConfigurationDTO> configurations =
            scr.getComponentConfigurationDTOs(component(name));
        for (ComponentConfigurationDTO configuration : configurations)
        {
            if (configuration.state == ComponentConfigurationDTO.ACTIVE)
            {
                return configuration;
            }
        }
        throw new AssertionError("no active configuration of " + name + " among " + configurations); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The bundle of the host of this fragment, which is the bundle that declares the components. */
    private Bundle host()
    {
        Bundle bundle = FrameworkUtil.getBundle(ToolRoadHolder.class);
        assertNotNull("the bundle that declares the components is not in this runtime", bundle); //$NON-NLS-1$
        return bundle;
    }

    /** The description of a component, named the way the bundle declares it. */
    private ComponentDescriptionDTO tool()
    {
        return component(TOOL_COMPONENT);
    }

    private ComponentDescriptionDTO provider()
    {
        return component(PROVIDER_COMPONENT);
    }

    private ComponentDescriptionDTO holder()
    {
        return component(HOLDER_COMPONENT);
    }

    /**
     * The live tool services that answer a given name, taken and released one by one, so that a
     * pass reads the same state the framework hands out.
     *
     * @param name the tool name to look for
     * @return the published tools that answer it
     */
    private List<IMcpTool> publishedTools(String name)
    {
        List<IMcpTool> found = new ArrayList<>();
        for (ServiceReference<IMcpTool> reference : references(IMcpTool.class))
        {
            IMcpTool tool = context.getService(reference);
            try
            {
                if (name.equals(tool.getName()))
                {
                    found.add(tool);
                }
            }
            finally
            {
                context.ungetService(reference);
            }
        }
        return found;
    }

    /**
     * How many module source provider services the framework publishes, whatever bundle they came
     * from - a provider carries no name to count by, so a pass measures this as a difference around
     * the count it recorded.
     *
     * @return the number of published provider services
     */
    private int providerServices()
    {
        return references(IModuleSourceProvider.class).size();
    }

    /**
     * The references the framework hands out for a service type, the framework's own answer for
     * "nothing matches" - which is null here rather than an empty collection - turned into an empty
     * one, so a pass counts and iterates the same way whether a service is there or not.
     *
     * @param type the service type
     * @return the references, empty when nothing matches
     */
    private <T> Collection<ServiceReference<T>> references(Class<T> type)
    {
        try
        {
            Collection<ServiceReference<T>> found = context.getServiceReferences(type, null);
            return found == null ? List.of() : found;
        }
        catch (InvalidSyntaxException impossible)
        {
            // No filter is given, so there is no filter syntax to be wrong.
            throw new AssertionError(impossible);
        }
    }

    /** How many providers of this bundle the plugin's module registry holds. */
    private static int ordinaryProviders()
    {
        int found = 0;
        for (IModuleSourceProvider provider : ModuleSources.providers())
        {
            if (provider instanceof OrdinaryFormModuleProvider)
            {
                found++;
            }
        }
        return found;
    }

    /** The names of the tools the catalogue offers for the surface. */
    private static Collection<String> listedNames()
    {
        List<String> names = new ArrayList<>();
        for (IMcpTool tool : McpToolCatalog.getInstance().getEnabledTools())
        {
            names.add(tool.getName());
        }
        return names;
    }

    /**
     * Publishes a road with the highest ranking for selection on the next binding.
     *
     * @param road the road to publish
     * @return its registration
     */
    private ServiceRegistration<IToolRoad> publishRoad(IToolRoad road)
    {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(Constants.SERVICE_RANKING, Integer.valueOf(Integer.MAX_VALUE));
        return context.registerService(IToolRoad.class, road, properties);
    }

    /** A road that stands in for one published by another bundle: every call is refused by name. */
    private static final class AProbeRoad implements IToolRoad
    {
        private final String name;

        AProbeRoad(String name)
        {
            this.name = name;
        }

        @Override
        public ToolRoadOutcome call(String tool, Map<String, Object> arguments, String origin)
        {
            return ToolRoadOutcome.refused(name);
        }

        @Override
        public ToolRoadOutcome resume(String runKey, long waitMillis)
        {
            return ToolRoadOutcome.refused(name);
        }

        @Override
        public boolean cancel(String runKey)
        {
            return false;
        }
    }
}
