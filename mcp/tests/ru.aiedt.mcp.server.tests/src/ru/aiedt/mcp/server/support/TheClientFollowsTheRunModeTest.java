/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The client a launch starts follows the run mode: the ordinary application takes the thick
 * client and the flag that opens it, the managed one keeps the configuration's own client and
 * loses the flag, and a request that contradicts itself is refused before anything starts.
 */
public class TheClientFollowsTheRunModeTest
{
    /** An ordinary-application configuration starts the thick client, whatever the launch configuration says. */
    @Test
    public void theOrdinaryApplicationTakesTheThickClient()
    {
        ClientLaunchMode fresh = ClientLaunchMode.decide(null, null, ClientLaunchMode.ORDINARY, false, null);
        assertNull(fresh.refusal);
        assertEquals(ClientLaunchMode.THICK, fresh.clientType);
        assertEquals(LaunchConfigAccess.CLIENT_TYPE_THICK, fresh.clientTypeId);
        assertEquals(ClientLaunchMode.ORDINARY, fresh.runMode);
        assertTrue(fresh.wantsOrdinaryFlag());
        assertTrue(fresh.runModeSource, fresh.runModeSource.contains("default run mode")); //$NON-NLS-1$

        ClientLaunchMode thinOnDisk =
            ClientLaunchMode.decide(null, null, ClientLaunchMode.ORDINARY, true, LaunchConfigAccess.CLIENT_TYPE_THIN);
        assertEquals(LaunchConfigAccess.CLIENT_TYPE_THICK, thinOnDisk.clientTypeId);
        assertTrue(thinOnDisk.clientTypeSource, thinOnDisk.clientTypeSource.contains("thick client")); //$NON-NLS-1$

        ClientLaunchMode thickOnDisk =
            ClientLaunchMode.decide(null, null, ClientLaunchMode.ORDINARY, true, LaunchConfigAccess.CLIENT_TYPE_THICK);
        assertEquals(ClientLaunchMode.THICK, thickOnDisk.clientType);
        assertNull("nothing to apply: the configuration already starts the thick client", thickOnDisk.clientTypeId); //$NON-NLS-1$
        assertTrue(thickOnDisk.wantsOrdinaryFlag());
    }

    /** A managed configuration keeps the launch configuration's client, thin when there is none, and wants no flag. */
    @Test
    public void theManagedApplicationKeepsTheConfiguredClient()
    {
        ClientLaunchMode kept =
            ClientLaunchMode.decide(null, null, ClientLaunchMode.MANAGED, true, LaunchConfigAccess.CLIENT_TYPE_THICK);
        assertEquals(ClientLaunchMode.THICK, kept.clientType);
        assertNull(kept.clientTypeId);
        assertEquals("the launch configuration", kept.clientTypeSource); //$NON-NLS-1$
        assertEquals(ClientLaunchMode.MANAGED, kept.runMode);
        assertFalse("a thick client in the managed mode must not carry the flag", kept.wantsOrdinaryFlag()); //$NON-NLS-1$

        ClientLaunchMode fresh = ClientLaunchMode.decide(null, null, ClientLaunchMode.MANAGED, false, null);
        assertEquals(ClientLaunchMode.THIN, fresh.clientType);
        assertEquals(LaunchConfigAccess.CLIENT_TYPE_THIN, fresh.clientTypeId);
        assertFalse(fresh.wantsOrdinaryFlag());

        ClientLaunchMode unknown = ClientLaunchMode.decide(null, null, null, false, null);
        assertEquals(ClientLaunchMode.MANAGED, unknown.runMode);
        assertTrue(unknown.runModeSource, unknown.runModeSource.contains("names none")); //$NON-NLS-1$

        ClientLaunchMode auto = ClientLaunchMode.decide(null, null, ClientLaunchMode.MANAGED, true, "something.else"); //$NON-NLS-1$
        assertEquals(ClientLaunchMode.AUTO, auto.clientType);

        // An existing configuration that leaves the client to EDT keeps that: nothing goes on the launch.
        ClientLaunchMode leftToEdt = ClientLaunchMode.decide(null, null, ClientLaunchMode.MANAGED, true, null);
        assertEquals(ClientLaunchMode.AUTO, leftToEdt.clientType);
        assertNull("EDT's own selection must not be replaced by thin", leftToEdt.clientTypeId); //$NON-NLS-1$
        assertTrue(leftToEdt.clientTypeSource, leftToEdt.clientTypeSource.contains("leaves the client to EDT")); //$NON-NLS-1$
        ClientLaunchMode leftToEdtOrdinary = ClientLaunchMode.decide(null, null, ClientLaunchMode.ORDINARY, true, null);
        assertEquals("the ordinary application still takes the thick client", //$NON-NLS-1$
            LaunchConfigAccess.CLIENT_TYPE_THICK, leftToEdtOrdinary.clientTypeId);
    }

