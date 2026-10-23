/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.BraceTree.ListNode;

/**
 * The standard actions a command-panel button can run, by their English camelCase name.
 * <p>
 * Each entry holds the action set the code belongs to, the code, the name the platform gives the
 * action, the number of corpus buttons that run it, and the record the corpus most often writes
 * for it - that record, with a fresh uuid for the action, is what {@code add_button action=<name>}
 * writes. The button counts are every button of the 2474 panels of the corpus, not only the
 * panels the platform does not fill itself. A code the platform reads under two names depends
 * on the list the panel serves, and the stand names it - those entries say so.
 * </p>
 */
public final class StandardActions
{
    /** One registered action. */
    public static final class Action
    {
        /** The English camelCase name the tool takes. */
        public final String name;

        /** The action set uuid the code belongs to. */
        public final String set;

        /** The code within the set. */
        public final String code;

        /** The name the platform gives the action. */
        public final String title;

        /** How many corpus buttons run it. */
        public final int buttons;

        /** The record the corpus most often writes for the action, uuid as {@code @U}. */
        public final String record;

        Action(String name, String set, String code, String title, int buttons, String record)
        {
            this.name = name;
            this.set = set;
            this.code = code;
            this.title = title;
            this.buttons = buttons;
            this.record = record;
        }

        /** @return the record with the action's uuid fresh */
        public ListNode freshRecord()
        {
            ListNode node = BraceTree.parse(record);
            CommandPanels.substitute(node, "@U", new BraceTree.Token(java.util.UUID.randomUUID().toString())); //$NON-NLS-1$
            return node;
        }
    }

    /** The generic action set, the nulls uuid. */
    public static final String NULL_SET = "00000000-0000-0000-0000-000000000000"; //$NON-NLS-1$

    private static final String HIER_SET = "55c7732c-0c33-4394-adca-9d15082552b6"; //$NON-NLS-1$

    private static final String LIST_SET = "6b7291bf-bcd2-41af-bac7-414d47cc6e6a"; //$NON-NLS-1$

    private static final Map<String, Action> BY_NAME = new LinkedHashMap<>();

    private static final Map<String, Action> BY_KEY = new LinkedHashMap<>();

    static
    {
        // The nulls set - the generic actions every list and form answers to.
        entry("close", NULL_SET, "142", "Закрыть", 665, 149); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("write", NULL_SET, "140", "Записать", 263, 88); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("writeAndClose", NULL_SET, "141", "Записать и закрыть (ОК on a choice form)", 261, 86); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("help", NULL_SET, "300", "Справка", 358, 274); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("add", NULL_SET, "80", "Добавить", 247, 123); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("change", NULL_SET, "84", "Изменить", 244, 130); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("delete", NULL_SET, "83", "Удалить", 171, 79); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("copy", NULL_SET, "82", "Скопировать", 186, 97); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("moveUp", NULL_SET, "85", "Переместить вверх", 111, 79); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("moveDown", NULL_SET, "86", "Переместить вниз", 111, 79); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("sortAscending", NULL_SET, "88", "Сортировать по возрастанию", 95, 69); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("sortDescending", NULL_SET, "89", "Сортировать по убыванию", 95, 69); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("finishEditing", NULL_SET, "94", "Закончить редактирование", 97, 71); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("filterByValue", NULL_SET, "91", "Отбор по значению в текущей колонке", 131, 58); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("disableFilter", NULL_SET, "93", "Отключить отбор", 132, 58); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("saveValues", NULL_SET, "143", "Сохранить значения...", 79, 50); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("restoreValues", NULL_SET, "144", "Восстановить значения...", 79, 50); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("copyObject", NULL_SET, "145", "Скопировать (a copy of the object from its form)", 69, 31); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("reread", NULL_SET, "146", "Перечитать", 73, 33); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("findInList", NULL_SET, "147", "Найти в списке", 76, 35); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("refresh", NULL_SET, "90", "Обновить", 105, 54); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("listSettings", NULL_SET, "97", "Настройка списка...", 90, 58); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("outputList", NULL_SET, "99", "Вывести список...", 70, 58); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("select", NULL_SET, "95", "Выбрать", 239, 230); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("enableAll", NULL_SET, "120", "Включить все", 8, 6); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("disableAll", NULL_SET, "121", "Выключить все", 8, 6); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("back", NULL_SET, "320", "Назад", 3, 2); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("forward", NULL_SET, "321", "Вперед", 3, 2); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // The set of hierarchical lists and documents.
        entry("newGroup", HIER_SET, "6", "Новая группа", 11, 4); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("findInTree", HIER_SET, "7", "Найти в дереве", 6, 3); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("moveToGroup", HIER_SET, "8", "Переместить в группу", 11, 4); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("hierarchy", HIER_SET, "9", "Иерархический просмотр", 11, 4); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("viewByOwner", HIER_SET, "11", "Просмотр по владельцу", 2, 2); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("searchByNumber", HIER_SET, "20", "Поиск по номеру...", 42, 21); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("setFilterAndSort", HIER_SET, "21", "Установить отбор и сортировку списка...", 39, 20); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("post", HIER_SET, "24", "Провести", 11, 4); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("unpost", HIER_SET, "29", "Отмена проведения", 11, 4); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("editInDialog", HIER_SET, "58", "Редактировать в диалоге", 7, 4); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // The set of lists.
        entry("markDeletion", LIST_SET, "38", "Установить пометку удаления", 101, 49); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("setFilter", LIST_SET, "53", "Отбор по значению в текущей колонке", 132, 58); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        entry("enterOnBasis", LIST_SET, "40", "(Ввести на основании)", 4, 4); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (Action action : BY_NAME.values())
        {
            BY_KEY.put(action.set + ":" + action.code, action); //$NON-NLS-1$
        }
    }

