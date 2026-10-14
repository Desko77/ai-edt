/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.BeforeClass;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * A type composition is a list even when it contains hundreds of entries: callers can add or
 * remove one name without rebuilding every existing type item and qualifier.
 */
public class ATypeCompositionHasListModesTest
{
    private static final int LARGE_COMPOSITION_SIZE = 300;
    private static Configuration configuration;
    private static DefinedType prototype;
    private static List<String> originalNames;

    /** Builds the large composition once; each test works on a detached copy. */
    @BeforeClass
    public static void buildLargeComposition()
    {
        configuration = MdClassFactory.eINSTANCE.createConfiguration();
        originalNames = new ArrayList<>();
        for (int i = 0; i <= LARGE_COMPOSITION_SIZE; i++)
        {
            String name = String.format("Type%03d", Integer.valueOf(i)); //$NON-NLS-1$
            Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
            catalog.setName(name);
            configuration.getCatalogs().add(catalog);
            if (i < LARGE_COMPOSITION_SIZE)
            {
                originalNames.add("CatalogRef." + name); //$NON-NLS-1$
            }
        }
        prototype = MdClassFactory.eINSTANCE.createDefinedType();
        prototype.setName("LargeType"); //$NON-NLS-1$
        BmDefinedTypeHelper.TypesResult written = BmDefinedTypeHelper.setTypes(prototype,
            (IProject)null, configuration, originalNames, null);
        assertTrue(String.valueOf(written.error), written.ok);
        assertEquals(LARGE_COMPOSITION_SIZE,
            BmDefinedTypeHelper.readTypeDescriptionNamesInOrder(prototype.getType()).size());
    }

    /** Adding one type appends it and leaves all 300 existing positions alone. */
    @Test
    public void addOneTypeToThreeHundredKeepsTheirOrder()
    {
        DefinedType target = copyPrototype();
        String extra = "CatalogRef.Type300"; //$NON-NLS-1$

        BmDefinedTypeHelper.TypesResult result = update(target, extra,
            ConfigurationListProperties.ListMode.ADD, false, null);

        assertTrue(String.valueOf(result.error), result.ok);
        assertEquals(Collections.singletonList(extra), result.added);
        assertTrue(result.removed.isEmpty());
        assertTrue(result.skipped.isEmpty());
        assertEquals(301, result.finalTypes.size());
        assertEquals(originalNames, result.finalTypes.subList(0, LARGE_COMPOSITION_SIZE));
        assertEquals(result.finalTypes,
            BmDefinedTypeHelper.readTypeDescriptionNamesInOrder(target.getType()));
    }

    /** Removing one middle entry keeps every remaining entry in its former relative order. */
    @Test
    public void removeOneTypeFromThreeHundredKeepsTheRestInPlace()
    {
        DefinedType target = copyPrototype();
        String removed = originalNames.get(150);
        List<String> expected = new ArrayList<>(originalNames);
        expected.remove(removed);

        BmDefinedTypeHelper.TypesResult result = update(target, removed,
            ConfigurationListProperties.ListMode.REMOVE, false, null);

        assertTrue(String.valueOf(result.error), result.ok);
        assertEquals(Collections.singletonList(removed), result.removed);
        assertEquals(299, result.finalTypes.size());
        assertEquals(expected, result.finalTypes);
        assertEquals(expected, BmDefinedTypeHelper.readTypeDescriptionNamesInOrder(target.getType()));
    }

    /** An existing add and an absent remove are successful skips, not failures. */
    @Test
    public void noOpEntriesAreReportedAsSkipped()
    {
        DefinedType target = copyPrototype();
        String existing = originalNames.get(100);

        BmDefinedTypeHelper.TypesResult add = update(target, existing,
            ConfigurationListProperties.ListMode.ADD, false, null);
        BmDefinedTypeHelper.TypesResult remove = update(target, "CatalogRef.Type300", //$NON-NLS-1$
            ConfigurationListProperties.ListMode.REMOVE, false, null);

        assertTrue(String.valueOf(add.error), add.ok);
        assertTrue(add.idempotentSkip);
        assertEquals(Collections.singletonList(existing), add.skipped);
        assertTrue(String.valueOf(remove.error), remove.ok);
        assertTrue(remove.idempotentSkip);
        assertEquals(Collections.singletonList("CatalogRef.Type300"), remove.skipped); //$NON-NLS-1$
        assertEquals(originalNames,
            BmDefinedTypeHelper.readTypeDescriptionNamesInOrder(target.getType()));
    }

    /** Removing the sole type is refused before the model is touched. */
    @Test
    public void removingTheLastTypeIsRefused()
    {
        DefinedType target = copyPrototype();
        List<String> allButLast = originalNames.subList(0, originalNames.size() - 1);
        BmDefinedTypeHelper.TypesResult narrowed = BmDefinedTypeHelper.updateTypes(target,
            null, configuration, allButLast, null, ConfigurationListProperties.ListMode.REMOVE,
            false);
        assertTrue(String.valueOf(narrowed.error), narrowed.ok);
        String last = originalNames.get(originalNames.size() - 1);

        BmDefinedTypeHelper.TypesResult refused = update(target, last,
            ConfigurationListProperties.ListMode.REMOVE, false, null);

        assertFalse(refused.ok);
        assertTrue(String.valueOf(refused.error), refused.error.contains("at least one type")); //$NON-NLS-1$
        assertEquals(Collections.singletonList(last),
            BmDefinedTypeHelper.readTypeDescriptionNamesInOrder(target.getType()));
    }

