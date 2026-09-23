/*
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import ru.aiedt.mcp.server.support.BmInfobaseRegistrationHelper;
import ru.aiedt.mcp.server.support.BmInfobaseRegistrationHelper.RegisterResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Registers an EXISTING infobase (file or server) in EDT's infobase list and associates it to a
 * project as its application, in one call. The infobase itself is never created or deleted: only
 * the list entry and the project binding are written. A duplicate address is reused, not added
 * twice; a failed binding rolls the added entry back.
 */
public class InfobaseRegistrar implements IMcpTool
{
    public static final String NAME = "register_infobase"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `infobase_admin` `operation=register_infobase`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Register an EXISTING infobase in EDT's list and associate it to a project as its " //$NON-NLS-1$
            + "application, in one call - the infobase files are never created or deleted. Pass " //$NON-NLS-1$
            + "projectName and exactly one of path (a file infobase directory) or connectionString " //$NON-NLS-1$
            + "(a server infobase, Srvr=...;Ref=..., no Usr/Pwd - store those with " //$NON-NLS-1$
            + "set_infobase_credentials). name is required for a server infobase and defaults to " //$NON-NLS-1$
            + "the directory name for a file one. An entry already in the list under the same " //$NON-NLS-1$
            + "address is reused, not duplicated. makeDefault makes the application the project's " //$NON-NLS-1$
            + "default (by default only when the project has none). Use create_infobase to CREATE " //$NON-NLS-1$
            + "a new file infobase instead."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project to register the infobase to as its application (required).", true) //$NON-NLS-1$
            .stringProperty("path", //$NON-NLS-1$
                "Absolute path of an existing FILE infobase directory. Exactly one of path / " //$NON-NLS-1$
                    + "connectionString.") //$NON-NLS-1$
            .stringProperty("connectionString", //$NON-NLS-1$
                "An existing SERVER infobase address: Srvr=<server>;Ref=<infobase>. Exactly one " //$NON-NLS-1$
                    + "of path / connectionString. A user or password in it (Usr, Pwd) is " //$NON-NLS-1$
                    + "refused - store those with set_infobase_credentials.") //$NON-NLS-1$
            .stringProperty("name", //$NON-NLS-1$
                "Name in EDT's infobase list. Required for a server infobase; for a file one it " //$NON-NLS-1$
                    + "defaults to the directory name.") //$NON-NLS-1$
            .booleanProperty("makeDefault", //$NON-NLS-1$
                "Make the application the project's default (default true when the project has " //$NON-NLS-1$
                    + "no default application, else false). The answer names the default that " //$NON-NLS-1$
                    + "stood before.") //$NON-NLS-1$
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
        String path = JsonUtils.extractStringArgument(params, "path"); //$NON-NLS-1$
        String connectionString = JsonUtils.extractStringArgument(params, "connectionString"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        Boolean makeDefault = JsonUtils.extractBooleanArgumentNullable(params, "makeDefault"); //$NON-NLS-1$

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase(projectName, path,
            connectionString, name, makeDefault);

        return response(r);
    }

    /**
     * Builds the answer: what was added or reused, the application it bound, the default and the
     * run-mode flag outcomes, and what the launch-configuration guard repaired.
     *
     * @param r what the registration did
     * @return the answer JSON
     */
    static String response(RegisterResult r)
    {
        if (!r.ok)
        {
            ToolResult err = ToolResult.error(r.error)
                .put("operation", NAME) //$NON-NLS-1$
                .put("infobaseName", r.infobaseName); //$NON-NLS-1$
            if (r.failureKind != null)
            {
                err.put(r.failureKind, Boolean.TRUE);
            }
            if (r.rolledBack)
            {
                err.put("rolledBack", Boolean.TRUE); //$NON-NLS-1$
            }
            if (r.launchApplicationIds != null)
            {
                err.put("launchApplicationIds", r.launchApplicationIds); //$NON-NLS-1$
            }
            return err.toJson();
        }

        ToolResult ok = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("infobaseName", r.infobaseName) //$NON-NLS-1$
            .put(r.added ? "added" : "reused", Boolean.TRUE); //$NON-NLS-1$ //$NON-NLS-2$
        if (r.uuid != null)
        {
            ok.put("uuid", r.uuid); //$NON-NLS-1$
        }
        if (r.applicationId != null)
        {
            ok.put("applicationId", r.applicationId); //$NON-NLS-1$
        }
        ok.put("defaultApplication", r.defaultApplication); //$NON-NLS-1$
        if (r.previousDefault != null)
        {
            ok.put("previousDefaultApplication", r.previousDefault); //$NON-NLS-1$
        }
        if (r.defaultWarning != null)
        {
            ok.put("defaultWarning", r.defaultWarning); //$NON-NLS-1$
        }
        if (r.ordinaryApplicationFlag != null)
        {
            ok.put("ordinaryApplicationFlag", r.ordinaryApplicationFlag); //$NON-NLS-1$
        }
        if (r.launchApplicationIds != null)
        {
            ok.put("launchApplicationIds", r.launchApplicationIds); //$NON-NLS-1$
        }
        return ok.toJson();
    }
}
