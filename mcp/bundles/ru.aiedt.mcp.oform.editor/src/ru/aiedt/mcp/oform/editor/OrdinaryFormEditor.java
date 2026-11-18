/**
 * AI-EDT Ordinary Form Editor
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.oform.editor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.draw2d.*;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.ui.part.EditorPart;
import org.eclipse.ui.part.FileEditorInput;
import ru.aiedt.mcp.oform.session.*;

/** Read-only schematic viewer. Navigation never invokes session edits, history or save. */
public final class OrdinaryFormEditor extends EditorPart
{
    public static final String ID = "ru.aiedt.mcp.oform.editor.ordinaryForm";
    private FormEditingSession session;
    private FormResourceWatcher watcher;
    private Composite body;
    private org.eclipse.swt.widgets.Label status;
    private Tree tree;
    private Table properties;
    private Combo rootPages;
    private FigureCanvas canvas;
    private ScalableLayeredPane drawing;
    private FormSnapshot snapshot;
    private boolean removedTerminal;
    private final FormViewState view = new FormViewState();
    private final Map<String, TreeItem> items = new HashMap<>();
    private final Map<String, IFigure> figures = new HashMap<>();

    @Override public void init(IEditorSite site, IEditorInput input) throws PartInitException
    {
        if (!(input instanceof IFileEditorInput)) { throw new PartInitException("A workspace Form.oform is required"); }
        setSite(site); setInput(input);
        session = OpenFormSessions.open(((IFileEditorInput)input).getFile());
        updateInput();
    }
    private void updateInput()
    {
        IFile file = session.file();
        if (file == null) { return; }
        if (!file.equals(((IFileEditorInput)getEditorInput()).getFile())) { setInputWithNotify(new FileEditorInput(file)); }
        setPartName(file.getParent().getName()); setTitleToolTip(file.getFullPath().toString());
    }
    @Override public void createPartControl(Composite parent)
    {
        body = new Composite(parent, SWT.NONE);
        GridLayoutFactory.swtDefaults().applyTo(body);
        status = new org.eclipse.swt.widgets.Label(body, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(status);
        SashForm panes = new SashForm(body, SWT.HORIZONTAL);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(panes);
        tree = new Tree(panes, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        Composite center = new Composite(panes, SWT.NONE);
        GridLayoutFactory.fillDefaults().applyTo(center);
        Composite toolbar = new Composite(center, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(4).applyTo(toolbar);
        new org.eclipse.swt.widgets.Label(toolbar, SWT.NONE).setText("Root page:");
        rootPages = new Combo(toolbar, SWT.READ_ONLY);
        rootPages.addListener(SWT.Selection, e -> {
            if (snapshot != null && rootPages.getSelectionIndex() >= 0) {
                view.page("root", snapshot.pages.get(rootPages.getSelectionIndex()).name); redraw();
            }
        });
        new org.eclipse.swt.widgets.Label(toolbar, SWT.NONE).setText("Zoom:");
        Combo zoom = new Combo(toolbar, SWT.READ_ONLY);
        zoom.setItems("50%", "75%", "100%", "125%", "150%", "200%"); zoom.select(2);
        zoom.addListener(SWT.Selection, e -> drawing.setScale(new double[] {.5,.75,1,1.25,1.5,2}[zoom.getSelectionIndex()]));
        canvas = new FigureCanvas(center, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(canvas);
        drawing = new ScalableLayeredPane(); drawing.setLayoutManager(new XYLayout());
        canvas.setContents(drawing);
        properties = new Table(panes, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        properties.setHeaderVisible(true); properties.setLinesVisible(true);
        TableColumn key = new TableColumn(properties, SWT.NONE); key.setText("Property"); key.setWidth(110);
        TableColumn value = new TableColumn(properties, SWT.NONE); value.setText("Read-only value"); value.setWidth(230);
        panes.setWeights(22, 54, 24);
        tree.addListener(SWT.Selection, e -> {
            if (e.item instanceof TreeItem && ((TreeItem)e.item).getData() instanceof FormSnapshot.Element) {
                select((FormSnapshot.Element)((TreeItem)e.item).getData());
            }
        });
        watcher = new FormResourceWatcher(body.getDisplay(), session, this::refresh, this::removed);
        refresh();
    }
    private void removed()
    {
        if (body == null || body.isDisposed() || removedTerminal) { return; }
        if (FormViewState.closeOnRemoval(session.isDirty())) { getSite().getPage().closeEditor(this, false); }
        else {
            removedTerminal = true;
            status.setText("The file was removed. Unsaved shared-session changes are retained in this read-only terminal view. "
                + "Saving is unavailable; closing the last view discards this session.");
            body.layout(true, true);
        }
    }
    private void refresh()
    {
        if (body == null || body.isDisposed() || removedTerminal) { return; }
        updateInput();
        FormEditingSession.Reconciliation result = session.reconcile();
        snapshot = session.snapshot(); view.reconcile(snapshot.elements);
        items.clear(); tree.removeAll(); addTree(snapshot.elements, null);
        rootPages.removeAll();
        for (FormSnapshot.Page page : snapshot.pages) { rootPages.add(FormFigures.text(page.title, page.name)); }
        String page = view.page("root", snapshot.pages);
        for (int i = 0; i < snapshot.pages.size(); i++) { if (snapshot.pages.get(i).name.equals(page)) { rootPages.select(i); } }
        rootPages.setEnabled(!snapshot.pages.isEmpty());
        status.setText((snapshot.problem == null ? "Read " + snapshot.allElements.size() + " elements, version " + snapshot.version
            : "The form could not be read: " + snapshot.problem)
            + "; " + snapshot.unreadRecords + " records of kinds the reader does not recognise. "
            + (snapshot.versionSupported ? "" : "Not a measured version. ")
            + "Read-only schematic; system font; coordinates and DPI are not measured against 1C. Styles and tabs are not read. "
            + (result.message == null ? "" : result.message));
        redraw();
        TreeItem selected = items.get(view.selection());
        if (selected != null) { tree.setSelection(selected); tree.showItem(selected); showProperties((FormSnapshot.Element)selected.getData()); }
        else { showProperties(null); }
        body.layout(true, true);
    }
    private void addTree(List<FormSnapshot.Element> elements, TreeItem parent)
    {
        for (FormSnapshot.Element element : elements) {
            TreeItem item = parent == null ? new TreeItem(tree, SWT.NONE) : new TreeItem(parent, SWT.NONE);
            item.setText(element.kind + ": " + element.name + (element.box == null ? " (no box)" : ""));
            item.setData(element); items.put(element.id, item); addTree(element.children, item); item.setExpanded(true);
        }
    }
    private void select(FormSnapshot.Element element)
    {
        view.reveal(snapshot.elements, element.id);
        redraw();
        TreeItem item = items.get(element.id);
        if (item != null) { tree.setSelection(item); tree.showItem(item); }
        showProperties(element);
        int[] bounds = FormViewState.contentBounds(snapshot.elements, element.id, drawing.getScale());
        if (bounds != null) { canvas.scrollSmoothTo(Math.max(0, bounds[0]), Math.max(0, bounds[1])); }
    }
    private void redraw()
    {
        drawing.removeAll(); figures.clear();
        Figure form = new Figure(); form.setLayoutManager(new XYLayout());
        form.setOpaque(true); form.setBackgroundColor(ColorConstants.white); form.setBorder(new LineBorder());
        int width = snapshot.width == null ? 800 : Math.max(1, snapshot.width);
        int height = snapshot.height == null ? 600 : Math.max(1, snapshot.height);
        drawing.add(form, new Rectangle(0, 0, width, height));
        addFigures(snapshot.elements, "root", snapshot.pages, form);
        drawing.revalidate(); drawing.repaint();
    }
    private void addFigures(List<FormSnapshot.Element> elements, String panel, List<FormSnapshot.Page> pages,
        Figure parent)
    {
        for (FormSnapshot.Element element : elements) {
            if (!view.visible(panel, pages, element.page) || element.box == null) { continue; }
            int[] box = element.box;
            Figure figure = FormFigures.create(element);
            int[] bounds = FormViewState.bounds(box);
            parent.add(figure, new Rectangle(bounds[0], bounds[1], bounds[2], bounds[3]));
            figures.put(element.id, figure);
            if (java.util.Objects.equals(element.id, view.selection())) { figure.setBorder(new LineBorder(ColorConstants.blue, 2)); }
            figure.addMouseListener(new MouseListener.Stub() {
                @Override public void mousePressed(MouseEvent event) { select(element); event.consume(); }
            });
            if (element.pages.size() > 1) {
                // Navigation strip, not an assertion that the unexposed tabs flag is true.
                int x = 0;
                for (FormSnapshot.Page page : element.pages) {
                    org.eclipse.draw2d.Label tab = new org.eclipse.draw2d.Label(FormFigures.text(page.title, page.name));
                    tab.setBorder(new LineBorder(java.util.Objects.equals(page.name, view.page(element.id, element.pages))
                        ? ColorConstants.blue : ColorConstants.gray));
                    figure.add(tab, new Rectangle(x, 24, 90, 26)); x += 90;
                    tab.addMouseListener(new MouseListener.Stub() {
                        @Override public void mousePressed(MouseEvent event) { view.page(element.id, page.name); redraw(); event.consume(); }
                    });
                }
            }
            addFigures(element.children, element.id, element.pages, figure);
        }
    }
    private void row(String key, Object value)
    {
        TableItem item = new TableItem(properties, SWT.NONE); item.setText(new String[] {key, value == null ? "Not read" : String.valueOf(value)});
    }
    private void showProperties(FormSnapshot.Element element)
    {
        properties.removeAll();
        if (element == null) { row("Title", snapshot.title); row("Width", snapshot.width); row("Height", snapshot.height); return; }
        row("Name", element.name); row("Kind", element.kind); row("Id", element.id); row("Render branch", element.renderBranch);
        row("Box", element.box == null ? "No box" : java.util.Arrays.toString(element.box)); row("Page", element.page);
        row("Data", element.data); row("Value type", element.valueType);
        for (String property : List.of("caption", "tooltip", "visible", "enabled", "font", "textColor", "backColor",
            "borderColor", "readOnly", "alignment", "format", "handlers")) {
            Object value = "Not measured for this kind";
            if (element.applicableProperties.contains(property)) {
                value = "Not read";
                if (property.equals("caption")) { value = element.title; }
                if (property.equals("tooltip")) { value = element.tooltip; }
                if (property.equals("handlers")) {
                    StringBuilder text = new StringBuilder();
                    for (FormSnapshot.Handler h : element.handlers) { text.append(h.event == null ? "Unmeasured event" : h.event).append(": ").append(h.procedure).append("; "); }
                    value = text.length() == 0 ? "None read" : text;
                }
            }
            row(property, value);
        }
    }
    @Override public void setFocus() { if (tree != null && !tree.isDisposed()) { tree.setFocus(); } }
    @Override public void dispose()
    {
        if (watcher != null) { watcher.close(); watcher = null; }
        if (session != null) { OpenFormSessions.release(session); session = null; }
        super.dispose();
    }
    @Override public void doSave(IProgressMonitor monitor) { /* Stage 8, intentionally disabled. */ }
    @Override public void doSaveAs() { }
    @Override public boolean isDirty() { return false; }
    @Override public boolean isSaveAsAllowed() { return false; }
}
