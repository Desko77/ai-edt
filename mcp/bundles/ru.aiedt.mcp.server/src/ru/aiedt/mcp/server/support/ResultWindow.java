/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.wire.GsonHolder;

/**
 * The window through which an operation returns a part of a pinned selection, and the rules that
 * turn a continuation back into a page.
 * <p>
 * The window is a leaf of the response stack, not a transport: it knows nothing about MCP, tools
 * or schemas. An operation hands it the already collected sequence, the arguments that shaped the
 * call and a declared {@link ResultArgumentProfile}, and receives the page's units plus the
 * envelope a caller needs to ask for the next one. A response that fits would not be routed through
 * a window at all; the operation keeps returning its own shape unchanged, which is why this class
 * has no "wrap everything" entry point.
 * </p>
 *
 * <h2>Two kinds of incompleteness</h2>
 * <p>
 * A finished collection may be larger than one answer. The window pages it: the total is exact, a
 * {@code next} points at the following unit, and {@code truncated} says part of the collected set
 * was shown. A collection that never finished is a different fact. Its total is a lower bound, its
 * {@code coverage} names what was not reached, and its {@code next} may only page the subset that
 * was actually collected - never re-collect, because a second constrained run is a different
 * subset.
 * </p>
 *
 * <h2>The envelope budget</h2>
 * <p>
 * The envelope - counters, coverage, snapshot and the continuation - may take at most
 * {@link #ENVELOPE_BUDGET_DIVISOR} of the response ceiling. It is measured as the serialized JSON
 * that will be sent, so quotes, backslashes and HTML escaping count for what they cost the client.
 * When the full argument set does not fit, arguments are dropped by descending serialized size
 * (ties by name) until it does; numeric, boolean and declared-enumerable arguments are never
 * dropped, and a required argument is emptied rather than removed. The fact that arguments were
 * dropped is carried inside the {@code resultId} token: after the cache forgets the selection there
 * is nothing else left to ask, and a short-form continuation must be refused rather than rebuilt
 * from an incomplete call.
 * </p>
 *
 * <h2>What a continuation may rebuild</h2>
 * <p>
 * Only a finished, reading collection whose arguments are all present may be collected again. The
 * rebuilt selection is compared through its {@code snapshot}, which covers the whole sequence, the
 * project context, the shaping arguments and any source fingerprint. A mismatch is a named
 * refusal. An incomplete collection, a changing call and a short-form continuation are refused
 * without running anything: the first would produce a different subset, the second is not safe to
 * repeat, and the third does not carry the original call. The changing flag comes from the
 * caller's explicit profile, never from the presence of a key such as {@code apply}.
 * </p>
 *
 * <h2>One canonical spelling of the arguments</h2>
 * <p>
 * The continuation envelope shows enumerated arguments in the canonical spelling the operation
 * resolved, and the snapshot must describe that same spelling: a selection pinned with a padded
 * {@code intent} and a selection rebuilt from the canonical {@code intent} are one call, not two.
 * Both the pinned and the rebuilt selection therefore pass through
 * {@link PinnedResult#withCanonicalArguments} with the request's profile before their snapshots
 * are compared, so one normalization - the profile's - decides both.
 * </p>
 *
 * <h2>A unit larger than the whole ceiling</h2>
 * <p>
 * A unit that alone cannot fit is never dropped and never blocks the sequence: the page shows a
 * prefix of its serialized form and moves on. The serialized item is an object carrying the
 * prefix under {@code value}, {@code unitTruncated} and the full length {@code fullLength}, so
 * the caller reads the truncation from the response itself and not only from this class's Java
 * API. Units that are shown whole keep their own shape, with no wrapper.
 * </p>
 * <p>
 * When the ceiling cannot hold the mandatory members of even a minimal page - fixed members,
 * counters, a snapshot - no honest page exists, and the window refuses by name rather than
 * sending an answer over the ceiling: {@link ResultRefusal.Reason#MANDATORY_MEMBERS_EXCEED_CEILING}.
 * </p>
 */
public final class ResultWindow
{
    /** The envelope may take one tenth of the response ceiling. */
    public static final int ENVELOPE_BUDGET_DIVISOR = 10;

    /** The fixed upper bound the minimal envelope is designed to stay under. */
    public static final int MINIMAL_ENVELOPE_CHARS = 1024;

    private static final int DEFAULT_MAX_COVERAGE_CHARS = 256;

    private static final String KEY_TOTAL = "total"; //$NON-NLS-1$

    private static final String KEY_TOTAL_EXACT = "totalExact"; //$NON-NLS-1$

    private static final String KEY_RETURNED = "returned"; //$NON-NLS-1$

    private static final String KEY_TRUNCATED = "truncated"; //$NON-NLS-1$

    private static final String KEY_COVERAGE = "coverage"; //$NON-NLS-1$

    private static final String KEY_FIELD_TRUNCATED = "fieldTruncated"; //$NON-NLS-1$

    private static final String KEY_SHORT_FORM = "shortForm"; //$NON-NLS-1$

    private static final String KEY_ARGS_ELIDED = "argsElided"; //$NON-NLS-1$

    private static final String KEY_SNAPSHOT = "snapshot"; //$NON-NLS-1$

    private static final String KEY_NEXT = "next"; //$NON-NLS-1$

    private static final String KEY_RESULT_ID = "resultId"; //$NON-NLS-1$

    private static final String KEY_ITEMS = "items"; //$NON-NLS-1$

    private static final String KEY_VALUE = "value"; //$NON-NLS-1$

    private static final String KEY_UNIT_TRUNCATED = "unitTruncated"; //$NON-NLS-1$

