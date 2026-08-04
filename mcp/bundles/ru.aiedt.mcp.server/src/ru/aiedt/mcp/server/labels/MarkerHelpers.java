/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels;

import java.lang.reflect.Method;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.wiring.ServiceAccess;

import ru.aiedt.mcp.server.Activator;

/**
 * The bridge between the EDT model and the plain strings the marker store speaks in.
 * <p>
 * A marker is stored against an object's fully qualified name, so the two jobs here are turning a model
 * object into that name and finding the project the name belongs to. Both have to cope with what the
 * Navigator actually hands over - sometimes a metadata {@link EObject}, sometimes an EDT folder or
 * cluster wrapper around one - so several of these methods fall back through a chain of strategies and
 * unwrap reflectively when they must.
 * </p>
 */
public final class MarkerHelpers
{
    /** The prefix that marks an EClass as an internal model wrapper, not an FQN segment. */
    private static final String MD_ECLASS_PREFIX = "Md"; //$NON-NLS-1$

    /** The EClass name of the configuration root, where an FQN stops climbing. */
    private static final String CONFIGURATION_ECLASS = "Configuration"; //$NON-NLS-1$

    /** The zero-argument getters tried, in order, when unwrapping a Navigator element. */
    private static final String[] UNWRAP_GETTERS =
        {"getTarget", "getData", "getElement", "getValue", "getObject", "getModel"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    private MarkerHelpers()
    {
        // Static utility.
    }

    /**
     * Builds the fully qualified name of a metadata object by climbing its containment chain.
     * <p>
     * Each step contributes an {@code EClassName.objectName} segment, from the top type down. The
     * climb stops before the configuration root and before any internal wrapper whose EClass name
     * begins with {@code Md}, so those never become segments.
     * </p>
     *
     * @param mdObject the metadata object
     * @return the FQN, for example {@code Catalog.Products.CatalogAttribute.Description}, or
     *         <code>null</code> when one cannot be built
     */
    public static String extractFqn(EObject mdObject)
    {
        if (mdObject == null)
        {
            return null;
        }
        StringBuilder fqn = new StringBuilder();
        EObject current = mdObject;
        while (current != null)
        {
            String className = current.eClass().getName();
            if (CONFIGURATION_ECLASS.equals(className) || className.startsWith(MD_ECLASS_PREFIX))
            {
                break;
            }
            String objectName = getObjectName(current);
            if (objectName == null || objectName.isEmpty())
            {
                return null;
            }
            String segment = className + "." + objectName; //$NON-NLS-1$
            if (fqn.length() == 0)
            {
                fqn.append(segment);
            }
            else
            {
                fqn.insert(0, segment + "."); //$NON-NLS-1$
            }
            current = getParentForFqn(current);
        }
        return fqn.length() == 0 ? null : fqn.toString();
    }

    /**
     * Returns the fully qualified name of a BM object.
     * <p>
     * The BM engine knows the name directly; only if it cannot answer does this fall back to walking
     * the containment chain.
     * </p>
     *
     * @param bmObject the BM object
     * @return the FQN, or <code>null</code> when one cannot be built
     */
    public static String extractFqn(IBmObject bmObject)
    {
        if (bmObject == null)
        {
            return null;
        }
        try
        {
            String fqn = bmObject.bmGetFqn();
            if (fqn != null && !fqn.isEmpty())
            {
                return fqn;
            }
        }
        catch (RuntimeException e)
        {
            // The object may be detached from its transaction; fall back to the model walk.
        }
        return extractFqn((EObject)bmObject);
    }

    /**
     * Returns the object an FQN climb should step to next.
     * <p>
     * For a nested subsystem that is its parent subsystem, which the containment reference does not
     * reach; for everything else it is the EMF container.
     * </p>
     *
     * @param eObject the current object
     * @return its FQN parent, or <code>null</code> at the top
     */
    public static EObject getParentForFqn(EObject eObject)
    {
        if (eObject instanceof Subsystem)
        {
            Subsystem parent = ((Subsystem)eObject).getParentSubsystem();
            if (parent != null)
            {
                return parent;
            }
        }
        return eObject.eContainer();
    }

    /**
     * Returns the name of a model object.
     *
     * @param eObject the object
     * @return its name, or <code>null</code> when it has none
     */
    public static String getObjectName(EObject eObject)
    {
        if (eObject instanceof MdObject)
        {
            return ((MdObject)eObject).getName();
        }
        Object name = invokeNoArg(eObject, "getName"); //$NON-NLS-1$
        return name != null ? name.toString() : null;
    }

    /**
     * Finds the project a metadata object belongs to, trying the resource lookup service, then the
     * object's resource URI, then the project manager.
     *
     * @param eObject the object
     * @return its project, or <code>null</code> when none can be found
     */
    public static IProject extractProject(EObject eObject)
    {
        if (eObject == null)
        {
            return null;
        }
        try
        {
            IResourceLookup lookup = ServiceAccess.get(IResourceLookup.class);
            if (lookup != null)
            {
                IProject project = lookup.getProject(eObject);
                if (project != null)
                {
                    return project;
                }
            }
        }
        catch (RuntimeException e)
        {
            // Service unavailable or object not resolvable through it; try the next strategy.
        }

        try
        {
            Resource resource = eObject.eResource();
            if (resource != null)
            {
                IProject project = projectFromUri(resource.getURI());
                if (project != null)
                {
                    return project;
                }
            }
        }
        catch (RuntimeException e)
        {
            // Malformed or detached resource; try the next strategy.
        }

        try
        {
            Activator activator = Activator.getDefault();
            if (activator != null)
            {
                IV8ProjectManager manager = activator.getV8ProjectManager();
                if (manager != null)
                {
                    IV8Project v8Project = manager.getProject(eObject);
                    if (v8Project != null)
                    {
                        return v8Project.getProject();
                    }
                }
            }
        }
        catch (RuntimeException e)
        {
            // Nothing more to try.
        }
        return null;
    }

    /**
     * Finds the project any Navigator element belongs to.
     * <p>
     * Handles a project directly, a metadata object, an EDT wrapper around one, and finally a wrapper
     * that answers {@code getProject} or {@code getModel} reflectively.
     * </p>
     *
     * @param element the selected element
     * @return its project, or <code>null</code> when none can be found
     */
    public static IProject extractProjectFromElement(Object element)
    {
        if (element == null)
        {
            return null;
        }
        if (element instanceof IProject)
        {
            return (IProject)element;
        }
        if (element instanceof EObject)
        {
            IProject project = extractProject((EObject)element);
            if (project != null)
            {
                return project;
            }
        }

        EObject unwrapped = unwrapToEObject(element);
        if (unwrapped != null)
        {
            IProject project = extractProject(unwrapped);
            if (project != null)
            {
                return project;
            }
        }

        Object viaProject = invokeNoArg(element, "getProject"); //$NON-NLS-1$
        if (viaProject instanceof IProject)
        {
            return (IProject)viaProject;
        }

        Object viaModel = invokeNoArg(element, "getModel"); //$NON-NLS-1$
        if (viaModel instanceof IProject)
        {
            return (IProject)viaModel;
        }
        if (viaModel instanceof EObject)
        {
            return extractProject((EObject)viaModel);
        }
        return null;
    }

    /**
     * Unwraps a Navigator element to the metadata object it stands for.
     * <p>
     * A plain {@link EObject} is returned as is. Otherwise a fixed list of accessor names is tried in
     * order, and a non-{@code EObject} result is unwrapped once more before giving up.
     * </p>
     *
     * @param element the element
     * @return the underlying object, or <code>null</code> when there is none
     */
    public static EObject unwrapToEObject(Object element)
    {
        return unwrapToEObject(element, 1);
    }

    /**
     * Adapts an element to a metadata object.
     *
     * @param element the element
     * @return the element itself when it is an {@link EObject}, the object it adapts to, or
     *         <code>null</code>
     */
    public static EObject extractMdObject(Object element)
    {
        if (element instanceof EObject)
        {
            return (EObject)element;
        }
        if (element == null)
        {
            return null;
        }
        return Platform.getAdapterManager().getAdapter(element, EObject.class);
    }

    /**
     * Builds the FQN an object takes after a rename, by swapping its final name segment.
     *
     * @param oldFqn the current FQN
     * @param newName the object's new short name
     * @return the new FQN, or just {@code newName} when the old FQN had no dot, or <code>null</code>
     *         when {@code oldFqn} is <code>null</code>
     */
    public static String buildNewFqn(String oldFqn, String newName)
    {
        if (oldFqn == null)
        {
            return null;
        }
        int lastDot = oldFqn.lastIndexOf('.');
        if (lastDot < 0)
        {
            return newName;
        }
        return oldFqn.substring(0, lastDot + 1) + newName;
    }

    /**
     * Recursive worker for {@link #unwrapToEObject(Object)} with a bounded depth.
     *
     * @param element the element
     * @param depth how many further levels of unwrapping are still allowed
     * @return the underlying object, or <code>null</code>
     */
    private static EObject unwrapToEObject(Object element, int depth)
    {
        if (element == null)
        {
            return null;
        }
        if (element instanceof EObject)
        {
            return (EObject)element;
        }
        for (String getter : UNWRAP_GETTERS)
        {
            Object result = invokeNoArg(element, getter);
            if (result instanceof EObject)
            {
                return (EObject)result;
            }
            if (result != null && depth > 0)
            {
                EObject inner = unwrapToEObject(result, depth - 1);
                if (inner != null)
                {
                    return inner;
                }
            }
        }
        return null;
    }

    /**
     * Reads a project name out of a resource URI and returns the matching open project.
     *
     * @param uri the resource URI
     * @return the project, or <code>null</code> when the URI carries no known project
     */
    private static IProject projectFromUri(URI uri)
    {
        if (uri == null)
        {
            return null;
        }
        String projectName = null;
        if (uri.isPlatformResource() && uri.segmentCount() > 1)
        {
            projectName = uri.segment(1);
        }
        else if (uri.toString().startsWith(MarkerKeys.BM_URI_SCHEME))
        {
            projectName = uri.authority();
        }
        else if (uri.authority() != null)
        {
            projectName = uri.authority();
        }
        if (projectName == null || projectName.isEmpty())
        {
            return null;
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        return project.exists() ? project : null;
    }

    /**
     * Invokes a named zero-argument method reflectively, swallowing every failure.
     *
     * @param target the object to call on
     * @param methodName the method name
     * @return the returned value, or <code>null</code> when the method is missing or throws
     */
    private static Object invokeNoArg(Object target, String methodName)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }
}
