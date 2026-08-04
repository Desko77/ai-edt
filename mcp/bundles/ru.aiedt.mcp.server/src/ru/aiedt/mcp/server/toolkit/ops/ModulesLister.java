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

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Lists the BSL modules of an EDT project.
 * <p>
 * With no type named, the {@code src} tree is walked and every module found is placed under the object
 * its path names. With a type named, only that type's objects are consulted - some hold a single
 * fixed module, some a folder of them. The module kind is read off the file name, with one wrinkle: a
 * form's {@code Module.bsl} is a form module only when the path says so, which is why a common form's
 * root module reads differently in the two modes.
 * </p>
 */
public class ModulesLister
    implements IMcpTool
{
    private static final String ALL = "all"; //$NON-NLS-1$

    private static final String SRC = "src"; //$NON-NLS-1$

    private static final String BSL_EXTENSION = "bsl"; //$NON-NLS-1$

    private static final String CONFIGURATION = "Configuration"; //$NON-NLS-1$

    private static final int LIMIT_MIN = 1;

    private static final int LIMIT_MAX = 1000;

    private static final int DEFAULT_LIMIT = 200;

    private static final int DEPTH_CAP = 20;

    @Override
    public String getName()
    {
        return "list_modules"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Lists the BSL modules that belong to an EDT project. An optional metadata-type filter " //$NON-NLS-1$
            + "narrows the result to one collection (documents, catalogs, commonModules, etc.), or an " //$NON-NLS-1$
            + "object name targets a single object. Each row reports the module's path, kind, and owning object."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to search (mandatory)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Narrows results to a single collection: 'all', 'documents', 'catalogs', 'commonModules', " //$NON-NLS-1$
                    + "'informationRegisters', 'accumulationRegisters', 'reports', 'dataProcessors', " //$NON-NLS-1$
                    + "'exchangePlans', 'businessProcesses', 'tasks', 'constants', 'commonCommands', " //$NON-NLS-1$
                    + "'commonForms', 'webServices', 'httpServices'. Falls back to 'all' when omitted.") //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "Restricts the listing to modules belonging to one named object (for example, 'Products')") //$NON-NLS-1$
            .stringProperty("nameFilter", "Keeps only modules whose path contains this text (case-insensitive)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Caps the number of rows returned. Defaults to 200 when omitted.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return "modules-list.md"; //$NON-NLS-1$
        }
        return "modules-" + projectName.toLowerCase(Locale.ROOT) + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: the projectName parameter must be provided"; //$NON-NLS-1$
        }

        String metadataTypeArg = JsonUtils.extractStringArgument(params, "metadataType"); //$NON-NLS-1$
        String metadataType = metadataTypeArg == null || metadataTypeArg.isEmpty() ? ALL : metadataTypeArg;
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
        String nameFilter = JsonUtils.extractStringArgument(params, "nameFilter"); //$NON-NLS-1$

        int limit = JsonUtils.extractIntArgument(params, "limit", DEFAULT_LIMIT); //$NON-NLS-1$
        limit = Math.max(LIMIT_MIN, Math.min(LIMIT_MAX, limit));

        int effectiveLimit = limit;
        try
        {
            return UiSync.call(() ->
                listModules(projectName, metadataType, objectName, nameFilter, effectiveLimit));
        }
        catch (Exception e)
        {
            return "Error: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the project and gathers its modules. Runs on the UI thread.
     *
     * @param projectName the project
     * @param metadataType the type argument, original case
     * @param objectName an object name to restrict to, or <code>null</code>/empty
     * @param nameFilter a module-path fragment to keep, or <code>null</code>/empty
     * @param limit the most rows to show
     * @return the markdown, or an {@code Error:} line
     */
    private static String listModules(String projectName, String metadataType, String objectName,
        String nameFilter, int limit)
    {
        try
        {
            IProject project = ProjectResolver.resolve(projectName);
            if (project == null)
            {
                return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$
            }

            String typeLower = metadataType.toLowerCase(Locale.ROOT);
            List<ModuleInfo> modules;
            if (ALL.equals(typeLower))
            {
                modules = scanAll(project, objectName, nameFilter);
            }
            else
            {
                Activator activator = Activator.getDefault();
                IConfigurationProvider configurationProvider =
                    activator == null ? null : activator.getConfigurationProvider();
                if (configurationProvider == null)
                {
                    return "Error: no configuration provider is available"; //$NON-NLS-1$
                }
                Configuration configuration = configurationProvider.getConfiguration(project);
                if (configuration == null)
                {
                    return "Error: unable to resolve the configuration for project: " + projectName; //$NON-NLS-1$
                }
                SpecificType type = resolveSpecific(typeLower);
                if (type == null)
                {
                    return unknownTypeError(metadataType);
                }
                modules = collectSpecific(project, configuration, type, objectName, nameFilter);
            }
            return formatOutput(modules, projectName, metadataType, limit);
        }
        catch (CoreException e)
        {
            return "Error: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Walks the whole {@code src} tree and places every module under the object its path names.
     *
     * @param project the project
     * @param objectName an object name to restrict to, or <code>null</code>/empty
     * @param nameFilter a module-path fragment to keep, or <code>null</code>/empty
     * @return the modules, in filesystem traversal order
     * @throws CoreException if the tree cannot be walked
     */
    private static List<ModuleInfo> scanAll(IProject project, String objectName, String nameFilter)
        throws CoreException
    {
        List<ModuleInfo> result = new ArrayList<>();
        IFolder sourceFolder = project.getFolder(SRC);
        if (!sourceFolder.exists())
        {
            return result;
        }
        List<IFile> files = new ArrayList<>();
        collectBslFiles(sourceFolder, 0, files);

        for (IFile file : files)
        {
            IPath relative = file.getProjectRelativePath().removeFirstSegments(1);
            if (relative.segmentCount() < 2)
            {
                continue;
            }
            String modulePath = relative.toString();
            String first = relative.segment(0);
            String parentName;
            String parentType;
            if (CONFIGURATION.equals(first))
            {
                parentName = CONFIGURATION;
                parentType = CONFIGURATION;
            }
            else
            {
                parentName = relative.segment(1);
                String mapped = MetadataTypeCatalog.getTypeByDirectoryName(first);
                parentType = mapped != null ? mapped : first;
            }

            if (objectName != null && !objectName.isEmpty() && !parentName.equalsIgnoreCase(objectName))
            {
                continue;
            }
            if (!matchesNameFilter(modulePath, nameFilter))
            {
                continue;
            }

            String basePath = relative.segment(0) + "/" + relative.segment(1); //$NON-NLS-1$
            result.add(new ModuleInfo(modulePath, determineModuleType(modulePath, basePath), parentType,
                parentName));
        }
        return result;
    }

    /**
     * Gathers the modules of one metadata type from its objects.
     *
     * @param project the project
     * @param configuration the configuration
     * @param type the type descriptor
     * @param objectName an object name to restrict to, or <code>null</code>/empty
     * @param nameFilter a module-path fragment to keep, or <code>null</code>/empty
     * @return the modules
     * @throws CoreException if a folder cannot be walked
     */
    private static List<ModuleInfo> collectSpecific(IProject project, Configuration configuration,
        SpecificType type, String objectName, String nameFilter) throws CoreException
    {
        List<ModuleInfo> result = new ArrayList<>();
        String folder = MetadataTypeCatalog.getDirectoryName(type.typeName);
        if (folder == null)
        {
            return result;
        }
        List<? extends MdObject> objects = MetadataTypeCatalog.getObjects(configuration, type.typeName);
        if (objects == null)
        {
            return result;
        }

        for (MdObject object : objects)
        {
            String name = object.getName();
            if (name == null)
            {
                continue;
            }
            if (objectName != null && !objectName.isEmpty() && !name.equalsIgnoreCase(objectName))
            {
                continue;
            }

            if (type.singleFile != null)
            {
                String modulePath = folder + "/" + name + "/" + type.singleFile; //$NON-NLS-1$ //$NON-NLS-2$
                if (fileExists(project, modulePath) && matchesNameFilter(modulePath, nameFilter))
                {
                    result.add(new ModuleInfo(modulePath, type.singleModuleType, type.typeName, name));
                }
                continue;
            }

            IFolder objectFolder = project.getFolder(new Path(SRC).append(folder).append(name));
            if (!objectFolder.exists())
            {
                continue;
            }
            List<IFile> files = new ArrayList<>();
            collectBslFiles(objectFolder, 0, files);
            String basePath = folder + "/" + name; //$NON-NLS-1$
            for (IFile file : files)
            {
                String modulePath = file.getProjectRelativePath().removeFirstSegments(1).toString();
                if (!matchesNameFilter(modulePath, nameFilter))
                {
                    continue;
                }
                result.add(new ModuleInfo(modulePath, determineModuleType(modulePath, basePath),
                    type.typeName, name));
            }
        }
        return result;
    }

    /**
     * Collects every {@code .bsl} file under a container, recursively and to a depth cap.
     *
     * @param container the folder to walk
     * @param depth the current depth
     * @param out the accumulator
     * @throws CoreException if a member cannot be read
     */
    private static void collectBslFiles(IContainer container, int depth, List<IFile> out)
        throws CoreException
    {
        if (depth > DEPTH_CAP)
        {
            return;
        }
        for (IResource member : container.members())
        {
            if (member instanceof IFile)
            {
                String extension = member.getFileExtension();
                if (extension != null && extension.equalsIgnoreCase(BSL_EXTENSION))
                {
                    out.add((IFile)member);
                }
            }
            else if (member instanceof IContainer)
            {
                collectBslFiles((IContainer)member, depth + 1, out);
            }
        }
    }

    /**
     * Works out a module's kind from its file name, treating a {@code Module.bsl} under a
     * {@code Forms/} path as a form module.
     *
     * @param modulePath the {@code src}-relative module path
     * @param basePath the object's folder, against which {@code Forms/} is measured
     * @return the module kind
     */
    private static String determineModuleType(String modulePath, String basePath)
    {
        String relative = modulePath;
        if (basePath != null && modulePath.startsWith(basePath))
        {
            relative = modulePath.substring(basePath.length());
        }
        while (relative.startsWith("/")) //$NON-NLS-1$
        {
            relative = relative.substring(1);
        }

        String fileName = modulePath;
        int slash = modulePath.lastIndexOf('/');
        if (slash >= 0)
        {
            fileName = modulePath.substring(slash + 1);
        }
        String baseName = fileName;
        if (fileName.length() >= 4 && fileName.regionMatches(true, fileName.length() - 4, ".bsl", 0, 4)) //$NON-NLS-1$
        {
            baseName = fileName.substring(0, fileName.length() - 4);
        }

        if ("Module".equals(baseName)) //$NON-NLS-1$
        {
            return relative.startsWith("Forms/") ? "FormModule" : "Module"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return baseName;
    }

    /**
     * @param project the project
     * @param modulePath the {@code src}-relative module path
     * @return whether the file exists
     */
    private static boolean fileExists(IProject project, String modulePath)
    {
        return project.getFile(new Path(SRC).append(modulePath)).exists();
    }

    /**
     * @param modulePath the module path
     * @param nameFilter the fragment to require, or <code>null</code>/empty for any
     * @return whether the path passes the filter
     */
    private static boolean matchesNameFilter(String modulePath, String nameFilter)
    {
        if (nameFilter == null || nameFilter.isEmpty())
        {
            return true;
        }
        return modulePath.toLowerCase(Locale.ROOT).contains(nameFilter.toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves a specific-type token to its descriptor.
     *
     * @param typeLower the lowercased type token
     * @return the descriptor, or <code>null</code> when the token is not a supported specific type
     */
    private static SpecificType resolveSpecific(String typeLower)
    {
        switch (typeLower)
        {
        case "commonmodules": //$NON-NLS-1$
            return new SpecificType("CommonModule", "Module.bsl", "Module"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "commoncommands": //$NON-NLS-1$
            return new SpecificType("CommonCommand", "CommandModule.bsl", "CommandModule"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "commonforms": //$NON-NLS-1$
            return new SpecificType("CommonForm", "Module.bsl", "FormModule"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "webservices": //$NON-NLS-1$
            return new SpecificType("WebService", "Module.bsl", "Module"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "httpservices": //$NON-NLS-1$
            return new SpecificType("HTTPService", "Module.bsl", "Module"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        case "documents": //$NON-NLS-1$
            return new SpecificType("Document", null, null); //$NON-NLS-1$
        case "catalogs": //$NON-NLS-1$
            return new SpecificType("Catalog", null, null); //$NON-NLS-1$
        case "informationregisters": //$NON-NLS-1$
            return new SpecificType("InformationRegister", null, null); //$NON-NLS-1$
        case "accumulationregisters": //$NON-NLS-1$
            return new SpecificType("AccumulationRegister", null, null); //$NON-NLS-1$
        case "reports": //$NON-NLS-1$
            return new SpecificType("Report", null, null); //$NON-NLS-1$
        case "dataprocessors": //$NON-NLS-1$
            return new SpecificType("DataProcessor", null, null); //$NON-NLS-1$
        case "exchangeplans": //$NON-NLS-1$
            return new SpecificType("ExchangePlan", null, null); //$NON-NLS-1$
        case "businessprocesses": //$NON-NLS-1$
            return new SpecificType("BusinessProcess", null, null); //$NON-NLS-1$
        case "tasks": //$NON-NLS-1$
            return new SpecificType("Task", null, null); //$NON-NLS-1$
        case "constants": //$NON-NLS-1$
            return new SpecificType("Constant", null, null); //$NON-NLS-1$
        default:
            return null;
        }
    }

    /**
     * Renders the modules.
     *
     * @param modules the modules
     * @param projectName the project, for the heading
     * @param metadataType the type argument, for the filter line
     * @param limit the most rows to show
     * @return the markdown
     */
    private static String formatOutput(List<ModuleInfo> modules, String projectName, String metadataType,
        int limit)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("## BSL Module Inventory: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        int total = modules.size();
        int shown = Math.min(total, limit);

        if (!metadataType.equalsIgnoreCase(ALL))
        {
            builder.append("**Applied filter:** ").append(metadataType).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        builder.append("**Module count:** ").append(total).append(" modules"); //$NON-NLS-1$ //$NON-NLS-2$
        if (shown < total)
        {
            builder.append(" (displaying ").append(shown).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        builder.append("\n\n"); //$NON-NLS-1$

        if (modules.isEmpty())
        {
            builder.append("No matching modules were found.\n"); //$NON-NLS-1$
            return builder.toString();
        }

        builder.append("| Path | Kind | Owner Type | Owner Name |\n"); //$NON-NLS-1$
        builder.append("|-------------|-------------|-------------|-------------|\n"); //$NON-NLS-1$
        for (int i = 0; i < shown; i++)
        {
            ModuleInfo module = modules.get(i);
            builder.append("| ").append(module.modulePath) //$NON-NLS-1$
                .append(" | ").append(module.moduleType) //$NON-NLS-1$
                .append(" | ").append(module.parentType) //$NON-NLS-1$
                .append(" | ").append(module.parentName) //$NON-NLS-1$
                .append(" |\n"); //$NON-NLS-1$
        }
        return builder.toString();
    }

    /**
     * Builds the message for an unrecognized type token.
     *
     * @param metadataType the type argument, original case
     * @return the error line
     */
    private static String unknownTypeError(String metadataType)
    {
        return "Error: unrecognized metadata type: " + metadataType + ". Accepted values: all, documents, catalogs, " //$NON-NLS-1$ //$NON-NLS-2$
            + "commonModules, informationRegisters, accumulationRegisters, reports, dataProcessors, " //$NON-NLS-1$
            + "exchangePlans, businessProcesses, tasks, constants, commonCommands, commonForms, " //$NON-NLS-1$
            + "webServices, httpServices"; //$NON-NLS-1$
    }

    /** One module, flattened to the four columns the table shows. */
    private static final class ModuleInfo
    {
        private final String modulePath;

        private final String moduleType;

        private final String parentType;

        private final String parentName;

        ModuleInfo(String modulePath, String moduleType, String parentType, String parentName)
        {
            this.modulePath = modulePath;
            this.moduleType = moduleType;
            this.parentType = parentType;
            this.parentName = parentName;
        }
    }

    /**
     * A specific metadata type to list. A non-null {@code singleFile} marks a type whose objects hold
     * one fixed module; a null one marks a type whose objects hold a folder of modules.
     */
    private static final class SpecificType
    {
        private final String typeName;

        private final String singleFile;

        private final String singleModuleType;

        SpecificType(String typeName, String singleFile, String singleModuleType)
        {
            this.typeName = typeName;
            this.singleFile = singleFile;
            this.singleModuleType = singleModuleType;
        }
    }
}
