/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmExternalObjectProjectHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Creates External Data Processor (.epf) and External Report (.erf) DT projects so
 * agents build them entirely through tools instead of hand-writing .mdo / Form.form.
 * <p>
 * {@code edit_metadata create_object} cannot make these - they are standalone DT
 * projects, not configuration objects. This tool wraps EDT's
 * {@code IExternalObjectProjectManager.create}. Once the project exists, populate
 * the root object (FQN {@code ExternalDataProcessor.<name>} / {@code
 * ExternalReport.<name>}) with the usual {@code edit_metadata} / {@code edit_form}
 * operations and build the binary with {@code export_object}.
 */
public class ExternalObjectWorkshopTool implements IMcpTool
{
    public static final String NAME = "external_object_workshop"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create External Data Processor / External Report DT projects (which edit_metadata " //$NON-NLS-1$
            + "create_object cannot - they are standalone DT projects, not configuration objects). " //$NON-NLS-1$
            + "operation=create (kind=ExternalDataProcessor|ExternalReport, name, parentProjectName?): " //$NON-NLS-1$
            + "scaffolds the DT project and its root object via EDT's external-object project manager. " //$NON-NLS-1$
            + "After create, fill the root object (FQN ExternalDataProcessor.<name> / ExternalReport.<name>) " //$NON-NLS-1$
            + "with edit_metadata (add_object_attribute / add_tabular_section / add_template / create_form) " //$NON-NLS-1$
            + "and edit_form, write its ObjectModule with write_module_source, then build the .epf/.erf with " //$NON-NLS-1$
            + "export_object. No manual file editing needed."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", "create | import_external_object | help (default: create)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("kind", "ExternalDataProcessor | ExternalReport (required for create; Russian " //$NON-NLS-1$ //$NON-NLS-2$
                + "aliases ВнешняяОбработка / ВнешнийОтчет accepted)") //$NON-NLS-1$
            .stringProperty("name", "New external object and project name (required for create)") //$NON-NLS-1$
            .stringProperty("parentProjectName", "Optional parent 1C configuration project - supplies the " //$NON-NLS-1$ //$NON-NLS-2$
                + "platform version and type-resolution context. Omit for a standalone object (the platform " //$NON-NLS-1$
                + "version is then taken from any open 1C project in the workspace).") //$NON-NLS-1$
            .stringProperty("targetProjectName", //$NON-NLS-1$
                "import_external_object: an EXISTING external-object container project " //$NON-NLS-1$
                    + "(V8ExternalObjectsNature, e.g. a shared 'ВнешниеОбработкиОтчеты') to import the " //$NON-NLS-1$
                    + "binary INTO. The object is added as another item of that container; types resolve " //$NON-NLS-1$
                    + "through the container's parent configuration (no markers). Required for import.") //$NON-NLS-1$
            .stringProperty("inputPath", //$NON-NLS-1$
                "import_external_object: absolute path of the .epf / .erf binary to import. Required " //$NON-NLS-1$
                    + "for import.") //$NON-NLS-1$
            .stringProperty("baseProjectName", //$NON-NLS-1$
                "import_external_object: the configuration project the imported object belongs to. " //$NON-NLS-1$
                    + "A container does not carry that link - the IDE's own import wizard asks for " //$NON-NLS-1$
                    + "it - so name it here when the container has no parent project. Omitted, the " //$NON-NLS-1$
                    + "container's parent is used, and the import refuses when there is none.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String operation = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (operation == null || operation.isEmpty())
        {
            operation = "create"; //$NON-NLS-1$
        }
        switch (operation)
        {
            case "create": //$NON-NLS-1$
                return doCreate(params);
            case "import_external_object": //$NON-NLS-1$
                return doImport(params);
            case "help": //$NON-NLS-1$
                return ToolResult.success().put("message", //$NON-NLS-1$
                    "# external_object_workshop\n\n" //$NON-NLS-1$
                    + "operation=create - scaffold an ExternalDataProcessor / ExternalReport DT project " //$NON-NLS-1$
                    + "(kind=ExternalDataProcessor|ExternalReport, name, optional parentProjectName). " //$NON-NLS-1$
                    + "After create, fill the root object (ExternalDataProcessor.<name> / " //$NON-NLS-1$
                    + "ExternalReport.<name>) via edit_metadata (add_object_attribute / " //$NON-NLS-1$
                    + "add_tabular_section / add_template / create_form), write its ObjectModule via " //$NON-NLS-1$
                    + "write_module_source, then build the .epf / .erf with export_object.\n" //$NON-NLS-1$
                    + "operation=import_external_object - import a .erf/.epf binary INTO an existing " //$NON-NLS-1$
                    + "V8ExternalObjectsNature container (targetProjectName, inputPath). Adds the object " //$NON-NLS-1$
                    + "to the container; types resolve via the container's parent config (no markers). " //$NON-NLS-1$
                    + "Mirrors EDT GUI Import.") //$NON-NLS-1$
                    .toJson();
            default:
                return ToolResult.error("Unknown operation '" + operation + "'. Valid: create, " //$NON-NLS-1$ //$NON-NLS-2$
                    + "import_external_object, help.").toJson(); //$NON-NLS-1$
        }
    }

    private String doImport(Map<String, String> params)
    {
        String targetProjectName = JsonUtils.extractStringArgument(params, "targetProjectName"); //$NON-NLS-1$
        String inputPath = JsonUtils.extractStringArgument(params, "inputPath"); //$NON-NLS-1$
        if (targetProjectName == null || targetProjectName.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("targetProjectName", //$NON-NLS-1$
                "external_object_workshop operation=import_external_object " //$NON-NLS-1$
                + "targetProjectName=<container> inputPath=<.erf|.epf>")).toJson(); //$NON-NLS-1$
        }
        if (inputPath == null || inputPath.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("inputPath", //$NON-NLS-1$
                "external_object_workshop operation=import_external_object " //$NON-NLS-1$
                + "targetProjectName=" + targetProjectName + " inputPath=<.erf|.epf>")).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        BmExternalObjectProjectHelper.ImportResult res =
            BmExternalObjectProjectHelper.importExternalObject(targetProjectName, inputPath,
                JsonUtils.extractStringArgument(params, "baseProjectName")); //$NON-NLS-1$
        if (!res.ok)
        {
            String message = res.error != null ? res.error : "import external object failed"; //$NON-NLS-1$
            if (res.hint != null)
            {
                message = message + " " + res.hint; //$NON-NLS-1$
            }
            ToolResult err = ToolResult.error(message);
            if (res.failureKind != null)
            {
                err.put(res.failureKind, Boolean.TRUE);
            }
            if (res.leftoverXmlDir != null)
            {
                // Kept on purpose; without the path the caller can neither look at it nor clean
                // it up, and every such failure leaves a directory nobody can find.
                err.put("leftoverXmlDir", res.leftoverXmlDir); //$NON-NLS-1$
            }
            return err.toJson();
        }
        // Name what arrived rather than asserting that something did: the caller
        // gets the object it can now address, not a promise to go and look.
        return ToolResult.success()
            .put("operation", "import_external_object") //$NON-NLS-1$ //$NON-NLS-2$
            .put("targetProject", targetProjectName) //$NON-NLS-1$
            .put("inputPath", inputPath) //$NON-NLS-1$
            .put("importedObject", res.importedObjectFqn) //$NON-NLS-1$
            .put("message", "Imported " + res.importedObjectFqn + " into '" + targetProjectName //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Run clean_project if the editor still shows the old contents, and rebuild " //$NON-NLS-1$
                + "binaries with export_object.") //$NON-NLS-1$
            .toJson();
    }

