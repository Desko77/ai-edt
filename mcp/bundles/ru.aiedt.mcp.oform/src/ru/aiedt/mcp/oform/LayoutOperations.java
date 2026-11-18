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
 * delivery mark - is decided once. The three steps are callable one by one: {@link #target}
 * reads the form, {@link #applyTo} runs the handler over a target and returns the texts, and
 * {@link #writeBack} writes them and marks the owner; {@link #run} calls them in that order.
 * </p>
 */
public final class LayoutOperations
{
    /** The form with what the operation needs of it. */
    public static final class Target
    {
        final IProject project;

        final OrdinaryFormModule module;

        final String fqn;

        final String ownerKind;

        final OrdinaryFormFile file;

        final ListNode tree;

        private Target(IProject project, OrdinaryFormModule module, String fqn, String ownerKind, OrdinaryFormFile file,
            ListNode tree)
        {
            this.project = project;
            this.module = module;
            this.fqn = fqn;
            this.ownerKind = ownerKind;
            this.file = file;
            this.tree = tree;
        }

        /**
         * A target over a container already read. The layout is parsed afresh, and an operation
         * applied to the target edits that tree in place, so a target serves one application.
         *
         * @param module the form's module, which names the container a write goes to
         * @param fqn the form's FQN
         * @param file the container the handler reads and a write compares against
         * @return the target
         * @throws IOException when the container holds no layout entry
         */
        public static Target of(OrdinaryFormModule module, String fqn, OrdinaryFormFile file) throws IOException
        {
            ListNode tree = file.formTree();
            if (tree == null)
            {
                throw new IOException("The container of " + fqn + " holds no layout entry."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String[] parts = fqn.split("\\."); //$NON-NLS-1$
            return new Target(module.getProject(), module, fqn, parts.length == 4 ? parts[0] : null, file, tree);
        }

        /**
         * The form's FQN.
         *
         * @return the FQN
         */
        public String fqn()
        {
            return fqn;
        }
    }

    /**
     * What a handler did to a target: refused on the layout version, refused by the handler, or
     * done with the new layout text, the new module text when the handler wrote one, and the answer
     * the handler filled in.
     */
    public static final class Applied
    {
        /** Whether the handler made its edit. */
        public final boolean done;

        /** The handler's outcome when done, the refusal otherwise. */
        public final String message;

        /** The layout version a refusal on the version names, {@code "none"} for none; {@code null} otherwise. */
        public final String refusedVersion;

        /** The element the outcome names, or {@code null}. */
        public final String elementId;

        /** The layout text the target held before the handler, or {@code null}. */
        public final String layoutBefore;

        /** The layout text after the handler; {@code null} when refused. */
        public final String layoutText;

        /** The full module text the handler returned, or {@code null} when it left the module. */
        public final String moduleText;

        /** The answer with the fields the handler added; {@code null} when refused. */
        final ToolResult answer;

        private Applied(boolean done, String message, String refusedVersion, String elementId, String layoutBefore,
            String layoutText, String moduleText, ToolResult answer)
        {
            this.done = done;
            this.message = message;
            this.refusedVersion = refusedVersion;
            this.elementId = elementId;
            this.layoutBefore = layoutBefore;
            this.layoutText = layoutText;
            this.moduleText = moduleText;
            this.answer = answer;
        }
    }

    /** What a write did: whether it wrote, and the delivery mark when it did. */
    public static final class Written
    {
        /** Whether the container was written. */
        public final boolean written;

        /** The delivery note of the owner's mark, or {@code null} when nothing was written. */
        public final String delivery;

        private Written(boolean written, String delivery)
        {
            this.written = written;
            this.delivery = delivery;
        }
    }

    private LayoutOperations()
    {
        // static operations
    }

    /**
     * Runs a layout operation: {@link #target}, {@link #applyTo} and, unless {@code dryRun},
     * {@link #writeBack}.
     *
     * @param spec the operation, of kind {@link OperationSpec.Kind#LAYOUT}
     * @param project the project
     * @param params the tool arguments
     * @return the answer as JSON
     */
    public static String run(OperationSpec spec, IProject project, Map<String, String> params)
    {
        String formFqn = JsonUtils.extractStringArgument(params, "formFqn"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        Target target;
        try
        {
            target = target(project, formFqn, modulePath);
        }
        catch (IOException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }
        Applied applied = applyTo(spec, target, params);
        if (applied.refusedVersion != null)
        {
            return ToolResult.error(applied.message).put("form", target.fqn) //$NON-NLS-1$
                .put("layoutVersion", applied.refusedVersion).toJson(); //$NON-NLS-1$
        }
        if (!applied.done)
        {
            return ToolResult.error(applied.message).put("form", target.fqn).toJson(); //$NON-NLS-1$
        }
        ToolResult answer = applied.answer;
        answer.put("outcome", applied.message) //$NON-NLS-1$
            .put("dryRun", dryRun) //$NON-NLS-1$
            .put("layoutCharsBefore", applied.layoutBefore == null ? 0 : applied.layoutBefore.length()) //$NON-NLS-1$
            .put("layoutCharsAfter", applied.layoutText.length()); //$NON-NLS-1$
        if (applied.elementId != null)
        {
            answer.put("elementId", applied.elementId); //$NON-NLS-1$
        }
        if (dryRun)
        {
            answer.put("written", false) //$NON-NLS-1$
                .put("note", "dryRun: nothing was written; the outcome is what the write would do."); //$NON-NLS-1$ //$NON-NLS-2$
            return answer.toJson();
        }
        try
        {
            Written written = writeBack(target, applied.layoutText, applied.moduleText);
            answer.put("written", written.written); //$NON-NLS-1$
            if (written.written)
            {
                answer.put("delivery", written.delivery); //$NON-NLS-1$
            }
        }
        catch (IOException e)
        {
            return ToolResult.error("The container could not be written: " + e.getMessage()).put("form", target.fqn).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return answer.toJson();
    }

    /**
     * Runs an operation's handler over a target and returns what it made of the texts. Nothing is
     * written; the handler edits the target's tree in place.
     *
     * @param spec the operation, of kind {@link OperationSpec.Kind#LAYOUT}
     * @param target the form, from {@link #target} or {@link Target#of}
     * @param params the tool arguments; {@code language} picks the language of new texts
     * @return refused on the layout version, refused by the handler, or done with the texts
     */
    public static Applied applyTo(OperationSpec spec, Target target, Map<String, String> params)
    {
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        String before = target.file.formText();
        LayoutEditor editor;
        try
        {
            editor = LayoutEditor.over(target.tree, target.ownerKind, language);
        }
        catch (UnsupportedLayoutVersionException e)
        {
            return new Applied(false, e.getMessage(), e.version() == null ? "none" : e.version(), null, before, null, //$NON-NLS-1$
                null, null);
        }
        ToolResult answer = ToolResult.success()
            .put("operation", spec.name) //$NON-NLS-1$
            .put("projectName", target.project.getName()) //$NON-NLS-1$
            .put("form", target.fqn) //$NON-NLS-1$
            .put("container", target.module.containerPath()); //$NON-NLS-1$
        LayoutContext context = new LayoutContext(target.project, target.fqn, target.ownerKind, target.file, target.tree,
            editor, params, answer);
        OperationResult result = spec.layout.apply(context);
        if (!result.outcome.done)
        {
            return new Applied(false, result.outcome.message, null, null, before, null, null, null);
        }
        return new Applied(true, result.outcome.message, null, result.outcome.elementId, before, editor.text(),
            result.moduleText, answer);
    }

    /**
     * Writes the texts into the target's container and marks the owner for the next infobase
     * update when the write landed. Each replaced entry is compared with what the target read.
     *
     * @param target the form the texts were made for
     * @param layoutText the new layout text, or {@code null} to leave the layout entry
     * @param moduleText the new module text, or {@code null} to leave the module entry
     * @return whether the container was written, and the delivery mark
     * @throws IOException when the container cannot be written or a replaced entry changed since it was read
     */
    public static Written writeBack(Target target, String layoutText, String moduleText) throws IOException
    {
        boolean written = target.module.writeEntries(target.file, layoutText, moduleText);
        return new Written(written, written ? target.module.afterWrite(true) : null);
    }

    /**
     * Reads the form an operation names: by {@code formFqn}, or by {@code modulePath} when no FQN
     * is given.
     *
     * @param project the project
     * @param formFqn the form's FQN, or {@code null}
     * @param modulePath the module address of the form, or {@code null}
     * @return the target
     * @throws IOException when the form is not found, is not an ordinary one or holds no layout
     */
    public static Target target(IProject project, String formFqn, String modulePath) throws IOException
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
        return Target.of(module, fqn, module.read());
    }
}
