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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * fails mid-list, a write that is accepted and dropped, edits that land between the snapshot and
 * the restore - of another configuration, and of the same attribute of the same configuration -
 * a configuration that disappears, and two configurations of different types sharing one
 * display name.</p>
 *
 * <p>Every case addresses configurations by memento: the memento is what the guard keys on, and
 * the display name only ever reaches the answer.</p>
 */
public class LaunchApplicationIdsTest
{
    /** The attribute the guard minds, the one EDT strips on the reload. */
    private static final String APP_ID = LaunchConfigAccess.ATTR_APPLICATION_ID;

    /**
     * The launch configurations as an in-memory store, addressed by memento.
     * {@code writeApplicationId} touches the one attribute and nothing else, the way the Eclipse
     * adapter's saved working copy does.
     */
    private static final class FakeAccess
        implements LaunchApplicationIds.Access
    {
        /** Memento to the configuration the guard is told about. */
        final Map<String, LaunchApplicationIds.Configuration> configs = new LinkedHashMap<>();

        /** Memento to what the configuration holds. */
        final Map<String, Map<String, String>> attributes = new LinkedHashMap<>();

        /** The mementos written to, in order. */
        final List<String> writes = new ArrayList<>();

        /** Mementos whose write is refused. */
        final Set<String> unwritable = new LinkedHashSet<>();

        /** Mementos whose write is accepted and never reaches the store. */
        final Set<String> dropped = new LinkedHashSet<>();

        void add(String memento, String name, String applicationId)
        {
            configs.put(memento, new LaunchApplicationIds.Configuration(memento, name));
            Map<String, String> held = new LinkedHashMap<>();
            held.put("project", "p-" + name); //$NON-NLS-1$ //$NON-NLS-2$
            if (applicationId != null)
            {
                held.put(APP_ID, applicationId);
            }
            attributes.put(memento, held);
        }

        /** What EDT's reload does: the application id is gone from every configuration. */
        void stripApplicationIds()
        {
            for (Map<String, String> held : attributes.values())
            {
                held.remove(APP_ID);
            }
        }

        @Override
        public List<LaunchApplicationIds.Configuration> configurations()
        {
            return new ArrayList<>(configs.values());
        }

        @Override
        public String readApplicationId(String memento)
        {
            Map<String, String> held = attributes.get(memento);
            return held == null ? null : held.get(APP_ID);
        }

        @Override
        public void writeApplicationId(String memento, String applicationId)
            throws Exception
        {
            if (unwritable.contains(memento))
            {
                throw new Exception("the store refuses the write"); //$NON-NLS-1$
            }
            writes.add(memento);
            if (!dropped.contains(memento))
            {
                attributes.get(memento).put(APP_ID, applicationId);
            }
        }
    }

