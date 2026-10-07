/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;

/**
 * A suspended 1C session with no client behind it.
 *
 * <p>The debug tools reach a live session through Eclipse objects - a thread, its stack, the variables
 * in a frame - and every rule they carry about those objects is a rule about shape, not about 1C: which
 * input wins, what is refused, what the answer names. These fakes give that shape: a thread whose stack
 * can be replaced between stops, frames that answer with the variables a test put there, and variables
 * that record what was written into them.</p>
 *
 * <p>Methods a fake does not care about answer the zero of their return type, so a proxy stands in for
 * the whole interface without a hand-written stub per method.</p>
 */
public final class FakeDebugFrames
{
    /** The debug model a fake frame belongs to; no delegate is registered for it. */
    public static final String MODEL_ID = "ru.aiedt.mcp.server.tests.fake"; //$NON-NLS-1$

    /** The scope getters only a BSL stack frame offers, declared so a fake frame can answer them. */
    public interface ModuleScope
    {
        /**
         * @return the module-level variables of the frame
         * @throws Exception when the frame cannot read them
         */
        IVariable[] getModuleVariables() throws Exception;

        /**
         * @return the module properties of the frame
         * @throws Exception when the frame cannot read them
         */
        IVariable[] getModuleProperties() throws Exception;
    }

    private FakeDebugFrames()
    {
        // factory
    }

    /**
     * One suspended thread of one application, with a stack that changes when the session moves on.
     *
     * <p>The application id is what ties the thread to a launch: the fakes answer the launch
     * configuration attribute every debug tool addresses a session by, so registering a frame from
     * this session records the application it belongs to.</p>
     *
     * <p>A session starts suspended, which is what the tools that read a stopped session want. A test
     * for the pause path starts it running instead ({@code suspended = false, canSuspend = true}) and
     * lets {@code suspend()} report the suspend back the way the platform does.</p>
     */
    public static final class Session
    {
        /** The application id the session answers to. */
        public final String applicationId;

        /** The frames the thread holds right now; replaced by {@link #moveTo}. */
        private IStackFrame[] stack = new IStackFrame[0];

        /** What {@code getModuleVariables} answers, when the frame declares the scope. */
        public IVariable[] moduleVariables = new IVariable[0];

        /** What {@code getModuleProperties} answers, when the frame declares the scope. */
        public IVariable[] moduleProperties = new IVariable[0];

        /** When set, {@code getModuleVariables} throws this instead of answering. */
        public RuntimeException moduleFailure;

        /** Whether the thread reports itself suspended. A pause test sets this to false first. */
        public boolean suspended = true;

        /** What {@code canSuspend} answers for a running thread. */
        public boolean canSuspend;

        /** Whether an accepted {@code suspend()} reports the suspend back through the registry. */
        public boolean suspendsOnRequest = true;

        /** When set, {@code suspend()} throws it instead of accepting the request. */
        public Exception suspendRefusal;

        /** How many suspend requests the thread received, so a test can see the ask was placed. */
        public int suspendRequests;

        /** Run inside {@code suspend()}, for the platform state a test wants to change at that moment. */
        public Runnable onSuspendRequest;

        /** Whether a stopped thread accepts a resume. A resume test keeps its session stopped. */
        public boolean canResume = true;

        /** When set, {@code resume()} throws it instead of resuming. */
        public Exception resumeRefusal;

        /** Run inside {@code resume()}, for the platform events a test wants delivered while the resume is being made. */
        public Runnable onResumeRequest;

        /** How many resumes the thread received. */
        public int resumeRequests;

        /** Whether {@code canStepOver} accepts a step over. */
        public boolean canStepOver;

        /** Whether {@code canStepInto} accepts a step into. */
        public boolean canStepInto;

        /** Whether {@code canStepReturn} accepts a step out. */
        public boolean canStepReturn;

        /** How many steps the thread was sent, whichever kind. */
        public int stepRequests;

        /** The target this thread belongs to; it carries the thread in its thread list. */
        public final Target target;

        private final IThread thread;

