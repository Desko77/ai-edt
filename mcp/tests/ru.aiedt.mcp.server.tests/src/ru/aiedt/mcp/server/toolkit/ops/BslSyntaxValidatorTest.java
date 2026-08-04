/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.ops.BslSyntaxValidator.CheckResult;

/**
 * Unit tests for {@link BslSyntaxValidator}. The checker is a pure, stateless balance guard, so it can
 * be exercised exhaustively without any Eclipse runtime. These tests cover every opener/closer pair
 * in English and Russian, nesting, comment and string-literal stripping, case insensitivity, and the
 * three error families (unclosed, unexpected, mismatched).
 */
public class BslSyntaxValidatorTest
{
    private static CheckResult scan(String... lines)
    {
        return BslSyntaxValidator.check(Arrays.asList(lines));
    }

    private static void assertBalanced(String... lines)
    {
        CheckResult outcome = scan(lines);
        assertTrue("expected a balanced module but got " + outcome.getErrors(), outcome.isValid());
        assertTrue(outcome.getErrors().isEmpty());
    }

    private static void assertBroken(String... lines)
    {
        CheckResult outcome = scan(lines);
        assertFalse(outcome.isValid());
        assertFalse(outcome.getErrors().isEmpty());
    }

    // ---------- balanced modules ----------

    @Test
    public void emptyModuleBalances()
    {
        CheckResult outcome = BslSyntaxValidator.check(Collections.emptyList());
        assertTrue(outcome.isValid());
        assertTrue(outcome.getErrors().isEmpty());
    }

    @Test
    public void englishProcedureWithBodyBalances()
    {
        assertBalanced("Procedure Run()", "    x = 1;", "EndProcedure");
    }

    @Test
    public void russianProcedureWithBodyBalances()
    {
        assertBalanced("Процедура Выполнить()",
            "    з = 1;", "КонецПроцедуры");
    }

    @Test
    public void englishFunctionBalances()
    {
        assertBalanced("Function Answer()", "    Return 42;", "EndFunction");
    }

    @Test
    public void russianFunctionBalances()
    {
        assertBalanced("Функция Ответ()",
            "    Возврат 42;", "КонецФункции");
    }

    @Test
    public void englishIfElsIfElseEndIfBalances()
    {
        assertBalanced("If n > 0 Then", "    a = 1;", "ElsIf n = 0 Then", "    a = 2;", "Else",
            "    a = 3;", "EndIf;");
    }

    @Test
    public void russianIfElseIfEndIfBalances()
    {
        assertBalanced("Если n > 0 Тогда", "    a = 1;",
            "ИначеЕсли n = 0 Тогда", "    a = 2;",
            "КонецЕсли;");
    }

    @Test
    public void whileLoopBalances()
    {
        assertBalanced("While n < 10 Do", "    n = n + 1;", "EndDo;");
    }

    @Test
    public void russianWhileLoopBalances()
    {
        assertBalanced("Пока n < 10 Цикл", "    n = n + 1;",
            "КонецЦикла;");
    }

    @Test
    public void counterForLoopBalances()
    {
        assertBalanced("For idx = 1 To 5 Do", "    sum = sum + i;", "EndDo;");
    }

    @Test
    public void englishForEachLoopBalances()
    {
        assertBalanced("For Each row In table Do", "    Handle(row);", "EndDo;");
    }

    @Test
    public void russianForEachLoopBalances()
    {
        assertBalanced("Для Каждого строка Из таблица Цикл",
            "    Обработать(строка);",
            "КонецЦикла;");
    }

    @Test
    public void tryExceptEndTryBalances()
    {
        assertBalanced("Try", "    Risky();", "Except", "    Recover();", "EndTry;");
    }

    @Test
    public void russianTryExceptEndTryBalances()
    {
        assertBalanced("Попытка", "    Риск();",
            "Исключение", "    Восстанов();",
            "КонецПопытки;");
    }

    @Test
    public void deeplyNestedBlocksBalance()
    {
        assertBalanced("Procedure Main()", "    If cond Then", "        For idx = 1 To 5 Do",
            "            Try", "                Work();", "            Except", "                Trace();",
            "            EndTry;", "        EndDo;", "    EndIf;", "EndProcedure");
    }

