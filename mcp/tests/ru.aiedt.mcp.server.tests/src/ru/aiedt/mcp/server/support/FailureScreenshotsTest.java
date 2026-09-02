/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Reading a run's images against the steps that broke.
 * <p>
 * The point of the attribution is not that it always finds an owner - it cannot, because nothing
 * in the report promises how the images are named. The point is that what it does not reach comes
 * back as unattributed, so a caller sees every image either against a step or in the leftovers,
 * and never sees one silently dropped.
 * </p>
 */
public class FailureScreenshotsTest
{
    private static List<JUnitRunOutcome.TestCase> cases(JUnitRunOutcome.TestCase... items)
    {
        return new ArrayList<>(Arrays.asList(items));
    }

    @Test
    public void aStepThatNamesItsOwnImageOwnsIt()
    {
        List<JUnitRunOutcome.TestCase> broken = cases(
            new JUnitRunOutcome.TestCase("Проведение документа", //$NON-NLS-1$
                "Шаг упал, снимок step-0007.png", null), //$NON-NLS-1$
            new JUnitRunOutcome.TestCase("Печать формы", null, null)); //$NON-NLS-1$

        FailureScreenshots read = FailureScreenshots.attribute(broken,
            Arrays.asList("step-0007.png")); //$NON-NLS-1$

        assertEquals("the message names the file, which settles it", //$NON-NLS-1$
            Arrays.asList("step-0007.png"), read.byStep().get("Проведение документа")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(read.unattributed().isEmpty());
    }

    @Test
    public void aTraceNamingTheImageCountsToo()
    {
        List<JUnitRunOutcome.TestCase> broken = cases(
            new JUnitRunOutcome.TestCase("Открытие списка", null, //$NON-NLS-1$
                "{Обработка.ВА(12)}: см. shots/fail-12.png")); //$NON-NLS-1$

        FailureScreenshots read = FailureScreenshots.attribute(broken,
            Arrays.asList("fail-12.png")); //$NON-NLS-1$

        assertEquals(Arrays.asList("fail-12.png"), read.byStep().get("Открытие списка")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anImageCarryingTheStepNameGoesToThatStep()
    {
        List<JUnitRunOutcome.TestCase> broken = cases(
            new JUnitRunOutcome.TestCase("Печать формы", null, null), //$NON-NLS-1$
            new JUnitRunOutcome.TestCase("Проведение документа", null, null)); //$NON-NLS-1$

        FailureScreenshots read = FailureScreenshots.attribute(broken,
            Arrays.asList("2026-09-02 Проведение_документа.png")); //$NON-NLS-1$

        assertEquals("spaces, case and punctuation differ and carry nothing here", //$NON-NLS-1$
            Arrays.asList("2026-09-02 Проведение_документа.png"), //$NON-NLS-1$
            read.byStep().get("Проведение документа")); //$NON-NLS-1$
    }

    @Test
    public void animageNothingClaimsIsReturnedNotDropped()
    {
        List<JUnitRunOutcome.TestCase> broken = cases(
            new JUnitRunOutcome.TestCase("Печать формы", "не совпало", null)); //$NON-NLS-1$ //$NON-NLS-2$

        FailureScreenshots read = FailureScreenshots.attribute(broken,
            Arrays.asList("screen-1.png", "screen-2.png")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("nothing links these, and inventing a link is worse than none", //$NON-NLS-1$
            read.byStep().isEmpty());
        assertEquals("every image is accounted for somewhere", //$NON-NLS-1$
            Arrays.asList("screen-1.png", "screen-2.png"), read.unattributed()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aStepNameTooShortToMeanAnythingIsNotMatched()
    {
        List<JUnitRunOutcome.TestCase> broken = cases(
            new JUnitRunOutcome.TestCase("Ок", null, null)); //$NON-NLS-1$

        FailureScreenshots read = FailureScreenshots.attribute(broken,
            Arrays.asList("окно-справки.png")); //$NON-NLS-1$

        assertTrue("two letters match by accident, and a wrong owner reads as a right one", //$NON-NLS-1$
            read.byStep().isEmpty());
        assertEquals(Arrays.asList("окно-справки.png"), read.unattributed()); //$NON-NLS-1$
    }

    @Test
    public void aNameThatOnlyBeginsAnotherNameIsNotAMatch()
    {
        List<JUnitRunOutcome.TestCase> broken = cases(
            new JUnitRunOutcome.TestCase("Печать формы", //$NON-NLS-1$
                "упало на снимке step-00070.png", null)); //$NON-NLS-1$

        FailureScreenshots read = FailureScreenshots.attribute(broken,
            Arrays.asList("step-0007.png")); //$NON-NLS-1$

        assertTrue("step-0007 begins step-00070 and belongs to neither for that reason", //$NON-NLS-1$
            read.byStep().isEmpty());
        assertEquals(Arrays.asList("step-0007.png"), read.unattributed()); //$NON-NLS-1$
    }

    @Test
    public void theWholeNameStillMatchesWhenItStandsAlone()
    {
        List<JUnitRunOutcome.TestCase> broken = cases(
            new JUnitRunOutcome.TestCase("Печать формы", //$NON-NLS-1$
                "упало, см. step-0007 в каталоге снимков", null)); //$NON-NLS-1$

        FailureScreenshots read = FailureScreenshots.attribute(broken,
            Arrays.asList("step-0007.png")); //$NON-NLS-1$

        assertEquals("nothing alphanumeric touches either end of it", //$NON-NLS-1$
            Arrays.asList("step-0007.png"), read.byStep().get("Печать формы")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aFileNamedAfterTheStepAloneStillFindsIt()
    {
        List<JUnitRunOutcome.TestCase> broken = cases(
            new JUnitRunOutcome.TestCase("Мод_Продажи.ПроведениеДокумента", null, null)); //$NON-NLS-1$

        FailureScreenshots read = FailureScreenshots.attribute(broken,
            Arrays.asList("ПроведениеДокумента.png")); //$NON-NLS-1$

        assertEquals("the report qualifies a step with its module and the file does not", //$NON-NLS-1$
            Arrays.asList("ПроведениеДокумента.png"), //$NON-NLS-1$
            read.byStep().get("Мод_Продажи.ПроведениеДокумента")); //$NON-NLS-1$
    }

    @Test
    public void theQualifiedNameStaysTheKey()
    {
        List<JUnitRunOutcome.TestCase> broken = cases(
            new JUnitRunOutcome.TestCase("Мод_Продажи.ПечатьФормы", null, null)); //$NON-NLS-1$

        FailureScreenshots read = FailureScreenshots.attribute(broken,
            Arrays.asList("Мод_Продажи.ПечатьФормы.png")); //$NON-NLS-1$

        assertTrue("the step is named as the report named it", //$NON-NLS-1$
            read.byStep().containsKey("Мод_Продажи.ПечатьФормы")); //$NON-NLS-1$
    }

    @Test
    public void theLongestMatchingStepOwnsTheImage()
    {
        List<JUnitRunOutcome.TestCase> broken = cases(
            new JUnitRunOutcome.TestCase("Открытие формы", null, null), //$NON-NLS-1$
            new JUnitRunOutcome.TestCase("Открытие формы списка", null, null)); //$NON-NLS-1$

        FailureScreenshots read = FailureScreenshots.attribute(broken,
            Arrays.asList("Открытие формы списка.png")); //$NON-NLS-1$

        assertTrue("report order has no business deciding this", //$NON-NLS-1$
            read.byStep().containsKey("Открытие формы списка")); //$NON-NLS-1$
        assertEquals(Arrays.asList("Открытие формы списка.png"), //$NON-NLS-1$
            read.byStep().get("Открытие формы списка")); //$NON-NLS-1$
    }

    @Test
    public void aRunWithNothingBrokenAndNoImagesReadsAsEmpty()
    {
        FailureScreenshots read =
            FailureScreenshots.attribute(Collections.emptyList(), Collections.emptyList());

        assertTrue(read.byStep().isEmpty());
        assertTrue(read.unattributed().isEmpty());
        assertEquals("nothing to say, so nothing is written", //$NON-NLS-1$
            "", read.toMarkdown(null)); //$NON-NLS-1$
    }

    @Test
    public void imagesWithNoBrokenStepAtAllAreStillReturned()
    {
        FailureScreenshots read =
            FailureScreenshots.attribute(null, Arrays.asList("one.png")); //$NON-NLS-1$

        assertEquals(Arrays.asList("one.png"), read.unattributed()); //$NON-NLS-1$
    }

    @Test
    public void theMarkdownShowsFullPathsUnderTheStepThatOwnsThem()
    {
        List<JUnitRunOutcome.TestCase> broken = cases(
            new JUnitRunOutcome.TestCase("Проведение документа", //$NON-NLS-1$
                "снимок shot-1.png", null)); //$NON-NLS-1$
        Map<String, String> full = new LinkedHashMap<>();
        full.put("shot-1.png", "C:\\runs\\shot-1.png"); //$NON-NLS-1$ //$NON-NLS-2$
        full.put("left-over.png", "C:\\runs\\left-over.png"); //$NON-NLS-1$ //$NON-NLS-2$

        String md = FailureScreenshots
            .attribute(broken, Arrays.asList("shot-1.png", "left-over.png")) //$NON-NLS-1$ //$NON-NLS-2$
            .toMarkdown(full);

        assertTrue(md, md.contains("### Проведение документа")); //$NON-NLS-1$
        assertTrue("the caller has to be able to open it", //$NON-NLS-1$
            md.contains("C:\\runs\\shot-1.png")); //$NON-NLS-1$
        assertTrue("the leftovers are shown, not hidden", //$NON-NLS-1$
            md.contains("Not attributed to a step")); //$NON-NLS-1$
        assertTrue(md, md.contains("C:\\runs\\left-over.png")); //$NON-NLS-1$
    }
}
