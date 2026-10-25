/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */

package ru.aiedt.mcp.server.toolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.McpHistory;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.support.ToolRoad;

/**
 * Checks external-tool declaration gates under every write-blocking preset.
 * <p>
 * Invalid declarations are checked through the catalogue and ToolRoad with an execute counter.
 * Two disabled capabilities allow a reading call; a declared registered capability that remains
 * enabled keeps the whole writing tool blocked. The synthetic services do not exercise ordinary
 * forms, Designer or database writes.
 * </p>
 */
public class ADeclaredWriteSurfaceHasToBeClosedTest
{
    /** The wire name of the reader published as the known, open door. */
    private static final String READER = "declared_surface_probe_reader"; //$NON-NLS-1$

    /** A write door every write-blocking preset disables (the ordinary-forms door). */
    private static final String FIRST_GATE = "ordinary_forms_writes"; //$NON-NLS-1$

    /** A second write door every write-blocking preset disables (the form-constructor door). */
    private static final String SECOND_GATE = "edit_form_writes"; //$NON-NLS-1$

    /** A door name no preset disables. */
    private static final String UNKNOWN_GATE = "declared_surface_probe_unknown_writes"; //$NON-NLS-1$

    /** The presets that block writes, in the order the variants are exercised. */
    private static final ToolProfile[] WRITE_BLOCKING =
        { ToolProfile.READ_ONLY, ToolProfile.CODE_REVIEW, ToolProfile.DEBUG_AND_TEST };

    private BundleContext context;

    private final List<ServiceRegistration<?>> registrations = new ArrayList<>();

    private IPreferenceStore store;

    private String presetBefore;

    private boolean presetWasDefault;

    private String disabledBefore;

    private boolean disabledWasDefault;

    /**
     * Takes the live context and store and remembers the raw preference strings of both policy
     * keys together with their is-default bits, so the restore can tell an untouched default
     * from an explicit value.
     */
    @Before
    public void aContextAStoreAndThePolicyAsFound()
    {
        context = FrameworkUtil.getBundle(ADeclaredWriteSurfaceHasToBeClosedTest.class).getBundleContext();
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        presetWasDefault = store.isDefault(PrefKeys.PREF_TOOL_PRESET);
        disabledBefore = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
        disabledWasDefault = store.isDefault(PrefKeys.PREF_DISABLED_TOOLS);
        McpHistory.clear();
    }

    /**
     * Withdraws the probe services this test published - and nothing else - and puts both
     * preference keys back to the value or the default they had, whatever the checks did.
     */
    @After
    public void thePolicyAndTheProbesGoBack()
    {
        for (ServiceRegistration<?> registration : registrations)
        {
            try
            {
                registration.unregister();
            }
            catch (IllegalStateException alreadyGone)
            {
                // withdrawn by the test itself
            }
        }
        restore(PrefKeys.PREF_TOOL_PRESET, presetBefore, presetWasDefault);
        restore(PrefKeys.PREF_DISABLED_TOOLS, disabledBefore, disabledWasDefault);
        McpHistory.clear();
    }

    /**
     * Every declaration that is not proof of a closed surface keeps the whole-tool refusal, in
     * every write-blocking preset: the interface default (no override at all), an empty list, a
     * null list, a null entry, an empty string, a whitespace string, a door no preset disables,
     * and a door that names the known, registered, still open reader. For each variant and each
     * preset the catalogue refuses the tool, the road turns the call away, and the execute
     * counter stays at exactly zero.
     */
    @Test
    public void everyNonProofDeclarationIsRefusedUnderEveryWriteBlockingPreset()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        BaseProbe reader = new BaseProbe(READER);
        publish(reader, false);

        String[] labels = {
            "no getGatedWriteNames override (the interface default)", //$NON-NLS-1$
            "an empty door list", //$NON-NLS-1$
            "a null door list", //$NON-NLS-1$
            "a null entry in the door list", //$NON-NLS-1$
            "an empty string as a door", //$NON-NLS-1$
            "a whitespace string as a door", //$NON-NLS-1$
            "a door no preset disables", //$NON-NLS-1$
            "a known, registered, but open door" //$NON-NLS-1$
        };
        BaseProbe[] probes = {
            new BaseProbe(probeName("no_override")), //$NON-NLS-1$
            new GatedProbe(probeName("empty_list"), List.of()), //$NON-NLS-1$
            new GatedProbe(probeName("null_list"), null), //$NON-NLS-1$
            new GatedProbe(probeName("null_entry"), Arrays.asList(new String[] { null })), //$NON-NLS-1$
            new GatedProbe(probeName("empty_string"), List.of("")), //$NON-NLS-1$ //$NON-NLS-2$
            new GatedProbe(probeName("whitespace"), List.of(" ")), //$NON-NLS-1$ //$NON-NLS-2$
            new GatedProbe(probeName("unknown_name"), List.of(UNKNOWN_GATE)), //$NON-NLS-1$
            new GatedProbe(probeName("known_open"), List.of(READER)) //$NON-NLS-1$
        };
        for (BaseProbe probe : probes)
        {
            publish(probe, true);
        }

