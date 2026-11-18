/**
 * AI-EDT Ordinary Form Editor
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.oform.editor;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.draw2d.ColorConstants;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.FigureCanvas;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.LineBorder;
import org.eclipse.draw2d.MouseEvent;
import org.eclipse.draw2d.MouseListener;
import org.eclipse.draw2d.MouseMotionListener;
import org.eclipse.draw2d.ScalableLayeredPane;
import org.eclipse.draw2d.XYLayout;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.ISaveablePart2;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.EditorPart;
import org.eclipse.ui.part.FileEditorInput;

import ru.aiedt.mcp.oform.session.FormEditingSession;
import ru.aiedt.mcp.oform.session.FormGeometry;
import ru.aiedt.mcp.oform.session.FormSnapshot;
import ru.aiedt.mcp.oform.session.FormViewState;
import ru.aiedt.mcp.oform.session.OpenFormSessions;

/**
 * The ordinary form editor: the element tree, the scrollable and zoomable schematic and the
 * property table over one shared {@link FormEditingSession}. Moving and resizing an element inside
 * its current parent and page, editing the caption, the tooltip or the form title, undo and redo,
 * save, dirty state and the conflict decision all go through the session; the editor keeps no
 * undo stack and no text of its own. A foreign write that conflicts with the draft keeps the
 * draft and shows it until the user discards it explicitly; a removed container with shared
 * unsaved changes stays as a terminal read-only view.
 */
public final class OrdinaryFormEditor extends EditorPart implements ISaveablePart2
{
    public static final String ID = "ru.aiedt.mcp.oform.editor.ordinaryForm";

    /** How near the lower-right corner of the selected element a press starts a resize. */
    private static final int RESIZE_CORNER = 8;

    private FormEditingSession session;
    private FormResourceWatcher watcher;
    private FormSessionListener sessionListener;
    private Thread uiThread;
    private volatile org.eclipse.swt.widgets.Display display;
    private volatile String saveFailure;
    private Composite body;
    private org.eclipse.swt.widgets.Label status;
    private Tree tree;
    private Table properties;
    private Combo rootPages;
    private Button undoButton;
    private Button redoButton;
    private Button saveButton;
    private Button discardButton;
    private FigureCanvas canvas;
    private ScalableLayeredPane drawing;
    private FormSnapshot snapshot;
    private boolean removedTerminal;
    private boolean conflicted;
    private boolean disposed;
    private String notice;
    private final FormViewState view = new FormViewState();
    private final Map<String, TreeItem> items = new HashMap<>();
    private final Map<String, IFigure> figures = new HashMap<>();

    private FormSnapshot.Element dragElement;
    private IFigure dragFigure;
    private org.eclipse.draw2d.geometry.Point dragStart;
    private int[] dragBox;
    private int[] dragTarget;
    private boolean resizing;

