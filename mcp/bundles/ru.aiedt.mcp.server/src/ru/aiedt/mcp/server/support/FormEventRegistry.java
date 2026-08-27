/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1.42 (RSV 4.2 parity): registry of common managed-form events with the
 * canonical procedure signature and the compilation directive
 * ({@code &НаКлиенте} / {@code &НаСервере}) the platform expects.
 *
 * <p>Used by {@code addEventHandler} to:
 * <ul>
 *   <li>Resolve the canonical handler signature when the agent passes only
 *       the event name (e.g. {@code OnChange} -&gt; {@code Элемент}).</li>
 *   <li>Pick the right compilation directive automatically so the agent
 *       does not have to know which events run on the client vs the
 *       server.</li>
 * </ul>
 *
 * <p>Names are stored in both Russian and English forms because the
 * platform uses Russian event identifiers in script-variant=Russian
 * configurations and English in script-variant=English ones.
 */
public final class FormEventRegistry
{
    /** Event categories - drives the default directive when the lookup misses. */
    public enum Scope
    {
        FORM,           // Events defined on Form root: OnOpen, OnCreateAtServer, ...
        FIELD,          // Field-level: OnChange, StartChoice, ChoiceProcessing, ...
        TABLE,          // Table-level: BeforeAddRow, OnActivateRow, ...
        BUTTON          // Button click handlers (custom form commands).
    }

    public static final class EventSpec
    {
        public final String englishName;
        public final String russianName;
        public final String signature;     // comma-separated parameter list
        public final String directive;     // "&НаКлиенте" / "&НаСервере" / "&НаСервереБезКонтекста"
        public final Scope scope;

        public EventSpec(String en, String ru, String signature, String directive, Scope scope)
        {
            this.englishName = en;
            this.russianName = ru;
            this.signature = signature;
            this.directive = directive;
            this.scope = scope;
        }
    }

    /**
     * Lookup table keyed by both English and Russian names (case-sensitive,
     * matches platform identifiers exactly).
     */
    private static final Map<String, EventSpec> EVENTS;

