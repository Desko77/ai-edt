/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.ops.GenerateEventHandlersTool.EventDef;

/**
 * Covers the shape of a generated handler, which nothing downstream can check.
 * <p>
 * A stub with the wrong parameter list or a directive that does not belong is written into a module
 * and looks fine there. It fails later - when the platform calls the handler, or when the module is
 * compiled - far from the tool that produced it and long after the agent moved on. Both faults
 * shipped: a document inherited the catalogue's one-argument {@code ПередЗаписью}, and every stub
 * carried {@code &НаСервере}, which an object module does not allow.
 * </p>
 */
public class GenerateEventHandlersTest
{
    @Test
    public void aDocumentIsWrittenWithThePostingArgumentsNotTheCatalogueOnes()
    {
        // The platform calls a document's BeforeWrite with three arguments, because writing a
        // document also decides its posting. A stub declaring one is never the handler that runs.
        EventDef beforeWrite = find("Document", "ПередЗаписью"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Отказ, РежимЗаписи, РежимПроведения", beforeWrite.signature); //$NON-NLS-1$
    }

    @Test
    public void aCatalogueKeepsItsOwnSingleArgument()
    {
        // The document fix must not leak sideways: a catalogue's BeforeWrite really does take one.
        assertEquals("Отказ", find("Catalog", "ПередЗаписью").signature); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aDocumentStillGetsItsPostingEvents()
    {
        assertNotNull(find("Document", "ОбработкаПроведения")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(find("Document", "ОбработкаУдаленияПроведения")); //$NON-NLS-1$ //$NON-NLS-2$
        // And the events it shares with a catalogue are still there.
        assertNotNull(find("Document", "ОбработкаЗаполнения")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aRegisterKeepsTheReplacementArgument()
    {
        assertEquals("Отказ, Замещение", find("InformationRegister", "ПередЗаписью").signature); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void aHandlerCarriesNoCompilationDirective()
    {
        // These events live in the object module, where a directive is not allowed at all. None of
        // the demo configuration's object modules carries one.
        String rendered = GenerateEventHandlersTool.renderEvent(find("Catalog", "ПриЗаписи"), false); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an object module takes no compilation directive", //$NON-NLS-1$
            rendered.contains("&НаСервере")); //$NON-NLS-1$
        assertFalse(rendered.contains("&НаКлиенте")); //$NON-NLS-1$
        assertTrue("the procedure itself must still be there", //$NON-NLS-1$
            rendered.startsWith("Процедура ПриЗаписи(Отказ)")); //$NON-NLS-1$
        assertTrue(rendered.endsWith("КонецПроцедуры")); //$NON-NLS-1$
    }

    @Test
    public void theFullModeWritesABodyAndTheStubModeWritesAMarker()
    {
        EventDef posting = find("Document", "ОбработкаПроведения"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(GenerateEventHandlersTool.renderEvent(posting, true).contains("Движения.Записать()")); //$NON-NLS-1$
        assertTrue(GenerateEventHandlersTool.renderEvent(posting, false).contains("TODO")); //$NON-NLS-1$
    }

    private static EventDef find(String kind, String event)
    {
        List<EventDef> events = GenerateEventHandlersTool.eventsFor(kind);
        assertNotNull("no event table for " + kind, events); //$NON-NLS-1$
        for (EventDef def : events)
        {
            if (event.equals(def.name))
            {
                return def;
            }
        }
        throw new AssertionError(kind + " has no event " + event); //$NON-NLS-1$
    }
}
