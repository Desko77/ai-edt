/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Every child kind is known to the one list, whichever question asks about it.
 * <p>
 * There were two lists. The one used to read an address knew accounting flags, enum values,
 * addressing attributes, columns and standard attributes, and did not know forms, templates or the
 * children of a service; the one used to borrow a child into an extension knew exactly the other
 * half. A kind the asking list happened to lack came back as no such child - the object was there,
 * the kind was real, and the answer said otherwise.
 * </p>
 */
public class OneListOfChildKindsTest
{
    /** Every kind, in both languages, against the collection it names. */
    private static Map<String, String> everyKind()
    {
        Map<String, String> kinds = new LinkedHashMap<>();
        kinds.put("Attribute", "getAttributes"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Реквизит", "getAttributes"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("TabularSection", "getTabularSections"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("ТабличнаяЧасть", "getTabularSections"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Dimension", "getDimensions"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Измерение", "getDimensions"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Resource", "getResources"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Ресурс", "getResources"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("AccountingFlag", "getAccountingFlags"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("ПризнакУчета", "getAccountingFlags"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("ExtDimensionAccountingFlag", "getExtDimensionAccountingFlags"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("ПризнакУчетаСубконто", "getExtDimensionAccountingFlags"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Command", "getCommands"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Команда", "getCommands"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("EnumValue", "getEnumValues"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("ЗначениеПеречисления", "getEnumValues"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("AddressingAttribute", "getAddressingAttributes"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("РеквизитАдресации", "getAddressingAttributes"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Column", "getColumns"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Графа", "getColumns"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("StandardAttribute", "getStandardAttributes"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("СтандартныйРеквизит", "getStandardAttributes"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Form", "getForms"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Форма", "getForms"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Template", "getTemplates"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Макет", "getTemplates"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("URLTemplate", "getUrlTemplates"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("ШаблонURL", "getUrlTemplates"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Method", "getMethods"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Метод", "getMethods"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Operation", "getOperations"); //$NON-NLS-1$ //$NON-NLS-2$
        kinds.put("Операция", "getOperations"); //$NON-NLS-1$ //$NON-NLS-2$
        return kinds;
    }

    /** Both halves of what used to be two lists are in the one. */
    @Test
    public void everyKindEitherListKnewIsKnown()
    {
        for (Map.Entry<String, String> kind : everyKind().entrySet())
        {
            assertEquals(kind.getKey(), kind.getValue(),
                BmObjectHelper.childKindGetter(kind.getKey()));
            assertTrue(kind.getKey(), BmObjectHelper.isChildKind(kind.getKey()));
        }
    }

    /** The kind arrives from a caller, who writes it as they please. */
    @Test
    public void theCaseTheCallerWroteDoesNotDecide()
    {
        assertEquals("getAttributes", BmObjectHelper.childKindGetter("attribute")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("getAttributes", BmObjectHelper.childKindGetter("ATTRIBUTE")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("getEnumValues", BmObjectHelper.childKindGetter("enumvalue")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("getForms", BmObjectHelper.childKindGetter("ФОРМА")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A word that names no child kind is still not one. */
    @Test
    public void somethingThatIsNotAChildKindIsNotOne()
    {
        assertNull(BmObjectHelper.childKindGetter("Catalog")); //$NON-NLS-1$
        assertNull(BmObjectHelper.childKindGetter("")); //$NON-NLS-1$
        assertNull(BmObjectHelper.childKindGetter(null));
    }
}
