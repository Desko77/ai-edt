/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.dialogs.SelectionDialog;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

import ru.aiedt.mcp.server.labels.MarkerManager;
import ru.aiedt.mcp.server.labels.model.Marker;

/**
 * Lets the user choose, per project, which markers the Navigator should filter to.
 * <p>
 * The tree lists each project that has markers and, under it, the markers themselves with tri-state project
 * checkboxes that follow their children. A search box narrows the markers by name or description, an
 * "unmarked only" checkbox flips the whole thing to show objects with no markers (and disables the tree),
 * and a context action edits a marker in place. "Set" applies the selection, the custom "Turn Off" button
 * clears the filter, and Cancel leaves it as it was.
 * </p>
 */
public class MarkerFilterDialog
    extends SelectionDialog
{
    private static final int TURN_OFF_ID = IDialogConstants.CLIENT_ID;

    private final IV8ProjectManager projectManager;

    private final MarkerManager service = MarkerManager.getInstance();

    private final List<IProject> projectsWithMarkers = new ArrayList<>();

    private final Map<IProject, Set<String>> initialSelection = new HashMap<>();

    private boolean initialShowUnmarkedOnly;

    private boolean turnedOff;

    private boolean resultShowUnmarkedOnly;

    private final Map<IProject, Set<Marker>> resultSelection = new HashMap<>();

    private CheckboxTreeViewer treeViewer;

    private Text searchText;

    private Button showUnmarkedCheck;

    private LocalResourceManager resourceManager;

    private final MarkerQueryFilter searchFilter = new MarkerQueryFilter();

    /**
     * Creates the dialog.
     *
     * @param parentShell the parent shell
     * @param projectManager the manager used to list projects
     */
    public MarkerFilterDialog(Shell parentShell, IV8ProjectManager projectManager)
    {
        super(parentShell);
        this.projectManager = projectManager;
        setTitle(Messages.FilterByMarkerDialog_Title);
    }

    /**
     * Sets the markers to show as selected when the dialog opens.
     *
     * @param selection the markers per project
     */
    public void setInitialSelection(Map<IProject, Set<Marker>> selection)
    {
        initialSelection.clear();
        if (selection != null)
        {
            for (Map.Entry<IProject, Set<Marker>> entry : selection.entrySet())
            {
                Set<String> names = new HashSet<>();
                for (Marker marker : entry.getValue())
                {
                    names.add(marker.getName());
                }
                initialSelection.put(entry.getKey(), names);
            }
        }
    }

    /**
     * Sets whether the "unmarked only" checkbox starts ticked.
     *
     * @param unmarkedOnly whether unmarked-only mode is initially on
     */
    public void setInitialShowUnmarkedOnly(boolean unmarkedOnly)
    {
        this.initialShowUnmarkedOnly = unmarkedOnly;
    }

    /**
     * Tells whether the dialog closed asking for a filter to be applied.
     *
     * @return <code>true</code> when unmarked-only mode is on or at least one marker was selected
     */
    public boolean isFilterEnabled()
    {
        if (resultShowUnmarkedOnly)
        {
            return true;
        }
        for (Set<Marker> markers : resultSelection.values())
        {
            if (!markers.isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether the dialog closed through the "Turn Off" button.
     *
     * @return <code>true</code> when the filter was turned off
     */
    public boolean isTurnedOff()
    {
        return turnedOff;
    }

    /**
     * Returns the markers selected per project.
     *
     * @return the selection
     */
    public Map<IProject, Set<Marker>> getSelectedMarkers()
    {
        return resultSelection;
    }

    /**
     * Tells whether the user asked to show only unmarked objects.
     *
     * @return <code>true</code> for unmarked-only mode
     */
    public boolean isShowUnmarkedOnly()
    {
        return resultShowUnmarkedOnly;
    }

    @Override
    protected Point getInitialSize()
    {
        return new Point(600, 500);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite container = (Composite)super.createDialogArea(parent);
        resourceManager = new LocalResourceManager(JFaceResources.getResources(), container);
        container.setLayout(new GridLayout(1, false));

        new Label(container, SWT.WRAP).setText(Messages.FilterByMarkerDialog_Description);

        createSearchRow(container);
        createTree(container);
        createUnmarkedCheck(container);

        loadProjects();
        treeViewer.setInput(projectsWithMarkers);
        applyInitialSelection();
        treeViewer.expandAll();
        applyInitialUnmarkedMode();
        return container;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, Messages.FilterByMarkerDialog_SetButton, true);
        createButton(parent, TURN_OFF_ID, Messages.FilterByMarkerDialog_TurnOffButton, false);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void buttonPressed(int buttonId)
    {
        if (buttonId == TURN_OFF_ID)
        {
            turnedOff = true;
            resultSelection.clear();
            resultShowUnmarkedOnly = false;
            setReturnCode(Window.CANCEL);
            close();
            return;
        }
        super.buttonPressed(buttonId);
    }

    @Override
    protected void okPressed()
    {
        resultShowUnmarkedOnly = showUnmarkedCheck.getSelection();
        resultSelection.clear();
        if (!resultShowUnmarkedOnly)
        {
            for (IProject project : projectsWithMarkers)
            {
                Set<Marker> checked = new HashSet<>();
                for (Marker marker : service.getMarkers(project))
                {
                    if (treeViewer.getChecked(marker))
                    {
                        checked.add(marker);
                    }
                }
                if (!checked.isEmpty())
                {
                    resultSelection.put(project, checked);
                }
            }
        }
        super.okPressed();
    }

    /**
     * Builds the marker search field.
     *
     * @param container the dialog area
     */
    private void createSearchRow(Composite container)
    {
        searchText = new Text(container, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
        searchText.setMessage(Messages.FilterByMarkerDialog_SearchPlaceholder);
        searchText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        searchText.addModifyListener(event -> {
            searchFilter.setQuery(searchText.getText());
            treeViewer.refresh();
            treeViewer.expandAll();
        });
    }

    /**
     * Builds the checkbox tree and its context menu, plus the select/deselect buttons.
     *
     * @param container the dialog area
     */
    private void createTree(Composite container)
    {
        Composite treeArea = new Composite(container, SWT.NONE);
        treeArea.setLayout(new GridLayout(2, false));
        treeArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        treeViewer = new CheckboxTreeViewer(treeArea, SWT.BORDER);
        treeViewer.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        treeViewer.setContentProvider(new MarkerTreeContentProvider());
        treeViewer.setLabelProvider(new MarkerTreeLabelProvider());
        treeViewer.addFilter(searchFilter);
        treeViewer.addCheckStateListener(event -> onCheckStateChanged(event.getElement(), event.getChecked()));
        addContextMenu(treeViewer.getTree());

        Composite buttons = new Composite(treeArea, SWT.NONE);
        buttons.setLayout(new GridLayout(1, false));
        buttons.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        Button selectAll = new Button(buttons, SWT.PUSH);
        selectAll.setText("+"); //$NON-NLS-1$
        selectAll.setToolTipText(Messages.FilterByMarkerDialog_SelectAll);
        selectAll.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        selectAll.addListener(SWT.Selection, event -> setAllChecked(true));
        Button deselectAll = new Button(buttons, SWT.PUSH);
        deselectAll.setText("-"); //$NON-NLS-1$
        deselectAll.setToolTipText(Messages.FilterByMarkerDialog_DeselectAll);
        deselectAll.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        deselectAll.addListener(SWT.Selection, event -> setAllChecked(false));
    }

    /**
     * Builds the "show unmarked only" checkbox.
     *
     * @param container the dialog area
     */
    private void createUnmarkedCheck(Composite container)
    {
        showUnmarkedCheck = new Button(container, SWT.CHECK);
        showUnmarkedCheck.setText(Messages.FilterByMarkerDialog_ShowUnmarkedOnly);
        showUnmarkedCheck.setToolTipText(Messages.FilterByMarkerDialog_ShowUnmarkedOnlyTooltip);
        showUnmarkedCheck.addListener(SWT.Selection,
            event -> treeViewer.getTree().setEnabled(!showUnmarkedCheck.getSelection()));
    }

    /**
     * Adds the "Edit Marker..." context action to the tree.
     *
     * @param tree the tree widget
     */
    private void addContextMenu(Tree tree)
    {
        MenuManager menuManager = new MenuManager();
        menuManager.add(new Action(Messages.FilterByMarkerDialog_EditMarker)
        {
            @Override
            public void run()
            {
                editSelectedMarker();
            }
        });
        Menu menu = menuManager.createContextMenu(tree);
        tree.setMenu(menu);
    }

    /**
     * Fills {@link #projectsWithMarkers} with every project that defines at least one marker.
     */
    private void loadProjects()
    {
        projectsWithMarkers.clear();
        if (projectManager == null)
        {
            return;
        }
        for (IV8Project v8Project : projectManager.getProjects())
        {
            IProject project = v8Project.getProject();
            if (project != null && !service.getMarkers(project).isEmpty() && !projectsWithMarkers.contains(project))
            {
                projectsWithMarkers.add(project);
            }
        }
    }

    /**
     * Ticks the markers recorded in the initial selection and updates the project tri-states.
     */
    private void applyInitialSelection()
    {
        for (IProject project : projectsWithMarkers)
        {
            Set<String> names = initialSelection.get(project);
            if (names == null)
            {
                continue;
            }
            for (Marker marker : service.getMarkers(project))
            {
                if (names.contains(marker.getName()))
                {
                    treeViewer.setChecked(marker, true);
                }
            }
            updateProjectState(project);
        }
    }

    /**
     * Applies the initial unmarked-only mode to the checkbox and tree.
     */
    private void applyInitialUnmarkedMode()
    {
        showUnmarkedCheck.setSelection(initialShowUnmarkedOnly);
        treeViewer.getTree().setEnabled(!initialShowUnmarkedOnly);
    }

    /**
     * Reacts to a checkbox change: a project cascades to its markers, a marker updates its project.
     *
     * @param element the element whose check state changed
     * @param checked its new state
     */
    private void onCheckStateChanged(Object element, boolean checked)
    {
        if (element instanceof IProject)
        {
            IProject project = (IProject)element;
            treeViewer.setGrayed(project, false);
            for (Marker marker : service.getMarkers(project))
            {
                treeViewer.setChecked(marker, checked);
            }
        }
        else if (element instanceof Marker)
        {
            IProject project = projectOf((Marker)element);
            if (project != null)
            {
                updateProjectState(project);
            }
        }
    }

    /**
     * Recomputes a project node's tri-state from how many of its markers are checked.
     *
     * @param project the project
     */
    private void updateProjectState(IProject project)
    {
        List<Marker> markers = service.getMarkers(project);
        int checkedCount = 0;
        for (Marker marker : markers)
        {
            if (treeViewer.getChecked(marker))
            {
                checkedCount++;
            }
        }
        if (checkedCount == 0)
        {
            treeViewer.setGrayed(project, false);
            treeViewer.setChecked(project, false);
        }
        else if (checkedCount == markers.size())
        {
            treeViewer.setGrayed(project, false);
            treeViewer.setChecked(project, true);
        }
        else
        {
            treeViewer.setGrayChecked(project, true);
        }
    }

    /**
     * Checks or unchecks every marker in every project.
     *
     * @param checked the state to apply
     */
    private void setAllChecked(boolean checked)
    {
        for (IProject project : projectsWithMarkers)
        {
            for (Marker marker : service.getMarkers(project))
            {
                treeViewer.setChecked(marker, checked);
            }
            updateProjectState(project);
        }
    }

    /**
     * Opens the edit dialog on the selected marker and refreshes.
     */
    private void editSelectedMarker()
    {
        IStructuredSelection selection = treeViewer.getStructuredSelection();
        Object first = selection.getFirstElement();
        if (!(first instanceof Marker))
        {
            return;
        }
        Marker marker = (Marker)first;
        IProject project = projectOf(marker);
        if (project == null)
        {
            return;
        }
        MarkerEditDialog dialog = new MarkerEditDialog(getShell(), marker);
        if (dialog.open() == Window.OK)
        {
            service.updateMarker(project, marker.getName(), dialog.getMarkerName(), dialog.getMarkerColor(),
                dialog.getMarkerDescription());
            treeViewer.refresh();
            treeViewer.expandAll();
        }
    }

    /**
     * Finds the project a marker instance belongs to.
     *
     * @param marker the marker
     * @return its project, or <code>null</code>
     */
    private IProject projectOf(Marker marker)
    {
        for (IProject project : projectsWithMarkers)
        {
            for (Marker candidate : service.getMarkers(project))
            {
                if (candidate == marker)
                {
                    return project;
                }
            }
        }
        return null;
    }

    /**
     * Supplies the tree structure: projects at the root, their markers beneath.
     */
    private final class MarkerTreeContentProvider
        implements ITreeContentProvider
    {
        @Override
        public Object[] getElements(Object inputElement)
        {
            return projectsWithMarkers.toArray();
        }

        @Override
        public Object[] getChildren(Object parentElement)
        {
            if (parentElement instanceof IProject)
            {
                return service.getMarkers((IProject)parentElement).toArray();
            }
            return new Object[0];
        }

        @Override
        public Object getParent(Object element)
        {
            return element instanceof Marker ? projectOf((Marker)element) : null;
        }

        @Override
        public boolean hasChildren(Object element)
        {
            return element instanceof IProject && !service.getMarkers((IProject)element).isEmpty();
        }
    }

    /**
     * Labels project nodes with their name and marker nodes with a swatch and name.
     */
    private final class MarkerTreeLabelProvider
        extends LabelProvider
    {
        @Override
        public String getText(Object element)
        {
            if (element instanceof IProject)
            {
                return ((IProject)element).getName();
            }
            if (element instanceof Marker)
            {
                return ((Marker)element).getName();
            }
            return super.getText(element);
        }

        @Override
        public Image getImage(Object element)
        {
            if (element instanceof Marker)
            {
                return resourceManager.create(MarkerIconFactory.getColorIcon(((Marker)element).getColor()));
            }
            return null;
        }
    }

    /**
     * Hides markers whose name and description do not contain the search text; projects always show so a
     * match is never hidden behind its project.
     */
    private static final class MarkerQueryFilter
        extends ViewerFilter
    {
        private String query = ""; //$NON-NLS-1$

        void setQuery(String query)
        {
            this.query = query != null ? query.toLowerCase() : ""; //$NON-NLS-1$
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            if (query.isEmpty() || !(element instanceof Marker))
            {
                return true;
            }
            Marker marker = (Marker)element;
            return marker.getName().toLowerCase().contains(query)
                || marker.getDescription().toLowerCase().contains(query);
        }
    }
}
