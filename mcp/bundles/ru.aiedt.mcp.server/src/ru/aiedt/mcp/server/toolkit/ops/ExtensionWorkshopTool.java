/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.nio.file.Paths;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BorrowedSyncClassification;
import ru.aiedt.mcp.server.support.BorrowedSyncReader;
import ru.aiedt.mcp.server.support.BorrowedSyncWriter;
import ru.aiedt.mcp.server.support.ExtensionFitness;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmBinaryImportHelper;
import ru.aiedt.mcp.server.support.BmExtensionHelper;
import ru.aiedt.mcp.server.support.BmExtensionProjectHelper;
import ru.aiedt.mcp.server.support.ChildBorrow;
import ru.aiedt.mcp.server.support.FacadeParameterHelp;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.PendingExecutor;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.ToolGate;

/**
 * Unified extension facade, three areas. Authoring:
 * create_extension_project, borrow_object, borrow_objects (batch), borrow_child,
 * borrow_form_item, borrow_module, list_borrowed, update_borrowed (review and
 * align the borrowed objects against the updated base). Deployment (routes to the
 * standalone tools): install_extension, uninstall_extension, list_extension,
 * export_extension. Inspection (routes to the standalone tools):
 * extension_lifecycle, extension_diff, list_interceptors, check_release_fitness,
 * check_platform_verdict.
 * <p>
 * The borrow ops run through {@link BmExtensionHelper#attemptBorrow}. When the
 * EDT adopt service is not reachable, the response carries a structured
 * {@code adoptServiceNotFound} tag with a GUI workaround hint. The deploy and
 * inspect operations delegate to the matching standalone tools, which remain
 * registered.
 */
public class ExtensionWorkshopTool implements IMcpTool
{
    public static final String NAME = "extension_workshop"; //$NON-NLS-1$

