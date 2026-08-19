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
import com._1c.g5.v8.dt.compare.settings.model.IMergeSettingsModel;
import com._1c.g5.wiring.ServiceAccess;

import ru.aiedt.mcp.server.Activator;

/**
 * Reads a comparison of two or three configurations and reports what it found.
 * <p>
 * <b>This class cannot merge.</b> It never calls {@code startMerge} or
 * {@code startMergeIgnoringProblems}, and it holds no reference to them: a merge writes into a
 * configuration and a wrong one is not undone by a button, so the reading half ships on its own and
 * the deciding half is a separate question with a person in it.
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

    /** How often to ask the manager what the status is. */
    private static final long POLL_MS = 500L;

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
    public static Outcome compare(String mainProjectName, String otherPath, String ancestorPath)
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
     * Guarded separately from the tree, and this is not defensive habit - it is measured. Merge
     * problems come out of the pre-merge validation, and a comparison that will never be merged
     * never runs it: {@code getMergeProblems} then fails an internal assertion. That threw away a
     * finished tree twice over, because the counts were already computed and the exception
     * discarded the whole answer on the way out.
     * </p>
     * <p>
     * So the absence of problems is reported as what it is - not asked, because no merge was
     * prepared - rather than as an empty list, which would read as "nothing stands in the way".
     * </p>
     *
     * @param manager the comparison service.
     * @param handle the process.
     * @param outcome the answer being built.
     */
    private static void readProblems(IComparisonManager manager, ComparisonProcessHandle handle,
        Outcome outcome)
    {
        List<MergeProblem> problems;
        try
        {
            problems = manager.getMergeProblems(handle);
        }
        catch (Exception notApplicable)
        {
            outcome.problemsNote = "not read: merge problems come from the pre-merge validation, " //$NON-NLS-1$
                + "which a comparison that is never merged does not run (" //$NON-NLS-1$
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
    private static void walk(ComparisonNode node, Outcome outcome)
    {
        outcome.nodes++;
        if (node.isOneSideNode())
        {
            outcome.oneSided++;
        }
        else if (node.getComparisonFlags() != null && node.getComparisonFlags().hasDiffsMainOther())
        {
            outcome.differing++;
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
