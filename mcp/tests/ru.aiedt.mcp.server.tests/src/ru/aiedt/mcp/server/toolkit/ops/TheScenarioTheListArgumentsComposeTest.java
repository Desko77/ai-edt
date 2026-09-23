/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

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
     * The kind picks the opening step's wording: each kind opens by its own words, an unknown one
     * is refused by name, and a call with no kind at all has nothing to open.
     */
    @Test
    public void theKindPicksTheOpeningStep()
    {
        for (Map.Entry<String, String> kind : VanessaTool.LIST_OPEN_STEPS.entrySet())
        {
            Map<String, String> p = given();
            p.put("listKind", kind.getKey()); //$NON-NLS-1$
            String scenario = accepted(p).scenario(null);
            assertTrue(kind.getKey() + " opens by its own words: " + scenario, //$NON-NLS-1$
                scenario.contains(kind.getValue().replace("{list}", "Товары"))); //$NON-NLS-1$ //$NON-NLS-2$
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
    }

    /**
     * The wait goes into the step's text and into the document's asynchronous-step ceiling, and is
     * bounded: not a whole number, less than a second, or above the run's own timeout, all
     * refused; left out it is ten seconds.
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
     * The three keys this branch sets are barred from the passthrough: a caller raising the
     * asynchronous-step ceiling above the seconds they named, or turning the capture machinery
     * off, would be answered with a run that did not happen the way it says.
     */
    @Test
    public void theThreeKeysAreRefusedInThePassthrough()
    {
        for (String key : new String[] {"ИспользоватьКомпонентуVanessaExt", //$NON-NLS-1$
            "ИспользоватьВнешнююКомпонентуДляСкриншотов", "ТаймаутДляАсинхронныхШагов"}) //$NON-NLS-1$ //$NON-NLS-2$
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
        assertEquals("Реализация товаров", VanessaTool.onScreenOf( //$NON-NLS-1$
            "Ожидали в течение 20 секунд, что откроется окно с заголовком <Печатная форма>. " //$NON-NLS-1$
                + "Текущее окно Реализация товаров.")); //$NON-NLS-1$
        assertEquals("a message that stops after the title still gives the title", "Реализация", //$NON-NLS-1$ //$NON-NLS-2$
            VanessaTool.onScreenOf("Текущее окно Реализация")); //$NON-NLS-1$
        assertEquals("", VanessaTool.onScreenOf( //$NON-NLS-1$
            "В таблице <Список> найдено <3> значений. А ожидали <1>.")); //$NON-NLS-1$
        assertEquals("", VanessaTool.onScreenOf(null)); //$NON-NLS-1$
    }
}
