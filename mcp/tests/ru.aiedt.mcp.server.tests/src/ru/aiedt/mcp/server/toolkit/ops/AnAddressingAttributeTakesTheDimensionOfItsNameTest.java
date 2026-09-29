/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterDimension;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * The addressing register's dimension a Task addressing attribute is bound to when the call
 * names it by its short name or does not name it at all.
 * <p>
 * EDT reports an addressing attribute without a dimension as an error, so set_task_addressing
 * binds such an attribute to the register's dimension of the same name and refuses when there
 * is none; the lookup and the list the refusal hands back are pinned here.
 * </p>
 */
public class AnAddressingAttributeTakesTheDimensionOfItsNameTest
{
    private static InformationRegister registerWith(String... names)
    {
        InformationRegister register = MdClassFactory.eINSTANCE.createInformationRegister();
        register.setName("Адресация"); //$NON-NLS-1$
        for (String name : names)
        {
            InformationRegisterDimension d = MdClassFactory.eINSTANCE.createInformationRegisterDimension();
            d.setName(name);
            register.getDimensions().add(d);
        }
        return register;
    }

    @Test
    public void aDimensionIsFoundByNameWithoutRegardToCase()
    {
        InformationRegister register = registerWith("Исполнитель", "Подразделение"); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(register.getDimensions().get(1),
            SpecializedOps.dimensionNamed(register, "подразделение")); //$NON-NLS-1$
        assertNull(SpecializedOps.dimensionNamed(register, "Проверяющий")); //$NON-NLS-1$
    }

    @Test
    public void noRegisterHasNoDimension()
    {
        assertNull(SpecializedOps.dimensionNamed(null, "Исполнитель")); //$NON-NLS-1$
        assertEquals("none", SpecializedOps.dimensionNames(null)); //$NON-NLS-1$
    }

    @Test
    public void theRefusalListsTheDimensionsThereAre()
    {
        assertEquals("Исполнитель, Подразделение", //$NON-NLS-1$
            SpecializedOps.dimensionNames(registerWith("Исполнитель", "Подразделение"))); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
