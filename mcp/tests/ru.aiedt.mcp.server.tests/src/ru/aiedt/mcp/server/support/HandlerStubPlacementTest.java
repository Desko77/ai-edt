/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Where handler stubs go in an object module: into the module's own handler region, into a new one
 * inside its conditional-compilation framing, into a new one at the end, or into the whole shape
 * when the module is empty. A placement that misses its rule writes a module that no longer
 * compiles - a second region of the same name, or handlers outside the framing the object module
 * needs - so each rule has its own test.
 */
public class HandlerStubPlacementTest
{
    /** Two stub procedures the way the plan builders hand them over. */
    private static final String PROCEDURES =
        "Процедура ПередЗаписью(Отказ)\nКонецПроцедуры\n\n" //$NON-NLS-1$
            + "Процедура ПриЗаписи(Отказ)\nКонецПроцедуры\n\n"; //$NON-NLS-1$

    /** A module with nothing in it takes the whole shape: framing, region, stubs. */
    @Test
    public void anEmptyModuleTakesTheWholeShape()
    {
        HandlerStubPlacement.Plan noModule = HandlerStubPlacement.plan(null, PROCEDURES,
            BslScriptLanguage.RUSSIAN);
        assertTrue(noModule.createsModule);
        assertNull(noModule.insertBeforeLine);
        assertEquals("#Если Сервер Или ТолстыйКлиентОбычноеПриложение Или ВнешнееСоединение Тогда\n" //$NON-NLS-1$
            + "#Область ОбработчикиСобытий\n" //$NON-NLS-1$
            + PROCEDURES
            + "#КонецОбласти\n" //$NON-NLS-1$
            + "#КонецЕсли\n", noModule.text); //$NON-NLS-1$

        HandlerStubPlacement.Plan blank = HandlerStubPlacement.plan("   \n", PROCEDURES, //$NON-NLS-1$
            BslScriptLanguage.RUSSIAN);
        assertTrue("a blank module is an empty one", blank.createsModule); //$NON-NLS-1$
        assertNull(blank.insertBeforeLine);
    }

    /** A module that has the handler region gets its stubs before that region's own close. */
    @Test
    public void aModuleWithTheRegionGetsItsStubsInsideIt()
    {
        String module = "#Область ОбработчикиСобытий\n" //$NON-NLS-1$
            + "Процедура Существующая()\nКонецПроцедуры\n" //$NON-NLS-1$
            + "#Область Вложенная\nПроцедура ВложеннаяПроцедура()\nКонецПроцедуры\n#КонецОбласти\n" //$NON-NLS-1$
            + "Процедура Последняя()\nКонецПроцедуры\n" //$NON-NLS-1$
            + "#КонецОбласти\n"; //$NON-NLS-1$

        HandlerStubPlacement.Plan plan = HandlerStubPlacement.plan(module, PROCEDURES,
            BslScriptLanguage.RUSSIAN);

        assertFalse(plan.createsModule);
        assertEquals("the region's own close, past the nested one", Integer.valueOf(10), //$NON-NLS-1$
            plan.insertBeforeLine);
        assertEquals("no second region is created", PROCEDURES, plan.text); //$NON-NLS-1$
    }

    /** The region is found in either language and whatever the case of its name. */
    @Test
    public void theRegionIsFoundInEitherLanguage()
    {
        String module = "#Region eventhandlers\n" //$NON-NLS-1$
            + "Procedure Existing()\nEndProcedure\n" //$NON-NLS-1$
            + "#EndRegion\n"; //$NON-NLS-1$

        HandlerStubPlacement.Plan plan = HandlerStubPlacement.plan(module, PROCEDURES,
            BslScriptLanguage.RUSSIAN);

        assertEquals(Integer.valueOf(4), plan.insertBeforeLine);
        assertEquals(PROCEDURES, plan.text);
    }

