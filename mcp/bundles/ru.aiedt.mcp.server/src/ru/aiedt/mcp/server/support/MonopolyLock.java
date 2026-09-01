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
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;

/**
 * A claim on something only one process may work on at a time, held across processes and named.
 * <p>
 * Several EDT instances on one machine is now ordinary here - seven at once has happened - and the
 * ones that share an infobase collide over it. What the collision looks like today is the reason
 * this exists: the second operation does not fail with "somebody else has it", it fails with a
 * platform error about a locked configuration, or hangs until a timeout. Both read as a broken tool.
 * The reader then guesses between a running client, a neighbouring EDT and a real defect, and a
 * wrong guess sends them looking in the wrong place.
 * </p>
 * <p>
 * So the claim is a file, and the file says who. Not a mutex - a mutex would only say "taken", which
 * is the half of the answer that was never missing.
 * </p>
 * <p>
 * Whoever holds it is a process, and processes die badly. A claim whose process is gone is not a
 * claim, and is taken over rather than waited on - the same liveness test the instance registry
 * uses, pid plus start time, so a recycled pid cannot inherit somebody else's lock.
 * </p>
 */
public final class MonopolyLock implements AutoCloseable
{
    /** Where claims live, unless the tests point somewhere else. */
    private static final String LOCK_DIR_PROPERTY = "aiedt.locks.dir"; //$NON-NLS-1$

    private static final String SUFFIX = ".lock.json"; //$NON-NLS-1$

    private final Path file;