    private static final String KEY_FULL_LENGTH = "fullLength"; //$NON-NLS-1$

    private static final Set<String> ROOT_MEMBERS = reserved(KEY_TOTAL, KEY_TOTAL_EXACT,
        KEY_RETURNED, KEY_TRUNCATED, KEY_COVERAGE, KEY_FIELD_TRUNCATED, KEY_SHORT_FORM,
        KEY_ARGS_ELIDED, KEY_SNAPSHOT, KEY_NEXT, KEY_ITEMS);

    private final PinnedResultCache cache;

    private final long ceilingChars;

    private final long envelopeBudgetChars;

    /**
     * Creates a window.
     *
     * @param cache where pinned selections are stored; never {@code null}
     * @param ceilingChars the response size ceiling in characters; must be positive
     * @param envelopeBudgetChars the envelope budget in characters, or {@code <= 0} to use one
     *            tenth of the ceiling; neither may exceed the ceiling
     */
    public ResultWindow(PinnedResultCache cache, long ceilingChars, long envelopeBudgetChars)
    {
        if (cache == null)
        {
            throw new IllegalArgumentException("a result window needs a cache"); //$NON-NLS-1$
        }
        if (ceilingChars <= 0)
        {
            throw new IllegalArgumentException("the response ceiling must be positive"); //$NON-NLS-1$
        }
        long budget = envelopeBudgetChars <= 0
            ? ceilingChars / ENVELOPE_BUDGET_DIVISOR : envelopeBudgetChars;
        if (budget <= 0 || budget > ceilingChars)
        {
            throw new IllegalArgumentException("the envelope budget must be positive and no larger " //$NON-NLS-1$
                + "than the response ceiling"); //$NON-NLS-1$
        }
        this.cache = cache;
        this.ceilingChars = ceilingChars;
        this.envelopeBudgetChars = budget;
    }

    /**
     * The page of a freshly collected selection.
     * <p>
     * The selection is pinned only when the page actually points at a next one. A response that
     * fits - the typical answer - leaves no envelope and no cache entry. Before anything is
     * pinned or hashed, enumerated arguments are replaced by their canonical spelling, so the
     * snapshot and the continuation describe the same call.
     * </p>
     *
     * @param selection the full collection
     * @param request the operation's declarations for this call
     * @return the page
     */
    public Page firstPage(PinnedResult selection, Request request)
    {
        if (selection == null || request == null)
        {
            throw new IllegalArgumentException("a first page needs a selection and a request"); //$NON-NLS-1$
        }
        PinnedResult canonical = PinnedResult.withCanonicalArguments(selection,
            request.profile().enumerables());
        UUID id = UUID.randomUUID();
        Page page = buildPage(canonical, 0L, request, id);
        if (page.hasNext() && !cache.put(id, canonical))
        {
            // The caller was about to be handed a continuation that cannot exist. Failing here is
            // the only honest answer: silently sending a next that the cache refuses would turn a
            // working call into a dead token on the client's next request.
            throw new IllegalStateException("the pinned selection exceeds the cache memory ceiling"); //$NON-NLS-1$
        }
        return page;
    }

    /**
     * The page of a continuation.
     * <p>
     * A live selection is served from the cache without collecting anything. A gone selection is
     * rebuilt only when the profile says the call reads, the token says the collection finished and
     * the token is not short-form. A rebuild is compared to the snapshot the continuation carried;
     * a different set is refused rather than mixed into pages the caller already saw.
     * </p>
     *
     * @param resultId the token from the previous page's {@code next}
     * @param callArguments the continuation's arguments as received
     * @param request the operation's declarations for this call
     * @param rebuild how to collect the selection again; may be {@code null} when the operation
     *            cannot rebuild at all
     * @return the page
     */
    public Page continuation(String resultId, Map<String, Object> callArguments, Request request,
        Rebuild rebuild)
    {
        if (request == null)
        {
            throw new IllegalArgumentException("a continuation needs a request"); //$NON-NLS-1$
        }
        PinnedResultCache.ParsedResultId parsed = PinnedResultCache.parse(resultId);
        if (parsed == null)
        {
            throw new ResultRefusal(ResultRefusal.Reason.UNKNOWN_RESULT_ID,
                "The resultId is not one this server issued; repeat the original call."); //$NON-NLS-1$
        }
        Map<String, Object> arguments = PinnedResult.copyArguments(callArguments);
        long position = positionOf(arguments, request.positionKey());

        PinnedResult live = cache.get(parsed.id);
        if (live != null)
        {
            checkSnapshot(arguments, live);
            return buildPage(live, position, request, parsed.id);
        }

        if (parsed.shortForm)
        {
            throw new ResultRefusal(ResultRefusal.Reason.SHORT_FORM_NO_LONGER_STORED,
                "This page was a short-form continuation, so its arguments are not the original " //$NON-NLS-1$
                    + "call and it cannot be rebuilt; repeat the original call."); //$NON-NLS-1$
        }
        if (parsed.incomplete)
        {
            throw new ResultRefusal(ResultRefusal.Reason.INCOMPLETE_SELECTION_NO_LONGER_STORED,
                "This collection never finished; a second run would be a different subset. " //$NON-NLS-1$
                    + "Repeat the original call to collect it again."); //$NON-NLS-1$
        }
        if (request.profile().changesState())
        {
            throw new ResultRefusal(ResultRefusal.Reason.CHANGING_CALL_NOT_REPLAYED,
                "This call changes the project or an infobase, so it is not re-run to answer a " //$NON-NLS-1$
                    + "continuation; repeat the original call."); //$NON-NLS-1$
        }
        if (rebuild == null)
        {
            throw new ResultRefusal(ResultRefusal.Reason.REBUILD_UNAVAILABLE,
                "The selection is gone and this operation cannot rebuild it; repeat the original " //$NON-NLS-1$
                    + "call."); //$NON-NLS-1$
        }

        String expected = stringValue(arguments.get(KEY_SNAPSHOT));
        if (expected == null)
        {
            throw new ResultRefusal(ResultRefusal.Reason.SELECTION_CHANGED,
                "The continuation carried no snapshot, so a rebuilt selection cannot be compared " //$NON-NLS-1$
                    + "with the one the caller saw; repeat the original call."); //$NON-NLS-1$
        }
        PinnedResult rebuilt = rebuild.rebuild(arguments);
        if (rebuilt == null)
        {
            throw new ResultRefusal(ResultRefusal.Reason.REBUILD_UNAVAILABLE,
                "The operation returned no rebuilt selection; repeat the original call."); //$NON-NLS-1$
        }
        if (!rebuilt.complete())
        {
            throw new ResultRefusal(ResultRefusal.Reason.INCOMPLETE_SELECTION_NO_LONGER_STORED,
                "The rebuilt collection is itself incomplete, so it cannot stand in for the " //$NON-NLS-1$
                    + "pinned one; repeat the original call."); //$NON-NLS-1$
        }
        // The same canonical spelling the pinned selection was hashed under: a rebuild from the
        // continuation's canonical arguments must describe the same call as the original, padded
        // spelling did.
        PinnedResult canonical = PinnedResult.withCanonicalArguments(rebuilt,
            request.profile().enumerables());
        if (!expected.equals(canonical.snapshot()))
        {
            throw new ResultRefusal(ResultRefusal.Reason.SELECTION_CHANGED,
                "The collection differs from the one the caller was reading; repeat the original " //$NON-NLS-1$
                    + "call to work from the new one."); //$NON-NLS-1$
        }
        UUID id = UUID.randomUUID();
        Page page = buildPage(canonical, position, request, id);
        if (page.hasNext() && !cache.put(id, canonical))
        {
            throw new IllegalStateException("the rebuilt selection exceeds the cache memory ceiling"); //$NON-NLS-1$
        }
        return page;
    }

