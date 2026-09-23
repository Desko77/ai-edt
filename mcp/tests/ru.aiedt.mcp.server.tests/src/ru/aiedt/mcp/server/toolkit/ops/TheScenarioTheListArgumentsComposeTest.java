/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.Assume;
import org.junit.Test;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The scenario the list arguments compose: open the list, go to the row, press the button, wait
 * for the window, capture it with a tag.
 * <p>
 * Every step is Vanessa's own wording, and each argument picks its part of that wording. The tests
 * here hold each argument to the three duties the specification gives it: the value reaches the
 * step when it is given, the call is refused when it cannot be composed, and the default is the
 * documented one when it is left out.
 * </p>
 */
public class TheScenarioTheListArgumentsComposeTest
{
    private final String[] refusal = new String[1];

    /**
     * A call complete enough to compose a scenario: every required argument given, nothing exotic.
     *
     * @return the call's arguments
     */
    private static Map<String, String> given()
    {
        Map<String, String> p = new HashMap<>();
        p.put("listKind", "catalog"); //$NON-NLS-1$ //$NON-NLS-2$
        p.put("listName", "Товары"); //$NON-NLS-1$ //$NON-NLS-2$
        p.put("column", "Наименование"); //$NON-NLS-1$ //$NON-NLS-2$
        p.put("columnValue", "Стол письменный"); //$NON-NLS-1$ //$NON-NLS-2$
        p.put("buttonTitle", "Печать"); //$NON-NLS-1$ //$NON-NLS-2$
        return p;
    }

    /**
     * Reads the given call, failing the test when it is refused.
     *
     * @param p the call's arguments.
     * @return the settled arguments
     */
    private VanessaTool.ListActionArgs accepted(Map<String, String> p)
    {
        refusal[0] = null;
        VanessaTool.ListActionArgs args = VanessaTool.ListActionArgs.read(p, refusal);
        assertNull("refused though it should compose: " + refusal[0], refusal[0]); //$NON-NLS-1$
        assertNotNull(args);
        return args;
    }

    /**
     * Reads the given call, asserting it is refused.
     *
     * @param p the call's arguments.
     * @return the refusal
     */
    private String refused(Map<String, String> p)
    {
        refusal[0] = null;
        VanessaTool.ListActionArgs.read(p, refusal);
        assertNotNull("accepted though it should be refused", refusal[0]); //$NON-NLS-1$
        return refusal[0];
    }

    /**
     * The kind picks the opening step's wording: each kind opens by the words of Vanessa's step
     * library, an unknown one is refused by name, and a call with no kind at all has nothing to
     * open.
     * <p>
     * The phrases are written out here, from the library's own lines. Comparing the scenario to
     * the map that produces it would stay green however those lines were spelled.
     * </p>
     */
    @Test
    public void theKindPicksTheOpeningStep()
    {
        String[][] kinds = {
            {"catalog", "Я открываю основную форму списка справочника \"Товары\""}, //$NON-NLS-1$ //$NON-NLS-2$
            {"document", "Я открываю основную форму списка документа \"Товары\""}, //$NON-NLS-1$ //$NON-NLS-2$
            {"documentJournal", "Я открываю основную форму журнала документов \"Товары\""}, //$NON-NLS-1$ //$NON-NLS-2$
            {"chartOfCharacteristicTypes", //$NON-NLS-1$
                "Я открываю основную форму списка плана видов характеристик \"Товары\""}, //$NON-NLS-1$
            {"chartOfAccounts", "Я открываю основную форму списка плана счетов \"Товары\""}, //$NON-NLS-1$ //$NON-NLS-2$
            {"chartOfCalculationTypes", //$NON-NLS-1$
                "Я открываю основную форму списка плана видов расчета \"Товары\""}, //$NON-NLS-1$
            {"informationRegister", //$NON-NLS-1$
                "Я открываю основную форму списка регистра сведений \"Товары\""}, //$NON-NLS-1$
            {"accumulationRegister", //$NON-NLS-1$
                "Я открываю основную форму списка регистра накопления \"Товары\""}, //$NON-NLS-1$
            {"accountingRegister", //$NON-NLS-1$
                "Я открываю основную форму списка регистра бухгалтерии \"Товары\""}, //$NON-NLS-1$
            {"calculationRegister", //$NON-NLS-1$
                "Я открываю основную форму списка регистра расчета \"Товары\""}, //$NON-NLS-1$
        };
        for (String[] kind : kinds)
        {
            Map<String, String> p = given();
            p.put("listKind", kind[0]); //$NON-NLS-1$
            String scenario = accepted(p).scenario(null);
            assertTrue(kind[0] + " opens by the library's own words: " + scenario, //$NON-NLS-1$
                scenario.contains(kind[1]));
        }
        Map<String, String> unknown = given();
        unknown.put("listKind", "crime"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(unknown).contains("crime")); //$NON-NLS-1$
        Map<String, String> missing = given();
        missing.remove("listKind"); //$NON-NLS-1$
        assertTrue(refused(missing).contains("listKind")); //$NON-NLS-1$
    }

