/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * The refusal of an unbounded String inside a composition judges the length the model will carry,
 * not the one the call spells out.
 * <p>
 * A caller that repeats a composition already written, naming no length, leaves the stored length
 * in place - the platform never sees an unbounded String and the database update is not at risk.
 * A caller that names {@code length=0} asks for an unbounded String and is refused, as is one
 * whose composition stands over a String the model already holds without a length.
 * </p>
 */
public class ARepeatedCompositionKeepsTheStoredLengthTest
{
    /** The composition this test writes: a String beside a reference type. */
    private static final List<String> COMPOSITION = Arrays.asList("String,CatalogRef.Kinds"); //$NON-NLS-1$

    /**
     * A configuration with the referenced catalog.
     *
     * @return the configuration
     */
    private static Configuration configurationWithCatalog()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Kinds"); //$NON-NLS-1$
        configuration.getCatalogs().add(catalog);
        return configuration;
    }

    /**
     * A catalog attribute already typed with the composition.
     *
     * @param configuration the configuration the reference resolves against
     * @param length the length the first write declares, or <code>null</code> for none
     * @return the attribute
     */
    private static CatalogAttribute attributeTypedWith(Configuration configuration, Integer length)
    {
        Catalog catalog = configuration.getCatalogs().get(0);
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Kind"); //$NON-NLS-1$
        catalog.getAttributes().add(attribute);

        BmDefinedTypeHelper.QualifierOptions first = new BmDefinedTypeHelper.QualifierOptions();
        first.length = length;
        BmDefinedTypeHelper.TypesResult written = BmDefinedTypeHelper.setTypes(attribute, (IProject)null,
            configuration, COMPOSITION, first);
        assertTrue(String.valueOf(written.error), written.ok);
        return attribute;
    }

    /**
     * Repeats the composition with no qualifiers named at all.
     *
     * @param attribute the attribute to retype
     * @param configuration the configuration the reference resolves against
     * @return the result
     */
    private static BmDefinedTypeHelper.TypesResult repeat(CatalogAttribute attribute,
        Configuration configuration)
    {
        return BmDefinedTypeHelper.setTypes(attribute, (IProject)null, configuration,
            COMPOSITION, new BmDefinedTypeHelper.QualifierOptions());
    }

    /**
     * A repeat over a bounded composition changes nothing and is not refused.
     */
    @Test
    public void aRepeatOverABoundedCompositionIsNotRefused()
    {
        Configuration configuration = configurationWithCatalog();
        CatalogAttribute attribute = attributeTypedWith(configuration, Integer.valueOf(50));

        BmDefinedTypeHelper.TypesResult repeated = repeat(attribute, configuration);

        assertTrue(String.valueOf(repeated.error), repeated.ok);
        assertTrue("the composition is already there, so nothing is written", //$NON-NLS-1$
            repeated.idempotentSkip);
        assertEquals("the stored length stays where it was", 50, //$NON-NLS-1$
            attribute.getType().getStringQualifiers().getLength());
    }

    /**
     * A repeat over a composition that holds an unbounded String is refused - the model would
     * carry length zero the platform rejects.
     */
    @Test
    public void aRepeatOverAnUnboundedStringIsRefused()
    {
        Configuration configuration = configurationWithCatalog();
        CatalogAttribute attribute = attributeTypedWith(configuration, Integer.valueOf(50));
        // The state a hand-written or migrated .mdo can hold: the composition is there, the
        // length is not.
        attribute.getType().getStringQualifiers().setLength(0);

        BmDefinedTypeHelper.TypesResult repeated = repeat(attribute, configuration);

        assertFalse(repeated.ok);
        assertTrue(String.valueOf(repeated.error), repeated.error.contains("'length'")); //$NON-NLS-1$
    }

    /**
     * A caller that names {@code length=0} asks for an unbounded String and is refused even where
     * a length is already stored.
     */
    @Test
    public void anExplicitZeroLengthIsRefused()
    {
        Configuration configuration = configurationWithCatalog();
        CatalogAttribute attribute = attributeTypedWith(configuration, Integer.valueOf(50));

        BmDefinedTypeHelper.QualifierOptions zero = new BmDefinedTypeHelper.QualifierOptions();
        zero.length = Integer.valueOf(0);
        BmDefinedTypeHelper.TypesResult refused = BmDefinedTypeHelper.setTypes(attribute,
            (IProject)null, configuration, COMPOSITION, zero);

        assertFalse(refused.ok);
        assertTrue(String.valueOf(refused.error), refused.error.contains("'length'")); //$NON-NLS-1$
        assertEquals("the stored length is untouched", 50, //$NON-NLS-1$
            attribute.getType().getStringQualifiers().getLength());
    }
}
