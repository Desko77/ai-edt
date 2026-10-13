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
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessorAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessorTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessorTabularSectionAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.ReportAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.ReportTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.ReportTabularSectionAttribute;

/**
 * A report and a data processor keep their attributes and tabular sections in memory.
 * <p>
 * The platform builds no table for either of them, so their type descriptions never become
 * columns, and the refusal of an unbounded String inside a composition does not apply. The
 * carriers are {@code BasicFeature} objects all the same - {@code ReportAttribute},
 * {@code ReportTabularSectionAttribute}, {@code DataProcessorAttribute} and their external
 * counterparts - so the carrier class alone does not answer the question. The root object of the
 * containment chain does.
 * </p>
 */
public class AReportKeepsItsAttributesInMemoryTest
{
    /** The composition this test writes: an unbounded String beside a reference type. */
    private static final List<String> COMPOSITION = Arrays.asList("String,CatalogRef.Kinds"); //$NON-NLS-1$

    /**
     * A configuration holding the catalog the composition references - without it the reference
     * type would not resolve and the write would be refused for the wrong reason.
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
     * A report attribute of the given name, placed in the report's own collection.
     *
     * @param report the owning report
     * @param name the attribute name
     * @return the attribute
     */
    private static ReportAttribute attributeOf(Report report, String name)
    {
        ReportAttribute attribute = MdClassFactory.eINSTANCE.createReportAttribute();
        attribute.setName(name);
        report.getAttributes().add(attribute);
        return attribute;
    }

    /**
     * Writes the composition and answers what the builder said.
     *
     * @param carrier the attribute to type
     * @param configuration the configuration the reference resolves against
     * @return the result
     */
    private static BmDefinedTypeHelper.TypesResult write(MdObject carrier, Configuration configuration)
    {
        return BmDefinedTypeHelper.setTypes(carrier, (IProject)null, configuration,
            COMPOSITION, new BmDefinedTypeHelper.QualifierOptions());
    }

    /**
     * The four root objects whose attributes are not stored are not carriers of a table column,
     * wherever in the object the attribute sits.
     */
    @Test
    public void aReportAndADataProcessorDoNotStoreTheirAttributes()
    {
        Report report = MdClassFactory.eINSTANCE.createReport();
        report.setName("Sales"); //$NON-NLS-1$
        assertFalse("a report attribute", //$NON-NLS-1$
            BmDefinedTypeHelper.typeIsStoredInDatabase(attributeOf(report, "Kind"))); //$NON-NLS-1$

        ReportTabularSection section = MdClassFactory.eINSTANCE.createReportTabularSection();
        section.setName("Prices"); //$NON-NLS-1$
        report.getTabularSections().add(section);
        ReportTabularSectionAttribute sectionAttribute =
            MdClassFactory.eINSTANCE.createReportTabularSectionAttribute();
        sectionAttribute.setName("Kind"); //$NON-NLS-1$
        section.getAttributes().add(sectionAttribute);
        assertFalse("a report tabular section attribute", //$NON-NLS-1$
            BmDefinedTypeHelper.typeIsStoredInDatabase(sectionAttribute));

        DataProcessor processor = MdClassFactory.eINSTANCE.createDataProcessor();
        processor.setName("Filler"); //$NON-NLS-1$
        DataProcessorAttribute processorAttribute =
            MdClassFactory.eINSTANCE.createDataProcessorAttribute();
        processorAttribute.setName("Kind"); //$NON-NLS-1$
        processor.getAttributes().add(processorAttribute);
        assertFalse("a data processor attribute", //$NON-NLS-1$
            BmDefinedTypeHelper.typeIsStoredInDatabase(processorAttribute));

        // An external report and an external data processor root their own .mdo, so the chain ends
        // at them rather than at a configuration.
        ExternalReport externalReport = MdClassFactory.eINSTANCE.createExternalReport();
        externalReport.setName("ExternalSales"); //$NON-NLS-1$
        assertFalse("an external report attribute", //$NON-NLS-1$
            BmDefinedTypeHelper.typeIsStoredInDatabase(attributeOfExternal(externalReport)));

        ExternalDataProcessor externalProcessor = MdClassFactory.eINSTANCE.createExternalDataProcessor();
        externalProcessor.setName("ExternalFiller"); //$NON-NLS-1$
        // An external data processor holds the same DataProcessorAttribute its database
        // counterpart does - the model declares no separate class for the external kind.
        DataProcessorAttribute externalProcessorAttribute =
            MdClassFactory.eINSTANCE.createDataProcessorAttribute();
        externalProcessorAttribute.setName("Kind"); //$NON-NLS-1$
        externalProcessor.getAttributes().add(externalProcessorAttribute);
        assertFalse("an external data processor attribute", //$NON-NLS-1$
            BmDefinedTypeHelper.typeIsStoredInDatabase(externalProcessorAttribute));

        // The database objects are unchanged: an attribute of a catalog becomes a column.
        Configuration configuration = configurationWithCatalog();
        Catalog catalog = configuration.getCatalogs().get(0);
        CatalogAttribute catalogAttribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        catalogAttribute.setName("Kind"); //$NON-NLS-1$
        catalog.getAttributes().add(catalogAttribute);
        assertTrue("a catalog attribute", //$NON-NLS-1$
            BmDefinedTypeHelper.typeIsStoredInDatabase(catalogAttribute));
    }

