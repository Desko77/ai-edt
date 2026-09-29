/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.ClientDialogReader;

/**
 * A Vanessa run that stops at its deadline names the 1C window that was holding it, and only
 * suggests a longer deadline when no such window was found.
 */
public class TheTimeoutNamesTheWindowHoldingTheRunTest
{
    private static final String TITLE =
        "\u0414\u043e\u0441\u0442\u0443\u043f \u043a \u0438\u043d\u0444\u043e\u0440\u043c\u0430\u0446\u0438\u043e\u043d\u043d\u043e\u0439 \u0431\u0430\u0437\u0435"; //$NON-NLS-1$

    private static final String TEXT =
        "\u041d\u0435\u0432\u0435\u0440\u043d\u043e \u0443\u043a\u0430\u0437\u0430\u043d \u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u0435\u043b\u044c \u0438\u043b\u0438 \u043f\u0430\u0440\u043e\u043b\u044c"; //$NON-NLS-1$

    /** The title of the frame a question box is drawn over. */
    private static final String BASE_TITLE =
        "1\u0421:\u041f\u0440\u0435\u0434\u043f\u0440\u0438\u044f\u0442\u0438\u0435 8.3 (8.3.27.2214)"; //$NON-NLS-1$

    /** The question a client asks when the infobase is not there, as a pane carries it. */
    private static final String QUESTION =
        "\u041d\u0435 \u043e\u0431\u043d\u0430\u0440\u0443\u0436\u0435\u043d\u0430 \u0438\u043d\u0444\u043e\u0440\u043c\u0430\u0446\u0438\u043e\u043d\u043d\u0430\u044f \u0431\u0430\u0437\u0430!"; //$NON-NLS-1$

    /** The line the question continues on inside the same pane name. */
    private static final String FOLLOW_UP =
        "\u0421\u043e\u0437\u0434\u0430\u0442\u044c \u043d\u043e\u0432\u0443\u044e?"; //$NON-NLS-1$

    /**
     * The catalogue description tells the caller that a timeout held by a window comes back as
     * {@code blockingWindows} and does not ask for more time.
     */
    @Test
    public void theDescriptionNamesTheWindowsATimeoutReturns()
    {
        String description = new VanessaTool().getDescription();
        assertTrue(description, description.contains("blockingWindows")); //$NON-NLS-1$
        assertTrue(description, description.contains("imageFile")); //$NON-NLS-1$
        assertTrue(description, description.contains("does not ask to raise timeoutSeconds")); //$NON-NLS-1$
    }

    /**
     * With a window, the error names that window's title and its first text, and it does not
     * suggest raising the deadline. The windows themselves travel beside the sentence.
     */
    @Test
    public void aHoldingWindowIsNamedAndTheDeadlineIsNot()
    {
        JsonObject answer = answer(300, false, null, "", holding()); //$NON-NLS-1$
        String error = answer.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains(TITLE));
        assertTrue(error, error.contains(TEXT));
        assertFalse(error, error.contains("timeoutSeconds")); //$NON-NLS-1$
        assertFalse(error, error.contains("second line")); //$NON-NLS-1$

