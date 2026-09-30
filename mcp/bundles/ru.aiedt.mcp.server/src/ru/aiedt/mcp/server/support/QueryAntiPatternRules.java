/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static rules for detecting anti-patterns in 1C query text. 1.38 MVP uses
 * regex-based analysis (best-effort). Note: full AST
 * traversal via Xtext is more accurate but slower; for the 1.38 first cut
 * regex covers the most common cases (SELECT *, missing WHERE, virtual table
 * params). Deeper rules (CROSS JOIN without condition, nested subquery depth)
 * remain regex too with documented false-positive risks.
 */
public final class QueryAntiPatternRules
{
    private QueryAntiPatternRules()
    {
        // utility class
    }

    /**
     * Severity levels.
     */
    public enum Severity
    {
        ERROR, WARNING, INFO
    }

    /**
     * One detected anti-pattern.
     */
    public static final class Issue
    {
        public final String rule;
        public final Severity severity;
        public final String message;
        public final int lineInQuery;

        public Issue(String rule, Severity severity, String message, int lineInQuery)
        {
            this.rule = rule;
            this.severity = severity;
            this.message = message;
            this.lineInQuery = lineInQuery;
        }

        public Map<String, Object> toMap()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", rule); //$NON-NLS-1$
            m.put("severity", severity.name()); //$NON-NLS-1$
            m.put("message", message); //$NON-NLS-1$
            m.put("lineInQuery", lineInQuery); //$NON-NLS-1$
            return m;
        }
    }

    private static final Pattern SELECT_STAR_PATTERN = Pattern.compile(
        "(ВЫБРАТЬ|SELECT)\\s+(РАЗЛИЧНЫЕ\\s+|DISTINCT\\s+)?\\*", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // UNICODE_CHARACTER_CLASS is not optional here. Java's \w is ASCII-only by default, so without
    // the flag the table name never matches and the rule silently reports nothing for any query
    // written in Russian - which is nearly all of them.
    private static final Pattern NO_WHERE_PATTERN = Pattern
        .compile("(ВЫБРАТЬ|SELECT)[^;]*?(ИЗ|FROM)\\s+([\\w\\.]+)", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);

    // Measured: plain CASE_INSENSITIVE folds ASCII only, so ГДЕ matched and где did not, and a
    // query whose filter is written in lower case was reported as a query without a filter. The
    // word boundary was never the problem - Java matches it around Cyrillic either way.
    private static final Pattern WHERE_PATTERN = Pattern.compile("\\b(ГДЕ|WHERE)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    // Same reason as NO_WHERE_PATTERN: the leading \w+ is the register's name, and an ASCII-only
    // \w cannot match one.
    private static final Pattern VIRTUAL_TABLE_PATTERN = Pattern
        .compile("(\\w+)\\s*\\.\\s*(СрезПоследних|СрезПервых|Остатки|Обороты|ОстаткиИОбороты|" //$NON-NLS-1$
            + "SliceLast|SliceFirst|Balance|Turnovers|BalanceAndTurnovers)\\s*\\(\\s*\\)", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern CROSS_JOIN_PATTERN = Pattern
        .compile("(КРОСС\\s+СОЕДИНЕНИЕ|CROSS\\s+JOIN)", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern SUBQUERY_PATTERN = Pattern.compile("\\(\\s*(ВЫБРАТЬ|SELECT)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Runs all enabled rules over the query text. {@code enabledRules=null}
     * means run all.
     */
    public static List<Issue> analyze(String queryText, java.util.Set<String> enabledRules)
    {
        List<Issue> issues = new ArrayList<>();
        if (queryText == null || queryText.isEmpty())
        {
            return issues;
        }
        if (isEnabled("SELECT_STAR", enabledRules)) //$NON-NLS-1$
        {
            checkSelectStar(queryText, issues);
        }
        if (isEnabled("NO_WHERE_ON_LARGE_TABLE", enabledRules)) //$NON-NLS-1$
        {
            checkNoWhere(queryText, issues);
        }
        if (isEnabled("VIRTUAL_TABLE_PARAMS", enabledRules)) //$NON-NLS-1$
        {
            checkVirtualTableParams(queryText, issues);
        }
        if (isEnabled("CROSS_JOIN_NO_CONDITION", enabledRules)) //$NON-NLS-1$
        {
            checkCrossJoin(queryText, issues);
        }
        if (isEnabled("NESTED_QUERY_DEPTH", enabledRules)) //$NON-NLS-1$
        {
            checkNestedDepth(queryText, issues);
        }
        if (isEnabled("SUBQUERY_IN_SELECT", enabledRules)) //$NON-NLS-1$
        {
            checkSubqueryInSelect(queryText, issues);
        }
        return issues;
    }

    private static boolean isEnabled(String rule, java.util.Set<String> enabled)
    {
        return enabled == null || enabled.isEmpty() || enabled.contains(rule);
    }

    private static void checkSelectStar(String queryText, List<Issue> issues)
    {
        Matcher m = SELECT_STAR_PATTERN.matcher(queryText);
        if (m.find())
        {
            int line = lineAt(queryText, m.start());
            issues.add(new Issue("SELECT_STAR", Severity.WARNING, //$NON-NLS-1$
                "ВЫБРАТЬ * - запрос всех полей. Перечислите явные поля.", line)); //$NON-NLS-1$
        }
    }

    private static void checkNoWhere(String queryText, List<Issue> issues)
    {
        if (queryText.toLowerCase().contains("временнаятаблица") //$NON-NLS-1$
            || queryText.toLowerCase().contains("temporary")) //$NON-NLS-1$
        {
            return; // временные таблицы могут быть без WHERE
        }
        Matcher m = NO_WHERE_PATTERN.matcher(queryText);
        if (m.find())
        {
            String table = m.group(3);
            if (table != null
                && (table.toLowerCase().startsWith("справочник.") //$NON-NLS-1$
                    || table.toLowerCase().startsWith("документ.") //$NON-NLS-1$
                    || table.toLowerCase().startsWith("регистрсведений.") //$NON-NLS-1$
                    || table.toLowerCase().startsWith("регистрнакопления.") //$NON-NLS-1$
                    || table.toLowerCase().startsWith("catalog.") //$NON-NLS-1$
                    || table.toLowerCase().startsWith("document.") //$NON-NLS-1$
                    || table.toLowerCase().startsWith("informationregister.") //$NON-NLS-1$
                    || table.toLowerCase().startsWith("accumulationregister."))) //$NON-NLS-1$
            {
                if (!WHERE_PATTERN.matcher(queryText).find())
                {
                    int line = lineAt(queryText, m.start());
                    issues.add(new Issue("NO_WHERE_ON_LARGE_TABLE", Severity.WARNING, //$NON-NLS-1$
                        "Запрос к таблице " + table //$NON-NLS-1$
                            + " без WHERE. Может вернуть всю таблицу.", //$NON-NLS-1$
                        line));
                }
            }
        }
    }

    private static void checkVirtualTableParams(String queryText, List<Issue> issues)
    {
        Matcher m = VIRTUAL_TABLE_PATTERN.matcher(queryText);
        while (m.find())
        {
            int line = lineAt(queryText, m.start());
            issues.add(new Issue("VIRTUAL_TABLE_PARAMS", Severity.WARNING, //$NON-NLS-1$
                "Виртуальная таблица " + m.group(2) //$NON-NLS-1$
                    + "() без параметров. Передайте период / условия.", //$NON-NLS-1$
                line));
        }
    }

    private static void checkCrossJoin(String queryText, List<Issue> issues)
    {
        Matcher m = CROSS_JOIN_PATTERN.matcher(queryText);
        while (m.find())
        {
            int line = lineAt(queryText, m.start());
            issues.add(new Issue("CROSS_JOIN_NO_CONDITION", Severity.ERROR, //$NON-NLS-1$
                "CROSS JOIN без явного условия. Может породить декартово произведение.", //$NON-NLS-1$
                line));
        }
    }

    private static void checkNestedDepth(String queryText, List<Issue> issues)
    {
        int maxDepth = deepestSubqueryNesting(queryText);
        if (maxDepth >= 3)
        {
            issues.add(new Issue("NESTED_QUERY_DEPTH", Severity.WARNING, //$NON-NLS-1$
                "Глубокая вложенность подзапросов: " + maxDepth //$NON-NLS-1$
                    + ". Рассмотрите рефакторинг через временные таблицы.", //$NON-NLS-1$
                1));
        }
    }

    /**
     * The deepest nesting of subqueries in a query text, over every query of a batch.
     * <p>
     * A subquery's depth is the number of subqueries it stands inside, plus one: three subqueries
     * side by side are each of depth 1. The batch is split at {@code ;} outside string literals
     * and comments, and each query is measured on its own.
     * </p>
     *
     * @param queryText the query text, possibly a batch
     * @return the deepest subquery nesting; 0 when there is no subquery
     */
    public static int deepestSubqueryNesting(String queryText)
    {
        int deepest = 0;
        for (String query : splitBatch(queryText))
        {
            deepest = Math.max(deepest, subqueryNesting(query));
        }
        return deepest;
    }

    /**
     * Splits a batch into its queries at the {@code ;} that stand outside string literals and
     * comments.
     *
     * @param text the batch
     * @return the queries, in order
     */
    public static List<String> splitBatch(String text)
    {
        List<String> queries = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length())
        {
            int skipped = skipLiteralOrComment(text, i);
            if (skipped > i)
            {
                i = skipped;
                continue;
            }
            if (text.charAt(i) == ';')
            {
                queries.add(text.substring(start, i));
                start = i + 1;
            }
            i++;
        }
        queries.add(text.substring(start));
        return queries;
    }

    /**
     * The deepest nesting of subqueries inside one query.
     *
     * @param query one query of a batch
     * @return the deepest nesting; 0 when there is no subquery
     */
    private static int subqueryNesting(String query)
    {
        java.util.Deque<Boolean> open = new java.util.ArrayDeque<>();
        int depth = 0;
        int deepest = 0;
        Matcher subquery = SUBQUERY_PATTERN.matcher(query);
        int i = 0;
        while (i < query.length())
        {
            int skipped = skipLiteralOrComment(query, i);
            if (skipped > i)
            {
                i = skipped;
                continue;
            }
            char c = query.charAt(i);
            if (c == '(')
            {
                boolean opensSubquery = subquery.region(i, query.length()).lookingAt();
                open.push(Boolean.valueOf(opensSubquery));
                if (opensSubquery)
                {
                    depth++;
                    deepest = Math.max(deepest, depth);
                }
            }
            else if (c == ')' && !open.isEmpty() && open.pop().booleanValue())
            {
                depth--;
            }
            i++;
        }
        return deepest;
    }

    /**
     * Steps over a string literal or a line comment that starts at a position.
     *
     * @param text the text
     * @param at the position
     * @return the position after the literal or comment, or {@code at} when none starts there
     */
    private static int skipLiteralOrComment(String text, int at)
    {
        char c = text.charAt(at);
        if (c == '"')
        {
            int i = at + 1;
            while (i < text.length())
            {
                if (text.charAt(i) == '"')
                {
                    // A doubled quote is a quote inside the literal.
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"')
                    {
                        i += 2;
                        continue;
                    }
                    return i + 1;
                }
                i++;
            }
            return text.length();
        }
        if (c == '/' && at + 1 < text.length() && text.charAt(at + 1) == '/')
        {
            int end = text.indexOf('\n', at);
            return end < 0 ? text.length() : end + 1;
        }
        return at;
    }

    private static void checkSubqueryInSelect(String queryText, List<Issue> issues)
    {
        // Heuristic: подзапрос внутри SELECT (между ВЫБРАТЬ и ИЗ)
        Pattern selectInSelect = Pattern.compile(
            "(ВЫБРАТЬ|SELECT)\\s+[^;]*?\\(\\s*(ВЫБРАТЬ|SELECT)", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CASE);
        Matcher m = selectInSelect.matcher(queryText);
        if (m.find())
        {
            int line = lineAt(queryText, m.start());
            issues.add(new Issue("SUBQUERY_IN_SELECT", Severity.WARNING, //$NON-NLS-1$
                "Подзапрос в списке SELECT. Часто заменяется на JOIN для производительности.", //$NON-NLS-1$
                line));
        }
    }

    private static int lineAt(String text, int offset)
    {
        if (offset <= 0)
        {
            return 1;
        }
        int line = 1;
        int max = Math.min(offset, text.length());
        for (int i = 0; i < max; i++)
        {
            if (text.charAt(i) == '\n')
            {
                line++;
            }
        }
        return line;
    }
}
