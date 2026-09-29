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

    /**
     * A literal the delivery re-cased is a drift.
     * <p>
     * A literal is data: "Готово" and "готово" are different code, and the platform refuses the
     * extension over the difference.
     * </p>
     */
    @Test
    public void aRecasedStringLiteralIsADriftNotAMatch()
    {
        // A literal is data: the delivery re-casing "Готово" to "готово" changes what the code
        // writes, and the platform refuses the extension for it. The comparison lowercased the
        // whole line, and this read as a match.
        String handler = String.join("\n",
            "	Начало();",
            "	Сообщить(\"Готово\");",
            "	Конец();");
        String recased = String.join("\n",
            "	Начало();",
            "	Сообщить(\"готово\");",
            "	Конец();");

        assertNotNull("a re-cased literal is a change in the controlled code, not formatting: "
            + handler, ControlledFragment.describeDrift(handler, recased));
    }

    /**
     * A comment the delivery re-cased is a drift.
     * <p>
     * A comment is part of the controlled code like any other line, and the platform compares it as
     * written.
     * </p>
     */
    @Test
    public void aRecasedCommentIsADriftNotAMatch()
    {
        String handler = String.join("\n",
            "	Начало();",
            "	// Права уже проверены",
            "	Конец();");
        String recased = String.join("\n",
            "	Начало();",
            "	// права уже проверены",
            "	Конец();");

        assertNotNull("a comment is text the delivery changed, and the platform compares it: ",
            ControlledFragment.describeDrift(handler, recased));
    }

    /**
     * Guards that a {@code //} inside a literal does not open a comment.
     * <p>
     * A guard rather than a regression test: the answer holds for a comparison that ignores
     * literals as well. It fails when a change reads a literal's text as code, because then the code
     * after the closing quote - a re-cased identifier included - comes back as drift.
     * </p>
     */
    @Test
    public void aSlashSlashInsideALiteralDoesNotStartAComment()
    {
        // The // in "http://x" is inside a literal, not a comment opener. A parser that opened a
        // comment there would keep the rest of the line as written - including the real code
        // after the closing quote - and report a re-cased identifier as drift.
        String handler = String.join("\n",
            "	Сообщить(\"http://x\"); Возврат;",
            "	Конец();");
        String recased = String.join("\n",
            "	СООБЩИТЬ(\"http://x\"); возврат;",
            "	Конец();");

        assertNull("identifier case is still ignored when a literal carries //: " + handler,
            ControlledFragment.describeDrift(handler, recased));
    }

    /**
     * A doubled quote inside a literal is an escaped quote, not the end of the literal.
     * <p>
     * A comparison that closed the literal at the first quote of the pair would go on lowering the
     * case of what the literal holds, and a re-cased word there would pass as a match.
     * </p>
     */
    @Test
    public void escapedQuotesKeepTheLiteralIntact()
    {
        // "" inside a literal is an escaped quote, not the end of it. A parser that closed the
        // string at the first quote of the pair would go on lowercasing literal text.
        String handler = String.join("\n",
            "	Начало();",
            "	Сообщить(\"Контрагент \"\"Ромашка\"\" готов\");",
            "	Конец();");
        String same = String.join("\n",
            "	Начало();",
            "	Сообщить(\"Контрагент \"\"Ромашка\"\" готов\");",
            "	Конец();");
        String recased = String.join("\n",
            "	Начало();",
            "	Сообщить(\"Контрагент \"\"Ромашка\"\" ГОТОВ\");",
            "	Конец();");

        assertNull(ControlledFragment.describeDrift(handler, same));
        assertNotNull("a re-cased word inside an escaped literal is still inside the literal: ",
            ControlledFragment.describeDrift(handler, recased));
    }

    /**
     * A clean answer carries what the comparison did not compare.
     * <p>
     * A match is a statement about this comparison and not a promise that the platform will accept
     * the extension, and the note beside the answer is where a caller reads that.
     * </p>
     */
    @Test
    public void theCaveatTravelsWithACleanAnswer()
    {
        // A clean answer never travels bare: it says what the comparison normalised, because a
        // reader who takes true as "the platform will accept it" has been promised something
        // this check never made.
        String caveat = ControlledFragment.matchCaveat();
        assertTrue(caveat, caveat.contains("compared as written"));
        assertTrue(caveat, caveat.contains("promise"));
    }

    /**
     * A continuation line of a multi-line literal is literal data, and re-casing it is a drift.
     * <p>
     * A query text is the commonest multi-line literal in BSL, and the platform compares each of
     * its lines as written.
     * </p>
     */
    @Test
    public void aRecasedContinuationLineOfALiteralIsADriftNotAMatch()
    {
        // A query text is the commonest multi-line literal in BSL, and every continuation line
        // of it is literal data the platform compares as written. A comparison that processed
        // such a line as code lowered its case on both sides and called a re-cased delivery a
        // match - a clean answer where the platform refuses the extension.
        String handler = String.join("\n",
            "	Текст = \"ВЫБРАТЬ",
            "	|Товар КАК Товар",
            "	|ИЗ Справочник.Товары\";",
            "	Записать();");
        String recased = String.join("\n",
            "	Текст = \"ВЫБРАТЬ",
            "	|ТОВАР КАК ТОВАР",
            "	|ИЗ Справочник.Товары\";",
            "	Записать();");

        assertNotNull("a continuation line is literal data, and re-casing it is a change: ",
            ControlledFragment.describeDrift(handler, recased));
    }

    /**
     * Guards that code after the closing quote of a continuation line is compared as code.
     * <p>
     * A guard rather than a regression test: the answer holds for a comparison that never reads a
     * continuation line as literal data as well. It fails when a change carries the literal state
     * past the closing quote, because then the identifiers after it turn case-sensitive.
     * </p>
     */
    @Test
    public void codeAfterTheClosingQuoteOfAContinuationLineIsStillCode()
    {
        // The closing quote on a continuation line ends the literal; what follows it - and every
        // later line - is code again, and its identifiers compare case-blind as they always did.
        String handler = String.join("\n",
            "	Текст = \"ВЫБРАТЬ",
            "	|Товар КАК Товар",
            "	|ИЗ Справочник.Товары\";",
            "	Записать();");
        String recasedCode = String.join("\n",
            "	ТЕКСТ = \"ВЫБРАТЬ",
            "	|Товар КАК Товар",
            "	|ИЗ Справочник.Товары\";",
            "	ЗАПИСАТЬ();");

        assertNull("identifier case outside the literal is still not a change: " + handler,
            ControlledFragment.describeDrift(handler, recasedCode));
    }

    /**
     * A doubled quote on a continuation line does not close the literal.
     * <p>
     * A comparison that closed the literal there would treat the rest of the line as code, lower
     * its case, and miss a re-cased word inside the literal.
     * </p>
     */
    @Test
    public void anEscapedQuoteOnAContinuationLineDoesNotCloseTheLiteral()
    {
        // "" on a continuation line is an escaped quote like anywhere else in a literal. A parser
        // that closed the literal at the first quote of the pair would treat the rest of the line
        // as code, lower its case, and miss a re-cased word the delivery changed.
        String handler = String.join("\n",
            "	Текст = \"ВЫБРАТЬ",
            "	|Товар КАК \"\"Ромашка\"\" Готов",
            "	|ИЗ Справочник.Товары\";",
            "	Записать();");
        String recased = String.join("\n",
            "	Текст = \"ВЫБРАТЬ",
            "	|Товар КАК \"\"Ромашка\"\" ГОТОВ",
            "	|ИЗ Справочник.Товары\";",
            "	Записать();");

        assertNotNull("the word after the escaped quotes is still inside the literal: ",
            ControlledFragment.describeDrift(handler, recased));
    }
}
