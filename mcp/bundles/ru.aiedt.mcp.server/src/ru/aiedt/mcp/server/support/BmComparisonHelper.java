/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessDescriptor;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessSettings;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus;
import com._1c.g5.v8.dt.compare.core.ComparisonScope;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.core.SerializableMergeSettings;
import com._1c.g5.v8.dt.compare.datasource.FileSystemComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.datasource.V8ProjectComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.matching.MatchingStrategy;
import com._1c.g5.v8.dt.compare.merge.MergeProblem;
import com._1c.g5.v8.dt.compare.model.ComparisonFlags;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.MergeRule;
import com._1c.g5.v8.dt.compare.model.MergeSettings;
import com._1c.g5.v8.dt.compare.model.ObjectsTriple;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;
import com._1c.g5.v8.dt.compare.settings.model.IMergeSettingsModel;
import com._1c.g5.v8.dt.compare.settings.model.RestoredMergeSettings;
import com._1c.g5.wiring.ServiceAccess;

import ru.aiedt.mcp.server.Activator;

/**
 * Reads a comparison of two or three configurations and reports what it found.
 * <p>
 * <b>Merging is possible here and never happens by itself.</b> Reading is the default and needs no
 * argument; a merge needs a named intent, decisions to apply, and - past a problem the environment
 * called blocking - a second, differently worded request. A merge writes into a configuration and a
 * wrong one is not undone by a button, so the cost of asking twice is nothing beside the cost of
 * asking once by accident.
 * </p>
 * <p>
 * Three sides are what an update on support actually is: our reworked configuration, the new
 * delivery and the old delivery they both came from. EDT models exactly that -
 * {@link ComparisonSide} is {@code MAIN} / {@code OTHER} / {@code COMMON_ANCESTOR} - so nothing here
 * compares anything itself. It arranges the sides, waits, and reads the tree the environment built.
 * </p>
 */
public final class BmComparisonHelper
{
    /**
     * How long to wait for a comparison before giving up on it and calling it off.
     * <p>
     * Ninety seconds, not thirty minutes. The first live run waited half an hour and in doing so
     * held a whole EDT session hostage: the comparison could not proceed, and the session would not
     * shut down while it was pending, so every attempt to install a build was refused for as long
     * as the wait lasted. A slow answer is a nuisance; an environment that cannot be closed is a
     * different order of problem, and the tool has no business creating one. Anything longer than
     * this goes through the Pending flow, where waiting costs nobody a session.
     * </p>
     */
    private static final long WAIT_LIMIT_MS = 90L * 1000L;

    /**
     * How long to wait for a started merge to end.
     * <p>
     * Longer than the comparison wait, and for the opposite reason. The comparison wait is short
     * because a pending comparison holds a session open for no gain; a merge that has started is
     * writing, and the useful thing to do is see it through and report what it did. Giving up on
     * one does not stop it - nothing here calls it off - it only makes the answer less certain, so
     * the limit exists to bound the call rather than to protect the environment.
     * </p>
     */
    private static final long MERGE_WAIT_LIMIT_MS = 10L * 60L * 1000L;

    /**
     * How long a merge may sit at "validation finished" before that is taken as where it stopped.
     * <p>
     * That state is ambiguous: a merge on its way to writing passes through it, and a merge the
     * environment refused ends on it. Blocking problems separate the two in every case observed,
     * and this exists only for the case where they do not - so that such a merge is reported as
     * having stopped, rather than as having timed out ten minutes later.
     * </p>
     */
    private static final long VALIDATION_SETTLE_MS = 30L * 1000L;

    /** How often to ask the manager what the status is. */
    private static final long POLL_MS = 500L;

    /**
     * How many changed objects one page carries by default, and the most it will carry.
     * <p>
     * This used to be a ceiling with nothing behind it: a real update runs to tens of thousands of
     * changed objects and the answer named five hundred of them, chosen by walk order, with no way
     * to ask for the rest. Protecting a customisation means naming every object that carries one,
     * so a ceiling here was a ceiling on the whole task.
     * </p>
     */
    private static final int PAGE_LIMIT = 500;

    /**
     * What a caller asked to happen after the comparison has been read.
     * <p>
     * Three states rather than a boolean, because "merge" and "merge even though the environment
     * objects" are different decisions and must not share a flag. A flag that quietly means both
     * is how an override becomes the default.
     * </p>
     */
    public enum Intent
    {
        /** Read, report, and change nothing. The only value that needs no argument. */
        REPORT,

        /** Apply the decisions, unless the environment raises a blocking problem. */
        MERGE,

        /** Apply them even then. Separate on purpose: the environment named those problems. */
        MERGE_IGNORING_PROBLEMS
    }

    /**
     * Which changed objects a caller wants named, and which page of them.
     * <p>
     * The counts are always over everything: filtering narrows what is listed, never what is
     * counted. A census that shrank with the page would understate the update, and understating it
     * is the one thing these numbers must not do.
     * </p>
     */
    public static final class Page
    {
        /** Report only objects with this attribution; every attribution when null. */
        public String changedBy;

        /** Report only objects of this metadata type; every type when null. */
        public String type;

        /** Report only objects present on one side; both kinds when null. */
        public Boolean oneSided;

        /** Report only objects the environment says must take part in a merge. */
        public boolean mustBeMergedOnly;

        /** How many matching objects to skip. */
        public int offset;

        /** How many to name, capped at {@link #PAGE_LIMIT}. */
        public int limit = PAGE_LIMIT;

        /**
         * Decides whether one change belongs in the listing.
         *
         * @param change the change to test.
         * @return <code>true</code> when it passes every stated filter
         */
        boolean wants(Change change)
        {
            if (changedBy != null && !changedBy.equalsIgnoreCase(change.changedBy))
            {
                return false;
            }
            if (oneSided != null && oneSided.booleanValue() != change.oneSided)
            {
                return false;
            }
            if (mustBeMergedOnly && !change.mustBeMerged)
            {
                return false;
            }
            return type == null || isOfType(change, type);
        }

        /**
         * Decides whether a change names an object of a metadata type.
         * <p>
         * Matched on the name the comparison gives the object, which is qualified - Catalog.Name -
         * so the type is the part before the first dot. Any side may carry the name: an object the
         * vendor added has none on ours.
         * </p>
         *
         * @param change the change.
         * @param wanted the type asked for.
         * @return <code>true</code> when any side names it as that type
         */
        private static boolean isOfType(Change change, String wanted)
        {
            return startsWithType(change.main, wanted) || startsWithType(change.other, wanted)
                || startsWithType(change.ancestor, wanted);
        }

        /**
         * Decides whether a qualified name belongs to a type.
         *
         * @param name the name; may be <code>null</code>.
         * @param wanted the type.
         * @return <code>true</code> when the name is that type's
         */
        private static boolean startsWithType(String name, String wanted)
        {
            if (name == null)
            {
                return false;
            }
            int dot = name.indexOf('.');
            return dot > 0 && name.substring(0, dot).equalsIgnoreCase(wanted);
        }
    }

    /** One object that differs between the sides, or exists on only one of them. */
    public static final class Change
    {
        /** Its identity on our side; empty when it is not there. */
        public String main;

        /** Its identity on the delivery being compared against. */
        public String other;

        /** Its identity on the delivery both came from, when there are three sides. */
        public String ancestor;

        /** True when it exists on one side only - added by one, or removed by the other. */
        public boolean oneSided;

        /** Which side has it, when only one does. */
        public String presentOn;

        /** Why it could not be named, when it could not. */
        public String note;

        /** What was decided for it, when the caller decided anything. */
        public String decision;

