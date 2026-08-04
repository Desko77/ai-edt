/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Text;

import ru.aiedt.mcp.server.labels.MarkerKeys;
import ru.aiedt.mcp.server.labels.MarkerManager;
import ru.aiedt.mcp.server.labels.model.Marker;

/**
 * Assigns markers to one metadata object and manages the project's marker list along the way.
 * <p>
 * The table lists every marker with its color, keyboard digit, name and description, and a checkbox
 * showing whether it is on this object. Definition edits - create, rename, delete, reorder - take
 * effect immediately; the assignment checkboxes are applied only when the dialog is confirmed, by
 * assigning the newly checked markers and unassigning the newly cleared ones.
 * </p>
 */
public class MarkerManagerDialog
    extends Dialog
{
    private final IProject project;

    private final String objectFqn;

    private final MarkerManager service = MarkerManager.getInstance();

    private CheckboxTableViewer tableViewer;

    private LocalResourceManager resourceManager;

    private Set<String> initiallyAssigned;

    private Text newMarkerNameText;

    private Button newColorButton;

    private String newMarkerColor = MarkerKeys.DEFAULT_TAG_COLOR;

    private ImageDescriptor newSwatchDescriptor;

    /**
     * Creates the dialog.
     *
     * @param parentShell the parent shell
     * @param project the project the object and its markers live in
     * @param objectFqn the FQN of the object being marked
     */
    public MarkerManagerDialog(Shell parentShell, IProject project, String objectFqn)
    {
        super(parentShell);
        this.project = project;
        this.objectFqn = objectFqn;
    }

    @Override
    protected void configureShell(Shell newShell)
    {
        super.configureShell(newShell);
        newShell.setText("Manage Markers"); //$NON-NLS-1$
    }

    @Override
    protected boolean isResizable()
    {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite container = (Composite)super.createDialogArea(parent);
        resourceManager = new LocalResourceManager(JFaceResources.getResources(), container);
        container.setLayout(new GridLayout(2, false));

        createTable(container);
        createSideButtons(container);
        createNewMarkerRow(container);

        initiallyAssigned = service.getMarkerStorage(project).getMarkerNames(objectFqn);
        refreshTable();
        return container;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "Apply", true); //$NON-NLS-1$
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed()
    {
        Set<String> checked = checkedMarkerNames();
        for (Marker marker : service.getMarkers(project))
        {
            String name = marker.getName();
            boolean was = initiallyAssigned.contains(name);
            boolean now = checked.contains(name);
            if (now && !was)
            {
                service.assignMarker(project, objectFqn, name);
            }
            else if (!now && was)
            {
                service.unassignMarker(project, objectFqn, name);
            }
        }
        super.okPressed();
    }

    /**
     * Builds the checkbox table of markers.
     *
     * @param container the dialog area
     */
    private void createTable(Composite container)
    {
        tableViewer = CheckboxTableViewer.newCheckList(container, SWT.BORDER | SWT.FULL_SELECTION);
        Table table = tableViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.widthHint = 420;
        tableData.heightHint = 260;
        table.setLayoutData(tableData);

        addColumn(table, "", 34); //$NON-NLS-1$
        addColumn(table, "Key", 34); //$NON-NLS-1$
        addColumn(table, "Name", 150); //$NON-NLS-1$
        addColumn(table, "Description", 200); //$NON-NLS-1$

        tableViewer.setContentProvider(ArrayContentProvider.getInstance());
        tableViewer.setLabelProvider(new MarkerTableLabelProvider());
    }

    /**
     * Builds the Move/Edit/Delete button column.
     *
     * @param container the dialog area
     */
    private void createSideButtons(Composite container)
    {
        Composite buttons = new Composite(container, SWT.NONE);
        buttons.setLayout(new GridLayout(1, false));
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));

        createSideButton(buttons, "Move Up", () -> moveSelected(true)); //$NON-NLS-1$
        createSideButton(buttons, "Move Down", () -> moveSelected(false)); //$NON-NLS-1$
        createSideButton(buttons, "Edit...", this::editSelected); //$NON-NLS-1$
        createSideButton(buttons, "Delete", this::deleteSelected); //$NON-NLS-1$
    }

    /**
     * Builds the "create a new marker" row: name field, color button and Add button.
     *
     * @param container the dialog area
     */
    private void createNewMarkerRow(Composite container)
    {
        Composite row = new Composite(container, SWT.NONE);
        row.setLayout(new GridLayout(4, false));
        GridData rowData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        rowData.horizontalSpan = 2;
        row.setLayoutData(rowData);

        new Label(row, SWT.NONE).setText("New marker:"); //$NON-NLS-1$
        newMarkerNameText = new Text(row, SWT.BORDER);
        newMarkerNameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        newColorButton = new Button(row, SWT.PUSH);
        newColorButton.addListener(SWT.Selection, event -> chooseNewColor());
        updateNewColorButton();
        Button addButton = new Button(row, SWT.PUSH);
        addButton.setText("Add"); //$NON-NLS-1$
        addButton.addListener(SWT.Selection, event -> addNewMarker());
    }

    /**
     * Creates one push button in the side column.
     *
     * @param parent the button column
     * @param label the button label
     * @param action what to run when it is pressed
     */
    private void createSideButton(Composite parent, String label, Runnable action)
    {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(label);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button.addListener(SWT.Selection, event -> action.run());
    }

    /**
     * Adds a table column.
     *
     * @param table the table
     * @param title the header text
     * @param width the column width
     */
    private static void addColumn(Table table, String title, int width)
    {
        TableColumn column = new TableColumn(table, SWT.NONE);
        column.setText(title);
        column.setWidth(width);
    }

    /**
     * Moves the selected marker one position, keeping the checkbox state.
     *
     * @param up <code>true</code> to move up, <code>false</code> to move down
     */
    private void moveSelected(boolean up)
    {
        Marker marker = selectedMarker();
        if (marker == null)
        {
            return;
        }
        boolean moved = up ? service.moveMarkerUp(project, marker.getName())
            : service.moveMarkerDown(project, marker.getName());
        if (moved)
        {
            refreshTablePreservingChecks();
        }
    }

    /**
     * Opens the edit dialog on the selected marker and applies the result.
     */
    private void editSelected()
    {
        Marker marker = selectedMarker();
        if (marker == null)
        {
            return;
        }
        MarkerEditDialog dialog = new MarkerEditDialog(getShell(), marker);
        if (dialog.open() == Window.OK)
        {
            service.updateMarker(project, marker.getName(), dialog.getMarkerName(), dialog.getMarkerColor(),
                dialog.getMarkerDescription());
            initiallyAssigned = renameInAssigned(initiallyAssigned, marker.getName(), dialog.getMarkerName());
            refreshTablePreservingChecks();
        }
    }

    /**
     * Deletes the selected marker after confirmation.
     */
    private void deleteSelected()
    {
        Marker marker = selectedMarker();
        if (marker == null)
        {
            return;
        }
        boolean confirmed = MessageDialog.openConfirm(getShell(), "Delete Marker", //$NON-NLS-1$
            "Delete the marker \"" + marker.getName() + "\"? It will be removed from every object."); //$NON-NLS-1$ //$NON-NLS-2$
        if (confirmed)
        {
            service.deleteMarker(project, marker.getName());
            refreshTablePreservingChecks();
        }
    }

    /**
     * Creates the marker entered in the new-marker row.
     */
    private void addNewMarker()
    {
        String name = newMarkerNameText.getText().trim();
        if (name.isEmpty())
        {
            return;
        }
        Marker created = service.createMarker(project, name, newMarkerColor, ""); //$NON-NLS-1$
        if (created == null)
        {
            MessageDialog.openWarning(getShell(), "Duplicate Marker", //$NON-NLS-1$
                "A marker named \"" + name + "\" already exists."); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        newMarkerNameText.setText(""); //$NON-NLS-1$
        refreshTablePreservingChecks();
    }

    /**
     * Opens the color chooser for the new-marker row.
     */
    private void chooseNewColor()
    {
        org.eclipse.swt.graphics.RGB chosen =
            new org.eclipse.swt.widgets.ColorDialog(getShell()).open();
        if (chosen != null)
        {
            newMarkerColor = MarkerIconFactory.rgbToHex(chosen);
            updateNewColorButton();
        }
    }

    /**
     * Refreshes the new-marker color button's swatch.
     */
    private void updateNewColorButton()
    {
        if (newSwatchDescriptor != null)
        {
            resourceManager.destroy(newSwatchDescriptor);
        }
        newSwatchDescriptor = MarkerIconFactory.getColorIcon(newMarkerColor);
        newColorButton.setImage(resourceManager.create(newSwatchDescriptor));
    }

    /**
     * Reloads the table from the service and checks the currently assigned markers.
     */
    private void refreshTable()
    {
        tableViewer.setInput(service.getMarkers(project));
        for (Marker marker : service.getMarkers(project))
        {
            tableViewer.setChecked(marker, initiallyAssigned.contains(marker.getName()));
        }
    }

    /**
     * Reloads the table after a definition change while keeping the pending checkbox state.
     */
    private void refreshTablePreservingChecks()
    {
        Set<String> checked = checkedMarkerNames();
        tableViewer.setInput(service.getMarkers(project));
        for (Marker marker : service.getMarkers(project))
        {
            tableViewer.setChecked(marker, checked.contains(marker.getName()));
        }
    }

    /**
     * Returns the names of the currently checked markers.
     *
     * @return the checked marker names
     */
    private Set<String> checkedMarkerNames()
    {
        Set<String> names = new HashSet<>();
        for (Object checked : tableViewer.getCheckedElements())
        {
            names.add(((Marker)checked).getName());
        }
        return names;
    }

    /**
     * Returns the selected marker, or <code>null</code>.
     *
     * @return the selected marker
     */
    private Marker selectedMarker()
    {
        IStructuredSelection selection = tableViewer.getStructuredSelection();
        Object first = selection.getFirstElement();
        return first instanceof Marker ? (Marker)first : null;
    }

    /**
     * Applies a rename to the set of initially assigned names, so a renamed-while-open marker is still
     * recognized as assigned when the dialog is confirmed.
     *
     * @param assigned the current set
     * @param oldName the marker's old name
     * @param newName the marker's new name, or <code>null</code> when unchanged
     * @return the updated set
     */
    private static Set<String> renameInAssigned(Set<String> assigned, String oldName, String newName)
    {
        if (newName == null || newName.equals(oldName) || !assigned.contains(oldName))
        {
            return assigned;
        }
        Set<String> updated = new HashSet<>(assigned);
        updated.remove(oldName);
        updated.add(newName);
        return updated;
    }

    /**
     * The table's cell content: a color swatch, the keyboard digit, the name and the description.
     */
    private final class MarkerTableLabelProvider
        extends LabelProvider
        implements ITableLabelProvider
    {
        @Override
        public Image getColumnImage(Object element, int columnIndex)
        {
            if (columnIndex == 0 && element instanceof Marker)
            {
                return resourceManager.create(MarkerIconFactory.getColorIcon(((Marker)element).getColor()));
            }
            return null;
        }

        @Override
        public String getColumnText(Object element, int columnIndex)
        {
            if (!(element instanceof Marker))
            {
                return ""; //$NON-NLS-1$
            }
            Marker marker = (Marker)element;
            switch (columnIndex)
            {
                case 1:
                    int digit = service.getMarkerHotkeyIndex(project, marker.getName());
                    return digit >= 0 ? String.valueOf(digit) : ""; //$NON-NLS-1$
                case 2:
                    return marker.getName();
                case 3:
                    return marker.getDescription();
                default:
                    return ""; //$NON-NLS-1$
            }
        }
    }
}
