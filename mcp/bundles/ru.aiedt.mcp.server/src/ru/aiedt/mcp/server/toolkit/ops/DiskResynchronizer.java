/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmExportHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Forces the in-memory Business Model of the given top-objects out to their
 * on-disk {@code .mdo} (and child resources), then refreshes the workspace.
 *
 * <p>Use when the BM is valid in EDT but the disk is stale - e.g. after raw
 * {@code .mdo} edits, or before {@code update_database} / configuration export -
 * to avoid the "valid in EDT, fails in IB" / {@code zip:///... not found} class
 * where the platform reads a stale or missing on-disk file. Backed by
 * {@link BmExportHelper#forceExportAndWait} (the same sync used internally after
 * metadata mutations), exposed as an explicit, user-invokable operation.
 *
 * <p>Strictly a BM-&gt;disk export + workspace refresh. It does NOT strip
 * dangling references from {@code Configuration.mdo} (that destructive cleanup is
 * intentionally out of scope). Pass the specific object FQNs to resync.
 */
public class DiskResynchronizer implements IMcpTool
{
    public static final String NAME = "resync_to_disk"; //$NON-NLS-1$

    private static final long DEFAULT_WAIT_MS = 10_000L;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `project_admin` `operation=resync_to_disk`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Force the in-memory BM of the given top-objects out to their on-disk .mdo " //$NON-NLS-1$
            + "(and child resources) and refresh the workspace. Use when EDT shows the object " //$NON-NLS-1$
            + "as valid but the disk is stale/missing - before update_database or configuration " //$NON-NLS-1$
            + "export - to avoid 'valid in EDT, fails in IB' / zip:/// not-found errors. " //$NON-NLS-1$
            + "BM->disk export only; does NOT strip Configuration.mdo references."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("objects", //$NON-NLS-1$
                "Top-object FQNs to force-export to disk (e.g. ['Catalog.Products', " //$NON-NLS-1$
                    + "'Document.SalesOrder']). Required - pass the objects whose disk is stale.", //$NON-NLS-1$
                true)
            .booleanProperty("refresh", //$NON-NLS-1$
                "Refresh the workspace after export so Eclipse sees the written files " //$NON-NLS-1$
                    + "(default true).") //$NON-NLS-1$
            .integerProperty("waitTimeoutMs", //$NON-NLS-1$
                "Max wait for the export to settle, ms (default 10000).") //$NON-NLS-1$
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
        List<String> objects = JsonUtils.extractArrayArgument(params, "objects"); //$NON-NLS-1$
        boolean refresh = JsonUtils.extractBooleanArgument(params, "refresh", true); //$NON-NLS-1$
        long waitMs = DEFAULT_WAIT_MS;
        Integer wt = JsonUtils.extractIntegerArgument(params, "waitTimeoutMs"); //$NON-NLS-1$
        if (wt != null && wt > 0)
        {
            waitMs = wt.longValue();
        }

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (objects == null || objects.isEmpty())
        {
            return ToolResult.error("objects is required - pass the FQNs to force-export, " //$NON-NLS-1$
                + "e.g. [\"Catalog.Products\"]").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        IBmModelManager manager = Activator.getDefault().getBmModelManager();
        if (manager == null)
        {
            return ToolResult.error("BM model manager is not available").toJson(); //$NON-NLS-1$
        }

        BmExportHelper.Result r = BmExportHelper.forceExportAndWait(manager, project, objects, waitMs);

        String refreshNote = null;
        if (refresh && r.isOk() && !r.syncFlushPending)
        {
            try
            {
                project.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
            catch (Exception e)
            {
                refreshNote = "export ok but workspace refresh failed: " + e.getMessage(); //$NON-NLS-1$
                Activator.logWarning("resync_to_disk: " + refreshNote); //$NON-NLS-1$
            }
        }

        ToolResult tool = r.isOk() ? ToolResult.success()
            : ToolResult.error(r.error != null ? r.error
                : "force-export failed (forceExport returned false)"); //$NON-NLS-1$
        tool.put("operation", "resync_to_disk") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("objects", objects) //$NON-NLS-1$
            .put("forceExportOk", r.forceExportOk) //$NON-NLS-1$
            .put("waitComputationOk", r.waitComputationOk) //$NON-NLS-1$
            .put("syncFlushPending", r.syncFlushPending) //$NON-NLS-1$
            .put("totalMs", r.totalMs) //$NON-NLS-1$
            .put("refreshed", refresh && r.isOk() && !r.syncFlushPending && refreshNote == null); //$NON-NLS-1$
        if (r.syncFlushPending)
        {
            // Row 42: the disk flush did not confirm within waitTimeoutMs. The
            // save is queued and may still complete in the background; the disk
            // can still be stale. Do not read forceExportOk as "disk written".
            tool.put("diskFlushHint", "Disk flush did not confirm within " //$NON-NLS-1$ //$NON-NLS-2$
                + "waitTimeoutMs - the save is queued and may still be completing. The .mdo " //$NON-NLS-1$
                + "may still be stale: re-run once EDT settles or raise waitTimeoutMs; if it " //$NON-NLS-1$
                + "stays stale, restart EDT and resync."); //$NON-NLS-1$
        }
        if (refreshNote != null)
        {
            tool.put("refreshNote", refreshNote); //$NON-NLS-1$
        }
        return tool.toJson();
    }
}
