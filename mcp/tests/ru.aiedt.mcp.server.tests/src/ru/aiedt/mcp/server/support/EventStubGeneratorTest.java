/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.support.EventStubGenerator.Stub;

/**
 * Covers the subscription-handler generator and, above all, how it behaves when it does not know
 * the event.
 * <p>
 * Guessing a signature silently is the dangerous outcome: the module compiles, the platform declines
 * to bind a handler whose parameters do not match, and the subscription quietly never fires. The
 * generator therefore labels every stub with where its signature came from and warns in the
 * fallback case, and those two signals are what the tests hold it to.
 * </p>
 */
public class EventStubGeneratorTest
{
    @Test
    public void aKnownEventGetsThePlatformSignature()
    {
        Stub stub = EventStubGenerator.generateStub("ОбработкаПроведения", "ПриПроведении", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("known", stub.signatureSource); //$NON-NLS-1$
        assertNull("a known signature is not something to warn about", stub.warning); //$NON-NLS-1$
        assertTrue(stub.code, stub.code.contains("РежимПроведения")); //$NON-NLS-1$
        assertTrue(stub.code, stub.code.contains("Процедура ПриПроведении(")); //$NON-NLS-1$
        assertTrue(stub.code, stub.code.contains("КонецПроцедуры")); //$NON-NLS-1$
    }

    @Test
    public void theEnglishNameOfAKnownEventResolvesToTheSameSignature()
    {
        Stub russian = EventStubGenerator.generateStub("ОбработкаПроведения", "H", null); //$NON-NLS-1$ //$NON-NLS-2$
        Stub english = EventStubGenerator.generateStub("Posting", "H", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(russian.signatureSource, english.signatureSource);
        assertEquals(russian.code, english.code);
    }

    @Test
    public void anUnknownEventFallsBackAndSaysSo()
    {
        Stub stub = EventStubGenerator.generateStub("ПриПолетеНаМарс", "Обработчик", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("fallback", stub.signatureSource); //$NON-NLS-1$
        assertNotNull("a guessed signature has to be announced, not slipped in", stub.warning); //$NON-NLS-1$
        assertTrue(stub.warning, stub.warning.contains("ПриПолетеНаМарс")); //$NON-NLS-1$
        assertTrue("the stub itself must carry the caveat into the module", //$NON-NLS-1$
            stub.code.contains("TODO")); //$NON-NLS-1$
    }

    @Test
    public void anExplicitSignatureWins()
    {
        Stub stub = EventStubGenerator.generateStub("ОбработкаПроведения", "H", //$NON-NLS-1$ //$NON-NLS-2$
            "Источник, МойПараметр"); //$NON-NLS-1$

        assertEquals("custom", stub.signatureSource); //$NON-NLS-1$
        assertTrue(stub.code, stub.code.contains("Источник, МойПараметр")); //$NON-NLS-1$
        assertTrue("an explicit signature is the caller's decision, not a guess", //$NON-NLS-1$
            stub.code.contains("Процедура H(Источник, МойПараметр) Экспорт")); //$NON-NLS-1$
    }

    @Test
    public void anExplicitSignatureIsAcceptedEvenForAnUnknownEvent()
    {
        // This is the escape hatch the fallback warning points at.
        Stub stub = EventStubGenerator.generateStub("ПриПолетеНаМарс", "H", "Источник, Отказ"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("custom", stub.signatureSource); //$NON-NLS-1$
        assertNull(stub.warning);
        assertTrue(stub.code, stub.code.contains("Источник, Отказ")); //$NON-NLS-1$
    }

    @Test
    public void withoutAHandlerNameNothingIsGenerated()
    {
        Stub stub = EventStubGenerator.generateStub("ОбработкаПроведения", null, null); //$NON-NLS-1$

        assertNull("half a procedure is worse than none", stub.code); //$NON-NLS-1$
        assertNotNull(stub.warning);
        assertTrue(stub.warning, stub.warning.contains("handlerName")); //$NON-NLS-1$
    }

    @Test
    public void theGeneratedProcedureIsExported()
    {
        // A subscription handler lives in a common module and the platform calls it from outside,
        // so it has to be exported.
        Stub stub = EventStubGenerator.generateStub("ПередЗаписью", "ПередЗаписьюДокумента", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(stub.code, stub.code.contains(") Экспорт")); //$NON-NLS-1$
    }
}