    @Test
    public void severalProceduresInSequenceBalance()
    {
        assertBalanced("Procedure First()", "    a = 1;", "EndProcedure", "",
            "Function Second()", "    Return 2;", "EndFunction");
    }

    // ---------- comments, strings, blank lines ----------

    @Test
    public void wholeLineCommentsAreIgnored()
    {
        assertBalanced("// Procedure Decoy()", "Procedure Real()", "    // EndProcedure",
            "    a = 1;", "EndProcedure");
    }

    @Test
    public void multilineStringContinuationLinesAreIgnored()
    {
        assertBalanced("Procedure WithString()", "    text = \"opening",
            "    |Procedure Decoy()", "    |EndProcedure\";", "EndProcedure");
    }

    @Test
    public void trailingCommentsAfterCodeAreStripped()
    {
        assertBalanced("Procedure WithRemark() // remark", "    a = 1; // EndProcedure here",
            "EndProcedure");
    }

    @Test
    public void blankAndWhitespaceLinesAreIgnored()
    {
        assertBalanced("", "Procedure With()", "", "    ", "EndProcedure", "");
    }

    @Test
    public void moduleWithOnlyCommentsBalances()
    {
        CheckResult outcome = BslSyntaxValidator.check(
            Arrays.asList("// one", "// two", "   // three"));
        assertTrue(outcome.isValid());
    }

    @Test
    public void looseStatementsWithNoBlocksBalance()
    {
        assertBalanced("a = 1;", "b = 2;", "c = a + b;");
    }

    @Test
    public void indentedOpenersAreRecognised()
    {
        assertBalanced("    Procedure Indented()", "        a = 1;", "    EndProcedure");
    }

    // ---------- case insensitivity ----------

    @Test
    public void upperCaseKeywordsAreAccepted()
    {
        assertBalanced("PROCEDURE Upper()", "    IF x THEN", "    ENDIF;", "ENDPROCEDURE");
    }

    // ---------- ElsIf must not open a fresh If ----------

    @Test
    public void manyElsIfBranchesDoNotStackUp()
    {
        assertBalanced("If a Then", "    x = 1;", "ElsIf b Then", "    x = 2;", "ElsIf c Then",
            "    x = 3;", "Else", "    x = 4;", "EndIf;");
    }

    @Test
    public void elseifSpellingIsAccepted()
    {
        assertBalanced("If a Then", "    x = 1;", "ElseIf b Then", "    x = 2;", "EndIf;");
    }

    @Test
    public void russianElseIfBranchesDoNotStackUp()
    {
        assertBalanced("Если a Тогда", "    x = 1;",
            "ИначеЕсли b Тогда", "    x = 2;",
            "ИначеЕсли c Тогда", "    x = 3;",
            "КонецЕсли;");
    }

    @Test
    public void realisticModuleWithMixedBlocksBalances()
    {
        List<String> lines = Arrays.asList("Procedure Handle(Data)", "    If Rows = Undefined Then",
            "        Return;", "    EndIf;", "", "    For Each Row In Rows Do", "        Try",
            "            If Item.Ok() Then", "                While Item.Next() Do",
            "                    Item.Do();", "                EndDo;", "            ElsIf Item.Retry() Then",
            "                Item.TryAgain();", "            Else", "                Row.Skip();",
            "            EndIf;", "        Except", "            Log(ErrorDescription());",
            "        EndTry;", "    EndDo;", "EndProcedure", "", "Function Twice(Value)",
            "    If Amount > 0 Then", "        Return Amount * 3;", "    EndIf;", "    Return 0;",
            "EndFunction");
        CheckResult outcome = BslSyntaxValidator.check(lines);
        assertTrue(outcome.isValid());
    }

    // ---------- unclosed openers ----------

    @Test
    public void procedureLeftOpenIsFlagged()
    {
        CheckResult outcome = scan("Procedure Dangling()", "    a = 1;");
        assertFalse(outcome.isValid());
        assertEquals(1, outcome.getErrors().size());
        assertTrue("names the block that stayed open", //$NON-NLS-1$
            outcome.getErrors().get(0).contains("Procedure"));
        assertTrue("points at the line it was opened on", //$NON-NLS-1$
            outcome.getErrors().get(0).contains("line 1"));
    }

