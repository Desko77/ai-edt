/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.BmDcsHelper;

/**
 * A {@code dryRun=true} call answers that it was a preview, and a write answers nothing about one.
 * <p>
 * The preview was indistinguishable from a write: the transaction was rolled back and the schema on
 * disk kept what it held, while the answer carried {@code success}, {@code message} and a schema
 * name exactly as an applied write does. A caller that read the answer as "the change is in" then
 * repeated the call for real, or, worse, reported the change as done.
 * </p>
 * <p>
 * The flag is read off the write result, which the routes that reach a schema fill in from the
 * argument the caller sent; the answer of a dynamic list is built the same way. A preview that a
 * guard refused carries the flag too - nothing was written there either, and that is what the
 * caller has to know before repeating it.
 * </p>
 */
public class APreviewSaysItIsAPreviewTest
{
    private static final String OP = "add_appearance"; //$NON-NLS-1$

    private final DcsWorkshopTool tool = new DcsWorkshopTool();

    /**
     * A write result, applied or previewed, with a message and a schema name as a real one has.
     *
     * @param ok whether the write succeeded
     * @param dryRun whether the call was a preview
     * @return the result
     */
    private static BmDcsHelper.Result aResult(boolean ok, boolean dryRun)
    {
        BmDcsHelper.Result r = new BmDcsHelper.Result();
        r.ok = ok;
        r.dryRun = dryRun;
        r.schemaFqn = "Report.МойОтчет.Template.ОсновнаяСхемаКомпоновкиДанных.Template"; //$NON-NLS-1$
        r.message = ok ? "conditional appearance added" : null; //$NON-NLS-1$
        r.error = ok ? null : "the filter field is not on this variant"; //$NON-NLS-1$
        return r;
    }

