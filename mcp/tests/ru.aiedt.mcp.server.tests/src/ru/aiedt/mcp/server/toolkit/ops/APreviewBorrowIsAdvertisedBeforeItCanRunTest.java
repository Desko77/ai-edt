/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A borrow preview is offered before it can run: the schema names the operation, the catalogue
 * lists it, and a call reaches the facade's own handler instead of the unknown-operation branch.
 * <p>
 * The preview is read-only, so it has no standalone tool: {@code routesTo} answers
 * <code>null</code> for it and the call is dispatched here. That is the routing half. The schema
 * and the catalogue are the two places a caller learns the name from, and a name in one and not
 * the other is a word the caller can read but the facade refuses.
 * </p>
 * <p>
 * The help answer carries this operation's own parameters rather than the union the facade
 * declares. A preview reads {@code includeChildren}, so the sentence that tells the other
 * operations the flag does not apply to them must not be repeated for this one.
 * </p>
 */
public class APreviewBorrowIsAdvertisedBeforeItCanRunTest
{
    private static final String PREVIEW = "preview_borrow"; //$NON-NLS-1$

    private static JsonObject json(String answer)
    {
        return JsonParser.parseString(answer).getAsJsonObject();
    }

    private static String help(String topic)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        if (topic != null)
        {
            params.put("topic", topic); //$NON-NLS-1$
        }
        return new ExtensionWorkshopTool().execute(params);
    }

    /** The operation vocabulary in the schema carries the preview, so a caller can build the call. */
    @Test
    public void theSchemaNamesPreviewBorrowAmongTheOperations()
    {
        JsonObject schema = json(new ExtensionWorkshopTool().getInputSchema());
        JsonObject operation = schema.getAsJsonObject("properties") //$NON-NLS-1$
            .getAsJsonObject("operation"); //$NON-NLS-1$

        assertTrue(operation.toString(), operation.get("description").getAsString() //$NON-NLS-1$
            .contains(PREVIEW));
    }

    /** The no-topic help is the catalogue a caller reads before choosing an operation. */
    @Test
    public void theCatalogueListsPreviewBorrow()
    {
        JsonObject answer = json(help(null));

        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.toString(), answer.get("help").getAsString().contains(PREVIEW)); //$NON-NLS-1$
    }

    /** The dispatch vocabulary refuses an unknown operation by listing the known ones. */
    @Test
    public void theUnknownOperationRefusalListsPreviewBorrowToo()
    {
        Map<String, String> call = new LinkedHashMap<>();
        call.put("operation", "definitely_not_a_preview_probe"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject answer = json(new ExtensionWorkshopTool().execute(call));

        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.toString(), answer.get("error").getAsString().contains(PREVIEW)); //$NON-NLS-1$
    }

    /** A topic naming the operation answers for it, with the parameters that operation reads. */
    @Test
    public void thePreviewTopicAnswersWithItsOwnParameters()
    {
        JsonObject answer = json(help(PREVIEW));

        assertTrue(answer.toString(), answer.get("success").getAsBoolean()); //$NON-NLS-1$
        String text = answer.get("text").getAsString(); //$NON-NLS-1$
        assertTrue(text, text.contains(PREVIEW));
        assertTrue("the preview reads the object address: " + text, //$NON-NLS-1$
            text.contains("objectFqn")); //$NON-NLS-1$
        assertTrue("the preview borrows into a named extension: " + text, //$NON-NLS-1$
            text.contains("projectName")); //$NON-NLS-1$
        assertTrue("the preview reads includeChildren: " + text, //$NON-NLS-1$
            text.contains("includeChildren")); //$NON-NLS-1$
        assertFalse("a preview reads includeChildren, so it must not repeat the sentence that " //$NON-NLS-1$
            + "says the operation does not: " + text, text.contains("does not read it")); //$NON-NLS-1$
    }

    /**
     * The preview runs in this facade. Nothing shapes a standalone route for it, and the call does
     * not fall through to either the unknown-operation branch or the dispatcher's default one.
     */
    @Test
    public void thePreviewIsDispatchedHereAndNotRoutedToAStandaloneTool()
    {
        Map<String, String> call = new LinkedHashMap<>();
        call.put("operation", PREVIEW);
        call.put("projectName", "NoSuchProjectForABorrowPreview"); //$NON-NLS-1$ //$NON-NLS-2$
        call.put("objectFqn", "Catalog.Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        ExtensionWorkshopTool tool = new ExtensionWorkshopTool();

        assertNull("the preview has no standalone tool of its own to route to", //$NON-NLS-1$
            tool.routesTo(call));

        String answer = tool.execute(call);
        assertFalse("the dispatcher has no case for the preview: " + answer, //$NON-NLS-1$
            answer.contains("Unknown operation")); //$NON-NLS-1$
        assertFalse("the dispatcher's default branch answered the preview: " + answer, //$NON-NLS-1$
            answer.contains("Unhandled op"));
        JsonObject refusal = json(answer);
        assertFalse("a preview of a project that is not there must refuse, not report a plan: " //$NON-NLS-1$
            + answer, refusal.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the refusal must say why nothing was previewed: " + answer, //$NON-NLS-1$
            refusal.has("error") && !refusal.get("error").getAsString().isBlank()); //$NON-NLS-1$
    }
}
