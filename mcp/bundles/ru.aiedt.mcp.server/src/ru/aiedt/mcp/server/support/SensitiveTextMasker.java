/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Masks Russian 152-FZ personal-data categories (INN, SNILS, passport, payment card, phone,
 * email) inside tool response text. Designed for precision over recall: INN / SNILS / card
 * candidates are checksum-validated before masking, so a digit run that merely looks like an
 * INN is NOT touched - this keeps redaction safe to apply to JSON string values (structure is
 * never altered, only matching string content). Used only when the
 * {@code mcpPiiRedactEnabled} preference is on (default off).
 */
public final class SensitiveTextMasker
{
    private SensitiveTextMasker()
    {
    }

    // 10- or 12-digit run; checksum-validated below.
    private static final Pattern INN = Pattern.compile("\\b(\\d{10}|\\d{12})\\b"); //$NON-NLS-1$
    // SNILS: XXX-XXX-XXX YY or XXX XXX XXX YY; checksum-validated below.
    private static final Pattern SNILS =
        Pattern.compile("\\b(\\d{3})[- ]?(\\d{3})[- ]?(\\d{3})[- ]?(\\d{2})\\b"); //$NON-NLS-1$
    // 16-digit card (4 groups of 4, optional separators); Luhn-validated below.
    private static final Pattern CARD = Pattern.compile("\\b((?:\\d{4}[- ]?){3}\\d{4})\\b"); //$NON-NLS-1$
    // RU internal passport: 4-digit series, REQUIRED separator (space/dash), 6-digit number.
    // A separator is required so a bare 10-digit identifier is NOT mistaken for a passport.
    private static final Pattern PASSPORT = Pattern.compile("\\b(\\d{4})[\\s-]+(\\d{6})\\b"); //$NON-NLS-1$
    // Phone: +7 (with optional space/paren), or 8/7 REQUIRED followed by a space or "("
    // (so a bare 11-digit identifier is not masked), then 10 digits with common separators.
    private static final Pattern PHONE =
        Pattern.compile("(?<!\\d)(?:\\+7[\\s(]*|(?:8|7)[\\s(]+)\\d{3}[\\s)]*[-\\s]?\\d{3}[-\\s]?\\d{2}[-\\s]?\\d{2}(?!\\d)"); //$NON-NLS-1$
    private static final Pattern EMAIL = Pattern.compile("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b"); //$NON-NLS-1$

    /** Masks PII inside an arbitrary text string (a JSON string value, a line of text, ...). */
    public static String redact(String text)
    {
        if (text == null || text.isEmpty())
        {
            return text;
        }
        String t = text;
        t = maskInn(t);
        t = maskSnils(t);
        t = maskCard(t);
        t = maskPassport(t);
        t = PHONE.matcher(t).replaceAll("[PHONE]"); //$NON-NLS-1$
        t = EMAIL.matcher(t).replaceAll("[EMAIL]"); //$NON-NLS-1$
        return t;
    }

    /**
     * Walks a JSON response and masks PII inside every string value, leaving numbers, booleans,
     * structure and keys untouched. Returns the redacted JSON text (or the original if it is not a
     * JSON object/array). Safe because only {@link JsonPrimitive} string values are rewritten.
     */
    public static String redactJson(String jsonResponse)
    {
        if (jsonResponse == null || jsonResponse.isEmpty())
        {
            return jsonResponse;
        }
        try
        {
            JsonElement root = com.google.gson.JsonParser.parseString(jsonResponse);
            walk(root);
            return ru.aiedt.mcp.server.wire.GsonHolder.toJson(root);
        }
        catch (Exception ignore)
        {
            // Not valid JSON, or walking failed - do not risk corrupting the response.
            return jsonResponse;
        }
    }

    private static void walk(JsonElement el)
    {
        if (el == null || el.isJsonNull())
        {
            return;
        }
        if (el.isJsonObject())
        {
            JsonObject o = el.getAsJsonObject();
            for (String key : new java.util.ArrayList<>(o.keySet()))
            {
                JsonElement child = o.get(key);
                if (child != null && child.isJsonPrimitive() && child.getAsJsonPrimitive().isString())
                {
                    String v = child.getAsString();
                    String r = redact(v);
                    if (!r.equals(v))
                    {
                        o.addProperty(key, r);
                    }
                }
                else
                {
                    walk(child);
                }
            }
        }
        else if (el.isJsonArray())
        {
            JsonArray a = el.getAsJsonArray();
            for (int i = 0; i < a.size(); i++)
            {
                JsonElement child = a.get(i);
                if (child != null && child.isJsonPrimitive() && child.getAsJsonPrimitive().isString())
                {
                    String v = child.getAsString();
                    String r = redact(v);
                    if (!r.equals(v))
                    {
                        a.set(i, new JsonPrimitive(r));
                    }
                }
                else
                {
                    walk(child);
                }
            }
        }
    }

