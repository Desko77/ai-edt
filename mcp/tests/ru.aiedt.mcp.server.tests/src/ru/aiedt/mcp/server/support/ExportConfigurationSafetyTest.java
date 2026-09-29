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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseEqualityState;

import ru.aiedt.mcp.server.support.BmExternalObjectDumpHelper.DumpInvocation;
import ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.DatabaseUpdateOutcome;

/**
 * The guarded .cf/.cfe/.epf/.erf export: a file a previous run left can never pass as this
 * run's result, an occupied destination is left byte-for-byte intact, and a dump that would
 * silently miss project changes not yet in the infobase is refused.
 * <p>
 * Each test here pins a branch that once produced a false success: the export judged the
 * destination file alone, so anything already sitting at outputPath counted as the product.
 * </p>
 */
public class ExportConfigurationSafetyTest
{
    private Path dir;

    @Before
    public void aScratchDirectory() throws IOException
    {
        dir = Files.createTempDirectory("export-safety");
    }

    @After
    public void theScratchDirectoryGoes() throws IOException
    {
        if (dir == null || !Files.exists(dir))
        {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (IOException ignored)
                {
                    // best effort cleanup
                }
            });
        }
    }

    private Path file(String name, String content) throws IOException
    {
        Path p = dir.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private static String content(Path p) throws IOException
    {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    // -- the occupied destination --

    @Test
    public void anOccupiedDestinationIsRefusedAndLeftUntouched() throws Exception
    {
        Path dest = file("previous.cf", "previous");

        String refusal = BmInfobaseExtensionHelper.occupiedOutputRefusal(dest, false);

        assertNotNull("an occupied destination is refused", refusal);
        assertTrue(refusal, refusal.contains("overwrite=true"));
        assertEquals("the previous file is byte-for-byte intact", "previous", content(dest));
    }

    @Test
    public void anOccupiedDestinationIsWritableWhenOverwriteIsAllowed() throws Exception
    {
        Path dest = file("previous.cf", "previous");

        assertNull(BmInfobaseExtensionHelper.occupiedOutputRefusal(dest, true));
    }

    @Test
    public void aFreeDestinationNeedsNoOverwrite()
    {
        assertNull(BmInfobaseExtensionHelper.occupiedOutputRefusal(dir.resolve("absent.cf"), false));
    }

    // -- the freshness judgement: the branch that refused the false success --

    @Test
    public void aMissingFileIsAProblem()
    {
        String problem = BmInfobaseExtensionHelper.freshExportProblem(dir.resolve("absent.cf"),
            Instant.now());

        assertNotNull(problem);
        assertTrue(problem, problem.contains("no file was written"));
    }

    @Test
    public void anEmptyFileCountsAsNoFile() throws Exception
    {
        Path empty = file("empty.cf", "");

        String problem = BmInfobaseExtensionHelper.freshExportProblem(empty,
            Instant.now().minusSeconds(10));

        assertNotNull(problem);
        assertTrue(problem, problem.contains("empty"));
    }

    /**
     * The case the fix exists for: a file from BEFORE this run is not this run's product,
     * however plausible its presence at the destination looks.
     */
    @Test
    public void aFileOlderThanTheRunIsNotItsProduct() throws Exception
    {
        Path stale = file("stale.cf", "previous run");
        Files.setLastModifiedTime(stale,
            java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(3600)));

        String problem = BmInfobaseExtensionHelper.freshExportProblem(stale, Instant.now());

        assertNotNull(problem);
        assertTrue(problem, problem.contains("predates this run"));
    }

    /**
     * A file stamped a moment before the start is still this run's product: the file system
     * stamps coarser than the clock the start is read from. One and a half seconds is inside
     * the allowance.
     */
    @Test
    public void aFileStampedJustBeforeTheStartIsStillTheProduct() throws Exception
    {
        Path fresh = file("coarse.cf", "this run");
        Instant start = Instant.now();
        Files.setLastModifiedTime(fresh, java.nio.file.attribute.FileTime.from(start.minusMillis(1500)));

        assertNull(BmInfobaseExtensionHelper.freshExportProblem(fresh, start));
    }

    /**
     * The allowance ends at two seconds: a file stamped two and a half seconds before the start
     * is a leftover, not this run's product.
     */
    @Test
    public void aFileStampedPastTheAllowanceIsNotTheProduct() throws Exception
    {
        Path stale = file("past.cf", "previous run");
        Instant start = Instant.now();
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.from(start.minusMillis(2500)));

        String problem = BmInfobaseExtensionHelper.freshExportProblem(stale, start);

        assertNotNull(problem);
        assertTrue(problem, problem.contains("predates this run"));
    }

    @Test
    public void aFreshFilePasses() throws Exception
    {
        Path fresh = file("fresh.cf", "this run");

        assertNull(BmInfobaseExtensionHelper.freshExportProblem(fresh,
            Instant.now().minusSeconds(10)));
    }

    // -- the move into place --

    @Test
    public void aVerifiedFileReplacesTheDestinationWhenOverwriteIsAllowed() throws Exception
    {
        Path temp = file("temp.part", "new");
        Path dest = file("out.cf", "old");

        assertNull(BmInfobaseExtensionHelper.moveExportIntoPlace(temp, dest, true));

        assertEquals("new", content(dest));
        assertFalse("the temp file is consumed by the move", Files.exists(temp));
    }

    @Test
    public void aVerifiedFileDoesNotReplaceAnOccupiedDestinationWithoutOverwrite() throws Exception
    {
        Path temp = file("temp.part", "new");
        Path dest = file("out.cf", "old");

        String error = BmInfobaseExtensionHelper.moveExportIntoPlace(temp, dest, false);

        assertNotNull(error);
        assertEquals("the occupied destination is left intact", "old", content(dest));
    }

    @Test
    public void aVerifiedFileMovesIntoAFreeDestination() throws Exception
    {
        Path temp = file("temp.part", "new");
        Path dest = dir.resolve("out.cf");

        assertNull(BmInfobaseExtensionHelper.moveExportIntoPlace(temp, dest, false));

        assertEquals("new", content(dest));
    }

    // -- the out-of-sync refusal --

    @Test
    public void anEqualInfobaseNeedsNoRefusal()
    {
        assertNull(BmInfobaseExtensionHelper.outOfSyncRefusal(InfobaseEqualityState.EQUAL));
    }

    @Test
    public void aNotEqualInfobaseIsRefused()
    {
        String refusal = BmInfobaseExtensionHelper.outOfSyncRefusal(InfobaseEqualityState.NOT_EQUAL);

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("update_database"));
        assertTrue(refusal, refusal.contains("allowOutOfSync=true"));
    }

    @Test
    public void aLoadingInfobaseIsRefused()
    {
        String refusal = BmInfobaseExtensionHelper.outOfSyncRefusal(InfobaseEqualityState.LOADING);

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("LOADING"));
    }

    @Test
    public void anUnreadableStateIsRefused()
    {
        // A guard that could not run has cleared nothing: unreadable is a refusal, not a pass.
        String refusal = BmInfobaseExtensionHelper.outOfSyncRefusal(null);

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("could not be read"));
    }

    // -- the database update the install was asked for --

    @Test
    public void aMissingLogVouchesForNothing()
    {
        assertEquals(DatabaseUpdateOutcome.UNVERIFIED,
            BmInfobaseExtensionHelper.databaseUpdateOutcome(null));
        assertEquals(DatabaseUpdateOutcome.UNVERIFIED,
            BmInfobaseExtensionHelper.databaseUpdateOutcome("  \n  "));
    }

    @Test
    public void aFailureLineFailsTheUpdate()
    {
        assertEquals(DatabaseUpdateOutcome.FAILED,
            BmInfobaseExtensionHelper.databaseUpdateOutcome(
                "Loading the extension\nDatabase update failed: lock conflict"));
    }

    @Test
    public void aRussianFailureLineFailsTheUpdate()
    {
        assertEquals(DatabaseUpdateOutcome.FAILED,
            BmInfobaseExtensionHelper.databaseUpdateOutcome(
                "Обновление конфигурации базы данных\nОшибка применения расширения"));
    }

    @Test
    public void aZeroErrorSummaryIsNotAFailure()
    {
        assertEquals(DatabaseUpdateOutcome.UNVERIFIED,
            BmInfobaseExtensionHelper.databaseUpdateOutcome("Run finished\nErrors: 0"));
        assertEquals(DatabaseUpdateOutcome.UNVERIFIED,
            BmInfobaseExtensionHelper.databaseUpdateOutcome("Ошибок: 0"));
    }

    @Test
    public void aSuccessLineConfirmsTheUpdate()
    {
        assertEquals(DatabaseUpdateOutcome.CONFIRMED,
            BmInfobaseExtensionHelper.databaseUpdateOutcome(
                "Database configuration update completed successfully"));
    }

    @Test
    public void aRussianSuccessLineConfirmsTheUpdate()
    {
        assertEquals(DatabaseUpdateOutcome.CONFIRMED,
            BmInfobaseExtensionHelper.databaseUpdateOutcome(
                "Обновление конфигурации базы данных успешно завершено"));
    }

    @Test
    public void aFailureWinsOverSuccess()
    {
        assertEquals(DatabaseUpdateOutcome.FAILED,
            BmInfobaseExtensionHelper.databaseUpdateOutcome(
                "Database configuration update completed successfully\nОшибка записи таблицы"));
    }

    /**
     * The log of a load and update run holds the load line first; that line vouches for the
     * load, so on its own it leaves the update unverified, in either language.
     */
    @Test
    public void theLoadLineAloneDoesNotConfirmTheUpdate()
    {
        assertEquals(DatabaseUpdateOutcome.UNVERIFIED,
            BmInfobaseExtensionHelper.databaseUpdateOutcome("Загрузка конфигурации успешно завершена"));
        assertEquals(DatabaseUpdateOutcome.UNVERIFIED,
            BmInfobaseExtensionHelper.databaseUpdateOutcome("Configuration loaded successfully"));
    }

    /**
     * The load line followed by the platform's own database update line is a confirmed update.
     */
    @Test
    public void theLoadLineWithTheDatabaseUpdateLineConfirmsTheUpdate()
    {
        assertEquals(DatabaseUpdateOutcome.CONFIRMED,
            BmInfobaseExtensionHelper.databaseUpdateOutcome(
                "Загрузка конфигурации успешно завершена\nОбновление конфигурации базы данных успешно завершено"));
    }

    // -- the .epf/.erf dump placement --

    @Test
    public void aFreshDumpIsMovedIntoPlace() throws Exception
    {
        Path temp = file("temp.part", "binary");
        Path out = dir.resolve("out.epf");

        DumpInvocation placed = BmExternalObjectDumpHelper.placeDumpResult(temp, out,
            Instant.now().minusSeconds(10));

        assertTrue(placed.error, placed.ok);
        assertEquals("binary", content(out));
        assertFalse("the temp file is consumed by the move", Files.exists(temp));
    }

    /**
     * The G1 twin for .epf/.erf: a dump whose file predates the run is refused, and the
     * file a previous run left at the destination stays byte-for-byte intact.
     */
    @Test
    public void aStaleDumpIsRefusedAndThePreviousFileStays() throws Exception
    {
        Path temp = file("temp.part", "leftover");
        Files.setLastModifiedTime(temp,
            java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(3600)));
        Path out = file("out.epf", "previous");

        DumpInvocation placed = BmExternalObjectDumpHelper.placeDumpResult(temp, out,
            Instant.now());

        assertFalse(placed.ok);
        assertEquals("outputMissing", placed.failureKind);
        assertTrue(placed.error, placed.error.contains("predates this run"));
        assertEquals("previous", content(out));
    }

    @Test
    public void aMissingDumpFileIsRefused()
    {
        DumpInvocation placed = BmExternalObjectDumpHelper.placeDumpResult(
            dir.resolve("absent.part"), dir.resolve("out.epf"), Instant.now());

        assertFalse(placed.ok);
        assertEquals("outputMissing", placed.failureKind);
    }

    @Test
    public void anEmptyDumpFileIsRefused() throws Exception
    {
        Path temp = file("temp.part", "");

        DumpInvocation placed = BmExternalObjectDumpHelper.placeDumpResult(temp,
            dir.resolve("out.epf"), Instant.now().minusSeconds(10));

        assertFalse(placed.ok);
        assertEquals("outputMissing", placed.failureKind);
    }
}
