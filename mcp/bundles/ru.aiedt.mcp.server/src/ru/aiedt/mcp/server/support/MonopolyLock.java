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

    private MonopolyLock(Path file)
    {
        this.file = file;
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
        if (subject == null || subject.isEmpty())
        {
            // Nothing to claim. Better to let the operation through than to invent a key and
            // serialise unrelated work behind it.
            return Optional.empty();
        }
        Path file = fileFor(subject);
        try
        {
            Files.createDirectories(file.getParent());
            JsonObject existing = readLiveClaim(file);
            if (existing != null)
            {
                return Optional.empty();
            }
            // CREATE_NEW is the whole race: two processes reaching here at once, exactly one file
            // created. Nothing is deleted first - a stale claim was already removed by the read
            // above, so anything still here belongs to a live holder, and clearing the way would
            // hand this process a lock somebody else was holding.
            Files.write(file, record(subject, operation).toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return Optional.of(new MonopolyLock(file));
        }
        catch (java.nio.file.FileAlreadyExistsException raced)
        {
            // Somebody claimed it between the read and the write. That is the race working, not a
            // failure: they hold it, we do not.
            return Optional.empty();
        }
        catch (IOException | RuntimeException e)
        {
            // A claim that cannot be written must not stop the work. This makes conflicts legible;
            // it is not the thing that keeps the infobase safe - the platform's own monopoly is.
            Activator.logWarning("Could not take the lock on " + operation + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return Optional.of(new MonopolyLock(null));
        }
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
            // The claim outlives its work now, and will be cleared as stale when this process ends.
            Activator.logWarning("Could not release a lock: " + e.getMessage()); //$NON-NLS-1$
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
