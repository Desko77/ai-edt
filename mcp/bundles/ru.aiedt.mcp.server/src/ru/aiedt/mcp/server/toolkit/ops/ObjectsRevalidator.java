/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.e1c.g5.v8.dt.check.ICheckScheduler;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BuildTaskHelper;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ProjectStateGuard;

public class ObjectsRevalidator
    implements IMcpTool
{
    public static final String NAME = "revalidate_objects"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `diagnostics` `operation=revalidate_objects`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Revalidates an EDT project as a whole, or just the objects named. Leave the objects array "
            + "empty or omit it to revalidate the entire project. FQN examples: 'Document.SalesOrder', "
            + "'Catalog.Products', 'CommonModule.Common'. Russian type names work too (e.g. "
            + "'Документ.ПриходнаяНакладная', "
            + "'Справочник.Номенклатура').";
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("objects", //$NON-NLS-1$
                "The FQNs to revalidate (e.g. ['Document.SalesOrder', 'XDTOPackage.MyPackage']). " //$NON-NLS-1$
                    + "Russian type names work too (e.g. 'Документ.ПродажаТоваров'). " //$NON-NLS-1$
                    + "Empty or omitted means revalidate the whole project. Alias: objectFqns.")
            .stringArrayProperty("objectFqns", //$NON-NLS-1$
                "Alias for 'objects' (the camelCase form other tools use). FQNs to revalidate; " //$NON-NLS-1$
                    + "empty or omitted means revalidate the whole project.")
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectsJson = JsonUtils.extractStringArgument(params, "objects"); //$NON-NLS-1$
        if (objectsJson == null || objectsJson.isEmpty())
        {
            objectsJson = JsonUtils.extractStringArgument(params, "objectFqns"); //$NON-NLS-1$
        }

        if (projectName != null && !projectName.isEmpty())
        {
            String notReadyError = ProjectStateGuard.checkReadyOrError(projectName);
            if (notReadyError != null)
            {
                return ToolResult.error(notReadyError).toJson();
            }
        }

        List<String> objects = parseObjectsList(objectsJson);
        return revalidateObjects(projectName, objects);
    }

    private List<String> parseObjectsList(String objectsJson)
    {
        List<String> result = new ArrayList<>();
        if (objectsJson == null || objectsJson.isEmpty())
        {
            return result;
        }
        try
        {
            JsonElement element = JsonParser.parseString(objectsJson);
            if (element.isJsonArray())
            {
                for (JsonElement item : element.getAsJsonArray())
                {
                    if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString())
                    {
                        result.add(item.getAsString());
                    }
                }
            }
        }
        catch (JsonParseException e)
        {
            Activator.logError("Could not parse the objects JSON: " + objectsJson, e); //$NON-NLS-1$
        }
        return result;
    }

    public static String revalidateObjects(String projectName, List<String> objectFqns)
    {
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName must be provided").toJson(); //$NON-NLS-1$
        }

        boolean fullProjectRevalidation = (objectFqns == null || objectFqns.isEmpty());

        try
        {
            IProgressMonitor monitor = new NullProgressMonitor();
            IProject project = ProjectResolver.resolve(projectName);
            if (project == null)
            {
                return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
            }

            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

            if (fullProjectRevalidation)
            {
                Activator.logInfo("Revalidating the whole project: " + project.getName()); //$NON-NLS-1$
                project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
                BuildTaskHelper.waitForBuildAndDerivedData(project, monitor);
                return ToolResult.success()
                    .put("project", projectName) //$NON-NLS-1$
                    .put("mode", "full") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("message", "Full project revalidation finished") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }
            else
            {
                return revalidateSpecificObjects(project, objectFqns, monitor);
            }
        }
        catch (Exception e)
        {
            Activator.logError("Project revalidation raised an exception", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    private static String revalidateSpecificObjects(IProject project, List<String> objectFqns,
        IProgressMonitor monitor) throws CoreException
    {
        String projectName = project.getName();

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("IBmModelManager service cannot be reached").toJson(); //$NON-NLS-1$
        }

        ICheckScheduler checkScheduler = Activator.getDefault().getCheckScheduler();
        if (checkScheduler == null)
        {
            return ToolResult.error("ICheckScheduler service cannot be reached").toJson(); //$NON-NLS-1$
        }

        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        IDtProject dtProject = dtProjectManager != null ? dtProjectManager.getDtProject(project) : null;
        if (dtProject == null)
        {
            return ToolResult.error("Not an EDT project (the EDT nature is missing): " + projectName).toJson(); //$NON-NLS-1$
        }

        IBmModel bmModel = bmModelManager.getModel(dtProject);
        if (bmModel == null)
        {
            return ToolResult.error("No BM model available for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        List<String> found = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        List<String> skippedNullUri = new ArrayList<>();
        Collection<Object> objectsToValidate = new ArrayList<>();

        List<String> originalFqns = new ArrayList<>(objectFqns);
        List<String> normalizedFqns = new ArrayList<>();
        for (String fqn : objectFqns)
        {
            normalizedFqns.add(MetadataTypeCatalog.normalizeFqn(fqn));
        }

        List<String> xdtoPackages = new ArrayList<>();
        for (String nf : normalizedFqns)
        {
            if (nf != null && nf.startsWith("XDTOPackage.")) //$NON-NLS-1$
            {
                xdtoPackages.add(nf);
            }
        }

        bmModel.executeReadonlyTask(new AbstractBmTask<Void>("RevalidateObjectsLookup") //$NON-NLS-1$
        {
            @Override
            public Void execute(IBmTransaction tx, IProgressMonitor pm)
            {
                for (int i = 0; i < normalizedFqns.size(); i++)
                {
                    String normalizedFqn = normalizedFqns.get(i);
                    String originalFqn = originalFqns.get(i);
                    IBmObject obj = tx.getTopObjectByFqn(normalizedFqn);
                    if (obj != null)
                    {
                        long bmId = obj.bmGetId();
                        if (bmId > 0)
                        {
                            Activator.logInfo("Resolved object: " + originalFqn + " -> bmId: " + bmId); //$NON-NLS-1$ //$NON-NLS-2$
                            objectsToValidate.add(Long.valueOf(bmId));
                            found.add(originalFqn);
                        }
                        else
                        {
                            Activator.logInfo("Object carries an invalid bmId: " + originalFqn + " -> " + bmId); //$NON-NLS-1$ //$NON-NLS-2$
                            skippedNullUri.add(originalFqn);
                        }
                    }
                    else
                    {
                        Activator.logInfo("No such object: " + originalFqn + " (after normalising: " + normalizedFqn //$NON-NLS-1$ //$NON-NLS-2$
                            + ")");
                        notFound.add(originalFqn);
                    }
                }
                return null;
            }
        });

        if (!objectsToValidate.isEmpty())
        {
            Collection<Object> validObjects = new ArrayList<>();
            for (Object obj : objectsToValidate)
            {
                if (obj != null)
                {
                    validObjects.add(obj);
                }
            }
            if (!validObjects.isEmpty())
            {
                checkScheduler.scheduleValidation(project, Collections.emptySet(), validObjects, monitor);
            }
        }

        BuildTaskHelper.waitForBuildAndDerivedData(project, monitor);

        ToolResult result = ToolResult.success()
            .put("project", projectName) //$NON-NLS-1$
            .put("mode", "objects") //$NON-NLS-1$ //$NON-NLS-2$
            .put("objectsRequested", objectFqns.size()) //$NON-NLS-1$
            .put("objectsFound", found.size()) //$NON-NLS-1$
            .put("objectsValidated", found) //$NON-NLS-1$
            .put("message", "Revalidation finished"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!notFound.isEmpty())
        {
            result.put("objectsNotFound", notFound); //$NON-NLS-1$
        }
        if (!skippedNullUri.isEmpty())
        {
            result.put("objectsSkippedNullUri", skippedNullUri); //$NON-NLS-1$
        }
        if (!xdtoPackages.isEmpty())
        {
            result.put("xdtoIndexHint", //$NON-NLS-1$
                "Revalidated XDTOPackage(s) " + xdtoPackages + ", but the XDTO type-import index is a " //$NON-NLS-1$ //$NON-NLS-2$
                    + "separate derived-data build that revalidate_objects does not rebuild. If " //$NON-NLS-1$
                    + "dependent objects still fail to resolve the package's types, run clean_project " //$NON-NLS-1$
                    + "to rebuild the full index."); //$NON-NLS-1$
        }
        return result.toJson();
    }
}
