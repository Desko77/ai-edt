/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.ui;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.ui.model.IWorkbenchAdapter;

import ru.aiedt.mcp.server.labels.MarkerHelpers;

/**
 * Reaches into EDT's own collection-folder adapter, which is a package-private class this bundle must
 * not compile against, and pulls out the few facts the clusters need: the collection's type token, its
 * owning project, and - for a nested collection - the object it hangs under.
 * <p>
 * Everything here is reflection over a class named only as a string. A collection adapter is any
 * object whose class chain includes {@code CollectionNavigatorAdapterBase}. From one, its type token
 * ({@code CommonModule}, {@code Catalog}) is read from a {@code getModelObjectName()} method; when that
 * cannot be reached, the workbench label is used with its spaces stripped. The lookups are cached per
 * class, so the reflection cost is paid once per adapter type.
 * </p>
 */
public final class CollectionAdapters
{
    private static final String COLLECTION_ADAPTER_CLASS_NAME =
        "com._1c.g5.v8.dt.navigator.adapters.CollectionNavigatorAdapterBase"; //$NON-NLS-1$

    private static final String MODEL_OBJECT_NAME_METHOD = "getModelObjectName"; //$NON-NLS-1$

    private static final String GET_PARENT_METHOD = "getParent"; //$NON-NLS-1$

    private static final String GET_PROJECT_METHOD = "getProject"; //$NON-NLS-1$

    /** Stands in the caches for "this class has no such method", so a miss is not looked up twice. */
    private static final MethodHandle NO_METHOD = createNoMethodMarker();

    private static final Map<Class<?>, MethodHandle> MODEL_NAME_HANDLES = new ConcurrentHashMap<>();

    private static final Map<Class<?>, MethodHandle> PARENT_HANDLES = new ConcurrentHashMap<>();

    private static final Map<Class<?>, MethodHandle> PROJECT_HANDLES = new ConcurrentHashMap<>();

    private CollectionAdapters()
    {
        // utility
    }

