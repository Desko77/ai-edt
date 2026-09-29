/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * A batch is keyed by everything its operations inherit, a multi-language synonym is applied whole
 * or not at all, and a line-form operation does not lose the tail of a value.
 */
public class ABatchIsKeyedAndReadWholeTest
{
    private static final String OPERATIONS =
        "[{\"operation\":\"add_field\",\"name\":\"Code\",\"dataPath\":\"Object.Code\"}]"; //$NON-NLS-1$

    /**
     * An outer batch call on one form.
     *
     * @param formFqn the form the operations inherit.
     * @return the call's parameters
     */
    private static Map<String, String> batchOn(String formFqn)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("batch", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "Probe"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("formFqn", formFqn); //$NON-NLS-1$
        params.put("operations", OPERATIONS); //$NON-NLS-1$
        return params;
    }

    /**
     * The same operations on two forms of one object are two runs.
     */
    @Test
    public void twoFormsAreTwoRuns()
    {
        assertNotEquals(EditMetadataTool.batchRunKey(batchOn("Catalog.X.Form.ItemForm")), //$NON-NLS-1$
            EditMetadataTool.batchRunKey(batchOn("Catalog.X.Form.ListForm"))); //$NON-NLS-1$
    }

    /**
     * Every inherited parameter is part of the key, and an absent dryRun is the same run as
     * dryRun=false.
     */
    @Test
    public void everyInheritedParameterIsPartOfTheKey()
    {
        String base = EditMetadataTool.batchRunKey(batchOn("Catalog.X.Form.ItemForm")); //$NON-NLS-1$
        assertEquals(base, EditMetadataTool.batchRunKey(batchOn("Catalog.X.Form.ItemForm"))); //$NON-NLS-1$
        Map<String, String> explicitFalse = batchOn("Catalog.X.Form.ItemForm"); //$NON-NLS-1$
        explicitFalse.put("dryRun", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(base, EditMetadataTool.batchRunKey(explicitFalse));
        for (String name : EditMetadataTool.SHARED_BATCH_PARAMS)
        {
            Map<String, String> changed = batchOn("Catalog.X.Form.ItemForm"); //$NON-NLS-1$
            changed.put(name, "dryRun".equals(name) ? "true" : "Other"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertNotEquals(name + " must change the key", base, //$NON-NLS-1$
                EditMetadataTool.batchRunKey(changed));
        }
        Map<String, String> stopping = batchOn("Catalog.X.Form.ItemForm"); //$NON-NLS-1$
        stopping.put("stopOnError", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotEquals(base, EditMetadataTool.batchRunKey(stopping));
    }

    /**
     * A catalog whose synonym already holds two languages.
     *
     * @return the catalog
     */
    private static Catalog catalogWithSynonym()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Counterparties"); //$NON-NLS-1$
        catalog.getSynonym().put("ru", "Старый"); //$NON-NLS-1$ //$NON-NLS-2$
        catalog.getSynonym().put("en", "Old"); //$NON-NLS-1$ //$NON-NLS-2$
        return catalog;
    }

    /**
     * Asserts the synonym still holds exactly what {@link #catalogWithSynonym()} gave it.
     *
     * @param catalog the catalog.
     */
    private static void assertUntouched(Catalog catalog)
    {
        assertEquals(2, catalog.getSynonym().size());
        assertEquals("Старый", catalog.getSynonym().get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Old", catalog.getSynonym().get("en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A value that is not a string refuses the whole synonym and leaves the map as it was.
     */
    @Test
    public void aValueThatIsNotAStringLeavesTheMapAlone()
    {
        Catalog catalog = catalogWithSynonym();
        EditMetadataTool.SynonymResult result = EditMetadataTool.applyMdObjectSynonym(catalog,
            "{\"ru\":\"Контрагент\",\"en\":[\"Counterparty\"]}", "Counterparties", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.applied);
        assertNotNull(result.error);
        assertTrue(result.error, result.error.contains("'en'")); //$NON-NLS-1$
        assertUntouched(catalog);
    }

    /**
     * An empty language code, broken JSON and an empty object are refused the same way.
     */
    @Test
    public void unreadableSynonymsAreRefusedWhole()
    {
        for (String bad : new String[] {"{\"\":\"текст\"}", "{ru: }", "{}"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Catalog catalog = catalogWithSynonym();
            EditMetadataTool.SynonymResult result =
                EditMetadataTool.applyMdObjectSynonym(catalog, bad, "Counterparties", null); //$NON-NLS-1$
            assertFalse(bad, result.applied);
            assertNotNull(bad, result.error);
            assertUntouched(catalog);
        }
    }

    /**
     * A readable object replaces the whole map.
     */
    @Test
    public void aReadableObjectReplacesTheMap()
    {
        Catalog catalog = catalogWithSynonym();
        EditMetadataTool.SynonymResult result = EditMetadataTool.applyMdObjectSynonym(catalog,
            "{\"ru\":\"Контрагенты\"}", "Counterparties", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.applied);
        assertEquals(1, catalog.getSynonym().size());
        assertEquals("Контрагенты", catalog.getSynonym().get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A line-form value is split at its first equals sign, and a token that is not name=value is
     * refused rather than dropped.
     */
    @Test
    public void aLineFormTokenWithoutAnEqualsSignIsRefused()
    {
        Map<String, String> op = EditMetadataTool.parseBatchLine("set_property name=a=b"); //$NON-NLS-1$
        assertEquals("set_property", op.get("operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a=b", op.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            EditMetadataTool.parseBatchLine("add_field name=X title=Мой заголовок"); //$NON-NLS-1$
            fail("the tail of a value with spaces must not be dropped in silence"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("заголовок")); //$NON-NLS-1$
        }
    }
}
