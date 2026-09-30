/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.extension.BasicFeatureExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionFactory;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeDescriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;

import org.eclipse.emf.ecore.EObject;

/**
 * The borrowed half of {@code extension_diff}'s typeChanges, on a model built by the EMF
 * factories.
 * <p>
 * A borrowed attribute keeps its type in the extension block's {@code typeExtension}, answers
 * {@code getType()} with nothing, and is paired with the base attribute by uuid rather than by
 * name. Before the fix, the name-based walk read an empty type on the extension side, skipped
 * the pair, and a base that retyped a borrowed attribute passed as though nothing had moved.
 * The model here is built without a project because the comparison reads plain values, not the
 * workspace.
 * </p>
 */
public class ExtensionDiffBorrowedTypeTest
{
    private static final UUID BASE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001"); //$NON-NLS-1$

    /**
     * Builds a borrowed attribute: linked by uuid, controlled at String(10) in its extension
     * block.
     *
     * @return the attribute, with no ordinary type of its own
     */
    private static CatalogAttribute borrowedAttribute()
    {
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Артикул"); //$NON-NLS-1$
        attribute.setUuid(UUID.fromString("00000000-0000-0000-0000-000000000002")); //$NON-NLS-1$
        attribute.setExtendedConfigurationObject(BASE_UUID);
        BasicFeatureExtension block = MdClassExtensionFactory.eINSTANCE.createBasicFeatureExtension();
        TypeDescriptionExtension composition =
            MdClassExtensionFactory.eINSTANCE.createTypeDescriptionExtension();
        TypeExtension entry = MdClassExtensionFactory.eINSTANCE.createTypeExtension();
        entry.setState(MdPropertyState.CHECKED);
        Type string = McoreFactory.eINSTANCE.createType();
        string.setName("String"); //$NON-NLS-1$
        entry.setType(string);
        composition.getTypes().add(entry);
        StringQualifiers qualifiers = McoreFactory.eINSTANCE.createStringQualifiers();
        qualifiers.setLength(10);
        composition.setStringQualifiers(qualifiers);
        block.setTypeExtension(composition);
        attribute.setExtension(block);
        return attribute;
    }

    /**
     * Builds the base's attribute: the uuid the link names, typed String of the given length.
     *
     * @param length the length the base now declares
     * @return the attribute
     */
    private static CatalogAttribute baseAttribute(int length)
    {
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Артикул"); //$NON-NLS-1$
        attribute.setUuid(BASE_UUID);
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        Type string = McoreFactory.eINSTANCE.createType();
        string.setName("String"); //$NON-NLS-1$
        description.getTypes().add(string);
        StringQualifiers qualifiers = McoreFactory.eINSTANCE.createStringQualifiers();
        qualifiers.setLength(length);
        description.setStringQualifiers(qualifiers);
        attribute.setType(description);
        return attribute;
    }

    /**
     * A base that grew the borrowed String's length shows as a type change, with the qualifiers
     * on both sides.
     */
    @Test
    public void aRetypedBaseShowsTheLengthOnBothSides()
    {
        List<Map<String, Object>> differences = new ArrayList<>();
        ExtensionDiffTool.addBorrowedTypeChanges(differences,
            List.of(borrowedAttribute()), List.of(baseAttribute(50)));
        assertEquals(1, differences.size());
        assertEquals("String(10)", differences.get(0).get("extensionType")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("String(50)", differences.get(0).get("baseType")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("uuid", differences.get(0).get("matchedBy")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A borrowed type that still matches the base shows nothing. */
    @Test
    public void anUntouchedBorrowedTypeShowsNothing()
    {
        List<Map<String, Object>> differences = new ArrayList<>();
        ExtensionDiffTool.addBorrowedTypeChanges(differences,
            List.of(borrowedAttribute()), List.of(baseAttribute(10)));
        assertTrue(differences.isEmpty());
    }

    /**
     * The pair is found by uuid even when the base renamed the attribute, which the name lists
     * read as two unrelated facts.
     */
    @Test
    public void aRenamedBaseStillPairsByUuid()
    {
        CatalogAttribute renamed = baseAttribute(50);
        renamed.setName("АртикулНовый"); //$NON-NLS-1$
        List<Map<String, Object>> differences = new ArrayList<>();
        ExtensionDiffTool.addBorrowedTypeChanges(differences,
            List.of(borrowedAttribute()), List.of((EObject)renamed));
        assertEquals(1, differences.size());
    }

    /** A borrowed attribute whose base is gone shows nothing: there is no pair to compare. */
    @Test
    public void aBorrowedAttributeWithoutItsBaseShowsNothing()
    {
        List<Map<String, Object>> differences = new ArrayList<>();
        ExtensionDiffTool.addBorrowedTypeChanges(differences,
            List.of(borrowedAttribute()), List.of());
        assertTrue(differences.isEmpty());
    }
}
