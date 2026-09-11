/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * A requested address the model does not hold is named, instead of answering a clean page.
 * <p>
 * Measured on a stand: {@code objects=["Document.НетТакого"]} answered "Nothing Found" and said
 * nothing about the address, so a mistyped FQN read as "this object has no errors". The same
 * measurement showed why the index alone cannot decide it: a data processor's existing form and
 * existing template both come back unresolved from {@code getTopObjectByFqn}, because neither is a
 * BM top object. The notice therefore asks the shared resolver, and stays silent without a project
 * to ask.
 * </p>
 */
public class AnAddressTheModelLacksIsNamedTest
{
    @Test
    public void withoutAProjectThereIsNothingToAsk()
    {
        assertEquals("", ProjectProblemsReader.unresolvedNotice(null, //$NON-NLS-1$
            Collections.singletonList("Document.Anything"))); //$NON-NLS-1$
        assertEquals("", ProjectProblemsReader.unresolvedNotice("", //$NON-NLS-1$ //$NON-NLS-2$
            Collections.singletonList("Document.Anything"))); //$NON-NLS-1$
    }

    @Test
    public void withoutAddressesThereIsNothingToName()
    {
        assertEquals("", ProjectProblemsReader.unresolvedNotice("AnyProject", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", ProjectProblemsReader.unresolvedNotice("AnyProject", //$NON-NLS-1$ //$NON-NLS-2$
            Collections.<String> emptyList()));
    }

    @Test
    public void aProjectThatIsNotInTheWorkspaceIsNotAnAbsentAddress()
    {
        // The caller is already told the project is unknown, by its own refusal. Naming every
        // address as missing on top of that would blame the addresses for the project.
        assertEquals("", ProjectProblemsReader.unresolvedNotice( //$NON-NLS-1$
            "NoSuchProjectInThisWorkspace", Arrays.asList("Document.A", "Catalog.B"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void theNoticeNamesTheAddressAndTellsTheReaderWhatToCheck()
    {
        // The text is what a caller reads instead of a clean page; pin its substance, not its
        // wording beyond the parts that carry meaning.
        String notice = ProjectProblemsReader.describeMissing(Arrays.asList("Document.НетТакого")); //$NON-NLS-1$
        assertTrue(notice, notice.contains("Document.НетТакого")); //$NON-NLS-1$
        assertTrue(notice, notice.contains("spelling")); //$NON-NLS-1$
        assertFalse("a single address is not addressed in the plural", notice.contains("them")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aMixedRequestNamesOnlyTheAddressThatIsMissing()
    {
        // The case that matters, and the one a workspace-bound method cannot be asked about: the
        // answer must not blame an address that stands.
        java.util.List<String> missing = ProjectProblemsReader.missingAmong(
            Arrays.asList("Document.Real", "Document.НетТакого"), //$NON-NLS-1$ //$NON-NLS-2$
            fqn -> "Document.Real".equals(fqn)); //$NON-NLS-1$

        assertEquals(Collections.singletonList("Document.НетТакого"), missing); //$NON-NLS-1$
    }

    @Test
    public void anExistingFormOrTemplateIsNotCalledMissing()
    {
        // Measured on a stand: getTopObjectByFqn answers null for both, though both exist. The
        // resolver handed in here is the one that knows child addresses; the point of the test is
        // that the notice reports whatever IT says, and adds nothing of its own.
        java.util.List<String> missing = ProjectProblemsReader.missingAmong(
            Arrays.asList("DataProcessor.X.Form.Форма", "DataProcessor.X.Template.Схема"), //$NON-NLS-1$ //$NON-NLS-2$
            fqn -> true);

        assertTrue(missing.toString(), missing.isEmpty());
        assertEquals("", ProjectProblemsReader.describeMissing(missing)); //$NON-NLS-1$
    }

    @Test
    public void everyAddressMissingIsEveryAddressNamed()
    {
        java.util.List<String> missing = ProjectProblemsReader.missingAmong(
            Arrays.asList("A.B", "C.D"), fqn -> false); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Arrays.asList("A.B", "C.D"), missing); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void severalAddressesAreAllNamed()
    {
        String notice = ProjectProblemsReader.describeMissing(Arrays.asList("Document.A", "Catalog.B")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(notice, notice.contains("Document.A")); //$NON-NLS-1$
        assertTrue(notice, notice.contains("Catalog.B")); //$NON-NLS-1$
        assertTrue(notice, notice.contains("them")); //$NON-NLS-1$
    }
}
