/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.YamlFrontMatter;

/**
 * The answer of a form write names the base-form attributes it borrowed and the data-path checks
 * that were not performed.
 * <p>
 * A data path on an extension form is exported only when the attribute it starts at belongs to the
 * extension, so the write borrows it. The caller did not ask for that by name, and a caller
 * comparing files otherwise reads the borrowed attribute as someone else's edit - so the names have
 * to reach the answer, and the answer is JSON on every path this class covers.
 * </p>
 * <p>
 * A check the runtime could not ask is of the same kind: the write stands without it, and an answer
 * that stays silent about it reads as a path that passed it. The guard names those checks under
 * {@code dataPathChecksNotPerformed}.
 * </p>
 * <p>
 * Two producers feed both keys. The operations that build their own answer go through
 * {@link EditMetadataTool#formatFormResult}/{@link EditMetadataTool#formatFormResultWithApiTag} with
 * the helper's borrowed names and unperformed checks; the operations routed through {@code edit_form}
 * arrive as front matter and go through {@link FormItemsOps#convertEditFormMarkdownToJson}. Both
 * halves are pinned here, and the front-matter literals are the ones
 * {@code FormExtensionDataPathGuardTest} asserts the writer produces - the writer and the reader are
 * checked apart because the guard test can reach the helper and this one can reach the conversion.
 * </p>
 */
public class TheAnswerNamesWhatWasBorrowedTest
{
    private static final String FORM_FQN = "Catalog.Товары.Form.ФормаЭлемента"; //$NON-NLS-1$

    private static final String OP = "add_form_attribute_column"; //$NON-NLS-1$

