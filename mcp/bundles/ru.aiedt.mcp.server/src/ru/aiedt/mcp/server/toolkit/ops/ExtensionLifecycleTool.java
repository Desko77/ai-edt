/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.wire.GsonHolder;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.ToolGate;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 1.40.x: Extension-lifecycle workflow helper. Combines several existing
 * tools into a single multi-step orchestration so an AI agent can drive
 * the whole "borrow object → generate handler → validate" flow without
 * coordinating five tools manually.
 * <p>
 * Steps when {@code mode=full}:
 * <ol>
 *   <li>Validate the extension project exists and probes succeed</li>
 *   <li>{@code adoptObject} - borrow {@code targetFqn} from the base
 *       configuration ({@code baseProjectName}). Idempotent if already
 *       borrowed.</li>
 *   <li>{@code generate_event_handlers} - emit BSL stub for the requested
 *       event ({@code eventName}, e.g. "BeforeWrite", "OnWrite") attached
 *       to the borrowed object.</li>
 *   <li>{@code revalidate_objects} - run targeted validation on the
 *       freshly-borrowed object.</li>
 * </ol>
 * Returns a structured JSON with per-step outcome plus an aggregated
 * {@code stepsOk} count and the final BSL handler body.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code full} (default) - run all four steps</li>
 *   <li>{@code dryRun} - report what would be done without mutating</li>
 *   <li>{@code probeOnly} - just step 1 (project + adopt service probe)</li>
 * </ul>
 */
public class ExtensionLifecycleTool implements IMcpTool
{
    public static final String NAME = "extension_lifecycle"; //$NON-NLS-1$

