/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
import com._1c.g5.v8.dt.compare.model.FeatureComparisonNode;
import com._1c.g5.v8.dt.compare.model.RelatedFeature;
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
     * How many objects one decision about a class may cover.
     * <p>
     * A real update runs to tens of thousands of changed objects, and all of them may legitimately
     * be OURS. The ceiling exists so that a filter matching everything cannot silently turn into an
     * unbounded write; reaching it is reported rather than trimmed away.
     * </p>
     */
    private static final int MASS_LIMIT = 20000;

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
        MERGE_IGNORING_PROBLEMS,

        /**
         * Take the delivery whole, having checked that nothing here was reworked.
         * <p>
         * The degenerate case of an update on support: no customisations, so nobody has to sit
         * through sixteen thousand objects deciding about each. The tool checks the condition
         * rather than trusting the caller to have checked it, because getting it wrong here hands
         * the conflicts to whatever the environment defaults to.
         * </p>
         * <p>
         * A separate value rather than a flag on MERGE: this is the one merge that runs with no
         * decisions at all, and a route that skips the decisions must not be reachable by
         * accident.
         * </p>
         */
        UPDATE_UNCHANGED,

        /**
         * Take the delivery, and keep every object this side reworked.
         * <p>
         * The ordinary shape of an update on support. Objects only the delivery changed take the
         * rule the environment itself proposes, so the release arrives; objects reworked here are
         * set to DO_NOT_MERGE, which was measured to keep their content.
         * </p>
         * <p>
         * Conflicts are protected too, and that is not caution. Measured: a catalogue we had
         * customised and the delivery had deleted comes back with mustBeMerged set and
         * GET_FROM_OTHER proposed - the environment is willing to move it, and moving it removes
         * the customisation. So they are held and listed, because a conflict is work for a person,
         * not for a default.
         * </p>
         */
        UPDATE_KEEPING_OURS
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
        /**
         * Look inside modules, so a change can be named by the piece of the module it is in.
         * <p>
         * Off by default. Telling a module apart piece by piece is more tree to build and more to
         * walk, and that cost belongs to the caller who wants the detail. comparedInMs comes back
         * either way, so the price is measured rather than argued about.
         * </p>
         */
        public boolean methodLevel;

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

    /**
     * One piece of a module the comparison told apart from the rest of it - in practice, a method.
     * <p>
     * <b>Measured on a stand.</b> With the switch on, the environment produces
     * {@code BslModuleSectionComparisonNode}, and each one is a top node carrying the method's full
     * name - {@code CommonModule.F3Api.Module.OurCustomisation} - with its own attribution. So a
     * conflict can be stated as "this method" instead of "this module was changed by both sides",
     * and a decision can be addressed at one method while its neighbours are left alone.
     * </p>
     * <p>
     * The kind is reported as the environment names it rather than mapped onto a vocabulary of our
     * own: what the tree holds is what a caller should be able to see.
     * </p>
     */
    public static final class Section
    {
        /** The module this piece belongs to, named as the object list names it. */
        public String module;

        /** What the piece is called, on whichever side names it. */
        public String name;

        /** What the environment calls this kind of node. */
        public String kind;

        /** OURS, VENDOR, BOTH or UNKNOWN, by the same rule as an object. */
        public String changedBy;

        /** The node, for addressing a decision at it. Not reported. */
        transient long nodeId;
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

        /**
         * A whole class of objects instead of one.
         * <p>
         * The only value is {@code matching}: every object the filter arguments matched. There is
         * no second selector vocabulary on purpose - the filter already says OURS, or one-sided,
         * or a metadata type, and a decision phrased in different words than the preview would be
         * a decision about a set nobody looked at.
         * </p>
         */
        public final String select;

        /** One of the environment's merge rules. */
        public final String rule;

        /**
         * Names one object and what to do with it.
         *
         * @param object the object.
         * @param rule the merge rule.
         */
        public Decision(String object, String rule)
        {
            this(object, null, rule);
        }

        /**
         * Names an object or a selector, and what to do with it.
         *
         * @param object the object, or <code>null</code> when a selector is given.
         * @param select the selector, or <code>null</code> when an object is named.
         * @param rule the merge rule.
         */
        public Decision(String object, String select, String rule)
        {
            this.object = object;
            this.select = select;
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

        /**
         * The session this answer came from, to ask further questions of the same comparison.
         * <p>
         * A comparison of a real configuration takes minutes. Paging through what changed - the
         * only way to enumerate what to protect - would otherwise cost one comparison per page.
         * </p>
         */
        public String sessionKey;

        /** True when an already open comparison answered instead of a fresh one. */
        public boolean sessionReused;

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
         * Every node the filter matched, for a decision about the whole class.
         * <p>
         * Not reported: it is thousands of internal identifiers. The count that IS reported is
         * changedMatching, and the objects themselves come back a page at a time - which is what a
         * person reads before deciding about all of them.
         * </p>
         */
        public final transient List<Long> matchingNodes = new ArrayList<>();

        /**
         * Names the caller asked to compare, when the comparison was narrowed.
         * <p>
         * Empty means the whole configuration was compared.
         * </p>
         */
        public final List<String> scopeRequested = new ArrayList<>();

        /**
         * Every top node the comparison produced, when the comparison was narrowed.
         * <p>
         * Collected only under a scope, where it is bounded by what was asked for. It is the
         * evidence a requested name landed: the environment's own scope object hands back the
         * names it was given, misspellings included, so it cannot answer this.
         * </p>
         */
        public final transient java.util.Set<String> namesInTree = new java.util.HashSet<>();

        /**
         * Requested names the environment did not recognise.
         * <p>
         * The reason this is reported rather than logged: a narrowed comparison whose names are
         * all misspelled compares nothing and answers "no differences", which is the worst thing
         * an update tool can say. A name that did not land has to arrive with the answer.
         * </p>
         */
        public final List<String> scopeUnrecognised = new ArrayList<>();

        /**
         * What the environment pulled in beyond what was asked for, and on account of what.
         * <p>
         * Comparing one object drags in what it cannot be compared without. The additions are the
         * difference between the scope a caller wrote and the scope that ran, so they are named
         * rather than counted.
         * </p>
         */
        public final List<String> scopeExtendedBy = new ArrayList<>();

        /**
         * Decisions the environment took and will not act on, named with what it will do instead.
         * <p>
         * Measured on a stand, and the reason this list exists: on a node the environment marks
         * {@code mustBeMerged=false} - both sides changed the same lines - asking for the
         * delivery's version does nothing. GET_FROM_OTHER and MERGE_PRIORITIZING_OTHER were both
         * accepted, the merge reported success, and our side was kept. Without this list the
         * answer says decided and merged, and a person reads that as the vendor's version having
         * arrived.
         * </p>
         */
        public final List<String> decisionsWithoutEffect = new ArrayList<>();

        /** Nodes attributed to us, for the mode that protects them. Not reported. */
        public final transient List<Long> oursNodes = new ArrayList<>();

        /** Nodes both sides changed, for the same mode and for the queue. Not reported. */
        public final transient List<Long> bothNodes = new ArrayList<>();

        /** How many objects were held back from the update and confirmed to carry the rule. */
        public int protectedFromUpdate;

        /**
         * Objects the environment would not hold back, named.
         * <p>
         * An object that cannot be protected is the whole point of the report: it is the work that
         * this update is about to overwrite whatever the caller asked for.
         * </p>
         */
        public final List<String> protectionRefused = new ArrayList<>();

        /**
         * The conflicts, named - what a person has to decide about by hand.
         * <p>
         * Held at DO_NOT_MERGE meanwhile, so the update cannot resolve them by default while
         * nobody is looking.
         * </p>
         */
        public final List<String> conflictQueue = new ArrayList<>();

        /**
         * The pieces of modules that differ, when the caller asked to look inside them.
         * <p>
         * "This module was changed by both sides" is not something a person can act on. Which
         * method it was, and whether the delivery touched the signature or the body, is.
         * </p>
         */
        public final List<Section> sections = new ArrayList<>();

        /** True when the caller asked to look inside modules. */
        public transient boolean sectionsWanted;

        /** True when there were more sections than the page holds. */
        public boolean moreSections;

        /** How long the comparison itself took, so the cost of looking inside modules is visible. */
        public long comparedInMs;

        /** True when there were more matches than one decision may cover. */
        public boolean matchingTruncated;

        /** How many objects a decision about the whole class was applied to and read back on. */
        public int massDecided;

        /**
         * Objects the environment would not take the rule for, named with its reason.
         * <p>
         * Named rather than counted, and separated from the successes for the reason the plan
         * spells out: a mass assignment reporting only how many calls it made says nothing about
         * how many took.
         * </p>
         */
        public final List<String> massRefused = new ArrayList<>();

        /**
         * Objects that accepted the call and do not carry the rule when read back.
         * <p>
         * The source of truth is the rule read back from the node, not the return of the setter.
         * These two disagreeing is the failure this list exists to make visible.
         * </p>
         */
        public final List<String> massMismatched = new ArrayList<>();

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

        /**
         * Objects that survived the merge and lost the support mode they had.
         * <p>
         * Named, not counted. These are the ones a person deliberately unlocked and the update
         * locked again; a number says damage happened, a list says which work is now unprotected.
         * Cut at a page, with the total beside it.
         * </p>
         */
        public final List<String> supportModesLost = new ArrayList<>();

        /** How many objects lost their mode in total, whatever the list holds. */
        public int supportModesLostCount;

        /** Objects the new delivery brought, which had no mode before and take the rules' default. */
        public int supportModesArrived;

        /** Objects the snapshot knew and the configuration no longer has; they take no mode. */
        public int supportModesGone;

        /**
         * Where the pre-merge support modes were written down.
         * <p>
         * The only route back from a merge that overwrote them. Absent when there was nothing on
         * support to record.
         * </p>
         */
        public String supportSnapshotFile;

        /** Why the modes could not be written down. Present only when the write failed. */
        public String supportSnapshotNote;

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
        boolean ignoreOriginMismatch, Page page, boolean closeSession, List<String> scopeNames)
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
            // Named as the schema names them. The refusal used to carry the internal parameter
            // name, which is in no schema and no client - so a caller reading it went looking for
            // something that does not exist. Caught on a stand, by a probe that had passed the
            // right arguments under the right names.
            outcome.cannotTell = "projectName and otherPath are required"; //$NON-NLS-1$
            return outcome;
        }
        // Both live outside the try so that the finally can reach them. Everything the comparison
        // opens has to be closed on EVERY path out of this method, not only the one that worked:
        // a comparison left open holds the comparison store of the environment, and an EDT that
        // cannot close is what that looks like from the outside.
        IComparisonManager manager = null;
        boolean keepSession = false;
        try
        {
            manager = ServiceAccess.get(IComparisonManager.class);
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

            // An open comparison of the same three sides is reused rather than made again. Only
            // ones this server opened are candidates: the environment can list a comparison a
            // person opened in the editor, and adopting that would mean applying decisions to
            // somebody else's work or closing it under them.
            ComparisonScope scope = scopeOf(scopeNames, ancestor != null, outcome);
            // The scope is part of what a session compares. Without it in the fingerprint a
            // narrowed comparison would be handed back to a caller who asked for the whole
            // configuration, and the answer would name a fraction of what changed while the counts
            // read like a complete census.
            String fingerprint = ComparisonSessions.fingerprintOf(mainProjectName, otherPath,
                ancestorPath) + " | " + describeScope(outcome.scopeRequested); //$NON-NLS-1$
            ComparisonSessions.Session session =
                ComparisonSessions.findByFingerprint(fingerprint, System.currentTimeMillis());
            ComparisonProcessHandle handle;
            if (session != null && session.handle instanceof ComparisonProcessHandle)
            {
                handle = (ComparisonProcessHandle)session.handle;
                outcome.sessionReused = true;
                // This call closes only what it opened. A comparison somebody else is still
                // paging through must survive a failure here - otherwise a mistyped path would
                // throw away minutes of work that had nothing to do with the mistake.
                keepSession = true;
            }
            else
            {
                handle = ancestor == null
                    ? new ComparisonProcessHandle(main, other, scope)
                    : new ComparisonProcessHandle(main, other, ancestor, scope);
                session = ComparisonSessions.open(fingerprint, handle,
                    System.currentTimeMillis());
            }
            outcome.sessionKey = session.key;
            outcome.threeWay = handle.isThreeWay();
            outcome.sectionsWanted = page.methodLevel;

            ComparisonProcessSettings settings = settingsFor(manager, handle, decisionsFrom,
                outcome);
            if (settings == null)
            {
                return outcome;
            }
            CompareMergeProcessBatch batch =
                new CompareMergeProcessBatch(new CompareMergeProcessDescriptor(handle, settings));

            long startedAt = System.currentTimeMillis();
            manager.startComparison(batch);
            awaitFinish(manager, handle, batch, outcome);
            outcome.comparedInMs = System.currentTimeMillis() - startedAt;
            if (!outcome.completed)
            {
                return outcome;
            }
            read(manager, handle, outcome, page);
            reportScope(scope, outcome);
            // Before the caller's own decisions, so an explicit one about a named object still
            // wins over the blanket protection.
            if (intent == Intent.UPDATE_KEEPING_OURS)
            {
                protectOurs(manager, handle, outcome);
            }
            decide(manager, handle, decisions, decisionsPath, outcome);
            // Only when something is going to be written. Reporting must leave no trace, and the
            // snapshot below is a file inside the project - a read that wrote one would break the
            // promise this tool makes about its default, and break it in a preset whose whole
            // point is that nothing changes.
            if (intent != null && intent != Intent.REPORT)
            {
                String unsettled = whyNotSettled();
                if (unsettled != null)
                {
                    outcome.mergeRefused = unsettled;
                    return outcome;
                }
                // Measured either side of the merge. The merge cannot be stopped from touching
                // support settings - that was tried and does not work - so the honest thing left
                // is to say what it did to them.
                censusSupport(mainProjectName, outcome.supportModesBefore);
                // Counts say that damage happened; only a per-object snapshot says which objects
                // it happened to, and only that can be put back. The merge takes the support model
                // from the delivery and the environment's merge rules do not stop it - measured -
                // so the snapshot is the whole of what makes a restore possible.
                SupportSnapshot supportBefore = snapshotSupport(mainProjectName);
                // Written to disk before the merge runs, not offered as an option. A snapshot that
                // exists only in this call dies with it, and the modes it recorded are then
                // unrecoverable - which is the exact loss the snapshot exists to undo.
                keepSnapshot(mainProjectName, supportBefore, outcome);
                if (outcome.supportSnapshotNote != null)
                {
                    // The one precondition that is worth stopping for. Everything else this call
                    // does can be repeated; a support model overwritten with no record of what it
                    // held cannot be put back by anything.
                    outcome.mergeRefused = "no merge was run: " + outcome.supportSnapshotNote; //$NON-NLS-1$
                    return outcome;
                }
                merge(manager, handle, batch, intent, outcome);
                censusSupport(mainProjectName, outcome.supportModesAfter);
                outcome.supportModesChanged =
                    !outcome.supportModesBefore.equals(outcome.supportModesAfter);
                describeSupportDrift(mainProjectName, supportBefore, outcome);
            }
            reportProjectState(mainProjectName, decisions, outcome);
            // A reporting comparison stays open, and its key goes out with the answer. Paging
            // through tens of thousands of changed objects is the only way to enumerate what to
            // protect, and closing here would charge a full comparison for every page. Only a
            // comparison that got this far is worth keeping: one that failed answers nothing and
            // would hold the store until it expired.
            //
            // A merge is different, and this is the transaction leak. The merge writes into the
            // project, so the comparison no longer describes it - paging on would answer from a
            // model that has been overtaken. Worse, keeping it open leaves a transaction on the
            // comparison store of the environment, and if the person closes EDT before the idle
            // limit runs out, the environment waits on a transaction whose owner never comes back.
            // Measured: an EDT at no load for the better part of an hour, unable to shut down.
            keepSession = !closeSession && intent == Intent.REPORT;
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
        finally
        {
            // Every path out of the method passes here: the answer, each early refusal, both
            // catches. Draining only on the path that worked was the defect - a comparison that
            // failed, timed out, was refused by a blocking problem or was cancelled kept its
            // transaction on the comparison store, and nothing ever came back to close it.
            closeDropped(manager);
            if (!keepSession)
            {
                closeOwnSession(manager, outcome);
            }
        }
    }

    /**
     * Closes the comparison this call opened, when it is not being kept for paging.
     *
     * @param manager the comparison service; may be <code>null</code> when it was never reached.
     * @param outcome the answer, whose session key is cleared once the comparison is gone.
     */
    private static void closeOwnSession(IComparisonManager manager, Outcome outcome)
    {
        if (outcome.sessionKey == null)
        {
            return;
        }
        ComparisonSessions.Session done = ComparisonSessions.close(outcome.sessionKey);
        outcome.sessionKey = null;
        if (manager != null && done != null && done.handle instanceof ComparisonProcessHandle)
        {
            release(manager, (ComparisonProcessHandle)done.handle);
        }
    }

    /**
     * Closes every comparison this server still has open.
     * <p>
     * For plugin stop. A comparison that outlives the server holds the comparison store of the
     * environment, and the environment then waits on a transaction whose owner is gone - measured
     * as an EDT sitting at no load for the better part of an hour, unable to shut down.
     * </p>
     *
     * @return how many were closed
     */
    public static int closeEverything()
    {
        int closed = 0;
        try
        {
            IComparisonManager manager = ServiceAccess.get(IComparisonManager.class);
            List<ComparisonSessions.Session> open = ComparisonSessions.closeAll();
            open.addAll(ComparisonSessions.drainDropped());
            for (ComparisonSessions.Session session : open)
            {
                if (manager != null && session.handle instanceof ComparisonProcessHandle)
                {
                    release(manager, (ComparisonProcessHandle)session.handle);
                }
                closed++;
            }
        }
        catch (RuntimeException | LinkageError noComparisonSubsystem)
        {
            // An install without the comparison packages has nothing open to close, and a stopping
            // plugin is the wrong place to raise that.
            Activator.logDebug("no comparison sessions to close: " + noComparisonSubsystem); //$NON-NLS-1$
        }
        return closed;
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
    /**
     * Starts the settings for a comparison, with the one switch that changes what the tree holds.
     * <p>
     * {@code parseBslModuleStructure} makes the environment look inside modules instead of treating
     * each one as a single lump. It is off unless asked for: a module told apart piece by piece is
     * more tree to build and more tree to walk, and the caller who wants "which method" should pay
     * for it knowingly rather than everybody paying for it always.
     * </p>
     *
     * @param outcome the answer being built, which carries whether pieces were asked for.
     * @return a builder with the matching strategy and that switch already set
     */
    private static ComparisonProcessSettings.ComparisonSettingsBuilder builder(Outcome outcome)
    {
        return new ComparisonProcessSettings.ComparisonSettingsBuilder(
            MatchingStrategy.UUID_THEN_NAME).parseBslModuleStructure(outcome.sectionsWanted);
    }

    private static ComparisonProcessSettings settingsFor(IComparisonManager manager,
        ComparisonProcessHandle handle, String decisionsFrom, Outcome outcome)
    {
        if (decisionsFrom == null || decisionsFrom.trim().isEmpty())
        {
            return builder(outcome).mergeSettingsModel(new NoMergeSettings()).build();
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
            ComparisonProcessSettings settings = builder(outcome)
                .correspondences(correspondences == null ? Collections.emptyList() : correspondences)
                .mergeSettingsModel(restoredModel == null ? new NoMergeSettings() : restoredModel)
                .build();
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
            if (decision.select != null && !decision.select.isEmpty())
            {
                decideClass(session, decision, outcome);
                continue;
            }
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
            String impossible = whyRuleCannotRun(rule);
            if (impossible != null)
            {
                outcome.decisionsNote = impossible;
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
            noteIfInert(session, nodeId, rule, decision.object, outcome);
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
     * Builds the scope a comparison runs under.
     * <p>
     * Every side gets the same names. An object carries one name across a vendor update, and the
     * case where it does not - a rename - is the case a narrowed comparison cannot express: the
     * object is called one thing here and another in the delivery, and naming only one of them
     * would compare it against nothing. Comparing the whole configuration is the answer there.
     * </p>
     *
     * @param names what the caller asked to compare; may be <code>null</code> or empty.
     * @param threeSided whether an ancestor takes part.
     * @param outcome the answer, which records what was asked for.
     * @return the scope, or the empty one which means the whole configuration
     */
    private static ComparisonScope scopeOf(List<String> names, boolean threeSided, Outcome outcome)
    {
        if (names == null || names.isEmpty())
        {
            return ComparisonScope.EMPTY_SCOPE;
        }
        List<String> wanted = new ArrayList<>();
        for (String name : names)
        {
            if (name != null && !name.trim().isEmpty() && !wanted.contains(name.trim()))
            {
                wanted.add(name.trim());
            }
        }
        if (wanted.isEmpty())
        {
            return ComparisonScope.EMPTY_SCOPE;
        }
        outcome.scopeRequested.addAll(wanted);
        return threeSided
            ? new ComparisonScope(new ArrayList<>(wanted), new ArrayList<>(wanted),
                new ArrayList<>(wanted))
            : new ComparisonScope(new ArrayList<>(wanted), new ArrayList<>(wanted));
    }

    /**
     * Says what the scope became once the environment had its say.
     *
     * @param scope the scope the comparison ran under.
     * @param outcome the answer being built.
     */
    private static void reportScope(ComparisonScope scope, Outcome outcome)
    {
        if (scope == null || outcome.scopeRequested.isEmpty())
        {
            return;
        }
        try
        {
            // Judged against the tree the comparison actually built. Asking the scope object was
            // measured and is useless: it returns the names it was handed, a misspelling included,
            // so a scope of names that exist nowhere compared nothing and reported no differences.
            for (String asked : outcome.scopeRequested)
            {
                if (!outcome.namesInTree.contains(asked))
                {
                    outcome.scopeUnrecognised.add(asked);
                }
            }
            Map<String, List<String>> extended = scope.getExtendedScope(ComparisonSide.MAIN);
            if (extended != null)
            {
                for (Map.Entry<String, List<String>> added : extended.entrySet())
                {
                    if (outcome.scopeExtendedBy.size() >= PAGE_LIMIT)
                    {
                        break;
                    }
                    outcome.scopeExtendedBy
                        .add(added.getKey() + " pulled in " + added.getValue()); //$NON-NLS-1$
                }
            }
        }
        catch (RuntimeException | LinkageError willNotSay)
        {
            Activator.logDebug("comparison: the scope would not describe itself: " + willNotSay); //$NON-NLS-1$
        }
    }

    /**
     * Describes a scope for the session fingerprint.
     *
     * @param names what was asked for.
     * @return a stable description; the same text for the same set
     */
    private static String describeScope(List<String> names)
    {
        if (names == null || names.isEmpty())
        {
            return "(whole configuration)"; //$NON-NLS-1$
        }
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        return String.join(",", sorted); //$NON-NLS-1$
    }

    /**
     * Applies one rule to every object the filter matched.
     *
     * @param session the comparison.
     * @param decision the selector and the rule.
     * @param outcome the answer being built.
     */
    private static void decideClass(IComparisonSession session, Decision decision, Outcome outcome)
    {
        if (!"matching".equalsIgnoreCase(decision.select.trim())) //$NON-NLS-1$
        {
            // Refused by name rather than ignored. A selector nobody implements that is silently
            // skipped reads, in the answer, exactly like a selector that matched nothing.
            outcome.decisionsNote = decision.select + " is not a selector. The only one is " //$NON-NLS-1$
                + "matching, which covers every object the filter arguments matched - use " //$NON-NLS-1$
                + "changedBy, type, oneSided and mustBeMergedOnly to say which those are."; //$NON-NLS-1$
            return;
        }
        MergeRule rule = ruleNamed(decision.rule);
        if (rule == null)
        {
            outcome.decisionsNote = decision.rule + " is not a merge rule. Use one of: " //$NON-NLS-1$
                + ruleNames();
            return;
        }
        String impossible = whyRuleCannotRun(rule);
        if (impossible != null)
        {
            outcome.decisionsNote = impossible;
            return;
        }
        if (outcome.matchingNodes.isEmpty())
        {
            outcome.decisionsNote = "the filter matched no objects, so a decision about the " //$NON-NLS-1$
                + "matching ones changes nothing"; //$NON-NLS-1$
            return;
        }
        int applied = 0;
        for (Long nodeId : outcome.matchingNodes)
        {
            if (!session.setMergeRuleToSubtree(nodeId, rule))
            {
                if (outcome.massRefused.size() < PAGE_LIMIT)
                {
                    outcome.massRefused
                        .add(nameOf(session, nodeId) + describeAvailable(session, nodeId));
                }
                outcome.decisionsRefused++;
                continue;
            }
            // The source of truth is the rule read back from the node, not the return of the
            // setter. Those two disagreeing is the failure this looks for, and a mass assignment
            // is precisely the case where nobody is going to check the objects by hand.
            MergeRule carries = ruleOn(session, nodeId);
            if (carries != rule)
            {
                if (outcome.massMismatched.size() < PAGE_LIMIT)
                {
                    outcome.massMismatched.add(nameOf(session, nodeId) + ": asked for " //$NON-NLS-1$
                        + rule.name() + ", carries " //$NON-NLS-1$
                        + (carries == null ? "nothing" : carries.name())); //$NON-NLS-1$
                }
                continue;
            }
            noteIfInert(session, nodeId, rule, nameOf(session, nodeId), outcome);
            outcome.massDecided++;
            applied++;
        }
        // Counted locally and added once. massDecided accumulates across every selector in the
        // call, so adding IT to the total would count the first selector again for each one that
        // followed - and the total is what a caller reads to decide the merge is safe.
        outcome.decided += applied;
        if (outcome.matchingTruncated)
        {
            outcome.decisionsNote = "more than " + MASS_LIMIT + " objects matched, and this " //$NON-NLS-1$ //$NON-NLS-2$
                + "decision covered the first " + MASS_LIMIT + " of them. Narrow the filter and " //$NON-NLS-1$ //$NON-NLS-2$
                + "decide in parts - the rest carry no rule from this call."; //$NON-NLS-1$
        }
    }

    /**
     * Says when a decision the environment accepted will not move anything.
     * <p>
     * <b>Measured, not inferred.</b> On a stand, with a module both sides had changed on the same
     * lines, the environment reported {@code mustBeMerged=false} and recommended DO_NOT_MERGE.
     * Asking for GET_FROM_OTHER and then for MERGE_PRIORITIZING_OTHER was accepted both times, the
     * merge finished with no problems, and the module kept our text. The same rules on a node the
     * environment marked {@code mustBeMerged=true} did take the delivery's version, and on a
     * module the two sides had changed in different places MERGE_PRIORITIZING_OTHER produced a
     * genuine textual merge carrying both.
     * </p>
     * <p>
     * So {@code mustBeMerged} is not advice about tidiness - it says whether the environment will
     * move this node at all. A decision recorded against it is accepted, counted, and inert, and
     * an answer reporting only "decided" and "merged" reads as though the delivery had arrived.
     * </p>
     *
     * @param session the comparison.
     * @param nodeId the node the rule was set on.
     * @param rule the rule that was asked for.
     * @param object how the caller named the object.
     * @param outcome the answer being built.
     */
    private static void noteIfInert(IComparisonSession session, long nodeId, MergeRule rule,
        String object, Outcome outcome)
    {
        if (rule != MergeRule.GET_FROM_OTHER && rule != MergeRule.MERGE_PRIORITIZING_OTHER)
        {
            return;
        }
        try
        {
            ComparisonNode node = session.getNode(nodeId);
            MergeSettings settings = node == null ? null : node.getMergeSettings();
            if (settings == null || settings.isMustBeMerged())
            {
                return;
            }
            if (outcome.decisionsWithoutEffect.size() >= PAGE_LIMIT)
            {
                return;
            }
            MergeRule instead = settings.getDefaultMergeRule();
            outcome.decisionsWithoutEffect.add(object + ": " + rule.name() //$NON-NLS-1$
                + " was recorded, but both sides changed the same content and the environment " //$NON-NLS-1$
                + "will keep ours" //$NON-NLS-1$
                + (instead == null ? "" : " - it proposes " + instead.name())); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (RuntimeException | LinkageError willNotSay)
        {
            Activator.logDebug("comparison: a node would not say whether it must be merged: " //$NON-NLS-1$
                + willNotSay);
        }
    }

    /**
     * Reads back the rule a node carries.    /**
     * Reads back the rule a node carries.
     *
     * @param session the comparison.
     * @param nodeId the node.
     * @return the rule, or <code>null</code> when the node carries none or will not say
     */
    private static MergeRule ruleOn(IComparisonSession session, long nodeId)
    {
        try
        {
            ComparisonNode node = session.getNode(nodeId);
            MergeSettings settings = node == null ? null : node.getMergeSettings();
            return settings == null ? null : settings.getMergeRule();
        }
        catch (RuntimeException | LinkageError willNotSay)
        {
            Activator.logDebug("comparison: a node would not say its merge rule: " + willNotSay); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Names a node the way the change list names objects.
     *
     * @param session the comparison.
     * @param nodeId the node.
     * @return a name a caller can find in the change list, or the identity when it has none
     */
    private static String nameOf(IComparisonSession session, long nodeId)
    {
        try
        {
            ComparisonNode node = session.getNode(nodeId);
            if (node instanceof TopComparisonNode)
            {
                Change named = describeChange((TopComparisonNode)node, node.isOneSideNode(), true);
                if (named.main != null && !named.main.isEmpty())
                {
                    return named.main;
                }
                if (named.other != null && !named.other.isEmpty())
                {
                    return named.other;
                }
            }
        }
        catch (RuntimeException | LinkageError cannotName)
        {
            Activator.logDebug("comparison: a node would not name itself: " + cannotName); //$NON-NLS-1$
        }
        return "node " + nodeId; //$NON-NLS-1$
    }

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
     * Takes a per-object snapshot of the support modes, guarded like the census.
     *
     * @param projectName the project.
     * @return the snapshot, or <code>null</code> when there is no support subsystem to ask
     */
    private static SupportSnapshot snapshotSupport(String projectName)
    {
        try
        {
            if (org.eclipse.core.runtime.Platform
                .getBundle("com.e1c.g5.v8.dt.distribution") == null) //$NON-NLS-1$
            {
                return null;
            }
            SupportSnapshot taken = BmSupportRegistryHelper.snapshot(projectName);
            return taken.cannotTell == null ? taken : null;
        }
        catch (RuntimeException | LinkageError cannotTake)
        {
            Activator.logDebug("merge: support modes could not be snapshotted: " + cannotTake); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Says why an irreversible operation should not start yet.
     * <p>
     * A workspace still indexing or building is one whose model is moving. A merge measured
     * against a moving model reports counts that were true a moment ago, and it writes on that
     * basis - which is the same defect as a success nobody verified, only harder to see.
     * </p>
     * <p>
     * <b>What this does NOT check, stated rather than implied:</b> whether a person has unsaved
     * work open in an editor. Reaching into the workbench to find out is exactly the kind of thing
     * that interrupts somebody mid-sentence, and this server has paid for that before. A merge run
     * while an editor holds unsaved changes is a case a person has to avoid; the tool cannot see
     * it and does not pretend to.
     * </p>
     *
     * @return the reason to wait, or <code>null</code> when the workspace has settled
     */
    private static String whyNotSettled()
    {
        try
        {
            if (!WorkspacePhase.busy())
            {
                return null;
            }
            return "no merge was run: the workspace is " + WorkspacePhase.current() //$NON-NLS-1$
                + ", so its model is still moving. A merge decided against counts taken from a " //$NON-NLS-1$
                + "moving model writes on the strength of numbers that were true a moment ago. " //$NON-NLS-1$
                + "Wait for it to settle and ask again."; //$NON-NLS-1$
        }
        catch (RuntimeException | LinkageError cannotTell)
        {
            // Not knowing is not a reason to stop: the check is a guard, and a guard that refuses
            // when it cannot see would block every merge on an install it does not understand.
            Activator.logDebug("comparison: the workspace phase could not be read: " + cannotTell); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Writes the pre-merge snapshot into the project, where a restore can find it.
     * <p>
     * Beside the plugin's other project state in {@code .settings}, and stamped with the time so a
     * second merge cannot overwrite the record of what the first one found. The file is the only
     * thing that makes the loss reversible, so failing to write it is reported in the answer rather
     * than logged and forgotten.
     * </p>
     *
     * @param projectName the project.
     * @param snapshot what was taken; may be <code>null</code> when there was nothing to take.
     * @param outcome where to record the path or the reason there is none.
     */
    private static void keepSnapshot(String projectName, SupportSnapshot snapshot, Outcome outcome)
    {
        if (snapshot == null || snapshot.isEmpty())
        {
            return;
        }
        try
        {
            IProject project = ProjectResolver.resolve(projectName);
            if (project == null || project.getLocation() == null)
            {
                return;
            }
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss") //$NON-NLS-1$
                .format(new java.util.Date());
            Path path = project.getLocation().toFile().toPath()
                .resolve(".settings").resolve("aiedt-support-snapshot-" + stamp + ".tsv"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            snapshot.write(path);
            outcome.supportSnapshotFile = path.toString();
        }
        catch (IOException | RuntimeException cannotKeep)
        {
            // A refusal, not a note. The snapshot is the only way back from a merge that takes the
            // support model from the delivery; going ahead without one means the loss is
            // unrecoverable, and a caller reading a note beside a successful merge will not
            // discover that until they need the file.
            outcome.supportSnapshotNote = "the support modes could not be written down before the " //$NON-NLS-1$
                + "merge, so anything the merge overwrites cannot be put back: " + cannotKeep; //$NON-NLS-1$
        }
    }

    /**
     * Says which objects lost the support mode they had.
     *
     * @param projectName the project.
     * @param before the snapshot taken before the merge; may be <code>null</code>.
     * @param outcome where to record what changed.
     */
    private static void describeSupportDrift(String projectName,
        SupportSnapshot before, Outcome outcome)
    {
        if (before == null || before.isEmpty())
        {
            return;
        }
        try
        {
            SupportSnapshot now = BmSupportRegistryHelper.snapshot(projectName);
            if (now.cannotTell != null)
            {
                return;
            }
            SupportSnapshot.Drift drift = SupportSnapshot.compare(before, now);
            outcome.supportModesLostCount = drift.changed.size();
            outcome.supportModesArrived = drift.arrived;
            outcome.supportModesGone = drift.gone;
            for (String lost : drift.changed)
            {
                if (outcome.supportModesLost.size() >= PAGE_LIMIT)
                {
                    break;
                }
                outcome.supportModesLost.add(lost);
            }
        }
        catch (RuntimeException | LinkageError cannotCompare)
        {
            Activator.logDebug("merge: support drift could not be read: " + cannotCompare); //$NON-NLS-1$
        }
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

    /**
     * The merge rule of that name, or <code>null</code>.
     *
     * @param name what the caller wrote.
     * @return the rule
     */
    /**
     * Says why a rule cannot be carried out from here.
     * <p>
     * <b>Measured on a stand, and only one of the two suspects is guilty.</b> On a node the
     * environment was willing to move - vendor-only change, {@code mustBeMerged} set, GET_FROM_OTHER
     * recommended - CUSTOM_MERGE was accepted, the merge reported success and the content did not
     * change. MERGE_USING_EXTERNAL_TOOL on the same node in the same state took the delivery's
     * version, so it stays.
     * </p>
     * <p>
     * CUSTOM_MERGE is the rule that asks a person to compose the result in the merge editor. There
     * is no editor in a call like this, so there is nothing for it to do - and it says so by doing
     * nothing while everything reports success, which is the worst way to fail.
     * </p>
     *
     * @param rule the rule asked for.
     * @return the refusal, or <code>null</code> when the rule can be carried out
     */
    private static String whyRuleCannotRun(MergeRule rule)
    {
        if (rule != MergeRule.CUSTOM_MERGE)
        {
            return null;
        }
        return "CUSTOM_MERGE composes the result in the merge editor, and there is no editor in " //$NON-NLS-1$
            + "this call. Measured: it is accepted, the merge reports success and nothing " //$NON-NLS-1$
            + "changes, even on a node the environment was willing to move. Use DO_NOT_MERGE to " //$NON-NLS-1$
            + "keep ours, GET_FROM_OTHER or MERGE_USING_EXTERNAL_TOOL to take the delivery, or " //$NON-NLS-1$
            + "write the decisions to a file with decisionsPath and let a person finish them in " //$NON-NLS-1$
            + "EDT."; //$NON-NLS-1$
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
     * Holds back everything this side reworked, so the delivery can be taken over the rest.
     * <p>
     * DO_NOT_MERGE on every object attributed to us and on every conflict, read back from the node
     * afterwards. Reading it back is not ceremony: a rule the environment declines leaves the
     * object exposed to the update, and that object is exactly the work this mode exists to keep.
     * </p>
     * <p>
     * Conflicts are held as well. Measured: a catalogue we had customised and the delivery had
     * deleted arrives with mustBeMerged set and GET_FROM_OTHER proposed, so the environment is
     * willing to carry out the deletion. Holding it and naming it in the queue turns a silent loss
     * into a decision somebody makes.
     * </p>
     *
     * @param manager the comparison service.
     * @param handle the process.
     * @param outcome the answer being built.
     */
    private static void protectOurs(IComparisonManager manager, ComparisonProcessHandle handle,
        Outcome outcome)
    {
        IComparisonSession session = manager.getComparisonSession(handle);
        if (session == null)
        {
            outcome.decisionsNote = "the comparison offered no session, so nothing was protected"; //$NON-NLS-1$
            return;
        }
        List<Long> hold = new ArrayList<>(outcome.oursNodes);
        hold.addAll(outcome.bothNodes);
        for (Long nodeId : hold)
        {
            if (!session.setMergeRuleToSubtree(nodeId, MergeRule.DO_NOT_MERGE))
            {
                if (outcome.protectionRefused.size() < PAGE_LIMIT)
                {
                    outcome.protectionRefused
                        .add(nameOf(session, nodeId) + describeAvailable(session, nodeId));
                }
                continue;
            }
            if (ruleOn(session, nodeId) != MergeRule.DO_NOT_MERGE)
            {
                if (outcome.protectionRefused.size() < PAGE_LIMIT)
                {
                    outcome.protectionRefused.add(nameOf(session, nodeId)
                        + ": took DO_NOT_MERGE and does not carry it"); //$NON-NLS-1$
                }
                continue;
            }
            outcome.protectedFromUpdate++;
            outcome.decided++;
        }
    }

    /**
     * Says why a configuration cannot be updated as an unchanged one.
     * <p>
     * The condition is strict on purpose, and all three counts matter. A zero for our own changes
     * is not enough: BOTH is a category of its own and can stand above zero while OURS is zero,
     * and UNKNOWN means there was no attribution at all - which is what a comparison without an
     * ancestor produces, and the case where taking the delivery whole would silently overwrite
     * work.
     * </p>
     *
     * <p>
     * <b>The whole safety of this route rests on the attribution being right.</b> Measured on a
     * stand: a catalogue we had customised and the delivery had deleted used to be attributed to
     * the vendor, which left all three counts at zero and would have let this run - taking the
     * delivery whole and removing the customisation without a word. The one-sided rule was
     * corrected for exactly that, and if it ever regresses this check goes back to being a promise
     * it cannot keep.
     * </p>
     *
     * @param outcome what the comparison found.
     * @return the reason, or <code>null</code> when the fast path is legitimate
     */
    private static String whyNotUnchanged(Outcome outcome)
    {
        if (!outcome.threeWay)
        {
            return "no merge was run: taking a delivery whole is only safe when the comparison " //$NON-NLS-1$
                + "has the delivery both sides came from, because without it nothing can tell a " //$NON-NLS-1$
                + "customisation from a vendor change. Pass ancestorPath."; //$NON-NLS-1$
        }
        if (!outcome.completed)
        {
            return "no merge was run: the comparison did not finish, so its counts say nothing " //$NON-NLS-1$
                + "about whether this configuration was reworked."; //$NON-NLS-1$
        }
        if (outcome.objectsChangedByUs > 0 || outcome.objectsChangedByBoth > 0
            || outcome.objectsChangedUnattributed > 0)
        {
            return "no merge was run: this configuration is not unchanged. " //$NON-NLS-1$
                + outcome.objectsChangedByUs + " object(s) were changed here, " //$NON-NLS-1$
                + outcome.objectsChangedByBoth + " on both sides, and " //$NON-NLS-1$
                + outcome.objectsChangedUnattributed + " could not be attributed. Taking the " //$NON-NLS-1$
                + "delivery whole would overwrite them. List them with changedBy=OURS and " //$NON-NLS-1$
                + "changedBy=BOTH, and decide about them."; //$NON-NLS-1$
        }
        return null;
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
        if (intent == Intent.UPDATE_KEEPING_OURS)
        {
            if (!outcome.threeWay)
            {
                outcome.mergeRefused = "no merge was run: keeping our changes means telling them " //$NON-NLS-1$
                    + "from the delivery's, and without the delivery both sides came from nothing " //$NON-NLS-1$
                    + "can. Pass ancestorPath."; //$NON-NLS-1$
                return;
            }
            if (!outcome.protectionRefused.isEmpty())
            {
                // Refused rather than merged partially. Going ahead would update the configuration
                // while leaving named customisations unprotected - the one outcome this mode is
                // supposed to make impossible.
                outcome.mergeRefused = "no merge was run: " + outcome.protectionRefused.size() //$NON-NLS-1$
                    + " object(s) could not be held back from the update, and they are named in " //$NON-NLS-1$
                    + "protectionRefused. Merging now would overwrite them."; //$NON-NLS-1$
                return;
            }
        }
        else if (intent == Intent.UPDATE_UNCHANGED)
        {
            String blocked = whyNotUnchanged(outcome);
            if (blocked != null)
            {
                outcome.mergeRefused = blocked;
                return;
            }
        }
        else if (outcome.decided == 0 && !outcome.decisionsRestored)
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
    /**
     * Closes the comparisons the registry dropped.
     * <p>
     * Called on the way out of any comparison, because that is a moment when talking to the
     * environment is already expected. A dropped session whose comparison is never closed leaves
     * the environment holding a comparison nobody can name.
     * </p>
     *
     * @param manager the comparison manager.
     */
    private static void closeDropped(IComparisonManager manager)
    {
        for (ComparisonSessions.Session dropped : ComparisonSessions.drainDropped())
        {
            if (dropped.handle instanceof ComparisonProcessHandle)
            {
                Activator.logDebug("closing dropped comparison session " + dropped.key); //$NON-NLS-1$
                release(manager, (ComparisonProcessHandle)dropped.handle);
            }
        }
    }

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
            walk(root, outcome, page, null);
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
    /**
     * Records a piece of a module when it is one that moved.
     * <p>
     * Three ways a piece can have moved, and the narrow one is not enough: it can exist on one side
     * only, it can differ between the sides, or the environment can mark it changed without the
     * plain difference flag - which is what a module told apart piece by piece produces.
     * </p>
     *
     * @param node the node inside the module.
     * @param outcome the answer being built.
     * @param module the module it belongs to.
     * @param oneSided whether it exists on one side only.
     * @param differs whether the sides disagree about it.
     */
    private static void recordIfPiece(ComparisonNode node, Outcome outcome, String module,
        boolean oneSided, boolean differs)
    {
        if (!outcome.sectionsWanted)
        {
            return;
        }
        boolean moved = oneSided || differs;
        if (!moved)
        {
            try
            {
                ComparisonFlags flags = node.getComparisonFlags();
                moved = flags != null && flags.hasChangedMainOther();
            }
            catch (RuntimeException | LinkageError willNotSay)
            {
                moved = false;
            }
        }
        if (moved)
        {
            recordSection(node, outcome, module, oneSided);
        }
    }

    /**
     * Records one differing piece of a module.
     *
     * @param node the node inside the module.
     * @param outcome the answer being built.
     * @param module the module it belongs to.
     * @param oneSided whether the piece exists on one side only.
     */
    private static void recordSection(ComparisonNode node, Outcome outcome, String module,
        boolean oneSided)
    {
        if (outcome.sections.size() >= PAGE_LIMIT)
        {
            outcome.moreSections = true;
            return;
        }
        Section section = new Section();
        section.module = module;
        section.nodeId = node.bmGetId();
        section.kind = node.getClass().getSimpleName().replace("Impl", ""); //$NON-NLS-1$
        section.changedBy = attribute(node, oneSided, outcome.threeWay);
        // A method section is a top node and names itself in full, which is what makes a decision
        // about one method possible at all. The feature id below is the fallback for a piece that
        // is not a top node: those carry no name of their own - ComparisonNode has no getName, and
        // FeatureComparisonNode offers only a numeric id - so they come back identified rather
        // than named, which is honest about being less than a name.
        try
        {
            if (node instanceof TopComparisonNode)
            {
                section.name = symlinkOf((TopComparisonNode)node, ComparisonSide.MAIN);
            }
            else if (node instanceof FeatureComparisonNode)
            {
                RelatedFeature feature = ((FeatureComparisonNode)node).getFeature();
                section.name = feature == null ? null : "feature " + feature.getFeatureId(); //$NON-NLS-1$
            }
        }
        catch (RuntimeException | LinkageError cannotName)
        {
            section.name = null;
        }
        if (section.name == null || section.name.isEmpty())
        {
            section.name = "node " + section.nodeId; //$NON-NLS-1$
        }
        outcome.sections.add(section);
    }

    /**
     * Names a top node on one side, without letting a node that will not answer stop the walk.
     *
     * @param node the node.
     * @param side the side to name it on.
     * @return the name, or <code>null</code> when the node has none there
     */
    private static String symlinkOf(TopComparisonNode node, ComparisonSide side)
    {
        try
        {
            return node.getSymlink(side);
        }
        catch (RuntimeException | LinkageError cannotName)
        {
            return null;
        }
    }

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
                ComparisonSide side = node.getNodeSide();
                if (side != ComparisonSide.OTHER && side != ComparisonSide.MAIN)
                {
                    return AttributionRule.UNKNOWN;
                }
                boolean presentOnMain = side == ComparisonSide.MAIN;
                boolean inAncestor = node.isAncestorObjectExists();
                return AttributionRule.forOneSided(presentOnMain, inAncestor,
                    inAncestor && survivorDiffersFromAncestor(node, side));
            }
            ComparisonFlags flags = node.getComparisonFlags();
            if (flags == null)
            {
                return "UNKNOWN"; //$NON-NLS-1$
            }
            return AttributionRule.forTwoSided(flags.hasDoubleChanges(),
                flags.hasDifferences(ComparisonSide.MAIN, ComparisonSide.COMMON_ANCESTOR),
                flags.hasDifferences(ComparisonSide.OTHER, ComparisonSide.COMMON_ANCESTOR));
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
    /**
     * Says whether the copy that survived a one-sided deletion was changed before it was deleted.
     * <p>
     * The question a deletion cannot answer by itself. An object the delivery removed matters
     * differently depending on whether we had reworked it; an object we removed matters
     * differently depending on whether the delivery had reworked it. Both are conflicts, and
     * neither is visible in which side the node stands on.
     * </p>
     *
     * @param node the one-sided node.
     * @param side the side it survives on.
     * @return <code>true</code> when the surviving copy differs from the ancestor
     */
    private static boolean survivorDiffersFromAncestor(ComparisonNode node, ComparisonSide side)
    {
        try
        {
            ComparisonFlags flags = node.getComparisonFlags();
            if (flags == null)
            {
                // No flags is not evidence of no change. Answering false here would restore the
                // very reading this method exists to correct, so the caller keeps the plain
                // one-sided answer and nothing is invented.
                return false;
            }
            return flags.hasDifferences(side, ComparisonSide.COMMON_ANCESTOR);
        }
        catch (RuntimeException | LinkageError flagsRefused)
        {
            Activator.logDebug("comparison: a one-sided node would not compare with its " //$NON-NLS-1$
                + "ancestor: " + flagsRefused); //$NON-NLS-1$
            return false;
        }
    }

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

    private static void walk(ComparisonNode node, Outcome outcome, Page page,
        String insideModule)
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
        if (node instanceof TopComparisonNode && !outcome.scopeRequested.isEmpty())
        {
            TopComparisonNode top = (TopComparisonNode)node;
            for (ComparisonSide side : new ComparisonSide[] {ComparisonSide.MAIN,
                ComparisonSide.OTHER, ComparisonSide.COMMON_ANCESTOR})
            {
                String named = symlinkOf(top, side);
                if (named != null && !named.isEmpty())
                {
                    outcome.namesInTree.add(named);
                }
            }
        }
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
                // Collected regardless of the page filter: the mode that protects customisations
                // has to reach every one of them, and the filter is what a caller reads, not what
                // an update covers.
                if (AttributionRule.OURS.equals(change.changedBy)
                    && outcome.oursNodes.size() < MASS_LIMIT)
                {
                    outcome.oursNodes.add(change.nodeId);
                }
                else if (AttributionRule.BOTH.equals(change.changedBy)
                    && outcome.bothNodes.size() < MASS_LIMIT)
                {
                    outcome.bothNodes.add(change.nodeId);
                    if (outcome.conflictQueue.size() < PAGE_LIMIT)
                    {
                        outcome.conflictQueue.add(change.main != null && !change.main.isEmpty()
                            ? change.main : String.valueOf(change.other));
                    }
                }
                if (page.wants(change))
                {
                    outcome.changedMatching++;
                    // Collected whole, not by the page: a decision about a class has to reach
                    // every member of it, and the page is what a person reads, not what the
                    // decision covers.
                    if (outcome.matchingNodes.size() < MASS_LIMIT)
                    {
                        outcome.matchingNodes.add(change.nodeId);
                    }
                    else
                    {
                        outcome.matchingTruncated = true;
                    }
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
        if (node instanceof TopComparisonNode)
        {
            String named = symlinkOf((TopComparisonNode)node, ComparisonSide.MAIN);
            if (named == null || named.isEmpty())
            {
                named = symlinkOf((TopComparisonNode)node, ComparisonSide.OTHER);
            }
            if (named != null && named.endsWith(".Module")) //$NON-NLS-1$
            {
                // A module is a top node of its own - measured, and the same holds for form and
                // template content. Everything below it belongs to that module.
                insideModule = named;
            }
            else if (insideModule != null)
            {
                // A piece of a module is a TOP node too, which is what made the first version of
                // this report empty: it recomputed the enclosing module for every top node and so
                // threw the context away at exactly the nodes it was looking for.
                recordIfPiece(node, outcome, insideModule, oneSided, differs);
            }
        }
        else if (insideModule != null)
        {
            recordIfPiece(node, outcome, insideModule, oneSided, differs);
        }
        if (!node.hasChildren())
        {
            return;
        }
        for (ComparisonNode child : node.<ComparisonNode> getChildren())
        {
            if (child != null)
            {
                walk(child, outcome, page, insideModule);
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
