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

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.model.Module;

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
import ru.aiedt.mcp.server.support.BslProposalAccess;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Asks the language what could be typed at a position and reports the completion proposals as JSON.
 * <p>
 * No editor is involved. The module's model is read through the plugin's usual BM-aware resource set,
 * its cross-references are resolved so that member proposals after a dot know what they are members of,
 * and the language's own proposal provider is asked directly. Nothing opens, nothing takes focus, and
 * nothing runs on the thread that draws the workbench.
 * </p>
 * <p>
 * The answer is about the module as saved. Unsaved edits sitting in someone's open editor are not
 * seen - and deliberately so: the text could be taken from the live buffer without an editor, but the
 * model could not, and answering about new text against an old parse is worse than answering about
 * the saved module consistently.
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

        try
        {
            return collectProposals(project, file, filePath, line, column, limit, offset, contains,
                extendedDocumentation);
        }
        catch (Exception e)
        {
            Activator.logError("get_content_assist tool failed", e); //$NON-NLS-1$
            return ToolResult.error("Unhandled exception: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Answers the question without an editor: the module's model is loaded through the same BM-aware
     * resource set the rest of the plugin reads BSL with, and the language's own proposal provider is
     * asked directly.
     * <p>
     * What the editor contributed was a viewer and a document. Both are supplied without a screen -
     * see {@link ru.aiedt.mcp.server.support.BslProposalAccess} - so a survey walking hundreds of
     * positions no longer opens a tab, moves the caret, or takes a turn on the thread that draws the
     * window of whoever is working in the same EDT.
     * </p>
     *
     * @param project the project the module belongs to
     * @param file the workspace file
     * @param filePath the {@code src/}-relative file path
     * @param line the 1-based line
     * @param column the 1-based column
     * @param limit how many proposals to keep
     * @param offset how many matching proposals to skip
     * @param contains the comma-separated substring filter, or <code>null</code>
     * @param extendedDocumentation attaches each proposal's help text when set
     * @return the JSON answer
     * @throws Exception when the module cannot be read
     */
    private String collectProposals(IProject project, IFile file, String filePath, int line, int column,
        int limit, int offset, String contains, boolean extendedDocumentation) throws Exception
    {
        String text = BslModuleAccess.readFileText(file);

        // A Document here is the plain text model of jface.text, not a widget - line arithmetic only.
        IDocument lines = new Document(text);
        int position;
        try
        {
            position = lines.getLineOffset(line - 1) + column - 1;
        }
        catch (BadLocationException e)
        {
            return ToolResult.error("Line number is invalid: " + line).toJson(); //$NON-NLS-1$
        }
        if (position < 0 || position > lines.getLength())
        {
            return ToolResult.error("The position falls outside the document bounds").toJson(); //$NON-NLS-1$
        }

        Module module = BslModuleAccess.loadModule(project, filePath);
        Resource resource = module == null ? null : module.eResource();
        if (!(resource instanceof XtextResource))
        {
            return ToolResult.error("The file is not a BSL module, or its model is not built yet").toJson(); //$NON-NLS-1$
        }
        XtextResource xtextResource = (XtextResource)resource;

        // What can be typed after a dot depends on the type of what precedes it, and types arrive with
        // the module's cross-references. The editor resolved them by having the module open.
        BslModuleAccess.resolveCrossReferences(xtextResource);

        ICompletionProposal[] proposals;
        try
        {
            proposals = BslProposalAccess.proposalsAt(text, xtextResource, position);
        }
        catch (IllegalStateException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }
        return formatProposals(file, line, column, proposals, limit, offset, contains,
            extendedDocumentation);
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
