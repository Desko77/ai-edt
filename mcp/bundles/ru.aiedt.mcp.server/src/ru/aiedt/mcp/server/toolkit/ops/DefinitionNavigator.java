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
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;

import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.ops.BslModuleAccess.ModulePathResolution;
import ru.aiedt.mcp.server.support.YamlFrontMatter;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Resolves a symbol given by name - a qualified method, a bare method with a module for context, or a
 * metadata FQN - to where it is defined, and hands back its source and location.
 * <p>
 * A two-part name is first read as {@code Module.Method}: when the first part names a common module,
 * the second is a method in it. Failing that the pair is read as a metadata object, whose modules are
 * listed. A one-part name is a method that needs a module path to look in. Russian and plural type
 * names are accepted because the name is normalized before it is split, and a miss answers with the
 * near matches rather than a dead end.
 * </p>
 */
public class DefinitionNavigator
    implements IMcpTool
{
    @Override
    public String getName()
    {
        return "go_to_definition"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `code_search` `operation=resolve_symbol`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Jump to a symbol's definition. Resolves 'ModuleName.MethodName' to its source and " //$NON-NLS-1$
            + "location, and also resolves metadata FQNs such as 'Catalog.Products'. Russian type " //$NON-NLS-1$
            + "names are accepted."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("symbol", //$NON-NLS-1$
                "Accepted forms: 'ModuleName.MethodName', a bare 'MethodName' (needs modulePath), " //$NON-NLS-1$
                    + "or 'Catalog.Products' (a metadata FQN). Russian type names also work.", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path under src/, or a module FQN such as 'CommonModule.MyModule'. Needed when " //$NON-NLS-1$
                    + "symbol is an unqualified method name.") //$NON-NLS-1$
            .booleanProperty("includeSource", "Whether to include the source code (default: true)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String symbol = JsonUtils.extractStringArgument(params, "symbol"); //$NON-NLS-1$
        if (symbol == null || symbol.isEmpty())
        {
            return "definition.md"; //$NON-NLS-1$
        }
        return "definition-" + symbol.replace('.', '-').toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String symbol = JsonUtils.extractStringArgument(params, "symbol"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String includeSourceRaw = JsonUtils.extractStringArgument(params, "includeSource"); //$NON-NLS-1$
        boolean includeSource = !"false".equalsIgnoreCase(includeSourceRaw); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName parameter is required"; //$NON-NLS-1$
        }
        if (symbol == null || symbol.isEmpty())
        {
            return "Error: symbol parameter is required"; //$NON-NLS-1$
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);

        try
        {
            return UiSync.call(() -> resolveDefinition(project, projectName, symbol, modulePath, includeSource));
        }
        catch (Exception e)
        {
            return "Error: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Normalizes the symbol, then routes it to the two-part or one-part resolver.
     *
     * @param project the project
     * @param projectName the project name
     * @param symbol the symbol
     * @param modulePath the context module path, for a bare method name
     * @param includeSource whether to include source code
     * @return the answer
     */
    private String resolveDefinition(IProject project, String projectName, String symbol,
        String modulePath, boolean includeSource)
    {
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }
        String normalized = MetadataTypeCatalog.normalizeFqn(symbol);
        String[] parts = normalized.split("\\.", 2); //$NON-NLS-1$
        if (parts.length == 2)
        {
            return resolveTwoPartSymbol(project, projectName, parts[0], parts[1], includeSource);
        }
        return resolveSinglePartSymbol(project, projectName, normalized, modulePath, includeSource);
    }

    /**
     * Resolves a two-part name: a common-module method first, else a metadata object.
     *
     * @param project the project
     * @param projectName the project name
     * @param first the part before the dot
     * @param second the part after the dot
     * @param includeSource whether to include source code
     * @return the answer
     */
    private String resolveTwoPartSymbol(IProject project, String projectName, String first,
        String second, boolean includeSource)
    {
        IConfigurationProvider configurationProvider = Activator.getDefault().getConfigurationProvider();
        if (configurationProvider == null)
        {
            return "Error: no configuration provider available"; //$NON-NLS-1$
        }
        Configuration config = configurationProvider.getConfiguration(project);
        if (config == null)
        {
            return "Error: unable to load configuration for the project"; //$NON-NLS-1$
        }

        CommonModule commonModule = findCommonModuleByName(config, first);
        if (commonModule != null)
        {
            String moduleName = commonModule.getName();
            return resolveMethodInModule(project, projectName,
                "CommonModules/" + moduleName + "/Module.bsl", second, includeSource, moduleName); //$NON-NLS-1$ //$NON-NLS-2$
        }

        MdObject mdObject = MetadataTypeCatalog.findObject(config, first, second);
        if (mdObject != null)
        {
            return formatMetadataDefinition(project, projectName, first, mdObject);
        }
        return buildNotFoundResponse(config, first, second);
    }

    /**
     * Resolves a one-part name: a method looked up in a module the caller must name.
     *
     * @param project the project
     * @param projectName the project name
     * @param methodName the method name
     * @param modulePath the context module path
     * @param includeSource whether to include source code
     * @return the answer
     */
    private String resolveSinglePartSymbol(IProject project, String projectName, String methodName,
        String modulePath, boolean includeSource)
    {
        if (modulePath == null || modulePath.isEmpty())
        {
            return "Error: modulePath must be provided when symbol is an unqualified method name. " //$NON-NLS-1$
                + "Pass the context module path (e.g. 'CommonModules/MyModule/Module.bsl'), or call " //$NON-NLS-1$
                + "it with the qualified name 'ModuleName.MethodName'."; //$NON-NLS-1$
        }
        ModulePathResolution resolution = BslModuleAccess.resolveModulePath(project, modulePath);
        if (!resolution.isResolved())
        {
            return resolution.getHint();
        }
        return resolveMethodInModule(project, projectName, resolution.getPath(), methodName,
            includeSource, null);
    }

    /**
     * Resolves a method in a module off the model, falling back to text.
     *
     * @param project the project
     * @param projectName the project name
     * @param modulePath the resolved module path
     * @param methodName the method name
     * @param includeSource whether to include source code
     * @param qualifierPrefix the prefix for the {@code qualifiedName} field, or <code>null</code>
     * @return the answer
     */
    private String resolveMethodInModule(IProject project, String projectName, String modulePath,
        String methodName, boolean includeSource, String qualifierPrefix)
    {
        Module module = BslModuleAccess.loadModule(project, modulePath);
        if (module == null)
        {
            return resolveMethodViaText(project, projectName, modulePath, methodName, includeSource,
                qualifierPrefix);
        }
        Method method = BslModuleAccess.findMethod(module, methodName);
        if (method == null)
        {
            return BslModuleAccess.buildMethodNotFoundResponse(module, modulePath, methodName);
        }

        int startLine = BslModuleAccess.getStartLine(method);
        int endLine = BslModuleAccess.getEndLine(method);

        List<String> lines = null;
        try
        {
            lines = BslModuleAccess.readFileLines(project.getFile(new Path("src").append(modulePath))); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            lines = null;
        }

        int from;
        int to;
        String region = null;
        if (lines != null)
        {
            from = Math.max(1, findDocCommentStart(lines, startLine));
            to = Math.min(lines.size(), endLine);
            region = BslModuleAccess.findRegionForLine(lines, startLine);
        }
        else
        {
            from = startLine;
            to = endLine;
        }
        String type = method instanceof Function ? "Function" : "Procedure"; //$NON-NLS-1$ //$NON-NLS-2$

        YamlFrontMatter frontMatter = YamlFrontMatter.create()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("module", modulePath) //$NON-NLS-1$
            .put("method", method.getName()) //$NON-NLS-1$
            .put("type", type) //$NON-NLS-1$
            .put("export", method.isExport()) //$NON-NLS-1$
            .put("startLine", from) //$NON-NLS-1$
            .put("endLine", to); //$NON-NLS-1$
        if (lines != null)
        {
            frontMatter.put("totalLines", lines.size()); //$NON-NLS-1$
        }
        if (region != null)
        {
            frontMatter.put("region", region); //$NON-NLS-1$
        }
        if (qualifierPrefix != null)
        {
            frontMatter.put("qualifiedName", qualifierPrefix + "." + methodName); //$NON-NLS-1$ //$NON-NLS-2$
        }

        StringBuilder body = new StringBuilder();
        if (includeSource)
        {
            body.append("```bsl\n"); //$NON-NLS-1$
            if (lines != null)
            {
                for (int i = from - 1; i < to; i++)
                {
                    body.append(lines.get(i)).append("\n"); //$NON-NLS-1$
                }
            }
            else
            {
                String source = BslModuleAccess.getSourceText(method);
                if (source == null)
                {
                    source = ""; //$NON-NLS-1$
                }
                body.append(source);
                if (!source.endsWith("\n")) //$NON-NLS-1$
                {
                    body.append("\n"); //$NON-NLS-1$
                }
            }
            body.append("```\n"); //$NON-NLS-1$
        }
        return frontMatter.wrapContent(body.toString());
    }

    /**
     * Resolves a method in a module by scanning the text, for when the model is not built.
     *
     * @param project the project
     * @param projectName the project name
     * @param modulePath the resolved module path
     * @param methodName the method name
     * @param includeSource whether to include source code
     * @param qualifierPrefix the prefix for the {@code qualifiedName} field, or <code>null</code>
     * @return the answer
     */
    private String resolveMethodViaText(IProject project, String projectName, String modulePath,
        String methodName, boolean includeSource, String qualifierPrefix)
    {
        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        if (!file.exists())
        {
            return "Error: no such module: src/" + modulePath; //$NON-NLS-1$
        }
        List<String> lines;
        try
        {
            lines = BslModuleAccess.readFileLines(file);
        }
        catch (Exception e)
        {
            return "Error: could not read file: " + e.getMessage(); //$NON-NLS-1$
        }

        int methodStart = -1;
        boolean isFunction = false;
        String matchedName = null;
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
                    matchedName = name;
                }
            }
        }

        if (methodStart < 0)
        {
            StringBuilder builder = new StringBuilder();
            builder.append("Error: no method called '").append(methodName).append("' not found in ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(modulePath).append("\n\n"); //$NON-NLS-1$
            builder.append("**Methods in this module** (").append(available.size()).append("):\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
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

        YamlFrontMatter frontMatter = YamlFrontMatter.create()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("module", modulePath) //$NON-NLS-1$
            .put("method", matchedName) //$NON-NLS-1$
            .put("type", type) //$NON-NLS-1$
            .put("startLine", from) //$NON-NLS-1$
            .put("endLine", to) //$NON-NLS-1$
            .put("totalLines", lines.size()); //$NON-NLS-1$
        if (region != null)
        {
            frontMatter.put("region", region); //$NON-NLS-1$
        }
        if (qualifierPrefix != null)
        {
            frontMatter.put("qualifiedName", qualifierPrefix + "." + methodName); //$NON-NLS-1$ //$NON-NLS-2$
        }

        StringBuilder body = new StringBuilder();
        if (includeSource)
        {
            body.append("```bsl\n"); //$NON-NLS-1$
            for (int i = from - 1; i < to; i++)
            {
                body.append(lines.get(i)).append("\n"); //$NON-NLS-1$
            }
            body.append("```\n"); //$NON-NLS-1$
        }
        return frontMatter.wrapContent(body.toString());
    }

    /**
     * Formats a metadata-object definition: its identity plus the modules it owns.
     *
     * @param project the project
     * @param projectName the project name
     * @param typeName the type segment of the FQN
     * @param mdObject the object
     * @return the answer
     */
    private String formatMetadataDefinition(IProject project, String projectName, String typeName,
        MdObject mdObject)
    {
        YamlFrontMatter frontMatter = YamlFrontMatter.create()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("kind", "MetadataObject") //$NON-NLS-1$ //$NON-NLS-2$
            .put("type", typeName) //$NON-NLS-1$
            .put("name", mdObject.getName()); //$NON-NLS-1$

        StringBuilder body = new StringBuilder();
        String folder = MetadataTypeCatalog.getDirectoryName(typeName);
        if (folder != null)
        {
            IResource objectDir = project.findMember("src/" + folder + "/" + mdObject.getName()); //$NON-NLS-1$ //$NON-NLS-2$
            List<String> modules = objectDir != null ? collectBslModules(objectDir) : new ArrayList<>();
            if (modules.isEmpty())
            {
                body.append("This object has no BSL modules.\n"); //$NON-NLS-1$
            }
            else
            {
                body.append("### Modules\n\n"); //$NON-NLS-1$
                for (String module : modules)
                {
                    body.append("- ").append(module).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        body.append("\n*Call `get_metadata_details` for the full set of properties, or " //$NON-NLS-1$
            + "`read_module_source`/`read_method_source` to read a particular module.*\n"); //$NON-NLS-1$
        return frontMatter.wrapContent(body.toString());
    }

    /**
     * Builds the "symbol not found" answer, with near matches and the accepted type names.
     *
     * @param config the configuration
     * @param first the part before the dot
     * @param second the part after the dot
     * @return the answer
     */
    private String buildNotFoundResponse(Configuration config, String first, String second)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("## No such symbol: ").append(first).append(".").append(second) //$NON-NLS-1$ //$NON-NLS-2$
            .append("\n\n"); //$NON-NLS-1$

        if (MetadataTypeCatalog.isMetadataTypeName(first))
        {
            String englishType = MetadataTypeCatalog.toEnglishSingular(first);
            List<String> similar = MetadataTypeCatalog.findSimilarObjects(config, englishType, second, 10);
            builder.append("### Closest Matches\n\n"); //$NON-NLS-1$
            for (String name : similar)
            {
                builder.append("- ").append(englishType).append(".").append(name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            builder.append("\n"); //$NON-NLS-1$
        }
        else
        {
            List<String> similar =
                MetadataTypeCatalog.findSimilarObjects(config, "CommonModule", first, 10); //$NON-NLS-1$
            builder.append("### Common Modules With a Similar Name\n\n"); //$NON-NLS-1$
            for (String name : similar)
            {
                builder.append("- ").append(name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            builder.append("\n"); //$NON-NLS-1$
        }

        builder.append("### Recognized Metadata Types\n\n"); //$NON-NLS-1$
        builder.append(String.join(", ", MetadataTypeCatalog.getAllEnglishSingularNames())) //$NON-NLS-1$
            .append("\n\n"); //$NON-NLS-1$
        builder.append("Russian type names work too (Справочник, Документ, " //$NON-NLS-1$
            + "РегистрСведений, and so on).\n\n"); //$NON-NLS-1$
        builder.append("**Tip:** for methods outside common modules, pass the full module path:\n"); //$NON-NLS-1$
        builder.append("`go_to_definition(symbol='MethodName', " //$NON-NLS-1$
            + "modulePath='Documents/SalesOrder/ObjectModule.bsl')`\n"); //$NON-NLS-1$
        return builder.toString();
    }

    /**
     * Finds a common module by name, ignoring case.
     *
     * @param config the configuration
     * @param name the module name
     * @return the module, or <code>null</code> when none matches
     */
    private static CommonModule findCommonModuleByName(Configuration config, String name)
    {
        for (CommonModule commonModule : config.getCommonModules())
        {
            if (name.equalsIgnoreCase(commonModule.getName()))
            {
                return commonModule;
            }
        }
        return null;
    }

    /**
     * Collects the {@code .bsl} modules under a folder, in visitation order, as src-relative paths.
     *
     * @param root the folder to walk
     * @return the module paths
     */
    private static List<String> collectBslModules(IResource root)
    {
        List<String> modules = new ArrayList<>();
        try
        {
            root.accept(resource -> {
                if (resource.getType() == IResource.FILE
                    && "bsl".equalsIgnoreCase(((IFile)resource).getFileExtension())) //$NON-NLS-1$
                {
                    String relative = resource.getProjectRelativePath().toString();
                    modules.add(relative.startsWith("src/") ? relative.substring(4) : relative); //$NON-NLS-1$
                }
                return true;
            });
        }
        catch (CoreException e)
        {
            // A folder that will not enumerate simply contributes no modules.
        }
        return modules;
    }

    /**
     * Finds the 1-based line a method's leading doc-comment begins on, or its declaration line when
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
}
