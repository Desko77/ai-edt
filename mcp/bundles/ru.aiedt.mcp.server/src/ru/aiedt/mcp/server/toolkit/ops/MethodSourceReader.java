/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;

import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.ops.BslModuleAccess.ModulePathResolution;
import ru.aiedt.mcp.server.support.BslCommentParseHelper;
import ru.aiedt.mcp.server.support.YamlFrontMatter;
import ru.aiedt.mcp.server.support.MarkdownTableHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.modules.IModuleSource;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Reads one procedure or function by name and hands it back with a front-matter header the agent can
 * read the outcome off without parsing the code.
 * <p>
 * The text comes from the one line sequence {@link ModuleTextSelection} selects - a registered
 * module provider first, then an open editor's unsaved buffer, then the file on disk. Provider and
 * editor text is scanned directly so the method and its inclusive bounds always describe the text
 * the caller is shown. For disk text the built model is asked first, because it names the method
 * canonically and knows its export flag for certain; when the model is not built the same answer is
 * reconstructed by scanning the text with the shared keyword patterns. Either way the returned span
 * reaches back over the leading doc-comment, and {@code includeDoc} turns that comment into a
 * structured section. When the name is unknown the available methods are listed, so a near miss
 * corrects itself.
 * </p>
 * <p>
 * The batch route added beside that one answers only from the single line sequence
 * {@link ModuleTextSelection} selects at the start of the call, and never consults the built model.
 * That is deliberate: the model and the text can describe different revisions, and consulting the
 * model per requested name would mix two sources into one answer. One captured snapshot and one
 * scan serve every name, so a batch is internally consistent even when it is partial.
 * </p>
 */