    @Test
    public void everyConfigurationKeepsItsIdAndTheIdlessOneStaysIdless()
    {
        FakeAccess access = new FakeAccess();
        access.add("m-one", "app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        access.add("m-two", "app-two", "app-2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        access.add("m-none", "no-application", null); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals("app-1", access.readApplicationId("m-one")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("app-2", access.readApplicationId("m-two")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a configuration that had no application id gains none", //$NON-NLS-1$
            access.attributes.get("m-none").get(APP_ID)); //$NON-NLS-1$
        assertEquals(List.of("app-one", "app-two"), report.restored); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(report.changedMeanwhile.isEmpty());
        assertTrue(report.gone.isEmpty());
        assertTrue(report.failed.isEmpty());
        assertTrue(report.lost.isEmpty());
    }

    @Test
    public void aWriteInterruptedMidListRestoresWhatWasStrippedAndNamesIt()
    {
        // The save died halfway: the reload had already stripped one configuration when the
        // caller landed on the failure path. The restore runs there too.
        FakeAccess access = new FakeAccess();
        access.add("m-one", "app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        access.add("m-two", "app-two", "app-2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        access.attributes.get("m-one").remove(APP_ID); //$NON-NLS-1$
        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals("app-1", access.readApplicationId("m-one")); //$NON-NLS-1$ //$NON-NLS-2$
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
        access.add("m-one", "app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        access.add("m-two", "app-two", "app-2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        // Between the snapshot and the restore somebody edits another configuration.
        access.attributes.get("m-two").put("clientType", "thick"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        LaunchApplicationIds.restore(access, snapshot);

        assertEquals("thick", access.attributes.get("m-two").get("clientType")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("p-app-one", access.attributes.get("m-one").get("project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("p-app-two", access.attributes.get("m-two").get("project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("restore wrote the id back and nothing else", //$NON-NLS-1$
            2, access.writes.size());
    }

    @Test
    public void aRereadAfterTheRestoreSeesTheSnapshot()
    {
        FakeAccess access = new FakeAccess();
        access.add("m-one", "app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        access.add("m-two", "app-two", "app-2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        LaunchApplicationIds.restore(access, snapshot);

        assertEquals("a fresh snapshot of the reread list is the one taken before the write", //$NON-NLS-1$
            snapshot.keySet(), LaunchApplicationIds.snapshot(access).keySet());
        Map<String, LaunchApplicationIds.SnapshotEntry> reread = LaunchApplicationIds.snapshot(access);
        for (Map.Entry<String, LaunchApplicationIds.SnapshotEntry> entry : snapshot.entrySet())
        {
            assertEquals("the id read back after the restore is the snapshotted one", //$NON-NLS-1$
                entry.getValue().applicationId, reread.get(entry.getKey()).applicationId);
        }
    }

    @Test
    public void aValueSetAfterTheSnapshotIsNotOverwrittenButNamed()
    {
        FakeAccess access = new FakeAccess();
        access.add("m-one", "app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        // The attribute reappears with another value before the restore runs: somebody's edit,
        // not the guard's loss to repair.
        access.attributes.get("m-one").put(APP_ID, "app-edited"); //$NON-NLS-1$ //$NON-NLS-2$

        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals("the later value stands", "app-edited", access.readApplicationId("m-one")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(access.writes.isEmpty());
        assertEquals(List.of("app-one"), report.changedMeanwhile); //$NON-NLS-1$
        assertTrue(report.describe().contains("app-one")); //$NON-NLS-1$
    }

    @Test
    public void aGoneConfigurationIsNamedNotRecreated()
    {
        FakeAccess access = new FakeAccess();
        access.add("m-one", "app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        access.add("m-two", "app-two", "app-2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        access.configs.remove("m-two"); //$NON-NLS-1$
        access.attributes.remove("m-two"); //$NON-NLS-1$

        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals(List.of("app-two"), report.gone); //$NON-NLS-1$
        assertFalse("the guard repairs an attribute, it does not resurrect a configuration", //$NON-NLS-1$
            access.attributes.containsKey("m-two")); //$NON-NLS-1$
        assertTrue(report.describe().contains("app-two")); //$NON-NLS-1$
    }

    @Test
    public void nothingStrippedIsQuiet()
    {
        FakeAccess access = new FakeAccess();
        access.add("m-one", "app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertTrue(report.isQuiet());
        assertEquals("", report.describe()); //$NON-NLS-1$
        assertTrue(access.writes.isEmpty());
        assertEquals(List.of("app-one"), report.kept); //$NON-NLS-1$
    }

    @Test
    public void aWriteThatIsRefusedIsNamedAsFailed()
    {
        FakeAccess access = new FakeAccess();
        access.add("m-one", "app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        access.unwritable.add("m-one"); //$NON-NLS-1$

        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals(List.of("app-one"), report.failed); //$NON-NLS-1$
        assertTrue(report.restored.isEmpty());
        assertTrue(report.describe().contains("app-one")); //$NON-NLS-1$
        assertNull(access.readApplicationId("m-one")); //$NON-NLS-1$
    }

    @Test
    public void aWriteTheStoreDropsIsNamedAsLost()
    {
        // The write returns, the value does not stay: the report says so instead of claiming a
        // restore. A late strip looks exactly like this from the guard's side.
        FakeAccess access = new FakeAccess();
        access.add("m-one", "app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        access.dropped.add("m-one"); //$NON-NLS-1$

        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals(List.of("app-one"), report.lost); //$NON-NLS-1$
        assertTrue("a write that did not stay is not reported as restored", report.restored.isEmpty()); //$NON-NLS-1$
        assertNull(access.readApplicationId("m-one")); //$NON-NLS-1$
        assertTrue(report.describe().contains("did not stay")); //$NON-NLS-1$
        assertTrue(report.describe().contains("app-one")); //$NON-NLS-1$
    }

    @Test
    public void oneNameInTwoTypesIsExcludedByMementoAndTheForeignOneIsRestored()
    {
        // Two configurations of different types carry the same display name, and the foreign one
        // comes first in the manager's list. A name-addressed guard would take the first of them
        // for both, exclude the wrong configuration and leave the deleted base's id in place.
        FakeAccess access = new FakeAccess();
        access.add("m-remote", "Run one", "app-foreign"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        access.add("m-local", "Run one", "app-deleted"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        RestoreReport report = LaunchApplicationIds.restore(access, snapshot, Set.of("m-local")); //$NON-NLS-1$

        assertEquals("the foreign binding goes back even though the name was taken", //$NON-NLS-1$
            "app-foreign", access.readApplicationId("m-remote")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("the deleted base's binding stays removed", access.readApplicationId("m-local")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("m-remote"), access.writes); //$NON-NLS-1$
        assertEquals(1, report.restored.size());
        assertEquals(1, report.excludedStripped.size());
    }

    @Test
    public void anExcludedConfigurationThatKeptItsIdIsNamedAsSuch()
    {
        // The binding outlived its application: the write did not strip this one, so the guard
        // must not report it as left unbound.
        FakeAccess access = new FakeAccess();
        access.add("m-deleted", "Deleted base run", "app-deleted"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        access.add("m-foreign", "Foreign run", "app-foreign"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        access.attributes.get("m-foreign").remove(APP_ID); //$NON-NLS-1$
        RestoreReport report = LaunchApplicationIds.restore(access, snapshot, Set.of("m-deleted")); //$NON-NLS-1$

        assertEquals(List.of("Deleted base run"), report.excludedKept); //$NON-NLS-1$
        assertTrue(report.excludedStripped.isEmpty());
        assertTrue(report.describe().contains("Deleted base run")); //$NON-NLS-1$
        assertFalse("a configuration still carrying its id is not reported as left unbound", //$NON-NLS-1$
            report.describe().contains("left without an application id")); //$NON-NLS-1$
    }

    @Test
    public void createInfobaseRestoresBothForeignApplicationIds()
    {
        FakeAccess access = new FakeAccess();
        access.add("m-one", "foreign-one", "application-one"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        access.add("m-two", "foreign-two", "application-two"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals("application-one", access.readApplicationId("m-one")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("application-two", access.readApplicationId("m-two")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("foreign-one", "foreign-two"), report.restored); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the create response has a launchApplicationIds report", report.isQuiet()); //$NON-NLS-1$
    }

    @Test
    public void deleteInfobaseLeavesItsConfigurationUnboundAndRestoresTheForeignOne()
    {
        FakeAccess access = new FakeAccess();
        access.add("m-deleted", "deleted-base", "deleted-application"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        access.add("m-foreign", "foreign-base", "foreign-application"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        access.stripApplicationIds();
        RestoreReport report = LaunchApplicationIds.restore(access, snapshot, Set.of("m-deleted")); //$NON-NLS-1$

        assertNull(access.readApplicationId("m-deleted")); //$NON-NLS-1$
        assertEquals("foreign-application", access.readApplicationId("m-foreign")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("deleted-base"), report.excludedStripped); //$NON-NLS-1$
        assertEquals(List.of("foreign-base"), report.restored); //$NON-NLS-1$
        assertTrue(report.describe().contains("deleted-base")); //$NON-NLS-1$
    }

    @Test
    public void aSnapshotRemembersTheNameAsItStoodThen()
    {
        // A configuration renamed between the snapshot and the restore is still addressed by its
        // memento, and the answer calls it what it was called when its id was taken.
        FakeAccess access = new FakeAccess();
        access.add("m-one", "app-one", "app-1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, LaunchApplicationIds.SnapshotEntry> snapshot = LaunchApplicationIds.snapshot(access);
        access.configs.put("m-one", new LaunchApplicationIds.Configuration("m-one", "renamed")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        access.stripApplicationIds();
        RestoreReport report = LaunchApplicationIds.restore(access, snapshot);

        assertEquals(List.of("app-one"), report.restored); //$NON-NLS-1$
        assertEquals("app-1", access.readApplicationId("m-one")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