    private static final String TYPE = "fbe38877-b914-4fd5-8540-07dde06ba2e1"; //$NON-NLS-1$

    private StandardActions()
    {
        // static registry
    }

    private static void entry(String name, String set, String code, String title, int buttons, int shape)
    {
        String record = recordOf(name);
        if (record == null)
        {
            throw new IllegalStateException("no measured record for the action " + name); //$NON-NLS-1$
        }
        BY_NAME.put(name, new Action(name, set, code, title, buttons, record));
    }

    /** @return the action a name names, or {@code null}; case does not matter */
    public static Action find(String name)
    {
        if (name == null)
        {
            return null;
        }
        String key = name.trim();
        Action direct = BY_NAME.get(key);
        if (direct != null)
        {
            return direct;
        }
        for (Map.Entry<String, Action> entry : BY_NAME.entrySet())
        {
            if (entry.getKey().equalsIgnoreCase(key))
            {
                return entry.getValue();
            }
        }
        return null;
    }

    /** @return the action a set and code name, or {@code null} */
    public static Action bySetAndCode(String set, String code)
    {
        return set == null || code == null ? null : BY_KEY.get(set.toLowerCase() + ":" + code); //$NON-NLS-1$
    }

    /** @return every registered action, in registration order */
    public static Collection<Action> all()
    {
        return BY_NAME.values();
    }

    /** @return the registered names, in registration order */
    public static List<String> names()
    {
        return new ArrayList<>(BY_NAME.keySet());
    }

    /**
     * The measured record of an action, its uuid as {@code @U}: the most frequent full record of
     * the corpus for the action's set and code, with the picture and the numbers of the payload as
     * the majority of the corpus writes them.
     */
    private static String recordOf(String name)
    {
        String payload;
        String tail;
        switch (name)
        {
        case "close": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",142,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,55,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "write": //$NON-NLS-1$
            payload = "{6,0," + NULL_SET + ",140,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "6,{1,1,{\"ru\",\"Записать\"}},{1,1,{\"ru\",\"Записать\"}}"; //$NON-NLS-1$
            break;
        case "writeAndClose": //$NON-NLS-1$
            payload = "{6,0," + NULL_SET + ",141,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "6,{1,1,{\"ru\",\"ОК\"}},{1,1,{\"ru\",\"ОК\"}}"; //$NON-NLS-1$
            break;
        case "help": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",300,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "add": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",80,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "change": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",84,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "delete": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",83,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "copy": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",82,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "moveUp": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",85,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "moveDown": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",86,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "sortAscending": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",88,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "sortDescending": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",89,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "finishEditing": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",94,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "filterByValue": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",91,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,55,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "disableFilter": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",93,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,55,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "saveValues": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",143,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "restoreValues": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",144,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "copyObject": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",145,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "reread": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",146,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,55,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "findInList": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",147,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "refresh": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",90,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "listSettings": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",97,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,55,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "outputList": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",99,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,55,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "select": //$NON-NLS-1$
            payload = "{6,1," + NULL_SET + ",95,{1,0,357c6a54-357d-425d-a2bd-22f4f6e86c87,2147483647,0},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "enableAll": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",120,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "disableAll": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",121,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "back": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",320,{1,99,357c6a54-357d-425d-a2bd-22f4f6e86c87,2147483647,0},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "forward": //$NON-NLS-1$
            payload = "{6,4294967295," + NULL_SET + ",321,{1,99,357c6a54-357d-425d-a2bd-22f4f6e86c87,2147483647,0},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "newGroup": //$NON-NLS-1$
            payload = "{6,4294967295," + HIER_SET + ",6,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "findInTree": //$NON-NLS-1$
            payload = "{6,4294967295," + HIER_SET + ",7,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "moveToGroup": //$NON-NLS-1$
            payload = "{6,4294967295," + HIER_SET + ",8,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "hierarchy": //$NON-NLS-1$
            payload = "{6,4294967295," + HIER_SET + ",9,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "viewByOwner": //$NON-NLS-1$
            payload = "{6,4294967295," + HIER_SET + ",11,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,55,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "searchByNumber": //$NON-NLS-1$
            payload = "{6,4294967295," + HIER_SET + ",20,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "setFilterAndSort": //$NON-NLS-1$
            payload = "{6,4294967295," + HIER_SET + ",21,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,55,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "post": //$NON-NLS-1$
            payload = "{6,4294967295," + HIER_SET + ",24,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "unpost": //$NON-NLS-1$
            payload = "{6,4294967295," + HIER_SET + ",29,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "editInDialog": //$NON-NLS-1$
            payload = "{6,4294967295," + HIER_SET + ",58,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,55,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "markDeletion": //$NON-NLS-1$
            payload = "{6,4294967295," + LIST_SET + ",38,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "setFilter": //$NON-NLS-1$
            payload = "{6,4294967295," + LIST_SET + ",53,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,55,1},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        case "enterOnBasis": //$NON-NLS-1$
            payload = "{6,4294967295," + LIST_SET + ",40,{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,0},0,1}"; //$NON-NLS-1$
            tail = "0"; //$NON-NLS-1$
            break;
        default:
            return null;
        }
        return "{6,@U,1," + TYPE + "," + payload + "," + tail + "}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