        /**
         * Which side made the change: OURS, VENDOR, BOTH, or UNKNOWN.
         * <p>
         * The question three sides exist to answer. Without the common ancestor it cannot be
         * answered at all, and the value is UNKNOWN rather than a guess: on a reworked
         * configuration almost everything differs from a new delivery, and which side moved it is
         * the whole of what a person needs.
         * </p>
         * <p>
         * BOTH is the expensive case - both the vendor and we changed the same object - and it is
         * the queue somebody has to work through by hand.
         * </p>
         */
        public String changedBy;

        /** The rule the environment itself proposes for this node, when it proposes one. */
        public String recommendedRule;

        /** True when the environment says this node has to take part in a merge. */
        public boolean mustBeMerged;

        /** True when the environment says this node can take part in one. */
        public boolean canBeMerged;

        /** The node this change belongs to, for applying a decision. Not reported. */
        transient long nodeId;
    }

    /** What a caller may decide about one object, in the environment's own vocabulary. */
    public static final class Decision
    {
        /** The object, named as the comparison names it on either side. */
        public final String object;

        /** One of the environment's merge rules. */
        public final String rule;

        public Decision(String object, String rule)
        {
            this.object = object;
            this.rule = rule;
        }
    }

    /** What a comparison produced. */
    public static final class Outcome
    {
        /** True when the comparison ran to completion. */
        public boolean completed;

        /** True when three sides took part rather than two. */
        public boolean threeWay;

        /** What the delivery being compared against turned out to be. */
        public String otherIs;

        /** What the delivery both sides came from turned out to be. */
        public String ancestorIs;

        /** What the project's own support registry says it descends from. */
        public String projectDescendsFrom;

        /**
         * What did not line up between the sides, when the caller chose to compare anyway.
         * <p>
         * Empty on an ordinary run. Non-empty means the caller passed
         * {@code ignoreOriginMismatch}, and every attribution in this answer is suspect - so the
         * reason travels with the answer rather than staying in the refusal that was overridden.
         * </p>
         */
        public final List<String> originMismatches = new ArrayList<>();

        /** The status the process ended on, named as the environment names it. */
        public String status;

        /** Nodes in the tree, counted by walking it. */
        public int nodes;

        /** Nodes the environment marked as differing between our side and the other one. */
        public int differing;

        /** Nodes present on one side only. */
        public int oneSided;

        /**
         * Metadata objects that moved, counted whole.
         * <p>
         * Separate from {@link #nodes} and {@link #differing} on purpose, and named so the two
         * cannot be confused. Those count every node in the tree: one changed object answers to
         * many differing child nodes - its properties, its module sections, its support settings -
         * so a category census over objects will never add up to a node count. Reporting both
         * under one word is how a correct implementation looks broken.
         * </p>
         */
        public int objectsChanged;

        /** Of those, the ones only we changed. */
        public int objectsChangedByUs;

        /** Of those, the ones only the vendor changed. */
        public int objectsChangedByVendor;

        /** Of those, the ones both sides changed - the queue for a person. */
        public int objectsChangedByBoth;

        /** Of those, the ones no ancestor was available to attribute. */
        public int objectsChangedUnattributed;

        /**
         * The metadata objects that moved, named - one page of them.
         * <p>
         * The counts above stay whole whatever the page holds, so a page never makes the update
         * look smaller than it is.
         * </p>
         */
        public final List<Change> changed = new ArrayList<>();

        /** How many objects matched the filter, across every page. */
        public int changedMatching;

        /** Where this page starts inside the matching set. */
        public int changedOffset;

        /** True when objects beyond this page matched and were not named. */
        public boolean moreChanged;

        /** Problems the environment raised, blocking ones first. */
        public final List<String> problems = new ArrayList<>();

        /** How many of those problems block a merge. */
        public int blockingProblems;

        /**
         * Why the problem list is empty, when it is empty because it was not read.
         * <p>
         * An empty list and an unread one are different answers, and only one of them means
         * "nothing stands in the way".
         * </p>
         */
        public String problemsNote;

        /** How many decisions were recorded. */
        public int decided;

        /**
         * Decisions the environment would not take.
         * <p>
         * Counted apart from {@link #decided}, because the call that records a decision returns a
         * boolean and the first version of this code discarded it. A count of calls presented as a
         * count of applied decisions is the defect this project keeps paying for; here it would
         * mean reporting a merge as decided when the environment decided nothing.
         * </p>
         */
        public int decisionsRefused;

        /**
         * Support modes across the configuration before a merge, counted by mode.
         * <p>
         * A measurement, because the promise could not be kept. Keeping vendor support settings
         * out of a merge was attempted through the environment's own merge rules and does not
         * work: with the configuration-level support node and 11537 of 11539 per-object ones all
         * set to DO_NOT_MERGE, the support model still came across from the delivery whole. That
         * was measured on a live configuration, not argued, so the tool stops claiming protection
         * and starts counting what happened.
         * </p>
         * <p>
         * Empty when the support subsystem is absent or the project is on nobody's support.
         * </p>
         */
        public final java.util.Map<String, Integer> supportModesBefore = new java.util.TreeMap<>();

        /** The same census after the merge. */
        public final java.util.Map<String, Integer> supportModesAfter = new java.util.TreeMap<>();

        /** True when the merge changed what objects are allowed to have done to them. */
        public boolean supportModesChanged;

        /** Where the decisions were written, when they were. */
        public String decisionsWrittenTo;

        /**
         * Why the decisions are not what the caller asked for, when they are not.
         * <p>
         * A settings file quietly missing half of somebody's decisions is worse than no file,
         * so anything that stopped one being recorded is said rather than implied by a count.
         * </p>
         */
        public String decisionsNote;

        /** True when a merge ran and the environment reported it as successful. */
        public boolean merged;

        /** What the merge answered, in the environment's own words. */
        public String mergeStatus;

        /** Why no merge ran, when one was asked for and did not happen. */
        public String mergeRefused;

        /** Where the decisions applied to this comparison were read from, when they were. */
        public String decisionsReadFrom;

        /**
         * True when that file actually carried decisions.
         * <p>
         * Separate from {@link #decisionsReadFrom} because a file that parses and decides nothing
         * is the failure mode worth naming: the caller believes the work somebody did by hand is
         * about to be carried out, and it is not.
         * </p>
         */
        public boolean decisionsRestored;

        /**
         * Errors standing against the merged objects afterwards; -1 when it could not be asked.
         * <p>
         * A merge that leaves the configuration broken is the ordinary case, not the exception -
         * taking the vendor's version of one object routinely breaks whatever referred to the old
         * one. An answer that stops at "merged" leaves the caller to discover that later, so the
         * objects are revalidated here and the count comes back with the merge.
         * </p>
         */
        public long errorsAfterMerge = -1L;

        /** True when the merged objects were revalidated before that count was taken. */
        public boolean revalidatedAfterMerge;

        /** Why nothing could be said, when nothing could. */
        public String cannotTell;

        /**
         * What the environment thought of the directories it was handed, appended to a failure.
         * <p>
         * A comparison source on disk is expected to be a DT project, and the environment can say
         * whether a directory looks like one. When a comparison dies, that answer is usually the
         * whole explanation - so it travels with the failure instead of being left for whoever
         * reads the workspace log.
         * </p>
         */
        String sourceNote = ""; //$NON-NLS-1$
    }

    private BmComparisonHelper()
    {
    }