public class MethodSourceReader
    implements IMcpTool
{
    /** The largest batch the wire contract promises. */
    private static final int MAX_BATCH_NAMES = 25;

    /** A generous ceiling for the batch text: 25 BSL names and their escapes fit far below it. */
    private static final int MAX_BATCH_CHARS = 64 * 1024;

    @Override
    public String getName()
    {
        return "read_method_source"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Fetches one named procedure or function from a BSL module. Returns its source together " //$NON-NLS-1$
            + "with metadata, and lists the available methods when the name does not match. Pass " //$NON-NLS-1$
            + "includeDoc=true to also parse the method's doc-comment into a structured 'Documentation' " //$NON-NLS-1$
            + "section (description, typed parameters, return type, deprecated flag)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path under src/, e.g. 'CommonModules/MyModule/Module.bsl', or a module FQN such as " //$NON-NLS-1$
                    + "'CommonModule.MyModule' / 'Catalog.Products.ManagerModule'", true) //$NON-NLS-1$
            .stringProperty("methodName", "Procedure/function name (matching ignores case)", false) //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("methodBatch", //$NON-NLS-1$
                "Batch alternative to methodName: a strict JSON array of 1 to 25 names. Names " //$NON-NLS-1$
                    + "match ignoring case; duplicates, empty names and unquoted or single-quoted " //$NON-NLS-1$
                    + "elements are refused. Use either methodName or methodBatch, not both.", false) //$NON-NLS-1$
            .booleanProperty("includeDoc", //$NON-NLS-1$
                "Also parses the method's doc-comment into a structured Documentation section " //$NON-NLS-1$
                    + "(description / typed parameters / return / deprecated). Default: false.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
        if (methodName == null || methodName.isEmpty())
        {
            return "method-source.md"; //$NON-NLS-1$
        }
        return "method-" + methodName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
        String methodBatchRaw = JsonUtils.extractStringArgument(params, "methodBatch"); //$NON-NLS-1$
        boolean includeDoc = JsonUtils.extractBooleanArgument(params, "includeDoc", false); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName must be provided"; //$NON-NLS-1$
        }
        if (modulePath == null || modulePath.isEmpty())
        {
            return "Error: " + TextSuggest.missingParam("modulePath", //$NON-NLS-1$ //$NON-NLS-2$
                "CommonModules/MyModule/Module.bsl"); //$NON-NLS-1$
        }

        boolean singleRequested = methodName != null && !methodName.isEmpty();
        boolean batchRequested = methodBatchRaw != null && !methodBatchRaw.trim().isEmpty();
        if (singleRequested && batchRequested)
        {
            return "Error: provide exactly one of 'methodName' or 'methodBatch', not both"; //$NON-NLS-1$
        }
        if (!singleRequested && !batchRequested)
        {
            return "Error: provide exactly one of 'methodName' (one method) or 'methodBatch' " //$NON-NLS-1$
                + "(a strict JSON array of 1 to 25 names)"; //$NON-NLS-1$
        }
        if (batchRequested)
        {
            List<String> requestedNames = new ArrayList<>();
            String problem = readBatchNames(methodBatchRaw, requestedNames);
            if (problem != null)
            {
                return problem;
            }
            return readBatch(projectName, modulePath, requestedNames, includeDoc);
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }

        ModulePathResolution resolution = BslModuleAccess.resolveModulePath(project, modulePath);
        if (!resolution.isResolved())
        {
            return resolution.getHint();
        }
        String resolvedPath = resolution.getPath();

        IFile file = project.getFile(new Path("src").append(resolvedPath));
        ModuleTextSelection selection = ModuleTextSelection.select(project, resolvedPath, file);

        if (selection.isProvider())
        {
            // EDT builds no model of a provided module: straight to the text reader.
            List<String> lines;
            try
            {
                lines = selection.lines();
            }
            catch (Exception e)
            {
                return "Error: could not read the module from " + selection.provider().source() + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + e.getMessage();
            }
            return readMethodFromLines(lines, resolvedPath, methodName, includeDoc, projectName,
                selection.provider());
        }
        if (selection.isEditor())
        {
            // The model may still hold the saved revision; the open editor's text is what the
            // user is looking at, so the method and its bounds are read from that text.
            try
            {
                return withUnsavedNote(readMethodFromLines(selection.lines(), resolvedPath, methodName,
                    includeDoc, projectName, null));
            }
            catch (Exception e)
            {
                return "Error: could not read the editor text: " + e.getMessage(); //$NON-NLS-1$
            }
        }
        if (selection.isDisk())
        {
            List<String> lines;
            try
            {
                lines = selection.lines();
            }
            catch (Exception e)
            {
                // The model may still answer though the file cannot be read, as it did before.
                String fromModel = readMethodViaModelOrNull(project, resolvedPath, methodName, includeDoc,
                    projectName);
                return fromModel != null ? fromModel
                    : readMethodViaText(project, resolvedPath, methodName, includeDoc, projectName);
            }
            // The disk text is the model's text: keep the model's canonical name and export flag
            // and its inclusive bounds, applied to the lines selected once for this call.
            String diskAnswer = readMethodViaModelOrNull(project, resolvedPath, methodName, includeDoc,
                projectName, lines);
            if (diskAnswer != null)
            {
                return diskAnswer;
            }
            return readMethodFromLines(lines, resolvedPath, methodName, includeDoc, projectName, null);
        }

        // No provider owns the address and no file exists there: the model may still have the
        // module, and the text reader keeps its own refusal when it does not.
        String modelAnswer = readMethodViaModelOrNull(project, resolvedPath, methodName, includeDoc, projectName);
        if (modelAnswer != null)
        {
            return modelAnswer;
        }
        return readMethodViaText(project, resolvedPath, methodName, includeDoc, projectName);
    }

    /**
     * Reads the requested batch names from their strict JSON array.
     * <p>
     * The wire argument is validated lexically before Gson sees it. The shipped Gson (2.10.1) has no
     * strictness setting on {@code JsonParser}, and its parser is lenient: it would accept unquoted
     * and single-quoted arrays. Its non-lenient streaming reader lives in a package this bundle does
     * not import, so the bundle cannot ask Gson for strict reading. Instead
     * {@link #isStrictJsonStringArray(String)} walks the text once - linear in the bounded argument -
     * and accepts only one array whose elements are JSON strings with valid escapes; a number, an
     * object, an unquoted name, a single-quoted one, a trailing comma or trailing text is refused.
     * The exported {@code JsonParser} then reads the same validated text into a tree, and the names
     * are taken from it. A comma-separated string is not a batch spelling either and is refused, so
     * a caller that sends one learns the wire shape instead of getting two methods it did not name.
     * Names are checked before the module is resolved, so a malformed batch touches no provider,
     * buffer or file.
     * </p>
     *
     * @param raw the argument as the caller sent it
     * @param names receives the names in request order
     * @return the refusal, or <code>null</code> when the batch is valid
     */
    private static String readBatchNames(String raw, List<String> names)
    {
        String text = raw.trim();
        if (text.length() > MAX_BATCH_CHARS || !isStrictJsonStringArray(text))
        {
            return batchArrayProblem();
        }
        JsonElement parsed;
        try
        {
            parsed = JsonParser.parseString(text);
        }
        catch (RuntimeException notJson)
        {
            return batchArrayProblem();
        }
        if (!parsed.isJsonArray())
        {
            return batchArrayProblem();
        }
        JsonArray array = parsed.getAsJsonArray();
        if (array.size() < 1 || array.size() > MAX_BATCH_NAMES)
        {
            return "Error: methodBatch must hold between 1 and 25 names"; //$NON-NLS-1$
        }
        Set<String> seen = new HashSet<>();
        for (JsonElement element : array)
        {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
            {
                return "Error: methodBatch must hold every name as a JSON string"; //$NON-NLS-1$
            }
            String name = element.getAsString();
            if (name.trim().isEmpty())
            {
                return "Error: methodBatch holds an empty name"; //$NON-NLS-1$
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT)))
            {
                return "Error: methodBatch names '" + name + "' twice (names match ignoring case)"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            names.add(name);
        }
        return null;
    }

    /**
     * Walks the bounded text once and tells whether it is exactly one JSON array whose elements are
     * JSON strings. Only RFC 8259 whitespace, double-quoted strings and the standard escapes are
     * accepted; the walk is linear in the text and never recurses.
     *
     * @param text the trimmed argument
     * @return <code>true</code> when the text is a strict JSON string array
     */
    private static boolean isStrictJsonStringArray(String text)
    {
        int length = text.length();
        int index = skipJsonWhitespace(text, 0);
        if (index >= length || text.charAt(index) != '[')
        {
            return false;
        }
        index = skipJsonWhitespace(text, index + 1);
        if (index < length && text.charAt(index) == ']')
        {
            return skipJsonWhitespace(text, index + 1) == length;
        }
        while (true)
        {
            index = skipJsonWhitespace(text, index);
            if (index >= length || text.charAt(index) != '"')
            {
                return false;
            }
            index = skipJsonString(text, index);
            if (index < 0)
            {
                return false;
            }
            index = skipJsonWhitespace(text, index);
            if (index >= length)
            {
                return false;
            }
            char next = text.charAt(index);
            if (next == ',')
            {
                index = skipJsonWhitespace(text, index + 1);
                if (index >= length || text.charAt(index) != '"')
                {
                    return false;
                }
                continue;
            }
            if (next == ']')
            {
                return skipJsonWhitespace(text, index + 1) == length;
            }
            return false;
        }
    }

    /**
     * Walks one JSON string from its opening quote and returns the index after its closing quote.
     *
     * @param text the text
     * @param openingQuote the index of the opening double quote
     * @return the index after the closing quote, or -1 when the string is malformed
     */
    private static int skipJsonString(String text, int openingQuote)
    {
        int index = openingQuote + 1;
        while (index < text.length())
        {
            char ch = text.charAt(index++);
            if (ch == '"')
            {
                return index;
            }
            if (ch == '\\')
            {
                if (index >= text.length())
                {
                    return -1;
                }
                char escape = text.charAt(index++);
                switch (escape)
                {
                    case '"':
                    case '\\':
                    case '/':
                    case 'b':
                    case 'f':
                    case 'n':
                    case 'r':
                    case 't':
                        break;
                    case 'u':
                        if (index + 4 > text.length())
                        {
                            return -1;
                        }
                        for (int i = 0; i < 4; i++)
                        {
                            char hex = text.charAt(index + i);
                            if (!((hex >= '0' && hex <= '9') || (hex >= 'a' && hex <= 'f')
                                || (hex >= 'A' && hex <= 'F')))
                            {
                                return -1;
                            }
                        }
                        index += 4;
                        break;
                    default:
                        return -1;
                }
            }
            else if (ch < 0x20)
            {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Skips the four whitespace characters JSON allows between tokens.
     *
     * @param text the text
     * @param index the index to start from
     * @return the first index that is not JSON whitespace
     */
    private static int skipJsonWhitespace(String text, int index)
    {
        while (index < text.length())
        {
            char ch = text.charAt(index);
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r')
            {
                index++;
            }
            else
            {
                break;
            }
        }
        return index;
    }

    /**
     * The refusal for a batch argument that is not one strict JSON array of strings.
     *
     * @return the refusal line
     */
    private static String batchArrayProblem()
    {
        return "Error: methodBatch must be a strict JSON array of strings, e.g. [\"MyProcedure\"]"; //$NON-NLS-1$
    }

    /**
     * Answers a batch from one module selection and one scan of its lines.
     * <p>
     * The built model is deliberately not consulted here, unlike the single-name disk route: the
     * model and the selected text can describe different revisions, and asking it per name would
     * mix two sources into one answer. The one captured line sequence is the whole input, so every
     * requested name is decided against the same snapshot.
     * </p>
     *
     * @param projectName the project name for the header
     * @param modulePath the module address the caller gave
     * @param requestedNames the requested names, already validated
     * @param includeDoc whether to append each found method's parsed doc-comment
     * @return one markdown document, or an {@code Error:} line
     */
    private String readBatch(String projectName, String modulePath, List<String> requestedNames,
        boolean includeDoc)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }

        ModulePathResolution resolution = BslModuleAccess.resolveModulePath(project, modulePath);
        if (!resolution.isResolved())
        {
            return resolution.getHint();
        }
        String resolvedPath = resolution.getPath();
        IFile file = project.getFile(new Path("src").append(resolvedPath)); //$NON-NLS-1$
        ModuleTextSelection selection = ModuleTextSelection.select(project, resolvedPath, file);

        List<String> lines;
        try
        {
            lines = selection.lines();
        }
        catch (Exception e)
        {
            if (selection.isProvider())
            {
                return "Error: could not read the module from " + selection.provider().source() + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + e.getMessage();
            }
            if (selection.isEditor())
            {
                return "Error: could not read the editor text: " + e.getMessage(); //$NON-NLS-1$
            }
            return "Error: could not read the file: " + e.getMessage(); //$NON-NLS-1$
        }
        return renderBatch(lines, resolvedPath, projectName, requestedNames, includeDoc, selection);
    }

    /**
     * Renders every found method of a batch and the names that were not found, from one scan of one
     * line sequence. The document carries one front-matter block, one section per found method and
     * one missing list, never a repeated front matter.
     *
     * @param lines the selected module text
     * @param modulePath the resolved module path
     * @param projectName the project name for the header
     * @param requestedNames the requested names in request order
     * @param includeDoc whether to append each found method's parsed doc-comment
     * @param selection where the lines came from, for the origin fields and note
     * @return the markdown document
     */
    private String renderBatch(List<String> lines, String modulePath, String projectName,
        List<String> requestedNames, boolean includeDoc, ModuleTextSelection selection)
    {
        List<String> available = new ArrayList<>();
        Map<String, MethodSpan> spans = new LinkedHashMap<>();
        scanMethods(lines, available, spans);

        List<MethodSpan> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : requestedNames)
        {
            MethodSpan span = spans.get(name.toLowerCase(Locale.ROOT));
            if (span == null)
            {
                missing.add(name);
            }
            else
            {
                found.add(span);
            }
        }

        YamlFrontMatter frontMatter = YamlFrontMatter.create()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("module", modulePath) //$NON-NLS-1$
            .put("requested", requestedNames.size()) //$NON-NLS-1$
            .put("found", found.size()) //$NON-NLS-1$
            .put("missing", missing.size()); //$NON-NLS-1$
        IModuleSource provided = selection.provider();
        if (provided != null)
        {
            frontMatter.put("source", provided.source()); //$NON-NLS-1$
            if (provided.containerPath() != null)
            {
                frontMatter.put("container", provided.containerPath()); //$NON-NLS-1$
            }
        }

        StringBuilder body = new StringBuilder();
        for (MethodSpan span : found)
        {
            body.append("## ").append(span.name).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            int from = Math.max(1, findDocCommentStart(lines, span.startLine));
            int to = Math.min(lines.size(), span.endLine);
            body.append("```bsl\n"); //$NON-NLS-1$
            for (int i = from - 1; i < to; i++)
            {
                body.append(lines.get(i)).append("\n"); //$NON-NLS-1$
            }
            body.append("```\n\n"); //$NON-NLS-1$
            if (includeDoc)
            {
                appendParsedDoc(body, lines, span.startLine);
            }
        }
        if (!missing.isEmpty())
        {
            body.append("## Methods not found (").append(missing.size()).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            for (String name : missing)
            {
                body.append("- ").append(name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            body.append("\n**Methods available** (").append(available.size()).append("):\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            for (String name : available)
            {
                body.append("- ").append(name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        if (selection.isEditor())
        {
            body.append("\n**Unsaved:** this is what the open editor holds; the file on disk still has the " //$NON-NLS-1$
                + "previous text.\n"); //$NON-NLS-1$
        }
        return frontMatter.wrapContent(body.toString());
    }

    /**
     * One scan of the selected lines: the available names in source order and the first declaration
     * of each name ignoring case. A batch looks every requested name up here, so the text and its
     * parse are shared by the whole call.
     *
     * @param lines the selected module text
     * @param available receives every declared name in source order
     * @param spans receives the first span of each name, keyed by its lower-cased form
     */
    private static void scanMethods(List<String> lines, List<String> available,
        Map<String, MethodSpan> spans)
    {
        for (int i = 0; i < lines.size(); i++)
        {
            Matcher matcher = BslModuleAccess.METHOD_START_PATTERN.matcher(lines.get(i));
            if (!matcher.find())
            {
                continue;
            }
            String name = matcher.group(1);
            available.add(name);
            String key = name.toLowerCase(Locale.ROOT);
            if (spans.containsKey(key))
            {
                continue;
            }
            spans.put(key, new MethodSpan(name, i + 1, findMethodEnd(lines, i)));
        }
    }

    /**
     * Finds the inclusive 1-based line a declaration ends on, scanning from the line after it.
     *
     * @param lines the module lines
     * @param declarationIndex the 0-based index of the declaration line
     * @return the 1-based end line, or the last line when no terminator follows
     */
    private static int findMethodEnd(List<String> lines, int declarationIndex)
    {
        for (int i = declarationIndex + 1; i < lines.size(); i++)
        {
            if (BslModuleAccess.METHOD_END_PATTERN.matcher(lines.get(i)).find())
            {
                return i + 1;
            }
        }
        return lines.size();
    }

    /** One declaration found by the batch scan: its canonical name and inclusive text bounds. */
    private static final class MethodSpan
    {
        final String name;

        final int startLine;

        final int endLine;

        MethodSpan(String name, int startLine, int endLine)
        {
            this.name = name;
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }

    /**
     * Reads the method off the built model, for when the file could not be read or does not exist.
     *
     * @param project the project
     * @param modulePath the resolved module path
     * @param methodName the method name
     * @param includeDoc whether to append the parsed doc-comment
     * @param projectName the project name for the header
     * @return the answer, or <code>null</code> when the model does not answer (send to the text path)
     */
    private String readMethodViaModelOrNull(IProject project, String modulePath, String methodName,
        boolean includeDoc, String projectName)
    {
        try
        {
            return UiSync.call(() ->
                readMethodViaEmf(project, modulePath, methodName, includeDoc, projectName));
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Reads the method off the built model against lines already selected for this call.
     *
     * @param project the project
     * @param modulePath the resolved module path
     * @param methodName the method name
     * @param includeDoc whether to append the parsed doc-comment
     * @param projectName the project name for the header
     * @param lines the selected disk lines
     * @return the answer, or <code>null</code> when the model is not built (send to the text path)
     */
    private String readMethodViaModelOrNull(IProject project, String modulePath, String methodName,
        boolean includeDoc, String projectName, List<String> lines)
    {
        try
        {
            return UiSync.call(() ->
                readMethodViaEmf(project, modulePath, methodName, includeDoc, projectName, lines));
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Names the origin of a method answer read from an open editor's unsaved text.
     *
     * @param answer the method answer
     * @return the answer with the unsaved note
     */
    private static String withUnsavedNote(String answer)
    {
        return answer + (answer.endsWith("\n") ? "\n" : "\n\n") //$NON-NLS-1$ //$NON-NLS-2$
            + "**Unsaved:** this is what the open editor holds; the file on disk still has the " //$NON-NLS-1$
            + "previous text.\n"; //$NON-NLS-1$
    }

    /**
     * Reads the method off the built model.
     *
     * @param project the project
     * @param modulePath the resolved module path
     * @param methodName the method name
     * @param includeDoc whether to append the parsed doc-comment
     * @param projectName the project name for the header
     * @return the answer, or <code>null</code> when the model is not built (send to the text path)
     */
    private String readMethodViaEmf(IProject project, String modulePath, String methodName,
        boolean includeDoc, String projectName)
    {
        Module module = BslModuleAccess.loadModule(project, modulePath);
        if (module == null)
        {
            return null;
        }
        Method method = BslModuleAccess.findMethod(module, methodName);
        if (method == null)
        {
            return BslModuleAccess.buildMethodNotFoundResponse(module, modulePath, methodName);
        }

        List<String> lines;
        try
        {
            IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
            lines = BslModuleAccess.readFileLines(file);
        }
        catch (Exception e)
        {
            return readMethodFromEmfText(method, modulePath, projectName, includeDoc);
        }

        return renderMethodViaEmf(method, modulePath, projectName, includeDoc, lines);
    }

    /**
     * Reads the method off the built model against the text already selected for this call.
     *
     * @param project the project
     * @param modulePath the resolved module path
     * @param methodName the method name
     * @param includeDoc whether to append the parsed doc-comment
     * @param projectName the project name for the header
     * @param lines the disk text, read once by the caller
     * @return the answer, or <code>null</code> when the model is not built (send to the text path)
     */
    private String readMethodViaEmf(IProject project, String modulePath, String methodName,
        boolean includeDoc, String projectName, List<String> lines)
    {
        Module module = BslModuleAccess.loadModule(project, modulePath);
        if (module == null)
        {
            return null;
        }
        Method method = BslModuleAccess.findMethod(module, methodName);
        if (method == null)
        {
            return BslModuleAccess.buildMethodNotFoundResponse(module, modulePath, methodName);
        }
        return renderMethodViaEmf(method, modulePath, projectName, includeDoc, lines);
    }

    /**
     * Renders a method found by the model, slicing the selected lines with the model's inclusive
     * bounds and reaching back over the leading doc-comment.
     *
     * @param method the model method
     * @param modulePath the resolved module path
     * @param projectName the project name for the header
     * @param includeDoc whether to append the parsed doc-comment
     * @param lines the text the bounds refer to
     * @return the answer
     */
    private String renderMethodViaEmf(Method method, String modulePath, String projectName,
        boolean includeDoc, List<String> lines)
    {
        int startLine = BslModuleAccess.getStartLine(method);
        int endLine = BslModuleAccess.getEndLine(method);

        int from = Math.max(1, findDocCommentStart(lines, startLine));
        int to = Math.min(lines.size(), endLine);
        String region = BslModuleAccess.findRegionForLine(lines, startLine);
        String type = method instanceof Function ? "Function" : "Procedure"; //$NON-NLS-1$ //$NON-NLS-2$

        YamlFrontMatter frontMatter = YamlFrontMatter.create()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("module", modulePath) //$NON-NLS-1$
            .put("method", method.getName()) //$NON-NLS-1$
            .put("type", type) //$NON-NLS-1$
            .put("export", method.isExport()) //$NON-NLS-1$
            .put("startLine", from) //$NON-NLS-1$
            .put("endLine", to) //$NON-NLS-1$
            .put("totalLines", lines.size()); //$NON-NLS-1$
        if (region != null)
        {
            frontMatter.put("region", region); //$NON-NLS-1$
        }

        StringBuilder body = new StringBuilder();
        body.append("```bsl\n"); //$NON-NLS-1$
        for (int i = from - 1; i < to; i++)
        {
            body.append(lines.get(i)).append("\n"); //$NON-NLS-1$
        }
        body.append("```\n"); //$NON-NLS-1$
        if (includeDoc)
        {
            appendParsedDoc(body, lines, startLine);
        }
        return frontMatter.wrapContent(body.toString());
    }

    /**
     * Builds the answer off the model alone, for when the file cannot be read but the model is built.
     *
     * @param method the method
     * @param modulePath the resolved module path
     * @param projectName the project name for the header
     * @param includeDoc whether to append the parsed doc-comment
     * @return the answer
     */
    private String readMethodFromEmfText(Method method, String modulePath, String projectName,
        boolean includeDoc)
    {
        String type = method instanceof Function ? "Function" : "Procedure"; //$NON-NLS-1$ //$NON-NLS-2$
        YamlFrontMatter frontMatter = YamlFrontMatter.create()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("module", modulePath) //$NON-NLS-1$
            .put("method", method.getName()) //$NON-NLS-1$
            .put("type", type) //$NON-NLS-1$
            .put("export", method.isExport()) //$NON-NLS-1$
            .put("startLine", BslModuleAccess.getStartLine(method)) //$NON-NLS-1$
            .put("endLine", BslModuleAccess.getEndLine(method)); //$NON-NLS-1$

        String source = BslModuleAccess.getSourceText(method);
        if (source == null)
        {
            source = ""; //$NON-NLS-1$
        }
        StringBuilder body = new StringBuilder();
        body.append("```bsl\n").append(source); //$NON-NLS-1$
        if (!source.endsWith("\n")) //$NON-NLS-1$
        {
            body.append("\n"); //$NON-NLS-1$
        }
        body.append("```\n"); //$NON-NLS-1$
        if (includeDoc)
        {
            Map<String, Object> doc = BslCommentParseHelper.parseMethodDoc(method);
            if (doc != null && !doc.isEmpty())
            {
                renderParsedDoc(body, doc);
            }
        }
        return frontMatter.wrapContent(body.toString());
    }

    /**
     * Reads the method by scanning the text, for when the model is not built.
     *
     * @param project the project
     * @param modulePath the resolved module path
     * @param methodName the method name
     * @param includeDoc whether to append the parsed doc-comment
     * @param projectName the project name for the header
     * @return the answer
     */
    private String readMethodViaText(IProject project, String modulePath, String methodName,
        boolean includeDoc, String projectName)
    {
        List<String> lines;
        try
        {
            IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
            lines = BslModuleAccess.readFileLines(file);
        }
        catch (Exception e)
        {
            return "Error: could not read the file: " + e.getMessage(); //$NON-NLS-1$
        }
        return readMethodFromLines(lines, modulePath, methodName, includeDoc, projectName, null);
    }

    /**
     * Finds the method in module lines and renders it.
     *
     * @param lines the module lines
     * @param modulePath the module path, for the header
     * @param methodName the method name
     * @param includeDoc whether to append the parsed doc-comment
     * @param projectName the project name for the header
     * @param provided the module source the lines came from, or <code>null</code> for a file
     * @return the answer
     */
    private String readMethodFromLines(List<String> lines, String modulePath, String methodName,
        boolean includeDoc, String projectName, IModuleSource provided)
    {
        int methodStart = -1;
        boolean isFunction = false;
        List<String> available = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++)
        {
            Matcher matcher = BslModuleAccess.METHOD_START_PATTERN.matcher(lines.get(i));
            if (matcher.find())
            {
                String name = matcher.group(1);
                available.add(name);
                if (methodStart < 0 && methodName.equalsIgnoreCase(name))
                {
                    methodStart = i + 1;
                    isFunction = BslModuleAccess.FUNC_KEYWORD_PATTERN.matcher(lines.get(i)).find();
                }
            }
        }

        if (methodStart < 0)
        {
            StringBuilder builder = new StringBuilder();
            builder.append("Error: no method called '").append(methodName).append("' in ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(modulePath).append("\n\n"); //$NON-NLS-1$
            builder.append("**Methods available** (").append(available.size()).append("):\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            for (String name : available)
            {
                builder.append("- ").append(name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return builder.toString();
        }

        int methodEnd = lines.size();
        for (int i = methodStart; i < lines.size(); i++)
        {
            if (BslModuleAccess.METHOD_END_PATTERN.matcher(lines.get(i)).find())
            {
                methodEnd = i + 1;
                break;
            }
        }

        int from = Math.max(1, findDocCommentStart(lines, methodStart));
        int to = Math.min(lines.size(), methodEnd);
        String region = BslModuleAccess.findRegionForLine(lines, methodStart);
        String type = isFunction ? "Function" : "Procedure"; //$NON-NLS-1$ //$NON-NLS-2$
        boolean export = detectExport(lines, methodStart - 1);

        YamlFrontMatter frontMatter = YamlFrontMatter.create()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("module", modulePath) //$NON-NLS-1$
            .put("method", methodName) //$NON-NLS-1$
            .put("type", type) //$NON-NLS-1$
            .put("export", export) //$NON-NLS-1$
            .put("startLine", from) //$NON-NLS-1$
            .put("endLine", to) //$NON-NLS-1$
            .put("totalLines", lines.size()); //$NON-NLS-1$
        if (region != null)
        {
            frontMatter.put("region", region); //$NON-NLS-1$
        }
        if (provided != null)
        {
            frontMatter.put("source", provided.source()); //$NON-NLS-1$
            if (provided.containerPath() != null)
            {
                frontMatter.put("container", provided.containerPath()); //$NON-NLS-1$
            }
        }

        StringBuilder body = new StringBuilder();
        body.append("```bsl\n"); //$NON-NLS-1$
        for (int i = from - 1; i < to; i++)
        {
            body.append(lines.get(i)).append("\n"); //$NON-NLS-1$
        }
        body.append("```\n"); //$NON-NLS-1$
        if (includeDoc)
        {
            appendParsedDoc(body, lines, methodStart);
        }
        return frontMatter.wrapContent(body.toString());
    }

    /**
     * Finds the 1-based line the method's leading doc-comment begins on, or the declaration line when
     * there is no comment directly above it.
     *
     * @param lines the module lines
     * @param methodStartLine the 1-based declaration line
     * @return the first line to include
     */
    private static int findDocCommentStart(List<String> lines, int methodStartLine)
    {
        int start = methodStartLine;
        for (int i = methodStartLine - 2; i >= 0; i--)
        {
            if (lines.get(i).trim().startsWith("//")) //$NON-NLS-1$
            {
                start = i + 1;
            }
            else
            {
                break;
            }
        }
        return start;
    }

    /**
     * Best-effort detection of the {@code Export} keyword after a method's parameter list.
     *
     * @param lines the module lines
     * @param declIndex the 0-based index of the declaration line
     * @return <code>true</code> when the method is exported
     */
    private static boolean detectExport(List<String> lines, int declIndex)
    {
        int depth = 0;
        boolean started = false;
        for (int i = declIndex; i < lines.size(); i++)
        {
            String line = lines.get(i);
            for (int j = 0; j < line.length(); j++)
            {
                char symbol = line.charAt(j);
                if (symbol == '(')
                {
                    depth++;
                    started = true;
                }
                else if (symbol == ')')
                {
                    depth--;
                    if (started && depth == 0)
                    {
                        String rest = line.substring(j + 1);
                        int comment = rest.indexOf("//"); //$NON-NLS-1$
                        if (comment >= 0)
                        {
                            rest = rest.substring(0, comment);
                        }
                        return rest.matches("(?iu)\\s*(?:Экспорт|Export)\\s*"); //$NON-NLS-1$
                    }
                }
            }
        }
        return false;
    }

    /**
     * Appends the parsed documentation section built from the {@code //} block above a method.
     *
     * @param body the body being built
     * @param lines the module lines
     * @param methodDeclLine the 1-based declaration line
     */
    private static void appendParsedDoc(StringBuilder body, List<String> lines, int methodDeclLine)
    {
        List<String> commentBlock = gatherCommentBlock(lines, methodDeclLine);
        List<String> paramNames = BslCommentParseHelper.extractParamNames(lines, methodDeclLine);
        Map<String, Object> doc = BslCommentParseHelper.parseDocAnchored(commentBlock, paramNames);
        if (doc == null)
        {
            doc = BslCommentParseHelper.parseMethodDoc(commentBlock);
        }
        if (doc == null || doc.isEmpty())
        {
            return;
        }
        renderParsedDoc(body, doc);
    }

    /**
     * Collects the contiguous {@code //} lines directly above a declaration, in source order.
     *
     * @param lines the module lines
     * @param declLine the 1-based declaration line
     * @return the raw comment lines
     */
    private static List<String> gatherCommentBlock(List<String> lines, int declLine)
    {
        List<String> block = new ArrayList<>();
        for (int i = declLine - 2; i >= 0; i--)
        {
            String raw = lines.get(i);
            if (raw.trim().startsWith("//")) //$NON-NLS-1$
            {
                block.add(0, raw);
            }
            else
            {
                break;
            }
        }
        return block;
    }

    /**
     * Renders a parsed doc-comment map into the {@code ## Documentation (parsed)} section.
     *
     * @param body the body being built
     * @param doc the parsed doc map
     */
    private static void renderParsedDoc(StringBuilder body, Map<String, Object> doc)
    {
        body.append("\n## Parsed Documentation\n\n"); //$NON-NLS-1$
        if (Boolean.TRUE.equals(doc.get("deprecated"))) //$NON-NLS-1$
        {
            body.append("**This method is deprecated.**\n\n"); //$NON-NLS-1$
        }
        Object description = doc.get("description"); //$NON-NLS-1$
        if (description != null && !description.toString().isEmpty())
        {
            body.append(description.toString()).append("\n\n"); //$NON-NLS-1$
        }
        Object parameters = doc.get("parameters"); //$NON-NLS-1$
        if (parameters instanceof List && !((List<?>)parameters).isEmpty())
        {
            body.append("**Parameters:**\n\n"); //$NON-NLS-1$
            body.append("| Name | Type(s) | Description |\n"); //$NON-NLS-1$
            body.append("|---|---|---|\n"); //$NON-NLS-1$
            for (Object entry : (List<?>)parameters)
            {
                Map<?, ?> parameter = (Map<?, ?>)entry;
                String name = stringValue(parameter.get("name")); //$NON-NLS-1$
                String types = stringValue(parameter.get("types")); //$NON-NLS-1$
                String paramDesc = stringValue(parameter.get("description")); //$NON-NLS-1$
                body.append("| ").append(MarkdownTableHelper.escapeForTable(name)).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(MarkdownTableHelper.escapeForTable(types)).append(" | ") //$NON-NLS-1$
                    .append(MarkdownTableHelper.escapeForTable(paramDesc)).append(" |\n"); //$NON-NLS-1$
            }
            body.append("\n"); //$NON-NLS-1$
        }
        Object returns = doc.get("returns"); //$NON-NLS-1$
        if (returns instanceof Map)
        {
            Object types = ((Map<?, ?>)returns).get("types"); //$NON-NLS-1$
            if (types != null && !types.toString().isEmpty())
            {
                body.append("**Return type:** ").append(types.toString()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /**
     * Renders a possibly-null value as a string, the empty string for <code>null</code>.
     *
     * @param value the value
     * @return the string form
     */
    private static String stringValue(Object value)
    {
        return value != null ? value.toString() : ""; //$NON-NLS-1$
    }
}