    /**
     * The character ceiling this window obeys.
     *
     * @return the ceiling in characters
     */
    public long ceilingChars()
    {
        return ceilingChars;
    }

    /**
     * The envelope budget this window obeys.
     *
     * @return the budget in characters
     */
    public long envelopeBudgetChars()
    {
        return envelopeBudgetChars;
    }

    private void checkSnapshot(Map<String, Object> arguments, PinnedResult live)
    {
        String expected = stringValue(arguments.get(KEY_SNAPSHOT));
        if (expected != null && !expected.equals(live.snapshot()))
        {
            throw new ResultRefusal(ResultRefusal.Reason.SELECTION_CHANGED,
                "The snapshot in the continuation does not match the pinned selection; repeat the " //$NON-NLS-1$
                    + "original call."); //$NON-NLS-1$
        }
    }

    private long positionOf(Map<String, Object> arguments, String positionKey)
    {
        Object raw = arguments.get(positionKey);
        Long value = null;
        if (raw instanceof Number)
        {
            value = Long.valueOf(((Number)raw).longValue());
        }
        else if (raw instanceof String)
        {
            try
            {
                value = Long.valueOf(((String)raw).trim());
            }
            catch (NumberFormatException notANumber)
            {
                value = null;
            }
        }
        if (value == null || value.longValue() < 0)
        {
            throw new ResultRefusal(ResultRefusal.Reason.POSITION_NOT_ADVANCING,
                "The continuation has no usable position; it cannot move forward."); //$NON-NLS-1$
        }
        return value.longValue();
    }

