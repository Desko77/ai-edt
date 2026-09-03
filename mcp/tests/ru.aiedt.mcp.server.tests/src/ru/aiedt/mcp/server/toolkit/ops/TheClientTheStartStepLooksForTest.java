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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The step "Я запускаю сценарий открытия TestClient" starts the client named in the TestClient
 * block of VAParams. With no block the step reaches Vanessa with nothing to start and answers
 * "Тип не определен (ТестируемаяГруппаФормы)" with an empty client type and PID 0 - the
 * UI-testing types exist only once a client runs under the test manager.
 */
public class TheClientTheStartStepLooksForTest
{
    private static final String CONNECTION = "File=\"C:/bases/demo\";"; //$NON-NLS-1$

    private static final int PORT = 48123;

    /** Under the ceiling, so this is the value the block must carry. */
    private static final int BUDGET_SEC = 300;

    /**
     * The block names the infobase the run was given, so the client opens the base the scenario
     * was written against rather than whichever one Vanessa was last pointed at.
     */
    @Test
    public void theClientOpensTheInfobaseOfTheRun()
    {
        JsonObject client = onlyClient(params());
        assertEquals(CONNECTION, client.get("PathToInfobase").getAsString()); //$NON-NLS-1$
        assertEquals("Thin", client.get("ClientType").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("localhost", client.get("ComputerName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(client.get("PortTestClient").getAsInt() > 0); //$NON-NLS-1$
    }

    /**
     * Vanessa spells the parameter name with a capital I in the middle. Corrected to the spelling
     * that reads right, the key is simply never read.
     */
    @Test
    public void theOddlySpelledKeyKeepsItsSpelling()
    {
        assertTrue(onlyClient(params()).has("AddItionalParameters")); //$NON-NLS-1$
    }

    /**
     * The deadline comes from the run's own budget rather than a constant, so raising
     * timeoutSeconds for a slow first start actually reaches the step that waits - but it stays
     * under that budget, so Vanessa reaches its own timeout and writes the report before this tool
     * kills the process.
     */
    @Test
    public void theClientIsGivenAWindowAndADeadlineUnderTheRunsOwn()
    {
        JsonObject block = params().getAsJsonObject("TestClient"); //$NON-NLS-1$
        assertTrue(block.get("runtestclientwithmaximizedwindow").getAsBoolean()); //$NON-NLS-1$
        int deadline = block.get("testclienttimeout").getAsInt(); //$NON-NLS-1$
        assertEquals(VanessaTool.clientWaitWithin(BUDGET_SEC), deadline);
        assertTrue("the client may not be waited for as long as the whole run", //$NON-NLS-1$
            deadline < BUDGET_SEC);
    }

    /**
     * Every budget keeps something back for Vanessa to write its report in, so the wait is always
     * shorter than the run that contains it. A budget of one second is the single exception, and
     * no allocation can do better with one second.
     */
    @Test
    public void everyBudgetKeepsAReserveToReportIn()
    {
        for (int budget : new int[] {2, 3, 5, 30, 60, 61, 90, 180, 300, 660, 3600})
        {
            int wait = VanessaTool.clientWaitWithin(budget);
            String said = "budget " + budget + " gave a wait of " + wait; //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(said, wait > 0);
            assertTrue(said, wait < budget);
            assertTrue(said, wait <= VanessaTool.TEST_CLIENT_WAIT_CEILING_SEC);
        }
        assertTrue(VanessaTool.clientWaitWithin(1) > 0);
    }

    /**
     * A number that is not a port is refused by name. Written down as given, it would leave the
     * client not listening and the start step failing for a reason naming neither the port nor
     * this tool.
     */
    @Test
    public void aNumberThatIsNotAPortIsRefused()
    {
        assertNull(VanessaTool.whyThePortCannotBeUsed(1));
        assertNull(VanessaTool.whyThePortCannotBeUsed(VanessaTool.TEST_CLIENT_PORT));
        assertNull(VanessaTool.whyThePortCannotBeUsed(VanessaTool.HIGHEST_PORT));
        for (int notAPort : new int[] {0, -1, VanessaTool.HIGHEST_PORT + 1})
        {
            String refusal = VanessaTool.whyThePortCannotBeUsed(notAPort);
            assertNotNull("a port of " + notAPort + " was accepted", refusal); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(refusal.contains(String.valueOf(notAPort)));
        }
    }

    /**
     * A budget longer than the ceiling does not become the wait: a client that has not come up
     * within the ceiling is not coming, and a long suite would otherwise spend all of its time
     * waiting for one that never will.
     */
    @Test
    public void aLongBudgetDoesNotBecomeALongWaitForTheClient()
    {
        String json = VanessaTool.buildVaParams(new File("C:/run/one.feature"), //$NON-NLS-1$
            new File("C:/run/junit.xml"), new File("C:/run/shots"), true, false, //$NON-NLS-1$ //$NON-NLS-2$
            CONNECTION, PORT, VanessaTool.TEST_CLIENT_WAIT_CEILING_SEC * 6, true, null);
        JsonObject block = JsonParser.parseString(json).getAsJsonObject()
            .getAsJsonObject("TestClient"); //$NON-NLS-1$
        assertEquals(VanessaTool.TEST_CLIENT_WAIT_CEILING_SEC,
            block.get("testclienttimeout").getAsInt()); //$NON-NLS-1$
    }

    /** The port is the one the caller named, so a second run can be given another. */
    @Test
    public void thePortIsTheOneTheCallerNamed()
    {
        assertEquals(PORT, onlyClient(params()).get("PortTestClient").getAsInt()); //$NON-NLS-1$
    }

    /**
     * Turned off, Vanessa opens its own window and waits there: the run spends its whole
     * budget on a form nobody is looking at and writes no report, which reads exactly like a
     * scenario that never started.
     */
    @Test
    public void theRunIsToldToPlayItsScenarios()
    {
        assertTrue(params().get("ВыполнитьСценарии").getAsBoolean()); //$NON-NLS-1$
    }

    /**
     * The single client of the block.
     *
     * @param root the parsed VAParams.
     * @return the one entry of datatestclients
     */
    private static JsonObject onlyClient(JsonObject root)
    {
        JsonObject block = root.getAsJsonObject("TestClient"); //$NON-NLS-1$
        assertNotNull("VAParams carries no TestClient block", block); //$NON-NLS-1$
        JsonArray clients = block.getAsJsonArray("datatestclients"); //$NON-NLS-1$
        assertNotNull("the TestClient block names no client", clients); //$NON-NLS-1$
        assertEquals(1, clients.size());
        return clients.get(0).getAsJsonObject();
    }

    /**
     * VAParams as the runner writes them.
     *
     * @return the parsed document
     */
    private static JsonObject params()
    {
        String json = VanessaTool.buildVaParams(new File("C:/run/one.feature"), //$NON-NLS-1$
            new File("C:/run/junit.xml"), new File("C:/run/shots"), true, false, //$NON-NLS-1$ //$NON-NLS-2$
            CONNECTION, PORT, BUDGET_SEC, true, null);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * A base with users defined meets a client that names none with a login window, and the run
     * then waits out its whole deadline. The user goes into the connection string, so it reaches
     * the test client the start step launches as well.
     */
    @Test
    public void theUserGoesIntoTheConnectionString()
    {
        assertEquals(CONNECTION + "Usr=\"Админ\"" + ";", //$NON-NLS-1$ //$NON-NLS-2$
            VanessaTool.namingTheUser(CONNECTION, "Админ")); //$NON-NLS-1$
    }

    /** A string that ends without one gets the separator the next field needs. */
    @Test
    public void aStringMissingItsSeparatorGetsOne()
    {
        assertEquals("File=\"C:/b\";Usr=\"A\";", //$NON-NLS-1$
            VanessaTool.namingTheUser("File=\"C:/b\"", "A")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Named nobody, the string is handed back as it came. */
    @Test
    public void withoutAUserTheStringIsUntouched()
    {
        assertEquals(CONNECTION, VanessaTool.namingTheUser(CONNECTION, null));
        assertEquals(CONNECTION, VanessaTool.namingTheUser(CONNECTION, "   ")); //$NON-NLS-1$
    }

    /** Off unless asked for: a run that drives no form needs no client of its own. */
    @Test
    public void theBlockIsAbsentUnlessTheCallerAsksForIt()
    {
        String json = VanessaTool.buildVaParams(new File("C:/run/one.feature"), //$NON-NLS-1$
            new File("C:/run/junit.xml"), new File("C:/run/shots"), true, false, //$NON-NLS-1$ //$NON-NLS-2$
            CONNECTION, PORT, BUDGET_SEC, false, null);
        assertNull(JsonParser.parseString(json).getAsJsonObject().get("TestClient")); //$NON-NLS-1$
    }

    /**
     * A named file is the only one played. Vanessa loads from a directory, and the directory is
     * all КаталогФич can carry, so without naming the file every other .feature sitting beside it
     * runs too - and the answer reports the run of the one that was asked for.
     *
     * @throws IOException if the temporary feature file cannot be written
     */
    @Test
    public void aNamedFileIsTheOnlyOnePlayed() throws IOException
    {
        File dir = Files.createTempDirectory("aiedt-va-one").toFile(); //$NON-NLS-1$
        File one = new File(dir, "one.feature"); //$NON-NLS-1$
        File other = new File(dir, "other.feature"); //$NON-NLS-1$
        Files.write(one.toPath(), new byte[0]);
        Files.write(other.toPath(), new byte[0]);
        try
        {
            JsonObject document = JsonParser.parseString(
                VanessaTool.buildVaParams(one, new File("C:/run/junit.xml"), //$NON-NLS-1$
                    new File("C:/run/shots"), true, false, CONNECTION, PORT, BUDGET_SEC, //$NON-NLS-1$
                    true, null)).getAsJsonObject();
            assertEquals(dir.getAbsolutePath(),
                document.get("КаталогФич").getAsString()); //$NON-NLS-1$
            JsonArray only = document.getAsJsonArray("СписокФичДляВыполнения"); //$NON-NLS-1$
            assertNotNull("the directory alone would have played both", only); //$NON-NLS-1$
            assertEquals(1, only.size());
            assertEquals(one.getAbsolutePath(), only.get(0).getAsString());

            JsonObject whole = JsonParser.parseString(
                VanessaTool.buildVaParams(dir, new File("C:/run/junit.xml"), //$NON-NLS-1$
                    new File("C:/run/shots"), true, false, CONNECTION, PORT, BUDGET_SEC, //$NON-NLS-1$
                    true, null)).getAsJsonObject();
            assertNull("a directory was asked for, so nothing narrows it", //$NON-NLS-1$
                whole.get("СписокФичДляВыполнения")); //$NON-NLS-1$
        }
        finally
        {
            one.delete();
            other.delete();
            dir.delete();
        }
    }

    /**
     * The refusal reaches a caller coming back for a run as well. Read after the runKey routing
     * it never would: that call is answered about the key, and the argument it also named is
     * dropped without a word.
     */
    @Test
    public void theRemovedArgumentIsRefusedOnEveryCall()
    {
        java.util.Map<String, String> comingBack = new java.util.HashMap<>();
        comingBack.put("runKey", "one-that-does-not-exist"); //$NON-NLS-1$ //$NON-NLS-2$
        comingBack.put("stepDelaySeconds", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the answer names the key instead of the argument", //$NON-NLS-1$
            new VanessaTool().execute(comingBack).contains("stepDelaySeconds")); //$NON-NLS-1$
    }

    /**
     * Every key of the document is one Vanessa reads, on every branch that builds it. A name it
     * does not know is dropped in silence, so a run asked for under one plays as though it had
     * not been asked at all: the report is not written, the screenshots are not taken, the client
     * is not closed - and the answer describes all three as done. Counting the keys is what tells
     * the difference, since nothing else does, and counting them on one branch leaves the others
     * free to carry anything.
     *
     * @throws IOException if the temporary feature file cannot be written
     */
    @Test
    public void everyKeyOfTheDocumentIsOneVanessaReads() throws IOException
    {
        File dir = Files.createTempDirectory("aiedt-va-keys").toFile(); //$NON-NLS-1$
        File one = new File(dir, "one.feature"); //$NON-NLS-1$
        Files.write(one.toPath(), new byte[0]);
        try
        {
            for (boolean withTestClient : new boolean[] {true, false})
            {
                for (boolean shots : new boolean[] {true, false})
                {
                    for (boolean keepOpen : new boolean[] {true, false})
                    {
                        census(one, withTestClient, shots, keepOpen, true);
                        census(dir, withTestClient, shots, keepOpen, false);
                        censusWithExtra(one, withTestClient, shots, keepOpen);
                    }
                }
            }
        }
        finally
        {
            one.delete();
            dir.delete();
        }
    }

    /**
     * The two lists are one. A key this tool sets and does not bar from the passthrough can be
     * replaced by the caller, and the merge happens last, so the replacement wins while the answer
     * still describes what the argument asked for. Comparing the set the guard holds with the keys
     * a fully populated document carries is what keeps a new key from arriving unguarded.
     *
     * @throws IOException if the temporary feature file cannot be written
     */
    @Test
    public void everyKeyThisToolSetsIsBarredFromThePassthrough() throws IOException
    {
        File dir = Files.createTempDirectory("aiedt-va-guard").toFile(); //$NON-NLS-1$
        File one = new File(dir, "one.feature"); //$NON-NLS-1$
        Files.write(one.toPath(), new byte[0]);
        try
        {
            java.util.Set<String> written = new java.util.TreeSet<>();
            for (String key : JsonParser.parseString(
                VanessaTool.buildVaParams(one, new File("C:/run/junit.xml"), //$NON-NLS-1$
                    new File("C:/run/shots"), true, false, CONNECTION, PORT, BUDGET_SEC, //$NON-NLS-1$
                    true, null)).getAsJsonObject().keySet())
            {
                written.add(key.toLowerCase(java.util.Locale.ROOT));
            }
            assertEquals("a key this tool sets is not barred from the passthrough", //$NON-NLS-1$
                new java.util.TreeSet<>(VanessaTool.OURS_TO_SET), written);
        }
        finally
        {
            one.delete();
            dir.delete();
        }
    }

    /**
     * What the caller added is the only difference the merge makes: it adds its own key and
     * changes no other. A key the passthrough is allowed to carry is one Vanessa reads too.
     *
     * @param featurePath the scenarios.
     * @param withTestClient whether a test client is named.
     * @param shots whether a screenshot is taken on failure.
     * @param keepOpen whether the client is left running.
     */
    private static void censusWithExtra(File featurePath, boolean withTestClient, boolean shots,
        boolean keepOpen)
    {
        JsonObject added = new JsonObject();
        added.addProperty("СписокТеговОтбор", "Дым"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject document = JsonParser.parseString(
            VanessaTool.buildVaParams(featurePath, new File("C:/run/junit.xml"), //$NON-NLS-1$
                new File("C:/run/shots"), shots, keepOpen, CONNECTION, PORT, BUDGET_SEC, //$NON-NLS-1$
                withTestClient, added)).getAsJsonObject();
        assertEquals("Дым", document.get("СписокТеговОтбор").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject withoutIt = JsonParser.parseString(
            VanessaTool.buildVaParams(featurePath, new File("C:/run/junit.xml"), //$NON-NLS-1$
                new File("C:/run/shots"), shots, keepOpen, CONNECTION, PORT, BUDGET_SEC, //$NON-NLS-1$
                withTestClient, null)).getAsJsonObject();
        // Taken back out, what is left has to be the document the merge started from - every
        // member of it, not merely the same set of names.
        document.remove("СписокТеговОтбор"); //$NON-NLS-1$
        assertEquals("the merge changed something other than what was added", withoutIt, //$NON-NLS-1$
            document);
    }

    /**
     * Builds the document one way and compares its keys with the ones Vanessa reads.
     *
     * @param featurePath the scenarios, a file or the directory holding them.
     * @param withTestClient whether a test client is named.
     * @param shots whether a screenshot is taken on failure.
     * @param keepOpen whether the client is left running.
     * @param named whether the feature path is a file, which is named to Vanessa on its own.
     */
    private static void census(File featurePath, boolean withTestClient, boolean shots,
        boolean keepOpen, boolean named)
    {
        String json = VanessaTool.buildVaParams(featurePath, new File("C:/run/junit.xml"), //$NON-NLS-1$
            new File("C:/run/shots"), shots, keepOpen, CONNECTION, PORT, BUDGET_SEC, //$NON-NLS-1$
            withTestClient, null);
        java.util.Set<String> read = new java.util.TreeSet<>(java.util.Arrays.asList(
            "ВыполнитьСценарии", "КаталогФич", "ДелатьОтчетВФорматеАллюр", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "КаталогOutputAllureБазовый", "ДелатьСкриншотПриВозникновенииОшибки", //$NON-NLS-1$ //$NON-NLS-2$
            "КаталогOutputСкриншоты", "ЗакрытьTestClientПослеЗапускаСценариев", //$NON-NLS-1$ //$NON-NLS-2$
            "ЗавершитьРаботуСистемы")); //$NON-NLS-1$
        if (withTestClient)
        {
            read.add("TestClient"); //$NON-NLS-1$
        }
        if (named)
        {
            read.add("СписокФичДляВыполнения"); //$NON-NLS-1$
        }
        java.util.Set<String> written = new java.util.TreeSet<>(
            JsonParser.parseString(json).getAsJsonObject().keySet());
        assertEquals("the document carries a key that is not among the ones Vanessa reads", //$NON-NLS-1$
            read, written);
    }
}
