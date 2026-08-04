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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Covers the redaction pass that stands between a customer's data and the model.
 * <p>
 * Two failures matter here and they pull in opposite directions. Letting a real identifier through
 * is a leak. Masking a number that only looks like one corrupts the answer - an eleven-digit
 * document code turned into {@code [PHONE]} is a bug report nobody can act on. Every identifier that
 * carries a checksum is therefore only masked when the checksum holds, and the tests below pin both
 * sides of that line with values whose validity is arithmetic, not opinion.
 * </p>
 */
public class SensitiveTextMaskerTest
{
    // Checksum-valid samples. INN 7707083893 and SNILS 123-456-789 64 satisfy their own check
    // digits; 4111111111111111 is the standard Luhn-valid test card.
    private static final String VALID_INN = "7707083893"; //$NON-NLS-1$
    private static final String VALID_SNILS = "123-456-789 64"; //$NON-NLS-1$
    private static final String VALID_CARD = "4111111111111111"; //$NON-NLS-1$
    // Ten digits that fail the INN check digit, so they are an ordinary number.
    private static final String NOT_AN_INN = "1234567890"; //$NON-NLS-1$

    @Test
    public void nullAndEmptyPassThrough()
    {
        assertNull(SensitiveTextMasker.redact(null));
        assertEquals("", SensitiveTextMasker.redact("")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(SensitiveTextMasker.redactJson(null));
        assertEquals("", SensitiveTextMasker.redactJson("")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void textWithoutIdentifiersIsReturnedUnchanged()
    {
        String text = "Procedure Post() Export // posts the document"; //$NON-NLS-1$
        assertEquals(text, SensitiveTextMasker.redact(text));
    }

    @Test
    public void anEmailIsMasked()
    {
        String masked = SensitiveTextMasker.redact("write to ivan.petrov@example.com today"); //$NON-NLS-1$
        assertTrue(masked, masked.contains("[EMAIL]")); //$NON-NLS-1$
        assertFalse(masked, masked.contains("example.com")); //$NON-NLS-1$
    }

    @Test
    public void aPhoneNumberIsMasked()
    {
        String masked = SensitiveTextMasker.redact("call +7 495 123-45-67 before noon"); //$NON-NLS-1$
        assertTrue(masked, masked.contains("[PHONE]")); //$NON-NLS-1$
        assertFalse(masked, masked.contains("123-45-67")); //$NON-NLS-1$
    }

    @Test
    public void aChecksumValidInnIsMaskedDownToItsLastTwoDigits()
    {
        String masked = SensitiveTextMasker.redact("ИНН " + VALID_INN); //$NON-NLS-1$
        assertTrue(masked, masked.contains("[INN")); //$NON-NLS-1$
        assertFalse("the identifier itself must not survive", masked.contains(VALID_INN)); //$NON-NLS-1$
        assertTrue("the tail is kept so a human can still match the record", //$NON-NLS-1$
            masked.contains("93")); //$NON-NLS-1$
    }

    @Test
    public void tenDigitsThatFailTheCheckDigitAreLeftAlone()
    {
        // The counterweight to the test above: masking every ten-digit run would eat order numbers,
        // line counts and ids that carry no personal data at all.
        assertEquals("code " + NOT_AN_INN, //$NON-NLS-1$
            SensitiveTextMasker.redact("code " + NOT_AN_INN)); //$NON-NLS-1$
    }

    @Test
    public void aLuhnValidCardIsMaskedToItsLastFour()
    {
        String masked = SensitiveTextMasker.redact("card " + VALID_CARD); //$NON-NLS-1$
        assertTrue(masked, masked.contains("[CARD")); //$NON-NLS-1$
        assertFalse(masked, masked.contains(VALID_CARD));
    }

    @Test
    public void aCardFailingLuhnIsLeftAlone()
    {
        String sixteenDigits = "1234567812345678"; //$NON-NLS-1$
        assertEquals("ref " + sixteenDigits, //$NON-NLS-1$
            SensitiveTextMasker.redact("ref " + sixteenDigits)); //$NON-NLS-1$
    }

    @Test
    public void aChecksumValidSnilsIsMasked()
    {
        String masked = SensitiveTextMasker.redact("СНИЛС " + VALID_SNILS); //$NON-NLS-1$
        assertTrue(masked, masked.contains("[SNILS")); //$NON-NLS-1$
        assertFalse(masked, masked.contains("123-456-789")); //$NON-NLS-1$
    }

    @Test
    public void aPassportNeedsItsSeparatorToBeRecognized()
    {
        String masked = SensitiveTextMasker.redact("паспорт 4509 123456"); //$NON-NLS-1$
        assertTrue(masked, masked.contains("[PASSPORT")); //$NON-NLS-1$
        // Without the separator the same digits are a plain ten-digit number, and the INN check
        // digit decides. 4509123456 fails it, so nothing is masked.
        assertEquals("паспорт 4509123456", //$NON-NLS-1$
            SensitiveTextMasker.redact("паспорт 4509123456")); //$NON-NLS-1$
    }

    @Test
    public void jsonKeepsItsKeysNumbersAndShape()
    {
        String json = "{\"email\":\"ivan@example.com\",\"count\":42,\"ok\":true,\"nested\":" //$NON-NLS-1$
            + "{\"phone\":\"+7 495 123-45-67\"},\"list\":[\"a@b.co\",7]}"; //$NON-NLS-1$

        JsonObject redacted = JsonParser.parseString(SensitiveTextMasker.redactJson(json))
            .getAsJsonObject();

        assertTrue("string values are redacted", //$NON-NLS-1$
            redacted.get("email").getAsString().contains("[EMAIL]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("numbers are never touched", 42, redacted.get("count").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("booleans are never touched", redacted.get("ok").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("nesting is walked", redacted.getAsJsonObject("nested") //$NON-NLS-1$ //$NON-NLS-2$
            .get("phone").getAsString().contains("[PHONE]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("array strings are walked", redacted.getAsJsonArray("list").get(0) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsString().contains("[EMAIL]")); //$NON-NLS-1$
        assertEquals("array numbers survive", 7, //$NON-NLS-1$
            redacted.getAsJsonArray("list").get(1).getAsInt()); //$NON-NLS-1$
    }

    @Test
    public void aKeyThatLooksLikeAnIdentifierIsNotRewritten()
    {
        // Only values are candidates. Rewriting a key would change the response contract, which is
        // a worse outcome than showing a key that happens to spell out an address.
        String json = "{\"ivan@example.com\":\"value\"}"; //$NON-NLS-1$
        JsonObject redacted = JsonParser.parseString(SensitiveTextMasker.redactJson(json))
            .getAsJsonObject();
        assertTrue(redacted.has("ivan@example.com")); //$NON-NLS-1$
    }

    @Test
    public void textThatIsNotJsonComesBackUntouched()
    {
        // Redaction must never be able to corrupt a response it failed to understand.
        String notJson = "Error: project not found"; //$NON-NLS-1$
        assertEquals(notJson, SensitiveTextMasker.redactJson(notJson));
    }
}