        JsonArray windows = answer.getAsJsonArray("blockingWindows"); //$NON-NLS-1$
        assertEquals(2, windows.size());
        JsonObject dialog = windows.get(1).getAsJsonObject();
        assertEquals(9, dialog.get("pid").getAsInt()); //$NON-NLS-1$
        assertEquals(TITLE, dialog.get("title").getAsString()); //$NON-NLS-1$
        assertEquals(TEXT, dialog.getAsJsonArray("texts").get(0).getAsString()); //$NON-NLS-1$
        assertEquals("\u0412\u043e\u0439\u0442\u0438", dialog.getAsJsonArray("buttons").get(0).getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("\u0417\u0430\u043a\u0440\u044b\u0442\u044c", dialog.getAsJsonArray("buttons").get(1).getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(dialog.get("modal").getAsBoolean()); //$NON-NLS-1$
        assertEquals("C:\\shots\\login.png", dialog.get("imageFile").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(answer.has("blockingWindowsError")); //$NON-NLS-1$
    }

    /**
     * {@code keepOpen} still says the client was left open, and a window on top of that still
     * replaces the advice to wait longer.
     */
    @Test
    public void aKeptOpenClientThatAlsoShowsAWindowDoesNotAskForMoreTime()
    {
        String error = answer(300, true, null, "", holding()).get("error").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.contains("keepOpen=false")); //$NON-NLS-1$
        assertTrue(error, error.contains(TITLE));
        assertTrue(error, error.contains(TEXT));
        assertFalse(error, error.contains("timeoutSeconds")); //$NON-NLS-1$
    }

    /**
     * With no window the sentence is the one the timeout gave before windows were read, including
     * the advice to raise the deadline.
     */
    @Test
    public void withoutAWindowTheTimeoutReadsAsBefore()
    {
        JsonObject answer = answer(300, false, "EDT had the infobase", "hello", //$NON-NLS-1$ //$NON-NLS-2$
            ClientDialogReader.Outcome.none());
        assertEquals("Vanessa run timed out after 300s. Raise timeoutSeconds, or the run may be " //$NON-NLS-1$
            + "stuck on a 1C login/update dialog. EDT had the infobase. Output tail: hello", //$NON-NLS-1$
            answer.get("error").getAsString()); //$NON-NLS-1$
        assertEquals(0, answer.getAsJsonArray("blockingWindows").size()); //$NON-NLS-1$
        assertFalse(answer.has("blockingWindowsError")); //$NON-NLS-1$
    }

    /**
     * A client left open on purpose still says so, and still does not blame a dialog that was not
     * found.
     */
    @Test
    public void aKeptOpenClientWithoutAWindowReadsAsBefore()
    {
        String error = answer(300, true, null, "", ClientDialogReader.Outcome.none()) //$NON-NLS-1$
            .get("error").getAsString(); //$NON-NLS-1$
        assertEquals("Vanessa run timed out after 300s (keepOpen=true keeps 1C open, so it never " //$NON-NLS-1$
            + "exits - set keepOpen=false). ", error); //$NON-NLS-1$
        assertFalse(error.contains("timeoutSeconds")); //$NON-NLS-1$
    }

    /**
     * A reader that failed leaves the old sentence in place and reports why the windows are
     * missing.
     */
    @Test
    public void aReaderFailureKeepsTheOldSentenceAndNamesTheReason()
    {
        JsonObject answer = answer(90, false, null, "", //$NON-NLS-1$
            ClientDialogReader.Outcome.failed("The dialog reader timed out.")); //$NON-NLS-1$
        assertTrue(answer.get("error").getAsString().contains("Raise timeoutSeconds")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("The dialog reader timed out.", //$NON-NLS-1$
            answer.get("blockingWindowsError").getAsString()); //$NON-NLS-1$
        assertEquals(0, answer.getAsJsonArray("blockingWindows").size()); //$NON-NLS-1$
    }

    /**
     * A question box reports its message as the name of a pane rather than as a text element, and
     * that message arrives with its line breaks. The timeout names it: the caller reads what the
     * window is asking instead of a frame title, and the whole message travels in the window.
     */
    @Test
    public void aPaneMessageWithLineBreaksIsWhatTheTimeoutNames()
    {
        String question = QUESTION + "\n" + FOLLOW_UP;
        ClientDialogReader.Outcome read = ClientDialogReader.parse("{\"windows\":[{\"pid\":9," //$NON-NLS-1$
            + "\"className\":\"V8NewLocalFrameBaseWnd\",\"title\":\"" + BASE_TITLE + "\"," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"texts\":[\"" + QUESTION + "\\n" + FOLLOW_UP + "\"]," //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "\"buttons\":[\"Да\",\"Нет\"],\"modal\":true}]}"); //$NON-NLS-1$
        assertNull(read.error());
        assertEquals(question, read.windows().get(0).texts().get(0));

        String error = answer(300, false, null, "hello", read).get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains(BASE_TITLE));
        assertTrue("the question is what the caller needs", error.contains(QUESTION)); //$NON-NLS-1$
        assertTrue(error, error.contains(question));
        assertFalse(error, error.contains("timeoutSeconds")); //$NON-NLS-1$
    }

    /**
     * The timeout answer for one read.
     *
     * @param timeoutSec how long the run was waited for
     * @param keepOpen whether the client was left open on purpose
     * @param heldBack why the infobase was not released
     * @param output what the client printed
     * @param dialogs the windows read before the client was stopped
     * @return the answer object
     */
    private static JsonObject answer(int timeoutSec, boolean keepOpen, String heldBack, String output,
        ClientDialogReader.Outcome dialogs)
    {
        return JsonParser.parseString(
            VanessaTool.timeoutAnswer(timeoutSec, keepOpen, heldBack, output, dialogs).toJson())
            .getAsJsonObject();
    }

    /**
     * A main window under the login dialog, as the reader lists them top-down: the dialog is the
     * later, modal one.
     *
     * @return the read
     */
    private static ClientDialogReader.Outcome holding()
    {
        return ClientDialogReader.parse("{\"windows\":[" //$NON-NLS-1$
            + "{\"pid\":9,\"className\":\"V8TopLevelFrameSDI\",\"title\":\"1\u0421:\u041f\u0440\u0435\u0434\u043f\u0440\u0438\u044f\u0442\u0438\u0435\"," //$NON-NLS-1$
            + "\"texts\":[\"main\"],\"buttons\":[],\"modal\":false}," //$NON-NLS-1$
            + "{\"pid\":9,\"className\":\"V8TopLevelFrameSDIsec\",\"title\":\"" + TITLE + "\"," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"texts\":[\"" + TEXT + "\",\"second line\"]," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"buttons\":[\"\u0412\u043e\u0439\u0442\u0438\",\"\u0417\u0430\u043a\u0440\u044b\u0442\u044c\"],\"modal\":true," //$NON-NLS-1$
            + "\"imageFile\":\"C:\\\\shots\\\\login.png\"}" //$NON-NLS-1$
            + "]}"); //$NON-NLS-1$
    }
}
