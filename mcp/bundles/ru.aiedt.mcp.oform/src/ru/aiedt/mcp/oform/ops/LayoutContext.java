/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import java.util.Map;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.OrdinaryFormFile;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * What a layout handler works with: the form as read, an editor over its tree, the arguments,
 * and the answer under construction, which the handler may add fields to.
 */
public final class LayoutContext
{
    public final IProject project;

    /** The form's FQN, {@code Type.Object.Form.Name} or {@code CommonForm.Name}. */
    public final String fqn;

    /** The owner's kind in EDT's English spelling, or {@code null} for a common form. */
    public final String ownerKind;

    /** The container as read: the module text is here. */
    public final OrdinaryFormFile file;

    /** The layout tree, edited in place by the editor. */
    public final ListNode tree;

    public final LayoutEditor editor;

    public final Map<String, String> params;

    /** The answer the operation builds; the handler adds what it knows. */
    public final ToolResult answer;

    public LayoutContext(IProject project, String fqn, String ownerKind, OrdinaryFormFile file, ListNode tree,
        LayoutEditor editor, Map<String, String> params, ToolResult answer)
    {
        this.project = project;
        this.fqn = fqn;
        this.ownerKind = ownerKind;
        this.file = file;
        this.tree = tree;
        this.editor = editor;
        this.params = params;
        this.answer = answer;
    }

    public String string(String name)
    {
        return JsonUtils.extractStringArgument(params, name);
    }

    public Integer integer(String name)
    {
        return JsonUtils.extractIntegerArgument(params, name);
    }

    public boolean bool(String name, boolean fallback)
    {
        return JsonUtils.extractBooleanArgument(params, name, fallback);
    }

    /** @return whether the argument is present and not blank */
    public boolean has(String name)
    {
        String value = string(name);
        return value != null && !value.trim().isEmpty();
    }
}