        /**
         * @param applicationId the application the session belongs to
         */
        Session(String applicationId)
        {
            this.applicationId = applicationId;
            this.target = new Target(applicationId);
            this.thread = asThread();
            this.target.with(thread);
        }

        /**
         * @return the target this session's thread belongs to, as the debug tools meet it
         */
        public IDebugTarget debugTarget()
        {
            return target.asDebugTarget();
        }

        /**
         * What a suspend request does on this thread: refuse if a refusal was set, otherwise accept it
         * and - unless the test asked for a request that never lands - report the stop back through the
         * registry, the way the platform does.
         *
         * @throws Exception the refusal, when the test set one
         */
        private void onSuspendRequested() throws Exception
        {
            suspendRequests++;
            if (suspendRefusal != null)
            {
                throw suspendRefusal;
            }
            suspended = true;
            Runnable hook = onSuspendRequest;
            if (hook != null)
            {
                hook.run();
            }
            if (suspendsOnRequest)
            {
                DebugSessionBook.get().injectSuspend(applicationId, thread);
            }
        }

        /**
         * What a resume does on this thread: refuse if a refusal was set, otherwise leave the stopped
         * state. The platform reports the resume as an event of its own, later, which is why the
         * tools that drop the snapshot do it themselves.
         *
         * @throws Exception the refusal, when the test set one
         */
        private void onResumeRequested() throws Exception
        {
            resumeRequests++;
            if (resumeRefusal != null)
            {
                throw resumeRefusal;
            }
            suspended = false;
            Runnable hook = onResumeRequest;
            if (hook != null)
            {
                hook.run();
            }
        }

        /**
         * @return the thread, as the debug tools meet it
         */
        public IThread thread()
        {
            return thread;
        }

        /**
         * @return the frames the thread holds now
         */
        public IStackFrame[] stack()
        {
            return stack;
        }

        /**
         * What a stop does: the frames the thread held are gone and these stand in their place.
         *
         * @param frames the frames the thread holds after the stop
         */
        public void moveTo(IStackFrame... frames)
        {
            this.stack = frames;
        }