    /**
     * An attribute of an external report, which holds {@code ReportAttribute} items the way a
     * report does.
     *
     * @param report the owning external report
     * @return the attribute
     */
    private static ReportAttribute attributeOfExternal(ExternalReport report)
    {
        ReportAttribute attribute = MdClassFactory.eINSTANCE.createReportAttribute();
        attribute.setName("Kind"); //$NON-NLS-1$
        report.getAttributes().add(attribute);
        return attribute;
    }

    /**
     * The write path follows the same distinction: a report and a data processor take the
     * composition, a catalog refuses it.
     */
    @Test
    public void theCompositionIsWrittenOnAReportAndRefusedOnACatalog()
    {
        Configuration configuration = configurationWithCatalog();

        Report report = MdClassFactory.eINSTANCE.createReport();
        report.setName("Sales"); //$NON-NLS-1$
        configuration.getReports().add(report);
        ReportAttribute reportAttribute = attributeOf(report, "Kind"); //$NON-NLS-1$
        BmDefinedTypeHelper.TypesResult onReport = write(reportAttribute, configuration);
        assertTrue(String.valueOf(onReport.error), onReport.ok);
        assertEquals(2, reportAttribute.getType().getTypes().size());

        // The attribute of a report's tabular section is two levels below the root.
        ReportTabularSection section = MdClassFactory.eINSTANCE.createReportTabularSection();
        section.setName("Prices"); //$NON-NLS-1$
        report.getTabularSections().add(section);
        ReportTabularSectionAttribute sectionAttribute =
            MdClassFactory.eINSTANCE.createReportTabularSectionAttribute();
        sectionAttribute.setName("Kind"); //$NON-NLS-1$
        section.getAttributes().add(sectionAttribute);
        BmDefinedTypeHelper.TypesResult onSection = write(sectionAttribute, configuration);
        assertTrue(String.valueOf(onSection.error), onSection.ok);

        DataProcessor processor = MdClassFactory.eINSTANCE.createDataProcessor();
        processor.setName("Filler"); //$NON-NLS-1$
        configuration.getDataProcessors().add(processor);
        DataProcessorAttribute processorAttribute =
            MdClassFactory.eINSTANCE.createDataProcessorAttribute();
        processorAttribute.setName("Kind"); //$NON-NLS-1$
        processor.getAttributes().add(processorAttribute);
        BmDefinedTypeHelper.TypesResult onProcessor = write(processorAttribute, configuration);
        assertTrue(String.valueOf(onProcessor.error), onProcessor.ok);

        DataProcessorTabularSection processorSection =
            MdClassFactory.eINSTANCE.createDataProcessorTabularSection();
        processorSection.setName("Prices"); //$NON-NLS-1$
        processor.getTabularSections().add(processorSection);
        DataProcessorTabularSectionAttribute inSection =
            MdClassFactory.eINSTANCE.createDataProcessorTabularSectionAttribute();
        inSection.setName("Kind"); //$NON-NLS-1$
        processorSection.getAttributes().add(inSection);
        BmDefinedTypeHelper.TypesResult onProcessorSection = write(inSection, configuration);
        assertTrue(String.valueOf(onProcessorSection.error), onProcessorSection.ok);

        // An external report and an external data processor have no configuration at all; the
        // reference type resolves through the configuration the caller passes and the string needs
        // no project.
        ExternalReport externalReport = MdClassFactory.eINSTANCE.createExternalReport();
        externalReport.setName("ExternalSales"); //$NON-NLS-1$
        BmDefinedTypeHelper.TypesResult onExternalReport =
            write(attributeOfExternal(externalReport), configuration);
        assertTrue(String.valueOf(onExternalReport.error), onExternalReport.ok);

        ExternalDataProcessor externalProcessor =
            MdClassFactory.eINSTANCE.createExternalDataProcessor();
        externalProcessor.setName("ExternalFiller"); //$NON-NLS-1$
        DataProcessorAttribute externalAttribute = MdClassFactory.eINSTANCE.createDataProcessorAttribute();
        externalAttribute.setName("Kind"); //$NON-NLS-1$
        externalProcessor.getAttributes().add(externalAttribute);
        BmDefinedTypeHelper.TypesResult onExternalProcessor = write(externalAttribute, configuration);
        assertTrue(String.valueOf(onExternalProcessor.error), onExternalProcessor.ok);

        // A catalog keeps refusing it, and writes nothing.
        Catalog catalog = configuration.getCatalogs().get(0);
        CatalogAttribute catalogAttribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        catalogAttribute.setName("Kind"); //$NON-NLS-1$
        catalog.getAttributes().add(catalogAttribute);
        BmDefinedTypeHelper.TypesResult onCatalog = write(catalogAttribute, configuration);
        assertFalse(String.valueOf(onCatalog.error), onCatalog.ok);
        assertTrue(String.valueOf(onCatalog.error), onCatalog.error.contains("'length'")); //$NON-NLS-1$
        assertTrue("nothing is written on a refusal", //$NON-NLS-1$
            catalogAttribute.getType() == null || catalogAttribute.getType().getTypes().isEmpty());
    }
}