    /**
     * Names a failure briefly, by type when it carries no message.
     *
     * @param e the failure.
     * @return a description that is never empty
     */
    private static String describeBriefly(Throwable e)
    {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    /**
     * What this instance holds right now, by infobase.
     * <p>
     * The tracking future is NOT this. Cancelling a run removes its registry entry while the
     * platform call carries on - {@code CompletableFuture.cancel} does not interrupt the worker -
     * so after a cancellation nothing in the registry says the infobase is still being written to.
     * This does, because it is cleared by {@link #close}, which runs when the blocking call
     * finally returns. That return is the confirmed stop; nothing before it is.
     * </p>
     * <p>
     * Keyed by infobase, so work on one says nothing about another.
     * </p>
     */
    private static final java.util.Map<String, Outstanding> HELD_HERE =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** What one infobase is busy with, and since when. */
    public static final class Outstanding
    {
        /** The operation that took the lock. */
        public final String operation;

        /** When it took it. */
        public final long since;

        Outstanding(String operation, long since)
        {
            this.operation = operation;
            this.since = since;
        }

        /** @return how long it has been held, in milliseconds */
        public long heldMs()
        {
            return System.currentTimeMillis() - since;
        }
    }

    /**
     * What this instance is holding on one infobase, if anything.
     *
     * @param subject identity of the infobase.
     * @return the outstanding work, or <code>null</code> when this instance holds nothing on it
     */
    public static Outstanding outstandingHere(String subject)
    {
        return subject == null || subject.isEmpty() ? null : HELD_HERE.get(subject);
    }

    /**
     * Every infobase this instance is holding.
     *
     * @return a snapshot, keyed by infobase identity; never <code>null</code>
     */
    public static java.util.Map<String, Outstanding> outstandingHere()
    {
        return new java.util.LinkedHashMap<>(HELD_HERE);
    }

    /**
     * Set once released, so a second {@link #close()} does nothing.
     * <p>
     * Not merely tidiness. Without it the sequence "we release, somebody else takes it, we close
     * again" deletes THEIR claim - and the second close is not hypothetical: a caller may close
     * early and still have a {@code finally} to run.
     * </p>
     */
    private final java.util.concurrent.atomic.AtomicBoolean released =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** The operating system's answer to who holds this; {@code null} when there is none to hold. */
    private final Holding holding;

    /**
     * Why this claim protects nothing, when it does not.
     * <p>
     * A claim that could not be written comes back GRANTED so the work is never blocked by the
     * bookkeeping - that is deliberate, and it is not what keeps the infobase safe. But a caller
     * handed one has no way to tell it from the real thing, and so reports a protected run when
     * nothing was protecting it. The reason travels with the claim instead.
     * </p>
     */
    private final String unprotected;

    private MonopolyLock(Path file)
    {
        this(file, null, null);
    }

    private MonopolyLock(String unprotectedReason)
    {
        this(null, null, unprotectedReason);
    }

    /**
     * Why this claim guards nothing, or <code>null</code> when it guards what it says.
     *
     * @return the reason, for an answer that must not overstate what it protected
     */
    public String unprotectedReason()
    {
        return unprotected;
    }

    private MonopolyLock(Path file, Holding holding)
    {
        this(file, holding, null);
    }

    /** The infobase this lock is on, when it is a real lock on one. */
    private final String subject;

    private MonopolyLock(Path file, Holding holding, String unprotected)
    {
        this(file, holding, unprotected, null);
    }

    private MonopolyLock(Path file, Holding holding, String unprotected, String subject)
    {
        this.file = file;
        this.holding = holding;
        this.unprotected = unprotected;
        this.subject = subject;
    }

    /**
     * An exclusive lock on a file, held for as long as this object is.
     * <p>
     * This is the part the operating system enforces. The claim beside it says WHO holds the
     * subject and is read by people; this says THAT it is held, and is read by the kernel. The
     * distinction matters when a holder dies: a claim file survives its process and has to be
     * judged stale by inspecting a pid, while a lock is dropped by the kernel the moment the
     * process ends - crash, kill, power loss alike.
     * </p>
     */
    private static final class Holding
    {
        private final java.nio.channels.FileChannel channel;

        private final java.nio.channels.FileLock lock;

        private Holding(java.nio.channels.FileChannel channel, java.nio.channels.FileLock lock)
        {
            this.channel = channel;
            this.lock = lock;
        }

        /**
         * Takes the lock, or reports that somebody else has it.
         *
         * @param path the file to lock; created when absent.
         * @return the holding, or {@code null} when it is held elsewhere
         * @param heldHere set when the lock could not be taken because THIS process holds it.
         * @throws IOException when the file cannot be opened at all
         */
        static Holding tryTake(Path path, java.util.concurrent.atomic.AtomicBoolean heldHere)
            throws IOException
        {
            java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try
            {
                java.nio.channels.FileLock lock = channel.tryLock();
                if (lock == null)
                {
                    channel.close();
                    return null;
                }
                return new Holding(channel, lock);
            }
            catch (java.nio.channels.OverlappingFileLockException mine)
            {
                // Held by THIS process already. That is NOT the same answer as a neighbour holding
                // it. A neighbour finishes and the next attempt succeeds; this one is us, and if
                // the call that took it is not coming back, no attempt will ever succeed. Telling
                // the caller to run it again would be advice that cannot work.
                channel.close();
                heldHere.set(true);
                return null;
            }
            catch (IOException | RuntimeException failed)
            {
                channel.close();
                throw failed;
            }
        }

        /** Lets the operating system know this is no longer held. */
        void release()
        {
            try
            {
                lock.release();
            }
            catch (IOException | RuntimeException e)
            {
                Activator.logWarning("Could not release a file lock: " + e.getMessage()); //$NON-NLS-1$
            }
            try
            {
                channel.close();
            }
            catch (IOException | RuntimeException e)
            {
                Activator.logWarning("Could not close a lock channel: " + e.getMessage()); //$NON-NLS-1$
            }
        }
    }

    /**
     * Takes the claim, or says who has it.
     * <p>
     * There is deliberately no waiting. These operations run for minutes and the caller is an agent
     * with its own patience; a blocked call that eventually times out tells it nothing, while
     * "instance X is updating this infobase" tells it what to do - go there, or wait and retry.
     * </p>
     *
     * @param subject what is being claimed - an infobase connection string, a project name; only its
     *            identity matters, and it is hashed for the file name.
     * @param operation what is being done to it, for the holder's benefit.
     * @return the held claim, or empty when somebody else has it. A claim that cannot be written at
     *         all - an unwritable home directory - comes back held but backed by nothing: making
     *         conflicts legible is what this is for, and it is not what keeps the infobase safe, so
     *         it must never be the reason an operation cannot run.
     */
    public static Optional<MonopolyLock> take(String subject, String operation)
    {
        return take(subject, operation, new java.util.concurrent.atomic.AtomicBoolean());
    }

    /**
     * Takes the claim, reporting separately when the refusal came from this same process.
     *
     * @param subject identity of the infobase.
     * @param operation what the claim is for.
     * @param heldHere set when the refusal is this process holding its own lock.
     * @return the claim, or empty
     */
    public static Optional<MonopolyLock> take(String subject, String operation,
        java.util.concurrent.atomic.AtomicBoolean heldHere)
    {
        if (subject == null || subject.isEmpty())
        {
            // Nothing to claim. Better to let the operation through than to invent a key and
            // serialise unrelated work behind it.
            return Optional.empty();
        }
        Path file = fileFor(subject);
        Holding holding = null;
        try
        {
            Files.createDirectories(file.getParent());
            // The operating system decides who holds this, not a guess about somebody else's
            // process. A pid plus a start time is a good heuristic and still only a heuristic: it
            // has to be re-evaluated by every reader, it cannot see a process that is alive but
            // wedged, and between deciding a claim is stale and replacing it there is a window.
            // An exclusive lock on a file has none of that, and it is released by the kernel when
            // the holder dies - including when it dies without releasing anything.
            holding = Holding.tryTake(lockFileFor(subject), heldHere);
            if (holding == null)
            {
                return Optional.empty();
            }
            JsonObject existing = readLiveClaim(file);
            if (existing != null)
            {
                // The lock is ours but a claim from someone else is lying there. It cannot be a
                // live holder - they would hold the lock - so it is a leftover, and the reader
                // below would report a stranger as the holder of a lock we own.
                Files.deleteIfExists(file);
            }
            // Written whole somewhere else, then moved into place. The move is the atomic step:
            // it either creates the claim complete or fails because somebody else got there, and
            // there is no instant at which the claim exists half-written. Writing straight to the
            // target with CREATE_NEW is atomic about EXISTENCE but not about CONTENT - a reader
            // arriving between the create and the write sees an empty file, calls it unreadable,
            // deletes it and takes the lock, and then two processes hold it.
            Path pending = Files.createTempFile(file.getParent(), "claim-", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                Files.write(pending, record(subject, operation).toString().getBytes(StandardCharsets.UTF_8));
                Files.move(pending, file);
            }
            catch (IOException | RuntimeException failed)
            {
                Files.deleteIfExists(pending);
                throw failed;
            }
            MonopolyLock taken = new MonopolyLock(file, holding, null, subject);
            holding = null;
            HELD_HERE.put(subject, new Outstanding(operation, System.currentTimeMillis()));
            return Optional.of(taken);
        }
        catch (java.nio.file.FileAlreadyExistsException raced)
        {
            // Somebody claimed it between the read and the move. That is the race working, not a
            // failure: they hold it, we do not.
            return Optional.empty();
        }
        catch (java.nio.file.FileSystemException broken)
        {
            // The store itself is unusable - the lock directory is a file, a claim cannot be
            // removed. Reported as unavailable rather than as contention: telling the caller
            // somebody else holds the infobase would send them looking for a neighbour that does
            // not exist.
            Activator.logWarning("The lock store is unusable (" + broken.getMessage() //$NON-NLS-1$
                + "); running " + operation + " without a cross-process claim."); //$NON-NLS-1$ //$NON-NLS-2$
            return Optional.of(new MonopolyLock(
                "the lock store is unusable (" + broken.getMessage() + ")")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (IOException | RuntimeException e)
        {
            // A claim that cannot be written must not stop the work. This makes conflicts legible;
            // it is not the thing that keeps the infobase safe - the platform's own monopoly is.
            Activator.logWarning("Could not take the lock on " + operation + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return Optional.of(new MonopolyLock(
                "the claim could not be written (" + describeBriefly(e) + ")")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            // Every path out of here that did not hand the lock to a MonopolyLock has to
            // let go of it. A lock held by nobody is held until this EDT exits, and the
            // operation it guards then refuses for a reason no one can find.
            if (holding != null)
            {
                holding.release();
            }
        }
    }

    /**
     * The outcome of one attempt: either the claim, or who has it.
     * <p>
     * One object rather than a take followed by a separate question, because between those two
     * calls the holder can finish - and the answer then read "another instance is working on this
     * infobase. null", which looks like a defect in the tool rather than a race the caller can
     * simply retry.
     * </p>
     */
    public static final class Claim
        implements AutoCloseable
    {
        /** The claim, when this caller got it. */
        public final MonopolyLock held;

        /** Who has it, when this caller did not. Never {@code null} when {@link #held} is. */
        public final String heldBy;

        Claim(MonopolyLock held, String heldBy)
        {
            this.held = held;
            this.heldBy = heldBy;
        }

        /** @return true when the caller may proceed */
        public boolean granted()
        {
            return held != null;
        }

        @Override
        public void close()
        {
            if (held != null)
            {
                held.close();
            }
        }
    }

    /**
     * Tries to claim something, and says who has it when the answer is no.
     *
     * @param subject what is being claimed.
     * @param operation what is being done to it.
     * @return the outcome, never {@code null}
     */
    /**
     * What a caller is told when this instance holds the lock and is not giving it back.
     * <p>
     * Kept as a constant because it is the one refusal a caller must not answer by repeating the
     * call, and something has to be able to recognise it.
     * </p>
     */
    public static final String HELD_BY_THIS_INSTANCE =
        "This AI-EDT instance is holding the infobase itself"; //$NON-NLS-1$

    /**
     * Whether a refusal is the one no retry can clear.
     *
     * @param heldBy the text from a refused {@link Claim}.
     * @return true when repeating the call is pointless
     */
    public static boolean isHeldByThisInstance(String heldBy)
    {
        return heldBy != null && heldBy.contains(HELD_BY_THIS_INSTANCE);
    }

    /**
     * The refusal for a lock this instance holds.
     * <p>
     * It does NOT say a retry is pointless. Two tool calls on one infobase are ordinary
     * contention, and there the holder finishes and the next attempt succeeds. What a caller
     * cannot tell apart from outside is that case from a call that is never coming back, so this
     * hands them what decides it - which operation, and for how long - and states the condition
     * instead of the verdict.
     * </p>
     *
     * @param fromClaimRecord what the claim file says, which names this instance; may be
     *            <code>null</code> when the record is gone, as it is after a cancellation.
     * @param outstanding what this instance holds on the infobase, or <code>null</code>.
     * @return the sentence a caller is shown
     */
    private static String heldByThisInstance(String fromClaimRecord, Outstanding outstanding)
    {
        StringBuilder said = new StringBuilder();
        if (fromClaimRecord != null && !fromClaimRecord.isEmpty())
        {
            said.append(fromClaimRecord).append(" "); //$NON-NLS-1$
        }
        said.append(HELD_BY_THIS_INSTANCE);
        if (outstanding != null)
        {
            said.append(": ").append(outstanding.operation).append(", for ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(outstanding.heldMs() / 1000).append("s"); //$NON-NLS-1$
        }
        said.append(". If that operation is not going to return, repeating this call will not " //$NON-NLS-1$
            + "clear it - stop it, or restart the environment."); //$NON-NLS-1$
        return said.toString();
    }

    public static Claim claim(String subject, String operation)
    {
        if (subject == null || subject.isEmpty())
        {
            // Nothing identifies this infobase - a connection string this cannot read, a kind of
            // reference it does not know. That is NOT contention, and answering "somebody else is
            // working on it" would refuse an operation for a neighbour that does not exist. The
            // caller proceeds unclaimed, exactly as it did before claims existed.
            return new Claim(new MonopolyLock("nothing was claimed: no subject was given"), null); //$NON-NLS-1$
        }
        java.util.concurrent.atomic.AtomicBoolean heldHere =
            new java.util.concurrent.atomic.AtomicBoolean();
        Optional<MonopolyLock> taken = take(subject, operation, heldHere);
        if (taken.isPresent())
        {
            return new Claim(taken.get(), null);
        }
        if (heldHere.get())
        {
            // Asked BEFORE the claim record, not after. While this instance holds the lock its own
            // claim is still lying there, so reading the record first answers "another AI-EDT
            // instance is working on this infobase" about ourselves - the exact sentence the field
            // report quoted - and the branch below would never be reached.
            return new Claim(null, heldByThisInstance(heldBy(subject), outstandingHere(subject)));
        }
        String who = heldBy(subject);
        if (who != null)
        {
            return new Claim(null, who);
        }
        return new Claim(null,
            "It has finished since this call tried to claim it - run the operation again."); //$NON-NLS-1$
    }

    /**
     * Who holds a claim, in words meant for whoever reads the failed answer.
     *
     * @param subject the same identity {@link #take} was called with.
     * @return a sentence naming the holder, or {@code null} when nobody does.
     */
    public static String heldBy(String subject)
    {
        if (subject == null || subject.isEmpty())
        {
            return null;
        }
        JsonObject claim = readLiveClaim(fileFor(subject));
        if (claim == null)
        {
            return null;
        }
        StringBuilder held = new StringBuilder();
        held.append(claim.has("title") ? claim.get("title").getAsString() : "another AI-EDT instance"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (claim.has("operation")) //$NON-NLS-1$
        {
            held.append(" is running ").append(claim.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (claim.has("takenAt")) //$NON-NLS-1$
        {
            held.append(" (since ").append(claim.get("takenAt").getAsString()).append(')'); //$NON-NLS-1$ //$NON-NLS-2$
        }
        held.append(". Run it from that instance, or wait for it to finish and try again."); //$NON-NLS-1$
        return held.toString();
    }

    /**
     * Releases the claim. Safe to call twice, and safe when the claim was never really written.
     */
    @Override
    public void close()
    {
        if (!released.compareAndSet(false, true))
        {
            return;
        }
        // Released before the claim is removed, and released even when there is no claim file to
        // remove. The lock is what actually keeps the next caller out; the claim only tells them
        // who to go and ask.
        if (subject != null)
        {
            HELD_HERE.remove(subject);
        }
        if (holding != null)
        {
            holding.release();
        }
        if (file == null)
        {
            return;
        }
        try
        {
            Files.deleteIfExists(file);
        }
        catch (IOException | RuntimeException e)
        {
            // The claim outlives its work, and nothing else will clear it while this process lives -
            // its pid is alive, so every other instance reads the claim as held. Logged loudly for
            // that reason: this is the one failure here that needs a person.
            Activator.logError("Could not release the lock on " + file //$NON-NLS-1$
                + ". Until this EDT is restarted, other instances will read that infobase as held.", //$NON-NLS-1$
                e instanceof Exception ? (Exception)e : new IOException(e));
        }
    }

    /**
     * Reads a claim, keeping it only while the process that made it is alive.
     * <p>
     * A dead holder's claim is deleted on the way past. Leaving it would turn one crash into an
     * operation nobody can ever run again.
     * </p>
     *
     * @param file the claim file.
     * @return the claim, or {@code null} when there is none or it is stale.
     */
    private static JsonObject readLiveClaim(Path file)
    {
        if (!Files.isRegularFile(file))
        {
            return null;
        }
        try
        {
            JsonObject claim =
                JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (isAlive(claim))
            {
                return claim;
            }
            Files.deleteIfExists(file);
            return null;
        }
        catch (IOException | RuntimeException e)
        {
            // Unreadable is treated as absent, and removed: a claim nobody can parse is a claim
            // nobody can release either.
            try
            {
                Files.deleteIfExists(file);
            }
            catch (IOException ignored)
            {
                Activator.logWarning("Could not remove an unreadable lock: " + ignored.getMessage()); //$NON-NLS-1$
            }
            return null;
        }
    }

    /**
     * Whether the process that made a claim is still running.
     * <p>
     * The start time is checked as well as the pid. Pids are recycled, and the day one is recycled
     * onto a stale claim is the day an operation refuses for a reason nobody can find.
     * </p>
     *
     * @param claim the claim.
     * @return true when the holder is alive.
     */
    private static boolean isAlive(JsonObject claim)
    {
        if (!claim.has("pid")) //$NON-NLS-1$
        {
            return false;
        }
        Optional<ProcessHandle> handle = ProcessHandle.of(claim.get("pid").getAsLong()); //$NON-NLS-1$
        if (handle.isEmpty() || !handle.get().isAlive())
        {
            return false;
        }
        if (!claim.has("processStartedAt")) //$NON-NLS-1$
        {
            return true;
        }
        String started = handle.get().info().startInstant().map(Instant::toString).orElse(null);
        return started == null || started.equals(claim.get("processStartedAt").getAsString()); //$NON-NLS-1$
    }

    /**
     * Builds the claim record.
     *
     * @param subject what is claimed.
     * @param operation what is being done.
     * @return the record
     */
    private static JsonObject record(String subject, String operation)
    {
        JsonObject claim = new JsonObject();
        ProcessHandle self = ProcessHandle.current();
        claim.addProperty("pid", self.pid()); //$NON-NLS-1$
        self.info().startInstant()
            .ifPresent(started -> claim.addProperty("processStartedAt", started.toString())); //$NON-NLS-1$
        claim.addProperty("title", InstanceRegistry.selfTitle()); //$NON-NLS-1$
        claim.addProperty("operation", operation); //$NON-NLS-1$
        claim.addProperty("subject", subject); //$NON-NLS-1$
        claim.addProperty("takenAt", Instant.now().toString()); //$NON-NLS-1$
        return claim;
    }

    /**
     * The file one subject is claimed through.
     * <p>
     * Hashed, because a subject is a connection string - it carries path separators, and on a file
     * infobase it carries the whole path.
     * </p>
     *
     * @param subject what is claimed.
     * @return the path
     */
    private static Path fileFor(String subject)
    {
        return lockDirectory().resolve(digest(subject) + SUFFIX);
    }

    /**
     * The file the operating system lock is taken on.
     * <p>
     * Separate from the claim on purpose. The claim is created and deleted around each piece of
     * work; a lock file that is deleted while somebody waits on it is a lock nobody is holding, so
     * this one is created once and left alone. It stays empty - everything a reader wants is in
     * the claim beside it.
     * </p>
     *
     * @param subject what is claimed.
     * @return the path of its lock file
     */
    private static Path lockFileFor(String subject)
    {
        return lockDirectory().resolve(digest(subject) + ".lock"); //$NON-NLS-1$
    }

    /**
     * @param subject what is claimed.
     * @return a stable hex digest of it
     */
    private static String digest(String subject)
    {
        try
        {
            byte[] hash = MessageDigest.getInstance("SHA-256") //$NON-NLS-1$
                .digest(subject.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++)
            {
                hex.append(String.format("%02x", hash[i])); //$NON-NLS-1$
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException impossible)
        {
            return Integer.toHexString(subject.hashCode());
        }
    }

    /**
     * @return where claims are kept
     */
    private static Path lockDirectory()
    {
        String override = System.getProperty(LOCK_DIR_PROPERTY);
        if (override != null && !override.isEmpty())
        {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".aiedt", "locks"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