    private Page buildPage(PinnedResult pinned, long start, Request request, UUID id)
    {
        int total = pinned.unitCount();
        if (start > total)
        {
            throw new ResultRefusal(ResultRefusal.Reason.POSITION_NOT_ADVANCING,
                "The continuation points past the end of the pinned selection."); //$NON-NLS-1$
        }
        CoverageField baseCoverage = composeCoverage(pinned, null, request.maxCoverageChars());
        if (start == total)
        {
            JsonObject envelope = pinned.complete() ? null
                : envelope(pinned, request, null, 0, start, false, false, Collections.emptyList(),
                    baseCoverage);
            List<String> empty = new ArrayList<>();
            String json = pageJson(request.fixed(), envelope, empty);
            if (json.length() > ceilingChars)
            {
                // An empty remainder has no units to drop and the envelope already fits its own
                // budget; when the mandatory members still do not fit the ceiling, no honest page
                // exists. Refusing by name beats sending an answer over the ceiling.
                throw new ResultRefusal(ResultRefusal.Reason.MANDATORY_MEMBERS_EXCEED_CEILING,
                    "The mandatory members of this response alone exceed the response ceiling; " //$NON-NLS-1$
                        + "no page can be sent for this call."); //$NON-NLS-1$
            }
            boolean coverageCut = baseCoverage.truncated
                || envelope != null && envelope.has(KEY_FIELD_TRUNCATED);
            return new Page(Collections.emptyList(), envelope, json, null, pinned.snapshot(), start,
                baseCoverage.full, coverageCut);
        }

        int maxK = total - (int)start;
        if (request.maxUnitsPerPage() > 0)
        {
            maxK = Math.min(maxK, request.maxUnitsPerPage());
        }

        // A response that fits needs no envelope at all - neither a finished collection that was
        // shown whole, nor the last page of a bigger one. Deciding this first also keeps the
        // envelope budget from refusing a selection that never needed a continuation.
        if (start + maxK == total)
        {
            JsonObject noNextEnvelope = pinned.complete() ? null
                : envelope(pinned, request, null, maxK, start + maxK, false, false,
                    Collections.emptyList(), baseCoverage);
            List<String> jsonItems = unitJson(pinned.units(), (int)start, maxK);
            long size = rootBaseSize(request.fixed(), noNextEnvelope) + jsonItemsSize(jsonItems);
            if (size <= ceilingChars)
            {
                return pageOf(pinned, request, start, maxK, noNextEnvelope, jsonItems, false, -1,
                    baseCoverage);
            }
        }

        // Whether a next page can exist at all is decided by the position, not by the envelope: a
        // page that starts at the last unit has nowhere to continue to. Planning an envelope with a
        // next for it used to reject a lone oversized unit whose shortened form fits the ceiling,
        // because the next envelope - which the page will never carry - did not fit its budget.
        EnvelopePlan preliminary = start + 1 < total
            ? planEnvelope(pinned, request, id, 1, start + 1, baseCoverage) : null;
        int chosen = 0;
        long unitsSize = 0;
        for (int k = 1; k <= maxK; k++)
        {
            boolean hasNext = start + k < total;
            JsonObject envelope = hasNext || !pinned.complete()
                ? envelope(pinned, request, preliminary, k, start + k, hasNext, false,
                    Collections.emptyList(), baseCoverage)
                : null;
            int unitJsonLength = pinned.units().get((int)start + k - 1).json().length();
            long candidateSize = rootBaseSize(request.fixed(), envelope) + unitsSize
                + unitJsonLength + (k > 1 ? 1 : 0);
            if (candidateSize > ceilingChars)
            {
                break;
            }
            chosen = k;
            unitsSize += unitJsonLength + (k > 1 ? 1 : 0);
        }

        if (chosen == 0)
        {
            return pageWithTruncatedUnit(pinned, request, id, start);
        }
        // The page that was actually chosen has its own return count and next position. Making the
        // elision decision against those exact digits - rather than the largest possible ones -
        // is what keeps a value that does fit the budget in the continuation. The preliminary plan
        // was only a sizing aid.
        boolean hasNext = start + chosen < total;
        EnvelopePlan exact = hasNext
            ? planEnvelope(pinned, request, id, chosen, start + chosen, baseCoverage) : null;
        JsonObject envelope = hasNext || !pinned.complete()
            ? envelope(pinned, request, exact, chosen, start + chosen, hasNext, false,
                Collections.emptyList(), baseCoverage)
            : null;
        List<String> jsonItems = unitJson(pinned.units(), (int)start, chosen);
        Page page = pageOf(pinned, request, start, chosen, envelope, jsonItems, false, -1,
            baseCoverage);
        // A serialization that came out one or two characters longer than the accumulation
        // predicted must not exceed the ceiling. Rolling back a whole unit is always safe; the
        // final single unit is protected by the truncation path above.
        while (page.json().length() > ceilingChars && chosen > 1)
        {
            chosen--;
            hasNext = start + chosen < total;
            exact = hasNext
                ? planEnvelope(pinned, request, id, chosen, start + chosen, baseCoverage) : null;
            envelope = hasNext || !pinned.complete()
                ? envelope(pinned, request, exact, chosen, start + chosen, hasNext, false,
                    Collections.emptyList(), baseCoverage)
                : null;
            jsonItems = unitJson(pinned.units(), (int)start, chosen);
            page = pageOf(pinned, request, start, chosen, envelope, jsonItems, false, -1,
                baseCoverage);
        }
        if (page.json().length() > ceilingChars)
        {
            // The single remaining unit is protected by the truncation path: never hand back an
            // answer that is over the ceiling, even if the accumulated and the serialized sizes
            // disagree for some reason the arithmetic above did not model.
            return pageWithTruncatedUnit(pinned, request, id, start);
        }
        return page;
    }

    private Page pageWithTruncatedUnit(PinnedResult pinned, Request request, UUID id, long start)
    {
        ResultUnit first = pinned.units().get((int)start);
        // A unit that alone exceeds the ceiling is delivered as a prefix of its serialized form -
        // the text itself for a text unit, the JSON for a structural one - so no unit disappears
        // silently and the cursor still advances past it.
        String source = first.isText() ? first.text() : first.json();
        boolean hasNext = start + 1 < pinned.unitCount();
        CoverageField coverage = composeCoverage(pinned,
            "unit at position " + start + " was shortened from " + source.length() + " characters", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            request.maxCoverageChars());
        // The note changes the coverage field, so the plan is made again against the envelope this
        // page will really send. Reusing the normal-page plan could push this one over the budget
        // by the size of the note.
        EnvelopePlan truncationPlan = hasNext
            ? planEnvelope(pinned, request, id, 1, start + 1, coverage) : null;
        JsonObject envelope = envelope(pinned, request, truncationPlan, 1, start + 1, hasNext, true,
            Collections.emptyList(), coverage);
        long available = ceilingChars - rootBaseSize(request.fixed(), envelope);
        int wrapperOverhead = truncatedItemJson("", source.length()).length(); //$NON-NLS-1$
        if (available <= wrapperOverhead + 2)
        {
            throw new ResultRefusal(ResultRefusal.Reason.MANDATORY_MEMBERS_EXCEED_CEILING,
                "The mandatory members of this response leave no room for even a shortened unit " //$NON-NLS-1$
                    + "under the response ceiling."); //$NON-NLS-1$
        }
        int cut = longestSerializedPrefix(source,
            (int)Math.min(Integer.MAX_VALUE, available - wrapperOverhead));
        List<String> jsonItems = new ArrayList<>();
        jsonItems.add(truncatedItemJson(source.substring(0, cut), source.length()));
        Page page = pageOf(pinned, request, start, 1, envelope, jsonItems, true, source.length(),
            coverage);
        while (page.json().length() > ceilingChars && cut > 0)
        {
            cut = cut > 4 ? cut - 4 : cut - 1;
            cut = codePointBoundary(source, cut);
            jsonItems = new ArrayList<>();
            jsonItems.add(truncatedItemJson(source.substring(0, cut), source.length()));
            page = pageOf(pinned, request, start, 1, envelope, jsonItems, true, source.length(),
                coverage);
        }
        if (page.json().length() > ceilingChars)
        {
            throw new ResultRefusal(ResultRefusal.Reason.MANDATORY_MEMBERS_EXCEED_CEILING,
                "The mandatory members of this response leave no room for even a shortened unit " //$NON-NLS-1$
                    + "under the response ceiling."); //$NON-NLS-1$
        }
        return page;
    }

