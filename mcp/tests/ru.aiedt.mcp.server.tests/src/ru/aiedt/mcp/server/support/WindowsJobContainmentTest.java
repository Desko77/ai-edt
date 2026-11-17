/*
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;

/**
 * The Windows backend: the native layout, the unassigned suspended root a failed assignment
 * leaves behind, a release that cannot be confirmed, and the late child a tree snapshot misses.
 */
public class WindowsJobContainmentTest
{
    @Test
    public void structSizesMatchThe64BitWindowsLayout()
    {
        Assume.assumeTrue("the layouts below are the 64-bit ones", Native.POINTER_SIZE == 8); //$NON-NLS-1$
        assertEquals(104, new WindowsJobContainment.STARTUPINFO().size());
        assertEquals(24, new WindowsJobContainment.PROCESS_INFORMATION().size());
        WindowsJobContainment.JOBOBJECT_EXTENDED_LIMIT_INFORMATION extended =
            new WindowsJobContainment.JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        extended.BasicLimitInformation =
            new WindowsJobContainment.JOBOBJECT_BASIC_LIMIT_INFORMATION();
        extended.IoInfo = new WindowsJobContainment.IO_COUNTERS();
        assertEquals(144, extended.size());
        assertEquals(48, new WindowsJobContainment.JOBOBJECT_BASIC_ACCOUNTING_INFORMATION().size());
    }

    @Test
    public void commandLineUsesWindowsUtf16EvenOnOtherHosts() throws Exception
    {
        FakeJobApi api = new FakeJobApi();
        api.terminateProcessResult = true;
        api.processSignaled = true;
        try (OwnedProcessContainment containment = WindowsJobContainment.spawn(
            List.of("client.exe", "\u0416\uD83D\uDE00"), null, api))
        {
            org.junit.Assert.assertArrayEquals("client.exe \u0416\uD83D\uDE00\0"
                .getBytes(java.nio.charset.StandardCharsets.UTF_16LE), api.commandBytes);
        }
    }

    @Test
    public void anAssignFailureThatWasTerminatedThrows() throws Exception
    {
        FakeJobApi api = new FakeJobApi();
        api.assignResult = false;
        api.terminateProcessResult = true;
        api.processSignaled = true;
        try
        {
            WindowsJobContainment.spawn(List.of("cmd.exe", "/c", "exit", "0"), new File("."), api); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("an assignment that failed must not hand the client back"); //$NON-NLS-1$
        }
        catch (IOException expected)
        {
            assertTrue(expected.getMessage().contains("AssignProcessToJobObject")); //$NON-NLS-1$
            assertTrue(expected.getMessage().contains("terminated")); //$NON-NLS-1$
        }
        assertEquals(0, api.resumeThreadCalls);
        assertTrue(api.processClosed);
    }

    @Test
    public void anUnassignedRootIsRetainedUntilTerminationIsConfirmed() throws Exception
    {
        FakeJobApi api = new FakeJobApi();
        api.assignResult = false;
        api.terminateProcessResult = false;
        api.waitResult = 258; // WAIT_TIMEOUT
        api.activeProcesses = 0;
        OwnedProcessContainment retained = WindowsJobContainment.spawn(
            List.of("cmd.exe", "/c", "exit", "0"), new File("."), api); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNotNull(retained);
        assertFalse("the assignment failed, so this is not an established tree", //$NON-NLS-1$
            retained.established());
        assertEquals(4242L, retained.pid());
        assertTrue("the suspended root is alive", retained.isTreeAlive()); //$NON-NLS-1$
        assertEquals("an empty job cannot decide this", 0, api.queryCalls); //$NON-NLS-1$
        assertFalse("the process handle must not be closed", api.processClosed); //$NON-NLS-1$
        assertFalse("termination was not confirmed", retained.tryClose()); //$NON-NLS-1$
        assertFalse("a failed release keeps the process handle", api.processClosed); //$NON-NLS-1$
        assertTrue("close must throw rather than ignore", thrownByClose(retained)); //$NON-NLS-1$

        api.terminateProcessResult = true;
        assertTrue(retained.tryClose());
        assertTrue(api.processClosed);
        assertEquals(OwnedProcessContainment.Proof.ROOT_HANDLE_EXIT, retained.proof());
    }

