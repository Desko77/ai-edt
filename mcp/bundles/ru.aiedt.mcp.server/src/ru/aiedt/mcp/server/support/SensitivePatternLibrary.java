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
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CASE);

    /**
     * Where a log-record call begins, without requiring it to end.
     * <p>
     * A file is read line by line, and a call that carries an event name, a level and a comment is
     * written across several of them - so the pattern above, which needs the closing bracket, saw
     * none of those. This finds the opening, and the caller gathers the lines until the brackets
     * balance.
     * </p>
     */
    public static final Pattern LOG_RECORD_START = Pattern.compile(
        "(ЗаписьЖурналаРегистрации|WriteLogRecord|WriteLogEvent)\\s*\\(", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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
     * Whether the given attribute name matches a known sensitive word.
     * <p>
     * A dictionary word counts where it is a word of the identifier, not a substring of one:
     * UserPassword carries password, but Setting does not carry tin and Длинный does not carry
     * инн. A compound dictionary entry (apikey, номеркарты) counts when the words it is made of
     * stand next to each other - UserCardNumber carries cardnumber. Comparison folds case.
     * </p>
     *
     * @param name the attribute name; may be <code>null</code>
     * @return whether the name looks like sensitive data
     */
    public static boolean isSensitiveName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        List<String> words = identifierWords(name.trim());
        // A run of consecutive words is one candidate: a single word for entries like token,
        // several joined for compound entries like cardnumber or датарождения.
        for (int from = 0; from < words.size(); from++)
        {
            StringBuilder joined = new StringBuilder();
            for (int to = from; to < words.size(); to++)
            {
                joined.append(words.get(to));
                if (SENSITIVE_NAMES.contains(joined.toString()))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Splits a 1C identifier into the words it is written with.
     * <p>
     * A word ends where the writing says it ends: at an underscore, where letters run into digits
     * or digits into letters, where a lower-case letter hands over to a capital, and where an
     * acronym hands over to a word - the last capital of ИННКонтрагента opens Контрагента.
     * </p>
     *
     * @param name the identifier, already trimmed
     * @return the words, lower case, in order
     */
    private static List<String> identifierWords(String name)
    {
        List<String> words = new ArrayList<>();
        int start = 0;
        for (int at = 1; at < name.length(); at++)
        {
            if (wordBreaks(name, at))
            {
                addWord(words, name, start, at);
                start = at;
            }
        }
        addWord(words, name, start, name.length());
        return words;
    }

    /**
     * Whether one word ends and another begins between the characters at {@code at - 1} and
     * {@code at}.
     *
     * @param name the identifier
     * @param at the position the second character sits at
     * @return whether the position is a word boundary
     */
    private static boolean wordBreaks(String name, int at)
    {
        char before = name.charAt(at - 1);
        char current = name.charAt(at);
        if (before == '_' || current == '_')
        {
            return true;
        }
        if (Character.isDigit(before) != Character.isDigit(current))
        {
            return true;
        }
        if (Character.isLowerCase(before) && Character.isUpperCase(current))
        {
            return true;
        }
        // An acronym followed by a word: the capital a lower-case letter follows starts the word.
        return Character.isUpperCase(before) && Character.isUpperCase(current)
            && at + 1 < name.length() && Character.isLowerCase(name.charAt(at + 1));
    }

    /**
     * Adds one slice of the identifier to the words, lower case and without underscores.
     *
     * @param words where the word goes
     * @param name the identifier
     * @param start where the slice begins
     * @param end where it ends
     */
    private static void addWord(List<String> words, String name, int start, int end)
    {
        String word = name.substring(start, end).replace("_", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
        if (!word.isEmpty())
        {
            words.add(word);
        }
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
