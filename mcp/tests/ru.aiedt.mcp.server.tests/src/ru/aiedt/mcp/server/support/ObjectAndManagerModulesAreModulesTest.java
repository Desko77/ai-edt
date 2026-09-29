/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Holds which qualified names name a module.
 * <p>
 * Measured on a live tree with {@code methodLevel} on: the nodes are
 * {@code Catalog.Валюты.ObjectModule}, {@code Catalog.Валюты.ManagerModule} and
 * {@code Catalog.Валюты.Form.ФормаСписка.Form.Module}, and only the last ends in {@code .Module}.
 * The first version of the check knew one suffix, so object and manager modules were not modules:
 * their methods never reached {@code sections}, and no decision could be addressed at them.
 * </p>
 */
public class ObjectAndManagerModulesAreModulesTest
{
    @Test
    public void everyModuleSuffixNamesAModule()
    {
        assertTrue(BmComparisonHelper.isModuleName("CommonModule.Api.Module")); //$NON-NLS-1$
        assertTrue(BmComparisonHelper.isModuleName("Catalog.Валюты.ObjectModule")); //$NON-NLS-1$
        assertTrue(BmComparisonHelper.isModuleName("Catalog.Валюты.ManagerModule")); //$NON-NLS-1$
        assertTrue(BmComparisonHelper.isModuleName("InformationRegister.Курсы.RecordSetModule")); //$NON-NLS-1$
        assertTrue(BmComparisonHelper.isModuleName("Constant.Флаг.ValueManagerModule")); //$NON-NLS-1$
        assertTrue(BmComparisonHelper.isModuleName("CommonCommand.Печать.CommandModule")); //$NON-NLS-1$
        assertTrue(BmComparisonHelper.isModuleName("Catalog.Валюты.Form.ФормаСписка.Form.Module")); //$NON-NLS-1$
    }

    @Test
    public void anObjectIsNotAModule()
    {
        assertFalse(BmComparisonHelper.isModuleName("Catalog.Валюты")); //$NON-NLS-1$
        assertFalse(BmComparisonHelper.isModuleName("Catalog.Валюты.Form.ФормаСписка")); //$NON-NLS-1$
        assertFalse(BmComparisonHelper.isModuleName(null));
    }

    @Test
    public void methodsOfTheObjectAndManagerModulesReachTheSections()
    {
        // The tree as the environment builds it with parseBslModuleStructure on: a module root and
        // its methods, each a top node with a full name of its own. What is asserted is the walk's
        // half of the promise - a piece of a module lands in sections under that module's name -
        // which is what makes a decision about one method possible at all.
        FakeComparison fake = FakeComparison.over(FakeComparison.plain(1L, null)
            .child(FakeComparison.top(2L, "Catalog.Валюты.ObjectModule", //$NON-NLS-1$
                FakeComparison.changedOnBothSides())
                    .child(FakeComparison.top(3L, "Catalog.Валюты.ObjectModule.ПередЗаписью", //$NON-NLS-1$
                        FakeComparison.changedOnBothSides()))
                    .child(FakeComparison.top(4L, "Catalog.Валюты.ObjectModule.КодыВалют", //$NON-NLS-1$
                        FakeComparison.changedOnBothSides())))
            .child(FakeComparison.top(5L, "Catalog.Валюты.ManagerModule", //$NON-NLS-1$
                FakeComparison.changedOnBothSides())
                    .child(FakeComparison.top(6L, "Catalog.Валюты.ManagerModule.КодыВалют", //$NON-NLS-1$
                        FakeComparison.changedOnBothSides()))));
        BmComparisonHelper.Outcome outcome = new BmComparisonHelper.Outcome();
        outcome.threeWay = true;
        outcome.sectionsWanted = true;

        BmComparisonHelper.readTree(fake.session(), outcome, new BmComparisonHelper.Page());

        assertTrue("the object module's methods are pieces of it", sectionsOf(outcome, 3L)); //$NON-NLS-1$
        assertTrue(sectionsOf(outcome, 4L));
        assertTrue("and so are the manager module's, which the one-suffix check never saw", //$NON-NLS-1$
            sectionsOf(outcome, 6L));
        assertTrue(moduleOf(outcome, "Catalog.Валюты.ObjectModule")); //$NON-NLS-1$
        assertTrue(moduleOf(outcome, "Catalog.Валюты.ManagerModule")); //$NON-NLS-1$
    }

    private static boolean sectionsOf(BmComparisonHelper.Outcome outcome, long nodeId)
    {
        for (BmComparisonHelper.Section section : outcome.sections)
        {
            if (section.nodeId == nodeId)
            {
                return true;
            }
        }
        return false;
    }

    private static boolean moduleOf(BmComparisonHelper.Outcome outcome, String module)
    {
        for (BmComparisonHelper.Section section : outcome.sections)
        {
            if (module.equals(section.module))
            {
                return true;
            }
        }
        return false;
    }
}
