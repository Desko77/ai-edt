/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.mdreport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.AccumulationRegisterResource;
import com._1c.g5.v8.dt.metadata.mdclass.FullTextSearchUsing;
import com._1c.g5.v8.dt.metadata.mdclass.Indexing;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterDimension;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterResource;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * A register field reports the index it carries, rather than a dash.
 * <p>
 * The wide table read indexing and full-text search from {@code DbObjectAttribute} alone. No
 * register field is one, though an information register's dimension, attribute and resource each
 * declare both properties themselves, so every field of every register came back with a dash
 * whatever the editor showed, and a caller reading that would set an index that was already there.
 * Which types carry which property is not uniform - an accumulation register's resource has no
 * indexing at all, where an information register's has - so the reading asks each type rather
 * than assuming a family.
 * </p>
 * <p>
 * Built from the factory rather than from a project: the model answers about its own features with
 * no workspace behind it, which is what the reading under test asks it. The assertions name a cell
 * of the field's own row, because the column is headed Indexing and a search of the whole table
 * for that word would pass with the property never read.
 * </p>
 */
public class ARegisterFieldShowsItsIndexTest
{
    // A row is written as "| a | b | ...", so splitting on the bar leaves an empty first element and
    // the columns Name, Synonym, Type, Indexing, FillChecking, FullTextSearch start at one.
    private static final int INDEXING = 4;
    private static final int FULL_TEXT_SEARCH = 6;
    private static final String DASH = "-"; //$NON-NLS-1$

    private static InformationRegister registerNamed(String name)
    {
        InformationRegister register = MdClassFactory.eINSTANCE.createInformationRegister();
        register.setName(name);
        return register;
    }

    /** The cells of the wide-table row for one field of a register, by its name. */
    private static String[] rowFor(InformationRegister register, String fieldName)
    {
        return rowOf(MetadataFormatter.format(register, true, "ru"), fieldName); //$NON-NLS-1$
    }

    /** The cells of the row naming one field, out of a rendered table. */
    private static String[] rowOf(String table, String fieldName)
    {
        for (String line : table.split("\n")) //$NON-NLS-1$
        {
            String[] cells = line.split("\\|"); //$NON-NLS-1$
            if (cells.length > FULL_TEXT_SEARCH && cells[1].trim().equals(fieldName))
            {
                for (int i = 0; i < cells.length; i++)
                {
                    cells[i] = cells[i].trim();
                }
                return cells;
            }
        }
        return null;
    }

    @Test
    public void aDimensionCarryingAnIndexSaysSo()
    {
        InformationRegister register = registerNamed("Events"); //$NON-NLS-1$
        InformationRegisterDimension dimension =
            MdClassFactory.eINSTANCE.createInformationRegisterDimension();
        dimension.setName("Site"); //$NON-NLS-1$
        dimension.setIndexing(Indexing.INDEX);
        register.getDimensions().add(dimension);

        String[] row = rowFor(register, "Site"); //$NON-NLS-1$

        assertNotNull("the dimension belongs in the wide table", row); //$NON-NLS-1$
        assertEquals("a dimension declares indexing of its own", //$NON-NLS-1$
            Indexing.INDEX.getName(), row[INDEXING]);
    }

    @Test
    public void aRegisterAttributeCarriesBothAndBothAreShown()
    {
        InformationRegister register = registerNamed("Events"); //$NON-NLS-1$
        InformationRegisterAttribute attribute =
            MdClassFactory.eINSTANCE.createInformationRegisterAttribute();
        attribute.setName("Note"); //$NON-NLS-1$
        attribute.setIndexing(Indexing.INDEX);
        attribute.setFullTextSearch(FullTextSearchUsing.USE);
        register.getAttributes().add(attribute);

        String[] row = rowFor(register, "Note"); //$NON-NLS-1$

        assertNotNull("the register attribute belongs in the wide table", row); //$NON-NLS-1$
        assertEquals("a register attribute declares indexing of its own", //$NON-NLS-1$
            Indexing.INDEX.getName(), row[INDEXING]);
        assertEquals("and full-text search of its own", //$NON-NLS-1$
            FullTextSearchUsing.USE.getName(), row[FULL_TEXT_SEARCH]);
    }

    @Test
    public void aResourceShowsBothPropertiesItCarries()
    {
        InformationRegister register = registerNamed("Events"); //$NON-NLS-1$
        InformationRegisterResource resource =
            MdClassFactory.eINSTANCE.createInformationRegisterResource();
        resource.setName("Amount"); //$NON-NLS-1$
        resource.setFullTextSearch(FullTextSearchUsing.USE);
        resource.setIndexing(Indexing.INDEX);
        register.getResources().add(resource);

        String[] row = rowFor(register, "Amount"); //$NON-NLS-1$

        assertNotNull("the resource belongs in the wide table", row); //$NON-NLS-1$
        assertEquals("a resource declares full-text search", //$NON-NLS-1$
            FullTextSearchUsing.USE.getName(), row[FULL_TEXT_SEARCH]);
        assertEquals("an information register resource declares indexing too", //$NON-NLS-1$
            Indexing.INDEX.getName(), row[INDEXING]);
    }

    @Test
    public void aFieldWithoutThePropertyStillReadsAsHavingNone()
    {
        // An accumulation register's resource has no indexing, where an information register's
        // does. Which type carries which property is not a family rule, so the reading asks the
        // type; a list of names ending in Resource would answer for both the same way.
        AccumulationRegister register = MdClassFactory.eINSTANCE.createAccumulationRegister();
        register.setName("Stock"); //$NON-NLS-1$
        AccumulationRegisterResource resource =
            MdClassFactory.eINSTANCE.createAccumulationRegisterResource();
        resource.setName("Quantity"); //$NON-NLS-1$
        register.getResources().add(resource);

        String[] row = rowOf(MetadataFormatter.format(register, true, "ru"), "Quantity"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull("the resource belongs in the wide table", row); //$NON-NLS-1$
        assertEquals("a property this type does not have stays a dash rather than becoming a guess", //$NON-NLS-1$
            DASH, row[INDEXING]);
    }
}
