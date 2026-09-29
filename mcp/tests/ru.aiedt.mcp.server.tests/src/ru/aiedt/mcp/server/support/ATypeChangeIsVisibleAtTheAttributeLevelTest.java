/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * A change of the referenced type is visible at the attribute level.
 * <p>
 * An attribute's {@code TypeDescription} holds its types as a multi-valued non-containment
 * reference ({@code refers TypeItem[] types}). Swapping CatalogRef.A for CatalogRef.B keeps the
 * list's size, and a list of references compared by size alone read as unchanged: the attribute
 * compared equal, the object above it compared equal, and no level of the comparison ever said a
 * word about the change.
 * </p>
 */
public class ATypeChangeIsVisibleAtTheAttributeLevelTest
{
    private static Catalog catalogWhoseAttributeReferences(String attributeName, String typeName)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Goods"); //$NON-NLS-1$
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName(attributeName);
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(typeName);
        description.getTypes().add(type);
        attribute.setType(description);
        catalog.getAttributes().add(attribute);
        return catalog;
    }

    /** An attribute whose type moved to another object is not equal to what it was. */
    @Test
    public void aTypeThatMovedToAnotherObjectIsAChange()
    {
        Catalog one = catalogWhoseAttributeReferences("Владелец", "CatalogRef.Контрагенты"); //$NON-NLS-1$ //$NON-NLS-2$
        Catalog two = catalogWhoseAttributeReferences("Владелец", "CatalogRef.Организации"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(MetadataDiffEngine.structurallyEqual(one.getAttributes().get(0),
            two.getAttributes().get(0)));
    }

    /** The attribute-level diff names the attribute, and names the feature that changed in it. */
    @Test
    public void theAttributeNamesTheFeatureThatChanged()
    {
        Catalog one = catalogWhoseAttributeReferences("Владелец", "CatalogRef.Контрагенты"); //$NON-NLS-1$ //$NON-NLS-2$
        Catalog two = catalogWhoseAttributeReferences("Владелец", "CatalogRef.Организации"); //$NON-NLS-1$ //$NON-NLS-2$

        MetadataDiffEngine.DiffResult diff = MetadataDiffEngine.diffAttributes(one, two);

        assertEquals(1, diff.modified.size());
        assertEquals("Attribute.Владелец", diff.modified.get(0).get("fqn")); //$NON-NLS-1$ //$NON-NLS-2$
        @SuppressWarnings("unchecked")
        List<String> changes = (List<String>)diff.modified.get(0).get("changes"); //$NON-NLS-1$
        assertTrue("the feature that changed is named: " + changes, changes.contains("type")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The same type on both sides is not a change, and the object-level equality still holds. */
    @Test
    public void theSameTypeOnBothSidesIsNotAChange()
    {
        Catalog one = catalogWhoseAttributeReferences("Владелец", "CatalogRef.Контрагенты"); //$NON-NLS-1$ //$NON-NLS-2$
        Catalog two = catalogWhoseAttributeReferences("Владелец", "CatalogRef.Контрагенты"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(MetadataDiffEngine.structurallyEqual(one.getAttributes().get(0),
            two.getAttributes().get(0)));
        assertTrue(MetadataDiffEngine.diffAttributes(one, two).modified.isEmpty());
    }

    /** A two-type composition that replaced one of its members is a change at the same level. */
    @Test
    public void aCompositionThatReplacedOneMemberIsAChange()
    {
        Catalog one = catalogWhoseAttributeReferences("Владелец", "CatalogRef.Контрагенты"); //$NON-NLS-1$ //$NON-NLS-2$
        Type extra = McoreFactory.eINSTANCE.createType();
        extra.setName("CatalogRef.Склады"); //$NON-NLS-1$
        one.getAttributes().get(0).getType().getTypes().add(extra);

        Catalog two = catalogWhoseAttributeReferences("Владелец", "CatalogRef.Организации"); //$NON-NLS-1$ //$NON-NLS-2$
        Type other = McoreFactory.eINSTANCE.createType();
        other.setName("CatalogRef.Склады"); //$NON-NLS-1$
        two.getAttributes().get(0).getType().getTypes().add(other);

        assertFalse(MetadataDiffEngine.structurallyEqual(one.getAttributes().get(0),
            two.getAttributes().get(0)));
        Map<String, Object> answer = MetadataDiffEngine.diffAttributes(one, two).toMap();
        assertEquals("modifiedCount stays a count of changed attributes, not of types", 1, //$NON-NLS-1$
            ((Integer)answer.get("modifiedCount")).intValue()); //$NON-NLS-1$
    }
}