    static
    {
        Map<String, EventSpec> m = new LinkedHashMap<>();

        // Form root - lifecycle on the server.
        register(m, "OnCreateAtServer", "ПриСозданииНаСервере", //$NON-NLS-1$ //$NON-NLS-2$
            "Отказ, СтандартнаяОбработка", "&НаСервере", Scope.FORM); //$NON-NLS-1$ //$NON-NLS-2$
        register(m, "OnReadAtServer", "ПриЧтенииНаСервере", //$NON-NLS-1$ //$NON-NLS-2$
            "ТекущийОбъект", "&НаСервере", Scope.FORM); //$NON-NLS-1$ //$NON-NLS-2$
        register(m, "BeforeWriteAtServer", "ПередЗаписьюНаСервере", //$NON-NLS-1$ //$NON-NLS-2$
            "Отказ, ТекущийОбъект, ПараметрыЗаписи", "&НаСервере", Scope.FORM); //$NON-NLS-1$ //$NON-NLS-2$
        register(m, "OnWriteAtServer", "ПриЗаписиНаСервере", //$NON-NLS-1$ //$NON-NLS-2$
            "ТекущийОбъект, ПараметрыЗаписи, Отказ", "&НаСервере", Scope.FORM); //$NON-NLS-1$ //$NON-NLS-2$
        register(m, "AfterWriteAtServer", "ПослеЗаписиНаСервере", //$NON-NLS-1$ //$NON-NLS-2$
            "ТекущийОбъект, ПараметрыЗаписи", "&НаСервере", Scope.FORM); //$NON-NLS-1$ //$NON-NLS-2$
        register(m, "FillCheckProcessingAtServer", "ОбработкаПроверкиЗаполненияНаСервере", //$NON-NLS-1$ //$NON-NLS-2$
            "Отказ, ПроверяемыеРеквизиты", "&НаСервере", Scope.FORM); //$NON-NLS-1$ //$NON-NLS-2$

        // Form root - lifecycle on the client.
        register(m, "OnOpen", "ПриОткрытии", //$NON-NLS-1$ //$NON-NLS-2$
            "Отказ", "&НаКлиенте", Scope.FORM); //$NON-NLS-1$ //$NON-NLS-2$
        register(m, "BeforeClose", "ПередЗакрытием", //$NON-NLS-1$ //$NON-NLS-2$
            "Отказ, ЗавершениеРаботы, ТекстПредупреждения, СтандартнаяОбработка", //$NON-NLS-1$
            "&НаКлиенте", Scope.FORM); //$NON-NLS-1$
        register(m, "OnClose", "ПриЗакрытии", //$NON-NLS-1$ //$NON-NLS-2$
            "ЗавершениеРаботы", "&НаКлиенте", Scope.FORM); //$NON-NLS-1$ //$NON-NLS-2$
        register(m, "ChoiceProcessing", "ОбработкаВыбора", //$NON-NLS-1$ //$NON-NLS-2$
            "ВыбранноеЗначение, ИсточникВыбора", "&НаКлиенте", Scope.FORM); //$NON-NLS-1$ //$NON-NLS-2$
        register(m, "NotificationProcessing", "ОбработкаОповещения", //$NON-NLS-1$ //$NON-NLS-2$
            "ИмяСобытия, Параметр, Источник", "&НаКлиенте", Scope.FORM); //$NON-NLS-1$ //$NON-NLS-2$

        // Field-level - the platform passes the form item itself first.
        register(m, "OnChange", "ПриИзменении", //$NON-NLS-1$ //$NON-NLS-2$
            "Элемент", "&НаКлиенте", Scope.FIELD); //$NON-NLS-1$ //$NON-NLS-2$
        register(m, "StartChoice", "НачалоВыбора", //$NON-NLS-1$ //$NON-NLS-2$
            "Элемент, ДанныеВыбора, СтандартнаяОбработка", //$NON-NLS-1$
            "&НаКлиенте", Scope.FIELD); //$NON-NLS-1$
        register(m, "AutoComplete", "АвтоПодбор", //$NON-NLS-1$ //$NON-NLS-2$
            "Элемент, Текст, ДанныеВыбора, ПараметрыПолученияДанных, " //$NON-NLS-1$
                + "ОжиданиеПолученияДанных, СтандартнаяОбработка", //$NON-NLS-1$
            "&НаКлиенте", Scope.FIELD); //$NON-NLS-1$
        register(m, "OnEditEnd", "ПриОкончанииРедактирования", //$NON-NLS-1$ //$NON-NLS-2$
            "Элемент, ОтменаРедактирования", "&НаКлиенте", Scope.FIELD); //$NON-NLS-1$ //$NON-NLS-2$
        // The clear button and Shift+F4. Documented on the input field's form-item extension as
        // Clearing(StandardProcessing); the element comes first here the way it does for every other
        // field event above.
        register(m, "Clearing", "Очистка", //$NON-NLS-1$ //$NON-NLS-2$
            "Элемент, СтандартнаяОбработка", "&НаКлиенте", Scope.FIELD); //$NON-NLS-1$ //$NON-NLS-2$
        register(m, "TextEditEnd", "ОкончаниеВводаТекста", //$NON-NLS-1$ //$NON-NLS-2$
            "Элемент, Текст, ДанныеВыбора, ПараметрыПолученияДанных, СтандартнаяОбработка", //$NON-NLS-1$
            "&НаКлиенте", Scope.FIELD); //$NON-NLS-1$
        register(m, "Opening", "Открытие", //$NON-NLS-1$ //$NON-NLS-2$
            "Элемент, СтандартнаяОбработка", "&НаКлиенте", Scope.FIELD); //$NON-NLS-1$ //$NON-NLS-2$

        // Table events.
        register(m, "BeforeAddRow", "ПередНачаломДобавления", //$NON-NLS-1$ //$NON-NLS-2$
            "Элемент, Отказ, Копирование, Родитель, ЭтоГруппа, Параметр", //$NON-NLS-1$
            "&НаКлиенте", Scope.TABLE); //$NON-NLS-1$
        register(m, "BeforeRowChange", "ПередНачаломИзменения", //$NON-NLS-1$ //$NON-NLS-2$
            "Элемент, Отказ", "&НаКлиенте", Scope.TABLE); //$NON-NLS-1$ //$NON-NLS-2$
        register(m, "BeforeDeleteRow", "ПередНачаломУдаления", //$NON-NLS-1$ //$NON-NLS-2$
            "Элемент, Отказ", "&НаКлиенте", Scope.TABLE); //$NON-NLS-1$ //$NON-NLS-2$
        register(m, "OnActivateRow", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$
            "Элемент", "&НаКлиенте", Scope.TABLE); //$NON-NLS-1$ //$NON-NLS-2$
        register(m, "Selection", "Выбор", //$NON-NLS-1$ //$NON-NLS-2$
            "Элемент, ВыбраннаяСтрока, Колонка, СтандартнаяОбработка", //$NON-NLS-1$
            "&НаКлиенте", Scope.TABLE); //$NON-NLS-1$

        // Button click - bound to a form command.
        register(m, "Click", "Нажатие", //$NON-NLS-1$ //$NON-NLS-2$
            "Команда", "&НаКлиенте", Scope.BUTTON); //$NON-NLS-1$ //$NON-NLS-2$

        EVENTS = Collections.unmodifiableMap(m);
    }

    private static void register(Map<String, EventSpec> m, String en, String ru,
        String signature, String directive, Scope scope)
    {
        EventSpec s = new EventSpec(en, ru, signature, directive, scope);
        m.put(en, s);
        m.put(ru, s);
    }

    private FormEventRegistry()
    {
    }

    /**
     * @return event spec for the given identifier (Russian or English) or
     *         {@code null} when the event is not in the registry.
     */
    public static EventSpec lookup(String eventName)
    {
        if (eventName == null || eventName.isEmpty())
        {
            return null;
        }
        return EVENTS.get(eventName);
    }

    /**
     * Builds a default handler procedure name from the event name and an
     * optional item name. Mirrors the EDT wizard convention:
     * {@code <itemName><EventName>} for items
     * (e.g. {@code НаименованиеПриИзменении}) and just {@code <EventName>}
     * for the form root (e.g. {@code ПриОткрытии}).
     *
     * <p>The returned name uses the event identifier in the same locale the
     * agent supplied (Russian or English).
     */
    public static String defaultHandlerName(String eventName, String itemName)
    {
        if (eventName == null || eventName.isEmpty())
        {
            return null;
        }
        return (itemName == null || itemName.isEmpty()) ? eventName : itemName + eventName;
    }

    /**
     * Generates the BSL handler stub:
     * <pre>
     * &amp;НаКлиенте
     * Процедура ИмяОбработчика(параметры)
     *
     * КонецПроцедуры
     * </pre>
     */
    public static String generateBslStub(String handlerName, EventSpec spec)
    {
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(spec.directive).append('\n');
        sb.append("Процедура ").append(handlerName).append("("); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(spec.signature);
        sb.append(")\n").append("    \n").append("КонецПроцедуры\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return sb.toString();
    }
}
