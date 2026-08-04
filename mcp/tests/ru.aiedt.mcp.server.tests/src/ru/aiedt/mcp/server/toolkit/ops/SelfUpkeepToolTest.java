/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.upkeep.ReleaseOffer;

/**
 * Covers what this tool answers when nothing is configured - which is every fresh installation -
 * and the two refusals that have to be told apart.
 * <p>
 * A withheld action and a misspelt one are different problems with different fixes, and answering
 * both with "unknown action" would send a caller looking for a typo that is not there. The other
 * thing pinned here is that a dormant installation is answered without a single network request:
 * the feature ships off, and a tool that reached for a site anyway would make that promise false.
 * </p>
 */
public class SelfUpkeepToolTest
{
    private static final String FALLBACK = "No action defined for this state."; //$NON-NLS-1$

    @Test
    public void theToolIsNamedForWhatItReportsOn()
    {
        SelfUpkeepTool tool = new SelfUpkeepTool();
        assertEquals("self_upkeep", tool.getName()); //$NON-NLS-1$
        assertEquals(IMcpTool.ResponseType.JSON, tool.getResponseType());
    }

    @Test
    public void aFreshInstallationIsReportedAsDormantWithoutAskingAnyone()
    {
        // No site is configured in a test runtime, so this must settle locally. If it ever did
        // reach for the network, this test would be the one that hangs.
        String json = new SelfUpkeepTool().execute(params("status")); //$NON-NLS-1$
        assertTrue(json, json.contains("\"state\": \"dormant\"") //$NON-NLS-1$
            || json.contains("\"state\":\"dormant\"")); //$NON-NLS-1$
        assertTrue(json, json.contains("nextStep")); //$NON-NLS-1$
    }

    @Test
    public void statusIsWhatYouGetWithoutAsking()
    {
        String explicit = new SelfUpkeepTool().execute(params("status")); //$NON-NLS-1$
        String implied = new SelfUpkeepTool().execute(new HashMap<>());
        assertEquals(explicit, implied);
    }

    @Test
    public void checkingADormantInstallationStillTouchesNothing()
    {
        // The manual check bypasses the throttle, not the switch: with the feature off there is
        // nothing to ask and no address to ask it of.
        String json = new SelfUpkeepTool().execute(params("check")); //$NON-NLS-1$
        assertTrue(json, json.contains("dormant")); //$NON-NLS-1$
    }

    @Test
    public void installWithoutConfirmationIsRefused()
    {
        String json = new SelfUpkeepTool().execute(params("install")); //$NON-NLS-1$
        assertTrue(json, json.contains("needs confirm")); //$NON-NLS-1$
        assertFalse(json, json.contains("Unknown action")); //$NON-NLS-1$
    }

    @Test
    public void onlyAPlainTrueCountsAsConfirmation()
    {
        // Missing, false, and a string that is not a boolean at all must all refuse. Treating any
        // non-empty value as consent is how a caller ends up replacing the IDE's code by accident.
        for (String value : new String[] {"false", "banana", "", "0"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            Map<String, String> params = params("install"); //$NON-NLS-1$
            params.put("confirm", value); //$NON-NLS-1$
            String json = new SelfUpkeepTool().execute(params);
            assertTrue(value + " was accepted as confirmation: " + json, //$NON-NLS-1$
                json.contains("needs confirm")); //$NON-NLS-1$
        }
    }

    @Test
    public void assertionsOnThisResponseMustAccountForJsonEscaping()
    {
        // A reminder in test form. The response is JSON, and '=' arrives escaped as =, so a
        // check for "confirm=true" silently never matches and the test passes for nothing.
        String json = new SelfUpkeepTool().execute(params("install")); //$NON-NLS-1$
        assertFalse(json, json.contains("confirm=true")); //$NON-NLS-1$
        assertTrue(json, json.contains("needs confirm")); //$NON-NLS-1$
    }

    @Test
    public void aConfirmedInstallWithNothingToInstallStillDoesNothing()
    {
        // Confirmation is not a licence to act on a state that offers nothing. In this runtime the
        // ledger is dormant, so the refusal must come before anything is provisioned.
        Map<String, String> params = params("install"); //$NON-NLS-1$
        params.put("confirm", "true"); //$NON-NLS-1$ //$NON-NLS-2$

        String json = new SelfUpkeepTool().execute(params);
        assertTrue(json, json.contains("nothing to install")); //$NON-NLS-1$
        assertTrue(json, json.contains("dormant")); //$NON-NLS-1$
    }

    @Test
    public void aMisspeltActionSaysWhatIsAccepted()
    {
        String json = new SelfUpkeepTool().execute(params("stauts")); //$NON-NLS-1$
        assertTrue(json, json.contains("Unknown action")); //$NON-NLS-1$
        assertTrue(json, json.contains("status")); //$NON-NLS-1$
        assertTrue(json, json.contains("check")); //$NON-NLS-1$
    }

    @Test
    public void helpNamesTheSettingsAndTheTrustBoundary()
    {
        String json = new SelfUpkeepTool().execute(params("help")); //$NON-NLS-1$
        assertTrue(json, json.contains("Update site")); //$NON-NLS-1$
        assertTrue(json, json.contains("https")); //$NON-NLS-1$
        // The one thing that must never be dropped from this text: the address is the whole
        // security boundary, and whoever controls it decides what the IDE would install.
        assertTrue(json, json.contains("Trust")); //$NON-NLS-1$
    }

    @Test
    public void everyStateGetsAdviceOfItsOwn()
    {
        for (ReleaseOffer.State state : ReleaseOffer.State.values())
        {
            String advice = SelfUpkeepTool.nextStep(state, true);
            assertNotNull(state.name(), advice);
            assertFalse("no advice for " + state, advice.isEmpty()); //$NON-NLS-1$
            assertFalse("state " + state + " fell through to the fallback text", //$NON-NLS-1$ //$NON-NLS-2$
                FALLBACK.equals(advice));
        }
    }

    @Test
    public void anUnmanagedInstallationIsToldToStopLooking()
    {
        // Whatever the state says, an installation p2 does not manage cannot act on any of it, so
        // the advice must not send the caller off to configure a site that could never be used.
        String advice = SelfUpkeepTool.nextStep(ReleaseOffer.State.UPDATE_AVAILABLE, false);
        assertTrue(advice, advice.contains("p2")); //$NON-NLS-1$
    }

    @Test
    public void theFacadeIsNotAWayPastTheToolBeingSwitchedOff()
    {
        // The catalog decides, and in this runtime nothing is registered, so it says no. That is
        // the branch worth pinning: a preset that switches self_upkeep off must not be bypassable
        // by asking project_admin for the same operation, and without the gate the facade would
        // simply run it.
        Map<String, String> params = new HashMap<>();
        params.put("operation", "self_upkeep"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("action", "status"); //$NON-NLS-1$ //$NON-NLS-2$

        String json = new ProjectAdminFacadeTool().execute(params);
        assertTrue(json, json.contains("is disabled and was not executed")); //$NON-NLS-1$
        assertTrue(json, json.contains("self_upkeep")); //$NON-NLS-1$
    }

    private static Map<String, String> params(String action)
    {
        Map<String, String> params = new HashMap<>();
        params.put("action", action); //$NON-NLS-1$
        return params;
    }
}
