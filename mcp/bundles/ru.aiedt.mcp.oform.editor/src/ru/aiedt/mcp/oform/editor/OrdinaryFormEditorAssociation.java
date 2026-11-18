/**
 * AI-EDT Ordinary Form Editor
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.editor;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IEditorAssociationOverride;

/**
 * Puts the ordinary form editor first for {@code Form.oform} while the preference
 * {@link #OPEN_IN_AIEDT_EDITOR} is on, so a double click opens it instead of EDT's
 * "unsupported object" placeholder. With the preference off nothing is overridden: EDT's
 * placeholder stays the default and this editor is offered under Open With.
 */
public final class OrdinaryFormEditorAssociation implements IEditorAssociationOverride
{
    /** The preference node the editor's settings live in. */
    public static final String PREFERENCE_NODE = "ru.aiedt.mcp.oform.editor"; //$NON-NLS-1$

    /** Whether ordinary forms open in this editor by default. */
    public static final String OPEN_IN_AIEDT_EDITOR = "openOrdinaryFormsInAiEdtEditor"; //$NON-NLS-1$

    /** The file name of an ordinary form container. */
    static final String FILE_NAME = "Form.oform"; //$NON-NLS-1$

    /**
     * Whether ordinary forms open in this editor by default.
     *
     * @return the preference, {@code true} when never set
     */
    public static boolean opensByDefault()
    {
        return Platform.getPreferencesService().getBoolean(PREFERENCE_NODE, OPEN_IN_AIEDT_EDITOR, true, null);
    }

    /** Overrides the editors offered for a input, putting this editor first for an ordinary form. */
    @Override
    public IEditorDescriptor[] overrideEditors(IEditorInput editorInput, IContentType contentType,
        IEditorDescriptor[] editorDescriptors)
    {
        return applies(editorInput) ? ourFirst(editorDescriptors) : editorDescriptors;
    }

    /** Overrides the editors offered for a file name, putting this editor first for an ordinary form. */
    @Override
    public IEditorDescriptor[] overrideEditors(String fileName, IContentType contentType,
        IEditorDescriptor[] editorDescriptors)
    {
        return applies(fileName) ? ourFirst(editorDescriptors) : editorDescriptors;
    }

    /** Overrides the default editor of a input, putting this editor first for an ordinary form. */
    @Override
    public IEditorDescriptor overrideDefaultEditor(IEditorInput editorInput, IContentType contentType,
        IEditorDescriptor editorDescriptor)
    {
        return applies(editorInput) ? ourOr(editorDescriptor) : editorDescriptor;
    }

    /** Overrides the default editor of a file name, putting this editor first for an ordinary form. */
    @Override
    public IEditorDescriptor overrideDefaultEditor(String fileName, IContentType contentType,
        IEditorDescriptor editorDescriptor)
    {
        return applies(fileName) ? ourOr(editorDescriptor) : editorDescriptor;
    }

    /** Whether the override takes this input: a workspace {@code Form.oform} with the preference on. */
    private static boolean applies(IEditorInput input)
    {
        return input instanceof IFileEditorInput fileInput && applies(fileInput.getFile().getName());
    }

    /** Whether the override takes this file name: {@code Form.oform} with the preference on. */
    private static boolean applies(String fileName)
    {
        return FILE_NAME.equals(fileName) && opensByDefault();
    }

    /** This editor's descriptor, or {@code null} without a running workbench. */
    private static IEditorDescriptor ours()
    {
        if (!PlatformUI.isWorkbenchRunning())
        {
            return null;
        }
        return PlatformUI.getWorkbench().getEditorRegistry().findEditor(OrdinaryFormEditor.ID);
    }

    /** This editor's descriptor, or the fallback when it is not registered. */
    private static IEditorDescriptor ourOr(IEditorDescriptor fallback)
    {
        IEditorDescriptor ours = ours();
        return ours != null ? ours : fallback;
    }

    /** The descriptors with this editor first and no second copy of it. */
    private static IEditorDescriptor[] ourFirst(IEditorDescriptor[] descriptors)
    {
        IEditorDescriptor ours = ours();
        if (ours == null)
        {
            return descriptors;
        }
        List<IEditorDescriptor> ordered = new ArrayList<>();
        ordered.add(ours);
        if (descriptors != null)
        {
            for (IEditorDescriptor descriptor : descriptors)
            {
                if (descriptor != null && !OrdinaryFormEditor.ID.equals(descriptor.getId()))
                {
                    ordered.add(descriptor);
                }
            }
        }
        return ordered.toArray(new IEditorDescriptor[0]);
    }
}