    /**
     * Tells whether an element is one of EDT's collection-folder adapters.
     *
     * @param element the element; may be <code>null</code>
     * @return <code>true</code> if its class chain includes the collection adapter base class
     */
    public static boolean isCollectionAdapter(Object element)
    {
        if (element == null)
        {
            return false;
        }
        for (Class<?> clazz = element.getClass(); clazz != null; clazz = clazz.getSuperclass())
        {
            if (COLLECTION_ADAPTER_CLASS_NAME.equals(clazz.getName()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the project a collection adapter belongs to.
     * <p>
     * Read from a {@code getProject()} method when the adapter has one; otherwise taken from the object
     * a nested collection hangs under.
     * </p>
     *
     * @param adapter the collection adapter
     * @return the project, or <code>null</code> if it cannot be determined
     */
    public static IProject getProjectFromAdapter(Object adapter)
    {
        if (adapter == null)
        {
            return null;
        }
        MethodHandle handle =
            handleFor(PROJECT_HANDLES, adapter.getClass(), GET_PROJECT_METHOD, MethodType.methodType(IProject.class));
        if (handle != null)
        {
            try
            {
                Object result = handle.invoke(adapter);
                if (result instanceof IProject)
                {
                    return (IProject)result;
                }
            }
            catch (Throwable t)
            {
                // Fall through to the parent-object route.
            }
        }
        EObject parent = getParentEObject(adapter);
        return parent == null ? null : MarkerHelpers.extractProject(parent);
    }

    /**
     * Returns the collection type token of an adapter - {@code CommonModule}, {@code Catalog}, and so on.
     *
     * @param adapter the collection adapter
     * @return the token, or <code>null</code> if it cannot be read
     */
    public static String getModelObjectName(Object adapter)
    {
        if (adapter == null)
        {
            return null;
        }
        MethodHandle handle = handleFor(MODEL_NAME_HANDLES, adapter.getClass(), MODEL_OBJECT_NAME_METHOD,
            MethodType.methodType(String.class));
        if (handle != null)
        {
            try
            {
                Object result = handle.invoke(adapter);
                if (result instanceof String)
                {
                    return (String)result;
                }
            }
            catch (Throwable t)
            {
                // Fall through to the label.
            }
        }
        if (adapter instanceof IWorkbenchAdapter)
        {
            String label = ((IWorkbenchAdapter)adapter).getLabel(adapter);
            if (label != null)
            {
                return label.replace(" ", ""); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return null;
    }

    /**
     * Returns the collection path of a top-level collection.
     * <p>
     * A collection that hangs under an object is nested - clusters are only offered on top-level
     * collections - so this answers <code>null</code> whenever the adapter has an object parent.
     * </p>
     *
     * @param adapter the collection adapter
     * @return the type token for a top-level collection, or <code>null</code> for a nested one
     */
    public static String getCollectionPath(Object adapter)
    {
        if (getParentEObject(adapter) != null)
        {
            return null;
        }
        return getModelObjectName(adapter);
    }

    /**
     * Returns the full collection path of an adapter.
     * <p>
     * The type token on its own for a top-level collection; the parent object's FQN, a dot and the
     * token for a nested one.
     * </p>
     *
     * @param adapter the collection adapter
     * @param parentFqnExtractor turns the parent object into an FQN
     * @return the full path, or <code>null</code> if the token cannot be read
     */
    public static String getFullCollectionPath(Object adapter, Function<EObject, String> parentFqnExtractor)
    {
        String modelObjectName = getModelObjectName(adapter);
        if (modelObjectName == null)
        {
            return null;
        }
        EObject parent = getParentEObject(adapter);
        if (parent == null)
        {
            return modelObjectName;
        }
        String parentFqn = parentFqnExtractor == null ? null : parentFqnExtractor.apply(parent);
        if (parentFqn == null || parentFqn.isEmpty())
        {
            return modelObjectName;
        }
        return parentFqn + "." + modelObjectName; //$NON-NLS-1$
    }

    /**
     * Clears the per-class method caches. For tests.
     */
    public static void clearCaches()
    {
        MODEL_NAME_HANDLES.clear();
        PARENT_HANDLES.clear();
        PROJECT_HANDLES.clear();
    }

    /**
     * Returns the object a nested collection hangs under, or <code>null</code> for a top-level one.
     *
     * @param adapter the collection adapter
     * @return the parent object, or <code>null</code>
     */
    private static EObject getParentEObject(Object adapter)
    {
        if (adapter == null)
        {
            return null;
        }
        MethodHandle handle = handleFor(PARENT_HANDLES, adapter.getClass(), GET_PARENT_METHOD,
            MethodType.methodType(Object.class, Object.class));
        if (handle == null)
        {
            return null;
        }
        try
        {
            Object result = handle.invoke(adapter, adapter);
            if (result instanceof EObject)
            {
                return (EObject)result;
            }
        }
        catch (Throwable t)
        {
            // No reachable parent.
        }
        return null;
    }

    /**
     * Looks up a virtual method on a class, caching both hits and misses.
     *
     * @param cache the per-class cache to use
     * @param clazz the class to look the method up on
     * @param methodName the method name
     * @param type the method type
     * @return the handle, or <code>null</code> if the class has no such reachable method
     */
    private static MethodHandle handleFor(Map<Class<?>, MethodHandle> cache, Class<?> clazz, String methodName,
        MethodType type)
    {
        MethodHandle cached = cache.get(clazz);
        if (cached != null)
        {
            return cached == NO_METHOD ? null : cached;
        }
        MethodHandle resolved;
        try
        {
            resolved = MethodHandles.lookup().findVirtual(clazz, methodName, type);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            resolved = NO_METHOD;
        }
        cache.put(clazz, resolved);
        return resolved == NO_METHOD ? null : resolved;
    }

    /**
     * Builds the sentinel handle the caches use to remember that a class lacks a method.
     *
     * @return a handle distinct from any real one
     */
    private static MethodHandle createNoMethodMarker()
    {
        try
        {
            return MethodHandles.lookup().findStatic(CollectionAdapters.class, "noMethodMarker", //$NON-NLS-1$
                MethodType.methodType(void.class));
        }
        catch (ReflectiveOperationException e)
        {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * The target of the sentinel handle. Never called.
     */
    private static void noMethodMarker()
    {
        // sentinel
    }
}