    /**
     * The serialized form of a shortened unit: the prefix under {@code value}, the fact of the
     * truncation and the full length, so the response itself says what was cut.
     *
     * @param shown the prefix that fits
     * @param fullLength the full length of the unit's serialized form
     * @return the serialized item
     */
    private static String truncatedItemJson(String shown, int fullLength)
    {
        JsonObject item = new JsonObject();
        item.addProperty(KEY_VALUE, shown);
        item.addProperty(KEY_UNIT_TRUNCATED, true);
        item.addProperty(KEY_FULL_LENGTH, fullLength);
        return GsonHolder.toJson(item);
    }

    private Page pageOf(PinnedResult pinned, Request request, long start, int count,
        JsonObject envelope, List<String> jsonItems, boolean unitTruncated, int fullLength,
        CoverageField coverage)
    {
        List<PageItem> items = new ArrayList<>(count);
        String resultId = null;
        if (envelope != null && envelope.has(KEY_NEXT))
        {
            resultId = envelope.getAsJsonObject(KEY_NEXT).get(KEY_RESULT_ID).getAsString();
        }
        for (int i = 0; i < count; i++)
        {
            ResultUnit unit = pinned.units().get((int)start + i);
            String json = jsonItems.get(i);
            JsonElement value = JsonParser.parseString(json);
            boolean truncatedHere = unitTruncated && i == 0;
            if (truncatedHere)
            {
                // The serialized item is the wrapper that carries the truncation facts; the value
                // an operation works with is the prefix it wraps.
                value = value.getAsJsonObject().get(KEY_VALUE);
            }
            items.add(new PageItem(unit, value, start + i, truncatedHere,
                truncatedHere ? fullLength : unit.textLength()));
        }
        String json = pageJson(request.fixed(), envelope, jsonItems);
        boolean coverageCut = coverage.truncated
            || envelope != null && envelope.has(KEY_FIELD_TRUNCATED);
        return new Page(items, envelope, json, resultId, pinned.snapshot(), start, coverage.full,
            coverageCut);
    }

    private EnvelopePlan planEnvelope(PinnedResult pinned, Request request, UUID id,
        long maxReturned, long maxNextPosition, CoverageField coverage)
    {
        Map<String, Object> canonical = pinned.arguments();
        for (Map.Entry<String, String> declared : request.profile().enumerables().entrySet())
        {
            if (canonical.containsKey(declared.getKey()))
            {
                canonical.put(declared.getKey(), declared.getValue());
            }
        }
        EnvelopePlan fullPlan = EnvelopePlan.full(canonical).withId(id);
        JsonObject full = envelope(pinned, request, fullPlan, maxReturned,
            maxNextPosition, true, true, Collections.emptyList(), coverage);
        if (GsonHolder.toJson(full).length() <= envelopeBudgetChars)
        {
            return fullPlan;
        }

        Set<String> reserved = new LinkedHashSet<>();
        reserved.add(KEY_RESULT_ID);
        reserved.add(KEY_SNAPSHOT);
        reserved.add(request.positionKey());
        List<String> order = request.profile().elisionOrder(canonical, reserved);
        EnvelopePlan shortPlan = EnvelopePlan.shortForm(canonical).withId(id);
        for (String name : order)
        {
            Object value = canonical.get(name);
            if (request.profile().required().contains(name))
            {
                Object empty = ResultArgumentProfile.emptyValueOf(value);
                if (empty == null)
                {
                    continue;
                }
                canonical.put(name, empty);
            }
            else
            {
                canonical.remove(name);
            }
            shortPlan.elide(name);
            JsonObject candidate = envelope(pinned, request, shortPlan, maxReturned, maxNextPosition,
                true, true, shortPlan.elided(), coverage);
            if (GsonHolder.toJson(candidate).length() <= envelopeBudgetChars)
            {
                return shortPlan;
            }
        }
        throw new IllegalArgumentException("the envelope cannot fit the budget even with every " //$NON-NLS-1$
            + "elidable argument removed"); //$NON-NLS-1$
    }

