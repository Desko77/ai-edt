/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccounts;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Task;

/**
 * The composition refusal a creating operation answers before any borrowing runs is judged on the
 * owner the call sees without adopting anything.
 * <p>
 * A borrowing adopts its object in a commit of its own and survives the rollback of the transaction
 * a refused creation throws in, so the refusal has to be answered while the project still holds
 * nothing of what the call would borrow. The owner that decides is the object the address resolves
 * to without an adoption - for an extension that borrowed nothing, the base-configuration object
 * at the same address, whose class and chain of containers are the same, so the carrier decision is
 * the one the attached element would get through the same code.
 * </p>
 * <p>
 * The rows here hold the owner views and the element kinds the stored-carrier creations pass: an
 * object attribute, a tabular-section column, the register field kinds, a Task addressing attribute
 * and the accounting-flag kinds. Each refuses the composition without a length and keeps the view
 * empty, the composition that names a length passes, and an element of the same name takes the
 * question to the idempotent path the transaction judges by the length the model carries.
 * </p>
 */
public class APreBorrowRefusalIsJudgedOnTheUnadoptedOwnerTest
{
    /** The composition every row asks for: an unbounded String beside a reference type. */
    private static final List<String> COMPOSITION = Arrays.asList("String,CatalogRef.Kinds"); //$NON-NLS-1$

    /** Qualifiers that name no length - what a call without the {@code length} argument carries. */
    private static final BmDefinedTypeHelper.QualifierOptions UNBOUNDED =
        new BmDefinedTypeHelper.QualifierOptions();

    /**
     * The question a creating operation asks before any borrowing, on the owner view the call sees.
     *
     * @param ownerView the owner as the call sees it without adopting anything
     * @param tabularSection the tabular section a column call names, or {@code null}
     * @param childKind the kind of the element the call creates
     * @param name the name the call gives the element
     * @return the refusal text, or {@code null}
     */
    private static String refusalOf(com._1c.g5.v8.dt.metadata.mdclass.MdObject ownerView,
        String tabularSection, String childKind, String name)
    {
        return BmObjectHelper.preBorrowCompositeStringRefusalOnView(ownerView, tabularSection,
            childKind, name, COMPOSITION, UNBOUNDED);
    }

    /**
     * A catalog named Goods, standing for the base-configuration object an extension call sees.
     *
     * @return the catalog
     */
    private static Catalog catalog()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Goods"); //$NON-NLS-1$
        return catalog;
    }

    /**
     * The unadopted owner answers the refusal, and the question leaves nothing on it: no attribute
     * of the name, so the borrowing the refused call would have needed has nothing to hold onto.
     */
    @Test
    public void anUnadoptedOwnerAnswersTheRefusalAndKeepsNothing()
    {
        Catalog catalog = catalog();

        String refusal = refusalOf(catalog, null, "Attribute", "KindOrText"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull("the composition without a length is refused before any borrowing", refusal); //$NON-NLS-1$
        assertTrue("the refusal asks for the length: " + refusal, refusal.contains("'length'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the view holds no attribute of that name", catalog.getAttributes().isEmpty()); //$NON-NLS-1$
    }

    /**
     * The composition that names a length passes the question, so the call proceeds into its
     * borrowing and its creation the way it did before the question was asked.
     */
    @Test
    public void aBoundedCompositionAnswersNoRefusal()
    {
        Catalog catalog = catalog();
        BmDefinedTypeHelper.QualifierOptions bounded = new BmDefinedTypeHelper.QualifierOptions();
        bounded.length = Integer.valueOf(20);

        assertNull("a composition with a length is not refused", //$NON-NLS-1$
            BmObjectHelper.preBorrowCompositeStringRefusalOnView(catalog, null, "Attribute", //$NON-NLS-1$
                "KindOrText", COMPOSITION, bounded)); //$NON-NLS-1$
        assertTrue(catalog.getAttributes().isEmpty());
    }

    /**
     * An element of the same name takes the question away: that call is the idempotent path, which
     * the transaction judges by the length the model carries, and a refusal here would refuse a
     * call the write path accepts after borrowing for it.
     */
    @Test
    public void anElementOfTheSameNameTakesTheQuestionAway()
    {
        Catalog catalog = catalog();
        CatalogAttribute existing = MdClassFactory.eINSTANCE.createCatalogAttribute();
        existing.setName("KindOrText"); //$NON-NLS-1$
        catalog.getAttributes().add(existing);

        assertNull(refusalOf(catalog, null, "Attribute", "KindOrText")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the existing element is the only one", 1, catalog.getAttributes().size()); //$NON-NLS-1$
    }

    /**
     * A tabular-section column is judged through the section the base view holds, and a section
     * nothing answers for leaves the question to the check inside the transaction.
     */
    @Test
    public void aTabularSectionColumnIsJudgedThroughItsSection()
    {
        Catalog catalog = catalog();
        CatalogTabularSection section = MdClassFactory.eINSTANCE.createCatalogTabularSection();
        section.setName("Lines"); //$NON-NLS-1$
        catalog.getTabularSections().add(section);

        String refusal = refusalOf(catalog, "Lines", "Attribute", "LineNumber"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNotNull(refusal);
        assertTrue("the section holds no attribute of that name", //$NON-NLS-1$
            section.getAttributes().isEmpty());
        assertNull("a section nothing answers for leaves the question to the transaction", //$NON-NLS-1$
            refusalOf(catalog, "Nowhere", "Attribute", "LineNumber")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Every stored-carrier kind the creating operations pass answers on its owner: the register
     * field kinds, the Task addressing attribute and the two accounting-flag kinds.
     */
    @Test
    public void everyStoredCarrierKindAnswersOnItsOwner()
    {
        InformationRegister register = MdClassFactory.eINSTANCE.createInformationRegister();
        register.setName("Turnover"); //$NON-NLS-1$

        assertNotNull(refusalOf(register, null, "Dimension", "Kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(refusalOf(register, null, "Resource", "Amount")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(refusalOf(register, null, "Attribute", "Comment")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the register holds no field of those names", register.getDimensions().isEmpty()); //$NON-NLS-1$
        assertTrue(register.getResources().isEmpty());
        assertTrue(register.getAttributes().isEmpty());

        Task task = MdClassFactory.eINSTANCE.createTask();
        task.setName("Task"); //$NON-NLS-1$

        assertNotNull(refusalOf(task, null, "AddressingAttribute", "Executor")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(task.getAddressingAttributes().isEmpty());

        ChartOfAccounts chart = MdClassFactory.eINSTANCE.createChartOfAccounts();
        chart.setName("Main"); //$NON-NLS-1$

        assertNotNull(refusalOf(chart, null, "AccountingFlag", "IsQuantitative")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(refusalOf(chart, null, "ExtDimensionAccountingFlag", "Amounts")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(chart.getAccountingFlags().isEmpty());
        assertTrue(chart.getExtDimensionAccountingFlags().isEmpty());
    }

    /**
     * A report computes its attributes at run time and stores none of them, so the question on a
     * report owner answers nothing - the same rule the attached element gets, through the same
     * code.
     */
    @Test
    public void aReportOwnerAnswersNoRefusal()
    {
        Report report = MdClassFactory.eINSTANCE.createReport();
        report.setName("Analysis"); //$NON-NLS-1$

        assertNull(refusalOf(report, null, "Attribute", "KindOrText")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An owner nothing answers for - no configuration holds one - leaves the question to the check
     * inside the transaction.
     */
    @Test
    public void noOwnerViewAnswersNothing()
    {
        assertNull(refusalOf(null, null, "Attribute", "KindOrText")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