    @Test
    public void functionLeftOpenIsFlagged()
    {
        CheckResult outcome = scan("Function Dangling()", "    Return 1;");
        assertFalse(outcome.isValid());
        assertEquals(1, outcome.getErrors().size());
        assertTrue("names the block that stayed open", //$NON-NLS-1$
            outcome.getErrors().get(0).contains("Function"));
    }

    @Test
    public void ifLeftOpenInsideProcedureIsFlagged()
    {
        assertBroken("Procedure Wrap()", "    If x Then", "        a = 1;", "EndProcedure");
    }

    @Test
    public void whileLeftOpenInsideProcedureIsFlagged()
    {
        assertBroken("Procedure Wrap()", "    While x Do", "        counter = counter - 1;", "EndProcedure");
    }

    @Test
    public void tryLeftOpenInsideProcedureIsFlagged()
    {
        assertBroken("Procedure Wrap()", "    Try", "        DoWork();", "EndProcedure");
    }

    @Test
    public void severalUnclosedBlocksProduceSeveralErrors()
    {
        CheckResult outcome = scan("Procedure First()", "    If x Then", "EndProcedure", "",
            "Procedure Second()");
        assertFalse(outcome.isValid());
        assertTrue(outcome.getErrors().size() >= 2);
    }

    // ---------- stray closers on an empty stack ----------

    @Test
    public void loneEndProcedureIsUnexpected()
    {
        CheckResult outcome = scan("EndProcedure");
        assertFalse(outcome.isValid());
        assertEquals(1, outcome.getErrors().size());
        assertTrue("names the stray closer", //$NON-NLS-1$
            outcome.getErrors().get(0).contains("EndProcedure"));
        assertTrue("points at the line carrying it", //$NON-NLS-1$
            outcome.getErrors().get(0).contains("line 1"));
    }

    @Test
    public void loneEndFunctionIsUnexpected()
    {
        CheckResult outcome = scan("EndFunction");
        assertFalse(outcome.isValid());
        assertTrue(outcome.getErrors().get(0).contains("EndFunction"));
    }

    @Test
    public void loneEndDoIsUnexpected()
    {
        CheckResult outcome = scan("EndDo;");
        assertFalse(outcome.isValid());
        assertEquals(1, outcome.getErrors().size());
        assertTrue(outcome.getErrors().get(0).contains("EndDo"));
    }

    @Test
    public void loneEndTryIsUnexpected()
    {
        CheckResult outcome = scan("EndTry;");
        assertFalse(outcome.isValid());
        assertTrue(outcome.getErrors().get(0).contains("EndTry"));
    }

    @Test
    public void loneEndIfIsUnexpected()
    {
        CheckResult outcome = scan("EndIf;");
        assertFalse(outcome.isValid());
        assertTrue(outcome.getErrors().get(0).contains("EndIf"));
    }

    // ---------- mismatched closers ----------

    @Test
    public void endFunctionClosingAProcedureIsMismatched()
    {
        CheckResult outcome = scan("Procedure Wrap()", "    a = 1;", "EndFunction");
        assertFalse(outcome.isValid());
        assertEquals(1, outcome.getErrors().size());
        assertTrue("names the closer that was written", //$NON-NLS-1$
            outcome.getErrors().get(0).contains("EndFunction"));
        assertTrue("names the block actually open", //$NON-NLS-1$
            outcome.getErrors().get(0).contains("Procedure"));
    }

    @Test
    public void endProcedureClosingAFunctionIsMismatched()
    {
        CheckResult outcome = scan("Function Wrap()", "    Return 1;", "EndProcedure");
        assertFalse(outcome.isValid());
        assertTrue(outcome.getErrors().get(0).contains("EndProcedure"));
        assertTrue(outcome.getErrors().get(0).contains("Function"));
    }

    @Test
    public void endDoClosingAnIfIsMismatched()
    {
        assertBroken("Procedure Wrap()", "    If x Then", "        a = 1;", "    EndDo;",
            "EndProcedure");
    }
}
