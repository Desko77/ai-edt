/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The events of an ordinary form and of its elements, by the code the layout records them
 * under.
 *
 * <p>A bound handler is an entry {@code {<code>, e1692cc2-605b-4535-84dd-28440238746c,
 * {3,"<Procedure>",{1,"<Procedure>",...}}}} in the events list of an element or of the form:
 * the code names the event, the uuid is the same in every entry, the procedure is named
 * twice. Which code is which event was measured over the handlers of a corpus of 1335 forms:
 * the Designer names a handler {@code <Element><Event>}, so the suffix of the procedure name
 * votes for the event, and every pair below won its vote. The form's own events are the codes
 * from 70000; the events of the form's main attribute are the codes from 80000, and what they
 * are depends on what the main attribute holds - an object, or the settings of a report or
 * data processor. A code outside the table stays a code.</p>
 */
public final class FormEvents
{
    /** The uuid every handler entry carries after the code. */
    public static final String HANDLER = "e1692cc2-605b-4535-84dd-28440238746c"; //$NON-NLS-1$

    private static final Map<String, Map<Integer, String>> BY_TYPE = new HashMap<>();

    private static final Map<Integer, String> FORM = new LinkedHashMap<>();

    private static final Map<Integer, String> OBJECT = new LinkedHashMap<>();

    private static final Map<Integer, String> SETTINGS = new LinkedHashMap<>();

    static
    {
        String editable = "2147483647 ПриИзменении, 1 НачалоВыбораИзСписка, 2 НачалоВыбора, 3 Очистка, " //$NON-NLS-1$
            + "4 РегулированиеЗначения, 5 Открытие, 7 ОбработкаВыбора, 10 ОкончаниеВводаТекста, 11 АвтоПодборТекста"; //$NON-NLS-1$
        put("Field", editable); //$NON-NLS-1$
        put("SelectField", editable); //$NON-NLS-1$
        put("CheckBox", "2147483647 ПриИзменении"); //$NON-NLS-1$ //$NON-NLS-2$
        put("RadioBtn", "2147483647 ПриИзменении"); //$NON-NLS-1$ //$NON-NLS-2$
        put("CalendarBox", "2147483647 ПриИзменении, 1 Выбор, 2 ПриВыводеПериода, 4 ПроверкаПеретаскивания, 6 Перетаскивание"); //$NON-NLS-1$ //$NON-NLS-2$
        put("Button", "0 Нажатие"); //$NON-NLS-1$ //$NON-NLS-2$
        put("Label", "0 Нажатие"); //$NON-NLS-1$ //$NON-NLS-2$
        put("Image", "0 Нажатие"); //$NON-NLS-1$ //$NON-NLS-2$
        put("Panel", "0 ПриСменеСтраницы"); //$NON-NLS-1$ //$NON-NLS-2$
        put("Chart", "1 ОбработкаРасшифровки"); //$NON-NLS-1$ //$NON-NLS-2$
        put("ListField", "37 ПриАктивизацииСтроки"); //$NON-NLS-1$ //$NON-NLS-2$
        put("GraphicalSchemaField", "1 ПриАктивизации"); //$NON-NLS-1$ //$NON-NLS-2$
        put("FieldHtml", "0 ДокументСформирован, 2 onclick, 17 ondragstart"); //$NON-NLS-1$ //$NON-NLS-2$
        put("TableField", "0 Выбор, 1 ОбработкаРасшифровки, 2 ПриАктивизацииОбласти, 3 НачалоПеретаскивания, " //$NON-NLS-1$ //$NON-NLS-2$
            + "4 ПроверкаПеретаскивания, 5 ОкончаниеПеретаскивания, 6 Перетаскивание"); //$NON-NLS-1$
        put("Table", "34 Выбор, 35 ПриАктивизацииСтроки, 36 ПриАктивизацииКолонки, 37 ПриАктивизацииЯчейки, " //$NON-NLS-1$ //$NON-NLS-2$
            + "40 ПередНачаломДобавления, 41 ПередНачаломИзменения, 42 ПередУдалением, 43 ПриНачалеРедактирования, " //$NON-NLS-1$
            + "44 ПередОкончаниемРедактирования, 45 ПриИзмененииФлажка, 47 ПриВыводеСтроки, 48 ВыборЗначения, " //$NON-NLS-1$
            + "49 ПриОкончанииРедактирования, 50 ОбработкаЗаписиНовогоОбъекта, 51 ПослеУдаления, 52 ОбработкаВыбора, " //$NON-NLS-1$
            + "53 ПриПолученииДанных, 900 НачалоПеретаскивания, 901 ПроверкаПеретаскивания, 902 ОкончаниеПеретаскивания, " //$NON-NLS-1$
            + "903 Перетаскивание, 10000 ПередРазворачиванием, 10001 ПередУстановкойПометкиУдаления"); //$NON-NLS-1$
        fill(FORM, "70000 ПередОткрытием, 70001 ПриОткрытии, 70002 ПередЗакрытием, 70003 ПриЗакрытии, 70004 ОбработкаВыбора, " //$NON-NLS-1$
            + "70005 ОбработкаАктивизацииОбъекта, 70006 ОбработкаЗаписиНовогоОбъекта, 70007 ОбработкаОповещения, " //$NON-NLS-1$
            + "70008 ПриПовторномОткрытии, 70009 ОбновлениеОтображения, 70010 ВнешнееСобытие, 70011 ОбработкаПроверкиЗаполнения"); //$NON-NLS-1$
        fill(OBJECT, "80000 ПередЗаписью, 80001 ПриЗаписи, 80002 ПослеЗаписи, 80003 ПриИзмененииДанных"); //$NON-NLS-1$
        fill(SETTINGS, "80000 ПередСохранениемЗначений, 80001 ПослеВосстановленияЗначений"); //$NON-NLS-1$
    }

