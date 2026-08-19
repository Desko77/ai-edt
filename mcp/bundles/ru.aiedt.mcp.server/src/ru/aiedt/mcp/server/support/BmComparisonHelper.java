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
    /** How long to wait for a comparison before giving up on it. */
    private static final long WAIT_LIMIT_MS = 30L * 60L * 1000L;

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

        /** Why nothing could be said, when nothing could. */
        public String cannotTell;
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
            IComparisonDataSourceDescriptor main =
                new V8ProjectComparisonDataSourceDescriptor(mainProjectName, null);

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
            Activator.logWarning("Comparison failed: " + e.getMessage()); //$NON-NLS-1$
            outcome.cannotTell = "the comparison could not be run: " + e.getMessage(); //$NON-NLS-1$
            return outcome;
        }
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
        return new FileSystemComparisonDataSourceDescriptor(directory);
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
                outcome.cannotTell = "the comparison failed: " + failure.getMessage(); //$NON-NLS-1$
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
        outcome.cannotTell = "the comparison did not finish within the time allowed; last status " //$NON-NLS-1$
            + outcome.status;
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
        List<MergeProblem> problems = manager.getMergeProblems(handle);
        if (problems != null)
        {
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
