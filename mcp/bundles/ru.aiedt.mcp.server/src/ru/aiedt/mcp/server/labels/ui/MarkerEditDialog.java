/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import ru.aiedt.mcp.server.labels.MarkerKeys;
import ru.aiedt.mcp.server.labels.model.Marker;

/**
 * A small dialog to edit one marker's name, color and description, or to enter a new one.
 * <p>
 * The name is trimmed and an empty name is refused. The color is picked from the platform color
 * chooser and held as a hex string; the description is a trimmed multi-line field. The edited values
 * are read back through the getters after the dialog closes with OK.
 * </p>
 */
public class MarkerEditDialog
    extends Dialog
{
    private final Marker original;

    private String markerName;

    private String markerColor;

    private String markerDescription;

    private String currentColor;

    private Text nameText;

    private Text descriptionText;

    private Button colorButton;

    private LocalResourceManager resourceManager;

    private ImageDescriptor swatchDescriptor;

    /**
     * Creates the dialog.
     *
     * @param parentShell the parent shell
     * @param marker the marker to edit, or <code>null</code> to enter a new one
     */
    public MarkerEditDialog(Shell parentShell, Marker marker)
    {
        super(parentShell);
        this.original = marker;
        if (marker != null)
        {
            this.markerName = marker.getName();
            this.markerColor = marker.getColor();
            this.markerDescription = marker.getDescription();
            this.currentColor = marker.getColor();
        }
        else
        {
            this.markerName = ""; //$NON-NLS-1$
            this.markerColor = MarkerKeys.DEFAULT_TAG_COLOR;
            this.markerDescription = ""; //$NON-NLS-1$
            this.currentColor = MarkerKeys.DEFAULT_TAG_COLOR;
        }
    }

    /**
     * Returns the edited marker name.
     *
     * @return the name
     */
    public String getMarkerName()
    {
        return markerName;
    }

    /**
     * Returns the edited marker color as a hex string.
     *
     * @return the color
     */
    public String getMarkerColor()
    {
        return markerColor;
    }

    /**
     * Returns the edited marker description.
     *
     * @return the description
     */
    public String getMarkerDescription()
    {
        return markerDescription;
    }

    @Override
    protected void configureShell(Shell newShell)
    {
        super.configureShell(newShell);
        newShell.setText(original != null ? "Edit Marker" : "New Marker"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite container = (Composite)super.createDialogArea(parent);
        resourceManager = new LocalResourceManager(JFaceResources.getResources(), container);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 10;
        layout.marginHeight = 10;
        container.setLayout(layout);

        new Label(container, SWT.NONE).setText("Name:"); //$NON-NLS-1$
        nameText = new Text(container, SWT.BORDER);
        nameText.setText(markerName);
        nameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(container, SWT.NONE).setText("Color:"); //$NON-NLS-1$
        colorButton = new Button(container, SWT.PUSH);
        colorButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        colorButton.addListener(SWT.Selection, event -> chooseColor());
        updateColorButton();

        Label descriptionLabel = new Label(container, SWT.NONE);
        descriptionLabel.setText("Description:"); //$NON-NLS-1$
        descriptionLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        descriptionText = new Text(container, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        descriptionText.setText(markerDescription);
        GridData descriptionData = new GridData(SWT.FILL, SWT.FILL, true, true);
        descriptionData.heightHint = 60;
        descriptionData.widthHint = 260;
        descriptionText.setLayoutData(descriptionData);

        return container;
    }

    @Override
    protected void okPressed()
    {
        String name = nameText.getText().trim();
        if (name.isEmpty())
        {
            MessageDialog.openWarning(getShell(), "Invalid Name", "Marker name cannot be empty."); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        markerName = name;
        markerColor = currentColor;
        markerDescription = descriptionText.getText().trim();
        super.okPressed();
    }

    /**
     * Opens the platform color chooser and records the picked color.
     */
    private void chooseColor()
    {
        ColorDialog colorDialog = new ColorDialog(getShell());
        colorDialog.setRGB(MarkerIconFactory.hexToRgb(currentColor));
        RGB chosen = colorDialog.open();
        if (chosen != null)
        {
            currentColor = MarkerIconFactory.rgbToHex(chosen);
            updateColorButton();
        }
    }

    /**
     * Refreshes the color button's swatch and hex label, freeing the previous swatch first.
     */
    private void updateColorButton()
    {
        if (swatchDescriptor != null)
        {
            resourceManager.destroy(swatchDescriptor);
        }
        swatchDescriptor = MarkerIconFactory.getColorIcon(currentColor);
        colorButton.setImage(resourceManager.create(swatchDescriptor));
        colorButton.setText(currentColor);
    }
}
