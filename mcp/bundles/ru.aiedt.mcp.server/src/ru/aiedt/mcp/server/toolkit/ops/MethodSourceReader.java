/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;

import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.ops.BslModuleAccess.ModulePathResolution;
import ru.aiedt.mcp.server.support.BslCommentParseHelper;
import ru.aiedt.mcp.server.support.YamlFrontMatter;
import ru.aiedt.mcp.server.support.MarkdownTableHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Reads one procedure or function by name and hands it back with a front-matter header the agent can
 * read the outcome off without parsing the code.
 * <p>
 * The model is asked first, because it names the method canonically and knows its export flag for
 * certain; when the model is not built the same answer is reconstructed by scanning the text with the
 * shared keyword patterns. Either way the returned span reaches back over the leading doc-comment, and
 * {@code includeDoc} turns that comment into a structured section. When the name is unknown the
 * available methods are listed, so a near miss corrects itself.
 * </p>
 */
public class MethodSourceReader
    implements IMcpTool
{
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
            .stringProperty("methodName", "Procedure/function name (matching ignores case)", true) //$NON-NLS-1$ //$NON-NLS-2$
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
        if (methodName == null || methodName.isEmpty())
        {
            return "Error: " + TextSuggest.missingParam("methodName", "MyProcedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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

        String result;
        try
        {
            result = UiSync.call(() ->
                readMethodViaEmf(project, resolvedPath, methodName, includeDoc, projectName));
        }
        catch (RuntimeException e)
        {
            // The model path failed or the UI thread was unavailable; fall through to the text reader.
            result = null;
        }

        if (result != null)
        {
            return result;
        }
        return readMethodViaText(project, resolvedPath, methodName, includeDoc, projectName);
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

        int startLine = BslModuleAccess.getStartLine(method);
        int endLine = BslModuleAccess.getEndLine(method);

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
                        return rest.matches("(?i)\\s*(?:Экспорт|Export)\\s*"); //$NON-NLS-1$
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
