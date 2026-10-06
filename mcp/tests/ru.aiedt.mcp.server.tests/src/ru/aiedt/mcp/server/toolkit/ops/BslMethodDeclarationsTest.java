/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

/**
 * The handler check of an HTTP service module reads the methods the module declares, not the text
 * it happens to contain: {@code GetAll} is not {@code Get}, a name in a comment or a string
 * literal declares nothing, and a module that cannot be read this way fails instead of answering.
 */
public class BslMethodDeclarationsTest
{
    private static List<BslMethodDeclarations.Method> parse(String module)
    {
        try
        {
            return BslMethodDeclarations.parse(module);
        }
        catch (BslMethodDeclarations.ParseFailure broken)
        {
            throw new AssertionError("a readable module does not parse: " + broken.getMessage(), broken);
        }
    }

    /** A function is declared by its whole name; a longer name that starts with it is another. */
    @Test
    public void aNameIsComparedWhole()
    {
        String module = moduleOf();
        List<BslMethodDeclarations.Method> methods = parse(module);

        assertEquals(1, methods.size());
        assertEquals("GetAll", methods.get(0).name);
        assertTrue(methods.get(0).function);
        assertTrue("the whole name is declared", declared(module, "GetAll")); //$NON-NLS-1$
        assertFalse("a longer name that starts with it is another", declared(module, "Get")); //$NON-NLS-1$
    }

    /** A name in a comment or in a string literal declares nothing. */
    @Test
    public void aMentionInACommentOrALiteralDeclaresNothing()
    {
        String comment = "// Функция Get(Запрос) была здесь\n" //$NON-NLS-1$
            + "Функция Другая()\n\tВозврат \"\";\nКонецФункции\n"; //$NON-NLS-1$
        assertFalse(parse(comment).stream().anyMatch(m -> "Get".equalsIgnoreCase(m.name))); //$NON-NLS-1$

        String literal = "Функция Обработчик()\n" //$NON-NLS-1$
            + "\tТекст = \"Функция Get(Запрос)\"; // и в строке тоже\n" //$NON-NLS-1$
            + "\tВозврат Текст;\nКонецФункции\n"; //$NON-NLS-1$
        assertFalse(parse(literal).stream().anyMatch(m -> "Get".equalsIgnoreCase(m.name))); //$NON-NLS-1$

        String afterCode = "А = 1; // Функция Get(Запрос)\n"; //$NON-NLS-1$
        assertTrue(parse(afterCode).isEmpty());
    }

    /** A name is matched without regard to case, and a procedure is not a function. */
    @Test
    public void theCaseIsIgnoredAndTheKindIsKept()
    {
        String cased = "функция gEtAll(Запрос)\n\tВозврат 1;\nКонецФункции\n"; //$NON-NLS-1$
        assertTrue(declared(cased, "GETALL")); //$NON-NLS-1$
        assertTrue(declared(cased, "getall")); //$NON-NLS-1$

        String procedure = "Процедура GetAll(Запрос)\nКонецПроцедуры\n"; //$NON-NLS-1$
        List<BslMethodDeclarations.Method> methods = parse(procedure);
        assertEquals(1, methods.size());
        assertFalse("a procedure is not a function", methods.get(0).function); //$NON-NLS-1$
        assertFalse("so it does not answer a function check", declared(procedure, "GetAll")); //$NON-NLS-1$ //$NON-NLS-2$

        String english = "Function GetAll(Request)\n\tReturn 1;\nEndFunction\n"; //$NON-NLS-1$
        assertTrue(declared(english, "getall")); //$NON-NLS-1$
    }

    /** A literal that spans lines through {@code |} keeps the lines after it readable. */
    @Test
    public void aMultilineLiteralStaysReadable()
    {
        String module = "Запрос = \"первая строка\n" //$NON-NLS-1$
            + "|вторая строка\";\n" //$NON-NLS-1$
            + "Функция GetAll(Запрос)\n\tВозврат 1;\nКонецФункции\n"; //$NON-NLS-1$

        assertTrue(declared(module, "GetAll")); //$NON-NLS-1$
    }

    /** A module whose text cannot be read fails instead of answering a guess. */
    @Test
    public void aBrokenModuleIsRefused()
    {
        try
        {
            BslMethodDeclarations.parse("Функция GetAll(Запрос)\n\tВозврат \"незакрытая;\nКонецФункции\n"); //$NON-NLS-1$
            fail("an unterminated literal has to fail the read"); //$NON-NLS-1$
        }
        catch (BslMethodDeclarations.ParseFailure expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("never closed")); //$NON-NLS-1$
        }
        try
        {
            BslMethodDeclarations.parse("Функция GetAll()\n\tВозврат \"открытая\n"); //$NON-NLS-1$
            fail("a literal open at the end of the module has to fail the read"); //$NON-NLS-1$
        }
        catch (BslMethodDeclarations.ParseFailure expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("never closed")); //$NON-NLS-1$
        }
    }

    private static String moduleOf()
    {
        return "Функция GetAll(Запрос)\n\tВозврат Новый HTTPСервисОтвет(200);\nКонецФункции\n"; //$NON-NLS-1$
    }

    private static boolean declared(String module, String name)
    {
        try
        {
            return BslMethodDeclarations.declaresFunction(module, name);
        }
        catch (BslMethodDeclarations.ParseFailure broken)
        {
            throw new AssertionError("a readable module does not parse: " + broken.getMessage(), broken);
        }
    }
}
