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
        "(ВЫБРАТЬ|SELECT)\\s+(РАЗЛИЧНЫЕ\\s+|DISTINCT\\s+)?\\*", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    // UNICODE_CHARACTER_CLASS is not optional here. Java's \w is ASCII-only by default, so without
    // the flag the table name never matches and the rule silently reports nothing for any query
    // written in Russian - which is nearly all of them.
    private static final Pattern NO_WHERE_PATTERN = Pattern
        .compile("(ВЫБРАТЬ|SELECT)[^;]*?(ИЗ|FROM)\\s+([\\w\\.]+)", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern WHERE_PATTERN = Pattern.compile("\\b(ГДЕ|WHERE)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE);

    // Same reason as NO_WHERE_PATTERN: the leading \w+ is the register's name, and an ASCII-only
    // \w cannot match one.
    private static final Pattern VIRTUAL_TABLE_PATTERN = Pattern
        .compile("(\\w+)\\s*\\.\\s*(СрезПоследних|СрезПервых|Остатки|Обороты|ОстаткиИОбороты|" //$NON-NLS-1$
            + "SliceLast|SliceFirst|Balance|Turnovers|BalanceAndTurnovers)\\s*\\(\\s*\\)", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern CROSS_JOIN_PATTERN = Pattern
        .compile("(КРОСС\\s+СОЕДИНЕНИЕ|CROSS\\s+JOIN)", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private static final Pattern SUBQUERY_PATTERN = Pattern.compile("\\(\\s*(ВЫБРАТЬ|SELECT)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE);

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
        int depth = 0;
        int maxDepth = 0;
        Matcher m = SUBQUERY_PATTERN.matcher(queryText);
        // Approximate: count opening "(SELECT" tokens
        while (m.find())
        {
            depth++;
            if (depth > maxDepth)
            {
                maxDepth = depth;
            }
        }
        if (maxDepth >= 3)
        {
            issues.add(new Issue("NESTED_QUERY_DEPTH", Severity.WARNING, //$NON-NLS-1$
                "Глубокая вложенность подзапросов: " + maxDepth //$NON-NLS-1$
                    + ". Рассмотрите рефакторинг через временные таблицы.", //$NON-NLS-1$
                1));
        }
    }

    private static void checkSubqueryInSelect(String queryText, List<Issue> issues)
    {
        // Heuristic: подзапрос внутри SELECT (между ВЫБРАТЬ и ИЗ)
        Pattern selectInSelect = Pattern.compile(
            "(ВЫБРАТЬ|SELECT)\\s+[^;]*?\\(\\s*(ВЫБРАТЬ|SELECT)", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
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
