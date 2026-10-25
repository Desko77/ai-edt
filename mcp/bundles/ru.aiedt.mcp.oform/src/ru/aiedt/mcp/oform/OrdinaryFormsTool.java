/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.oform.ops.OperationSpec;
import ru.aiedt.mcp.oform.ops.OperationsCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ToolCallScope;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * {@code ordinary_forms} - the ordinary (non-managed) forms of a project, which EDT itself does
 * not open: their {@code Form.oform} containers read from and written into on disk.
 *
 * <p>The operations, their arguments, the schema and the help all come from
 * {@link OperationsCatalog}; this class only gates the writes, resolves the project and routes
 * the call by the operation's kind - a query answers on its own, a layout edit goes through
 * {@link LayoutOperations}, a creation through its creator.</p>
 */
public class OrdinaryFormsTool
    implements IMcpTool
{
    /** The write capability of this tool: every writing operation, dryRun included. */
    static final String WRITE_DOOR = "ordinary_forms_writes"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return "ordinary_forms"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Ordinary (non-managed) forms of a project, read from and written into their Form.oform " //$NON-NLS-1$
            + "containers - the forms EDT does not open. " + OperationsCatalog.knownNames().size() //$NON-NLS-1$
            + " operations, the schema naming every argument of each: " + namesOfKind() //$NON-NLS-1$
            + ". A write marks the form's owner for the next infobase update. A write-blocking " //$NON-NLS-1$
            + "preset refuses every writing operation of this tool, a dryRun included, while the " //$NON-NLS-1$
            + "queries stay available."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public List<String> getGatedWriteNames()
    {
        return List.of(WRITE_DOOR);
    }

    @Override
    public String getInputSchema()
    {
        return OperationsCatalog.schema();
    }

    /**
     * Refuses disabled or already cancelled writes, then dispatches a validated operation.
     * Does not guarantee cancellation after the operation has started.
     *
     * @param params the flattened operation arguments
     * @return the operation result or a structured refusal
     */
    @Override
    public String execute(Map<String, String> params)
    {
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.trim().isEmpty())
        {
            return ToolResult.error("operation is required: " + String.join(" | ", OperationsCatalog.knownNames())).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        OperationSpec spec = OperationsCatalog.find(operation);
        if (spec == null)
        {
            return ToolResult.error("Unknown operation: " + operation + ". Known: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", OperationsCatalog.knownNames()) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (spec.writes && McpToolCatalog.getInstance().isDisabledByPreset(WRITE_DOOR))
        {
            return ToolResult.error("The operation " + spec.name + " of ordinary_forms writes, and its " //$NON-NLS-1$ //$NON-NLS-2$
                + "write capability " + WRITE_DOOR + " is switched off by the tool settings; " //$NON-NLS-1$ //$NON-NLS-2$
                + "nothing was executed - not even as dryRun. Ask the user to allow this capability " //$NON-NLS-1$
                + "in EDT Preferences > AI-EDT > Tools tab, then try again.").toJson(); //$NON-NLS-1$
        }
        ToolCallScope scope = ToolCallScope.current();
        if (spec.writes && scope != null && scope.cancellation().isCancelled())
        {
            return ToolResult.error("The operation " + spec.name //$NON-NLS-1$
                + " was cancelled before execution; nothing was changed.").toJson(); //$NON-NLS-1$
        }
        IProject project = null;
        if (spec.needsProject)
        {
            String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
            if (projectName == null || projectName.isEmpty())
            {
                return ToolResult.error("projectName must be provided").toJson(); //$NON-NLS-1$
            }
            project = ProjectResolver.resolve(projectName);
            if (project == null)
            {
                return ProjectResolver.notFound(projectName).toJson();
            }
        }
        switch (spec.kind)
        {
        case QUERY:
            return spec.query.run(project, params);
        case CREATION:
            return spec.creation.run(project, params);
        default:
            return LayoutOperations.run(spec, project, params);
        }
    }

    /**
     * The operations grouped by what they do to the form, in the catalog's order - read, edit an
     * existing form's layout, create a form that does not exist yet - so the description names
     * every real operation without keeping a second list of them.
     *
     * @return the names, a group per kind
     */
    private static String namesOfKind()
    {
        StringBuilder text = new StringBuilder();
        for (OperationSpec.Kind kind : new OperationSpec.Kind[] { OperationSpec.Kind.QUERY,
            OperationSpec.Kind.LAYOUT, OperationSpec.Kind.CREATION })
        {
            List<String> names = new ArrayList<>();
            for (String name : OperationsCatalog.knownNames())
            {
                OperationSpec spec = OperationsCatalog.find(name);
                if (spec.kind == kind)
                {
                    names.add(spec.name);
                }
            }
            if (!names.isEmpty())
            {
                if (text.length() > 0)
                {
                    text.append("; "); //$NON-NLS-1$
                }
                text.append(kindHeadline(kind)).append(": ").append(String.join(", ", names)); //$NON-NLS-1$
            }
        }
        return text.toString();
    }

    /**
     * @param kind the group
     * @return the words the description heads the group with
     */
    private static String kindHeadline(OperationSpec.Kind kind)
    {
        switch (kind)
        {
        case QUERY:
            return "read, answers without writing"; //$NON-NLS-1$
        case LAYOUT:
            return "edit an existing form"; //$NON-NLS-1$
        default:
            return "create a form"; //$NON-NLS-1$
        }
    }
}