    /**
     * The name stands inside the step's own quotes; a quote of its own or a line break composes a
     * scenario that means something else, and an empty name opens nothing.
     */
    @Test
    public void theNameGoesIntoTheStepInQuotes()
    {
        String scenario = accepted(given()).scenario(null);
        assertTrue("the name stands in the step's quotes: " + scenario, //$NON-NLS-1$
            scenario.contains("справочника \"Товары\"")); //$NON-NLS-1$

        Map<String, String> quoted = given();
        quoted.put("listName", "Спра\"вочник"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(quoted).contains("listName")); //$NON-NLS-1$
        Map<String, String> lined = given();
        lined.put("listName", "Справочник\nТовары"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(lined).contains("listName")); //$NON-NLS-1$
        Map<String, String> empty = given();
        empty.put("listName", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(empty).contains("listName")); //$NON-NLS-1$
        Map<String, String> missing = given();
        missing.remove("listName"); //$NON-NLS-1$
        assertTrue(refused(missing).contains("listName")); //$NON-NLS-1$
    }

    /**
     * The table is named in the step that counts the rows and the step that moves to one; an empty
     * name addresses nothing, and a call that names no table gets the usual Список.
     */
    @Test
    public void theTableIsNamedInTheCountingAndTheMove()
    {
        Map<String, String> p = given();
        p.put("tableName", "СписокДокументов"); //$NON-NLS-1$ //$NON-NLS-2$
        String scenario = accepted(p).scenario(null);
        assertTrue("the counting step names the table: " + scenario, //$NON-NLS-1$
            scenario.contains("в таблице \"СписокДокументов\" 1 строк")); //$NON-NLS-1$
        assertTrue("the move names it too", //$NON-NLS-1$
            scenario.contains("в таблице \"СписокДокументов\" я перехожу к строке")); //$NON-NLS-1$

        Map<String, String> empty = given();
        empty.put("tableName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(empty).contains("tableName")); //$NON-NLS-1$

        assertTrue("no table named means the usual one: " + accepted(given()).scenario(null), //$NON-NLS-1$
            accepted(given()).scenario(null).contains("в таблице \"Список\"")); //$NON-NLS-1$
    }

    /**
     * The column stands in the Gherkin table's header row and in the counting step; an empty or
     * missing column leaves the row unnamed.
     */
    @Test
    public void theColumnStandsInTheTableAndTheCount()
    {
        String scenario = accepted(given()).scenario(null);
        assertTrue("the Gherkin row carries the column: " + scenario, //$NON-NLS-1$
            scenario.contains("| 'Наименование' |")); //$NON-NLS-1$
        assertTrue("the count names it too", //$NON-NLS-1$
            scenario.contains("колонка \"Наименование\"")); //$NON-NLS-1$

        Map<String, String> empty = given();
        empty.put("column", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(empty).contains("column")); //$NON-NLS-1$
        Map<String, String> missing = given();
        missing.remove("column"); //$NON-NLS-1$
        assertTrue(refused(missing).contains("column")); //$NON-NLS-1$
    }

    /**
     * The value stands in both rows of the Gherkin table; a quote breaks the step, a missing value
     * is refused, and an empty string is a value that is looked for as empty.
     */
    @Test
    public void theValueIsLookedForAsGiven()
    {
        String scenario = accepted(given()).scenario(null);
        assertTrue("the Gherkin row carries the value: " + scenario, //$NON-NLS-1$
            scenario.contains("| 'Стол письменный' |")); //$NON-NLS-1$
        assertTrue("the count carries it too", //$NON-NLS-1$
            scenario.contains("\"Равно\" \"Стол письменный\"")); //$NON-NLS-1$

        Map<String, String> quoted = given();
        quoted.put("columnValue", "Стол \"обычный\""); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(quoted).contains("columnValue")); //$NON-NLS-1$
        Map<String, String> singleQuoted = given();
        singleQuoted.put("columnValue", "Стол 'обычный'"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(singleQuoted).contains("columnValue")); //$NON-NLS-1$

        Map<String, String> missing = given();
        missing.remove("columnValue"); //$NON-NLS-1$
        assertTrue(refused(missing).contains("columnValue")); //$NON-NLS-1$

        Map<String, String> empty = given();
        empty.put("columnValue", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String withEmpty = accepted(empty).scenario(null);
        assertTrue("an empty value is a value, not an absence: " + withEmpty, //$NON-NLS-1$
            withEmpty.contains("\"Равно\" \"\"")); //$NON-NLS-1$
        assertTrue(withEmpty.contains("| '' |")); //$NON-NLS-1$
    }

    /**
     * Exactly one row is demanded before the move unless first is asked for; any other word is
     * refused, and the default is the demand.
     */
    @Test
    public void oneRowIsDemandedBeforeTheMoveUnlessFirstIsAsked()
    {
        String unique = accepted(given()).scenario(null);
        assertTrue("unique demands exactly one row first: " + unique, //$NON-NLS-1$
            unique.contains("\" 1 строк, у которых")); //$NON-NLS-1$
        assertTrue("and only then moves", //$NON-NLS-1$
            unique.indexOf(" 1 строк") < unique.indexOf("я перехожу к строке")); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> first = given();
        first.put("whenSeveral", "first"); //$NON-NLS-1$ //$NON-NLS-2$
        String moving = accepted(first).scenario(null);
        assertTrue("first moves without counting: " + moving, //$NON-NLS-1$
            !moving.contains("1 строк")); //$NON-NLS-1$

        Map<String, String> other = given();
        other.put("whenSeveral", "any"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(other).contains("whenSeveral")); //$NON-NLS-1$
    }

    /**
     * The button is pressed by title or by name and not both: both given is refused, neither given
     * is refused, and the one given picks the step - a title inside double quotes, a name inside
     * single ones.
     */
    @Test
    public void theButtonIsPressedByTitleOrByNameAndNotBoth()
    {
        String byTitle = accepted(given()).scenario(null);
        assertTrue("a title is pressed inside double quotes: " + byTitle, //$NON-NLS-1$
            byTitle.contains("И я нажимаю на кнопку \"Печать\"")); //$NON-NLS-1$

        Map<String, String> byName = given();
        byName.remove("buttonTitle"); //$NON-NLS-1$
        byName.put("buttonName", "ФормаОбщаяПечать"); //$NON-NLS-1$ //$NON-NLS-2$
        String named = accepted(byName).scenario(null);
        assertTrue("a name is pressed inside single quotes: " + named, //$NON-NLS-1$
            named.contains("И я нажимаю на кнопку с именем 'ФормаОбщаяПечать'")); //$NON-NLS-1$

        Map<String, String> both = given();
        both.put("buttonName", "ФормаОбщаяПечать"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(both).contains("buttonTitle")); //$NON-NLS-1$

        Map<String, String> neither = given();
        neither.remove("buttonTitle"); //$NON-NLS-1$
        assertTrue(refused(neither).contains("buttonTitle")); //$NON-NLS-1$

        Map<String, String> quoted = given();
        quoted.put("buttonTitle", "Печать \"быстрая\""); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(quoted).contains("buttonTitle")); //$NON-NLS-1$
    }

