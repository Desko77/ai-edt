/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.wire.GsonHolder;

/**
 * Holds the result window against the contract: the two kinds of incompleteness, the serialized
 * character ceiling, the guarantee of progress, the envelope budget and the transition to short
 * form, and what a continuation may rebuild after its pinned selection is gone.
 * <p>
 * Everything here is synthetic. No test reads a project, calls an EDT model or writes an infobase;
 * a "changing call" is a profile flag and a counter, never an operation.
 * </p>
 */
public class ResultWindowTest
{
    private static final ResultArgumentProfile READ = ResultArgumentProfile.builder("read").build(); //$NON-NLS-1$

    private static final ResultArgumentProfile CHANGING =
        ResultArgumentProfile.builder("change").changesState(true).build(); //$NON-NLS-1$

    // ---------------------------------------------------------------- envelope boundaries

    @Test
    public void anEmptyCompleteAnswerHasNoEnvelopeAndIsNotPinned()
    {
        PinnedResultCache cache = new PinnedResultCache(() -> 0L, 60_000L, 1_000_000L);
        ResultWindow window = new ResultWindow(cache, 1000, 0);
        ResultWindow.Page page = window.firstPage(complete(List.of()), request(READ, 0));
        assertNull(page.envelope());
        assertTrue(page.items().isEmpty());
        assertFalse(page.hasNext());
        assertEquals(0, cache.size());

        JsonObject root = JsonParser.parseString(page.json()).getAsJsonObject();
        assertFalse(root.has("total"));
        assertTrue(root.getAsJsonArray("items").isEmpty());
    }

    @Test
    public void anAnswerExactlyAtTheCeilingIsNotWrapped()
    {
        PinnedResult selection = complete(units(10, 100));
        ResultWindowCacheWindow fixture = windowWithHugeCeiling();
        ResultWindow.Page full = fixture.window.firstPage(selection, request(READ, 0));
        long exact = full.json().length();

        PinnedResultCache belowCache = new PinnedResultCache(() -> 0L, 60_000L, 1_000_000L);
        ResultWindow below = new ResultWindow(belowCache, exact - 1, exact - 1);
        ResultWindow.Page shortened = below.firstPage(selection, request(READ, 0));
        assertNotNull("one character short of the ceiling forces a page", shortened.envelope()); //$NON-NLS-1$
        assertTrue(shortened.json().length() <= exact - 1);

        PinnedResultCache exactCache = new PinnedResultCache(() -> 0L, 60_000L, 1_000_000L);
        ResultWindow exactly = new ResultWindow(exactCache, exact, exact);
        ResultWindow.Page whole = exactly.firstPage(selection, request(READ, 0));
        assertNull(whole.envelope());
        assertEquals(exact, whole.json().length());
        assertEquals(0, exactCache.size());
    }

    @Test
    public void aLongFinishedCollectionIsPagedByCharactersAndTheEnvelopeStaysWithinItsBudget()
    {
        PinnedResultCache cache = new PinnedResultCache(() -> 0L, 60_000L, 100_000_000L);
        ResultWindow window = new ResultWindow(cache, 5000, 0);
        ResultWindow.Page page = window.firstPage(complete(units(200, 100)), request(READ, 0));

        assertNotNull(page.envelope());
        assertTrue(page.items().size() > 1);
        assertTrue(page.items().size() < 200);
        assertTrue(page.json().length() <= 5000);
        assertTrue(envelopeLength(page) <= 500);
        assertTrue(page.envelope().get("truncated").equals(Boolean.TRUE));
        assertEquals(Long.valueOf(200), page.envelope().get("total"));
        assertEquals(Long.valueOf(page.items().size()), page.envelope().get("returned"));
        assertEquals(Long.valueOf(page.items().size()), nextPosition(page));
    }

    @Test
    public void aPageUnitLimitPagesEvenWhenTheWholeAnswerWouldFit()
    {
        PinnedResultCache cache = new PinnedResultCache(() -> 0L, 60_000L, 100_000_000L);
        ResultWindow window = new ResultWindow(cache, 100_000, 10_000);
        ResultWindow.Page page = window.firstPage(complete(units(10, 20)), request(READ, 5));
        assertEquals(5, page.items().size());
        assertTrue(page.hasNext());
        assertEquals(Long.valueOf(5), nextPosition(page));
    }

    @Test
    public void theMinimalEnvelopeStaysUnderOneKilobyteAndWithinATenth()
    {
        PinnedResultCache cache = new PinnedResultCache(() -> 0L, 60_000L, 100_000_000L);
        ResultWindow window = new ResultWindow(cache, 100_000, 0);
        ResultWindow.Page page = window.firstPage(complete(units(10, 20)), request(READ, 3));
        assertTrue(envelopeLength(page) <= ResultWindow.MINIMAL_ENVELOPE_CHARS);
        assertTrue(envelopeLength(page) <= 10_000);
    }

    @Test
    public void fullArgumentsBelowAtAndAboveTheEnvelopeBudgetBehaveAsSpecified()
    {
        long ceiling = 100_000L;
        int budget = 1000;
        // The page limit forces a next page; the filler is an optional string argument.
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(ceiling, budget);
        ResultWindow.Page basePage = fixture.window.firstPage(
            complete(units(10, 20), Map.of("note", "")), //$NON-NLS-1$ //$NON-NLS-2$
            request(READ, 5));
        int base = envelopeLength(basePage);
        assertTrue("the base envelope must leave room for the test", base < budget); //$NON-NLS-1$
        int fillerForBudget = budget - base;

        ResultWindow.Page below = fixture.window.firstPage(
            complete(units(10, 20), Map.of("note", "a".repeat(fillerForBudget - 1))), //$NON-NLS-1$ //$NON-NLS-2$
            request(READ, 5));
        assertEquals(budget - 1, envelopeLength(below));
        assertNull(below.envelope().get("shortForm"));
        assertNull(below.envelope().get("argsElided"));
        assertEquals("a".repeat(fillerForBudget - 1), //$NON-NLS-1$
            below.nextArguments().get("note")); //$NON-NLS-1$

        ResultWindow.Page at = fixture.window.firstPage(
            complete(units(10, 20), Map.of("note", "a".repeat(fillerForBudget))), //$NON-NLS-1$ //$NON-NLS-2$
            request(READ, 5));
        assertEquals(budget, envelopeLength(at));
        assertNull(at.envelope().get("shortForm"));
        assertEquals("a".repeat(fillerForBudget), at.nextArguments().get("note")); //$NON-NLS-1$

        ResultWindow.Page above = fixture.window.firstPage(
            complete(units(10, 20), Map.of("note", "a".repeat(fillerForBudget + 1))), //$NON-NLS-1$ //$NON-NLS-2$
            request(READ, 5));
        assertEquals(Boolean.TRUE, above.envelope().get("shortForm"));
        assertEquals(List.of("note"), above.envelope().get("argsElided")); //$NON-NLS-1$
        assertTrue(envelopeLength(above) <= budget);
        assertFalse(above.nextArguments().containsKey("note")); //$NON-NLS-1$
    }