        for (ToolProfile profile : WRITE_BLOCKING)
        {
            selectPreset(profile);
            assertSame(profile + ": the known reader really is registered", //$NON-NLS-1$
                reader, catalog.getTool(READER));
            assertTrue(profile + ": the known reader stays callable", catalog.isToolEnabled(READER)); //$NON-NLS-1$
            assertFalse(profile + ": the preset does not disable the known reader", //$NON-NLS-1$
                catalog.isDisabledByPreset(READER));
            for (int i = 0; i < probes.length; i++)
            {
                assertRefused(profile, labels[i], probes[i]);
            }
        }
    }

    /**
     * The positive control: two doors chosen because every write-blocking preset disables them
     * both - asserted as a precondition, not assumed - keep the mixed tool callable and listed
     * for its reads, and a road call executes the probe exactly once per preset.
     */
    @Test
    public void twoClosedDoorsKeepTheReadsUnderEveryWriteBlockingPreset()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        GatedProbe twoClosed = new GatedProbe(probeName("two_closed"), List.of(FIRST_GATE, SECOND_GATE)); //$NON-NLS-1$
        publish(twoClosed, true);

        for (ToolProfile profile : WRITE_BLOCKING)
        {
            selectPreset(profile);
            assertTrue(profile + ": the first door is one this preset disables", //$NON-NLS-1$
                profile.getDisabledTools().contains(FIRST_GATE));
            assertTrue(profile + ": the second door is one this preset disables", //$NON-NLS-1$
                profile.getDisabledTools().contains(SECOND_GATE));
            assertTrue(profile + ": the first door is disabled by name", //$NON-NLS-1$
                catalog.isDisabledByPreset(FIRST_GATE));
            assertTrue(profile + ": the second door is disabled by name", //$NON-NLS-1$
                catalog.isDisabledByPreset(SECOND_GATE));
            assertTrue(profile + ": a tool with every door closed keeps its reads", //$NON-NLS-1$
                catalog.isToolEnabled(twoClosed.getName()));

            int before = twoClosed.calls.get();
            ToolRoadOutcome outcome =
                new ToolRoad(new Semaphore(1)).call(twoClosed.getName(), Map.of(), "probe-test"); //$NON-NLS-1$
            assertFalse(profile + ": the road lets the reading call through", outcome.refused()); //$NON-NLS-1$
            assertEquals("ran", outcome.text()); //$NON-NLS-1$
            assertEquals(profile + ": execute ran exactly once for this preset", //$NON-NLS-1$
                before + 1, twoClosed.calls.get());
        }
        assertEquals("execute ran once per write-blocking preset and no more", //$NON-NLS-1$
            WRITE_BLOCKING.length, twoClosed.calls.get());
    }

    /**
     * A closed door beside a known, registered, still open one is one door too few: the write
     * behind the open door stays reachable, so the whole-tool refusal stands in every
     * write-blocking preset and execute is never reached.
     */
    @Test
    public void aClosedDoorBesideAKnownOpenOneStaysBlockedUnderEveryWriteBlockingPreset()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        BaseProbe reader = new BaseProbe(READER);
        publish(reader, false);
        GatedProbe closedAndOpen =
            new GatedProbe(probeName("closed_and_open"), List.of(FIRST_GATE, READER)); //$NON-NLS-1$
        publish(closedAndOpen, true);

        for (ToolProfile profile : WRITE_BLOCKING)
        {
            selectPreset(profile);
            assertTrue(profile + ": the closed door really is disabled", //$NON-NLS-1$
                catalog.isDisabledByPreset(FIRST_GATE));
            assertSame(profile + ": the open door names a registered tool", //$NON-NLS-1$
                reader, catalog.getTool(READER));
            assertTrue(profile + ": the open door really is open", catalog.isToolEnabled(READER)); //$NON-NLS-1$
            assertFalse(profile + ": the preset leaves the open door alone", //$NON-NLS-1$
                catalog.isDisabledByPreset(READER));
            assertRefused(profile, "one closed door beside a known open one", closedAndOpen); //$NON-NLS-1$
        }
    }

    /**
     * Asserts one refused variant under the preset currently in force: the catalogue's call
     * gate says no, the road turns the call away, and the probe's execute counter is exactly
     * zero.
     *
     * @param profile the preset in force, for the assertion messages
     * @param label what the variant declares, for the assertion messages
     * @param probe the probe on trial
     */
    private static void assertRefused(ToolProfile profile, String label, BaseProbe probe)
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        String context = profile + " / " + label; //$NON-NLS-1$
        assertFalse(context + ": the whole tool is refused by the catalogue", //$NON-NLS-1$
            catalog.isToolEnabled(probe.getName()));
        ToolRoadOutcome outcome =
            new ToolRoad(new Semaphore(1)).call(probe.getName(), Map.of(), "probe-test"); //$NON-NLS-1$
        assertTrue(context + ": the road turns the call away", outcome.refused()); //$NON-NLS-1$
        assertEquals(context + ": execute is never reached", 0, probe.calls.get()); //$NON-NLS-1$
    }

    /**
     * Selects a preset and clears the explicit disabled list, so the preset's own disabled set
     * - not a leftover hand-picked list - is what the gate reads.
     *
     * @param profile the preset to put in force
     */
    private void selectPreset(ToolProfile profile)
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, profile.name());
        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, ""); //$NON-NLS-1$
    }

    /**
     * Publishes a probe as an {@link IMcpTool} service with the declared write flag and waits,
     * bounded, for the whiteboard to register it.
     *
     * @param tool the probe to publish
     * @param writes the value of {@link ToolWhiteboard#WRITES} to declare
     */
    private void publish(IMcpTool tool, boolean writes)
    {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(ToolWhiteboard.WRITES, Boolean.valueOf(writes));
        registrations.add(context.registerService(IMcpTool.class, tool, properties));
        waitFor(tool.getName());
    }

    /**
     * Puts one preference key back to the state the test found it in: the default when it was
     * the default, the remembered raw string otherwise.
     *
     * @param key the preference key
     * @param value the raw string the key held before the test
     * @param wasDefault whether the key was still on its default before the test
     */
    private void restore(String key, String value, boolean wasDefault)
    {
        if (wasDefault)
        {
            store.setToDefault(key);
        }
        else
        {
            store.setValue(key, value);
        }
    }

    /**
     * Builds a wire name unique to this test class and the case it stands for.
     *
     * @param caseId the case the probe stands for
     * @return the unique wire name
     */
    private static String probeName(String caseId)
    {
        return "declared_surface_probe_" + caseId; //$NON-NLS-1$
    }

    /**
     * Waits, bounded, for the whiteboard to register a tool under the given name.
     *
     * @param name the wire name to await
     */
    private static void waitFor(String name)
    {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (McpToolCatalog.getInstance().getTool(name) == null)
        {
            if (System.currentTimeMillis() > deadline)
            {
                throw new IllegalStateException("the whiteboard never picked up " + name); //$NON-NLS-1$
            }
            sleep(20L);
        }
    }

    /**
     * Sleeps, restoring the interrupt flag when woken.
     *
     * @param millis how long to sleep
     */
    private static void sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A probe that counts its calls and deliberately does <em>not</em> override
     * {@link IMcpTool#getGatedWriteNames()}, so the real interface default - an empty list - is
     * what the no-override variant puts on trial. Not final: {@link GatedProbe} adds the
     * declaration.
     */
    private static class BaseProbe implements IMcpTool
    {
        private final String name;

        final AtomicInteger calls = new AtomicInteger();

        /**
         * Creates a counting probe that inherits the interface's default write declaration.
         *
         * @param name the wire name - and so the capability id - to publish under
         */
        BaseProbe(String name)
        {
            this.name = name;
        }

        /**
         * Returns the wire name this probe was published under.
         *
         * @return the wire name, never <code>null</code>
         */
        @Override
        public String getName()
        {
            return name;
        }

        /**
         * Returns the probe's description.
         *
         * @return a fixed description
         */
        @Override
        public String getDescription()
        {
            return "a probe"; //$NON-NLS-1$
        }

        /**
         * Returns the probe's input schema: it takes no arguments.
         *
         * @return an empty object schema
         */
        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        /**
         * Counts the call and answers a fixed document.
         *
         * @param params the arguments, ignored
         * @return the fixed answer
         */
        @Override
        public String execute(Map<String, String> params)
        {
            calls.incrementAndGet();
            return "ran"; //$NON-NLS-1$
        }
    }

    /**
     * A probe that declares the write doors it was handed, standing in for a bundle that names
     * its write surface - well or badly.
     */
    private static final class GatedProbe extends BaseProbe
    {
        private final List<String> doors;

        /**
         * Creates a counting probe with the exact supplied write declaration.
         *
         * @param name the wire name to publish under
         * @param doors the declared write doors; may be {@code null} and may hold {@code null}
         *            or blank entries, which is part of what is on trial
         */
        GatedProbe(String name, List<String> doors)
        {
            super(name);
            this.doors = doors;
        }

        /**
         * Returns the declaration this probe was built with, verbatim.
         *
         * @return the declared doors, exactly as supplied
         */
        @Override
        public List<String> getGatedWriteNames()
        {
            return doors;
        }
    }
}
