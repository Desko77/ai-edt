/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which support mode each object held, by vendor configuration.
 * <p>
 * <b>Counts cannot put anything back.</b> A merge takes the support model from the delivery -
 * measured on a stand, and not preventable through the environment's merge rules - so restoring what
 * was there means knowing which object had which mode, not how many objects had each. The census
 * that reports the damage and the snapshot that can undo it are different shapes, and only one of
 * them is a restore.
 * </p>
 * <p>
 * Deliberately free of any EDT type. The shape, the file format and the rule for telling three kinds
 * of difference apart carry the meaning, and they are provable without an environment to run in.
 * </p>
 */
public final class SupportSnapshot
{
    /** First line of the file, so a wrong file is refused rather than half-read. */
    static final String HEADER = "# AI-EDT support mode snapshot v1"; //$NON-NLS-1$

    /** Marks a snapshot that cleanup must not remove. See {@link #write(Path, boolean)}. */
    static final String PROTECTED = "# protected"; //$NON-NLS-1$

    private static final String VENDOR = "# vendor\t"; //$NON-NLS-1$

    private static final String PROJECT = "# project\t"; //$NON-NLS-1$

    /** What one vendor configuration held. */
    public static final class Parent
    {
        /**
         * The vendor configuration's identity, which is what before and after are matched on.
         * <p>
         * Not the version: an update is expected to change the version and keep the identity.
         * Matching on version would find no parent at all after exactly the update this exists for.
         * </p>
         */
        public final String id;

        /** The vendor configuration's name, for the report. */
        public final String name;

        /** The version at the time of the snapshot, so a report can name what it moved from. */
        public final String version;

        /** Mode by object identity. A null value means the object had no mode recorded. */
        public final Map<UUID, String> modes = new LinkedHashMap<>();

        /**
         * Name by object identity, for whatever the walk could name.
         * <p>
         * The file used to carry identities and nothing else, and this file is the material of a
         * restore - what somebody opens when an update has gone wrong. Rows of bare identities tell
         * them nothing about which objects are involved.
         * </p>
         * <p>
         * <b>Written, never read back into a decision.</b> A restore matches on identity because
         * names move between releases and identities do not; the column exists for the person, not
         * for the code.
         * </p>
         */
        public final Map<UUID, String> names = new LinkedHashMap<>();

        /**
         * Creates a record of one vendor configuration.
         *
         * @param id its identity.
         * @param name its name.
         * @param version its version at the time of the snapshot.
         */
        public Parent(String id, String name, String version)
        {
            this.id = id;
            this.name = name;
            this.version = version;
        }
    }

    /** Why nothing could be taken. Present only when the answer is a refusal. */
    public String cannotTell;

    /** The project the snapshot was taken from. */
    public String projectName;

    /**
     * Where this snapshot was read from, when it came off disk.
     * <p>
     * Kept so that anything written on the strength of it lands beside it. A record of what a
     * restore replaced is only useful if it can be found next to the file that caused the restore.
     * </p>
     */
    public String sourcePath;

    /** One entry per vendor configuration, in the order the support model lists them. */
    public final List<Parent> parents = new ArrayList<>();

    /**
     * How many of the entries here name something the configuration no longer has.
     * <p>
     * Counted because they cannot be restored: an object that is gone takes no mode. Saying so as a
     * number is the difference between a restore that is complete and one that quietly was not.
     * </p>
     * <p>
     * <b>Only meaningful while {@link #indexComplete} holds.</b> An entry the walk could not name
     * is indistinguishable from one whose object was deleted, so when the walk was not whole every
     * such entry is counted in {@link #unclassified} instead and this stays at zero. Reporting
     * them here would state a deletion nobody established - and for a while it did, on a
     * configuration that had every one of those objects.
     * </p>
     */
    public int unresolved;

    /**
     * How many entries could not be classified because the walk of the model was not whole.
     * <p>
     * Distinct from {@link #unresolved} on purpose: that one says the object is gone, this one says
     * we do not know. The reader of a restore acts differently on each - the first is a fact about
     * the configuration, the second is a fault in the reading of it.
     * </p>
     */
    public int unclassified;

    /**
     * How many entries the support registry holds without an identity of their own.
     * <p>
     * The environment allows an item with no user id, and such an entry cannot be written down or
     * put back: there is nothing to set a mode on. It is counted rather than skipped so the totals
     * add up, and it blocks an automatic merge, because a snapshot that silently omits an entry
     * offers a way back it does not have.
     * </p>
     */
    public int withoutAnIdentifier;

    /**
     * Whether the walk that produced the names saw the whole model.
     * <p>
     * False when the configuration would not load, when a whole kind of object refused to list, or
     * when an object would not yield its contents. Any of the three makes an absent name mean
     * nothing in particular, which is why the classification above turns on this.
     * </p>
     */
    public boolean indexComplete = true;

    /** How many objects refused to yield their contents. Diagnostic, not a count of entries. */
    public int ownersThatWouldNotOpen;