    private String doCreate(Map<String, String> params)
    {
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String kind = JsonUtils.extractStringArgument(params, "kind"); //$NON-NLS-1$
        String parentProjectName = JsonUtils.extractStringArgument(params, "parentProjectName"); //$NON-NLS-1$
        if (name == null || name.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("name", //$NON-NLS-1$
                "external_object_workshop operation=create kind=ExternalDataProcessor name=MyTool")).toJson(); //$NON-NLS-1$
        }
        if (kind == null || kind.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("kind", //$NON-NLS-1$
                "external_object_workshop operation=create kind=ExternalDataProcessor name=" + name)).toJson(); //$NON-NLS-1$
        }
        IProject parent = null;
        if (parentProjectName != null && !parentProjectName.isEmpty())
        {
            parent = ProjectResolver.resolve(parentProjectName);
            if (parent == null)
            {
                IProject exact = ResourcesPlugin.getWorkspace().getRoot().getProject(parentProjectName);
                if (exact.exists())
                {
                    parent = exact;
                }
            }
            if (parent == null || !parent.isAccessible())
            {
                return ToolResult.error(ProjectResolver.describeNotFound(parentProjectName)
                    + " (the parent project must be open).").toJson(); //$NON-NLS-1$
            }
        }

        BmExternalObjectProjectHelper.CreateResult res =
            BmExternalObjectProjectHelper.createExternalObjectProject(name, kind, parent);
        if (!res.ok)
        {
            String message = res.error != null ? res.error : "create external object project failed"; //$NON-NLS-1$
            if (res.hint != null)
            {
                message = message + " " + res.hint; //$NON-NLS-1$
            }
            return ToolResult.error(message).toJson();
        }
        return ToolResult.success()
            .put("operation", "create") //$NON-NLS-1$ //$NON-NLS-2$
            .put("createdProjectName", res.createdProjectName) //$NON-NLS-1$
            .put("kind", res.kind) //$NON-NLS-1$
            .put("rootFqn", res.rootFqn) //$NON-NLS-1$
            .put(ErrorTags.ALREADY_EXISTS.wire(), res.alreadyExists)
            .put("nextSteps", "Fill the root object via edit_metadata with ownerFqn=" + res.rootFqn //$NON-NLS-1$ //$NON-NLS-2$
                + " (add_object_attribute / add_tabular_section / add_template / create_form), edit_form for " //$NON-NLS-1$
                + "form layout, write_module_source for the ObjectModule, then export_object to build the " //$NON-NLS-1$
                + (res.kind != null && res.kind.contains("Report") ? ".erf." : ".epf.")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .put("note", res.hint != null ? res.hint //$NON-NLS-1$
                : "Project created. Run clean_project if EDT does not show it immediately.") //$NON-NLS-1$
            .toJson();
    }
}
