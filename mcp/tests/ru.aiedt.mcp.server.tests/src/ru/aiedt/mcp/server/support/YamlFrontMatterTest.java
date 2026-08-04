/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Checks the {@link YamlFrontMatter} wire format: fence shape, scalar rendering per type, insertion order,
 * chaining, and the YAML-quoting rule set exposed via the package-private escape helper.
 */
public class YamlFrontMatterTest
{
    // ---------- create / build ----------

    @Test
    public void createReturnsBuilder()
    {
        assertNotNull(YamlFrontMatter.create());
    }

    @Test
    public void emptyBlockIsTwoFences()
    {
        assertEquals("---\n---\n", YamlFrontMatter.create().build());
    }

    // ---------- put: scalar types render unquoted ----------

    @Test
    public void stringFieldRenderedAsScalar()
    {
        assertEquals("---\nprojectName: Ledger\n---\n",
            YamlFrontMatter.create().put("projectName", "Ledger").build());
    }

    @Test
    public void multipleStringsKeepInsertionOrder()
    {
        String result = YamlFrontMatter.create()
            .put("tool", "code_search")
            .put("projectName", "Ledger")
            .build();
        assertEquals("---\ntool: code_search\nprojectName: Ledger\n---\n", result);
    }

    @Test
    public void intFieldRenderedUnquoted()
    {
        assertEquals("---\nlinesAfter: 40\n---\n",
            YamlFrontMatter.create().put("linesAfter", 40).build());
    }

    @Test
    public void intZeroRenderedAsZero()
    {
        assertEquals("---\ncount: 0\n---\n",
            YamlFrontMatter.create().put("count", 0).build());
    }

    @Test
    public void longFieldRenderedUnquoted()
    {
        assertEquals("---\nsize: 987654321\n---\n",
            YamlFrontMatter.create().put("size", 987654321L).build());
    }

    @Test
    public void booleanTrueRenderedAsTrue()
    {
        assertEquals("---\ndryRun: true\n---\n",
            YamlFrontMatter.create().put("dryRun", true).build());
    }

    @Test
    public void booleanFalseRenderedAsFalse()
    {
        assertEquals("---\ndryRun: false\n---\n",
            YamlFrontMatter.create().put("dryRun", false).build());
    }

    @Test
    public void mixedTypesRenderedInOrder()
    {
        String result = YamlFrontMatter.create()
            .put("name", "probe")
            .put("count", 7)
            .put("active", true)
            .build();
        assertEquals("---\nname: probe\ncount: 7\nactive: true\n---\n", result);
    }

    @Test
    public void manyFieldsPreserveOrder()
    {
        String result = YamlFrontMatter.create()
            .put("tool", "code_search")
            .put("projectName", "Ledger")
            .put("modulePath", "CommonModules/Ledger/Module.bsl")
            .put("mode", "replace")
            .put("status", "success")
            .put("linesAfter", 10)
            .put("syntaxCheck", "passed")
            .put("dryRun", true)
            .build();
        String expected = "---\n"
            + "tool: code_search\n"
            + "projectName: Ledger\n"
            + "modulePath: CommonModules/Ledger/Module.bsl\n"
            + "mode: replace\n"
            + "status: success\n"
            + "linesAfter: 10\n"
            + "syntaxCheck: passed\n"
            + "dryRun: true\n"
            + "---\n";
        assertEquals(expected, result);
    }

    // ---------- put null values / null keys ----------