    private JsonObject envelope(PinnedResult pinned, Request request, EnvelopePlan plan,
        long returned, long nextPosition, boolean hasNext, boolean unitTruncated,
        List<String> elided, CoverageField coverage)
    {
        JsonObject built = buildEnvelope(pinned, request, plan, returned, nextPosition, hasNext,
            unitTruncated, elided, coverage);
        if (hasNext || GsonHolder.toJson(built).length() <= envelopeBudgetChars)
        {
            return built;
        }
        // Without a next there are no arguments to drop, so the only thing that can shrink the
        // envelope is the coverage text. The budget applies to the envelope as a whole; when the
        // page is the last one, the field is the only discretionary member.
        String shown = coverage.text;
        boolean truncated = coverage.truncated;
        while (GsonHolder.toJson(built).length() > envelopeBudgetChars && shown.length() > 0)
        {
            int excess = (int)(GsonHolder.toJson(built).length() - envelopeBudgetChars);
            int shorter = Math.max(0, shown.length() - Math.max(1, excess));
            shown = shown.substring(0, codePointBoundary(shown, shorter));
            truncated = true;
            built = buildEnvelope(pinned, request, plan, returned, nextPosition, hasNext,
                unitTruncated, elided, new CoverageField(shown, coverage.full, truncated));
        }
        if (GsonHolder.toJson(built).length() > envelopeBudgetChars)
        {
            throw new IllegalArgumentException(
                "the envelope cannot fit its budget even without a coverage field"); //$NON-NLS-1$
        }
        return built;
    }

    private JsonObject buildEnvelope(PinnedResult pinned, Request request, EnvelopePlan plan,
        long returned, long nextPosition, boolean hasNext, boolean unitTruncated,
        List<String> elided, CoverageField coverage)
    {
        JsonObject envelope = new JsonObject();
        envelope.addProperty(KEY_TOTAL, pinned.unitCount());
        envelope.addProperty(KEY_TOTAL_EXACT, pinned.complete());
        envelope.addProperty(KEY_RETURNED, returned);
        envelope.addProperty(KEY_TRUNCATED, hasNext || unitTruncated);
        envelope.addProperty(KEY_COVERAGE, coverage.text);
        if (coverage.truncated)
        {
            envelope.addProperty(KEY_FIELD_TRUNCATED, true);
        }
        envelope.addProperty(KEY_SNAPSHOT, pinned.snapshot());
        if (hasNext && plan != null)
        {
            if (plan.shortForm())
            {
                envelope.addProperty(KEY_SHORT_FORM, true);
                JsonArray names = new JsonArray();
                for (String name : plan.elided())
                {
                    names.add(name);
                }
                envelope.add(KEY_ARGS_ELIDED, names);
            }
            String token = PinnedResultCache.resultId(plan.id(), !pinned.complete(),
                plan.shortForm());
            JsonObject next = new JsonObject();
            for (Map.Entry<String, Object> argument : plan.arguments().entrySet())
            {
                next.add(argument.getKey(), PinnedResult.canonical(argument.getValue()));
            }
            next.addProperty(KEY_RESULT_ID, token);
            next.addProperty(KEY_SNAPSHOT, pinned.snapshot());
            next.addProperty(request.positionKey(), nextPosition);
            envelope.add(KEY_NEXT, next);
        }
        return envelope;
    }

    private CoverageField composeCoverage(PinnedResult pinned, String note, int maxChars)
    {
        String base = pinned.complete() ? "complete" : pinned.coverage(); //$NON-NLS-1$
        String full = note == null ? base : base + "; " + note; //$NON-NLS-1$
        if (full.length() <= maxChars)
        {
            return new CoverageField(full, full, false);
        }
        int cut = codePointBoundary(full, maxChars);
        return new CoverageField(full.substring(0, cut), full, true);
    }

    private long rootBaseSize(Map<String, Object> fixed, JsonObject envelope)
    {
        return GsonHolder.toJson(root(fixed, envelope, new JsonArray())).length();
    }

    private String pageJson(Map<String, Object> fixed, JsonObject envelope, List<String> units)
    {
        JsonArray array = new JsonArray();
        for (String unit : units)
        {
            array.add(JsonParser.parseString(unit));
        }
        return GsonHolder.toJson(root(fixed, envelope, array));
    }

