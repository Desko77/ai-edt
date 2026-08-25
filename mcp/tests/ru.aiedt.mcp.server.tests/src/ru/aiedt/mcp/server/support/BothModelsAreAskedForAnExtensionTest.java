/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertNull;

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
}
