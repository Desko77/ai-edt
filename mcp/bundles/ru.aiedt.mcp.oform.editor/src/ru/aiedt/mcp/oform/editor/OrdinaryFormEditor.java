/**
 * AI-EDT Ordinary Form Editor
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.editor;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.EditorPart;

import ru.aiedt.mcp.oform.session.FormSummary;

/**
 * The editor of an ordinary form ({@code Form.oform}). Shows what the container holds, counted
 * by the layout reader: the number of recognised elements and the layout version. Read only:
 * it is never dirty and saving does nothing.
 */
public final class OrdinaryFormEditor extends EditorPart
{
    /** The editor id registered in {@code plugin.xml}. */
    public static final String ID = "ru.aiedt.mcp.oform.editor.ordinaryForm"; //$NON-NLS-1$

    private Label status;

    /**
     * Accepts a workspace file and names the part after the form's directory.
     *
     * @param site the editor site
     * @param input the input, which must be a file in the workspace
     * @throws PartInitException when the input is not a workspace file
     */
    @Override
    public void init(IEditorSite site, IEditorInput input) throws PartInitException
    {
        if (!(input instanceof IFileEditorInput))
        {
            throw new PartInitException("The ordinary form editor opens a Form.oform in the workspace"); //$NON-NLS-1$
        }
        setSite(site);
        setInput(input);
        IFile file = ((IFileEditorInput)input).getFile();
        String formName = file.getParent() != null ? file.getParent().getName() : file.getName();
        setPartName(formName);
        setTitleToolTip(file.getFullPath().toString());
    }

    /**
     * Builds the part: one line with the counts read off the file.
     *
     * @param parent the parent composite
     */
    @Override
    public void createPartControl(Composite parent)
    {
        Composite body = new Composite(parent, SWT.NONE);
        GridLayoutFactory.swtDefaults().applyTo(body);
        status = new Label(body, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(status);
        status.setText(describe(FormSummary.read(((IFileEditorInput)getEditorInput()).getFile())));
    }

    /**
     * The status line for a summary.
     *
     * @param summary what the file holds
     * @return the text shown in the editor
     */
    static String describe(FormSummary summary)
    {
        if (summary.problem != null)
        {
            return "The form could not be read: " + summary.problem; //$NON-NLS-1$
        }
        StringBuilder text = new StringBuilder()
            .append("Read ").append(summary.elementCount).append(" elements, version ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(summary.version == null ? "unknown" : summary.version); //$NON-NLS-1$
        if (!summary.versionSupported)
        {
            text.append(" (not a measured version; editing will be read only)"); //$NON-NLS-1$
        }
        if (summary.unreadRecords > 0)
        {
            text.append(System.lineSeparator()).append(summary.unreadRecords)
                .append(" records of kinds the reader does not recognise"); //$NON-NLS-1$
        }
        return text.toString();
    }

    /**
     * Gives the status line focus.
     */
    @Override
    public void setFocus()
    {
        if (status != null && !status.isDisposed())
        {
            status.setFocus();
        }
    }

    /**
     * Writes nothing: the editor does not change the file.
     *
     * @param monitor the progress monitor
     */
    @Override
    public void doSave(IProgressMonitor monitor)
    {
        // Nothing to save: the editor holds no changes.
    }

    /**
     * Writes nothing: Save As is not offered.
     */
    @Override
    public void doSaveAs()
    {
        // Save As is not allowed.
    }

    /**
     * Never dirty: the editor holds no changes.
     *
     * @return {@code false}
     */
    @Override
    public boolean isDirty()
    {
        return false;
    }

    /**
     * Save As is not offered.
     *
     * @return {@code false}
     */
    @Override
    public boolean isSaveAsAllowed()
    {
        return false;
    }
}
