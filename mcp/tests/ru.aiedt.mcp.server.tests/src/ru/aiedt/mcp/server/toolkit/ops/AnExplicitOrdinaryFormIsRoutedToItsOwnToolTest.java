/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
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
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ToolWhiteboard;
import ru.aiedt.mcp.server.wire.GsonHolder;

/**
 * What {@code edit_metadata create_form} does with an explicit {@code formType=ORDINARY}: it hands
 * the call to the ordinary-forms tool through the tool road, before the CommonForm shortcut and the
 * managed BM transaction, and nothing of the managed machinery runs for it.
 * <p>
 * The ordinary-forms tool of the oform bundle is stood in for by a probe published on the
 * whiteboard under its wire name, which records the flattened arguments it was called with - so
 * the assertions read the arguments the routing actually forwarded, not the ones it documents. A
 * missing tool, a refused road call, a {@code layout} argument and the un-routed managed default
 * are refused or kept on their own paths, each by name.
 * </p>
 */
public class AnExplicitOrdinaryFormIsRoutedToItsOwnToolTest
{
    private static final String PROJECT_NAME = "aiedt-ordinary-routing-test"; //$NON-NLS-1$

    private BundleContext context;

    private final List<ServiceRegistration<?>> registrations = new ArrayList<>();

    private IPreferenceStore store;

    private String presetBefore;

    private String disabledBefore;

    private OrdinaryProbe probe;

