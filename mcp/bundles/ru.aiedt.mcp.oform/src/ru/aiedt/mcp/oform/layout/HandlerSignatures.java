/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The parameter list a handler procedure takes, per element type and event, and per event of
 * the form and of its main attribute.
 *
 * <p>Voted over the modules of the corpus: for every bound handler the procedure was looked up
 * in the form's module and its parameter list counted; every row below won its vote (824 of 846
 * for a button's click, 1303 of 1318 for a field's change, 370 of 370 for a table's row
 * output). A document's main attribute takes {@code ПередЗаписью(Отказ, РежимЗаписи,
 * РежимПроведения)}, every other object {@code ПередЗаписью(Отказ)}.</p>
 */
public final class HandlerSignatures
{
    private static final Map<String, String> ELEMENT = new HashMap<>();

    private static final Map<String, String> FORM = new HashMap<>();

    private static final Map<String, String> OBJECT = new HashMap<>();

    private static final Map<String, String> SETTINGS = new HashMap<>();

    static
    {
        String field = "ПриИзменении=Элемент; НачалоВыбора=Элемент, СтандартнаяОбработка; Очистка=Элемент, СтандартнаяОбработка; " //$NON-NLS-1$
            + "Открытие=Элемент, СтандартнаяОбработка; ОбработкаВыбора=Элемент, ВыбранноеЗначение, СтандартнаяОбработка; " //$NON-NLS-1$
            + "ОкончаниеВводаТекста=Элемент, Текст, Значение, СтандартнаяОбработка; " //$NON-NLS-1$
            + "АвтоПодборТекста=Элемент, Текст, ТекстАвтоПодбора, СтандартнаяОбработка; " //$NON-NLS-1$
            + "НачалоВыбораИзСписка=Элемент, СтандартнаяОбработка; РегулированиеЗначения=Элемент, Направление, СтандартнаяОбработка"; //$NON-NLS-1$
        put(ELEMENT, "Field", field); //$NON-NLS-1$
        put(ELEMENT, "SelectField", field); //$NON-NLS-1$
        put(ELEMENT, "CheckBox", "ПриИзменении=Элемент"); //$NON-NLS-1$ //$NON-NLS-2$
        put(ELEMENT, "RadioBtn", "ПриИзменении=Элемент"); //$NON-NLS-1$ //$NON-NLS-2$
        put(ELEMENT, "CalendarBox", "ПриИзменении=Элемент; Выбор=Элемент, ВыбраннаяДата"); //$NON-NLS-1$ //$NON-NLS-2$
        put(ELEMENT, "Button", "Нажатие=Элемент"); //$NON-NLS-1$ //$NON-NLS-2$
        put(ELEMENT, "Label", "Нажатие=Элемент"); //$NON-NLS-1$ //$NON-NLS-2$
        put(ELEMENT, "Image", "Нажатие=Элемент"); //$NON-NLS-1$ //$NON-NLS-2$
        put(ELEMENT, "Panel", "ПриСменеСтраницы=Элемент, ТекущаяСтраница"); //$NON-NLS-1$ //$NON-NLS-2$
        put(ELEMENT, "Chart", "ОбработкаРасшифровки=Элемент, Расшифровка, СтандартнаяОбработка"); //$NON-NLS-1$ //$NON-NLS-2$
        put(ELEMENT, "ListField", "ПриАктивизацииСтроки=Элемент"); //$NON-NLS-1$ //$NON-NLS-2$
        put(ELEMENT, "TableField", "Выбор=Элемент, Область, СтандартнаяОбработка; ОбработкаРасшифровки=Элемент, Расшифровка, СтандартнаяОбработка; " //$NON-NLS-1$ //$NON-NLS-2$
            + "ПроверкаПеретаскивания=Элемент, ПараметрыПеретаскивания, СтандартнаяОбработка, Область; " //$NON-NLS-1$
            + "Перетаскивание=Элемент, ПараметрыПеретаскивания, СтандартнаяОбработка, Область; " //$NON-NLS-1$
            + "ОкончаниеПеретаскивания=Элемент, ПараметрыПеретаскивания, СтандартнаяОбработка"); //$NON-NLS-1$
        put(ELEMENT, "Table", "Выбор=Элемент, ВыбраннаяСтрока, Колонка, СтандартнаяОбработка; ПриАктивизацииСтроки=Элемент; " //$NON-NLS-1$ //$NON-NLS-2$
            + "ПриАктивизацииКолонки=Элемент; ПриАктивизацииЯчейки=Элемент; ПередНачаломДобавления=Элемент, Отказ, Копирование; " //$NON-NLS-1$
            + "ПередНачаломИзменения=Элемент, Отказ; ПередУдалением=Элемент, Отказ; ПриНачалеРедактирования=Элемент, НоваяСтрока; " //$NON-NLS-1$
            + "ПередОкончаниемРедактирования=Элемент, НоваяСтрока, ОтменаРедактирования, Отказ; ПриИзмененииФлажка=Элемент, Колонка; " //$NON-NLS-1$
            + "ПриВыводеСтроки=Элемент, ОформлениеСтроки, ДанныеСтроки; ПриОкончанииРедактирования=Элемент, НоваяСтрока, ОтменаРедактирования; " //$NON-NLS-1$
            + "ОбработкаЗаписиНовогоОбъекта=Элемент, Объект, СтандартнаяОбработка; ПослеУдаления=Элемент; " //$NON-NLS-1$
            + "ОбработкаВыбора=Элемент, ВыбранноеЗначение, СтандартнаяОбработка; ПриПолученииДанных=Элемент, ОформленияСтрок; " //$NON-NLS-1$
            + "ПроверкаПеретаскивания=Элемент, ПараметрыПеретаскивания, СтандартнаяОбработка, Строка, Колонка; " //$NON-NLS-1$
            + "Перетаскивание=Элемент, ПараметрыПеретаскивания, СтандартнаяОбработка, Строка, Колонка; " //$NON-NLS-1$
            + "ОкончаниеПеретаскивания=Элемент, ПараметрыПеретаскивания, СтандартнаяОбработка"); //$NON-NLS-1$
        fill(FORM, "ПередОткрытием=Отказ, СтандартнаяОбработка; ПриОткрытии=; ПередЗакрытием=Отказ, СтандартнаяОбработка; ПриЗакрытии=; " //$NON-NLS-1$
            + "ОбработкаВыбора=ЗначениеВыбора, Источник; ОбработкаАктивизацииОбъекта=АктивныйОбъект, Источник; " //$NON-NLS-1$
            + "ОбработкаЗаписиНовогоОбъекта=Объект, Источник; ОбработкаОповещения=ИмяСобытия, Параметр, Источник; " //$NON-NLS-1$
            + "ПриПовторномОткрытии=СтандартнаяОбработка; ОбновлениеОтображения=; ВнешнееСобытие=Источник, Событие, Данные; " //$NON-NLS-1$
            + "ОбработкаПроверкиЗаполнения=Отказ, ПроверяемыеРеквизиты"); //$NON-NLS-1$
        fill(OBJECT, "ПередЗаписью=Отказ; ПриЗаписи=Отказ; ПослеЗаписи=; ПриИзмененииДанных="); //$NON-NLS-1$
        fill(SETTINGS, "ПередСохранениемЗначений=Отказ; ПослеВосстановленияЗначений="); //$NON-NLS-1$
    }

