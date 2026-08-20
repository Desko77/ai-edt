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
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.MergeRule;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;
import com._1c.g5.v8.dt.compare.settings.model.IMergeSettingsModel;
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

    /** How many changed objects are named before the list is cut short. */
    private static final int CHANGED_LIMIT = 500;

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

        /** The status the process ended on, named as the environment names it. */
        public String status;

        /** Nodes in the tree, counted by walking it. */
        public int nodes;

        /** Nodes the environment marked as differing between our side and the other one. */
        public int differing;

        /** Nodes present on one side only. */
        public int oneSided;

        /**
         * The metadata objects that moved, named. Cut at {@link #CHANGED_LIMIT}; the counts above
         * stay whole, so a truncated list never makes the change look smaller than it is.
         */
        public final List<Change> changed = new ArrayList<>();

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
        List<Decision> decisions, String decisionsPath, Intent intent)
    {
        Outcome outcome = new Outcome();
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

            ComparisonProcessSettings settings = new ComparisonProcessSettings(
                MatchingStrategy.UUID_THEN_NAME, Collections.emptyList(), new NoMergeSettings());
            CompareMergeProcessBatch batch =
                new CompareMergeProcessBatch(new CompareMergeProcessDescriptor(handle, settings));

            manager.startComparison(batch);
            awaitFinish(manager, handle, batch, outcome);
            if (!outcome.completed)
            {
                return outcome;
            }
            read(manager, handle, outcome);
            decide(manager, handle, decisions, decisionsPath, outcome);
            merge(manager, handle, batch, intent, outcome);
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
            if (target == null)
            {
                outcome.decisionsNote = "no object named " + decision.object //$NON-NLS-1$
                    + " is among the changes, so no decision was recorded for it"; //$NON-NLS-1$
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
            session.setMergeRuleToSubtree(target.nodeId, rule);
            target.decision = rule.name();
            outcome.decided++;
        }
        if (path == null || path.isEmpty())
        {
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
        if (outcome.decided == 0)
        {
            outcome.mergeRefused = "no merge was run: there are no decisions to apply. Pass " //$NON-NLS-1$
                + "decisions naming what to do with each object first."; //$NON-NLS-1$
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
        Outcome outcome)
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
            walk(root, outcome);
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
    private static Change describeChange(TopComparisonNode node, boolean oneSided)
    {
        Change change = new Change();
        change.oneSided = oneSided;
        change.nodeId = node.bmGetId();
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

    private static void walk(ComparisonNode node, Outcome outcome)
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
        if ((oneSided || differs) && node instanceof TopComparisonNode
            && outcome.changed.size() < CHANGED_LIMIT)
        {
            Change change = describeChange((TopComparisonNode)node, oneSided);
            // The configuration root is a top node too, and it names itself on no side at all. It
            // differs whenever anything inside it does, so listing it says only "something
            // changed" - which the counts already said. An entry that identifies nothing is noise
            // in a list whose whole purpose is to identify.
            if (change.main != null && !change.main.isEmpty()
                || change.other != null && !change.other.isEmpty()
                || change.ancestor != null && !change.ancestor.isEmpty() || change.note != null)
            {
                outcome.changed.add(change);
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
                walk(child, outcome);
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
