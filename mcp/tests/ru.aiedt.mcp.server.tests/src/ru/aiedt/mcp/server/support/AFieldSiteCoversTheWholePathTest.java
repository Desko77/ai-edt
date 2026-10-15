/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * A field site reaches the end of its dotted path, so a diagnostic pointing at the field after the
 * dot falls inside the site the alias opened.
 */
public class AFieldSiteCoversTheWholePathTest
{
    /** A node over the alias alone is read on through the field after the dot. */
    @Test
    public void aSiteOverTheAliasAloneReachesItsField()
    {
        String text = "ВЫБРАТЬ\n\tВиды.НаборСвойств КАК Р"; //$NON-NLS-1$
        int offset = text.indexOf("\n\tВиды"); //$NON-NLS-1$

        int length = QlValidator.pathLengthFrom(text, offset, "\n\tВиды".length()); //$NON-NLS-1$

        assertEquals(text.indexOf(" КАК") - offset, length); //$NON-NLS-1$
    }

    /** Every further segment is read too, and the path stops at the first character no name holds. */
    @Test
    public void aLongerPathIsReadToItsLastSegment()
    {
        String text = "ВЫБРАТЬ Т.Ссылка.Владелец.Код, Т.Код"; //$NON-NLS-1$
        int offset = text.indexOf(" Т."); //$NON-NLS-1$

        int length = QlValidator.pathLengthFrom(text, offset, 2);

        assertEquals(text.indexOf(",") - offset, length); //$NON-NLS-1$
    }

    /** Whitespace on either side of a dot, a line break included, stays inside the path. */
    @Test
    public void whitespaceAroundADotStaysInThePath()
    {
        String text = "ВЫБРАТЬ Т . Ссылка .\n\tКод КАК К"; //$NON-NLS-1$
        int offset = text.indexOf(" Т "); //$NON-NLS-1$

        assertEquals(text.indexOf(" КАК") - offset, QlValidator.pathLengthFrom(text, offset, 2)); //$NON-NLS-1$
    }

    /** A node already longer than the path keeps its own length. */
    @Test
    public void aNodeLongerThanThePathKeepsItsLength()
    {
        String text = "ВЫБРАТЬ Т.Код + 1"; //$NON-NLS-1$
        int offset = text.indexOf(" Т."); //$NON-NLS-1$

        assertEquals(text.length() - offset,
            QlValidator.pathLengthFrom(text, offset, text.length() - offset));
    }

    /** A dot not followed by a name ends the path at the name before it. */
    @Test
    public void aTrailingDotIsNotPartOfThePath()
    {
        String text = "ВЫБРАТЬ Т."; //$NON-NLS-1$
        int offset = text.indexOf(" Т."); //$NON-NLS-1$

        assertEquals(" Т".length(), QlValidator.pathLengthFrom(text, offset, 1)); //$NON-NLS-1$
    }
}