    /** A module without the region but inside a framing gets a new region inside that framing. */
    @Test
    public void aFramedModuleGetsItsNewRegionInsideTheFraming()
    {
        String framedWithoutElse = "#Если Сервер Или ТолстыйКлиентОбычноеПриложение" //$NON-NLS-1$
            + " Или ВнешнееСоединение Тогда\n" //$NON-NLS-1$
            + "#Область ПрограммныйИнтерфейс\n" //$NON-NLS-1$
            + "Процедура Открыть()\nКонецПроцедуры\n" //$NON-NLS-1$
            + "#КонецОбласти\n" //$NON-NLS-1$
            + "#КонецЕсли\n"; //$NON-NLS-1$

        HandlerStubPlacement.Plan plan = HandlerStubPlacement.plan(framedWithoutElse, PROCEDURES,
            BslScriptLanguage.RUSSIAN);

        assertEquals("before the framing's close", Integer.valueOf(6), plan.insertBeforeLine); //$NON-NLS-1$
        assertEquals("\n#Область ОбработчикиСобытий\n" + PROCEDURES + "#КонецОбласти\n", //$NON-NLS-1$ //$NON-NLS-2$
            plan.text);

        String framedWithElse = "#Если Сервер Тогда\n" //$NON-NLS-1$
            + "Процедура Серверная()\nКонецПроцедуры\n" //$NON-NLS-1$
            + "#Иначе\n" //$NON-NLS-1$
            + "Процедура Клиентская()\nКонецПроцедуры\n" //$NON-NLS-1$
            + "#КонецЕсли\n"; //$NON-NLS-1$

        HandlerStubPlacement.Plan beforeElse = HandlerStubPlacement.plan(framedWithElse, PROCEDURES,
            BslScriptLanguage.RUSSIAN);

        assertEquals("before the top-level #Иначе", Integer.valueOf(4), //$NON-NLS-1$
            beforeElse.insertBeforeLine);
        assertTrue(beforeElse.text, beforeElse.text.startsWith("\n#Область ОбработчикиСобытий\n")); //$NON-NLS-1$
    }

    /** A conditional-compilation block in the middle of a module does not frame it. */
    @Test
    public void aBlockInTheMiddleDoesNotFrameTheModule()
    {
        String middle = "#Если Сервер Тогда\nПроцедура А()\nКонецПроцедуры\n#КонецЕсли\n" //$NON-NLS-1$
            + "Процедура Вне()\nКонецПроцедуры\n"; //$NON-NLS-1$

        HandlerStubPlacement.Plan plan = HandlerStubPlacement.plan(middle, PROCEDURES,
            BslScriptLanguage.RUSSIAN);

        assertNull("code follows the block, so it is not the module's framing", //$NON-NLS-1$
            plan.insertBeforeLine);
        assertTrue(plan.text, plan.text.startsWith("\n#Область ОбработчикиСобытий\n")); //$NON-NLS-1$
    }

    /** A module with neither the region nor a framing gets a new region at its end. */
    @Test
    public void aPlainModuleGetsANewRegionAtItsEnd()
    {
        String plain = "Процедура Первая()\nКонецПроцедуры"; //$NON-NLS-1$

        HandlerStubPlacement.Plan plan = HandlerStubPlacement.plan(plain, PROCEDURES,
            BslScriptLanguage.RUSSIAN);

        assertNull(plan.insertBeforeLine);
        assertFalse(plan.createsModule);
        assertEquals("a blank line keeps the region off the last procedure", //$NON-NLS-1$
            "\n#Область ОбработчикиСобытий\n" + PROCEDURES + "#КонецОбласти\n", //$NON-NLS-1$ //$NON-NLS-2$
            plan.text);
    }

    /** The new region speaks the configuration's language, whatever the module's own regions. */
    @Test
    public void theNewRegionSpeaksTheConfigurationsLanguage()
    {
        HandlerStubPlacement.Plan plan = HandlerStubPlacement.plan("Процедура А()\nКонецПроцедуры", //$NON-NLS-1$
            "Procedure BeforeWrite(Cancel)\nEndProcedure\n\n", BslScriptLanguage.ENGLISH); //$NON-NLS-1$

        assertTrue(plan.text, plan.text.contains("#Region EventHandlers\n")); //$NON-NLS-1$
        assertTrue(plan.text, plan.text.contains("#EndRegion\n")); //$NON-NLS-1$
    }
}
