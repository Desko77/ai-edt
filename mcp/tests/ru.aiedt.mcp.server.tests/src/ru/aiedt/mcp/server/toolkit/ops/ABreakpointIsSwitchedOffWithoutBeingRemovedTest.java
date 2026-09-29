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
            return module.endsWith("Второй") ? 5 : 3; //$NON-NLS-1$
        });

        assertEquals("a module named twice is cleared once", 2, calls.size()); //$NON-NLS-1$
        assertEquals("MyProject|CommonModule.Первый", calls.get(0)); //$NON-NLS-1$
        assertEquals("MyProject|CommonModule.Второй", calls.get(1)); //$NON-NLS-1$
        assertEquals(2, cleared.size());
        assertEquals(3, ((Integer)cleared.get(0).get("removedCount")).intValue()); //$NON-NLS-1$
        assertEquals(5, ((Integer)cleared.get(1).get("removedCount")).intValue()); //$NON-NLS-1$
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
            return 2;
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
            return 1;
        });

        assertEquals("only the item with a module is cleared", 1, calls.size()); //$NON-NLS-1$
        assertEquals("CommonModule.Первый", calls.get(0)); //$NON-NLS-1$
        assertEquals(1, cleared.size());
        assertFalse("an item with no project does not name one", cleared.get(0).containsKey("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void replacingWithoutABatchIsRefused()
    {
        JsonObject answer = json(new BreakpointSetter()
            .execute(FakeDebugToolCalls.args("replaceModuleSet", "true"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the refusal says what a replacing call needs: " + answer, //$NON-NLS-1$
            answer.get("error").getAsString().contains("replaceModuleSet needs a `breakpoints` batch")); //$NON-NLS-1$ //$NON-NLS-2$
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
