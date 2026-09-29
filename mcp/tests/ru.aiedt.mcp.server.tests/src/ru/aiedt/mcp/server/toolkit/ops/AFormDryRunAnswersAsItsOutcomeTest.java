/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A form operation previewed with {@code dryRun=true} is answered by what the preview found: a
 * refusal is a failure, a clean preview is a success.
 * <p>
 * The helper hands the preview back as text and the facade turns that text into the JSON a client
 * reads, so the classification is what decides whether the caller may read the call as applied. A
 * preview whose action refused is written as {@code "Error: dry run - action would FAIL: ..."}: the
 * call wrote nothing and has to arrive as a failure. A preview whose action ran clean is written as
 * {@code "Dry run: form operation previewed..."} and is a success - the operation was performed in
 * the model and rolled back, which is all a preview promises.
 * </p>
 * <p>
 * The two texts are the ones {@code BmFormHelper} returns for those outcomes; producing them needs
 * the form model, so what is pinned here is the entry that builds the final answer from them.
 * </p>
 */
public class AFormDryRunAnswersAsItsOutcomeTest
{
    private static final String OP = "add_form_appearance_rule"; //$NON-NLS-1$

    private static final String FORM_FQN = "Catalog.Валюты.Form.ФормаЭлемента"; //$NON-NLS-1$

    private static final String REFUSAL = "the form has no item ПолеВвода. Nothing was written."; //$NON-NLS-1$

    /** What the helper returns when the previewed action refused. */
    private static final String REFUSED_PREVIEW = "Error: dry run - action would FAIL: " + REFUSAL //$NON-NLS-1$
        + " (rolled back, no changes written to Form.form)."; //$NON-NLS-1$

    /** What the helper returns when the previewed action ran clean. */
    private static final String CLEAN_PREVIEW = "Dry run: form operation previewed inside a BM " //$NON-NLS-1$
        + "transaction and rolled back - no changes written to Form.form. Post-commit EDT validation " //$NON-NLS-1$
        + "is NOT run in a dry run, so a clean preview does not by itself guarantee the real " //$NON-NLS-1$
        + "operation validates clean."; //$NON-NLS-1$

    /**
     * The answer the facade builds from what the helper returned.
     *
     * @param helperResult the text of the form operation
     * @return the JSON answer
     */
    private static JsonObject answerOf(String helperResult)
    {
        return JsonParser.parseString(EditMetadataTool.formatFormResult(helperResult, OP, FORM_FQN))
            .getAsJsonObject();
    }

    /**
     * A preview that met a refusal is answered as a failure, and the refusal reaches the caller.
     */
    @Test
    public void aRefusedPreviewIsAFailure()
    {
        JsonObject answer = answerOf(REFUSED_PREVIEW);

        assertFalse("a preview that refused changed nothing and must not read as applied: " //$NON-NLS-1$
            + answer, answer.get("success").getAsBoolean());
        assertTrue("the refusal itself travels to the caller: " + answer, //$NON-NLS-1$
            answer.get("error").getAsString().contains(REFUSAL));
        assertEquals("the answer names the operation that refused", OP, //$NON-NLS-1$
            answer.get("operation").getAsString());
    }

    /**
     * A preview that ran clean is answered as a success, with the preview text as its message.
     */
    @Test
    public void aCleanPreviewIsASuccess()
    {
        JsonObject answer = answerOf(CLEAN_PREVIEW);

        assertTrue("the preview reached the model and rolled back, so it succeeded: " + answer, //$NON-NLS-1$
            answer.get("success").getAsBoolean());
        assertEquals("and says what it was", CLEAN_PREVIEW, //$NON-NLS-1$
            answer.get("message").getAsString());
        assertFalse("a success carries no error", answer.has("error")); //$NON-NLS-1$
    }
}
