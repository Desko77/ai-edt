/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.UiSync;
import ru.aiedt.mcp.server.support.YamlFrontMatter;

/**
 * A busy-UI refusal keeps its machine-readable tag on both routes that can carry it.
 * <p>
 * {@code edit_form} catches the condition inside its own {@code execute}, so the router-level
 * tagging never fires for it; the tool has to tag the answer itself, and the facade conversion
 * ({@code edit_metadata} form operations) has to carry the tag into its JSON. Without the tag a
 * retryable busy condition reads as a failed write, and a caller that retries it does so by
 * accident rather than by reading the answer.
 * </p>
 */
public class ABusyAnswerKeepsItsTagTest
{
    private static final String FORM_FQN = "Catalog.Товары.Form.ФормаЭлемента"; //$NON-NLS-1$

    /**
     * The tool's own answer tags the condition beside its status and message.
     */
    @Test
    public void theToolsOwnAnswerNamesTheTag()
    {
        String answer = EditFormTool.uiBusyAnswer(new UiSync.UiBusyException(
            "The EDT UI thread did not respond within 60000 ms (it is busy, or a modal dialog is open); " //$NON-NLS-1$
                + "nothing was changed")); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: error")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("tag: uiBusy")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("nothing was changed")); //$NON-NLS-1$
    }

    /**
     * A write whose wait broke after the work had started answers {@code outcomeUnknown}, not the
     * retryable {@code uiBusy}, and sends the caller to read the form before any retry.
     */
    @Test
    public void aStartedWriteInterruptedAnswersOutcomeUnknown()
    {
        String answer = EditFormTool.outcomeUnknownAnswer(new UiSync.UiOutcomeUnknownException(
            "Interrupted while the work was already running on the EDT UI thread; it may still apply")); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: error")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("tag: outcomeUnknown")); //$NON-NLS-1$
        assertFalse("a retryable tag here would invite a second write racing the first", //$NON-NLS-1$
            answer.contains("tag: uiBusy")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("get_form_structure")); //$NON-NLS-1$
    }

    /**
     * The facade conversion carries the tag of a tagged refusal into its JSON error, the way it
     * already carries the warning of a successful write.
     */
    @Test
    public void theFacadeConversionCarriesTheTagIntoItsJsonError()
    {
        String markdown = YamlFrontMatter.create()
            .put("tool", "edit_form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("operation", "add_button") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "error") //$NON-NLS-1$ //$NON-NLS-2$
            .put("tag", "uiBusy") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent("The EDT UI thread did not respond within 60000 ms"); //$NON-NLS-1$

        String json = FormItemsOps.convertEditFormMarkdownToJson(markdown, "add_button", FORM_FQN); //$NON-NLS-1$

        JsonObject answer = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the tag has to survive the conversion: " + json, //$NON-NLS-1$
            "uiBusy".equals(answer.get("tag").getAsString())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An untagged refusal stays untagged: the key belongs to answers that carry a condition, not
     * to every error the conversion sees.
     */
    @Test
    public void anUntaggedRefusalGainsNoTag()
    {
        String markdown = YamlFrontMatter.create()
            .put("tool", "edit_form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "error") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent("formFqn is required."); //$NON-NLS-1$

        String json = FormItemsOps.convertEditFormMarkdownToJson(markdown, "add_button", FORM_FQN); //$NON-NLS-1$

        assertFalse(json, json.contains("\"tag\"")); //$NON-NLS-1$
    }
}
