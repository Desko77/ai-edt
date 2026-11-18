/**
 * AI-EDT Ordinary Form Editor
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.editor;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * The preference page of the ordinary form editor, under AI-EDT: whether ordinary forms open in
 * this editor by default.
 */
public final class OrdinaryFormEditorPage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage
{
    /**
     * Lays the fields out in a grid.
     */
    public OrdinaryFormEditorPage()
    {
        super(GRID);
    }

    /**
     * Binds the page to the editor's preference node and sets the default.
     *
     * @param workbench the workbench
     */
    @Override
    public void init(IWorkbench workbench)
    {
        ScopedPreferenceStore store =
            new ScopedPreferenceStore(InstanceScope.INSTANCE, OrdinaryFormEditorAssociation.PREFERENCE_NODE);
        store.setDefault(OrdinaryFormEditorAssociation.OPEN_IN_AIEDT_EDITOR, true);
        setPreferenceStore(store);
        setDescription("Ordinary forms (Form.oform) in the AI-EDT editor."); //$NON-NLS-1$
    }

    /**
     * Adds the switch that decides which editor a double click opens.
     */
    @Override
    protected void createFieldEditors()
    {
        addField(new BooleanFieldEditor(OrdinaryFormEditorAssociation.OPEN_IN_AIEDT_EDITOR,
            "Open ordinary forms in the AI-EDT editor", getFieldEditorParent())); //$NON-NLS-1$
    }
}
