/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the vocabulary the sensitive-data scan works from.
 * <p>
 * It answers two questions: does this attribute name suggest the column holds something private,
 * and does this text contain a credential. Both are heuristics and both are used to warn rather
 * than to block, so the cost of a miss is higher than the cost of a false alarm - but only up to a
 * point, because a scan that flags every field is a scan people turn off.
 * </p>
 */
public class SensitivePatternLibraryTest
{
    // -- attribute names --

    @Test
    public void aPlainSensitiveNameIsRecognized()
    {
        assertTrue(SensitivePatternLibrary.isSensitiveName("password")); //$NON-NLS-1$
        assertTrue(SensitivePatternLibrary.isSensitiveName("token")); //$NON-NLS-1$
        assertTrue(SensitivePatternLibrary.isSensitiveName("Пароль")); //$NON-NLS-1$
        assertTrue(SensitivePatternLibrary.isSensitiveName("СНИЛС")); //$NON-NLS-1$
    }

    @Test
    public void caseAndUnderscoresDoNotHideAName()
    {
        // 1C attribute names arrive in every spelling an author felt like using.
        assertTrue(SensitivePatternLibrary.isSensitiveName("API_KEY")); //$NON-NLS-1$
        assertTrue(SensitivePatternLibrary.isSensitiveName("Auth_Token")); //$NON-NLS-1$
        assertTrue(SensitivePatternLibrary.isSensitiveName("  PassWord  ")); //$NON-NLS-1$
    }

    @Test
    public void aCompoundNameIsRecognizedByItsSensitivePart()
    {
        // The common shape in real configurations: the sensitive word is never the whole name.
        assertTrue(SensitivePatternLibrary.isSensitiveName("UserPassword")); //$NON-NLS-1$
        assertTrue(SensitivePatternLibrary.isSensitiveName("ПарольПользователя")); //$NON-NLS-1$
        assertTrue(SensitivePatternLibrary.isSensitiveName("ClientEmailAddress")); //$NON-NLS-1$
    }

    @Test
    public void anOrdinaryNameIsNotFlagged()
    {
        assertFalse(SensitivePatternLibrary.isSensitiveName("Наименование")); //$NON-NLS-1$
        assertFalse(SensitivePatternLibrary.isSensitiveName("Quantity")); //$NON-NLS-1$
        assertFalse(SensitivePatternLibrary.isSensitiveName("ДатаДокумента")); //$NON-NLS-1$
    }

    /**
     * A dictionary word counts only where it is a word of the identifier.
     * <p>
     * The short words of the dictionary - tin, inn, ssn, инн - sit inside ordinary words
     * (Setting, Beginning, Длинный), and a substring match flagged all of those as personal
     * data. The word boundaries of a 1C identifier are a change of case, a digit, an underscore
     * and the ends of the name, so an acronym reads as a word of its own on either side of it.
     * </p>
     */
    @Test
    public void onlyAWordOfItsOwnCounts()
    {
        String[][] expectations = {
            // The word stands as a word of its own.
            { "ИНН", "true" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "КонтрагентИНН", "true" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "ИННКонтрагента", "true" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "CustomerSSN", "true" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "TaxINN", "true" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "UserPassword", "true" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "ДатаРождения", "true" }, //$NON-NLS-1$ //$NON-NLS-2$
            // The word is only a substring of a longer word.
            { "Setting", "false" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "Settings", "false" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "Testing", "false" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "Beginning", "false" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "ClassName", "false" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "Длинный", "false" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "Старинный", "false" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "Винный", "false" }, //$NON-NLS-1$ //$NON-NLS-2$
        };
        for (String[] row : expectations)
        {
            assertEquals(row[0], Boolean.parseBoolean(row[1]),
                SensitivePatternLibrary.isSensitiveName(row[0]));
        }
    }

    @Test
    public void nothingIsNotSensitive()
    {
        assertFalse(SensitivePatternLibrary.isSensitiveName(null));
        assertFalse(SensitivePatternLibrary.isSensitiveName("")); //$NON-NLS-1$
    }

    // -- secrets in text --

    @Test
    public void aBearerTokenIsFound()
    {
        assertNotNull(SensitivePatternLibrary.matchSecret(
            "Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123456789")); //$NON-NLS-1$
    }

    @Test
    public void anAwsAccessKeyIsFound()
    {
        assertNotNull(SensitivePatternLibrary.matchSecret("key = AKIAIOSFODNN7EXAMPLE")); //$NON-NLS-1$
    }

    @Test
    public void everyPrivateKeyHeaderIsFound()
    {
        // All four are headers a real key file starts with. The detector used to see only the last
        // one, because the other three put the algorithm in front of PRIVATE.
        String[] headers = {
            "-----BEGIN RSA PRIVATE KEY-----", //$NON-NLS-1$
            "-----BEGIN EC PRIVATE KEY-----", //$NON-NLS-1$
            "-----BEGIN OPENSSH PRIVATE KEY-----", //$NON-NLS-1$
            "-----BEGIN PRIVATE KEY-----", //$NON-NLS-1$
        };
        for (String header : headers)
        {
            assertNotNull(header, SensitivePatternLibrary.matchSecret(header + "\nMIIE...")); //$NON-NLS-1$
        }
    }

    @Test
    public void anOpenAiStyleKeyIsFound()
    {
        assertNotNull(SensitivePatternLibrary.matchSecret("sk-abcdefghij0123456789KLMNOP")); //$NON-NLS-1$
    }

    @Test
    public void ordinaryCodeCarriesNoSecret()
    {
        assertNull(SensitivePatternLibrary.matchSecret(
            "Процедура ПриСозданииНаСервере(Отказ, СтандартнаяОбработка)")); //$NON-NLS-1$
        assertNull(SensitivePatternLibrary.matchSecret(null));
        assertNull(SensitivePatternLibrary.matchSecret("")); //$NON-NLS-1$
    }

    @Test
    public void theLibraryItselfCannotBeEditedByACaller()
    {
        // Both collections are shared statics read on every scan; a caller able to add to or clear
        // them would silently change what every later scan considers sensitive.
        try
        {
            SensitivePatternLibrary.SENSITIVE_NAMES.add("anything"); //$NON-NLS-1$
            throw new AssertionError("the name set must be unmodifiable"); //$NON-NLS-1$
        }
        catch (UnsupportedOperationException expected)
        {
            // as intended
        }
        try
        {
            SensitivePatternLibrary.SECRET_PATTERNS.clear();
            throw new AssertionError("the pattern list must be unmodifiable"); //$NON-NLS-1$
        }
        catch (UnsupportedOperationException expected)
        {
            // as intended
        }
    }
}
