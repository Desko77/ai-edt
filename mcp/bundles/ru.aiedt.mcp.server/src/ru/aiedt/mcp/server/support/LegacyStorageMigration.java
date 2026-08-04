/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Moves a per-project settings file from the name an earlier build used onto the current one.
 * <p>
 * The migration happens once and then the old file is renamed out of the way, rather than being
 * read as a fallback for as long as it exists. Keeping it live looks safer and is not: two files
 * claiming the same setting drift apart the moment an older build, or a teammate on an older build,
 * writes to the one this build ignores. Worse for anything that deletes its file when it holds
 * nothing - clearing the last entry removes the current file, the old one survives, and the next
 * start reads the deleted entries straight back in.
 * </p>
 * <p>
 * Renaming rather than deleting keeps the user's data recoverable by hand if the move ever goes
 * wrong. The class deliberately touches nothing but the filesystem, so it can be exercised without
 * a running workbench.
 * </p>
 */
public final class LegacyStorageMigration
{
    /** Appended to the old file once its contents have been carried over. */
    public static final String MIGRATED_SUFFIX = ".migrated.bak"; //$NON-NLS-1$

    private LegacyStorageMigration()
    {
        // utility
    }

    /**
     * Carries {@code legacyFile} over to {@code currentFile} when only the former exists.
     * <p>
     * Does nothing when the current file is already there (it wins), when the old one is absent
     * (nothing to carry). A failed move is reported to the caller with both files untouched.
     * </p>
     *
     * @param settingsFolder the folder both files live in
     * @param legacyFileName the file name an earlier build wrote
     * @param currentFileName the file name this build reads
     * @return {@code true} when a file was carried over by this call
     * @throws IOException when the copy or the rename fails; both files are left as they were, so
     *             the caller can log and carry on rather than lose the user's data
     */
    public static boolean carryOver(Path settingsFolder, String legacyFileName, String currentFileName)
        throws IOException
    {
        if (settingsFolder == null || !Files.isDirectory(settingsFolder))
        {
            return false;
        }

        Path current = settingsFolder.resolve(currentFileName);
        Path legacy = settingsFolder.resolve(legacyFileName);
        if (Files.exists(current) || !Files.exists(legacy))
        {
            return false;
        }

        Files.copy(legacy, current, StandardCopyOption.COPY_ATTRIBUTES);
        Files.move(legacy, settingsFolder.resolve(legacyFileName + MIGRATED_SUFFIX),
            StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
}
