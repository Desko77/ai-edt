/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.TabularSectionAttribute;

/**
 * When the base object a borrowed shell extends is gone, only what the shell borrowed is reported
 * gone: a tabular section the extension added itself, with its attributes, is left out.
 */
public class AGoneBaseMarksOnlyBorrowedSectionsTest
{
    /**
     * @param name the section name
     * @param borrowed whether the section carries a link to the base
     * @return a section holding one attribute of the same kind
     */
    private static CatalogTabularSection section(String name, boolean borrowed)
    {
        CatalogTabularSection section = MdClassFactory.eINSTANCE.createCatalogTabularSection();
        section.setName(name);
        TabularSectionAttribute attribute = MdClassFactory.eINSTANCE.createTabularSectionAttribute();
        attribute.setName("Amount"); //$NON-NLS-1$
        if (borrowed)
        {
            section.setExtendedConfigurationObject(UUID.randomUUID());
            attribute.setExtendedConfigurationObject(UUID.randomUUID());
        }
        section.getAttributes().add(attribute);
        return section;
    }

    /** The borrowed section and its attribute are gone; the extension's own section is not. */
    @Test
    public void anOwnSectionIsNotReportedGone()
    {
        Catalog shell = MdClassFactory.eINSTANCE.createCatalog();
        shell.setName("Products"); //$NON-NLS-1$
        shell.setExtendedConfigurationObject(UUID.randomUUID());
        shell.getTabularSections().add(section("Prices", true)); //$NON-NLS-1$
        shell.getTabularSections().add(section("Notes", false)); //$NON-NLS-1$

        BorrowedSyncReader.Report report = new BorrowedSyncReader.Report();
        BorrowedSyncReader.markChildrenGone(report, shell, "Catalog.Products"); //$NON-NLS-1$

        List<String> gone = new ArrayList<>();
        for (BorrowedSyncReader.Row row : report.rows)
        {
            assertEquals(BorrowedSyncClassification.Status.SOURCE_GONE, row.status);
            gone.add(row.fqn);
        }
        assertEquals(List.of("Catalog.Products.TabularSection.Prices", //$NON-NLS-1$
            "Catalog.Products.TabularSection.Prices.Attribute.Amount"), gone); //$NON-NLS-1$
    }
}