    /**
     * How many modes the snapshot holds in total.
     *
     * @return the count across every vendor configuration
     */
    public int entries()
    {
        int total = 0;
        for (Parent parent : parents)
        {
            total += parent.modes.size();
        }
        return total;
    }

    /**
     * Says whether there is anything to restore.
     *
     * @return <code>true</code> when the snapshot holds no modes
     */
    public boolean isEmpty()
    {
        return entries() == 0;
    }

    /**
     * Finds a vendor configuration by the identity before and after are matched on.
     *
     * @param id the identity.
     * @return the record, or <code>null</code> when this snapshot has none
     */
    public Parent parentById(String id)
    {
        for (Parent parent : parents)
        {
            if (parent.id != null && parent.id.equals(id))
            {
                return parent;
            }
        }
        return null;
    }

    /**
     * How a later state differs from an earlier one.
     * <p>
     * Three groups, because they mean different things and only one of them is damage. An object
     * that survived the update and lost its mode was overwritten - that is work no longer marked as
     * ours. An object the new delivery brought has no mode in the snapshot and takes the rules'
     * default, which is not damage. An object the update removed cannot take a mode at all.
     * </p>
     */
    public static final class Drift
    {
        /** Objects that survived and whose mode changed: identity, then what it was and is. */
        public final List<String> changed = new ArrayList<>();

        /** Vendor configurations present before and after, by identity. */
        public final List<String> parentsMatched = new ArrayList<>();

        /** Vendor configurations the snapshot knew and the project no longer lists. */
        public final List<String> parentsGone = new ArrayList<>();

        /** Vendor configurations the project lists and the snapshot did not know. */
        public final List<String> parentsNew = new ArrayList<>();

        /** Objects the newer state has and the snapshot did not. */
        public int arrived;

        /** Objects the snapshot had and the newer state does not. */
        public int gone;

        /**
         * Says whether every surviving object still holds the mode it had.
         * <p>
         * Deliberately narrow, and three tests spell out why: an object the delivery brought has no
         * mode in the snapshot to lose, an object the update removed cannot hold one, and a vendor
         * no longer listed leaves nothing to compare against. Counting any of those as damage
         * inflates the figure a person uses to judge what an update cost.
         * </p>
         * <p>
         * So this is not the question "is there anything to put back" - see {@link #vendorReplaced}
         * for the case where there is nothing to put it back ONTO.
         * </p>
         *
         * @return <code>true</code> when nothing that survived changed
         */
        public boolean isClean()
        {
            return changed.isEmpty();
        }

        /**
         * Whether the project is on support from a different vendor configuration than the snapshot.
         * <p>
         * A mode belongs to a vendor. When the vendor is gone the modes cannot be put back onto it,
         * and saying so is the whole answer - a count of what was restored would be zero either way,
         * and zero reads as "nothing needed doing".
         * </p>
         *
         * @return <code>true</code> when no vendor the snapshot knew is still there
         */
        public boolean vendorReplaced()
        {
            return parentsMatched.isEmpty() && !parentsGone.isEmpty();
        }
    }

