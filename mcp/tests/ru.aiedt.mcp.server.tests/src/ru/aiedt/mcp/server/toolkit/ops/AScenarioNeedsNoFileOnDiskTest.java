/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A scenario composed by the caller, played without a file the caller could not have placed.
 * <p>
 * {@code featurePath} needs a file that already exists on the machine. A caller reaching this
 * server over MCP cannot put one there, so it could run only scenarios somebody had written by
 * hand - which is what kept a form snapshot in a running 1C out of reach: composing three lines of
 * Gherkin is easy, getting them onto the machine was not.
 * </p>
 */
public class AScenarioNeedsNoFileOnDiskTest
{
    private Path dir;

    @Before
    public void makeADirectory() throws Exception
    {
        dir = Files.createTempDirectory("vanessa-scenario-test"); //$NON-NLS-1$
    }

    @After
    public void removeIt() throws Exception
    {
        try (Stream<Path> entries = Files.walk(dir))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void namingBothWaysIsRefusedRatherThanResolvedByPrecedence()
    {
        String why = VanessaTool.whyTheScenarioIsNotNamed(true, true);

        assertNotNull("nothing in the call says which was meant", why); //$NON-NLS-1$
        assertTrue(why, why.contains("featurePath") && why.contains("scenarioText")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void namingNeitherWayIsRefusedAndBothWaysAreOffered()
    {
        String why = VanessaTool.whyTheScenarioIsNotNamed(false, false);

        assertNotNull(why);
        assertTrue("a caller that cannot write a file has to be told the other way exists: " + why, //$NON-NLS-1$
            why.contains("scenarioText")); //$NON-NLS-1$
    }

    @Test
    public void eitherWayOnItsOwnGoesThrough()
    {
        assertNull(VanessaTool.whyTheScenarioIsNotNamed(true, false));
        assertNull(VanessaTool.whyTheScenarioIsNotNamed(false, true));
    }

    @Test
    public void aFormIsPhotographedByATagRatherThanAStep()
    {
        String scenario = VanessaTool.scenarioForForm("ИИА_Агент", null, null); //$NON-NLS-1$

        assertTrue("the snapshot is a tag on the step that follows, not a step of its own: " //$NON-NLS-1$
            + scenario, scenario.contains("@screenshot")); //$NON-NLS-1$
        assertTrue("the tag stands before the step it photographs", //$NON-NLS-1$
            scenario.indexOf("@screenshot") < scenario.indexOf("Я открываю")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the form the caller named belongs in the step: " + scenario, //$NON-NLS-1$
            scenario.contains("\"ИИА_Агент\"")); //$NON-NLS-1$
        assertTrue("a scenario needs a client to work in", //$NON-NLS-1$
            scenario.contains(VanessaTool.START_STEP));
        assertTrue("Vanessa reads the language from the first line", //$NON-NLS-1$
            scenario.startsWith("#language: ru")); //$NON-NLS-1$
    }

    @Test
    public void theWordingOfEachStepCanBeGivenByTheCaller()
    {
        String scenario = VanessaTool.scenarioForForm("Справочник.Товары", //$NON-NLS-1$
            "Я подключаюсь к запущенному клиенту", //$NON-NLS-1$
            "Я открываю форму списка справочника {form}"); //$NON-NLS-1$

        assertTrue("a list form is opened by other words than a common form: " + scenario, //$NON-NLS-1$
            scenario.contains("Я открываю форму списка справочника Справочник.Товары")); //$NON-NLS-1$
        assertTrue("and the client is reached by the caller's words too", //$NON-NLS-1$
            scenario.contains("Я подключаюсь к запущенному клиенту")); //$NON-NLS-1$
        assertTrue("the default wording is gone when the caller gave one", //$NON-NLS-1$
            !scenario.contains(VanessaTool.OPEN_STEP.replace("{form}", "Справочник.Товары"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aFormNameThatWouldBreakTheStepIsRefused()
    {
        assertNotNull("a quote closes the step's own quotes and the scenario means something else", //$NON-NLS-1$
            VanessaTool.whyTheFormCannotBeNamed("Спра\"вочник", null)); //$NON-NLS-1$
        assertNotNull("a step is one line", //$NON-NLS-1$
            VanessaTool.whyTheFormCannotBeNamed("Справочник\nТовары", null)); //$NON-NLS-1$
        assertNotNull("an empty name opens nothing", //$NON-NLS-1$
            VanessaTool.whyTheFormCannotBeNamed("   ", null)); //$NON-NLS-1$
        assertNull("an ordinary name goes through", //$NON-NLS-1$
            VanessaTool.whyTheFormCannotBeNamed("ИИА_Агент", null)); //$NON-NLS-1$
    }

    @Test
    public void aWordingWithNowhereToPutTheNameIsRefused()
    {
        String why = VanessaTool.whyTheFormCannotBeNamed("ИИА_Агент", //$NON-NLS-1$
            "Я открываю общую форму"); //$NON-NLS-1$

        assertNotNull("a wording without {form} opens whatever it names, not what was asked", why); //$NON-NLS-1$
        assertTrue("the refusal says what to put in it: " + why, why.contains("{form}")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a wording spanning lines is not one step", //$NON-NLS-1$
            VanessaTool.whyTheFormCannotBeNamed("ИИА_Агент", "Я открываю\nформу {form}")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the step that gets a client is one line too", //$NON-NLS-1$
            VanessaTool.whyTheFormCannotBeNamed("ИИА_Агент", null, //$NON-NLS-1$
                "Я подключаюсь\nи еще что-то")); //$NON-NLS-1$
    }

    @Test
    public void namingAFormAndSomethingElseIsRefused()
    {
        assertNotNull("a form and a file both say what to play", //$NON-NLS-1$
            VanessaTool.whyTheScenarioIsNotNamed(true, false, true));
        assertNotNull("a form and a text both say what to play", //$NON-NLS-1$
            VanessaTool.whyTheScenarioIsNotNamed(false, true, true));
        assertNull("a form on its own is one way of saying it", //$NON-NLS-1$
            VanessaTool.whyTheScenarioIsNotNamed(false, false, true));
        assertTrue("the refusal offers the form as a third way: " //$NON-NLS-1$
            + VanessaTool.whyTheScenarioIsNotNamed(false, false, false),
            VanessaTool.whyTheScenarioIsNotNamed(false, false, false).contains("formToOpen")); //$NON-NLS-1$
    }

    @Test
    public void aNamedFileIsPlayedAsItIs() throws Exception
    {
        File named = new File(dir.toFile(), "given.feature"); //$NON-NLS-1$
        Files.write(named.toPath(), "#language: ru\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        File playing = VanessaTool.scenarioFileFor(dir.toFile(), named);

        assertEquals("a file the caller named is not rewritten", named, playing); //$NON-NLS-1$
    }

    @Test
    public void composedTextBecomesAFileInTheRunsOwnDirectory() throws Exception
    {
        String scenario = "#language: ru\n\nФункционал: Снимок формы\n\nСценарий: Открыть\n" //$NON-NLS-1$
            + "    Когда Я открываю общую форму \"ИИА_Агент\"\n"; //$NON-NLS-1$

        File playing = VanessaTool.scenarioFileFor(dir.toFile(), null);
        assertEquals("the place is named before anything is written, so a write that fails " //$NON-NLS-1$
            + "halfway still leaves something to remove", dir.toFile(), playing.getParentFile()); //$NON-NLS-1$
        Files.write(playing.toPath(), scenario.getBytes(StandardCharsets.UTF_8));

        assertTrue("the run plays a file, so the text has to become one", playing.isFile()); //$NON-NLS-1$
        assertEquals("it belongs to this run, beside its report and its screenshots", //$NON-NLS-1$
            dir.toFile(), playing.getParentFile());
        String written = new String(Files.readAllBytes(playing.toPath()), StandardCharsets.UTF_8);
        assertTrue("the scenario reaches the file whole: " + written, //$NON-NLS-1$
            written.contains("Я открываю общую форму")); //$NON-NLS-1$
    }

    @Test
    public void aLaunchThatNeverHappenedTakesItsScenarioAway() throws Exception
    {
        File composed = new File(dir.toFile(), "never-launched.feature"); //$NON-NLS-1$
        Files.write(composed.toPath(), "#language: ru".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        String stillHere = VanessaTool.scenarioAfterFailure(false, null, composed);

        assertNull("nobody is reading it, so it does not stay: " + stillHere, stillHere); //$NON-NLS-1$
        assertTrue("a missing executable throws before a process exists, and the scenario - " //$NON-NLS-1$
            + "which may carry a password - must not be left in the run directory", //$NON-NLS-1$
            !composed.exists());
    }

    @Test
    public void aLaunchedClientKeepsItsScenarioAndIsSaidTo() throws Exception
    {
        File composed = new File(dir.toFile(), "being-read.feature"); //$NON-NLS-1$
        Files.write(composed.toPath(), "#language: ru".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        String stillHere = VanessaTool.scenarioAfterFailure(true, null, composed);

        assertEquals("a client already started may be reading it", //$NON-NLS-1$
            composed.getAbsolutePath(), stillHere);
        assertTrue("so it stays where it is", composed.isFile()); //$NON-NLS-1$
    }

    @Test
    public void aRemovalAlreadyReportedIsNotContradicted() throws Exception
    {
        assertEquals("what an earlier removal said stands", "C:/somewhere/left.feature", //$NON-NLS-1$ //$NON-NLS-2$
            VanessaTool.scenarioAfterFailure(true, "C:/somewhere/left.feature", null)); //$NON-NLS-1$
        assertNull("and a caller who named their own file has nothing composed to answer for", //$NON-NLS-1$
            VanessaTool.scenarioAfterFailure(true, null, null));
    }

    @Test
    public void anIncompleteDistributionIsNamedInsteadOfGuessedAt() throws Exception
    {
        File epf = new File(dir.toFile(), "vanessa-automation.epf"); //$NON-NLS-1$
        Files.write(epf.toPath(), new byte[] { 1 });
        Files.createDirectories(new File(dir.toFile(), "locales").toPath()); //$NON-NLS-1$

        String missing = VanessaTool.whatTheDistributionIsMissing(epf);

        assertNotNull("an empty locales and an absent lib is why the run produced nothing", //$NON-NLS-1$
            missing);
        assertTrue("both folders are named: " + missing, //$NON-NLS-1$
            missing.contains("locales") && missing.contains("lib")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and the way out is named too: " + missing, //$NON-NLS-1$
            missing.contains("single-file")); //$NON-NLS-1$
    }

    @Test
    public void aCompleteDistributionIsNotComplainedAbout() throws Exception
    {
        File epf = new File(dir.toFile(), "vanessa-automation.epf"); //$NON-NLS-1$
        Files.write(epf.toPath(), new byte[] { 1 });
        for (String folder : new String[] { "locales", "lib" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Files.createDirectories(new File(dir.toFile(), folder).toPath());
            Files.write(new File(dir.toFile(), folder + "/x.epf").toPath(), new byte[] { 1 }); //$NON-NLS-1$
        }

        assertNull("nothing is missing, so nothing is said", //$NON-NLS-1$
            VanessaTool.whatTheDistributionIsMissing(epf));
    }

    @Test
    public void theSingleFileBuildNeedsNothingBesideIt() throws Exception
    {
        File epf = new File(dir.toFile(), "vanessa-automation-single.epf"); //$NON-NLS-1$
        Files.write(epf.toPath(), new byte[] { 1 });

        assertNull("the single-file build carries its companions inside, so an empty directory " //$NON-NLS-1$
            + "beside it says nothing", VanessaTool.whatTheDistributionIsMissing(epf)); //$NON-NLS-1$
    }

    @Test
    public void aFileThatWouldNotGoIsNamedRatherThanForgotten() throws Exception
    {
        File composed = new File(dir.toFile(), "composed.feature"); //$NON-NLS-1$
        Files.write(composed.toPath(), "#language: ru".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        assertNull("a file that goes is not reported", VanessaTool.removeComposed(composed)); //$NON-NLS-1$
        assertTrue("and it is actually gone", !composed.exists()); //$NON-NLS-1$
        assertNull("nothing to remove is not a failure to remove", //$NON-NLS-1$
            VanessaTool.removeComposed(null));
        assertNull("neither is a file that was never there", //$NON-NLS-1$
            VanessaTool.removeComposed(new File(dir.toFile(), "never.feature"))); //$NON-NLS-1$
    }
}
