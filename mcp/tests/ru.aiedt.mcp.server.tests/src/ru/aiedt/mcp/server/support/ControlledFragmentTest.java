/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Guards the check for the change that breaks quietly.
 * <p>
 * A controlled change carries a copy of the base method's code and edits it in place. The platform
 * applies the extension only while the code around the markers is still the code the base has - so
 * a release that reformats one line of that method makes the extension refuse to load, with nothing
 * about the extension having changed.
 * </p>
 */
public class ControlledFragmentTest
{
    private static final String HANDLER = String.join("\n",
        "	Если Отказ Тогда",
        "		Возврат;",
        "	КонецЕсли;",
        "#Вставка",
        "	ЖурналРегистрации.Записать(\"Расширение\");",
        "#КонецВставки",
        "	Записать();");

    private static final String BASE = String.join("\n",
        "	Если Отказ Тогда",
        "		Возврат;",
        "	КонецЕсли;",
        "	Записать();");

    @Test
    public void whatTheExtensionInsertedIsNotControlled()
    {
        List<String> controlled = ControlledFragment.controlledPartOf(HANDLER);
        String joined = String.join("|", controlled);
        assertTrue(joined, joined.contains("Возврат"));
        assertTrue("the extension's own line is its own, and the base never had it: " + joined,
            !joined.contains("ЖурналРегистрации"));
    }

    @Test
    public void codeTheExtensionDeletesIsStillControlled()
    {
        // It is the base's own code, marked for removal, and the platform checks it against the
        // base exactly like the untouched lines around it. Dropping it would let a release change
        // the very lines an extension deletes and pass unnoticed.
        String handler = String.join("\n", "	Начало();", "#Удаление", "	Старое();",
            "#КонецУдаления", "	Конец();");
        String joined = String.join("|", ControlledFragment.controlledPartOf(handler));
        assertTrue(joined, joined.contains("Старое()"));
        assertTrue("the markers themselves belong to the extension", !joined.contains("#"));
    }

    @Test
    public void anUntouchedMethodMatches()
    {
        assertNull(ControlledFragment.describeDrift(HANDLER, BASE));
    }

    @Test
    public void aReformattedMethodStillMatches()
    {
        // Indentation, blank lines and the case of identifiers. Reporting a reindent as a break
        // would make this check unusable on the first release that touched formatting.
        String reformatted = String.join("\n", "ЕСЛИ отказ ТОГДА", "", "    ВОЗВРАТ;",
            "КОНЕЦЕСЛИ;", "    записать();");
        assertNull(ControlledFragment.describeDrift(HANDLER, reformatted));
    }

    @Test
    public void aLineTheDeliveryNoLongerHasIsNamed()
    {
        String changed = String.join("\n", "	Если Отказ Тогда", "		ВызватьИсключение \"нет\";",
            "	КонецЕсли;", "	Записать();");
        String drift = ControlledFragment.describeDrift(HANDLER, changed);
        assertNotNull(drift);
        assertTrue("the first line to fix is the one to name, because the extension refuses to "
            + "load on the first mismatch: " + drift, drift.contains("Возврат"));
    }

    @Test
    public void codeInsertedIntoTheMiddleOfWhatIsControlledIsCalledOut()
    {
        // Every controlled line still exists, so a check that only looked for missing lines would
        // pass - and the platform would refuse the extension, because the run is broken.
        String interrupted = String.join("\n", "	Если Отказ Тогда", "		Возврат;",
            "	КонецЕсли;", "	ПроверитьПрава();", "	Записать();");
        String drift = ControlledFragment.describeDrift(HANDLER, interrupted);
        assertNotNull("the run was broken, and that is a refusal to load: " + drift, drift);
        assertTrue(drift, drift.contains("unbroken run"));
    }

    @Test
    public void aHandlerThatControlsNothingIsNotAFinding()
    {
        String onlyInsertions = String.join("\n", "#Вставка", "	Наше();", "#КонецВставки");
        assertEquals(0, ControlledFragment.controlledPartOf(onlyInsertions).size());
        assertNull("an extension that only adds code controls none of the base, and reporting a "
            + "drift there would be a finding about nothing",
            ControlledFragment.describeDrift(onlyInsertions, BASE));
    }

    @Test
    public void nothingAtAllIsHandledRatherThanThrown()
    {
        assertEquals(0, ControlledFragment.controlledPartOf(null).size());
        assertNull(ControlledFragment.describeDrift(null, BASE));
    }
}
