/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.HashMap;
import java.util.Map;

/**
 * Generates BSL handler stubs for EventSubscription operations
 * (Phase 7, three-tier strategy):
 * <ol>
 *   <li>Hard-coded map for ~20 standard platform events</li>
 *   <li>EDT API runtime probe for custom events (best-effort)</li>
 *   <li>Fallback: stub with sole "Источник" parameter + TODO comment</li>
 * </ol>
 * Also accepts an explicit {@code customSignature} override.
 */
public final class EventStubGenerator
{
    /** Known platform events with their canonical signatures (Russian + English keys). */
    private static final Map<String, String> KNOWN_EVENTS = buildKnownEvents();

    private EventStubGenerator()
    {
    }

    public static final class Stub
    {
        public String code;
        public String signatureSource; // "known" | "probe" | "fallback" | "custom"
        public String warning;
    }

    /**
     * Resolves the signature and returns the BSL stub for the given handler.
     *
     * @param eventName platform event name (Russian or English)
     * @param handlerName handler name in module ({@code "MyHandler"})
     * @param customSignature optional override (e.g. {@code "Источник, Параметр1, Параметр2"})
     * @return generated stub with code and signature source
     */
    public static Stub generateStub(String eventName, String handlerName, String customSignature)
    {
        Stub stub = new Stub();
        if (handlerName == null || handlerName.isEmpty())
        {
            stub.warning = "handlerName is required"; //$NON-NLS-1$
            return stub;
        }

        String signature;
        if (customSignature != null && !customSignature.isEmpty())
        {
            signature = customSignature;
            stub.signatureSource = "custom"; //$NON-NLS-1$
        }
        else
        {
            String key = eventName != null ? eventName.trim() : ""; //$NON-NLS-1$
            String mapped = KNOWN_EVENTS.get(key);
            if (mapped == null && key.length() > 0)
            {
                // Tier 2: EDT API runtime probe — placeholder. The actual lookup
                // requires a live EDT context (Metadata.События[name].Параметры).
                // For now we surface the fallback path.
            }
            if (mapped != null)
            {
                signature = mapped;
                stub.signatureSource = "known"; //$NON-NLS-1$
            }
            else
            {
                signature = "Источник"; //$NON-NLS-1$
                stub.signatureSource = "fallback"; //$NON-NLS-1$
                stub.warning = "Event '" + eventName //$NON-NLS-1$
                    + "' not in known map; generated stub with single 'Источник' parameter and TODO comment. " //$NON-NLS-1$
                    + "Override via customSignature parameter."; //$NON-NLS-1$
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\nПроцедура ").append(handlerName).append("(").append(signature) //$NON-NLS-1$ //$NON-NLS-2$
            .append(") Экспорт\n"); //$NON-NLS-1$
        if ("fallback".equals(stub.signatureSource)) //$NON-NLS-1$
        {
            sb.append("    // TODO: уточнить сигнатуру события вручную\n"); //$NON-NLS-1$
        }
        sb.append("    \n"); //$NON-NLS-1$
        sb.append("КонецПроцедуры\n"); //$NON-NLS-1$
        stub.code = sb.toString();
        return stub;
    }

    private static Map<String, String> buildKnownEvents()
    {
        Map<String, String> m = new HashMap<>();
        // Documents — posting / writing
        put(m, "ОбработкаПроведения", "Posting", "Источник, Отказ, РежимПроведения"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        put(m, "ОбработкаУдаленияПроведения", "UndoPosting", "Источник, Отказ"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        put(m, "ПередЗаписью", "BeforeWrite", "Источник, Отказ"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        put(m, "ПриЗаписи", "OnWrite", "Источник, Отказ"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        put(m, "ПередУдалением", "BeforeDelete", "Источник, Отказ"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Catalogs — common
        put(m, "ПриКопировании", "OnCopy", "Источник, ОбъектКопирования"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        put(m, "ПриУстановкеНовогоКода", "OnSetNewCode", "Источник, СтандартнаяОбработка, Префикс"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        put(m, "ПриУстановкеНовогоНомера", "OnSetNewNumber", "Источник, СтандартнаяОбработка, Префикс"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        put(m, "ОбработкаЗаполнения", "FillCheckProcessing", "Источник, ДанныеЗаполнения, СтандартнаяОбработка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        put(m, "ОбработкаПроверкиЗаполнения", "FillProcessing", "Источник, Отказ, ПроверяемыеРеквизиты"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Registers — record set
        put(m, "ПередЗаписьюНаборЗаписей", "BeforeWriteRecordSet", "Источник, Отказ, Замещение"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        put(m, "ПриЗаписиНаборЗаписей", "OnWriteRecordSet", "Источник, Отказ, Замещение"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Sequences
        put(m, "ВосстановлениеГраницыПоследовательности", "RecoverSequenceBoundary", "Источник, Граница"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Reports (data composition)
        put(m, "ПриКомпоновкеРезультата", "OnComposeResult", "ДокументРезультат, ДанныеРасшифровки, СтандартнаяОбработка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Generic source events
        put(m, "ОбработкаПолученияДанныхВыбора", "ChoiceDataGetProcessing", "Источник, ДанныеВыбора, Параметры, СтандартнаяОбработка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        put(m, "ОбработкаПолученияФормы", "FormGetProcessing", "Источник, ВидФормы, Параметры, ВыбраннаяФорма, ДополнительнаяИнформация, СтандартнаяОбработка"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return m;
    }

    private static void put(Map<String, String> m, String ru, String en, String signature)
    {
        m.put(ru, signature);
        m.put(en, signature);
    }
}
