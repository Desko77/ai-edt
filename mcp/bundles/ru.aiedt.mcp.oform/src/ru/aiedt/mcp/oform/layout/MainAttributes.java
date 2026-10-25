/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The main attribute an ordinary form of a given purpose holds, per kind of owner: the name
 * the Designer gives it, the produced type of the owner it is typed with, and the block the
 * layout keeps the main attribute's events in.
 *
 * <p>Measured over the forms of a configuration: a form of an object holds the owner's object
 * type ({@code СправочникОбъект}, {@code ДокументОбъект}, ...), a form of a list its list type
 * ({@code СправочникСписок}, ...), a record form of an information register its record manager
 * type, a record set form its record set type; a data processor or report form holds the
 * object type as {@code ОбработкаОбъект} / {@code ОтчетОбъект}. The events block is
 * {@code {<uuid of the kind>, 1, {...}}} with an events list somewhere inside, or
 * {@code {<uuid>, 0}} for a list; a form with no main attribute carries the zero uuid. The
 * shapes below are the ones the corpus holds with no handler bound.</p>
 */
public final class MainAttributes
{
    /** A form's purpose, as the create operation names it. */
    public enum Purpose
    {
        /** the form of an object: item, folder, document, data processor, report */
        OBJECT("defaultObjectForm"), //$NON-NLS-1$
        /** the form of a list */
        LIST("defaultListForm"), //$NON-NLS-1$
        /** the choice form: a list form the owner opens for choice */
        CHOICE("defaultChoiceForm"), //$NON-NLS-1$
        /** the folder form of a hierarchical catalog */
        FOLDER("defaultFolderForm"), //$NON-NLS-1$
        /** the record form of an information register */
        RECORD("defaultRecordForm"), //$NON-NLS-1$
        /** the record set form of a register */
        RECORD_SET(null),
        /** a form with no main attribute */
        NONE(null);

        /** The owner property that names the default form of this purpose, or {@code null}. */
        public final String defaultSetter;

        Purpose(String defaultSetter)
        {
            this.defaultSetter = defaultSetter;
        }
    }

    /** The main attribute of one (owner kind, purpose). */
    public static final class Spec
    {
        /** The attribute's name, as the Designer names it. */
        public final String name;

        /** The feature of the owner's produced types the attribute is typed with. */
        public final String producedType;

        /** The events block of the main attribute, with no handler bound. */
        public final String block;

        Spec(String name, String producedType, String block)
        {
            this.name = name;
            this.producedType = producedType;
            this.block = block;
        }
    }

    /** The events block of a form with no main attribute. */
    public static final String NO_MAIN_ATTRIBUTE_BLOCK = "{00000000-0000-0000-0000-000000000000,0}"; //$NON-NLS-1$

    private static final Map<String, Spec> OBJECT = new LinkedHashMap<>();

    private static final Map<String, Spec> LIST = new LinkedHashMap<>();

    private static final Map<String, Spec> RECORD = new LinkedHashMap<>();

    private static final Map<String, Spec> RECORD_SET = new LinkedHashMap<>();

