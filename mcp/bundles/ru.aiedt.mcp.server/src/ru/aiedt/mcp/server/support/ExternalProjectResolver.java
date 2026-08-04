/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * Finds the root objects of an external-object project - the DT projects that hold external data
 * processors ({@code .epf}) and external reports ({@code .erf}).
 *
 * <p>
 * Such a project has no {@code Configuration} container: its roots sit directly in the project's
 * model. Asking {@code IConfigurationProvider.getConfiguration(project)} for one therefore answers
 * either nothing (a standalone external project) or - worse - the configuration of the project it
 * was created against, so a caller that trusts the answer ends up reading a foreign configuration's
 * objects. Everything here goes to the project's own model instead.
 * </p>
 *
 * <p>
 * Detection is by model type, not by Eclipse nature. There is exactly one nature for both kinds
 * ({@code V8ExternalObjectsNature}), so the nature can neither identify an external project by kind
 * nor be relied on to name it - the kind comes from the root object's own class.
 * </p>
 */
public final class ExternalProjectResolver
{
    public static final String KIND_EXTERNAL_DATA_PROCESSOR = "ExternalDataProcessor"; //$NON-NLS-1$
    public static final String KIND_EXTERNAL_REPORT = "ExternalReport"; //$NON-NLS-1$

    private ExternalProjectResolver()
    {
    }

    /**
     * Whether the project holds external objects.
     *
     * @param project the project to test, may be <code>null</code>
     * @return <code>true</code> for an external-object project, <code>false</code> for a
     *         configuration, an extension, or anything else
     */
    public static boolean isExternalProject(IProject project)
    {
        return externalProjectOf(project) != null;
    }

    /**
     * The root objects the project holds.
     * <p>
     * A project may hold more than one - callers that assume a single root will misreport the rest.
     * </p>
     *
     * @param project the project, may be <code>null</code>
     * @return the roots, or an empty list when the project holds none or is not external
     */
    public static List<MdObject> getExternalObjects(IProject project)
    {
        IExternalObjectProject external = externalProjectOf(project);
        if (external == null)
        {
            return Collections.emptyList();
        }
        try
        {
            return List.copyOf(external.getExternalObjects());
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not read the external objects of '" //$NON-NLS-1$
                + project.getName() + "': " + e.getMessage()); //$NON-NLS-1$
            return Collections.emptyList();
        }
    }

    /**
     * A root object by name.
     *
     * @param project the project, may be <code>null</code>
     * @param name the object name (the part of the FQN after the dot)
     * @return the root, or <code>null</code> when the project holds no root under that name
     */
    public static MdObject getExternalObject(IProject project, String name)
    {
        if (name == null || name.isEmpty())
        {
            return null;
        }
        for (MdObject root : getExternalObjects(project))
        {
            if (name.equals(root.getName()))
            {
                return root;
            }
        }
        return null;
    }

    /**
     * The kind of a root object, as it appears in an FQN.
     *
     * @param object a root object, may be <code>null</code>
     * @return {@link #KIND_EXTERNAL_DATA_PROCESSOR}, {@link #KIND_EXTERNAL_REPORT}, or
     *         <code>null</code> when the object is neither
     */
    public static String kindOf(MdObject object)
    {
        if (object == null)
        {
            return null;
        }
        String eClassName = object.eClass().getName();
        if (KIND_EXTERNAL_DATA_PROCESSOR.equals(eClassName) || KIND_EXTERNAL_REPORT.equals(eClassName))
        {
            return eClassName;
        }
        return null;
    }

    /**
     * The kind of the project's first root object.
     * <p>
     * Kept for callers that only need to know "processor or report". A project holding both kinds
     * answers with the first one, so callers that care about all of them should use
     * {@link #getExternalObjects(IProject)} and ask each root with {@link #kindOf(MdObject)}.
     * </p>
     *
     * @param project the project, may be <code>null</code>
     * @return the kind, or <code>null</code> when the project is not external or holds no root
     */
    public static String detectExternalKind(IProject project)
    {
        for (MdObject root : getExternalObjects(project))
        {
            String kind = kindOf(root);
            if (kind != null)
            {
                return kind;
            }
        }
        return null;
    }

    /**
     * The FQN of the project's first root object.
     * <p>
     * Built from the object, not from the project name: the two need not match, and an external
     * project may hold several roots.
     * </p>
     *
     * @param project the project, may be <code>null</code>
     * @return e.g. {@code ExternalDataProcessor.MyTool}, or <code>null</code> when there is no root
     */
    public static String getRootFqn(IProject project)
    {
        for (MdObject root : getExternalObjects(project))
        {
            String kind = kindOf(root);
            if (kind != null)
            {
                return kind + "." + root.getName(); //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Resolves any top-level object by FQN through the project's model, without going through a
     * {@code Configuration} container.
     *
     * @param project the owning project, must be open
     * @param fqn the fully qualified name, e.g. {@code ExternalDataProcessor.MyTool}
     * @return the object, or <code>null</code> when the project holds none under that FQN
     */
    public static MdObject resolveByFqn(IProject project, String fqn)
    {
        if (project == null || fqn == null || fqn.isEmpty())
        {
            return null;
        }
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return null;
        }
        IBmModel model = bmModelManager.getModel(project);
        if (model == null)
        {
            return null;
        }
        final MdObject[] result = new MdObject[1];
        model.executeReadonlyTask(new AbstractBmTask<Void>("resolve-external-by-fqn") //$NON-NLS-1$
        {
            @Override
            public Void execute(IBmTransaction tx, org.eclipse.core.runtime.IProgressMonitor monitor)
            {
                IBmObject top = tx.getTopObjectByFqn(fqn);
                if (top instanceof MdObject)
                {
                    result[0] = (MdObject)top;
                }
                return null;
            }
        });
        return result[0];
    }

    /**
     * The project as an external-object project, or <code>null</code> when it is not one.
     *
     * @param project the project, may be <code>null</code>
     * @return the model view of the project, or <code>null</code>
     */
    private static IExternalObjectProject externalProjectOf(IProject project)
    {
        if (project == null || !project.isAccessible())
        {
            return null;
        }
        try
        {
            Activator activator = Activator.getDefault();
            if (activator == null)
            {
                return null;
            }
            IV8ProjectManager projectManager = activator.getV8ProjectManager();
            if (projectManager == null)
            {
                return null;
            }
            IV8Project v8Project = projectManager.getProject(project);
            return v8Project instanceof IExternalObjectProject ? (IExternalObjectProject)v8Project : null;
        }
        catch (Exception e)
        {
            // The project may be closing or mid-import; treat it as "not external" rather than fail.
            return null;
        }
    }
}
