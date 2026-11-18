/*
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;

/**
 * Windows containment of one client process tree by a private job object.
 * <p>
 * The client is not wrapped and its configured executable is not replaced. It is started by
 * {@code CreateProcessW} with {@code CREATE_SUSPENDED}, assigned to an unnamed job object while
 * it cannot execute one instruction, and only then resumed. A child it creates is associated with
 * the job from creation, and the association cannot be broken unless the job permits breakaway -
 * this one does not. The job is created with {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}, so
 * closing the last handle terminates whatever the tree still holds.
 * </p>
 * <p>
 * The terminal observation is {@code ActiveProcesses == 0} from
 * {@code JobObjectBasicAccountingInformation}, after the root process handle has been waited on
 * and closed. That value may lag the real exit while the system releases references; it cannot
 * run ahead of a live associated process. A query that fails is therefore read as alive, never as
 * gone.
 * </p>
 * <p>
 * JNA is the vehicle, not a new dependency: the EDT platform carries {@code com.sun.jna} through
 * its third-party feature, and the job-object calls are not part of JNA Platform (5.13 and 5.18
 * both lack them), so they are declared here against the JNA core. The backend is loaded only on
 * Windows; on every other platform the factory refuses before a client is started.
 * </p>
 */
public final class WindowsJobContainment implements OwnedProcessContainment
{
    /** {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}. */
    static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;

    /** {@code JobObjectBasicAccountingInformation}. */
    static final int JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION = 1;

    /** {@code JobObjectExtendedLimitInformation}. */
    static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;

    /** {@code CREATE_SUSPENDED}. */
    static final int CREATE_SUSPENDED = 0x00000004;

    /** {@code CREATE_UNICODE_ENVIRONMENT}. */
    static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;

    private static final int WAIT_OBJECT_0 = 0;

    /** How long a termination request is given to show on the process handle. */
    private static final long CONFIRM_MS = 5000L;

    private static final WindowsJobApi API;

    private static final String UNAVAILABLE;

    static
    {
        WindowsJobApi loaded = null;
        String unavailable = null;
        try
        {
            if (Native.POINTER_SIZE != 8)
            {
                unavailable = "Windows job-object containment needs a 64-bit JVM; this JVM is "
                    + (Native.POINTER_SIZE * 8) + "-bit.";
            }
            else
            {
                loaded = Native.load("kernel32", WindowsJobApi.class);
                if (loaded == null)
                {
                    unavailable = "Windows job-object containment could not load kernel32.";
                }
            }
        }
        catch (Throwable failed)
        {
            unavailable = "Windows job-object containment could not load the JNA backend: " + failed;
        }
        API = loaded;
        UNAVAILABLE = unavailable;
    }

    private final WindowsJobApi api;

    private final Object lock = new Object();

    private final long pid;

    private final boolean established;

    private Pointer job;

    private Pointer process;

    private boolean rootExited;

    private int exitCode = -1;

    private volatile Proof proof = Proof.NOT_PROVEN;

    private boolean closed;

    WindowsJobContainment(WindowsJobApi api, Pointer job, Pointer process, long pid)
    {
        this(api, job, process, pid, true);
    }

    WindowsJobContainment(WindowsJobApi api, Pointer job, Pointer process, long pid,
        boolean established)
    {
        this.api = api;
        this.job = job;
        this.process = process;
        this.pid = pid;
        this.established = established;
    }

    /**
     * Why this backend cannot be used here, or {@code null} when it can.
     *
     * @return the reason, or {@code null}
     */
    public static String unavailableReason()
    {
        return UNAVAILABLE;
    }

