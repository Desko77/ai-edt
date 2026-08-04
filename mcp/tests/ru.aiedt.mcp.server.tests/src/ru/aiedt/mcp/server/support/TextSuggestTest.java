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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Covers the diagnostics an agent reads when a call is wrong.
 * <p>
 * The value of these messages is that they are self-correcting: they name the bad argument, offer
 * the nearest valid one, and cap the list so a hundred candidates do not bury the answer. A message
 * that loses the suggestion, or that returns {@code null} where text was expected, costs the agent
 * a round-trip it cannot diagnose - which is why the tests pin the parts that carry information
 * rather than the sentences around them.
 * </p>
 */
public class TextSuggestTest
{
    private static final List<String> MODES = Arrays.asList("summary", "unified", "methods"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    // -- closest --

    @Test
    public void aTypoIsMatchedToItsIntendedCandidate()
    {
        assertEquals("summary", TextSuggest.closest("sumary", MODES)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("methods", TextSuggest.closest("Methods", MODES)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void somethingUnrelatedGetsNoSuggestion()
    {
        // A suggestion that is merely the least-bad of the candidates is worse than none: the agent
        // acts on it.
        assertNull(TextSuggest.closest("qwertyuiop", MODES)); //$NON-NLS-1$
    }

    @Test
    public void closestToleratesNothingToSuggestFrom()
    {
        assertNull(TextSuggest.closest("mode", null)); //$NON-NLS-1$
        assertNull(TextSuggest.closest("mode", Collections.emptyList())); //$NON-NLS-1$
        assertNull(TextSuggest.closest(null, MODES));
    }

    // -- levenshtein --

    @Test
    public void editDistanceCountsSingleCharacterEdits()
    {
        assertEquals(0, TextSuggest.levenshtein("form", "form")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, TextSuggest.levenshtein("form", "forms")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, TextSuggest.levenshtein("forms", "form")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, TextSuggest.levenshtein("form", "fort")); //$NON-NLS-1$ //$NON-NLS-2$
        // Two neighbours swapped is two substitutions here - this metric has no transposition.
        assertEquals(2, TextSuggest.levenshtein("form", "from")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void editDistanceAgainstAnEmptyStringIsTheOtherLength()
    {
        assertEquals(4, TextSuggest.levenshtein("", "form")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(4, TextSuggest.levenshtein("form", "")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, TextSuggest.levenshtein("", "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -- invalidValue --

    @Test
    public void anInvalidValueMessageNamesTheParameterTheValueAndTheAlternatives()
    {
        String message = TextSuggest.invalidValue("mode", "sumary", MODES); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(message, message.contains("mode")); //$NON-NLS-1$
        assertTrue(message, message.contains("sumary")); //$NON-NLS-1$
        assertTrue("the near miss is the whole point", message.contains("summary")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(message, message.contains("unified")); //$NON-NLS-1$
    }

    @Test
    public void aLongListOfValidValuesIsCapped()
    {
        List<String> many = new ArrayList<>();
        for (int i = 1; i <= 45; i++)
        {
            many.add("value" + i); //$NON-NLS-1$
        }

        String message = TextSuggest.invalidValue("kind", "zzz", many); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("an uncapped list buries the answer: " + message, message.contains("more)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the head has to survive", message.contains("value1")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the tail must be elided", message.contains("value45")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anInvalidValueMessageSurvivesHavingNoCandidates()
    {
        String message = TextSuggest.invalidValue("mode", "zzz", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(message);
        assertTrue(message, message.contains("zzz")); //$NON-NLS-1$
    }

    // -- missingParam --

    @Test
    public void aMissingParameterMessageCarriesTheExpectedShape()
    {
        String message = TextSuggest.missingParam("objectName", "'Catalog.Products'"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(message, message.contains("objectName")); //$NON-NLS-1$
        assertTrue("without an example the agent guesses the format", //$NON-NLS-1$
            message.contains("Catalog.Products")); //$NON-NLS-1$
    }

    @Test
    public void aMissingParameterMessageWorksWithoutAnExample()
    {
        String message = TextSuggest.missingParam("projectName", null); //$NON-NLS-1$
        assertTrue(message, message.contains("projectName")); //$NON-NLS-1$
        assertFalse("no dangling example label", message.contains("Example:")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -- propertyNotFound --

    @Test
    public void anAbsentPropertyMessageNamesTheTypeAndSuggestsTheNearestSetter()
    {
        String message = TextSuggest.propertyNotFound("synonim", "Catalog", //$NON-NLS-1$ //$NON-NLS-2$
            Arrays.asList("name", "synonym", "comment")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(message, message.contains("synonim")); //$NON-NLS-1$
        assertTrue(message, message.contains("Catalog")); //$NON-NLS-1$
        assertTrue("the correction is what saves the round-trip", message.contains("synonym")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -- safeMessage --

    @Test
    public void anExceptionWithoutAMessageStillProducesText()
    {
        // The failure this guards: "Error: null" reaching an agent, which describes nothing.
        String message = TextSuggest.safeMessage(new NullPointerException());
        assertNotNull(message);
        assertFalse(message.isBlank());
        assertNotEquals("the literal null must never reach an agent", "null", message); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(message, message.contains("NullPointerException")); //$NON-NLS-1$
    }

    @Test
    public void anExceptionMessageIsUsedWhenThereIsOne()
    {
        assertEquals("project is locked", //$NON-NLS-1$
            TextSuggest.safeMessage(new IllegalStateException("project is locked"))); //$NON-NLS-1$
    }

    @Test
    public void aCauseIsConsultedWhenTheOuterExceptionSaysNothing()
    {
        // The outer exception carries no message of its own, so the cause has to supply one - and it
        // is named, because "bad fqn" alone does not say what kind of failure it was.
        String message = TextSuggest.safeMessage(
            new RuntimeException((String)null, new IllegalArgumentException("bad fqn"))); //$NON-NLS-1$
        assertTrue(message, message.contains("bad fqn")); //$NON-NLS-1$
        assertTrue(message, message.contains("IllegalArgumentException")); //$NON-NLS-1$
    }

    @Test
    public void aNullThrowableStillProducesText()
    {
        String message = TextSuggest.safeMessage(null);
        assertNotNull(message);
        assertFalse(message.isBlank());
    }
}
