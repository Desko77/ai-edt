/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccounts;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.ReportAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Task;

/**
 * A creating operation answers the refusal of an unbounded String inside a composition before
 * the element it was asked for exists.
 * <p>
 * The refusal is known from the arguments and the carrier alone, so it is asked while the
 * element is still detached; after it, the owner holds no element of that name. Each row here
 * is one creating operation's carrier pair - an object attribute, a tabular-section attribute,
 * the three register field kinds, a Task addressing attribute, the two accounting-flag kinds -
 * and the non-stored row a report keeps: there the element is created and the composition
 * applied.
 * </p>
 */
public class ACreationRefusesItsCompositeStringBeforeTheElementExistsTest
{
    /** The composition every row asks for: an unbounded String beside a reference type. */
    private static final List<String> COMPOSITION = Arrays.asList("String,CatalogRef.Kinds"); //$NON-NLS-1$

    /** A configuration holding the catalog the composition references. */
    private static final Configuration CONFIGURATION = configurationWithCatalog("Kinds"); //$NON-NLS-1$

    /** Qualifiers that name no length - what a call without the {@code length} argument carries. */
    private static final BmDefinedTypeHelper.QualifierOptions UNBOUNDED =
        new BmDefinedTypeHelper.QualifierOptions();

    /**
     * Builds a configuration holding the named catalog, so the composition's reference type
     * resolves.
     *
     * @param name the catalog's name
     * @return the configuration
     */
    private static Configuration configurationWithCatalog(String name)
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        configuration.getCatalogs().add(catalog);
        return configuration;
    }

    /**
     * A catalog named Goods, the owner of an object attribute and of a tabular section.
     *
     * @return the catalog
     */
    private static Catalog catalog()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Goods"); //$NON-NLS-1$
        CONFIGURATION.getCatalogs().add(catalog);
        return catalog;
    }

    /**
     * The refusal of a creation the owner is asked for, asked the way the creating operations
     * ask it: on the detached element, before anything is attached.
     *
     * @param owner the owner the element is about to be created in
     * @param child the detached element of the kind the creation would attach
     * @return the refusal text, or <code>null</code>
     */
    private static String refusalOf(org.eclipse.emf.ecore.EObject owner, Object child)
    {
        return BmDefinedTypeHelper.creationCompositeStringRefusal(owner, child, COMPOSITION,
            UNBOUNDED);
    }

    /**
     * add_object_attribute on a database object: the attribute is refused while detached, and
     * the owner keeps no attribute of that name.
     */
    @Test
    public void anObjectAttributeIsRefusedBeforeItExists()
    {
        Catalog catalog = catalog();

        String refusal = refusalOf(catalog, MdClassFactory.eINSTANCE.createCatalogAttribute());

        assertNotNull("the composition without a length is refused", refusal); //$NON-NLS-1$
        assertTrue("the refusal asks for the length: " + refusal, //$NON-NLS-1$
            refusal.contains("'length'")); //$NON-NLS-1$
        assertTrue("the owner holds no attribute of that name", catalog.getAttributes().isEmpty()); //$NON-NLS-1$
    }

    /**
     * add_tabular_section_attribute: the column is refused while detached, and the tabular
     * section keeps no attribute of that name.
     */
    @Test
    public void aTabularSectionAttributeIsRefusedBeforeItExists()
    {
        Catalog catalog = catalog();
        CatalogTabularSection section = MdClassFactory.eINSTANCE.createCatalogTabularSection();
        section.setName("Lines"); //$NON-NLS-1$
        catalog.getTabularSections().add(section);

        String refusal = refusalOf(section, MdClassFactory.eINSTANCE.createTabularSectionAttribute());

        assertNotNull(refusal);
        assertTrue("the tabular section holds no attribute of that name", //$NON-NLS-1$
            section.getAttributes().isEmpty());
    }

    /**
     * add_register_field: the dimension, the resource and the plain attribute of a register are
     * each refused while detached.
     */
    @Test
    public void everyRegisterFieldKindIsRefusedBeforeItExists()
    {
        InformationRegister register = MdClassFactory.eINSTANCE.createInformationRegister();
        register.setName("Turnover"); //$NON-NLS-1$
        CONFIGURATION.getInformationRegisters().add(register);

        assertNotNull(refusalOf(register,
            MdClassFactory.eINSTANCE.createInformationRegisterDimension()));
        assertNotNull(refusalOf(register,
            MdClassFactory.eINSTANCE.createInformationRegisterResource()));
        assertNotNull(refusalOf(register,
            MdClassFactory.eINSTANCE.createInformationRegisterAttribute()));

        assertTrue("the register holds no field of that name", register.getDimensions().isEmpty()); //$NON-NLS-1$
        assertTrue(register.getResources().isEmpty());
        assertTrue(register.getAttributes().isEmpty());
    }

    /**
     * add_addressing_attribute and the addressingAttributes of set_task_addressing: the
     * addressing attribute of a Task is refused while detached.
     */
    @Test
    public void anAddressingAttributeIsRefusedBeforeItExists()
    {
        Task task = MdClassFactory.eINSTANCE.createTask();
        task.setName("Task"); //$NON-NLS-1$
        CONFIGURATION.getTasks().add(task);

        String refusal = refusalOf(task, MdClassFactory.eINSTANCE.createAddressingAttribute());

        assertNotNull(refusal);
        assertTrue("the Task holds no addressing attribute of that name", //$NON-NLS-1$
            task.getAddressingAttributes().isEmpty());
    }

    /**
     * add_accounting_flag and add_ext_dimension_accounting_flag: both accounting-flag kinds of
     * a chart of accounts are refused while detached.
     */
    @Test
    public void bothAccountingFlagKindsAreRefusedBeforeTheyExist()
    {
        ChartOfAccounts chart = MdClassFactory.eINSTANCE.createChartOfAccounts();
        chart.setName("Main"); //$NON-NLS-1$
        CONFIGURATION.getChartsOfAccounts().add(chart);

        assertNotNull(refusalOf(chart, MdClassFactory.eINSTANCE.createAccountingFlag()));
        assertNotNull(refusalOf(chart,
            MdClassFactory.eINSTANCE.createExtDimensionAccountingFlag()));

        assertTrue("the chart holds no flag of that name", chart.getAccountingFlags().isEmpty()); //$NON-NLS-1$
        assertTrue(chart.getExtDimensionAccountingFlags().isEmpty());
    }

    /**
     * The refusal is the composition refusal itself: it names the missing length and says
     * nothing about a creation, because after it there is nothing on disk to talk about.
     */
    @Test
    public void theRefusalNamesTheLengthAndNotACreation()
    {
        String refusal = refusalOf(catalog(), MdClassFactory.eINSTANCE.createCatalogAttribute());

        assertTrue(refusal.contains("A composition of more than one type that includes String " //$NON-NLS-1$
            + "requires the 'length' argument")); //$NON-NLS-1$
        assertFalse("nothing was created, so the refusal cannot say it was", //$NON-NLS-1$
            refusal.contains("was created")); //$NON-NLS-1$
        assertFalse(refusal.contains("was not applied")); //$NON-NLS-1$
    }

    /**
     * The follow-up that names the length goes through: the pair passes the pre-creation
     * question, the element attaches, and the write puts both types and the length on it.
     */
    @Test
    public void aBoundedCompositionCreatesTheElement()
    {
        Catalog catalog = catalog();
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("KindOrText"); //$NON-NLS-1$
        BmDefinedTypeHelper.QualifierOptions bounded = new BmDefinedTypeHelper.QualifierOptions();
        bounded.length = Integer.valueOf(20);

        assertNull("a composition with a length is not refused", //$NON-NLS-1$
            BmDefinedTypeHelper.creationCompositeStringRefusal(catalog, attribute, COMPOSITION,
                bounded));

        catalog.getAttributes().add(attribute);
        BmDefinedTypeHelper.TypesResult applied = BmDefinedTypeHelper.setTypes(attribute,
            (IProject)null, CONFIGURATION, COMPOSITION, bounded);

        assertTrue(String.valueOf(applied.error), applied.ok);
        assertEquals("the composition landed as two types", 2, //$NON-NLS-1$
            attribute.getType().getTypes().size());
        assertEquals("the String carries the requested length", 20, //$NON-NLS-1$
            attribute.getType().getStringQualifiers().getLength());
    }

    /**
     * add_object_attribute on a report: the attribute of a report is computed at run time and
     * stored nowhere, so the composition is kept and the element is created.
     */
    @Test
    public void anAttributeOfAReportIsCreated()
    {
        Report report = MdClassFactory.eINSTANCE.createReport();
        report.setName("Analysis"); //$NON-NLS-1$
        CONFIGURATION.getReports().add(report);
        ReportAttribute attribute = MdClassFactory.eINSTANCE.createReportAttribute();
        attribute.setName("KindOrText"); //$NON-NLS-1$

        assertNull("a report keeps the composition", refusalOf(report, attribute)); //$NON-NLS-1$

        report.getAttributes().add(attribute);
        BmDefinedTypeHelper.TypesResult applied = BmDefinedTypeHelper.setTypes(attribute,
            (IProject)null, CONFIGURATION, COMPOSITION, UNBOUNDED);

        assertTrue(String.valueOf(applied.error), applied.ok);
        assertEquals("the element exists and carries both types", 2, //$NON-NLS-1$
            attribute.getType().getTypes().size());
    }
}