    @Override public void init(IEditorSite site, IEditorInput input) throws PartInitException
    {
        uiThread = Thread.currentThread();
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
        uiThread = Thread.currentThread();
        body = new Composite(parent, SWT.NONE);
        display = body.getDisplay();
        GridLayoutFactory.swtDefaults().applyTo(body);
        status = new org.eclipse.swt.widgets.Label(body, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(status);
        SashForm panes = new SashForm(body, SWT.HORIZONTAL);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(panes);
        tree = new Tree(panes, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        Composite center = new Composite(panes, SWT.NONE);
        GridLayoutFactory.fillDefaults().applyTo(center);
        Composite toolbar = new Composite(center, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(8).applyTo(toolbar);
        undoButton = new Button(toolbar, SWT.PUSH); undoButton.setText("Undo"); //$NON-NLS-1$
        undoButton.addListener(SWT.Selection, e -> undo());
        redoButton = new Button(toolbar, SWT.PUSH); redoButton.setText("Redo"); //$NON-NLS-1$
        redoButton.addListener(SWT.Selection, e -> redo());
        saveButton = new Button(toolbar, SWT.PUSH); saveButton.setText("Save"); //$NON-NLS-1$
        saveButton.addListener(SWT.Selection, e -> doSave(null));
        discardButton = new Button(toolbar, SWT.PUSH); discardButton.setText("Discard my changes"); //$NON-NLS-1$
        discardButton.addListener(SWT.Selection, e -> discard());
        new org.eclipse.swt.widgets.Label(toolbar, SWT.NONE).setText("Root page:"); //$NON-NLS-1$
        rootPages = new Combo(toolbar, SWT.READ_ONLY);
        rootPages.addListener(SWT.Selection, e -> {
            if (snapshot != null && rootPages.getSelectionIndex() >= 0) {
                view.page("root", snapshot.pages.get(rootPages.getSelectionIndex()).name); redraw(); //$NON-NLS-1$
            }
        });
        new org.eclipse.swt.widgets.Label(toolbar, SWT.NONE).setText("Zoom:"); //$NON-NLS-1$
        Combo zoom = new Combo(toolbar, SWT.READ_ONLY);
        zoom.setItems("50%", "75%", "100%", "125%", "150%", "200%"); zoom.select(2); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        zoom.addListener(SWT.Selection, e -> drawing.setScale(new double[] {.5,.75,1,1.25,1.5,2}[zoom.getSelectionIndex()]));
        canvas = new FigureCanvas(center, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(canvas);
        drawing = new ScalableLayeredPane(); drawing.setLayoutManager(new XYLayout());
        canvas.setContents(drawing);
        properties = new Table(panes, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        properties.setHeaderVisible(true); properties.setLinesVisible(true);
        TableColumn key = new TableColumn(properties, SWT.NONE); key.setText("Property"); key.setWidth(110); //$NON-NLS-1$
        TableColumn value = new TableColumn(properties, SWT.NONE); value.setText("Value"); value.setWidth(230); //$NON-NLS-1$
        properties.addListener(SWT.MouseDoubleClick, e -> editSelectedProperty());
        org.eclipse.swt.events.KeyListener keys = new org.eclipse.swt.events.KeyListener() {
            @Override public void keyPressed(org.eclipse.swt.events.KeyEvent event) {
                if ((event.stateMask & SWT.CTRL) == 0) { return; }
                if (event.keyCode == 'z' || event.keyCode == 'Z') { undo(); event.doit = false; }
                else if (event.keyCode == 'y' || event.keyCode == 'Y') { redo(); event.doit = false; }
            }
            @Override public void keyReleased(org.eclipse.swt.events.KeyEvent event) { }
        };
        tree.addKeyListener(keys); properties.addKeyListener(keys); canvas.addKeyListener(keys);
        panes.setWeights(22, 54, 24);
        tree.addListener(SWT.Selection, e -> {
            if (e.item instanceof TreeItem && ((TreeItem)e.item).getData() instanceof FormSnapshot.Element) {
                select((FormSnapshot.Element)((TreeItem)e.item).getData());
            }
        });
        watcher = new FormResourceWatcher(body.getDisplay(), session, this::refresh, this::removed);
        sessionListener = new FormSessionListener(body.getDisplay(), session, this::refresh);
        refresh();
    }

    private void removed()
    {
        if (disposed || session == null || body == null || body.isDisposed() || removedTerminal) { return; }
        if (FormViewState.closeOnRemoval(session.isDirty())) { getSite().getPage().closeEditor(this, false); }
        else {
            removedTerminal = true;
            status.setText("The file was removed. Unsaved shared-session changes are retained in this read-only terminal view. " //$NON-NLS-1$
                + "Saving is unavailable; closing the last view discards this session."); //$NON-NLS-1$
            updateActions();
            fireDirty();
            body.layout(true, true);
        }
    }

    private void refresh()
    {
        if (disposed || session == null || body == null || body.isDisposed() || removedTerminal) { return; }
        updateInput();
        FormEditingSession.Reconciliation result = session.settle();
        conflicted = FormEditingSession.Reconciliation.CONFLICT.equals(result.outcome);
        if (conflicted) { result = null; }
        snapshot = session.snapshot(); view.reconcile(snapshot.elements);
        items.clear(); tree.removeAll(); addTree(snapshot.elements, null);
        rootPages.removeAll();
        for (FormSnapshot.Page page : snapshot.pages) { rootPages.add(FormFigures.text(page.title, page.name)); }
        String page = view.page("root", snapshot.pages); //$NON-NLS-1$
        for (int i = 0; i < snapshot.pages.size(); i++) { if (snapshot.pages.get(i).name.equals(page)) { rootPages.select(i); } }
        rootPages.setEnabled(!snapshot.pages.isEmpty());
        String read = snapshot.problem == null ? "Read " + snapshot.allElements.size() + " elements, version " + snapshot.version //$NON-NLS-1$ //$NON-NLS-2$
            : "The form could not be read: " + snapshot.problem; //$NON-NLS-1$
        String line;
        if (conflicted) {
            line = "The form changed outside the editor. Your unsaved changes are kept; Save is refused. " //$NON-NLS-1$
                + "Use \"Discard my changes\" to take the file on disk, or close this editor to drop the draft."; //$NON-NLS-1$
        } else {
            line = read + "; " + snapshot.unreadRecords + " records of kinds the reader does not recognise. " //$NON-NLS-1$ //$NON-NLS-2$
                + (snapshot.versionSupported ? "" : "Not a measured version. ") //$NON-NLS-1$ //$NON-NLS-2$
                + "Drag a selected element to move it; drag its lower-right corner to resize. Double-click caption or tooltip to edit it. " //$NON-NLS-1$
                + "Styles and tabs are not read." //$NON-NLS-1$
                + (result != null && result.message != null ? " " + result.message : ""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (notice != null) { line = notice + " " + line; notice = null; } //$NON-NLS-1$
        status.setText(line);
        redraw();
        TreeItem selected = items.get(view.selection());
        if (selected != null) { tree.setSelection(selected); tree.showItem(selected); showProperties((FormSnapshot.Element)selected.getData()); }
        else { showProperties(null); }
        updateActions();
        fireDirty();
        body.layout(true, true);
    }

    private void updateActions()
    {
        if (body == null || body.isDisposed() || undoButton == null || undoButton.isDisposed()) { return; }
        boolean editable = canEdit();
        undoButton.setEnabled(editable);
        redoButton.setEnabled(editable);
        saveButton.setEnabled(editable && session.isDirty());
        discardButton.setEnabled(conflicted);
    }

    private boolean canEdit()
    {
        return session != null && !disposed && !removedTerminal && !conflicted && !session.readOnly();
    }

    private void undo()
    {
        if (!canEdit()) { return; }
        FormEditingSession.Reversal reversal = session.undo();
        if (!reversal.done && reversal.message != null) { note(reversal.message); }
        refresh();
    }

    private void redo()
    {
        if (!canEdit()) { return; }
        FormEditingSession.Reversal reversal = session.redo();
        if (!reversal.done && reversal.message != null) { note(reversal.message); }
        refresh();
    }

    private void discard()
    {
        if (body == null || body.isDisposed() || !conflicted) { return; }
        if (!org.eclipse.jface.dialogs.MessageDialog.openConfirm(body.getShell(), "Discard my changes", //$NON-NLS-1$
            "The file changed outside the editor. Discard your unsaved changes and take the file on disk?")) { return; } //$NON-NLS-1$
        session.reconcile();
        conflicted = false;
        refresh();
    }

    private void addTree(List<FormSnapshot.Element> elements, TreeItem parent)
    {
        for (FormSnapshot.Element element : elements) {
            TreeItem item = parent == null ? new TreeItem(tree, SWT.NONE) : new TreeItem(parent, SWT.NONE);
            item.setText(element.kind + ": " + element.name + (element.box == null ? " (no box)" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
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
        addFigures(snapshot.elements, "root", snapshot.pages, form); //$NON-NLS-1$
        drawing.revalidate(); drawing.repaint();
    }

    private void addFigures(List<FormSnapshot.Element> elements, String panel, List<FormSnapshot.Page> pages,
        Figure parent)
    {
        for (FormSnapshot.Element element : elements) {
            if (!view.visible(panel, pages, element.page) || element.box == null) { continue; }
            Figure figure = FormFigures.create(element);
            int[] bounds = FormViewState.bounds(element.box);
            parent.add(figure, new Rectangle(bounds[0], bounds[1], bounds[2], bounds[3]));
            figures.put(element.id, figure);
            if (java.util.Objects.equals(element.id, view.selection())) { figure.setBorder(new LineBorder(ColorConstants.blue, 2)); }
            figure.addMouseListener(new MouseListener.Stub() {
                @Override public void mousePressed(MouseEvent event) {
                    if (!java.util.Objects.equals(element.id, view.selection())) { select(element); event.consume(); return; }
                    beginDrag(element, figure, event); event.consume();
                }
                @Override public void mouseReleased(MouseEvent event) { endDrag(); event.consume(); }
            });
            figure.addMouseMotionListener(new MouseMotionListener.Stub() {
                @Override public void mouseDragged(MouseEvent event) { drag(event); event.consume(); }
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

    private void beginDrag(FormSnapshot.Element element, Figure figure, MouseEvent event)
    {
        if (!canEdit() || element.name == null) { return; }
        Rectangle bounds = figure.getBounds();
        resizing = event.x >= bounds.width - RESIZE_CORNER && event.y >= bounds.height - RESIZE_CORNER;
        dragElement = element;
        dragFigure = figure;
        dragStart = absolute(event);
        dragBox = element.box.clone();
        dragTarget = dragBox;
    }

    private void drag(MouseEvent event)
    {
        if (dragElement == null || dragFigure == null || dragStart == null || !canEdit()) { return; }
        double scale = drawing.getScale();
        if (!(scale > 0)) { return; }
        org.eclipse.draw2d.geometry.Point now = absolute(event);
        int dx = now.x - dragStart.x;
        int dy = now.y - dragStart.y;
        dragTarget = resizing ? FormGeometry.resized(dragBox, dx, dy, scale)
            : FormGeometry.moved(dragBox, dx, dy, scale);
        int[] bounds = FormViewState.bounds(dragTarget);
        dragFigure.setBounds(new Rectangle(bounds[0], bounds[1], bounds[2], bounds[3]));
    }

    /** The pointer in LightweightSystem pixels, where the scale has already been applied. */
    private org.eclipse.draw2d.geometry.Point absolute(MouseEvent event)
    {
        org.eclipse.draw2d.geometry.Point point = event.getLocation().getCopy();
        dragFigure.translateToAbsolute(point);
        return point;
    }

    private void endDrag()
    {
        if (dragElement == null) { return; }
        FormSnapshot.Element element = dragElement;
        int[] target = dragTarget;
        clearDrag();
        if (target == null || element.name == null || java.util.Arrays.equals(target, element.box)) { return; }
        Map<String, String> params = new HashMap<>();
        params.put("property", "box"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("elementName", element.name); //$NON-NLS-1$
        params.put("left", Integer.toString(target[0])); //$NON-NLS-1$
        params.put("top", Integer.toString(target[1])); //$NON-NLS-1$
        params.put("right", Integer.toString(target[2])); //$NON-NLS-1$
        params.put("bottom", Integer.toString(target[3])); //$NON-NLS-1$
        FormEditingSession.Edit result = session.edit("set_property", params); //$NON-NLS-1$
        if (!result.done && result.message != null) { note(result.message); }
        refresh();
    }

    private void clearDrag()
    {
        dragElement = null; dragFigure = null; dragStart = null; dragBox = null; dragTarget = null; resizing = false;
    }

    private void editSelectedProperty()
    {
        if (!canEdit() || properties.getSelectionCount() == 0) { return; }
        String key = properties.getSelection()[0].getText(0);
        FormSnapshot.Element element = selectedElement();
        String property;
        String elementName = null;
        String current;
        if (element == null && "Title".equals(key)) { //$NON-NLS-1$
            property = "title"; current = FormFigures.text(snapshot.title, ""); //$NON-NLS-1$ //$NON-NLS-2$
        } else if (element != null && "caption".equals(key)) { //$NON-NLS-1$
            property = "caption"; elementName = element.name; current = FormFigures.text(element.title, ""); //$NON-NLS-1$ //$NON-NLS-2$
        } else if (element != null && "tooltip".equals(key)) { //$NON-NLS-1$
            property = "tooltip"; elementName = element.name; current = FormFigures.text(element.tooltip, ""); //$NON-NLS-1$ //$NON-NLS-2$
        } else {
            return;
        }
        InputDialog dialog = new InputDialog(getSite().getShell(), "Edit " + key, //$NON-NLS-1$
            "The new value; an empty value clears it.", current, null); //$NON-NLS-1$
        if (dialog.open() != Window.OK) { return; }
        Map<String, String> params = new HashMap<>();
        params.put("property", property); //$NON-NLS-1$
        if (elementName != null) { params.put("elementName", elementName); } //$NON-NLS-1$
        params.put("value", dialog.getValue()); //$NON-NLS-1$
        FormEditingSession.Edit result = session.edit("set_property", params); //$NON-NLS-1$
        if (!result.done && result.message != null) { note(result.message); }
        refresh();
    }

    private FormSnapshot.Element selectedElement()
    {
        if (snapshot == null || view.selection() == null) { return null; }
        for (FormSnapshot.Element element : snapshot.allElements) {
            if (java.util.Objects.equals(element.id, view.selection())) { return element; }
        }
        return null;
    }

    private void row(String key, Object value)
    {
        TableItem item = new TableItem(properties, SWT.NONE); item.setText(new String[] {key, value == null ? "Not read" : String.valueOf(value)}); //$NON-NLS-1$
    }

    private void showProperties(FormSnapshot.Element element)
    {
        properties.removeAll();
        if (element == null) { row("Title", snapshot.title); row("Width", snapshot.width); row("Height", snapshot.height); return; } //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        row("Name", element.name); row("Kind", element.kind); row("Id", element.id); row("Render branch", element.renderBranch); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        row("Box", element.box == null ? "No box" : java.util.Arrays.toString(element.box)); row("Page", element.page); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        row("Data", element.data); row("Value type", element.valueType); //$NON-NLS-1$ //$NON-NLS-2$
        for (String property : List.of("caption", "tooltip", "visible", "enabled", "font", "textColor", "backColor", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "borderColor", "readOnly", "alignment", "format", "handlers")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            Object value = "Not measured for this kind"; //$NON-NLS-1$
            if (element.applicableProperties.contains(property)) {
                value = "Not read"; //$NON-NLS-1$
                if (property.equals("caption")) { value = element.title; } //$NON-NLS-1$
                if (property.equals("tooltip")) { value = element.tooltip; } //$NON-NLS-1$
                if (property.equals("handlers")) { //$NON-NLS-1$
                    StringBuilder text = new StringBuilder();
                    for (FormSnapshot.Handler h : element.handlers) { text.append(h.event == null ? "Unmeasured event" : h.event).append(": ").append(h.procedure).append("; "); } //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    value = text.length() == 0 ? "None read" : text; //$NON-NLS-1$
                }
            }
            row(property, value);
        }
    }

    @Override public void setFocus() { if (tree != null && !tree.isDisposed()) { tree.setFocus(); } }

    /**
     * A conflict must not close the editor silently, and a dirty editor saves to completion before
     * it closes: the progress service forks the save and keeps dispatching UI events while this
     * caller waits, so the UI never waits on the workspace rule itself.
     */
    @Override public int promptToSaveOnClose()
    {
        if (session == null || !isDirty()) { return ISaveablePart2.NO; }
        if (!conflicted && session.hasConflict()) { conflicted = true; updateActions(); }
        if (conflicted) {
            org.eclipse.jface.dialogs.MessageDialog.openError(getSite().getShell(), "Unsaved changes are kept", //$NON-NLS-1$
                "The file changed outside the editor. Your unsaved changes are kept here. " //$NON-NLS-1$
                    + "Discard them deliberately or cancel closing."); //$NON-NLS-1$
            return ISaveablePart2.CANCEL;
        }
        org.eclipse.jface.dialogs.MessageDialog dialog =
            new org.eclipse.jface.dialogs.MessageDialog(getSite().getShell(), "Unsaved changes", null, //$NON-NLS-1$
                "Save your changes before closing?", org.eclipse.jface.dialogs.MessageDialog.QUESTION, //$NON-NLS-1$
                new String[] { "Save", "Discard", "Cancel" }, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int choice = dialog.open();
        if (choice == 1) { return ISaveablePart2.NO; }
        if (choice == 0) {
            doSave(null);
            if (!isDirty()) { return ISaveablePart2.NO; }
            if (conflicted) {
                org.eclipse.jface.dialogs.MessageDialog.openError(getSite().getShell(), "Unsaved changes are kept", //$NON-NLS-1$
                    "The file changed outside the editor. Your unsaved changes are kept here."); //$NON-NLS-1$
            }
        }
        return ISaveablePart2.CANCEL;
    }

    @Override public void dispose()
    {
        disposed = true;
        clearDrag();
        if (sessionListener != null) { sessionListener.close(); sessionListener = null; }
        if (watcher != null) { watcher.close(); watcher = null; }
        if (session != null) { OpenFormSessions.release(session); session = null; }
        super.dispose();
    }

    /**
     * Saves to completion for the caller. On the UI thread the progress service forks the save and
     * dispatches events until it finishes; a caller that is not the UI thread runs the save on its
     * own thread and never touches SWT. The supplied monitor is propagated, and its cancellation
     * (like the service's own) stops the save before its commit point.
     */
    @Override public void doSave(IProgressMonitor monitor)
    {
        if (session == null || disposed || removedTerminal) { return; }
        if (Thread.currentThread() != uiThread) {
            FormEditingSession.Save save;
            try {
                save = new FormSaveOperation(session, monitor).runDirectly();
            } catch (RuntimeException e) {
                saveFailure = "The save failed: " + e.getMessage(); //$NON-NLS-1$
                save = null;
            }
            org.eclipse.swt.widgets.Display target = display;
            FormEditingSession.Save answer = save;
            if (target != null) {
                try {
                    target.asyncExec(() -> applySaveResult(answer));
                } catch (org.eclipse.swt.SWTException e) {
                    // the display went away while the save was running
                }
            }
            return;
        }
        if (!session.isDirty()) { refresh(); return; }
        applySaveResult(saveOnUiThread(monitor));
    }

    /** Shows what the finished save did; runs on the UI thread. */
    private void applySaveResult(FormEditingSession.Save save)
    {
        if (disposed || body == null || body.isDisposed() || removedTerminal) { return; }
        if (save == null) {
            note(saveFailure != null ? saveFailure : "The save failed."); //$NON-NLS-1$
        } else if (save.written) {
            note("Saved."); //$NON-NLS-1$
        } else if (FormEditingSession.Reconciliation.CONFLICT.equals(save.reconciliation)) {
            conflicted = true;
        } else if (FormEditingSession.CANCELLED.equals(save.reconciliation)) {
            note("Save cancelled before the file was written; a write already started completes."); //$NON-NLS-1$
        } else if (save.reconciliation != null) {
            note(save.reconciliation);
        }
        saveFailure = null;
        refresh();
    }

    /** Runs the save through the progress service and answers what it did, after it finished. */
    private FormEditingSession.Save saveOnUiThread(IProgressMonitor caller)
    {
        if (caller != null && caller.isCanceled()) {
            saveFailure = "Save cancelled before the file was written."; //$NON-NLS-1$
            return null;
        }
        FormSaveOperation operation = new FormSaveOperation(session, caller);
        try
        {
            PlatformUI.getWorkbench().getProgressService().run(true, true, operation);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            saveFailure = "The save failed: " + (cause == null ? e.getMessage() : cause.getMessage()); //$NON-NLS-1$
            return null;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            saveFailure = "Save interrupted."; //$NON-NLS-1$
            return null;
        }
        catch (RuntimeException e)
        {
            saveFailure = "The save failed: " + e.getMessage(); //$NON-NLS-1$
            return null;
        }
        return operation.save();
    }

    @Override public void doSaveAs() { }

    @Override public boolean isDirty()
    {
        return session != null && !removedTerminal && !disposed && session.isDirty();
    }

    @Override public boolean isSaveAsAllowed() { return false; }

    private void fireDirty()
    {
        if (body != null && !body.isDisposed()) { firePropertyChange(IEditorPart.PROP_DIRTY); }
    }

    private void note(String text)
    {
        notice = text;
        if (status != null && !status.isDisposed()) { status.setText(text); }
    }
}