    /**
     * Takes the live context and store, clears the history the road records into, and opens a
     * scratch project the routing can be asked about.
     */
    @Before
    public void aContextAStoreAProjectAndAnEmptyHistory() throws Exception
    {
        context = FrameworkUtil.getBundle(AnExplicitOrdinaryFormIsRoutedToItsOwnToolTest.class)
            .getBundleContext();
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        disabledBefore = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
        McpHistory.clear();
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME);
        if (project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

    /**
     * Puts the preferences back, withdraws the probe and removes the scratch project.
     */
    @After
    public void thePreferencesTheProbeAndTheProjectGo() throws Exception
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
        registrations.clear();
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, disabledBefore);
        McpHistory.clear();
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME);
        if (project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
    }

    /** Only the exact ORDINARY token names the ordinary model; everything else stays managed. */
    @Test
    public void onlyTheExactTokenNamesTheOrdinaryModel()
    {
        assertTrue(FormCreateOps.explicitOrdinary("ORDINARY")); //$NON-NLS-1$
        assertTrue(FormCreateOps.explicitOrdinary("ordinary")); //$NON-NLS-1$
        assertTrue(FormCreateOps.explicitOrdinary("  Ordinary  ")); //$NON-NLS-1$

        assertFalse(FormCreateOps.explicitOrdinary(null));
        assertFalse(FormCreateOps.explicitOrdinary("")); //$NON-NLS-1$
        assertFalse(FormCreateOps.explicitOrdinary(" ")); //$NON-NLS-1$
        assertFalse(FormCreateOps.explicitOrdinary("MANAGED")); //$NON-NLS-1$
        assertFalse(FormCreateOps.explicitOrdinary("managed")); //$NON-NLS-1$
        assertFalse("a legacy purpose value is not the ordinary token", //$NON-NLS-1$
            FormCreateOps.explicitOrdinary("ListForm")); //$NON-NLS-1$
        assertFalse(FormCreateOps.explicitOrdinary("Generic")); //$NON-NLS-1$
    }

    /**
     * The routed call reaches the tool through the road with exactly the shared vocabulary: the
     * arguments edit_metadata declares that the ordinary creation reads, the resolved form name,
     * and nothing else - no formType, no layout, none of the arguments the ordinary tool takes
     * but the edit_metadata schema does not declare.
     */
    @Test
    public void theRoutedCallCarriesTheSharedVocabularyAndNothingElse()
    {
        probe = publishProbe();

        String answer = new FormCreateOps().opCreateForm(argumentsOf(Map.of(
            "formName", "ItemForm1", //$NON-NLS-1$ //$NON-NLS-2$
            "formType", "ORDINARY", //$NON-NLS-1$ //$NON-NLS-2$
            "purpose", "ItemForm", //$NON-NLS-1$ //$NON-NLS-2$
            "synonym", "Goods card", //$NON-NLS-1$ //$NON-NLS-2$
            "title", "Goods", //$NON-NLS-1$ //$NON-NLS-2$
            "setAsDefault", "true", //$NON-NLS-1$ //$NON-NLS-2$
            "dryRun", "true"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, probe.calls.get());
        Map<String, String> seen = probe.lastCall;
        assertNotNull(seen);
        assertEquals("operation is set by the routing", "create_form", seen.get("operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(PROJECT_NAME, seen.get("projectName")); //$NON-NLS-1$
        assertEquals("Catalog.Products", seen.get("ownerFqn")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the resolved form name fills name", "ItemForm1", seen.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ItemForm", seen.get("purpose")); //$NON-NLS-1$
        assertEquals("Goods card", seen.get("synonym")); //$NON-NLS-1$
        assertEquals("Goods", seen.get("title")); //$NON-NLS-1$
        assertEquals("true", seen.get("setAsDefault")); //$NON-NLS-1$
        assertEquals("true", seen.get("dryRun")); //$NON-NLS-1$
        assertEquals("the shared vocabulary is the whole call", 9, seen.size()); //$NON-NLS-1$
        assertFalse("formType is consumed by the routing decision", seen.containsKey("formType")); //$NON-NLS-1$
        assertFalse("layout is refused, not forwarded", seen.containsKey("layout")); //$NON-NLS-1$
        assertFalse("formName is forwarded as name only", seen.containsKey("formName")); //$NON-NLS-1$
        assertFalse("an argument the edit_metadata schema does not declare is not promised", //$NON-NLS-1$
            seen.containsKey("width")); //$NON-NLS-1$
        assertFalse(seen.containsKey("language")); //$NON-NLS-1$

        assertTrue("the answer is the tool's own document: " + answer, //$NON-NLS-1$
            answer.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue("the routing is marked where the answer came from: " + answer, //$NON-NLS-1$
            answer.contains("\"routedTo\":\"ordinary_forms\"")); //$NON-NLS-1$
    }

    /** Every argument outside the metadata route is refused before the ordinary tool executes. */
    @Test
    public void ordinaryOnlyArgumentsAreRefusedBeforeDispatch()
    {
        probe = publishProbe();
        for (String argument : List.of("width", "height", "withFields", "withTables", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "withCommands", "columns", "tabularSections", "language")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            for (String dryRun : List.of("false", "true")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                String answer = new FormCreateOps().opCreateForm(argumentsOf(Map.of(
                    "formName", "ItemForm1", "formType", "ORDINARY", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "dryRun", dryRun, argument, "explicit"))); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(answer, answer.contains("\"success\":false")); //$NON-NLS-1$
                assertTrue(answer, answer.contains(argument));
                assertTrue(answer, answer.contains("ordinary_forms create_form")); //$NON-NLS-1$
                assertEquals("a refused argument must not execute the ordinary tool", 0, probe.calls.get()); //$NON-NLS-1$
            }
        }
    }

    /** A call that named the form under the generic {@code name} reaches the tool the same way. */
    @Test
    public void aCallByNameFillsTheOrdinaryName()
    {
        probe = publishProbe();

        new FormCreateOps().opCreateForm(argumentsOf(Map.of(
            "name", "ByAlias", //$NON-NLS-1$ //$NON-NLS-2$
            "formType", "ORDINARY"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, probe.calls.get());
        assertEquals("ByAlias", probe.lastCall.get("name")); //$NON-NLS-1$
        assertFalse(probe.lastCall.containsKey("formName")); //$NON-NLS-1$
    }

    /**
     * A call that named no form at all is still routed: the tool refuses the missing name in its
     * own words, and that refusal keeps its failure shape - the routing does not re-wrap it as a
     * success of its own.
     */
    @Test
    public void aMissingNameIsRefusedByTheToolInItsOwnWords()
    {
        probe = publishProbe();

        String answer = new FormCreateOps().opCreateForm(argumentsOf(Map.of(
            "formType", "ORDINARY"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, probe.calls.get());
        assertTrue("the tool's own refusal is handed back: " + answer, //$NON-NLS-1$
            answer.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(answer.contains("needs ownerFqn")); //$NON-NLS-1$
        assertTrue(answer.contains("\"routedTo\":\"ordinary_forms\"")); //$NON-NLS-1$
    }

    /**
     * {@code layout} names the managed form's layout policy and has no ordinary meaning: it is
     * refused before the tool is called, because dropping it silently would tell the caller their
     * value was applied.
     */
    @Test
    public void aLayoutArgumentIsRefusedBeforeTheToolRuns()
    {
        probe = publishProbe();

        String answer = new FormCreateOps().opCreateForm(argumentsOf(Map.of(
            "formName", "ItemForm1", //$NON-NLS-1$ //$NON-NLS-2$
            "formType", "ORDINARY", //$NON-NLS-1$ //$NON-NLS-2$
            "layout", "empty"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the refusal names the argument: " + answer, //$NON-NLS-1$
            answer.contains("does not take a layout")); //$NON-NLS-1$
        assertEquals("the tool was not called", 0, probe.calls.get()); //$NON-NLS-1$
    }

    /**
     * Without the ordinary-forms tool the call is refused by name, and the refusal says what was
     * not done: no ordinary form, and no managed form either.
     */
    @Test
    public void withoutTheToolTheRefusalNamesIt()
    {
        String answer = new FormCreateOps().opCreateForm(argumentsOf(Map.of(
            "formName", "ItemForm1", //$NON-NLS-1$ //$NON-NLS-2$
            "formType", "ORDINARY"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the missing tool is named: " + answer, //$NON-NLS-1$
            answer.contains("ordinary_forms")); //$NON-NLS-1$
        assertTrue(answer.contains("not available")); //$NON-NLS-1$
        assertTrue("and nothing was created on either path: " + answer, //$NON-NLS-1$
            answer.contains("was not created")); //$NON-NLS-1$
    }

    /**
     * A tool the preset gate turns away surfaces as the routing's own refusal: the road refused
     * the call, the probe never ran, and the managed path did not take over.
     */
    @Test
    public void aRoadRefusalSurfacesAsTheRoutingRefusal()
    {
        probe = publishProbe();
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ""); //$NON-NLS-1$
        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, "ordinary_forms"); //$NON-NLS-1$

        String answer = new FormCreateOps().opCreateForm(argumentsOf(Map.of(
            "formName", "ItemForm1", //$NON-NLS-1$ //$NON-NLS-2$
            "formType", "ORDINARY"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the road refusal is carried, not swallowed: " + answer, //$NON-NLS-1$
            answer.contains("The ordinary form was not created")); //$NON-NLS-1$
        assertEquals(0, probe.calls.get());
    }

    /**
     * A Pending envelope keeps its {@code runKey} intact and gains only the routing marker: the
     * caller polls the run the tool started exactly as a direct caller would.
     */
    @Test
    public void aPendingEnvelopeKeepsItsRunKey()
    {
        probe = publishProbe();
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("status", "Pending"); //$NON-NLS-1$ //$NON-NLS-2$
        pending.put("pendingEnvelope", Boolean.TRUE); //$NON-NLS-1$
        pending.put("runKey", "routing-probe-1"); //$NON-NLS-1$ //$NON-NLS-2$
        probe.answer = GsonHolder.toJson(pending);

        String answer = new FormCreateOps().opCreateForm(argumentsOf(Map.of(
            "formName", "ItemForm1", //$NON-NLS-1$ //$NON-NLS-2$
            "formType", "ORDINARY"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(1, probe.calls.get());
        assertTrue("the runKey survives the routing: " + answer, //$NON-NLS-1$
            answer.contains("routing-probe-1")); //$NON-NLS-1$
        assertTrue(answer.contains("\"routedTo\":\"ordinary_forms\"")); //$NON-NLS-1$
    }

    /**
     * A tool that ran and threw is a failure, not a refusal: the routing says the creation failed,
     * and the caller who reads the difference does not retry a call that may have applied part of
     * what it was asked for.
     */
    @Test
    public void aThrownToolIsAFailureNotARefusal()
    {
        probe = publishProbe();
        probe.throwOnCall = new IllegalStateException("halfway through");

        String answer = new FormCreateOps().opCreateForm(argumentsOf(Map.of(
            "formName", "ItemForm1", //$NON-NLS-1$ //$NON-NLS-2$
            "formType", "ORDINARY"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the failure is named as a failure: " + answer, //$NON-NLS-1$
            answer.contains("The ordinary form creation failed")); //$NON-NLS-1$
        assertTrue(answer.contains("ordinary_forms")); //$NON-NLS-1$
        assertEquals(1, probe.calls.get());
    }

    /**
     * A CommonForm owner with an explicit ORDINARY goes to the ordinary tool as sent - the
     * managed CommonForm shortcut is what the routing runs before, and the tool's own refusal is
     * the honest answer for a kind it does not create.
     */
    @Test
    public void anExplicitCommonFormOwnerIsRoutedNotRedirected()
    {
        probe = publishProbe();

        String answer = new FormCreateOps().opCreateForm(argumentsOf(Map.of(
            "formName", "Shared", //$NON-NLS-1$ //$NON-NLS-2$
            "formType", "ORDINARY", //$NON-NLS-1$ //$NON-NLS-2$
            "ownerFqn", "CommonForm.Shared"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("the ordinary tool was called, not the CommonForm shortcut", 1, probe.calls.get()); //$NON-NLS-1$
        assertEquals("CommonForm.Shared", probe.lastCall.get("ownerFqn")); //$NON-NLS-1$
        assertTrue(answer.contains("\"routedTo\":\"ordinary_forms\"")); //$NON-NLS-1$
    }

    /**
     * Without the ORDINARY token the managed path is kept: the probe stays uncalled and no
     * routing marker appears, whatever the managed path then answers about this scratch project.
     */
    @Test
    public void aCallWithoutTheTokenKeepsTheManagedPath()
    {
        probe = publishProbe();

        String managed = new FormCreateOps().opCreateForm(argumentsOf(Map.of(
            "formName", "ItemForm1"))); //$NON-NLS-1$
        assertFalse("no routing marker on a managed call: " + managed, //$NON-NLS-1$
            managed.contains("routedTo")); //$NON-NLS-1$

        String legacy = new FormCreateOps().opCreateForm(argumentsOf(Map.of(
            "formName", "ListForm1", //$NON-NLS-1$ //$NON-NLS-2$
            "formType", "ListForm"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a legacy purpose value is a managed formType: " + legacy, //$NON-NLS-1$
            legacy.contains("routedTo")); //$NON-NLS-1$
        assertEquals(0, probe.calls.get());
    }

    private Map<String, String> argumentsOf(Map<String, String> call)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", PROJECT_NAME); //$NON-NLS-1$
        params.put("ownerFqn", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        params.putAll(call);
        return params;
    }

    private OrdinaryProbe publishProbe()
    {
        OrdinaryProbe published = new OrdinaryProbe();
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(ToolWhiteboard.WRITES, Boolean.TRUE);
        registrations.add(context.registerService(IMcpTool.class, published, properties));
        long deadline = System.currentTimeMillis() + 10_000L;
        while (McpToolCatalog.getInstance().getTool("ordinary_forms") == null) //$NON-NLS-1$
        {
            if (System.currentTimeMillis() > deadline)
            {
                throw new IllegalStateException("the whiteboard never picked up ordinary_forms"); //$NON-NLS-1$
            }
            sleep(20L);
        }
        return published;
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

    /**
     * A stand-in for the ordinary-forms tool: it records the flattened arguments of its last call
     * and answers a JSON document, the way the real tool does. Its write door is declared so a
     * write-blocking preset judge has something to read.
     */
    private static final class OrdinaryProbe implements IMcpTool
    {
        final AtomicInteger calls = new AtomicInteger();

        volatile Map<String, String> lastCall;

        volatile String answer;

        volatile RuntimeException throwOnCall;

        OrdinaryProbe()
        {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("operation", "create_form"); //$NON-NLS-1$ //$NON-NLS-2$
            document.put("success", Boolean.TRUE); //$NON-NLS-1$
            document.put("written", Boolean.TRUE); //$NON-NLS-1$
            document.put("formFqn", "Catalog.Products.Form.ItemForm1"); //$NON-NLS-1$ //$NON-NLS-2$
            answer = GsonHolder.toJson(document);
        }

        @Override
        public String getName()
        {
            return "ordinary_forms"; //$NON-NLS-1$
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
        public List<String> getGatedWriteNames()
        {
            return List.of("ordinary_forms_writes"); //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            calls.incrementAndGet();
            lastCall = new LinkedHashMap<>(params);
            RuntimeException thrown = throwOnCall;
            if (thrown != null)
            {
                throw thrown;
            }
            if (!params.containsKey("name")) //$NON-NLS-1$
            {
                Map<String, Object> refusal = new LinkedHashMap<>();
                refusal.put("operation", "create_form"); //$NON-NLS-1$ //$NON-NLS-2$
                refusal.put("success", Boolean.FALSE); //$NON-NLS-1$
                refusal.put("error", "create_form needs ownerFqn (Type.Object) and name."); //$NON-NLS-1$
                return GsonHolder.toJson(refusal);
            }
            return answer;
        }
    }
}