    private static String maskInn(String t)
    {
        Matcher m = INN.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find())
        {
            String digits = m.group(1);
            if (innValid(digits))
            {
                m.appendReplacement(sb, "[INN••" + digits.substring(digits.length() - 2) + "]"); //$NON-NLS-1$
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String maskSnils(String t)
    {
        Matcher m = SNILS.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find())
        {
            String full = m.group(1) + m.group(2) + m.group(3);
            int check = Integer.parseInt(m.group(4));
            if (snilsValid(full, check))
            {
                m.appendReplacement(sb, "[SNILS••" + m.group(4) + "]"); //$NON-NLS-1$
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String maskCard(String t)
    {
        Matcher m = CARD.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find())
        {
            String digits = m.group(1).replaceAll("[^0-9]", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (digits.length() == 16 && luhnValid(digits))
            {
                m.appendReplacement(sb, "[CARD••" + digits.substring(12) + "]"); //$NON-NLS-1$
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String maskPassport(String t)
    {
        Matcher m = PASSPORT.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find())
        {
            m.appendReplacement(sb, "[PASSPORT••" + m.group(2).substring(4) + "]"); //$NON-NLS-1$
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** INN-10 / INN-12 checksum (Russian tax-id check digits). */
    private static boolean innValid(String inn)
    {
        if (allSameDigit(inn))
        {
            return false;
        }
        int n = inn.length();
        int[][] weights = { {2, 4, 10, 3, 5, 9, 4, 6, 8}, {7, 2, 4, 10, 3, 5, 9, 4, 6, 8} };
        try
        {
            if (n == 10)
            {
                int sum = 0;
                for (int i = 0; i < 9; i++)
                {
                    sum += (inn.charAt(i) - '0') * weights[0][i];
                }
                int ctrl = (sum % 11) % 10;
                return ctrl == (inn.charAt(9) - '0');
            }
            else if (n == 12)
            {
                int sum1 = 0;
                for (int i = 0; i < 10; i++)
                {
                    sum1 += (inn.charAt(i) - '0') * weights[1][i];
                }
                int ctrl1 = (sum1 % 11) % 10;
                if (ctrl1 != (inn.charAt(10) - '0'))
                {
                    return false;
                }
                int sum2 = 0;
                int[] w2 = {3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8};
                for (int i = 0; i < 11; i++)
                {
                    sum2 += (inn.charAt(i) - '0') * w2[i];
                }
                int ctrl2 = (sum2 % 11) % 10;
                return ctrl2 == (inn.charAt(11) - '0');
            }
        }
        catch (Exception ignore)
        {
            return false;
        }
        return false;
    }

    /** SNILS checksum: sum of digit*position (right-to-left, 1..9), mod 101. */
    private static boolean snilsValid(String nineDigits, int check)
    {
        if (nineDigits.length() != 9 || allSameDigit(nineDigits))
        {
            return false;
        }
        try
        {
            int sum = 0;
            for (int i = 0; i < 9; i++)
            {
                sum += (nineDigits.charAt(i) - '0') * (9 - i);
            }
            int ctrl = sum % 101;
            if (ctrl == 100)
            {
                ctrl = 0;
            }
            return ctrl == check;
        }
        catch (Exception ignore)
        {
            return false;
        }
    }

    /** Luhn checksum for payment cards. */
    private static boolean luhnValid(String digits)
    {
        if (allSameDigit(digits))
        {
            return false;
        }
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--)
        {
            int d = digits.charAt(i) - '0';
            if (alt)
            {
                d *= 2;
                if (d > 9)
                {
                    d -= 9;
                }
            }
            sum += d;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    /** Rejects degenerate all-same-digit strings (0000000000, 111111111111) that satisfy a
     * checksum by accident - common placeholders, not real PII. */
    private static boolean allSameDigit(String digits)
    {
        if (digits == null || digits.isEmpty())
        {
            return true;
        }
        char first = digits.charAt(0);
        for (int i = 1; i < digits.length(); i++)
        {
            if (digits.charAt(i) != first)
            {
                return false;
            }
        }
        return true;
    }
}
