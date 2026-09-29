/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers what counts as a failed answer, for the two places that have to agree about it.
 * <p>
 * They did not. The idempotency store knew all four shapes a refusal takes; the request router
 * carried its own copy that knew only the JSON one, so a tool refusing in plain text went into the
 * call history as a success - and the history window's "failures only" filter hid precisely the
 * calls somebody had opened it to find. The rule lives in one place now, and these pin it.
 * </p>
 */
public class FailureShapeTest
{
    @Test
    public void aJsonBodySayingSoIsAFailure()
    {
        assertTrue(FailureShape.looksFailed("{\"success\":false,\"error\":\"nope\"}")); //$NON-NLS-1$
        // Both spacings, because the answer is assembled in more than one place.
        assertTrue(FailureShape.looksFailed("{\"success\": false}")); //$NON-NLS-1$
    }

    @Test
    public void aPlainTextRefusalIsAFailureToo()
    {
        // This is the one the router used to miss. Text and markdown tools refuse like this.
        assertTrue(FailureShape.looksFailed("Error: Project not found: 'NoSuchProject'")); //$NON-NLS-1$
        assertTrue(FailureShape.looksFailed("\n  Error: no BM model available")); //$NON-NLS-1$
    }

    @Test
    public void theOtherTwoShapesCountAsWell()
    {
        assertTrue(FailureShape.looksFailed("Failed while writing the file: access denied")); //$NON-NLS-1$
        assertTrue(FailureShape.looksFailed("---\ntool: edit_form\nstatus: error\n---\nbad field")); //$NON-NLS-1$
    }

    @Test
    public void aModuleThatMerelyContainsThosePhrasesIsNotAFailedCall()
    {
        // This rule is asked of EVERY answer now, including the source of a module. Searching for
        // these phrases anywhere would read a successful read_module_source as a failed call, and
        // the history's "failures only" filter would then show a defect that never happened.
        String source = "Процедура Записать()\n"  //$NON-NLS-1$
            + "    ЗаписьЖурналаРегистрации(\"Failed while writing the file\");\n" //$NON-NLS-1$
            + "    // возвращаем status: error клиенту\n" //$NON-NLS-1$
            + "КонецПроцедуры"; //$NON-NLS-1$
        assertFalse(FailureShape.looksFailed(source));
    }

    @Test
    public void anAnswerThatQuotesAFailureLineMidTextIsNotAFailure()
    {
        // A report or a search hit may carry the yaml line as data. Only a line of its own counts.
        assertFalse(FailureShape.looksFailed("Найдено 2 совпадения: status: error в шаблоне")); //$NON-NLS-1$
    }

    @Test
    public void nothingAtAllIsAFailure()
    {
        // A tool that returned nothing did not do the thing.
        assertTrue(FailureShape.looksFailed(null));
    }

    @Test
    public void anOrdinaryAnswerIsNotAFailure()
    {
        assertFalse(FailureShape.looksFailed("{\"success\":true,\"count\":3}")); //$NON-NLS-1$
        assertFalse(FailureShape.looksFailed("# Metadata objects across 8 open projects")); //$NON-NLS-1$
        assertFalse(FailureShape.looksFailed("")); //$NON-NLS-1$
    }

    /**
     * An answer that carries other answers is judged by its own verdict, not by theirs.
     *
     * <p>Measured: <code>get_mcp_history</code> lists recorded calls with the result of each, so a
     * listing holding one failed call was handed to the client with <code>isError</code> and
     * recorded as a failure itself. The same reading evicts an idempotency entry for a call that
     * succeeded, and an evicted entry is what lets a retry mutate twice.
     */
    @Test
    public void anAnswerIsJudgedByItsOwnVerdictAndNotAQuotedOne()
    {
        String listing = "{\"success\":true,\"operation\":\"get_mcp_history\",\"history\":[" //$NON-NLS-1$
            + "{\"tool\":\"launch_debugger\",\"result\":\"{\\\"success\\\":false,\\\"error\\\":\\\"no\\\"}\"}" //$NON-NLS-1$
            + "]}"; //$NON-NLS-1$
        assertFalse(listing, FailureShape.looksFailed(listing));

        String batch = "{\"success\":true,\"batchResults\":[{\"success\":false,\"index\":2}]}"; //$NON-NLS-1$
        assertFalse(batch, FailureShape.looksFailed(batch));

        // The other direction still holds: the answer's own false is the verdict, whatever the
        // rows below it say.
        String refused = "{\"success\":false,\"results\":[{\"success\":true}]}"; //$NON-NLS-1$
        assertTrue(refused, FailureShape.looksFailed(refused));
    }