    @Test
    public void aCloseThatCannotReleaseKeepsTheHandleAndRetries() throws Exception
    {
        FakeJobApi api = new FakeJobApi();
        api.terminateProcessResult = true;
        api.processSignaled = true;
        api.closeJobSucceeds = false;
        OwnedProcessContainment containment = WindowsJobContainment.spawn(
            List.of("cmd.exe", "/c", "exit", "0"), new File("."), api); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(containment.tryClose());
        assertFalse(containment.tryClose());
        assertTrue(thrownByClose(containment));
        api.closeJobSucceeds = true;
        assertTrue(containment.tryClose());
    }

    @Test
    public void aMissingRootHandleIsFailClosedUnknown()
    {
        FakeJobApi api = new FakeJobApi();
        api.activeProcesses = 0;
        WindowsJobContainment empty = new WindowsJobContainment(api, new Memory(8), null, 1L, true);
        assertTrue("a missing root handle is not an exit observation", empty.isTreeAlive()); //$NON-NLS-1$
        assertEquals(OwnedProcessContainment.Proof.NOT_PROVEN, empty.proof());
        assertEquals("a missing root handle must not be probed for a zero", 0, api.queryCalls); //$NON-NLS-1$
    }

    @Test
    public void closeWithoutObservedZeroDoesNotClaimZero() throws Exception
    {
        FakeJobApi api = new FakeJobApi();
        WindowsJobContainment empty = new WindowsJobContainment(api, null, null, 1L, true);
        assertTrue(empty.isTreeAlive());
        assertTrue(empty.tryClose());
        assertEquals("closing does not record a zero that was never observed", //$NON-NLS-1$
            OwnedProcessContainment.Proof.NOT_PROVEN, empty.proof());
    }

