/*
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Chooses the owned-process containment for this machine and refuses when there is none.
 * <p>
 * The only backend is the Windows job object. A machine that cannot offer it gets a refusal
 * before a client is started: the caller must not run the client on a weaker liveness heuristic,
 * and must not release the infobase on one. On Linux and macOS there is no equivalent that is
 * both unprivileged and atomic at spawn, so the honest answer is "not started", not a poll.
 * </p>
 */
public final class OwnedProcessContainments
{
    private OwnedProcessContainments()
    {
    }

    /** The reason computed once for this process, or {@code null} when the backend is usable. */
    private static final class Backend
    {
        private static final String REASON = reason();
    }

    private static String reason()
    {
        String osName = System.getProperty("os.name", ""); //$NON-NLS-1$
        String policy = whyUnavailable(osName, true, true);
        if (policy != null)
        {
            return policy;
        }
        boolean jna = jnaPresent();
        boolean sixtyFourBit = jna && is64Bit();
        String unavailable = whyUnavailable(osName, sixtyFourBit, jna);
        if (unavailable != null)
        {
            return unavailable;
        }
        try
        {
            return WindowsJobContainment.unavailableReason();
        }
        catch (Throwable failed)
        {
            return "Windows job-object containment could not be loaded: " + failed;
        }
    }

    /**
     * Whether the owned client can be started under an authoritative containment here.
     *
     * @return {@code true} on a supported Windows installation
     */
    public static boolean isAvailable()
    {
        return Backend.REASON == null;
    }

    /**
     * Why the owned client cannot be started here.
     *
     * @return the refusal sentence, or {@code null} when containment is available
     */
    public static String unavailableReason()
    {
        return Backend.REASON;
    }

    /**
     * Starts the configured client under an authoritative containment.
     *
     * @param command the executable first, then its arguments
     * @param workingDirectory the directory the client runs in, or {@code null} to inherit
     * @return the containment that owns the tree
     * @throws IOException when the operating system refuses a step; nothing is left running
     * @throws ContainmentUnavailableException when this machine has no such backend
     */
    public static OwnedProcessContainment spawn(List<String> command, File workingDirectory)
        throws IOException, ContainmentUnavailableException
    {
        String unavailable = Backend.REASON;
        if (unavailable != null)
        {
            throw new ContainmentUnavailableException(unavailable);
        }
        return WindowsJobContainment.spawn(command, workingDirectory);
    }

    /**
     * The policy decision, separated from the machine so it can be tested anywhere.
     *
     * @param osName the {@code os.name} value
     * @param sixtyFourBitJvm whether the JVM is 64-bit
     * @param jnaLoadable whether the JNA platform bundle is available
     * @return the refusal sentence, or {@code null}
     */
    static String whyUnavailable(String osName, boolean sixtyFourBitJvm, boolean jnaLoadable)
    {
        if (osName == null || !osName.toLowerCase(Locale.ROOT).contains("win")) //$NON-NLS-1$
        {
            return "The owned scenario client was not started: Windows job-object containment is " //$NON-NLS-1$
                + "available only on Windows. The client is never run without an authoritative " //$NON-NLS-1$
                + "containment, because releasing the infobase then rests on a process-tree " //$NON-NLS-1$
                + "snapshot that cannot see a child started just before its parent exits."; //$NON-NLS-1$
        }
        if (!jnaLoadable)
        {
            return "The owned scenario client was not started: the JNA platform bundle this EDT " //$NON-NLS-1$
                + "normally carries is not available to the plugin, so Windows job-object " //$NON-NLS-1$
                + "containment cannot be created. Install the EDT third-party feature or run " //$NON-NLS-1$
                + "without the owned client."; //$NON-NLS-1$
        }
        if (!sixtyFourBitJvm)
        {
            return "The owned scenario client was not started: Windows job-object containment " //$NON-NLS-1$
                + "needs a 64-bit JVM, and this EDT runs a 32-bit one. Start EDT with a 64-bit " //$NON-NLS-1$
                + "JVM or run without the owned client."; //$NON-NLS-1$
        }
        return null;
    }

    private static boolean jnaPresent()
    {
        try
        {
            Class.forName("com.sun.jna.Native", false, OwnedProcessContainments.class.getClassLoader()); //$NON-NLS-1$
            return true;
        }
        catch (Throwable missing)
        {
            // The platform bundle is genuinely optional at runtime: the tool refuses before it
            // starts a client rather than falling back to a snapshot of a process tree.
            return false;
        }
    }

    private static boolean is64Bit()
    {
        try
        {
            return com.sun.jna.Native.POINTER_SIZE == 8;
        }
        catch (Throwable unavailable)
        {
            return false;
        }
    }
}
