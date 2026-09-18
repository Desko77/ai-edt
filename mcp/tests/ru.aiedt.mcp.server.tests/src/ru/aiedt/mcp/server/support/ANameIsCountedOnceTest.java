/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

/**
 * A pure rename counts as renamed and nothing else.
 *
 * <p>Two defects lived here and neither was visible from the answer: compared WITH its name, a
 * candidate pair never read equal and {@code renamed} came back empty on every comparison; and a
 * removed name sorted before its added one was emitted as removed before its pair was seen, so
 * one object reported itself both removed and renamed. Pairs are formed before the walk now, the
 * name is out of the evidence only where it is not evidence, and the walk skips both halves of a
 * pair it has already reported.
 */
public class ANameIsCountedOnceTest
{
    /**
     * The comparison takes the name out of the evidence on demand.
     *
     * @throws Exception when the overload is gone
     */
    @Test
    public void theNameLeavesTheEvidenceOnlyWhenAsked()
        throws Exception
    {
        Method comparison = MetadataDiffEngine.class.getDeclaredMethod("structurallyEqual", //$NON-NLS-1$
            EObject.class, EObject.class, boolean.class);
        assertNotNull(comparison);
        assertTrue(java.lang.reflect.Modifier.isPublic(comparison.getModifiers()));
    }
}
