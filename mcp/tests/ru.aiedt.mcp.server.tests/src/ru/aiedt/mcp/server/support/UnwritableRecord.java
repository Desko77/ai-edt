/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import org.junit.Assume;

/**
 * A record file that still reads but cannot be replaced, on Windows and on POSIX systems alike.
 * <p>
 * On Windows a read-only file cannot be replaced by a move. On a POSIX system the move replaces a
 * read-only file, and what stops it is a directory that grants no write: the record still reads,
 * and neither the temporary file beside it nor the replacing move can be made. A user the file
 * system does not refuse, such as root, is not stopped by either; the calling test is skipped then,
 * because a check that cannot fail proves nothing.
 * </p>
 */
public final class UnwritableRecord implements AutoCloseable
{
    private final Path record;

    private final Set<PosixFilePermission> directoryWas;

    /**
     * @param record the record file
     * @param directoryWas the permissions its directory had, or {@code null} when the file itself
     *            was made read-only
     */
    private UnwritableRecord(Path record, Set<PosixFilePermission> directoryWas)
    {
        this.record = record;
        this.directoryWas = directoryWas;
    }

    /**
     * Makes a record file unreplaceable until {@link #close()}.
     *
     * @param record an existing record file
     * @return the handle that restores it
     * @throws IOException when the file or its directory cannot be changed
     */
    public static UnwritableRecord of(Path record) throws IOException
    {
        Path directory = record.toAbsolutePath().getParent();
        PosixFileAttributeView posix =
            Files.getFileAttributeView(directory, PosixFileAttributeView.class);
        if (posix == null)
        {
            Files.setAttribute(record, "dos:readonly", Boolean.TRUE); //$NON-NLS-1$
            return new UnwritableRecord(record, null);
        }
        Set<PosixFilePermission> was = posix.readAttributes().permissions();
        posix.setPermissions(
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        UnwritableRecord made = new UnwritableRecord(record, was);
        if (Files.isWritable(directory))
        {
            made.close();
            Assume.assumeTrue("this user writes into a directory that grants no write", false); //$NON-NLS-1$
        }
        return made;
    }

    /**
     * Restores what {@link #of(Path)} changed.
     *
     * @throws IOException when the file or its directory cannot be changed back
     */
    @Override
    public void close() throws IOException
    {
        if (directoryWas == null)
        {
            Files.setAttribute(record, "dos:readonly", Boolean.FALSE); //$NON-NLS-1$
        }
        else
        {
            Files.setPosixFilePermissions(record.toAbsolutePath().getParent(), directoryWas);
        }
    }
}
