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
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.bsl.model.DeclareStatement;
import com._1c.g5.v8.dt.bsl.model.ExplicitVariable;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com._1c.g5.v8.dt.bsl.model.RegionPreprocessor;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.ops.BslModuleAccess.ModulePathResolution;
import ru.aiedt.mcp.server.support.MarkdownTableHelper;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Lays out the shape of a BSL module: its procedures and functions, each with a signature, a line
 * span, an execution context and an export flag, grouped by the regions they sit in.
 * <p>
 * The model is the first choice, because it carries the pragmas and the declared variables that raw
 * text cannot show. When the model is not built - a fresh project, a half-indexed one - the tool
 * still answers, by parsing the text with the shared keyword patterns and saying so in a note. The
 * two paths funnel into one table writer, so the shape of the answer does not change under the reader
 * when the source of it does.
 * </p>
 */
public class ModuleOutlineReader
    implements IMcpTool
{
    private static final String MODEL_UNAVAILABLE_PREFIX = "Error: BSL model is not available"; //$NON-NLS-1$

    private static final int SIGNATURE_SCAN_LIMIT = 20;

    @Override
    public String getName()
    {
        return "get_module_structure"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Show the shape of a BSL module: every procedure/function with its signature, line " //$NON-NLS-1$
            + "numbers, region, execution context (&AtServer, &AtClient), export flag, and " //$NON-NLS-1$
            + "parameters."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path under src/, e.g. 'CommonModules/MyModule/Module.bsl', or a module FQN " //$NON-NLS-1$
                    + "such as 'CommonModule.MyModule' / 'Catalog.Products.ManagerModule' (required)", //$NON-NLS-1$
                true)
            .booleanProperty("includeVariables", //$NON-NLS-1$
                "Also list module-level variable declarations. Default: false") //$NON-NLS-1$
            .booleanProperty("includeComments", //$NON-NLS-1$
                "Also extract each method's leading doc-comment. Default: false") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        if (modulePath == null || modulePath.isEmpty())
        {
            return "module-structure.md"; //$NON-NLS-1$
        }
        return "structure-" + modulePath.replace('/', '-').replace('\\', '-').toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        boolean includeVariables = JsonUtils.extractBooleanArgument(params, "includeVariables", false); //$NON-NLS-1$
        boolean includeComments = JsonUtils.extractBooleanArgument(params, "includeComments", false); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName parameter is required"; //$NON-NLS-1$
        }
        if (modulePath == null || modulePath.isEmpty())
        {
            return "Error: modulePath parameter is required, e.g. 'CommonModules/MyModule/Module.bsl'"; //$NON-NLS-1$
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
                getStructureInternal(project, resolvedPath, includeVariables, includeComments));
        }
        catch (RuntimeException e)
        {
            // The model path failed or the UI thread was unavailable; fall through to the text reader.
            result = null;
        }
        if (result != null && !result.startsWith(MODEL_UNAVAILABLE_PREFIX))
        {
            return result;
        }

        String textResult = getStructureFromText(project, resolvedPath, includeVariables, includeComments);
        if (textResult != null)
        {
            return textResult;
        }
        return "Error: BSL model is not available for '" + resolvedPath + "'\nMake sure project '" //$NON-NLS-1$ //$NON-NLS-2$
            + projectName + "' is open in EDT and its index has finished building."; //$NON-NLS-1$
    }

    /**
     * Builds the structure from the built model, or reports that the model is not there.
     *
     * @param project the project
     * @param modulePath the resolved module path
     * @param includeVariables whether to list module variables
     * @param includeComments whether to parse doc-comments
     * @return the report, or the "model not available" string that sends the caller to the text path
     */
    private String getStructureInternal(IProject project, String modulePath, boolean includeVariables,
        boolean includeComments)
    {
        Module module = BslModuleAccess.loadModule(project, modulePath);
        if (module == null)
        {
            return "Error: BSL model is not available for '" + modulePath + "'\nMake sure project '" //$NON-NLS-1$ //$NON-NLS-2$
                + project.getName() + "' is open in EDT and its index has finished building."; //$NON-NLS-1$
        }

        List<String> lines = readLinesQuietly(project, modulePath);

        List<Method> methods = new ArrayList<>(module.allMethods());
        int procedures = 0;
        int functions = 0;
        for (Method method : methods)
        {
            if (method instanceof Function)
            {
                functions++;
            }
            else
            {
                procedures++;
            }
        }
        int totalLines = BslModuleAccess.getEndLine(module);

        StringBuilder builder = new StringBuilder();
        builder.append("## Module Layout: ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        builder.append("**Total:** ").append(procedures).append(" procedures, ").append(functions) //$NON-NLS-1$ //$NON-NLS-2$
            .append(" functions | **Line span:** ").append(totalLines).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        appendRegions(builder, collectModelRegions(module));

        if (includeVariables)
        {
            appendVariables(builder, collectModelVariables(module, lines));
        }

        if (methods.isEmpty())
        {
            builder.append("This module has no methods.\n"); //$NON-NLS-1$
            return builder.toString();
        }

        List<MethodInfo> infos = new ArrayList<>();
        for (Method method : methods)
        {
            infos.add(describeModelMethod(method, lines, includeComments));
        }
        appendMethodsTable(builder, infos, includeComments);
        return builder.toString();
    }

    /**
     * Builds the structure by parsing the module text, for when the model is not built.
     *
     * @param project the project
     * @param modulePath the resolved module path
     * @param includeVariables whether to list module variables (never available on this path)
     * @param includeComments whether to parse doc-comments
     * @return the report, or <code>null</code> when the file cannot be read
     */
    private String getStructureFromText(IProject project, String modulePath, boolean includeVariables,
        boolean includeComments)
    {
        List<String> lines;
        try
        {
            IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
            if (!file.exists())
            {
                return null;
            }
            lines = BslModuleAccess.readFileLines(file);
        }
        catch (Exception e)
        {
            return null;
        }

        List<RegionRange> regions = new ArrayList<>();
        List<MethodInfo> methods = parseTextStructure(lines, regions, includeComments);

        int procedures = 0;
        int functions = 0;
        for (MethodInfo info : methods)
        {
            if (info.function)
            {
                functions++;
            }
            else
            {
                procedures++;
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append("## Module Layout: ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        builder.append("_(fallback: parsed from raw text because the BSL model is not indexed; " //$NON-NLS-1$
            + "execution context and variable declarations are unavailable this way, and a " //$NON-NLS-1$
            + "method's line is its declaration keyword, which may omit a leading doc-comment)_\n\n"); //$NON-NLS-1$
        builder.append("**Total:** ").append(procedures).append(" procedures, ").append(functions) //$NON-NLS-1$ //$NON-NLS-2$
            .append(" functions | **Line span:** ").append(lines.size()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        appendRegions(builder, regions);

        if (methods.isEmpty())
        {
            builder.append("This module has no methods.\n"); //$NON-NLS-1$
            return builder.toString();
        }
        appendMethodsTable(builder, methods, includeComments);
        return builder.toString();
    }

    /**
     * Appends the regions list, when there is one.
     *
     * @param builder the report
     * @param regions the regions in document order
     */
    private static void appendRegions(StringBuilder builder, List<RegionRange> regions)
    {
        if (regions.isEmpty())
        {
            return;
        }
        builder.append("### Code Regions\n\n"); //$NON-NLS-1$
        for (RegionRange region : regions)
        {
            builder.append("- ").append(region.name).append(" (line ").append(region.start) //$NON-NLS-1$ //$NON-NLS-2$
                .append("-").append(region.end).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        builder.append("\n"); //$NON-NLS-1$
    }

    /**
     * Appends the module-variable table, when there is one.
     *
     * @param builder the report
     * @param variables the variables
     */
    private static void appendVariables(StringBuilder builder, List<VariableInfo> variables)
    {
        if (variables.isEmpty())
        {
            return;
        }
        builder.append("### Module-Level Variables\n\n"); //$NON-NLS-1$
        builder.append("| # | Name | Exported | Line | Region |\n"); //$NON-NLS-1$
        builder.append("|---|------|--------|------|--------|\n"); //$NON-NLS-1$
        int index = 1;
        for (VariableInfo variable : variables)
        {
            builder.append("| ").append(index).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(MarkdownTableHelper.escapeForTable(variable.name)).append(" | ") //$NON-NLS-1$
                .append(variable.export ? "Yes" : "-").append(" | ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(variable.line).append(" | ") //$NON-NLS-1$
                .append(variable.region != null ? MarkdownTableHelper.escapeForTable(variable.region) : "-") //$NON-NLS-1$
                .append(" |\n"); //$NON-NLS-1$
            index++;
        }
        builder.append("\n"); //$NON-NLS-1$
    }

    /**
     * Appends the methods table. The description column appears only when some method actually
     * carries a doc-comment.
     *
     * @param builder the report
     * @param methods the methods
     * @param includeComments whether doc-comments were requested
     */
    private static void appendMethodsTable(StringBuilder builder, List<MethodInfo> methods,
        boolean includeComments)
    {
        boolean withComments = false;
        if (includeComments)
        {
            for (MethodInfo method : methods)
            {
                if (method.doc != null && !method.doc.isEmpty())
                {
                    withComments = true;
                    break;
                }
            }
        }

        builder.append("### Module Methods\n\n"); //$NON-NLS-1$
        if (withComments)
        {
            builder.append("| # | Kind | Name | Exported | Directive | Lines | Params | Region | " //$NON-NLS-1$
                + "Summary |\n"); //$NON-NLS-1$
            builder.append("|---|------|------|--------|---------|-------|------------|--------|" //$NON-NLS-1$
                + "-------------|\n"); //$NON-NLS-1$
        }
        else
        {
            builder.append("| # | Kind | Name | Exported | Directive | Lines | Params | Region |\n"); //$NON-NLS-1$
            builder.append("|---|------|------|--------|---------|-------|------------|--------|\n"); //$NON-NLS-1$
        }

        int index = 1;
        for (MethodInfo method : methods)
        {
            builder.append("| ").append(index).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(method.function ? "Function" : "Procedure").append(" | ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(MarkdownTableHelper.escapeForTable(method.name)).append(" | ") //$NON-NLS-1$
                .append(method.export ? "Yes" : "-").append(" | ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(MarkdownTableHelper.escapeForTable(contextOrDash(method.context))).append(" | ") //$NON-NLS-1$
                .append(method.startLine).append("-").append(method.endLine).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(MarkdownTableHelper.escapeForTable(method.params)).append(" | ") //$NON-NLS-1$
                .append(method.region != null ? MarkdownTableHelper.escapeForTable(method.region) : "-") //$NON-NLS-1$
                .append(" |"); //$NON-NLS-1$
            if (withComments)
            {
                String doc = method.doc != null && !method.doc.isEmpty()
                    ? MarkdownTableHelper.escapeForTable(method.doc) : "-"; //$NON-NLS-1$
                builder.append(" ").append(doc).append(" |"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            builder.append("\n"); //$NON-NLS-1$
            index++;
        }
    }

    /**
     * Turns a possibly-empty context into the dash the table wants for "none".
     *
     * @param context the joined context symbols, or <code>null</code>/empty
     * @return the context, or a dash
     */
    private static String contextOrDash(String context)
    {
        return context == null || context.isEmpty() ? "-" : context; //$NON-NLS-1$
    }

    /**
     * Collects the regions of a built module, in document order.
     *
     * @param module the module
     * @return the regions with their line spans
     */
    private static List<RegionRange> collectModelRegions(Module module)
    {
        List<RegionRange> regions = new ArrayList<>();
        module.eAllContents().forEachRemaining(element -> {
            if (element instanceof RegionPreprocessor)
            {
                RegionPreprocessor region = (RegionPreprocessor)element;
                int start = BslModuleAccess.getStartLine(region);
                int maxChild = 0;
                for (EObject child : region.eContents())
                {
                    int childEnd = BslModuleAccess.getEndLine(child);
                    if (childEnd > maxChild)
                    {
                        maxChild = childEnd;
                    }
                }
                int end = maxChild > 0 ? maxChild + 1 : start + 1;
                regions.add(new RegionRange(region.getName(), start, end));
            }
        });
        return regions;
    }

    /**
     * Collects the module-level variables of a built module.
     *
     * @param module the module
     * @param lines the module lines, for locating each variable's region
     * @return the variables
     */
    private static List<VariableInfo> collectModelVariables(Module module, List<String> lines)
    {
        List<VariableInfo> variables = new ArrayList<>();
        for (DeclareStatement statement : module.allDeclareStatements())
        {
            for (ExplicitVariable variable : statement.getVariables())
            {
                int line = BslModuleAccess.getStartLine(variable);
                String region = BslModuleAccess.findRegionForLine(lines, line);
                variables.add(new VariableInfo(variable.getName(), variable.isExport(), line, region));
            }
        }
        return variables;
    }

    /**
     * Describes one method off the model.
     *
     * @param method the method
     * @param lines the module lines, for region and doc-comment lookup
     * @param includeComments whether to extract the doc-comment
     * @return the row data
     */
    private static MethodInfo describeModelMethod(Method method, List<String> lines,
        boolean includeComments)
    {
        MethodInfo info = new MethodInfo();
        info.function = method instanceof Function;
        info.name = method.getName();
        info.export = method.isExport();
        info.params = BslModuleAccess.buildParamsString(method);
        info.startLine = BslModuleAccess.getStartLine(method);
        info.endLine = BslModuleAccess.getEndLine(method);
        info.context = joinContext(method);
        info.region = BslModuleAccess.findRegionForLine(lines, info.startLine);
        if (includeComments)
        {
            info.doc = extractDocCommentFromLines(lines, info.startLine);
        }
        return info;
    }

    /**
     * Joins a method's execution-context pragmas into a single {@code &Sym, &Sym} cell.
     *
     * @param method the method
     * @return the joined context, or the empty string when the method has no pragmas
     */
    private static String joinContext(Method method)
    {
        StringBuilder context = new StringBuilder();
        for (Pragma pragma : method.getPragmas())
        {
            if (context.length() > 0)
            {
                context.append(", "); //$NON-NLS-1$
            }
            context.append("&").append(pragma.getSymbol()); //$NON-NLS-1$
        }
        return context.toString();
    }

    /**
     * Extracts a method's doc-comment by walking back over the {@code //} lines above its declaration.
     *
     * @param lines the module lines
     * @param methodStartLine the 1-based declaration line
     * @return the joined comment text, or <code>null</code> when there is none
     */
    private static String extractDocCommentFromLines(List<String> lines, int methodStartLine)
    {
        if (lines == null || methodStartLine < 2)
        {
            return null;
        }
        List<String> collected = new ArrayList<>();
        for (int i = methodStartLine - 2; i >= 0; i--)
        {
            String line = lines.get(i).trim();
            if (line.startsWith("//")) //$NON-NLS-1$
            {
                String text = line.substring(2);
                if (text.startsWith(" ")) //$NON-NLS-1$
                {
                    text = text.substring(1);
                }
                collected.add(0, text);
            }
            else if (line.isEmpty())
            {
                break;
            }
            else
            {
                break;
            }
        }
        if (collected.isEmpty())
        {
            return null;
        }
        return String.join(" ", collected).trim(); //$NON-NLS-1$
    }

    /**
     * Parses the method and region structure out of module text.
     *
     * @param lines the module lines
     * @param regions filled with the regions found, in document order
     * @param includeComments whether to parse doc-comments
     * @return the methods in document order
     */
    private static List<MethodInfo> parseTextStructure(List<String> lines, List<RegionRange> regions,
        boolean includeComments)
    {
        List<MethodInfo> methods = new ArrayList<>();
        List<String> regionNameStack = new ArrayList<>();
        List<Integer> regionStartStack = new ArrayList<>();

        int lineCount = lines.size();
        for (int i = 0; i < lineCount; i++)
        {
            String line = lines.get(i);
            int lineNumber = i + 1;

            Matcher regionStart = BslModuleAccess.REGION_START_PATTERN.matcher(line);
            if (regionStart.find())
            {
                regionNameStack.add(regionStart.group(1));
                regionStartStack.add(lineNumber);
                continue;
            }
            if (BslModuleAccess.REGION_END_PATTERN.matcher(line).find())
            {
                if (!regionNameStack.isEmpty())
                {
                    int last = regionNameStack.size() - 1;
                    regions.add(new RegionRange(regionNameStack.remove(last),
                        regionStartStack.remove(last).intValue(), lineNumber));
                }
                continue;
            }

            Matcher methodStart = BslModuleAccess.METHOD_START_PATTERN.matcher(line);
            if (methodStart.find())
            {
                MethodInfo info = new MethodInfo();
                info.name = methodStart.group(1);
                info.function = BslModuleAccess.FUNC_KEYWORD_PATTERN.matcher(line).find();
                info.params = extractTextParams(methodStart.group(2));
                info.startLine = lineNumber;
                info.endLine = findMethodEnd(lines, i);
                info.export = detectTextExport(lines, i);
                info.region = regionNameStack.isEmpty()
                    ? null : regionNameStack.get(regionNameStack.size() - 1);
                if (includeComments)
                {
                    info.doc = extractDocCommentFromLines(lines, lineNumber);
                }
                methods.add(info);
            }
        }

        while (!regionNameStack.isEmpty())
        {
            int last = regionNameStack.size() - 1;
            regions.add(new RegionRange(regionNameStack.remove(last),
                regionStartStack.remove(last).intValue(), lineCount));
        }
        return methods;
    }

    /**
     * Pulls the parameter text out of the remainder of a method header line.
     *
     * @param afterParen the text from just after the opening parenthesis to end of line
     * @return the parameter list text, before the first closing parenthesis, trimmed
     */
    private static String extractTextParams(String afterParen)
    {
        int close = afterParen.indexOf(')');
        String params = close >= 0 ? afterParen.substring(0, close) : afterParen;
        return params.trim();
    }

    /**
     * Finds the end line of a method whose declaration is at {@code declIndex}.
     *
     * @param lines the module lines
     * @param declIndex the 0-based index of the declaration line
     * @return the 1-based end line, or the last line when the method is not terminated
     */
    private static int findMethodEnd(List<String> lines, int declIndex)
    {
        for (int i = declIndex + 1; i < lines.size(); i++)
        {
            if (BslModuleAccess.METHOD_END_PATTERN.matcher(lines.get(i)).find())
            {
                return i + 1;
            }
        }
        return lines.size();
    }

    /**
     * Best-effort detection of the {@code Export} keyword after a method's parameter list, tolerating
     * a signature that spans several lines and a trailing line comment.
     *
     * @param lines the module lines
     * @param declIndex the 0-based index of the declaration line
     * @return <code>true</code> when the method is exported
     */
    private static boolean detectTextExport(List<String> lines, int declIndex)
    {
        int depth = 0;
        boolean started = false;
        int limit = Math.min(lines.size(), declIndex + SIGNATURE_SCAN_LIMIT);
        for (int i = declIndex; i < limit; i++)
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
     * Reads a module's lines, answering an empty list rather than failing.
     *
     * @param project the project
     * @param modulePath the resolved module path
     * @return the lines, or an empty list on any read error
     */
    private static List<String> readLinesQuietly(IProject project, String modulePath)
    {
        try
        {
            IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
            if (!file.exists())
            {
                return new ArrayList<>();
            }
            return BslModuleAccess.readFileLines(file);
        }
        catch (Exception e)
        {
            return new ArrayList<>();
        }
    }

    /** One region and the lines it spans. */
    private static final class RegionRange
    {
        final String name;

        final int start;

        final int end;

        RegionRange(String name, int start, int end)
        {
            this.name = name;
            this.start = start;
            this.end = end;
        }
    }

    /** One module-level variable. */
    private static final class VariableInfo
    {
        final String name;

        final boolean export;

        final int line;

        final String region;

        VariableInfo(String name, boolean export, int line, String region)
        {
            this.name = name;
            this.export = export;
            this.line = line;
            this.region = region;
        }
    }

    /** One method's row data, common to the model and text paths. */
    private static final class MethodInfo
    {
        boolean function;

        String name;

        boolean export;

        String context;

        String params;

        int startLine;

        int endLine;

        String region;

        String doc;
    }
}