    /**
     * How long the adopt step waits once more for an adopt that answered Pending, in seconds.
     * The wait edit_metadata applies to a call that names none, so the step waits at most twice
     * what a single adopt call does.
     */
    private static final int ADOPT_POLL_SECONDS = 25;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `extension_workshop` `operation=extension_lifecycle`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "One-shot extension workflow - validates extension, " //$NON-NLS-1$
            + "borrows targetFqn from baseProjectName, generates an event " //$NON-NLS-1$
            + "handler stub, runs targeted validation. Replaces 4-5 manual " //$NON-NLS-1$
            + "tool calls with a single orchestrated request. Pass " //$NON-NLS-1$
            + "mode=dryRun to preview, probeOnly to test connectivity."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Extension project name (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("baseProjectName", "Base configuration project to borrow from", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("targetFqn", "Top-level FQN to borrow (e.g. Catalog.Products)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("eventName", "Optional event to generate stub for: " //$NON-NLS-1$ //$NON-NLS-2$
                + "BeforeWrite, OnWrite, BeforeDelete, OnCopy, FillCheckProcessing, " //$NON-NLS-1$
                + "OnReadAtServer, OnOpen, etc.") //$NON-NLS-1$
            .stringProperty("mode", "Mode: full (default), dryRun, probeOnly") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName");
        String baseProjectName = JsonUtils.extractStringArgument(params, "baseProjectName");
        String targetFqn = JsonUtils.extractStringArgument(params, "targetFqn");
        String eventName = JsonUtils.extractStringArgument(params, "eventName");
        String mode = JsonUtils.extractStringArgument(params, "mode");
        if (mode == null || mode.isEmpty())
        {
            mode = "full";
        }
        mode = mode.toLowerCase().trim();

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson();
        }
        // baseProjectName is optional: when omitted, the adopt step auto-resolves it from the
        // extension's parent configuration (IExtensionProject.getParentProject()).
        if (targetFqn == null || targetFqn.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("targetFqn", "Catalog.Products")).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        if (baseProjectName != null && !baseProjectName.isEmpty()
            && ProjectResolver.resolve(baseProjectName) == null)
        {
            return ProjectResolver.notFound(baseProjectName).toJson();
        }

        long start = System.currentTimeMillis();
        List<Map<String, Object>> steps = new ArrayList<>();
        int stepsOk = 0;

        // ---- Step 1: probe ----
        Map<String, Object> probeStep = new LinkedHashMap<>();
        probeStep.put("step", "probe");
        probeStep.put("ok", true);
        probeStep.put("extensionProject", projectName);
        probeStep.put("baseProject", baseProjectName);
        probeStep.put("targetFqn", targetFqn);
        steps.add(probeStep);
        stepsOk++;

        if ("probeonly".equals(mode))
        {
            return finish(steps, stepsOk, start, "probeOnly", null);
        }

        boolean dryRun = "dryrun".equals(mode);

        // This composite reaches edit_metadata and generate_event_handlers as plain Java calls, which
        // never pass the router where the active preset is enforced. So it asks the preset itself, and
        // asks before the first write: that is the only point where a refusal leaves nothing half-done.
        String gate = ToolGate.gateIfPresetDisabled("edit_metadata"); //$NON-NLS-1$
        if (gate == null && eventName != null && !eventName.isEmpty())
        {
            gate = ToolGate.gateIfPresetDisabled("generate_event_handlers"); //$NON-NLS-1$
        }
        if (gate != null)
        {
            return finish(steps, stepsOk, start, mode, gate);
        }

        // ---- Step 2: adopt (borrow) ----
        Map<String, Object> adoptStep = new LinkedHashMap<>();
        adoptStep.put("step", "adopt");
        String adoptRunKey = null;
        try
        {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("projectName", projectName);
            p.put("baseProjectName", baseProjectName);
            p.put("targetFqn", targetFqn);
            if (dryRun)
            {
                p.put("dryRun", "true");
            }
            String body = invokeAdopt(p);
            adoptRunKey = pendingRunKey(body);
            if (adoptRunKey != null)
            {
                Map<String, String> poll = new LinkedHashMap<>(p);
                poll.put("runKey", adoptRunKey); //$NON-NLS-1$
                poll.put("timeoutSeconds", String.valueOf(ADOPT_POLL_SECONDS)); //$NON-NLS-1$
                body = invokeAdopt(poll);
                adoptRunKey = pendingRunKey(body);
            }
            adoptStep.put("response", parseJsonOrRaw(body));
            adoptStep.put("ok", adoptRunKey == null && looksOk(body));
        }
        catch (Exception e)
        {
            adoptStep.put("ok", false);
            // A key already issued means the adopt was started: the failure is the wait's, and the
            // work it waited on may still be running under that key.
            adoptStep.put(adoptRunKey != null ? "pollError" : "error", e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (adoptRunKey != null)
        {
            adoptStep.put("status", "Pending"); //$NON-NLS-1$ //$NON-NLS-2$
            adoptStep.put("runKey", adoptRunKey); //$NON-NLS-1$
            steps.add(adoptStep);
            return finishAdoptPending(steps, stepsOk, start, mode, adoptRunKey);
        }
        steps.add(adoptStep);
        if (Boolean.TRUE.equals(adoptStep.get("ok")))
        {
            stepsOk++;
        }
        else if (!dryRun)
        {
            // In full mode, abort on adopt failure
            return finish(steps, stepsOk, start, mode,
                "Adopt step failed - cannot proceed to handler/validation. "
                    + "See steps[].response for details.");
        }

        // ---- Step 3: generate handler (only if eventName given) ----
        if (eventName != null && !eventName.isEmpty())
        {
            Map<String, Object> handlerStep = new LinkedHashMap<>();
            handlerStep.put("step", "generateHandler");
            handlerStep.put("eventName", eventName);
            try
            {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("projectName", projectName);
                p.put("objectFqn", targetFqn);
                p.put("events", eventName);
                String body = invokeGenerateHandler(p);
                handlerStep.put("response", parseJsonOrRaw(body));
                handlerStep.put("ok", looksOk(body));
            }
            catch (Exception e)
            {
                handlerStep.put("ok", false);
                handlerStep.put("error", e.getMessage());
            }
            steps.add(handlerStep);
            if (Boolean.TRUE.equals(handlerStep.get("ok")))
            {
                stepsOk++;
            }
        }

        // ---- Step 4: validate ----
        if (!dryRun)
        {
            Map<String, Object> validateStep = new LinkedHashMap<>();
            validateStep.put("step", "validate");
            try
            {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("projectName", projectName);
                // ObjectsRevalidator expects "objects" as a JSON array of FQNs
                p.put("objects", "[\"" + targetFqn.replace("\"", "\\\"") + "\"]");
                String body = new ObjectsRevalidator().execute(p);
                validateStep.put("response", parseJsonOrRaw(body));
                validateStep.put("ok", looksOk(body));
            }
            catch (Exception e)
            {
                validateStep.put("ok", false);
                validateStep.put("error", e.getMessage());
            }
            steps.add(validateStep);
            if (Boolean.TRUE.equals(validateStep.get("ok")))
            {
                stepsOk++;
            }
        }

        return finish(steps, stepsOk, start, mode, null);
    }

    /**
     * Runs adopt_object through edit_metadata, so the lifecycle helper needs no probe of its own.
     *
     * @param p the step's arguments; a poll for a Pending adopt adds runKey and timeoutSeconds
     * @return edit_metadata's answer, a Pending one included
     */
    String invokeAdopt(Map<String, String> p)
    {
        Map<String, String> forwarded = new LinkedHashMap<>(p);
        forwarded.put("operation", "adopt_object");
        return new EditMetadataTool().execute(forwarded);
    }

    /**
     * Runs generate_event_handlers for the handler step.
     *
     * @param p the step's arguments, under the names that tool reads
     * @return the tool's answer
     */
    String invokeGenerateHandler(Map<String, String> p)
    {
        return new GenerateEventHandlersTool().execute(p);
    }

    /**
     * Reads the key out of a Pending answer.
     * <p>
     * edit_metadata answers Pending, as a success, when the work outlives its wait, and the work
     * goes on behind the key. So the answer is read by its status and key rather than by its
     * success, which a Pending answer shares with a finished one.
     * </p>
     *
     * @param json a step's answer
     * @return the key when the answer is Pending, otherwise <code>null</code>
     */
    static String pendingRunKey(String json)
    {
        if (json == null)
        {
            return null;
        }
        JsonElement tree;
        try
        {
            tree = GsonHolder.fromJson(json.trim(), JsonElement.class);
        }
        catch (RuntimeException notJson)
        {
            return null;
        }
        if (tree == null || !tree.isJsonObject())
        {
            return null;
        }
        JsonObject body = tree.getAsJsonObject();
        JsonElement status = body.get("status"); //$NON-NLS-1$
        JsonElement runKey = body.get("runKey"); //$NON-NLS-1$
        if (status == null || !status.isJsonPrimitive()
            || !"Pending".equals(status.getAsString()) //$NON-NLS-1$
            || runKey == null || !runKey.isJsonPrimitive() || runKey.getAsString().isEmpty())
        {
            return null;
        }
        return runKey.getAsString();
    }

    /**
     * Answers a workflow stopped at an adopt that is still running.
     * <p>
     * The adopt step is not counted and no step after it runs: they act on the borrowed object,
     * which does not exist until the adopt finishes. The answer carries the key the adopt's
     * result is collected by.
     * </p>
     *
     * @param steps the steps run so far, the adopt step last
     * @param stepsOk how many of them finished
     * @param start when the workflow started, in milliseconds
     * @param mode the workflow mode
     * @param runKey the key the adopt's result is collected by
     * @return the answer
     */
    private String finishAdoptPending(List<Map<String, Object>> steps, int stepsOk, long start,
        String mode, String runKey)
    {
        return summary(steps, stepsOk, start, mode,
            "The adopt step is still running; the steps after it were not run.") //$NON-NLS-1$
            .put("pendingStep", "adopt") //$NON-NLS-1$ //$NON-NLS-2$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("hint", "Collect the adopt result with edit_metadata operation=adopt_object " //$NON-NLS-1$ //$NON-NLS-2$
                + "and this runKey, then call extension_lifecycle again: the adopt is idempotent " //$NON-NLS-1$
                + "and the remaining steps run once it has finished.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Answers the workflow.
     *
     * @param steps the steps run
     * @param stepsOk how many of them finished
     * @param start when the workflow started, in milliseconds
     * @param mode the workflow mode
     * @param earlyAbort why the workflow stopped early, or <code>null</code>
     * @return the answer
     */
    private String finish(List<Map<String, Object>> steps, int stepsOk, long start, String mode,
        String earlyAbort)
    {
        return summary(steps, stepsOk, start, mode, earlyAbort).toJson();
    }

    /**
     * Builds the workflow's answer: the steps, the counts and a hint.
     *
     * @param steps the steps run
     * @param stepsOk how many of them finished
     * @param start when the workflow started, in milliseconds
     * @param mode the workflow mode
     * @param earlyAbort why the workflow stopped early, or <code>null</code>
     * @return the answer, still open to more members
     */
    private ToolResult summary(List<Map<String, Object>> steps, int stepsOk, long start,
        String mode, String earlyAbort)
    {
        ToolResult tr = ToolResult.success()
            .put("operation", NAME)
            .put("mode", mode)
            .put("stepsOk", stepsOk)
            .put("stepsTotal", steps.size())
            .put("steps", steps)
            .put("elapsedMs", System.currentTimeMillis() - start);
        if (earlyAbort != null)
        {
            tr.put("earlyAbort", earlyAbort);
        }
        if (stepsOk == steps.size() && earlyAbort == null)
        {
            tr.put("hint", "Workflow completed. The borrowed object is ready - drop the "
                + "generated handler body into the object module and continue.");
        }
        else
        {
            tr.put("hint", "Some steps failed. Inspect steps[].response for the underlying "
                + "tool's error and re-run individually with edit_metadata / generate_event_handlers / "
                + "revalidate_objects.");
        }
        return tr;
    }

    private boolean looksOk(String json)
    {
        if (json == null)
        {
            return false;
        }
        // Heuristic: ToolResult.error() emits "success":false; success has true.
        return json.contains("\"success\":true") || (!json.contains("\"success\":false")
            && !json.toLowerCase().startsWith("error"));
    }

    private Object parseJsonOrRaw(String maybeJson)
    {
        if (maybeJson == null)
        {
            return null;
        }
        String trimmed = maybeJson.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("["))
        {
            try
            {
                return GsonHolder.fromJson(trimmed, JsonElement.class);
            }
            catch (Exception e)
            {
                return maybeJson;
            }
        }
        return maybeJson;
    }
}
