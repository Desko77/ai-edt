/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCodeType;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccounts;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccountsPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypes;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypesCodeType;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypesPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * A predefined item's code is written in the kind its owner's code type declares.
 * <p>
 * A Catalog and a ChartOfCalculationTypes keep their predefined code in an {@code mcore.Value}
 * whose kind comes from the owner, while a ChartOfAccounts and a ChartOfCharacteristicTypes keep
 * it in a String. Only a String setter used to be reached, so a Catalog or ChartOfCalculationTypes
 * code was never written: the call answered a warning beside {@code codeApplied: false} - success
 * reported for a write that had not happened, with the code left for the caller to set by hand.
 * </p>
 */
public class APredefinedCodeTakesTheKindOfItsOwnerTest
{
    private static Catalog catalogWithCodeType(CatalogCodeType codeType)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setCodeType(codeType);
        return catalog;
    }

    private static EList<Object> noExistingItems()
    {
        return new BasicEList<>();
    }

    /** A numeric catalog code reaches the item as a number, not as text. */
    @Test
    public void aNumberCodeTypeGetsANumberValue()
    {
        Catalog catalog = catalogWithCodeType(CatalogCodeType.NUMBER);
        CatalogPredefinedItem item = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();

        assertNull("a number fits a Number code type", //$NON-NLS-1$
            PredefinedOps.applyPredefinedCode(catalog, item, noExistingItems(), "42")); //$NON-NLS-1$

        assertTrue("the item carries a value", item.getCode() instanceof NumberValue); //$NON-NLS-1$
        assertEquals("and the value is the number itself", 0, //$NON-NLS-1$
            new BigDecimal("42").compareTo(((NumberValue) item.getCode()).getValue())); //$NON-NLS-1$
    }

    /** The same is true of a plan of calculation types, which holds its code the same way. */
    @Test
    public void aCalculationTypeCodeIsANumberToo()
    {
        ChartOfCalculationTypes types = MdClassFactory.eINSTANCE.createChartOfCalculationTypes();
        types.setCodeType(ChartOfCalculationTypesCodeType.NUMBER);
        ChartOfCalculationTypesPredefinedItem item =
            MdClassFactory.eINSTANCE.createChartOfCalculationTypesPredefinedItem();

        assertNull("a number fits a Number code type", //$NON-NLS-1$
            PredefinedOps.applyPredefinedCode(types, item, noExistingItems(), "7")); //$NON-NLS-1$

        assertTrue("the item carries a value", item.getCode() instanceof NumberValue); //$NON-NLS-1$
        assertEquals("and the value is the number itself", 0, //$NON-NLS-1$
            new BigDecimal("7").compareTo(((NumberValue) item.getCode()).getValue())); //$NON-NLS-1$
    }

    /** A textual catalog code is still a value, a String one, not a String on the feature. */
    @Test
    public void aTextCodeTypeGetsAStringValue()
    {
        Catalog catalog = catalogWithCodeType(CatalogCodeType.STRING);
        CatalogPredefinedItem item = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();

        assertNull("text fits a String code type", //$NON-NLS-1$
            PredefinedOps.applyPredefinedCode(catalog, item, noExistingItems(), "001")); //$NON-NLS-1$

        assertTrue("the item carries a value", item.getCode() instanceof StringValue); //$NON-NLS-1$
        assertEquals("which holds the text as it came", "001", //$NON-NLS-1$ //$NON-NLS-2$
            ((StringValue) item.getCode()).getValue());
    }

    /** A word where the owner declares a Number code is refused, and nothing is written. */
    @Test
    public void aWordOnANumberCodeTypeIsRefused()
    {
        Catalog catalog = catalogWithCodeType(CatalogCodeType.NUMBER);
        CatalogPredefinedItem item = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();

        String reason = PredefinedOps.applyPredefinedCode(catalog, item, noExistingItems(),
            "Товар"); //$NON-NLS-1$

        assertNotNull("the reason is named", reason); //$NON-NLS-1$
        assertTrue("and it is about the code and the type: " + reason, //$NON-NLS-1$
            reason.contains("Товар") && reason.contains("Number")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("the item was left without a code", item.getCode()); //$NON-NLS-1$
    }

    /** An owner whose code type cannot be read is a refusal, not a warning beside a success. */
    @Test
    public void anOwnerWithoutACodeTypeIsRefused()
    {
        // Any object the code type cannot be read from stands in here: what matters is that the
        // absence is answered, not that the value is guessed.
        CatalogPredefinedItem item = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();

        String reason = PredefinedOps.applyPredefinedCode(
            MdClassFactory.eINSTANCE.createConfiguration(), item, noExistingItems(), "5"); //$NON-NLS-1$

        assertNotNull("the reason is named", reason); //$NON-NLS-1$
        assertTrue("and it says the type could not be read: " + reason, //$NON-NLS-1$
            reason.contains("code type")); //$NON-NLS-1$
        assertNull("the item was left without a code", item.getCode()); //$NON-NLS-1$
    }

    /** A ChartOfAccounts keeps its code a plain string, exactly as before. */
    @Test
    public void aChartOfAccountsCodeStaysAString()
    {
        ChartOfAccounts accounts = MdClassFactory.eINSTANCE.createChartOfAccounts();
        ChartOfAccountsPredefinedItem item =
            MdClassFactory.eINSTANCE.createChartOfAccountsPredefinedItem();

        assertNull("a string setter takes the text as it comes", //$NON-NLS-1$
            PredefinedOps.applyPredefinedCode(accounts, item, noExistingItems(), "01")); //$NON-NLS-1$
        assertEquals("01", item.getCode()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A code another item of the same owner carries is refused; a free one goes through. */
    @Test
    public void aCodeAnotherItemCarriesIsRefused()
    {
        Catalog catalog = catalogWithCodeType(CatalogCodeType.NUMBER);
        CatalogPredefinedItem first = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
        first.setName("Первый"); //$NON-NLS-1$
        assertNull("the first item takes the code", //$NON-NLS-1$
            PredefinedOps.applyPredefinedCode(catalog, first, noExistingItems(), "42")); //$NON-NLS-1$
        EList<Object> existing = new BasicEList<>();
        existing.add(first);

        CatalogPredefinedItem second = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
        String reason = PredefinedOps.applyPredefinedCode(catalog, second, existing, "42"); //$NON-NLS-1$

        assertNotNull("the repeat is refused", reason); //$NON-NLS-1$
        assertTrue("and the item holding it is named: " + reason, //$NON-NLS-1$
            reason.contains("Первый")); //$NON-NLS-1$
        assertNull("the second item was left without a code", second.getCode()); //$NON-NLS-1$

        assertNull("a code nobody holds goes through", //$NON-NLS-1$
            PredefinedOps.applyPredefinedCode(catalog, second, existing, "43")); //$NON-NLS-1$
    }
}