    /** Clearing a type composition is the same invalid typeless result and is refused. */
    @Test
    public void clearIsRefused()
    {
        DefinedType target = copyPrototype();

        BmDefinedTypeHelper.TypesResult refused = BmDefinedTypeHelper.updateTypes(target,
            null, configuration, Collections.<String>emptyList(), null,
            ConfigurationListProperties.ListMode.CLEAR, false);

        assertFalse(refused.ok);
        assertTrue(String.valueOf(refused.error), refused.error.contains("at least one type")); //$NON-NLS-1$
        assertEquals(originalNames,
            BmDefinedTypeHelper.readTypeDescriptionNamesInOrder(target.getType()));
    }

    /** Adding an unbounded String to a stored composition keeps the old composition unchanged. */
    @Test
    public void addingAnUnboundedStringToAStoredCompositionIsRefused()
    {
        DefinedType target = copyPrototype();

        BmDefinedTypeHelper.TypesResult refused = update(target, "String", //$NON-NLS-1$
            ConfigurationListProperties.ListMode.ADD, false,
            new BmDefinedTypeHelper.QualifierOptions());

        assertFalse(refused.ok);
        assertTrue(String.valueOf(refused.error), refused.error.contains("'length'")); //$NON-NLS-1$
        assertEquals(originalNames,
            BmDefinedTypeHelper.readTypeDescriptionNamesInOrder(target.getType()));
    }

    /** A preview reports the 301-entry result and writes none of it. */
    @Test
    public void dryRunReportsTheFinalCompositionWithoutWritingIt()
    {
        DefinedType target = copyPrototype();

        BmDefinedTypeHelper.TypesResult preview = update(target, "CatalogRef.Type300", //$NON-NLS-1$
            ConfigurationListProperties.ListMode.ADD, true, null);

        assertTrue(String.valueOf(preview.error), preview.ok);
        assertFalse(preview.mutated);
        assertEquals(301, preview.finalTypes.size());
        assertEquals(LARGE_COMPOSITION_SIZE,
            BmDefinedTypeHelper.readTypeDescriptionNamesInOrder(target.getType()).size());
        assertEquals(originalNames,
            BmDefinedTypeHelper.readTypeDescriptionNamesInOrder(target.getType()));
    }

    /** Qualifiers already stored for a retained type are not rewritten by an add. */
    @Test
    public void addDoesNotChangeAnExistingTypesQualifiers()
    {
        DefinedType target = MdClassFactory.eINSTANCE.createDefinedType();
        target.setName("BoundedText"); //$NON-NLS-1$
        BmDefinedTypeHelper.QualifierOptions initial = new BmDefinedTypeHelper.QualifierOptions();
        initial.length = Integer.valueOf(50);
        BmDefinedTypeHelper.TypesResult written = BmDefinedTypeHelper.setTypes(target,
            (IProject)null, configuration,
            Collections.singletonList("String"), initial); //$NON-NLS-1$
        assertTrue(String.valueOf(written.error), written.ok);
        BmDefinedTypeHelper.QualifierOptions unrelated = new BmDefinedTypeHelper.QualifierOptions();
        unrelated.length = Integer.valueOf(5);

        BmDefinedTypeHelper.TypesResult added = update(target, "CatalogRef.Type300", //$NON-NLS-1$
            ConfigurationListProperties.ListMode.ADD, false, unrelated);

        assertTrue(String.valueOf(added.error), added.ok);
        assertEquals(50, target.getType().getStringQualifiers().getLength());
    }

    /** A replacement that names a type twice stores it once and reports the second as skipped. */
    @Test
    public void replaceWithADuplicateStoresTheTypeOnce()
    {
        DefinedType target = copyPrototype();
        String extra = "CatalogRef.Type300"; //$NON-NLS-1$

        BmDefinedTypeHelper.TypesResult result = update(target, extra + "," + extra, //$NON-NLS-1$
            ConfigurationListProperties.ListMode.REPLACE, false, null);

        assertTrue(String.valueOf(result.error), result.ok);
        assertEquals(Collections.singletonList(extra), result.skipped);
        assertEquals(Collections.singletonList(extra),
            BmDefinedTypeHelper.readTypeDescriptionNamesInOrder(target.getType()));
        assertEquals(Collections.singletonList(extra), result.finalTypes);
    }

    /** A replacement that only reorders the stored types answers the composition the model holds. */
    @Test
    public void replaceThatReordersAnswersTheStoredComposition()
    {
        DefinedType target = copyPrototype();
        String first = originalNames.get(1);
        String second = originalNames.get(2);
        assertTrue(update(target, first + "," + second, //$NON-NLS-1$
            ConfigurationListProperties.ListMode.REPLACE, false, null).ok);

        BmDefinedTypeHelper.TypesResult result = update(target, second + "," + first, //$NON-NLS-1$
            ConfigurationListProperties.ListMode.REPLACE, false, null);

        assertTrue(String.valueOf(result.error), result.ok);
        assertEquals(BmDefinedTypeHelper.readTypeDescriptionNamesInOrder(target.getType()),
            result.finalTypes);
    }

    private static DefinedType copyPrototype()
    {
        return EcoreUtil.copy(prototype);
    }

    private static BmDefinedTypeHelper.TypesResult update(DefinedType target, String type,
        ConfigurationListProperties.ListMode mode, boolean preview,
        BmDefinedTypeHelper.QualifierOptions qualifiers)
    {
        return BmDefinedTypeHelper.updateTypes(target, null, configuration,
            Collections.singletonList(type), qualifiers, mode, preview);
    }
}
