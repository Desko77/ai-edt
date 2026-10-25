/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */

package ru.aiedt.mcp.server.toolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import ru.aiedt.mcp.server.wire.ToolAnnotations;

/**
 * A mixed tool another bundle publishes - one that names the doors its writes go through - keeps
 * its reading calls under a write-blocking preset, and nothing less than every door actually
 * disabled buys that.
 * <p>
 * The presets name only this server's own tools, so a tool from outside is classified by its
 * bundle's declared write flag and switched off whole. The mixed exception is a contract with the
 * publishing bundle: it gates its own writes on the doors it names, the preset disables them, and
 * the catalogue keeps the tool callable for its reads. A declaration that is empty, blank, or
 * names a door no preset disables is not proof of anything and leaves the whole-tool refusal
 * exactly where it was.
 * </p>
 */
public class AMixedExternalToolKeepsItsReadsUnderWriteBlockingPresetsTest
{
    private static final String MIXED = "mixed_probe_ordinary"; //$NON-NLS-1$

    private static final String MIXED_DOOR = "ordinary_forms_writes"; //$NON-NLS-1$

    private static final String PLAIN_WRITER = "plain_probe_writer"; //$NON-NLS-1$

    private static final String READER = "mixed_probe_reader"; //$NON-NLS-1$

    private BundleContext context;

    private final List<ServiceRegistration<?>> registrations = new ArrayList<>();

    private IPreferenceStore store;

    private String presetBefore;

    private String disabledBefore;

    /**
     * Takes the live context, store and preset, and clears the history the road calls below write
     * into.
     */
    @Before
    public void aContextAStoreAndAnEmptyHistory()
    {
        context = FrameworkUtil.getBundle(AMixedExternalToolKeepsItsReadsUnderWriteBlockingPresetsTest.class)
            .getBundleContext();
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        disabledBefore = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
        McpHistory.clear();
    }