        /**
         * @param name the name the frame reports
         * @param variables the variables the frame holds
         * @return a frame of this session
         */
        public IStackFrame frame(String name, IVariable... variables)
        {
            return (IStackFrame)Proxy.newProxyInstance(FakeDebugFrames.class.getClassLoader(),
                new Class<?>[] { IStackFrame.class }, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                    case "getName": //$NON-NLS-1$
                        return name;
                    case "getVariables": //$NON-NLS-1$
                        return variables;
                    case "getThread": //$NON-NLS-1$
                        return thread;
                    case "getDebugTarget": //$NON-NLS-1$
                        return target(applicationId);
                    case "getModelIdentifier": //$NON-NLS-1$
                        return MODEL_ID;
                    case "equals": //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    case "hashCode": //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    case "toString": //$NON-NLS-1$
                        return name;
                    default:
                        return defaultValue(method.getReturnType());
                    }
                });
        }

        /**
         * A frame of this session that also carries the module scopes, which only a BSL frame has.
         *
         * @param name the name the frame reports
         * @param variables the local variables the frame holds
         * @return the frame
         */
        public IStackFrame moduleFrame(String name, IVariable... variables)
        {
            return (IStackFrame)Proxy.newProxyInstance(FakeDebugFrames.class.getClassLoader(),
                new Class<?>[] { IStackFrame.class, ModuleScope.class }, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                    case "getName": //$NON-NLS-1$
                        return name;
                    case "getVariables": //$NON-NLS-1$
                        return variables;
                    case "getThread": //$NON-NLS-1$
                        return thread;
                    case "getDebugTarget": //$NON-NLS-1$
                        return target(applicationId);
                    case "getModelIdentifier": //$NON-NLS-1$
                        return MODEL_ID;
                    case "getModuleVariables": //$NON-NLS-1$
                        if (moduleFailure != null)
                        {
                            throw moduleFailure;
                        }
                        return moduleVariables;
                    case "getModuleProperties": //$NON-NLS-1$
                        return moduleProperties;
                    case "equals": //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    case "hashCode": //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    case "toString": //$NON-NLS-1$
                        return name;
                    default:
                        return defaultValue(method.getReturnType());
                    }
                });
        }

        private IThread asThread()
        {
            return (IThread)Proxy.newProxyInstance(FakeDebugFrames.class.getClassLoader(),
                new Class<?>[] { IThread.class }, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                    case "getStackFrames": //$NON-NLS-1$
                        return stack;
                    case "getDebugTarget": //$NON-NLS-1$
                        return target.asDebugTarget();
                    case "getName": //$NON-NLS-1$
                        return "Session thread"; //$NON-NLS-1$
                    case "isSuspended": //$NON-NLS-1$
                        return Boolean.valueOf(suspended);
                    case "canSuspend": //$NON-NLS-1$
                        return Boolean.valueOf(canSuspend);
                    case "suspend": //$NON-NLS-1$
                        onSuspendRequested();
                        return null;
                    case "canResume": //$NON-NLS-1$
                        return Boolean.valueOf(suspended && canResume);
                    case "resume": //$NON-NLS-1$
                        onResumeRequested();
                        return null;
                    case "canStepOver": //$NON-NLS-1$
                        return Boolean.valueOf(canStepOver);
                    case "canStepInto": //$NON-NLS-1$
                        return Boolean.valueOf(canStepInto);
                    case "canStepReturn": //$NON-NLS-1$
                        return Boolean.valueOf(canStepReturn);
                    case "stepOver": //$NON-NLS-1$
                    case "stepInto": //$NON-NLS-1$
                    case "stepReturn": //$NON-NLS-1$
                        stepRequests++;
                        return null;
                    case "isTerminated": //$NON-NLS-1$
                        return Boolean.valueOf(target.terminated);
                    case "equals": //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    case "hashCode": //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    case "toString": //$NON-NLS-1$
                        return "thread of " + applicationId; //$NON-NLS-1$
                    default:
                        return defaultValue(method.getReturnType());
                    }
                });
        }
    }

    /**
     * The debug target of a launch: the threads the pause path asks, and whether the launch is still
     * alive.
     *
     * <p>By default the target is alive and exposes no thread, which is what every frame's
     * {@code getDebugTarget} answers - enough for the tools that only walk target to launch to launch
     * configuration to learn an application id. A pause test builds one, hands it to the tool, and sees
     * the ask arrive on the thread it put there.</p>
     */
    public static final class Target
    {
        /** The application the target answers to. */
        public final String applicationId;

        /** Whether the target reports itself terminated. */
        public boolean terminated;

        /** The threads the target exposes, in the order {@code getThreads} answers them. */
        public final List<IThread> threads = new ArrayList<>();

        /** How many suspend requests the target itself received. */
        public int suspendRequests;

        /** How many resume requests the target itself received. */
        public int resumeRequests;

        /** When set, a suspend on the target itself throws it instead of being accepted. */
        public Exception suspendRefusal;

        /**
         * @param applicationId the application the target answers to
         */
        public Target(String applicationId)
        {
            this.applicationId = applicationId;
        }

        /**
         * @param thread a thread of this target
         * @return this target
         */
        public Target with(IThread thread)
        {
            threads.add(thread);
            return this;
        }

        /**
         * @return the target, as the debug tools meet it
         */
        public IDebugTarget asDebugTarget()
        {
            ILaunch launch = FakeDebugFrames.launch(applicationId);
            return (IDebugTarget)Proxy.newProxyInstance(FakeDebugFrames.class.getClassLoader(),
                new Class<?>[] { IDebugTarget.class }, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                    case "getLaunch": //$NON-NLS-1$
                        return launch;
                    case "isTerminated": //$NON-NLS-1$
                        return Boolean.valueOf(terminated);
                    case "getThreads": //$NON-NLS-1$
                        return threads.toArray(new IThread[0]);
                    case "getName": //$NON-NLS-1$
                        return "target of " + applicationId; //$NON-NLS-1$
                    case "suspend": //$NON-NLS-1$
                        suspendRequests++;
                        if (suspendRefusal != null)
                        {
                            throw suspendRefusal;
                        }
                        return null;
                    case "canResume": //$NON-NLS-1$
                        // A debug target of this platform does not resume; only its threads do.
                        return Boolean.FALSE;
                    case "resume": //$NON-NLS-1$
                        resumeRequests++;
                        return null;
                    case "equals": //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    case "hashCode": //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    case "toString": //$NON-NLS-1$
                        return "target of " + applicationId; //$NON-NLS-1$
                    default:
                        return defaultValue(method.getReturnType());
                    }
                });
        }
    }

    /**
     * @param applicationId the application the session belongs to
     * @return the session, with a thread that holds no frames until {@link Session#moveTo} gives it some
     */
    public static Session session(String applicationId)
    {
        return new Session(applicationId);
    }

    /**
     * Delivers the registry side of the platform's RESUME event: everything the application issued
     * is dropped, the way the event listener does it. A resume hook that wants the event to arrive
     * while the resume is still being made calls this.
     *
     * @param applicationId the application that resumed
     */
    public static void resumeEvent(String applicationId)
    {
        DebugSessionBook.get().forget(applicationId);
    }

    /** A variable of a frame, remembering what was written into it. */
    public static final class Var
    {
        /** The name the variable reports. */
        public final String name;

        /** The type the value reports. */
        public String type = "Число"; //$NON-NLS-1$

        /** The value the variable renders. */
        public String value = "0"; //$NON-NLS-1$

        /** Whether the variable accepts a new value at all. */
        public boolean modifiable = true;

        /** Whether the variable accepts the value it was offered. */
        public boolean verifies = true;

        /** Every value written into the variable, in the order it was written. */
        public final List<String> written = new ArrayList<>();

        /** The children the value carries, for the frames that expand a composite. */
        public final List<Var> children = new ArrayList<>();

        /**
         * @param name the name the variable reports
         */
        public Var(String name)
        {
            this.name = name;
        }

        /**
         * @param newType the type the value reports
         * @return this variable
         */
        public Var type(String newType)
        {
            this.type = newType;
            return this;
        }

        /**
         * @param newValue the value the variable renders
         * @return this variable
         */
        public Var value(String newValue)
        {
            this.value = newValue;
            return this;
        }

        /**
         * @param child a child of the value
         * @return this variable
         */
        public Var child(Var child)
        {
            this.children.add(child);
            return this;
        }

        /**
         * @return the variable, as the debug tools meet it
         */
        public IVariable asVariable()
        {
            return (IVariable)Proxy.newProxyInstance(FakeDebugFrames.class.getClassLoader(),
                new Class<?>[] { IVariable.class }, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                    case "getName": //$NON-NLS-1$
                        return name;
                    case "getValue": //$NON-NLS-1$
                        return asValue();
                    case "supportsValueModification": //$NON-NLS-1$
                        return Boolean.valueOf(modifiable);
                    case "verifyValue": //$NON-NLS-1$
                        return Boolean.valueOf(verifies);
                    case "setValue": //$NON-NLS-1$
                        written.add((String)args[0]);
                        this.value = (String)args[0];
                        return null;
                    case "equals": //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    case "hashCode": //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    case "toString": //$NON-NLS-1$
                        return name;
                    default:
                        return defaultValue(method.getReturnType());
                    }
                });
        }

        /**
         * @return the value, as the debug tools meet it
         */
        public IValue asValue()
        {
            IVariable[] kids = new IVariable[children.size()];
            for (int i = 0; i < kids.length; i++)
            {
                kids[i] = children.get(i).asVariable();
            }
            return (IValue)Proxy.newProxyInstance(FakeDebugFrames.class.getClassLoader(),
                new Class<?>[] { IValue.class }, (proxy, method, args) -> {
                    switch (method.getName())
                    {
                    case "getReferenceTypeName": //$NON-NLS-1$
                        return type;
                    case "getValueString": //$NON-NLS-1$
                        return value;
                    case "hasVariables": //$NON-NLS-1$
                        return Boolean.valueOf(kids.length > 0);
                    case "getVariables": //$NON-NLS-1$
                        return kids;
                    case "equals": //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    case "hashCode": //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    case "toString": //$NON-NLS-1$
                        return value;
                    default:
                        return defaultValue(method.getReturnType());
                    }
                });
        }
    }

    /**
     * @param name the name the variable reports
     * @return a variable holding a number
     */
    public static Var var(String name)
    {
        return new Var(name);
    }

    /**
     * The debug target of a launch, and the launch configuration behind it carrying the application
     * id - the chain {@code DebugSessionBook} walks to learn which application a frame belongs to.
     *
     * @param applicationId the application id the configuration carries
     * @return the target, alive and exposing no thread
     */
    private static IDebugTarget target(String applicationId)
    {
        return new Target(applicationId).asDebugTarget();
    }

    /**
     * @param applicationId the application id the configuration carries
     * @return the launch
     */
    private static ILaunch launch(String applicationId)
    {
        ILaunchConfiguration configuration = configuration(applicationId);
        return (ILaunch)Proxy.newProxyInstance(FakeDebugFrames.class.getClassLoader(),
            new Class<?>[] { ILaunch.class }, new InvocationHandler()
            {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args)
                {
                    switch (method.getName())
                    {
                    case "getLaunchConfiguration": //$NON-NLS-1$
                        return configuration;
                    case "isTerminated": //$NON-NLS-1$
                        return Boolean.FALSE;
                    case "getDebugTargets": //$NON-NLS-1$
                        return new IDebugTarget[0];
                    case "equals": //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    case "hashCode": //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    case "toString": //$NON-NLS-1$
                        return "launch of " + applicationId; //$NON-NLS-1$
                    default:
                        return defaultValue(method.getReturnType());
                    }
                }
            });
    }

    /**
     * @param applicationId the application id the configuration carries
     * @return the launch configuration, a runtime client bound to that application
     */
    private static ILaunchConfiguration configuration(String applicationId)
    {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(LaunchConfigAccess.ATTR_APPLICATION_ID, applicationId);
        ILaunchConfigurationType type = (ILaunchConfigurationType)Proxy.newProxyInstance(
            FakeDebugFrames.class.getClassLoader(), new Class<?>[] { ILaunchConfigurationType.class },
            (proxy, method, args) -> "getIdentifier".equals(method.getName()) //$NON-NLS-1$
                ? LaunchConfigAccess.LAUNCH_CONFIG_TYPE_ID : defaultValue(method.getReturnType()));
        return (ILaunchConfiguration)Proxy.newProxyInstance(FakeDebugFrames.class.getClassLoader(),
            new Class<?>[] { ILaunchConfiguration.class }, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "getName": //$NON-NLS-1$
                    return "session-" + applicationId; //$NON-NLS-1$
                case "getMemento": //$NON-NLS-1$
                    return "m-" + applicationId; //$NON-NLS-1$
                case "getType": //$NON-NLS-1$
                    return type;
                case "exists": //$NON-NLS-1$
                    return Boolean.TRUE;
                case "getAttribute": //$NON-NLS-1$
                    Object value = attributes.get(args[0]);
                    return value != null ? value : args[1];
                case "equals": //$NON-NLS-1$
                    return Boolean.valueOf(proxy == args[0]);
                case "hashCode": //$NON-NLS-1$
                    return Integer.valueOf(System.identityHashCode(proxy));
                default:
                    return defaultValue(method.getReturnType());
                }
            });
    }

    /**
     * The value a proxy returns for a method the fake does not care about.
     *
     * @param type the method's return type
     * @return nothing, of the right shape
     */
    static Object defaultValue(Class<?> type)
    {
        if (type.isArray())
        {
            return Array.newInstance(type.getComponentType(), 0);
        }
        if (!type.isPrimitive())
        {
            return null;
        }
        if (type == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == int.class)
        {
            return Integer.valueOf(0);
        }
        if (type == long.class)
        {
            return Long.valueOf(0L);
        }
        if (type == double.class)
        {
            return Double.valueOf(0);
        }
        if (type == float.class)
        {
            return Float.valueOf(0);
        }
        if (type == char.class)
        {
            return Character.valueOf('\0');
        }
        if (type == short.class)
        {
            return Short.valueOf((short)0);
        }
        if (type == byte.class)
        {
            return Byte.valueOf((byte)0);
        }
        return null;
    }
}
