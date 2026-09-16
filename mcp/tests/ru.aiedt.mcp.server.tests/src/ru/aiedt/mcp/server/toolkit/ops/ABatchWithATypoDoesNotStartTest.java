/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * A batch that cannot run as written is refused before any of it runs.
 * <p>
 * The batch used to walk its operations and fail on the one it reached, and the ones before it were
 * already in the project - so a misspelled operation in the ninth entry meant eight objects created
 * and a state to sort out by hand. What can be told without touching anything is told first, and
 * every such entry is named at once: a caller who fixes one typo only to meet the next has paid
 * twice for one reading.
 * </p>
 */
public class ABatchWithATypoDoesNotStartTest
{
    @SuppressWarnings("unchecked")
    private static List<String> refusals(List<Map<String, String>> ops) throws Exception
    {
        Method method = EditMetadataTool.class.getDeclaredMethod("whatCannotBeRun", List.class); //$NON-NLS-1$
        method.setAccessible(true);
        return (List<String>)method.invoke(new EditMetadataTool(), ops);
    }

    private static Map<String, String> entry(String... keysAndValues)
    {
        Map<String, String> one = new LinkedHashMap<>();
        for (int at = 0; at + 1 < keysAndValues.length; at += 2)
        {
            one.put(keysAndValues[at], keysAndValues[at + 1]);
        }
        return one;
    }

    private static List<Map<String, String>> batch(Map<String, String>... entries)
    {
        List<Map<String, String>> ops = new ArrayList<>();
        for (Map<String, String> one : entries)
        {
            ops.add(one);
        }
        return ops;
    }

    /** A batch every entry of which could be attempted is not held back. */
    @Test
    @SuppressWarnings("unchecked")
    public void aBatchWithoutATypoIsNotRefused() throws Exception
    {
        List<String> refused = refusals(batch(
            entry("operation", "create_object", "objectType", "Catalog", "name", "Товары"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            entry("operation", "add_object_attribute", "name", "Цена", "type", "Number"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertEquals(refused.toString(), 0, refused.size());
    }

    /** An operation this tool does not have is named, with the one it was probably meant to be. */
    @Test
    @SuppressWarnings("unchecked")
    public void anOperationThatDoesNotExistIsNamedBeforeAnythingRuns() throws Exception
    {
        List<String> refused = refusals(batch(
            entry("operation", "create_objekt", "objectType", "Catalog"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(refused.toString(), 1, refused.size());
        assertTrue(refused.get(0), refused.get(0).contains("[0]")); //$NON-NLS-1$
        assertTrue(refused.get(0), refused.get(0).contains("create_objekt")); //$NON-NLS-1$
        assertTrue("and what it was probably meant to be", //$NON-NLS-1$
            refused.get(0).contains("create_object")); //$NON-NLS-1$
    }

    /** An argument name nothing declares is a typo the caller can see before the batch runs. */
    @Test
    @SuppressWarnings("unchecked")
    public void anArgumentNameNothingDeclaresIsNamed() throws Exception
    {
        List<String> refused = refusals(batch(
            entry("operation", "create_object", "objectType", "Catalog", "nam", "Товары"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertEquals(refused.toString(), 1, refused.size());
        assertTrue(refused.get(0), refused.get(0).contains("nam")); //$NON-NLS-1$
    }

    /** Two typos in two entries come back together, not one call at a time. */
    @Test
    @SuppressWarnings("unchecked")
    public void twoTyposInTwoEntriesAreBothNamed() throws Exception
    {
        List<String> refused = refusals(batch(
            entry("operation", "create_object", "objectType", "Catalog", "name", "Товары"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            entry("operation", "add_tabular_sektion", "name", "Строки"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            entry("operation", "create_object", "objectTyp", "Document", "name", "Заказ"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertEquals(refused.toString(), 2, refused.size());
        assertTrue(refused.toString(), refused.get(0).contains("[1]")); //$NON-NLS-1$
        assertTrue(refused.toString(), refused.get(1).contains("[2]")); //$NON-NLS-1$
        assertTrue(refused.toString(), refused.get(1).contains("objectTyp")); //$NON-NLS-1$
    }

    /** An entry that names no operation at all, and one that carries a batch of its own. */
    @Test
    @SuppressWarnings("unchecked")
    public void anEntryWithNoOperationAndAnEntryCarryingABatchAreBothRefused() throws Exception
    {
        List<String> refused = refusals(batch(
            entry("objectType", "Catalog", "name", "Товары"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            entry("operation", "create_object", "objectType", "Catalog", "name", "Цены", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "batch", "true"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(refused.toString(), 2, refused.size());
        assertTrue(refused.get(0), refused.get(0).contains("names no operation")); //$NON-NLS-1$
        assertTrue(refused.get(1), refused.get(1).contains("cannot carry batch")); //$NON-NLS-1$
    }
}