    private HandlerSignatures()
    {
        // static table
    }

    private static void put(Map<String, String> into, String type, String pairs)
    {
        for (String pair : pairs.split("; ")) //$NON-NLS-1$
        {
            int eq = pair.indexOf('=');
            into.put((type + "/" + pair.substring(0, eq)).toLowerCase(Locale.ROOT), pair.substring(eq + 1)); //$NON-NLS-1$
        }
    }

    private static void fill(Map<String, String> into, String pairs)
    {
        for (String pair : pairs.split("; ")) //$NON-NLS-1$
        {
            int eq = pair.indexOf('=');
            into.put(pair.substring(0, eq).toLowerCase(Locale.ROOT), pair.substring(eq + 1));
        }
    }

    /**
     * The parameters of an element's handler.
     *
     * @param type the element type
     * @param event the event
     * @return the parameters as written between the parentheses, empty for none, or
     *         {@code null} when the pair was not measured
     */
    public static String elementParameters(String type, String event)
    {
        return type == null || event == null ? null : ELEMENT.get((type + "/" + event).toLowerCase(Locale.ROOT)); //$NON-NLS-1$
    }

    /**
     * The parameters of a handler of the form or of its main attribute.
     *
     * @param event the event
     * @param settingsHolder whether the main attribute holds settings rather than an object
     * @param document whether the main attribute is a document, whose write event takes the
     *        write and posting modes
     * @return the parameters, empty for none, or {@code null} when the event was not measured
     */
    public static String formParameters(String event, boolean settingsHolder, boolean document)
    {
        if (event == null)
        {
            return null;
        }
        String key = event.toLowerCase(Locale.ROOT);
        String parameters = FORM.get(key);
        if (parameters == null)
        {
            parameters = (settingsHolder ? SETTINGS : OBJECT).get(key);
            if (document && "передзаписью".equals(key)) //$NON-NLS-1$
            {
                parameters = "Отказ, РежимЗаписи, РежимПроведения"; //$NON-NLS-1$
            }
        }
        if (parameters == null)
        {
            parameters = ELEMENT.get("table/" + key); //$NON-NLS-1$
        }
        return parameters;
    }
}
