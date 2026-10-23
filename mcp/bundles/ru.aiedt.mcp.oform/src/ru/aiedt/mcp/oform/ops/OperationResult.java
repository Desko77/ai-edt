/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import ru.aiedt.mcp.oform.layout.LayoutEditor;

/**
 * What a layout edit did: the editor's outcome (the tree is edited in place) and, when the edit
 * also changes the module - a handler's stub - the module's new text.
 */
public final class OperationResult
{
    public final LayoutEditor.Outcome outcome;

    /** The module's whole new text, or {@code null} when the module stays. */
    public final String moduleText;

    private OperationResult(LayoutEditor.Outcome outcome, String moduleText)
    {
        this.outcome = outcome;
        this.moduleText = moduleText;
    }

    public static OperationResult of(LayoutEditor.Outcome outcome)
    {
        return new OperationResult(outcome, null);
    }

    public static OperationResult withModule(LayoutEditor.Outcome outcome, String moduleText)
    {
        return new OperationResult(outcome, moduleText);
    }

    public static OperationResult refused(String message)
    {
        return new OperationResult(LayoutEditor.refusal(message), null);
    }
}
