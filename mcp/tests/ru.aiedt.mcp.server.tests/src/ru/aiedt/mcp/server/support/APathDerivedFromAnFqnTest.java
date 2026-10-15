/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The path a module's FQN names, for a module whose resource is addressed some other way - or has
 * no address at all.
 */
public class APathDerivedFromAnFqnTest
{
    /** A common module's FQN names its kind folder and its file. */
    @Test
    public void aCommonModuleNamesItsFolderAndFile()
    {
        assertEquals("CommonModules/Подготовка/Module.bsl", //$NON-NLS-1$
            BslCallGraphHelper.modulePathFromFqn("CommonModule.Подготовка.Module")); //$NON-NLS-1$
    }

    /** A form module's FQN names the owner's folder and the Forms level of the layout. */
    @Test
    public void aFormModuleNamesTheFormsLevel()
    {
        assertEquals("Catalogs/Товары/Forms/ФормаЭлемента/Module.bsl", //$NON-NLS-1$
            BslCallGraphHelper.modulePathFromFqn("Catalog.Товары.Form.ФормаЭлемента.Module")); //$NON-NLS-1$
    }

    /** A command module's FQN names the Commands level of the layout. */
    @Test
    public void aCommandModuleNamesTheCommandsLevel()
    {
        assertEquals("Catalogs/Товары/Commands/Команда/CommandModule.bsl", //$NON-NLS-1$
            BslCallGraphHelper.modulePathFromFqn("Catalog.Товары.Commands.Команда.CommandModule")); //$NON-NLS-1$
    }

    /** An FQN that names nothing answers nothing. */
    @Test
    public void anAbsentFqnNamesNothing()
    {
        assertNull(BslCallGraphHelper.modulePathFromFqn(null));
        assertNull(BslCallGraphHelper.modulePathFromFqn("")); //$NON-NLS-1$
    }
}
