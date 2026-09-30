/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * {@code extension_lifecycle} runs its steps on each other's results: the handler step writes into
 * the object the adopt step borrowed. So the adopt step counts only once its work has finished, and
 * the handler step names the object and the event under the arguments
 * {@code generate_event_handlers} reads.
 */
public class AnExtensionLifecycleWaitsForItsAdoptTest
{
    private static final String PROJECT = "AiEdtLifecycleProbe"; //$NON-NLS-1$

    private static final String TARGET = "Catalog.Goods"; //$NON-NLS-1$

    private static final String EVENT = "BeforeWrite"; //$NON-NLS-1$

    private static final String DONE = "{\"success\":true,\"operation\":\"adopt_object\"}"; //$NON-NLS-1$

    private static IProject project;

    /**
     * The workflow with its two tool calls answered from a script.
     */
    private static final class Scripted extends ExtensionLifecycleTool
    {
        private final Deque<String> adoptAnswers = new ArrayDeque<>();

        private final List<Map<String, String>> adoptCalls = new ArrayList<>();

        private Map<String, String> handlerCall;

        /**
         * @param answers what the adopt calls answer, in order
         */
        Scripted(String... answers)
        {
            adoptAnswers.addAll(Arrays.asList(answers));
        }

        @Override
        String invokeAdopt(Map<String, String> p)
        {
            adoptCalls.add(new LinkedHashMap<>(p));
            return adoptAnswers.poll();
        }

        @Override
        String invokeGenerateHandler(Map<String, String> p)
        {
            handlerCall = new LinkedHashMap<>(p);
            return "{\"success\":true}"; //$NON-NLS-1$
        }
    }

    /**
     * Opens the project the workflow resolves by name.
     *
     * @throws Exception when the workspace cannot create the project
     */
    @BeforeClass
    public static void aProject() throws Exception
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT);
        if (!project.exists())
        {
            project.create(new NullProgressMonitor());
        }
        project.open(new NullProgressMonitor());
    }

    /**
     * Removes the project.
     *
     * @throws Exception when the workspace cannot delete the project
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
    }

    /**
     * An adopt still running: a success with status Pending and a key.
     *
     * @param runKey the key
     * @return the answer
     */
    private static String pending(String runKey)
    {
        return "{\"success\":true,\"status\":\"Pending\",\"pendingEnvelope\":true," //$NON-NLS-1$
            + "\"operation\":\"adopt_object\",\"runKey\":\"" + runKey + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The arguments of a dry run that generates a handler for {@link #EVENT}.
     *
     * @return the arguments
     */
    private static Map<String, String> dryRun()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("targetFqn", TARGET); //$NON-NLS-1$
        params.put("eventName", EVENT); //$NON-NLS-1$
        params.put("mode", "dryRun"); //$NON-NLS-1$ //$NON-NLS-2$
        return params;
    }

    /**
     * @param json the workflow's answer
     * @return it parsed
     */
    private static JsonObject answer(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** The handler step names the object and the event under the names the tool reads. */
    @Test
    public void theHandlerStepNamesTheObjectAndTheEvent()
    {
        Scripted tool = new Scripted(DONE);
        JsonObject result = answer(tool.execute(dryRun()));
        assertNotNull(tool.handlerCall);
        assertEquals(PROJECT, tool.handlerCall.get("projectName")); //$NON-NLS-1$
        assertEquals(TARGET, tool.handlerCall.get("objectFqn")); //$NON-NLS-1$
        assertEquals(EVENT, tool.handlerCall.get("events")); //$NON-NLS-1$
        assertEquals(3, result.get("stepsOk").getAsInt()); //$NON-NLS-1$
    }

    /** An adopt still running after the second wait stops the workflow and hands back the key. */
    @Test
    public void anAdoptStillRunningStopsTheWorkflow()
    {
        Scripted tool = new Scripted(pending("k-1"), pending("k-1")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject result = answer(tool.execute(dryRun()));
        assertEquals(2, tool.adoptCalls.size());
        assertEquals("k-1", tool.adoptCalls.get(1).get("runKey")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("25", tool.adoptCalls.get(1).get("timeoutSeconds")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("no step runs after an adopt still running", tool.handlerCall); //$NON-NLS-1$
        assertEquals(1, result.get("stepsOk").getAsInt()); //$NON-NLS-1$
        assertEquals("adopt", result.get("pendingStep").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("k-1", result.get("runKey").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.has("earlyAbort")); //$NON-NLS-1$
        JsonObject adopt = result.getAsJsonArray("steps").get(1).getAsJsonObject(); //$NON-NLS-1$
        assertFalse(adopt.get("ok").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Pending", adopt.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An adopt that finishes on the second wait counts, and the workflow goes on. */
    @Test
    public void anAdoptThatFinishesOnTheSecondWaitCounts()
    {
        Scripted tool = new Scripted(pending("k-2"), DONE); //$NON-NLS-1$
        JsonObject result = answer(tool.execute(dryRun()));
        assertEquals(2, tool.adoptCalls.size());
        assertNotNull(tool.handlerCall);
        assertEquals(3, result.get("stepsOk").getAsInt()); //$NON-NLS-1$
        assertFalse(result.has("pendingStep")); //$NON-NLS-1$
    }

    /** Only an answer with status Pending and a key reads as pending. */
    @Test
    public void onlyAPendingAnswerWithAKeyIsPending()
    {
        assertEquals("k", ExtensionLifecycleTool.pendingRunKey(pending("k"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ExtensionLifecycleTool.pendingRunKey(DONE));
        assertNull(ExtensionLifecycleTool.pendingRunKey("{\"status\":\"Pending\"}")); //$NON-NLS-1$
        assertNull(ExtensionLifecycleTool.pendingRunKey("Error: not json")); //$NON-NLS-1$
        assertNull(ExtensionLifecycleTool.pendingRunKey(null));
    }
}
