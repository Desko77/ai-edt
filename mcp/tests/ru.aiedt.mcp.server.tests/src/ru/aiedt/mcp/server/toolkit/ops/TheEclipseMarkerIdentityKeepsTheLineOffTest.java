/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore;
import ru.aiedt.mcp.server.support.DiagnosticsBaselineStore.Diagnostic;

/**
 * The identity reference of a plain Eclipse marker carries no line number.
 * <p>
 * A marker without a location used to present itself as {@code path:line}, and the presentation
 * went into the baseline identity whole - so a finding one line lower read as one new and one
 * resolved. The reference is the location, or the path alone.
 * </p>
 */
public class TheEclipseMarkerIdentityKeepsTheLineOffTest
{
    private static final String PATH = "/Project/src/CommonModules/Common/Module.bsl"; //$NON-NLS-1$

    /**
     * Without a location the reference is the path, with no line in it.
     */
    @Test
    public void withoutALocationTheReferenceIsThePathAlone()
    {
        assertEquals(PATH, ProjectProblemsReader.eclipseIdentityRef("", PATH)); //$NON-NLS-1$
        assertEquals(PATH, ProjectProblemsReader.eclipseIdentityRef(null, PATH));
    }

    /**
     * A location the marker carries is the reference, as it is for the presentation.
     */
    @Test
    public void aCarriedLocationIsTheReference()
    {
        assertEquals("line 5", ProjectProblemsReader.eclipseIdentityRef("line 5", PATH)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Two findings of one marker that moved lines collapse into one counted record: the
     * references are equal, so the identity never sees the shift.
     */
    @Test
    public void aShiftedFindingCollapsesIntoOneRecord()
    {
        Diagnostic atLine12 = new Diagnostic(
            ProjectProblemsReader.eclipseIdentityRef("", PATH), "org.eclipse.core.resources.problem", //$NON-NLS-1$ //$NON-NLS-2$
            "Problem", "The import X is never used"); //$NON-NLS-1$ //$NON-NLS-2$
        Diagnostic atLine13 = new Diagnostic(
            ProjectProblemsReader.eclipseIdentityRef("", PATH), "org.eclipse.core.resources.problem", //$NON-NLS-1$ //$NON-NLS-2$
            "Problem", "The import X is never used"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(atLine12.identity(), atLine13.identity());
        List<Diagnostic> collapsed = DiagnosticsBaselineStore.collapse(Arrays.asList(atLine12, atLine13));
        assertEquals(1, collapsed.size());
        assertEquals(2, collapsed.get(0).count);
        assertNotEquals(atLine12.identity(),
            new Diagnostic(PATH + ":12", "org.eclipse.core.resources.problem", "Problem", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "The import X is never used").identity()); //$NON-NLS-1$
    }
}
