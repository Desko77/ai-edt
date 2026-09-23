/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.McpHistory;
import ru.aiedt.mcp.server.settings.HistorySettings;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.IToolRoad;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ToolRoadOutcome;

/**
 * The one road a tool call takes: what runs, whether it may run now, and for how long its heavy
 * permit is held.
 * <p>
 * Two doors onto it. The HTTP endpoint asks {@link #admit(String, Map)} before it starts a worker
 * thread and hands the ticket it gets to the router, which runs the body through
 * {@link #runBody(IMcpTool, Map, Ticket)}. A bundle calls {@link #call(String, Map, String)} on
 * the calling thread. Both doors share every decision: the name the call routes to, the preset
 * gate, the heap gate for a heavy call, one permit per heavy caller, and the release of that
 * permit when the work - not the answer - is done.
 * </p>
 * <p>
 * A call that answers {@code Pending} hands its ticket to the registry entry: the permit comes
 * back when the entry's work leaves the executor, and never on the tracking future's completion
 * alone - a cancel completes that future without reaching work already running. The entry the
 * answer names is not only the one the registry map still holds: work whose tracking was dropped
 * while it runs - a subject sweep, a cancel that cannot reach it - is found through the entry the
 * call's scope kept when it started the run, so its permit waits on the work and not on the
 * tracking. A poll of a live {@code runKey} starts no new work and so takes no permit and clears
 * no heap gate, and only the tool whose resumption path the key belongs to polls it. A nested
 * heavy call under a caller that already holds a permit inherits a share of it rather than taking
 * a second one, and that share stays with the child's work after the parent returns - a parent
 * that answers its own text frees only its own share; a heavy child under a light caller binds
 * its own permit to the scope for the duration of its body, so a grandchild under it inherits
 * that one.
 * </p>
 */