    /**
     * Starts one client under a new private job object, suspended until it is a member.
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
        return spawn(command, workingDirectory, API);
    }

    /**
     * The spawn with an explicit native API, for the finite unit test that has to see a failed
     * assignment without starting a real client.
     */
    static OwnedProcessContainment spawn(List<String> command, File workingDirectory,
        WindowsJobApi api) throws IOException, ContainmentUnavailableException
    {
        if (api == null)
        {
            throw new ContainmentUnavailableException(UNAVAILABLE != null ? UNAVAILABLE
                : "Windows job-object containment is not loaded.");
        }
        if (command == null || command.isEmpty())
        {
            throw new IOException("No client command was given.");
        }
        String line = WindowsCommandLine.join(command);

        Pointer job = api.CreateJobObjectW(null, null);
        if (job == null)
        {
            throw new IOException("CreateJobObjectW failed (" + lastError() + ").");
        }
        Pointer process = null;
        Pointer thread = null;
        try
        {
            JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
            limits.BasicLimitInformation = new JOBOBJECT_BASIC_LIMIT_INFORMATION();
            limits.IoInfo = new IO_COUNTERS();
            limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            limits.write();
            if (!api.SetInformationJobObject(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, limits,
                limits.size()))
            {
                throw new IOException("SetInformationJobObject failed (" + lastError()
                    + "); no client was started.");
            }

            STARTUPINFO startup = new STARTUPINFO();
            startup.cb = startup.size();
            startup.write();
            PROCESS_INFORMATION created = new PROCESS_INFORMATION();
            Memory commandLine = new Memory((line.length() + 1L) * 2L);
            // CreateProcessW takes UTF-16LE, regardless of the host's wchar_t size.
            for (int i = 0; i < line.length(); i++)
            {
                char unit = line.charAt(i);
                commandLine.setByte(i * 2L, (byte) unit);
                commandLine.setByte(i * 2L + 1L, (byte) (unit >>> 8));
            }
            commandLine.setShort(line.length() * 2L, (short) 0);
            WString currentDirectory = workingDirectory == null
                ? null : new WString(workingDirectory.getAbsolutePath());
            // The module name is the first token of the command line, as ProcessBuilder's was.
            // Passing NULL here lets Windows resolve a bare name through its search path; the
            // configured 1C executable is an absolute path and does not depend on that.
            if (!api.CreateProcessW(null, commandLine, null, null, false,
                CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT, null, currentDirectory, startup,
                created))
            {
                throw new IOException("CreateProcessW failed (" + lastError()
                    + "); no client was started.");
            }
            created.read();
            process = created.hProcess;
            thread = created.hThread;
            if (process == null || thread == null)
            {
                throw new IOException("CreateProcessW returned no process or thread handle.");
            }

            // The process cannot execute user code while suspended, so this is the only window in
            // which no child can exist. Assignment before resume is the whole point.
            if (!api.AssignProcessToJobObject(job, process))
            {
                int assignError = Native.getLastError();
                boolean requested = api.TerminateProcess(process, 1);
                boolean gone = api.WaitForSingleObject(process, (int)CONFIRM_MS) == WAIT_OBJECT_0;
                if (!gone)
                {
                    // The process is suspended, unassigned and not terminated. Closing the empty
                    // job cannot kill it, so keep its handle and report a retained root instead of
                    // claiming a termination nobody observed.
                    api.CloseHandle(thread);
                    thread = null;
                    api.CloseHandle(job);
                    job = null;
                    OwnedProcessContainment retained = new WindowsJobContainment(api, null, process,
                        Integer.toUnsignedLong(created.dwProcessId), false);
                    process = null;
                    return retained;
                }
                throw new IOException("AssignProcessToJobObject failed (" + assignError
                    + "); the suspended client " + (requested ? "was terminated" : "had already exited")
                    + " and never resumed.");
            }
            if (api.ResumeThread(thread) == -1)
            {
                api.TerminateJobObject(job, 1);
                throw new IOException("ResumeThread failed (" + lastError()
                    + "); the client was terminated before it ran.");
            }
            api.CloseHandle(thread);
            thread = null;

            OwnedProcessContainment containment = new WindowsJobContainment(api, job, process,
                Integer.toUnsignedLong(created.dwProcessId), true);
            job = null;
            process = null;
            return containment;
        }
        finally
        {
            if (thread != null)
            {
                api.CloseHandle(thread);
            }
            if (process != null)
            {
                api.CloseHandle(process);
            }
            if (job != null)
            {
                // KILL_ON_JOB_CLOSE makes this the failure path that cannot leave a client behind.
                api.CloseHandle(job);
            }
        }
    }

    @Override
    public long pid()
    {
        return pid;
    }

    @Override
    public boolean established()
    {
        return established;
    }

    @Override
    public Proof proof()
    {
        return proof;
    }

    @Override
    public boolean isTreeAlive()
    {
        synchronized (lock)
        {
            if (closed)
            {
                return false;
            }
            if (!established)
            {
                return retainedRootAliveLocked();
            }
            if (!rootExited)
            {
                if (process == null)
                {
                    // A missing root handle with an empty job does not prove that the root was
                    // assigned or ever exited. Stay unknown and alive; never record a zero here.
                    return true;
                }
                if (api.WaitForSingleObject(process, 0) != WAIT_OBJECT_0)
                {
                    return true;
                }
                IntByReference code = new IntByReference();
                if (api.GetExitCodeProcess(process, code))
                {
                    exitCode = code.getValue();
                }
                api.CloseHandle(process);
                process = null;
                rootExited = true;
            }
            // The root exit was observed at this point or on an earlier call. Only a successful
            // query that reports no associated process records the terminal zero.
            int active = activeProcessesLocked();
            if (active == 0)
            {
                proof = Proof.JOB_OBJECT_ACTIVE_PROCESSES_ZERO;
                return false;
            }
            return true;
        }
    }