    /**
     * Compares a workspace project against one or two exports on disk.
     *
     * @param mainProjectName the project that plays our side; must be open in the workspace.
     * @param otherPath a directory holding the configuration to compare against.
     * @param ancestorPath a directory holding the common ancestor, or <code>null</code> for a
     *            two-sided comparison.
     * @return what the comparison found, or the reason it could not run
     */
    public static Outcome compare(String mainProjectName, String otherPath, String ancestorPath,
        List<Decision> decisions, String decisionsPath, String decisionsFrom, Intent intent,
        boolean ignoreOriginMismatch, Page page)
    {
        Outcome outcome = new Outcome();
        if (page == null)
        {
            page = new Page();
        }
        page.limit = page.limit <= 0 ? PAGE_LIMIT : Math.min(page.limit, PAGE_LIMIT);
        page.offset = Math.max(0, page.offset);
        outcome.changedOffset = page.offset;
        // Before anything is compared, and not after: an ancestor from the wrong configuration
        // inverts every attribution in the answer, and nothing downstream would notice.
        OriginCheck.Verdict origin = OriginCheck.check(mainProjectName, otherPath, ancestorPath);
        outcome.otherIs = origin.other == null ? null : origin.other.toString();
        outcome.ancestorIs = origin.ancestor == null ? null : origin.ancestor.toString();
        outcome.projectDescendsFrom = origin.projectDescendsFrom;
        if (!origin.agrees())
        {
            if (!ignoreOriginMismatch)
            {
                outcome.cannotTell = OriginCheck.refusal(origin);
                return outcome;
            }
            outcome.originMismatches.addAll(origin.mismatches);
        }
        if (mainProjectName == null || mainProjectName.isEmpty() || otherPath == null
            || otherPath.isEmpty())
        {
            outcome.cannotTell = "mainProjectName and otherPath are required"; //$NON-NLS-1$
            return outcome;
        }
        try
        {
            IComparisonManager manager = ServiceAccess.get(IComparisonManager.class);
            if (manager == null)
            {
                outcome.cannotTell = "this EDT install offers no comparison service"; //$NON-NLS-1$
                return outcome;
            }
            IComparisonDataSourceDescriptor other = onDisk(otherPath, outcome);
            if (other == null)
            {
                return outcome;
            }
            IComparisonDataSourceDescriptor ancestor = null;
            if (ancestorPath != null && !ancestorPath.isEmpty())
            {
                ancestor = onDisk(ancestorPath, outcome);
                if (ancestor == null)
                {
                    return outcome;
                }
            }
            IComparisonDataSourceDescriptor main = ourSide(mainProjectName, outcome);
            if (main == null)
            {
                return outcome;
            }

            ComparisonProcessHandle handle = ancestor == null
                ? new ComparisonProcessHandle(main, other, ComparisonScope.EMPTY_SCOPE)
                : new ComparisonProcessHandle(main, other, ancestor, ComparisonScope.EMPTY_SCOPE);
            outcome.threeWay = handle.isThreeWay();

            ComparisonProcessSettings settings = settingsFor(manager, handle, decisionsFrom,
                outcome);
            if (settings == null)
            {
                return outcome;
            }
            CompareMergeProcessBatch batch =
                new CompareMergeProcessBatch(new CompareMergeProcessDescriptor(handle, settings));

            manager.startComparison(batch);
            awaitFinish(manager, handle, batch, outcome);
            if (!outcome.completed)
            {
                return outcome;
            }
            read(manager, handle, outcome, page);
            decide(manager, handle, decisions, decisionsPath, outcome);
            // Measured either side of the merge. The merge cannot be stopped from touching
            // support settings - that was tried and does not work - so the honest thing left is
            // to say what it did to them.
            censusSupport(mainProjectName, outcome.supportModesBefore);
            merge(manager, handle, batch, intent, outcome);
            censusSupport(mainProjectName, outcome.supportModesAfter);
            outcome.supportModesChanged =
                !outcome.supportModesBefore.equals(outcome.supportModesAfter);
            reportProjectState(mainProjectName, decisions, outcome);
            // Read, decided, possibly merged - then let go. A finished comparison stays registered
            // with the virtual project contexts it opened for the sources on disk, and nothing
            // else will ever close it: the handle lives only inside this call. One call is
            // harmless, a working day of them is an environment quietly filling up with
            // comparisons nobody can name - and the day this tool learned that a pending
            // comparison keeps a session from shutting down was expensive enough not to leave the
            // successful ones lying about too.
            //
            // It is also why deciding and merging happen in the same call: keeping the session
            // alive between calls is what would have to be designed for, and this is the shape
            // that needs no such thing.
            release(manager, handle);
            return outcome;
        }
        catch (NoClassDefFoundError absent)
        {
            // The comparison packages are imported optionally, so an install without them leaves
            // the rest of the plugin working and only this answer unavailable.
            outcome.cannotTell = "the comparison machinery is not present in this EDT: " //$NON-NLS-1$
                + absent.getMessage();
            return outcome;
        }
        catch (Exception e)
        {
            // Named by type, not only by message. The first live run answered "could not be run:
            // null" - an NPE carries no message, so the report said nothing at all and the reason
            // had to be hunted in the workspace log. A failure that cannot say what it was is the
            // same defect as a success that did not happen.
            outcome.cannotTell = "the comparison could not be run: " + describe(e) + outcome.sourceNote; //$NON-NLS-1$
            Activator.logError("Comparison failed: " + describe(e), e); //$NON-NLS-1$
            return outcome;
        }
    }

    /**
     * Builds the settings the comparison runs under, restoring saved decisions when asked.
     * <p>
     * This closes the circle the settings file opened. Writing decisions out is only half of
     * handing work to a person: they open the file in EDT, decide the hard objects by eye, save,
     * and the decisions then have to come back. The environment restores both halves of that file
     * - the rules and the correspondences somebody established by hand between objects that do not
     * match by uuid or name - and losing the second half would silently discard the most expensive
     * part of their work.
     * </p>
     *
     * @param manager the comparison service.
     * @param handle the process, already built.
     * @param decisionsFrom path to a saved settings file, or <code>null</code> for none.
     * @param outcome the answer being built.
     * @return the settings, or <code>null</code> when the file was named and could not be read
     */
    private static ComparisonProcessSettings settingsFor(IComparisonManager manager,
        ComparisonProcessHandle handle, String decisionsFrom, Outcome outcome)
    {
        if (decisionsFrom == null || decisionsFrom.trim().isEmpty())
        {
            return new ComparisonProcessSettings(MatchingStrategy.UUID_THEN_NAME,
                Collections.emptyList(), new NoMergeSettings());
        }
        String path = decisionsFrom.trim();
        if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) //$NON-NLS-1$
        {
            outcome.cannotTell = "decisionsFrom must name a .zip file - that is the format the " //$NON-NLS-1$
                + "environment writes and the only one it reads: " + path; //$NON-NLS-1$
            return null;
        }
        try
        {
            RestoredMergeSettings restored = manager.deserializeMergeSettings(handle, path);
            if (restored == null)
            {
                outcome.cannotTell = "the environment read " + path + " and produced no settings"; //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
            List<ObjectsTriple<String>> correspondences =
                restored.getComparedObjectsCorrespondences();
            IMergeSettingsModel restoredModel = restored.getMergeSettingsModel();
            ComparisonProcessSettings settings =
                new ComparisonProcessSettings(MatchingStrategy.UUID_THEN_NAME,
                    correspondences == null ? Collections.emptyList() : correspondences,
                    restoredModel == null ? new NoMergeSettings() : restoredModel);
            settings.setRestoredMergeSettings(restored);
            outcome.decisionsReadFrom = path;
            outcome.decisionsRestored = restoredModel != null && !restoredModel.isEmpty();
            if (!outcome.decisionsRestored)
            {
                outcome.decisionsNote = path + " was read and carries no decisions, so nothing " //$NON-NLS-1$
                    + "came from it"; //$NON-NLS-1$
            }
            return settings;
        }
        catch (Exception cannotRead)
        {
            // Named as a failure to read that file, not as a comparison that could not run: the
            // caller pointed at something, and what they need to know is what was wrong with it.
            outcome.cannotTell = "the decisions in " + path + " could not be read: " //$NON-NLS-1$ //$NON-NLS-2$
                + describe(cannotRead);
            return null;
        }
    }

