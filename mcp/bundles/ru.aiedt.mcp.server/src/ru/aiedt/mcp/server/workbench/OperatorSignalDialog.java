/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.workbench;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import ru.aiedt.mcp.server.OperatorSignal;

/**
 * The little box that opens when the user raises a signal from the status bar.
 * <p>
 * It shows what this kind of signal means, lets the user edit the message that will go to the agent,
 * and previews - live, as they type - exactly the JSON the agent will receive. The signal changes no
 * work in progress; it only reaches the agent. The wording throughout says so.
 * </p>
 */
public class OperatorSignalDialog
    extends Dialog
{
    private static final int DESCRIPTION_WIDTH = 400;

    private static final int MESSAGE_HEIGHT = 100;

    private static final int PREVIEW_HEIGHT = 80;

    private final OperatorSignal.SignalType signalType;

    private final String title;

    private String message;

    private Text messageText;

    private Text previewText;

    /**
     * Opens the dialog for a signal.
     *
     * @param parentShell the shell to centre on
     * @param signalType  what the user is telling the agent; also selects the description shown
     * @param title       the window title
     */
    public OperatorSignalDialog(Shell parentShell, OperatorSignal.SignalType signalType, String title)
    {
        super(parentShell);
        this.signalType = signalType;
        this.title = title;
        this.message = OperatorSignal.getDefaultMessage(signalType);
    }

    @Override
    protected void configureShell(Shell newShell)
    {
        super.configureShell(newShell);
        newShell.setText(title);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite area = (Composite)super.createDialogArea(parent);

        Composite content = new Composite(area, SWT.NONE);
        content.setLayout(new GridLayout(1, false));
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label descriptionLabel = new Label(content, SWT.WRAP);
        descriptionLabel.setText(describe(signalType));
        GridData descriptionData = new GridData(SWT.FILL, SWT.TOP, true, false);
        descriptionData.widthHint = DESCRIPTION_WIDTH;
        descriptionLabel.setLayoutData(descriptionData);

        Label separator = new Label(content, SWT.SEPARATOR | SWT.HORIZONTAL);
        separator.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label messageLabel = new Label(content, SWT.NONE);
        messageLabel.setText("Your message:"); //$NON-NLS-1$

        messageText = new Text(content, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        messageText.setText(message);
        GridData messageData = new GridData(SWT.FILL, SWT.FILL, true, true);
        messageData.heightHint = MESSAGE_HEIGHT;
        messageData.widthHint = DESCRIPTION_WIDTH;
        messageText.setLayoutData(messageData);

        Label previewLabel = new Label(content, SWT.NONE);
        previewLabel.setText("The agent will receive:"); //$NON-NLS-1$

        previewText = new Text(content, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        // A system colour; the toolkit owns it, so it must not be disposed here.
        previewText.setBackground(parent.getDisplay().getSystemColor(SWT.COLOR_INFO_BACKGROUND));
        GridData previewData = new GridData(SWT.FILL, SWT.FILL, true, false);
        previewData.heightHint = PREVIEW_HEIGHT;
        previewData.widthHint = DESCRIPTION_WIDTH;
        previewText.setLayoutData(previewData);

        messageText.addModifyListener(e -> refreshPreview());
        refreshPreview();

        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "Deliver", true); //$NON-NLS-1$
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed()
    {
        // Captured before the widgets go, so getMessage() still works after open() returns.
        message = messageText.getText();
        super.okPressed();
    }

    /**
     * Returns the message the user is sending.
     *
     * @return the edited text once OK has been pressed, or the default message when the dialog was
     *         cancelled or never opened
     */
    public String getMessage()
    {
        return message;
    }

    /**
     * Returns the kind of signal this dialog is for.
     *
     * @return the signal type
     */
    public OperatorSignal.SignalType getSignalType()
    {
        return signalType;
    }

    /**
     * Rebuilds the preview from what is currently typed.
     */
    private void refreshPreview()
    {
        OperatorSignal preview = new OperatorSignal(signalType, messageText.getText());
        previewText.setText(preview.toJson());
    }

    /**
     * Returns the sentence that explains a signal type.
     *
     * @param type the signal type; may be <code>null</code>
     * @return the description
     */
    private static String describe(OperatorSignal.SignalType type)
    {
        if (type == null)
        {
            return "Type a message and it reaches the agent on its next check."; //$NON-NLS-1$
        }
        switch (type)
        {
        case CANCEL:
            return "Cancel the current operation. The agent will be notified that you manually stopped " //$NON-NLS-1$
                + "the operation."; //$NON-NLS-1$
        case RETRY:
            return "Ask the agent to retry the last operation. Use this when an EDT error occurred and " //$NON-NLS-1$
                + "you want the agent to try again."; //$NON-NLS-1$
        case BACKGROUND:
            return "Notify the agent that this is a long-running operation. The agent should check the " //$NON-NLS-1$
                + "status periodically instead of waiting."; //$NON-NLS-1$
        case EXPERT:
            return "Stop the current action and ask the agent to consult with you (the expert) before " //$NON-NLS-1$
                + "continuing."; //$NON-NLS-1$
        case CUSTOM:
        default:
            return "Type a message and it reaches the agent on its next check."; //$NON-NLS-1$
        }
    }
}
