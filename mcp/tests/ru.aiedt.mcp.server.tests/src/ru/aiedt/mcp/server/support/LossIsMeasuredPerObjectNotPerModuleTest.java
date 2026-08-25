/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Pins the unit the loss check measures in.
 * <p>
 * <b>The first version of the check compared one file and called it an object.</b> It resolved the
 * name to a module and compared that, so an update that overwrote an object's .mdo while leaving its
 * module alone passed unmentioned, and an object whose module happened to match the delivery was
 * reported as wholly lost on that evidence alone. Neither claim was supported by what had been read.
 * </p>
 * <p>
 * The unit is now the object's directory - its .mdo, its modules, its forms, its templates - and the
 * several names one object reaches the conflict list under all resolve to it, so it is judged once.
 * </p>
 */
public class LossIsMeasuredPerObjectNotPerModuleTest
{
    @Test
    public void anObjectAndItsModuleResolveToOneDirectory()
    {
        // This is the deduplication the check depends on: the comparison names the object and its
        // module separately, and they stand or fall together.
        String object = BmComparisonHelper.objectDirectoryOf("Catalog.Partners"); //$NON-NLS-1$
        String module = BmComparisonHelper.objectDirectoryOf("Catalog.Partners.ManagerModule"); //$NON-NLS-1$
        assertEquals("Catalogs/Partners", object); //$NON-NLS-1$
        assertEquals(object, module);
    }

    @Test
    public void aFormBelowAnObjectResolvesToTheSameDirectory()
    {
        assertEquals("Documents/SalesOrder", //$NON-NLS-1$
            BmComparisonHelper.objectDirectoryOf("Document.SalesOrder.Form.ItemForm")); //$NON-NLS-1$
    }

    @Test
    public void theTypeDecidesTheFolderName()
    {
        // Not a plural bolted on with an "s": the folder name comes from the type catalogue, which
        // is the same source the rest of the server places objects with.
        assertEquals("CommonModules/Common", //$NON-NLS-1$
            BmComparisonHelper.objectDirectoryOf("CommonModule.Common")); //$NON-NLS-1$
        assertEquals("InformationRegisters/Rates", //$NON-NLS-1$
            BmComparisonHelper.objectDirectoryOf("InformationRegister.Rates")); //$NON-NLS-1$
    }

    @Test
    public void aNameThatIsNotAnObjectIsRefusedRatherThanGuessed()
    {
        // A guess here would compare the wrong directory and answer with confidence about it.
        assertNull(BmComparisonHelper.objectDirectoryOf("Configuration")); //$NON-NLS-1$
        assertNull(BmComparisonHelper.objectDirectoryOf("NotAType.Whatever")); //$NON-NLS-1$
        assertNull(BmComparisonHelper.objectDirectoryOf("")); //$NON-NLS-1$
        assertNull(BmComparisonHelper.objectDirectoryOf(null));
    }
}
