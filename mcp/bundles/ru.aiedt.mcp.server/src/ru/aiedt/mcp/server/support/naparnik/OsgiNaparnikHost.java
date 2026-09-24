/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.naparnik;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IProject;

/**
 * The 1C:Naparnik installation in this OSGi runtime, reached only by reflection.
 * <p>
 * Nothing here names {@code com.e1c.edt.ai} as a compile dependency. {@code Platform.getBundles},
 * {@code Bundle.loadClass} and the activator methods are looked up by name. A failure is thrown
 * with the link it belongs to; it is not caught and dropped.
 * </p>
 * <p>
 * {@code getInjector} is private on {@code BaseActivator}. The running plugin is the UI activator,
 * a subclass, so the method is taken from the loaded {@code BaseActivator} class and then invoked
 * on the instance. {@code getDefault} is public and static on that same class.
 * {@code getInstance(Class)} is invoked on the public {@code com.google.inject.Injector}
 * interface: the concrete class is not public, and a method object taken from it throws
 * {@code IllegalAccessException}.
 * </p>
 */
public final class OsgiNaparnikHost
    implements NaparnikHost
{
    /** OSGi {@code Bundle.INSTALLED}. */
    private static final int INSTALLED = 2;

    /** OSGi {@code Bundle.RESOLVED}. */
    private static final int RESOLVED = 4;

    /** OSGi {@code Bundle.STARTING}. */
    private static final int STARTING = 8;

    /** OSGi {@code Bundle.STOPPING}. */
    private static final int STOPPING = 16;

    /** OSGi {@code Bundle.ACTIVE}. */
    private static final int ACTIVE = 32;

    /** Public Guice type. The runtime object implements it; its concrete class is not public. */
    private static final String INJECTOR_TYPE = "com.google.inject.Injector"; //$NON-NLS-1$

    private static final String REQUEST_CLASS = "com.e1c.edt.ai.assistent.SendUserMessageRequest"; //$NON-NLS-1$

    private static final String SESSION_CLASS = "com.e1c.edt.ai.assistent.ConversationSession"; //$NON-NLS-1$

    private static final String POLICY_CLASS = "com.e1c.edt.ai.assistent.model.SkillCompletionPolicy"; //$NON-NLS-1$

    private static final String TOKEN_CLASS = "com.e1c.edt.ai.CancellationTokenSource"; //$NON-NLS-1$

    private static final String TOKEN_INTERFACE = "com.e1c.edt.ai.ICancellationToken"; //$NON-NLS-1$

    private static final String LISTENER_INTERFACE = "com.e1c.edt.ai.IConversationProgressListener"; //$NON-NLS-1$

    /**
     * OSGi {@code Bundle.START_TRANSIENT}. Activates the bundle for this session and does not mark
     * it to start on the next launch.
     */
    private static final int START_TRANSIENT = 1;

    @Override
    public List<BundleCopy> copiesOf(String symbolicName)
        throws NaparnikAccessException
    {
        String link = "copies:" + symbolicName; //$NON-NLS-1$
        Class<?> platform = type("org.eclipse.core.runtime.Platform", link); //$NON-NLS-1$
        Object raw = callStatic(platform, "getBundles", link, //$NON-NLS-1$
            new Class<?>[] {String.class, String.class},
            new Object[] {symbolicName, null});
        if (raw == null)
        {
            return List.of();
        }
        if (!(raw instanceof Object[] bundles))
        {
            throw new NaparnikAccessException(link,
                "Platform.getBundles returned " + raw.getClass().getName(), null); //$NON-NLS-1$
        }
        List<BundleCopy> copies = new ArrayList<>();
        for (Object bundle : bundles)
        {
            if (bundle != null)
            {
                copies.add(describe(bundle, link));
            }
        }
        return copies;
    }

    @Override
    public Class<?> loadClass(BundleCopy bundle, String className)
        throws NaparnikAccessException
    {
        String link = NaparnikHost.loadClassLink(className);
        if (bundle == null || bundle.identity() == null)
        {
            throw new NaparnikAccessException(link, "no bundle to load " + className + " from", null); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Object loaded = call(bundle.identity(), "loadClass", link, //$NON-NLS-1$
            new Class<?>[] {String.class}, new Object[] {className});
        if (!(loaded instanceof Class<?> loadedType))
        {
            throw new NaparnikAccessException(link, "loadClass returned " + loaded, null); //$NON-NLS-1$
        }
        return loadedType;
    }

    @Override
    public InjectorDoor openInjector(BundleCopy uiBundle, Class<?> baseActivator)
        throws NaparnikAccessException
    {
        String link = NaparnikHost.LINK_INJECTOR;
        if (uiBundle == null || uiBundle.identity() == null)
        {
            throw new NaparnikAccessException(link, "no UI bundle to start", null); //$NON-NLS-1$
        }
        int state = stateOf(uiBundle.identity(), link);
        if (state == RESOLVED)
        {
            call(uiBundle.identity(), "start", link, //$NON-NLS-1$
                new Class<?>[] {int.class}, new Object[] {Integer.valueOf(START_TRANSIENT)});
        }
        else if (state != ACTIVE && state != STARTING)
        {
            throw new NaparnikAccessException(link,
                uiBundle.name() + " is " + stateName(state) + ", not resolved", null); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Object plugin = callStatic(baseActivator, "getDefault", link, new Class<?>[0], new Object[0]); //$NON-NLS-1$
        if (plugin == null)
        {
            throw new NaparnikAccessException(link,
                "BaseActivator.getDefault() returned null after " + uiBundle.name(), null); //$NON-NLS-1$
        }
        Object injector = readInjector(baseActivator, plugin, link);
        if (injector == null)
        {
            throw new NaparnikAccessException(link, "getInjector() returned null", null); //$NON-NLS-1$
        }
        BundleCopy activator = owningBundle(plugin.getClass(), link);
        return new InjectorDoor(injector, activator);
    }

    @Override
    public FacadeDoor openFacade(Object injector, Class<?> facadeType)
        throws NaparnikAccessException
    {
        String link = NaparnikHost.LINK_FACADE;
        Object facade = readInstance(injector, facadeType, link);
        if (facade == null)
        {
            throw new NaparnikAccessException(link, "getInstance returned null for " + facadeType.getName(), //$NON-NLS-1$
                null);
        }
        BundleCopy owner = owningBundle(facade.getClass(), link);
        return new FacadeDoor(facade, owner);
    }

    @Override
    public List<String> toolNames(Object injector, Class<?> mcpToolsType)
        throws NaparnikAccessException
    {
        String link = NaparnikHost.LINK_TOOLS;
        Object tools = readInstance(injector, mcpToolsType, link);
        if (tools == null)
        {
            throw new NaparnikAccessException(link, "getInstance returned null for " + mcpToolsType.getName(), //$NON-NLS-1$
                null);
        }
        Object future = call(tools, "getSpecifications", link, new Class<?>[0], new Object[0]); //$NON-NLS-1$
        Object list = call(future, "join", link, new Class<?>[0], new Object[0]); //$NON-NLS-1$
        if (!(list instanceof Iterable<?> specs))
        {
            throw new NaparnikAccessException(link, "getSpecifications completed as " + list, null); //$NON-NLS-1$
        }
        List<String> names = new ArrayList<>();
        for (Object spec : specs)
        {
            if (spec == null)
            {
                continue;
            }
            Object function = field(spec, "function", link); //$NON-NLS-1$
            if (function == null)
            {
                throw new NaparnikAccessException(link, "a tool specification has no function", null); //$NON-NLS-1$
            }
            Object name = field(function, "name", link); //$NON-NLS-1$
            if (name != null)
            {
                names.add(name.toString());
            }
        }
        return names;
    }

    @Override
    public RunningQuestion ask(Object facade, BundleCopy source, Question question)
        throws NaparnikAccessException
    {
        String link = NaparnikHost.LINK_ASK;
        if (facade == null || source == null || question == null)
        {
            throw new NaparnikAccessException(link, "no facade to ask", null); //$NON-NLS-1$
        }
        if (!(question.project() instanceof IProject project))
        {
            throw new NaparnikAccessException(link, "project is not an IProject", null); //$NON-NLS-1$
        }
        Class<?> requestType = loadClass(source, REQUEST_CLASS);
        Class<?> sessionType = loadClass(source, SESSION_CLASS);
        Class<?> policyType = loadClass(source, POLICY_CLASS);
        Class<?> tokenType = loadClass(source, TOKEN_CLASS);
        Class<?> tokenIface = loadClass(source, TOKEN_INTERFACE);
        Class<?> listenerType = loadClass(source, LISTENER_INTERFACE);
        Object session = null;
        if (!question.forceNew())
        {
            session = construct(sessionType, link, new Class<?>[] {String.class, String.class},
                new Object[] {question.conversationId(), question.replyTo()});
        }
        // The 9-argument constructor. completionPolicy stays null: this bridge does not ask
        // Naparnik to keep completing a skill. skillName and chat are the values the 7-argument
        // constructor writes when a caller leaves them unset.
        Object request = construct(requestType, link, new Class<?>[] {
            IProject.class, String.class, sessionType, boolean.class, String.class, Boolean.class,
            Integer.class, Set.class, policyType},
            new Object[] {
                project, question.text(), session, Boolean.valueOf(question.forceNew()),
                question.skillName(), question.chat(), Integer.valueOf(question.maxToolRounds()),
                question.allowedTools(), null});
        Object token = construct(tokenType, link, new Class<?>[0], new Object[0]);
        ReflectedQuestion running = new ReflectedQuestion(tokenType, token);
        Object listener = listener(listenerType, question, running, link);
        Object future = call(facade, "sendAsync", link, //$NON-NLS-1$
            new Class<?>[] {requestType, tokenIface, listenerType},
            new Object[] {request, token, listener});
        if (!(future instanceof CompletableFuture<?> pending))
        {
            throw new NaparnikAccessException(link, "sendAsync returned " + future, null); //$NON-NLS-1$
        }
        running.future = pending;
        return running;
    }

    private static Object construct(Class<?> type, String link, Class<?>[] parameters, Object[] args)
        throws NaparnikAccessException
    {
        try
        {
            return type.getConstructor(parameters).newInstance(args);
        }
        catch (ReflectiveOperationException failure)
        {
            throw access(link, failure);
        }
    }

    /**
     * A proxy of {@code IConversationProgressListener}. {@code onToolCallStart} records the names
     * and cancels the installation's own token when the question says to stop. Cancelling does
     * not stop the call that already started.
     */
    private static Object listener(Class<?> listenerType, Question question, ReflectedQuestion running,
        String link)
        throws NaparnikAccessException
    {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("onToolCallStart".equals(name)) //$NON-NLS-1$
            {
                List<String> names = toolNamesOf(args);
                running.toolsCalled.addAll(names);
                ToolStart notice = question.onToolStart();
                if (notice != null)
                {
                    String veto = notice.onStart(names);
                    if (veto != null)
                    {
                        running.cancel();
                    }
                }
                return null;
            }
            if ("equals".equals(name)) //$NON-NLS-1$
            {
                return Boolean.valueOf(proxy == args[0]);
            }
            if ("hashCode".equals(name)) //$NON-NLS-1$
            {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("toString".equals(name)) //$NON-NLS-1$
            {
                return "naparnik-progress"; //$NON-NLS-1$
            }
            return defaultValue(method.getReturnType());
        };
        try
        {
            return Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[] {listenerType},
                handler);
        }
        catch (RuntimeException failure)
        {
            throw new NaparnikAccessException(link, "cannot proxy the progress listener: " + failure, //$NON-NLS-1$
                failure);
        }
    }

    private static List<String> toolNamesOf(Object[] args)
    {
        List<String> names = new ArrayList<>();
        if (args == null || args.length == 0 || !(args[0] instanceof List<?> list))
        {
            return names;
        }
        for (Object item : list)
        {
            if (item != null)
            {
                names.add(item.toString());
            }
        }
        return names;
    }

    private static Object defaultValue(Class<?> type)
    {
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
        return null;
    }

    /**
     * {@code getInjector} is private on the base class. Opening it is the step that fails closed
     * when the runtime refuses {@code setAccessible}: the injector link names that refusal.
     */
    private static Object readInjector(Class<?> baseActivator, Object plugin, String link)
        throws NaparnikAccessException
    {
        Method getter;
        try
        {
            getter = baseActivator.getDeclaredMethod("getInjector"); //$NON-NLS-1$
        }
        catch (NoSuchMethodException failure)
        {
            throw new NaparnikAccessException(link,
                failure.getClass().getSimpleName() + ": " + failure.getMessage(), failure); //$NON-NLS-1$
        }
        try
        {
            getter.setAccessible(true);
        }
        catch (RuntimeException failure)
        {
            throw new NaparnikAccessException(link, "cannot open getInjector: " + failure, failure); //$NON-NLS-1$
        }
        try
        {
            return getter.invoke(plugin);
        }
        catch (ReflectiveOperationException failure)
        {
            throw access(link, failure);
        }
    }

    private static BundleCopy owningBundle(Class<?> type, String link)
        throws NaparnikAccessException
    {
        Class<?> util = type("org.osgi.framework.FrameworkUtil", link); //$NON-NLS-1$
        Object bundle = callStatic(util, "getBundle", link, //$NON-NLS-1$
            new Class<?>[] {Class.class}, new Object[] {type});
        if (bundle == null)
        {
            throw new NaparnikAccessException(link, "no bundle owns " + type.getName(), null); //$NON-NLS-1$
        }
        return describe(bundle, link);
    }

    private static BundleCopy describe(Object bundle, String link)
        throws NaparnikAccessException
    {
        String name = (String)call(bundle, "getSymbolicName", link, new Class<?>[0], new Object[0]); //$NON-NLS-1$
        Object version = call(bundle, "getVersion", link, new Class<?>[0], new Object[0]); //$NON-NLS-1$
        int state = stateOf(bundle, link);
        return new BundleCopy(name, String.valueOf(version), stateName(state), bundle);
    }

    private static int stateOf(Object bundle, String link)
        throws NaparnikAccessException
    {
        Object state = call(bundle, "getState", link, new Class<?>[0], new Object[0]); //$NON-NLS-1$
        if (!(state instanceof Number code))
        {
            throw new NaparnikAccessException(link, "getState returned " + state, null); //$NON-NLS-1$
        }
        return code.intValue();
    }

    private static String stateName(int state)
    {
        switch (state)
        {
            case INSTALLED:
                return "INSTALLED"; //$NON-NLS-1$
            case RESOLVED:
                return "RESOLVED"; //$NON-NLS-1$
            case STARTING:
                return "STARTING"; //$NON-NLS-1$
            case STOPPING:
                return "STOPPING"; //$NON-NLS-1$
            case ACTIVE:
                return "ACTIVE"; //$NON-NLS-1$
            default:
                return "STATE_" + state; //$NON-NLS-1$
        }
    }

    private static Class<?> type(String name, String link)
        throws NaparnikAccessException
    {
        try
        {
            return Class.forName(name);
        }
        catch (ClassNotFoundException failure)
        {
            throw new NaparnikAccessException(link,
                failure.getClass().getSimpleName() + ": " + failure.getMessage(), failure); //$NON-NLS-1$
        }
    }

    /**
     * Reads one binding through the public {@code com.google.inject.Injector} interface.
     * <p>
     * {@code getInjector()} returns {@code com.google.inject.internal.InjectorImpl}. That class is
     * not public. {@code getMethod} on it returns a method whose declaring class is inaccessible,
     * and {@code invoke} then throws {@code IllegalAccessException}. The interface method is
     * already public, so {@code setAccessible} is not used. The interface is taken from the object
     * and its superclasses, and only then loaded by the object's own class loader.
     * </p>
     */
    private static Object readInstance(Object injector, Class<?> type, String link)
        throws NaparnikAccessException
    {
        Class<?> iface = injectorInterface(injector, link);
        try
        {
            Method method = iface.getMethod("getInstance", Class.class); //$NON-NLS-1$
            return method.invoke(injector, type);
        }
        catch (ReflectiveOperationException failure)
        {
            throw access(link, failure);
        }
    }

    private static Class<?> injectorInterface(Object injector, String link)
        throws NaparnikAccessException
    {
        Class<?> found = findInjectorInterface(injector.getClass());
        if (found != null)
        {
            return found;
        }
        ClassLoader loader = injector.getClass().getClassLoader();
        try
        {
            return Class.forName(INJECTOR_TYPE, false, loader);
        }
        catch (ClassNotFoundException failure)
        {
            throw new NaparnikAccessException(link,
                failure.getClass().getSimpleName() + ": " + failure.getMessage(), failure); //$NON-NLS-1$
        }
    }

    /**
     * The {@code com.google.inject.Injector} type this object implements, walking superclasses and
     * superinterfaces so a subclass that does not redeclare the interface still yields it.
     */
    private static Class<?> findInjectorInterface(Class<?> type)
    {
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class)
        {
            Class<?> found = findInjectorInterfaceOn(cursor);
            if (found != null)
            {
                return found;
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    private static Class<?> findInjectorInterfaceOn(Class<?> type)
    {
        for (Class<?> iface : type.getInterfaces())
        {
            if (INJECTOR_TYPE.equals(iface.getName()))
            {
                return iface;
            }
            Class<?> nested = findInjectorInterfaceOn(iface);
            if (nested != null)
            {
                return nested;
            }
        }
        return null;
    }

    private static Object call(Object target, String method, String link, Class<?>[] types, Object[] args)
        throws NaparnikAccessException
    {
        try
        {
            Method found = target.getClass().getMethod(method, types);
            return found.invoke(target, args);
        }
        catch (ReflectiveOperationException failure)
        {
            throw access(link, failure);
        }
    }

    private static Object callStatic(Class<?> owner, String method, String link, Class<?>[] types,
        Object[] args)
        throws NaparnikAccessException
    {
        try
        {
            Method found = owner.getMethod(method, types);
            return found.invoke(null, args);
        }
        catch (ReflectiveOperationException failure)
        {
            throw access(link, failure);
        }
    }

    private static Object field(Object target, String name, String link)
        throws NaparnikAccessException
    {
        try
        {
            return target.getClass().getField(name).get(target);
        }
        catch (ReflectiveOperationException failure)
        {
            throw access(link, failure);
        }
    }

    private static NaparnikAccessException access(String link, ReflectiveOperationException failure)
    {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        String detail = cause.getClass().getSimpleName();
        if (cause.getMessage() != null)
        {
            detail = detail + ": " + cause.getMessage(); //$NON-NLS-1$
        }
        return new NaparnikAccessException(link, detail, cause);
    }

    /**
     * One question backed by Naparnik's own {@code CancellationTokenSource} and the future
     * {@code sendAsync} returned. {@code getReasoning} is not read.
     */
    private static final class ReflectedQuestion
        implements RunningQuestion
    {
        private final Class<?> tokenType;

        private final Object token;

        private final List<String> toolsCalled = java.util.Collections.synchronizedList(new ArrayList<>());

        private CompletableFuture<?> future;

        private Throwable failure;

        private String text;

        private String conversationId;

        private String replyTo;

        private int assistantMessages;

        private boolean read;

        private ReflectedQuestion(Class<?> tokenType, Object token)
        {
            this.tokenType = tokenType;
            this.token = token;
        }

        @Override
        public boolean await(long timeoutMs)
            throws NaparnikAccessException
        {
            if (future == null)
            {
                throw new NaparnikAccessException(NaparnikHost.LINK_ASK, "sendAsync returned no future", //$NON-NLS-1$
                    null);
            }
            try
            {
                Object result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                read(result);
                return true;
            }
            catch (TimeoutException elapsed)
            {
                return future.isDone() && finishQuietly();
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                return future.isDone() && finishQuietly();
            }
            catch (ExecutionException failed)
            {
                failure = failed.getCause() == null ? failed : failed.getCause();
                return true;
            }
        }

        @Override
        public void cancel()
        {
            try
            {
                tokenType.getMethod("cancel").invoke(token); //$NON-NLS-1$
            }
            catch (ReflectiveOperationException failure)
            {
                // The token is Naparnik's own CancellationTokenSource. A cancel that cannot be
                // delivered is reported when the wait ends, not dropped as a success.
                this.failure = failure.getCause() == null ? failure : failure.getCause();
            }
        }

        @Override
        public Throwable failure()
        {
            return failure;
        }

        @Override
        public String text()
        {
            return text;
        }

        @Override
        public String conversationId()
        {
            return conversationId;
        }

        @Override
        public String replyTo()
        {
            return replyTo;
        }

        @Override
        public int assistantMessages()
        {
            return assistantMessages;
        }

        @Override
        public List<String> toolsCalled()
        {
            synchronized (toolsCalled)
            {
                return List.copyOf(toolsCalled);
            }
        }

        private boolean finishQuietly()
        {
            if (failure != null || read)
            {
                return true;
            }
            try
            {
                read(future.getNow(null));
            }
            catch (RuntimeException | NaparnikAccessException failure)
            {
                // The wait already ended. A result that cannot be read is the failure of the
                // question, not a second wait.
                this.failure = failure;
            }
            return true;
        }

        private void read(Object result)
            throws NaparnikAccessException
        {
            read = true;
            if (result == null)
            {
                return;
            }
            text = string(call(result, "getText", NaparnikHost.LINK_ASK, new Class<?>[0], new Object[0])); //$NON-NLS-1$
            Object session = call(result, "getSession", NaparnikHost.LINK_ASK, new Class<?>[0], //$NON-NLS-1$
                new Object[0]);
            if (session != null)
            {
                conversationId = string(call(session, "getConversationId", NaparnikHost.LINK_ASK, //$NON-NLS-1$
                    new Class<?>[0], new Object[0]));
                replyTo = string(call(session, "getReplyToMessageUuid", NaparnikHost.LINK_ASK, //$NON-NLS-1$
                    new Class<?>[0], new Object[0]));
            }
            Object count = call(result, "getAssistantMessageCount", NaparnikHost.LINK_ASK, //$NON-NLS-1$
                new Class<?>[0], new Object[0]);
            if (count instanceof Number number)
            {
                assistantMessages = number.intValue();
            }
        }

        private static String string(Object value)
        {
            return value == null ? null : value.toString();
        }
    }
}
