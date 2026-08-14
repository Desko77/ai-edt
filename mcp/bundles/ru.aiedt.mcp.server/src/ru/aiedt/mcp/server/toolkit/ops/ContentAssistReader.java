/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.contentassist.IContentAssistant;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.XtextSourceViewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.furstenheim.CopyDown;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.ToolParamSettings;
import ru.aiedt.mcp.server.wire.GsonHolder;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Asks the editor what could be typed at a position and reports the completion proposals as JSON.
 * <p>
 * The file is opened in its Xtext editor, the editor's own content assistant is asked for proposals
 * at the offset, and the results are filtered, paged and optionally documented. An editor that this
 * call opened is closed again on the way out, so repeated calls do not leave a trail of tabs; one
 * that was already open is left as it was found.
 * </p>
 */
public class ContentAssistReader
    implements IMcpTool
{
    private static final int LIMIT_MAX = 1000;

    @Override
    public String getName()
    {
        return "get_content_assist"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `code_search` `operation=content_assist`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Retrieves code-completion (content assist) proposals for a specific position in a BSL file. " //$NON-NLS-1$
            + "Opens the file in the EDT editor and collects the completions available at the requested " //$NON-NLS-1$
            + "line and column."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project (must be supplied)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("filePath", //$NON-NLS-1$
                "Relative path to the BSL file, measured from the project's src folder (e.g. " //$NON-NLS-1$
                    + "'CommonModules/MyModule/Module.bsl')", true) //$NON-NLS-1$
            .integerProperty("line", "1-based line number to inspect", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("column", "1-based column number to inspect", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", //$NON-NLS-1$
                "Upper bound on how many proposals to return (defaults to the preference value)") //$NON-NLS-1$
            .integerProperty("offset", "Number of matching proposals to skip before returning results (default 0), for paging") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("contains", //$NON-NLS-1$
                "Keep only proposals whose display text contains one of these comma-separated substrings (" //$NON-NLS-1$
                    + "e.g. 'Insert,Add')") //$NON-NLS-1$
            .booleanProperty("extendedDocumentation", //$NON-NLS-1$
                "Whether to include the full proposal documentation (default false returns just the display text)") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName must be provided").toJson(); //$NON-NLS-1$
        }
        if (filePath == null || filePath.isEmpty())
        {
            return ToolResult.error("filePath must be provided").toJson(); //$NON-NLS-1$
        }

        int line;
        int column;
        try
        {
            line = (int)Double.parseDouble(JsonUtils.extractStringArgument(params, "line")); //$NON-NLS-1$
            column = (int)Double.parseDouble(JsonUtils.extractStringArgument(params, "column")); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return ToolResult.error("Line or column value is not numeric").toJson(); //$NON-NLS-1$
        }
        if (line < 1 || column < 1)
        {
            return ToolResult.error("Line and column must both be 1 or greater").toJson(); //$NON-NLS-1$
        }

        int defaultLimit =
            ToolParamSettings.getInstance().getParameterValue("get_content_assist", "limit", 100); //$NON-NLS-1$ //$NON-NLS-2$
        int limit = JsonUtils.extractIntArgument(params, "limit", defaultLimit); //$NON-NLS-1$
        limit = Math.max(1, Math.min(LIMIT_MAX, limit));

        int offset = 0;
        String offsetRaw = JsonUtils.extractStringArgument(params, "offset"); //$NON-NLS-1$
        if (offsetRaw != null && !offsetRaw.trim().isEmpty())
        {
            try
            {
                offset = (int)Double.parseDouble(offsetRaw.trim());
            }
            catch (NumberFormatException e)
            {
                offset = 0;
            }
            if (offset < 0)
            {
                offset = 0;
            }
        }

        String contains = JsonUtils.extractStringArgument(params, "contains"); //$NON-NLS-1$
        boolean extendedDocumentation =
            "true".equalsIgnoreCase(JsonUtils.extractStringArgument(params, "extendedDocumentation")); //$NON-NLS-1$ //$NON-NLS-2$

        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        IFile file = project.getFile("src/" + filePath); //$NON-NLS-1$
        if (!file.exists())
        {
            return ToolResult.error("File does not exist: " + file.getProjectRelativePath() //$NON-NLS-1$
                + " within project " + projectName).toJson(); //$NON-NLS-1$
        }

        int line0 = line;
        int column0 = column;
        int limit0 = limit;
        int offset0 = offset;
        AtomicReference<String> holder = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                holder.set(executeOnUiThread(file, line0, column0, limit0, offset0, contains,
                    extendedDocumentation));
            }
            catch (Exception e)
            {
                Activator.logError("get_content_assist tool failed", e); //$NON-NLS-1$
                holder.set(ToolResult.error("Unhandled exception: " + e.getMessage()).toJson()); //$NON-NLS-1$
            }
        });
        return holder.get();
    }

    /**
     * Runs the editor-bound part on the UI thread.
     *
     * @param file the BSL file
     * @param line the 1-based line
     * @param column the 1-based column
     * @param limit how many proposals to keep
     * @param offset how many matching proposals to skip
     * @param contains the comma-separated substring filter, or <code>null</code>
     * @param extendedDocumentation attaches each proposal's help text when set
     * @return the JSON answer
     * @throws Exception when the editor cannot be opened
     */
    private String executeOnUiThread(IFile file, int line, int column, int limit, int offset,
        String contains, boolean extendedDocumentation) throws Exception
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
        {
            return ToolResult.error("There is no active workbench window").toJson(); //$NON-NLS-1$
        }
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
        {
            return ToolResult.error("There is no active workbench page").toJson(); //$NON-NLS-1$
        }

        boolean wasAlreadyOpen = page.findEditor(new FileEditorInput(file)) != null;
        IEditorPart editorPart;
        try
        {
            // Opened, not activated - see SymbolInfoReader for why: an activated editor becomes the
            // active part and drags the workbench title along with it, so a long series of calls
            // leaves the user watching module names flicker past in their window title.
            editorPart = IDE.openEditor(page, file, false);
        }
        catch (PartInitException e)
        {
            return ToolResult.error("Unable to open an editor for the file").toJson(); //$NON-NLS-1$
        }
        if (editorPart == null)
        {
            return ToolResult.error("Unable to open an editor for the file").toJson(); //$NON-NLS-1$
        }

        try
        {
            if (!(editorPart instanceof XtextEditor))
            {
                return ToolResult.error("The file is not a BSL module (no Xtext editor available)").toJson(); //$NON-NLS-1$
            }
            XtextEditor xtextEditor = (XtextEditor)editorPart;
            ISourceViewer sourceViewer = xtextEditor.getInternalSourceViewer();
            if (sourceViewer == null)
            {
                return ToolResult.error("Unable to obtain the source viewer").toJson(); //$NON-NLS-1$
            }
            IDocument document = sourceViewer.getDocument();
            if (document == null)
            {
                return ToolResult.error("Unable to obtain the document").toJson(); //$NON-NLS-1$
            }

            int position;
            try
            {
                position = document.getLineOffset(line - 1) + column - 1;
            }
            catch (BadLocationException e)
            {
                return ToolResult.error("Line number is invalid: " + line).toJson(); //$NON-NLS-1$
            }
            if (position < 0 || position > document.getLength())
            {
                return ToolResult.error("The position falls outside the document bounds").toJson(); //$NON-NLS-1$
            }

            if (!(sourceViewer instanceof XtextSourceViewer))
            {
                return ToolResult.error("Source viewer is not an XtextSourceViewer instance").toJson(); //$NON-NLS-1$
            }

            xtextEditor.selectAndReveal(position, 0);

            IContentAssistant assistant =
                xtextEditor.getXtextSourceViewerConfiguration().getContentAssistant(sourceViewer);
            if (assistant == null)
            {
                return ToolResult.error("No content assistant is available").toJson(); //$NON-NLS-1$
            }
            String partitioning =
                xtextEditor.getXtextSourceViewerConfiguration().getConfiguredDocumentPartitioning(sourceViewer);
            String contentType;
            try
            {
                contentType = TextUtilities.getContentType(document, partitioning, position, true);
            }
            catch (BadLocationException e)
            {
                contentType = IDocument.DEFAULT_CONTENT_TYPE;
            }
            IContentAssistProcessor processor = assistant.getContentAssistProcessor(contentType);
            if (processor == null)
            {
                return ToolResult.error("No content-assist processor registered for content type: " //$NON-NLS-1$
                    + contentType).toJson();
            }

            ICompletionProposal[] proposals = processor.computeCompletionProposals(sourceViewer, position);
            if (proposals == null)
            {
                proposals = new ICompletionProposal[0];
            }
            return formatProposals(file, line, column, proposals, limit, offset, contains,
                extendedDocumentation);
        }
        finally
        {
            if (!wasAlreadyOpen)
            {
                page.closeEditor(editorPart, false);
            }
        }
    }

    /**
     * Filters, pages, documents and serializes the proposals.
     *
     * @param file the BSL file
     * @param line the 1-based line
     * @param column the 1-based column
     * @param proposals the proposals the editor produced
     * @param limit how many proposals to keep
     * @param offset how many matching proposals to skip
     * @param contains the comma-separated substring filter, or <code>null</code>
     * @param extendedDocumentation attaches each proposal's help text when set
     * @return the JSON answer
     */
    private String formatProposals(IFile file, int line, int column, ICompletionProposal[] proposals,
        int limit, int offset, String contains, boolean extendedDocumentation)
    {
        List<String> filters = parseContains(contains);

        JsonObject root = new JsonObject();
        root.addProperty("success", Boolean.TRUE); //$NON-NLS-1$
        root.addProperty("file", file.getFullPath().toString()); //$NON-NLS-1$
        root.addProperty("line", Integer.valueOf(line)); //$NON-NLS-1$
        root.addProperty("column", Integer.valueOf(column)); //$NON-NLS-1$

        CopyDown copyDown = extendedDocumentation ? new CopyDown() : null;
        JsonArray items = new JsonArray();
        int filteredOut = 0;
        int skipped = 0;
        int returned = 0;
        for (ICompletionProposal proposal : proposals)
        {
            String displayString = proposal.getDisplayString();
            if (!matchesFilter(displayString, filters))
            {
                filteredOut++;
                continue;
            }
            if (skipped < offset)
            {
                skipped++;
                continue;
            }
            if (returned >= limit)
            {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("displayString", displayString); //$NON-NLS-1$
            if (extendedDocumentation)
            {
                String documentation = proposal.getAdditionalProposalInfo();
                if (documentation != null && !documentation.isEmpty() && !isObjectDump(documentation))
                {
                    item.addProperty("documentation", cleanHtmlToMarkdown(documentation, copyDown)); //$NON-NLS-1$
                }
            }
            items.add(item);
            returned++;
        }

        root.addProperty("totalProposals", Integer.valueOf(proposals.length)); //$NON-NLS-1$
        root.addProperty("filteredOut", Integer.valueOf(filteredOut)); //$NON-NLS-1$
        root.addProperty("skipped", Integer.valueOf(skipped)); //$NON-NLS-1$
        root.addProperty("returnedProposals", Integer.valueOf(returned)); //$NON-NLS-1$
        root.add("proposals", items); //$NON-NLS-1$
        return GsonHolder.toJson(root);
    }

    /**
     * Tells whether a documentation string is really a model object that printed itself.
     * <p>
     * Some proposals have no documentation to give and hand back the element instead, which arrives
     * as Java's default rendering - {@code ProposalElementImpl@2194540c}, or a function's whole
     * internal state trailing after it. That is of no use to any caller, and for a function it puts
     * the model's insides on the wire. An answer with no documentation is the honest one.
     * </p>
     *
     * @param text the documentation the proposal returned
     * @return <code>true</code> when the text is an object rendering rather than documentation
     */
    static boolean isObjectDump(String text)
    {
        if (text == null)
        {
            return false;
        }
        int at = text.indexOf('@');
        if (at <= 0)
        {
            return false;
        }
        for (int i = 0; i < at; i++)
        {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '.' && c != '_' && c != '$')
            {
                return false;
            }
        }
        // A default rendering continues with the identity hash; anything else here is real text that
        // merely happens to contain an at-sign, such as an address in a comment.
        int digits = 0;
        for (int i = at + 1; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))
            {
                digits++;
                continue;
            }
            break;
        }
        return digits > 0;
    }

    /**
     * Splits the {@code contains} filter into lowercased, non-empty parts.
     *
     * @param contains the comma-separated filter, or <code>null</code>
     * @return the parts, possibly empty
     */
    private static List<String> parseContains(String contains)
    {
        List<String> parts = new ArrayList<>();
        if (contains == null || contains.isEmpty())
        {
            return parts;
        }
        for (String part : contains.split(",")) //$NON-NLS-1$
        {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty())
            {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    /**
     * Tells whether a proposal passes the {@code contains} filter.
     *
     * @param displayString the proposal display string
     * @param filters the lowercased substrings; an empty list keeps everything
     * @return <code>true</code> when the proposal is kept
     */
    private static boolean matchesFilter(String displayString, List<String> filters)
    {
        if (filters.isEmpty())
        {
            return true;
        }
        String lower = displayString.toLowerCase(Locale.ROOT);
        for (String filter : filters)
        {
            if (lower.contains(filter))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Cleans proposal documentation from HTML to Markdown.
     *
     * @param html the documentation HTML
     * @param copyDown the converter to use
     * @return the cleaned text
     */
    private static String cleanHtmlToMarkdown(String html, CopyDown copyDown)
    {
        try
        {
            String withoutStyle = html.replaceAll("(?is)<style.*?</style>", ""); //$NON-NLS-1$ //$NON-NLS-2$
            String markdown = copyDown.convert(withoutStyle);
            markdown = markdown.replaceAll("\n{3,}", "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            return markdown.trim();
        }
        catch (Exception e)
        {
            return html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
    }
}
