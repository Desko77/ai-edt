/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.BmDcsHelper;

/**
 * A conditional-appearance item that would change nothing is refused: an unknown appearance
 * property, an appearance without a {@code Name=Value} pair, and an appearance without a condition
 * whose every entry is left out. An entry left out beside a written one, or beside a condition,
 * still builds the item and is named.
 */
public class AnAppearanceThatWritesNothingIsRefusedTest
{
    private DcsWorkshopTool tool;

    /**
     * Skips where the composition model is not in the runtime.
     */
    @Before
    public void requireTheCompositionModel()
    {
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            BmDcsHelper.createElement("createDataCompositionConditionalAppearanceItem") != null); //$NON-NLS-1$
        tool = new DcsWorkshopTool();
    }

    /**
     * An appearance property no item carries is refused and the accepted ones are named.
     */
    @Test
    public void anUnknownPropertyIsRefused()
    {
        assertRefused(null, null, "NoSuchProperty=1", "unknown appearance property 'NoSuchProperty'"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused("Контрагент", "10", "TextColor=#FF0000;NoSuchProperty=1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "TextColor, BackColor, BorderColor, Font, Format"); //$NON-NLS-1$
    }

    /**
     * An appearance without a single {@code Name=Value} pair is refused.
     */
    @Test
    public void anAppearanceWithoutAPairIsRefused()
    {
        assertRefused(null, null, "bold", "carries no Name=Value pair"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Without a condition, an appearance whose every entry is left out is refused.
     */
    @Test
    public void anAppearanceLeftOutWholeIsRefused()
    {
        assertRefused(null, null, "TextColor=Style.Important", "none of the appearance entries"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An entry left out beside a written entry, or beside a condition, builds the item and is named
     * among the entries left out.
     */
    @Test
    public void anEntryLeftOutBesideAWrittenOneIsNamed()
    {
        DcsWorkshopTool.AppearanceItem mixed =
            tool.newAppearanceItem(null, null, null, "TextColor=#FF0000;BackColor=Style.Important"); //$NON-NLS-1$
        assertEquals(1, mixed.skipped.size());
        DcsWorkshopTool.AppearanceItem conditioned =
            tool.newAppearanceItem("Контрагент", null, "10", "TextColor=Style.Important"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(1, conditioned.skipped.size());
    }

    /**
     * @param field the condition field, or <code>null</code>
     * @param conditionValue the compared value, or <code>null</code>
     * @param appearance the appearance
     * @param expected a fragment the refusal carries
     */
    private void assertRefused(String field, String conditionValue, String appearance, String expected)
    {
        try
        {
            tool.newAppearanceItem(field, null, conditionValue, appearance);
            fail("an appearance that writes nothing must be refused: " + appearance); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains(expected));
        }
    }
}
