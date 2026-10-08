/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.BreakpointAccess;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * A breakpoint that is in the way can be switched off and switched back on, and a batch can become a
 * module's whole set - both without losing the lines that are already there.
 *
 * <p>A breakpoint used to have two answers: remove it, or keep stopping at it. The state is now asked
 * for by name ({@code breakpointEnabled}), so a call that leaves it out is refused rather than
 * guessed: both defaults stop a client somebody wanted running.</p>
 *
 * <p>The state and the clearing are exercised against fake debug objects, which is where the decision
 * lives; that the platform honors a disabled breakpoint is a property of the platform and is measured
 * on the stand.</p>
 */
public class ABreakpointIsSwitchedOffWithoutBeingRemovedTest
{
    // ---- switching one breakpoint ----------------------------------------------------------

    @Test
    public void switchingOffKeepsTheBreakpointAndItsLine() throws Exception
    {
        FakeBreakpoint breakpoint = new FakeBreakpoint(41L, true, "/project/src/CommonModules/МойМодуль/Module.bsl", 12); //$NON-NLS-1$

        JsonObject answer = json(BreakpointStateSetter.setState(id -> breakpoint.asBreakpoint(), 41L, false));

        assertEquals("disabled", answer.get("outcome").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the breakpoint is switched off, not removed", breakpoint.enabled); //$NON-NLS-1$
        assertEquals("the state was written once", 1, breakpoint.writes); //$NON-NLS-1$
        assertEquals(true, answer.get("changed").getAsBoolean()); //$NON-NLS-1$
        assertEquals(false, answer.get("enabled").getAsBoolean()); //$NON-NLS-1$
        assertEquals("the answer names the line the caller is switching", 12, //$NON-NLS-1$
            answer.get("lineNumber").getAsInt()); //$NON-NLS-1$
        assertTrue(answer.get("file").getAsString().contains("МойМодуль")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void switchingBackOnIsReportedAsSuch() throws Exception
    {
        FakeBreakpoint breakpoint = new FakeBreakpoint(42L, false, "/project/src/Module.bsl", 3); //$NON-NLS-1$

        JsonObject answer = json(BreakpointStateSetter.setState(id -> breakpoint.asBreakpoint(), 42L, true));

        assertEquals("enabled", answer.get("outcome").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(breakpoint.enabled);
        assertEquals(1, breakpoint.writes);
    }

    @Test
    public void aStateThatIsAlreadySetWritesNothing() throws Exception
    {
        FakeBreakpoint breakpoint = new FakeBreakpoint(43L, false, "/project/src/Module.bsl", 7); //$NON-NLS-1$

        JsonObject answer = json(BreakpointStateSetter.setState(id -> breakpoint.asBreakpoint(), 43L, false));

        assertEquals("disabled", answer.get("outcome").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(false, answer.get("changed").getAsBoolean()); //$NON-NLS-1$
        assertEquals("nothing needed writing", 0, breakpoint.writes); //$NON-NLS-1$
        assertTrue(answer.get("note").getAsString().contains("already disabled")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anIdNoBreakpointCarriesIsRefusedWithTheWayToFindOne() throws Exception
    {
        JsonObject answer = json(BreakpointStateSetter.setState(id -> null, 777L, true));

        assertEquals("error", answer.get("outcome").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.get("error").getAsString().contains("777")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(777L, answer.get("breakpointId").getAsLong()); //$NON-NLS-1$
        assertEquals("list_breakpoints", //$NON-NLS-1$
            answer.getAsJsonObject("helpHint").get("arguments").getAsJsonObject().get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- what the tool refuses before it touches anything -----------------------------------

    @Test
    public void theStateHasToBeSpelledOut()
    {
        JsonObject answer = json(new BreakpointStateSetter()
            .execute(FakeDebugToolCalls.args("breakpointId", "41"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("error", answer.get("outcome").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal names the argument that is missing: " + answer, //$NON-NLS-1$
            answer.get("error").getAsString().contains("breakpointEnabled")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theSchemaInsistsOnTheStateToo()
    {
        JsonObject schema = JsonParser.parseString(new BreakpointStateSetter().getInputSchema())
            .getAsJsonObject();

        boolean insisted = false;
        for (com.google.gson.JsonElement name : schema.getAsJsonArray("required")) //$NON-NLS-1$
        {
            insisted |= "breakpointEnabled".equals(name.getAsString()); //$NON-NLS-1$
        }
        assertTrue("the schema must carry the state in required, or a client learns it is " //$NON-NLS-1$
            + "optional and sends no state: " + schema, insisted); //$NON-NLS-1$
    }

    @Test
    public void anIdThatIsNotAnIdIsRefused()
    {
        JsonObject answer = json(new BreakpointStateSetter().execute(
            FakeDebugToolCalls.args("breakpointId", "the first one", "breakpointEnabled", "false"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals("error", answer.get("outcome").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(answer.get("error").getAsString().contains("numeric id")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- a batch that replaces a module's set ----------------------------------------------

    @Test
    public void aBatchClearsEachAddressedModuleOnce() throws Exception
    {
        List<Map<String, String>> items = new ArrayList<>();
        items.add(moduleItem("MyProject", "CommonModule.Первый")); //$NON-NLS-1$ //$NON-NLS-2$
        items.add(moduleItem("MyProject", "CommonModule.Первый")); //$NON-NLS-1$ //$NON-NLS-2$
        items.add(moduleItem("MyProject", "CommonModule.Второй")); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> calls = new ArrayList<>();

        List<Map<String, Object>> cleared = BreakpointSetter.clearModuleSet(items, (project, module) ->
        {
            calls.add(project + "|" + module); //$NON-NLS-1$ //$NON-NLS-2$
            return module.endsWith("Второй") //$NON-NLS-1$
                ? new BreakpointAccess.Removal(5, List.of(21, 22, 23, 24, 25)) //$NON-NLS-1$
                : new BreakpointAccess.Removal(3, List.of(11, 12, 13)); //$NON-NLS-1$
        });

        assertEquals("a module named twice is cleared once", 2, calls.size()); //$NON-NLS-1$
        assertEquals("MyProject|CommonModule.Первый", calls.get(0)); //$NON-NLS-1$
        assertEquals("MyProject|CommonModule.Второй", calls.get(1)); //$NON-NLS-1$
        assertEquals(2, cleared.size());
        assertEquals(3, ((Integer)cleared.get(0).get("removedCount")).intValue()); //$NON-NLS-1$
        assertEquals(5, ((Integer)cleared.get(1).get("removedCount")).intValue()); //$NON-NLS-1$
        assertEquals("the answer names the lines the removed breakpoints sat on", //$NON-NLS-1$
            List.of(11, 12, 13), cleared.get(0).get("removedLines")); //$NON-NLS-1$
        assertEquals("MyProject", cleared.get(0).get("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aModuleThatCannotBeClearedLeavesTheOthersReplaced() throws Exception
    {
        List<Map<String, String>> items = new ArrayList<>();
        items.add(moduleItem("MyProject", "CommonModule.Первый")); //$NON-NLS-1$ //$NON-NLS-2$
        items.add(moduleItem("MyProject", "CommonModule.Второй")); //$NON-NLS-1$ //$NON-NLS-2$

        List<Map<String, Object>> cleared = BreakpointSetter.clearModuleSet(items, (project, module) ->
        {
            if (module.endsWith("Второй")) //$NON-NLS-1$
            {
                throw new IllegalStateException("module not found"); //$NON-NLS-1$
            }
            return new BreakpointAccess.Removal(2, List.of(31, 32)); //$NON-NLS-1$
        });

        assertEquals(2, cleared.size());
        assertEquals(2, ((Integer)cleared.get(0).get("removedCount")).intValue()); //$NON-NLS-1$
        assertEquals("module not found", cleared.get(1).get("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a module that failed is not reported as removed", //$NON-NLS-1$
            cleared.get(1).containsKey("removedCount")); //$NON-NLS-1$
    }

    @Test
    public void anItemThatNamesNoModuleIsNotCleared() throws Exception
    {
        List<Map<String, String>> items = new ArrayList<>();
        items.add(moduleItem("MyProject", "")); //$NON-NLS-1$ //$NON-NLS-2$
        items.add(moduleItem(null, "CommonModule.Первый")); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> calls = new ArrayList<>();
        List<Map<String, Object>> cleared = BreakpointSetter.clearModuleSet(items, (project, module) ->
        {
            calls.add(module);
            return new BreakpointAccess.Removal(1, List.of(41)); //$NON-NLS-1$
        });

        assertEquals("only the item with a module is cleared", 1, calls.size()); //$NON-NLS-1$
        assertEquals("CommonModule.Первый", calls.get(0)); //$NON-NLS-1$
        assertEquals(1, cleared.size());
        assertFalse("an item with no project does not name one", cleared.get(0).containsKey("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void clearingCountsBreakpointsThatCarryNoLine() throws Exception
    {
        List<Map<String, String>> items = new ArrayList<>();
        items.add(moduleItem("MyProject", "CommonModules/Первый")); //$NON-NLS-1$ //$NON-NLS-2$

        // Two line breakpoints and an exception one: three go, two lines can be named.
        List<Map<String, Object>> cleared = BreakpointSetter.clearModuleSet(items, (project, module) ->
            new BreakpointAccess.Removal(3, List.of(11, 12))); //$NON-NLS-1$

        assertEquals("every removed breakpoint counts, also the one without a line", 3, //$NON-NLS-1$
            ((Integer)cleared.get(0).get("removedCount")).intValue()); //$NON-NLS-1$
        assertEquals("the lines still name only what a caller can re-arm", //$NON-NLS-1$
            List.of(11, 12), cleared.get(0).get("removedLines")); //$NON-NLS-1$
    }

    @Test
    public void replacingWithoutABatchIsRefused()
    {
        JsonObject answer = json(new BreakpointSetter()
            .execute(FakeDebugToolCalls.args("replaceModuleSet", "true"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the refusal says what a replacing call needs: " + answer, //$NON-NLS-1$
            answer.get("error").getAsString().contains("replaceModuleSet needs a `breakpoints` batch")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aReplacingBatchWithAnUnresolvableModuleIsRefusedWhole()
    {
        JsonObject answer = json(new BreakpointSetter().execute(FakeDebugToolCalls.args(
            "projectName", "AProjectTheWorkspaceDoesNotHold", //$NON-NLS-1$ //$NON-NLS-2$
            "replaceModuleSet", "true", //$NON-NLS-1$ //$NON-NLS-2$
            "breakpoints", //$NON-NLS-1$
            "[{\"module\":\"CommonModules/First/Module.bsl\",\"lineNumber\":5}," //$NON-NLS-1$
                + "{\"module\":\"CommonModules/Missing/Module.bsl\",\"lineNumber\":6}]"))); //$NON-NLS-1$

        assertEquals(false, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(BreakpointSetter.REASON_MODULE_NOT_RESOLVED,
            answer.get("reason").getAsString()); //$NON-NLS-1$
        assertEquals("the refusal lists every module that does not resolve", //$NON-NLS-1$
            List.of("CommonModules/First/Module.bsl", "CommonModules/Missing/Module.bsl"), //$NON-NLS-1$ //$NON-NLS-2$
            strings(answer.get("modules").getAsJsonArray())); //$NON-NLS-1$
        assertEquals("nothing was removed", 0, answer.get("removedCount").getAsInt()); //$NON-NLS-1$
        assertFalse("nothing was cleared either", answer.has("clearedModules")); //$NON-NLS-1$
        assertFalse("nothing was armed either", answer.has("breakpointResults")); //$NON-NLS-1$
    }

    @Test
    public void anUnresolvableModuleIsListedOnceHoweverManyItemsNameIt()
    {
        List<Map<String, String>> items = new ArrayList<>();
        items.add(moduleItem("P", "CommonModules/First/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        items.add(moduleItem("P", "CommonModules/First/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        items.add(moduleItem("P", "CommonModules/Second/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        items.add(null);
        items.add(moduleItem("P", "")); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> unresolvable = BreakpointSetter.unresolvableModules(items, (project, module) ->
            module.endsWith("Second/Module.bsl")); //$NON-NLS-1$

        assertEquals(List.of("CommonModules/First/Module.bsl"), unresolvable); //$NON-NLS-1$
    }

    @Test
    public void theSameModuleUnderTwoProjectsIsResolvedForEachProject()
    {
        List<Map<String, String>> items = new ArrayList<>();
        items.add(moduleItem("One", "CommonModules/Shared/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        items.add(moduleItem("Two", "CommonModules/Shared/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> unresolvable = BreakpointSetter.unresolvableModules(items, (project, module) ->
            "One".equals(project)); //$NON-NLS-1$

        assertEquals("the copy under the second project does not resolve, and the batch has to be " //$NON-NLS-1$
            + "refused rather than clear the first project's module: " + unresolvable, //$NON-NLS-1$
            List.of("CommonModules/Shared/Module.bsl"), unresolvable); //$NON-NLS-1$
    }

    @Test
    public void theReverseOrderStillNamesTheModuleThatDoesNotResolve()
    {
        List<Map<String, String>> items = new ArrayList<>();
        items.add(moduleItem("Two", "CommonModules/Shared/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        items.add(moduleItem("One", "CommonModules/Shared/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> unresolvable = BreakpointSetter.unresolvableModules(items, (project, module) ->
            "One".equals(project)); //$NON-NLS-1$

        assertEquals(List.of("CommonModules/Shared/Module.bsl"), unresolvable); //$NON-NLS-1$
    }

    @Test
    public void aReplacingBatchThatArmedNothingAnswersWhatItRemoved()
    {
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("index", Integer.valueOf(0)); //$NON-NLS-1$
        failed.put("module", "CommonModules/First/Module.bsl"); //$NON-NLS-1$
        failed.put("ok", Boolean.FALSE); //$NON-NLS-1$
        failed.put("error", "lineNumber (or line) must be 1 or greater"); //$NON-NLS-1$
        results.add(failed);
        List<Map<String, Object>> cleared = new ArrayList<>();
        Map<String, Object> clearedModule = new LinkedHashMap<>();
        clearedModule.put("module", "CommonModules/First/Module.bsl"); //$NON-NLS-1$
        clearedModule.put("removedCount", Integer.valueOf(2)); //$NON-NLS-1$
        clearedModule.put("removedLines", List.of(12, 40)); //$NON-NLS-1$
        cleared.add(clearedModule);

        JsonObject answer = json(BreakpointSetter.noBreakpointSetAnswer(results, 1, 2, cleared));

        assertEquals(false, answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(BreakpointSetter.REASON_NO_BREAKPOINT_SET,
            answer.get("reason").getAsString()); //$NON-NLS-1$
        assertEquals(0, answer.get("ok").getAsInt()); //$NON-NLS-1$
        assertEquals(1, answer.get("fail").getAsInt()); //$NON-NLS-1$
        assertEquals("the answer names how many breakpoints went", 2, //$NON-NLS-1$
            answer.get("removedCount").getAsInt()); //$NON-NLS-1$
        JsonObject module = answer.getAsJsonArray("clearedModules").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("the answer names the lines the caller can re-arm", //$NON-NLS-1$
            List.of(12, 40), ints(module.getAsJsonArray("removedLines"))); //$NON-NLS-1$
        assertEquals("the per-item reason travels with the refusal", //$NON-NLS-1$
            "lineNumber (or line) must be 1 or greater", //$NON-NLS-1$
            answer.getAsJsonArray("breakpointResults").get(0).getAsJsonObject() //$NON-NLS-1$
                .get("error").getAsString()); //$NON-NLS-1$
    }

    // ---- the argument the state action insists on -------------------------------------------

    @Test
    public void theStateRefusalNamesTheMissingArgument()
    {
        JsonObject answer = json(new BreakpointStateSetter()
            .execute(FakeDebugToolCalls.args("breakpointId", "41"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(BreakpointStateSetter.REASON_MISSING_ARGUMENT,
            answer.get("reason").getAsString()); //$NON-NLS-1$
        assertEquals("breakpointEnabled", answer.get("argument").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theMissingIdRefusalNamesTheMissingArgumentToo()
    {
        JsonObject answer = json(new BreakpointStateSetter().execute(
            FakeDebugToolCalls.args("breakpointEnabled", "true"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(BreakpointStateSetter.REASON_MISSING_ARGUMENT,
            answer.get("reason").getAsString()); //$NON-NLS-1$
        assertEquals("breakpointId", answer.get("argument").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theFacadeDescribesTheStateArgumentWithoutTheWordRequired()
    {
        JsonObject schema = JsonParser.parseString(new LaunchDebuggerTool().getInputSchema())
            .getAsJsonObject();

        String state = schema.getAsJsonObject("properties") //$NON-NLS-1$
            .getAsJsonObject("breakpointEnabled").get("description").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the facade is shared by many actions, so no argument of it is Required: " //$NON-NLS-1$
            + state, state.contains("Required")); //$NON-NLS-1$
        assertTrue("the sentence names the action that insists on the argument", //$NON-NLS-1$
            state.contains("set_breakpoint_state")); //$NON-NLS-1$
        assertTrue("the sentence says the action refuses without it", //$NON-NLS-1$
            state.contains("refuses a call without it")); //$NON-NLS-1$

        String id = schema.getAsJsonObject("properties") //$NON-NLS-1$
            .getAsJsonObject("breakpointId").get("description").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the id sentence carries no Required either: " + id, id.contains("Required")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- fakes -----------------------------------------------------------------------------

    /**
     * @param project the project the item names; may be <code>null</code>
     * @param module the module the item names
     * @return one batch item
     */
    private static Map<String, String> moduleItem(String project, String module)
    {
        Map<String, String> item = new LinkedHashMap<>();
        if (project != null)
        {
            item.put("projectName", project); //$NON-NLS-1$
        }
        item.put("module", module); //$NON-NLS-1$
        return item;
    }

    /**
     * @param array what an answer carried as a list of names
     * @return the names, for an assertEquals against a {@link List#of}
     */
    private static List<String> strings(com.google.gson.JsonArray array)
    {
        List<String> values = new ArrayList<>();
        for (com.google.gson.JsonElement element : array)
        {
            values.add(element.getAsString());
        }
        return values;
    }

    /**
     * @param array what an answer carried as a list of numbers
     * @return the numbers, for an assertEquals against a {@link List#of}
     */
    private static List<Integer> ints(com.google.gson.JsonArray array)
    {
        List<Integer> values = new ArrayList<>();
        for (com.google.gson.JsonElement element : array)
        {
            values.add(Integer.valueOf(element.getAsInt()));
        }
        return values;
    }

    /**
     * @param result what the tool built
     * @return the document a client reads
     */
    private static JsonObject json(ToolResult result)
    {
        return FakeDebugToolCalls.json(result.toJson());
    }

    /**
     * @param answer what the tool returned as text
     * @return the document a client reads
     */
    private static JsonObject json(String answer)
    {
        return FakeDebugToolCalls.json(answer);
    }

    /** A breakpoint as the setter meets it: an id, a state, and where it sits. */
    private static final class FakeBreakpoint
    {
        private final long id;

        private final String file;

        private final int line;

        private boolean enabled;

        private int writes;

        /**
         * @param id the marker id the breakpoint answers to
         * @param enabled whether it is enabled to begin with
         * @param file the file it sits in
         * @param line the line it sits on
         */
        FakeBreakpoint(long id, boolean enabled, String file, int line)
        {
            this.id = id;
            this.enabled = enabled;
            this.file = file;
            this.line = line;
        }

        /**
         * @return the breakpoint, as the tools meet it
         */
        IBreakpoint asBreakpoint()
        {
            return (IBreakpoint)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { ILineBreakpoint.class }, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                    case "isEnabled": //$NON-NLS-1$
                        return Boolean.valueOf(enabled);
                    case "setEnabled": //$NON-NLS-1$
                        enabled = ((Boolean)args[0]).booleanValue();
                        writes++;
                        return null;
                    case "getMarker": //$NON-NLS-1$
                        return marker();
                    case "getLineNumber": //$NON-NLS-1$
                        return Integer.valueOf(line);
                    case "equals": //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    case "hashCode": //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    case "toString": //$NON-NLS-1$
                        return "breakpoint " + id; //$NON-NLS-1$
                    default:
                        return null;
                    }
                });
        }

        private IMarker marker()
        {
            IResource resource = (IResource)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { IResource.class }, (proxy, method, args) ->
                    "getFullPath".equals(method.getName()) ? path() : null); //$NON-NLS-1$
            return (IMarker)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { IMarker.class }, (proxy, method, args) ->
                {
                    switch (method.getName())
                    {
                    case "getId": //$NON-NLS-1$
                        return Long.valueOf(id);
                    case "getResource": //$NON-NLS-1$
                        return resource;
                    default:
                        return null;
                    }
                });
        }

        private IPath path()
        {
            return (IPath)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { IPath.class }, (proxy, method, args) ->
                    "toString".equals(method.getName()) ? file : null); //$NON-NLS-1$
        }
    }
}
