/**

 * AI-EDT - 1C AI tools for EDT

 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)

 * Licensed under AGPL-3.0-or-later

 */



package ru.aiedt.mcp.server.labels.ui;



import java.util.ArrayList;

import java.util.List;

import java.util.Map;

import java.util.Set;

import java.util.TreeSet;

import java.util.regex.Pattern;

import java.util.regex.PatternSyntaxException;

import java.util.stream.Collectors;



import org.eclipse.core.resources.IFile;

import org.eclipse.core.resources.IProject;

import org.eclipse.core.runtime.IProgressMonitor;

import org.eclipse.emf.ecore.EClass;

import org.eclipse.emf.ecore.EClassifier;

import org.eclipse.emf.ecore.EObject;

import org.eclipse.jface.action.Action;

import org.eclipse.jface.action.MenuManager;

import org.eclipse.jface.resource.ImageDescriptor;

import org.eclipse.jface.resource.JFaceResources;

import org.eclipse.jface.resource.LocalResourceManager;

import org.eclipse.jface.viewers.ArrayContentProvider;

import org.eclipse.jface.viewers.CheckboxTreeViewer;

import org.eclipse.jface.viewers.ColumnLabelProvider;

import org.eclipse.jface.viewers.ITreeContentProvider;

import org.eclipse.jface.viewers.IStructuredSelection;

import org.eclipse.jface.viewers.LabelProvider;

import org.eclipse.jface.viewers.TableViewer;

import org.eclipse.jface.viewers.TableViewerColumn;

import org.eclipse.jface.viewers.Viewer;

import org.eclipse.jface.viewers.ViewerFilter;

import org.eclipse.swt.SWT;

import org.eclipse.swt.custom.SashForm;

import org.eclipse.swt.dnd.Clipboard;

import org.eclipse.swt.dnd.TextTransfer;

import org.eclipse.swt.dnd.Transfer;

import org.eclipse.swt.graphics.Image;

import org.eclipse.swt.layout.GridData;

import org.eclipse.swt.layout.GridLayout;

import org.eclipse.swt.widgets.Button;

import org.eclipse.swt.widgets.Composite;

import org.eclipse.swt.widgets.Menu;

import org.eclipse.swt.widgets.Table;

import org.eclipse.swt.widgets.Text;

import org.eclipse.ui.ISharedImages;

import org.eclipse.ui.IWorkbenchPage;

import org.eclipse.ui.PlatformUI;

import org.eclipse.ui.ide.IDE;

import org.eclipse.ui.part.ViewPart;



import com._1c.g5.v8.bm.core.IBmObject;

import com._1c.g5.v8.bm.core.IBmTransaction;

import com._1c.g5.v8.bm.integration.AbstractBmTask;

import com._1c.g5.v8.bm.integration.IBmModel;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;

import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

import com._1c.g5.v8.dt.ui.util.OpenHelper;



import ru.aiedt.mcp.server.Activator;

import ru.aiedt.mcp.server.labels.MarkerKeys;

import ru.aiedt.mcp.server.labels.MarkerManager;

import ru.aiedt.mcp.server.labels.model.Marker;



/**

 * A standalone view for exploring marked objects across projects.

 * <p>

 * The left side is a checkbox tree of projects and their markers, each marker showing how many objects

 * carry it (or a filtered/total pair while a search is active) and offering an "Open YAML File"

 * action. The right side lists the objects carrying the checked markers, with a case-insensitive regular

 * expression search over their simplified FQNs, a "hide empty markers" toggle, copy actions and

 * double-click navigation to the object's editor. The view title tracks the number of results.

 * </p>

 */

public class MarkerFilterPanel

    extends ViewPart

    implements MarkerManager.IMarkerChangeListener

