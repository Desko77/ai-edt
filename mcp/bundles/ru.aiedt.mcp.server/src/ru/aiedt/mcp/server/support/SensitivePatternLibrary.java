/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Library of patterns for sensitive-data detection. Used by
 * {@code sensitive_data_scan} tool.
 */
public final class SensitivePatternLibrary
{
    /**
     * Russian + English attribute name patterns. Lowercase, normalized.
     */
    public static final Set<String> SENSITIVE_NAMES = buildSensitiveNames();

    /**
     * Hardcoded secret regex patterns: Bearer tokens, AWS keys, API keys,
     * private keys, base64-like blobs longer than 20 chars with limited
     * character set.
     */
    public static final List<Pattern> SECRET_PATTERNS = buildSecretPatterns();

    /**
     * Email + phone leak detection in BSL comments.
     */
    public static final Pattern EMAIL_IN_COMMENT = Pattern.compile(
        "//[^\\n]*\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"); //$NON-NLS-1$

    public static final Pattern PHONE_IN_COMMENT = Pattern.compile(
        "//[^\\n]*\\b(\\+?7|8)[\\s-]?\\(?9\\d{2}\\)?[\\s-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}\\b"); //$NON-NLS-1$

    public static final Pattern LOG_RECORD = Pattern.compile(
        "(ЗаписьЖурналаРегистрации|WriteLogRecord|WriteLogEvent)\\s*\\([^)]*\\)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private SensitivePatternLibrary()
    {
        // utility class
    }

    private static Set<String> buildSensitiveNames()
    {
        Set<String> set = new LinkedHashSet<>(Arrays.asList(
            // English
            "password", "passwd", "pwd", "login", "secret", "apikey", "api_key", //$NON-NLS-1$
            "token", "authtoken", "auth_token", "bearer", "creditcard", "cardnumber", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "passport", "ssn", "tin", "inn", "snils", "ogrn", "kpp", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "email", "phone", "address", "fullname", "firstname", "lastname", "middlename", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "birthdate", "dob",
            // Russian (lowercase normalization)
            "пароль", "логин", "секрет", "ключапи", "ключ_апи", "токен", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "паспорт", "снилс", "инн", "огрн", "кпп",
            "номеркарты", "кодкарты", "cvv",
            "электроннаяпочта", "емайл", "телефон", "адрес",
            "фио", "фамилия", "имя", "отчество",
            "датарождения", "деньрождения"
        ));
        return java.util.Collections.unmodifiableSet(set);
    }

    private static List<Pattern> buildSecretPatterns()
    {
        List<Pattern> list = new ArrayList<>();
        list.add(Pattern.compile("Bearer\\s+[A-Za-z0-9_\\-\\.]{20,}")); //$NON-NLS-1$
        list.add(Pattern.compile("AKIA[0-9A-Z]{16}")); // AWS access key id //$NON-NLS-1$
        list.add(Pattern.compile("sk-[A-Za-z0-9]{20,}")); // OpenAI-style //$NON-NLS-1$
        list.add(Pattern.compile("-----BEGIN\\s+(RSA|DSA|EC|OPENSSH|PRIVATE)\\s+KEY-----")); //$NON-NLS-1$
        // The line above wants the algorithm and KEY to be adjacent, which only the PKCS#8 header
        // "BEGIN PRIVATE KEY" is. Everything OpenSSL and ssh-keygen actually write puts the
        // algorithm in front of PRIVATE - "BEGIN RSA PRIVATE KEY", "BEGIN OPENSSH PRIVATE KEY" -
        // and went undetected. Left as a second pattern so nothing the first one caught is lost.
        list.add(Pattern.compile("-----BEGIN\\s+[A-Z0-9]+\\s+PRIVATE\\s+KEY-----")); //$NON-NLS-1$
        list.add(Pattern.compile("eyJ[A-Za-z0-9_=\\-]{20,}\\.[A-Za-z0-9_=\\-]{20,}\\.")); // JWT //$NON-NLS-1$
        list.add(Pattern.compile("[A-Za-z0-9+/=]{40,}")); // base64-like 40+ char //$NON-NLS-1$
        return java.util.Collections.unmodifiableList(list);
    }

    /**
     * Returns true if the given attribute name (case-insensitive, after
     * removing underscores) matches a known sensitive pattern.
     */
    public static boolean isSensitiveName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        String normalized = name.toLowerCase().replace("_", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        // Direct match
        if (SENSITIVE_NAMES.contains(normalized))
        {
            return true;
        }
        // Substring match for compound names like UserPassword, ParolPolzovatelya
        for (String sensitive : SENSITIVE_NAMES)
        {
            if (normalized.contains(sensitive))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the first secret pattern matched in {@code text}, or null.
     */
    public static Pattern matchSecret(String text)
    {
        if (text == null || text.isEmpty())
        {
            return null;
        }
        for (Pattern pattern : SECRET_PATTERNS)
        {
            if (pattern.matcher(text).find())
            {
                return pattern;
            }
        }
        return null;
    }
}
