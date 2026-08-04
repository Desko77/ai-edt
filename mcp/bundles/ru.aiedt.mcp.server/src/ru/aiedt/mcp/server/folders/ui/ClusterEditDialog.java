/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.folders.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import ru.aiedt.mcp.server.folders.model.Cluster;

/**
 * Asks for a cluster's name and an optional multi-line description.
 * <p>
 * Serves both New and Edit: the plain constructor opens empty and titled "New Cluster", the one taking
 * a cluster opens prefilled and titled "Edit Cluster". The name is validated as it is typed through a
 * caller-supplied check, and OK stays disabled while the name is rejected. Both values come back
 * trimmed.
 * </p>
 */
public class ClusterEditDialog
    extends Dialog
{
    /**
     * Checks a proposed cluster name.
     */
    public interface IClusterNameValidator
    {
        /**
         * Validates a name.
         *
         * @param name the proposed name, already trimmed
         * @return an error message when the name is not acceptable, or <code>null</code> when it is
         */
        String validate(String name);
    }

    private static final String NEW_GROUP_TITLE = "New Cluster"; //$NON-NLS-1$

    private static final String EDIT_GROUP_TITLE = "Edit Cluster"; //$NON-NLS-1$

    private final String title;

    private final String initialName;

    private final String initialDescription;

    private final IClusterNameValidator validator;

    private Text nameText;

    private Text descriptionText;

    private Label errorLabel;

    private String clusterName;

    private String clusterDescription;

    /**
     * Creates a New Cluster dialog with empty fields.
     *
     * @param parentShell the parent shell
     * @param nameValidator the name check
     */
    public ClusterEditDialog(Shell parentShell, IClusterNameValidator nameValidator)
    {
        this(parentShell, NEW_GROUP_TITLE, "", "", nameValidator); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Creates an Edit Cluster dialog prefilled from a cluster.
     *
     * @param parentShell the parent shell
     * @param cluster the cluster to edit
     * @param nameValidator the name check
     */
    public ClusterEditDialog(Shell parentShell, Cluster cluster, IClusterNameValidator nameValidator)
    {
        this(parentShell, EDIT_GROUP_TITLE, cluster == null ? null : cluster.getName(),
            cluster == null ? null : cluster.getDescription(), nameValidator);
    }

    /**
     * Creates a dialog with an explicit title and initial values.
     *
     * @param parentShell the parent shell
     * @param title the shell title
     * @param name the initial name; <code>null</code> is treated as empty
     * @param description the initial description; <code>null</code> is treated as empty
     * @param nameValidator the name check
     */
    public ClusterEditDialog(Shell parentShell, String title, String name, String description,
        IClusterNameValidator nameValidator)
    {
        super(parentShell);
        this.title = title;
        this.initialName = name == null ? "" : name; //$NON-NLS-1$
        this.initialDescription = description == null ? "" : description; //$NON-NLS-1$
        this.validator = nameValidator;
    }

    /**
     * Returns the entered name, trimmed. Meaningful only after OK.
     *
     * @return the name
     */
    public String getClusterName()
    {
        return clusterName;
    }

    /**
     * Returns the entered description, trimmed. Meaningful only after OK.
     *
     * @return the description
     */
    public String getClusterDescription()
    {
        return clusterDescription;
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

        Composite container = new Composite(area, SWT.NONE);
        container.setLayout(new GridLayout(2, false));
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label nameLabel = new Label(container, SWT.NONE);
        nameLabel.setText("Name:"); //$NON-NLS-1$
        nameLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        nameText = new Text(container, SWT.BORDER);
        nameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        nameText.setText(initialName);
        nameText.addModifyListener(event -> validate());

        Label descriptionLabel = new Label(container, SWT.NONE);
        descriptionLabel.setText("Description:"); //$NON-NLS-1$
        descriptionLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));

        descriptionText = new Text(container, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData descriptionData = new GridData(SWT.FILL, SWT.FILL, true, true);
        descriptionData.heightHint = 60;
        descriptionData.widthHint = 320;
        descriptionText.setLayoutData(descriptionData);
        descriptionText.setText(initialDescription);

        errorLabel = new Label(container, SWT.NONE);
        errorLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        super.createButtonsForButtonBar(parent);
        validate();
    }

    @Override
    protected void okPressed()
    {
        clusterName = nameText.getText().trim();
        clusterDescription = descriptionText.getText().trim();
        super.okPressed();
    }

    /**
     * Runs the name check and reflects it in the error text and the OK button.
     */
    private void validate()
    {
        if (nameText == null || nameText.isDisposed())
        {
            return;
        }
        String name = nameText.getText().trim();
        String error = validator == null ? null : validator.validate(name);
        if (errorLabel != null && !errorLabel.isDisposed())
        {
            errorLabel.setText(error == null ? "" : error); //$NON-NLS-1$
        }
        Button okButton = getButton(IDialogConstants.OK_ID);
        if (okButton != null && !okButton.isDisposed())
        {
            okButton.setEnabled(error == null);
        }
    }
}