    /**
     * Says what state the project is in after a merge has written to it.
     * <p>
     * Only after a merge, and only for the objects it touched. A merge that succeeds and leaves the
     * configuration broken is the ordinary case rather than the exception - taking the other side's
     * version of one object routinely breaks whatever referred to the old one - so an answer that
     * stops at "merged" is true and useless. The objects are revalidated first, because a count
     * taken off stale markers describes the configuration as it was before the merge.
     * </p>
     *
     * @param projectName the project that was merged into.
     * @param decisions what the caller decided, which names the objects that could have changed.
     * @param outcome the answer being built.
     */
    private static void reportProjectState(String projectName, List<Decision> decisions,
        Outcome outcome)
    {
        if (!outcome.merged)
        {
            return;
        }
        List<String> objects = new ArrayList<>();
        if (decisions != null)
        {
            for (Decision decision : decisions)
            {
                if (decision.object != null && !decision.object.isEmpty())
                {
                    objects.add(decision.object);
                }
            }
        }
        if (objects.isEmpty())
        {
            // A merge driven from a settings file passes no decisions here, and skipping the check
            // for exactly those merges would leave the ones somebody prepared by hand - the larger,
            // riskier ones - as the only merges whose result nobody looks at. The objects the
            // comparison found are the only ones that can have moved.
            for (Change change : outcome.changed)
            {
                String named = change.main != null && !change.main.isEmpty() ? change.main
                    : change.other;
                if (named != null && !named.isEmpty())
                {
                    objects.add(named);
                }
            }
        }
        if (objects.isEmpty())
        {
            return;
        }
        try
        {
            ru.aiedt.mcp.server.toolkit.ops.ObjectsRevalidator.revalidateObjects(projectName,
                objects);
            outcome.revalidatedAfterMerge = true;
        }
        catch (Exception couldNotRevalidate)
        {
            // The count still gets taken, off whatever markers exist. Saying which of the two
            // happened is the point: a number from stale markers is not the same claim.
            Activator.logError("Revalidation after merge failed", couldNotRevalidate); //$NON-NLS-1$
        }
        outcome.errorsAfterMerge =
            ru.aiedt.mcp.server.toolkit.ops.ProjectProblemsReader.countErrorsOn(projectName,
                objects);
    }

