/*
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import ru.aiedt.mcp.server.support.BmInfobaseRegistrationHelper;
import ru.aiedt.mcp.server.support.BmInfobaseRegistrationHelper.RegisterResult;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Registers an EXISTING infobase (file or server) in EDT's infobase list and associates it to a
 * project as its application, in one call. The infobase itself is never created or deleted: only
 * the list entry and the project binding are written. A duplicate address is reused, not added
 * twice, and the answer names the projects the reused entry was already bound to, plus the
 * projects whose applications could not be read, where the binding check did not run; a failed
 * binding rolls the added entry back. Access arguments ({@code accessMode} / {@code userName} /
 * {@code password}) are stored in EDT's encrypted store after the list entry and before the
 * binding, through the same code {@code set_infobase_credentials} uses; a failed write refuses
 * the call before the binding. A failed binding puts a reused entry's access settings back to
 * what stood before the write, and the answer names that, the removal of the settings with an
 * added entry, or a restore that failed. The password is never part of the answer.
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
            + "default (by default only when the project has none). accessMode / userName / " //$NON-NLS-1$
            + "password store the infobase credentials in EDT's encrypted store as part of the " //$NON-NLS-1$
            + "call - written after the list entry and before the binding, so a base with users " //$NON-NLS-1$
            + "registers without an interactive login prompt; the same contract as " //$NON-NLS-1$
            + "set_infobase_credentials. Use create_infobase to CREATE a " //$NON-NLS-1$
            + "new file infobase instead."; //$NON-NLS-1$
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
            .stringProperty("accessMode", //$NON-NLS-1$
                "Authentication mode to store for the infobase: 'INFOBASE' (user + password) or " //$NON-NLS-1$
                    + "'OS' (operating system / pass-through, no user/password). Optional - " //$NON-NLS-1$
                    + "defaults to INFOBASE when a userName is supplied, else OS. Stored after " //$NON-NLS-1$
                    + "the list entry and before the binding.") //$NON-NLS-1$
            .stringProperty("userName", //$NON-NLS-1$
                "Infobase user name to store (for INFOBASE access).") //$NON-NLS-1$
            .stringProperty("password", //$NON-NLS-1$
                "Infobase password to store (for INFOBASE access). Stored encrypted; never " //$NON-NLS-1$
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
        String path = JsonUtils.extractStringArgument(params, "path"); //$NON-NLS-1$
        String connectionString = JsonUtils.extractStringArgument(params, "connectionString"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        Boolean makeDefault = JsonUtils.extractBooleanArgumentNullable(params, "makeDefault"); //$NON-NLS-1$
        String accessMode = JsonUtils.extractStringArgument(params, "accessMode"); //$NON-NLS-1$
        String userName = JsonUtils.extractStringArgument(params, "userName"); //$NON-NLS-1$
        String password = JsonUtils.extractStringArgument(params, "password"); //$NON-NLS-1$

        // The same checks and the same defaulting set_infobase_credentials keeps.
        if (accessMode != null && !accessMode.isEmpty()
            && !"OS".equalsIgnoreCase(accessMode) && !"INFOBASE".equalsIgnoreCase(accessMode)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("accessMode must be 'OS' or 'INFOBASE' (got '" //$NON-NLS-1$
                + accessMode + "').").toJson(); //$NON-NLS-1$
        }
        boolean wantsAccess = accessMode != null && !accessMode.isEmpty()
            || userName != null && !userName.isEmpty()
            || password != null && !password.isEmpty();
        if (wantsAccess && (accessMode == null || accessMode.isEmpty()))
        {
            accessMode = userName != null && !userName.isEmpty() ? "INFOBASE" : "OS"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!wantsAccess)
        {
            accessMode = null;
        }

        RegisterResult r = BmInfobaseRegistrationHelper.registerInfobase(projectName, path,
            connectionString, name, makeDefault, accessMode, userName, password);

        return response(r);
    }

    /**
     * Builds the answer: what was added or reused, the application it bound, the projects a reused
     * entry was already bound to, the default and the run-mode flag outcomes, what the
     * launch-configuration guard repaired, and - when access arguments were carried - what access
     * settings were stored (never the password). A failed binding names what became of those
     * settings in {@code accessSettings}, and that text never carries the password.
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
            if (r.accessSettings != null)
            {
                err.put("accessSettings", r.accessSettings); //$NON-NLS-1$
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
        if (r.alsoAssociatedWith != null)
        {
            ok.put("alsoAssociatedWith", r.alsoAssociatedWith); //$NON-NLS-1$
        }
        if (r.associationCheckFailed != null)
        {
            ok.put("associationCheckFailed", r.associationCheckFailed); //$NON-NLS-1$
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
        if (r.credentials != null)
        {
            // The fields set_infobase_credentials answers with; the password itself is never
            // among them.
            ok.put("access", r.credentials.access); //$NON-NLS-1$
            ok.put("userName", r.credentials.userName != null ? r.credentials.userName : ""); //$NON-NLS-1$ //$NON-NLS-2$
            ok.put("passwordStored", r.credentials.passwordStored); //$NON-NLS-1$
            boolean readback = !ErrorTags.READBACK_FAILED.wire().equals(r.credentials.failureKind);
            ok.put("verifiedByReadback", readback); //$NON-NLS-1$
            if (!readback)
            {
                ok.put(ErrorTags.READBACK_FAILED.wire(), Boolean.TRUE)
                    .put("note", "Credentials were stored, but the confirmation read-back " //$NON-NLS-1$ //$NON-NLS-2$
                        + "failed; verify manually if a later connect still prompts."); //$NON-NLS-1$
            }
        }
        return ok.toJson();
    }
}
