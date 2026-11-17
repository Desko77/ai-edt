/*
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

/**
 * One client process tree whose membership is owned by this process.
 * <p>
 * The contract is deliberately about the tree, not about one {@code Process}. A parent that
 * exits before its worker is observed is exactly the case {@code Process.descendants()} cannot
 * answer: it reports the children of a live parent, and once the parent is gone there is no tree
 * left to enumerate. {@link ProcessHandle#parent()} and
 * {@link ProcessHandle#allProcesses()} exist, but neither proves that a late child belongs to
 * this run: the first sees one parent of one process, and the second is a snapshot of the whole
 * machine that another process can enter or leave between two reads. Neither is a containment
 * witness, so neither is used here.
 * </p>
 * <p>
 * The Windows implementation creates a private, unnamed job object, starts the configured client
 * suspended, assigns it to the job before it can execute one instruction, and resumes it. The
 * kernel then maintains the membership for the whole run: a child created by a member is a member
 * from creation, and membership cannot be broken while the job forbids breakaway. "No process
 * currently associated" is therefore a terminal observation, not a snapshot of a process tree.
 * </p>
 */
public interface OwnedProcessContainment extends AutoCloseable
{
    /**
     * The process id of the client this containment started.
     *
     * @return the root process id
     */
    long pid();

    /**
     * Whether any process of the owned tree is still associated with the containment.
     * <p>
     * The answer can lag the real exit while the operating system releases references; it cannot
     * run ahead of it. A caller that must not release a resource early treats a failure to answer
     * as alive.
     * </p>
     *
     * @return {@code true} while the tree may still be running
     */
    boolean isTreeAlive();

    /**
     * The root process exit code, once it has exited.
     *
     * @return the exit code, or {@code -1} when the root has not exited or could not be read
     */
    int exitCode();

    /**
     * Terminates every process of the tree and waits, within the budget, for the containment to
     * report none.
     *
     * @param waitMs how long to wait, in milliseconds
     * @return {@code true} only when the containment reports no associated process
     */
    boolean terminateAndAwait(long waitMs);

    /**
     * Whether this containment was established at spawn.
     * <p>
     * A Windows process that could not be assigned to its job remains suspended and owned by
     * this object, but it never ran and has no job membership. That state is reported as
     * {@code false} so the run answer can say so and keep the normal run key instead of reading
     * result files a client never wrote.
     * </p>
     *
     * @return {@code true} for a normally contained tree
     */
    boolean established();

    /**
     * The terminal fact recorded by the native containment, or that none was observed.
     * <p>
     * A value is written only where the operating system answered the question: the root
     * process handle was observed signalled, or the job was observed with no associated
     * process. Closing a job can terminate a tree without that observation, so a close never
     * turns {@link Proof#NOT_PROVEN} into a zero claim.
     * </p>
     */
    enum Proof
    {
        /** Nothing terminal was observed. */
        NOT_PROVEN,

        /** A root exit was observed and the job then reported no active process. */
        JOB_OBJECT_ACTIVE_PROCESSES_ZERO,

        /** The unassigned root process handle was observed signalled. */
        ROOT_HANDLE_EXIT
    }

    /**
     * The recorded terminal proof. Read-only; it never queries the operating system.
     *
     * @return the last observed proof, or {@link Proof#NOT_PROVEN}
     */
    Proof proof();

    /**
     * Releases the containment and reports whether that was confirmed.
     * <p>
     * This is the checked form of {@link #close()}: {@code false} means a handle or a suspended
     * process is still retained, and the caller must not release the rest of the run's resources
     * yet. Calling it again retries. {@link #close()} stays for {@link AutoCloseable} and throws
     * when this returns {@code false}.
     * </p>
     *
     * @return {@code true} only when the containment is closed
     */
    boolean tryClose();

    /**
     * Releases the containment.
     * <p>
     * Closing a job object with the kill-on-close limit terminates whatever is still associated,
     * so a close after a failed wait cannot leave a writer behind. The call is idempotent but
     * reports an unconfirmed release by throwing rather than silently leaving the handle owned;
     * callers that can retry use {@link #tryClose()}.
     * </p>
     *
     * @throws IllegalStateException when the release could not be confirmed
     */
    @Override
    void close();

    /**
     * Which operating-system mechanism owns this tree, for the answer.
     *
     * @return a stable identifier such as {@code windowsJobObject}
     */
    String backend();
}
