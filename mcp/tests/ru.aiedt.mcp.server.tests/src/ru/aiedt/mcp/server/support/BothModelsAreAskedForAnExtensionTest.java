/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Holds the query validator to asking both models an extension has.
 * <p>
 * <b>Choosing one of them without looking at the query was a regression, and a measured one.</b>
 * An extension's own view lacks the inherited fields of a borrowed object, so
 * {@code ВЫБРАТЬ Наименование ИЗ Справочник._ДемоКонтрагенты} failed there and passed in the
 * configuration. Sending every query to the configuration fixed that and broke the other half: the
 * configuration has never held the objects an extension declares itself, so an extension's own
 * information register came back as "table not found" from a model that never had it - reproduced
 * on a stand, and reported first from a live project where the rejected query runs in production.
 * </p>
 * <p>
 * What made the regression possible was the shape of the check that shipped with it: four cases,
 * none of which named an object the extension owns. The claim that the configuration's model "holds
 * both" was asserted rather than measured, and that run could not have contradicted it.
 * </p>
 * <p>
 * <b>How the two answers are compared, and how they are not.</b> Ranking them by how many
 * diagnostics each produced was the first attempt and is unsound: a model that cannot find the
 * table never reaches the fields, so it answers with one complaint while the model that did find it
 * reports a real error per bad field - and counting would prefer the one that resolved nothing.
 * The configuration's answer is therefore taken only when it is clean; when neither is, the answer
 * kept is the one from the project the caller named.
 * </p>
 * <p>
 * <b>What this file can and cannot hold.</b> The routing decision needs a live workspace with an
 * extension and its open parent, which no unit test has - so the case that caused the regression is
 * a live check, recorded in the commit and not simulated here. Stubbing {@code IProject} to fake it
 * would assert against the stub rather than against EDT. What is pinned here is only the part that
 * answers without a workspace, and this note exists so the gap is read as known rather than as
 * covered.
 * </p>
 */
public class BothModelsAreAskedForAnExtensionTest
{
    @Test
    public void nothingToAskAlongsideNothing()
    {
        // Finding out whether there is a second model must not itself become a null dereference:
        // this is called on every query, extension or not.
        assertNull(QlValidator.alsoAsk(null));
    }

    @Test
    public void absentAndUnusableAreDifferentAnswers()
    {
        // The whole point of the three-way answer. A configuration project has no other model and
        // needs none, so its errors are settled where they were found. An extension whose parent is
        // closed or missing HAS one and cannot reach it, and its errors are settled by nothing -
        // a query naming an inherited field fails in the extension and passes in the parent.
        //
        // While both answers were the same null, those two cases were indistinguishable, and the
        // second one shipped unconfirmed errors as a verdict.
        assertEquals(4, QlValidator.Companion.Kind.values().length);
        assertNotNull(QlValidator.Companion.Kind.NONE);
        assertNotNull(QlValidator.Companion.Kind.READY_TO_ASK);
        assertNotNull(QlValidator.Companion.Kind.UNUSABLE);
        assertNotNull(QlValidator.Companion.Kind.CANNOT_TELL);
    }

    @Test
    public void nothingNamedIsNothingToConfirm()
    {
        // No project, no claim about extensions - and specifically not "unusable", which would
        // refuse every call made before a project name is resolved.
        QlValidator.Companion companion = QlValidator.companionOf(null);
        assertNotNull(companion);
        assertEquals(QlValidator.Companion.Kind.NONE, companion.kind);
        assertNull(companion.project);
    }

    @Test
    public void aShrugAndARefusalAreToldApart()
    {
        // These two share a false `available` and mean opposite things to a caller that writes.
        //
        // Unavailable is an EDT that cannot check queries at all - dcs_workshop has always written
        // anyway, because blocking every edit on such an install would make the tool useless.
        // Unconfirmed is a check that was possible and did not settle. Writing on that is writing
        // text nothing vouched for, and while both were the same flag, that is what happened.
        QlValidator.ValidationResult cannotCheckHere =
            QlValidator.ValidationResult.unavailable("no query language in this EDT"); //$NON-NLS-1$
        assertFalse(cannotCheckHere.available);
        assertFalse("an absent language is not an unsettled answer", //$NON-NLS-1$
            cannotCheckHere.unconfirmed);

        QlValidator.ValidationResult didNotSettle =
            QlValidator.ValidationResult.unconfirmed("the other model is still building"); //$NON-NLS-1$
        assertFalse(didNotSettle.available);
        assertTrue(didNotSettle.unconfirmed);
    }

    @Test
    public void whatWasFoundSurvivesNotBeingSettled()
    {
        // Half an answer is still worth reporting: the syntax errors found before the semantic
        // checker turned out to be missing are real, and a caller should see them while being told
        // the rest was never asked.
        List<QlValidator.QlIssue> found = new ArrayList<>();
        found.add(new QlValidator.QlIssue("ERROR", "unexpected token", 1, 7)); //$NON-NLS-1$ //$NON-NLS-2$
        QlValidator.ValidationResult partial = QlValidator.ValidationResult.unconfirmedWith(found,
            "the checker that resolves names was not available"); //$NON-NLS-1$

        assertTrue(partial.unconfirmed);
        assertEquals(1, partial.errorCount);
        assertTrue(partial.hasErrors());
        assertEquals(1, partial.issues.size());
    }

    @Test
    public void anOrdinaryAnswerClaimsNothingUnsettled()
    {
        assertFalse(QlValidator.ValidationResult.ok().unconfirmed);
        assertFalse(QlValidator.ValidationResult.of(new ArrayList<>()).unconfirmed);
    }

    @Test
    public void aDiagnosticCarriesACodeBesideItsLocalisedWords()
    {
        // The words are localised - "field not found" arrives in Russian on a Russian EDT - so
        // anything deciding what to do about a diagnostic has to read the code instead. A check
        // written against the message works here and silently stops working elsewhere.
        QlValidator.QlIssue withCode =
            new QlValidator.QlIssue("ERROR", "Поле 'Amount' не найдено", 1, 8, "Field not found"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Field not found", withCode.code); //$NON-NLS-1$
        assertEquals("Field not found", withCode.toMap().get("code")); //$NON-NLS-1$ //$NON-NLS-2$

        // And an older diagnostic that carries none says so by absence rather than by an empty
        // string, so a reader can tell "no code" from "a code that is blank".
        QlValidator.QlIssue withoutCode =
            new QlValidator.QlIssue("ERROR", "unexpected token", 1, 7); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(withoutCode.code);
        assertFalse(withoutCode.toMap().containsKey("code")); //$NON-NLS-1$
    }
}
