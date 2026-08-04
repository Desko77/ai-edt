/**

 * AI-EDT - 1C AI tools for EDT

 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)

 * Licensed under AGPL-3.0-or-later

 */



package ru.aiedt.mcp.server.folders.ui;



import java.lang.reflect.Method;

import java.net.URL;

import java.util.ArrayList;

import java.util.List;

import java.util.Objects;



import org.eclipse.core.resources.IProject;

import org.eclipse.core.runtime.IAdaptable;

import org.eclipse.core.runtime.IProgressMonitor;

import org.eclipse.core.runtime.Platform;

import org.eclipse.emf.ecore.EObject;

import org.eclipse.emf.ecore.EReference;

import org.eclipse.jface.resource.ImageDescriptor;

import org.eclipse.ui.model.WorkbenchAdapter;

import org.osgi.framework.Bundle;



import com._1c.g5.v8.bm.core.IBmObject;

import com._1c.g5.v8.bm.core.IBmTransaction;

import com._1c.g5.v8.bm.integration.AbstractBmTask;

import com._1c.g5.v8.bm.integration.IBmModel;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;



import ru.aiedt.mcp.server.Activator;

import ru.aiedt.mcp.server.folders.IClusterManager;

import ru.aiedt.mcp.server.folders.model.Cluster;



/**

 * The tree node that stands for a cluster folder in the Navigator.

 * <p>

 * Its children are, in order, the nodes for any clusters nested under it, then the real metadata

 * objects it holds. Those objects are stored by name, so each is looked up afresh against the BM

 * model inside a read transaction; a name that no longer resolves - the object was deleted - is

 * quietly left out.

 * </p>

 */

public class ClusterNavigatorBridge

    extends WorkbenchAdapter

    implements IAdaptable

