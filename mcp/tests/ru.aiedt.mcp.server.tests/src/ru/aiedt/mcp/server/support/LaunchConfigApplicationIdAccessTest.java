/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.Test;

import ru.aiedt.mcp.server.support.FakeLaunchConfigurations.Configuration;

/**
 * The application-id adapter over Eclipse's launch manager.
 *
 * <p>Two things it must not do. It must not write through a working copy without saving it -
 * Eclipse's copy changes nothing until {@code doSave}, so an adapter that forgets the save
 * reports a restore that never happened. And it must not hand the manager a display name where a
 * memento belongs: one name occurs in several launch types at once, and Eclipse answers a name
 * with a failure rather than with a configuration.</p>
 */
public class LaunchConfigApplicationIdAccessTest
{
    private static final String APP_ID = LaunchConfigAccess.ATTR_APPLICATION_ID;

    @Test
    public void theStoreChangesOnlyWhenTheWorkingCopyIsSaved()
    {
        FakeLaunchConfigurations fake = new FakeLaunchConfigurations();
        Configuration configuration = fake.add("m-one", "Run one", "project-one", "application-one"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        configuration.stored.put("anotherAttribute", "kept"); //$NON-NLS-1$ //$NON-NLS-2$
        LaunchApplicationIds.Access access = LaunchConfigAccess.applicationIdAccess(fake.manager());

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        fake.stripApplicationIds();
        // The strip is real in the store, which is what makes the save load-bearing: an adapter
        // that edits the copy and walks away leaves the configuration stripped.
        assertNull(configuration.applicationId());
        LaunchApplicationIds.RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals("application-one", configuration.applicationId()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("kept", configuration.stored.get("anotherAttribute")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("one save per restored configuration", 1, configuration.saves); //$NON-NLS-1$
        assertEquals(List.of("Run one"), report.restored); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aDisplayNameIsNeverHandedToTheManagerWhereAMementoBelongs()
        throws Exception
    {
        FakeLaunchConfigurations fake = new FakeLaunchConfigurations();
        fake.add("m-one", "Run one", "project-one", "application-one"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        ILaunchManager manager = fake.manager();
        LaunchApplicationIds.Access access = LaunchConfigAccess.applicationIdAccess(manager);

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        fake.stripApplicationIds();
        LaunchApplicationIds.RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals("the round trip went through the memento, so it restored", //$NON-NLS-1$
            List.of("Run one"), report.restored); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("application-one", fake.byName("Run one").applicationId()); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            manager.getLaunchConfiguration("Run one"); //$NON-NLS-1$
            fail("the fake manager must reject a display name where Eclipse expects a memento"); //$NON-NLS-1$
        }
        catch (CoreException expected)
        {
            assertEquals("a display name is not a memento", expected.getMessage()); //$NON-NLS-1$
        }
    }

    @Test
    public void twoConfigurationsOfDifferentTypesWithOneNameAreWrittenApart()
    {
        // The remote configuration comes first in the manager's list, so a name-addressed adapter
        // would take it for both writes and leave the runtime client stripped.
        FakeLaunchConfigurations fake = new FakeLaunchConfigurations();
        Configuration remote = fake.add("m-remote", "Run one", //$NON-NLS-1$ //$NON-NLS-2$
            LaunchConfigAccess.TYPE_REMOTE_RUNTIME);
        remote.stored.put(APP_ID, "application-remote"); //$NON-NLS-1$
        Configuration client = fake.add("m-client", "Run one", //$NON-NLS-1$ //$NON-NLS-2$
            LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID);
        client.stored.put(APP_ID, "application-client"); //$NON-NLS-1$
        LaunchApplicationIds.Access access = LaunchConfigAccess.applicationIdAccess(fake.manager());

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        fake.stripApplicationIds();
        LaunchApplicationIds.RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals("application-remote", remote.applicationId()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("application-client", client.applicationId()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, report.restored.size());
        assertTrue(report.failed.isEmpty());
    }

    @Test
    public void aConfigurationThatCannotBeAddressedIsSkippedWithoutCostingTheOthers()
    {
        FakeLaunchConfigurations fake = new FakeLaunchConfigurations();
        Configuration broken = fake.add("m-broken", "Run broken", "project-one", "application-broken"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        broken.unreadable = true;
        Configuration good = fake.add("m-good", "Run good", "project-one", "application-good"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        LaunchApplicationIds.Access access = LaunchConfigAccess.applicationIdAccess(fake.manager());

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        assertEquals("an unaddressed configuration is not in the snapshot at all", //$NON-NLS-1$
            1, snapshot.size());
        fake.stripApplicationIds();
        LaunchApplicationIds.RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals("application-good", good.applicationId()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("nothing was written to the configuration that could not be addressed", //$NON-NLS-1$
            broken.applicationId());
        assertEquals(List.of("Run good"), report.restored); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(report.failed.isEmpty());
        assertFalse("nothing was claimed about the configuration that could not be read", //$NON-NLS-1$
            report.describe().contains("Run broken")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
