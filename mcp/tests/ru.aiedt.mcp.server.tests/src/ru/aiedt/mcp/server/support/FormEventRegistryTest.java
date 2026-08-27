/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.support.FormEventRegistry.EventSpec;

/**
 * Covers the table that turns a form-event name into a handler stub.
 * <p>
 * The platform accepts an event under two identifiers, English and Russian, and generated code has
 * to compile under either. What makes this worth pinning is the compile directive: a handler the
 * platform runs on the server, emitted under {@code &amp;НаКлиенте}, is a module that fails to build -
 * and the failure surfaces far from the generator that caused it.
 * </p>
 */
public class FormEventRegistryTest
{
    @Test
    public void anEventIsFoundUnderBothItsNames()
    {
        EventSpec english = FormEventRegistry.lookup("OnCreateAtServer"); //$NON-NLS-1$
        EventSpec russian = FormEventRegistry.lookup("ПриСозданииНаСервере"); //$NON-NLS-1$

        assertNotNull("the English identifier has to resolve", english); //$NON-NLS-1$
        assertNotNull("the Russian identifier has to resolve", russian); //$NON-NLS-1$
        assertSame("both names must reach one and the same spec", english, russian); //$NON-NLS-1$
    }

    @Test
    public void aServerEventCarriesTheServerDirective()
    {
        EventSpec spec = FormEventRegistry.lookup("OnCreateAtServer"); //$NON-NLS-1$
        assertTrue("a server handler emitted as client code will not compile: " + spec.directive, //$NON-NLS-1$
            spec.directive.contains("НаСервере")); //$NON-NLS-1$
    }

    @Test
    public void theSignatureIsTheParameterListThePlatformPasses()
    {
        EventSpec spec = FormEventRegistry.lookup("OnCreateAtServer"); //$NON-NLS-1$
        assertTrue(spec.signature, spec.signature.contains("Отказ")); //$NON-NLS-1$
        assertTrue(spec.signature, spec.signature.contains("СтандартнаяОбработка")); //$NON-NLS-1$
    }

    @Test
    public void anUnknownEventResolvesToNothing()
    {
        assertNull(FormEventRegistry.lookup("ПриПолетеНаМарс")); //$NON-NLS-1$
        assertNull(FormEventRegistry.lookup(null));
        assertNull(FormEventRegistry.lookup("")); //$NON-NLS-1$
    }

    @Test
    public void anItemHandlerIsNamedAfterTheItemAndTheEvent()
    {
        // The EDT wizard's own convention. Diverging from it produces handlers a developer opening
        // the form does not recognize as generated.
        assertEquals("НаименованиеПриИзменении", //$NON-NLS-1$
            FormEventRegistry.defaultHandlerName("ПриИзменении", "Наименование")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aFormLevelHandlerIsNamedAfterTheEventAlone()
    {
        assertEquals("ПриОткрытии", //$NON-NLS-1$
            FormEventRegistry.defaultHandlerName("ПриОткрытии", null)); //$NON-NLS-1$
        assertEquals("ПриОткрытии", //$NON-NLS-1$
            FormEventRegistry.defaultHandlerName("ПриОткрытии", "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void thereIsNoHandlerNameWithoutAnEvent()
    {
        assertNull(FormEventRegistry.defaultHandlerName(null, "Наименование")); //$NON-NLS-1$
        assertNull(FormEventRegistry.defaultHandlerName("", "Наименование")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theGeneratedStubIsAWholeProcedureWithItsDirective()
    {
        EventSpec spec = FormEventRegistry.lookup("OnCreateAtServer"); //$NON-NLS-1$

        String stub = FormEventRegistry.generateBslStub("ПриСозданииНаСервере", spec); //$NON-NLS-1$

        assertTrue(stub, stub.contains(spec.directive));
        assertTrue(stub, stub.contains("Процедура ПриСозданииНаСервере(")); //$NON-NLS-1$
        assertTrue("an unterminated procedure breaks the module it lands in", //$NON-NLS-1$
            stub.contains("КонецПроцедуры")); //$NON-NLS-1$
        assertTrue("the parameters have to be there or the platform will not bind the handler", //$NON-NLS-1$
            stub.contains(spec.signature));
    }

    /**
     * The clear button on an input field.
     * <p>
     * Reported from a workspace where the event was refused as unknown: {@code Clearing} appears 109
     * times in the forms of a shipped configuration, and every one of them had to be written into
     * the .form by hand. Its neighbours on the same field extension were missing with it.
     * </p>
     */
    @Test
    public void theClearButtonAndItsNeighboursAreKnownEvents()
    {
        for (String event : new String[] {"Clearing", "Очистка", //$NON-NLS-1$ //$NON-NLS-2$
            "TextEditEnd", "Opening"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertNotNull(event + " is a platform event and must resolve", //$NON-NLS-1$
                FormEventRegistry.lookup(event));
        }
    }

    @Test
    public void aFieldEventTakesTheElementFirst()
    {
        // Every field event the platform documents without an element still receives one as the
        // first argument of the handler. Clearing is documented as Clearing(StandardProcessing).
        EventSpec spec = FormEventRegistry.lookup("Clearing"); //$NON-NLS-1$

        assertNotNull(spec);
        assertTrue(spec.signature, spec.signature.startsWith("Элемент")); //$NON-NLS-1$
        assertTrue(spec.signature,
            spec.signature.contains("СтандартнаяОбработка")); //$NON-NLS-1$
    }
}