    /**
     * Puts the preset, the disabled list and the probe services back.
     */
    @After
    public void thePresetAndTheProbesGo()
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
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, disabledBefore);
        McpHistory.clear();
    }

    /**
     * Under a write-blocking preset, a mixed tool whose one door the preset disables stays
     * callable, listed, and its call runs through the road; the plain writer next to it is off.
     */
    @Test
    public void aMixedToolRunsItsReadsWithItsDoorClosed()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        CountingProbe mixed = new CountingProbe(MIXED, List.of(MIXED_DOOR));
        publish(mixed, Boolean.TRUE);
        CountingProbe writer = new CountingProbe(PLAIN_WRITER, null);
        publish(writer, Boolean.TRUE);

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());
        assertTrue("the door is disabled by the preset", //$NON-NLS-1$
            ToolProfile.READ_ONLY.getDisabledTools().contains(MIXED_DOOR));
        assertTrue("the mixed tool keeps its reading calls", catalog.isToolEnabled(MIXED)); //$NON-NLS-1$
        assertTrue("the door itself is disabled by name", catalog.isDisabledByPreset(MIXED_DOOR)); //$NON-NLS-1$
        assertTrue(names(catalog.getEnabledTools()).contains(MIXED));
        assertFalse("the plain writer stays off whole", catalog.isToolEnabled(PLAIN_WRITER)); //$NON-NLS-1$

        ToolRoadOutcome outcome = new ToolRoad(new Semaphore(1)).call(MIXED, Map.of(), "probe-test"); //$NON-NLS-1$
        assertEquals("ran", outcome.text()); //$NON-NLS-1$
        assertEquals(1, mixed.calls.get());
        assertEquals(0, writer.calls.get());

        ToolRoadOutcome writerCall =
            new ToolRoad(new Semaphore(1)).call(PLAIN_WRITER, Map.of(), "probe-test"); //$NON-NLS-1$
        assertTrue("the plain writer is turned away by the road too", writerCall.refused()); //$NON-NLS-1$
        assertEquals(0, writer.calls.get());
    }

    /**
     * A door no preset disables - the whole point of the exception is what the preset actually
     * disabled, not what the declaration says - keeps the whole tool blocked and its execute is
     * never reached.
     */
    @Test
    public void anUnknownDoorKeepsTheWholeToolBlockedAndExecuteUncalled()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        CountingProbe mixed = new CountingProbe(MIXED, List.of("unknown_writes")); //$NON-NLS-1$
        publish(mixed, Boolean.TRUE);

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());
        assertFalse(catalog.isToolEnabled(MIXED));
        assertTrue(catalog.isDisabledByPreset(MIXED));
        assertFalse(names(catalog.getEnabledTools()).contains(MIXED));

        ToolRoadOutcome outcome = new ToolRoad(new Semaphore(1)).call(MIXED, Map.of(), "probe-test"); //$NON-NLS-1$
        assertTrue("the road turns the call away", outcome.refused()); //$NON-NLS-1$
        assertEquals("a blocked tool never runs", 0, mixed.calls.get()); //$NON-NLS-1$

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.ALL_TOOLS.name());
        assertTrue("with no preset blocking writes the tool is back", catalog.isToolEnabled(MIXED)); //$NON-NLS-1$
    }

    /**
     * A bundle that said nothing about writing counts as a writer: the safe side of a preset that
     * blocks writes, and no door declaration of its own to soften it.
     */
    @Test
    public void anExternalToolThatSaysNothingCountsAsAWriter()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        CountingProbe silent = new CountingProbe("mixed_probe_silent_writer", null); //$NON-NLS-1$
        Hashtable<String, Object> noFacts = new Hashtable<>();
        registrations.add(context.registerService(IMcpTool.class, silent, noFacts));
        waitFor(silent.getName());

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());
        assertFalse("a tool that declared nothing stays a whole-tool writer", //$NON-NLS-1$
            catalog.isToolEnabled(silent.getName()));

        ToolRoadOutcome outcome = new ToolRoad(new Semaphore(1)).call(silent.getName(), Map.of(), "probe-test"); //$NON-NLS-1$
        assertTrue("the road turns the call away", outcome.refused()); //$NON-NLS-1$
        assertEquals("a blocked tool never runs", 0, silent.calls.get()); //$NON-NLS-1$
    }

    /**
     * The door is read when the call is made, not when the service arrives: a preset already in
     * force before the bundle registered keeps the reads on exactly as a preset chosen after.
     */
    @Test
    public void aDoorClosedBeforeRegistrationKeepsTheReads()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());

        CountingProbe mixed = new CountingProbe(MIXED, List.of(MIXED_DOOR));
        publish(mixed, Boolean.TRUE);
        assertTrue("a tool registered under an active preset keeps its reading calls", //$NON-NLS-1$
            catalog.isToolEnabled(MIXED));

        ToolRoadOutcome outcome = new ToolRoad(new Semaphore(1)).call(MIXED, Map.of(), "probe-test"); //$NON-NLS-1$
        assertEquals("ran", outcome.text()); //$NON-NLS-1$
        assertEquals(1, mixed.calls.get());
    }

    /**
     * An alias is the same capability under another name: with the doors closed the alias runs,
     * and a direct disable of the capability turns both names away.
     */
    @Test
    public void anAliasIsGatedUnderTheCapabilityItResolvesTo()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        CountingProbe aliased = new CountingProbe("mixed_probe_aliased", List.of(MIXED_DOOR)) //$NON-NLS-1$
        {
            @Override
            public List<String> getAliases()
            {
                return List.of("mixed_probe_aliased_v1"); //$NON-NLS-1$
            }
        };
        publish(aliased, Boolean.TRUE);

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());
        ToolRoadOutcome byAlias =
            new ToolRoad(new Semaphore(1)).call("mixed_probe_aliased_v1", Map.of(), "probe-test"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the alias reaches the tool through the same gate", "ran", byAlias.text()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, aliased.calls.get());

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ""); //$NON-NLS-1$
        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, aliased.getName());
        assertFalse("the primary name is off", catalog.isToolEnabled(aliased.getName())); //$NON-NLS-1$
        assertFalse("and so is the alias", catalog.isToolEnabled("mixed_probe_aliased_v1")); //$NON-NLS-1$
    }

    /**
     * The fail-closed cases of the declaration itself: no doors, a null list, a blank name, a
     * null entry, and one open door beside a closed one. None of them is proof of protection, so
     * none of them unblocks the tool. Each variant publishes under its own name: what is on
     * trial is the declaration, not the re-registration of one name.
     */
    @Test
    public void aDeclarationThatIsNoProofChangesNothing()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        CountingProbe noDoors = new CountingProbe("mixed_probe_no_doors", null); //$NON-NLS-1$
        publish(noDoors, Boolean.TRUE);
        CountingProbe blank = new CountingProbe("mixed_probe_blank_door", List.of(" ")); //$NON-NLS-1$ //$NON-NLS-2$
        publish(blank, Boolean.TRUE);
        CountingProbe nullEntry = new CountingProbe("mixed_probe_null_door", //$NON-NLS-1$
            Arrays.asList(new String[] { null }));
        publish(nullEntry, Boolean.TRUE);
        CountingProbe oneOpen = new CountingProbe("mixed_probe_one_open", //$NON-NLS-1$
            List.of(MIXED_DOOR, "unknown_writes")); //$NON-NLS-1$
        publish(oneOpen, Boolean.TRUE);

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());
        assertFalse("no doors named means the whole tool stays off", //$NON-NLS-1$
            catalog.isToolEnabled(noDoors.getName()));
        assertFalse("a blank door is not a door", catalog.isToolEnabled(blank.getName())); //$NON-NLS-1$
        assertFalse("a null entry is not a door", catalog.isToolEnabled(nullEntry.getName())); //$NON-NLS-1$
        assertFalse("one door left open leaves the write behind it reachable", //$NON-NLS-1$
            catalog.isToolEnabled(oneOpen.getName()));
    }

    /**
     * The annotations follow the same rule the gate does: a mixed tool with every door closed is
     * published as a read, with a door open as a writer, and a plain external writer keeps its
     * declared class whatever it declares about doors.
     */
    @Test
    public void theAnnotationsFollowTheDoors()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        CountingProbe mixed = new CountingProbe(MIXED, List.of(MIXED_DOOR));
        publish(mixed, Boolean.TRUE);
        CountingProbe unknown = new CountingProbe("mixed_probe_unknown_door", List.of("unknown_writes")); //$NON-NLS-1$ //$NON-NLS-2$
        publish(unknown, Boolean.TRUE);
        CountingProbe writer = new CountingProbe(PLAIN_WRITER, null);
        publish(writer, Boolean.TRUE);
        CountingProbe reader = new CountingProbe(READER, null);
        publish(reader, Boolean.FALSE);

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());
        assertEquals(Boolean.TRUE, ToolAnnotations.of(mixed, catalog).get("readOnlyHint")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, ToolAnnotations.of(mixed, catalog).get("idempotentHint")); //$NON-NLS-1$
        assertNull("a door no preset disables keeps the writer class", //$NON-NLS-1$
            ToolAnnotations.of(unknown, catalog).get("readOnlyHint")); //$NON-NLS-1$
        assertNull("a plain external writer keeps its declared class", //$NON-NLS-1$
            ToolAnnotations.of(writer, catalog).get("readOnlyHint")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, ToolAnnotations.of(reader, catalog).get("readOnlyHint")); //$NON-NLS-1$

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.ALL_TOOLS.name());
        assertNull("with its door open the mixed tool is a writer", //$NON-NLS-1$
            ToolAnnotations.of(mixed, catalog).get("readOnlyHint")); //$NON-NLS-1$
    }

    /**
     * A preset that does not block writes never switched the tool off, doors or no doors; and a
     * capability disabled directly stays disabled whatever its doors say.
     */
    @Test
    public void anEditingPresetAndADirectDisable()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        CountingProbe mixed = new CountingProbe(MIXED, List.of(MIXED_DOOR));
        publish(mixed, Boolean.TRUE);

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.EDITING.name());
        assertTrue(catalog.isToolEnabled(MIXED));

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ""); //$NON-NLS-1$
        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, MIXED);
        assertFalse("a directly disabled capability stays disabled", catalog.isToolEnabled(MIXED)); //$NON-NLS-1$

        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, ""); //$NON-NLS-1$
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());
        assertTrue(catalog.isToolEnabled(MIXED));
    }

    /**
     * A clear of the catalogue forgets this server's tools and re-registers the external ones
     * with the facts they declared, so the mixed policy survives it - the ordinary-forms bundle
     * publishes once and the server may clear at every start. The server's own tools are put back
     * afterwards, whatever the checks did, so the clear leaves the catalogue the way the test
     * found it.
     */
    @Test
    public void thePolicySurvivesAClear()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        List<IMcpTool> ownTools = new ArrayList<>(catalog.getAllTools());
        ownTools.removeAll(catalog.getExternalTools().values());
        CountingProbe mixed = new CountingProbe(MIXED, List.of(MIXED_DOOR));
        publish(mixed, Boolean.TRUE);

        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());
        try
        {
            catalog.clear();
            assertSame(mixed, catalog.getTool(MIXED));
            assertTrue(catalog.isToolEnabled(MIXED));
            assertTrue(catalog.externalWritesDeclared(MIXED).booleanValue());

            // And the re-registered tool still runs through the road.
            ToolRoadOutcome outcome = new ToolRoad(new Semaphore(1)).call(MIXED, Map.of(), "probe-test"); //$NON-NLS-1$
            assertEquals("ran", outcome.text()); //$NON-NLS-1$
            assertEquals(1, mixed.calls.get());
        }
        finally
        {
            // The clear forgot the server's own tools; every test after this one needs them back.
            for (IMcpTool tool : ownTools)
            {
                catalog.register(tool);
            }
        }
    }

    private void publish(IMcpTool tool, Boolean writes)
    {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(ToolWhiteboard.WRITES, writes);
        registrations.add(context.registerService(IMcpTool.class, tool, properties));
        waitFor(tool.getName());
    }

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

    private static List<String> names(Collection<IMcpTool> tools)
    {
        List<String> out = new ArrayList<>();
        for (IMcpTool tool : tools)
        {
            out.add(tool.getName());
        }
        return out;
    }

    /**
     * A probe that counts its calls and names the write doors it was given. Not final: the alias
     * test extends it with a second callable name.
     */
    private static class CountingProbe implements IMcpTool
    {
        private final String name;

        private final List<String> doors;

        final AtomicInteger calls = new AtomicInteger();

        CountingProbe(String name, List<String> doors)
        {
            this.name = name;
            this.doors = doors;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public List<String> getGatedWriteNames()
        {
            return doors;
        }

        @Override
        public String getDescription()
        {
            return "a probe"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            calls.incrementAndGet();
            return "ran"; //$NON-NLS-1$
        }
    }
}
