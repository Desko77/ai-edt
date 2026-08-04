/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.ITreeViewerListener;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TreeExpansionEvent;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.ToolParamSettings.ParameterDef;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * The Tools tab: a checkbox tree of every tool, the presets that set it in one move, and the numbers
 * a handful of tools let you tune.
 * <p>
 * It works on edit buffers, not on the store: nothing is written until OK. The disabled set is the
 * source of truth the checkboxes are painted from - never the other way - because
 * {@link CheckboxTreeViewer} will not tick a child whose group is collapsed, so the widget cannot be
 * trusted to remember. A second buffer holds the unlisted set - the tools a preset hides from
 * <code>tools/list</code> yet leaves callable - which has no per-leaf visual yet, so an
 * unlisted-but-enabled tool still shows as checked. A change to either set restarts the server on OK;
 * changing a spinner does not.
 * </p>
 */
public class ToolsPrefTab
{
    private static final int SASH_WIDTH = 580;

    private static final int SASH_HEIGHT = 360;

    private static final String KEY_DATA = "key"; //$NON-NLS-1$

    private static final String HINT_TEXT = "Choose a tool or group to view its description."; //$NON-NLS-1$

    private final Composite control;

    private final HashSet<String> disabledTools;

    private final HashSet<String> unlistedTools;

    private final LinkedHashMap<String, Integer> pendingValues = new LinkedHashMap<>();

    private final List<Spinner> spinners = new ArrayList<>();

    private final List<Image> images = new ArrayList<>();

    private CheckboxTreeViewer treeViewer;

    private Text searchBox;

    private ToolNameFilter nameFilter;

    private Combo presetCombo;

    private Label counterLabel;

    private Composite detailPanel;

    private String selectedToolName;

    private boolean updatingChecks;

    /**
     * Builds the tab and seeds both edit buffers from the current state.
     *
     * @param parent the tab folder to build into
     */
    public ToolsPrefTab(Composite parent)
    {
        disabledTools = new HashSet<>(ToolSettingsStore.getInstance().getDisabledTools());
        unlistedTools = new HashSet<>(ToolSettingsStore.getInstance().getUnlistedTools());
        seedPendingValues();

        control = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        control.setLayout(layout);

        SashForm sash = new SashForm(control, SWT.HORIZONTAL);
        GridData sashData = new GridData(SWT.FILL, SWT.FILL, true, true);
        sashData.widthHint = SASH_WIDTH;
        sashData.heightHint = SASH_HEIGHT;
        sash.setLayoutData(sashData);

        createLeftPane(sash);
        createRightPane(sash);
        sash.setWeights(45, 55);

        refreshChecks();
        rederivePreset();
        updateCounter();
    }

    /**
     * Returns the tab's control.
     *
     * @return the composite
     */
    public Composite getControl()
    {
        return control;
    }

    /**
     * Tells whether the disabled set or the unlisted set differs from what is stored. Only these - not
     * a changed spinner - count, which is why editing a parameter and pressing OK does not restart the
     * server.
     *
     * @return <code>true</code> when either the callable or the listed set of tools has changed
     */
    public boolean hasChanges()
    {
        ToolSettingsStore store = ToolSettingsStore.getInstance();
        return !disabledTools.equals(store.getDisabledTools())
            || !unlistedTools.equals(store.getUnlistedTools());
    }

    /**
     * Writes the edit buffers to the store: the disabled set and the unlisted set, then every
     * parameter that was touched (untouched ones equal the default and so write nothing).
     */
    public void performOk()
    {
        savePendingSpinnerValues();
        ToolSettingsStore store = ToolSettingsStore.getInstance();
        store.setDisabledTools(disabledTools);
        store.setUnlistedTools(unlistedTools);

        ToolParamSettings parameters = ToolParamSettings.getInstance();
        for (Map.Entry<String, Integer> entry : pendingValues.entrySet())
        {
            // tool.<tool>.<param> - and a tool name may never contain a dot, or this would misparse.
            String[] parts = entry.getKey().split("\\.", 3); //$NON-NLS-1$
            if (parts.length == 3)
            {
                parameters.setParameterValue(parts[1], parts[2], entry.getValue());
            }
        }
    }

