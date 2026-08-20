/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the cross-process claim on an infobase.
 * <p>
 * What it has to get right is not exclusion - the platform enforces that on its own, and badly
 * enough that the failure reads as a broken tool. It is the naming: a refused operation has to say
 * who has the thing, and a claim left behind by a process that died must never be the reason an
 * operation can no longer run at all.
 * </p>
 */
public class MonopolyLockTest
{
    private static final String LOCK_DIR_PROPERTY = "aiedt.locks.dir"; //$NON-NLS-1$

    private static final String SUBJECT = "file:e:/bases/demo"; //$NON-NLS-1$

    private Path locks;

    private String previous;

    @Before
    public void useATemporaryLockDirectory() throws Exception
    {
        locks = Files.createTempDirectory("aiedt-locks"); //$NON-NLS-1$
        previous = System.getProperty(LOCK_DIR_PROPERTY);
        System.setProperty(LOCK_DIR_PROPERTY, locks.toString());
    }

    @After
    public void putItBack() throws Exception
    {
        if (previous == null)
        {
            System.clearProperty(LOCK_DIR_PROPERTY);
        }
        else
        {
            System.setProperty(LOCK_DIR_PROPERTY, previous);
        }
        try (Stream<Path> entries = Files.walk(locks))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /** The first claim goes through and nobody else can have it. */
    @Test
    public void oneClaimAtATime()
    {
        try (MonopolyLock first = MonopolyLock.take(SUBJECT, "update_database").orElseThrow()) //$NON-NLS-1$
        {
            assertNotNull(first);
            assertTrue("a second claim on the same subject must not go through", //$NON-NLS-1$
                MonopolyLock.take(SUBJECT, "export_extension").isEmpty()); //$NON-NLS-1$
        }
    }

    /** Releasing frees it for the next caller. */
    @Test
    public void releasingLetsTheNextOneIn()
    {
        MonopolyLock.take(SUBJECT, "update_database").orElseThrow().close(); //$NON-NLS-1$

        Optional<MonopolyLock> second = MonopolyLock.take(SUBJECT, "update_database"); //$NON-NLS-1$

        assertTrue("a released claim still blocked the next caller", second.isPresent()); //$NON-NLS-1$
        second.get().close();
    }

    /** Two different subjects do not queue behind each other. */
    @Test
    public void differentSubjectsDoNotBlockEachOther()
    {
        try (MonopolyLock one = MonopolyLock.take(SUBJECT, "update_database").orElseThrow(); //$NON-NLS-1$
            MonopolyLock two = MonopolyLock.take("file:e:/bases/other", "update_database").orElseThrow()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertNotNull(one);
            assertNotNull(two);
        }
    }

    /** A held claim names its holder, what they are doing, and what to do about it. */
    @Test
    public void theHolderIsNamed()
    {
        try (MonopolyLock held = MonopolyLock.take(SUBJECT, "update_database").orElseThrow()) //$NON-NLS-1$
        {
            String who = MonopolyLock.heldBy(SUBJECT);

            assertNotNull("a claim that cannot say who has it is half a claim", who); //$NON-NLS-1$
            assertTrue("it should name the instance: " + who, //$NON-NLS-1$
                who.contains(InstanceRegistry.selfTitle()));
            assertTrue("and what it is doing: " + who, who.contains("update_database")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("and what the reader can do: " + who, who.contains("try again")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Nothing held, nobody named. */
    @Test
    public void anUnclaimedSubjectHasNoHolder()
    {
        assertNull(MonopolyLock.heldBy(SUBJECT));
        assertNull("a null subject must not throw", MonopolyLock.heldBy(null)); //$NON-NLS-1$
    }

    /**
     * A claim from a process that is gone is taken over, not waited on.
     * <p>
     * This is the failure mode that matters most. One crash during an update would otherwise leave
     * a file that refuses every future update of that infobase, on every instance, until somebody
     * works out what the file is and deletes it by hand.
     * </p>
     */
    @Test
    public void aClaimFromADeadProcessIsTakenOver() throws Exception
    {
        writeClaim(deadPid(), "AI-EDT @ a workspace that is gone"); //$NON-NLS-1$

        Optional<MonopolyLock> mine = MonopolyLock.take(SUBJECT, "update_database"); //$NON-NLS-1$

        assertTrue("a dead holder's claim blocked a live caller", mine.isPresent()); //$NON-NLS-1$
        mine.get().close();
    }

    /** And a dead holder is not reported as holding anything. */
    @Test
    public void aDeadHolderIsNotNamed() throws Exception
    {
        writeClaim(deadPid(), "AI-EDT @ gone"); //$NON-NLS-1$

        assertNull(MonopolyLock.heldBy(SUBJECT));
    }

    /**
     * A claim whose pid is alive but whose process started at another time is stale.
     * <p>
     * Pids are recycled. The day one is recycled onto a stale claim is the day an operation refuses
     * for a reason nobody can find, so the start time is checked too.
     * </p>
     */
    @Test
    public void aRecycledPidDoesNotInheritTheClaim() throws Exception
    {
        Path file = writeClaim(ProcessHandle.current().pid(), "AI-EDT @ somebody else"); //$NON-NLS-1$
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            .replace("\"processStartedAt\":\"\"", "\"processStartedAt\":\"1999-01-01T00:00:00Z\""); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));

        Optional<MonopolyLock> mine = MonopolyLock.take(SUBJECT, "update_database"); //$NON-NLS-1$

        assertTrue("a claim from an earlier life of this pid blocked a live caller", //$NON-NLS-1$
            mine.isPresent());
        mine.get().close();
    }

    /** Rubbish in the claim file is treated as no claim, and cleared. */
    @Test
    public void anUnreadableClaimIsNotAClaim() throws Exception
    {
        Path file = claimFile();
        Files.write(file, "not json at all".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        Optional<MonopolyLock> mine = MonopolyLock.take(SUBJECT, "update_database"); //$NON-NLS-1$

        assertTrue("an unparseable file is a claim nobody could ever release", mine.isPresent()); //$NON-NLS-1$
        mine.get().close();
    }

    /** Nothing is claimed for a subject that does not exist. */
    @Test
    public void thereIsNothingToClaimWithoutASubject()
    {
        assertTrue(MonopolyLock.take(null, "update_database").isEmpty()); //$NON-NLS-1$
        assertTrue(MonopolyLock.take("", "update_database").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Closing twice is not an error - the finally that releases it may run after an early close. */
    @Test
    public void closingTwiceIsHarmless()
    {
        MonopolyLock held = MonopolyLock.take(SUBJECT, "update_database").orElseThrow(); //$NON-NLS-1$
        held.close();
        held.close();

        assertFalse("the claim should be gone, not resurrected", //$NON-NLS-1$
            Files.exists(claimFile()));
    }

    /**
     * The claim, which is the file that comes and goes.
     * <p>
     * Named by its suffix rather than taken as "the first file here": two files live in this
     * directory now. The claim says who holds the subject and is deleted on release; beside it sits
     * the file the operating system lock is taken on, which is created once and deliberately never
     * removed - deleting a lock file while another process waits on it would leave a lock nobody
     * holds.
     * </p>
     *
     * @return the claim file's path, whether or not it exists
     */
    private Path claimFile() throws RuntimeException
    {
        try (Stream<Path> entries = Files.list(locks))
        {
            return entries.filter(path -> path.getFileName().toString().endsWith(".lock.json")) //$NON-NLS-1$
                .findFirst()
                .orElse(locks.resolve(digestOfSubject() + ".lock.json")); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return locks.resolve(digestOfSubject() + ".lock.json"); //$NON-NLS-1$
        }
    }

    /**
     * A SECOND PROCESS cannot take what this one holds.
     * <p>
     * Every other test here runs in one JVM, where an overlapping lock is refused by Java itself
     * rather than by the operating system - so none of them actually proves the property the whole
     * design now rests on. This one starts a real second process.
     * </p>
     * <p>
     * It needs no plugin classes: since ownership became an OS file lock, the probe only has to
     * open the same file and try. Java runs a single source file directly, so there is nothing to
     * compile and no classpath to get wrong - the one dependency is a JDK, and where there is none
     * the test says so instead of failing.
     * </p>
     */
    @Test
    public void aSecondProcessIsRefusedWhileThisOneHolds() throws Exception
    {
        Path launcher = Path.of(System.getProperty("java.home"), "bin", //$NON-NLS-1$ //$NON-NLS-2$
            System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        org.junit.Assume.assumeTrue("no java to start a second process with", //$NON-NLS-1$
            Files.isRegularFile(launcher));

        Path probe = locks.resolve("LockProbe.java"); //$NON-NLS-1$
        Files.write(probe, ("import java.nio.channels.*;import java.nio.file.*;" //$NON-NLS-1$
            + "public class LockProbe{public static void main(String[] a)throws Exception{" //$NON-NLS-1$
            + "try(FileChannel c=FileChannel.open(Path.of(a[0])," //$NON-NLS-1$
            + "StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE)){" //$NON-NLS-1$
            + "FileLock l=c.tryLock();" //$NON-NLS-1$
            + "System.out.println(l==null?\"BUSY\":\"FREE\");" //$NON-NLS-1$
            + "if(l!=null)l.release();}}}").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        // The claim and the lock are two files beside each other, and only the second is what the
        // operating system holds. digestOfSubject() names the claim, so the suffix is swapped -
        // appending to it produced a third path nobody locks, and the probe duly found it free.
        // A test that measures the wrong file passes for the wrong reason; this one failed for
        // the right one.
        Path lockFile = locks.resolve(
            digestOfSubject().replace(".lock.json", ".lock")); //$NON-NLS-1$ //$NON-NLS-2$

        MonopolyLock held = MonopolyLock.take(SUBJECT, "update_database").orElseThrow(); //$NON-NLS-1$
        String whileHeld;
        try
        {
            whileHeld = askAnotherProcess(launcher, probe, lockFile);
        }
        finally
        {
            held.close();
        }
        String afterRelease = askAnotherProcess(launcher, probe, lockFile);

        assertEquals("another process must be refused while this one holds it", "BUSY", whileHeld); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("and must get it once this one lets go", "FREE", afterRelease); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Runs the probe and returns its verdict.
     *
     * @param launcher the java launcher.
     * @param probe the single-file source to run.
     * @param lockFile the file to try locking.
     * @return {@code BUSY} or {@code FREE}
     * @throws Exception when the process cannot be run at all
     */
    private static String askAnotherProcess(Path launcher, Path probe, Path lockFile) throws Exception
    {
        ProcessBuilder builder = new ProcessBuilder(launcher.toString(), probe.toString(),
            lockFile.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
        {
            output = reader.lines().reduce("", (a, b) -> a + b.trim()); //$NON-NLS-1$
        }
        // Bounded: a probe that will not finish must fail the test rather than hang the suite.
        assertTrue("the probe process did not finish", //$NON-NLS-1$
            process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
        return output.contains("BUSY") ? "BUSY" : output.contains("FREE") ? "FREE" : output; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * The operating system holds this, not a heuristic about somebody else's process.
     * <p>
     * A pid plus a start time can only be re-judged by every reader, cannot see a process that is
     * alive but wedged, and leaves a window between calling a claim stale and replacing it. An
     * exclusive file lock has none of that and is dropped by the kernel when the holder dies. So a
     * second take must come back empty while the first is held, and must succeed once it is
     * released - measured here through the public API rather than by inspecting files.
     * </p>
     */
    @Test
    public void asecondTakeWaitsForTheFirstToLetGo()
    {
        MonopolyLock first = MonopolyLock.take(SUBJECT, "update_database").orElseThrow(); //$NON-NLS-1$
        try
        {
            assertTrue("while one holds it, nobody else may", //$NON-NLS-1$
                MonopolyLock.take(SUBJECT, "export_configuration_to_cf").isEmpty()); //$NON-NLS-1$
        }
        finally
        {
            first.close();
        }
        Optional<MonopolyLock> after = MonopolyLock.take(SUBJECT, "update_database"); //$NON-NLS-1$
        assertTrue("once released it must be takeable again", after.isPresent()); //$NON-NLS-1$
        after.get().close();
    }

    private String digestOfSubject()
    {
        // The same digest the lock uses - first sixteen bytes of SHA-256, hex.
        try
        {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256") //$NON-NLS-1$
                .digest(SUBJECT.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++)
            {
                hex.append(String.format("%02x", hash[i])); //$NON-NLS-1$
            }
            return hex + ".lock.json"; //$NON-NLS-1$
        }
        catch (Exception impossible)
        {
            throw new IllegalStateException(impossible);
        }
    }

    private Path writeClaim(long pid, String title) throws Exception
    {
        Path file = locks.resolve(digestOfSubject());
        String claim = "{\"pid\":" + pid + ",\"processStartedAt\":\"\",\"title\":\"" + title //$NON-NLS-1$ //$NON-NLS-2$
            + "\",\"operation\":\"update_database\",\"subject\":\"" + SUBJECT //$NON-NLS-1$
            + "\",\"takenAt\":\"2026-08-19T00:00:00Z\"}"; //$NON-NLS-1$
        Files.write(file, claim.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** A pid nothing is running under. Walked upward so a busy machine cannot make it flaky. */
    private static long deadPid()
    {
        for (long candidate = 900_000L; candidate < 999_999L; candidate++)
        {
            if (ProcessHandle.of(candidate).isEmpty())
            {
                return candidate;
            }
        }
        throw new IllegalStateException("no free pid to pretend with"); //$NON-NLS-1$
    }
}