    private boolean retainedRootAliveLocked()
    {
        if (process == null)
        {
            // A missing root handle is not an exit observation; fail closed.
            return !rootExited;
        }
        if (api.WaitForSingleObject(process, 0) != WAIT_OBJECT_0)
        {
            return true;
        }
        IntByReference code = new IntByReference();
        if (api.GetExitCodeProcess(process, code))
        {
            exitCode = code.getValue();
        }
        api.CloseHandle(process);
        process = null;
        rootExited = true;
        proof = Proof.ROOT_HANDLE_EXIT;
        return false;
    }

    @Override
    public int exitCode()
    {
        synchronized (lock)
        {
            return exitCode;
        }
    }

    @Override
    public boolean terminateAndAwait(long waitMs)
    {
        synchronized (lock)
        {
            if (closed)
            {
                return true;
            }
            if (!established)
            {
                if (process == null)
                {
                    return true;
                }
                api.TerminateProcess(process, 1);
            }
            else
            {
                if (job == null)
                {
                    return true;
                }
                api.TerminateJobObject(job, 1);
            }
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, waitMs));
        while (true)
        {
            if (!isTreeAlive())
            {
                return true;
            }
            if (System.nanoTime() >= deadline)
            {
                return false;
            }
            try
            {
                Thread.sleep(20L);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                return !isTreeAlive();
            }
        }
    }

    @Override
    public boolean tryClose()
    {
        if (!tryTerminateForClose())
        {
            return false;
        }
        synchronized (lock)
        {
            if (closed)
            {
                return true;
            }
            if (process != null)
            {
                if (api.WaitForSingleObject(process, 0) != WAIT_OBJECT_0)
                {
                    return false;
                }
                if (!api.CloseHandle(process))
                {
                    return false;
                }
                process = null;
            }
            if (job != null)
            {
                if (!api.CloseHandle(job))
                {
                    // KILL_ON_JOB_CLOSE did not run; keep the handle and let a retry try again.
                    return false;
                }
                job = null;
            }
            closed = true;
            return true;
        }
    }

    private boolean tryTerminateForClose()
    {
        if (!isTreeAlive())
        {
            return true;
        }
        return terminateAndAwait(CONFIRM_MS);
    }

    @Override
    public void close()
    {
        if (!tryClose())
        {
            throw new IllegalStateException("The owned client containment is still retained.");
        }
    }

    @Override
    public String backend()
    {
        return "windowsJobObject"; //$NON-NLS-1$
    }

    private int activeProcessesLocked()
    {
        if (job == null)
        {
            // No job handle means no successful query; it is not a zero observation.
            return -1;
        }
        JOBOBJECT_BASIC_ACCOUNTING_INFORMATION accounting =
            new JOBOBJECT_BASIC_ACCOUNTING_INFORMATION();
        if (!api.QueryInformationJobObject(job, JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION,
            accounting, accounting.size(), null))
        {
            // A query that cannot answer is not evidence that the tree is gone.
            return -1;
        }
        accounting.read();
        return accounting.ActiveProcesses;
    }

    private static String lastError()
    {
        int error = Native.getLastError();
        return error == 0 ? "no error code" : Integer.toString(error);
    }

    /** The native calls this backend needs; JNA Platform does not declare the job calls. */
    public interface WindowsJobApi extends Library
    {
        Pointer CreateJobObjectW(Pointer lpJobAttributes, WString lpName);

        boolean SetInformationJobObject(Pointer hJob, int infoClass, Structure lpInfo, int cbInfo);

        boolean AssignProcessToJobObject(Pointer hJob, Pointer hProcess);

        boolean QueryInformationJobObject(Pointer hJob, int infoClass, Structure lpInfo,
            int cbInfo, IntByReference lpReturnLength);

        boolean TerminateJobObject(Pointer hJob, int uExitCode);

        boolean CreateProcessW(WString lpApplicationName, Pointer lpCommandLine,
            Pointer lpProcessAttributes, Pointer lpThreadAttributes, boolean bInheritHandles,
            int dwCreationFlags, Pointer lpEnvironment, WString lpCurrentDirectory,
            Structure lpStartupInfo, Structure lpProcessInformation);

        int ResumeThread(Pointer hThread);

        boolean TerminateProcess(Pointer hProcess, int uExitCode);

        int WaitForSingleObject(Pointer hHandle, int dwMilliseconds);

        boolean GetExitCodeProcess(Pointer hProcess, IntByReference lpExitCode);

        boolean CloseHandle(Pointer hObject);
    }

    /* {@code STARTUPINFOW}; 104 bytes on 64-bit. */
    @Structure.FieldOrder({"cb", "lpReserved", "lpDesktop", "lpTitle", "dwX", "dwY", "dwXSize",
        "dwYSize", "dwXCountChars", "dwYCountChars", "dwFillAttribute", "dwFlags", "wShowWindow",
        "cbReserved2", "lpReserved2", "hStdInput", "hStdOutput", "hStdError"})
    public static class STARTUPINFO extends Structure
    {
        public int cb;

        public Pointer lpReserved;

        public Pointer lpDesktop;

        public Pointer lpTitle;

        public int dwX;

        public int dwY;

        public int dwXSize;

        public int dwYSize;

        public int dwXCountChars;

        public int dwYCountChars;

        public int dwFillAttribute;

        public int dwFlags;

        public short wShowWindow;

        public short cbReserved2;

        public Pointer lpReserved2;

        public Pointer hStdInput;

        public Pointer hStdOutput;

        public Pointer hStdError;
    }

    /* {@code PROCESS_INFORMATION}; 24 bytes on 64-bit. */
    @Structure.FieldOrder({"hProcess", "hThread", "dwProcessId", "dwThreadId"})
    public static class PROCESS_INFORMATION extends Structure
    {
        public Pointer hProcess;

        public Pointer hThread;

        public int dwProcessId;

        public int dwThreadId;
    }

    /* {@code JOBOBJECT_BASIC_LIMIT_INFORMATION}; 64 bytes on 64-bit. */
    @Structure.FieldOrder({"PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags",
        "MinimumWorkingSetSize", "MaximumWorkingSetSize", "ActiveProcessLimit", "Affinity",
        "PriorityClass", "SchedulingClass"})
    public static class JOBOBJECT_BASIC_LIMIT_INFORMATION extends Structure
    {
        public long PerProcessUserTimeLimit;

        public long PerJobUserTimeLimit;

        public int LimitFlags;

        public long MinimumWorkingSetSize;

        public long MaximumWorkingSetSize;

        public int ActiveProcessLimit;

        public long Affinity;

        public int PriorityClass;

        public int SchedulingClass;
    }

    /* {@code IO_COUNTERS}; 48 bytes. */
    @Structure.FieldOrder({"ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
        "ReadTransferCount", "WriteTransferCount", "OtherTransferCount"})
    public static class IO_COUNTERS extends Structure
    {
        public long ReadOperationCount;

        public long WriteOperationCount;

        public long OtherOperationCount;

        public long ReadTransferCount;

        public long WriteTransferCount;

        public long OtherTransferCount;
    }

    /* {@code JOBOBJECT_EXTENDED_LIMIT_INFORMATION}; 144 bytes on 64-bit. */
    @Structure.FieldOrder({"BasicLimitInformation", "IoInfo", "ProcessMemoryLimit",
        "JobMemoryLimit", "PeakProcessMemoryUsed", "PeakJobMemoryUsed"})
    public static class JOBOBJECT_EXTENDED_LIMIT_INFORMATION extends Structure
    {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;

        public IO_COUNTERS IoInfo;

        public long ProcessMemoryLimit;

        public long JobMemoryLimit;

        public long PeakProcessMemoryUsed;

        public long PeakJobMemoryUsed;
    }

    /* {@code JOBOBJECT_BASIC_ACCOUNTING_INFORMATION}; 48 bytes on 64-bit. */
    @Structure.FieldOrder({"TotalUserTime", "TotalKernelTime", "ThisPeriodTotalUserTime",
        "ThisPeriodTotalKernelTime", "TotalPageFaultCount", "TotalProcesses", "ActiveProcesses",
        "TotalTerminatedProcesses"})
    public static class JOBOBJECT_BASIC_ACCOUNTING_INFORMATION extends Structure
    {
        public long TotalUserTime;

        public long TotalKernelTime;

        public long ThisPeriodTotalUserTime;

        public long ThisPeriodTotalKernelTime;

        public int TotalPageFaultCount;

        public int TotalProcesses;

        public int ActiveProcesses;

        public int TotalTerminatedProcesses;
    }
}