    @Test
    public void aRequiredArgumentIsEmptiedRatherThanRemovedFromTheContinuation()
    {
        ResultArgumentProfile profile = ResultArgumentProfile.builder("read") //$NON-NLS-1$
            .required("objectFqns") //$NON-NLS-1$
            .build();
        List<String> fqns = new ArrayList<>();
        for (int i = 0; i < 100; i++)
        {
            fqns.add("Catalog.Object" + i); //$NON-NLS-1$
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("objectFqns", fqns); //$NON-NLS-1$
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 500);
        ResultWindow.Page page = fixture.window.firstPage(complete(units(10, 20), arguments),
            request(profile, 5));
        assertEquals(Boolean.TRUE, page.envelope().get("shortForm"));
        assertEquals(List.of("objectFqns"), page.envelope().get("argsElided")); //$NON-NLS-1$
        assertTrue(page.nextArguments().containsKey("objectFqns")); //$NON-NLS-1$
        assertEquals(List.of(), page.nextArguments().get("objectFqns")); //$NON-NLS-1$
    }

    // ---------------------------------------------------------------- argument classification

    @Test
    public void numbersFlagsAndCanonicalEnumsSurviveShortForm()
    {
        ResultArgumentProfile profile = ResultArgumentProfile.builder("compare") //$NON-NLS-1$
            .alwaysKept("offset", "limit", "sectionsOffset") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .enumerable("intent", "REPORT") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("offset", "100"); //$NON-NLS-1$ //$NON-NLS-2$
        arguments.put("limit", Integer.valueOf(50)); //$NON-NLS-1$
        arguments.put("sectionsOffset", "7"); //$NON-NLS-1$ //$NON-NLS-2$
        arguments.put("apply", Boolean.TRUE); //$NON-NLS-1$
        arguments.put("intent", "REPORT"); //$NON-NLS-1$ //$NON-NLS-2$
        arguments.put("scope", "s".repeat(2000)); //$NON-NLS-1$ //$NON-NLS-2$
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 500);
        ResultWindow.Page page = fixture.window.firstPage(complete(units(10, 20), arguments),
            request(profile, 5));
        assertTrue(page.hasNext());
        Map<String, Object> next = page.nextArguments();
        assertEquals("100", next.get("offset")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(50, ((Number)next.get("limit")).intValue()); //$NON-NLS-1$
        assertEquals("7", next.get("sectionsOffset")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, next.get("apply")); //$NON-NLS-1$
        assertEquals("REPORT", next.get("intent")); //$NON-NLS-1$ //$NON-NLS-2$
        @SuppressWarnings("unchecked")
        List<String> elided = (List<String>)page.envelope().get("argsElided"); //$NON-NLS-1$
        assertNotNull(elided);
        assertTrue(elided.contains("scope")); //$NON-NLS-1$
        assertFalse(elided.contains("offset")); //$NON-NLS-1$
        assertFalse(elided.contains("limit")); //$NON-NLS-1$
        assertFalse(elided.contains("sectionsOffset")); //$NON-NLS-1$
        assertFalse(elided.contains("apply")); //$NON-NLS-1$
        assertFalse(elided.contains("intent")); //$NON-NLS-1$
    }

    @Test
    public void anEnumWithThousandsOfSpacesCostsOnlyItsCanonicalValue()
    {
        ResultArgumentProfile profile = ResultArgumentProfile.builder("compare") //$NON-NLS-1$
            .enumerable("intent", "REPORT") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
        String padded = " ".repeat(5000) + "report" + " ".repeat(5000); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("intent", padded); //$NON-NLS-1$
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 500);
        ResultWindow.Page page = fixture.window.firstPage(complete(units(10, 20), arguments),
            request(profile, 5));
        assertNull(page.envelope().get("shortForm"));
        assertEquals("REPORT", page.nextArguments().get("intent")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(page.envelope().get("argsElided"));
        assertTrue(envelopeLength(page) <= 500);
    }

