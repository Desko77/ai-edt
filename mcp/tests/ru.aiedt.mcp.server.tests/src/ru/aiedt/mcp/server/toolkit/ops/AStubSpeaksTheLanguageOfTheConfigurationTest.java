/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmRouteMapHelper;
import ru.aiedt.mcp.server.support.BslScriptLanguage;
import ru.aiedt.mcp.server.support.FormEventRegistry;

/**
 * Every stub writer answers the language of the configuration: an object module with the English
 * script variant gets English keywords, and a form module's stub gets its procedure keywords in
 * that language too. Event names and parameter names stay as the event tables carry them - only
 * the language's own keywords change.
 */
public class AStubSpeaksTheLanguageOfTheConfigurationTest
{
    /** An object-module handler is rendered in the configuration's language. */
    @Test
    public void anObjectModuleHandlerIsRenderedInTheLanguagesOwnKeywords()
    {
        GenerateEventHandlersTool.EventDef beforeWrite =
            new GenerateEventHandlersTool.EventDef("ПередЗаписью", "Отказ", null); //$NON-NLS-1$ //$NON-NLS-2$
        String english = GenerateEventHandlersTool.renderEvent(beforeWrite, false,
            BslScriptLanguage.ENGLISH);

        assertTrue(english, english.startsWith("Procedure ПередЗаписью(Отказ)\n")); //$NON-NLS-1$
        assertTrue(english, english.contains("implement the ПередЗаписью handler")); //$NON-NLS-1$
        assertTrue(english, english.endsWith("EndProcedure")); //$NON-NLS-1$
        assertFalse("the event name and its parameters stay as the table carries them", //$NON-NLS-1$
            english.contains("Write")); //$NON-NLS-1$

        String russian = GenerateEventHandlersTool.renderEvent(beforeWrite, false,
            BslScriptLanguage.RUSSIAN);
        assertTrue(russian, russian.startsWith("Процедура ПередЗаписью(Отказ)\n")); //$NON-NLS-1$
        assertTrue(russian, russian.contains("реализовать обработчик")); //$NON-NLS-1$
        assertTrue(russian, russian.endsWith("КонецПроцедуры")); //$NON-NLS-1$
    }

    /** The short renderings default to Russian, the platform's own default. */
    @Test
    public void theShortRenderingsDefaultToRussian()
    {
        GenerateEventHandlersTool.EventDef def =
            new GenerateEventHandlersTool.EventDef("ПриЗаписи", "Отказ", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(GenerateEventHandlersTool.renderEvent(def, false)
            .startsWith("Процедура ПриЗаписи(Отказ)")); //$NON-NLS-1$

        String stub = BmRouteMapHelper.handlerStub("ДействиеПриВыполнении", "onexecute"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(stub, stub.startsWith("Процедура ДействиеПриВыполнении(")); //$NON-NLS-1$
    }

    /** A route-point handler procedure speaks the configuration's language. */
    @Test
    public void aRoutePointHandlerSpeaksTheLanguagesOwnKeywords()
    {
        String english = BmRouteMapHelper.handlerStub("DoIt", "onexecute", //$NON-NLS-1$ //$NON-NLS-2$
            BslScriptLanguage.ENGLISH);

        assertTrue(english, english
            .startsWith("Procedure DoIt(ТочкаМаршрутаБизнесПроцесса, Задача, Отказ)\n")); //$NON-NLS-1$
        assertTrue(english, english.contains("implement the DoIt handler")); //$NON-NLS-1$
        assertTrue(english, english.endsWith("EndProcedure")); //$NON-NLS-1$
    }

    /** A form handler's procedure keywords follow the language; the directive stays the event's. */
    @Test
    public void aFormHandlerFollowsTheLanguageForItsKeywords()
    {
        FormEventRegistry.EventSpec spec = FormEventRegistry.lookup("OnCreateAtServer"); //$NON-NLS-1$

        String english = FormEventRegistry.generateBslStub("ПриСозданииНаСервере", spec, //$NON-NLS-1$
            BslScriptLanguage.ENGLISH);

        assertTrue(english, english.contains(spec.directive));
        assertTrue(english, english.contains("Procedure ПриСозданииНаСервере(")); //$NON-NLS-1$
        assertTrue("an unterminated procedure breaks the module it lands in", //$NON-NLS-1$
            english.contains("EndProcedure\n")); //$NON-NLS-1$

        String russian = FormEventRegistry.generateBslStub("ПриСозданииНаСервере", spec); //$NON-NLS-1$
        assertEquals("the Russian rendering is unchanged", //$NON-NLS-1$
            "\n&НаСервере\nПроцедура ПриСозданииНаСервере(Отказ, СтандартнаяОбработка)\n    \nКонецПроцедуры\n", //$NON-NLS-1$
            russian);
    }
}
