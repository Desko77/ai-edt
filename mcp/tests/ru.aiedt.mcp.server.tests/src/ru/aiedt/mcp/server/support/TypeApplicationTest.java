/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

import org.junit.Test;

/**
 * Covers the shape of the {@code typeApplication} tag and the verdict that goes with it.
 * <p>
 * Six operations create something that carries a type, and every one of them answered
 * {@code success:true} after leaving the element typeless - a shape the platform rejects
 * on the next database update. The caller had the evidence available in a nested field
 * and no reason to look for it. These pin the rule now that there is only one of it.
 * </p>
 */
public class TypeApplicationTest
{
    @Test
    public void anAppliedTypeIsNotAFailure()
    {
        assertFalse(TypeApplication.failed(true, null));
        assertFalse(TypeApplication.failed(true, new ArrayList<String>()));
    }

    @Test
    public void aTypeThatWasAskedForAndDidNotLandIsAFailure()
    {
        // The tag is built only on the creation path - an idempotent skip reports the
        // existing type separately - so this always means "created, and not typed".
        assertTrue(TypeApplication.failed(false, null));
    }

    @Test
    public void aCompositeThatOnlyHalfLandedIsAFailureToo()
    {
        // The type machinery writes the part it resolved and reports the WRITE as fine,
        // listing the rest as unresolved. Reading only the flag would call that a success
        // while the element carries a type narrower than the one asked for.
        assertTrue(TypeApplication.failed(true, Arrays.asList("CatalogRef.Missing"))); //$NON-NLS-1$
    }

    @Test
    public void thePartialMessageDoesNotClaimTheElementIsTypeless()
    {
        String message = TypeApplication.failureMessage("attribute 'Плательщик'", //$NON-NLS-1$
            "String,CatalogRef.Missing", null, false, true); //$NON-NLS-1$

        assertTrue(message.contains("only part of type")); //$NON-NLS-1$
        assertFalse(message.contains("carries no type")); //$NON-NLS-1$
        assertFalse(message.contains("typeless")); //$NON-NLS-1$
    }

    @Test
    public void theTagLeadsWithWhatWasAskedAndWhetherItLanded()
    {
        Map<String, Object> tag = TypeApplication.tag("ДеревоЗначений", false, //$NON-NLS-1$
            new ArrayList<String>(), Arrays.asList("ДеревоЗначений"), "could not resolve"); //$NON-NLS-1$ //$NON-NLS-2$
        Iterator<String> keys = tag.keySet().iterator();
        assertEquals("requested", keys.next()); //$NON-NLS-1$
        assertEquals("applied", keys.next()); //$NON-NLS-1$
        assertEquals("ДеревоЗначений", tag.get("requested")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.FALSE, tag.get("applied")); //$NON-NLS-1$
        assertEquals(Arrays.asList("ДеревоЗначений"), tag.get("unresolved")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("could not resolve", tag.get("error")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void emptyAndAbsentDetailAreLeftOutRatherThanShownAsNothing()
    {
        Map<String, Object> tag = TypeApplication.tag("String", true, //$NON-NLS-1$
            Arrays.asList("String"), new ArrayList<String>(), null); //$NON-NLS-1$
        assertTrue(tag.containsKey("resolved")); //$NON-NLS-1$
        assertFalse(tag.containsKey("unresolved")); //$NON-NLS-1$
        assertFalse(tag.containsKey("error")); //$NON-NLS-1$
        // Nulls must not blow up the tag builder either - callers pass raw refs.
        Map<String, Object> bare = TypeApplication.tag("String", true, null, null, null); //$NON-NLS-1$
        assertEquals(2, bare.size());
    }

    @Test
    public void theMessageNamesWhatWasLeftBehindAndWhatToDoAboutIt()
    {
        String message = TypeApplication.failureMessage("attribute 'Сумма'", //$NON-NLS-1$
            "Stirng", "Some types could not be resolved: Stirng", false, false); //$NON-NLS-1$ //$NON-NLS-2$
        // The caller has to be able to find the thing and undo it, so it is named.
        assertTrue(message.contains("attribute 'Сумма'")); //$NON-NLS-1$
        assertTrue(message.contains("Stirng")); //$NON-NLS-1$
        assertTrue(message.contains("Some types could not be resolved")); //$NON-NLS-1$
        // And it must say why this is not a cosmetic problem.
        assertTrue(message.contains("database update")); //$NON-NLS-1$
    }

    @Test
    public void aPreviewIsNeverToldToGoAndRemoveSomething()
    {
        // A dry run rolls its transaction back, so nothing was written. Telling the
        // caller to remove what was created would send them after an element that does
        // not exist - a wasted call at best, and against a same-named existing element
        // at worst.
        String message = TypeApplication.failureMessage("attribute 'Сумма'", "Stirng", null, true, false); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(message.contains("was created")); //$NON-NLS-1$
        assertFalse(message.contains("remove")); //$NON-NLS-1$
        assertTrue(message.contains("nothing was written")); //$NON-NLS-1$
        assertTrue(message.contains("attribute 'Сумма'")); //$NON-NLS-1$
    }

    @Test
    public void theMessageSurvivesHavingNoUnderlyingError()
    {
        String message = TypeApplication.failureMessage("form parameter 'Отбор'", //$NON-NLS-1$
            "ValueTree", null, false, false); //$NON-NLS-1$
        assertTrue(message.contains("form parameter 'Отбор'")); //$NON-NLS-1$
        assertFalse(message.contains("null")); //$NON-NLS-1$
    }
}
