/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.naparnik;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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
}
