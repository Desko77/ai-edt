/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.LaunchApplicationIds.RestoreReport;

/**
 * The launch-configuration guard puts back what a save of the infobase list strips - and only
 * that.
 *
 * <p>Saving the list makes EDT reload it, the reload announces every application as deleted, and
 * the launch-configuration manager answers by removing the application id from every launch
 * configuration. The guard brackets the write: snapshot before, restore after. These cases run
 * the guard against a fake store: several configurations and one without an id, a write that
 * fails mid-list, edits that land between the snapshot and the restore - of another
 * configuration, and of the same attribute of the same configuration - and a write that does
 * not land.</p>
 */
public class LaunchApplicationIdsTest
{
    /** The attribute the guard minds, the one EDT strips on the reload. */
    private static final String APP_ID = LaunchConfigAccess.ATTR_APPLICATION_ID;

    /**
     * The launch configurations as an in-memory store. {@code writeApplicationId} touches the
     * one attribute and nothing else, the way the Eclipse adapter's working copy does.
     */
    private static final class FakeAccess
        implements LaunchApplicationIds.Access
    {
        final Map<String, Map<String, String>> configs = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        String failOn;

        void add(String name, String applicationId)
        {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("project", "p-" + name); //$NON-NLS-1$ //$NON-NLS-2$
            if (applicationId != null)
            {
                attributes.put(APP_ID, applicationId);
            }
            configs.put(name, attributes);
        }

        /** What EDT's reload does: the application id is gone from every configuration. */
        void stripApplicationIds()
        {
            for (Map<String, String> attributes : configs.values())
            {
                attributes.remove(APP_ID);
            }
        }

        @Override
        public List<String> configurationNames()
        {
            return new ArrayList<>(configs.keySet());
        }

        @Override
        public String readApplicationId(String name)
        {
            Map<String, String> attributes = configs.get(name);
            return attributes == null ? null : attributes.get(APP_ID);
        }

        @Override
        public void writeApplicationId(String name, String applicationId)
            throws Exception
        {
            if (name.equals(failOn))
            {
                throw new Exception("the store refuses the write"); //$NON-NLS-1$
            }
            writes.add(name);
            configs.get(name).put(APP_ID, applicationId);
        }
    }

    @Test
    public void everyConfigurationKeepsItsIdAndTheIdlessOneStaysIdless()
    {
        FakeAccess access = new FakeAccess();
        access.add("app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$
        access.add("app-two", "app-2"); //$NON-NLS-1$ //$NON-NLS-2$
        access.add("no-application", null); //$NON-NLS-1$

        Map<String, String> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals("app-1", access.readApplicationId("app-one")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("app-2", access.readApplicationId("app-two")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a configuration that had no application id gains none", //$NON-NLS-1$
            access.configs.get("no-application").get(APP_ID)); //$NON-NLS-1$
        assertEquals(List.of("app-one", "app-two"), report.restored); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(report.changedMeanwhile.isEmpty());
        assertTrue(report.gone.isEmpty());
        assertTrue(report.failed.isEmpty());
    }

    @Test
    public void aWriteInterruptedMidListRestoresWhatWasStrippedAndNamesIt()
    {
        // The save died halfway: the reload had already stripped one configuration when the
        // caller landed on the failure path. The restore runs there too.
        FakeAccess access = new FakeAccess();
        access.add("app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$
        access.add("app-two", "app-2"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> snapshot = LaunchApplicationIds.snapshot(access);
        access.configs.get("app-one").remove(APP_ID); //$NON-NLS-1$
        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals("app-1", access.readApplicationId("app-one")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("app-one"), report.restored); //$NON-NLS-1$
        assertEquals("the untouched configuration is reported as kept, not as restored", //$NON-NLS-1$
            List.of("app-two"), report.kept); //$NON-NLS-1$
        assertTrue("the answer names the restored configuration", //$NON-NLS-1$
            report.describe().contains("app-one")); //$NON-NLS-1$
        assertFalse(report.describe().contains("app-two")); //$NON-NLS-1$
    }

    @Test
    public void otherAttributesAndEditsOfOtherConfigurationsSurvive()
    {
        FakeAccess access = new FakeAccess();
        access.add("app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$
        access.add("app-two", "app-2"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        // Between the snapshot and the restore somebody edits another configuration.
        access.configs.get("app-two").put("clientType", "thick"); //$NON-NLS-1$ //$NON-NLS-2$
        LaunchApplicationIds.restore(access, snapshot);

        assertEquals("thick", access.configs.get("app-two").get("clientType")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("p-app-one", access.configs.get("app-one").get("project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("p-app-two", access.configs.get("app-two").get("project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("restore wrote the id back and nothing else", //$NON-NLS-1$
            2, access.writes.size());
    }

    @Test
    public void aRereadAfterTheRestoreSeesTheSnapshot()
    {
        FakeAccess access = new FakeAccess();
        access.add("app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$
        access.add("app-two", "app-2"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        LaunchApplicationIds.restore(access, snapshot);

        assertEquals("a fresh snapshot of the reread list is the one taken before the write", //$NON-NLS-1$
            snapshot, LaunchApplicationIds.snapshot(access));
    }

    @Test
    public void aValueSetAfterTheSnapshotIsNotOverwrittenButNamed()
    {
        FakeAccess access = new FakeAccess();
        access.add("app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        // The attribute reappears with another value before the restore runs: somebody's edit,
        // not the guard's loss to repair.
        access.configs.get("app-one").put(APP_ID, "app-edited"); //$NON-NLS-1$ //$NON-NLS-2$

        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals("the later value stands", "app-edited", access.readApplicationId("app-one")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(access.writes.isEmpty());
        assertEquals(List.of("app-one"), report.changedMeanwhile); //$NON-NLS-1$
        assertTrue(report.describe().contains("app-one")); //$NON-NLS-1$
    }

    @Test
    public void aGoneConfigurationIsNamedNotRecreated()
    {
        FakeAccess access = new FakeAccess();
        access.add("app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$
        access.add("app-two", "app-2"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        access.configs.remove("app-two"); //$NON-NLS-1$

        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals(List.of("app-two"), report.gone); //$NON-NLS-1$
        assertFalse("the guard repairs an attribute, it does not resurrect a configuration", //$NON-NLS-1$
            access.configs.containsKey("app-two")); //$NON-NLS-1$
        assertTrue(report.describe().contains("app-two")); //$NON-NLS-1$
    }

    @Test
    public void nothingStrippedIsQuiet()
    {
        FakeAccess access = new FakeAccess();
        access.add("app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> snapshot = LaunchApplicationIds.snapshot(access);
        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertTrue(report.isQuiet());
        assertEquals("", report.describe()); //$NON-NLS-1$
        assertTrue(access.writes.isEmpty());
        assertEquals(List.of("app-one"), report.kept); //$NON-NLS-1$
    }

    @Test
    public void aWriteThatDoesNotLandIsNamedAsFailed()
    {
        FakeAccess access = new FakeAccess();
        access.add("app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        access.failOn = "app-one"; //$NON-NLS-1$

        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals(List.of("app-one"), report.failed); //$NON-NLS-1$
        assertTrue(report.restored.isEmpty());
        assertTrue(report.describe().contains("app-one")); //$NON-NLS-1$
        assertNull(access.readApplicationId("app-one")); //$NON-NLS-1$
    }
}