    /**
     * Says how a later snapshot differs from an earlier one.
     *
     * @param before the earlier snapshot.
     * @param after the later snapshot.
     * @return the three groups of difference
     */
    public static Drift compare(SupportSnapshot before, SupportSnapshot after)
    {
        Drift drift = new Drift();
        for (Parent was : before.parents)
        {
            Parent is = after.parentById(was.id);
            if (is == null)
            {
                // The vendor configuration itself is no longer listed. Every object it held counts
                // as gone rather than as changed: there is no mode to compare against.
                drift.parentsGone.add(describe(was));
                drift.gone += was.modes.size();
                continue;
            }
            drift.parentsMatched.add(was.version == null || was.version.equals(is.version)
                ? describe(is)
                : describe(is) + " (was " + was.version + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            for (Map.Entry<UUID, String> mode : was.modes.entrySet())
            {
                if (!is.modes.containsKey(mode.getKey()))
                {
                    drift.gone++;
                    continue;
                }
                String now = is.modes.get(mode.getKey());
                if (mode.getValue() == null ? now != null : !mode.getValue().equals(now))
                {
                    drift.changed.add(mode.getKey() + ": " + mode.getValue() + " -> " + now); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        for (Parent is : after.parents)
        {
            Parent was = before.parentById(is.id);
            if (was == null)
            {
                drift.parentsNew.add(describe(is));
                drift.arrived += is.modes.size();
                continue;
            }
            for (UUID identity : is.modes.keySet())
            {
                if (!was.modes.containsKey(identity))
                {
                    drift.arrived++;
                }
            }
        }
        return drift;
    }

    /**
     * Names a vendor configuration the way a person reading the report would.
     *
     * @param parent the record.
     * @return its name and version, or its identity when it has no name
     */
    private static String describe(Parent parent)
    {
        return parent.name == null ? String.valueOf(parent.id)
            : parent.name + (parent.version == null ? "" : " " + parent.version); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Writes the snapshot where a person can read it before it is applied.
     * <p>
     * Tab separated text rather than the environment's own format, because this file is meant to be
     * looked at: an update is the moment to see which objects were marked as ours, and a person
     * deciding whether to put them back needs to be able to search and diff the list.
     * </p>
     *
     * @param path where to write.
     * @throws IOException when the file cannot be written
     */
    public void write(Path path) throws IOException
    {
        write(path, false);
    }

    /**
     * Writes the snapshot, optionally marked as the only way back.
     * <p>
     * The mark goes into the file rather than beside it, because the file is published by moving it
     * into place: a mark written afterwards would leave a window in which a crash produces a
     * snapshot the cleanup takes for an ordinary one and deletes. Written inside, it is there the
     * instant the snapshot exists.
     * </p>
     * <p>
     * It is a comment line, which every reader of this format already skips, so a snapshot marked
     * by this build still restores through an older one.
     * </p>
     *
     * @param path where to write.
     * @param protectedSnapshot whether the snapshot must survive cleanup until someone says the
     *            merge it belongs to has been dealt with.
     * @throws IOException when the file cannot be written
     */
    public void write(Path path, boolean protectedSnapshot) throws IOException
    {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        if (protectedSnapshot)
        {
            lines.add(PROTECTED);
        }
        lines.add(PROJECT + projectName);
        lines.add("# objects no longer in the configuration\t" + unresolved); //$NON-NLS-1$
        for (Parent parent : parents)
        {
            lines.add(VENDOR + parent.id + "\t" + parent.name + "\t" + parent.version); //$NON-NLS-1$ //$NON-NLS-2$
            for (Map.Entry<UUID, String> mode : parent.modes.entrySet())
            {
                // The name goes on as a fourth column, which every reader of the v1 format already
                // tolerates: rows are split with no limit and read by position, parts[0] through
                // parts[2], so a column past those is ignored rather than fatal. A snapshot taken
                // by this build therefore still restores through an older one, and the version in
                // the header stays where it is.
                String named = parent.names.get(mode.getKey());
                lines.add(parent.id + "\t" + mode.getKey() + "\t" //$NON-NLS-1$ //$NON-NLS-2$
                    + (mode.getValue() == null ? "" : mode.getValue()) //$NON-NLS-1$
                    + "\t" + (named == null ? "" : named)); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        if (path.getParent() != null)
        {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    /**
     * Reads a snapshot back.
     *
     * @param path the file.
     * @return the snapshot, or one carrying a refusal when the file is not one
     * @throws IOException when the file cannot be read
     */
    public static SupportSnapshot read(Path path) throws IOException
    {
        SupportSnapshot snapshot = new SupportSnapshot();
        snapshot.sourcePath = path.toString();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).startsWith(HEADER))
        {
            // Refused rather than parsed leniently: a restore driven by the wrong file would write
            // modes nobody chose, and there is no undo for that beyond another snapshot.
            snapshot.cannotTell = path + " does not begin with \"" + HEADER //$NON-NLS-1$
                + "\", so it is not a support mode snapshot"; //$NON-NLS-1$
            return snapshot;
        }
        Map<String, Parent> byId = new LinkedHashMap<>();
        for (String line : lines)
        {
            if (line.startsWith(VENDOR))
            {
                String[] parts = line.split("\t", -1); //$NON-NLS-1$
                if (parts.length >= 4)
                {
                    Parent parent =
                        new Parent(parts[1], emptyToNull(parts[2]), emptyToNull(parts[3]));
                    byId.put(parent.id, parent);
                    snapshot.parents.add(parent);
                }
                continue;
            }
            if (line.startsWith(PROJECT))
            {
                snapshot.projectName = emptyToNull(line.substring(PROJECT.length()));
                continue;
            }
            if (line.startsWith("#") || line.trim().isEmpty()) //$NON-NLS-1$
            {
                continue;
            }
            String[] parts = line.split("\t", -1); //$NON-NLS-1$
            if (parts.length < 3)
            {
                continue;
            }
            Parent parent = byId.get(parts[0]);
            if (parent == null)
            {
                parent = new Parent(parts[0], null, null);
                byId.put(parts[0], parent);
                snapshot.parents.add(parent);
            }
            try
            {
                UUID identity = UUID.fromString(parts[1]);
                parent.modes.put(identity, emptyToNull(parts[2]));
                // Absent from a file written before the column existed, and that is not a defect -
                // the restore never consults it. It is carried back only so a report of what a
                // restore is about to do can name the objects.
                if (parts.length >= 4 && emptyToNull(parts[3]) != null)
                {
                    parent.names.put(identity, parts[3]);
                }
            }
            catch (IllegalArgumentException notAnIdentity)
            {
                // One unreadable line does not condemn the file, but it must not be silent either:
                // a restore that skipped rows without saying so would report a complete run.
                snapshot.unresolved++;
            }
        }
        return snapshot;
    }

    /**
     * Treats an absent value and an empty one as the same thing.
     *
     * @param value the text read.
     * @return the trimmed value, or <code>null</code> when there was none
     */
    private static String emptyToNull(String value)
    {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
