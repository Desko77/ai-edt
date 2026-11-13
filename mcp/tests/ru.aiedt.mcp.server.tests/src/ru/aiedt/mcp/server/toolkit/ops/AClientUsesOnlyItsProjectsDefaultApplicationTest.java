/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import ru.aiedt.mcp.server.support.ClientApplicationSelection;

/** Client-only selection, with the application's owning project already fixed by the caller. */
public class AClientUsesOnlyItsProjectsDefaultApplicationTest
{
    @Test public void soleApplicationIsSelected() {
        assertEquals("one", ClientApplicationSelection.select(null, null, List.of("one")));
    }
    @Test public void defaultWinsAmongSeveral() {
        assertEquals("two", ClientApplicationSelection.select(null, "two", List.of("one", "two")));
    }
    @Test public void explicitIdOverridesDefault() {
        assertEquals("one", ClientApplicationSelection.select("one", "two", List.of("one", "two")));
    }
    @Test public void explicitIdDoesNotFallBack() {
        refuse("missing", "one", List.of("one"), "Explicit applicationId");
    }
    @Test public void staleDefaultDoesNotFallBackToSoleApplication() {
        refuse(null, "gone", List.of("one"), "default application");
    }
    @Test public void emptyProjectDoesNotSelectAnotherProject() {
        refuse(null, null, List.of(), "No registered application");
    }
    @Test public void severalApplicationsWithoutDefaultRefuse() {
        refuse(null, null, List.of("one", "two"), "Several registered applications");
    }
    private static void refuse(String explicit, String preferred, List<String> ids, String message) {
        try {
            ClientApplicationSelection.select(explicit, preferred, ids);
            fail("Selection must refuse rather than guess");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }
}