    static
    {
        OBJECT.put("catalog", new Spec("СправочникОбъект", "objectType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{c5251418-7f99-4a2f-ba62-7f3c9e7bf914,1,{2,0,{0},1}}")); //$NON-NLS-1$
        LIST.put("catalog", new Spec("СправочникСписок", "listType", "{53fb5449-841a-459c-9a83-576f05941e1f,0}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OBJECT.put("document", new Spec("ДокументОбъект", "objectType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{4b7f5926-3927-48c8-a955-c1b03658f9c4,1,{4,{0},1,3,3,1}}")); //$NON-NLS-1$
        LIST.put("document", new Spec("ДокументСписок", "listType", "{8d9c2110-ed81-11d5-b9b6-0050bae0a95d,0}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OBJECT.put("chartofcharacteristictypes", new Spec("ПланВидовХарактеристикОбъект", "objectType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{caf71ec7-8789-41ae-863c-7d24bd53a1a2,1,{2,0,{0},1}}")); //$NON-NLS-1$
        LIST.put("chartofcharacteristictypes", new Spec("ПланВидовХарактеристикСписок", "listType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{357bcdc7-67b5-40ce-ba8c-6c01177d42c9,0}")); //$NON-NLS-1$
        OBJECT.put("exchangeplan", new Spec("ПланОбменаОбъект", "objectType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{6c60ed71-a196-4b2e-9344-7e346610f97d,1,{1,{0}}}")); //$NON-NLS-1$
        LIST.put("exchangeplan", new Spec("ПланОбменаСписок", "listType", "{3cf35b50-98f7-4d87-be9a-7dd3d78da3b4,0}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OBJECT.put("businessprocess", new Spec("БизнесПроцессОбъект", "objectType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{35d891ce-14b8-4780-be70-7d0b8da797b7,1,{1,{0},0}}")); //$NON-NLS-1$
        LIST.put("businessprocess", new Spec("БизнесПроцессСписок", "listType", "{137e64ae-a76f-44de-bcc7-c6386c694768,0}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OBJECT.put("task", new Spec("ЗадачаОбъект", "objectType", "{4e4d55fb-a9c5-4b82-9812-63e14bcb685c,1,{1,{0},0}}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        LIST.put("task", new Spec("ЗадачаСписок", "listType", "{9ad84a0d-87fe-46e8-afb6-22c659c547be,0}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OBJECT.put("dataprocessor", new Spec("ОбработкаОбъект", "objectType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{59d6c227-97d3-46f6-84a0-584c5a2807e1,1,{2,0,{0,0},{0},1}}")); //$NON-NLS-1$
        OBJECT.put("report", new Spec("ОтчетОбъект", "objectType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{343b4e00-29f0-11d6-a3c7-0050bae0a776,1,{2,0,{0,0},{0},1,4294967295}}")); //$NON-NLS-1$
        LIST.put("informationregister", new Spec("РегистрСведенийСписок", "listType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{cb575e8c-871e-4a9e-9767-e7c783bdf1e6,0}")); //$NON-NLS-1$
        RECORD.put("informationregister", new Spec("РегистрСведенийМенеджерЗаписи", "recordManagerType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{8aac6b95-2cce-43d4-9c93-d8dcd2d0a6eb,1,{1,{0}}}")); //$NON-NLS-1$
        RECORD_SET.put("informationregister", new Spec("РегистрСведенийНаборЗаписей", "recordSetType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{ab464421-f1ec-4145-8448-db5942239602,1,{1,{0}}}")); //$NON-NLS-1$
        LIST.put("accumulationregister", new Spec("РегистрНакопленияСписок", "listType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{8dbd9f80-21ff-11d6-a3c7-0050bae0a776,0}")); //$NON-NLS-1$
    }

    private MainAttributes()
    {
        // static utility
    }

    /**
     * The purpose named by a caller.
     *
     * @param purpose {@code ItemForm}, {@code ObjectForm}, {@code DocumentForm}, {@code Generic},
     *        {@code ListForm}, {@code ChoiceForm}, {@code FolderForm}, {@code RecordForm},
     *        {@code RecordSetForm} or {@code None}, in any case; {@code null} for the owner's
     *        natural purpose
     * @param ownerKind the owner's kind, English singular
     * @return the purpose, or {@code null} when the word names none
     */
    public static Purpose purposeOf(String purpose, String ownerKind)
    {
        String kind = ownerKind == null ? "" : ownerKind.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        if (purpose == null || purpose.trim().isEmpty())
        {
            if (OBJECT.containsKey(kind))
            {
                return Purpose.OBJECT;
            }
            return LIST.containsKey(kind) ? Purpose.LIST : Purpose.NONE;
        }
        switch (purpose.trim().toLowerCase(Locale.ROOT))
        {
        case "itemform": //$NON-NLS-1$
        case "objectform": //$NON-NLS-1$
        case "documentform": //$NON-NLS-1$
        case "object": //$NON-NLS-1$
        case "generic": //$NON-NLS-1$
            return Purpose.OBJECT;
        case "listform": //$NON-NLS-1$
        case "list": //$NON-NLS-1$
            return Purpose.LIST;
        case "choiceform": //$NON-NLS-1$
        case "choice": //$NON-NLS-1$
            return Purpose.CHOICE;
        case "folderform": //$NON-NLS-1$
        case "folder": //$NON-NLS-1$
            return Purpose.FOLDER;
        case "recordform": //$NON-NLS-1$
        case "record": //$NON-NLS-1$
            return Purpose.RECORD;
        case "recordsetform": //$NON-NLS-1$
        case "recordset": //$NON-NLS-1$
            return Purpose.RECORD_SET;
        case "none": //$NON-NLS-1$
        case "empty": //$NON-NLS-1$
            return Purpose.NONE;
        default:
            return null;
        }
    }

    /**
     * The main attribute of a form of a purpose on an owner of a kind.
     *
     * @param ownerKind the owner's kind, English singular
     * @param purpose the purpose
     * @return the attribute, or {@code null} when the corpus measured no such form
     */
    public static Spec of(String ownerKind, Purpose purpose)
    {
        String kind = ownerKind == null ? "" : ownerKind.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        switch (purpose)
        {
        case OBJECT:
        case FOLDER:
            return OBJECT.get(kind);
        case LIST:
        case CHOICE:
            return LIST.get(kind);
        case RECORD:
            return RECORD.get(kind);
        case RECORD_SET:
            return RECORD_SET.get(kind);
        default:
            return null;
        }
    }

    /**
     * The purposes measured for an owner of a kind.
     *
     * @param ownerKind the owner's kind, English singular
     * @return the purposes, {@link Purpose#NONE} always among them
     */
    public static List<Purpose> purposesOf(String ownerKind)
    {
        String kind = ownerKind == null ? "" : ownerKind.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        List<Purpose> out = new ArrayList<>();
        if (OBJECT.containsKey(kind))
        {
            out.add(Purpose.OBJECT);
            if ("catalog".equals(kind) || "chartofcharacteristictypes".equals(kind)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                out.add(Purpose.FOLDER);
            }
        }
        if (LIST.containsKey(kind))
        {
            out.add(Purpose.LIST);
            out.add(Purpose.CHOICE);
        }
        if (RECORD.containsKey(kind))
        {
            out.add(Purpose.RECORD);
        }
        if (RECORD_SET.containsKey(kind))
        {
            out.add(Purpose.RECORD_SET);
        }
        out.add(Purpose.NONE);
        return out;
    }

    /**
     * The owner property a purpose's default form is named in, for an owner of a kind.
     *
     * @param ownerKind the owner's kind, English singular
     * @param purpose the purpose
     * @return the property, or {@code null} when the purpose has no default slot
     */
    public static String defaultSetter(String ownerKind, Purpose purpose)
    {
        String kind = ownerKind == null ? "" : ownerKind.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        if ("dataprocessor".equals(kind) || "report".equals(kind)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return purpose == Purpose.OBJECT ? "defaultForm" : null; //$NON-NLS-1$
        }
        return purpose.defaultSetter;
    }
}
