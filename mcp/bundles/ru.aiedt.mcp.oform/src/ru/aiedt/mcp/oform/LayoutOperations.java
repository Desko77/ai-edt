/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.UnsupportedLayoutVersionException;
import ru.aiedt.mcp.oform.ops.LayoutContext;
import ru.aiedt.mcp.oform.ops.OperationResult;
import ru.aiedt.mcp.oform.ops.OperationSpec;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Runs a layout operation of {@code ordinary_forms}: reads the container, opens an editor over
 * its layout, hands both to the operation's handler, and writes back what changed - the layout
 * and, when the handler returns one, the module - unless {@code dryRun}, marking the form's
 * owner in the sync baselines so the next infobase update carries the form.
 * <p>
 * Every handler goes through here, so what is common to every write - locating the form,
 * refusing a layout version the writers were not measured on, the dry run, the write and the
 * delivery mark - is decided once.
 * </p>
 */
final class LayoutOperations
{
    /** The form with what the operation needs of it. */
    private static final class Target
    {
        final OrdinaryFormModule module;

        final String fqn;

        final String ownerKind;

        final OrdinaryFormFile file;

        final ListNode tree;

        Target(OrdinaryFormModule module, String fqn, String ownerKind, OrdinaryFormFile file, ListNode tree)
        {
            this.module = module;
            this.fqn = fqn;
            this.ownerKind = ownerKind;
            this.file = file;
            this.tree = tree;
        }
    }

    private LayoutOperations()
    {
        // static operations
    }

    /**
     * Runs a layout operation.
     *
     * @param spec the operation, of kind {@link OperationSpec.Kind#LAYOUT}
     * @param project the project
     * @param params the tool arguments
     * @return the answer as JSON
     */
    static String run(OperationSpec spec, IProject project, Map<String, String> params)
    {
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        Target target;
        try
        {
            target = target(project, formFqn, modulePath);
        }
        catch (IOException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }
        String before = target.file.formText();
        LayoutEditor editor;
        try
        {
            editor = LayoutEditor.over(target.tree, target.ownerKind, language);
        }
        catch (UnsupportedLayoutVersionException e)
        {
            return ToolResult.error(e.getMessage()).put("form", target.fqn) //$NON-NLS-1$
                .put("layoutVersion", e.version() == null ? "none" : e.version()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        ToolResult answer = ToolResult.success()
            .put("operation", spec.name) //$NON-NLS-1$
            .put("projectName", project.getName()) //$NON-NLS-1$
            .put("form", target.fqn) //$NON-NLS-1$
            .put("container", target.module.containerPath()); //$NON-NLS-1$
        LayoutContext context = new LayoutContext(project, target.fqn, target.ownerKind, target.file, target.tree, editor,
            params, answer);
        OperationResult result = spec.layout.apply(context);
        if (!result.outcome.done)
        {
            return ToolResult.error(result.outcome.message).put("form", target.fqn).toJson(); //$NON-NLS-1$
        }
        String after = editor.text();
        answer.put("outcome", result.outcome.message) //$NON-NLS-1$
            .put("dryRun", dryRun) //$NON-NLS-1$
            .put("layoutCharsBefore", before == null ? 0 : before.length()) //$NON-NLS-1$
            .put("layoutCharsAfter", after.length()); //$NON-NLS-1$
        if (result.outcome.elementId != null)
        {
            answer.put("elementId", result.outcome.elementId); //$NON-NLS-1$
        }
        if (dryRun)
        {
            answer.put("written", false) //$NON-NLS-1$
                .put("note", "dryRun: nothing was written; the outcome is what the write would do."); //$NON-NLS-1$ //$NON-NLS-2$
            return answer.toJson();
        }
        try
        {
            boolean written = target.module.writeEntries(target.file, after, result.moduleText);
            answer.put("written", written); //$NON-NLS-1$
            if (written)
            {
                answer.put("delivery", target.module.afterWrite(true)); //$NON-NLS-1$
            }
        }
        catch (IOException e)
        {
            return ToolResult.error("The container could not be written: " + e.getMessage()).put("form", target.fqn).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return answer.toJson();
    }

    private static Target target(IProject project, String formFqn, String modulePath) throws IOException
    {
        OrdinaryFormModule module = null;
        String fqn = formFqn == null ? null : formFqn.trim();
        if ((fqn == null || fqn.isEmpty()) && modulePath != null && !modulePath.trim().isEmpty())
        {
            module = OrdinaryFormModule.locate(project, modulePath.trim());
            if (module == null)
            {
                throw new IOException("No ordinary form's container lies behind " + modulePath.trim() + "."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            fqn = module.fqn();
        }
        if (fqn == null || fqn.isEmpty())
        {
            throw new IOException("The operation needs formFqn (Type.Object.Form.Name or CommonForm.Name) or modulePath."); //$NON-NLS-1$
        }
        if (module == null)
        {
            Path file = OrdinaryFormLocator.fileOf(project, fqn);
            if (file == null || project.getLocation() == null)
            {
                throw new IOException("No Form.oform for " + fqn + " in " + project.getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + ": the form is managed, does not exist, or the FQN is not a form's."); //$NON-NLS-1$
            }
            String relative = project.getLocation().toFile().toPath().relativize(file).toString().replace('\\', '/');
            module = OrdinaryFormModule.locate(project, OrdinaryFormModule.moduleAddressOf(relative));
            if (module == null)
            {
                throw new IOException("The container of " + fqn + " is not reachable through the workspace."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        OrdinaryFormFile file = module.read();
        ListNode tree = file.formTree();
        if (tree == null)
        {
            throw new IOException("The container of " + fqn + " holds no layout entry."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        return new Target(module, fqn, parts.length == 4 ? parts[0] : null, file, tree);
    }
}
