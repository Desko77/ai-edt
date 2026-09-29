/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * What a batch says about an operation that fails because an earlier one did not create what it
 * needs.
 * <p>
 * An operation that previewed an object (dryRun) or failed to create it leaves that object absent.
 * A later operation addressing the object, or anything inside it, is marked as following from that
 * operation. A batch in which every operation was a preview does not say anything was applied.
 * </p>
 */
public class AFailureNamesTheOperationItFollowsFromTest
{
    @Test
    public void theObjectsACreatingOperationMakesAreNamed()
    {
        assertEquals("Catalog.Товары", EditMetadataTool.createdObjectOf("create_object", //$NON-NLS-1$ //$NON-NLS-2$
            params("objectType", "Catalog", "name", "Товары"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("Catalog.Товары.Form.ФормаЭлемента", EditMetadataTool.createdObjectOf("create_form", //$NON-NLS-1$ //$NON-NLS-2$
            params("ownerFqn", "Catalog.Товары", "formName", "ФормаЭлемента"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNull(EditMetadataTool.createdObjectOf("add_object_attribute", //$NON-NLS-1$
            params("ownerFqn", "Catalog.Товары", "name", "Цена"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void anOperationOnTheAbsentObjectFollowsFromTheOneThatLeftItAbsent()
    {
        Map<String, Integer> absent = absent("Catalog.D11Probe", 0); //$NON-NLS-1$

        assertEquals(Integer.valueOf(0), EditMetadataTool.causedBy(
            params("parentFqn", "Catalog.D11Probe", "attributeName", "Field1"), absent)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("names compare without regard to case", Integer.valueOf(0), //$NON-NLS-1$
            EditMetadataTool.causedBy(params("ownerFqn", "catalog.d11probe"), absent)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a child of the absent object is absent too", Integer.valueOf(0), //$NON-NLS-1$
            EditMetadataTool.causedBy(params("formFqn", "Catalog.D11Probe.Form.ItemForm"), absent)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aTypeNamedInRussianAddressesTheSameObject()
    {
        Map<String, Integer> absent = absent("Catalog.Товары", 2); //$NON-NLS-1$

        assertEquals(Integer.valueOf(2),
            EditMetadataTool.causedBy(params("ownerFqn", "Справочник.Товары"), absent)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anotherObjectWithTheSameBeginningDoesNotFollow()
    {
        Map<String, Integer> absent = absent("Catalog.D11Probe", 0); //$NON-NLS-1$

        assertNull(EditMetadataTool.causedBy(params("ownerFqn", "Catalog.D11ProbeOther"), absent)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(EditMetadataTool.causedBy(params("name", "Catalog.D11Probe"), absent)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aPreviewBatchDoesNotSayAnythingWasApplied()
    {
        String preview = EditMetadataTool.batchFailureText(1, 2, true);
        String applied = EditMetadataTool.batchFailureText(1, 2, false);

        assertTrue(preview, preview.startsWith("1 of 2 operations failed.")); //$NON-NLS-1$
        assertTrue(preview, preview.contains("Nothing was written")); //$NON-NLS-1$
        assertFalse(preview, preview.contains("already applied")); //$NON-NLS-1$
        assertTrue(applied, applied.contains("already applied")); //$NON-NLS-1$
    }

    private static Map<String, Integer> absent(String fqn, int index)
    {
        Map<String, Integer> absent = new LinkedHashMap<>();
        absent.put(EditMetadataTool.objectKey(fqn), Integer.valueOf(index));
        return absent;
    }

    private static Map<String, String> params(String... keysAndValues)
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2)
        {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return params;
    }
}
