/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmDefinedTypeHelper;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.MetadataGuards;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * HTTP and Web (SOAP) service operations extracted from {@link EditMetadataTool} as the seventh cluster
 * of the god-class split (Inc4). Composite service creation (create_http_service,
 * create_web_service) builds the root object + URL template / operation + handler stub end-to-end via a
 * direct {@link AbstractBmTask}; the incremental ops (add/remove url_template, url_template_method,
 * web_service_operation, operation_parameter) go through {@link BmObjectHelper#executeWriteOnObject}.
 * <p>
 * The cluster ships its own reflection helpers ({@link #applyHttpMethodValue},
 * {@link #applyOptionalBoolean}, {@link #applyOptionalInteger}, XDTO {@link #parseQNameArg} /
 * {@link #resolveTransferDirection}), the {@link #VALID_HTTP_METHODS} vocabulary, and the BSL
 * module-stub builders / writers for HTTP and Web services - all used nowhere else. The generic
 * {@code applyOptionalString} and the validation/format helpers ({@code requireNonEmpty},
 * {@code formatResult}, {@code findSingleArgSetter}) live on {@link EditMetadataTool} and are called
 * statically from here.
 */
final class ServiceOps
{
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema"; //$NON-NLS-1$

    String opAddUrlTemplate(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        // urlTemplate is the canonical param (shared with create_http_service);
        // template is the legacy alias for older call sites. Accept both, with
        // urlTemplate taking precedence when both are supplied.
        String templateArg = JsonUtils.extractStringArgument(params, "urlTemplate"); //$NON-NLS-1$
        if (templateArg == null || templateArg.isEmpty())
        {
            templateArg = JsonUtils.extractStringArgument(params, "template"); //$NON-NLS-1$
        }
        // Effectively final for capture inside the BM write lambda below.
        final String template = templateArg;
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(template, "template (or urlTemplate)"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                String ecName = owner.eClass().getName();
                // Cover both EDT camelCase variants: HTTPService and HttpService.
                if (!"HTTPService".equals(ecName) && !"HttpService".equals(ecName)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    throw new RuntimeException("Unsupported owner type '" + ecName //$NON-NLS-1$
                        + "' is not an HTTPService. add_url_template only applies to " //$NON-NLS-1$
                        + "HTTP service objects."); //$NON-NLS-1$
                }
                @SuppressWarnings("unchecked")
                EList<MdObject> templates = (EList<MdObject>) owner.getClass()
                    .getMethod("getUrlTemplates").invoke(owner); //$NON-NLS-1$
                if (templates == null)
                {
                    throw new RuntimeException("HTTPService.getUrlTemplates() returned null"); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(templates, name) != null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("name", name); //$NON-NLS-1$
                    data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
                    data.put("kind", "urlTemplate"); //$NON-NLS-1$ //$NON-NLS-2$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "URL template already exists: " + name, //$NON-NLS-1$
                        "Use a different name or addUrlTemplateMethod to extend the existing template.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.ALREADY_EXISTS.wire(), data)));
                }
                // EDT 2026.1: HTTPService.UrlTemplate is just URLTemplate (no
                // owner-prefixed factory method exists).
                MdObject newTemplate = BmObjectHelper.createGenericObject("URLTemplate"); //$NON-NLS-1$
                if (newTemplate == null)
                {
                    throw new RuntimeException("Cannot create URL template: " //$NON-NLS-1$
                        + "MdClassFactory.createURLTemplate() and MdClassPackage " //$NON-NLS-1$
                        + "lookup both unavailable on this EDT runtime."); //$NON-NLS-1$
                }
                newTemplate.setName(name);
                EditMetadataTool.applyOptionalString(newTemplate, "setTemplate", template); //$NON-NLS-1$
                templates.add(newTemplate);
                return name + " (" + template + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            });
        return EditMetadataTool.formatResult(r, "add_url_template"); //$NON-NLS-1$
    }

    String opAddUrlTemplateMethod(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String httpMethod = JsonUtils.extractStringArgument(params, "httpMethod"); //$NON-NLS-1$
        String handlerParam = JsonUtils.extractStringArgument(params, "handler"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        boolean withHandlerStub = JsonUtils.extractBooleanArgument(params, "withHandlerStub", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(templateName, "templateName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        if (httpMethod == null || httpMethod.isEmpty())
        {
            httpMethod = "GET"; //$NON-NLS-1$
        }
        if (!VALID_HTTP_METHODS.contains(httpMethod))
        {
            return ToolResult.error("Invalid httpMethod '" + httpMethod //$NON-NLS-1$
                + "'. Allowed (case-sensitive): " //$NON-NLS-1$
                + String.join(", ", VALID_HTTP_METHODS)).toJson(); //$NON-NLS-1$
        }
        final String resolvedHandler = (handlerParam != null && !handlerParam.isEmpty())
            ? handlerParam
            : templateName + name;
        final String httpMethodFinal = httpMethod;
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                String ecName2 = owner.eClass().getName();
                if (!"HTTPService".equals(ecName2) && !"HttpService".equals(ecName2)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    throw new RuntimeException("Unsupported owner type '" + ecName2 //$NON-NLS-1$
                        + "' is not an HTTPService."); //$NON-NLS-1$
                }
                @SuppressWarnings("unchecked")
                EList<MdObject> templates = (EList<MdObject>) owner.getClass()
                    .getMethod("getUrlTemplates").invoke(owner); //$NON-NLS-1$
                MdObject template = BmObjectHelper.findByName(templates, templateName);
                if (template == null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("templateName", templateName); //$NON-NLS-1$
                    data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "URL template not found: " + templateName, //$NON-NLS-1$
                        "Add the template via add_url_template first.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.NOT_FOUND.wire(), data)));
                }
                @SuppressWarnings("unchecked")
                EList<MdObject> methods = (EList<MdObject>) template.getClass()
                    .getMethod("getMethods").invoke(template); //$NON-NLS-1$
                if (BmObjectHelper.findByName(methods, name) != null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("name", name); //$NON-NLS-1$
                    data.put("templateName", templateName); //$NON-NLS-1$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "URL template method already exists: " + name, //$NON-NLS-1$
                        "Pick a different name or remove the existing method first.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.ALREADY_EXISTS.wire(), data)));
                }
                // EDT 2026.1: HTTPService URLTemplate.Method is plain Method
                // (createMethod()), no HTTPService-prefixed factory method.
                MdObject method = BmObjectHelper.createGenericObject("Method"); //$NON-NLS-1$
                if (method == null)
                {
                    throw new RuntimeException("Cannot create HTTP service method: " //$NON-NLS-1$
                        + "MdClassFactory.createMethod() and MdClassPackage " //$NON-NLS-1$
                        + "lookup both unavailable on this EDT runtime."); //$NON-NLS-1$
                }
                method.setName(name);
                EditMetadataTool.applyOptionalString(method, "setHandler", resolvedHandler); //$NON-NLS-1$
                applyHttpMethodValue(method, httpMethodFinal);
                methods.add(method);
                return name + " (" + httpMethodFinal + " -> " + resolvedHandler + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            });
        if (r.ok)
        {
            r.tags.put("handler", resolvedHandler); //$NON-NLS-1$
            r.tags.put("httpMethod", httpMethodFinal); //$NON-NLS-1$
            if (withHandlerStub && !dryRun)
            {
                String serviceName = ownerFqn.startsWith("HTTPService.") //$NON-NLS-1$
                    ? ownerFqn.substring("HTTPService.".length()) : null; //$NON-NLS-1$
                if (serviceName != null && !serviceName.isEmpty())
                {
                    try
                    {
                        String stubPath = appendHandlerStubIfMissing(
                            project, serviceName, resolvedHandler);
                        Map<String, Object> stubInfo = new LinkedHashMap<>();
                        stubInfo.put("module", stubPath); //$NON-NLS-1$
                        stubInfo.put("handler", resolvedHandler); //$NON-NLS-1$
                        r.tags.put("handlerStub", stubInfo); //$NON-NLS-1$
                    }
                    catch (Exception stubEx)
                    {
                        Map<String, Object> stubErr = new LinkedHashMap<>();
                        stubErr.put("error", stubEx.getMessage()); //$NON-NLS-1$
                        stubErr.put("hint", //$NON-NLS-1$
                            "Insert manually via write_module_source. Standard signature: " //$NON-NLS-1$
                                + "Функция " + resolvedHandler //$NON-NLS-1$
                                + "(Запрос) Возврат Новый HTTPСервисОтвет(200); КонецФункции"); //$NON-NLS-1$
                        r.tags.put("handlerStubFailed", stubErr); //$NON-NLS-1$
                    }
                }
            }
            else
            {
                r.tags.put("hint", //$NON-NLS-1$
                    "Insert the handler procedure into the HTTP service module via " //$NON-NLS-1$
                        + "write_module_source, or re-run with withHandlerStub=true. " //$NON-NLS-1$
                        + "Standard signature: " //$NON-NLS-1$
                        + "Функция " + resolvedHandler + "(Запрос) Возврат Новый HTTPСервисОтвет(200); КонецФункции"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return EditMetadataTool.formatResult(r, "add_url_template_method"); //$NON-NLS-1$
    }

    String opRemoveUrlTemplate(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                String ecName = owner.eClass().getName();
                if (!"HTTPService".equals(ecName) && !"HttpService".equals(ecName)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    throw new RuntimeException("Unsupported owner type '" + ecName //$NON-NLS-1$
                        + "' is not an HTTPService."); //$NON-NLS-1$
                }
                @SuppressWarnings("unchecked")
                EList<MdObject> templates = (EList<MdObject>) owner.getClass()
                    .getMethod("getUrlTemplates").invoke(owner); //$NON-NLS-1$
                MdObject target = BmObjectHelper.findByName(templates, name);
                if (target == null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("name", name); //$NON-NLS-1$
                    data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "URL template not found: " + name, //$NON-NLS-1$
                        "Check the template name. Use add_url_template to create one.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.NOT_FOUND.wire(), data)));
                }
                templates.remove(target);
                return name;
            });
        return EditMetadataTool.formatResult(r, "remove_url_template"); //$NON-NLS-1$
    }

    String opRemoveUrlTemplateMethod(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String templateName = JsonUtils.extractStringArgument(params, "templateName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(templateName, "templateName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                String ecName = owner.eClass().getName();
                if (!"HTTPService".equals(ecName) && !"HttpService".equals(ecName)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    throw new RuntimeException("Unsupported owner type '" + ecName //$NON-NLS-1$
                        + "' is not an HTTPService."); //$NON-NLS-1$
                }
                @SuppressWarnings("unchecked")
                EList<MdObject> templates = (EList<MdObject>) owner.getClass()
                    .getMethod("getUrlTemplates").invoke(owner); //$NON-NLS-1$
                MdObject template = BmObjectHelper.findByName(templates, templateName);
                if (template == null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("templateName", templateName); //$NON-NLS-1$
                    data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "URL template not found: " + templateName, //$NON-NLS-1$
                        "Check templateName.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.NOT_FOUND.wire(), data)));
                }
                @SuppressWarnings("unchecked")
                EList<MdObject> methods = (EList<MdObject>) template.getClass()
                    .getMethod("getMethods").invoke(template); //$NON-NLS-1$
                MdObject target = BmObjectHelper.findByName(methods, name);
                if (target == null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("name", name); //$NON-NLS-1$
                    data.put("templateName", templateName); //$NON-NLS-1$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "URL template method not found: " + name, //$NON-NLS-1$
                        "Check the method name.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.NOT_FOUND.wire(), data)));
                }
                methods.remove(target);
                return name;
            });
        return EditMetadataTool.formatResult(r, "remove_url_template_method"); //$NON-NLS-1$
    }

    String opCreateWebService(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String namespace = JsonUtils.extractStringArgument(params, "namespace"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(namespace, "namespace"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()
                + " (namespace is a valid XML URI like http://example.com/<service>)").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        Configuration config = configProvider != null ? configProvider.getConfiguration(project) : null;
        if (config == null)
        {
            return ToolResult.error("Configuration not available for project: " + projectName) //$NON-NLS-1$
                .toJson();
        }

        String operationName = JsonUtils.extractStringArgument(params, "operationName"); //$NON-NLS-1$
        if (operationName == null || operationName.isEmpty())
        {
            operationName = "Default"; //$NON-NLS-1$
        }
        String handlerParam = JsonUtils.extractStringArgument(params, "handler"); //$NON-NLS-1$
        final String handler = (handlerParam != null && !handlerParam.isEmpty())
            ? handlerParam : operationName;
        Boolean transactional = JsonUtils.extractBooleanArgumentNullable(params, "transactional"); //$NON-NLS-1$
        boolean createModule = JsonUtils.extractBooleanArgument(params, "createModule", true); //$NON-NLS-1$
        boolean withHandlerStub = JsonUtils.extractBooleanArgument(params, "withHandlerStub", true); //$NON-NLS-1$

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        IBmModel bmModel = bmModelManager != null ? bmModelManager.getModel(project) : null;
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available").toJson(); //$NON-NLS-1$
        }

        final String fNamespace = namespace;
        final String fOperationName = operationName;
        final Boolean fTransactional = transactional;

        StringBuilder finalErr = new StringBuilder();
        try
        {
            bmModel.getGlobalContext().execute(
                (IBmTask) new AbstractBmTask<Void>("edit_metadata.createWebService") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    MdObject service = BmObjectHelper.createGenericObject("WebService"); //$NON-NLS-1$
                    if (service == null)
                    {
                        finalErr.append("Cannot create WebService - " //$NON-NLS-1$
                            + "MdClassFactory.createWebService() and MdClassPackage " //$NON-NLS-1$
                            + "lookup both unavailable on this EDT runtime."); //$NON-NLS-1$
                        return null;
                    }
                    service.setName(name);
                    EditMetadataTool.applyMdObjectSynonym(service, null, name, project);
                    EditMetadataTool.applyOptionalString(service, "setNamespace", fNamespace); //$NON-NLS-1$
                    // Some EDT builds expose the property as URL instead of Namespace.
                    EditMetadataTool.applyOptionalString(service, "setURL", fNamespace); //$NON-NLS-1$
                    if (!BmObjectHelper.addToConfiguration(config, service))
                    {
                        finalErr.append("Created WebService but failed to attach it to the " //$NON-NLS-1$
                            + "configuration. Configuration may not expose the webServices " //$NON-NLS-1$
                            + "collection on this EDT runtime."); //$NON-NLS-1$
                        return null;
                    }
                    // Operation
                    try
                    {
                        @SuppressWarnings("unchecked")
                        EList<MdObject> ops = (EList<MdObject>) service.getClass()
                            .getMethod("getOperations").invoke(service); //$NON-NLS-1$
                        MdObject op = BmObjectHelper.createGenericObject("WebServiceOperation"); //$NON-NLS-1$
                        if (op == null)
                        {
                            // Older EDT may use shorter type name; fall back to it.
                            op = BmObjectHelper.createGenericObject("Operation"); //$NON-NLS-1$
                        }
                        if (op == null)
                        {
                            finalErr.append("Cannot create WebServiceOperation - factory unavailable."); //$NON-NLS-1$
                            return null;
                        }
                        op.setName(fOperationName);
                        EditMetadataTool.applyOptionalString(op, "setProcedureName", handler); //$NON-NLS-1$
                        if (fTransactional != null)
                        {
                            applyOptionalBoolean(op, "setTransactional", fTransactional); //$NON-NLS-1$
                        }
                        ops.add(op);
                    }
                    catch (Exception nestedEx)
                    {
                        finalErr.append("Failed to attach Operation to WebService: " //$NON-NLS-1$
                            + nestedEx.getMessage());
                        return null;
                    }
                    String fqn = "WebService." + name; //$NON-NLS-1$
                    tx.attachTopObject((IBmObject) service, fqn);
                    if (dryRun)
                    {
                        throw new RuntimeException("__DRY_RUN__"); //$NON-NLS-1$
                    }
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            String causeMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            if (!"__DRY_RUN__".equals(causeMsg)) //$NON-NLS-1$
            {
                return ToolResult.error("create_web_service failed: " + causeMsg).toJson(); //$NON-NLS-1$
            }
        }
        if (finalErr.length() > 0)
        {
            return ToolResult.error(finalErr.toString()).toJson();
        }

        Map<String, Object> moduleInfo = new LinkedHashMap<>();
        if (!dryRun && createModule)
        {
            try
            {
                String moduleBody = withHandlerStub
                    ? buildWebServiceModuleStub(handler)
                    : ""; //$NON-NLS-1$
                String modulePath = writeWebServiceModule(project, name, moduleBody);
                moduleInfo.put("path", modulePath); //$NON-NLS-1$
                moduleInfo.put("handlerStub", withHandlerStub); //$NON-NLS-1$
            }
            catch (Exception moduleEx)
            {
                moduleInfo.put("error", "Module.bsl write failed: " + moduleEx.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                moduleInfo.put("hint", //$NON-NLS-1$
                    "BM object is created. Add the module manually via write_module_source " //$NON-NLS-1$
                        + "with objectName=WebService." + name + " moduleType=Module."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        ToolResult result = ToolResult.success()
            .put("operation", "create_web_service") //$NON-NLS-1$ //$NON-NLS-2$
            .put("name", name) //$NON-NLS-1$
            .put("fqn", "WebService." + name) //$NON-NLS-1$ //$NON-NLS-2$
            .put("namespace", namespace) //$NON-NLS-1$
            .put("operation_name", operationName) //$NON-NLS-1$
            .put("handler", handler) //$NON-NLS-1$
            .put("dryRun", dryRun); //$NON-NLS-1$
        if (!moduleInfo.isEmpty())
        {
            result.put("module", moduleInfo); //$NON-NLS-1$
        }
        result.put("hint", //$NON-NLS-1$
            "Handler stub returns empty string. Edit it via write_module_source " //$NON-NLS-1$
                + "with objectName=WebService." + name + " moduleType=Module. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Return type (returningValueType) is not set - EDT validator may require " //$NON-NLS-1$
                + "it; set via set_object_property on WebService.<name>.Operation.<op>."); //$NON-NLS-1$
        return result.toJson();
    }

    String opAddWebServiceOperation(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String handlerParam = JsonUtils.extractStringArgument(params, "handler"); //$NON-NLS-1$
        Boolean transactional = JsonUtils.extractBooleanArgumentNullable(params, "transactional"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        boolean withHandlerStub = JsonUtils.extractBooleanArgument(params, "withHandlerStub", false); //$NON-NLS-1$
        String returningValueType = JsonUtils.extractStringArgument(params, "returningValueType"); //$NON-NLS-1$
        String returningValueTypeNs = JsonUtils.extractStringArgument(params, "returningValueTypeNs"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        final String resolvedHandler = (handlerParam != null && !handlerParam.isEmpty())
            ? handlerParam : name;
        final Boolean fTransactional = transactional;
        final String[] retQn = parseQNameArg(returningValueType, returningValueTypeNs);
        final boolean[] retApplied = { false };
        final List<String> retWarn = new ArrayList<>();
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                String ecName = owner.eClass().getName();
                if (!"WebService".equals(ecName)) //$NON-NLS-1$
                {
                    throw new RuntimeException("Unsupported owner type '" + ecName //$NON-NLS-1$
                        + "' is not a WebService."); //$NON-NLS-1$
                }
                @SuppressWarnings("unchecked")
                EList<MdObject> ops = (EList<MdObject>) owner.getClass()
                    .getMethod("getOperations").invoke(owner); //$NON-NLS-1$
                if (BmObjectHelper.findByName(ops, name) != null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("name", name); //$NON-NLS-1$
                    data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "WebService Operation already exists: " + name, //$NON-NLS-1$
                        "Pick a different name or remove the existing operation first.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.ALREADY_EXISTS.wire(), data)));
                }
                MdObject op = BmObjectHelper.createGenericObject("WebServiceOperation"); //$NON-NLS-1$
                if (op == null)
                {
                    op = BmObjectHelper.createGenericObject("Operation"); //$NON-NLS-1$
                }
                if (op == null)
                {
                    throw new RuntimeException("Cannot create WebServiceOperation - factory unavailable."); //$NON-NLS-1$
                }
                op.setName(name);
                EditMetadataTool.applyOptionalString(op, "setProcedureName", resolvedHandler); //$NON-NLS-1$
                if (fTransactional != null)
                {
                    applyOptionalBoolean(op, "setTransactional", fTransactional); //$NON-NLS-1$
                }
                if (retQn != null)
                {
                    Object qname = BmDefinedTypeHelper.createQName(retQn[0], retQn[1]);
                    if (qname != null)
                    {
                        java.lang.reflect.Method setter =
                            EditMetadataTool.findSingleArgSetter(op.getClass(), "setXdtoReturningValueType"); //$NON-NLS-1$
                        if (setter != null)
                        {
                            setter.invoke(op, qname);
                            retApplied[0] = true;
                        }
                        else
                        {
                            retWarn.add("setXdtoReturningValueType not found on Operation"); //$NON-NLS-1$
                        }
                    }
                    else
                    {
                        retWarn.add("QName factory unreachable - return value type not set"); //$NON-NLS-1$
                    }
                }
                ops.add(op);
                return name + " -> " + resolvedHandler; //$NON-NLS-1$
            });
        if (r.ok)
        {
            r.tags.put("handler", resolvedHandler); //$NON-NLS-1$
            if (retQn != null)
            {
                Map<String, Object> rv = new LinkedHashMap<>();
                rv.put("applied", retApplied[0]); //$NON-NLS-1$
                rv.put("nsUri", retQn[0]); //$NON-NLS-1$
                rv.put("localName", retQn[1]); //$NON-NLS-1$
                r.tags.put("returningValueType", rv); //$NON-NLS-1$
                if (!retWarn.isEmpty())
                {
                    r.tags.put("returningValueTypeWarnings", retWarn); //$NON-NLS-1$
                }
            }
            if (withHandlerStub && !dryRun)
            {
                String serviceName = ownerFqn.startsWith("WebService.") //$NON-NLS-1$
                    ? ownerFqn.substring("WebService.".length()) : null; //$NON-NLS-1$
                if (serviceName != null && !serviceName.isEmpty())
                {
                    try
                    {
                        String stubPath = appendWebServiceHandlerStubIfMissing(
                            project, serviceName, resolvedHandler);
                        Map<String, Object> stubInfo = new LinkedHashMap<>();
                        stubInfo.put("module", stubPath); //$NON-NLS-1$
                        stubInfo.put("handler", resolvedHandler); //$NON-NLS-1$
                        r.tags.put("handlerStub", stubInfo); //$NON-NLS-1$
                    }
                    catch (Exception stubEx)
                    {
                        Map<String, Object> stubErr = new LinkedHashMap<>();
                        stubErr.put("error", stubEx.getMessage()); //$NON-NLS-1$
                        r.tags.put("handlerStubFailed", stubErr); //$NON-NLS-1$
                    }
                }
            }
            else
            {
                r.tags.put("hint", //$NON-NLS-1$
                    "Insert the handler procedure manually via write_module_source, or " //$NON-NLS-1$
                        + "re-run with withHandlerStub=true. Signature: " //$NON-NLS-1$
                        + "Функция " + resolvedHandler + "() Экспорт ... КонецФункции"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return EditMetadataTool.formatResult(r, "add_web_service_operation"); //$NON-NLS-1$
    }

    String opRemoveWebServiceOperation(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                String ecName = owner.eClass().getName();
                if (!"WebService".equals(ecName)) //$NON-NLS-1$
                {
                    throw new RuntimeException("Unsupported owner type '" + ecName //$NON-NLS-1$
                        + "' is not a WebService."); //$NON-NLS-1$
                }
                @SuppressWarnings("unchecked")
                EList<MdObject> ops = (EList<MdObject>) owner.getClass()
                    .getMethod("getOperations").invoke(owner); //$NON-NLS-1$
                MdObject target = BmObjectHelper.findByName(ops, name);
                if (target == null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("name", name); //$NON-NLS-1$
                    data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "WebService Operation not found: " + name, //$NON-NLS-1$
                        "Check the operation name.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.NOT_FOUND.wire(), data)));
                }
                ops.remove(target);
                return name;
            });
        return EditMetadataTool.formatResult(r, "remove_web_service_operation"); //$NON-NLS-1$
    }

    String opAddOperationParameter(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String operationName = JsonUtils.extractStringArgument(params, "operationName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String valueType = JsonUtils.extractStringArgument(params, "valueType"); //$NON-NLS-1$
        String valueTypeNs = JsonUtils.extractStringArgument(params, "valueTypeNs"); //$NON-NLS-1$
        String direction = JsonUtils.extractStringArgument(params, "transferDirection"); //$NON-NLS-1$
        boolean nillable = JsonUtils.extractBooleanArgument(params, "nillable", false); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(operationName, "operationName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        final String[] qn = parseQNameArg(valueType, valueTypeNs);
        final String fDirection = direction;
        final boolean fNillable = nillable;
        final boolean[] typeApplied = { false };
        final List<String> warn = new ArrayList<>();

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                String ecName = owner.eClass().getName();
                if (!"WebService".equals(ecName)) //$NON-NLS-1$
                {
                    throw new RuntimeException("Unsupported owner type '" + ecName //$NON-NLS-1$
                        + "' is not a WebService. HTTP services are untyped and have no operation " //$NON-NLS-1$
                        + "parameters."); //$NON-NLS-1$
                }
                @SuppressWarnings("unchecked")
                EList<MdObject> ops = (EList<MdObject>) owner.getClass()
                    .getMethod("getOperations").invoke(owner); //$NON-NLS-1$
                MdObject operation = BmObjectHelper.findByName(ops, operationName);
                if (operation == null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("operationName", operationName); //$NON-NLS-1$
                    data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "WebService Operation not found: " + operationName, //$NON-NLS-1$
                        "Create it first via add_web_service_operation.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.NOT_FOUND.wire(), data)));
                }
                @SuppressWarnings("unchecked")
                EList<MdObject> plist = (EList<MdObject>) operation.getClass()
                    .getMethod("getParameters").invoke(operation); //$NON-NLS-1$
                if (BmObjectHelper.findByName(plist, name) != null)
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("name", name); //$NON-NLS-1$
                    data.put("operationName", operationName); //$NON-NLS-1$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "Operation parameter already exists: " + name, //$NON-NLS-1$
                        "Pick a different name or remove it first.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.ALREADY_EXISTS.wire(), data)));
                }
                MdObject param = BmObjectHelper.createGenericObject("Parameter"); //$NON-NLS-1$
                if (param == null)
                {
                    throw new RuntimeException("Cannot create Parameter - " //$NON-NLS-1$
                        + "MdClassFactory.createParameter() unavailable on this EDT runtime."); //$NON-NLS-1$
                }
                param.setName(name);
                if (qn != null)
                {
                    Object qname = BmDefinedTypeHelper.createQName(qn[0], qn[1]);
                    if (qname != null)
                    {
                        java.lang.reflect.Method setter =
                            EditMetadataTool.findSingleArgSetter(param.getClass(), "setXdtoValueType"); //$NON-NLS-1$
                        if (setter != null)
                        {
                            setter.invoke(param, qname);
                            typeApplied[0] = true;
                        }
                        else
                        {
                            warn.add("setXdtoValueType not found on Parameter"); //$NON-NLS-1$
                        }
                    }
                    else
                    {
                        warn.add("QName factory unreachable - value type not set"); //$NON-NLS-1$
                    }
                }
                if (fDirection != null && !fDirection.trim().isEmpty())
                {
                    Object dir = resolveTransferDirection(fDirection);
                    if (dir != null)
                    {
                        java.lang.reflect.Method setter =
                            EditMetadataTool.findSingleArgSetter(param.getClass(), "setTransferDirection"); //$NON-NLS-1$
                        if (setter != null)
                        {
                            setter.invoke(param, dir);
                        }
                    }
                    else
                    {
                        warn.add("unknown transferDirection '" + fDirection //$NON-NLS-1$
                            + "' (use IN / OUT / IN_OUT)"); //$NON-NLS-1$
                    }
                }
                if (fNillable)
                {
                    applyOptionalBoolean(param, "setNillable", true); //$NON-NLS-1$
                }
                plist.add(param);
                return operationName + "." + name; //$NON-NLS-1$
            });
        if (r.ok)
        {
            Map<String, Object> ti = new LinkedHashMap<>();
            ti.put("valueTypeApplied", typeApplied[0]); //$NON-NLS-1$
            if (qn != null)
            {
                ti.put("nsUri", qn[0]); //$NON-NLS-1$
                ti.put("localName", qn[1]); //$NON-NLS-1$
            }
            r.tags.put("valueType", ti); //$NON-NLS-1$
            if (!warn.isEmpty())
            {
                r.tags.put("warnings", warn); //$NON-NLS-1$
            }
        }
        return EditMetadataTool.formatResult(r, "add_operation_parameter"); //$NON-NLS-1$
    }

    String opCreateHttpService(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        Configuration config = configProvider != null ? configProvider.getConfiguration(project) : null;
        if (config == null)
        {
            return ToolResult.error("Configuration not available for project: " + projectName) //$NON-NLS-1$
                .toJson();
        }

        // Optional inputs with platform-valid defaults. The 1C platform
        // rejects rootURL with slashes ("Invalid rootURL") - it must be a
        // single identifier ([a-zA-Z0-9_]+). Empty/"/" url templates are also
        // rejected as "Неверный формат шаблона".
        String rootURL = JsonUtils.extractStringArgument(params, "rootURL"); //$NON-NLS-1$
        if (rootURL == null || rootURL.isEmpty())
        {
            rootURL = name;
        }
        else
        {
            while (rootURL.startsWith("/")) //$NON-NLS-1$
            {
                rootURL = rootURL.substring(1);
            }
            while (rootURL.endsWith("/")) //$NON-NLS-1$
            {
                rootURL = rootURL.substring(0, rootURL.length() - 1);
            }
            if (rootURL.isEmpty())
            {
                rootURL = name;
            }
            if (rootURL.indexOf('/') >= 0)
            {
                return ToolResult.error("rootURL '" + rootURL //$NON-NLS-1$
                    + "' contains '/' which is invalid in EDT 2026.1. " //$NON-NLS-1$
                    + "The root URL must be a single identifier (letters, digits, underscore). " //$NON-NLS-1$
                    + "Use e.g. 'apiOrders' or 'api_orders' instead.") //$NON-NLS-1$
                    .toJson();
            }
        }
        String aliases = JsonUtils.extractStringArgument(params, "aliases"); //$NON-NLS-1$
        Boolean reuseSessions = JsonUtils.extractBooleanArgumentNullable(params, "reuseSessions"); //$NON-NLS-1$
        Integer sessionMaxAge = JsonUtils.extractIntegerArgument(params, "sessionMaxAge"); //$NON-NLS-1$

        String urlTemplateName = JsonUtils.extractStringArgument(params, "urlTemplateName"); //$NON-NLS-1$
        if (urlTemplateName == null || urlTemplateName.isEmpty())
        {
            urlTemplateName = "Template1"; //$NON-NLS-1$
        }
        String urlTemplate = JsonUtils.extractStringArgument(params, "urlTemplate"); //$NON-NLS-1$
        if (urlTemplate == null || urlTemplate.isEmpty() || "/".equals(urlTemplate)) //$NON-NLS-1$
        {
            urlTemplate = "/" + urlTemplateName; //$NON-NLS-1$
        }
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
        if (methodName == null || methodName.isEmpty())
        {
            methodName = "Get"; //$NON-NLS-1$
        }
        String httpMethod = JsonUtils.extractStringArgument(params, "httpMethod"); //$NON-NLS-1$
        if (httpMethod == null || httpMethod.isEmpty())
        {
            httpMethod = "GET"; //$NON-NLS-1$
        }
        if (!VALID_HTTP_METHODS.contains(httpMethod))
        {
            return ToolResult.error("Invalid httpMethod '" + httpMethod //$NON-NLS-1$
                + "'. Allowed (case-sensitive): " //$NON-NLS-1$
                + String.join(", ", VALID_HTTP_METHODS)).toJson(); //$NON-NLS-1$
        }
        String handlerParam = JsonUtils.extractStringArgument(params, "handler"); //$NON-NLS-1$
        final String handler = (handlerParam != null && !handlerParam.isEmpty())
            ? handlerParam : (urlTemplateName + methodName);
        boolean createModule = JsonUtils.extractBooleanArgument(params, "createModule", true); //$NON-NLS-1$
        boolean withHandlerStub = JsonUtils.extractBooleanArgument(params, "withHandlerStub", true); //$NON-NLS-1$

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        IBmModel bmModel = bmModelManager != null ? bmModelManager.getModel(project) : null;
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available").toJson(); //$NON-NLS-1$
        }

        final String fRootURL = rootURL;
        final String fAliases = aliases;
        final Boolean fReuseSessions = reuseSessions;
        final Integer fSessionMaxAge = sessionMaxAge;
        final String fUrlTemplateName = urlTemplateName;
        final String fUrlTemplate = urlTemplate;
        final String fMethodName = methodName;
        final String fHttpMethod = httpMethod;
        final String fHandler = handler;

        StringBuilder finalErr = new StringBuilder();
        try
        {
            bmModel.getGlobalContext().execute(
                (IBmTask) new AbstractBmTask<Void>("edit_metadata.createHttpService") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    MdObject service = BmObjectHelper.createGenericObject("HTTPService"); //$NON-NLS-1$
                    if (service == null)
                    {
                        finalErr.append("Cannot create HTTPService - " //$NON-NLS-1$
                            + "MdClassFactory.createHTTPService() and MdClassPackage " //$NON-NLS-1$
                            + "lookup both unavailable on this EDT runtime."); //$NON-NLS-1$
                        return null;
                    }
                    service.setName(name);
                    EditMetadataTool.applyMdObjectSynonym(service, null, name, project);
                    EditMetadataTool.applyOptionalString(service, "setRootURL", fRootURL); //$NON-NLS-1$
                    EditMetadataTool.applyOptionalString(service, "setAliases", fAliases); //$NON-NLS-1$
                    // HTTPService.setReuseSessions takes the SessionReuseMode enum (Use/DontUse/AutoUse),
                    // not a boolean - applyOptionalBoolean would find no boolean setter and no-op, leaving
                    // reuse silently unset. Map the boolean wire flag (true = reuse -> Use, false -> DontUse)
                    // onto the enum setter. AutoUse is not reachable through this boolean; leave it to the
                    // platform default when the argument is omitted.
                    if (fReuseSessions != null)
                    {
                        applyEnumByName(service, "setReuseSessions", //$NON-NLS-1$
                            fReuseSessions ? "USE" : "DONT_USE"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    if (fSessionMaxAge != null)
                    {
                        applyOptionalInteger(service, "setSessionMaxAge", fSessionMaxAge); //$NON-NLS-1$
                    }
                    if (!BmObjectHelper.addToConfiguration(config, service))
                    {
                        finalErr.append("Created HTTPService but failed to attach it to the " //$NON-NLS-1$
                            + "configuration. Configuration may not expose the httpServices " //$NON-NLS-1$
                            + "collection on this EDT runtime."); //$NON-NLS-1$
                        return null;
                    }
                    // URL template
                    try
                    {
                        @SuppressWarnings("unchecked")
                        EList<MdObject> templates = (EList<MdObject>) service.getClass()
                            .getMethod("getUrlTemplates").invoke(service); //$NON-NLS-1$
                        MdObject tmpl = BmObjectHelper.createGenericObject("URLTemplate"); //$NON-NLS-1$
                        if (tmpl == null)
                        {
                            finalErr.append("Cannot create URLTemplate - factory unavailable."); //$NON-NLS-1$
                            return null;
                        }
                        tmpl.setName(fUrlTemplateName);
                        EditMetadataTool.applyOptionalString(tmpl, "setTemplate", fUrlTemplate); //$NON-NLS-1$
                        templates.add(tmpl);
                        // Method
                        @SuppressWarnings("unchecked")
                        EList<MdObject> methods = (EList<MdObject>) tmpl.getClass()
                            .getMethod("getMethods").invoke(tmpl); //$NON-NLS-1$
                        MdObject m = BmObjectHelper.createGenericObject("Method"); //$NON-NLS-1$
                        if (m == null)
                        {
                            finalErr.append("Cannot create HTTPService Method - factory unavailable."); //$NON-NLS-1$
                            return null;
                        }
                        m.setName(fMethodName);
                        EditMetadataTool.applyOptionalString(m, "setHandler", fHandler); //$NON-NLS-1$
                        applyHttpMethodValue(m, fHttpMethod);
                        methods.add(m);
                    }
                    catch (Exception nestedEx)
                    {
                        finalErr.append("Failed to attach URLTemplate / Method: " //$NON-NLS-1$
                            + nestedEx.getMessage());
                        return null;
                    }
                    String fqn = "HTTPService." + name; //$NON-NLS-1$
                    tx.attachTopObject((IBmObject) service, fqn);
                    if (dryRun)
                    {
                        throw new RuntimeException("__DRY_RUN__"); //$NON-NLS-1$
                    }
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            String causeMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            if (!"__DRY_RUN__".equals(causeMsg)) //$NON-NLS-1$
            {
                return ToolResult.error("create_http_service failed: " + causeMsg).toJson(); //$NON-NLS-1$
            }
        }
        if (finalErr.length() > 0)
        {
            return ToolResult.error(finalErr.toString()).toJson();
        }

        Map<String, Object> moduleInfo = new LinkedHashMap<>();
        if (!dryRun && createModule)
        {
            try
            {
                String moduleBody = withHandlerStub
                    ? buildHttpServiceModuleStub(handler)
                    : ""; //$NON-NLS-1$
                String modulePath = writeHttpServiceModule(project, name, moduleBody);
                moduleInfo.put("path", modulePath); //$NON-NLS-1$
                moduleInfo.put("handlerStub", withHandlerStub); //$NON-NLS-1$
            }
            catch (Exception moduleEx)
            {
                moduleInfo.put("error", "Module.bsl write failed: " + moduleEx.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                moduleInfo.put("hint", //$NON-NLS-1$
                    "BM object is created. Add the module manually via write_module_source " //$NON-NLS-1$
                        + "with objectName=HTTPService." + name + " moduleType=Module."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        ToolResult result = ToolResult.success()
            .put("operation", "create_http_service") //$NON-NLS-1$ //$NON-NLS-2$
            .put("name", name) //$NON-NLS-1$
            .put("fqn", "HTTPService." + name) //$NON-NLS-1$ //$NON-NLS-2$
            .put("rootURL", rootURL) //$NON-NLS-1$
            .put("urlTemplate", //$NON-NLS-1$
                urlTemplateName + " " + urlTemplate) //$NON-NLS-1$
            .put("method", //$NON-NLS-1$
                methodName + " " + httpMethod + " -> " + handler) //$NON-NLS-1$ //$NON-NLS-2$
            .put("dryRun", dryRun); //$NON-NLS-1$
        if (!moduleInfo.isEmpty())
        {
            result.put("module", moduleInfo); //$NON-NLS-1$
        }
        result.put("hint", //$NON-NLS-1$
            "Handler stub returns 200 OK. Edit it via write_module_source " //$NON-NLS-1$
                + "with objectName=HTTPService." + name + " moduleType=Module."); //$NON-NLS-1$ //$NON-NLS-2$
        return result.toJson();
    }

    // ---- Cluster-local helpers --------------------------------------------

    private static String[] parseQNameArg(String typeStr, String nsArg)
    {
        if (typeStr == null || typeStr.trim().isEmpty())
        {
            return null;
        }
        String s = typeStr.trim();
        if (s.startsWith("{")) //$NON-NLS-1$
        {
            int close = s.indexOf('}');
            if (close > 0)
            {
                return new String[] { s.substring(1, close), s.substring(close + 1) };
            }
        }
        String ns = (nsArg != null && !nsArg.trim().isEmpty()) ? nsArg.trim() : XSD_NS;
        return new String[] { ns, s };
    }

    private static Object resolveTransferDirection(String value)
    {
        String literal = value.trim().toUpperCase(Locale.ROOT);
        if (literal.equals("INOUT")) //$NON-NLS-1$
        {
            literal = "IN_OUT"; //$NON-NLS-1$
        }
        if (!literal.equals("IN") && !literal.equals("OUT") && !literal.equals("IN_OUT")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return null;
        }
        try
        {
            Class<?> td = Class.forName("com._1c.g5.v8.dt.metadata.mdclass.TransferDirection"); //$NON-NLS-1$
            return td.getField(literal).get(null);
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveTransferDirection failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static String appendHandlerStubIfMissing(IProject project, String serviceName,
        String handler) throws Exception
    {
        org.eclipse.core.resources.IFolder srcFolder = project.getFolder("src"); //$NON-NLS-1$
        org.eclipse.core.resources.IContainer base = srcFolder.exists()
            ? srcFolder : (org.eclipse.core.resources.IContainer) project;
        org.eclipse.core.resources.IFolder httpServices = base.getFolder(
            new org.eclipse.core.runtime.Path("HTTPServices")); //$NON-NLS-1$
        if (!httpServices.exists())
        {
            httpServices.create(true, true, null);
        }
        org.eclipse.core.resources.IFolder serviceDir = httpServices.getFolder(serviceName);
        if (!serviceDir.exists())
        {
            serviceDir.create(true, true, null);
        }
        org.eclipse.core.resources.IFile moduleFile = serviceDir.getFile("Module.bsl"); //$NON-NLS-1$
        String existing;
        if (moduleFile.exists())
        {
            try (java.io.InputStream is = moduleFile.getContents())
            {
                existing = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        else
        {
            existing = ""; //$NON-NLS-1$
        }
        if (existing.contains("Функция " + handler) //$NON-NLS-1$
            || existing.contains("Function " + handler)) //$NON-NLS-1$
        {
            return moduleFile.getFullPath().toString();
        }
        String stub = buildHttpServiceModuleStub(handler);
        String newContent;
        if (existing.isEmpty())
        {
            newContent = stub;
        }
        else
        {
            String sep = existing.endsWith("\n") ? "\n" : "\n\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            String stubAppend = stub.startsWith("﻿") ? stub.substring(1) : stub; //$NON-NLS-1$
            newContent = existing + sep + stubAppend;
        }
        byte[] bytes = newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(bytes);
        if (moduleFile.exists())
        {
            moduleFile.setContents(stream, true, true, null);
        }
        else
        {
            moduleFile.create(stream, true, null);
        }
        return moduleFile.getFullPath().toString();
    }

    private static String appendWebServiceHandlerStubIfMissing(IProject project, String serviceName,
        String handler) throws Exception
    {
        org.eclipse.core.resources.IFolder srcFolder = project.getFolder("src"); //$NON-NLS-1$
        org.eclipse.core.resources.IContainer base = srcFolder.exists()
            ? srcFolder : (org.eclipse.core.resources.IContainer) project;
        org.eclipse.core.resources.IFolder webServices = base.getFolder(
            new org.eclipse.core.runtime.Path("WebServices")); //$NON-NLS-1$
        if (!webServices.exists())
        {
            webServices.create(true, true, null);
        }
        org.eclipse.core.resources.IFolder serviceDir = webServices.getFolder(serviceName);
        if (!serviceDir.exists())
        {
            serviceDir.create(true, true, null);
        }
        org.eclipse.core.resources.IFile moduleFile = serviceDir.getFile("Module.bsl"); //$NON-NLS-1$
        String existing;
        if (moduleFile.exists())
        {
            try (java.io.InputStream is = moduleFile.getContents())
            {
                existing = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        else
        {
            existing = ""; //$NON-NLS-1$
        }
        if (existing.contains("Функция " + handler) //$NON-NLS-1$
            || existing.contains("Function " + handler)) //$NON-NLS-1$
        {
            return moduleFile.getFullPath().toString();
        }
        String stub = buildWebServiceModuleStub(handler);
        String newContent;
        if (existing.isEmpty())
        {
            newContent = stub;
        }
        else
        {
            String sep = existing.endsWith("\n") ? "\n" : "\n\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            String stubAppend = stub.startsWith("﻿") ? stub.substring(1) : stub; //$NON-NLS-1$
            newContent = existing + sep + stubAppend;
        }
        byte[] bytes = newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(bytes);
        if (moduleFile.exists())
        {
            moduleFile.setContents(stream, true, true, null);
        }
        else
        {
            moduleFile.create(stream, true, null);
        }
        return moduleFile.getFullPath().toString();
    }

    private static String buildWebServiceModuleStub(String handler)
    {
        return "﻿" //$NON-NLS-1$
            + "// Web service handler. Returns empty string by default.\n" //$NON-NLS-1$
            + "// Insert your business logic here.\n" //$NON-NLS-1$
            + "Функция " + handler + "() Экспорт\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "\tВозврат \"\";\n" //$NON-NLS-1$
            + "КонецФункции\n"; //$NON-NLS-1$
    }

    private static String writeWebServiceModule(IProject project, String name, String content)
        throws Exception
    {
        org.eclipse.core.resources.IFolder srcFolder = project.getFolder("src"); //$NON-NLS-1$
        org.eclipse.core.resources.IContainer base = srcFolder.exists()
            ? srcFolder : (org.eclipse.core.resources.IContainer) project;
        org.eclipse.core.resources.IFolder webServices = base.getFolder(
            new org.eclipse.core.runtime.Path("WebServices")); //$NON-NLS-1$
        if (!webServices.exists())
        {
            webServices.create(true, true, null);
        }
        org.eclipse.core.resources.IFolder serviceDir = webServices.getFolder(name);
        if (!serviceDir.exists())
        {
            serviceDir.create(true, true, null);
        }
        org.eclipse.core.resources.IFile moduleFile = serviceDir.getFile("Module.bsl"); //$NON-NLS-1$
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(bytes);
        if (moduleFile.exists())
        {
            moduleFile.setContents(stream, true, true, null);
        }
        else
        {
            moduleFile.create(stream, true, null);
        }
        return moduleFile.getFullPath().toString();
    }

    private static final Set<String> VALID_HTTP_METHODS =
        new LinkedHashSet<>(Arrays.asList(
            "GET", "POST", "PUT", "DELETE", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "MERGE", "PROPFIND", "PROPPATCH", "MKCOL", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "COPY", "MOVE", "LOCK", "UNLOCK", "Any")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    private static void applyHttpMethodValue(MdObject method, String httpMethod)
    {
        for (java.lang.reflect.Method m : method.getClass().getMethods())
        {
            if (!"setHttpMethod".equals(m.getName()) || m.getParameterCount() != 1) //$NON-NLS-1$
            {
                continue;
            }
            Class<?> p = m.getParameterTypes()[0];
            try
            {
                if (p == String.class)
                {
                    m.invoke(method, httpMethod);
                    return;
                }
                if (p.isEnum())
                {
                    // EDT enum constants are upper-case; the wire vocabulary carries the mixed-case
                    // verb "Any" (a valid HTTP method that maps to HTTPMethod.ANY). resolveEnumConstant
                    // tries the name as given and then its upper-case form, so "Any" -> ANY resolves
                    // instead of throwing IllegalArgumentException and leaving the field unset.
                    Object enumValue = resolveEnumConstant(p, httpMethod);
                    if (enumValue != null)
                    {
                        m.invoke(method, enumValue);
                        return;
                    }
                }
            }
            catch (Exception ignored)
            {
                // Try next overload.
            }
        }
    }

    private static void applyOptionalBoolean(MdObject target, String setterName, Boolean value)
    {
        if (value == null)
        {
            return;
        }
        for (java.lang.reflect.Method m : target.getClass().getMethods())
        {
            if (!setterName.equals(m.getName()) || m.getParameterCount() != 1)
            {
                continue;
            }
            Class<?> p = m.getParameterTypes()[0];
            if (p == boolean.class || p == Boolean.class)
            {
                try
                {
                    m.invoke(target, value);
                    return;
                }
                catch (Exception e)
                {
                    Activator.logWarning(setterName + " on " + target.eClass().getName() //$NON-NLS-1$
                        + " failed: " + e.getMessage()); //$NON-NLS-1$
                    return;
                }
            }
        }
    }

    /**
     * Reflectively invokes a single-argument enum setter, mapping a wire literal name to the enum
     * constant. EDT enum constants are upper-case; the wire vocabulary may carry mixed-case aliases
     * (the boolean-derived "Use"/"DontUse" for {@code SessionReuseMode}), so the lookup tries the name
     * as given and then its upper-case form. Best-effort, like the other apply* helpers: a missing
     * setter or an unresolved literal is logged and skipped, never thrown.
     *
     * @param target the mdclass object whose enum property to set
     * @param setterName the setter method name (expected to take one enum argument)
     * @param literalName the enum constant name to resolve (case-tolerant)
     */
    private static void applyEnumByName(MdObject target, String setterName, String literalName)
    {
        java.lang.reflect.Method setter = null;
        for (java.lang.reflect.Method m : target.getClass().getMethods())
        {
            if (setterName.equals(m.getName()) && m.getParameterCount() == 1
                && m.getParameterTypes()[0].isEnum())
            {
                setter = m;
                break;
            }
        }
        if (setter == null)
        {
            Activator.logWarning(setterName + " not applied: no single-arg enum setter on " //$NON-NLS-1$
                + target.eClass().getName());
            return;
        }
        Object constant = resolveEnumConstant(setter.getParameterTypes()[0], literalName);
        if (constant == null)
        {
            Activator.logWarning(setterName + " not applied: enum constant '" + literalName //$NON-NLS-1$
                + "' is absent on " + setter.getParameterTypes()[0].getSimpleName()); //$NON-NLS-1$
            return;
        }
        try
        {
            setter.invoke(target, constant);
        }
        catch (Exception e)
        {
            Activator.logWarning(setterName + " on " + target.eClass().getName() //$NON-NLS-1$
                + " failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Resolves an enum constant by name, tolerating the case gap between the mixed-case wire
     * vocabulary and the upper-case EDT enum constants. Returns {@code null} when neither the name
     * as given nor its upper-case form matches a constant of {@code enumType}.
     *
     * @param enumType the enum class (a {@link Class} that {@link Class#isEnum()})
     * @param name the literal name to resolve
     * @return the enum constant, or {@code null} if none matches
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object resolveEnumConstant(Class enumType, String name)
    {
        try
        {
            return Enum.valueOf(enumType, name);
        }
        catch (IllegalArgumentException notExact)
        {
            try
            {
                return Enum.valueOf(enumType, name.toUpperCase(Locale.ROOT));
            }
            catch (IllegalArgumentException notUpper)
            {
                return null;
            }
        }
    }

    private static void applyOptionalInteger(MdObject target, String setterName, Integer value)
    {
        if (value == null)
        {
            return;
        }
        for (java.lang.reflect.Method m : target.getClass().getMethods())
        {
            if (!setterName.equals(m.getName()) || m.getParameterCount() != 1)
            {
                continue;
            }
            Class<?> p = m.getParameterTypes()[0];
            if (p == int.class || p == Integer.class)
            {
                try
                {
                    m.invoke(target, value);
                    return;
                }
                catch (Exception e)
                {
                    Activator.logWarning(setterName + " on " + target.eClass().getName() //$NON-NLS-1$
                        + " failed: " + e.getMessage()); //$NON-NLS-1$
                    return;
                }
            }
        }
    }

    private static String buildHttpServiceModuleStub(String handler)
    {
        return "﻿" //$NON-NLS-1$
            + "// HTTP service handler. Returns 200 OK by default.\n" //$NON-NLS-1$
            + "// Insert your business logic here.\n" //$NON-NLS-1$
            + "Функция " + handler + "(Запрос)\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "\tОтвет = Новый HTTPСервисОтвет(200);\n" //$NON-NLS-1$
            + "\tВозврат Ответ;\n" //$NON-NLS-1$
            + "КонецФункции\n"; //$NON-NLS-1$
    }

    private static String writeHttpServiceModule(IProject project, String name, String content)
        throws Exception
    {
        org.eclipse.core.resources.IFolder srcFolder = project.getFolder("src"); //$NON-NLS-1$
        if (!srcFolder.exists())
        {
            srcFolder = null;
        }
        org.eclipse.core.resources.IContainer base = srcFolder != null
            ? srcFolder : (org.eclipse.core.resources.IContainer) project;
        org.eclipse.core.resources.IFolder httpServices = base.getFolder(
            new org.eclipse.core.runtime.Path("HTTPServices")); //$NON-NLS-1$
        if (!httpServices.exists())
        {
            httpServices.create(true, true, null);
        }
        org.eclipse.core.resources.IFolder serviceDir = httpServices.getFolder(name);
        if (!serviceDir.exists())
        {
            serviceDir.create(true, true, null);
        }
        org.eclipse.core.resources.IFile moduleFile = serviceDir.getFile("Module.bsl"); //$NON-NLS-1$
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(bytes);
        if (moduleFile.exists())
        {
            moduleFile.setContents(stream, true, true, null);
        }
        else
        {
            moduleFile.create(stream, true, null);
        }
        return moduleFile.getFullPath().toString();
    }
}
