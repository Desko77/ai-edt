/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/**
 * Covers the guard that keeps a stock-command button off a form that cannot render one.
 * <p>
 * The failure it exists to prevent leaves no trace: the platform accepts the button, writes it into
 * the form, and then never draws it. Nothing errors, so the only way anyone learns is by opening the
 * form and not finding the button. The registry turns that into a refusal at the point of the edit.
 * </p>
 */
public class StandardCommandRegistryTest
{
    @Test
    public void aStockCommandFqnIsBuiltInThePlatformsShape()
    {
        assertEquals("Form.StandardCommand.Post", //$NON-NLS-1$
            StandardCommandRegistry.buildStandardCommandFqn("Post")); //$NON-NLS-1$
    }

    @Test
    public void aDocumentFormCanCarryAStockCommand()
    {
        assertNull("a document form is exactly what stock commands are for", //$NON-NLS-1$
            StandardCommandRegistry.checkOwnerKindCompatibility("Document", "Post")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aKnownIncompatibleOwnerIsRefusedWithAnExplanation()
    {
        String refusal = StandardCommandRegistry.checkOwnerKindCompatibility("CommonModule", "Post"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull("this is the silent-button case; it has to be refused", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("CommonModule")); //$NON-NLS-1$
        assertTrue("the refusal has to point at the way that does work", //$NON-NLS-1$
            refusal.contains("addCommandHandler")); //$NON-NLS-1$
    }

    @Test
    public void aCommonFormHasNoOwnerAndIsRefused()
    {
        String refusal = StandardCommandRegistry.checkOwnerKindCompatibility(null, "Post"); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue("the refusal has to name the alternative", //$NON-NLS-1$
            refusal.contains("addCommandHandler")); //$NON-NLS-1$
    }

    @Test
    public void anOwnerTheRegistryHasNoOpinionOnIsPermitted()
    {
        // Deliberate: an unknown owner type is far more likely to be a type added by a newer
        // platform than a mistake, and refusing it would block work the platform allows.
        assertNull(StandardCommandRegistry.checkOwnerKindCompatibility("SomeFutureType", "Post")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theAutoIconSetIsQueryableAndNullSafe()
    {
        assertFalse(StandardCommandRegistry.hasAutoIcon(null));
        assertFalse(StandardCommandRegistry.hasAutoIcon("NotACommand")); //$NON-NLS-1$
    }

    @Test
    public void theAutoIconPreviewIsNonEmptyAndDeterministic()
    {
        String first = StandardCommandRegistry.describeAutoIconCommands();
        String second = StandardCommandRegistry.describeAutoIconCommands();

        assertNotNull(first);
        assertFalse("an empty preview helps nobody pick a command", first.isBlank()); //$NON-NLS-1$
        assertEquals("the preview goes into error text, so its order must be stable", //$NON-NLS-1$
            first, second);
    }

    @Test
    public void everyAutoIconCommandReportsItself()
    {
        // The preview and the predicate read the same set; if they ever stop agreeing, an error
        // message would recommend a command the check then rejects.
        for (String name : StandardCommandRegistry.describeAutoIconCommands().split(", ")) //$NON-NLS-1$
        {
            assertTrue(name, StandardCommandRegistry.hasAutoIcon(name));
        }
    }

    @Test
    public void theCompatibleOwnerTypesAreDescribed()
    {
        Map<String, String> owners = StandardCommandRegistry.getCompatibleOwnerTypes();

        assertNotNull(owners);
        assertTrue("Document is the canonical compatible owner", owners.containsKey("Document")); //$NON-NLS-1$ //$NON-NLS-2$
        for (Map.Entry<String, String> entry : owners.entrySet())
        {
            assertNull("a compatible owner must not also be refused: " + entry.getKey(), //$NON-NLS-1$
                StandardCommandRegistry.checkOwnerKindCompatibility(entry.getKey(), "Post")); //$NON-NLS-1$
        }
    }
}