    /** The arguments win over the configuration, and a contradiction between them is refused. */
    @Test
    public void theArgumentsWinAndAContradictionIsRefused()
    {
        ClientLaunchMode managedThin =
            ClientLaunchMode.decide("thin", "managed", ClientLaunchMode.ORDINARY, true, LaunchConfigAccess.CLIENT_TYPE_THICK); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(managedThin.refusal);
        assertEquals(LaunchConfigAccess.CLIENT_TYPE_THIN, managedThin.clientTypeId);
        assertEquals(ClientLaunchMode.MANAGED, managedThin.runMode);
        assertEquals("the runMode argument", managedThin.runModeSource); //$NON-NLS-1$
        assertFalse(managedThin.wantsOrdinaryFlag());

        ClientLaunchMode thickManaged = ClientLaunchMode.decide(" Thick ", null, ClientLaunchMode.MANAGED, false, null); //$NON-NLS-1$
        assertEquals(LaunchConfigAccess.CLIENT_TYPE_THICK, thickManaged.clientTypeId);
        assertFalse(thickManaged.wantsOrdinaryFlag());

        ClientLaunchMode ordinaryOnManaged = ClientLaunchMode.decide(null, "ordinary", ClientLaunchMode.MANAGED, false, null); //$NON-NLS-1$
        assertEquals(ClientLaunchMode.THICK, ordinaryOnManaged.clientType);
        assertTrue(ordinaryOnManaged.wantsOrdinaryFlag());

        ClientLaunchMode thinOrdinary = ClientLaunchMode.decide("thin", null, ClientLaunchMode.ORDINARY, false, null); //$NON-NLS-1$
        assertNotNull(thinOrdinary.refusal);
        assertTrue(thinOrdinary.refusal, thinOrdinary.refusal.contains("runMode=managed")); //$NON-NLS-1$
        assertNotNull(ClientLaunchMode.decide("web", "ordinary", null, false, null).refusal); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(ClientLaunchMode.decide("fat", null, null, false, null).refusal.contains("thin, thick or web")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(ClientLaunchMode.decide(null, "classic", null, false, null).refusal.contains("ordinary or managed")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The flag is added once, removed wherever it stands, and the other parameters stay. */
    @Test
    public void theFlagIsAddedOnceAndRemovedWhereverItStands()
    {
        assertEquals(ClientLaunchMode.ORDINARY_FLAG, ClientLaunchMode.withOrdinaryFlag(null, true));
        assertEquals(ClientLaunchMode.ORDINARY_FLAG, ClientLaunchMode.withOrdinaryFlag("  ", true)); //$NON-NLS-1$
        assertEquals("/DisableStartupMessages /RunModeOrdinaryApplication", //$NON-NLS-1$
            ClientLaunchMode.withOrdinaryFlag("/DisableStartupMessages", true)); //$NON-NLS-1$
        assertEquals("/DisableStartupMessages /RunModeOrdinaryApplication", //$NON-NLS-1$
            ClientLaunchMode.withOrdinaryFlag("/DisableStartupMessages /RunModeOrdinaryApplication", true)); //$NON-NLS-1$
        assertEquals("/A /B", ClientLaunchMode.withOrdinaryFlag("/A /runmodeordinaryapplication  /B", false)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", ClientLaunchMode.withOrdinaryFlag("/RunModeOrdinaryApplication", false)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("nothing to remove leaves the parameters as they are", ClientLaunchMode.withOrdinaryFlag(null, false)); //$NON-NLS-1$
        assertEquals("/A", ClientLaunchMode.withOrdinaryFlag("/A", false)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A quoted value is one parameter: the flag inside it is text, and its inner spacing stays. */
    @Test
    public void aQuotedValueStaysWhole()
    {
        String quoted = "/C \"a /RunModeOrdinaryApplication  b\" /X"; //$NON-NLS-1$
        assertEquals(quoted, ClientLaunchMode.withOrdinaryFlag(quoted, false));
        assertEquals(quoted + " " + ClientLaunchMode.ORDINARY_FLAG, ClientLaunchMode.withOrdinaryFlag(quoted, true)); //$NON-NLS-1$
        assertEquals("/C \"a  b\"", ClientLaunchMode.withOrdinaryFlag("/C \"a  b\" /RunModeOrdinaryApplication", false)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(java.util.List.of("/C", "\"a  b\"", "/X"), ClientLaunchMode.tokensOf("  /C \"a  b\"   /X ")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** The attribute values of the three clients read back as their names, anything else as auto. */
    @Test
    public void theClientTypeIdsHaveNames()
    {
        assertEquals(ClientLaunchMode.THIN, ClientLaunchMode.nameOf(LaunchConfigAccess.CLIENT_TYPE_THIN));
        assertEquals(ClientLaunchMode.THICK, ClientLaunchMode.nameOf(LaunchConfigAccess.CLIENT_TYPE_THICK));
        assertEquals(ClientLaunchMode.WEB, ClientLaunchMode.nameOf(LaunchConfigAccess.CLIENT_TYPE_WEB));
        assertEquals(ClientLaunchMode.AUTO, ClientLaunchMode.nameOf(null));
        assertEquals(LaunchConfigAccess.CLIENT_TYPE_WEB, ClientLaunchMode.decide("web", null, null, false, null).clientTypeId); //$NON-NLS-1$
    }
}