    /**
     * The answer to a preview reports the outcome a write reports and names itself a preview.
     */
    @Test
    public void aPreviewSaysItIsAPreview()
    {
        JsonObject json = JsonParser
            .parseString(tool.formatResultForTest(aResult(true, true), OP))
            .getAsJsonObject();

        assertTrue("a preview reaches the model read-only, so it succeeds", //$NON-NLS-1$
            json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("and says the change was not applied: " + json, //$NON-NLS-1$
            json.get("dryRun").getAsBoolean()); //$NON-NLS-1$
        assertEquals("the operation is named as on any other answer", OP, //$NON-NLS-1$
            json.get("operation").getAsString()); //$NON-NLS-1$
        assertFalse("a preview writes no file, so there is nothing to report about one", //$NON-NLS-1$
            json.has("directSavePath")); //$NON-NLS-1$
    }

    /**
     * The answer to a write says nothing about a preview: the field is absent, not false.
     */
    @Test
    public void aWriteSaysNothingAboutAPreview()
    {
        JsonObject json = JsonParser
            .parseString(tool.formatResultForTest(aResult(true, false), OP))
            .getAsJsonObject();

        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse("a write is not a preview and must not be answered as one: " + json, //$NON-NLS-1$
            json.has("dryRun")); //$NON-NLS-1$
    }

    /**
     * A preview that was refused is still a preview: the refusal reports what the write ran into,
     * and the caller has to know the refusal came from a call that changed nothing.
     */
    @Test
    public void aRefusedPreviewIsStillAPreview()
    {
        JsonObject json = JsonParser
            .parseString(tool.formatResultForTest(aResult(false, true), OP))
            .getAsJsonObject();

        assertFalse(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the refusal still says what went wrong: " + json, //$NON-NLS-1$
            json.get("error").getAsString().contains("not on this variant")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and that the call was a preview all the same: " + json, //$NON-NLS-1$
            json.get("dryRun").getAsBoolean()); //$NON-NLS-1$
    }

    /**
     * A write that was refused says nothing about a preview either.
     */
    @Test
    public void aRefusedWriteSaysNothingAboutAPreview()
    {
        JsonObject json = JsonParser
            .parseString(tool.formatResultForTest(aResult(false, false), OP))
            .getAsJsonObject();

        assertFalse(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse("a refusal of a real call is not a preview: " + json, json.has("dryRun")); //$NON-NLS-1$
    }

    /**
     * The schema write route marks its result with the flag it was given, before it can return for
     * any other reason.
     * <p>
     * A result the route returns with the flag left at its default is a preview answered as a
     * write, whatever the reason the route stopped at. The call here stops at the arguments, which
     * is the first guard - but the flag is set before it, so a later edit that moves the assignment
     * below the guards fails here rather than on a stand.
     * </p>
     */
    @Test
    public void theSchemaWriteRouteCarriesTheDryRunItWasGiven()
    {
        BmDcsHelper.Result previewed =
            BmDcsHelper.executeWriteOnSchema(null, null, null, true, (tx, schema) -> null); //$NON-NLS-1$
        assertTrue("the route knows the call was a preview", previewed.dryRun); //$NON-NLS-1$
        assertTrue("and the answer it produces says so", //$NON-NLS-1$
            JsonParser.parseString(tool.formatResultForTest(previewed, OP)).getAsJsonObject()
                .get("dryRun").getAsBoolean()); //$NON-NLS-1$

        BmDcsHelper.Result applied =
            BmDcsHelper.executeWriteOnSchema(null, null, null, false, (tx, schema) -> null); //$NON-NLS-1$
        assertFalse("a real call is not a preview", applied.dryRun); //$NON-NLS-1$
        assertFalse("and is not answered as one", //$NON-NLS-1$
            JsonParser.parseString(tool.formatResultForTest(applied, OP)).getAsJsonObject()
                .has("dryRun")); //$NON-NLS-1$
    }

    /**
     * A settings write on a dynamic list is answered the same way: the preview names itself, the
     * write says nothing about one, and the completeness warnings stay where they were.
     */
    @Test
    public void aListWriteIsAnsweredTheSameWay()
    {
        JsonObject previewed = JsonParser.parseString(DcsWorkshopTool.dynamicListAnswer(OP,
            "Form.Форма", "Список", "conditional appearance added", true, List.of(), null)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .getAsJsonObject();

        assertTrue("the settings on the form keep what they held, and the answer says so", //$NON-NLS-1$
            previewed.get("dryRun").getAsBoolean()); //$NON-NLS-1$
        assertEquals("on the attribute the call named", "Список", //$NON-NLS-1$ //$NON-NLS-2$
            previewed.get("attributeName").getAsString()); //$NON-NLS-1$

        JsonObject applied = JsonParser.parseString(DcsWorkshopTool.dynamicListAnswer(OP,
            "Form.Форма", "Список", "conditional appearance added", false, List.of(), null)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .getAsJsonObject();

        assertTrue(applied.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse("a list write is not a preview: " + applied, applied.has("dryRun")); //$NON-NLS-1$
        assertFalse("settings that reached the disk carry no warning: " + applied, //$NON-NLS-1$
            applied.has("persistWarning")); //$NON-NLS-1$
    }

    /**
     * A list write whose settings did not reach the disk says so, and stays a write that landed in
     * the model.
     */
    @Test
    public void aListWriteNotOnDiskSaysSo()
    {
        JsonObject answer = JsonParser.parseString(DcsWorkshopTool.dynamicListAnswer(OP,
            "Form.Форма", "Список", "order added", false, List.of(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Form.Форма.Attributes.Список.ExtInfo.ListSettings is changed in the model and not on disk")) //$NON-NLS-1$
            .getAsJsonObject();

        assertTrue(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.toString(), answer.get("persistWarning").getAsString().contains("ListSettings")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A refused list preview carries the flag, and the warnings of the completeness check are
     * dropped from the refusal.
     */
    @Test
    public void aRefusedListPreviewCarriesTheFlagAndNoWarnings()
    {
        JsonObject refused = JsonParser.parseString(DcsWorkshopTool.dynamicListAnswer(OP,
            "Form.Форма", "Список", "Error: no such attribute", true, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            List.of(Map.of("kind", "emptyFilterValue")), null)) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject();

        assertFalse(refused.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the refusal is a refusal of a preview: " + refused, //$NON-NLS-1$
            refused.get("dryRun").getAsBoolean()); //$NON-NLS-1$
        assertFalse("and a write that did not reach the settings reports none of what it found " //$NON-NLS-1$
            + "in them: " + refused, refused.has("settingsWarnings")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
