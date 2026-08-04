/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.widgets.Display;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.EditorImageCapture;
import ru.aiedt.mcp.server.support.EditorImageCapture.CaptureResult;
import ru.aiedt.mcp.server.support.ReflectionAccess;

/**
 * Hands an agent a picture of a 1C form the way EDT draws it in its WYSIWYG editor.
 * <p>
 * The heavy lifting (opening the editor, switching the render into a buffer, pulling the pixels out
 * of EDT's internals) lives in {@link EditorImageCapture}; this tool is the thin front door that
 * reads the request, runs the capture on the SWT thread, and decides whether to return the picture
 * inline or drop it on disk.
 * </p>
 * <p>
 * The capture itself runs through {@code Display.syncExec} because EDT will only give an image up on
 * the thread that owns it, and that same thread is the one building the editor - so this tool blocks
 * for as long as the editor takes to settle.
 * </p>
 */
public class FormScreenshotGrabber implements IMcpTool
{
    public static final String NAME = "get_form_screenshot"; //$NON-NLS-1$

    /** Reflection name of the field on EDT's form editor page that holds the WYSIWYG canvas viewer. */
    private static final String FIELD_WYSIWYG_VIEWER = "wysiwygViewer"; //$NON-NLS-1$

    /** How many times to pump the SWT event queue while the freshly opened editor finds its feet. */
    private static final int SETTLE_ATTEMPTS = 5;
    private static final long SETTLE_STEP_MS = 100L;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Grabs a PNG snapshot of the form currently shown in EDT's WYSIWYG editor. " //$NON-NLS-1$
            + "When given a metadata FQN path it opens that form and brings it to the front first. " //$NON-NLS-1$
            + "Pass activePage to switch to a specific Page element of the form before the capture is taken."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the EDT project. Needed only when formPath is given.") //$NON-NLS-1$
            .stringProperty("formPath", //$NON-NLS-1$
                "Metadata FQN path leading to the form. " //$NON-NLS-1$
                    + "Pattern: 'MetadataType.ObjectName.Forms.FormName' or 'CommonForm.FormName'. " //$NON-NLS-1$
                    + "For instance: 'Catalog.Products.Forms.ItemForm', 'Document.SalesOrder.Forms.DocumentForm', " //$NON-NLS-1$
                    + "'CommonForm.MyForm'. Leave unset to capture whichever form editor is currently active.") //$NON-NLS-1$
            .booleanProperty("refresh", "Forces the WYSIWYG view to redraw before the capture is taken (defaults to false)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("activePage", //$NON-NLS-1$
                "Name of a Page element within the form to switch to before capturing. " //$NON-NLS-1$
                    + "formPath must also be given. The search walks the whole form by element name; " //$NON-NLS-1$
                    + "when several Page elements share that name, the first one found depth-first wins. " //$NON-NLS-1$
                    + "When no matching page exists, the error response lists the pages that are available.") //$NON-NLS-1$
            .stringProperty("savePath", //$NON-NLS-1$
                "Optional absolute path to save the captured PNG to (missing parent folders are " //$NON-NLS-1$
                    + "created automatically). Once set, the tool writes the file to disk and answers with a compact " //$NON-NLS-1$
                    + "JSON object {savedPath, width, height, bytes} rather than the inline image data - handy for " //$NON-NLS-1$
                    + "keeping a large base64 payload out of the conversation. Leaving it unset returns the PNG " //$NON-NLS-1$
                    + "inline as an embedded image resource, same as before.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.IMAGE;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String formPath = params.get("formPath"); //$NON-NLS-1$
        if (formPath != null && !formPath.isEmpty())
        {
            String[] segments = formPath.split("\\."); //$NON-NLS-1$
            if (segments.length > 0)
            {
                return segments[segments.length - 1] + ".png"; //$NON-NLS-1$
            }
        }
        return "form.png"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formPath = JsonUtils.extractStringArgument(params, "formPath"); //$NON-NLS-1$
        boolean refresh = "true".equalsIgnoreCase(JsonUtils.extractStringArgument(params, "refresh")); //$NON-NLS-1$ //$NON-NLS-2$
        String activePage = JsonUtils.extractStringArgument(params, "activePage"); //$NON-NLS-1$
        String savePath = JsonUtils.extractStringArgument(params, "savePath"); //$NON-NLS-1$

        boolean hasFormPath = formPath != null && !formPath.isEmpty();
        if (hasFormPath && (projectName == null || projectName.isEmpty()))
        {
            return ToolResult.error("projectName must be set whenever formPath is provided").toJson(); //$NON-NLS-1$
        }

        if (activePage != null && !activePage.isEmpty() && !hasFormPath)
        {
            return ToolResult.error("activePage cannot be used without formPath").toJson(); //$NON-NLS-1$
        }

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            return ToolResult.error("No display is available").toJson(); //$NON-NLS-1$
        }

        AtomicReference<CaptureResult> captured = new AtomicReference<>();
        display.syncExec(() -> captured.set(capture(projectName, formPath, refresh, activePage)));
        CaptureResult result = captured.get();

        if (!result.isSuccess())
        {
            return result.getError();
        }

        if (savePath != null && !savePath.isEmpty())
        {
            return writePngToDisk(result.getBase64Data(), savePath);
        }