    @Test
    public void theDroppingOrderIsDeterministicLongestFirstThenName()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 500);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("zzz", "z".repeat(2000)); //$NON-NLS-1$ //$NON-NLS-2$
        arguments.put("aaa", "a".repeat(1000)); //$NON-NLS-1$ //$NON-NLS-2$
        arguments.put("bbb", "b".repeat(1000)); //$NON-NLS-1$ //$NON-NLS-2$
        arguments.put("ccc", "c".repeat(500)); //$NON-NLS-1$ //$NON-NLS-2$
        ResultWindow.Page first = fixture.window.firstPage(complete(units(10, 20), arguments),
            request(READ, 5));
        ResultWindowCacheWindow secondFixture = new ResultWindowCacheWindow(100_000L, 500);
        ResultWindow.Page second = secondFixture.window.firstPage(complete(units(10, 20), arguments),
            request(READ, 5));

        @SuppressWarnings("unchecked")
        List<String> firstElided = (List<String>)first.envelope().get("argsElided"); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        List<String> secondElided = (List<String>)second.envelope().get("argsElided"); //$NON-NLS-1$
        assertNotNull(firstElided);
        assertEquals(firstElided, secondElided);
        List<String> expectedOrder = List.of("zzz", "aaa", "bbb", "ccc"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(expectedOrder.subList(0, firstElided.size()), firstElided);
    }

    // ---------------------------------------------------------------- progress and truncation

    @Test
    public void aUnitLongerThanTheCeilingIsShortenedAndTheSequenceAdvances()
    {
        List<ResultUnit> units = new ArrayList<>();
        units.add(ResultUnit.of("L".repeat(5000))); //$NON-NLS-1$
        units.add(ResultUnit.of("after")); //$NON-NLS-1$
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(3000L, 500);
        ResultWindow.Page page = fixture.window.firstPage(complete(units), request(READ, 0));

        assertEquals(1, page.items().size());
        assertTrue(page.items().get(0).unitTruncated());
        assertEquals(5000, page.items().get(0).fullLength());
        assertTrue(page.json().length() <= 3000);
        assertTrue(page.fullCoverage().contains("shortened from 5000")); //$NON-NLS-1$
        assertTrue(page.hasNext());
        assertEquals(Long.valueOf(1), nextPosition(page));

        // The truncation is a fact of the serialized answer, not only of the Java API.
        JsonObject root = JsonParser.parseString(page.json()).getAsJsonObject();
        JsonObject item = root.getAsJsonArray("items").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertTrue(item.getAsJsonPrimitive("unitTruncated").getAsBoolean()); //$NON-NLS-1$
        assertEquals(5000, item.getAsJsonPrimitive("fullLength").getAsInt()); //$NON-NLS-1$
        String shown = item.getAsJsonPrimitive("value").getAsString(); //$NON-NLS-1$
        assertTrue("the shown value is a prefix of the unit", //$NON-NLS-1$
            "L".repeat(5000).startsWith(shown)); //$NON-NLS-1$
        assertTrue(shown.length() > 0);

        ResultWindow.Page last = fixture.window.continuation(page.resultId(), page.nextArguments(),
            request(READ, 0), null);
        assertEquals("after", last.items().get(0).unit().text()); //$NON-NLS-1$
        assertFalse(last.hasNext());
    }

    @Test
    public void aLoneOversizedUnitIsShortenedWithoutPlanningAContinuation()
    {
        // The unit is the last one, so no next page can exist and no continuation envelope is
        // needed. Sizing a page against one used to refuse the call although the shortened form
        // fits the ceiling with the default envelope budget.
        List<ResultUnit> units = new ArrayList<>();
        units.add(ResultUnit.of("L".repeat(5000))); //$NON-NLS-1$
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(3000L, 0);
        ResultWindow.Page page = fixture.window.firstPage(complete(units), request(READ, 0));

        assertFalse(page.hasNext());
        assertNull(page.nextArguments());
        assertEquals(0, fixture.cache.size());
        assertEquals(1, page.items().size());
        assertTrue(page.items().get(0).unitTruncated());
        assertEquals(5000, page.items().get(0).fullLength());
        assertTrue(page.fullCoverage().contains("shortened from 5000")); //$NON-NLS-1$
        assertTrue(page.json().length() <= 3000);

        JsonObject root = JsonParser.parseString(page.json()).getAsJsonObject();
        assertEquals(1, root.getAsJsonPrimitive("total").getAsInt()); //$NON-NLS-1$
        assertTrue(root.getAsJsonPrimitive("totalExact").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, root.getAsJsonPrimitive("returned").getAsInt()); //$NON-NLS-1$
        assertTrue(root.getAsJsonPrimitive("truncated").getAsBoolean()); //$NON-NLS-1$
        JsonObject item = root.getAsJsonArray("items").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertTrue(item.getAsJsonPrimitive("unitTruncated").getAsBoolean()); //$NON-NLS-1$
        assertEquals(5000, item.getAsJsonPrimitive("fullLength").getAsInt()); //$NON-NLS-1$
        String shown = item.getAsJsonPrimitive("value").getAsString(); //$NON-NLS-1$
        assertTrue(shown.length() > 0);
        assertTrue("L".repeat(5000).startsWith(shown)); //$NON-NLS-1$
    }

    @Test
    public void everyPageAdvancesAndARepeatedNextCannotHappen()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        List<ResultWindow.Page> pages = pages(fixture,
            complete(units(7, 20)), request(READ, 2));
        long expected = 0;
        for (ResultWindow.Page page : pages)
        {
            assertEquals(expected, page.position());
            assertTrue(page.items().size() >= 1);
            expected += page.items().size();
            if (page.hasNext())
            {
                long next = nextPosition(page);
                assertTrue("the next page must start after this one", next > page.position()); //$NON-NLS-1$
                assertEquals("the next page starts where this one stopped", //$NON-NLS-1$
                    page.position() + page.items().size(), next);
            }
        }
        assertEquals(7, expected);
    }

    @Test
    public void aFullTraversalHasNoGapsOrRepeats()
    {
        List<ResultUnit> units = new ArrayList<>();
        for (int i = 0; i < 25; i++)
        {
            units.add(ResultUnit.of(String.format("%02d-", i) + "u".repeat(40 + i * 7))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(1200L, 400);
        List<String> collected = new ArrayList<>();
        ResultWindow.Page page = fixture.window.firstPage(complete(units), request(READ, 0));
        while (true)
        {
            for (ResultWindow.PageItem item : page.items())
            {
                assertFalse("this fixture has no oversized unit", item.unitTruncated()); //$NON-NLS-1$
                collected.add(item.value().getAsString());
            }
            if (!page.hasNext())
            {
                break;
            }
            page = fixture.window.continuation(page.resultId(), page.nextArguments(),
                request(READ, 0), null);
        }
        List<String> expected = new ArrayList<>();
        for (ResultUnit unit : units)
        {
            expected.add(unit.text());
        }
        assertEquals(expected, collected);
    }

    @Test
    public void aStructuralUnitLargerThanTheCeilingIsShortenedAndTheSequenceAdvances()
    {
        Map<String, Object> big = new LinkedHashMap<>();
        big.put("payload", "x".repeat(6000)); //$NON-NLS-1$ //$NON-NLS-2$
        ResultUnit structural = ResultUnit.of(big);
        List<ResultUnit> units = new ArrayList<>();
        units.add(structural);
        units.add(ResultUnit.of("after")); //$NON-NLS-1$
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(5000L, 500);
        ResultWindow.Page page = fixture.window.firstPage(complete(units), request(READ, 0));

        assertEquals(1, page.items().size());
        assertTrue(page.items().get(0).unitTruncated());
        assertEquals(structural.json().length(), page.items().get(0).fullLength());
        assertTrue(page.json().length() <= 5000);
        assertTrue(page.fullCoverage()
            .contains("shortened from " + structural.json().length())); //$NON-NLS-1$
        assertTrue(page.hasNext());
        assertEquals(Long.valueOf(1), nextPosition(page));

        JsonObject item = JsonParser.parseString(page.json()).getAsJsonObject()
            .getAsJsonArray("items").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertTrue(item.getAsJsonPrimitive("unitTruncated").getAsBoolean()); //$NON-NLS-1$
        String shown = item.getAsJsonPrimitive("value").getAsString(); //$NON-NLS-1$
        assertTrue("the shown value is a prefix of the unit's serialized form", //$NON-NLS-1$
            structural.json().startsWith(shown));
        assertTrue(shown.length() > 0);

        ResultWindow.Page last = fixture.window.continuation(page.resultId(), page.nextArguments(),
            request(READ, 0), null);
        assertEquals("after", last.items().get(0).unit().text()); //$NON-NLS-1$
        assertFalse(last.hasNext());
    }

    @Test
    public void mandatoryMembersThatAloneExceedTheCeilingAreRefusedByName()
    {
        ResultArgumentProfile profile = ResultArgumentProfile.builder("read").build(); //$NON-NLS-1$
        ResultWindow.Request request = ResultWindow.Request.builder(profile)
            .fixed("warnings", "w".repeat(4000)) //$NON-NLS-1$ //$NON-NLS-2$
            .maxUnitsPerPage(3)
            .build();

        // An empty answer carries only the mandatory members; over the ceiling it refuses rather
        // than sending a page that breaks the ceiling.
        ResultWindowCacheWindow empty = new ResultWindowCacheWindow(3000L, 500);
        try
        {
            empty.window.firstPage(complete(new ArrayList<>()), request);
            fail("an empty page over the ceiling cannot be sent"); //$NON-NLS-1$
        }
        catch (ResultRefusal refusal)
        {
            assertEquals(ResultRefusal.Reason.MANDATORY_MEMBERS_EXCEED_CEILING, refusal.reason());
            assertEquals("mandatory_members_exceed_ceiling", refusal.code()); //$NON-NLS-1$
        }

        // A page that would carry units refuses the same way: there is no room even for a
        // shortened unit.
        ResultWindowCacheWindow paged = new ResultWindowCacheWindow(3000L, 500);
        try
        {
            paged.window.firstPage(complete(units(10, 20)), request);
            fail("a page whose fixed members alone exceed the ceiling cannot be sent"); //$NON-NLS-1$
        }
        catch (ResultRefusal refusal)
        {
            assertEquals(ResultRefusal.Reason.MANDATORY_MEMBERS_EXCEED_CEILING, refusal.reason());
        }
    }

    // ---------------------------------------------------------------- finished and unfinished

    @Test
    public void aFinishedAndAnUnfinishedCollectionTellDifferentFacts()
    {
        ResultWindowCacheWindow finishedFixture = new ResultWindowCacheWindow(100_000L, 10_000);
        ResultWindow.Page finished = finishedFixture.window.firstPage(complete(units(10, 20)),
            request(READ, 3));
        assertEquals(Boolean.TRUE, finished.envelope().get("totalExact"));
        assertEquals("complete", finished.envelope().get("coverage")); //$NON-NLS-1$
        assertEquals(Long.valueOf(10), finished.envelope().get("total"));

        ResultWindowCacheWindow unfinishedFixture = new ResultWindowCacheWindow(100_000L, 10_000);
        ResultWindow.Page unfinished = unfinishedFixture.window.firstPage(
            incomplete(units(10, 20), "the search was cut short; two modules were not visited"), //$NON-NLS-1$
            request(READ, 3));
        assertEquals(Boolean.FALSE, unfinished.envelope().get("totalExact"));
        assertTrue(String.valueOf(unfinished.envelope().get("coverage")).contains("cut short")); //$NON-NLS-1$
        assertEquals(Long.valueOf(10), unfinished.envelope().get("total"));
    }

    @Test
    public void anUnfinishedCollectionPagesOnlyWhatItCollected()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        PinnedResult selection = incomplete(units(10, 20), "stopped at the time limit"); //$NON-NLS-1$
        List<ResultWindow.Page> pages = pages(fixture, selection, request(READ, 3));
        int returned = 0;
        for (ResultWindow.Page page : pages)
        {
            assertEquals(Boolean.FALSE, page.envelope().get("totalExact"));
            assertTrue(String.valueOf(page.envelope().get("coverage")).contains("time limit")); //$NON-NLS-1$ //$NON-NLS-2$
            returned += page.items().size();
        }
        assertEquals(10, returned);
        assertFalse(pages.get(pages.size() - 1).hasNext());
    }

    @Test
    public void anUnfinishedCollectionThatWasShownWholeStillCarriesCoverage()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        ResultWindow.Page page = fixture.window.firstPage(
            incomplete(units(2, 20), "the provider refused one module"), request(READ, 0)); //$NON-NLS-1$
        assertNotNull(page.envelope());
        assertFalse(page.hasNext());
        assertEquals(Boolean.FALSE, page.envelope().get("totalExact"));
        assertEquals("the provider refused one module", page.envelope().get("coverage")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anEmptyResultWithLargeCoveragePagesToTheEnd()
    {
        List<ResultUnit> units = new ArrayList<>();
        for (int i = 0; i < 20; i++)
        {
            units.add(ResultUnit.ofCoverage("coverage " + i + ": " + "c".repeat(60))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        List<ResultWindow.Page> pages = pages(fixture, complete(units), request(READ, 4));
        int seen = 0;
        for (ResultWindow.Page page : pages)
        {
            seen += page.items().size();
        }
        assertEquals(20, seen);
    }

    @Test
    public void anEnvelopeWithNoNextStillObeysItsBudget()
    {
        String fullCoverage = "x".repeat(200); //$NON-NLS-1$
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(10_000L, 300);
        ResultWindow.Request request = ResultWindow.Request.builder(READ)
            .maxCoverageChars(200)
            .build();
        ResultWindow.Page page = fixture.window.firstPage(
            incomplete(units(1, 20), fullCoverage), request);
        assertFalse(page.hasNext());
        assertTrue(envelopeLength(page) <= 300);
        assertEquals(Boolean.TRUE, page.envelope().get("fieldTruncated"));
        assertEquals(fullCoverage, page.fullCoverage());
    }

    @Test
    public void aCoverageFieldLongerThanTheLimitIsTruncatedAndItsFullTextIsASequenceUnit()
    {
        String fullCoverage = "the search was cut short: " + "very long reason ".repeat(40); //$NON-NLS-1$ //$NON-NLS-2$
        List<ResultUnit> units = new ArrayList<>(units(5, 20));
        units.add(ResultUnit.ofCoverage(fullCoverage));
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        ResultWindow.Request request = ResultWindow.Request.builder(READ)
            .maxCoverageChars(50)
            .maxUnitsPerPage(5)
            .build();
        ResultWindow.Page page = fixture.window.firstPage(incomplete(units, fullCoverage), request);
        assertEquals(Boolean.TRUE, page.envelope().get("fieldTruncated"));
        assertTrue(page.coverageTruncated());
        assertEquals(fullCoverage, page.fullCoverage());
        assertEquals(50, String.valueOf(page.envelope().get("coverage")).length());

        List<String> seen = new ArrayList<>();
        while (true)
        {
            for (ResultWindow.PageItem item : page.items())
            {
                seen.add(item.value().getAsString());
            }
            if (!page.hasNext())
            {
                break;
            }
            page = fixture.window.continuation(page.resultId(), page.nextArguments(), request, null);
        }
        assertTrue("the full coverage text is reachable as a unit", //$NON-NLS-1$
            seen.contains(fullCoverage));
    }

    // ---------------------------------------------------------------- the pinned selection

    @Test
    public void aFinishedReadIsServedFromTheCacheWithoutCollectingAgain()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        AtomicInteger collections = new AtomicInteger();
        PinnedResult selection = complete(units(10, 20));
        ResultWindow.Rebuild rebuild = arguments ->
        {
            collections.incrementAndGet();
            return selection;
        };
        ResultWindow.Request request = request(READ, 3);
        ResultWindow.Page page = fixture.window.firstPage(selection, request);
        List<String> collected = new ArrayList<>();
        while (true)
        {
            for (ResultWindow.PageItem item : page.items())
            {
                collected.add(item.unit().text());
            }
            if (!page.hasNext())
            {
                break;
            }
            page = fixture.window.continuation(page.resultId(), page.nextArguments(), request,
                rebuild);
        }
        assertEquals(10, collected.size());
        assertEquals("no page re-collects a live selection", 0, collections.get()); //$NON-NLS-1$
    }

    @Test
    public void mutableArgumentsAndUnitsDoNotChangeThePinnedPages()
    {
        List<ResultUnit> sourceUnits = new ArrayList<>(units(5, 20));
        Map<String, Object> sourceArguments = new LinkedHashMap<>();
        sourceArguments.put("offset", 10); //$NON-NLS-1$
        PinnedResult selection = complete(sourceUnits, sourceArguments);
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        ResultWindow.Page first = fixture.window.firstPage(selection, request(READ, 2));
        sourceUnits.clear();
        sourceArguments.put("offset", 99); //$NON-NLS-1$

        ResultWindow.Page second = fixture.window.continuation(first.resultId(), first.nextArguments(),
            request(READ, 2), null);
        assertEquals("plain result 2", second.items().get(0).value().getAsString().substring(0, 14)); //$NON-NLS-1$
        assertEquals(10, ((Number)second.nextArguments().get("offset")).intValue()); //$NON-NLS-1$
    }

    @Test
    public void anEvictedFinishedReadIsRebuiltAndComparedThroughItsSnapshot()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        AtomicInteger collections = new AtomicInteger();
        PinnedResult selection = complete(units(10, 20));
        ResultWindow.Request request = request(READ, 3);
        ResultWindow.Page first = fixture.window.firstPage(selection, request);
        UUID id = PinnedResultCache.parse(first.resultId()).id;
        assertTrue(fixture.cache.remove(id));

        ResultWindow.Page rebuilt = fixture.window.continuation(first.resultId(),
            first.nextArguments(), request, arguments ->
            {
                collections.incrementAndGet();
                return selection;
            });
        assertEquals(1, collections.get());
        assertEquals(3, rebuilt.position());
        assertEquals(selection.snapshot(), rebuilt.snapshot());
        assertNotEquals(id, PinnedResultCache.parse(rebuilt.resultId()).id);
    }

    @Test
    public void aRebuiltSelectionThatChangedIsRefused()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        PinnedResult selection = complete(units(10, 20));
        ResultWindow.Request request = request(READ, 3);
        ResultWindow.Page first = fixture.window.firstPage(selection, request);
        UUID id = PinnedResultCache.parse(first.resultId()).id;
        fixture.cache.remove(id);
        try
        {
            fixture.window.continuation(first.resultId(), first.nextArguments(), request,
                arguments -> complete(units(10, 21)));
            fail("a changed collection must not be mixed into old pages"); //$NON-NLS-1$
        }
        catch (ResultRefusal refusal)
        {
            assertEquals(ResultRefusal.Reason.SELECTION_CHANGED, refusal.reason());
            assertEquals("selection_changed", refusal.code()); //$NON-NLS-1$
        }
    }

    @Test
    public void anIncompleteCollectionIsNeverRebuiltAfterEviction()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        AtomicInteger collections = new AtomicInteger();
        PinnedResult selection = incomplete(units(10, 20), "the search was cut short"); //$NON-NLS-1$
        ResultWindow.Request request = request(READ, 3);
        ResultWindow.Page first = fixture.window.firstPage(selection, request);
        UUID id = PinnedResultCache.parse(first.resultId()).id;
        fixture.cache.remove(id);
        try
        {
            fixture.window.continuation(first.resultId(), first.nextArguments(), request,
                arguments ->
                {
                    collections.incrementAndGet();
                    return selection;
                });
            fail("an unfinished collection must not be collected again"); //$NON-NLS-1$
        }
        catch (ResultRefusal refusal)
        {
            assertEquals(ResultRefusal.Reason.INCOMPLETE_SELECTION_NO_LONGER_STORED,
                refusal.reason());
            assertEquals(0, collections.get());
        }
    }

    @Test
    public void aChangingCallIsNeverReplayedAfterEviction()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        AtomicInteger executions = new AtomicInteger();
        PinnedResult selection = complete(units(10, 20));
        ResultWindow.Request request = request(CHANGING, 3);
        ResultWindow.Page first = fixture.window.firstPage(selection, request);
        UUID id = PinnedResultCache.parse(first.resultId()).id;
        fixture.cache.remove(id);
        try
        {
            fixture.window.continuation(first.resultId(), first.nextArguments(), request,
                arguments ->
                {
                    executions.incrementAndGet();
                    return selection;
                });
            fail("a changing call must not run again"); //$NON-NLS-1$
        }
        catch (ResultRefusal refusal)
        {
            assertEquals(ResultRefusal.Reason.CHANGING_CALL_NOT_REPLAYED, refusal.reason());
            assertEquals(0, executions.get());
        }
    }

    @Test
    public void aReadProfileIsNotJudgedByAnApplyArgument()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        AtomicInteger collections = new AtomicInteger();
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("apply", Boolean.TRUE); //$NON-NLS-1$
        PinnedResult selection = complete(units(10, 20), arguments);
        ResultWindow.Request request = request(READ, 3);
        ResultWindow.Page first = fixture.window.firstPage(selection, request);
        UUID id = PinnedResultCache.parse(first.resultId()).id;
        fixture.cache.remove(id);

        ResultWindow.Page page = fixture.window.continuation(first.resultId(),
            first.nextArguments(), request, received ->
            {
                collections.incrementAndGet();
                return selection;
            });
        assertEquals("apply is an argument, not a declaration of a changing call", //$NON-NLS-1$
            1, collections.get());
        assertEquals(3, page.position());
    }

    // ---------------------------------------------------------------- short form survives eviction

    @Test
    public void onlyTheOptionalArgumentIsDroppedAndTheShortFormRefusesAfterEviction()
    {
        ResultArgumentProfile profile = ResultArgumentProfile.builder("read") //$NON-NLS-1$
            .required("modulePath") //$NON-NLS-1$
            .build();
        long ceiling = 100_000L;
        int budget = 1000;
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(ceiling, budget);
        ResultWindow.Request request = ResultWindow.Request.builder(profile)
            .maxUnitsPerPage(5)
            .build();
        Map<String, Object> baseArguments = new LinkedHashMap<>();
        baseArguments.put("modulePath", "CommonModule.Module"); //$NON-NLS-1$ //$NON-NLS-2$
        baseArguments.put("note", ""); //$NON-NLS-1$ //$NON-NLS-2$
        int base = envelopeLength(fixture.window.firstPage(complete(units(10, 20), baseArguments),
            request));
        assertTrue(base < budget);
        int filler = budget - base;

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("modulePath", "CommonModule.Module"); //$NON-NLS-1$ //$NON-NLS-2$
        arguments.put("note", "n".repeat(filler + 1)); //$NON-NLS-1$ //$NON-NLS-2$
        ResultWindow.Page page = fixture.window.firstPage(complete(units(10, 20), arguments),
            request);
        assertEquals(List.of("note"), page.envelope().get("argsElided")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommonModule.Module", page.nextArguments().get("modulePath")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(page.nextArguments().containsKey("note")); //$NON-NLS-1$

        UUID id = PinnedResultCache.parse(page.resultId()).id;
        fixture.cache.remove(id);
        AtomicInteger collections = new AtomicInteger();
        try
        {
            fixture.window.continuation(page.resultId(), page.nextArguments(), request, rebuilt ->
            {
                collections.incrementAndGet();
                return complete(units(10, 20), arguments);
            });
            fail("a short-form continuation must not be rebuilt"); //$NON-NLS-1$
        }
        catch (ResultRefusal refusal)
        {
            assertEquals(ResultRefusal.Reason.SHORT_FORM_NO_LONGER_STORED, refusal.reason());
            assertEquals(0, collections.get());
        }
    }

    // ---------------------------------------------------------------- canonical arguments

    @Test
    public void anEnumArgumentIsCanonicalizedBeforeBothSnapshots()
    {
        ResultArgumentProfile profile = ResultArgumentProfile.builder("compare") //$NON-NLS-1$
            .enumerable("intent", "REPORT") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("intent", " REPORT "); //$NON-NLS-1$ //$NON-NLS-2$
        List<ResultUnit> units = new ArrayList<>();
        for (int i = 0; i < 10; i++)
        {
            units.add(ResultUnit.of("unit " + i)); //$NON-NLS-1$
        }
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        ResultWindow.Request request = ResultWindow.Request.builder(profile)
            .maxUnitsPerPage(3)
            .build();
        ResultWindow.Page page = fixture.window.firstPage(
            PinnedResult.builder("compare").units(units).arguments(arguments).build(), request); //$NON-NLS-1$
        assertEquals("the continuation carries the canonical spelling", "REPORT", //$NON-NLS-1$ //$NON-NLS-2$
            page.nextArguments().get("intent")); //$NON-NLS-1$

        UUID id = PinnedResultCache.parse(page.resultId()).id;
        assertTrue(fixture.cache.remove(id));
        AtomicInteger collections = new AtomicInteger();
        ResultWindow.Page rebuilt = fixture.window.continuation(page.resultId(),
            page.nextArguments(), request, received ->
            {
                collections.incrementAndGet();
                // The rebuild keeps the operation's own arguments; the control keys a caller
                // sends back are not part of the call the selection was shaped by.
                Map<String, Object> callArguments = new LinkedHashMap<>();
                callArguments.put("intent", received.get("intent")); //$NON-NLS-1$ //$NON-NLS-2$
                return PinnedResult.builder("compare").units(units).arguments(callArguments) //$NON-NLS-1$
                    .build();
            });
        assertEquals("unchanged data rebuilds despite the padded original spelling", //$NON-NLS-1$
            1, collections.get());
        assertEquals(3, rebuilt.position());
    }

    @Test
    public void aShortFormTokenWithItsFlagClearedIsRefusedAfterEviction()
    {
        ResultArgumentProfile profile = ResultArgumentProfile.builder("read").build(); //$NON-NLS-1$
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("note", "n".repeat(1200)); //$NON-NLS-1$ //$NON-NLS-2$
        List<ResultUnit> units = new ArrayList<>();
        for (int i = 0; i < 10; i++)
        {
            units.add(ResultUnit.of("unit " + i)); //$NON-NLS-1$
        }
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 500);
        ResultWindow.Request request = ResultWindow.Request.builder(profile)
            .maxUnitsPerPage(3)
            .build();
        ResultWindow.Page page = fixture.window.firstPage(
            PinnedResult.builder("read").units(units).arguments(arguments).build(), request); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, page.envelope().get("shortForm")); //$NON-NLS-1$
        UUID id = PinnedResultCache.parse(page.resultId()).id;
        assertTrue(fixture.cache.remove(id));

        String token = page.resultId();
        String stripped = token.replace(".s.", ".-."); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a token with a cleared flag was never issued", PinnedResultCache.parse(stripped)); //$NON-NLS-1$
        AtomicInteger collections = new AtomicInteger();
        try
        {
            fixture.window.continuation(stripped, page.nextArguments(), request, received ->
            {
                collections.incrementAndGet();
                return PinnedResult.builder("read").units(units).arguments(arguments).build(); //$NON-NLS-1$
            });
            fail("a forged full-form token cannot be rebuilt"); //$NON-NLS-1$
        }
        catch (ResultRefusal refusal)
        {
            assertEquals(ResultRefusal.Reason.UNKNOWN_RESULT_ID, refusal.reason());
            assertEquals(0, collections.get());
        }
    }

    // ---------------------------------------------------------------- serialized measurement

    @Test
    public void escapedAndUnicodeContentIsMeasuredAsSerialized()
    {
        String warning = "quote \" backslash \\ less < and > and & and Привет 😀"; //$NON-NLS-1$
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(3000L, 500);
        ResultWindow.Request request = ResultWindow.Request.builder(READ)
            .fixed("warnings", List.of(warning)) //$NON-NLS-1$
            .maxUnitsPerPage(3)
            .build();
        ResultWindow.Page page = fixture.window.firstPage(complete(units(10, 20)), request);
        assertTrue(page.json().length() <= 3000);
        JsonObject root = JsonParser.parseString(page.json()).getAsJsonObject();
        assertEquals(warning, root.getAsJsonArray("warnings").get(0).getAsString()); //$NON-NLS-1$
        assertTrue(page.hasNext());
    }

    @Test
    public void theLongestSerializedPrefixCountsEscapingAndNeverSplitsASurrogatePair()
    {
        assertEquals(4, ResultWindow.longestSerializedPrefix("\"\"\"\"", 10)); //$NON-NLS-1$
        assertEquals(2, ResultWindow.longestSerializedPrefix("😀😀😀", 5)); //$NON-NLS-1$
        assertEquals(0, ResultWindow.codePointBoundary("😀a", 1)); //$NON-NLS-1$
        assertEquals(2, ResultWindow.codePointBoundary("😀a", 2)); //$NON-NLS-1$
    }

    @Test
    public void aFixedMemberIsCarriedOnEveryPage()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        ResultWindow.Request request = ResultWindow.Request.builder(READ)
            .fixed("warnings", List.of("kept warning")) //$NON-NLS-1$ //$NON-NLS-2$
            .maxUnitsPerPage(2)
            .build();
        ResultWindow.Page page = fixture.window.firstPage(complete(units(5, 20)), request);
        while (true)
        {
            JsonObject root = JsonParser.parseString(page.json()).getAsJsonObject();
            assertEquals("kept warning", root.getAsJsonArray("warnings").get(0).getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            if (!page.hasNext())
            {
                break;
            }
            page = fixture.window.continuation(page.resultId(), page.nextArguments(), request, null);
        }
    }

    @Test
    public void theCeilingCountsCharactersNotUtf8Bytes()
    {
        String text = "П".repeat(100); //$NON-NLS-1$
        PinnedResultCache cache = new PinnedResultCache(() -> 0L, 60_000L, 1_000_000L);
        ResultWindow huge = new ResultWindow(cache, 100_000, 10_000);
        ResultWindow.Page whole = huge.firstPage(complete(List.of(ResultUnit.of(text))),
            request(READ, 0));
        int exact = whole.json().length();
        assertTrue("the Cyrillic text encodes to more bytes than the response has characters", //$NON-NLS-1$
            text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > exact);

        PinnedResultCache exactCache = new PinnedResultCache(() -> 0L, 60_000L, 1_000_000L);
        ResultWindow exactly = new ResultWindow(exactCache, exact, exact);
        ResultWindow.Page page = exactly.firstPage(complete(List.of(ResultUnit.of(text))),
            request(READ, 0));
        assertNull("the ceiling is in characters, so the page is not shortened", //$NON-NLS-1$
            page.envelope());
        assertEquals(exact, page.json().length());
    }

    @Test
    public void coverageOutlineAndBlocksAreUnitsOfTheSameSequence()
    {
        List<ResultUnit> units = List.of(
            ResultUnit.of("result-0"), //$NON-NLS-1$
            ResultUnit.ofCoverage("coverage-1"), //$NON-NLS-1$
            ResultUnit.ofBlock(Map.of("outline", "block-2")), //$NON-NLS-1$ //$NON-NLS-2$
            ResultUnit.of("result-3")); //$NON-NLS-1$
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        List<ResultWindow.Page> pages = pages(fixture, complete(units), request(READ, 2));
        List<String> collected = new ArrayList<>();
        for (ResultWindow.Page page : pages)
        {
            for (ResultWindow.PageItem item : page.items())
            {
                collected.add(item.value().toString());
            }
        }
        assertEquals(4, collected.size());
        assertTrue(collected.get(0).contains("result-0")); //$NON-NLS-1$
        assertTrue(collected.get(1).contains("coverage-1")); //$NON-NLS-1$
        assertTrue(collected.get(2).contains("block-2")); //$NON-NLS-1$
        assertTrue(collected.get(3).contains("result-3")); //$NON-NLS-1$
    }

    // ---------------------------------------------------------------- refusals

    @Test
    public void anUnknownTokenIsRefused()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        try
        {
            fixture.window.continuation("not-a-result-id", Map.of("position", 0), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                request(READ, 0), null);
            fail("a token nobody issued cannot be served"); //$NON-NLS-1$
        }
        catch (ResultRefusal refusal)
        {
            assertEquals(ResultRefusal.Reason.UNKNOWN_RESULT_ID, refusal.reason());
        }
    }

    @Test
    public void aPositionPastTheEndOrBeforeTheStartIsRefused()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        ResultWindow.Request request = request(READ, 3);
        ResultWindow.Page page = fixture.window.firstPage(complete(units(10, 20)), request);
        Map<String, Object> next = page.nextArguments();
        next.put("position", 100); //$NON-NLS-1$
        try
        {
            fixture.window.continuation(page.resultId(), next, request, null);
            fail("a position past the selection cannot be served"); //$NON-NLS-1$
        }
        catch (ResultRefusal refusal)
        {
            assertEquals(ResultRefusal.Reason.POSITION_NOT_ADVANCING, refusal.reason());
        }
        Map<String, Object> negative = page.nextArguments();
        negative.put("position", -1); //$NON-NLS-1$
        try
        {
            fixture.window.continuation(page.resultId(), negative, request, null);
            fail("a negative position cannot be served"); //$NON-NLS-1$
        }
        catch (ResultRefusal refusal)
        {
            assertEquals(ResultRefusal.Reason.POSITION_NOT_ADVANCING, refusal.reason());
        }
    }

    @Test
    public void aSnapshotMismatchOnALiveSelectionIsRefused()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        ResultWindow.Request request = request(READ, 3);
        ResultWindow.Page page = fixture.window.firstPage(complete(units(10, 20)), request);
        Map<String, Object> next = page.nextArguments();
        next.put("snapshot", "not the snapshot"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            fixture.window.continuation(page.resultId(), next, request, null);
            fail("pages of one selection cannot be requested with another snapshot"); //$NON-NLS-1$
        }
        catch (ResultRefusal refusal)
        {
            assertEquals(ResultRefusal.Reason.SELECTION_CHANGED, refusal.reason());
        }
    }

    @Test
    public void aPageAtTheEndIsEmptyAndHasNoNext()
    {
        ResultWindowCacheWindow fixture = new ResultWindowCacheWindow(100_000L, 10_000);
        ResultWindow.Request request = request(READ, 3);
        PinnedResult selection = complete(units(4, 20));
        ResultWindow.Page first = fixture.window.firstPage(selection, request);
        Map<String, Object> next = first.nextArguments();
        next.put("position", 4); //$NON-NLS-1$
        ResultWindow.Page empty = fixture.window.continuation(first.resultId(), next, request, null);
        assertTrue(empty.items().isEmpty());
        assertFalse(empty.hasNext());
    }

    // ---------------------------------------------------------------- helpers

    private static List<ResultWindow.Page> pages(ResultWindowCacheWindow fixture,
        PinnedResult selection, ResultWindow.Request request)
    {
        List<ResultWindow.Page> collected = new ArrayList<>();
        ResultWindow.Page page = fixture.window.firstPage(selection, request);
        collected.add(page);
        while (page.hasNext())
        {
            page = fixture.window.continuation(page.resultId(), page.nextArguments(), request, null);
            collected.add(page);
        }
        return collected;
    }

    private static ResultWindowCacheWindow windowWithHugeCeiling()
    {
        return new ResultWindowCacheWindow(1_000_000L, 100_000);
    }

    private static ResultWindow.Request request(ResultArgumentProfile profile, int pageLimit)
    {
        return ResultWindow.Request.builder(profile).maxUnitsPerPage(pageLimit).build();
    }

    private static PinnedResult complete(List<ResultUnit> units)
    {
        return complete(units, new LinkedHashMap<>());
    }

    private static PinnedResult complete(List<ResultUnit> units, Map<String, Object> arguments)
    {
        return PinnedResult.builder("read").units(units).arguments(arguments).build(); //$NON-NLS-1$
    }

    private static PinnedResult incomplete(List<ResultUnit> units, String coverage)
    {
        return PinnedResult.builder("read").units(units).complete(false).coverage(coverage) //$NON-NLS-1$
            .build();
    }

    private static List<ResultUnit> units(int count, int size)
    {
        List<ResultUnit> units = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            units.add(ResultUnit.of("plain result " + i + " " + "u".repeat(size))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return units;
    }

    private static int envelopeLength(ResultWindow.Page page)
    {
        Map<String, Object> envelope = page.envelope();
        return envelope == null ? 0 : GsonHolder.toJson(envelope).length();
    }

    private static Long nextPosition(ResultWindow.Page page)
    {
        Object position = page.nextArguments().get("position"); //$NON-NLS-1$
        return Long.valueOf(((Number)position).longValue());
    }

    /** A cache and a window over it, kept together so a test can evict a record by hand. */
    private static final class ResultWindowCacheWindow
    {
        private final PinnedResultCache cache;

        private final ResultWindow window;

        private ResultWindowCacheWindow(long ceiling, long budget)
        {
            this.cache = new PinnedResultCache(() -> 0L, 60_000L, 100_000_000L);
            this.window = new ResultWindow(cache, ceiling, budget);
        }
    }
}