    @Test
    public void theTopLevelVerdictIsReadFromDepthOneOnly()
    {
        assertEquals(Boolean.TRUE, FailureShape.topLevelSuccess("{\"success\":true}")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, FailureShape.topLevelSuccess("{ \"success\" : false }")); //$NON-NLS-1$
        // A key of that name deeper in the answer is not the answer's verdict.
        assertNull(FailureShape.topLevelSuccess("{\"rows\":[{\"success\":false}]}")); //$NON-NLS-1$
        // And neither is a value that merely spells it.
        assertNull(FailureShape.topLevelSuccess("{\"note\":\"success\"}")); //$NON-NLS-1$
        assertNull(FailureShape.topLevelSuccess("not json at all")); //$NON-NLS-1$
    }

    @Test
    public void theWordErrorInsideAnAnswerIsNotARefusal()
    {
        // Only a leading Error: counts. A result that merely talks about errors - a check
        // description, a search hit, a listing of problems - is a successful answer, and reading it
        // as a failure would mark most of the diagnostics surface as broken.
        assertFalse(FailureShape.looksFailed("# Problems\n\n| Error | Line |\n|---|---|\n")); //$NON-NLS-1$
        assertFalse(FailureShape.looksFailed("The check reports Error: severity for this rule")); //$NON-NLS-1$
    }

    /**
     * The markdown refusal counts, for every tool that refuses in that shape.
     * <p>
     * Measured: the rule knew the plain-text {@code Error:} and not the bold one, so
     * {@code run_yaxunit_tests} and the six other markdown tools were answered to the client without
     * {@code isError} and recorded in the call history as successes. One refusal per tool that
     * writes this shape is asserted here.
     */
    @Test
    public void theMarkdownRefusalCountsForEveryToolThatRefusesThatWay()
    {
        String[] refusals = {
            "**Error:** projectName must be supplied (or provide launchConfigurationName instead)", // run_yaxunit_tests
            "**Error:** Project not found: 'NoSuchProject'", // bookmarks
            "**Error:** No documentation is available for check: bsl-variable-name-invalid", // get_check_description
            "**Error:** invalid FQN: Catalog", // get_metadata_details
            "**Error:** the IMarkerManager service is unavailable", // get_project_errors
            "**Error:** The workspace is not available", // list_projects
            "**Error:** no task list named 'TODO'", // tasks
        };
        for (String refusal : refusals)
        {
            assertTrue(refusal, FailureShape.looksFailed(refusal));
        }
        // Read from the head, so the blank line a refusal may open with is not its undoing.
        assertTrue(FailureShape.looksFailed("\n \n**Error:** nothing to run")); //$NON-NLS-1$
    }

    /**
     * The bold header further down an answer is a row of a report, not the answer's verdict.
     * <p>
     * The metadata reader writes one such line per object it could not resolve while the answer
     * around it is a successful listing, and a module may quote the header as text at all. Reading
     * it anywhere would put both into the history as failed calls.
     */
    @Test
    public void theMarkdownHeaderMidAnswerIsNotARefusal()
    {
        String partial = "# Catalog.X\n\n| Property | Value |\n|---|---|\n" //$NON-NLS-1$
            + "\n**Error:** no such object: Catalog.Y\n"; //$NON-NLS-1$
        assertFalse(partial, FailureShape.looksFailed(partial));
        assertFalse(FailureShape.looksFailed("A refusal opens with **Error:** and a reason")); //$NON-NLS-1$
    }
}