    /**
     * Enables every tool, resets every parameter to its default, and repaints. Nothing is saved until
     * OK.
     */
    public void performDefaults()
    {
        disabledTools.clear();
        unlistedTools.clear();
        refreshChecks();
        rederivePreset();
        updateCounter();

        ToolParamSettings parameters = ToolParamSettings.getInstance();
        for (Map.Entry<String, List<ParameterDef>> entry : parameters.getAllParameters().entrySet())
        {
            for (ParameterDef definition : entry.getValue())
            {
                pendingValues.put(ToolParamSettings.buildKey(entry.getKey(), definition.getName()),
                    definition.getDefaultValue());
            }
        }

        if (selectedToolName != null)
        {
            spinners.clear();
            showDetail(selectedToolName);
        }
    }

    /**
     * Frees the two toolbar images. The tree's group image belongs to the label provider and is freed
     * by JFace.
     */
    public void dispose()
    {
        for (Image image : images)
        {
            if (image != null && !image.isDisposed())
            {
                image.dispose();
            }
        }
        images.clear();
    }

    private void seedPendingValues()
    {
        ToolParamSettings parameters = ToolParamSettings.getInstance();
        for (Map.Entry<String, List<ParameterDef>> entry : parameters.getAllParameters().entrySet())
        {
            for (ParameterDef definition : entry.getValue())
            {
                String key = ToolParamSettings.buildKey(entry.getKey(), definition.getName());
                int value = parameters.getParameterValue(entry.getKey(), definition.getName(),
                    definition.getDefaultValue());
                pendingValues.put(key, value);
            }
        }
    }

    private void createLeftPane(Composite parent)
    {
        Composite left = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        left.setLayout(layout);

        createPresetBar(left);
        createTree(left);

        counterLabel = new Label(left, SWT.NONE);
        counterLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void createPresetBar(Composite parent)
    {
        Composite bar = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(4, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        bar.setLayout(layout);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button enableAll = toolbarButton(bar, "icons/check_all.png", "All", //$NON-NLS-1$ //$NON-NLS-2$
            "Enable all tools"); //$NON-NLS-1$
        enableAll.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            disabledTools.clear();
            unlistedTools.clear();
            refreshChecks();
            rederivePreset();
            updateCounter();
        }));

