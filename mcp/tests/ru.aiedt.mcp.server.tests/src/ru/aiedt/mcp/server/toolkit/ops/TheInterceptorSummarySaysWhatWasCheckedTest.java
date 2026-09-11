/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The answer says which interceptors were held against a delivery and which were not.
 * <p>
 * An interceptor is only as good as the code it wraps, and nothing in the list tells a reader
 * whether that code was looked at. Without a delivery to check against, every interceptor comes
 * back unexamined - and a list of interceptors with no problem beside any of them reads as a list
 * of interceptors with no problems. The count of what drifted has to be in the summary for the
 * same reason: an answer that carries the finding only inside the hundredth entry has told nobody.
 * </p>
 */
public class TheInterceptorSummarySaysWhatWasCheckedTest
{
    private static Map<String, Object> interceptor(String kind, Object targetExists,
        Object controlledMatches, String controlledNote)
    {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", kind);
        if (targetExists != null)
        {
            entry.put("targetExists", targetExists);
        }
        if (controlledMatches != null)
        {
            entry.put("controlledMatches", controlledMatches);
        }
        if (controlledNote != null)
        {
            entry.put("controlledNote", controlledNote);
        }
        return entry;
    }

    private static Map<String, Object> summaryOf(String baseProjectName,
        List<Map<String, Object>> hits)
    {
        Map<String, Object> body = new LinkedHashMap<>();
        ListInterceptorsTool.summarise(body, baseProjectName, hits);
        return body;
    }

    @Test
    public void withoutADeliveryTheAnswerSaysNothingWasChecked()
    {
        Map<String, Object> body = summaryOf(null,
            Collections.singletonList(interceptor("before", null, null, null)));

        assertEquals("nothing was held against a delivery, and the answer has to say so",
            Boolean.FALSE, body.get("validated"));
    }

    @Test
    public void theUncheckedAnswerNamesWhatTurnsTheCheckOn()
    {
        Map<String, Object> body = summaryOf(null,
            Collections.singletonList(interceptor("changeAndValidate", null, null, null)));

        Object note = body.get("validationNote");
        assertTrue("a reader who is told nothing was checked needs to be told how to check: "
            + note, note != null && String.valueOf(note).contains("baseProjectName"));
    }

    @Test
    public void aDeliveryMakesTheAnswerSayItWasChecked()
    {
        Map<String, Object> body = summaryOf("Base",
            Collections.singletonList(interceptor("before", Boolean.TRUE, null, null)));

        assertEquals(Boolean.TRUE, body.get("validated"));
        assertEquals("Base", body.get("baseProject"));
    }

    @Test
    public void aTargetTheDeliveryLacksIsCounted()
    {
        Map<String, Object> body = summaryOf("Base", Arrays.asList(
            interceptor("before", Boolean.FALSE, null, null),
            interceptor("after", Boolean.TRUE, null, null)));

        assertEquals(Integer.valueOf(1), body.get("unresolvedTargets"));
    }

    @Test
    public void aControlledFragmentThatDriftedIsCounted()
    {
        Map<String, Object> body = summaryOf("Base", Arrays.asList(
            interceptor("changeAndValidate", Boolean.TRUE, Boolean.FALSE, null),
            interceptor("changeAndValidate", Boolean.TRUE, Boolean.TRUE, null),
            interceptor("before", Boolean.TRUE, null, null)));

        assertEquals("the one that no longer matches the delivery belongs in the summary",
            Integer.valueOf(1), body.get("driftedControlled"));
    }

    @Test
    public void aControlledFragmentNobodyCouldCompareIsCountedApart()
    {
        Map<String, Object> body = summaryOf("Base", Arrays.asList(
            interceptor("changeAndValidate", Boolean.TRUE, null, "could not be read on both sides"),
            interceptor("changeAndValidate", Boolean.TRUE, Boolean.FALSE, null)));

        assertEquals("a fragment that could not be compared is not a fragment that matched",
            Integer.valueOf(1), body.get("controlledUnchecked"));
        assertEquals(Integer.valueOf(1), body.get("driftedControlled"));
    }

    @Test
    public void aControlledFragmentNobodyReachedIsCountedUncheckedToo()
    {
        // What the base check leaves behind when the delivery's module is missing or will not be
        // read: a target that does not exist, and not one word about the fragment. Counting that
        // as neither drifted nor unchecked is how a zero comes to mean "nothing was compared".
        Map<String, Object> body = summaryOf("Base", Collections.singletonList(
            interceptor("changeAndValidate", Boolean.FALSE, null, null)));

        assertEquals("no answer about a controlled fragment is not the fragment matching",
            Integer.valueOf(1), body.get("controlledUnchecked"));
    }

    @Test
    public void anInterceptorThatCarriesNoFragmentIsNotCountedUnchecked()
    {
        // Only changeAndValidate carries a copy of the base method. The others have no fragment to
        // compare, so they are not something that went unchecked.
        Map<String, Object> body = summaryOf("Base", Arrays.asList(
            interceptor("before", Boolean.FALSE, null, null),
            interceptor("after", Boolean.TRUE, null, null),
            interceptor("around", Boolean.TRUE, null, null)));

        assertEquals(Integer.valueOf(0), body.get("controlledUnchecked"));
    }

    @Test
    public void nothingDriftedIsStillAnAnswer()
    {
        Map<String, Object> body = summaryOf("Base", Collections.singletonList(
            interceptor("changeAndValidate", Boolean.TRUE, Boolean.TRUE, null)));

        assertEquals("zero is said out loud, so that absent and none are not the same word",
            Integer.valueOf(0), body.get("driftedControlled"));
        assertEquals(Integer.valueOf(0), body.get("controlledUnchecked"));
    }

    @Test
    public void anEmptyListCountsNothingAndStillReportsTheCheck()
    {
        Map<String, Object> body = summaryOf("Base", new ArrayList<Map<String, Object>>());

        assertEquals(Boolean.TRUE, body.get("validated"));
        assertEquals(Integer.valueOf(0), body.get("unresolvedTargets"));
        assertEquals(Integer.valueOf(0), body.get("driftedControlled"));
    }

    @Test
    public void theUncheckedAnswerCountsNothing()
    {
        Map<String, Object> body = summaryOf(null, Collections.singletonList(
            interceptor("changeAndValidate", Boolean.FALSE, Boolean.FALSE, null)));

        assertFalse("counting what was never checked would be a number with no meaning",
            body.containsKey("unresolvedTargets"));
        assertFalse(body.containsKey("driftedControlled"));
    }
}