    /**
     * The window is waited for by name or by change: a named title goes into the waiting step with
     * the seconds, an omitted one waits for a window differing from the remembered title, and a
     * title carrying a quote breaks the step.
     */
    @Test
    public void theWindowIsWaitedForByNameOrByChange()
    {
        Map<String, String> p = given();
        p.put("windowTitle", "Печатная форма"); //$NON-NLS-1$ //$NON-NLS-2$
        p.put("windowWaitSeconds", "20"); //$NON-NLS-1$ //$NON-NLS-2$
        String named = accepted(p).scenario(null);
        assertTrue("the waiting step carries the title and the seconds: " + named, //$NON-NLS-1$
            named.contains("И я жду открытия окна \"Печатная форма\" в течение 20 секунд")); //$NON-NLS-1$
        assertTrue("a named window needs no remembered one", //$NON-NLS-1$
            !named.contains("запоминаю заголовок")); //$NON-NLS-1$

        String unnamed = accepted(given()).scenario(null);
        assertTrue("an unnamed window is waited for by change: " + unnamed, //$NON-NLS-1$
            unnamed.contains("И я жду открытия окна отличного от \"$ОкноДо$\" в течение 10 секунд")); //$NON-NLS-1$
        assertTrue("the remembered title stands before that step", //$NON-NLS-1$
            unnamed.indexOf("И я запоминаю заголовок текущего окна как \"ОкноДо\"") >= 0 //$NON-NLS-1$
                && unnamed.indexOf("запоминаю заголовок") < unnamed.indexOf("отличного от")); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> quoted = given();
        quoted.put("windowTitle", "Окно \"это\""); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(quoted).contains("windowTitle")); //$NON-NLS-1$
        Map<String, String> lined = given();
        lined.put("windowTitle", "Окно\nдва"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(lined).contains("windowTitle")); //$NON-NLS-1$
    }

    /**
     * The wait goes into the step's text and into the document's asynchronous-step ceiling, and is
     * bounded below: not a whole number or less than a second is refused; left out it is ten
     * seconds. A wait above the run's own timeout is judged later, once that timeout has been
     * clamped.
     */
    @Test
    public void theWaitGoesIntoTheStepAndTheDocument()
    {
        Map<String, String> p = given();
        p.put("windowWaitSeconds", "25"); //$NON-NLS-1$ //$NON-NLS-2$
        VanessaTool.ListActionArgs args = accepted(p);
        assertTrue(args.scenario(null).contains("в течение 25 секунд")); //$NON-NLS-1$
        assertEquals(25, JsonParser.parseString(VanessaTool.buildVaParams(
            new java.io.File("C:/run/one.feature"), new java.io.File("C:/run/junit.xml"), //$NON-NLS-1$ //$NON-NLS-2$
            new java.io.File("C:/run/shots"), true, false, "File=\"C:/bases/demo\";", 48123, 300, //$NON-NLS-1$ //$NON-NLS-2$
            true, null, args.ourKeys())).getAsJsonObject()
            .get("ТаймаутДляАсинхронныхШагов").getAsInt()); //$NON-NLS-1$

        Map<String, String> notWhole = given();
        notWhole.put("windowWaitSeconds", "half"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(notWhole).contains("windowWaitSeconds")); //$NON-NLS-1$
        Map<String, String> none = given();
        none.put("windowWaitSeconds", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused(none).contains("windowWaitSeconds")); //$NON-NLS-1$

        assertTrue("left out, the wait is ten seconds: " + accepted(given()).scenario(null), //$NON-NLS-1$
            accepted(given()).scenario(null).contains("в течение 10 секунд")); //$NON-NLS-1$
        assertEquals(VanessaTool.DEFAULT_WINDOW_WAIT_SEC, accepted(given()).waitSeconds);
    }

    /**
     * The tag that captures stands on the waiting step, and the scenario keeps the steps' order:
     * open, count, move, press, wait.
     */
    @Test
    public void theCaptureStandsOnTheWaitingStep()
    {
        String scenario = accepted(given()).scenario(null);
        assertTrue("the tag photographs the step that follows it: " + scenario, //$NON-NLS-1$
            scenario.indexOf("@screenshot") < scenario.indexOf("жду открытия окна")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(scenario.indexOf("перехожу к строке") < scenario.indexOf("нажимаю на кнопку")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(scenario.indexOf("нажимаю на кнопку") < scenario.indexOf("жду открытия окна")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a scenario needs a client to work in", //$NON-NLS-1$
            scenario.contains(VanessaTool.START_STEP));
    }

    /**
     * The answer's sought names what was looked for: the arguments as settled, the button as the
     * one value it was given, and the window as its title or as the remembered title it differed
     * from.
     */
    @Test
    public void theSoughtNamesWhatWasLookedFor()
    {
        Map<String, String> p = given();
        p.put("windowTitle", "Печатная форма"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject sought = accepted(p).sought();
        assertEquals("catalog", sought.get("listKind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Товары", sought.get("listName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Список", sought.get("tableName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Наименование", sought.get("column").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Стол письменный", sought.get("columnValue").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Печать", sought.get("button").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Печатная форма", sought.get("windowTitle").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(!sought.has("differentFrom")); //$NON-NLS-1$
        assertEquals(10, sought.get("windowWaitSeconds").getAsInt()); //$NON-NLS-1$

        Map<String, String> byName = given();
        byName.remove("buttonTitle"); //$NON-NLS-1$
        byName.put("buttonName", "ФормаОбщаяПечать"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject unnamed = accepted(byName).sought();
        assertEquals("ФормаОбщаяПечать", unnamed.get("button").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("$ОкноДо$", unnamed.get("differentFrom").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(!unnamed.has("windowTitle")); //$NON-NLS-1$
    }

    /**
     * The capture cannot be switched off on this call: the frame of the opened window is the point
     * of it, and the tag captures only while the run's screenshots are on.
     */
    @Test
    public void theCaptureCannotBeSwitchedOff()
    {
        assertNotNull(VanessaTool.whyTheCaptureCannotBeOff(false));
        assertNull(VanessaTool.whyTheCaptureCannotBeOff(true));
    }

    /**
     * The step that gets a client is one line here as on the formToOpen branch: the scenario is
     * composed on this branch too, and a start step spanning lines breaks it.
     */
    @Test
    public void theStartStepIsOneLineHereToo()
    {
        assertNotNull(VanessaTool.whyStartStepIsNotOneLine("Я подключаюсь\nи еще что-то")); //$NON-NLS-1$
        assertNull(VanessaTool.whyStartStepIsNotOneLine("Я подключаюсь к запущенному клиенту")); //$NON-NLS-1$
        assertNull(VanessaTool.whyStartStepIsNotOneLine(null));
        assertNull(VanessaTool.whyStartStepIsNotOneLine("  ")); //$NON-NLS-1$
    }

    /**
     * The list way is the only way: a file, a text or a form alongside the list arguments says
     * nothing about which of the two was meant, and an openStep names a second way of opening.
     */
    @Test
    public void theListWayIsTheOnlyWay()
    {
        assertNotNull(VanessaTool.whyTheListWayIsNotTheOnlyOne(true, false, false, null));
        assertNotNull(VanessaTool.whyTheListWayIsNotTheOnlyOne(false, true, false, null));
        assertNotNull(VanessaTool.whyTheListWayIsNotTheOnlyOne(false, false, true, null));
        assertNotNull(VanessaTool.whyTheListWayIsNotTheOnlyOne(false, false, false,
            "Я открываю форму {form}")); //$NON-NLS-1$
        assertNull(VanessaTool.whyTheListWayIsNotTheOnlyOne(false, false, false, null));
        assertNull(VanessaTool.whyTheListWayIsNotTheOnlyOne(false, false, false, "  ")); //$NON-NLS-1$
    }

    /**
     * The keys this branch sets are barred from the passthrough, under the Russian names the
     * document carries and under the English names Vanessa's name table gives the same three: a
     * caller raising the asynchronous-step ceiling above the seconds they named, or turning the
     * capture machinery off, would be answered with a run that did not happen the way it says.
     */
    @Test
    public void theThreeKeysAreRefusedInThePassthrough()
    {
        for (String key : new String[] {"ИспользоватьКомпонентуVanessaExt", //$NON-NLS-1$
            "ИспользоватьВнешнююКомпонентуДляСкриншотов", "ТаймаутДляАсинхронныхШагов", //$NON-NLS-1$ //$NON-NLS-2$
            // The name table's English names of those three. A different case is the same name:
            // Vanessa folds it before it reads.
            "useaddin", "useaddinforscreencapture", "TimeoutForAsynchronousSteps"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            String[] refusalOfPassthrough = new String[1];
            VanessaTool.extraParams("{\"" + key + "\": true}", refusalOfPassthrough); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull(key + " is ours to set on this branch", refusalOfPassthrough[0]); //$NON-NLS-1$
        }
    }

    /**
     * The document carries the three keys on this branch and only the screenshot pair on the
     * formToOpen branch - and neither anywhere else.
     */
    @Test
    public void theDocumentCarriesTheKeysOfItsOwnBranch()
    {
        java.io.File feature = new java.io.File("C:/run/one.feature"); //$NON-NLS-1$
        java.io.File junit = new java.io.File("C:/run/junit.xml"); //$NON-NLS-1$
        java.io.File shots = new java.io.File("C:/run/shots"); //$NON-NLS-1$
        String connection = "File=\"C:/bases/demo\";"; //$NON-NLS-1$

        JsonObject ofList = JsonParser.parseString(VanessaTool.buildVaParams(feature, junit,
            shots, true, false, connection, 48123, 300, true, null, accepted(given()).ourKeys()))
            .getAsJsonObject();
        assertTrue(ofList.get("ИспользоватьКомпонентуVanessaExt").getAsBoolean()); //$NON-NLS-1$
        assertTrue(ofList.get("ИспользоватьВнешнююКомпонентуДляСкриншотов").getAsBoolean()); //$NON-NLS-1$
        assertEquals(10, ofList.get("ТаймаутДляАсинхронныхШагов").getAsInt()); //$NON-NLS-1$

        JsonObject ofForm = JsonParser.parseString(VanessaTool.buildVaParams(feature, junit,
            shots, true, false, connection, 48123, 300, true, null, VanessaTool.screenshotKeys()))
            .getAsJsonObject();
        assertTrue(ofForm.get("ИспользоватьКомпонентуVanessaExt").getAsBoolean()); //$NON-NLS-1$
        assertTrue(ofForm.get("ИспользоватьВнешнююКомпонентуДляСкриншотов").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the ceiling belongs to the branch whose step carries a wait", //$NON-NLS-1$
            !ofForm.has("ТаймаутДляАсинхронныхШагов")); //$NON-NLS-1$

        JsonObject plain = JsonParser.parseString(VanessaTool.buildVaParams(feature, junit,
            shots, true, false, connection, 48123, 300, true, null, null)).getAsJsonObject();
        assertTrue("a call that composes no scenario of its own sets no capture keys", //$NON-NLS-1$
            !plain.has("ИспользоватьКомпонентуVanessaExt") //$NON-NLS-1$
                && !plain.has("ИспользоватьВнешнююКомпонентуДляСкриншотов") //$NON-NLS-1$
                && !plain.has("ТаймаутДляАсинхронныхШагов")); //$NON-NLS-1$
    }

    /**
     * The window said to be on screen comes from the failing step's own text, and nothing else: a
     * button that was not found names the active window, a window that never opened is named in
     * the waiting step's refusal, and every other failure leaves it empty.
     */
    @Test
    public void theWindowOnScreenComesFromTheFailingStepsOwnText()
    {
        assertEquals("Список документов", VanessaTool.onScreenOf( //$NON-NLS-1$
            "Кнопка/команда с заголовком <Печать> не найдена. ТекущееОкно=Список документов")); //$NON-NLS-1$
        // The library's own sentence: the seconds, the sought title and the window on screen each
        // sit in angle brackets, and the sentence closes with a full stop. The title is what is
        // between the last pair.
        assertEquals("Реализация товаров", VanessaTool.onScreenOf( //$NON-NLS-1$
            "Ожидали в течение <20> секунд, что откроется окно с заголовком <Печатная форма>. " //$NON-NLS-1$
                + "Текущее окно <Реализация товаров>.")); //$NON-NLS-1$
        assertEquals("a sentence that stops at the title still gives the title", "Реализация", //$NON-NLS-1$ //$NON-NLS-2$
            VanessaTool.onScreenOf("Текущее окно <Реализация>.")); //$NON-NLS-1$
        assertEquals("", VanessaTool.onScreenOf( //$NON-NLS-1$
            "В таблице <Список> найдено <3> значений. А ожидали <1>.")); //$NON-NLS-1$
        assertEquals("", VanessaTool.onScreenOf(null)); //$NON-NLS-1$
    }

    /**
     * The scenario for one ordinary call, line for line. A phrase checked with {@code contains}
     * stays green when a step is added, dropped or reordered around it.
     */
    @Test
    public void theWholeScenarioIsThatOneCall()
    {
        String[] lines = accepted(given()).scenario(null).split("\n", -1); //$NON-NLS-1$
        String[] expected = {
            "#language: ru", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Функционал: Снимок после действия", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Контекст:", //$NON-NLS-1$
            "    Дано Я запускаю сценарий открытия TestClient или подключаю уже существующий", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Сценарий: Действие в списке Товары", //$NON-NLS-1$
            "    Когда Я открываю основную форму списка справочника \"Товары\"", //$NON-NLS-1$
            "    И я запоминаю заголовок текущего окна как \"ОкноДо\"", //$NON-NLS-1$
            "    И в таблице \"Список\" 1 строк, у которых колонка \"Наименование\" \"Равно\" " //$NON-NLS-1$
                + "\"Стол письменный\"", //$NON-NLS-1$
            "    И в таблице \"Список\" я перехожу к строке", //$NON-NLS-1$
            "        | 'Наименование' |", //$NON-NLS-1$
            "        | 'Стол письменный' |", //$NON-NLS-1$
            "    И я нажимаю на кнопку \"Печать\"", //$NON-NLS-1$
            "    @screenshot", //$NON-NLS-1$
            "    И я жду открытия окна отличного от \"$ОкноДо$\" в течение 10 секунд", //$NON-NLS-1$
            "    И Я закрываю все окна клиентского приложения", //$NON-NLS-1$
            "", //$NON-NLS-1$
        };
        assertEquals(expected.length, lines.length);
        for (int i = 0; i < expected.length; i++)
        {
            assertEquals("line " + (i + 1), expected[i], lines[i]); //$NON-NLS-1$
        }
    }

    /**
     * A vertical bar in a table cell splits the cell. The column and the value both stand in one,
     * so a bar in either is refused before a scenario is written.
     */
    @Test
    public void aBarInATableCellIsRefused()
    {
        Map<String, String> column = given();
        column.put("column", "Код|Наименование"); //$NON-NLS-1$ //$NON-NLS-2$
        String columnRefusal = refused(column);
        assertTrue(columnRefusal, columnRefusal.contains("column carries")); //$NON-NLS-1$
        Map<String, String> value = given();
        value.put("columnValue", "А|Б"); //$NON-NLS-1$ //$NON-NLS-2$
        String valueRefusal = refused(value);
        assertTrue(valueRefusal, valueRefusal.contains("columnValue")); //$NON-NLS-1$
    }

    /**
     * A call that brings only the list arguments is a named scenario. It is answered through the
     * same entry a client uses, and it gets as far as the scenario: the refusal that follows is
     * the wait judged against the run's timeout after that timeout has been clamped, which is
     * past the point where an unnamed call is turned away.
     *
     * @throws IOException when the stand-in processor files cannot be created
     */
    @Test
    public void aCallWithOnlyTheListArgumentsReachesTheScenario() throws IOException
    {
        assertNull("the list arguments are a way of naming the scenario", //$NON-NLS-1$
            VanessaTool.whyTheScenarioIsNotNamed(false, false, false, true));
        Map<String, String> p = given();
        p.put("connectionString", "File=\"C:/bases/demo\";"); //$NON-NLS-1$ //$NON-NLS-2$
        // 5000 is above the ceiling, so the run's timeout becomes 3600. 4000 is above that and
        // below 5000: a comparison made before the clamp would let the call through.
        p.put("timeoutSeconds", "5000"); //$NON-NLS-1$ //$NON-NLS-2$
        p.put("windowWaitSeconds", "4000"); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = answered(p);
        assertFalse("answered as if nothing named the scenario: " + answer, //$NON-NLS-1$
            answer.contains("featurePath is required")); //$NON-NLS-1$
        assertTrue("the wait is judged against the timeout after it is clamped: " + answer, //$NON-NLS-1$
            answer.contains("windowWaitSeconds is 4000") //$NON-NLS-1$
                && answer.contains("timeoutSeconds of 3600")); //$NON-NLS-1$
    }

    /**
     * The list arguments stay the only way when the call is answered the way a client answers it.
     * A file, a text or a form beside them is still a refusal, and the refusal happens before a
     * client is started.
     *
     * @throws IOException when the stand-in processor files cannot be created
     */
    @Test
    public void theListArgumentsStayAloneWhenTheCallIsAnswered() throws IOException
    {
        String[][] others = {
            {"featurePath", "features/one.feature"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"scenarioText", "Сценарий: что-нибудь"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"formToOpen", "ОбщаяФорма.Печать"}, //$NON-NLS-1$ //$NON-NLS-2$
        };
        for (String[] other : others)
        {
            Map<String, String> p = given();
            p.put("connectionString", "File=\"C:/bases/demo\";"); //$NON-NLS-1$ //$NON-NLS-2$
            // High enough that a missed mix check still stops before a client is started.
            p.put("timeoutSeconds", "30"); //$NON-NLS-1$ //$NON-NLS-2$
            p.put("windowWaitSeconds", "4000"); //$NON-NLS-1$ //$NON-NLS-2$
            p.put(other[0], other[1]);
            String answer = answered(p);
            assertTrue(other[0] + " beside the list arguments is still refused: " + answer, //$NON-NLS-1$
                answer.contains("only one of them")); //$NON-NLS-1$
            assertFalse(other[0] + " reached the launch: " + answer, //$NON-NLS-1$
                answer.contains("junitXmlPath")); //$NON-NLS-1$
        }
    }

    /**
     * The answer {@code execute} gives once the processor and the client are configured as files
     * that exist, so the call gets past the setup check.
     * <p>
     * The files are empty. The calls that use this are refused before anything is launched; the
     * assertion on the answer is what says so.
     * </p>
     *
     * @param params the call's arguments, including a connection string.
     * @return the tool's answer
     * @throws IOException when the stand-in files cannot be created
     */
    private static String answered(Map<String, String> params) throws IOException
    {
        Assume.assumeNotNull(Activator.getDefault());
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        boolean epfWasDefault = store.isDefault(PrefKeys.PREF_VANESSA_EPF);
        boolean exeWasDefault = store.isDefault(PrefKeys.PREF_VANESSA_1C_EXE);
        String epfBefore = store.getString(PrefKeys.PREF_VANESSA_EPF);
        String exeBefore = store.getString(PrefKeys.PREF_VANESSA_1C_EXE);
        File epf = File.createTempFile("aiedt-vanessa", ".epf"); //$NON-NLS-1$ //$NON-NLS-2$
        File exe = File.createTempFile("aiedt-1cv8", ".exe"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            store.setValue(PrefKeys.PREF_VANESSA_EPF, epf.getAbsolutePath());
            store.setValue(PrefKeys.PREF_VANESSA_1C_EXE, exe.getAbsolutePath());
            return new VanessaTool().execute(params);
        }
        finally
        {
            if (epfWasDefault)
            {
                store.setToDefault(PrefKeys.PREF_VANESSA_EPF);
            }
            else
            {
                store.setValue(PrefKeys.PREF_VANESSA_EPF, epfBefore);
            }
            if (exeWasDefault)
            {
                store.setToDefault(PrefKeys.PREF_VANESSA_1C_EXE);
            }
            else
            {
                store.setValue(PrefKeys.PREF_VANESSA_1C_EXE, exeBefore);
            }
            epf.delete();
            exe.delete();
        }
    }
}
