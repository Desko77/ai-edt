/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextHoverExtension2;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;

import io.github.furstenheim.CopyDown;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.YamlFrontMatter;
import ru.aiedt.mcp.server.support.BslHoverAccess;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.ReflectionAccess;

/**
 * Reports type and hover information for the symbol at a given position in a BSL module. Two
 * resolution levels are tried in turn on the UI thread: first the BSL text hover (the tooltip a user
 * sees in the editor), then the EMF element under the offset. When no workbench is available, an EMF
 * fallback reads the model and the raw file bytes directly.
 */
public class SymbolInfoReader
    implements IMcpTool
{
    /** The tool name, also the registry key. */
    public static final String NAME = "get_symbol_info"; //$NON-NLS-1$

    private static final String DESCRIPTION =
        "Back-compat alias of `code_search` `operation=symbol_info`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Reports type and hover details for the symbol located at a given line and column in a BSL module. " //$NON-NLS-1$
            + "Includes resolved types, method signatures, and any available documentation. " //$NON-NLS-1$
            + "Answers about the module as saved - unsaved editor changes are not seen. " //$NON-NLS-1$
            + "Pass positions to ask about several places in one call."; //$NON-NLS-1$

    /** How many positions one call answers; beyond this the caller is asked to split the batch. */
    static final int POSITIONS_MAX = 1000;

    /** A dummy {@code .bsl} URI, used only to look up the BSL language services from the Xtext registry. */
    private static final URI BSL_LOOKUP_URI = URI.createURI("dummy.bsl"); //$NON-NLS-1$

    /**
     * HTML-to-Markdown converter, thread-confined to the UI thread (the tool only ever runs there).
     */
    private CopyDown copyDown;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return DESCRIPTION;
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("filePath", "Path to the BSL module relative to the project's src folder, e.g. 'CommonModules/MyModule/Module.bsl'", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("line", "Line number, counting from 1. Not needed when positions is given.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("column", "Column number, counting from 1. Not needed when positions is given.") //$NON-NLS-1$ //$NON-NLS-2$
            .arrayProperty("positions", //$NON-NLS-1$
                "Several positions in the same module, answered in one call and reported in the "
                    + "order given. Each entry is either \"line:column\" or {\"line\":N,\"column\":M}. "
                    + "The module is loaded and its cross-references resolved once for the whole "
                    + "batch, which is the expensive part - so a batch costs about what a single "
                    + "call costs. A position with no symbol yields its own note rather than "
                    + "failing the call.")
            .booleanProperty("computeTypes", //$NON-NLS-1$
                "Resolve the module's cross-references first so the answer names the type of the "
                    + "symbol (default true). Set false to skip that step when only the name and "
                    + "documentation are wanted and the module is large.")
            .build();
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        if (JsonUtils.extractStringArgument(params, "positions") != null) //$NON-NLS-1$
        {
            return "symbol-info-batch.md"; //$NON-NLS-1$
        }
        String line = JsonUtils.extractStringArgument(params, "line"); //$NON-NLS-1$
        String column = JsonUtils.extractStringArgument(params, "column"); //$NON-NLS-1$
        return "symbol-info-" + (line != null ? line : "0") + "-" + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            (column != null ? column : "0") + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$
        String lineStr = JsonUtils.extractStringArgument(params, "line"); //$NON-NLS-1$
        String columnStr = JsonUtils.extractStringArgument(params, "column"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName parameter is missing"; //$NON-NLS-1$
        }
        if (filePath == null || filePath.isEmpty())
        {
            return "Error: filePath parameter is missing"; //$NON-NLS-1$
        }

        boolean computeTypes = !"false".equalsIgnoreCase(
            JsonUtils.extractStringArgument(params, "computeTypes")); //$NON-NLS-1$

        List<int[]> positions;
        try
        {
            positions = parsePositions(JsonUtils.extractStringArgument(params, "positions")); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            return "Error: " + e.getMessage(); //$NON-NLS-1$
        }
        if (positions != null)
        {
            return getSymbolInfoAt(projectName, filePath, positions, computeTypes);
        }

        int line;
        int column;
        try
        {
            line = (int)Double.parseDouble(lineStr);
            column = (int)Double.parseDouble(columnStr);
        }
        catch (NumberFormatException | NullPointerException e)
        {
            return "Error: line or column value is not a valid number"; //$NON-NLS-1$
        }

        if (line < 1 || column < 1)
        {
            return "Error: line and column must both be at least 1"; //$NON-NLS-1$
        }

        return getSymbolInfo(projectName, filePath, line, column, computeTypes);
    }

    /**
     * Resolves the project and file, runs the UI-thread resolution, and falls back to EMF when the
     * workbench cannot serve the editor.
     *
     * @param projectName the project name
     * @param filePath the {@code src/}-relative file path
     * @param line the 1-based line
     * @param column the 1-based column
     * @return the Markdown report, with front matter on non-error results
     */
    private String getSymbolInfo(String projectName, String filePath, int line, int column,
        boolean computeTypes)
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject project = workspace.getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }
        if (!project.isOpen())
        {
            return "Error: Project is not open: " + projectName; //$NON-NLS-1$
        }

        IPath relativePath = new Path("src").append(filePath); //$NON-NLS-1$
        IFile file = project.getFile(relativePath);
        if (!file.exists())
        {
            return "Error: No such file: " + relativePath.toString() + " within project " + projectName; //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Answered without an editor and without the UI thread. The hover this returns is the same
        // text the environment shows on a mouse-over, but asked of the language's hover service
        // directly - so a survey walking hundreds of positions no longer opens a tab, takes the
        // focus, or makes the workbench pause under whoever is typing in it.
        AtomicBoolean modelPathUnavailable = new AtomicBoolean();
        String result = readWithoutEditor(project, file, line, column, filePath, computeTypes, modelPathUnavailable);

        if (result == null || modelPathUnavailable.get())
        {
            String emfResult = getSymbolInfoViaEmf(project, filePath, line, column);
            if (emfResult != null)
            {
                result = emfResult;
            }
        }

        if (result == null)
        {
            return "Error: Unable to retrieve symbol info"; //$NON-NLS-1$
        }
        if (result.startsWith("Error:")) //$NON-NLS-1$
        {
            return result;
        }

        YamlFrontMatter fm = YamlFrontMatter.create()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("module", filePath) //$NON-NLS-1$
            .put("line", line) //$NON-NLS-1$
            .put("column", column); //$NON-NLS-1$
        return fm.wrapContent(result);
    }

    /**
     * Answers several positions in one module, in the order they were given.
     * <p>
     * The saving is not in the round trips - each answer is small - but in the preparation. Reading the
     * module and resolving its cross-references is what costs, and it costs the same whether one
     * position is asked about or three hundred. A survey that walks a module position by position pays
     * that once here instead of once per position.
     * </p>
     * <p>
     * A position that resolves to nothing gets its own note. Nothing about one position is allowed to
     * cost the caller the answers to the others.
     * </p>
     *
     * @param projectName the project name
     * @param filePath the {@code src/}-relative file path
     * @param positions the positions, each a line and a column counting from 1
     * @param computeTypes resolves the module's cross-references, without which no type is named
     * @return the Markdown report, one section per position
     */
    private String getSymbolInfoAt(String projectName, String filePath, List<int[]> positions,
        boolean computeTypes)
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject project = workspace.getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }
        if (!project.isOpen())
        {
            return "Error: Project is not open: " + projectName; //$NON-NLS-1$
        }

        IPath relativePath = new Path("src").append(filePath); //$NON-NLS-1$
        IFile file = project.getFile(relativePath);
        if (!file.exists())
        {
            return "Error: No such file: " + relativePath.toString() + " within project " + projectName; //$NON-NLS-1$ //$NON-NLS-2$
        }

        AtomicBoolean modelPathUnavailable = new AtomicBoolean();
        ModuleUnderQuestion module = open(project, file, filePath, computeTypes, modelPathUnavailable);
        if (module.failure != null)
        {
            return module.failure;
        }

        // Falling back to the metadata model costs a module load and a file read per position, which
        // is exactly what a batch exists to avoid. When the model is not built the fallback cannot
        // answer any position, so that is settled once here rather than a thousand times in the loop.
        if (module.resource == null && BslModuleAccess.loadModule(project, filePath) == null)
        {
            return "Error: the module model is not available yet - the project may still be " //$NON-NLS-1$
                + "building. Nothing was asked position by position, because the answer would be " //$NON-NLS-1$
                + "the same for all " + positions.size() + " of them."; //$NON-NLS-1$ //$NON-NLS-2$
        }

        StringBuilder body = new StringBuilder();
        for (int[] position : positions)
        {
            String answer = module.resource == null ? null : module.answerAt(position[0], position[1]);
            if (answer == null)
            {
                answer = getSymbolInfoViaEmf(project, filePath, position[0], position[1]);
            }
            if (answer == null)
            {
                answer = "Error: Unable to retrieve symbol info"; //$NON-NLS-1$
            }
            body.append("## ").append(position[0]).append(":").append(position[1]).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            body.append(answer.trim()).append("\n\n"); //$NON-NLS-1$
        }

        YamlFrontMatter fm = YamlFrontMatter.create()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("module", filePath) //$NON-NLS-1$
            .put("positions", positions.size()); //$NON-NLS-1$
        return fm.wrapContent(body.toString().trim());
    }

    /**
     * Reads the positions argument, accepting either spelling a caller might reach for.
     * <p>
     * A JSON array of objects is the shape a program sends; a list of {@code "line:column"} tokens is
     * what a person types. Both are read, because refusing one of them would teach the caller a
     * spelling rather than answer the question.
     * </p>
     *
     * @param raw the argument as it arrived, or <code>null</code>
     * @return the positions, or <code>null</code> when the argument was not given
     * @throws IllegalArgumentException when the argument is given but cannot be read
     */
    static List<int[]> parsePositions(String raw)
    {
        if (raw == null || raw.trim().isEmpty())
        {
            return null;
        }
        String value = raw.trim();
        List<int[]> positions = new ArrayList<>();
        if (value.startsWith("[")) //$NON-NLS-1$
        {
            JsonElement parsed;
            try
            {
                parsed = JsonParser.parseString(value);
            }
            catch (JsonParseException e)
            {
                throw new IllegalArgumentException("positions is not valid JSON: " + e.getMessage()); //$NON-NLS-1$
            }
            if (!parsed.isJsonArray())
            {
                throw new IllegalArgumentException("positions must be an array"); //$NON-NLS-1$
            }
            for (JsonElement element : parsed.getAsJsonArray())
            {
                positions.add(readPosition(element));
            }
        }
        else
        {
            for (String token : value.split(",")) //$NON-NLS-1$
            {
                if (!token.trim().isEmpty())
                {
                    positions.add(readPair(token.trim()));
                }
            }
        }

        if (positions.isEmpty())
        {
            throw new IllegalArgumentException("positions is empty"); //$NON-NLS-1$
        }
        if (positions.size() > POSITIONS_MAX)
        {
            throw new IllegalArgumentException("positions holds " + positions.size() //$NON-NLS-1$
                + " entries; at most " + POSITIONS_MAX + " are answered in one call"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return positions;
    }

    /**
     * @param element one entry of the positions array
     * @return the line and column
     * @throws IllegalArgumentException when the entry is not a position
     */
    private static int[] readPosition(JsonElement element)
    {
        if (element.isJsonObject())
        {
            JsonObject object = element.getAsJsonObject();
            if (!object.has("line") || !object.has("column")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                throw new IllegalArgumentException("a position needs both line and column"); //$NON-NLS-1$
            }
            return checked(object.get("line").getAsInt(), object.get("column").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (element.isJsonArray() && element.getAsJsonArray().size() == 2)
        {
            return checked(element.getAsJsonArray().get(0).getAsInt(),
                element.getAsJsonArray().get(1).getAsInt());
        }
        if (element.isJsonPrimitive())
        {
            return readPair(element.getAsString().trim());
        }
        throw new IllegalArgumentException("a position is either a line:column pair or {line, column}"); //$NON-NLS-1$
    }

    /**
     * @param token a {@code "line:column"} pair
     * @return the line and column
     * @throws IllegalArgumentException when the token is not such a pair
     */
    private static int[] readPair(String token)
    {
        int separator = token.indexOf(':');
        if (separator < 0)
        {
            throw new IllegalArgumentException(token + " is not a line:column pair"); //$NON-NLS-1$
        }
        try
        {
            return checked(Integer.parseInt(token.substring(0, separator).trim()),
                Integer.parseInt(token.substring(separator + 1).trim()));
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException(token + " is not a line:column pair"); //$NON-NLS-1$
        }
    }

    /**
     * @param line the 1-based line
     * @param column the 1-based column
     * @return the pair
     * @throws IllegalArgumentException when either number is below 1
     */
    private static int[] checked(int line, int column)
    {
        if (line < 1 || column < 1)
        {
            throw new IllegalArgumentException("line and column must both be at least 1, got " //$NON-NLS-1$
                + line + ":" + column); //$NON-NLS-1$
        }
        return new int[] {line, column};
    }

    /**
     * Resolves the symbol without an editor: the module's model is loaded through the same
     * BM-aware resource set the rest of the plugin reads BSL with, and the hover is asked of the
     * language's hover service directly.
     * <p>
     * The editor was never the point - it was only a way to reach a parsed module and a hover. Both
     * are available without it, and doing it this way costs the person working in EDT nothing: no
     * tab opens, no focus moves, and nothing runs on the thread that draws their window.
     * </p>
     *
     * @param project the project the module belongs to
     * @param file the workspace file
     * @param line the 1-based line
     * @param column the 1-based column
     * @param filePath the {@code src/}-relative file path
     * @param modelPathUnavailable raised when the module model could not be reached at all, which is
     *            the caller's signal to retry the same question against the metadata model
     * @return the resolved info, or an error string
     */
    private String readWithoutEditor(IProject project, IFile file, int line, int column, String filePath,
        boolean computeTypes, AtomicBoolean modelPathUnavailable)
    {
        ModuleUnderQuestion module = open(project, file, filePath, computeTypes, modelPathUnavailable);
        if (module.failure != null)
        {
            return module.failure;
        }
        if (module.resource == null)
        {
            return null;
        }
        return module.answerAt(line, column);
    }

    /**
     * Reads a module once so that any number of positions can be asked of it.
     * <p>
     * Both halves of the preparation cost something, and the second one costs a lot: the text has to be
     * read off disk, and the module's cross-references have to be resolved before any type can be
     * named. Neither depends on which position is asked about, so both are done once here and reused.
     * </p>
     *
     * @param project the project the module belongs to
     * @param file the workspace file
     * @param filePath the {@code src/}-relative file path
     * @param computeTypes resolves the cross-references, without which no type is named
     * @param modelPathUnavailable raised when the module model could not be reached at all, which is
     *            the caller's signal to retry the same question against the metadata model
     * @return the opened module, which may carry a failure instead of a resource
     */
    private ModuleUnderQuestion open(IProject project, IFile file, String filePath, boolean computeTypes,
        AtomicBoolean modelPathUnavailable)
    {
        String text;
        try
        {
            text = BslModuleAccess.readFileText(file);
        }
        catch (Exception e)
        {
            modelPathUnavailable.set(true);
            return ModuleUnderQuestion.failed("Error: the module could not be read: " + e.getMessage()); //$NON-NLS-1$
        }

        // A Document here is the plain text model of jface.text, not a widget - it is used for the
        // line arithmetic only, and nothing about it reaches the screen.
        IDocument document = new Document(text);

        Module module = BslModuleAccess.loadModule(project, filePath);
        Resource resource = module == null ? null : module.eResource();
        if (!(resource instanceof XtextResource))
        {
            modelPathUnavailable.set(true);
            return new ModuleUnderQuestion(this, document, null);
        }
        XtextResource xtextResource = (XtextResource)resource;

        if (computeTypes)
        {
            BslModuleAccess.resolveCrossReferences(xtextResource);
        }
        return new ModuleUnderQuestion(this, document, xtextResource);
    }

    /**
     * A module read once, ready to answer about any position in it.
     */
    private static final class ModuleUnderQuestion
    {
        private final SymbolInfoReader reader;

        private final IDocument document;

        private final XtextResource resource;

        private final String failure;

        ModuleUnderQuestion(SymbolInfoReader reader, IDocument document, XtextResource resource)
        {
            this.reader = reader;
            this.document = document;
            this.resource = resource;
            this.failure = null;
        }

        private ModuleUnderQuestion(String failure)
        {
            this.reader = null;
            this.document = null;
            this.resource = null;
            this.failure = failure;
        }

        static ModuleUnderQuestion failed(String failure)
        {
            return new ModuleUnderQuestion(failure);
        }

        /**
         * @param line the 1-based line
         * @param column the 1-based column
         * @return the resolved info for that position, or an error string
         */
        String answerAt(int line, int column)
        {
            int offset;
            try
            {
                offset = this.document.getLineOffset(line - 1) + column - 1;
            }
            catch (BadLocationException e)
            {
                return "Error: Line number is invalid: " + line; //$NON-NLS-1$
            }
            if (offset < 0 || offset > this.document.getLength())
            {
                return "Error: The position falls outside the document bounds"; //$NON-NLS-1$
            }
            if (!this.reader.hasTokenAtPosition(this.document, offset, line))
            {
                return "There is no symbol at this position."; //$NON-NLS-1$
            }

            EObject element = null;
            EObjectAtOffsetHelper offsetHelper = this.reader.getOffsetHelper();
            if (offsetHelper != null)
            {
                element = offsetHelper.resolveElementAt(this.resource, offset);
            }
            String hoverHtml = BslHoverAccess.hoverHtml(element, offset);
            if (hoverHtml != null)
            {
                String markdown = this.reader.cleanHtmlToMarkdown(hoverHtml);
                if (markdown != null && !markdown.isEmpty())
                {
                    return markdown;
                }
            }

            String described = this.reader.resolveEObjectInfo(this.resource, offset);
            if (described != null && !described.isEmpty())
            {
                return described;
            }
            return "No symbol could be resolved at this position.\n"; //$NON-NLS-1$
        }
    }

    /**
     * Attempts to read the BSL text hover (the editor tooltip) at the offset and convert it to
     * Markdown.
     *
     * @param sourceViewer the source viewer
     * @param offset the character offset
     * @return the hover info as Markdown, or {@code null} when nothing is available
     */
    private String tryGetHoverInfo(ISourceViewer sourceViewer, int offset)
    {
        try
        {
            ITextHover textHover = getTextHoverViaReflection(sourceViewer, offset);
            if (textHover == null)
            {
                return null;
            }
            IRegion hoverRegion = textHover.getHoverRegion(sourceViewer, offset);
            if (hoverRegion == null)
            {
                return null;
            }
            if (textHover instanceof ITextHoverExtension2)
            {
                Object info2 = ((ITextHoverExtension2)textHover).getHoverInfo2(sourceViewer, hoverRegion);
                if (info2 != null)
                {
                    String infoStr = extractHoverContent(info2);
                    if (infoStr != null && !infoStr.isEmpty())
                    {
                        return cleanHtmlToMarkdown(infoStr);
                    }
                }
            }
            @SuppressWarnings("deprecation")
            String hoverInfo = textHover.getHoverInfo(sourceViewer, hoverRegion);
            if (hoverInfo != null && !hoverInfo.isEmpty())
            {
                return cleanHtmlToMarkdown(hoverInfo);
            }
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("Unable to retrieve hover info: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Reaches the source viewer's text hover through reflection, since the BSL editor does not expose
     * it through a published API.
     *
     * @param sourceViewer the source viewer
     * @param offset the character offset
     * @return the text hover, or {@code null}
     */
    private ITextHover getTextHoverViaReflection(ISourceViewer sourceViewer, int offset)
    {
        try
        {
            java.lang.reflect.Method m =
                ReflectionAccess.findMethod(sourceViewer.getClass(), "getTextHover", int.class, int.class); //$NON-NLS-1$
            if (m != null)
            {
                m.setAccessible(true);
                Object result = m.invoke(sourceViewer, offset, 0);
                if (result instanceof ITextHover)
                {
                    return (ITextHover)result;
                }
            }
            Object hoverField = ReflectionAccess.getFieldValue(sourceViewer, "fTextHover"); //$NON-NLS-1$
            if (hoverField instanceof ITextHover)
            {
                return (ITextHover)hoverField;
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Reflection-based text hover lookup failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Extracts a string payload from a hover info object of unknown shape.
     *
     * @param hoverInfo the hover info
     * @return the string payload
     */
    private String extractHoverContent(Object hoverInfo)
    {
        if (hoverInfo instanceof String)
        {
            return (String)hoverInfo;
        }
        try
        {
            Object html = ReflectionAccess.invokeMethod(hoverInfo, "getHtml"); //$NON-NLS-1$
            if (html instanceof String)
            {
                return (String)html;
            }
        }
        catch (Exception e)
        {
            // ignore - try the next strategy
        }
        try
        {
            Object inputElement = ReflectionAccess.invokeMethod(hoverInfo, "getInputElement"); //$NON-NLS-1$
            if (inputElement != null)
            {
                return inputElement.toString();
            }
        }
        catch (Exception e)
        {
            // ignore - fall through to toString
        }
        return hoverInfo.toString();
    }

    /**
     * Resolves the EMF element at the offset and builds the info table from the model.
     *
     * @param resource the Xtext resource
     * @param offset the character offset
     * @return the info table, or {@code null} when nothing resolves
     */
    private String resolveEObjectInfo(XtextResource resource, int offset)
    {
        try
        {
            EObjectAtOffsetHelper offsetHelper = getOffsetHelper();
            if (offsetHelper != null)
            {
                EObject element = offsetHelper.resolveElementAt(resource, offset);
                if (element != null)
                {
                    return buildEObjectInfo(element);
                }
                element = offsetHelper.resolveContainedElementAt(resource, offset);
                if (element != null)
                {
                    return buildEObjectInfo(element);
                }
            }

            ICompositeNode rootNode =
                resource.getParseResult() != null ? resource.getParseResult().getRootNode() : null;
            if (rootNode != null)
            {
                ILeafNode leafNode = NodeModelUtils.findLeafNodeAtOffset(rootNode, offset);
                if (leafNode != null)
                {
                    EObject semanticElement = NodeModelUtils.findActualSemanticObjectFor(leafNode);
                    if (semanticElement != null)
                    {
                        return buildEObjectInfo(semanticElement);
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("| Field | Value |\n"); //$NON-NLS-1$
                    sb.append("|----------|-------|\n"); //$NON-NLS-1$
                    sb.append("| **Token** | `").append(leafNode.getText()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    sb.append("| **Grammar** | ") //$NON-NLS-1$
                        .append(leafNode.getGrammarElement() != null
                            ? leafNode.getGrammarElement().eClass().getName()
                            : "-") //$NON-NLS-1$
                        .append(" |\n"); //$NON-NLS-1$
                    return sb.toString();
                }
            }
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to resolve EObject: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Obtains the BSL {@link EObjectAtOffsetHelper} from the language services, with a plain fallback.
     *
     * @return the helper, never {@code null}
     */
    private EObjectAtOffsetHelper getOffsetHelper()
    {
        try
        {
            IResourceServiceProvider rsp =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(BSL_LOOKUP_URI);
            if (rsp != null)
            {
                EObjectAtOffsetHelper helper = rsp.get(EObjectAtOffsetHelper.class);
                if (helper != null)
                {
                    return helper;
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Unable to obtain EObjectAtOffsetHelper: " + e.getMessage()); //$NON-NLS-1$
        }
        return new EObjectAtOffsetHelper();
    }

    /**
     * Builds the property/value table describing an EMF element. The rows are fixed per element kind;
     * the method branch deliberately shows only symbol, kind, signature, export, lines and parameters.
     *
     * @param element the resolved element
     * @return the info table
     */
    private String buildEObjectInfo(EObject element)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("| Field | Value |\n"); //$NON-NLS-1$
        sb.append("|----------|-------|\n"); //$NON-NLS-1$

        if (element instanceof Method)
        {
            Method method = (Method)element;
            boolean isFunction = element instanceof Function;
            sb.append("| **Symbol** | `").append(method.getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("| Symbol kind | ").append(isFunction ? "Function" : "Procedure").append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            sb.append("| **Signature** | `").append(BslModuleAccess.buildSignature(method)).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("| Exported | ").append(method.isExport() ? "Yes" : "No").append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            int startLine = BslModuleAccess.getStartLine(method);
            int endLine = BslModuleAccess.getEndLine(method);
            if (startLine > 0)
            {
                sb.append("| Line span | ").append(startLine); //$NON-NLS-1$
                if (endLine > startLine)
                {
                    sb.append(" - ").append(endLine); //$NON-NLS-1$
                }
                sb.append(" |\n"); //$NON-NLS-1$
            }
            String params = BslModuleAccess.buildParamsString(method);
            if (params != null && !params.isEmpty())
            {
                sb.append("| **Parameters** | ").append(params).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        else if (element instanceof FormalParam)
        {
            FormalParam param = (FormalParam)element;
            sb.append("| **Symbol** | `").append(param.getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("| Symbol kind | Parameter |\n"); //$NON-NLS-1$
            sb.append("| Passed by value | ").append(param.isByValue() ? "Yes" : "No").append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            EObject container = param.eContainer();
            if (container instanceof Method)
            {
                sb.append("| **In method** | `").append(((Method)container).getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        else if (element instanceof StaticFeatureAccess)
        {
            StaticFeatureAccess sfa = (StaticFeatureAccess)element;
            sb.append("| **Symbol** | `").append(sfa.getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("| Symbol kind | StaticFeatureAccess |\n"); //$NON-NLS-1$
            sb.append("| **EMF type** | ").append(sfa.eClass().getName()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            EObject container = findContainingMethod(sfa);
            if (container instanceof Method)
            {
                sb.append("| **In method** | `").append(((Method)container).getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        else if (element instanceof DynamicFeatureAccess)
        {
            DynamicFeatureAccess dfa = (DynamicFeatureAccess)element;
            sb.append("| **Symbol** | `").append(dfa.getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("| Symbol kind | DynamicFeatureAccess |\n"); //$NON-NLS-1$
            sb.append("| **EMF type** | ").append(dfa.eClass().getName()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            EObject container = findContainingMethod(dfa);
            if (container instanceof Method)
            {
                sb.append("| **In method** | `").append(((Method)container).getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        else if (element instanceof Invocation)
        {
            Invocation invocation = (Invocation)element;
            sb.append("| Symbol kind | Invocation |\n"); //$NON-NLS-1$
            sb.append("| **EMF type** | ").append(invocation.eClass().getName()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            EObject methodAccess = invocation.getMethodAccess();
            if (methodAccess instanceof StaticFeatureAccess)
            {
                sb.append("| **Symbol** | `") //$NON-NLS-1$
                    .append(((StaticFeatureAccess)methodAccess).getName()).append("` |\n"); //$NON-NLS-1$
            }
            else if (methodAccess instanceof DynamicFeatureAccess)
            {
                sb.append("| **Symbol** | `") //$NON-NLS-1$
                    .append(((DynamicFeatureAccess)methodAccess).getName()).append("` |\n"); //$NON-NLS-1$
            }
        }
        else if (element instanceof Module)
        {
            sb.append("| Symbol kind | Module |\n"); //$NON-NLS-1$
            sb.append("| **EMF type** | ").append(element.eClass().getName()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            sb.append("| Symbol kind | ").append(element.eClass().getName()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                Object name = ReflectionAccess.invokeMethod(element, "getName"); //$NON-NLS-1$
                if (name instanceof String && !((String)name).isEmpty())
                {
                    sb.append("| **Symbol** | `").append(name).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            catch (Exception e)
            {
                // no name available - skip the row
            }
            EObject container = findContainingMethod(element);
            if (container instanceof Method)
            {
                sb.append("| **In method** | `").append(((Method)container).getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            int startLine = BslModuleAccess.getStartLine(element);
            if (startLine > 0)
            {
                sb.append("| At line | ").append(startLine).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        return sb.toString();
    }

    /**
     * Walks the containment chain up from an element to the nearest enclosing method.
     *
     * @param element the starting element
     * @return the enclosing method, or {@code null}
     */
    private EObject findContainingMethod(EObject element)
    {
        EObject current = element.eContainer();
        while (current != null)
        {
            if (current instanceof Method)
            {
                return current;
            }
            current = current.eContainer();
        }
        return null;
    }

    /**
     * Tells whether a non-whitespace, non-comment character sits at the offset.
     *
     * @param document the document
     * @param offset the character offset
     * @param line the 1-based line
     * @return {@code true} when a token is present at the position
     */
    private boolean hasTokenAtPosition(IDocument document, int offset, int line)
    {
        try
        {
            if (offset >= document.getLength())
            {
                return false;
            }
            char ch = document.getChar(offset);
            if (Character.isWhitespace(ch))
            {
                return false;
            }
            int lineIndex = line - 1;
            int lineOffset = document.getLineOffset(lineIndex);
            int lineLength = document.getLineLength(lineIndex);
            String lineText = document.get(lineOffset, lineLength);
            int commentStart = findCommentStart(lineText);
            if (commentStart >= 0)
            {
                int columnInLine = offset - lineOffset;
                if (columnInLine >= commentStart)
                {
                    return false;
                }
            }
            return true;
        }
        catch (BadLocationException e)
        {
            return false;
        }
    }

    /**
     * Finds the column index where a line comment begins, honoring double-quoted strings.
     *
     * @param lineText the line text
     * @return the index of the {@code //}, or -1 when the line has no comment
     */
    private int findCommentStart(String lineText)
    {
        boolean inString = false;
        for (int i = 0; i < lineText.length() - 1; i++)
        {
            char ch = lineText.charAt(i);
            if (ch == '"')
            {
                inString = !inString;
            }
            else if (!inString && ch == '/' && lineText.charAt(i + 1) == '/')
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * EMF fallback used when the workbench cannot serve the editor: loads the model and reads the raw
     * file to compute the offset, then resolves the element from the node model.
     *
     * @param project the project
     * @param filePath the {@code src/}-relative file path
     * @param line the 1-based line
     * @param column the 1-based column
     * @return the info table, or {@code null} on failure
     */
    private String getSymbolInfoViaEmf(IProject project, String filePath, int line, int column)
    {
        try
        {
            Module module = BslModuleAccess.loadModule(project, filePath);
            if (module == null)
            {
                return null;
            }

            IPath relativePath = new Path("src").append(filePath); //$NON-NLS-1$
            IFile file = project.getFile(relativePath);
            String content;
            try (InputStream is = file.getContents())
            {
                content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (content.length() > 0 && content.charAt(0) == '﻿')
            {
                content = content.substring(1); // strip UTF-8 BOM
            }
            if (content == null)
            {
                return null;
            }

            int offset = 0;
            int currentLine = 1;
            for (int i = 0; i < content.length() && currentLine < line; i++)
            {
                char ch = content.charAt(i);
                if (ch == '\r')
                {
                    currentLine++;
                    if (i + 1 < content.length() && content.charAt(i + 1) == '\n')
                    {
                        i++;
                    }
                }
                else if (ch == '\n')
                {
                    currentLine++;
                }
                offset = i + 1;
            }
            offset += Math.max(0, column - 1);

            ICompositeNode rootNode = NodeModelUtils.getNode(module);
            if (rootNode == null)
            {
                return null;
            }
            ILeafNode leafNode = NodeModelUtils.findLeafNodeAtOffset(rootNode, offset);
            if (leafNode == null)
            {
                return null;
            }
            EObject semanticElement = NodeModelUtils.findActualSemanticObjectFor(leafNode);
            if (semanticElement != null)
            {
                return buildEObjectInfo(semanticElement);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("| Field | Value |\n"); //$NON-NLS-1$
            sb.append("|----------|-------|\n"); //$NON-NLS-1$
            sb.append("| **Token** | `").append(leafNode.getText()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            return sb.toString();
        }
        catch (Exception e)
        {
            Activator.logWarning("EMF fallback path failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Converts an HTML hover string to Markdown, stripping style blocks and collapsing runs of blank
     * lines. Falls back to a tag-stripping pass when the converter fails.
     *
     * @param html the HTML
     * @return the Markdown, never {@code null}
     */
    private String cleanHtmlToMarkdown(String html)
    {
        if (html == null || html.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        try
        {
            String cleaned = html.replaceAll("(?s)<style[^>]*>.*?</style>", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (copyDown == null)
            {
                copyDown = new CopyDown();
            }
            String markdown = copyDown.convert(cleaned);
            markdown = markdown.replaceAll("\n{3,}", "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            return markdown.trim();
        }
        catch (Exception e)
        {
            Activator.logError("Failed converting HTML to Markdown", e); //$NON-NLS-1$
            return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
    }
}
