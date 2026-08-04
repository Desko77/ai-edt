/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com._1c.g5.v8.dt.bsl.model.RegionPreprocessor;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.mdreport.MetadataFormatterHub;
import ru.aiedt.mcp.server.support.BmDcsHelper;
import ru.aiedt.mcp.server.support.YamlFrontMatter;
import ru.aiedt.mcp.server.support.MarkdownTableHelper;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Context aggregator tool that collects metadata, modules, structure, and source code
 * for a 1C metadata object or module in a single call.
 * <p>
 * Combines functionality of get_metadata_details, list_modules, and get_module_structure
 * to reduce round-trips when investigating a metadata object.
 */
public class AiContextTool implements IMcpTool
{
    public static final String NAME = "ai_context"; //$NON-NLS-1$

    private static final int MAX_RECURSION_DEPTH = 20;
    private static final int DEFAULT_MAX_METHODS = 30;

    /**
     * Classification of the target parameter.
     */
    private enum TargetType
    {
        /** FQN like "Catalog.Products" or "Document.SalesOrder" */
        METADATA_OBJECT,
        /** FQN like "CommonModule.MyModule" */
        COMMON_MODULE,
        /** Path like "CommonModules/MyModule/Module.bsl" */
        MODULE_PATH
    }

    /**
     * Information about a discovered BSL module.
     */
    private static class ModuleInfo
    {
        String relativePath; // from src/
        String moduleType;   // ObjectModule, ManagerModule, FormModule, etc.
        IFile file;
        int lineCount;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Aggregate context about a 1C metadata object or module in one call. " + //$NON-NLS-1$
               "Returns metadata details, list of BSL modules, and module structure. " + //$NON-NLS-1$
               "Combines get_metadata_details + list_modules + get_module_structure " + //$NON-NLS-1$
               "to reduce round-trips. Use 'depth' to control detail level."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Name of the EDT project to work in", true) //$NON-NLS-1$
            .stringProperty("target", //$NON-NLS-1$
                "FQN like 'Catalog.Products', 'CommonModule.MyModule', " + //$NON-NLS-1$
                "or path like 'CommonModules/MyModule/Module.bsl'. " + //$NON-NLS-1$
                "Russian type names supported (e.g. '\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u041D\u043E\u043C\u0435\u043D\u043A\u043B\u0430\u0442\u0443\u0440\u0430'). Required.", //$NON-NLS-1$
                true)
            .stringProperty("depth", //$NON-NLS-1$
                "Detail level: 'minimal' (metadata + module list), " + //$NON-NLS-1$
                "'standard' (+ method structure, default), " + //$NON-NLS-1$
                "'full' (+ source code)") //$NON-NLS-1$
            .stringProperty("focusMethod", //$NON-NLS-1$
                "Method name for detailed analysis - only this method's source is included in 'full' mode") //$NON-NLS-1$
            .booleanProperty("includeSource", //$NON-NLS-1$
                "Include source code regardless of depth. Default: false for minimal/standard, true for full") //$NON-NLS-1$
            .integerProperty("maxMethods", //$NON-NLS-1$
                "Max methods to show per module in structure. Default: 30") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String target = JsonUtils.extractStringArgument(params, "target"); //$NON-NLS-1$
        if (target != null && !target.isEmpty())
        {
            String safeName = target.replace("/", "-").replace("\\", "-") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .replace(".", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            return "ai-context-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "ai-context.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String target = JsonUtils.extractStringArgument(params, "target"); //$NON-NLS-1$
        String depth = JsonUtils.extractStringArgument(params, "depth"); //$NON-NLS-1$
        String focusMethod = JsonUtils.extractStringArgument(params, "focusMethod"); //$NON-NLS-1$
        boolean includeSource = JsonUtils.extractBooleanArgument(params, "includeSource", false); //$NON-NLS-1$
        int maxMethods = JsonUtils.extractIntArgument(params, "maxMethods", DEFAULT_MAX_METHODS); //$NON-NLS-1$

        // Validate required parameters
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (target == null || target.isEmpty())
        {
            return "Error: target is required. Examples: 'Catalog.Products', " + //$NON-NLS-1$
                   "'CommonModule.MyModule', 'CommonModules/MyModule/Module.bsl'"; //$NON-NLS-1$
        }

        // Normalize depth. An empty value uses the default; an explicitly WRONG value is
        // surfaced (was silently reset to "standard", so the agent never learned its
        // depth argument was ignored).
        if (depth == null || depth.isEmpty())
        {
            depth = "standard"; //$NON-NLS-1$
        }
        else
        {
            depth = depth.toLowerCase();
            if (!"minimal".equals(depth) && !"standard".equals(depth) && !"full".equals(depth)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                return "Error: " + TextSuggest.invalidValue("depth", depth, //$NON-NLS-1$ //$NON-NLS-2$
                    java.util.Arrays.asList("minimal", "standard", "full")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }

        // For full depth, include source by default
        if ("full".equals(depth)) //$NON-NLS-1$
        {
            includeSource = true;
        }

        maxMethods = Math.max(1, Math.min(maxMethods, 200));

        // Classify target
        TargetType targetType = classifyTarget(target);

        final String depthFinal = depth;
        final boolean includeSourceFinal = includeSource;
        final int maxMethodsFinal = maxMethods;
        final TargetType targetTypeFinal = targetType;

        try
        {
            return UiSync.call(() -> executeInternal(projectName, target, targetTypeFinal,
                depthFinal, focusMethod, includeSourceFinal, maxMethodsFinal));
        }
        catch (Exception e)
        {
            Activator.logError("Error in ai_context", e); //$NON-NLS-1$
            return "Error: " + TextSuggest.safeMessage(e); //$NON-NLS-1$
        }
    }

    // -- Target classification --

    /**
     * Classifies the target string into one of the three target types.
     */
    private TargetType classifyTarget(String target)
    {
        // Path-based target
        if (target.contains("/") || target.endsWith(".bsl")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return TargetType.MODULE_PATH;
        }

        // Split by dot for FQN analysis
        int dotIdx = target.indexOf('.');
        if (dotIdx > 0)
        {
            String typePart = target.substring(0, dotIdx);
            String normalized = MetadataTypeCatalog.toEnglishSingular(typePart);
            if ("CommonModule".equals(normalized)) //$NON-NLS-1$
            {
                return TargetType.COMMON_MODULE;
            }
        }

        // Default: metadata object (Type.Name)
        return TargetType.METADATA_OBJECT;
    }

    // -- Main execution --

    private String executeInternal(String projectName, String target, TargetType targetType,
        String depth, String focusMethod, boolean includeSource, int maxMethods)
    {
        // Resolve project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
        }

        StringBuilder md = new StringBuilder();
        YamlFrontMatter fm = YamlFrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("target", target) //$NON-NLS-1$
            .put("targetType", targetType.name()) //$NON-NLS-1$
            .put("depth", depth) //$NON-NLS-1$
            .put("projectName", projectName); //$NON-NLS-1$

        // Parse target parts
        String mdTypeName = null;
        String mdObjectName = null;
        String directoryName = null;

        if (targetType == TargetType.METADATA_OBJECT || targetType == TargetType.COMMON_MODULE)
        {
            int dotIdx = target.indexOf('.');
            if (dotIdx <= 0)
            {
                return "Error: Invalid FQN: " + target + ". Expected format: Type.Name"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            mdTypeName = target.substring(0, dotIdx);
            mdObjectName = target.substring(dotIdx + 1);

            // Normalize type
            String normalized = MetadataTypeCatalog.toEnglishSingular(mdTypeName);
            if (normalized != null)
            {
                mdTypeName = normalized;
            }
            directoryName = MetadataTypeCatalog.getDirectoryName(mdTypeName);
        }

        md.append("# Context: ").append(target).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // ---- METADATA section ----
        if (targetType != TargetType.MODULE_PATH)
        {
            appendMetadata(md, project, mdTypeName, mdObjectName, fm);
        }

        // ---- MODULES section ----
        List<ModuleInfo> modules = discoverModules(project, targetType, target,
            directoryName, mdObjectName);
        appendModulesList(md, modules, fm);

        // ---- STRUCTURE section (standard+) ----
        if (!"minimal".equals(depth)) //$NON-NLS-1$
        {
            int totalMethods = 0;
            for (ModuleInfo module : modules)
            {
                totalMethods += appendModuleStructure(md, project, module, maxMethods);
            }
            fm.put("totalMethods", totalMethods); //$NON-NLS-1$
        }

        // ---- SOURCE section (full or includeSource) ----
        if (includeSource)
        {
            appendSourceCode(md, modules, focusMethod);
        }

        return fm.wrapContent(md.toString());
    }

    // -- Metadata --

    /**
     * Appends metadata details for the target object.
     */
    private void appendMetadata(StringBuilder md, IProject project, String mdType,
        String mdName, YamlFrontMatter fm)
    {
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            md.append("## Metadata\n\n"); //$NON-NLS-1$
            md.append("*configuration provider is not published as a service*\n\n"); //$NON-NLS-1$
            return;
        }

        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            md.append("## Metadata\n\n"); //$NON-NLS-1$
            md.append("*Could not get configuration for project*\n\n"); //$NON-NLS-1$
            return;
        }

        // Determine language
        String language = "ru"; //$NON-NLS-1$
        if (config.getDefaultLanguage() != null)
        {
            language = config.getDefaultLanguage().getName();
        }

        MdObject mdObject = MetadataTypeCatalog.findObject(config, mdType, mdName);
        if (mdObject == null)
        {
            md.append("## Metadata\n\n"); //$NON-NLS-1$
            md.append("*Object not found: ").append(mdType).append(".").append(mdName).append("*\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            // Suggest similar names
            List<String> similar = MetadataTypeCatalog.findSimilarObjects(config, mdType, mdName, 5);
            if (!similar.isEmpty())
            {
                md.append("**Similar objects:** "); //$NON-NLS-1$
                for (int i = 0; i < similar.size(); i++)
                {
                    if (i > 0)
                    {
                        md.append(", "); //$NON-NLS-1$
                    }
                    md.append(similar.get(i));
                }
                md.append("\n\n"); //$NON-NLS-1$
            }
            return;
        }

        md.append("## Metadata\n\n"); //$NON-NLS-1$
        String formatted = MetadataFormatterHub.format(mdObject, true, language);
        md.append(formatted);
        md.append("\n"); //$NON-NLS-1$

        // H4: compact DCS schema overview for report-like objects, so a large
        // schema does not flood the context with the full query+settings.
        if ("Report".equals(mdType) || "DataProcessor".equals(mdType)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            appendDcsOverview(md, project, mdType + "." + mdName); //$NON-NLS-1$
        }
    }

    /**
     * H4: appends a compact DCS schema map (datasets with field counts + query
     * length, calculated / total / parameter counts, settings-variant names) so
     * a large report schema does not flood ai_context. Skips silently when the
     * owner has no schema or the DCS API is unavailable.
     */
    private void appendDcsOverview(StringBuilder md, IProject project, String ownerFqn)
    {
        Map<String, Object> dcs;
        try
        {
            dcs = BmDcsHelper.summarizeSchema(project, ownerFqn, null);
        }
        catch (Exception e)
        {
            return;
        }
        if (dcs == null)
        {
            return;
        }
        md.append("## DCS Schema (overview)\n\n"); //$NON-NLS-1$
        md.append("*Compact map - call dcs_workshop or get_metadata_details for full detail.*\n\n"); //$NON-NLS-1$
        md.append("- Datasets: ").append(dcs.get("dataSetCount")) //$NON-NLS-1$ //$NON-NLS-2$
            .append(", fields: ").append(dcs.get("dataSetFieldCount")) //$NON-NLS-1$ //$NON-NLS-2$
            .append(", calculated: ").append(dcs.get("calculatedFieldCount")) //$NON-NLS-1$ //$NON-NLS-2$
            .append(", totals: ").append(dcs.get("totalFieldCount")).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        md.append("- Parameters: ").append(dcs.get("parameterCount")) //$NON-NLS-1$ //$NON-NLS-2$
            .append(", settings variants: ").append(dcs.get("settingsVariantCount")) //$NON-NLS-1$ //$NON-NLS-2$
            .append("\n\n"); //$NON-NLS-1$

        Object dsObj = dcs.get("dataSets"); //$NON-NLS-1$
        if (dsObj instanceof List && !((List<?>)dsObj).isEmpty())
        {
            // Only queryLength is shown here (not the full query / queryPreview) -
            // the whole point of H4 is to keep the schema compact in the context;
            // the preview is available to JSON consumers via object_summary.
            md.append("| Dataset | Kind | Fields | Query len |\n"); //$NON-NLS-1$
            md.append("|---|---|---|---|\n"); //$NON-NLS-1$
            for (Object o : (List<?>)dsObj)
            {
                if (!(o instanceof Map))
                {
                    continue;
                }
                Map<?, ?> dm = (Map<?, ?>)o;
                md.append("| ").append(MarkdownTableHelper.escapeForTable(asText(dm.get("name")))) //$NON-NLS-1$ //$NON-NLS-2$
                    .append(" | ").append(MarkdownTableHelper.escapeForTable(asText(dm.get("kind")))) //$NON-NLS-1$ //$NON-NLS-2$
                    .append(" | ").append(dm.get("fieldCount") != null ? dm.get("fieldCount") : 0) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .append(" | ") //$NON-NLS-1$
                    .append(dm.get("queryLength") != null ? dm.get("queryLength") : "-") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .append(" |\n"); //$NON-NLS-1$
            }
            md.append("\n"); //$NON-NLS-1$
        }

        appendNameList(md, "Parameters", dcs.get("parameters")); //$NON-NLS-1$ //$NON-NLS-2$
        appendNameList(md, "Settings variants", dcs.get("settingsVariants")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void appendNameList(StringBuilder md, String label, Object listObj)
    {
        if (!(listObj instanceof List) || ((List<?>)listObj).isEmpty())
        {
            return;
        }
        md.append("- ").append(label).append(": "); //$NON-NLS-1$ //$NON-NLS-2$
        List<?> list = (List<?>)listObj;
        for (int i = 0; i < list.size(); i++)
        {
            if (i > 0)
            {
                md.append(", "); //$NON-NLS-1$
            }
            md.append(MarkdownTableHelper.escapeForTable(asText(list.get(i))));
        }
        md.append("\n\n"); //$NON-NLS-1$
    }

    private static String asText(Object o)
    {
        return o != null ? o.toString() : ""; //$NON-NLS-1$
    }

    // -- Module discovery --

    /**
     * Discovers BSL modules for the target.
     */
    private List<ModuleInfo> discoverModules(IProject project, TargetType targetType,
        String target, String directoryName, String objectName)
    {
        List<ModuleInfo> modules = new ArrayList<>();

        switch (targetType)
        {
            case MODULE_PATH:
                discoverSingleModule(project, modules, target);
                break;

            case COMMON_MODULE:
                discoverCommonModuleModules(project, modules, objectName);
                break;

            case METADATA_OBJECT:
                if (directoryName != null && objectName != null)
                {
                    discoverObjectModules(project, modules, directoryName, objectName);
                }
                break;
        }

        return modules;
    }

    /**
     * Discovers a single module by path.
     */
    private void discoverSingleModule(IProject project, List<ModuleInfo> modules, String modulePath)
    {
        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        if (file.exists())
        {
            ModuleInfo info = new ModuleInfo();
            info.relativePath = modulePath;
            info.moduleType = determineModuleType(modulePath);
            info.file = file;
            info.lineCount = countLines(file);
            modules.add(info);
        }
    }

    /**
     * Discovers modules for a common module (just Module.bsl).
     */
    private void discoverCommonModuleModules(IProject project, List<ModuleInfo> modules,
        String moduleName)
    {
        String path = "CommonModules/" + moduleName + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
        IFile file = project.getFile(new Path("src").append(path)); //$NON-NLS-1$
        if (file.exists())
        {
            ModuleInfo info = new ModuleInfo();
            info.relativePath = path;
            info.moduleType = "Module"; //$NON-NLS-1$
            info.file = file;
            info.lineCount = countLines(file);
            modules.add(info);
        }
    }

    /**
     * Discovers all BSL modules under a metadata object directory.
     */
    private void discoverObjectModules(IProject project, List<ModuleInfo> modules,
        String directoryName, String objectName)
    {
        String basePath = directoryName + "/" + objectName; //$NON-NLS-1$
        IContainer folder = project.getFolder(new Path("src").append(basePath)); //$NON-NLS-1$
        if (!folder.exists())
        {
            return;
        }

        try
        {
            scanBslFilesRecursive(folder, modules, basePath, 0);
        }
        catch (Exception e)
        {
            Activator.logError("Error scanning BSL modules for " + basePath, e); //$NON-NLS-1$
        }
    }

    /**
     * Recursively scans for .bsl files under a container.
     */
    private void scanBslFilesRecursive(IContainer container, List<ModuleInfo> modules,
        String basePath, int depth)
        throws Exception
    {
        if (depth > MAX_RECURSION_DEPTH)
        {
            return;
        }

        for (IResource member : container.members())
        {
            if (member instanceof IFile)
            {
                IFile file = (IFile) member;
                if (file.getName().endsWith(".bsl")) //$NON-NLS-1$
                {
                    String fullPath = file.getProjectRelativePath().toString();
                    String modulePath = fullPath.startsWith("src/") //$NON-NLS-1$
                        ? fullPath.substring(4) : fullPath;

                    ModuleInfo info = new ModuleInfo();
                    info.relativePath = modulePath;
                    info.moduleType = determineModuleType(modulePath, basePath);
                    info.file = file;
                    info.lineCount = countLines(file);
                    modules.add(info);
                }
            }
            else if (member instanceof IContainer)
            {
                scanBslFilesRecursive((IContainer) member, modules, basePath, depth + 1);
            }
        }
    }

    /**
     * Determines module type from full path (for MODULE_PATH targets).
     */
    private String determineModuleType(String modulePath)
    {
        String fileName = modulePath.contains("/") //$NON-NLS-1$
            ? modulePath.substring(modulePath.lastIndexOf('/') + 1)
            : modulePath;

        String baseName = fileName.endsWith(".bsl") //$NON-NLS-1$
            ? fileName.substring(0, fileName.length() - 4)
            : fileName;

        if ("Module".equals(baseName)) //$NON-NLS-1$
        {
            if (modulePath.contains("Forms/")) //$NON-NLS-1$
            {
                return "FormModule"; //$NON-NLS-1$
            }
            if (modulePath.startsWith("CommonModules/")) //$NON-NLS-1$
            {
                return "Module"; //$NON-NLS-1$
            }
            return "Module"; //$NON-NLS-1$
        }

        return baseName;
    }

    /**
     * Determines module type from path relative to a base path.
     */
    private String determineModuleType(String modulePath, String basePath)
    {
        String relativePath = modulePath.substring(basePath.length());
        if (relativePath.startsWith("/")) //$NON-NLS-1$
        {
            relativePath = relativePath.substring(1);
        }

        String fileName = relativePath.contains("/") //$NON-NLS-1$
            ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
            : relativePath;

        String baseName = fileName.endsWith(".bsl") //$NON-NLS-1$
            ? fileName.substring(0, fileName.length() - 4)
            : fileName;

        if ("Module".equals(baseName)) //$NON-NLS-1$
        {
            if (relativePath.startsWith("Forms/")) //$NON-NLS-1$
            {
                return "FormModule"; //$NON-NLS-1$
            }
            return "Module"; //$NON-NLS-1$
        }

        return baseName;
    }

    /**
     * Counts lines in a file.
     */
    private int countLines(IFile file)
    {
        try
        {
            List<String> lines = BslModuleAccess.readFileLines(file);
            return lines.size();
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    // -- Output formatting --

    /**
     * Appends the modules list section.
     */
    private void appendModulesList(StringBuilder md, List<ModuleInfo> modules, YamlFrontMatter fm)
    {
        fm.put("moduleCount", modules.size()); //$NON-NLS-1$

        md.append("## Modules (").append(modules.size()).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (modules.isEmpty())
        {
            md.append("No BSL modules found.\n\n"); //$NON-NLS-1$
            return;
        }

        md.append("| Module | Path | Lines |\n"); //$NON-NLS-1$
        md.append("|--------|------|-------|\n"); //$NON-NLS-1$

        for (ModuleInfo module : modules)
        {
            md.append("| ").append(MarkdownTableHelper.escapeForTable(module.moduleType)); //$NON-NLS-1$
            md.append(" | ").append(MarkdownTableHelper.escapeForTable(module.relativePath)); //$NON-NLS-1$
            md.append(" | ").append(module.lineCount); //$NON-NLS-1$
            md.append(" |\n"); //$NON-NLS-1$
        }
        md.append("\n"); //$NON-NLS-1$
    }

    // -- Module structure --

    /**
     * Appends module structure (methods, regions) for a single module.
     *
     * @return number of methods found
     */
    private int appendModuleStructure(StringBuilder md, IProject project,
        ModuleInfo moduleInfo, int maxMethods)
    {
        md.append("## Structure: ").append(moduleInfo.moduleType); //$NON-NLS-1$
        md.append(" (").append(moduleInfo.lineCount).append(" lines)\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // Try EMF-based structure extraction
        Module module = BslModuleAccess.loadModule(project, moduleInfo.relativePath);
        if (module != null)
        {
            return appendStructureFromEmf(md, module, moduleInfo, maxMethods);
        }

        // Fallback: text-based parsing
        return appendStructureFromText(md, moduleInfo, maxMethods);
    }

    /**
     * Extracts structure using EMF AST model.
     */
    private int appendStructureFromEmf(StringBuilder md, Module module,
        ModuleInfo moduleInfo, int maxMethods)
    {
        // Collect regions
        List<RegionInfo> regions = collectRegions(module);
        if (!regions.isEmpty())
        {
            md.append("### Region map\n\n"); //$NON-NLS-1$
            for (RegionInfo region : regions)
            {
                md.append("- ").append(region.name); //$NON-NLS-1$
                md.append(" (line ").append(region.startLine); //$NON-NLS-1$
                md.append("-").append(region.endLine).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            md.append("\n"); //$NON-NLS-1$
        }

        // Collect methods
        List<MethodInfo> methods = collectMethods(module, regions);

        if (methods.isEmpty())
        {
            md.append("No methods found.\n\n"); //$NON-NLS-1$
            return 0;
        }

        int shown = Math.min(methods.size(), maxMethods);

        md.append("### Methods"); //$NON-NLS-1$
        if (shown < methods.size())
        {
            md.append(" (showing ").append(shown).append(" of ").append(methods.size()).append(")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        md.append("\n\n"); //$NON-NLS-1$

        md.append("| # | Kind | Name | Exported | Compiled for | Line span | Parameters | Region |\n"); //$NON-NLS-1$
        md.append("|---|------|------|--------|---------|-------|------------|--------|\n"); //$NON-NLS-1$

        for (int i = 0; i < shown; i++)
        {
            MethodInfo m = methods.get(i);
            md.append("| ").append(i + 1); //$NON-NLS-1$
            md.append(" | ").append(m.isFunction ? "Function" : "Procedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            md.append(" | ").append(MarkdownTableHelper.escapeForTable(m.name)); //$NON-NLS-1$
            md.append(" | ").append(m.isExport ? "Yes" : "-"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            md.append(" | ").append(m.executionContext != null //$NON-NLS-1$
                ? MarkdownTableHelper.escapeForTable(m.executionContext) : "-"); //$NON-NLS-1$
            md.append(" | ").append(m.startLine).append("-").append(m.endLine); //$NON-NLS-1$ //$NON-NLS-2$
            md.append(" | ").append(MarkdownTableHelper.escapeForTable(m.paramsString)); //$NON-NLS-1$
            md.append(" | ").append(m.region != null //$NON-NLS-1$
                ? MarkdownTableHelper.escapeForTable(m.region) : "-"); //$NON-NLS-1$
            md.append(" |\n"); //$NON-NLS-1$
        }
        md.append("\n"); //$NON-NLS-1$

        return methods.size();
    }

    /**
     * Extracts structure using text-based regex parsing (fallback).
     */
    private int appendStructureFromText(StringBuilder md, ModuleInfo moduleInfo, int maxMethods)
    {
        List<String> lines;
        try
        {
            lines = BslModuleAccess.readFileLines(moduleInfo.file);
        }
        catch (Exception e)
        {
            md.append("*Could not read module*\n\n"); //$NON-NLS-1$
            return 0;
        }

        // Parse methods
        List<MethodInfo> methods = new ArrayList<>();
        String currentMethodName = null;
        boolean currentIsFunction = false;
        String currentParams = "-"; //$NON-NLS-1$
        int currentStartLine = 0;

        for (int i = 0; i < lines.size(); i++)
        {
            String line = lines.get(i);
            int lineNum = i + 1;

            java.util.regex.Matcher startMatcher = BslModuleAccess.METHOD_START_PATTERN.matcher(line);
            if (startMatcher.find())
            {
                currentMethodName = startMatcher.group(1);
                currentIsFunction = BslModuleAccess.FUNC_KEYWORD_PATTERN.matcher(line).find();
                currentStartLine = lineNum;

                // Extract params text
                String paramsText = startMatcher.group(2);
                if (paramsText != null)
                {
                    paramsText = paramsText.replaceAll("\\)\\s*(Экспорт|Export)?\\s*$", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
                    currentParams = paramsText.isEmpty() ? "-" : paramsText; //$NON-NLS-1$
                }
                continue;
            }

            if (BslModuleAccess.METHOD_END_PATTERN.matcher(line).find() && currentMethodName != null)
            {
                MethodInfo info = new MethodInfo();
                info.name = currentMethodName;
                info.isFunction = currentIsFunction;
                info.isExport = false; // Can't reliably determine from text
                info.startLine = currentStartLine;
                info.endLine = lineNum;
                info.paramsString = currentParams;
                info.region = BslModuleAccess.findRegionForLine(lines, currentStartLine);
                methods.add(info);
                currentMethodName = null;
            }
        }

        if (methods.isEmpty())
        {
            md.append("No methods found.\n\n"); //$NON-NLS-1$
            return 0;
        }

        int shown = Math.min(methods.size(), maxMethods);

        md.append("### Methods"); //$NON-NLS-1$
        if (shown < methods.size())
        {
            md.append(" (showing ").append(shown).append(" of ").append(methods.size()).append(")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        md.append("\n\n"); //$NON-NLS-1$

        md.append("| # | Type | Name | Lines | Parameters | Region |\n"); //$NON-NLS-1$
        md.append("|---|------|------|-------|------------|--------|\n"); //$NON-NLS-1$

        for (int i = 0; i < shown; i++)
        {
            MethodInfo m = methods.get(i);
            md.append("| ").append(i + 1); //$NON-NLS-1$
            md.append(" | ").append(m.isFunction ? "Function" : "Procedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            md.append(" | ").append(MarkdownTableHelper.escapeForTable(m.name)); //$NON-NLS-1$
            md.append(" | ").append(m.startLine).append("-").append(m.endLine); //$NON-NLS-1$ //$NON-NLS-2$
            md.append(" | ").append(MarkdownTableHelper.escapeForTable(m.paramsString)); //$NON-NLS-1$
            md.append(" | ").append(m.region != null //$NON-NLS-1$
                ? MarkdownTableHelper.escapeForTable(m.region) : "-"); //$NON-NLS-1$
            md.append(" |\n"); //$NON-NLS-1$
        }
        md.append("\n"); //$NON-NLS-1$

        return methods.size();
    }

    // -- Source code --

    /**
     * Appends source code for all modules (or just focusMethod).
     */
    private void appendSourceCode(StringBuilder md, List<ModuleInfo> modules, String focusMethod)
    {
        for (ModuleInfo module : modules)
        {
            List<String> lines;
            try
            {
                lines = BslModuleAccess.readFileLines(module.file);
            }
            catch (Exception e)
            {
                md.append("## Source: ").append(module.moduleType).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                md.append("*Could not read source*\n\n"); //$NON-NLS-1$
                continue;
            }

            if (focusMethod != null && !focusMethod.isEmpty())
            {
                appendFocusMethodSource(md, module, lines, focusMethod);
            }
            else
            {
                md.append("## Source: ").append(module.moduleType).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                md.append("```bsl\n"); //$NON-NLS-1$
                for (String line : lines)
                {
                    md.append(line).append("\n"); //$NON-NLS-1$
                }
                md.append("```\n\n"); //$NON-NLS-1$
            }
        }
    }

    /**
     * Appends source code for a specific method.
     */
    private void appendFocusMethodSource(StringBuilder md, ModuleInfo module,
        List<String> lines, String focusMethod)
    {
        int methodStart = -1;
        int methodEnd = -1;

        for (int i = 0; i < lines.size(); i++)
        {
            String line = lines.get(i);

            if (methodStart < 0)
            {
                java.util.regex.Matcher matcher = BslModuleAccess.METHOD_START_PATTERN.matcher(line);
                if (matcher.find() && focusMethod.equalsIgnoreCase(matcher.group(1)))
                {
                    methodStart = i;
                }
            }
            else if (BslModuleAccess.METHOD_END_PATTERN.matcher(line).find())
            {
                methodEnd = i;
                break;
            }
        }

        if (methodStart < 0)
        {
            md.append("## Source: ").append(module.moduleType); //$NON-NLS-1$
            md.append(" (method '").append(focusMethod).append("' not found)\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        if (methodEnd < 0)
        {
            methodEnd = lines.size() - 1;
        }

        // Include doc-comments before method
        int docStart = methodStart;
        for (int i = methodStart - 1; i >= 0; i--)
        {
            String line = lines.get(i).trim();
            if (line.startsWith("//")) //$NON-NLS-1$
            {
                docStart = i;
            }
            else if (line.isEmpty())
            {
                continue;
            }
            else
            {
                break;
            }
        }

        md.append("## Source: ").append(module.moduleType); //$NON-NLS-1$
        md.append(" - ").append(focusMethod); //$NON-NLS-1$
        md.append(" (lines ").append(docStart + 1).append("-").append(methodEnd + 1).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        md.append("```bsl\n"); //$NON-NLS-1$
        for (int i = docStart; i <= methodEnd; i++)
        {
            md.append(lines.get(i)).append("\n"); //$NON-NLS-1$
        }
        md.append("```\n\n"); //$NON-NLS-1$
    }

    // -- EMF data collection --

    /**
     * Collects region information from the BSL AST.
     */
    private List<RegionInfo> collectRegions(Module module)
    {
        List<RegionInfo> regions = new ArrayList<>();

        try
        {
            for (var iter = module.eAllContents(); iter.hasNext();)
            {
                EObject obj = iter.next();
                if (obj instanceof RegionPreprocessor region)
                {
                    RegionInfo info = new RegionInfo();
                    info.name = region.getName();
                    info.startLine = BslModuleAccess.getStartLine(region);
                    info.endLine = computeRegionEndLine(region, info.startLine);
                    if (info.name != null && !info.name.isEmpty() && info.startLine > 0)
                    {
                        regions.add(info);
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error collecting regions in ai_context", e); //$NON-NLS-1$
        }

        return regions;
    }

    /**
     * Computes the end line of a region by scanning contained EObjects.
     */
    private int computeRegionEndLine(RegionPreprocessor region, int startLine)
    {
        int endLine = startLine;
        for (var iter = region.eAllContents(); iter.hasNext();)
        {
            int childEnd = BslModuleAccess.getEndLine(iter.next());
            if (childEnd > endLine)
            {
                endLine = childEnd;
            }
        }
        return endLine > startLine ? endLine + 1 : startLine + 1;
    }

    /**
     * Collects method information from the BSL AST.
     */
    private List<MethodInfo> collectMethods(Module module, List<RegionInfo> regions)
    {
        List<MethodInfo> methods = new ArrayList<>();

        for (Method method : module.allMethods())
        {
            try
            {
                MethodInfo info = new MethodInfo();
                info.name = method.getName();
                info.isFunction = method instanceof Function;
                info.isExport = method.isExport();
                info.startLine = BslModuleAccess.getStartLine(method);
                info.endLine = BslModuleAccess.getEndLine(method);
                info.paramsString = BslModuleAccess.buildParamsString(method);
                info.executionContext = collectPragmas(method);
                info.region = findContainingRegion(info.startLine, regions);
                methods.add(info);
            }
            catch (Exception e)
            {
                Activator.logError("Error: could not read method: " + method.getName(), e); //$NON-NLS-1$
            }
        }

        return methods;
    }

    /**
     * Collects pragma annotations (&AtServer, &AtClient, etc.) for a method.
     */
    private String collectPragmas(Method method)
    {
        try
        {
            EList<Pragma> pragmas = method.getPragmas();
            if (pragmas != null && !pragmas.isEmpty())
            {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < pragmas.size(); i++)
                {
                    if (i > 0)
                    {
                        sb.append(", "); //$NON-NLS-1$
                    }
                    Pragma pragma = pragmas.get(i);
                    sb.append("&").append(pragma.getSymbol()); //$NON-NLS-1$
                }
                return sb.toString();
            }
        }
        catch (Exception e)
        {
            // Pragmas may not be available in all module types
        }
        return null;
    }

    /**
     * Finds the innermost region containing the given line.
     */
    private String findContainingRegion(int line, List<RegionInfo> regions)
    {
        String bestRegion = null;
        int bestRange = Integer.MAX_VALUE;
        for (RegionInfo region : regions)
        {
            if (line >= region.startLine && line <= region.endLine)
            {
                int range = region.endLine - region.startLine;
                if (range < bestRange)
                {
                    bestRange = range;
                    bestRegion = region.name;
                }
            }
        }
        return bestRegion;
    }

    // -- Internal data structures --

    private static class MethodInfo
    {
        String name;
        boolean isFunction;
        boolean isExport;
        int startLine;
        int endLine;
        String executionContext;
        String region;
        String paramsString;
    }

    private static class RegionInfo
    {
        String name;
        int startLine;
        int endLine;
    }
}
