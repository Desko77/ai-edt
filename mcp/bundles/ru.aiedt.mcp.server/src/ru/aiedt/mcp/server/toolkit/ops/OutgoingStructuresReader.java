/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmReferencesHelper;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Lists an object's OUTBOUND structural references - the metadata it points at (its type's
 * reference, owner, hierarchy parent, basedOn documents, dimension/resource types, ...).
 * The mirror of {@code find_references} for the outgoing direction: where find_references
 * answers "who points AT this object", this answers "what does this object point AT".
 * Computed from the BM model's forward references
 * ({@link BmReferencesHelper#forwardReferences}), so it sees the same edges
 * {@code dependency_graph direction=out} walks, returned as a flat per-object list with no
 * BFS / depth / graph framing.
 */
public class OutgoingStructuresReader
    implements IMcpTool
{
    @Override
    public String getName()
    {
        return "get_outgoing_structures"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `code_search` `operation=outgoing_structures`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "List an object's OUTBOUND structural references - the metadata it points at " //$NON-NLS-1$
            + "(type, owner, hierarchy parent, basedOn, dimension/resource types, ...). The " //$NON-NLS-1$
            + "mirror of find_references for the outgoing direction. Flat per-object list."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in") //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "Top-level metadata FQN, e.g. Catalog.Products, Document.Order, " //$NON-NLS-1$
                    + "InformationRegister.Stock") //$NON-NLS-1$
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
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (objectFqn == null || objectFqn.isEmpty())
        {
            return ToolResult.error("objectFqn is required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        final Configuration config = configProvider != null ? configProvider.getConfiguration(project) : null;
        if (config == null)
        {
            return ToolResult.error("No configuration in project '" + project.getName() + "'").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IBmModelManager bmManager = Activator.getDefault().getBmModelManager();
        IBmModel bmModel = bmManager != null ? bmManager.getModel(project) : null;
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available").toJson(); //$NON-NLS-1$
        }
        final String normFqn = MetadataTypeCatalog.normalizeFqn(objectFqn);
        AtomicReference<List<Map<String, Object>>> outRef = new AtomicReference<>();
        AtomicReference<Exception> errRef = new AtomicReference<>();
        bmModel.executeReadonlyTask(new AbstractBmTask<Void>("get_outgoing_structures") //$NON-NLS-1$
        {
            @Override
            public Void execute(IBmTransaction tx, IProgressMonitor monitor)
            {
                try
                {
                    String[] parts = normFqn.split("\\.", 2); //$NON-NLS-1$
                    if (parts.length < 2)
                    {
                        throw new RuntimeException("objectFqn must be 'Type.Name' (e.g. Catalog.Products)"); //$NON-NLS-1$
                    }
                    MdObject target = MetadataTypeCatalog.findObject(config, parts[0], parts[1]);
                    if (!(target instanceof IBmObject))
                    {
                        throw new RuntimeException("No such object: " + normFqn); //$NON-NLS-1$
                    }
                    List<BmReferencesHelper.Reference> refs =
                        BmReferencesHelper.forwardReferences(tx, (IBmObject)target);
                    // Dedupe by (targetFqn, feature): the containment walk can surface the
                    // same logical edge from several nested nodes; the consumer wants the SET
                    // of outgoing structures, not the multiset.
                    List<Map<String, Object>> rows = new ArrayList<>();
                    Set<String> seen = new LinkedHashSet<>();
                    for (BmReferencesHelper.Reference r : refs)
                    {
                        IBmObject t = (IBmObject)r.target;
                        String fqn = safeFqn(t);
                        String feature = r.feature != null ? r.feature.getName() : null;
                        String key = fqn + "#" + feature; //$NON-NLS-1$
                        if (!seen.add(key))
                        {
                            continue;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("feature", feature); //$NON-NLS-1$
                        row.put("targetFqn", fqn); //$NON-NLS-1$
                        row.put("targetType", t.eClass().getName()); //$NON-NLS-1$
                        rows.add(row);
                    }
                    outRef.set(rows);
                }
                catch (Exception ex)
                {
                    errRef.set(ex);
                }
                return null;
            }
        });
        Exception ex = errRef.get();
        if (ex != null)
        {
            return ToolResult.error(ex.getMessage()).toJson();
        }
        List<Map<String, Object>> rows = outRef.get();
        ToolResult res = ToolResult.success();
        res.put("objectFqn", normFqn); //$NON-NLS-1$
        res.put("count", rows == null ? 0 : rows.size()); //$NON-NLS-1$
        res.put("outgoing", rows == null ? new ArrayList<>() : rows); //$NON-NLS-1$
        return res.toJson();
    }

    private static String safeFqn(IBmObject obj)
    {
        try
        {
            return obj.bmGetFqn();
        }
        catch (Exception e)
        {
            return obj.eClass().getName();
        }
    }
}