    @Test
    public void aNativeSuspendedRootIsRetainedWhenAssignmentFails() throws Exception
    {
        Assume.assumeTrue("the Windows job-object backend runs only on Windows", //$NON-NLS-1$
            OwnedProcessContainments.isAvailable());
        DelegatingJobApi api = new DelegatingJobApi();
        OwnedProcessContainment retained = WindowsJobContainment.spawn(
            List.of("cmd.exe", "/c", "exit", "0"), new File("."), api); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        try
        {
            assertFalse("assignment failed, so this is not an established tree", //$NON-NLS-1$
                retained.established());
            assertEquals("nothing terminal was observed yet", //$NON-NLS-1$
                OwnedProcessContainment.Proof.NOT_PROVEN, retained.proof());
            assertTrue("the real suspended process is alive", //$NON-NLS-1$
                ProcessHandle.of(retained.pid()).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(retained.isTreeAlive());
            assertFalse("termination was not confirmed", retained.tryClose()); //$NON-NLS-1$
            assertTrue("a failed release keeps the real process alive", //$NON-NLS-1$
                ProcessHandle.of(retained.pid()).map(ProcessHandle::isAlive).orElse(false));

            api.allowTerminate = true;
            assertTrue(retained.tryClose());
            assertEquals(OwnedProcessContainment.Proof.ROOT_HANDLE_EXIT, retained.proof());
            assertFalse(ProcessHandle.of(retained.pid()).map(ProcessHandle::isAlive).orElse(false));
        }
        finally
        {
            api.allowTerminate = true;
            try { retained.tryClose(); }
            catch (RuntimeException alreadyGone) { }
        }
    }

    @Test
    public void aLateChildKeepsTheTreeAliveUntilItExitsItself() throws Exception
    {
        Assume.assumeTrue("the Windows job-object backend runs only on Windows", //$NON-NLS-1$
            OwnedProcessContainments.isAvailable());
        OwnedProcessContainment containment = spawnLateChildTree();
        try
        {
            assertTrue("the root cmd must exit before the child does", waitGone(containment.pid(), 10_000L)); //$NON-NLS-1$
            assertTrue("the late child must keep the tree owned", containment.isTreeAlive()); //$NON-NLS-1$
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (containment.isTreeAlive() && System.nanoTime() < deadline)
            {
                Thread.sleep(50L);
            }
            assertFalse(containment.isTreeAlive());
            assertEquals(0, containment.exitCode());
            assertEquals(OwnedProcessContainment.Proof.JOB_OBJECT_ACTIVE_PROCESSES_ZERO,
                containment.proof());
        }
        finally
        {
            containment.tryClose();
        }
    }

    @Test
    public void terminateKillsTheLateChild() throws Exception
    {
        Assume.assumeTrue("the Windows job-object backend runs only on Windows", //$NON-NLS-1$
            OwnedProcessContainments.isAvailable());
        OwnedProcessContainment containment = spawnLateChildTree();
        try
        {
            assertTrue(waitGone(containment.pid(), 10_000L));
            assertTrue(containment.terminateAndAwait(10_000L));
            assertFalse(containment.isTreeAlive());
        }
        finally
        {
            containment.tryClose();
        }
    }

    @Test
    public void aMachineWithoutTheBackendRefusesBeforeLaunch() throws Exception
    {
        if (OwnedProcessContainments.isAvailable())
        {
            return;
        }
        try
        {
            OwnedProcessContainments.spawn(List.of("cmd.exe", "/c", "exit", "0"), new File(".")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("containment unavailable must refuse, not fall back"); //$NON-NLS-1$
        }
        catch (ContainmentUnavailableException expected)
        {
            assertTrue(expected.getMessage().contains("not started")); //$NON-NLS-1$
        }
    }

    private static boolean thrownByClose(OwnedProcessContainment containment)
    {
        try
        {
            containment.close();
            return false;
        }
        catch (IllegalStateException expected)
        {
            return true;
        }
    }

    private static OwnedProcessContainment spawnLateChildTree() throws Exception
    {
        // The root cmd starts a second cmd in the background and exits immediately. The inner
        // cmd (and its ping) live for about eleven seconds - the child the old closure misses.
        return OwnedProcessContainments.spawn(List.of("cmd.exe", "/c", "start", "\"\"", "/b", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "cmd.exe", "/c", "ping -n 12 127.0.0.1 >nul"), new File(".")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static boolean waitGone(long pid, long budgetMs) throws Exception
    {
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
            && System.nanoTime() < deadline)
        {
            Thread.sleep(20L);
        }
        return !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** A real kernel32 API with a failing assignment and a switchable termination. */
    private static final class DelegatingJobApi implements WindowsJobContainment.WindowsJobApi
    {
        private final WindowsJobContainment.WindowsJobApi real =
            Native.load("kernel32", WindowsJobContainment.WindowsJobApi.class); //$NON-NLS-1$

        boolean allowTerminate;

        @Override
        public Pointer CreateJobObjectW(Pointer lpJobAttributes, WString lpName)
        {
            return real.CreateJobObjectW(lpJobAttributes, lpName);
        }

        @Override
        public boolean SetInformationJobObject(Pointer hJob, int infoClass, Structure lpInfo,
            int cbInfo)
        {
            return real.SetInformationJobObject(hJob, infoClass, lpInfo, cbInfo);
        }

        @Override
        public boolean AssignProcessToJobObject(Pointer hJob, Pointer hProcess)
        {
            return false;
        }

        @Override
        public boolean QueryInformationJobObject(Pointer hJob, int infoClass, Structure lpInfo,
            int cbInfo, IntByReference lpReturnLength)
        {
            return real.QueryInformationJobObject(hJob, infoClass, lpInfo, cbInfo, lpReturnLength);
        }

        @Override
        public boolean TerminateJobObject(Pointer hJob, int uExitCode)
        {
            return real.TerminateJobObject(hJob, uExitCode);
        }

        @Override
        public boolean CreateProcessW(WString lpApplicationName, Pointer lpCommandLine,
            Pointer lpProcessAttributes, Pointer lpThreadAttributes, boolean bInheritHandles,
            int dwCreationFlags, Pointer lpEnvironment, WString lpCurrentDirectory,
            Structure lpStartupInfo, Structure lpProcessInformation)
        {
            return real.CreateProcessW(lpApplicationName, lpCommandLine, lpProcessAttributes,
                lpThreadAttributes, bInheritHandles, dwCreationFlags, lpEnvironment,
                lpCurrentDirectory, lpStartupInfo, lpProcessInformation);
        }

        @Override
        public int ResumeThread(Pointer hThread)
        {
            return real.ResumeThread(hThread);
        }

        @Override
        public boolean TerminateProcess(Pointer hProcess, int uExitCode)
        {
            return allowTerminate && real.TerminateProcess(hProcess, uExitCode);
        }

        @Override
        public int WaitForSingleObject(Pointer hHandle, int dwMilliseconds)
        {
            return real.WaitForSingleObject(hHandle, dwMilliseconds);
        }

        @Override
        public boolean GetExitCodeProcess(Pointer hProcess, IntByReference lpExitCode)
        {
            return real.GetExitCodeProcess(hProcess, lpExitCode);
        }

        @Override
        public boolean CloseHandle(Pointer hObject)
        {
            return real.CloseHandle(hObject);
        }
    }

    /** A native API that lets assignment and termination fail without a real client. */
    private static final class FakeJobApi implements WindowsJobContainment.WindowsJobApi
    {
        boolean assignResult = true;

        boolean terminateProcessResult = true;

        boolean processSignaled;

        int waitResult = 0;

        boolean closeJobSucceeds = true;

        int activeProcesses;

        int queryCalls;

        int resumeThreadCalls;

        boolean processClosed;

        private final Pointer process = new Memory(8);
        private byte[] commandBytes;

        @Override
        public Pointer CreateJobObjectW(Pointer lpJobAttributes, WString lpName)
        {
            return new Memory(8);
        }

        @Override
        public boolean SetInformationJobObject(Pointer hJob, int infoClass, Structure lpInfo,
            int cbInfo)
        {
            return true;
        }

        @Override
        public boolean AssignProcessToJobObject(Pointer hJob, Pointer hProcess)
        {
            return assignResult;
        }

        @Override
        public boolean QueryInformationJobObject(Pointer hJob, int infoClass, Structure lpInfo,
            int cbInfo, IntByReference lpReturnLength)
        {
            queryCalls++;
            WindowsJobContainment.JOBOBJECT_BASIC_ACCOUNTING_INFORMATION accounting =
                (WindowsJobContainment.JOBOBJECT_BASIC_ACCOUNTING_INFORMATION) lpInfo;
            accounting.ActiveProcesses = activeProcesses;
            accounting.write();
            return true;
        }

        @Override
        public boolean TerminateJobObject(Pointer hJob, int uExitCode)
        {
            return true;
        }

        @Override
        public boolean CreateProcessW(WString lpApplicationName, Pointer lpCommandLine,
            Pointer lpProcessAttributes, Pointer lpThreadAttributes, boolean bInheritHandles,
            int dwCreationFlags, Pointer lpEnvironment, WString lpCurrentDirectory,
            Structure lpStartupInfo, Structure lpProcessInformation)
        {
            commandBytes = lpCommandLine.getByteArray(0, (int) ((Memory) lpCommandLine).size());
            WindowsJobContainment.PROCESS_INFORMATION created =
                (WindowsJobContainment.PROCESS_INFORMATION) lpProcessInformation;
            created.hProcess = process;
            created.hThread = new Memory(8);
            created.dwProcessId = 4242;
            created.dwThreadId = 5678;
            created.write();
            return true;
        }

        @Override
        public int ResumeThread(Pointer hThread)
        {
            resumeThreadCalls++;
            return 1;
        }

        @Override
        public boolean TerminateProcess(Pointer hProcess, int uExitCode)
        {
            if (terminateProcessResult)
            {
                processSignaled = true;
            }
            return terminateProcessResult;
        }

        @Override
        public int WaitForSingleObject(Pointer hHandle, int dwMilliseconds)
        {
            return processSignaled ? 0 : waitResult;
        }

        @Override
        public boolean GetExitCodeProcess(Pointer hProcess, IntByReference lpExitCode)
        {
            lpExitCode.setValue(0);
            return true;
        }

        @Override
        public boolean CloseHandle(Pointer hObject)
        {
            if (hObject == process)
            {
                processClosed = true;
            }
            return hObject == process || closeJobSucceeds;
        }
    }
}