    private static JsonObject parse(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonArray adoptedOf(String json)
    {
        JsonObject answer = parse(json);
        assertTrue("the answer must carry adoptedFormAttributes: " + json, //$NON-NLS-1$
            answer.has("adoptedFormAttributes")); //$NON-NLS-1$
        return answer.getAsJsonArray("adoptedFormAttributes"); //$NON-NLS-1$
    }

    private static JsonArray notPerformedOf(String json)
    {
        JsonObject answer = parse(json);
        assertTrue("the answer must carry dataPathChecksNotPerformed: " + json, //$NON-NLS-1$
            answer.has("dataPathChecksNotPerformed")); //$NON-NLS-1$
        return answer.getAsJsonArray("dataPathChecksNotPerformed"); //$NON-NLS-1$
    }

    @Test
    public void aBorrowedAttributeIsNamedInTheAnswerOfAFormOperation()
    {
        JsonArray adopted = adoptedOf(EditMetadataTool.formatFormResultWithApiTag("ok", OP, FORM_FQN, //$NON-NLS-1$
            List.of("Объект", "Курс"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(2, adopted.size());
        assertEquals("Объект", adopted.get(0).getAsString()); //$NON-NLS-1$
        assertEquals("Курс", adopted.get(1).getAsString()); //$NON-NLS-1$
    }

    @Test
    public void aWriteThatBorrowedNothingLeavesTheKeyOut()
    {
        assertFalse("an answer with no borrow must not name one", //$NON-NLS-1$
            parse(EditMetadataTool.formatFormResultWithApiTag("ok", OP, FORM_FQN, List.of())) //$NON-NLS-1$
                .has("adoptedFormAttributes")); //$NON-NLS-1$
        assertFalse("a caller that passes no list gets the same answer", //$NON-NLS-1$
            parse(EditMetadataTool.formatFormResult("ok", OP, FORM_FQN)) //$NON-NLS-1$
                .has("adoptedFormAttributes")); //$NON-NLS-1$
    }

    @Test
    public void aRefusedWriteNamesNothingBecauseItRolledBack()
    {
        String json = EditMetadataTool.formatFormResult("Error: ПолеВвода: путь данных Объект.Курс " //$NON-NLS-1$
            + "не будет выгружен с формой расширения; ничего не записано", OP, FORM_FQN, //$NON-NLS-1$
            List.of("Объект")); //$NON-NLS-1$

        JsonObject answer = parse(json);
        assertFalse("a rolled-back write borrowed nothing, whatever the refusal named", //$NON-NLS-1$
            answer.has("adoptedFormAttributes")); //$NON-NLS-1$
        assertNotNull(answer.get("error")); //$NON-NLS-1$
    }

    @Test
    public void theNamesSurviveTheEditFormFrontMatterConversion()
    {
        String markdown = YamlFrontMatter.create()
            .put("tool", "edit_form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("operation", "add_field") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .put("adoptedFormAttributes", "Объект,Курс") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent("Field 'ПолеВвода' added to form successfully."); //$NON-NLS-1$

        String json = FormItemsOps.convertEditFormMarkdownToJson(markdown, "add_field", FORM_FQN); //$NON-NLS-1$

        JsonArray adopted = adoptedOf(json);
        assertEquals(2, adopted.size());
        assertEquals("Объект", adopted.get(0).getAsString()); //$NON-NLS-1$
        assertEquals("Курс", adopted.get(1).getAsString()); //$NON-NLS-1$
    }

    @Test
    public void aFrontMatterWithoutTheLineLeavesTheKeyOut()
    {
        String markdown = YamlFrontMatter.create()
            .put("tool", "edit_form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("operation", "add_field") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent("Field 'ПолеВвода' added to form successfully."); //$NON-NLS-1$

        assertFalse(parse(FormItemsOps.convertEditFormMarkdownToJson(markdown, "add_field", FORM_FQN)) //$NON-NLS-1$
            .has("adoptedFormAttributes")); //$NON-NLS-1$
    }

    @Test
    public void anEmptyLineNamesNoAttribute()
    {
        String markdown = YamlFrontMatter.create()
            .put("tool", "edit_form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .put("adoptedFormAttributes", "") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent("ok"); //$NON-NLS-1$

        assertFalse("an empty list is reported as nothing borrowed, not as one empty name", //$NON-NLS-1$
            parse(FormItemsOps.convertEditFormMarkdownToJson(markdown, "add_field", FORM_FQN)) //$NON-NLS-1$
                .has("adoptedFormAttributes")); //$NON-NLS-1$
    }

    /**
     * A check the guard could not ask is named in the answer, under its own key.
     * <p>
     * The write went through with the question unanswered. An answer that stays silent about it is
     * read as a path that passed the check, so the names of the checks travel beside the borrowed
     * attributes - and only when there is one.
     * </p>
     */
    @Test
    public void aCheckThatCouldNotBeAskedIsNamedInTheAnswerOfAFormOperation()
    {
        JsonArray notPerformed = notPerformedOf(EditMetadataTool.formatFormResultWithApiTag(
            "ok", OP, FORM_FQN, List.of("Объект"), //$NON-NLS-1$ //$NON-NLS-2$
            List.of("exportOfExtensionForm", "pathResolutionInForm"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(2, notPerformed.size());
        assertEquals("exportOfExtensionForm", notPerformed.get(0).getAsString()); //$NON-NLS-1$
        assertEquals("pathResolutionInForm", notPerformed.get(1).getAsString()); //$NON-NLS-1$
    }

    @Test
    public void aWriteWhereEveryCheckRanLeavesTheChecksKeyOut()
    {
        assertFalse("an answer with no unperformed check must not name one", //$NON-NLS-1$
            parse(EditMetadataTool.formatFormResultWithApiTag("ok", OP, FORM_FQN, List.of(), //$NON-NLS-1$
                List.of())).has("dataPathChecksNotPerformed")); //$NON-NLS-1$
        assertFalse("a caller that passes no list gets the same answer", //$NON-NLS-1$
            parse(EditMetadataTool.formatFormResult("ok", OP, FORM_FQN)) //$NON-NLS-1$
                .has("dataPathChecksNotPerformed")); //$NON-NLS-1$
    }

    @Test
    public void theUnperformedChecksSurviveTheEditFormFrontMatterConversion()
    {
        String markdown = YamlFrontMatter.create()
            .put("tool", "edit_form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("operation", "add_field") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .put("dataPathChecksNotPerformed", "exportOfExtensionForm,pathResolutionInForm") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent("Field 'ПолеВвода' added to form successfully."); //$NON-NLS-1$

        JsonArray notPerformed = notPerformedOf(
            FormItemsOps.convertEditFormMarkdownToJson(markdown, "add_field", FORM_FQN)); //$NON-NLS-1$

        assertEquals(2, notPerformed.size());
        assertEquals("exportOfExtensionForm", notPerformed.get(0).getAsString()); //$NON-NLS-1$
        assertEquals("pathResolutionInForm", notPerformed.get(1).getAsString()); //$NON-NLS-1$
    }

    @Test
    public void aFrontMatterWithoutTheChecksLineLeavesTheKeyOut()
    {
        String markdown = YamlFrontMatter.create()
            .put("tool", "edit_form") //$NON-NLS-1$ //$NON-NLS-2$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .wrapContent("ok"); //$NON-NLS-1$

        assertFalse(parse(FormItemsOps.convertEditFormMarkdownToJson(markdown, "add_field", FORM_FQN)) //$NON-NLS-1$
            .has("dataPathChecksNotPerformed")); //$NON-NLS-1$
    }
}