{

    /** The view id, matching the one declared in plugin.xml. */

    public static final String ID = "ru.aiedt.mcp.server.labels.filterView"; //$NON-NLS-1$



    private final MarkerManager service = MarkerManager.getInstance();



    private final List<IProject> projectsWithMarkers = new ArrayList<>();



    private final List<Image> toolbarImages = new ArrayList<>();



    private CheckboxTreeViewer markerTree;



    private TableViewer resultTable;



    private Text searchText;



    private Button hideEmptyCheck;



    private LocalResourceManager resourceManager;



    private Pattern searchPattern;



    /** A marked object as it appears in the results table. */

    private record ResultRow(IProject project, String fqn, List<String> markerNames)

    {

    }



    @Override

    public void createPartControl(Composite parent)

    {

        SashForm sash = new SashForm(parent, SWT.HORIZONTAL);

        resourceManager = new LocalResourceManager(JFaceResources.getResources(), sash);

        createMarkersPanel(sash);

        createResultsPanel(sash);

        sash.setWeights(new int[] {1, 2});



        service.addMarkerChangeListener(this);

        refresh();

    }



    @Override

    public void setFocus()

    {

        if (markerTree != null && !markerTree.getControl().isDisposed())

        {

            markerTree.getControl().setFocus();

        }

    }



    @Override

    public void dispose()

    {

        service.removeMarkerChangeListener(this);

        // F8: the three toolbar images were created directly, so they are disposed here rather than

        // left to leak on every view open.

        for (Image image : toolbarImages)

        {

            if (image != null && !image.isDisposed())

            {

                image.dispose();

            }

        }

        toolbarImages.clear();

        super.dispose();

    }



    @Override

    public void onMarkersChanged(IProject project)

    {

        asyncRefresh();

    }



    @Override

    public void onAssignmentsChanged(IProject project, String objectFqn)

    {

        asyncRefresh();

    }



    /**

     * Builds the left panel: the toolbar and the checkbox tree of projects and markers.

     *

     * @param parent the sash

     */

    private void createMarkersPanel(Composite parent)

    {

        Composite panel = new Composite(parent, SWT.NONE);

        panel.setLayout(new GridLayout(1, false));



        Composite toolbar = new Composite(panel, SWT.NONE);

        toolbar.setLayout(new GridLayout(3, false));

        Button selectAll = toolbarButton(toolbar, "Select All", ISharedImages.IMG_ELCL_SYNCED); //$NON-NLS-1$

        selectAll.addListener(SWT.Selection, event -> setAllChecked(true));

        Button deselectAll = toolbarButton(toolbar, "Deselect All", ISharedImages.IMG_ELCL_REMOVEALL); //$NON-NLS-1$

        deselectAll.addListener(SWT.Selection, event -> setAllChecked(false));

        Button refreshButton = toolbarButton(toolbar, "Refresh", ISharedImages.IMG_ELCL_SYNCED); //$NON-NLS-1$

        refreshButton.addListener(SWT.Selection, event -> refresh());



        markerTree = new CheckboxTreeViewer(panel, SWT.BORDER);

        markerTree.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        markerTree.setContentProvider(new MarkerTreeContentProvider());

        markerTree.setLabelProvider(new MarkerTreeLabelProvider());

        markerTree.addFilter(new HideEmptyMarkersFilter());

        markerTree.addCheckStateListener(event -> onMarkerChecked(event.getElement(), event.getChecked()));

        addMarkerContextMenu();

    }



    /**

     * Builds the right panel: the search field, the hide-empty toggle and the results table.

     *

     * @param parent the sash

     */

    private void createResultsPanel(Composite parent)

    {

        Composite panel = new Composite(parent, SWT.NONE);

        panel.setLayout(new GridLayout(1, false));



        searchText = new Text(panel, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);

        searchText.setMessage("Filter objects by regular expression..."); //$NON-NLS-1$

        searchText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        searchText.addModifyListener(event -> onSearchChanged());



        hideEmptyCheck = new Button(panel, SWT.CHECK);

        hideEmptyCheck.setText("Hide empty markers"); //$NON-NLS-1$

        hideEmptyCheck.addListener(SWT.Selection, event -> markerTree.refresh());



        resultTable = new TableViewer(panel, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);

        Table table = resultTable.getTable();

        table.setHeaderVisible(true);

        table.setLinesVisible(true);

        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        resultTable.setContentProvider(ArrayContentProvider.getInstance());



        addTableColumn("Project", 140, row -> row.project().getName(), null); //$NON-NLS-1$

        addTableColumn("Object", 280, row -> simplifyFqn(row.fqn()), this::objectImage); //$NON-NLS-1$

        addTableColumn("Markers", 200, row -> String.join(", ", row.markerNames()), null); //$NON-NLS-1$ //$NON-NLS-2$



        resultTable.addDoubleClickListener(event -> navigateToSelected());

        addResultContextMenu();

    }



    /**

     * Creates one toolbar push button carrying a shared image (tracked for later disposal).

     *

     * @param parent the toolbar composite

     * @param tooltip the button tooltip

     * @param sharedImageId a workbench shared-image id

     * @return the button

     */

    private Button toolbarButton(Composite parent, String tooltip, String sharedImageId)

    {

        Button button = new Button(parent, SWT.PUSH);

        button.setToolTipText(tooltip);

        ImageDescriptor descriptor = PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(sharedImageId);

        if (descriptor != null)

        {

            Image image = descriptor.createImage();

            toolbarImages.add(image);

            button.setImage(image);

        }

        else

        {

            button.setText(tooltip);

        }

        return button;

    }



    /**

     * Adds a results table column.

     *

     * @param title the header text

     * @param width the column width

     * @param textFunction how to render the cell text

     * @param imageFunction how to render the cell image, or <code>null</code>

     */

    private void addTableColumn(String title, int width, java.util.function.Function<ResultRow, String> textFunction,

        java.util.function.Function<ResultRow, Image> imageFunction)

    {

        TableViewerColumn column = new TableViewerColumn(resultTable, SWT.NONE);

        column.getColumn().setText(title);

        column.getColumn().setWidth(width);

        column.setLabelProvider(new ColumnLabelProvider()

        {

            @Override

            public String getText(Object element)

            {

                return element instanceof ResultRow ? textFunction.apply((ResultRow)element) : ""; //$NON-NLS-1$

            }



            @Override

            public Image getImage(Object element)

            {

                if (imageFunction != null && element instanceof ResultRow)

                {

                    return imageFunction.apply((ResultRow)element);

                }

                return null;

            }

        });

    }



    /**

     * Returns the EDT icon for a result row's object, derived from its top metadata type.

     *

     * @param row the row

     * @return the type's shared image, or <code>null</code>

     */

    private Image objectImage(ResultRow row)

    {

        EClassifier classifier = MdClassPackage.eINSTANCE.getEClassifier(topType(row.fqn()));

        if (classifier instanceof EClass)

        {

            return MdUiSharedImages.getMdClassImage((EClass)classifier);

        }

        return null;

    }



    /**

     * Recompiles the search pattern (tolerating an invalid one) and refreshes both sides.

     */

    private void onSearchChanged()

    {

        String text = searchText.getText();

        if (text.isEmpty())

        {

            searchPattern = null;

            searchText.setToolTipText(null);

        }

        else

        {

            try

            {

                searchPattern = Pattern.compile(text, Pattern.CASE_INSENSITIVE);

                searchText.setToolTipText(null);

            }

            catch (PatternSyntaxException e)

            {

                searchPattern = null;

                searchText.setToolTipText("Invalid regular expression: " + e.getDescription()); //$NON-NLS-1$

            }

        }

        markerTree.refresh();

        updateResults();

    }



    /**

     * Reacts to a checkbox change on the left tree.

     *

     * @param element the element toggled

     * @param checked its new state

     */

    private void onMarkerChecked(Object element, boolean checked)

    {

        if (element instanceof IProject)

        {

            for (Marker marker : service.getMarkers((IProject)element))

            {

                markerTree.setChecked(marker, checked);

            }

        }

        updateResults();

    }



    /**

     * Checks or unchecks every marker.

     *

     * @param checked the state to apply

     */

    private void setAllChecked(boolean checked)

    {

        for (IProject project : projectsWithMarkers)

        {

            markerTree.setChecked(project, checked);

            for (Marker marker : service.getMarkers(project))

            {

                markerTree.setChecked(marker, checked);

            }

        }

        updateResults();

    }



    /**

     * Reloads projects and repaints both sides, keeping the current checkbox state.

     */

    private void refresh()

    {

        Set<String> checkedKeys = checkedMarkerKeys();

        loadProjects();

        markerTree.setInput(projectsWithMarkers);

        markerTree.expandAll();

        restoreChecks(checkedKeys);

        updateResults();

    }



    /**

     * Marshals a {@link #refresh()} onto the UI thread for listener callbacks.

     */

    private void asyncRefresh()

    {

        if (markerTree == null || markerTree.getControl().isDisposed())

        {

            return;

        }

        markerTree.getControl().getDisplay().asyncExec(() -> {

            if (!markerTree.getControl().isDisposed())

            {

                refresh();

            }

        });

    }



    /**

     * Rebuilds the results table from the checked markers and the search, and updates the view title.

     */

    private void updateResults()

    {

        List<ResultRow> rows = new ArrayList<>();

        for (IProject project : projectsWithMarkers)

        {

            Set<String> checkedNames = checkedMarkerNames(project);

            if (checkedNames.isEmpty())

            {

                continue;

            }

            Map<String, Set<Marker>> matches = service.findObjectsByMarkers(project, checkedNames);

            for (Map.Entry<String, Set<Marker>> entry : matches.entrySet())

            {

                String fqn = entry.getKey();

                if (searchPattern != null && !searchPattern.matcher(simplifyFqn(fqn)).find())

                {

                    continue;

                }

                List<String> names = entry.getValue().stream().map(Marker::getName).sorted()

                    .collect(Collectors.toList());

                rows.add(new ResultRow(project, fqn, names));

            }

        }

        resultTable.setInput(rows);

        setPartName("Marker Filter (" + rows.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$

    }



    /**

     * Fills {@link #projectsWithMarkers} with every project that defines a marker.

     */

    private void loadProjects()

    {

        projectsWithMarkers.clear();

        for (IProject project : org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProjects())

        {

            if (project.isAccessible() && !service.getMarkers(project).isEmpty())

            {

                projectsWithMarkers.add(project);

            }

        }

    }



    /**

     * Returns the {@code project.getName()/markerName} keys of the currently checked markers.

     *

     * @return the checked keys

     */

    private Set<String> checkedMarkerKeys()

    {

        Set<String> keys = new TreeSet<>();

        if (markerTree == null)

        {

            return keys;

        }

        for (IProject project : projectsWithMarkers)

        {

            for (Marker marker : service.getMarkers(project))

            {

                if (markerTree.getChecked(marker))

                {

                    keys.add(project.getName() + "/" + marker.getName()); //$NON-NLS-1$

                }

            }

        }

        return keys;

    }



    /**

     * Re-checks the markers named by the given keys after a reload.

     *

     * @param checkedKeys the keys to restore

     */

    private void restoreChecks(Set<String> checkedKeys)

    {

        for (IProject project : projectsWithMarkers)

        {

            for (Marker marker : service.getMarkers(project))

            {

                if (checkedKeys.contains(project.getName() + "/" + marker.getName())) //$NON-NLS-1$

                {

                    markerTree.setChecked(marker, true);

                }

            }

        }

    }



    /**

     * Returns the names of the checked markers of one project.

     *

     * @param project the project

     * @return the checked marker names

     */

    private Set<String> checkedMarkerNames(IProject project)

    {

        Set<String> names = new TreeSet<>();

        for (Marker marker : service.getMarkers(project))

        {

            if (markerTree.getChecked(marker))

            {

                names.add(marker.getName());

            }

        }

        return names;

    }



    /**

     * Counts the objects a marker is on, honoring the search when one is active.

     *

     * @param project the project

     * @param markerName the marker name

     * @return the label suffix, either {@code (n)} or {@code (filtered/total)}

     */

    private String countLabel(IProject project, String markerName)

    {

        Set<String> objects = service.findObjectsByMarker(project, markerName);

        int total = objects.size();

        if (searchPattern == null)

        {

            return " (" + total + ")"; //$NON-NLS-1$ //$NON-NLS-2$

        }

        int filtered = 0;

        for (String fqn : objects)

        {

            if (searchPattern.matcher(simplifyFqn(fqn)).find())

            {

                filtered++;

            }

        }

        return " (" + filtered + "/" + total + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    }



    /**

     * Adds the "Open YAML File" context action to the marker tree.

     */

    private void addMarkerContextMenu()

    {

        MenuManager menuManager = new MenuManager();

        menuManager.add(new Action("Open YAML File") //$NON-NLS-1$

        {

            @Override

            public void run()

            {

                openYamlFile();

            }

        });

        markerTree.getTree().setMenu(menuManager.createContextMenu(markerTree.getTree()));

    }



    /**

     * Adds the copy context action to the results table.

     */

    private void addResultContextMenu()

    {

        MenuManager menuManager = new MenuManager();

        menuManager.add(new Action("Copy") //$NON-NLS-1$

        {

            @Override

            public void run()

            {

                copySelectedRows();

            }

        });

        Menu menu = menuManager.createContextMenu(resultTable.getTable());

        resultTable.getTable().setMenu(menu);

    }



    /**

     * Opens the marker file of the project of the current tree selection.

     */

    private void openYamlFile()

    {

        IProject project = selectedProject();

        if (project == null)

        {

            return;

        }

        IFile file = project.getFolder(MarkerKeys.SETTINGS_FOLDER).getFile(MarkerKeys.MARKERS_FILE);

        if (!file.exists())

        {

            return;

        }

        try

        {

            IWorkbenchPage page = getSite().getPage();

            IDE.openEditor(page, file);

        }

        catch (org.eclipse.ui.PartInitException e)

        {

            Activator.logError("Could not open the marker file", e); //$NON-NLS-1$

        }

    }



    /**

     * Returns the project of the current tree selection, whether a project or a marker is selected.

     *

     * @return the project, or <code>null</code>

     */

    private IProject selectedProject()

    {

        IStructuredSelection selection = markerTree.getStructuredSelection();

        Object first = selection.getFirstElement();

        if (first instanceof IProject)

        {

            return (IProject)first;

        }

        if (first instanceof Marker)

        {

            for (IProject project : projectsWithMarkers)

            {

                if (service.getMarkers(project).contains(first))

                {

                    return project;

                }

            }

        }

        return null;

    }



    /**

     * Copies the simplified FQNs of the selected result rows to the clipboard.

     */

    private void copySelectedRows()

    {

        IStructuredSelection selection = resultTable.getStructuredSelection();

        if (selection.isEmpty())

        {

            return;

        }

        StringBuilder text = new StringBuilder();

        for (Object element : selection.toList())

        {

            if (element instanceof ResultRow)

            {

                if (text.length() > 0)

                {

                    text.append(System.lineSeparator());

                }

                text.append(simplifyFqn(((ResultRow)element).fqn()));

            }

        }

        Clipboard clipboard = new Clipboard(resultTable.getControl().getDisplay());

        try

        {

            clipboard.setContents(new Object[] {text.toString()}, new Transfer[] {TextTransfer.getInstance()});

        }

        finally

        {

            clipboard.dispose();

        }

    }



    /**

     * Opens the object of the selected result row in its editor.

     */

    private void navigateToSelected()

    {

        Object first = resultTable.getStructuredSelection().getFirstElement();

        if (!(first instanceof ResultRow))

        {

            return;

        }

        ResultRow row = (ResultRow)first;

        EObject object = resolveTopObject(row.project(), row.fqn());

        if (object != null)

        {

            new OpenHelper().openEditor(object);

        }

    }



    /**

     * Resolves the top object of an FQN through the BM engine.

     *

     * @param project the project

     * @param fqn the object FQN

     * @return the resolved object, or <code>null</code>

     */

    private EObject resolveTopObject(IProject project, String fqn)

    {

        Activator activator = Activator.getDefault();

        if (activator == null)

        {

            return null;

        }

        IBmModelManager modelManager = activator.getBmModelManager();

        if (modelManager == null)

        {

            return null;

        }

        IBmModel model = modelManager.getModel(project);

        if (model == null)

        {

            return null;

        }

        String topFqn = topFqn(fqn);

        return model.executeReadonlyTask(new AbstractBmTask<EObject>("Resolve marked object") //$NON-NLS-1$

        {

            @Override

            public EObject execute(IBmTransaction transaction, IProgressMonitor monitor)

            {

                IBmObject object = transaction.getTopObjectByFqn(topFqn);

                return object instanceof EObject ? (EObject)object : null;

            }

        });

    }



    /**

     * Simplifies an FQN to its names, dropping the intermediate type segments (keeping the leading

     * type). For example {@code Catalog.Products.CatalogAttribute.Description} becomes

     * {@code Catalog.Products.Description}.

     *

     * @param fqn the FQN

     * @return the simplified form

     */

    private static String simplifyFqn(String fqn)

    {

        if (fqn == null)

        {

            return ""; //$NON-NLS-1$

        }

        String[] parts = fqn.split("\\."); //$NON-NLS-1$

        StringBuilder simplified = new StringBuilder(parts[0]);

        for (int i = 1; i < parts.length; i += 2)

        {

            simplified.append('.').append(parts[i]);

        }

        return simplified.toString();

    }



    /**

     * Returns the top FQN (first two segments) of an FQN.

     *

     * @param fqn the FQN

     * @return the top FQN

     */

    private static String topFqn(String fqn)

    {

        String[] parts = fqn.split("\\."); //$NON-NLS-1$

        if (parts.length >= 2)

        {

            return parts[0] + "." + parts[1]; //$NON-NLS-1$

        }

        return fqn;

    }



    /**

     * Returns the leading type segment of an FQN.

     *

     * @param fqn the FQN

     * @return the top type name

     */

    private static String topType(String fqn)

    {

        int dot = fqn.indexOf('.');

        return dot < 0 ? fqn : fqn.substring(0, dot);

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

            return parentElement instanceof IProject ? service.getMarkers((IProject)parentElement).toArray()

                : new Object[0];

        }



        @Override

        public Object getParent(Object element)

        {

            if (element instanceof Marker)

            {

                for (IProject project : projectsWithMarkers)

                {

                    if (service.getMarkers(project).contains(element))

                    {

                        return project;

                    }

                }

            }

            return null;

        }



        @Override

        public boolean hasChildren(Object element)

        {

            return element instanceof IProject && !service.getMarkers((IProject)element).isEmpty();

        }

    }



    /**

     * Labels project nodes with their name and marker nodes with a swatch, name and object count.

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

                Marker marker = (Marker)element;

                IProject project = projectOf(marker);

                String count = project != null ? countLabel(project, marker.getName()) : ""; //$NON-NLS-1$

                return marker.getName() + count;

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



        private IProject projectOf(Marker marker)

        {

            for (IProject project : projectsWithMarkers)

            {

                if (service.getMarkers(project).contains(marker))

                {

                    return project;

                }

            }

            return null;

        }

    }



    /**

     * Hides markers with no matching objects when the "hide empty markers" toggle is on.

     */

    private final class HideEmptyMarkersFilter

        extends ViewerFilter

    {

        @Override

        public boolean select(Viewer viewer, Object parentElement, Object element)

        {

            if (hideEmptyCheck == null || !hideEmptyCheck.getSelection() || !(element instanceof Marker))

            {

                return true;

            }

            Marker marker = (Marker)element;

            if (!(parentElement instanceof IProject))

            {

                return true;

            }

            IProject project = (IProject)parentElement;

            Set<String> objects = service.findObjectsByMarker(project, marker.getName());

            if (searchPattern == null)

            {

                return !objects.isEmpty();

            }

            for (String fqn : objects)

            {

                if (searchPattern.matcher(simplifyFqn(fqn)).find())

                {

                    return true;

                }

            }

            return false;

        }

    }

}