    private JsonObject root(Map<String, Object> fixed, JsonObject envelope, JsonArray items)
    {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Object> member : fixed.entrySet())
        {
            root.add(member.getKey(), JsonParser.parseString(GsonHolder.toJson(member.getValue())));
        }
        if (envelope != null)
        {
            for (Map.Entry<String, JsonElement> member : envelope.entrySet())
            {
                root.add(member.getKey(), member.getValue().deepCopy());
            }
        }
        root.add(KEY_ITEMS, items);
        return root;
    }

    private static List<String> unitJson(List<ResultUnit> units, int start, int count)
    {
        List<String> json = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            json.add(units.get(start + i).json());
        }
        return json;
    }

    private static long jsonItemsSize(List<String> jsonItems)
    {
        long size = 0;
        for (int i = 0; i < jsonItems.size(); i++)
        {
            size += jsonItems.get(i).length() + (i > 0 ? 1 : 0);
        }
        return size;
    }

    /**
     * The longest prefix of a text whose serialized JSON value fits the given number of characters.
     * <p>
     * Measured on the serialized value, not on the raw text: a quote or a backslash costs two
     * characters in the response and a surrogate pair counts as the two characters Java hides in
     * it, so a raw-length cut would promise a size the client does not receive. The cut never
     * splits a surrogate pair.
     * </p>
     *
     * @param text the text to shorten
     * @param available how many serialized characters are available
     * @return the prefix length in characters
     */
    static int longestSerializedPrefix(String text, int available)
    {
        int low = 0;
        int high = text.length();
        int best = 0;
        while (low <= high)
        {
            int middle = low + (high - low) / 2;
            int cut = codePointBoundary(text, middle);
            if (GsonHolder.toJson(text.substring(0, cut)).length() <= available)
            {
                best = cut;
                low = middle + 1;
            }
            else
            {
                high = middle - 1;
            }
        }
        return best;
    }

    /**
     * Moves a cut back by one when it would split a surrogate pair.
     *
     * @param text the text
     * @param cut the proposed length
     * @return the length that ends on a whole code point
     */
    static int codePointBoundary(String text, int cut)
    {
        if (cut > 0 && cut < text.length() && Character.isHighSurrogate(text.charAt(cut - 1))
            && Character.isLowSurrogate(text.charAt(cut)))
        {
            return cut - 1;
        }
        return cut;
    }

    private static String stringValue(Object value)
    {
        return value == null ? null : String.valueOf(value);
    }

    private static Set<String> reserved(String... names)
    {
        Set<String> set = new LinkedHashSet<>();
        Collections.addAll(set, names);
        return Collections.unmodifiableSet(set);
    }

    /** How to collect a selection again when its cache record is gone. */
    @FunctionalInterface
    public interface Rebuild
    {
        /**
         * Collects the selection again.
         *
         * @param callArguments the continuation's arguments as received, control keys included
         * @return the rebuilt selection, or {@code null} when it cannot be produced
         */
        PinnedResult rebuild(Map<String, Object> callArguments);
    }

    /**
     * What an operation declares for one call: how its arguments are classified, which fields are
     * mandatory, and where the cursor lives.
     */
    public static final class Request
    {
        private final ResultArgumentProfile profile;

        private final Map<String, Object> fixed;

        private final int maxCoverageChars;

        private final String positionKey;

        private final int maxUnitsPerPage;

        private Request(Builder builder)
        {
            this.profile = builder.profile;
            this.fixed = PinnedResult.copyArguments(builder.fixed);
            this.maxCoverageChars = builder.maxCoverageChars;
            this.positionKey = builder.positionKey;
            this.maxUnitsPerPage = builder.maxUnitsPerPage;
        }

        /**
         * Starts a request for an operation's profile.
         *
         * @param profile the profile, never {@code null}
         * @return the builder
         */
        public static Builder builder(ResultArgumentProfile profile)
        {
            return new Builder(profile);
        }

        /**
         * The argument classification.
         *
         * @return the profile
         */
        public ResultArgumentProfile profile()
        {
            return profile;
        }

        /**
         * Members that must appear in every response and are never dropped for size.
         *
         * @return the fixed members, never {@code null}
         */
        public Map<String, Object> fixed()
        {
            return PinnedResult.copyArguments(fixed);
        }

        /**
         * How many characters of the coverage string the envelope may show.
         *
         * @return the limit
         */
        public int maxCoverageChars()
        {
            return maxCoverageChars;
        }

        /**
         * The argument that carries the continuation position.
         *
         * @return the name
         */
        public String positionKey()
        {
            return positionKey;
        }

        /**
         * The largest number of units a page may show, or 0 for no unit limit.
         *
         * @return the page unit limit
         */
        public int maxUnitsPerPage()
        {
            return maxUnitsPerPage;
        }

        /**
         * Collects the request.
         */
        public static final class Builder
        {
            private final ResultArgumentProfile profile;

            private final Map<String, Object> fixed = new LinkedHashMap<>();

            private int maxCoverageChars = DEFAULT_MAX_COVERAGE_CHARS;

            private String positionKey = "position"; //$NON-NLS-1$

            private int maxUnitsPerPage;

            private Builder(ResultArgumentProfile profile)
            {
                if (profile == null)
                {
                    throw new IllegalArgumentException("a request needs an argument profile"); //$NON-NLS-1$
                }
                this.profile = profile;
            }

            /**
             * Adds a member that every page must carry.
             *
             * @param name the member name
             * @param value its value
             * @return this builder
             */
            public Builder fixed(String name, Object value)
            {
                if (name == null || name.trim().isEmpty() || value == null)
                {
                    throw new IllegalArgumentException("a fixed member needs a name and a value"); //$NON-NLS-1$
                }
                if (ROOT_MEMBERS.contains(name))
                {
                    throw new IllegalArgumentException(
                        "'" + name + "' is written by the envelope itself"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                fixed.put(name, value);
                return this;
            }

            /**
             * Sets how much of the coverage string the envelope may show.
             *
             * @param chars the limit; must be positive
             * @return this builder
             */
            public Builder maxCoverageChars(int chars)
            {
                if (chars <= 0)
                {
                    throw new IllegalArgumentException("the coverage field limit must be positive"); //$NON-NLS-1$
                }
                this.maxCoverageChars = chars;
                return this;
            }

            /**
             * Sets the argument that carries the cursor.
             *
             * @param name the name; must not be blank, may be {@code null} for the default
             * @return this builder
             */
            public Builder positionKey(String name)
            {
                if (name != null && name.trim().isEmpty())
                {
                    throw new IllegalArgumentException("the position key must not be blank"); //$NON-NLS-1$
                }
                this.positionKey = name == null ? "position" : name.trim(); //$NON-NLS-1$
                return this;
            }

            /**
             * Limits how many units one page may show.
             *
             * @param units the limit; 0 means no unit limit, never negative
             * @return this builder
             */
            public Builder maxUnitsPerPage(int units)
            {
                if (units < 0)
                {
                    throw new IllegalArgumentException("the page unit limit cannot be negative"); //$NON-NLS-1$
                }
                this.maxUnitsPerPage = units;
                return this;
            }

            /**
             * Builds the request.
             *
             * @return the request
             */
            public Request build()
            {
                return new Request(this);
            }
        }
    }

    /**
     * One page: the units handed over, the envelope a caller needs for the next one, and the exact
     * serialized response.
     */
    public static final class Page
    {
        private final List<PageItem> items;

        private final JsonObject envelope;

        private final String json;

        private final String resultId;

        private final String snapshot;

        private final long position;

        private final String fullCoverage;

        private final boolean coverageTruncated;

        private Page(List<PageItem> items, JsonObject envelope, String json, String resultId,
            String snapshot, long position, String fullCoverage, boolean coverageTruncated)
        {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.envelope = envelope;
            this.json = json;
            this.resultId = resultId;
            this.snapshot = snapshot;
            this.position = position;
            this.fullCoverage = fullCoverage;
            this.coverageTruncated = coverageTruncated;
        }

        /**
         * The units this page shows.
         *
         * @return the items, in the collection's order
         */
        public List<PageItem> items()
        {
            return items;
        }

        /**
         * The envelope, or {@code null} when the page needs none.
         *
         * @return a detached map of the envelope members, or {@code null}
         */
        public Map<String, Object> envelope()
        {
            if (envelope == null)
            {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = (Map<String, Object>)GsonHolder.fromJson(
                GsonHolder.toJson(envelope), Object.class);
            return copy;
        }

        /**
         * The exact serialized page, envelope and fixed members included.
         *
         * @return the JSON that would be sent
         */
        public String json()
        {
            return json;
        }

        /**
         * Whether another page follows this one.
         *
         * @return {@code true} when the envelope points at a next page
         */
        public boolean hasNext()
        {
            return resultId != null;
        }

        /**
         * The token the next page is requested with, or {@code null}.
         *
         * @return the result id
         */
        public String resultId()
        {
            return resultId;
        }

        /**
         * The continuation's arguments, ready to be sent back, or {@code null} when there is none.
         *
         * @return a detached copy of the {@code next} object
         */
        public Map<String, Object> nextArguments()
        {
            if (envelope == null || !envelope.has(KEY_NEXT))
            {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = (Map<String, Object>)GsonHolder.fromJson(
                GsonHolder.toJson(envelope.get(KEY_NEXT)), Object.class);
            return copy;
        }

        /**
         * The snapshot of the pinned selection this page belongs to.
         *
         * @return the digest
         */
        public String snapshot()
        {
            return snapshot;
        }

        /**
         * Where this page starts in the collection's sequence.
         *
         * @return the first unit's absolute position
         */
        public long position()
        {
            return position;
        }

        /**
         * The untruncated coverage text, so an operation can place it in the sequence.
         *
         * @return the full coverage text
         */
        public String fullCoverage()
        {
            return fullCoverage;
        }

        /**
         * Whether the coverage field in the envelope was cut to its field limit.
         *
         * @return {@code true} when the envelope shows only a prefix
         */
        public boolean coverageTruncated()
        {
            return coverageTruncated;
        }
    }

    /** One unit as it appears on a page. */
    public static final class PageItem
    {
        private final ResultUnit unit;

        private final JsonElement value;

        private final long position;

        private final boolean unitTruncated;

        private final int fullLength;

        private PageItem(ResultUnit unit, JsonElement value, long position, boolean unitTruncated,
            int fullLength)
        {
            this.unit = unit;
            this.value = value;
            this.position = position;
            this.unitTruncated = unitTruncated;
            this.fullLength = fullLength;
        }

        /**
         * The unit at the collection's own position.
         *
         * @return the unit
         */
        public ResultUnit unit()
        {
            return unit;
        }

        /**
         * The value as rendered on this page, shortened when the unit alone was too large.
         *
         * @return a detached copy
         */
        public JsonElement value()
        {
            return value.deepCopy();
        }

        /**
         * The unit's position in the collection.
         *
         * @return the absolute position
         */
        public long position()
        {
            return position;
        }

        /**
         * Whether this page had to shorten the unit.
         *
         * @return {@code true} when only a prefix is shown
         */
        public boolean unitTruncated()
        {
            return unitTruncated;
        }

        /**
         * The unit's full length in characters, whether or not it was shortened.
         *
         * @return the character count
         */
        public int fullLength()
        {
            return fullLength;
        }
    }

    /** A coverage string limited to the field budget, with the full text kept for the sequence. */
    private static final class CoverageField
    {
        private final String text;

        private final String full;

        private final boolean truncated;

        private CoverageField(String text, String full, boolean truncated)
        {
            this.text = text;
            this.full = full;
            this.truncated = truncated;
        }
    }

    /** The plan for the continuation envelope: what is kept, what was dropped, and how. */
    private static final class EnvelopePlan
    {
        private final Map<String, Object> arguments;

        private final List<String> elided = new ArrayList<>();

        private boolean shortForm;

        private UUID id;

        private EnvelopePlan(Map<String, Object> arguments)
        {
            this.arguments = arguments;
        }

        private static EnvelopePlan full(Map<String, Object> arguments)
        {
            return new EnvelopePlan(arguments);
        }

        private static EnvelopePlan shortForm(Map<String, Object> arguments)
        {
            EnvelopePlan plan = new EnvelopePlan(arguments);
            plan.shortForm = true;
            return plan;
        }

        private EnvelopePlan withId(UUID selectionId)
        {
            this.id = selectionId;
            return this;
        }

        private void elide(String name)
        {
            if (!elided.contains(name))
            {
                elided.add(name);
            }
        }

        private Map<String, Object> arguments()
        {
            return arguments;
        }

        private List<String> elided()
        {
            return elided;
        }

        private boolean shortForm()
        {
            return shortForm;
        }

        private UUID id()
        {
            return id;
        }
    }
}