        Button disableAll = toolbarButton(bar, "icons/uncheck_all.png", "None", //$NON-NLS-1$ //$NON-NLS-2$
            "Disable all tools"); //$NON-NLS-1$
        disableAll.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            disabledTools.clear();
            unlistedTools.clear();
            for (ToolCategory group : ToolCategory.values())
            {
                disabledTools.addAll(group.getToolNames());
            }
            refreshChecks();
            rederivePreset();
            updateCounter();
        }));

        Label presetLabel = new Label(bar, SWT.NONE);
        presetLabel.setText("Preset:"); //$NON-NLS-1$

        presetCombo = new Combo(bar, SWT.READ_ONLY);
        for (ToolProfile preset : ToolProfile.values())
        {
            presetCombo.add(preset.getDisplayName());
        }
        presetCombo.setToolTipText("Choose a preset configuration, or configure tools manually"); //$NON-NLS-1$
        presetCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        presetCombo.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> applySelectedPreset()));
    }

    private void createTree(Composite parent)
    {
        searchBox = new Text(parent, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
        searchBox.setMessage("Filter tools..."); //$NON-NLS-1$
        searchBox.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        treeViewer = new CheckboxTreeViewer(parent, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        treeViewer.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        treeViewer.setContentProvider(new GroupTreeContent());
        treeViewer.setLabelProvider(new GroupTreeLabels());
        treeViewer.setInput(ToolCategory.values());

        nameFilter = new ToolNameFilter();
        treeViewer.addFilter(nameFilter);
        searchBox.addModifyListener(e -> {
            nameFilter.setQuery(searchBox.getText());
            treeViewer.refresh();
            treeViewer.expandAll();
        });

        treeViewer.addCheckStateListener(new ICheckStateListener()
        {
            @Override
            public void checkStateChanged(CheckStateChangedEvent event)
            {
                onCheckStateChanged(event);
            }
        });

        treeViewer.addTreeListener(new ITreeViewerListener()
        {
            @Override
            public void treeExpanded(TreeExpansionEvent event)
            {
                pushGroupCheckStates(event.getElement());
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event)
            {
                // Nothing: a collapsed group's children are dropped from the widget anyway.
            }
        });

        treeViewer.addSelectionChangedListener(new ISelectionChangedListener()
        {
            @Override
            public void selectionChanged(SelectionChangedEvent event)
            {
                showDetail(event.getStructuredSelection().getFirstElement());
            }
        });
    }

    private void createRightPane(Composite parent)
    {
        detailPanel = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        detailPanel.setLayout(layout);

        buildHint();
    }

    private void onCheckStateChanged(CheckStateChangedEvent event)
    {
        if (updatingChecks)
        {
            return;
        }
        Object element = event.getElement();
        boolean enabled = event.getChecked();

        if (element instanceof ToolCategory)
        {
            for (String toolName : ((ToolCategory)element).getToolNames())
            {
                setToolEnabled(toolName, enabled);
            }
        }
        else if (element instanceof String)
        {
            setToolEnabled((String)element, enabled);
        }

        refreshChecks();
        rederivePreset();
        updateCounter();
    }

    private void setToolEnabled(String toolName, boolean enabled)
    {
        if (enabled)
        {
            disabledTools.remove(toolName);
        }
        else
        {
            disabledTools.add(toolName);
        }
    }

    private void applySelectedPreset()
    {
        int index = presetCombo.getSelectionIndex();
        if (index < 0)
        {
            return;
        }
        ToolProfile preset = ToolProfile.values()[index];
        if (preset == ToolProfile.CUSTOM || preset.getDisabledTools() == null)
        {
            return;
        }
        disabledTools.clear();
        disabledTools.addAll(preset.getDisabledTools());
        unlistedTools.clear();
        unlistedTools.addAll(preset.getUnlistedTools());
        refreshChecks();
        updateCounter();
        // The combo keeps the user's pick; a shadowed duplicate snaps to its shadow on the next click.
    }

    /**
     * Paints every checkbox from the disabled set. Children are only pushed when their group is open;
     * a closed group's tool items do not exist yet and {@code setChecked} on them is ignored, which is
     * what {@link #pushGroupCheckStates} handles at the moment the group opens.
     */
    private void refreshChecks()
    {
        updatingChecks = true;
        try
        {
            for (ToolCategory group : ToolCategory.values())
            {
                boolean expanded = treeViewer.getExpandedState(group);
                boolean allEnabled = true;
                boolean anyEnabled = false;
                for (String toolName : group.getToolNames())
                {
                    boolean enabled = !disabledTools.contains(toolName);
                    if (enabled)
                    {
                        anyEnabled = true;
                    }
                    else
                    {
                        allEnabled = false;
                    }
                    if (expanded)
                    {
                        treeViewer.setChecked(toolName, enabled);
                        // TODO K1: paint the third "callable-but-unlisted" state per leaf here. A tool
                        // in unlistedTools is enabled (so it shows checked) yet hidden from tools/list,
                        // which a plain checkbox cannot express. A later GUI task adds the distinct
                        // visual (e.g. a grey "unlisted" badge); for F1 an unlisted tool shows checked.
                    }
                }
                treeViewer.setChecked(group, allEnabled || anyEnabled);
                treeViewer.setGrayed(group, anyEnabled && !allEnabled);
            }
        }
        finally
        {
            updatingChecks = false;
        }
    }

    private void pushGroupCheckStates(Object element)
    {
        if (!(element instanceof ToolCategory))
        {
            return;
        }
        updatingChecks = true;
        try
        {
            for (String toolName : ((ToolCategory)element).getToolNames())
            {
                treeViewer.setChecked(toolName, !disabledTools.contains(toolName));
            }
        }
        finally
        {
            updatingChecks = false;
        }
    }

    private void rederivePreset()
    {
        presetCombo.select(ToolProfile.matchPreset(disabledTools, unlistedTools).ordinal());
    }

    private void updateCounter()
    {
        int total = ToolCategory.getTotalToolCount();
        int enabled = 0;
        for (ToolCategory group : ToolCategory.values())
        {
            for (String toolName : group.getToolNames())
            {
                if (!disabledTools.contains(toolName))
                {
                    enabled++;
                }
            }
        }
        counterLabel.setText(enabled + " of " + total + " tools on"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Rebuilds the detail panel for the selected element: a group, a tool, or nothing.
     *
     * @param element the selection, or <code>null</code>
     */
    private void showDetail(Object element)
    {
        savePendingSpinnerValues();
        spinners.clear();
        selectedToolName = element instanceof String ? (String)element : null;

        for (Control child : detailPanel.getChildren())
        {
            child.dispose();
        }

        if (element instanceof ToolCategory)
        {
            buildGroupDetail((ToolCategory)element);
        }
        else if (element instanceof String)
        {
            buildToolDetail((String)element);
        }
        else
        {
            buildHint();
        }

        detailPanel.layout(true, true);
    }

    private void buildGroupDetail(ToolCategory group)
    {
        Label title = new Label(detailPanel, SWT.NONE);
        title.setText(group.getDisplayName());
        title.setFont(JFaceResources.getBannerFont());

        Text description = new Text(detailPanel, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.READ_ONLY
            | SWT.V_SCROLL);
        description.setText(group.getDescription());
        description.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    }

    private void buildToolDetail(String toolName)
    {
        Label title = new Label(detailPanel, SWT.NONE);
        title.setText(toolName);
        title.setFont(JFaceResources.getBannerFont());

        IMcpTool tool = McpToolCatalog.getInstance().getTool(toolName);
        Text description = new Text(detailPanel, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.READ_ONLY
            | SWT.V_SCROLL);
        description.setText(tool != null ? tool.getDescription() : ""); //$NON-NLS-1$
        description.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        List<ParameterDef> parameters =
            ToolParamSettings.getInstance().getParametersForTool(toolName);
        if (!parameters.isEmpty())
        {
            buildSettings(toolName, parameters);
        }
    }

    private void buildSettings(String toolName, List<ParameterDef> parameters)
    {
        Group settings = new Group(detailPanel, SWT.NONE);
        settings.setText("Settings"); //$NON-NLS-1$
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 8;
        layout.marginHeight = 8;
        settings.setLayout(layout);
        GridData settingsData = new GridData(SWT.FILL, SWT.TOP, true, false);
        settingsData.verticalIndent = 8;
        settings.setLayoutData(settingsData);

        for (ParameterDef definition : parameters)
        {
            Label label = new Label(settings, SWT.NONE);
            label.setText(definition.getDisplayName() + ":"); //$NON-NLS-1$
            label.setToolTipText(definition.getDescription());

            String key = ToolParamSettings.buildKey(toolName, definition.getName());
            int value = pendingValues.containsKey(key) ? pendingValues.get(key)
                : ToolParamSettings.getInstance().getParameterValue(toolName, definition.getName(),
                    definition.getDefaultValue());

            Spinner spinner = new Spinner(settings, SWT.BORDER);
            spinner.setMinimum(definition.getMinValue());
            spinner.setMaximum(definition.getMaxValue());
            spinner.setSelection(value);
            spinner.setToolTipText(definition.getDescription() + " (default: " //$NON-NLS-1$
                + definition.getDefaultValue() + ", range: " + definition.getMinValue() //$NON-NLS-1$
                + "-" + definition.getMaxValue() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            spinner.setData(KEY_DATA, key);
            spinners.add(spinner);
        }

        Button restore = new Button(settings, SWT.PUSH);
        restore.setText("Restore Defaults"); //$NON-NLS-1$
        GridData restoreData = new GridData();
        restoreData.horizontalSpan = 2;
        restoreData.verticalIndent = 5;
        restore.setLayoutData(restoreData);
        restore.addSelectionListener(SelectionListener.widgetSelectedAdapter(
            e -> restoreToolDefaults(toolName, parameters)));
    }

    /**
     * Puts one tool's parameters back to their defaults, both in the pending map and in the spinners
     * on screen. It matches spinners to parameters by position, which holds because they were added
     * in the same order.
     *
     * @param toolName   the tool
     * @param parameters its parameters
     */
    private void restoreToolDefaults(String toolName, List<ParameterDef> parameters)
    {
        for (int i = 0; i < parameters.size(); i++)
        {
            ParameterDef definition = parameters.get(i);
            String key = ToolParamSettings.buildKey(toolName, definition.getName());
            pendingValues.put(key, definition.getDefaultValue());
            if (i < spinners.size() && !spinners.get(i).isDisposed())
            {
                spinners.get(i).setSelection(definition.getDefaultValue());
            }
        }
    }

    private void buildHint()
    {
        Label hint = new Label(detailPanel, SWT.WRAP);
        hint.setText(HINT_TEXT);
        hint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    }

    private void savePendingSpinnerValues()
    {
        for (Spinner spinner : spinners)
        {
            if (!spinner.isDisposed())
            {
                Object key = spinner.getData(KEY_DATA);
                if (key instanceof String)
                {
                    pendingValues.put((String)key, spinner.getSelection());
                }
            }
        }
    }

    private Button toolbarButton(Composite parent, String iconPath, String textFallback, String tooltip)
    {
        Button button = new Button(parent, SWT.PUSH);
        button.setToolTipText(tooltip);
        ImageDescriptor descriptor = AbstractUIPlugin.imageDescriptorFromPlugin(Activator.PLUGIN_ID, iconPath);
        if (descriptor != null)
        {
            Image image = descriptor.createImage();
            images.add(image);
            button.setImage(image);
        }
        else
        {
            button.setText(textFallback);
        }
        return button;
    }

    /**
     * The tree's shape: groups at the root, their tool names beneath.
     */
    private static final class GroupTreeContent
        implements ITreeContentProvider
    {
        @Override
        public Object[] getElements(Object inputElement)
        {
            return (Object[])inputElement;
        }

        @Override
        public Object[] getChildren(Object parentElement)
        {
            if (parentElement instanceof ToolCategory)
            {
                return ((ToolCategory)parentElement).getToolNames().toArray();
            }
            return new Object[0];
        }

        @Override
        public Object getParent(Object element)
        {
            if (element instanceof String)
            {
                return ToolCategory.getGroupForTool((String)element);
            }
            return null;
        }

        @Override
        public boolean hasChildren(Object element)
        {
            return element instanceof ToolCategory;
        }
    }

    /**
     * Labels: a group shows its name and how many tools it holds, with an icon; a tool shows its bare
     * name, with a slate badge when it is callable-but-unlisted (hidden from {@code tools/list} yet
     * still answering a call). Non-static so it can read the unlisted edit buffer. The images are made
     * once and disposed when JFace disposes the provider.
     */
    private final class GroupTreeLabels
        extends LabelProvider
    {
        private Image categoryImage;

        private Image unlistedBadge;

        @Override
        public String getText(Object element)
        {
            if (element instanceof ToolCategory)
            {
                ToolCategory group = (ToolCategory)element;
                return group.getDisplayName() + " - " + group.getToolNames().size(); //$NON-NLS-1$
            }
            return String.valueOf(element);
        }

        @Override
        public Image getImage(Object element)
        {
            if (element instanceof ToolCategory)
            {
                if (categoryImage == null)
                {
                    ImageDescriptor descriptor = AbstractUIPlugin.imageDescriptorFromPlugin(
                        Activator.PLUGIN_ID, "icons/category.png"); //$NON-NLS-1$
                    if (descriptor != null)
                    {
                        categoryImage = descriptor.createImage();
                    }
                }
                return categoryImage;
            }
            if (element instanceof String && unlistedTools.contains(element))
            {
                if (unlistedBadge == null)
                {
                    unlistedBadge = buildUnlistedBadge();
                }
                return unlistedBadge;
            }
            return null;
        }

        /**
         * Builds the small slate square that marks a tool hidden from the catalogue but still callable.
         * Same visual idiom as the status-bar indicator, so the two read as one design language.
         *
         * @return the badge image
         */
        private Image buildUnlistedBadge()
        {
            Display display = treeViewer.getControl().getDisplay();
            PaletteData palette = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
            ImageData data = new ImageData(8, 8, 24, palette);
            int fillPixel = palette.getPixel(new RGB(120, 130, 145));
            int borderPixel = palette.getPixel(new RGB(80, 88, 100));
            for (int y = 0; y < 8; y++)
            {
                for (int x = 0; x < 8; x++)
                {
                    boolean edge = x == 0 || y == 0 || x == 7 || y == 7;
                    data.setPixel(x, y, edge ? borderPixel : fillPixel);
                }
            }
            return new Image(display, data);
        }

        @Override
        public void dispose()
        {
            if (categoryImage != null && !categoryImage.isDisposed())
            {
                categoryImage.dispose();
            }
            categoryImage = null;
            if (unlistedBadge != null && !unlistedBadge.isDisposed())
            {
                unlistedBadge.dispose();
            }
            unlistedBadge = null;
            super.dispose();
        }
    }

    /**
     * Narrows the tree to tools whose name contains the query (case-insensitive). A group is shown when
     * any of its members matches; an empty query shows everything. Re-applied on every keystroke.
     */
    private static final class ToolNameFilter
        extends ViewerFilter
    {
        private String query = ""; //$NON-NLS-1$

        void setQuery(String text)
        {
            query = text == null ? "" : text.toLowerCase().trim(); //$NON-NLS-1$
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            if (query.isEmpty())
            {
                return true;
            }
            if (element instanceof String)
            {
                return ((String)element).toLowerCase().contains(query);
            }
            if (element instanceof ToolCategory)
            {
                for (String toolName : ((ToolCategory)element).getToolNames())
                {
                    if (toolName.toLowerCase().contains(query))
                    {
                        return true;
                    }
                }
                return false;
            }
            return true;
        }
    }
}
