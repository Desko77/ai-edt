/*
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmInfobaseLifecycleHelper;

/**
 * Removes an infobase from EDT's list, and (when deleteContent) its .1CD on
 * disk. When projectName is given, dissociates it from that project first.
 * DESTRUCTIVE when deleteContent is true.
 */
public class InfobaseRemover implements IMcpTool
{
    public static final String NAME = "delete_infobase"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `infobase_admin` `operation=delete_infobase`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Remove an infobase from EDT's infobase list. Pass name; deleteContent=" //$NON-NLS-1$
            + "true ALSO deletes the .1CD directory on disk (DESTRUCTIVE, irreversible). " //$NON-NLS-1$
            + "Pass projectName to first dissociate it from that project's launch " //$NON-NLS-1$
            + "configuration. Reference removal and dissociation are in-process."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("name", "Infobase name to delete (required).", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("deleteContent", //$NON-NLS-1$
                "Also delete the .1CD directory on disk (DESTRUCTIVE, default false = " //$NON-NLS-1$
                    + "only remove the reference from EDT's list).") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Optional project to dissociate the infobase from first.") //$NON-NLS-1$
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
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        boolean deleteContent = JsonUtils.extractBooleanArgument(params, "deleteContent", false); //$NON-NLS-1$

        if (name == null || name.isEmpty())
        {
            return ToolResult.error("name is required").toJson(); //$NON-NLS-1$
        }

        BmInfobaseLifecycleHelper.DeleteResult r =
            BmInfobaseLifecycleHelper.deleteInfobase(name, deleteContent, projectName);

        if (!r.ok)
        {
            ToolResult err = ToolResult.error(r.error)
                .put("operation", NAME) //$NON-NLS-1$
                .put("name", name); //$NON-NLS-1$
            if (r.failureKind != null)
            {
                err.put(r.failureKind, Boolean.TRUE);
            }
            return err.toJson();
        }

        ToolResult ok = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("infobaseName", name) //$NON-NLS-1$
            .put("dissociated", r.dissociated) //$NON-NLS-1$
            .put("contentDeleted", r.contentDeleted); //$NON-NLS-1$
        if (r.dissociateWarning != null)
        {
            ok.put("dissociateWarning", r.dissociateWarning); //$NON-NLS-1$
        }
        return ok.toJson();
    }
}
