/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Drives EDT's form editor so that a 1C form can be handed back as a picture.
 * <p>
 * There is no API for this. EDT renders a form in its WYSIWYG editor and keeps the result to itself,
 * so the way to a PNG runs through the editor's internals: open the form, make sure EDT is rendering
 * into an offscreen buffer, wait for the canvas to exist, and then ask the render representation for
 * the image it already has. When that cannot be had, the control is asked to paint itself into an
 * image instead - lower fidelity, but a picture.
 * </p>
 * <p>
 * Unlike the breakpoint classes, everything here is in packages this bundle imports, so plain
 * {@link Class#forName(String)} reaches it; the reflection is only needed because the fields and
 * methods are private, not because the classes are hidden.
 * </p>
 * <p>
 * All of it runs on the SWT display thread, and it blocks that thread in slices while it waits - it
 * pumps the event queue by hand so the editor can finish building while we stand on its foot. The UI
 * does freeze for a moment. That is the price of the screenshot, and the sleeps are not padding: take
 * them out and the capture comes back empty or half-drawn.
 * </p>
 */
public final class EditorImageCapture
{
    private static final String FORM_EDITOR_CLASS = "com._1c.g5.v8.dt.form.ui.editor.FormEditor"; //$NON-NLS-1$
    private static final String FORM_EDITOR_ID = "com._1c.g5.v8.dt.form.ui.formEditor"; //$NON-NLS-1$

    /** The editor's WYSIWYG tab. The other tabs hold the model tree, which is not what we are after. */
    private static final String FORM_EDITOR_MAIN_PAGE = "editors.form.pages.main"; //$NON-NLS-1$

    private static final String FIELD_WYSIWYG_VIEWER = "wysiwygViewer"; //$NON-NLS-1$
    private static final String FIELD_WYSIWYG_REPRESENTATION = "wysiwygRepresentation"; //$NON-NLS-1$

    private static final String NATIVE_RENDER_SERVICE = "com._1c.g5.v8.dt.form.layout.service.NativeRenderService"; //$NON-NLS-1$
    private static final String BUFFERED_RENDER_FIELD = "NATIVE_FORM_BUFFERED_LAYOUT_RENDER"; //$NON-NLS-1$
    private static final String BUFFERED_RENDER_PROPERTY = "nativeFormBufferedLayoutRender"; //$NON-NLS-1$

    private static final String FORM_MODEL_CLASS = "com._1c.g5.v8.dt.form.model.Form"; //$NON-NLS-1$
    private static final String FORM_GROUP_CLASS = "com._1c.g5.v8.dt.form.model.FormGroup"; //$NON-NLS-1$
    private static final String FORM_ITEM_CONTAINER_CLASS = "com._1c.g5.v8.dt.form.model.FormItemContainer"; //$NON-NLS-1$
    private static final String PAGE_GROUP_EXT_INFO_CLASS = "com._1c.g5.v8.dt.form.model.PageGroupExtInfo"; //$NON-NLS-1$
    private static final String NAMED_ELEMENT_CLASS = "com._1c.g5.v8.dt.mcore.NamedElement"; //$NON-NLS-1$

    /** The rendered widget that owns the page tabs. Matched by simple name, guarded by package. */
    private static final String TAB_CONTROL_NAME = "TabControl"; //$NON-NLS-1$
    private static final String FORM_PACKAGE = "com._1c.g5.v8.dt.form."; //$NON-NLS-1$

    /** Where the Form model might be hiding, in the order most likely to be the one being rendered. */
    private static final String[] FORM_FIELDS = {"form", "formModel", "model", "rootForm"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    private static final String[] FORM_GETTERS = {"getForm", "getModel", "getRootForm"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final int PAGE_ATTEMPTS = 15;
    private static final long PAGE_INTERVAL_MS = 500;
    private static final int REBUILD_STEPS = 5;
    private static final long REBUILD_INTERVAL_MS = 200;
    private static final int SETTLE_STEPS = 3;
    private static final long SETTLE_INTERVAL_MS = 100;
    private static final int CONTROL_ATTEMPTS = 5;
    private static final long CONTROL_INTERVAL_MS = 200;
    private static final int MAX_PARENT_HOPS = 100;

    private EditorImageCapture()
    {
        // utility
    }

    /**
     * Makes EDT render forms into an offscreen buffer, which is the only mode a form can be read out of.
     * <p>
     * Two moves, because one is not enough. The system property is read when EDT's render service is
     * first loaded, so setting it now does nothing at all if that has already happened - which, by the
     * time an agent asks for a screenshot, it has. Hence the second move: the flag the service actually
     * consults is overwritten in place. Setting the property still matters for the case where the
     * service has not been loaded yet.
     * </p>
     * <p>
     * Best effort throughout. If the flag cannot be turned, the warning says how to start EDT with it
     * already on.
     * </p>
     */
    public static void ensureBufferedNativeRenderMode()
    {
        try
        {
            System.setProperty(BUFFERED_RENDER_PROPERTY, "true"); //$NON-NLS-1$

            Class<?> service = Class.forName(NATIVE_RENDER_SERVICE);
            boolean nativeRender = readFlag(service, "isNativeRender"); //$NON-NLS-1$
            boolean buffered = readFlag(service, "isBufferedRender"); //$NON-NLS-1$

            if (!nativeRender || buffered)
            {
                return;
            }

            try
            {
                Field flag = service.getDeclaredField(BUFFERED_RENDER_FIELD);
                flag.setAccessible(true);
                flag.setBoolean(null, true);
            }
            catch (Throwable t)
            {
                // A static final the JDK will not let us assign. There is one other way in.
                ReflectionAccess.forceStaticFinalBoolean(service, BUFFERED_RENDER_FIELD, true);
            }

            if (!readFlag(service, "isBufferedRender")) //$NON-NLS-1$
            {
                Activator.logWarning(
                    "EDT is not buffering form renders, so screenshots will come out lower fidelity. " //$NON-NLS-1$
                        + "Restart EDT with the VM option -D" + BUFFERED_RENDER_PROPERTY + "=true"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (Throwable t)
        {
            Activator.logWarning("Could not switch EDT to buffered native form render: " + t.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Opens a form in EDT's form editor and brings its WYSIWYG page up.
     * <p>
     * An editor that is already open for this form is closed first, without saving. That looks wasteful
     * and is not: the render mode is chosen when the editor is built, and an editor built before we
     * turned buffering on will never give up an image.
     * </p>
     * <p>
     * Must run on the UI thread.
     * </p>
     *
     * @param projectName the project holding the form
     * @param formPath the form, as {@code Catalog.Products.Forms.ItemForm} or {@code CommonForm.MyForm}
     * @return <code>null</code> when the form is open, or a ready-to-return JSON error body
     */
    public static String openAndActivateForm(String projectName, String formPath)
    {
        try
        {
            String filePath = MetadataPathMapper.resolveFormFilePath(formPath);
            if (filePath == null)
            {
                return ToolResult
                    .error("Could not resolve form path: " + formPath //$NON-NLS-1$
                        + ". Expected Catalog.Products.Forms.ItemForm or CommonForm.MyForm") //$NON-NLS-1$
                    .toJson();
            }

            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists())
            {
                return ToolResult.error("No such project: " + projectName).toJson(); //$NON-NLS-1$
            }

            IFile formFile = project.getFile(filePath);
            if (!formFile.exists())
            {
                return ToolResult.error("Could not find form file: " + filePath + " in project " + projectName) //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }

            IWorkbenchPage page = getWorkbenchPage();
            if (page == null)
            {
                return ToolResult.error("There is no active workbench page").toJson(); //$NON-NLS-1$
            }

            IEditorPart open = page.findEditor(new FileEditorInput(formFile));
            if (open != null)
            {
                page.closeEditor(open, false);
            }

            IEditorPart editor = IDE.openEditor(page, formFile, FORM_EDITOR_ID, true);
            if (editor == null)
            {
                return ToolResult.error("Could not open the form editor for: " + formPath).toJson(); //$NON-NLS-1$
            }

            activateMainPage(editor);
            return null;
        }
        catch (Exception e)
        {
            Activator.logError("Opening the form editor failed for " + formPath, e); //$NON-NLS-1$
            return ToolResult.error("Could not open the form editor: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * @return the workbench page to open editors in, falling back to the first window when none is
     *         active; <code>null</code> when the workbench has no page at all. Must run on the UI thread
     */
    public static IWorkbenchPage getWorkbenchPage()
    {
        IWorkbench workbench = PlatformUI.getWorkbench();

        IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
        if (window == null)
        {
            IWorkbenchWindow[] windows = workbench.getWorkbenchWindows();
            if (windows.length == 0)
            {
                return null;
            }
            window = windows[0];
        }
        return window == null ? null : window.getActivePage();
    }

    /**
     * Waits for the form editor's WYSIWYG page to exist.
     * <p>
     * Polls for up to fifteen half-second turns, pumping the event queue around each wait so the editor
     * can get on with building itself. A page is only accepted once it has a viewer: the page object
     * appears before the canvas does, and capturing then yields nothing.
     * </p>
     * <p>
     * Must run on the UI thread.
     * </p>
     *
     * @return the editor page, possibly still without a viewer if the wait ran out, or
     *         <code>null</code> when there is no form editor at all
     */
    public static Object waitForFormEditorPage()
    {
        Display display = Display.getCurrent();

        for (int attempt = 0; attempt < PAGE_ATTEMPTS; attempt++)
        {
            processEvents(display);

            try
            {
                Object editorPage = getActiveFormEditorPage();
                if (editorPage != null && ReflectionAccess.getFieldValue(editorPage, FIELD_WYSIWYG_VIEWER) != null)
                {
                    return editorPage;
                }
            }
            catch (Exception e)
            {
                // Still building. Give it another turn.
            }

            sleep(PAGE_INTERVAL_MS);
            processEvents(display);
        }

        try
        {
            // Out of turns. Hand back whatever there is and let the caller decide.
            return getActiveFormEditorPage();
        }
        catch (Exception e)
        {
            Activator.logError("The form editor page never showed up", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * @return EDT's currently active form editor page, or <code>null</code> when the active editor is
     *         not a form editor. Must run on the UI thread
     * @throws Exception if EDT's form editor class is not where it used to be
     */
    public static Object getActiveFormEditorPage() throws Exception
    {
        Class<?> formEditor = Class.forName(FORM_EDITOR_CLASS);
        return formEditor.getMethod("getActiveFormEditorPage").invoke(null); //$NON-NLS-1$
    }

    /**
     * Takes the rendered form straight out of EDT's render representation.
     * <p>
     * The good path: EDT has already drawn the form into a buffer, and this asks for it. A re-render is
     * requested first so the image is not one edit out of date, but a re-render that fails is not fatal
     * - a slightly stale picture beats no picture.
     * </p>
     * <p>
     * Must run on the UI thread.
     * </p>
     *
     * @param wysiwygViewer the viewer, from the editor page
     * @return the rendered form, or <code>null</code> when EDT has nothing to give - which is the
     *         caller's cue to fall back to {@link #captureControlImageData(Object)}
     * @throws Exception if the representation refuses the call outright
     */
    public static ImageData extractFormImageData(Object wysiwygViewer) throws Exception
    {
        Object representation = ReflectionAccess.getFieldValue(wysiwygViewer, FIELD_WYSIWYG_REPRESENTATION);
        if (representation == null)
        {
            return null;
        }

        try
        {
            Method rebuild = ReflectionAccess.findMethod(representation.getClass(), "rebuild", boolean.class); //$NON-NLS-1$
            if (rebuild != null)
            {
                rebuild.setAccessible(true);
                rebuild.invoke(representation, Boolean.TRUE);
                settle(REBUILD_STEPS, REBUILD_INTERVAL_MS);
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Re-rendering the form before capture failed: " + e.getMessage()); //$NON-NLS-1$
        }

        Method getFormImageData = ReflectionAccess.findMethod(representation.getClass(), "getFormImageData"); //$NON-NLS-1$
        if (getFormImageData == null)
        {
            Activator.logWarning("getFormImageData() is no longer on " + representation.getClass().getName()); //$NON-NLS-1$
            return null;
        }

        getFormImageData.setAccessible(true);
        Object image = getFormImageData.invoke(representation);
        if (!(image instanceof ImageData))
        {
            return null;
        }

        ImageData imageData = (ImageData)image;
        return imageData.width > 0 && imageData.height > 0 ? imageData : null;
    }

    /**
     * Makes the form's canvas paint itself into an image.
     * <p>
     * The fallback, for when EDT is not buffering its render and has no image to hand over. It is what
     * is on screen rather than what was laid out, so it is only as good as the canvas is big - a form
     * scrolled out of view is captured as it looks, cropped. Painted onto white first, because a
     * control that does not paint its own background would otherwise come out on whatever the last
     * image left behind.
     * </p>
     * <p>
     * Must run on the UI thread.
     * </p>
     *
     * @param wysiwygViewer the viewer, from the editor page
     * @return the picture, or <code>null</code> when there is no drawable control to take one of
     * @throws Exception if the viewer will not give up its control
     */
    public static ImageData captureControlImageData(Object wysiwygViewer) throws Exception
    {
        Object result = ReflectionAccess.invokeMethod(wysiwygViewer, "getControl"); //$NON-NLS-1$
        if (!(result instanceof Control))
        {
            return null;
        }

        Control control = (Control)result;
        if (control.isDisposed())
        {
            return null;
        }

        Rectangle bounds = control.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0)
        {
            return null;
        }

        control.update();

        Display display = control.getDisplay();
        Image image = new Image(display, bounds.width, bounds.height);
        GC gc = new GC(image);
        try
        {
            gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
            gc.fillRectangle(0, 0, bounds.width, bounds.height);
            control.print(gc);

            // Copies the pixels out, so the image below can go.
            return image.getImageData();
        }
        finally
        {
            gc.dispose();
            image.dispose();
        }
    }

    /**
     * Asks the viewer to redraw, and gives it a moment to. Best effort; must run on the UI thread.
     *
     * @param wysiwygViewer the viewer, from the editor page
     */
    public static void refreshViewer(Object wysiwygViewer)
    {
        try
        {
            ReflectionAccess.invokeMethod(wysiwygViewer, "refresh"); //$NON-NLS-1$
            settle(SETTLE_STEPS, SETTLE_INTERVAL_MS);
        }
        catch (Exception e)
        {
            Activator.logWarning("Refreshing the form viewer failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Encodes a captured form as a PNG.
     *
     * @param imageData the picture
     * @return the PNG, base64 encoded, with no data-URI prefix. The client reads the PNG header itself
     *         to size the image, so this has to be a real PNG
     */
    public static String encodePng(ImageData imageData)
    {
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] {imageData};

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        loader.save(png, SWT.IMAGE_PNG);
        return Base64.getEncoder().encodeToString(png.toByteArray());
    }

    /**
     * Brings a named page of a Pages group to the front, so that the screenshot shows it.
     * <p>
     * The route is roundabout because the model and the render are two different worlds: find the page
     * in the form model by name, ask the representation which widget it drew for it, walk up from that
     * widget to the tab control that owns it, and tell the representation to open that tab.
     * </p>
     * <p>
     * Must run on the UI thread.
     * </p>
     *
     * @param editorPage the form editor page
     * @param pageName the page to activate; <code>null</code> or empty means "leave it alone", which
     *            counts as success
     * @return <code>null</code> when the page is showing, or a ready-to-return JSON error body naming
     *         which step of the walk failed
     */
    public static String activatePageInForm(Object editorPage, String pageName)
    {
        if (pageName == null || pageName.isEmpty())
        {
            return null;
        }

        try
        {
            Object viewer = readField(editorPage, FIELD_WYSIWYG_VIEWER);
            if (viewer == null)
            {
                return ToolResult.error("WYSIWYG viewer is not available, so the page cannot be activated").toJson(); //$NON-NLS-1$
            }

            Object representation = readField(viewer, FIELD_WYSIWYG_REPRESENTATION);
            if (representation == null)
            {
                return ToolResult.error("WYSIWYG representation is not available, so the page cannot be activated").toJson(); //$NON-NLS-1$
            }

            Object form = resolveFormModel(representation, viewer, editorPage);
            if (form == null)
            {
                Activator.logWarning("Could not reach the Form model from the editor - the EDT form API has moved"); //$NON-NLS-1$
                return ToolResult.error("Could not resolve the Form model from the editor to activate the page").toJson(); //$NON-NLS-1$
            }

            Object item = findItemNamed(form, pageName);
            if (item == null)
            {
                List<String> pages = collectPageNames(form);
                String hint = pages.isEmpty() ? "the form has no Page elements" : "pages available: " + pages; //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.error("Page '" + pageName + "' was not found in the form (" + hint + ")").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }

            Control rendered = findRelatedControl(representation, item);
            if (rendered == null)
            {
                return ToolResult
                    .error("Page '" + pageName + "' is not rendered in WYSIWYG yet (no control found)") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }

            Control tabControl = findTabControl(rendered);
            if (tabControl == null)
            {
                return ToolResult.error("No TabControl ancestor was found for page '" + pageName //$NON-NLS-1$
                    + "' (the page may not sit inside a Pages group)").toJson(); //$NON-NLS-1$
            }

            Method openTab = findSingleArgMethod(representation.getClass(), "openTab", tabControl); //$NON-NLS-1$
            if (openTab == null)
            {
                return ToolResult
                    .error("No openTab method was found on " + representation.getClass().getSimpleName()) //$NON-NLS-1$
                    .toJson();
            }

            openTab.setAccessible(true);
            openTab.invoke(representation, tabControl);
            settle(SETTLE_STEPS, SETTLE_INTERVAL_MS);
            return null;
        }
        catch (Exception e)
        {
            Activator.logError("Activating page '" + pageName + "' raised an exception", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Could not activate page '" + pageName + "': " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Runs the SWT event queue dry.
     * <p>
     * A nested event loop, on the UI thread, on purpose: it is what lets the editor lay itself out while
     * this code is standing on the very thread that would otherwise do it.
     * </p>
     *
     * @param display the display to pump; <code>null</code> or disposed does nothing
     */
    public static void processEvents(Display display)
    {
        if (display == null || display.isDisposed())
        {
            return;
        }

        while (display.readAndDispatch())
        {
            // Keep going until the queue is empty.
        }
    }

    /**
     * Brings the editor's WYSIWYG tab to the front.
     *
     * @param editor the form editor
     */
    private static void activateMainPage(IEditorPart editor)
    {
        try
        {
            if (!Class.forName(FORM_EDITOR_CLASS).isInstance(editor))
            {
                return;
            }

            Method setActivePage = ReflectionAccess.findMethod(editor.getClass(), "setActivePage", String.class); //$NON-NLS-1$
            if (setActivePage == null)
            {
                Activator.logWarning("setActivePage(String) is no longer on " + editor.getClass().getName()); //$NON-NLS-1$
                return;
            }

            setActivePage.setAccessible(true);
            setActivePage.invoke(editor, FORM_EDITOR_MAIN_PAGE);
        }
        catch (Throwable t)
        {
            Activator.logWarning("Activating the WYSIWYG page failed: " + t.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Finds the Form model the editor is showing.
     * <p>
     * Four places are asked, nearest the rendering first: the representation, then the viewer, then the
     * editor, then the editor page. The order is the safeguard. A search that simply hunted the object
     * graph for anything of type Form could turn up a stale one, or one belonging to another editor,
     * and screenshot the wrong form while reporting success.
     * </p>
     *
     * @param representation the render representation
     * @param viewer the WYSIWYG viewer
     * @param editorPage the editor page
     * @return the Form, or <code>null</code> when none of the four is holding one
     */
    private static Object resolveFormModel(Object representation, Object viewer, Object editorPage)
    {
        Class<?> formClass = loadClass(FORM_MODEL_CLASS);
        if (formClass == null)
        {
            return null;
        }

        List<Object> sources = new ArrayList<>();
        sources.add(representation);
        sources.add(viewer);
        sources.add(resolveEditor(editorPage));
        sources.add(editorPage);

        for (Object source : sources)
        {
            if (source == null)
            {
                continue;
            }

            for (String field : FORM_FIELDS)
            {
                Object candidate = readField(source, field);
                if (formClass.isInstance(candidate))
                {
                    return candidate;
                }
            }

            for (String getter : FORM_GETTERS)
            {
                Object candidate = call(source, getter);
                if (formClass.isInstance(candidate))
                {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * @param editorPage the editor page
     * @return the editor it belongs to, or <code>null</code>
     */
    private static Object resolveEditor(Object editorPage)
    {
        Object editor = call(editorPage, "getEditor"); //$NON-NLS-1$
        return editor != null ? editor : readField(editorPage, "editor"); //$NON-NLS-1$
    }

    /**
     * Depth-first search of the form for an item of a given name. Exact match, case and all - a form
     * may hold two items whose names differ only in case, and guessing between them is not this
     * method's business.
     *
     * @param container the form, or a group inside it
     * @param name the name to find
     * @return the first item so named, or <code>null</code>
     */
    private static Object findItemNamed(Object container, String name)
    {
        Class<?> containerClass = loadClass(FORM_ITEM_CONTAINER_CLASS);
        Class<?> namedClass = loadClass(NAMED_ELEMENT_CLASS);
        if (containerClass == null || namedClass == null)
        {
            return null;
        }
        return findItemNamed(container, name, containerClass, namedClass);
    }

    /**
     * @param container the container to search
     * @param name the name to find
     * @param containerClass EDT's FormItemContainer
     * @param namedClass EDT's NamedElement
     * @return the first item so named, or <code>null</code>
     */
    private static Object findItemNamed(Object container, String name, Class<?> containerClass, Class<?> namedClass)
    {
        for (Object item : itemsOf(container))
        {
            if (namedClass.isInstance(item) && name.equals(call(item, "getName"))) //$NON-NLS-1$
            {
                return item;
            }

            if (containerClass.isInstance(item))
            {
                Object found = findItemNamed(item, name, containerClass, namedClass);
                if (found != null)
                {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Lists the names of the form's pages, for the error message when the one asked for is not there.
     *
     * @param form the form model
     * @return the page names, in form order; empty when the form has no pages
     */
    private static List<String> collectPageNames(Object form)
    {
        List<String> names = new ArrayList<>();
        try
        {
            Class<?> containerClass = loadClass(FORM_ITEM_CONTAINER_CLASS);
            Class<?> groupClass = loadClass(FORM_GROUP_CLASS);
            if (containerClass == null || groupClass == null)
            {
                return names;
            }
            collectPageNames(form, containerClass, groupClass, loadClass(PAGE_GROUP_EXT_INFO_CLASS), names);
        }
        catch (Exception e)
        {
            Activator.logWarning("Listing the form's pages failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return names;
    }

    /**
     * A page is a group whose extended info says it is one - there is no Page type of its own in the
     * form model.
     *
     * @param container the container to walk
     * @param containerClass EDT's FormItemContainer
     * @param groupClass EDT's FormGroup
     * @param pageExtInfoClass EDT's PageGroupExtInfo, or <code>null</code> when it cannot be loaded, in
     *            which case nothing is a page
     * @param names collects the names found
     */
    private static void collectPageNames(Object container, Class<?> containerClass, Class<?> groupClass,
        Class<?> pageExtInfoClass, List<String> names)
    {
        for (Object item : itemsOf(container))
        {
            if (pageExtInfoClass != null && groupClass.isInstance(item)
                && pageExtInfoClass.isInstance(call(item, "getExtInfo"))) //$NON-NLS-1$
            {
                Object name = call(item, "getName"); //$NON-NLS-1$
                if (name instanceof String && !((String)name).isEmpty())
                {
                    names.add((String)name);
                }
            }

            if (containerClass.isInstance(item))
            {
                collectPageNames(item, containerClass, groupClass, pageExtInfoClass, names);
            }
        }
    }

    /**
     * @param container a FormItemContainer
     * @return its items; empty when it has none or will not say
     */
    private static List<?> itemsOf(Object container)
    {
        Object items = call(container, "getItems"); //$NON-NLS-1$
        return items instanceof List ? (List<?>)items : List.of();
    }

    /**
     * Asks the representation which widget it drew for a model item.
     * <p>
     * Retried, because the answer is no until EDT has drawn it, and it draws on the very thread that is
     * asking - hence the event pumping between tries.
     * </p>
     *
     * @param representation the render representation
     * @param item the form model item
     * @return the widget, or <code>null</code> when it never appeared
     * @throws Exception if the call itself is refused
     */
    private static Control findRelatedControl(Object representation, Object item) throws Exception
    {
        for (int attempt = 0; attempt < CONTROL_ATTEMPTS; attempt++)
        {
            Method getRelatedControl = findSingleArgMethod(representation.getClass(), "getRelatedControl", item); //$NON-NLS-1$
            if (getRelatedControl != null)
            {
                getRelatedControl.setAccessible(true);
                Object control = getRelatedControl.invoke(representation, item);
                if (control instanceof Control)
                {
                    return (Control)control;
                }
            }

            processEvents(Display.getCurrent());
            sleep(CONTROL_INTERVAL_MS);
        }
        return null;
    }

    /**
     * Walks up from a rendered widget to the tab control that owns it.
     * <p>
     * Matched by simple name and by package: {@code TabControl} is a name other bundles use too, and
     * opening a tab on somebody else's widget would fail in a way that takes an afternoon to read.
     * </p>
     *
     * @param control the rendered widget
     * @return the tab control above it, or <code>null</code> when there is none - which means the page
     *         is not inside a Pages group
     */
    private static Control findTabControl(Control control)
    {
        Control current = control;

        for (int hop = 0; hop < MAX_PARENT_HOPS && current != null; hop++)
        {
            Class<?> type = current.getClass();
            if (TAB_CONTROL_NAME.equals(type.getSimpleName()) && type.getName().contains(FORM_PACKAGE))
            {
                return current;
            }

            Control parent = current.getParent();
            if (parent == current)
            {
                break;
            }
            current = parent;
        }
        return null;
    }

    /**
     * Finds the one-argument method that best fits an argument.
     * <p>
     * "Best" means most derived. A class may offer both {@code openTab(Object)} and
     * {@code openTab(TabControl)}, and the general one is liable to accept the call and quietly do
     * nothing with it.
     * </p>
     *
     * @param targetClass the class to search, superclasses included
     * @param methodName the method
     * @param argument the argument it must accept
     * @return the most specific match, or <code>null</code>
     */
    private static Method findSingleArgMethod(Class<?> targetClass, String methodName, Object argument)
    {
        Method best = null;

        for (Class<?> current = targetClass; current != null; current = current.getSuperclass())
        {
            best = mostSpecific(best, current.getDeclaredMethods(), methodName, argument);
        }
        return mostSpecific(best, targetClass.getMethods(), methodName, argument);
    }

    /**
     * @param best the best candidate so far
     * @param candidates the methods to consider
     * @param methodName the method name to match
     * @param argument the argument the method must accept
     * @return the better of the two
     */
    private static Method mostSpecific(Method best, Method[] candidates, String methodName, Object argument)
    {
        Method winner = best;

        for (Method candidate : candidates)
        {
            if (!candidate.getName().equals(methodName))
            {
                continue;
            }

            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length != 1 || !parameters[0].isInstance(argument))
            {
                continue;
            }

            if (winner == null || winner.getParameterTypes()[0].isAssignableFrom(parameters[0]))
            {
                winner = candidate;
            }
        }
        return winner;
    }

    /**
     * @param service the render service class
     * @param methodName a static no-argument boolean getter
     * @return what it answered, or <code>false</code> when it cannot be asked
     */
    private static boolean readFlag(Class<?> service, String methodName) throws Exception
    {
        Object value = service.getMethod(methodName).invoke(null);
        return value instanceof Boolean && ((Boolean)value).booleanValue();
    }

    /**
     * @param target the object to read from; may be <code>null</code>
     * @param fieldName the field, at any level of the hierarchy
     * @return its value, or <code>null</code> when there is no such field or it cannot be read
     */
    private static Object readField(Object target, String fieldName)
    {
        if (target == null)
        {
            return null;
        }

        try
        {
            return ReflectionAccess.getFieldValue(target, fieldName);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Calls a no-argument method, private ones included.
     *
     * @param target the receiver; may be <code>null</code>
     * @param methodName the method
     * @return what it returned, or <code>null</code> when it is not there or would not run
     */
    private static Object call(Object target, String methodName)
    {
        if (target == null)
        {
            return null;
        }

        try
        {
            Method method = ReflectionAccess.findMethod(target.getClass(), methodName);
            if (method == null)
            {
                method = target.getClass().getMethod(methodName);
            }

            method.setAccessible(true);
            return method.invoke(target);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /**
     * @param className the class to load
     * @return it, or <code>null</code> when this EDT does not have it
     */
    private static Class<?> loadClass(String className)
    {
        try
        {
            return Class.forName(className);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /**
     * Lets the UI catch up: pump, wait, repeat.
     *
     * @param steps how many turns
     * @param intervalMs how long each turn waits
     */
    private static void settle(int steps, long intervalMs)
    {
        Display display = Display.getCurrent();

        for (int step = 0; step < steps; step++)
        {
            processEvents(display);
            sleep(intervalMs);
        }
    }

    /**
     * @param millis how long to wait; an interruption ends the wait and is remembered on the thread
     */
    private static void sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A capture: a PNG, or the reason there is not one.
     */
    public static class CaptureResult
    {
        private final String base64Data;
        private final String error;

        private CaptureResult(String base64Data, String error)
        {
            this.base64Data = base64Data;
            this.error = error;
        }

        /**
         * @param base64 the PNG, base64 encoded
         * @return a successful capture
         */
        public static CaptureResult success(String base64)
        {
            return new CaptureResult(base64, null);
        }

        /**
         * @param errorJson a ready-to-return JSON error body, not a bare message
         * @return a failed capture
         */
        public static CaptureResult error(String errorJson)
        {
            return new CaptureResult(null, errorJson);
        }

        /**
         * @return whether there is a picture
         */
        public boolean isSuccess()
        {
            return error == null;
        }

        /**
         * @return the PNG, base64 encoded, or <code>null</code> when the capture failed
         */
        public String getBase64Data()
        {
            return base64Data;
        }

        /**
         * @return the JSON error body, or <code>null</code> when the capture worked
         */
        public String getError()
        {
            return error;
        }
    }
}
