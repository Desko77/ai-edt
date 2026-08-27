/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Exercises the task layer over the pending machinery: what a poll reports while work runs, what it
 * reports once the work is done, and the two ways a task ends without an answer.
 */
public class TaskDirectoryTest
{
    private TaskDirectory directory;

    private final java.util.List<String> keysToClean = new java.util.ArrayList<>();

    @Before
    public void emptyDirectory()
    {
        directory = TaskDirectory.getInstance();
        directory.clear();
    }

    @After
    public void releaseRuns()
    {
        for (String key : keysToClean)
        {
            PendingWorkRegistry.GENERIC.remove(key);
        }
        keysToClean.clear();
        directory.clear();
    }

    /** While the work runs, a poll says so and says nothing else. */
    @Test
    public void aPollDuringTheWorkSaysItIsWorking() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        String key = start("still-going", release); //$NON-NLS-1$

        TaskDirectory.Task task = directory.open(key, "find_references", "find_references", args()); //$NON-NLS-1$ //$NON-NLS-2$

        TaskDirectory.Task polled = directory.poll(task.taskId);
        assertEquals(TaskDirectory.WORKING, polled.status);
        assertNull("nothing has finished, so there is nothing to report", polled.result); //$NON-NLS-1$
        assertFalse(polled.isTerminal());
        release.countDown();
    }

    /**
     * The first poll to find the work done takes the answer out and keeps it.
     * <p>
     * The keeping is the point. The registry drops a result when it is collected - right for a
     * caller that asked once - so without this the second poll of a completed task would find
     * nothing where the answer had been.
     * </p>
     */
    @Test
    public void aFinishedTaskKeepsItsAnswerForEveryLaterPoll() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        String key = start("the answer", release); //$NON-NLS-1$
        release.countDown();
        awaitCompletion(key);

        TaskDirectory.Task task = directory.open(key, "code_review", "code_review", args()); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(TaskDirectory.COMPLETED, directory.poll(task.taskId).status);
        assertEquals("the answer", directory.poll(task.taskId).result); //$NON-NLS-1$
        assertEquals("a second poll must report the same finished task", //$NON-NLS-1$
            "the answer", directory.poll(task.taskId).result);
        assertNotNull("the task keeps its own copy and does not consume the run: two tasks over " //$NON-NLS-1$
            + "one run would otherwise compete for a single result", //$NON-NLS-1$
            PendingWorkRegistry.GENERIC.get(key));
    }

    /**
     * Two tasks over one run both get the answer.
     * <p>
     * Identical calls coalesce onto one entry in the registry. When a task took the answer OUT of
     * the registry, the second task found nothing and reported the run had vanished - so two agents
     * asking the same question got one answer and one lie. They are the same run, so they are now
     * the same task.
     * </p>
     *
     * @throws Exception when the seeded run cannot be driven
     */
    @Test
    public void twoTasksOverOneRunAreOneTask() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        String key = start("shared", release); //$NON-NLS-1$

        TaskDirectory.Task first = directory.open(key, "code_review", "code_review", args()); //$NON-NLS-1$ //$NON-NLS-2$
        TaskDirectory.Task second = directory.open(key, "code_review", "code_review", args()); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("one run is one task", first.taskId, second.taskId); //$NON-NLS-1$
        release.countDown();
        awaitCompletion(key);
        awaitSettled(first.taskId);
        assertEquals("shared", directory.poll(first.taskId).result); //$NON-NLS-1$
        assertEquals("shared", directory.poll(second.taskId).result); //$NON-NLS-1$
    }

    /**
     * The answer lands when the work finishes, not when somebody happens to poll.
     * <p>
     * The registry drops an uncollected result after five minutes while a task advertises thirty.
     * Collecting on poll therefore made the advertised lifetime a promise the server could not
     * keep; subscribing to the work makes it one it does not have to.
     * </p>
     *
     * @throws Exception when the seeded run cannot be driven
     */
    @Test
    public void theAnswerArrivesWithoutBeingPolledFor() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        String key = start("unpolled", release); //$NON-NLS-1$
        TaskDirectory.Task task = directory.open(key, "find_references", "find_references", args()); //$NON-NLS-1$ //$NON-NLS-2$

        release.countDown();
        awaitCompletion(key);
        for (int attempt = 0; attempt < 100 && !task.isTerminal(); attempt++)
        {
            Thread.sleep(20L);
        }

        assertEquals("the task settled on its own", TaskDirectory.COMPLETED, task.status); //$NON-NLS-1$
        assertEquals("unpolled", task.result); //$NON-NLS-1$
    }

    /**
     * A key no domain holds is reported as failed, with the reason, rather than as working forever.
     * <p>
     * It happens for real: a caller of the older revision that re-issues the same call produces the
     * same key and collects the result, leaving the task holding a name for something that is gone.
     * Reporting {@code working} in that state would have a client polling until the TTL for an
     * answer that will never come.
     * </p>
     */
    @Test
    public void aTaskWhoseRunHasVanishedSaysSoInsteadOfWaitingForever()
    {
        TaskDirectory.Task task =
            directory.open("no-domain-has-this", "export_object", "export_object", args()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        TaskDirectory.Task polled = directory.poll(task.taskId);

        assertEquals(TaskDirectory.FAILED, polled.status);
        assertNotNull(polled.failure);
        assertTrue("the reason should name both ways this happens: " + polled.failure, //$NON-NLS-1$
            polled.failure.contains("collected") && polled.failure.contains("evicted")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Cancelling moves the task to its terminal state and says the work may still be finishing. */
    @Test
    public void cancellingIsTerminalAndSaysItIsCooperative() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        String key = start("never collected", release); //$NON-NLS-1$
        TaskDirectory.Task task = directory.open(key, "update_database", "update_database", args()); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(directory.cancel(task.taskId));

        TaskDirectory.Task polled = directory.poll(task.taskId);
        assertEquals(TaskDirectory.CANCELLED, polled.status);
        assertTrue("a cancelled task must not be quietly revived by a later poll", //$NON-NLS-1$
            polled.isTerminal());
        assertTrue("the message should not promise the work stopped: " + polled.statusMessage, //$NON-NLS-1$
            polled.statusMessage.contains("may still be finishing")); //$NON-NLS-1$
        release.countDown();
    }

    /** An id nobody handed out is not a task, and cancelling one is not a success. */
    @Test
    public void anUnknownIdIsNotATask()
    {
        assertNull(directory.poll("made-up")); //$NON-NLS-1$
        assertNull("a null id must not throw", directory.poll(null)); //$NON-NLS-1$
        assertFalse(directory.cancel("made-up")); //$NON-NLS-1$
        assertFalse(directory.cancel(null));
    }

    /** Opening a task does not ask the work how it is getting on - the first poll does that. */
    @Test
    public void openingDoesNotPoll()
    {
        TaskDirectory.Task task = directory.open("anything", "op", "tool", args()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("a freshly opened task starts out working, whatever the run is doing", //$NON-NLS-1$
            TaskDirectory.WORKING, task.status);
        assertEquals(1, directory.size());
    }

    /** Arguments are copied, so a caller reusing its map cannot rewrite a task's history. */
    @Test
    public void argumentsAreCopiedNotBorrowed()
    {
        Map<String, String> mutable = args();
        TaskDirectory.Task task = directory.open("k", "op", "tool", mutable); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        mutable.put("fqn", "something else"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("CommonModule.Whatever", task.arguments.get("fqn")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String start(String produces, CountDownLatch release)
    {
        String key = PendingWorkRegistry.computeRunKey("test", produces, //$NON-NLS-1$
            String.valueOf(System.nanoTime()));
        keysToClean.add(key);
        PendingWorkRegistry.GENERIC.getOrStart(key, () -> {
            try
            {
                release.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
            return produces;
        });
        return key;
    }

    private static void awaitCompletion(String key) throws InterruptedException
    {
        PendingWorkRegistry.PendingEntry entry = PendingWorkRegistry.GENERIC.get(key);
        assertNotNull(entry);
        for (int attempt = 0; attempt < 100 && !entry.isDone(); attempt++)
        {
            Thread.sleep(20L);
        }
        assertTrue("the work never finished", entry.isDone()); //$NON-NLS-1$
    }

    /**
     * Waits for a task to have taken the answer, not merely for the work to have finished.
     * <p>
     * The two are different moments. A task settles from the future's {@code whenComplete} callback,
     * which by definition runs AFTER the future reports itself done - so a poll issued between the
     * two sees a task that has not recorded anything yet. Waiting on {@code isDone} alone made this
     * suite fail about once in a long run of green ones.
     * </p>
     * <p>
     * The production order is the other way round and is deliberate: {@code settle} writes the
     * result before the status, so a reader that sees COMPLETED finds the result already there.
     * Nothing here is working around a defect - the test was watching the wrong signal.
     * </p>
     *
     * @param taskId the task to wait for
     * @throws InterruptedException when the wait is cut short
     */
    private void awaitSettled(String taskId) throws InterruptedException
    {
        for (int attempt = 0; attempt < 100; attempt++)
        {
            String status = directory.poll(taskId).status;
            if (TaskDirectory.COMPLETED.equals(status) || TaskDirectory.FAILED.equals(status))
            {
                return;
            }
            Thread.sleep(20L);
        }
        assertTrue("the task never recorded what the work produced", false); //$NON-NLS-1$
    }

    private static Map<String, String> args()
    {
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("fqn", "CommonModule.Whatever"); //$NON-NLS-1$ //$NON-NLS-2$
        return arguments;
    }
}
