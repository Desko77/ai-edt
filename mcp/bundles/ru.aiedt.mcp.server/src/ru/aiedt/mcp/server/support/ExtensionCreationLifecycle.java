/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.Gson;

/** Bounded, operation-owned coordination; never deletes or detaches a native creation. */
public final class ExtensionCreationLifecycle
{
    public record Scope(String projectName, String baseProjectName, String namePrefix) {}
    public interface Port
    {
        String preflight(Scope scope);
        Map<String, Object> create(Scope scope, ToolCallScope.Cancellation cancellation) throws Exception;
        Map<String, Object> observe(Scope scope);
    }
    private final Port port;
    private final PendingWorkRegistry registry;
    private final int capacity;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();
    private static final class Run
    {
        final Scope scope;
        volatile ToolCallScope.Cancellation cancel = new ToolCallScope.Cancellation();
        volatile boolean started;
        volatile Map<String, Object> result;
        volatile long finishedAt;
        final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        PendingWorkRegistry.PendingEntry entry;
        Run(Scope scope) { this.scope = scope; }
    }
    public ExtensionCreationLifecycle(Port port, PendingWorkRegistry registry, int capacity)
    {
        this.port = port; this.registry = registry; this.capacity = capacity;
    }
    public Map<String, Object> execute(Map<String, String> args, long waitMs)
    {
        String supplied = args.get("runKey");
        Run run;
        String key;
        synchronized (runs)
        {
            registry.pruneExpired();
            runs.entrySet().removeIf(item -> item.getValue().entry != null
                && item.getValue().finishedAt > 0
                && System.currentTimeMillis() - item.getValue().finishedAt > 300000L);
            if (supplied != null)
            {
                key = supplied;
                run = runs.get(key);
                if (run == null) return refused("Unknown creation runKey");
                if (!matches(args, run.scope)) return refused("runKey parameters do not match creation scope");
            }
            else
            {
                Scope scope = new Scope(trim(args.get("projectName")), trim(args.get("baseProjectName")), trim(args.get("namePrefix")));
                if (scope.projectName.isEmpty() || scope.baseProjectName.isEmpty()) return refused("projectName and baseProjectName are required");
                // Canonical scope deduplicates only locally owned runs. A new generation must not
                // collide with a registry result whose independent completion TTL has not elapsed.
                key = null;
                run = null;
                for (Map.Entry<String, Run> candidate : runs.entrySet())
                {
                    if (candidate.getValue().scope.equals(scope))
                    {
                        key = candidate.getKey();
                        run = candidate.getValue();
                        break;
                    }
                }
                if (key == null) key = PendingWorkRegistry.computeRunKey("create_extension_project",
                    scope.projectName, scope.baseProjectName, scope.namePrefix, java.util.UUID.randomUUID().toString());
                if (run != null && run.finished.getCount() == 0)
                    return refused("Creation already finished; poll its runKey to collect the result");
                if (run == null)
                {
                    // Admission precedes preflight and dispatch: CallerRunsPolicy cannot run native work inline.
                    if (runs.size() >= capacity) return refused("Extension creation is busy; no mutation started");
                    for (Run active : runs.values()) if (active.scope.projectName.equals(scope.projectName))
                        return refused("Another creation owns this project name");
                    String error = port.preflight(scope);
                    if (error != null) return refused(error);
                    run = new Run(scope);
                    runs.put(key, run);
                    Run owned = run;
                    run.entry = registry.getOrStart(key, entry -> perform(owned, entry));
                    run.entry.attachWorkExit(owned.finished::countDown);
                    run.entry.workKind = "create_extension_project";
                    run.entry.startedBy = "extension_workshop";
                    run.entry.subject = scope.projectName;
                }
            }
            if ("true".equals(args.get("cancel")))
            {
                run.cancel.cancel("Creation cancellation requested");
                if (run.entry.cancellation != null) run.entry.cancellation.cancel("Creation cancellation requested");
            }
        }
        try { run.finished.await(Math.max(0, waitMs), java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (run.finished.getCount() == 0 && run.result == null)
        {
            // The registry can cancel queued work before our body is entered. Its work-exit
            // acknowledgement, not the cancelled future, proves no native body remains alive.
            Map<String, Object> cancelled = base(run);
            cancelled.put("success", false);
            cancelled.put("phase", "cancelled");
            cancelled.put("cancellationRequested", true);
            cancelled.put("mutationStarted", run.started);
            if (run.started)
            {
                cancelled.put("phase", "failed");
                cancelled.put("error", "Creation worker exited without a final outcome; project state is unknown");
                cancelled.put("projectState", "unknown");
            }
            run.result = cancelled;
            run.finishedAt = System.currentTimeMillis();
        }
        if (run.finished.getCount() == 0 && run.result != null)
        {
            Map<String, Object> result = run.result;
            synchronized (runs) { runs.remove(key, run); registry.remove(key, run.entry); }
            return result;
        }
        Map<String, Object> pending = base(run);
        pending.put("status", "Pending"); pending.put("runKey", key);
        pending.put("phase", run.started ? "creating" : "queued");
        pending.put("elapsedMs", run.entry.elapsedMs()); pending.put("workerRunning", true);
        pending.put("dialogObservationAvailable", false);
        pending.put("dialogObservationReason", "The EDT creation service exposes no creation-owned dialog handle");
        return pending;
    }
    private String perform(Run run, PendingWorkRegistry.PendingEntry entry)
    {
        Map<String, Object> result = base(run);
        try
        {
            if (entry.cancellation == null) entry.cancellation = run.cancel;
            else run.cancel = entry.cancellation;
            if (!run.cancel.isCancelled() && entry.claimTheLaunch())
            {
                run.started = true;
                result.putAll(port.create(run.scope, run.cancel));
            }
            result.putAll(port.observe(run.scope));
            boolean cancelled = run.cancel.isCancelled() || entry.cancellation != null && entry.cancellation.isCancelled();
            boolean available = Boolean.TRUE.equals(result.get("configurationAvailable"));
            boolean exists = Boolean.TRUE.equals(result.get("projectExists"));
            result.put("success", run.started && available && !cancelled);
            result.put("phase", cancelled ? (exists ? "residual" : "cancelled") : available ? "available" : "residual");
            if (exists && (cancelled || !available)) result.put("residualProjectName", run.scope.projectName);
        }
        catch (Exception error)
        {
            result.put("success", false); result.put("phase", "failed");
            result.put("error", TextSuggest.safeMessage(error));
            try { result.putAll(port.observe(run.scope)); } catch (Exception unavailable) { result.put("projectState", "unknown"); }
            if (Boolean.TRUE.equals(result.get("projectExists"))) result.put("residualProjectName", run.scope.projectName);
        }
        result.put("mutationStarted", run.started);
        result.put("cancellationRequested", run.cancel.isCancelled() || entry.cancellation != null && entry.cancellation.isCancelled());
        run.result = result;
        run.finishedAt = System.currentTimeMillis();
        return gson.toJson(result);
    }
    private static Map<String, Object> base(Run run)
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("operation", "create_extension_project"); r.put("projectName", run.scope.projectName);
        r.put("baseProjectName", run.scope.baseProjectName); r.put("mutationStarted", run.started);
        r.put("cancellationRequested", run.cancel.isCancelled()); return r;
    }
    private static Map<String, Object> refused(String error) { return Map.of("success", false, "error", error, "mutationStarted", false); }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static boolean matches(Map<String, String> args, Scope scope)
    {
        return (!args.containsKey("projectName") || trim(args.get("projectName")).equals(scope.projectName))
            && (!args.containsKey("baseProjectName") || trim(args.get("baseProjectName")).equals(scope.baseProjectName))
            && (!args.containsKey("namePrefix") || trim(args.get("namePrefix")).equals(scope.namePrefix));
    }
    public int trackedCount() { return runs.size(); }
}
