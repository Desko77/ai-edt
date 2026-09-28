/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.YamlFrontMatter;

/**
 * The answer of {@code edit_metadata operation=add_radio_button} is JSON, like every other answer
 * of that tool.
 * <p>
 * The operation is served by {@link EditFormTool}, whose response type is MARKDOWN: the markdown
 * report it returns used to leave the route as the body of a JSON answer, so the protocol handler
 * failed to parse it and the caller got {@code -32603 MalformedJsonException} although the element
 * had already been written. The route now passes its response through
 * {@link FormItemsOps#convertEditFormMarkdownToJson} with its own operation name, the way the
 * neighbouring {@code add_field} route does.
 * </p>
 * <p>
 * The route itself is reached here through its pre-dispatch refusal, the only branch of
 * {@link EditFormTool} that answers without the Eclipse workbench - the same branch
 * {@code EditFormToolTest} covers. The success branch is pinned on the front matter
 * {@code EditFormTool} writes for a written element, which the conversion reads back.
 * </p>
 */
public class ARadioButtonWriteAnswersJsonTest
{
    private static final String FORM_FQN = "Catalog.Товары.Form.ФормаЭлемента"; //$NON-NLS-1$

    private static JsonObject parse(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * The write the route cannot start answers JSON, not the markdown of {@code edit_form}.
     * <p>
     * The call carries no project, so {@link EditFormTool} refuses before it touches the
     * workbench - and the refusal is what reaches the caller. The route is the producer of the
     * answer here, so this also pins the operation name it reports.
     * </p>
     */
    @Test
    public void aWriteThatCannotStartAnswersJsonUnderItsOwnOperationName()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("formFqn", FORM_FQN); //$NON-NLS-1$
        params.put("name", "ПолеВыбора"); //$NON-NLS-1$

        String answer = new FormItemsOps().delegateToEditFormAsRadioButton(params);

        JsonObject json = parse(answer);
        assertFalse("the markdown of edit_form must not reach the caller", //$NON-NLS-1$
            answer.startsWith("---")); //$NON-NLS-1$
        assertFalse(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("add_radio_button", json.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("error").getAsString().contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An element written through the radio-button route is reported as JSON carrying the form it
     * was written to.
     * <p>
     * The front matter is the one {@link EditFormTool} writes for a written field, with
     * {@code elementType=RadioButton} the route sets before delegating.
     * </p>
     */
    @Test
    public void aWrittenRadioButtonIsReportedAsJson()
    {
        String markdown = YamlFrontMatter.create()
            .put("tool", "edit_form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", "MyProject") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formFqn", FORM_FQN) //$NON-NLS-1$
            .put("operation", "add_field") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent("Operation completed successfully.\n" //$NON-NLS-1$
                + "- Operation: add_field\n" //$NON-NLS-1$
                + "- Name: ПолеВыбора\n" //$NON-NLS-1$
                + "- Type: RadioButton\n" //$NON-NLS-1$
                + "- Parent: root\n"); //$NON-NLS-1$

        JsonObject json = parse(
            FormItemsOps.convertEditFormMarkdownToJson(markdown, "add_radio_button", FORM_FQN)); //$NON-NLS-1$

        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("add_radio_button", json.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(FORM_FQN, json.get("formFqn").getAsString()); //$NON-NLS-1$
        assertTrue(json.get("message").getAsString().contains("RadioButton")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A refused write through the radio-button route answers a JSON refusal naming the operation,
     * not a markdown report.
     */
    @Test
    public void aRefusedRadioButtonWriteIsReportedAsJson()
    {
        String markdown = YamlFrontMatter.create()
            .put("tool", "edit_form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "error") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent("formFqn is required. Example: 'Catalog.Products.Form.ItemForm.Form'"); //$NON-NLS-1$

        JsonObject json = parse(
            FormItemsOps.convertEditFormMarkdownToJson(markdown, "add_radio_button", FORM_FQN)); //$NON-NLS-1$

        assertFalse(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("add_radio_button", json.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("error").getAsString().contains("formFqn is required")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