{

    private static final String CLUSTER_ICON_PATH = "icons/cluster.png"; //$NON-NLS-1$



    private static final String RESOLVE_TASK_NAME = "Resolve clustered object"; //$NON-NLS-1$



    private static final String GET_NAME_METHOD = "getName"; //$NON-NLS-1$



    private static final String FQN_SEPARATOR = "\\."; //$NON-NLS-1$



    private static ImageDescriptor clusterImageDescriptor;



    private final Cluster cluster;



    private final IProject project;



    private final Object parent;



    /**

     * Creates a node for a cluster.

     *

     * @param cluster the cluster; not <code>null</code>

     * @param project the project it belongs to; not <code>null</code>

     * @param parent the node's parent in the tree; may be <code>null</code>

     */

    public ClusterNavigatorBridge(Cluster cluster, IProject project, Object parent)

    {

        this.cluster = Objects.requireNonNull(cluster, "cluster"); //$NON-NLS-1$

        this.project = Objects.requireNonNull(project, "project"); //$NON-NLS-1$

        this.parent = parent;

    }



    /**

     * Returns the cluster this node stands for.

     *

     * @return the cluster

     */

    public Cluster getCluster()

    {

        return cluster;

    }



    /**

     * Returns the project the cluster belongs to.

     *

     * @return the project

     */

    public IProject getProject()

    {

        return project;

    }



    @Override

    public String getLabel(Object object)

    {

        return cluster.getName();

    }



    @Override

    public ImageDescriptor getImageDescriptor(Object object)

    {

        return clusterImage();

    }



    @Override

    public Object[] getChildren(Object object)

    {

        List<Object> children = new ArrayList<>();



        IClusterManager service = Activator.getClusterServiceStatic();

        if (service != null)

        {

            for (Cluster nested : service.getClustersAtPath(project, cluster.getFullPath()))

            {

                children.add(new ClusterNavigatorBridge(nested, project, this));

            }

        }



        for (String fqn : cluster.getChildren())

        {

            EObject resolved = resolveFqnToEObject(fqn);

            if (resolved != null)

            {

                children.add(resolved);

            }

        }



        return children.isEmpty() ? NO_CHILDREN : children.toArray();

    }



    @Override

    public Object getParent(Object object)

    {

        return parent;

    }



    @Override

    public <T> T getAdapter(Class<T> adapter)

    {

        if (adapter == Cluster.class)

        {

            return adapter.cast(cluster);

        }

        if (adapter == IProject.class)

        {

            return adapter.cast(project);

        }

        return Platform.getAdapterManager().getAdapter(this, adapter);

    }



    @Override

    public int hashCode()

    {

        return Objects.hash(cluster.getFullPath(), project);

    }



    @Override

    public boolean equals(Object obj)

    {

        if (this == obj)

        {

            return true;

        }

        if (obj == null || getClass() != obj.getClass())

        {

            return false;

        }

        ClusterNavigatorBridge other = (ClusterNavigatorBridge)obj;

        return Objects.equals(cluster.getFullPath(), other.cluster.getFullPath())

            && Objects.equals(project, other.project);

    }



    /**

     * Lazily loads the shared cluster folder image descriptor from the bundle.

     * <p>

     * A descriptor holds no operating-system resource, so the one instance is shared by every node and

     * there is nothing to dispose.

     * </p>

     *

     * @return the descriptor, or <code>null</code> if the icon cannot be found

     */

    private static synchronized ImageDescriptor clusterImage()

    {

        if (clusterImageDescriptor == null)

        {

            Activator activator = Activator.getDefault();

            if (activator != null)

            {

                Bundle bundle = activator.getBundle();

                if (bundle != null)

                {

                    URL url = bundle.getEntry(CLUSTER_ICON_PATH);

                    if (url != null)

                    {

                        clusterImageDescriptor = ImageDescriptor.createFromURL(url);

                    }

                }

            }

        }

        return clusterImageDescriptor;

    }



    /**

     * Resolves a fully qualified name to the object it names, in a read transaction.

     *

     * @param fqn the fully qualified name

     * @return the object, or <code>null</code> if it cannot be resolved

     */

    private EObject resolveFqnToEObject(String fqn)

    {

        Activator activator = Activator.getDefault();

        if (activator == null)

        {

            return null;

        }

        IBmModelManager modelManager = activator.getBmModelManager();

        if (modelManager == null)

        {

            return null;

        }

        IBmModel model = modelManager.getModel(project);

        if (model == null)

        {

            return null;

        }

        return model.executeReadonlyTask(new AbstractBmTask<EObject>(RESOLVE_TASK_NAME)

        {

            @Override

            public EObject execute(IBmTransaction transaction, IProgressMonitor monitor)

            {

                return resolveInTransaction(transaction, fqn);

            }

        });

    }



    /**

     * Resolves a fully qualified name within a transaction, descending into nested objects as needed.

     *

     * @param transaction the read transaction

     * @param fqn the fully qualified name

     * @return the object, or <code>null</code>

     */

    private static EObject resolveInTransaction(IBmTransaction transaction, String fqn)

    {

        if (fqn == null)

        {

            return null;

        }

        String[] parts = fqn.split(FQN_SEPARATOR);

        if (parts.length < 2)

        {

            return null;

        }

        String topFqn = parts[0] + "." + parts[1]; //$NON-NLS-1$

        IBmObject top = transaction.getTopObjectByFqn(topFqn);

        if (top == null)

        {

            return null;

        }

        if (parts.length == 2)

        {

            return top;

        }

        return resolveNestedObject(top, parts, 2);

    }



    /**

     * Walks a fully qualified name past its top object, taking sub-type and sub-name in pairs.

     *

     * @param top the resolved top object

     * @param parts the split fully qualified name

     * @param startIndex the index of the first sub-type

     * @return the deepest object reached, or <code>null</code> if any step fails to resolve

     */

    private static EObject resolveNestedObject(EObject top, String[] parts, int startIndex)

    {

        EObject current = top;

        for (int i = startIndex; i + 1 < parts.length; i += 2)

        {

            EObject child = findContainedChild(current, parts[i], parts[i + 1]);

            if (child == null)

            {

                return null;

            }

            current = child;

        }

        return current;

    }



    /**

     * Finds a contained child of an object by its type token and name.

     *

     * @param parent the containing object

     * @param subTypeName the token the child's class name must equal or end with

     * @param subName the child's name

     * @return the matching child, or <code>null</code>

     */

    private static EObject findContainedChild(EObject parent, String subTypeName, String subName)

    {

        for (EReference reference : parent.eClass().getEAllContainments())

        {

            Object value = parent.eGet(reference);

            if (reference.isMany())

            {

                if (value instanceof Iterable<?>)

                {

                    for (Object element : (Iterable<?>)value)

                    {

                        EObject match = matchChild(element, subTypeName, subName);

                        if (match != null)

                        {

                            return match;

                        }

                    }

                }

            }

            else

            {

                EObject match = matchChild(value, subTypeName, subName);

                if (match != null)

                {

                    return match;

                }

            }

        }

        return null;

    }



    /**

     * Tests one candidate against a type token and a name.

     *

     * @param candidate the candidate; may be anything

     * @param subTypeName the token the class name must equal or end with

     * @param subName the required name

     * @return the candidate as an {@link EObject} when it matches, else <code>null</code>

     */

    private static EObject matchChild(Object candidate, String subTypeName, String subName)

    {

        if (!(candidate instanceof EObject))

        {

            return null;

        }

        EObject eObject = (EObject)candidate;

        String className = eObject.eClass().getName();

        if (!className.equals(subTypeName) && !className.endsWith(subTypeName))

        {

            return null;

        }

        return subName.equals(reflectName(eObject)) ? eObject : null;

    }



    /**

     * Reads an object's name by reflectively invoking its {@code getName()} method.

     *

     * @param eObject the object

     * @return the name, or <code>null</code> if there is none to read

     */

    private static String reflectName(EObject eObject)

    {

        try

        {

            Method method = eObject.getClass().getMethod(GET_NAME_METHOD);

            Object result = method.invoke(eObject);

            return result instanceof String ? (String)result : null;

        }

        catch (ReflectiveOperationException | RuntimeException e)

        {

            return null;

        }

    }

}