    private FormEvents()
    {
        // static table
    }

    private static void put(String type, String pairs)
    {
        Map<Integer, String> codes = new LinkedHashMap<>();
        fill(codes, pairs);
        BY_TYPE.put(type.toLowerCase(Locale.ROOT), codes);
    }

    private static void fill(Map<Integer, String> codes, String pairs)
    {
        for (String pair : pairs.split(", ")) //$NON-NLS-1$
        {
            String[] parts = pair.trim().split(" "); //$NON-NLS-1$
            codes.put(Integer.valueOf(parts[0]), parts[1]);
        }
    }

    /**
     * The event of an element by code.
     *
     * @param type the element type as the reader names it ({@code Field}, {@code Table}, ...)
     * @param code the code of the entry
     * @return the event name, or {@code null} when the pair was not measured
     */
    public static String elementEvent(String type, int code)
    {
        Map<Integer, String> codes = type == null ? null : BY_TYPE.get(type.toLowerCase(Locale.ROOT));
        return codes == null ? null : codes.get(Integer.valueOf(code));
    }

    /**
     * The code of an element's event by name.
     *
     * @param type the element type as the reader names it
     * @param event the event name, case-insensitive
     * @return the code, or {@code null} when the pair was not measured
     */
    public static Integer elementCode(String type, String event)
    {
        Map<Integer, String> codes = type == null ? null : BY_TYPE.get(type.toLowerCase(Locale.ROOT));
        if (codes == null || event == null)
        {
            return null;
        }
        for (Map.Entry<Integer, String> entry : codes.entrySet())
        {
            if (entry.getValue().equalsIgnoreCase(event))
            {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * The events of an element type, code to name, in the order measured.
     *
     * @param type the element type as the reader names it
     * @return the events; empty for a type without measured events
     */
    public static Map<Integer, String> elementEvents(String type)
    {
        Map<Integer, String> codes = type == null ? null : BY_TYPE.get(type.toLowerCase(Locale.ROOT));
        return codes == null ? Collections.emptyMap() : Collections.unmodifiableMap(codes);
    }

    /**
     * The event of the form, or of its main attribute, by code.
     *
     * @param code the code of the entry
     * @param settingsHolder whether the form's main attribute holds the settings of a report or a
     *        data processor rather than an object, which decides what the codes from 80000 mean
     * @return the event name, or {@code null} when the code was not measured
     */
    public static String formEvent(int code, boolean settingsHolder)
    {
        String name = FORM.get(Integer.valueOf(code));
        if (name == null)
        {
            name = (settingsHolder ? SETTINGS : OBJECT).get(Integer.valueOf(code));
        }
        if (name == null)
        {
            // A list as the main attribute binds the events of a table at the form level.
            name = elementEvent("Table", code); //$NON-NLS-1$
        }
        return name;
    }

    /**
     * The code of the form's own event, or of its main attribute's event, by name.
     *
     * @param event the event name, case-insensitive
     * @param settingsHolder whether the form's main attribute holds settings rather than an object
     * @return the code, or {@code null}
     */
    public static Integer formCode(String event, boolean settingsHolder)
    {
        if (event == null)
        {
            return null;
        }
        for (Map<Integer, String> table : List.of(FORM, settingsHolder ? SETTINGS : OBJECT, BY_TYPE.get("table"))) //$NON-NLS-1$
        {
            for (Map.Entry<Integer, String> entry : table.entrySet())
            {
                if (entry.getValue().equalsIgnoreCase(event))
                {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * The form's own events, code to name.
     *
     * @return the events
     */
    public static Map<Integer, String> formEvents()
    {
        return Collections.unmodifiableMap(FORM);
    }

    /**
     * The events of the form's main attribute, code to name.
     *
     * @param settingsHolder whether the main attribute holds settings rather than an object
     * @return the events
     */
    public static Map<Integer, String> mainAttributeEvents(boolean settingsHolder)
    {
        return Collections.unmodifiableMap(settingsHolder ? SETTINGS : OBJECT);
    }
}
