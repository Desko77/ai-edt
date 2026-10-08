/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;

/**
 * The language of a configuration's script variant decides the spelling of the keywords its
 * generated stubs carry. A configuration that says nothing - or one that cannot be read at all -
 * answers Russian, which is what the platform itself defaults to, never a half-translated stub.
 */
public class BslScriptLanguageTest
{
    /** An English configuration writes English keywords; a Russian one and none at all write Russian. */
    @Test
    public void theScriptVariantNamesTheLanguage()
    {
        Configuration english = MdClassFactory.eINSTANCE.createConfiguration();
        english.setScriptVariant(ScriptVariant.ENGLISH);
        assertEquals(BslScriptLanguage.ENGLISH, BslScriptLanguage.of(english));

        Configuration russian = MdClassFactory.eINSTANCE.createConfiguration();
        russian.setScriptVariant(ScriptVariant.RUSSIAN);
        assertEquals(BslScriptLanguage.RUSSIAN, BslScriptLanguage.of(russian));

        assertEquals("no configuration is the platform's own default", //$NON-NLS-1$
            BslScriptLanguage.RUSSIAN, BslScriptLanguage.of(null));
    }

    /** The platform's own enum constant reaches the EDT APIs that ask for it. */
    @Test
    public void thePlatformConstantReachesTheApi()
    {
        Configuration english = MdClassFactory.eINSTANCE.createConfiguration();
        english.setScriptVariant(ScriptVariant.ENGLISH);
        assertSame(ScriptVariant.ENGLISH, BslScriptLanguage.platformVariant(english));

        assertNotNull("a silent configuration still answers the platform's default constant", //$NON-NLS-1$
            BslScriptLanguage.platformVariant(null));
        assertEquals(ScriptVariant.RUSSIAN, BslScriptLanguage.platformVariant(null));
    }

    /** Every keyword a stub is built from comes in the language's own spelling. */
    @Test
    public void everyKeywordComesInTheLanguagesOwnSpelling()
    {
        assertEquals("Процедура", BslScriptLanguage.RUSSIAN.procedure()); //$NON-NLS-1$
        assertEquals("КонецПроцедуры", BslScriptLanguage.RUSSIAN.endProcedure()); //$NON-NLS-1$
        assertEquals("Функция", BslScriptLanguage.RUSSIAN.function()); //$NON-NLS-1$
        assertEquals("КонецФункции", BslScriptLanguage.RUSSIAN.endFunction()); //$NON-NLS-1$
        assertEquals("#Область Имя", BslScriptLanguage.RUSSIAN.regionOpen("Имя")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("#КонецОбласти", BslScriptLanguage.RUSSIAN.regionEnd()); //$NON-NLS-1$
        assertEquals("#Если Сервер Или ТолстыйКлиентОбычноеПриложение Или ВнешнееСоединение Тогда", //$NON-NLS-1$
            BslScriptLanguage.RUSSIAN.serverFramingOpen());
        assertEquals("#КонецЕсли", BslScriptLanguage.RUSSIAN.framingEnd()); //$NON-NLS-1$
        assertEquals("// TODO: реализовать обработчик ПередЗаписью", //$NON-NLS-1$
            BslScriptLanguage.RUSSIAN.todoComment("ПередЗаписью")); //$NON-NLS-1$

        assertEquals("Procedure", BslScriptLanguage.ENGLISH.procedure()); //$NON-NLS-1$
        assertEquals("EndProcedure", BslScriptLanguage.ENGLISH.endProcedure()); //$NON-NLS-1$
        assertEquals("Function", BslScriptLanguage.ENGLISH.function()); //$NON-NLS-1$
        assertEquals("EndFunction", BslScriptLanguage.ENGLISH.endFunction()); //$NON-NLS-1$
        assertEquals("#Region Name", BslScriptLanguage.ENGLISH.regionOpen("Name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("#EndRegion", BslScriptLanguage.ENGLISH.regionEnd()); //$NON-NLS-1$
        assertEquals("#If Server Or ThickClientOrdinaryApplication Or ExternalConnection Then", //$NON-NLS-1$
            BslScriptLanguage.ENGLISH.serverFramingOpen());
        assertEquals("#EndIf", BslScriptLanguage.ENGLISH.framingEnd()); //$NON-NLS-1$
        assertEquals("// TODO: implement the BeforeWrite handler", //$NON-NLS-1$
            BslScriptLanguage.ENGLISH.todoComment("BeforeWrite")); //$NON-NLS-1$
    }

    /** The handler region is named once, so every writer looks for and creates the same region. */
    @Test
    public void theHandlerRegionHasOneNamePerLanguage()
    {
        assertEquals("ОбработчикиСобытий", BslScriptLanguage.RUSSIAN.handlerRegionName()); //$NON-NLS-1$
        assertEquals("EventHandlers", BslScriptLanguage.ENGLISH.handlerRegionName()); //$NON-NLS-1$
    }
}