    /**
     * Marks the caller's decisions on the comparison and writes them to a file.
     * <p>
     * Marking is separate from merging, and stays separate even now that this class can merge. What
     * this produces is a settings file EDT reads back ({@code deserializeMergeSettings}), so the
     * decisions can be handed to a person to run, reviewed before anything is applied, or kept as a
     * record of what was chosen. Nothing here calls {@code startMerge}; that happens afterwards and
     * only when the intent said so.
     * </p>
     * <p>
     * A decision naming an object the comparison did not report is refused rather than ignored: a
     * settings file that quietly lacks half the decisions somebody wrote is worse than no file.
     * </p>
     *
     * @param manager the comparison service.
     * @param handle the process.
     * @param decisions what the caller decided; may be empty.
     * @param path where to write the settings, or <code>null</code> to only mark them.
     * @param outcome the answer being built.
     */
    private static void decide(IComparisonManager manager, ComparisonProcessHandle handle,
        List<Decision> decisions, String path, Outcome outcome)
    {
        if (decisions == null || decisions.isEmpty())
        {
            return;
        }
        IComparisonSession session = manager.getComparisonSession(handle);
        if (session == null)
        {
            outcome.decisionsNote = "the comparison offered no session, so nothing was decided"; //$NON-NLS-1$
            return;
        }
        for (Decision decision : decisions)
        {
            Change target = null;
            for (Change change : outcome.changed)
            {
                if (decision.object != null && (decision.object.equals(change.main)
                    || decision.object.equals(change.other) || decision.object.equals(change.ancestor)))
                {
                    target = change;
                    break;
                }
            }
            // The reported list stops at CHANGED_LIMIT, and a real update runs to tens of
            // thousands of changed objects. Resolving a decision against that list alone meant a
            // decision could only be made about whatever happened to land in the printed window -
            // and the window's contents move between runs, so the same call answered "recorded"
            // one day and "not among the changes" the next. The environment indexes top nodes by
            // name; ask it instead, and the list stays what it is - a report.
            long nodeId = target != null ? target.nodeId : findTopNode(session, decision.object);
            if (nodeId < 0)
            {
                outcome.decisionsNote = "no object named " + decision.object //$NON-NLS-1$
                    + " takes part in this comparison, so no decision was recorded for it"; //$NON-NLS-1$
                return;
            }
            MergeRule rule = ruleNamed(decision.rule);
            if (rule == null)
            {
                outcome.decisionsNote = decision.rule + " is not a merge rule. Use one of: " //$NON-NLS-1$
                    + ruleNames();
                return;
            }
            // To the subtree, because a decision about an object is a decision about what the
            // object is made of. The narrower call needs a comparison context this has no honest
            // value for.
            //
            // The return value is the whole point: the environment refuses a rule a node does not
            // accept, and a decision counted without reading it is a decision nobody made.
            if (!session.setMergeRuleToSubtree(nodeId, rule))
            {
                outcome.decisionsRefused++;
                if (outcome.decisionsNote == null)
                {
                    outcome.decisionsNote = "the environment refused " + rule.name() + " for " //$NON-NLS-1$ //$NON-NLS-2$
                        + decision.object + describeAvailable(session, nodeId);
                }
                continue;
            }
            if (target != null)
            {
                target.decision = rule.name();
            }
            outcome.decided++;
        }
        if (path == null || path.isEmpty())
        {
            return;
        }
        if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) //$NON-NLS-1$
        {
            // Refused here rather than inside the environment, which enforces the same thing with
            // an assertion whose message reaches the caller as a failed write of a file they were
            // told nothing about. The decisions are marked either way; only the file is missed.
            outcome.decisionsNote = "the decisions are marked but were not written: " //$NON-NLS-1$
                + "decisionsPath must end in .zip, which is the format the environment reads " //$NON-NLS-1$
                + "back. Given: " + path; //$NON-NLS-1$
            return;
        }
        try
        {
            manager.serializeMergeSettings(Collections.singletonList(handle), path);
            outcome.decisionsWrittenTo = path;
        }
        catch (Exception cannotWrite)
        {
            // The decisions are marked either way, but they die with the comparison. Said plainly:
            // a caller told the file was written would go looking for one.
            outcome.decisionsNote = "the decisions were marked but could not be written to " + path //$NON-NLS-1$
                + ": " + describe(cannotWrite); //$NON-NLS-1$
        }
    }

    /**
     * The merge rule of that name, or <code>null</code>.
     *
     * @param name what the caller wrote.
     * @return the rule
     */
    /**
     * Names the rules a node will accept, for a refusal message.
     * <p>
     * A refusal without the alternatives sends the caller guessing through six rule names. The
     * environment already knows which ones this node allows.
     * </p>
     *
     * @param session the comparison session.
     * @param nodeId the node the rule was refused for.
     * @return a readable tail for the refusal, empty when the node will not say
     */
    private static String describeAvailable(IComparisonSession session, long nodeId)
    {
        try
        {
            ComparisonNode node = session.getNode(nodeId);
            MergeSettings settings = node == null ? null : node.getMergeSettings();
            if (settings == null || settings.getAvailableMergeRules() == null
                || settings.getAvailableMergeRules().isEmpty())
            {
                return ". That node accepts no merge rule at all."; //$NON-NLS-1$
            }
            StringBuilder allowed = new StringBuilder();
            for (MergeRule rule : settings.getAvailableMergeRules())
            {
                if (allowed.length() > 0)
                {
                    allowed.append('/');
                }
                allowed.append(rule.name());
            }
            return ". That node accepts: " + allowed; //$NON-NLS-1$
        }
        catch (RuntimeException | LinkageError silent)
        {
            Activator.logDebug("comparison: node would not list its rules: " + silent); //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * Finds the node of a named object anywhere in the comparison.
     * <p>
     * Asked of the environment rather than searched for in the reported list, because the list is
     * capped and the comparison is not. Each side is tried in turn: an object added by the vendor
     * has no name on our side, one we deleted has none on theirs, and either is still a legitimate
     * thing to decide about.
     * </p>
     *
     * @param session the comparison session.
     * @param name the object as the comparison names it.
     * @return the node identity, or -1 when no side knows that name
     */
    private static long findTopNode(IComparisonSession session, String name)
    {
        if (name == null || name.isEmpty())
        {
            return -1;
        }
        for (ComparisonSide side : new ComparisonSide[] {ComparisonSide.MAIN, ComparisonSide.OTHER,
            ComparisonSide.COMMON_ANCESTOR})
        {
            try
            {
                TopComparisonNode node = session.getTopNode(name, side);
                if (node != null)
                {
                    return node.bmGetId();
                }
            }
            catch (RuntimeException | LinkageError notThere)
            {
                Activator.logDebug("comparison: side " + side + " could not resolve " + name //$NON-NLS-1$ //$NON-NLS-2$
                    + ": " + notThere); //$NON-NLS-1$
            }
        }
        return -1;
    }

    /**
     * Counts the support modes of a project, by mode.
     * <p>
     * Guarded by a bundle check because the support subsystem is an optional dependency: an EDT
     * without it must produce an empty census rather than a class-loading failure in the middle of
     * a merge.
     * </p>
     *
     * @param projectName the project to count.
     * @param into where to write the counts; left empty when nothing can be counted.
     */
    private static void censusSupport(String projectName, java.util.Map<String, Integer> into)
    {
        try
        {
            if (org.eclipse.core.runtime.Platform
                .getBundle("com.e1c.g5.v8.dt.distribution") == null) //$NON-NLS-1$
            {
                return;
            }
            BmSupportRegistryHelper.Registry registry = BmSupportRegistryHelper.read(projectName);
            if (registry.cannotTell != null || !registry.onSupport)
            {
                return;
            }
            for (BmSupportRegistryHelper.Parent parent : registry.parents)
            {
                for (java.util.Map.Entry<String, Integer> mode : parent.byUserMode.entrySet())
                {
                    into.merge(mode.getKey(), mode.getValue(),
                        (was, more) -> Integer.valueOf(was.intValue() + more.intValue()));
                }
            }
        }
        catch (RuntimeException | LinkageError cannotCount)
        {
            Activator.logDebug("merge: support modes could not be counted: " + cannotCount); //$NON-NLS-1$
        }
    }

    private static MergeRule ruleNamed(String name)
    {
        if (name == null)
        {
            return null;
        }
        for (MergeRule rule : MergeRule.values())
        {
            if (rule.name().equalsIgnoreCase(name.trim()))
            {
                return rule;
            }
        }
        return null;
    }

    /** @return every rule name, for a refusal that tells the caller what to write instead */
    private static String ruleNames()
    {
        StringBuilder names = new StringBuilder();
        for (MergeRule rule : MergeRule.values())
        {
            names.append(names.length() == 0 ? "" : ", ").append(rule.name()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return names.toString();
    }

    /**
     * Applies the decisions to the configuration, when that is what was asked for.
     * <p>
     * This is the only irreversible thing in this class, and everything about it is arranged so it
     * cannot happen by accident: the caller has to ask by name, has to have supplied decisions
     * (there is nothing to apply otherwise), and has to ask a second time in different words to
     * proceed past a problem the environment called blocking.
     * </p>
     * <p>
     * What blocks a merge is decided by the environment, not here, and this is measured rather than
     * assumed: merge problems come out of a validation phase that runs as part of the merge itself,
     * so before one is started there is nothing to read - {@code getMergeProblems} fails an internal
     * assertion. A check made here would therefore always see zero problems and always pass, which
     * is worse than no check at all. The two entry points differ exactly in whether the environment
     * proceeds past its own objection, and that is the choice the intent carries.
     * </p>
     * <p>
     * The merge is also scheduled rather than performed: {@code startMerge} returns OK as soon as
     * the job is accepted. Reporting that status as the result would say "merged" before anything
     * had been written - so the answer here is taken from the process state after the work ends.
     * </p>
     *
     * @param manager the comparison service.
     * @param handle the process.
     * @param batch the batch that was compared.
     * @param intent what the caller asked for.
     * @param outcome the answer being built.
     */
    private static void merge(IComparisonManager manager, ComparisonProcessHandle handle,
        CompareMergeProcessBatch batch, Intent intent, Outcome outcome)
    {
        if (intent == null || intent == Intent.REPORT)
        {
            return;
        }
        // Decisions reach a comparison two ways, and the guard has to see both. Counting only the
        // ones passed in this call refused every merge driven from a saved settings file - which is
        // the whole point of writing one: hand the hard objects to a person, take back what they
        // decided, carry it out. The environment had the rules; we were the ones who said no.
        if (outcome.decided == 0 && !outcome.decisionsRestored)
        {
            outcome.mergeRefused = "no merge was run: there are no decisions to apply. Pass " //$NON-NLS-1$
                + "decisions naming what to do with each object, or decisionsFrom pointing at a " //$NON-NLS-1$
                + "settings file that carries them."; //$NON-NLS-1$
            return;
        }
        try
        {
            org.eclipse.core.runtime.IStatus scheduled =
                intent == Intent.MERGE_IGNORING_PROBLEMS
                    ? manager.startMergeIgnoringProblems(batch,
                        new org.eclipse.core.runtime.NullProgressMonitor())
                    : manager.startMerge(batch, new org.eclipse.core.runtime.NullProgressMonitor());
            if (scheduled == null || !scheduled.isOK())
            {
                outcome.mergeStatus = scheduled == null ? "no status at all" //$NON-NLS-1$
                    : scheduled.getSeverity() + ": " + scheduled.getMessage(); //$NON-NLS-1$
                if (scheduled != null && scheduled.getException() != null)
                {
                    outcome.mergeStatus += " (" + describe(scheduled.getException()) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                outcome.mergeRefused = "the environment would not start the merge: " //$NON-NLS-1$
                    + outcome.mergeStatus;
                return;
            }
            awaitMergeEnd(manager, handle, outcome);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            outcome.mergeStatus = "the wait for the merge was interrupted"; //$NON-NLS-1$
            outcome.mergeRefused = "the merge was started and this call stopped waiting for it; " //$NON-NLS-1$
                + "whether it finished is not known from here"; //$NON-NLS-1$
        }
        catch (Exception failed)
        {
            // Said as a failure of the merge, not of the comparison: the comparison succeeded, and
            // what the caller needs to know is whether their configuration was touched.
            outcome.merged = false;
            outcome.mergeStatus = "the merge failed: " + describe(failed); //$NON-NLS-1$
            Activator.logError("Merge failed", failed); //$NON-NLS-1$
        }
    }

    /**
     * Waits for a started merge to end, and reports what the environment actually did.
     * <p>
     * Three ends are distinguished, because they mean entirely different things to whoever asked:
     * the merge ran, the environment's own validation stopped it before it wrote anything, or it
     * was discarded. The middle one is the reason this waits at all - it is the ordinary outcome of
     * intent=MERGE against a configuration the environment objects to, and it must not read as a
     * merge that happened.
     * </p>
     * <p>
     * Validation finishing is not by itself an end: on the way to a successful merge the process
     * passes through that same state. What separates the two is whether the validation left
     * blocking problems behind, which is readable from that point on - so the problems are read
     * there and the answer follows them, rather than following a timer.
     * </p>
     * <p>
     * A merge that outlives the wait is left alone. Calling off a comparison is safe and this class
     * does it; calling off a merge half way through is how a configuration ends up in a state
     * nobody chose.
     * </p>
     *
     * @param manager the comparison service.
     * @param handle the process.
     * @param outcome the answer being built.
     * @throws InterruptedException when the wait is interrupted
     */
    private static void awaitMergeEnd(IComparisonManager manager, ComparisonProcessHandle handle,
        Outcome outcome) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + MERGE_WAIT_LIMIT_MS;
        boolean problemsTaken = false;
        long validationFinishedAt = 0L;
        while (System.currentTimeMillis() < deadline)
        {
            ComparisonProcessStatus status = manager.getStatus(handle);
            if (status != null)
            {
                outcome.mergeStatus = status.name();
                if (status == ComparisonProcessStatus.MERGE_PROCESS_VALIDATION_FINISHED)
                {
                    // The one moment the problem list is legal to read. Taken once and kept: by the
                    // time the merge has finished this same call throws again, and re-reading would
                    // replace a real list with "not read".
                    if (!problemsTaken)
                    {
                        readProblems(manager, handle, outcome);
                        problemsTaken = true;
                        validationFinishedAt = System.currentTimeMillis();
                    }
                    if (outcome.blockingProblems > 0)
                    {
                        outcome.mergeRefused = "no merge was run: the environment's own " //$NON-NLS-1$
                            + "validation raised " + outcome.blockingProblems //$NON-NLS-1$
                            + " blocking problem(s), listed in problems, and stopped before " //$NON-NLS-1$
                            + "writing anything. Read them, and if they are acceptable ask again " //$NON-NLS-1$
                            + "with intent=MERGE_IGNORING_PROBLEMS."; //$NON-NLS-1$
                        return;
                    }
                    if (System.currentTimeMillis() - validationFinishedAt > VALIDATION_SETTLE_MS)
                    {
                        // Validation refused for a reason it did not express as a blocking problem.
                        // Reported as a stop rather than waited out to the full limit, because the
                        // merge phase never starts from here and the caller would otherwise be told
                        // about a timeout that is not one.
                        outcome.mergeRefused = "no merge was run: the environment stopped after " //$NON-NLS-1$
                            + "its own validation and did not begin writing. It raised no " //$NON-NLS-1$
                            + "blocking problem to explain that, so the reason is the " //$NON-NLS-1$
                            + "environment's own."; //$NON-NLS-1$
                        return;
                    }
                }
                if (status == ComparisonProcessStatus.MERGE_PROCESS_FINISHED)
                {
                    outcome.merged = true;
                    return;
                }
                if (status == ComparisonProcessStatus.MERGE_PROCESS_DISCARDED
                    || status == ComparisonProcessStatus.COMPARISON_MERGE_PROCESS_CANCELLED)
                {
                    outcome.mergeRefused = "no merge was run: the environment ended the merge as " //$NON-NLS-1$
                        + status.name() + ". Nothing was written."; //$NON-NLS-1$
                    return;
                }
            }
            else
            {
                // The session is gone, and for a merge that is the ordinary ending rather than an
                // error: the environment sets MERGE_PROCESS_FINISHED and discards the session in
                // the same breath, so the finished state is usually never observable from outside.
                // Waiting for it cost ten minutes per merge and ended in a timeout on work that had
                // already been done - the answer was wrong in the safe direction, which is still
                // wrong. A vanished session cannot mean "not started": nothing gets here without
                // startMerge having been accepted.
                outcome.merged = true;
                outcome.mergeStatus = "MERGE_PROCESS_FINISHED (session discarded on completion)"; //$NON-NLS-1$
                return;
            }
            Thread.sleep(POLL_MS);
        }
        outcome.mergeRefused = "the merge was started and had not finished within the time " //$NON-NLS-1$
            + "allowed; last state " + outcome.mergeStatus + ". It is still running - this call " //$NON-NLS-1$ //$NON-NLS-2$
            + "stopped waiting, it did not stop the merge, and whether the configuration was " //$NON-NLS-1$
            + "written cannot be answered from here."; //$NON-NLS-1$
    }

    /**
     * Closes a comparison that has been read.
     * <p>
     * Failure to close is logged and nothing more: the answer is already complete and correct, and
     * turning a successful comparison into a refusal because the cleanup stumbled would be the
     * worse trade. It is logged rather than swallowed because a comparison that will not close is
     * exactly what the caller will feel later, when the session declines to shut down.
     * </p>
     *
     * @param manager the comparison service.
     * @param handle the process to close.
     */
    private static void release(IComparisonManager manager, ComparisonProcessHandle handle)
    {
        try
        {
            manager.stop(handle);
        }
        catch (Exception cannotStop)
        {
            Activator.logWarning("A finished comparison could not be closed: " //$NON-NLS-1$
                + describe(cannotStop));
        }
    }

    /**
     * Calls off a comparison that is no longer being waited for.
     *
     * @param manager the comparison service.
     * @param handle the process to stop.
     * @return what to append to the reason, saying whether it could be stopped
     */
    private static String cancel(IComparisonManager manager, ComparisonProcessHandle handle)
    {
        try
        {
            manager.cancel(handle);
            return ", and it was called off"; //$NON-NLS-1$
        }
        catch (Exception cannotCancel)
        {
            Activator.logWarning("Could not call off a comparison: " //$NON-NLS-1$
                + describe(cannotCancel));
            // Said out loud rather than swallowed: a comparison still running holds contexts open,
            // and whoever reads this needs to know the environment may not close cleanly.
            return ", and it could NOT be called off (" + describe(cannotCancel) //$NON-NLS-1$
                + ") - it may still be running"; //$NON-NLS-1$
        }
    }

    /**
     * Names a failure by type as well as by message.
     *
     * @param e the failure.
     * @return a description that is never empty
     */
    private static String describe(Throwable e)
    {
        String message = e.getMessage();
        if (message != null && !message.isEmpty())
        {
            return e.getClass().getSimpleName() + ": " + message; //$NON-NLS-1$
        }
        // A bare type name is barely better than "null". The failures that arrive here carry no
        // message at all - a guava precondition, an Eclipse assertion - and every one of them was
        // thrown inside the environment's own comparison code, which the frame names precisely.
        // Measured: "NullPointerException" alone said nothing, while
        // "TopObjectInfo.setUuid" says a source object has no UUID and points at the data rather
        // than at us.
        return e.getClass().getName() + whereItCameFrom(e);
    }

    /**
     * The first frame that belongs to the environment, which is where a message-less failure
     * actually happened.
     *
     * @param e the failure.
     * @return {@code " at Class.method"}, or the empty string when no such frame exists
     */
    private static String whereItCameFrom(Throwable e)
    {
        StackTraceElement[] frames = e.getStackTrace();
        if (frames == null)
        {
            return ""; //$NON-NLS-1$
        }
        for (StackTraceElement frame : frames)
        {
            String className = frame.getClassName();
            if (className.startsWith("com._1c.")) //$NON-NLS-1$
            {
                return " at " + className.substring(className.lastIndexOf('.') + 1) //$NON-NLS-1$
                    + "." + frame.getMethodName(); //$NON-NLS-1$
            }
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * The open project that plays our side.
     * <p>
     * Built from the project itself rather than from its name and a guessed nature. The
     * name-and-nature constructor takes a nature string, and there is no honest value to pass for
     * it from here - a null went in on the first live run and came back as an exception with no
     * message at all.
     * </p>
     *
     * @param projectName the project name.
     * @param outcome the answer being built, for the reason when the project is not there.
     * @return the descriptor, or <code>null</code>
     */
    private static IComparisonDataSourceDescriptor ourSide(String projectName, Outcome outcome)
    {
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            outcome.cannotTell = ProjectResolver.describeNotFound(projectName);
            return null;
        }
        Activator activator = Activator.getDefault();
        IV8ProjectManager projects = activator == null ? null : activator.getV8ProjectManager();
        IV8Project v8Project = projects == null ? null : projects.getProject(project);
        if (v8Project == null)
        {
            outcome.cannotTell = projectName
                + " is in the workspace but is not a 1C project the environment recognises"; //$NON-NLS-1$
            return null;
        }
        return new V8ProjectComparisonDataSourceDescriptor(v8Project);
    }

    /**
     * A directory that holds a configuration, checked before the environment is asked to open it.
     *
     * @param path the directory.
     * @param outcome the answer being built, for the reason when the path is unusable.
     * @return the descriptor, or <code>null</code>
     */
    private static IComparisonDataSourceDescriptor onDisk(String path, Outcome outcome)
    {
        Path directory = Paths.get(path);
        if (!Files.isDirectory(directory))
        {
            outcome.cannotTell = path + " is not a directory"; //$NON-NLS-1$
            return null;
        }
        FileSystemComparisonDataSourceDescriptor descriptor =
            new FileSystemComparisonDataSourceDescriptor(directory);
        // Asked, not assumed, and not used to refuse: the environment may well read a layout this
        // predicate does not recognise. It is recorded so that a failure can say what the sources
        // looked like rather than leaving the caller to guess at the format.
        if (!descriptor.storesValidDtProject())
        {
            outcome.sourceNote += ". " + path //$NON-NLS-1$
                + " does not look like a DT project directory to the environment"; //$NON-NLS-1$
        }
        return descriptor;
    }

    /**
     * Waits for the process to reach a state worth reading.
     * <p>
     * Polled rather than listened to on purpose: a listener would have to be removed on every exit
     * path, and a comparison that dies without a final event would leave the caller waiting
     * forever. Polling ends on its own.
     * </p>
     *
     * @param manager the comparison service.
     * @param handle the process.
     * @param batch the batch, which carries the failure when one happened.
     * @param outcome the answer being built.
     * @throws InterruptedException when the wait is interrupted
     */
    private static void awaitFinish(IComparisonManager manager, ComparisonProcessHandle handle,
        CompareMergeProcessBatch batch, Outcome outcome) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + WAIT_LIMIT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            Throwable failure = batch.getFailureCause();
            if (failure != null)
            {
                outcome.status = "FAILED"; //$NON-NLS-1$
                // Named by type here too. The first fix named the type in one of the two failure
                // paths and left this one saying "null" - the same silence, one line further on.
                outcome.cannotTell = "the comparison failed: " + describe(failure) //$NON-NLS-1$
                    + outcome.sourceNote;
                Activator.logError("Comparison failed: " + describe(failure), failure); //$NON-NLS-1$
                return;
            }
            ComparisonProcessStatus status = manager.getStatus(handle);
            if (status != null)
            {
                outcome.status = status.name();
                if (status == ComparisonProcessStatus.COMPARISON_PROCESS_FINISHED)
                {
                    outcome.completed = true;
                    return;
                }
            }
            Thread.sleep(POLL_MS);
        }
        // Called off, not abandoned. A comparison left pending keeps its virtual project contexts
        // open, and EDT will not shut down while they are - which is how the first live run turned
        // a slow answer into a session that refused to close. Whoever asked has stopped waiting, so
        // the work stops too.
        String calledOff = cancel(manager, handle);
        outcome.cannotTell = "the comparison did not finish within the time allowed; last status " //$NON-NLS-1$
            + outcome.status + calledOff + outcome.sourceNote;
    }

    /**
     * Reads the finished comparison: the tree, then the problems.
     *
     * @param manager the comparison service.
     * @param handle the process.
     * @param outcome the answer being built.
     */
    private static void read(IComparisonManager manager, ComparisonProcessHandle handle,
        Outcome outcome, Page page)
    {
        IComparisonSession session = manager.getComparisonSession(handle);
        if (session == null)
        {
            outcome.cannotTell = "the comparison finished but produced no session to read"; //$NON-NLS-1$
            return;
        }
        ComparisonNode root = session.getRootNode();
        if (root != null)
        {
            walk(root, outcome, page);
        }
        readProblems(manager, handle, outcome);
    }

    /**
     * Reads the merge problems, when there are any to read.
     * <p>
     * Guarded separately from the tree, and this is not defensive habit - it is measured. The
     * environment only allows this list to be read once its merge validation has run, and that
     * validation is part of a merge: before one is started {@code getMergeProblems} fails an
     * internal assertion. That threw away a finished tree twice over, because the counts were
     * already computed and the exception discarded the whole answer on the way out.
     * </p>
     * <p>
     * So the absence of problems is reported as what it is - not asked yet - rather than as an
     * empty list, which would read as "nothing stands in the way". The difference matters most
     * where it is easiest to miss: a merge must never be allowed to proceed on the strength of a
     * problem count that nobody was able to take.
     * </p>
     * <p>
     * Called again after a merge, when the same list has become readable and says something real.
     * It therefore replaces what it found last time rather than adding to it.
     * </p>
     *
     * @param manager the comparison service.
     * @param handle the process.
     * @param outcome the answer being built.
     */
    private static void readProblems(IComparisonManager manager, ComparisonProcessHandle handle,
        Outcome outcome)
    {
        outcome.problems.clear();
        outcome.blockingProblems = 0;
        outcome.problemsNote = null;
        List<MergeProblem> problems;
        try
        {
            problems = manager.getMergeProblems(handle);
        }
        catch (Exception notApplicable)
        {
            outcome.problemsNote = "not read: the environment allows merge problems to be read " //$NON-NLS-1$
                + "only after its merge validation has run, and that happens as part of a merge (" //$NON-NLS-1$
                + describe(notApplicable) + ")"; //$NON-NLS-1$
            return;
        }
        if (problems == null)
        {
            outcome.problemsNote = "not read: the environment offered no problem list"; //$NON-NLS-1$
            return;
        }
        for (MergeProblem problem : problems)
        {
            if (problem.isBlocking())
            {
                outcome.blockingProblems++;
                outcome.problems.add(0, "BLOCKING: " + problem.getMessage()); //$NON-NLS-1$
            }
            else
            {
                outcome.problems.add(problem.getMessage());
            }
        }
    }

    /**
     * Counts the tree without changing anything in it.
     *
     * @param node the node to count and descend from.
     * @param outcome the answer being built.
     */
    /**
     * Names one changed object and says on which side it stands.
     *
     * @param node the top node, which is a metadata object.
     * @param oneSided whether it exists on one side only.
     * @return the description
     */
    private static Change describeChange(TopComparisonNode node, boolean oneSided,
        boolean threeWay)
    {
        Change change = new Change();
        change.oneSided = oneSided;
        change.nodeId = node.bmGetId();
        change.changedBy = attribute(node, oneSided, threeWay);
        readRecommendation(node, change);
        try
        {
            change.main = node.getSymlink(ComparisonSide.MAIN);
            change.other = node.getSymlink(ComparisonSide.OTHER);
            change.ancestor = node.getSymlink(ComparisonSide.COMMON_ANCESTOR);
            if (oneSided && node.getNodeSide() != null)
            {
                change.presentOn = node.getNodeSide().getName();
            }
        }
        catch (Exception cannotName)
        {
            // A node that will not name itself is still reported: dropping it would understate
            // what moved, which is the one thing this list must not do.
            change.note = "could not be named: " + describe(cannotName); //$NON-NLS-1$
        }
        return change;
    }

    /**
     * Says which side changed an object.
     * <p>
     * Read from the environment own flags rather than inferred. A difference between our side and
     * the ancestor is our doing; a difference between the delivery and the ancestor is the
     * vendor doing; both at once is a conflict, and the environment has a flag for exactly that.
     * </p>
     * <p>
     * A one-sided node is attributed by the side it stands on: present only on the delivery means
     * the vendor added it, present only here means either we added it or the vendor removed it -
     * the same event seen from two directions, so the answer names the side rather than pretending
     * to know the intent.
     * </p>
     *
     * @param node the node to attribute.
     * @param oneSided whether it exists on one side only.
     * @param threeWay whether a common ancestor took part.
     * @return OURS, VENDOR, BOTH or UNKNOWN
     */
    private static String attribute(ComparisonNode node, boolean oneSided, boolean threeWay)
    {
        if (!threeWay)
        {
            // Two sides can say that something differs and never which side moved it. Answering
            // anything else here would be a guess dressed as a measurement.
            return "UNKNOWN"; //$NON-NLS-1$
        }
        try
        {
            if (oneSided)
            {
                // Which side an object stands on does not say who moved it - the ancestor does.
                // An object present here and absent from the delivery was added by us if the
                // ancestor never had it, and DELETED BY THE VENDOR if it did. Reading the side
                // alone calls every vendor deletion our own work, and every deletion of ours a
                // vendor addition, which turns the whole census inside out on exactly the objects
                // an update is most likely to break.
                boolean inAncestor = node.isAncestorObjectExists();
                ComparisonSide side = node.getNodeSide();
                if (side == ComparisonSide.OTHER)
                {
                    return inAncestor ? "OURS" : "VENDOR"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (side == ComparisonSide.MAIN)
                {
                    return inAncestor ? "VENDOR" : "OURS"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                return "UNKNOWN"; //$NON-NLS-1$
            }
            ComparisonFlags flags = node.getComparisonFlags();
            if (flags == null)
            {
                return "UNKNOWN"; //$NON-NLS-1$
            }
            if (flags.hasDoubleChanges())
            {
                return "BOTH"; //$NON-NLS-1$
            }
            boolean ours =
                flags.hasDifferences(ComparisonSide.MAIN, ComparisonSide.COMMON_ANCESTOR);
            boolean vendor =
                flags.hasDifferences(ComparisonSide.OTHER, ComparisonSide.COMMON_ANCESTOR);
            if (ours && vendor)
            {
                return "BOTH"; //$NON-NLS-1$
            }
            if (ours)
            {
                return "OURS"; //$NON-NLS-1$
            }
            if (vendor)
            {
                return "VENDOR"; //$NON-NLS-1$
            }
            return "UNKNOWN"; //$NON-NLS-1$
        }
        catch (RuntimeException | LinkageError flagsRefused)
        {
            // Named as unknown rather than assumed: a wrong attribution sends somebody to review
            // the wrong side of an update.
            Activator.logDebug("comparison: could not attribute a node: " + flagsRefused); //$NON-NLS-1$
            return "UNKNOWN"; //$NON-NLS-1$
        }
    }

    /**
     * Copies the environment own merge recommendation onto a change.
     * <p>
     * The environment already worked out which rule suits each node. Recomputing that here would be
     * inventing a second opinion; carrying it out means a caller can confirm or override a proposal
     * instead of deciding from nothing.
     * </p>
     *
     * @param node the node.
     * @param change where to write the recommendation.
     */
    private static void readRecommendation(ComparisonNode node, Change change)
    {
        try
        {
            MergeSettings settings = node.getMergeSettings();
            if (settings == null)
            {
                return;
            }
            MergeRule rule = settings.getDefaultMergeRule();
            change.recommendedRule = rule == null ? null : rule.name();
            change.mustBeMerged = settings.isMustBeMerged();
            change.canBeMerged = settings.isCanBeMerged();
        }
        catch (RuntimeException | LinkageError noSettings)
        {
            Activator.logDebug("comparison: no merge settings on a node: " + noSettings); //$NON-NLS-1$
        }
    }

    /**
     * Adds one object to the census of who changed what.
     *
     * @param outcome where to count.
     * @param changedBy the attribution.
     */
    private static void countAttribution(Outcome outcome, String changedBy)
    {
        outcome.objectsChanged++;
        if ("OURS".equals(changedBy)) //$NON-NLS-1$
        {
            outcome.objectsChangedByUs++;
        }
        else if ("VENDOR".equals(changedBy)) //$NON-NLS-1$
        {
            outcome.objectsChangedByVendor++;
        }
        else if ("BOTH".equals(changedBy)) //$NON-NLS-1$
        {
            outcome.objectsChangedByBoth++;
        }
        else
        {
            outcome.objectsChangedUnattributed++;
        }
    }

    private static void walk(ComparisonNode node, Outcome outcome, Page page)
    {
        outcome.nodes++;
        boolean oneSided = node.isOneSideNode();
        boolean differs = !oneSided && node.getComparisonFlags() != null
            && node.getComparisonFlags().hasDiffsMainOther();
        if (oneSided)
        {
            outcome.oneSided++;
        }
        else if (differs)
        {
            outcome.differing++;
        }
        // Counts alone do not answer the question anybody asks of an update on support, which is
        // WHICH objects moved and on whose side. Only the top nodes are named: those are the
        // metadata objects, and the nodes below them are the fields and members that make one
        // object differ - listing those would bury the answer in its own detail.
        if ((oneSided || differs) && node instanceof TopComparisonNode)
        {
            Change change =
                describeChange((TopComparisonNode)node, oneSided, outcome.threeWay);
            // The configuration root is a top node too, and it names itself on no side at all. It
            // differs whenever anything inside it does, so listing it says only "something
            // changed" - which the counts already said. An entry that identifies nothing is noise
            // in a list whose whole purpose is to identify.
            if (change.main != null && !change.main.isEmpty()
                || change.other != null && !change.other.isEmpty()
                || change.ancestor != null && !change.ancestor.isEmpty() || change.note != null)
            {
                // Counted whole, listed by the page. A census that shrank with the page would
                // understate the update, which is the one thing these numbers must not do.
                countAttribution(outcome, change.changedBy);
                if (page.wants(change))
                {
                    outcome.changedMatching++;
                    if (outcome.changedMatching > page.offset
                        && outcome.changed.size() < page.limit)
                    {
                        outcome.changed.add(change);
                    }
                    else if (outcome.changed.size() >= page.limit)
                    {
                        outcome.moreChanged = true;
                    }
                }
            }
        }
        if (!node.hasChildren())
        {
            return;
        }
        for (ComparisonNode child : node.<ComparisonNode> getChildren())
        {
            if (child != null)
            {
                walk(child, outcome, page);
            }
        }
    }

    /**
     * The empty set of merge decisions.
     * <p>
     * A comparison still wants a settings model, and ours is deliberately barren: this class reads,
     * it does not decide. Supplying the environment's own model here would carry decisions into a
     * process that has no business making any.
     * </p>
     */
    private static final class NoMergeSettings
        implements IMergeSettingsModel
    {
        @Override
        public SerializableMergeSettings getMergeSettingContainer(ComparisonNode node,
            IComparisonSession session)
        {
            return null;
        }

        @Override
        public boolean isEmpty()
        {
            return true;
        }
    }
}