    private static final Map<String, String> OPS = buildOpsCatalog();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Unified extension facade - author, deploy and inspect configuration " //$NON-NLS-1$
            + "extensions. Authoring: create_extension_project (new extension from a base " //$NON-NLS-1$
            + "configuration), borrow_object, borrow_objects (batch), borrow_child, " //$NON-NLS-1$
            + "borrow_form_item, borrow_module, list_borrowed, update_borrowed (resync with the " //$NON-NLS-1$
            + "base). Deployment (into a " //$NON-NLS-1$
            + "project's infobase): install_extension, uninstall_extension, list_extension, " //$NON-NLS-1$
            + "export_extension. Inspection: extension_lifecycle, extension_diff, " //$NON-NLS-1$
            + "list_interceptors, check_release_fitness, check_platform_verdict. The deploy / " //$NON-NLS-1$
            + "inspect operations route " //$NON-NLS-1$
            + "to the matching " //$NON-NLS-1$
            + "standalone tools, which remain available. dryRun is not supported for the " //$NON-NLS-1$
            + "authoring operations; when the EDT adopt service is not reachable the " //$NON-NLS-1$
            + "response carries `adoptServiceNotFound` with a GUI workaround hint."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("operation", //$NON-NLS-1$
                "create_extension_project / borrow_object / borrow_objects / borrow_child / " //$NON-NLS-1$
                    + "borrow_form_item / borrow_module / list_borrowed / update_borrowed / " //$NON-NLS-1$
                    + "install_extension / uninstall_extension / list_extension / " //$NON-NLS-1$
                    + "export_extension / extension_lifecycle / extension_diff / " //$NON-NLS-1$
                    + "list_interceptors / check_release_fitness / check_platform_verdict / help", //$NON-NLS-1$
                true)
            .stringProperty("projectName", //$NON-NLS-1$
                "Extension project name. For create_extension_project this is the NEW " //$NON-NLS-1$
                    + "extension's name (must not already exist); for borrow_* it is the " //$NON-NLS-1$
                    + "existing extension to borrow into.") //$NON-NLS-1$
            .stringProperty("baseProjectName", //$NON-NLS-1$
                "Base configuration project. Required for create_extension_project; for " //$NON-NLS-1$
                    + "borrow_* and update_borrowed defaults to the extension's parent.") //$NON-NLS-1$
            .stringProperty("namePrefix", //$NON-NLS-1$
                "create_extension_project: optional object name prefix for the new " //$NON-NLS-1$
                    + "extension (applied to its Configuration). Omit to keep the EDT default.") //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN to borrow; update_borrowed: the one object to review. " //$NON-NLS-1$
                    + "Top-level: Catalog.Name. " //$NON-NLS-1$
                    + "Child: Catalog.Name.Form.FormName, Document.Name.Attribute.AttrName, " //$NON-NLS-1$
                    + "Document.Name.TabularSection.TsName, Catalog.Name.Template.TplName, " //$NON-NLS-1$
                    + "Catalog.Name.Command.CmdName, InformationRegister.Name.Dimension.DimName, " //$NON-NLS-1$
                    + "InformationRegister.Name.Resource.ResName. Russian child-kind aliases accepted.") //$NON-NLS-1$
            .stringArrayProperty("objectFqns", //$NON-NLS-1$
                "Array of FQNs for borrow_objects batch") //$NON-NLS-1$
            .stringProperty("runKey", //$NON-NLS-1$
                "borrow_objects: a large batch can exceed the soft timeout and return a " //$NON-NLS-1$
                    + "Pending status with a runKey - re-call borrow_objects with the same " //$NON-NLS-1$
                    + "objectFqns and this runKey to fetch the final per-FQN batchResults.") //$NON-NLS-1$
            .stringProperty("childKind", //$NON-NLS-1$
                "borrow_child: Attribute / TabularSection / Form / Template") //$NON-NLS-1$
            .stringProperty("itemName", //$NON-NLS-1$
                "borrow_form_item: the item you mean to change. The item itself is not " //$NON-NLS-1$
                    + "borrowed - the form is, and the item is then yours to change.") //$NON-NLS-1$
            .stringProperty("formName", //$NON-NLS-1$
                "borrow_form_item: which form, when objectFqn names the owner rather than " //$NON-NLS-1$
                    + "the form.") //$NON-NLS-1$
            .booleanProperty("includeChildren", //$NON-NLS-1$
                "borrow_object: also borrow that object's own children. Default false.") //$NON-NLS-1$
            .booleanProperty("apply", //$NON-NLS-1$
                "update_borrowed: write the alignments. Default false - review only.") //$NON-NLS-1$
            .stringProperty("moduleType", //$NON-NLS-1$
                "borrow_module: ObjectModule / ManagerModule / RecordSetModule / " //$NON-NLS-1$
                    + "CommandModule / ValueModule") //$NON-NLS-1$
            .stringProperty("extensionName", //$NON-NLS-1$
                "Deployed extension name for install / uninstall / export_extension.") //$NON-NLS-1$
            .stringProperty("inputPath", //$NON-NLS-1$
                "install_extension: source of the .cfe - a local file path, an http(s):// URL, " //$NON-NLS-1$
                    + "or a github:/gh: release reference.") //$NON-NLS-1$
            .stringProperty("outputPath", //$NON-NLS-1$
                "export_extension: path to write the extracted .cfe to.") //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "install / uninstall / list / export_extension: target infobase application id " //$NON-NLS-1$
                    + "(omit for the project default).") //$NON-NLS-1$
            .booleanProperty("updateDatabase", //$NON-NLS-1$
                "install_extension: run the infobase configuration update after install. Default true.") //$NON-NLS-1$
            .stringProperty("targetFqn", //$NON-NLS-1$
                "extension_lifecycle: the object/method the workflow acts on.") //$NON-NLS-1$
            .stringProperty("eventName", //$NON-NLS-1$
                "extension_lifecycle: the extension event to generate a handler for.") //$NON-NLS-1$
            .stringProperty("mode", //$NON-NLS-1$
                "extension_lifecycle: execution mode - full / dryRun / probeOnly.") //$NON-NLS-1$
            .stringProperty("kind", //$NON-NLS-1$
                "list_interceptors: filter by interceptor kind.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "list_interceptors: maximum rows to return.") //$NON-NLS-1$
            .stringProperty("configurationFile", //$NON-NLS-1$
                "check_platform_verdict: the delivery as a .cf. It is loaded into a staging " //$NON-NLS-1$
                    + "infobase created for this run; the working infobase is not opened, " //$NON-NLS-1$
                    + "locked or altered.") //$NON-NLS-1$
            .stringProperty("extensionFile", //$NON-NLS-1$
                "check_platform_verdict: the extension as a .cfe, put to the platform against " //$NON-NLS-1$
                    + "the delivery above.") //$NON-NLS-1$
            .stringProperty("loadAs", //$NON-NLS-1$
                "check_platform_verdict: the name to load the extension under. Omit and the file " //$NON-NLS-1$
                    + "name is used - which the answer reports back as loadedAs. The platform " //$NON-NLS-1$
                    + "matches an extension to a configuration by content, so the name does not " //$NON-NLS-1$
                    + "change the verdict.") //$NON-NLS-1$
            .stringProperty("platform", //$NON-NLS-1$
                "check_platform_verdict: platform version to run (for example 8.3.27.1234). " //$NON-NLS-1$
                    + "Omit for the newest installed. A delivery built for an older platform " //$NON-NLS-1$
                    + "is refused by a newer one, so name the version it was built for.") //$NON-NLS-1$
            .stringProperty("topic", //$NON-NLS-1$
                "Help topic when operation=help. Without topic - lists all operations.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    /**
     * Where an operation sends the call, for the operations that send it to a tool of its own.
     * <p>
     * Read off the dispatch below one case at a time: these five construct another tool and hand it
     * the call, and four of the five are heavy enough that the server has to know before it starts.
     * {@code check_platform_verdict} is work this facade does itself, but through a staging
     * infobase and a Designer, so it answers its own name for the road to weigh. The rest is work
     * this facade does itself, and answers <code>null</code>. The operation is compared exactly,
     * as the dispatch accepts it: a spelling the dispatch would refuse - uppercase, padded - routes
     * nowhere, and a route that weighed it would take a permit for a call that never runs.
     * </p>
     *
     * @param arguments the call arguments
     * @return the tool the call reaches, or <code>null</code> when this facade runs it
     */
    @Override
    public String routesTo(Map<String, String> arguments)
    {
        String operation = JsonUtils.extractStringArgument(arguments, "operation"); //$NON-NLS-1$
        if (operation == null)
        {
            return null;
        }
        switch (operation)
        {
        case "install_extension": //$NON-NLS-1$
            return "install_extension"; //$NON-NLS-1$
        case "uninstall_extension": //$NON-NLS-1$
            return "uninstall_extension"; //$NON-NLS-1$
        case "list_extension": //$NON-NLS-1$
            return "list_extension"; //$NON-NLS-1$
        case "export_extension": //$NON-NLS-1$
            return "export_extension"; //$NON-NLS-1$
        case "list_interceptors": //$NON-NLS-1$
            return "list_interceptors"; //$NON-NLS-1$
        case "check_platform_verdict": //$NON-NLS-1$
            return "check_platform_verdict"; //$NON-NLS-1$
        default:
            return null;
        }
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String op = JsonUtils.extractStringArgument(params, "operation"); //$NON-NLS-1$
        if (op == null || op.isEmpty())
        {
            return ToolResult.error("operation is required").toJson(); //$NON-NLS-1$
        }
        if ("help".equalsIgnoreCase(op)) //$NON-NLS-1$
        {
            return handleHelp(params);
        }
        if (!OPS.containsKey(op))
        {
            return ToolResult.error("Unknown operation: " + op //$NON-NLS-1$
                + ". Available: " + String.join(", ", OPS.keySet()) //$NON-NLS-1$ //$NON-NLS-2$
                + ". Call operation=help for details.").toJson(); //$NON-NLS-1$
        }
        switch (op)
        {
            case "borrow_object": //$NON-NLS-1$
                String refused = refuseBadIncludeChildren(params);
                if (refused != null)
                {
                    return refused;
                }
                return doBorrow(params, op, null, includeChildrenRequested(params));
            case "borrow_child": //$NON-NLS-1$
                return doBorrow(params, op,
                    JsonUtils.extractStringArgument(params, "childKind")); //$NON-NLS-1$
            case "borrow_form_item": //$NON-NLS-1$
                return doBorrow(params, op, "Form"); //$NON-NLS-1$
            case "borrow_module": //$NON-NLS-1$
                // The module kind travels with the borrow: each kind of object keeps its override
                // flag under its own property, and without knowing which one was meant the linkage
                // could only ever set a common module's.
                return doBorrow(params, op,
                    moduleKindOrDefault(JsonUtils.extractStringArgument(params, "moduleType"))); //$NON-NLS-1$
            case "borrow_objects": //$NON-NLS-1$
                return doBorrowBatch(params);
            case "list_borrowed": //$NON-NLS-1$
                return doListBorrowed(params);
            case "update_borrowed": //$NON-NLS-1$
                return doUpdateBorrowed(params);
            case "create_extension_project": //$NON-NLS-1$
                return doCreateExtensionProject(params);
            case "install_extension": //$NON-NLS-1$
                return gatedRoute(op, () -> new InstallExtensionTool().execute(params));
            case "uninstall_extension": //$NON-NLS-1$
                return gatedRoute(op, () -> new UninstallExtensionTool().execute(params));
            case "list_extension": //$NON-NLS-1$
                return gatedRoute(op, () -> new ListExtensionsTool().execute(params));
            case "export_extension": //$NON-NLS-1$
                return gatedRoute(op, () -> new ExportExtensionTool().execute(params));
            case "extension_lifecycle": //$NON-NLS-1$
                return gatedRoute(op, () -> new ExtensionLifecycleTool().execute(params));
            case "extension_diff": //$NON-NLS-1$
                return new ExtensionDiffTool().execute(params);
            case "list_interceptors": //$NON-NLS-1$
                return new ListInterceptorsTool().execute(params);
            case "check_release_fitness": //$NON-NLS-1$
                return checkReleaseFitness(params);
            case "check_platform_verdict": //$NON-NLS-1$
                return checkPlatformVerdict(params);
            default:
                return ToolResult.error("Unhandled op: " + op).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Says what a new release breaks in an extension.
     * <p>
     * Every object the extension borrowed is looked up in the delivery: gone, or still there with a
     * field missing or a field whose type moved. The interceptors are a separate question and
     * {@code list_interceptors} with a base project answers it, down to whether each handler still
     * matches its target's signature.
     * </p>
     *
     * @param params the call.
     * @return the answer as JSON
     */
    private static String checkReleaseFitness(Map<String, String> params)
    {
        String extension = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String base = JsonUtils.extractStringArgument(params, "baseProjectName"); //$NON-NLS-1$
        if (extension == null || extension.isBlank() || base == null || base.isBlank())
        {
            return ToolResult.error("projectName (the extension) and baseProjectName (the " //$NON-NLS-1$
                + "configuration the new delivery is loaded as) are both required.").toJson(); //$NON-NLS-1$
        }
        ExtensionFitness.Verdict verdict = ExtensionFitness.check(extension, base);
        if (verdict.cannotTell != null)
        {
            return ToolResult.error(verdict.cannotTell).toJson();
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        for (ExtensionFitness.Finding finding : verdict.findings)
        {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("object", finding.object); //$NON-NLS-1$
            one.put("kind", finding.kind); //$NON-NLS-1$
            one.put("what", finding.what); //$NON-NLS-1$
            findings.add(one);
        }
        return ToolResult.success()
            .put("extension", extension) //$NON-NLS-1$
            .put("base", base) //$NON-NLS-1$
            .put("adoptedObjects", verdict.adoptedObjects) //$NON-NLS-1$
            .put("findings", findings) //$NON-NLS-1$
            .put("findingsCount", verdict.findings.size()) //$NON-NLS-1$
            .put("truncated", verdict.truncated) //$NON-NLS-1$
            // Stated in the answer, not only in the description. An empty list here is the moment
            // somebody decides to ship, and it is the moment the limit of this check matters most.
            .put("note", "no findings does NOT mean the extension applies - only the platform " //$NON-NLS-1$ //$NON-NLS-2$
                + "loading it says that. This reads declarations: an object borrowed, a field " //$NON-NLS-1$
                + "borrowed, a type depended on. A dependency written as a string in code, a name " //$NON-NLS-1$
                + "inside a query, or a controlled fragment whose text drifted is not a " //$NON-NLS-1$
                + "declaration and is invisible here. Run list_interceptors with the same " //$NON-NLS-1$
                + "baseProjectName for the handlers.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * The name to load the extension under.
     * <p>
     * {@code loadAs} is what this operation calls it. {@code extensionName} is accepted too because
     * every other operation on this facade spells it that way, and a caller who reaches for the
     * familiar name should not get silence.
     * </p>
     *
     * @param params the call.
     * @return the name, or <code>null</code> to take it off the file
     */
    static String loadAs(Map<String, String> params)
    {
        String named = JsonUtils.extractStringArgument(params, "loadAs"); //$NON-NLS-1$
        return named != null && !named.isBlank() ? named
            : JsonUtils.extractStringArgument(params, "extensionName"); //$NON-NLS-1$
    }

    /**
     * Puts the extension to the platform and reports what the platform said.
     * <p>
     * {@code check_release_fitness} reads declarations and says what it cannot see. This is the
     * other half: the platform loads the extension against the delivery and either takes it or
     * refuses it in its own words. It happens in a staging infobase created for the run and removed
     * afterwards, so the working infobase keeps its configuration and the open EDT session keeps
     * its lock.
     * </p>
     * <p>
     * Gated on {@code create_infobase} because that is what it does. A preset that will not let an
     * infobase be created must not let one be created through here either.
     * </p>
     *
     * @param params the call.
     * @return the verdict as JSON
     */
    private static String checkPlatformVerdict(Map<String, String> params)
    {
        String gate = ToolGate.gateIfPresetDisabled("create_infobase"); //$NON-NLS-1$
        if (gate != null)
        {
            return ToolResult.error(gate).toJson();
        }
        String configuration = JsonUtils.extractStringArgument(params, "configurationFile"); //$NON-NLS-1$
        String extension = JsonUtils.extractStringArgument(params, "extensionFile"); //$NON-NLS-1$
        if (configuration == null || configuration.isBlank() || extension == null
            || extension.isBlank())
        {
            return ToolResult.error("configurationFile (the delivery as a .cf) and extensionFile " //$NON-NLS-1$
                + "(the extension as a .cfe) are both required. Files rather than projects on " //$NON-NLS-1$
                + "purpose: exporting either one out of a project takes the configuration lock " //$NON-NLS-1$
                + "away from the open EDT session, and this operation exists to avoid touching " //$NON-NLS-1$
                + "it.").toJson(); //$NON-NLS-1$
        }
        BmBinaryImportHelper.Verdict verdict = BmBinaryImportHelper.verdict(
            Paths.get(configuration), Paths.get(extension),
            JsonUtils.extractStringArgument(params, "platform"), //$NON-NLS-1$
            loadAs(params));

        ToolResult result = verdict.ok ? ToolResult.success()
            : ToolResult.error(verdict.error == null
                ? "the run ended without reaching a verdict and without saying why" //$NON-NLS-1$
                : verdict.error);
        // Named for what it is. Without an extensionName the value comes off the FILE name, so
        // "ext.cfe" reported extensionName=ext for an extension called AiEdtC22Ext - a guess
        // reading as a fact. The platform matches an extension to a configuration by content and
        // not by name, so the verdict stands either way; the field just has to stop claiming to be
        // the extension's own name.
        result.put("loadedAs", verdict.extensionName) //$NON-NLS-1$
            .put("stagingInfobaseName", verdict.stagingInfobaseName) //$NON-NLS-1$
            .put("stagingCreated", verdict.stagingCreated) //$NON-NLS-1$
            // Said out loud. A staging infobase that outlived its run is a file tree on disk, and
            // the only way the caller can clear it is by the name printed above.
            .put("stagingRemoved", verdict.stagingRemoved); //$NON-NLS-1$
        if (verdict.stagingCreated && !verdict.stagingRemoved)
        {
            result.put("stagingLeftBehind", "the staging infobase could not be removed - it is " //$NON-NLS-1$ //$NON-NLS-2$
                + "registered under the name above and can be deleted with " //$NON-NLS-1$
                + "infobase_admin operation=delete_infobase."); //$NON-NLS-1$
        }
        if (!verdict.ok)
        {
            result.put("failureKind", verdict.failureKind); //$NON-NLS-1$
            return result.toJson();
        }
        return result.put("applies", verdict.applies) //$NON-NLS-1$
            .put("refusedAt", verdict.refusedAt) //$NON-NLS-1$
            .put("platformSaid", verdict.platformSaid) //$NON-NLS-1$
            .put("note", Boolean.TRUE.equals(verdict.applies) //$NON-NLS-1$
                ? "the platform loaded the extension against this delivery. That is the verdict " //$NON-NLS-1$
                    + "on whether it APPLIES, not on whether it still does what it was written " //$NON-NLS-1$
                    + "to do - a handler left pointing at a method that changed meaning loads " //$NON-NLS-1$
                    + "fine. Run check_release_fitness and list_interceptors for that half."
                : "the platform refused the extension, and platformSaid carries its words " //$NON-NLS-1$ //$NON-NLS-2$
                    + "unedited. refusedAt says which question it failed: 'applicability' means " //$NON-NLS-1$
                    + "the extension does not fit this delivery; 'load' means the file would not " //$NON-NLS-1$
                    + "go into the configuration store at all, which is usually the file or the " //$NON-NLS-1$
                    + "platform version rather than fitness.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Runs a delegated deployment operation only when its standalone tool is enabled under the active
     * preset; otherwise returns the preset's rejection wrapped in this JSON-typed tool's envelope.
     * <p>
     * {@code install_extension}, {@code uninstall_extension}, {@code list_extension} and
     * {@code export_extension} are APPLICATIONS-group standalones that presets such as Code Review
     * disable, while {@code extension_workshop} is a CONSTRUCTORS tool such a preset keeps. Without this
     * gate the facade would run them regardless - a preset bypass. The rejection text is shared with
     * {@code McpRequestRouter} through {@link ToolGate}, so the facade operation and the standalone
     * carry the same rejection wording (the MCP envelope differs - JSON here, text content from the
     * router); it is JSON-wrapped because a raw string would crash the protocol handler for this
     * JSON-typed tool.
     * </p>
     *
     * @param op the operation name, also the standalone tool name the gate consults
     * @param run the delegation to run when the standalone is enabled
     * @return the delegation's result, or a JSON-wrapped rejection when the standalone is disabled
     */
    private static String gatedRoute(String op, Supplier<String> run)
    {
        String gate = ToolGate.gateOrNull(op);
        return gate != null ? ToolResult.error(gate).put("operation", op).toJson() : run.get(); //$NON-NLS-1$
    }

    private String doBorrow(Map<String, String> params, String op, String childKind,
        boolean includeChildren)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String baseProjectName = JsonUtils.extractStringArgument(params, "baseProjectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        if (projectName == null || objectFqn == null)
        {
            return ToolResult.error("projectName and objectFqn are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        // The child's name has to reach the borrow as part of the name being borrowed. The helper
        // resolves one object from one FQN and ignores childKind entirely, so a call that named an
        // owner and a kind adopted the owner and reported success - measured, and the template it
        // was asked for appeared nowhere.
        String borrowFqn = MiscOps.composeChildFqn(op, objectFqn, params);
        if (borrowFqn == null)
        {
            return MiscOps.noFormNamed(objectFqn, params);
        }
        BmExtensionHelper.BorrowResult r = BmExtensionHelper.attemptBorrow(project,
            baseProjectName, borrowFqn, childKind);
        if (includeChildren && r.ok)
        {
            return ChildBorrow.finish(op, borrowFqn, r, project, baseProjectName,
                fqn -> ChildBorrow.fromBorrow(BmExtensionHelper.attemptBorrow(project,
                    baseProjectName, fqn, null)));
        }
        return formatResult(r, op, borrowFqn);
    }

    /**
     * The borrow the other operations use: the named object only.
     *
     * @param params the call
     * @param op the operation
     * @param childKind the child kind, or <code>null</code> for a top object
     * @return the answer
     */
    private String doBorrow(Map<String, String> params, String op, String childKind)
    {
        return doBorrow(params, op, childKind, false);
    }

    /**
     * Refuses a value of includeChildren that is not a boolean, before any borrow.
     * <p>
     * Absent is the default and is not a refusal. A typo must not borrow the one object and
     * look like success.
     * </p>
     *
     * @param params the call
     * @return the refusal, or <code>null</code> when the value is absent or a boolean
     */
    private static String refuseBadIncludeChildren(Map<String, String> params)
    {
        String raw = JsonUtils.extractStringArgument(params, "includeChildren"); //$NON-NLS-1$
        if (raw == null)
        {
            return null;
        }
        if (JsonUtils.extractBooleanArgumentNullable(params, "includeChildren") != null) //$NON-NLS-1$
        {
            return null;
        }
        return ToolResult.error("includeChildren must be true or false (also 1, 0, yes, no); '" //$NON-NLS-1$
            + raw + "' is not one of those, and nothing was borrowed.").toJson(); //$NON-NLS-1$
    }

    /**
     * Whether this borrow_object call asked for the object's own children.
     *
     * @param params the call
     * @return <code>true</code> only for a recognized true
     */
    private static boolean includeChildrenRequested(Map<String, String> params)
    {
        return Boolean.TRUE.equals(
            JsonUtils.extractBooleanArgumentNullable(params, "includeChildren")); //$NON-NLS-1$
    }

    /**
     * The module kind a borrow should carry, defaulting to the one an object has when it has only
     * one.
     *
     * @param moduleType what the caller named, possibly nothing.
     * @return the kind to pass along.
     */
    private static String moduleKindOrDefault(String moduleType)
    {
        return moduleType == null || moduleType.trim().isEmpty() ? "Module" : moduleType.trim(); //$NON-NLS-1$
    }

    private String doBorrowBatch(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String baseProjectName = JsonUtils.extractStringArgument(params, "baseProjectName"); //$NON-NLS-1$
        String fqnsRaw = JsonUtils.extractStringArgument(params, "objectFqns"); //$NON-NLS-1$
        if (projectName == null || fqnsRaw == null)
        {
            return ToolResult.error("projectName and objectFqns are required").toJson(); //$NON-NLS-1$
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        List<String> fqns = parseFqnsArray(fqnsRaw);
        if (fqns.isEmpty())
        {
            return ToolResult
                .error("objectFqns must be a non-empty JSON array of FQN strings").toJson(); //$NON-NLS-1$
        }
        // Borrow is a mutating batch that can outlive the MCP call timeout when
        // adopt work is slow (large FQN sets, deep objects). Run it on the Pending
        // pool and hand back a runKey on soft-timeout, so the caller polls for the
        // final per-FQN results instead of receiving a bare timeout that hides the
        // partial adopt progress. Mirrors edit_metadata batch. Like executeBatch,
        // this is a mutating batch on the shared GENERIC executor (the only
        // established pattern for mutating Pending work in this codebase).
        PendingWorkRegistry reg = PendingWorkRegistry.GENERIC;
        reg.pruneExpired();
        String providedRunKey = JsonUtils.extractStringArgument(params, "runKey"); //$NON-NLS-1$
        boolean resume = providedRunKey != null && !providedRunKey.isEmpty();
        // The key is ALWAYS derived from THIS tool + params. A supplied runKey is
        // only a resume signal, never a lookup token: trusting a caller-supplied
        // key would let it retrieve/remove an unrelated tool's Pending entry from
        // the shared registry. Reject a mismatched supplied key outright.
        String runKey = PendingWorkRegistry.computeRunKey("borrow_objects", projectName, //$NON-NLS-1$
            baseProjectName == null ? "" : baseProjectName, fqnsRaw); //$NON-NLS-1$
        if (resume && !runKey.equals(providedRunKey))
        {
            return ToolResult.error("runKey does not match these parameters - re-call " //$NON-NLS-1$
                + "borrow_objects with the same objectFqns (and baseProjectName) that " //$NON-NLS-1$
                + "produced the runKey, or omit runKey to start a fresh batch.").toJson(); //$NON-NLS-1$
        }

        final IProject fProject = project;
        final String fBaseProjectName = baseProjectName;
        final List<String> fFqns = fqns;
        Supplier<String> work = () -> runBorrowBatch(fProject, fBaseProjectName, fFqns);

        PendingWorkRegistry.PendingEntry entry;
        if (resume)
        {
            // Explicit retry - poll the existing entry, do NOT restart the work.
            entry = reg.get(runKey);
            if (entry == null)
            {
                return ToolResult.error("runKey not found - the batch either completed and its " //$NON-NLS-1$
                    + "result was already retrieved, or it expired. Re-call borrow_objects without " //$NON-NLS-1$
                    + "runKey to start a fresh batch.").toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            // A fresh resubmit of an identical completed-but-never-retrieved batch
            // must RE-RUN (project state may have moved on), not replay the stale
            // cached result - mirrors edit_metadata batch / update_database.
            PendingWorkRegistry.PendingEntry prior = reg.get(runKey);
            if (prior != null && prior.isDone())
            {
                reg.remove(runKey);
            }
            entry = reg.getOrStart(runKey, work);
        }

        String done = entry.await(PendingExecutor.DEFAULT_SOFT_TIMEOUT_MS);
        if (done != null)
        {
            reg.remove(runKey);
            return done;
        }
        return ToolResult.success()
            .put("status", "Pending") //$NON-NLS-1$ //$NON-NLS-2$
            .put(ru.aiedt.mcp.server.support.PendingEnvelope.MARK, true)
            .put("batch", true) //$NON-NLS-1$
            .put("runKey", runKey) //$NON-NLS-1$
            .put("elapsedMs", entry.elapsedMs()) //$NON-NLS-1$
            .put("total", fFqns.size()) //$NON-NLS-1$
            .put("message", "borrow_objects still running - adopts apply on a worker thread. " //$NON-NLS-1$
                + "Re-call extension_workshop operation=borrow_objects with the same objectFqns " //$NON-NLS-1$
                + "and this runKey to fetch the per-FQN batchResults.").toJson(); //$NON-NLS-1$
    }

    /**
     * Runs the borrow loop synchronously and returns the per-FQN result JSON.
     * Extracted so the whole batch can run on the Pending pool thread.
     */
    private static String runBorrowBatch(IProject project, String baseProjectName, List<String> fqns)
    {
        List<Map<String, Object>> results = new ArrayList<>();
        int okCount = 0;
        int failCount = 0;
        for (String fqn : fqns)
        {
            BmExtensionHelper.BorrowResult br = BmExtensionHelper.attemptBorrow(project,
                baseProjectName, fqn, null);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("targetFqn", fqn); //$NON-NLS-1$
            entry.put("ok", br.ok); //$NON-NLS-1$
            if (br.error != null)
            {
                entry.put("error", br.error); //$NON-NLS-1$
            }
            entry.putAll(br.tags);
            results.add(entry);
            if (br.ok)
            {
                okCount++;
            }
            else
            {
                failCount++;
            }
        }
        return ToolResult.success()
            .put("operation", "borrow_objects") //$NON-NLS-1$ //$NON-NLS-2$
            .put("ok", okCount) //$NON-NLS-1$
            .put("fail", failCount) //$NON-NLS-1$
            .put("batchResults", results).toJson(); //$NON-NLS-1$
    }

    private String doListBorrowed(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null)
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        // list_borrowed semantics: read the extension's "adopted" objects via
        // the configuration's adoptedObjects collection (best-effort reflection).
        // When the configuration provider is not reachable we surface the same
        // adoptServiceNotFound tag so callers can fall back to GUI.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hint", //$NON-NLS-1$
            "list_borrowed in 1.37 returns the discovered API class so the agent " //$NON-NLS-1$
                + "can see whether borrowing is even possible. Listing the actual " //$NON-NLS-1$
                + "borrowed objects requires an EDT-side index that is not exposed " //$NON-NLS-1$
                + "as a stable API yet. Use the EDT Project Explorer's Extension " //$NON-NLS-1$
                + "subtree to view borrowed objects."); //$NON-NLS-1$
        data.put("discoveredApi", BmExtensionHelper.resolvedAdoptServiceClass()); //$NON-NLS-1$
        return ToolResult.success()
            .put("operation", "list_borrowed") //$NON-NLS-1$ //$NON-NLS-2$
            .put("projectName", projectName) //$NON-NLS-1$
            .put("listBorrowed", data) //$NON-NLS-1$
            .toJson();
    }

    /** How many rows one update_borrowed answer carries before it says it stopped counting. */
    private static final int UPDATE_BORROWED_ROW_CAP = 1000;

    /** The statuses an answer counts, in the order the summary lists them. */
    private static final List<String> UPDATE_BORROWED_STATUSES = List.of(
        "inSync", "baseWider", "typeConflict", "sourceGone", "renamedInBase", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "formOutOfDate", "handlerSignatureChanged"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Says how far the borrowed objects of an extension have drifted from the base it extends,
     * and with {@code apply=true} writes what aligns safely.
     * <p>
     * The review reads each borrowed thing's own record against the base: a uuid that no longer
     * resolves, a name that moved, a controlled type or its qualifiers that moved, a base form
     * that grew items, a handler that no longer fits its target. Apply writes only the two safe
     * halves - the controlled types and, through the EDT adopt service, the borrowed forms -
     * and lists everything else under {@code notApplied} with the reason it stayed. After the
     * writes the same walk runs again, so {@code stillOutOfSync} is what the model now holds
     * rather than what the write intended.
     * </p>
     *
     * @param params the call
     * @return the review, and with {@code apply=true} the write outcomes
     */
    private String doUpdateBorrowed(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String baseProjectName = JsonUtils.extractStringArgument(params, "baseProjectName"); //$NON-NLS-1$
        String objectFqnRaw = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        Integer limitArg = JsonUtils.extractIntegerArgument(params, "limit"); //$NON-NLS-1$
        boolean apply = Boolean.TRUE.equals(
            JsonUtils.extractBooleanArgumentNullable(params, "apply")); //$NON-NLS-1$
        if (projectName == null || projectName.isBlank())
        {
            return ToolResult.error(TextSuggest.missingParam("projectName", //$NON-NLS-1$
                "update_borrowed projectName=MyExt [objectFqn=Catalog.Products] [apply=true]")) //$NON-NLS-1$
                .put("operation", "update_borrowed").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (apply)
        {
            // The facade is the write door here, so the door is asked before anything else: a
            // preset that switched this tool off must not let the write through the operation
            // it kept quiet about.
            String gate = ToolGate.gateIfPresetDisabled(NAME);
            if (gate != null)
            {
                return ToolResult.error(gate).put("operation", "update_borrowed").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        IProject extension = ProjectResolver.resolve(projectName);
        if (extension == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        String objectFqn = objectFqnRaw == null || objectFqnRaw.isBlank() ? null
            : MetadataTypeCatalog.normalizeFqn(objectFqnRaw.trim());
        Review review = reviewBorrowed(extension, baseProjectName, objectFqn, limitArg);
        if (review.error != null)
        {
            return ToolResult.error(review.error).put("operation", "update_borrowed").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (objectFqn != null && review.rows.isEmpty() && review.notChecked.isEmpty())
        {
            return objectNotFound(objectFqn, review);
        }
        ToolResult result = ToolResult.success()
            .put("operation", "update_borrowed") //$NON-NLS-1$ //$NON-NLS-2$
            .put("extension", projectName) //$NON-NLS-1$
            .put("base", review.baseProjectName) //$NON-NLS-1$
            .put("apply", apply); //$NON-NLS-1$
        if (objectFqn != null)
        {
            result.put("objectFqn", objectFqn); //$NON-NLS-1$
        }
        result.put("rows", review.rows); //$NON-NLS-1$
        result.put("summary", review.summary); //$NON-NLS-1$
        if (!review.notChecked.isEmpty())
        {
            result.put("notChecked", review.notChecked); //$NON-NLS-1$
        }
        if (review.truncated)
        {
            result.put("truncated", true); //$NON-NLS-1$
        }
        if (!apply)
        {
            return result.toJson();
        }
        return applyReview(extension, objectFqn, limitArg, review, result);
    }

    /** One review of an extension's borrowed things: rows, their summary, what was not read. */
    private static final class Review
    {
        /** The rows, as the answer shows them. */
        final List<Map<String, Object>> rows = new ArrayList<>();

        /** Counters per status plus the totals the answer leads with. */
        final Map<String, Object> summary = new LinkedHashMap<>();

        /** What could not be established, each with a reason. */
        final List<Map<String, String>> notChecked = new ArrayList<>();

        /** The typeConflict rows that carry what a write needs. */
        final List<BorrowedSyncReader.Row> typeRows = new ArrayList<>();

        /** The formOutOfDate rows that carry a base form. */
        final List<BorrowedSyncReader.Row> formRows = new ArrayList<>();

        /** The base the review read, by name. */
        String baseProjectName;

        /** The extension's configuration, for the refusal that names what was not found. */
        Object extensionConfiguration;

        /** Why nothing was reviewed. Null when the walk ran. */
        String error;

        /** True when the row cap stopped the count. */
        boolean truncated;
    }

    /**
     * Reads one review: the model walk plus the interceptors, with the summary over both.
     *
     * @param extension the extension project
     * @param baseProjectName the base by name, or null to derive it
     * @param objectFqn the one object to restrict to, or null
     * @param limitArg the interceptor cap the caller named, or null
     * @return the review
     */
    private Review reviewBorrowed(IProject extension, String baseProjectName, String objectFqn,
        Integer limitArg)
    {
        Review review = new Review();
        BorrowedSyncReader.Report report =
            BorrowedSyncReader.read(extension, baseProjectName, objectFqn);
        review.error = report.error;
        review.baseProjectName = report.baseProjectName;
        review.extensionConfiguration = report.extensionConfiguration;
        if (report.error != null)
        {
            return review;
        }
        for (BorrowedSyncReader.Row row : report.rows)
        {
            if (review.rows.size() >= UPDATE_BORROWED_ROW_CAP)
            {
                review.truncated = true;
                break;
            }
            review.rows.add(rowMap(row));
            if (row.status == BorrowedSyncClassification.Status.TYPE_CONFLICT
                && row.targetId != 0 && row.baseTypeDescription != null)
            {
                review.typeRows.add(row);
            }
            if (row.status == BorrowedSyncClassification.Status.FORM_OUT_OF_DATE
                && row.baseForm != null)
            {
                review.formRows.add(row);
            }
        }
        review.notChecked.addAll(report.notChecked);
        addInterceptorRows(review, extension, report.baseProject, objectFqn, limitArg);
        summarise(review);
        return review;
    }

    /**
     * Adds the interceptor rows, from the same scan {@code list_interceptors} runs.
     *
     * @param review the review being built
     * @param extension the extension project
     * @param baseProject the base the signatures are checked against
     * @param objectFqn the one object to restrict to, or null
     * @param limitArg the interceptor cap the caller named, or null
     */
    private static void addInterceptorRows(Review review, IProject extension, IProject baseProject,
        String objectFqn, Integer limitArg)
    {
        int max = limitArg != null && limitArg.intValue() > 0 ? limitArg.intValue() : 200;
        ListInterceptorsTool.Scan scan = ListInterceptorsTool.scan(extension, baseProject, null,
            max);
        if (scan.error != null)
        {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("fqn", "the modules of " + extension.getName()); //$NON-NLS-1$ //$NON-NLS-2$
            entry.put("reason", "the interceptor scan failed: " + scan.error); //$NON-NLS-1$
            review.notChecked.add(entry);
            return;
        }
        // The scan stops at its cap, as list_interceptors reports: past it a signature break is
        // unseen, so the review is not complete.
        if (scan.hits.size() >= max)
        {
            review.truncated = true;
        }
        for (Map<String, Object> hit : scan.hits)
        {
            if (review.rows.size() >= UPDATE_BORROWED_ROW_CAP)
            {
                review.truncated = true;
                break;
            }
            String modulePath = String.valueOf(hit.get("module")); //$NON-NLS-1$
            String owner = ownerFqnOfModule(extension.getName(), modulePath);
            if (objectFqn != null && (owner == null || !underFilter(owner, objectFqn)))
            {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fqn", modulePath); //$NON-NLS-1$
            row.put("kind", "interceptor"); //$NON-NLS-1$ //$NON-NLS-2$
            row.put("handler", hit.get("handler")); //$NON-NLS-1$
            row.put("targetMethod", hit.get("targetMethod")); //$NON-NLS-1$
            Object breaks = hit.get("signatureBreaks"); //$NON-NLS-1$
            if (breaks != null)
            {
                row.put("signatureBreaks", breaks); //$NON-NLS-1$
            }
            Boolean fits = (Boolean)hit.get("signatureFits"); //$NON-NLS-1$
            Boolean targetExists = (Boolean)hit.get("targetExists"); //$NON-NLS-1$
            if (fits != null)
            {
                row.put("status", fits.booleanValue() ? "inSync" : "handlerSignatureChanged"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                review.rows.add(row);
                continue;
            }
            if (Boolean.FALSE.equals(targetExists))
            {
                row.put("status", "handlerSignatureChanged"); //$NON-NLS-1$ //$NON-NLS-2$
                row.put("note", "the target method is gone from the base module"); //$NON-NLS-1$ //$NON-NLS-2$
                review.rows.add(row);
                continue;
            }
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("fqn", modulePath + ": " + hit.get("handler")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            entry.put("reason", "the signatures could not be compared: " //$NON-NLS-1$
                + hit.get("signatureNote")); //$NON-NLS-1$
            review.notChecked.add(entry);
        }
    }

    /**
     * The object a module path belongs to.
     *
     * @param projectName the project the path was read under
     * @param modulePath the module's workspace path
     * @return the owning object's FQN, or null when the path names no object
     */
    private static String ownerFqnOfModule(String projectName, String modulePath)
    {
        String relative = modulePath;
        String prefix = "/" + projectName + "/"; //$NON-NLS-1$ //$NON-NLS-2$
        if (relative.startsWith(prefix))
        {
            relative = relative.substring(prefix.length());
        }
        if (relative.startsWith("src/")) //$NON-NLS-1$
        {
            relative = relative.substring(4);
        }
        String[] segments = relative.split("/"); //$NON-NLS-1$
        if (segments.length < 2)
        {
            return null;
        }
        String type = MetadataTypeCatalog.getTypeByDirectoryName(segments[0]);
        return type == null ? null : type + "." + segments[1]; //$NON-NLS-1$
    }

    /**
     * Whether an object is the one a review was restricted to or lives under it.
     *
     * @param fqn the object's FQN
     * @param filter the restriction
     * @return true when the object belongs in the review
     */
    private static boolean underFilter(String fqn, String filter)
    {
        return fqn.equals(filter) || fqn.startsWith(filter + "."); //$NON-NLS-1$
    }

    /**
     * Turns one model row into the map the answer shows.
     *
     * @param row the row
     * @return the answer's row
     */
    private static Map<String, Object> rowMap(BorrowedSyncReader.Row row)
    {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fqn", row.fqn); //$NON-NLS-1$
        map.put("kind", row.kind); //$NON-NLS-1$
        map.put("status", BorrowedSyncClassification.wire(row.status)); //$NON-NLS-1$
        if (row.controlledType != null)
        {
            map.put("controlledType", row.controlledType); //$NON-NLS-1$
            map.put("baseType", row.baseType); //$NON-NLS-1$
        }
        if (row.baseName != null)
        {
            map.put("extensionName", row.extensionName); //$NON-NLS-1$
            map.put("baseName", row.baseName); //$NON-NLS-1$
        }
        if (row.missingItems != null)
        {
            map.put("missingItems", row.missingItems); //$NON-NLS-1$
        }
        return map;
    }

    /**
     * Counts the rows per status and answers whether anything is out of sync.
     *
     * @param review the review to count
     */
    private static void summarise(Review review)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String status : UPDATE_BORROWED_STATUSES)
        {
            counts.put(status, Integer.valueOf(0));
        }
        boolean outOfSync = false;
        for (Map<String, Object> row : review.rows)
        {
            String status = String.valueOf(row.get("status")); //$NON-NLS-1$
            counts.merge(status, Integer.valueOf(1), Integer::sum);
            if (!"inSync".equals(status) && !"baseWider".equals(status)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                outOfSync = true;
            }
        }
        review.summary.put("rows", Integer.valueOf(review.rows.size())); //$NON-NLS-1$
        review.summary.putAll(counts);
        review.summary.put("outOfSync", Boolean.valueOf(outOfSync)); //$NON-NLS-1$
    }

    /**
     * Refuses an objectFqn that names no borrowed object, naming the nearest ones.
     *
     * @param objectFqn the address the caller asked for
     * @param review the review that found nothing under it
     * @return the refusal
     */
    private String objectNotFound(String objectFqn, Review review)
    {
        List<String> candidates = new ArrayList<>();
        int dot = objectFqn.indexOf('.');
        if (dot > 0
            && review.extensionConfiguration instanceof com._1c.g5.v8.dt.metadata.mdclass.Configuration)
        {
            candidates.addAll(MetadataTypeCatalog.findSimilarObjects(
                (com._1c.g5.v8.dt.metadata.mdclass.Configuration)review.extensionConfiguration,
                objectFqn.substring(0, dot), objectFqn.substring(dot + 1), 5));
        }
        StringBuilder message = new StringBuilder("No borrowed object under "); //$NON-NLS-1$
        message.append(objectFqn).append(" in this extension."); //$NON-NLS-1$
        String suggestion = TextSuggest.closest(objectFqn, candidates);
        if (suggestion != null)
        {
            message.append(" Did you mean '").append(suggestion).append("'?"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!candidates.isEmpty())
        {
            message.append(" Borrowed here: ").append(String.join(", ", candidates)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ToolResult.error(message.toString())
            .put("reason", "objectNotFound") //$NON-NLS-1$ //$NON-NLS-2$
            .put("operation", "update_borrowed") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Writes the alignments, then reads the model again for what is still out of sync.
     *
     * @param extension the extension project
     * @param objectFqn the restriction the review ran under, or null
     * @param limitArg the interceptor cap the caller named, or null
     * @param review the review that decided the writes
     * @param result the answer being built
     * @return the finished answer
     */
    private String applyReview(IProject extension, String objectFqn, Integer limitArg,
        Review review, ToolResult result)
    {
        List<Map<String, Object>> updatedTypes = new ArrayList<>();
        List<Map<String, Object>> formsUpdated = new ArrayList<>();
        List<Map<String, Object>> notApplied = new ArrayList<>();
        Map<String, BorrowedSyncWriter.TypeWrite> writesByFqn = new LinkedHashMap<>();
        if (!review.typeRows.isEmpty())
        {
            for (BorrowedSyncWriter.TypeWrite write : BorrowedSyncWriter.alignTypes(extension,
                review.typeRows))
            {
                writesByFqn.put(write.fqn, write);
            }
        }
        for (BorrowedSyncReader.Row row : review.typeRows)
        {
            BorrowedSyncWriter.TypeWrite write = writesByFqn.get(row.fqn);
            if (write != null && write.error == null)
            {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("fqn", write.fqn); //$NON-NLS-1$
                one.put("was", write.was); //$NON-NLS-1$
                one.put("now", write.now); //$NON-NLS-1$
                if (write.flushNote != null)
                {
                    one.put("flushNote", write.flushNote); //$NON-NLS-1$
                }
                updatedTypes.add(one);
            }
            else
            {
                notApplied.add(notApplied(write == null ? row.fqn : write.fqn,
                    write == null || write.error == null ? "nothing was written" : write.error)); //$NON-NLS-1$
            }
        }
        for (BorrowedSyncReader.Row row : review.formRows)
        {
            BorrowedSyncWriter.FormWrite write = BorrowedSyncWriter.updateForm(extension, row);
            if (write.updated)
            {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("fqn", write.fqn); //$NON-NLS-1$
                if (write.flushNote != null)
                {
                    one.put("flushNote", write.flushNote); //$NON-NLS-1$
                }
                formsUpdated.add(one);
            }
            else
            {
                notApplied.add(notApplied(write.fqn, write.status));
            }
        }
        for (Map<String, Object> row : review.rows)
        {
            Object fqn = row.get("fqn"); //$NON-NLS-1$
            String status = String.valueOf(row.get("status")); //$NON-NLS-1$
            if (hasFqn(updatedTypes, fqn) || hasFqn(formsUpdated, fqn) || hasFqn(notApplied, fqn))
            {
                // A row with a write of its own is accounted for above - written, or refused
                // with the write's own failure - and is not listed twice.
                continue;
            }
            notApplied.add(notApplied(String.valueOf(fqn), reasonFor(status)));
        }
        result.put("updatedTypes", updatedTypes); //$NON-NLS-1$
        result.put("formsUpdated", formsUpdated); //$NON-NLS-1$
        result.put("notApplied", notApplied); //$NON-NLS-1$
        Review after = reviewBorrowed(extension, review.baseProjectName, objectFqn, limitArg);
        List<Map<String, Object>> still = new ArrayList<>();
        if (after.error == null)
        {
            for (Map<String, Object> row : after.rows)
            {
                String status = String.valueOf(row.get("status")); //$NON-NLS-1$
                if (!"inSync".equals(status) && !"baseWider".equals(status)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    still.add(row);
                }
            }
        }
        result.put("stillOutOfSync", still); //$NON-NLS-1$
        result.put("note", "apply writes the controlled types and updates the borrowed forms " //$NON-NLS-1$
            + "through the EDT adopt service. sourceGone, renamedInBase and " //$NON-NLS-1$
            + "handlerSignatureChanged rows are never written: removing a borrowed object, " //$NON-NLS-1$
            + "renaming to a base name and rewriting a handler module are decisions this " //$NON-NLS-1$
            + "operation does not take for you."); //$NON-NLS-1$
        return result.toJson();
    }

    /**
     * Whether a list of answer entries already names an FQN.
     *
     * @param entries the entries, each a map with an fqn
     * @param fqn the FQN to look for
     * @return true when one of the entries carries it
     */
    private static boolean hasFqn(List<Map<String, Object>> entries, Object fqn)
    {
        for (Map<String, Object> entry : entries)
        {
            if (entry.get("fqn").equals(fqn)) //$NON-NLS-1$
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Why a status leaves its row unwritten.
     *
     * @param status the row's status
     * @return the reason
     */
    private static String reasonFor(String status)
    {
        switch (status)
        {
        case "inSync": //$NON-NLS-1$
            return "in sync - nothing to align"; //$NON-NLS-1$
        case "baseWider": //$NON-NLS-1$
            return "the base is wider and the platform accepts it - nothing to align"; //$NON-NLS-1$
        case "sourceGone": //$NON-NLS-1$
            return "the base holds nothing under the uuid - removing the borrowed object is a " //$NON-NLS-1$
                + "separate decision"; //$NON-NLS-1$
        case "renamedInBase": //$NON-NLS-1$
            return "the base renamed the object - rewriting the extension's name is a separate " //$NON-NLS-1$
                + "decision"; //$NON-NLS-1$
        case "handlerSignatureChanged": //$NON-NLS-1$
            return "the handler is code in a module - align it with write_module_source"; //$NON-NLS-1$
        case "typeConflict": //$NON-NLS-1$
            return "the attribute is not one the object model lets this operation write"; //$NON-NLS-1$
        case "formOutOfDate": //$NON-NLS-1$
            return "the base form was not reached, so the adopt service was not asked"; //$NON-NLS-1$
        default:
            return "nothing this operation writes"; //$NON-NLS-1$
        }
    }

    /**
     * One notApplied entry.
     *
     * @param fqn what was not written
     * @param reason why
     * @return the entry
     */
    private static Map<String, Object> notApplied(String fqn, String reason)
    {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("fqn", fqn); //$NON-NLS-1$
        entry.put("reason", reason); //$NON-NLS-1$
        return entry;
    }

    private String doCreateExtensionProject(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String baseProjectName = JsonUtils.extractStringArgument(params, "baseProjectName"); //$NON-NLS-1$
        String namePrefix = JsonUtils.extractStringArgument(params, "namePrefix"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("projectName", //$NON-NLS-1$
                "create_extension_project projectName=MyExt baseProjectName=BaseCfg")).toJson(); //$NON-NLS-1$
        }
        if (baseProjectName == null || baseProjectName.isEmpty())
        {
            return ToolResult.error(TextSuggest.missingParam("baseProjectName", //$NON-NLS-1$
                "create_extension_project projectName=MyExt baseProjectName=BaseCfg")).toJson(); //$NON-NLS-1$
        }
        IProject baseProject = ProjectResolver.resolve(baseProjectName);
        if (baseProject == null)
        {
            return ProjectResolver.notFound(baseProjectName).toJson();
        }
        BmExtensionProjectHelper.CreateResult r =
            BmExtensionProjectHelper.createExtensionProject(projectName, baseProject, namePrefix);
        if (r.ok)
        {
            ToolResult res = ToolResult.success()
                .put("operation", "create_extension_project") //$NON-NLS-1$ //$NON-NLS-2$
                .put("projectName", r.createdProjectName) //$NON-NLS-1$
                .put("baseProject", baseProjectName); //$NON-NLS-1$
            if (r.alreadyExists)
            {
                res.put(ErrorTags.ALREADY_EXISTS.wire(), true);
                res.put("message", r.hint != null ? r.hint : "already exists"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                res.put("message", "Extension project created"); //$NON-NLS-1$ //$NON-NLS-2$
                boolean prefixRequested = namePrefix != null && !namePrefix.trim().isEmpty();
                if (prefixRequested)
                {
                    res.put("namePrefixApplied", r.namePrefixApplied); //$NON-NLS-1$
                    if (!r.namePrefixApplied)
                    {
                        res.put("namePrefixHint", //$NON-NLS-1$
                            "namePrefix not applied yet (new project still indexing). Set it via " //$NON-NLS-1$
                                + "edit_metadata operation=set_object_property projectName=" //$NON-NLS-1$
                                + r.createdProjectName
                                + " objectName=Configuration property=namePrefix value=" //$NON-NLS-1$
                                + namePrefix.trim());
                    }
                }
                res.put("nextSteps", //$NON-NLS-1$
                    "Run get_project_errors on '" + r.createdProjectName //$NON-NLS-1$
                        + "' to confirm a clean build, then borrow base objects via " //$NON-NLS-1$
                        + "borrow_object before overriding them."); //$NON-NLS-1$
            }
            return res.toJson();
        }
        ToolResult err = ToolResult.error("create_extension_project failed: " //$NON-NLS-1$
            + (r.error != null ? r.error : "unknown error")) //$NON-NLS-1$
            .put("operation", "create_extension_project"); //$NON-NLS-1$ //$NON-NLS-2$
        if (r.serviceNotFound)
        {
            err.put("serviceNotFound", true); //$NON-NLS-1$
        }
        if (r.hint != null)
        {
            err.put("hint", r.hint); //$NON-NLS-1$
        }
        return err.toJson();
    }

    private String formatResult(BmExtensionHelper.BorrowResult r, String op, String objectFqn)
    {
        if (r.ok)
        {
            ToolResult result = ToolResult.success()
                .put("operation", op) //$NON-NLS-1$
                .put("objectFqn", objectFqn) //$NON-NLS-1$
                .put("message", r.alreadyBorrowed ? "already borrowed" : "borrowed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            applyTags(result, r.tags);
            return result.toJson();
        }
        ToolResult err = ToolResult.error(op + " failed: " //$NON-NLS-1$
            + (r.error != null ? r.error : "unknown error")) //$NON-NLS-1$
            .put("operation", op) //$NON-NLS-1$
            .put("objectFqn", objectFqn); //$NON-NLS-1$
        applyTags(err, r.tags);
        return err.toJson();
    }

    private static void applyTags(ToolResult result, Map<String, Object> tags)
    {
        if (tags == null || tags.isEmpty())
        {
            return;
        }
        for (Map.Entry<String, Object> entry : tags.entrySet())
        {
            result.put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Parses a JSON-array string ({@code ["a","b"]}) or a comma-separated list
     * into a list of FQNs. Best-effort; trims quotes/whitespace.
     */
    private static List<String> parseFqnsArray(String raw)
    {
        List<String> out = new ArrayList<>();
        if (raw == null)
        {
            return out;
        }
        String s = raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            s = s.substring(1, s.length() - 1);
        }
        for (String token : s.split(",")) //$NON-NLS-1$
        {
            String t = token.trim();
            if (t.startsWith("\"") && t.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                t = t.substring(1, t.length() - 1).trim();
            }
            if (!t.isEmpty())
            {
                out.add(t);
            }
        }
        return out;
    }

    private String handleHelp(Map<String, String> params)
    {
        String topic = JsonUtils.extractStringArgument(params, "topic"); //$NON-NLS-1$
        if (topic == null || topic.isEmpty())
        {
            StringBuilder sb = new StringBuilder("# extension_workshop\n\n"); //$NON-NLS-1$
            // Counted, not typed. It said 14 while the facade answered 15, because a number
            // written into prose stops being true the next time an operation is added.
            sb.append("Author, deploy and inspect configuration extensions. ") //$NON-NLS-1$
                .append(OPS.size()).append(" operations.\n\n"); //$NON-NLS-1$
            sb.append("Authoring:\n"); //$NON-NLS-1$
            sb.append("- create_extension_project - create a NEW extension project from a " //$NON-NLS-1$
                + "base configuration (projectName=new name, baseProjectName=base config)\n"); //$NON-NLS-1$
            sb.append("- borrow_object - borrow a single FQN (Catalog/Document/etc.); " //$NON-NLS-1$
                + "includeChildren also borrows its own children\n"); //$NON-NLS-1$
            sb.append("- borrow_objects - batch borrow with per-FQN results\n"); //$NON-NLS-1$
            sb.append("- borrow_child - borrow a single attribute/tabular section/form/template\n"); //$NON-NLS-1$
            sb.append("- borrow_form_item - borrow a single form item by name\n"); //$NON-NLS-1$
            sb.append("- borrow_module - borrow a specific module of an object\n"); //$NON-NLS-1$
            sb.append("- list_borrowed - report the discovered adopt API and a hint\n"); //$NON-NLS-1$
            sb.append("- update_borrowed - how far the borrowed objects drifted from the " //$NON-NLS-1$
                + "updated base, and with apply=true align the controlled types (base's exact " //$NON-NLS-1$
                + "type, or AnyRef when the base went composite with references) and update the " //$NON-NLS-1$
                + "borrowed forms. Statuses: inSync, baseWider, typeConflict, sourceGone, " //$NON-NLS-1$
                + "renamedInBase, formOutOfDate, handlerSignatureChanged. apply writes nothing " //$NON-NLS-1$
                + "for sourceGone, renamedInBase or handlerSignatureChanged.\n"); //$NON-NLS-1$
            sb.append("Deployment (into a project's infobase; route to the standalone tools):\n"); //$NON-NLS-1$
            sb.append("- install_extension - install a .cfe (inputPath) as an extension\n"); //$NON-NLS-1$
            sb.append("- uninstall_extension - remove a named extension (extensionName)\n"); //$NON-NLS-1$
            sb.append("- list_extension - list installed extensions\n"); //$NON-NLS-1$
            sb.append("- export_extension - extract a named extension to a .cfe (outputPath)\n"); //$NON-NLS-1$
            sb.append("Inspection:\n"); //$NON-NLS-1$
            sb.append("- extension_lifecycle - guided probe / adopt / generate / revalidate\n"); //$NON-NLS-1$
            sb.append("- extension_diff - what an extension changes vs the base configuration\n"); //$NON-NLS-1$
            sb.append("- list_interceptors - method interceptors declared by extensions\n\n"); //$NON-NLS-1$
            sb.append("- check_release_fitness - what a new delivery breaks in an extension: an " //$NON-NLS-1$
                + "adopted object gone, a borrowed field gone, a field whose type moved. Needs " //$NON-NLS-1$
                + "baseProjectName. Finding nothing does NOT mean the extension applies.\n"); //$NON-NLS-1$
            sb.append("- check_platform_verdict - the platform itself loads the extension against " //$NON-NLS-1$
                + "a delivery .cf, in a staging infobase created for the run and removed after " //$NON-NLS-1$
                + "it. Takes configurationFile and extensionFile. The working infobase is not " //$NON-NLS-1$
                + "opened or locked. This is the half check_release_fitness cannot answer.\n\n"); //$NON-NLS-1$
            sb.append("**Adopt API status:** ") //$NON-NLS-1$
                .append(BmExtensionHelper.isAvailable()
                    ? ("found - " + BmExtensionHelper.resolvedAdoptServiceClass()) //$NON-NLS-1$
                    : "NOT reachable in this EDT version") //$NON-NLS-1$
                .append("\n\n"); //$NON-NLS-1$
            sb.append("Topics: workflow, errorTags\n"); //$NON-NLS-1$
            return ToolResult.success().put("help", sb.toString()).toJson(); //$NON-NLS-1$
        }
        switch (topic.toLowerCase())
        {
            case "workflow": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", //$NON-NLS-1$
                        "Borrow workflow:\n" //$NON-NLS-1$
                            + "0. (optional) Create the extension project first:\n" //$NON-NLS-1$
                            + "   create_extension_project projectName=MyExt baseProjectName=BaseCfg " //$NON-NLS-1$
                            + "[namePrefix=ME_]\n" //$NON-NLS-1$
                            + "   -> writes the new project (V8ExtensionNature + its own " //$NON-NLS-1$
                            + "Configuration.mdo, objectBelonging=Adopted). Then get_project_errors " //$NON-NLS-1$
                            + "to confirm a clean build.\n" //$NON-NLS-1$
                            + "1. Open base configuration + extension project in the same workspace.\n" //$NON-NLS-1$
                            + "2. baseProjectName is OPTIONAL for borrow_* (auto-resolved from the " //$NON-NLS-1$
                            + "extension's parent config); pass it only to borrow from a non-default base.\n" //$NON-NLS-1$
                            + "3. Top-level borrow (baseProjectName omitted = auto-resolve):\n" //$NON-NLS-1$
                            + "   borrow_object projectName=MyExt objectFqn=Catalog.Products\n" //$NON-NLS-1$
                            + "   includeChildren=true also borrows that object's own children " //$NON-NLS-1$
                            + "(not modules, not objects reached only by type).\n" //$NON-NLS-1$
                            + "4. Child borrow (form / attribute / tabular section / template / command / dimension / resource):\n" //$NON-NLS-1$
                            + "   borrow_child projectName=MyExt baseProjectName=BaseCfg " //$NON-NLS-1$
                            + "objectFqn=Catalog.Products.Form.ItemForm childKind=Form\n" //$NON-NLS-1$
                            + "   Supported child-FQN forms: \n" //$NON-NLS-1$
                            + "     Catalog.X.Form.Y\n" //$NON-NLS-1$
                            + "     Document.X.Attribute.Y\n" //$NON-NLS-1$
                            + "     Document.X.TabularSection.Y\n" //$NON-NLS-1$
                            + "     Catalog.X.Template.Y\n" //$NON-NLS-1$
                            + "     Catalog.X.Command.Y\n" //$NON-NLS-1$
                            + "     InformationRegister.X.Dimension.Y\n" //$NON-NLS-1$
                            + "     InformationRegister.X.Resource.Y\n" //$NON-NLS-1$
                            + "5. Batch borrow:\n" //$NON-NLS-1$
                            + "   borrow_objects projectName=MyExt baseProjectName=BaseCfg " //$NON-NLS-1$
                            + "objectFqns=[\"Catalog.A\",\"Document.B.Form.C\"]\n" //$NON-NLS-1$
                            + "6. After successful borrow the extension can override " //$NON-NLS-1$
                            + "attributes / forms via edit_metadata / edit_form.")
                    .toJson();
            case "errortags": //$NON-NLS-1$
                return ToolResult.success().put("topic", topic) //$NON-NLS-1$
                    .put("text", //$NON-NLS-1$
                        "Tags surfaced by extension_workshop:\n" //$NON-NLS-1$
                            + "- borrowed { targetFqn, baseProject, returned } - success.\n" //$NON-NLS-1$
                            + "- alreadyBorrowed { targetFqn } - idempotent success path " //$NON-NLS-1$
                            + "(verified via extension BM/EMF, not EDT message heuristic).\n" //$NON-NLS-1$
                            + "- partialBorrowDetected { targetFqn, edtMessage, api?, hint } - " //$NON-NLS-1$
                            + "EDT reported 'already' but the target is NOT resolvable in the " //$NON-NLS-1$
                            + "extension model. Stale files on disk (orphan Forms/<name>/, " //$NON-NLS-1$
                            + "missing Form.form, parent .mdo without the child entry) are the " //$NON-NLS-1$
                            + "usual cause. Inspect filesystem, remove orphan files, retry.\n" //$NON-NLS-1$
                            + "- adoptServiceNotFound { operation, targetFqn, hint } - probe failed.\n" //$NON-NLS-1$
                            + "- adoptInvocationFailed { targetFqn, methodSignature?, error } - " //$NON-NLS-1$
                            + "service found but invocation threw.\n" //$NON-NLS-1$
                            + "- batchResults [...] - one entry per FQN in borrow_objects.\n")
                    .toJson();
            default:
                String operation = topic.toLowerCase(java.util.Locale.ROOT);
                if (OPS.containsKey(operation))
                {
                    String text = FacadeParameterHelp.fromTheMap(operation, "ExtensionWorkshopTool", //$NON-NLS-1$
                        getInputSchema(), includeChildrenDetail(operation));
                    if (!"borrow_object".equals(operation)) //$NON-NLS-1$
                    {
                        text = text + "\nincludeChildren applies only to borrow_object; this " //$NON-NLS-1$
                            + "operation does not read it.\n"; //$NON-NLS-1$
                    }
                    return ToolResult.success().put("topic", topic).put("text", text).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return ToolResult.error("Unknown topic: " + topic).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * The rules the one-sentence schema description of includeChildren no longer carries.
     *
     * @param operation the operation being described
     * @return parameter name to those rules, empty for every operation but borrow_object
     */
    private static Map<String, String> includeChildrenDetail(String operation)
    {
        Map<String, String> detail = new LinkedHashMap<>();
        if ("borrow_object".equals(operation)) //$NON-NLS-1$
        {
            detail.put("includeChildren", //$NON-NLS-1$
                "Absent or false borrows only the named object and the answer is unchanged: " //$NON-NLS-1$
                    + "no children, skipped or pulledByType. A value other than true, false, 1, 0, " //$NON-NLS-1$
                    + "yes or no is refused, naming includeChildren, before anything is borrowed. " //$NON-NLS-1$
                    + "true walks that object's own collections, including attributes of tabular " //$NON-NLS-1$
                    + "sections and nested subsystems, and does not follow a reference to another " //$NON-NLS-1$
                    + "object. Modules are not overridden and predefined items are not included. A " //$NON-NLS-1$
                    + "standard attribute is skipped with reason notAdoptable. Objects EDT brings " //$NON-NLS-1$
                    + "in because an attribute type refers to them are listed in pulledByType (an " //$NON-NLS-1$
                    + "empty list when none were, pulledByTypeUnverified with a reason when the " //$NON-NLS-1$
                    + "snapshot could not be taken), and their children are not borrowed. A root " //$NON-NLS-1$
                    + "that is already borrowed stays a success and the children are still walked. " //$NON-NLS-1$
                    + "borrow_objects does not read this argument."); //$NON-NLS-1$
        }
        return detail;
    }

    private static Map<String, String> buildOpsCatalog()
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (String op : Arrays.asList(
            "create_extension_project", //$NON-NLS-1$
            "borrow_object", "borrow_objects", "borrow_child", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "borrow_form_item", "borrow_module", "list_borrowed", "update_borrowed", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "install_extension", "uninstall_extension", "list_extension", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "export_extension", "extension_lifecycle", "extension_diff", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "list_interceptors", "check_release_fitness", "check_platform_verdict")) //$NON-NLS-1$
        {
            m.put(op, op);
        }
        return Collections.unmodifiableMap(m);
    }
}
