/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The module generate_event_handlers writes to is named by a path under src/, and a register's
 * handlers go to its record set module.
 * <p>
 * The module writer takes a path to a {@code .bsl} file and refuses an FQN, so a handler written
 * under {@code Catalog.X.ObjectModule} never reached the module.
 * </p>
 */
public class HandlersGoToTheModuleOfTheirKindTest
{
    @Test
    public void aCatalogWritesToItsObjectModule()
    {
        assertEquals("Catalogs/Товары/ObjectModule.bsl", //$NON-NLS-1$
            GenerateEventHandlersTool.handlerModulePath("Catalog.Товары", "Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aRussianTypeNameGivesTheSameFolder()
    {
        assertEquals("Documents/Заказ/ObjectModule.bsl", //$NON-NLS-1$
            GenerateEventHandlersTool.handlerModulePath("Документ.Заказ", "Document")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aRegisterWritesToItsRecordSetModule()
    {
        assertEquals("InformationRegisters/Цены/RecordSetModule.bsl", //$NON-NLS-1$
            GenerateEventHandlersTool.handlerModulePath("InformationRegister.Цены", //$NON-NLS-1$
                "InformationRegister")); //$NON-NLS-1$
        assertEquals("AccumulationRegisters/Остатки/RecordSetModule.bsl", //$NON-NLS-1$
            GenerateEventHandlersTool.handlerModulePath("AccumulationRegister.Остатки", //$NON-NLS-1$
                "AccumulationRegister")); //$NON-NLS-1$
    }

    @Test
    public void somethingThatIsNotTypeDotNameHasNoPath()
    {
        assertNull(GenerateEventHandlersTool.handlerModulePath("Catalog", "Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(GenerateEventHandlersTool.handlerModulePath("Catalog.X.Form", "Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(GenerateEventHandlersTool.handlerModulePath("NoSuchType.X", "Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(GenerateEventHandlersTool.handlerModulePath(null, "Catalog")); //$NON-NLS-1$
    }
}
