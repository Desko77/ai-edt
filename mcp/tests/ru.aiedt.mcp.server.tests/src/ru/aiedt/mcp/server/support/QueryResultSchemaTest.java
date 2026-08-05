/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.ops.QueryValidator;

/**
 * Covers the contract {@code describeResult} makes to a caller, in the parts that hold without a
 * project behind them.
 * <p>
 * Extracting a real schema needs EDT's query-wizard model over a loaded configuration, so the shape
 * of an actual answer is verified against a live workspace, not here. What is pinned here is the
 * behaviour a caller depends on being safe: that asking costs nothing when the query is broken or
 * the services are missing, and that the failure is reported rather than thrown.
 * </p>
 */
public class QueryResultSchemaTest
{
    @Test
    public void describingNothingIsAnAnswerNotAFailure()
    {
        // The extractor is reached from inside validation, which has already produced a useful
        // result by then. Throwing here would cost the caller the validation it actually asked for.
        QueryResultSchema.Result result = QueryResultSchema.describe(null, "ВЫБРАТЬ 1", false); //$NON-NLS-1$
        assertNotNull("a missing project must be reported, not thrown", result); //$NON-NLS-1$
        assertNotNull("the reason has to be sayable to the caller", result.error); //$NON-NLS-1$
        assertTrue("nothing can be described without a project", result.tables.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void anEmptyQueryIsRefusedBeforeAnyModelWork()
    {
        assertNotNull(QueryResultSchema.describe(null, null, false).error);
        assertNotNull(QueryResultSchema.describe(null, "   ", false).error); //$NON-NLS-1$
    }

    @Test
    public void aColumnWithoutATypeCarriesNoTypeKeyAtAll()
    {
        // The whole point of the confidence rule: a caller acts on what it is told, so an unknown
        // type must be absent rather than filled with a plausible-looking default.
        QueryResultSchema.Column typeless =
            new QueryResultSchema.Column("Сумма", new java.util.TreeSet<>()); //$NON-NLS-1$
        Map<String, Object> asMap = typeless.toMap();
        assertEquals("Сумма", asMap.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an unknown type must be omitted, never guessed", //$NON-NLS-1$
            !asMap.containsKey("types")); //$NON-NLS-1$
    }

    @Test
    public void aColumnWithTypesReportsThem()
    {
        java.util.TreeSet<String> types = new java.util.TreeSet<>();
        types.add("CatalogRef.Товары"); //$NON-NLS-1$
        types.add("Строка"); //$NON-NLS-1$
        Map<String, Object> asMap = new QueryResultSchema.Column("Номенклатура", types).toMap(); //$NON-NLS-1$
        assertEquals(java.util.List.of("CatalogRef.Товары", "Строка"), asMap.get("types")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void theValidatorAdvertisesTheOption()
    {
        // An agent picks tools by their descriptions. A capability nobody is told about is one
        // nobody uses - which is exactly how the composite-type support stayed invisible for months.
        QueryValidator validator = new QueryValidator();
        assertTrue("the schema must accept describeResult", //$NON-NLS-1$
            validator.getInputSchema().contains("describeResult")); //$NON-NLS-1$
        assertTrue("the description must mention what the query returns", //$NON-NLS-1$
            validator.getDescription().contains("describeResult")); //$NON-NLS-1$
    }

    @Test
    public void askingForASchemaDoesNotChangeOrdinaryValidation()
    {
        // describeResult is an addition. A caller that was getting a validation answer must keep
        // getting the same one, whatever happens to the extra.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "aiedt-tests-no-such-project"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("queryText", "ВЫБРАТЬ 1"); //$NON-NLS-1$ //$NON-NLS-2$
        String plain = new QueryValidator().execute(params);
        params.put("describeResult", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        String described = new QueryValidator().execute(params);
        assertEquals("an unresolvable project answers the same either way", plain, described); //$NON-NLS-1$
    }
}
