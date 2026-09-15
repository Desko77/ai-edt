/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

/**
 * Covers the half of {@code generate_event_handlers} that decides what NOT to write.
 * <p>
 * {@code writeToModule} and {@code skipExisting} were advertised by the schema and read by nothing:
 * a caller who asked for the handlers to be appended got the text back, an untouched module, and no
 * word about either. Writing them is now the tool's job, and the first thing that job needs is to
 * recognise a handler the module already declares - in Cyrillic, in whatever case the author used,
 * because appending a second handler of the same name is how a module stops compiling.
 * </p>
 */
public class HandlersGoIntoTheModuleWhenAskedTest
{
    private static boolean declares(String moduleText, String methodName) throws Exception
    {
        Method method = GenerateEventHandlersTool.class
            .getDeclaredMethod("declares", String.class, String.class); //$NON-NLS-1$
        method.setAccessible(true);
        return (Boolean)method.invoke(null, moduleText, methodName);
    }

    /** A handler already in the module is found, whatever case its keyword carries. */
    @Test
    public void aHandlerAlreadyInTheModuleIsFound() throws Exception
    {
        String module = "Процедура ПередЗаписью(Отказ)\n\t// ...\nКонецПроцедуры\n"; //$NON-NLS-1$
        assertTrue("as the module writes it", declares(module, "ПередЗаписью")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and with the keyword in lower case", //$NON-NLS-1$
            declares("процедура ПередЗаписью(Отказ)\nКонецПроцедуры\n", "ПередЗаписью")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a function counts too", //$NON-NLS-1$
            declares("Функция ОбработкаПолученияФормы(Источник)\nКонецФункции\n", //$NON-NLS-1$
                "ОбработкаПолученияФормы")); //$NON-NLS-1$
    }

    /** A name that only appears inside a call is not a declaration. */
    @Test
    public void aMentionIsNotADeclaration() throws Exception
    {
        String module = "Процедура ПриЗаписи(Отказ)\n\tПередЗаписью(Отказ);\nКонецПроцедуры\n"; //$NON-NLS-1$
        assertFalse("calling it is not declaring it", declares(module, "ПередЗаписью")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A handler the module does not have is not reported as present. */
    @Test
    public void aHandlerTheModuleLacksIsNotFound() throws Exception
    {
        String module = "Процедура ПриЗаписи(Отказ)\nКонецПроцедуры\n"; //$NON-NLS-1$
        assertFalse("nothing declares it", declares(module, "ПередУдалением")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an empty module declares nothing", declares("", "ПриЗаписи")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A longer name that starts with a shorter one is a different handler. */
    @Test
    public void aLongerNameIsADifferentHandler() throws Exception
    {
        String module = "Процедура ОбработкаПроверкиЗаполнения(Отказ, ПроверяемыеРеквизиты)\nКонецПроцедуры\n"; //$NON-NLS-1$
        assertFalse("ОбработкаЗаполнения is not ОбработкаПроверкиЗаполнения", //$NON-NLS-1$
            declares(module, "ОбработкаЗаполнения")); //$NON-NLS-1$
        assertTrue("and the one it does declare is found", //$NON-NLS-1$
            declares(module, "ОбработкаПроверкиЗаполнения")); //$NON-NLS-1$
    }
}
