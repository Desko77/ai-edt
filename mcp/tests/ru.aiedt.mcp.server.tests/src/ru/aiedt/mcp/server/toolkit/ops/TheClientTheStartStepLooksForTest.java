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
            new File("C:/run/junit.xml"), new File("C:/run/shots"), true, false, 0, //$NON-NLS-1$ //$NON-NLS-2$
            CONNECTION, PORT, VanessaTool.TEST_CLIENT_WAIT_CEILING_SEC * 6, null);
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
            new File("C:/run/junit.xml"), new File("C:/run/shots"), true, false, 0, //$NON-NLS-1$ //$NON-NLS-2$
            CONNECTION, PORT, BUDGET_SEC, null);
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
}
