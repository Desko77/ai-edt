/*
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmInfobaseCredentialsHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Stores an infobase application's connection credentials (user + password, or
 * OS authentication) in EDT's encrypted secure storage so that later operations
 * ({@code update_database}, base-linked external-object build, launch) connect
 * without an interactive login prompt.
 * <p>
 * The password is written only to the encrypted store - it is never logged and
 * never returned (the response reports {@code passwordStored} as a boolean).
 */
public class InfobaseCredentialsWriter implements IMcpTool
{
    public static final String NAME = "set_infobase_credentials"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `infobase_admin` `operation=set_infobase_credentials`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Store an infobase application's connection credentials in EDT's " //$NON-NLS-1$
            + "encrypted secure storage so update_database / launch / external-object " //$NON-NLS-1$
            + "build connect without an interactive login dialog. Pass projectName and " //$NON-NLS-1$
            + "(for password auth) userName + password; applicationId is optional when " //$NON-NLS-1$
            + "the project has one application (see get_applications). accessMode is OS " //$NON-NLS-1$
            + "or INFOBASE (default INFOBASE when a userName is given, else OS). The " //$NON-NLS-1$
            + "password is stored encrypted and is never logged or returned - the " //$NON-NLS-1$
            + "response reports passwordStored as a boolean. Verified by an in-process " //$NON-NLS-1$
            + "read-back of the stored settings."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application (infobase) id. Optional when the project has a single " //$NON-NLS-1$
                    + "application; otherwise required (get_applications lists ids).") //$NON-NLS-1$
            .stringProperty("accessMode", //$NON-NLS-1$
                "Authentication mode: 'INFOBASE' (user + password) or 'OS' (operating " //$NON-NLS-1$
                    + "system / pass-through, no user/password). Optional - defaults to " //$NON-NLS-1$
                    + "INFOBASE when a userName is supplied, else OS.") //$NON-NLS-1$
            .stringProperty("userName", //$NON-NLS-1$
                "Infobase user name (for INFOBASE access).") //$NON-NLS-1$
            .stringProperty("password", //$NON-NLS-1$
                "Infobase password (for INFOBASE access). Stored encrypted; never " //$NON-NLS-1$
                    + "logged or returned.") //$NON-NLS-1$
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
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        String accessMode = JsonUtils.extractStringArgument(params, "accessMode"); //$NON-NLS-1$
        String userName = JsonUtils.extractStringArgument(params, "userName"); //$NON-NLS-1$
        String password = JsonUtils.extractStringArgument(params, "password"); //$NON-NLS-1$

        if (accessMode != null && !accessMode.isEmpty()
            && !"OS".equalsIgnoreCase(accessMode) && !"INFOBASE".equalsIgnoreCase(accessMode)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("accessMode must be 'OS' or 'INFOBASE' (got '" //$NON-NLS-1$
                + accessMode + "').").toJson(); //$NON-NLS-1$
        }
        // Default the mode from whether a userName was supplied.
        if (accessMode == null || accessMode.isEmpty())
        {
            accessMode = (userName != null && !userName.isEmpty()) ? "INFOBASE" : "OS"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        BmInfobaseCredentialsHelper.CredentialResult r =
            BmInfobaseCredentialsHelper.setCredentials(project, applicationId, accessMode,
                userName, password);

        if (!r.ok)
        {
            ToolResult err = ToolResult.error(r.error)
                .put("operation", NAME) //$NON-NLS-1$
                .put("projectName", projectName); //$NON-NLS-1$
            if (r.failureKind != null)
            {
                err.put(r.failureKind, Boolean.TRUE);
            }
            return err.toJson();
        }

        ToolResult ok = ToolResult.success()
            .put("operation", NAME) //$NON-NLS-1$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("applicationId", r.applicationId) //$NON-NLS-1$
            .put("infobaseName", r.infobaseName) //$NON-NLS-1$
            .put("access", r.access) //$NON-NLS-1$
            .put("userName", r.userName != null ? r.userName : "") //$NON-NLS-1$ //$NON-NLS-2$
            .put("passwordStored", r.passwordStored) //$NON-NLS-1$
            .put("verifiedByReadback", Boolean.TRUE); //$NON-NLS-1$
        // additionalParameters is intentionally NOT echoed: 1C connection
        // additional parameters can carry a plaintext password (e.g. /P<pwd>).
        if (ErrorTags.READBACK_FAILED.wire().equals(r.failureKind))
        {
            ok.put(ErrorTags.READBACK_FAILED.wire(), Boolean.TRUE)
                .put("note", "Credentials were stored, but the confirmation read-back " //$NON-NLS-1$ //$NON-NLS-2$
                    + "failed; verify manually if a later connect still prompts."); //$NON-NLS-1$
        }
        return ok.toJson();
    }
}