    @Test
    public void nullStringValueWritesEmptyScalar()
    {
        assertEquals("---\nkey: \n---\n",
            YamlFrontMatter.create().put("key", (String)null).build());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullKeyRejectedForStringOverload()
    {
        YamlFrontMatter.create().put(null, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullKeyRejectedForIntOverload()
    {
        YamlFrontMatter.create().put(null, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullKeyRejectedForLongOverload()
    {
        YamlFrontMatter.create().put(null, 1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullKeyRejectedForBooleanOverload()
    {
        YamlFrontMatter.create().put(null, true);
    }

    // ---------- chaining ----------

    @Test
    public void putReturnsSameBuilder()
    {
        YamlFrontMatter fm = YamlFrontMatter.create();
        assertSame(fm, fm.put("a", "b"));
        assertSame(fm, fm.put("c", 1));
        assertSame(fm, fm.put("d", 1L));
        assertSame(fm, fm.put("e", true));
    }

    // ---------- wrapContent ----------

    @Test
    public void wrapContentAppendsBodyAfterFence()
    {
        String result = YamlFrontMatter.create()
            .put("status", "success")
            .wrapContent("File written");
        assertEquals("---\nstatus: success\n---\nFile written", result);
    }

    @Test
    public void wrapContentEmptyBody()
    {
        assertEquals("---\ntool: probe\n---\n",
            YamlFrontMatter.create().put("tool", "probe").wrapContent(""));
    }

    // ---------- escapeYamlValue: the quoting rule set ----------

    @Test
    public void yamlNullReturnsEmpty()
    {
        assertEquals("", YamlFrontMatter.escapeYamlValue(null));
    }

    @Test
    public void yamlEmptyReturnsQuotedEmpty()
    {
        assertEquals("\"\"", YamlFrontMatter.escapeYamlValue(""));
    }

    @Test
    public void yamlPlainStringUnquoted()
    {
        assertEquals("MyProject", YamlFrontMatter.escapeYamlValue("MyProject"));
    }

    @Test
    public void yamlColonForcesQuotes()
    {
        assertEquals("\"field: payload\"", YamlFrontMatter.escapeYamlValue("field: payload"));
    }

    @Test
    public void yamlHashForcesQuotes()
    {
        assertEquals("\"note # inline\"", YamlFrontMatter.escapeYamlValue("note # inline"));
    }

    @Test
    public void yamlSquareBracketsForcesQuotes()
    {
        assertEquals("\"[array]\"", YamlFrontMatter.escapeYamlValue("[array]"));
    }

    @Test
    public void yamlBracesForcesQuotes()
    {
        assertEquals("\"{map}\"", YamlFrontMatter.escapeYamlValue("{map}"));
    }

    @Test
    public void yamlEmbeddedDoubleQuoteEscaped()
    {
        assertEquals("\"he wrote \\\"done\\\"\"", YamlFrontMatter.escapeYamlValue("he wrote \"done\""));
    }

    @Test
    public void yamlBackslashDoubled()
    {
        assertEquals("\"path\\\\to\\\\file\"", YamlFrontMatter.escapeYamlValue("path\\to\\file"));
    }

    @Test
    public void yamlEmbeddedNewlineEscaped()
    {
        assertEquals("\"line1\\nline2\"", YamlFrontMatter.escapeYamlValue("line1\nline2"));
    }

    @Test
    public void yamlLeadingWhitespaceForcesQuotes()
    {
        assertEquals("\" padded-left\"", YamlFrontMatter.escapeYamlValue(" padded-left"));
    }

    @Test
    public void yamlTrailingWhitespaceForcesQuotes()
    {
        assertEquals("\"padded \"", YamlFrontMatter.escapeYamlValue("padded "));
    }

    // YAML reserved words

    @Test
    public void yamlReservedTrueQuoted()
    {
        assertEquals("\"true\"", YamlFrontMatter.escapeYamlValue("true"));
    }

    @Test
    public void yamlReservedFalseQuoted()
    {
        assertEquals("\"false\"", YamlFrontMatter.escapeYamlValue("false"));
    }

    @Test
    public void yamlReservedNullQuoted()
    {
        assertEquals("\"null\"", YamlFrontMatter.escapeYamlValue("null"));
    }

    @Test
    public void yamlReservedYesQuoted()
    {
        assertEquals("\"yes\"", YamlFrontMatter.escapeYamlValue("yes"));
    }

    @Test
    public void yamlReservedNoQuoted()
    {
        assertEquals("\"no\"", YamlFrontMatter.escapeYamlValue("no"));
    }

    @Test
    public void yamlReservedTrueUpperSpelling()
    {
        assertEquals("\"TRUE\"", YamlFrontMatter.escapeYamlValue("TRUE"));
    }

    @Test
    public void yamlReservedNullCapitalized()
    {
        assertEquals("\"Null\"", YamlFrontMatter.escapeYamlValue("Null"));
    }

    @Test
    public void yamlWordStartingWithReservedStaysUnquoted()
    {
        assertEquals("trueValue", YamlFrontMatter.escapeYamlValue("trueValue"));
    }

    // numeric-looking strings

    @Test
    public void yamlIntegerLookingQuoted()
    {
        assertEquals("\"123\"", YamlFrontMatter.escapeYamlValue("123"));
    }

    @Test
    public void yamlDecimalLookingQuoted()
    {
        assertEquals("\"3.14\"", YamlFrontMatter.escapeYamlValue("3.14"));
    }

    @Test
    public void yamlNegativeNumberLookingQuoted()
    {
        assertEquals("\"-42\"", YamlFrontMatter.escapeYamlValue("-42"));
    }

    @Test
    public void yamlScientificLookingQuoted()
    {
        assertEquals("\"1e10\"", YamlFrontMatter.escapeYamlValue("1e10"));
    }

    @Test
    public void yamlNumericPrefixButNotNumberStaysUnquoted()
    {
        assertEquals("123abc", YamlFrontMatter.escapeYamlValue("123abc"));
    }

    @Test
    public void yamlForwardSlashPathStaysUnquoted()
    {
        assertEquals("Documents/MyDoc/ObjectModule.bsl",
            YamlFrontMatter.escapeYamlValue("Documents/MyDoc/ObjectModule.bsl"));
    }
}