        return result.getBase64Data();
    }

    /**
     * Runs the capture on the SWT thread. Finds the editor page (opening one when a path was given),
     * optionally flips to a Page, and pulls the rendered image out of EDT. Any failure comes back as a
     * {@link CaptureResult} carrying a ready-to-return JSON error body.
     *
     * @param projectName the project, or {@code null} when capturing the already-active editor
     * @param formPath the form to open, or {@code null} to use the active editor
     * @param refresh whether to ask the viewer to redraw first
     * @param activePage a Page element to bring to the front, or {@code null}
     * @return the capture, or the reason there is not one
     */
    private CaptureResult capture(String projectName, String formPath, boolean refresh, String activePage)
    {
        try
        {
            Object editorPage;

            if (formPath != null && !formPath.isEmpty())
            {
                EditorImageCapture.ensureBufferedNativeRenderMode();

                String openError = EditorImageCapture.openAndActivateForm(projectName, formPath);
                if (openError != null)
                {
                    return CaptureResult.error(openError);
                }

                // The editor builds on this very thread; pump the queue so it can.
                Display display = Display.getCurrent();
                for (int step = 0; step < SETTLE_ATTEMPTS; step++)
                {
                    EditorImageCapture.processEvents(display);
                    sleep(SETTLE_STEP_MS);
                }

                editorPage = EditorImageCapture.waitForFormEditorPage();
                if (editorPage == null)
                {
                    return CaptureResult.error(ToolResult.error(
                        "The form editor opened, but its WYSIWYG page is not ready yet. " //$NON-NLS-1$
                            + "The form might still be loading.").toJson()); //$NON-NLS-1$
                }
            }
            else
            {
                editorPage = EditorImageCapture.getActiveFormEditorPage();
                if (editorPage == null)
                {
                    return CaptureResult.error(ToolResult.error(
                        "Could not find an active form editor page. " //$NON-NLS-1$
                            + "Pass formPath to have the tool open a form automatically.").toJson()); //$NON-NLS-1$
                }
            }

            if (activePage != null && !activePage.isEmpty())
            {
                String pageError = EditorImageCapture.activatePageInForm(editorPage, activePage);
                if (pageError != null)
                {
                    return CaptureResult.error(pageError);
                }
            }

            Object wysiwygViewer = ReflectionAccess.getFieldValue(editorPage, FIELD_WYSIWYG_VIEWER);
            if (wysiwygViewer == null)
            {
                return CaptureResult.error(ToolResult.error("WYSIWYG viewer is unavailable").toJson()); //$NON-NLS-1$
            }

            if (refresh)
            {
                EditorImageCapture.refreshViewer(wysiwygViewer);
            }

            // Prefer the image EDT already rendered; fall back to making the canvas paint itself.
            ImageData imageData = EditorImageCapture.extractFormImageData(wysiwygViewer);
            if (imageData == null)
            {
                imageData = EditorImageCapture.captureControlImageData(wysiwygViewer);
            }

            if (imageData == null || imageData.width <= 0 || imageData.height <= 0)
            {
                return CaptureResult.error(ToolResult.error("No form image data is available").toJson()); //$NON-NLS-1$
            }

            return CaptureResult.success(EditorImageCapture.encodePng(imageData));
        }
        catch (Exception e)
        {
            Activator.logError("Could not capture form screenshot", e); //$NON-NLS-1$
            return CaptureResult.error(
                ToolResult.error("Could not capture form screenshot: " + e.getMessage()).toJson()); //$NON-NLS-1$
        }
    }

    /**
     * Writes the captured PNG to {@code savePath} (making parent folders) and returns a small JSON
     * confirmation instead of the base64 blob. The PNG is decoded first so the reported byte count and
     * dimensions are real. A capture that succeeds but cannot be written is reported as such.
     *
     * @param base64 the PNG, base64 encoded
     * @param savePath where to write it
     * @return a JSON success body with the absolute path, byte size and dimensions, or a JSON error
     */
    private String writePngToDisk(String base64, String savePath)
    {
        try
        {
            byte[] png = Base64.getDecoder().decode(base64);
            Path path = Paths.get(savePath);
            Path parent = path.getParent();
            if (parent != null)
            {
                Files.createDirectories(parent);
            }
            Files.write(path, png);

            ToolResult ok = ToolResult.success()
                .put("savedPath", path.toAbsolutePath().toString()) //$NON-NLS-1$
                .put("bytes", png.length); //$NON-NLS-1$
            int[] dims = readPngDimensions(png);
            if (dims != null)
            {
                ok.put("width", dims[0]).put("height", dims[1]); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return ok.put("message", "PNG screenshot saved.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logError("Could not write form screenshot to " + savePath, e); //$NON-NLS-1$
            return ToolResult.error("Captured the screenshot but failed to write it to '" + savePath //$NON-NLS-1$
                + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Reads the pixel dimensions out of a PNG's IHDR chunk. The width sits at byte offset 16 and the
     * height at 20 (past the 8-byte signature, the 4-byte chunk length, and the "IHDR" tag), both
     * big-endian. Returns {@code null} for a buffer too short to hold an IHDR or for nonsensical values.
     *
     * @param png the PNG bytes
     * @return {@code {width, height}}, or {@code null}
     */
    private static int[] readPngDimensions(byte[] png)
    {
        if (png == null || png.length < 24)
        {
            return null;
        }
        int width = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16)
            | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int height = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16)
            | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        if (width <= 0 || height <= 0)
        {
            return null;
        }
        return new int[] {width, height};
    }

    /**
     * Sleeps for a short while, ending early if interrupted and leaving the interruption recorded on
     * the thread. Used between event-queue pumps while the editor settles.
     *
     * @param millis how long to wait
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
}