public final class ToolRoad
    implements IToolRoad
{
    /**
     * The refusal a caller gets at the heavy-tool concurrency limit. Moved here from the endpoint
     * so the wire refusal and the internal refusal are the same string by construction.
     */
    public static final String MSG_HEAVY_BUSY =
        "A heavy tool is already running at the concurrency limit; retry shortly"; //$NON-NLS-1$

    /**
     * The refusal a caller gets when the heap has no room for an expensive call. The heap's own
     * description is appended to it.
     */
    public static final String MSG_HEAP_EXHAUSTED =
        "Refused to start an expensive tool: EDT has too little heap left, and starting it would " //$NON-NLS-1$
            + "likely end the whole session rather than this one call. Give the workbench a moment, " //$NON-NLS-1$
            + "run fewer expensive calls in a row, or restart EDT with a larger -Xmx. Heap: "; //$NON-NLS-1$

    /** The key a caller polls a background run by. */
    private static final String ARG_RUN_KEY = "runKey"; //$NON-NLS-1$

    /** The key an idempotent mutator is made at-most-once by. */
    private static final String ARG_OPERATION_ID = "operationId"; //$NON-NLS-1$

    private final Semaphore permits;

    /**
     * Answers whether the heap refuses an expensive call, with the refusal text; {@code null} when
     * it does not. A seam rather than the check itself so a test can force the refusal.
     */
    private final Supplier<String> heapRefusal;

    /**
     * @param permits the heavy-tool limiter this road admits against
     */
    public ToolRoad(Semaphore permits)
    {
        this(permits, ToolRoad::heapRefusalNow);
    }

    /**
     * @param permits the heavy-tool limiter this road admits against
     * @param heapRefusal whether the heap refuses an expensive call, with the refusal text
     */
    ToolRoad(Semaphore permits, Supplier<String> heapRefusal)
    {
        this.permits = permits;
        this.heapRefusal = heapRefusal;
    }

    /**
     * Decides whether a call may run now, and takes the permit when it may.
     * <p>
     * The decision reads the name the call will actually run under - a facade routes onward, and
     * the tool underneath is what is expensive - and the tool's own declaration when it came from
     * another bundle ({@code ru.aiedt.mcp.tool.heavy}). A call that names a live {@code runKey}
     * of its own resumption path polls work already running: it starts nothing, so it takes no
     * permit and clears no heap gate. A key belonging to any other run exempts nothing - a heavy
     * tool that reads no key would otherwise start new work past both gates under a running
     * work's key.
     * </p>
     *
     * @param toolName the name the call arrived under
     * @param arguments the call arguments, as far as a decision before the call needs them
     * @return the admission: a ticket when the call may run, a refusal when it may not
     */
    public Admission admit(String toolName, Map<String, String> arguments)
    {
        String polled = arguments == null ? null : arguments.get(ARG_RUN_KEY);
        IMcpTool named = toolName == null || toolName.isEmpty() ? null
            : McpToolCatalog.getInstance().getTool(toolName);
        String canonical = named == null ? toolName : named.getName();
        String routed = named == null ? null : named.routesTo(arguments);
        if (polled != null && !polled.isEmpty() && isOwnPoll(polled, named, canonical, arguments))
        {
            // A poll waits on work that is already accounted for; charging it again would count
            // one run as many.
            return Admission.admitted(new Ticket((Runnable)null));
        }
        boolean heavy = HeavyTools.isHeavy(toolName)
            || (routed != null && HeavyTools.isHeavy(routed));
        if (!heavy)
        {
            return Admission.admitted(new Ticket((Runnable)null));
        }
        String refusal = heapRefusal.get();
        if (refusal != null)
        {
            Activator.logInfo("Heavy tool '" + toolName + "' refused: " + refusal); //$NON-NLS-1$ //$NON-NLS-2$
            return Admission.turnedAway(refusal);
        }
        ToolCallScope scope = ToolCallScope.current();
        if (scope != null && scope.holdsHeavyPermit())
        {
            // The caller on this thread already holds a permit for the work this call is part of.
            // The limit counts concurrent callers, not reentry into them. The child takes a share
            // of that permit rather than a permit-less ride: work the child dispatches can go on
            // after the parent has returned, and the permit must stay held for as long as any of
            // that work runs.
            return Admission.admitted(scope.ticket().share());
        }
        if (!permits.tryAcquire())
        {
            // At the heavy-tool limit: turn the call away at once rather than piling another
            // expensive run onto EDT and starving everything else.
            Activator.logInfo("Heavy tool '" + toolName + "' refused: concurrency limit reached"); //$NON-NLS-1$ //$NON-NLS-2$
            return Admission.turnedAway(MSG_HEAVY_BUSY);
        }
        return Admission.admitted(new Ticket(permits::release));
    }

    /**
     * Whether a {@code runKey} argument names a run this call will actually resume.
     * <p>
     * A live entry is not enough, and neither is a name. The call resumes when the tool declares
     * the starter it polls in that entry's domain and the entry was started under that name, or
     * when the road's own generic wrapper runs the called tool by its own name and will poll the
     * entry before {@code execute}. A facade that only routes to the starter and then calls
     * {@code execute} declares nothing and starts a new traversal. A finished entry, still sitting
     * in the registry until somebody collects it, is not a live run: polling it is a new call.
     * </p>
     *
     * @param polled the key from the arguments; neither null nor empty
     * @param named the called tool, or {@code null} when the catalogue has no such name
     * @param canonical the called tool's own name
     * @param arguments the call arguments; may be {@code null}
     * @return whether polling the key is this call's own resumption path
     */
    private static boolean isOwnPoll(String polled, IMcpTool named, String canonical,
        Map<String, String> arguments)
    {
        PendingWorkRegistry domain = PendingWorkRegistry.domainOf(polled);
        PendingWorkRegistry.PendingEntry entry = domain == null ? null : domain.get(polled);
        if (entry == null || entry.completedAt > 0 || entry.isDone())
        {
            return false;
        }
        // The road wraps these tools itself, before execute, and that wrapper is what polls.
        // A facade whose routesTo names one of them does not: it calls execute and starts again.
        if (GenericPending.applies(canonical) && domain == PendingWorkRegistry.GENERIC
            && entry.resumableBy(canonical))
        {
            return true;
        }
        if (named == null)
        {
            return false;
        }
        String operation = arguments == null ? null : arguments.get("operation"); //$NON-NLS-1$
        String starter = named.resumes(domain.domain(), operation);
        return starter != null && entry.resumableBy(starter);
    }

    /**
     * Runs the tool body under the road's rules: idempotency for an allowlisted mutator, the
     * generic Pending flow for an allowlisted slow read, the tool itself for everything else.
     * <p>
     * The ticket is spent here. A synchronous answer releases it; a {@code Pending} envelope hands
     * it to the entry the envelope names, and the permit comes back when that entry's work leaves
     * the executor.
     * </p>
     *
     * @param tool the resolved tool
     * @param arguments the flattened arguments
     * @param ticket the call's permit ticket; may be {@code null} or permit-less
     * @return the finished result, or a {@code Pending} JSON with a runKey
     */
    public static String runBody(IMcpTool tool, Map<String, String> arguments, Ticket ticket)
    {
        String name = tool.getName();
        // An optional client operationId makes an allowlisted mutator at-most-once: a repeat
        // with the same id replays the first result instead of mutating again.
        String operationId = arguments.get(ARG_OPERATION_ID);
        if (MutatorIdempotency.applies(name, operationId, arguments))
        {
            String key = MutatorIdempotency.key(name, operationId);
            String result = MutatorIdempotencyStore.INSTANCE.call(key, () -> tool.execute(arguments),
                MutatorIdempotencyStore.DEFAULT_WAITER_TIMEOUT_MS);
            spend(ticket, result);
            return result;
        }
        if (!GenericPending.applies(name))
        {
            String result = tool.execute(arguments);
            spend(ticket, result);
            return result;
        }
        String runKey = PendingWorkRegistry.computeRunKey(name,
            GenericPending.canonicalParams(arguments));
        String result = PendingExecutor.execute(PendingWorkRegistry.GENERIC, name, arguments, runKey,
            PendingExecutor.DEFAULT_SOFT_TIMEOUT_MS, () -> tool.execute(arguments), null);
        spend(ticket, result);
        return result;
    }

    /**
     * Spends the ticket on an answer: hands it to the entry a {@code Pending} envelope names, or
     * releases it for anything else.
     * <p>
     * The entry the envelope names is looked up twice. Its domain first, and - when nothing there
     * holds the key - among the entries this call started, which its scope kept: tracking dropped
     * while the work still runs is not work that finished, and only the entry's own exit may
     * return the permit.
     * </p>
     *
     * @param ticket the call's permit ticket; may be {@code null} or permit-less
     * @param result what the body produced
     */
    private static void spend(Ticket ticket, String result)
    {
        if (ticket == null)
        {
            return;
        }
        String runKey = pendingRunKey(result);
        if (runKey == null)
        {
            ticket.release();
            return;
        }
        PendingWorkRegistry.PendingEntry entry = entryOf(runKey);
        if (entry == null)
        {
            // The entry was collected or evicted between the answer and here, and this call
            // started nothing that still runs under that key; a permit nobody releases is one the
            // next caller lacks.
            ticket.release();
            return;
        }
        ticket.transferTo(entry);
    }

    /**
     * The entry a {@code Pending} envelope names, live or detached.
     * <p>
     * The domain map first. An answer whose key no domain holds is not necessarily finished work:
     * a subject sweep or a cancel that cannot reach running work takes the tracking away while
     * the work goes on, and the scope this call ran under kept the entry it started for exactly
     * that case.
     * </p>
     *
     * @param runKey the key from the envelope
     * @return the entry that runs the answer's work, or {@code null} when nothing does
     */
    private static PendingWorkRegistry.PendingEntry entryOf(String runKey)
    {
        PendingWorkRegistry domain = PendingWorkRegistry.domainOf(runKey);
        PendingWorkRegistry.PendingEntry entry = domain == null ? null : domain.get(runKey);
        if (entry != null)
        {
            return entry;
        }
        ToolCallScope scope = ToolCallScope.current();
        return scope == null ? null : scope.pendingEntryStartedHere(runKey);
    }

    /**
     * Reads the {@code runKey} out of a {@code Pending} envelope.
     *
     * @param result the answer to inspect
     * @return the key, or {@code null} when the answer is no envelope
     */
    private static String pendingRunKey(String result)
    {
        if (!PendingEnvelope.isCandidate(result))
        {
            return null;
        }
        try
        {
            JsonElement tree = JsonParser.parseString(result);
            if (!tree.isJsonObject())
            {
                return null;
            }
            JsonElement runKey = tree.getAsJsonObject().get(ARG_RUN_KEY);
            return runKey != null && runKey.isJsonPrimitive() && !runKey.getAsString().isEmpty()
                ? runKey.getAsString() : null;
        }
        catch (JsonParseException malformed)
        {
            return null;
        }
    }

    /**
     * The heap's own answer to whether an expensive call should start.
     *
     * @return the refusal text when it should not, or {@code null} when it may
     */
    private static String heapRefusalNow()
    {
        HeapHeadroom.Reading heap = HeapHeadroom.current();
        return HeapHeadroom.refusesWork(heap, HeapHeadroom.refusalPercent())
            ? MSG_HEAP_EXHAUSTED + heap.describe() : null;
    }

    @Override
    public ToolRoadOutcome call(String tool, Map<String, Object> arguments, String origin)
    {
        Map<String, String> flat = flattenArguments(arguments);
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        IMcpTool found = tool == null ? null : catalog.getTool(tool);
        if (found == null)
        {
            return ToolRoadOutcome.refused("Tool not found: " + tool); //$NON-NLS-1$
        }
        if (!catalog.isToolEnabled(tool))
        {
            return ToolRoadOutcome.refused(ToolGate.disabledMessage(tool));
        }
        Admission admission = admit(tool, flat);
        if (admission.refusal() != null)
        {
            return ToolRoadOutcome.refused(admission.refusal());
        }
        ToolCallScope parent = ToolCallScope.current();
        if (parent != null)
        {
            // Nested: the caller's flag is the one a cancel should reach, and its permit is the
            // one this call inherits when it holds one.
            Ticket ticket = admission.ticket();
            if (ticket != null && ticket.holdsPermit())
            {
                // The child took a permit its caller did not have. Bound to the scope for the
                // body's duration, so a grandchild under the child inherits that permit - and so
                // work the body dispatches to a registry re-enters the child's scope rather than
                // the caller's permit-less one. The caller's scope comes back after the body.
                ToolCallScope childScope = parent.withTicket(ticket);
                ToolCallScope.enter(childScope);
                try
                {
                    return runAndRecord(found, flat, ticket, origin);
                }
                finally
                {
                    ToolCallScope.enter(parent);
                    // The safety spend for a body that never answered: a ticket the road already
                    // spent does nothing here.
                    ticket.release();
                }
            }
            try
            {
                return runAndRecord(found, flat, ticket, origin);
            }
            finally
            {
                // The safety spend for a body that never answered: a ticket the road already spent
                // does nothing here.
                ticket.release();
            }
        }
        ToolCallScope scope = ToolCallScope.create(null);
        scope.adoptTicket(admission.ticket());
        ToolCallScope.enter(scope);
        try
        {
            return runAndRecord(found, flat, admission.ticket(), origin);
        }
        finally
        {
            ToolCallScope.exit();
            admission.ticket().release();
        }
    }

    /**
     * Runs the body for an internal call and records the call in the history under its origin.
     *
     * @param tool the resolved tool
     * @param arguments the flattened arguments
     * @param ticket the call's permit ticket
     * @param origin the calling bundle's symbolic name
     * @return the outcome, never {@code null}
     */
    private static ToolRoadOutcome runAndRecord(IMcpTool tool, Map<String, String> arguments,
        Ticket ticket, String origin)
    {
        long start = System.currentTimeMillis();
        String result = null;
        boolean success = true;
        String error = null;
        try
        {
            result = runBody(tool, arguments, ticket);
            return outcomeOf(result);
        }
        catch (RuntimeException re)
        {
            success = false;
            error = re.getMessage();
            // A tool that threw already ran, and may have applied part of what it was asked for.
            // A refusal would tell a retrying caller this never happened, and the retry would
            // repeat the mutation.
            return ToolRoadOutcome.failed("The tool '" + tool.getName() + "' threw " //$NON-NLS-1$ //$NON-NLS-2$
                + re.getClass().getSimpleName() + ": " + error); //$NON-NLS-1$
        }
        finally
        {
            record(tool, arguments, result, success, error, start, origin);
        }
    }

    /**
     * Shapes an answer as an outcome.
     *
     * @param result what the body produced
     * @return the outcome, never {@code null}
     */
    private static ToolRoadOutcome outcomeOf(String result)
    {
        String runKey = pendingRunKey(result);
        if (runKey == null)
        {
            return ToolRoadOutcome.done(result);
        }
        PendingWorkRegistry domain = PendingWorkRegistry.domainOf(runKey);
        return ToolRoadOutcome.pending(result, runKey, domain == null ? null : domain.domain());
    }

    /**
     * Records an internal call in the history, with its arguments masked by the same keys the
     * wire masks an external call's.
     *
     * @param tool the tool that ran
     * @param arguments the flattened arguments
     * @param result what it answered
     * @param success whether it answered rather than threw
     * @param error why it threw, when it did
     * @param start when the call began
     * @param origin the calling bundle's symbolic name
     */
    private static void record(IMcpTool tool, Map<String, String> arguments, String result,
        boolean success, String error, long start, String origin)
    {
        HistorySettings history = HistorySettings.current();
        if (!history.isEnabled())
        {
            return;
        }
        String resultSummary = success ? result
            : "exception: " + (error != null ? error : "RuntimeException"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean logicalSuccess = success && !FailureShape.looksFailed(result);
        // The same reading the wire path makes: an image tool's non-JSON answer is binary data,
        // and the full-text store keeps a marker and a length rather than the base64 itself.
        boolean binaryResult = tool.getResponseType() == IMcpTool.ResponseType.IMAGE
            && !FailureShape.looksFailed(result) && !result.trim().startsWith("{"); //$NON-NLS-1$
        McpHistory.ArgsSummary args = McpHistory.summarizeArguments(arguments, history.argChars());
        McpHistory.ArgsSummary whole = McpHistory.summarizeArguments(arguments, Integer.MAX_VALUE);
        McpHistory.Completion completion = new McpHistory.Completion(tool.getName(), args.text,
            args.cut, resultSummary, System.currentTimeMillis() - start, logicalSuccess, whole.text,
            binaryResult, origin);
        McpHistory.record(completion, McpHistory.Answer.unobserved());
    }

    @Override
    public ToolRoadOutcome resume(String runKey, long waitMillis)
    {
        PendingWorkRegistry domain = PendingWorkRegistry.domainOf(runKey);
        if (domain == null)
        {
            return ToolRoadOutcome.refused("Not a registry entry: " + runKey //$NON-NLS-1$
                + " - the run either completed and was already retrieved, or was evicted."); //$NON-NLS-1$
        }
        PendingWorkRegistry.PendingEntry entry = domain.get(runKey);
        if (entry == null)
        {
            return ToolRoadOutcome.refused("Not a registry entry: " + runKey); //$NON-NLS-1$
        }
        String result = entry.await(Math.max(1L, waitMillis));
        if (result == null)
        {
            return ToolRoadOutcome.pending(null, runKey, domain.domain());
        }
        domain.remove(runKey, entry);
        return ToolRoadOutcome.done(result);
    }

    @Override
    public boolean cancel(String runKey)
    {
        PendingWorkRegistry domain = PendingWorkRegistry.domainOf(runKey);
        if (domain == null)
        {
            return false;
        }
        PendingWorkRegistry.PendingEntry entry = domain.get(runKey);
        if (entry != null && entry.cancellation != null)
        {
            // Raised before the entry is dropped, so the flag is in the work's hands even while
            // the future's own cancellation races it.
            entry.cancellation.cancel("cancelled through the tool road"); //$NON-NLS-1$
        }
        domain.cancelAndStop(runKey);
        return true;
    }

    /**
     * Flattens the arguments of a call into the string map tools are written against.
     * <p>
     * Structure survives as compact JSON; everything else is stringified. An explicit {@code null}
     * is dropped: a tool cannot tell it from an argument that was never sent.
     * </p>
     *
     * @param arguments the raw arguments object; may be {@code null}
     * @return the flattened arguments, never {@code null}
     */
    public static Map<String, String> flattenArguments(Map<String, Object> arguments)
    {
        Map<String, String> flattened = new LinkedHashMap<>();
        if (arguments == null)
        {
            return flattened;
        }
        for (Map.Entry<String, Object> argument : arguments.entrySet())
        {
            Object value = argument.getValue();
            if (value == null)
            {
                continue;
            }
            if (value instanceof String)
            {
                flattened.put(argument.getKey(), (String)value);
            }
            else if (value instanceof List || value instanceof Map)
            {
                flattened.put(argument.getKey(), ru.aiedt.mcp.server.wire.GsonHolder.toJson(value));
            }
            else
            {
                flattened.put(argument.getKey(), String.valueOf(value));
            }
        }
        return flattened;
    }

    /**
     * What {@link #admit(String, Map)} came back with: a ticket to run under, or why not.
     */
    public static final class Admission
    {
        private final Ticket ticket;

        private final String refusal;

        private Admission(Ticket ticket, String refusal)
        {
            this.ticket = ticket;
            this.refusal = refusal;
        }

        static Admission admitted(Ticket ticket)
        {
            return new Admission(ticket, null);
        }

        static Admission turnedAway(String refusal)
        {
            return new Admission(null, refusal);
        }

        /**
         * @return the ticket whose release ends the call's hold on the limiter
         */
        public Ticket ticket()
        {
            return ticket;
        }

        /**
         * @return why the call may not run, or {@code null} when it may
         */
        public String refusal()
        {
            return refusal;
        }
    }

    /**
     * A call's hold on the heavy-tool limiter, spent exactly once.
     * <p>
     * A ticket either holds a permit or holds nothing - a light call or a poll of a live run take
     * no second permit, and spending their ticket is a no-op. A ticket that holds a permit is
     * spent once: released when the call ends synchronously, or handed to the registry entry a
     * {@code Pending} answer named so the permit returns when that entry's work leaves the
     * executor - which a cancel cannot move, unlike the tracking future's completion. A second
     * spend, and the safety release a caller makes after the ticket was already handed over, do
     * nothing.
     * </p>
     * <p>
     * The hold may be shared. A nested heavy call inherits a share of its caller's permit rather
     * than a permit-less ride, so background work it dispatches keeps the limiter's count for as
     * long as that work runs - past the caller that returns first. The permit itself returns when
     * the last holder departs, and it returns exactly once; a spent ticket still reports its
     * permit held while a share of it lives.
     * </p>
     */
    public static final class Ticket
    {
        private final Lease lease;

        private final AtomicBoolean spent = new AtomicBoolean();

        Ticket(Runnable release)
        {
            this(release == null ? null : new Lease(release));
        }

        private Ticket(Lease lease)
        {
            this.lease = lease;
        }

        /**
         * @return whether this ticket's permit is still held, by this ticket or by a share of it
         */
        public boolean holdsPermit()
        {
            return lease != null && lease.isHeld();
        }

        /**
         * Releases the permit, if this ticket holds one and it was not spent already.
         */
        public void release()
        {
            if (lease == null || !spent.compareAndSet(false, true))
            {
                return;
            }
            lease.depart();
        }

        /**
         * Hands the permit to an entry: it is released when the entry's work leaves the executor,
         * in success, failure, or after a cancellation or eviction that could not reach it.
         *
         * @param entry the entry the answer named
         * @return whether this ticket was the one handed over
         */
        boolean transferTo(PendingWorkRegistry.PendingEntry entry)
        {
            if (!spent.compareAndSet(false, true))
            {
                return false;
            }
            if (lease == null)
            {
                return true;
            }
            lease.transferTo(entry);
            return true;
        }

        /**
         * A ticket sharing this one's permit.
         * <p>
         * For a nested call that inherits its caller's hold: the share is spent like any ticket,
         * and the permit returns only when the caller's own hold and every share of it have
         * departed - so work dispatched by the nested call can outlive the caller and still be
         * counted.
         * </p>
         *
         * @return a ticket holding a share of this one's permit, or a permit-less ticket when
         *         this one holds no permit
         */
        Ticket share()
        {
            return lease == null ? new Ticket((Lease)null) : lease.share();
        }
    }

    /**
     * One permit of the heavy-tool limiter and everyone still holding it.
     * <p>
     * The count starts at one - the call that took the permit - and grows by every share handed to
     * a nested call. Each holder departs at most once (a ticket's spend is single-shot), so the
     * count cannot pass zero, and the permit is returned at the one moment it reaches it.
     * </p>
     */
    private static final class Lease
    {
        private final Runnable release;

        private final AtomicInteger holders = new AtomicInteger(1);

        Lease(Runnable release)
        {
            this.release = release;
        }

        boolean isHeld()
        {
            return holders.get() > 0;
        }

        void depart()
        {
            if (holders.decrementAndGet() == 0)
            {
                release.run();
            }
        }

        Ticket share()
        {
            holders.incrementAndGet();
            return new Ticket(this);
        }

        void transferTo(PendingWorkRegistry.PendingEntry entry)
        {
            CompletableFuture<String> future = entry.future;
            if (future == null)
            {
                depart();
                return;
            }
            // Not the future's completion, which a cancel moves while the body still runs: the
            // body's own exit is what says the session's resources are free again.
            entry.attachWorkExit(this::depart);
        }
    }
}
