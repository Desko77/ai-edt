package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmCommonFormPostCreate;
import ru.aiedt.mcp.server.support.BmCommonModuleGuards;
import ru.aiedt.mcp.server.support.BmDcsHelper;
import ru.aiedt.mcp.server.support.BmDefinedTypeHelper;
import ru.aiedt.mcp.server.support.BmExtensionTypeHelper;
import ru.aiedt.mcp.server.support.BmExportHelper;
import ru.aiedt.mcp.server.support.BmExtensionHelper;
import ru.aiedt.mcp.server.support.BmFormResourceHelper;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.BmSubsystemHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.MetadataGuards;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TypeApplication;

/**
 * Objects cluster of {@code edit_metadata}: top-object create/remove, scalar and
 * reference property setting, type application, and the attribute / tabular-section
 * CRUD. Extracted verbatim from {@link EditMetadataTool} (Inc4 god-class split);
 * handlers are package-visible and dispatched through the single-source op-registry.
 * Shared stateless helpers live on {@link EditMetadataTool} (qualified calls);
 * cluster-local helpers ({@link #extractReferenceTargetFqns},
 * {@link #refFqnForSegment}, {@link #maybeAutoBorrowOwner}) are private here.
 */
final class ObjectOps
{
    /**
     * Applies the execution-context flags to a freshly created CommonModule. EDT requires a
     * module to declare at least one context (server/client/externalConnection/serverCall/
     * global), else it fails the "common-module-type" check on UpdateDBCfg. When the caller
     * set none, server defaults to true (the EDT wizard default); an explicit server wins;
     * global alone counts as a context and so suppresses the server default. Reflective
     * because the setters live on the mdclass EMF object.
     */
    private static void applyCommonModuleFlags(MdObject module, Boolean privileged, Boolean global,
        Boolean server, Boolean externalConnection, Boolean clientOrdinaryApplication,
        Boolean serverCall)
    {
        // Default server=true only when the caller named NO execution context at all: EDT's
        // common-module-type check requires one, and `global` is not one, so a global-only request
        // would otherwise leave the module contextless and invalid. Any context the caller did name
        // wins - defaulting server on top of an explicit externalConnection would write a module
        // nobody asked for. Measured on 2026.2: every one of these setters takes a bare boolean.
        setBooleanProperty(module, "setServer", //$NON-NLS-1$
            serverFlagToWrite(server, externalConnection, clientOrdinaryApplication, serverCall));
        if (externalConnection != null)
        {
            setBooleanProperty(module, "setExternalConnection", externalConnection); //$NON-NLS-1$
        }
        if (clientOrdinaryApplication != null)
        {
            setBooleanProperty(module, "setClientOrdinaryApplication", //$NON-NLS-1$
                clientOrdinaryApplication);
        }
        if (serverCall != null)
        {
            setBooleanProperty(module, "setServerCall", serverCall); //$NON-NLS-1$
        }
        if (global != null)
        {
            setBooleanProperty(module, "setGlobal", global); //$NON-NLS-1$
        }
        if (privileged != null)
        {
            setBooleanProperty(module, "setPrivileged", privileged); //$NON-NLS-1$
        }
    }

    /**
     * What to write to Server, given the execution contexts the caller named.
     * <p>
     * True when the caller asked for it, and true when the caller turned no other context ON - the
     * common-module-type check requires one, and Global is not one. A caller who did turn another
     * context on does not get Server on top of it. A context named as false leaves the module with
     * none, so it does not suppress the default: the point of defaulting is that the tool never
     * writes a module the check rejects.
     * </p>
     *
     * @param server the Server flag as asked for, or <code>null</code>
     * @param externalConnection the External connection flag as asked for, or <code>null</code>
     * @param clientOrdinaryApplication the Client (ordinary application) flag, or <code>null</code>
     * @param serverCall the Server call flag as asked for, or <code>null</code>
     * @return the value to write
     */
    static boolean serverFlagToWrite(Boolean server, Boolean externalConnection,
        Boolean clientOrdinaryApplication, Boolean serverCall)
    {
        if (server != null)
        {
            return server;
        }
        return !Boolean.TRUE.equals(externalConnection)
            && !Boolean.TRUE.equals(clientOrdinaryApplication)
            && !Boolean.TRUE.equals(serverCall);
    }

    /**
     * What steers the call rather than describing the object. None of these is a property of
     * anything, so a properties object carrying one has nothing to apply.
     */
    private static final Set<String> SERVICE_ARGUMENTS =
        Collections.unmodifiableSet(new java.util.HashSet<>(Arrays.asList(
            "projectName", "objectType", "name", "synonym", "dryRun", "properties"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    /**
     * The execution contexts a common module takes as arguments of its own. Only that type reads
     * them by name; on any other type a property of the same name is an ordinary property.
     */
    private static final Set<String> COMMON_MODULE_FLAGS =
        Collections.unmodifiableSet(new java.util.HashSet<>(Arrays.asList(
            "server", "externalConnection", "clientOrdinaryApplication", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "global", "privileged", "serverCall"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * What the properties object still has to write itself.
     * <p>
     * The service arguments come out always: they steer the call and describe no object. The module
     * flags come out ONLY for a common module, the one type that reads them as arguments of its own.
     * </p>
     * <p>
     * <b>Taking the flags out for every type would drop a property named {@code server} on a
     * catalogue without a word</b> - the silent loss this whole change exists to end, reintroduced
     * one method away from where it was fixed. Caught reading the change back, not by a test, which
     * is why there is one now.
     * </p>
     *
     * @param declared the members of the properties object.
     * @param isCommonModule whether the object being created is a common module.
     * @return the properties to apply one by one, in the order they were given
     */
    static Map<String, String> propertiesToApply(Map<String, String> declared,
        boolean isCommonModule)
    {
        Map<String, String> remaining = new LinkedHashMap<>(declared);
        remaining.keySet().removeAll(SERVICE_ARGUMENTS);
        if (isCommonModule)
        {
            remaining.keySet().removeAll(COMMON_MODULE_FLAGS);
        }
        return remaining;
    }

    /**
     * Says which property was given twice with two different values.
     *
     * @param params the call's arguments.
     * @param declared the members of the properties object.
     * @return the refusal text, or <code>null</code> when nothing contradicts
     */
    static String contradictingProperty(Map<String, String> params,
        Map<String, String> declared)
    {
        if (params == null || declared.isEmpty())
        {
            return null;
        }
        for (Map.Entry<String, String> given : declared.entrySet())
        {
            String named = params.get(given.getKey());
            if (named != null && !named.equals(given.getValue()))
            {
                return "'" + given.getKey() + "' was given twice and the two disagree: " //$NON-NLS-1$ //$NON-NLS-2$
                    + "the argument says '" + named + "', properties says '" //$NON-NLS-1$ //$NON-NLS-2$
                    + given.getValue() + "'. Nothing was created. Give it once, or give the same " //$NON-NLS-1$
                    + "value in both."; //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Writes one boolean flag, and fails the call when it cannot.
     * <p>
     * <b>This used to swallow every exception into a log line.</b> A flag the caller asked for
     * could go unwritten - a setter renamed between EDT builds, a value the model refused - and
     * {@code create_object} still answered success. Nothing in the answer said which flag was
     * dropped; the log is not somewhere a caller looks, and by the time anyone read the file the
     * module had already failed EDT's own check. Found by reconciliation on 01.09.
     * </p>
     * <p>
     * Raised rather than collected: the caller creates the object in one transaction, and a flag
     * that cannot be written has to stop that transaction before the object is attached, so
     * nothing half-made reaches the configuration.
     * </p>
     *
     * @param target the object being built.
     * @param setter the setter's name.
     * @param value the value to write.
     */
    private static void setBooleanProperty(Object target, String setter, boolean value)
    {
        try
        {
            target.getClass().getMethod(setter, boolean.class).invoke(target, value);
        }
        catch (ReflectiveOperationException | RuntimeException notApplied)
        {
            throw new RuntimeException("CommonModule flag " + setter.substring(3) //$NON-NLS-1$
                + " could not be written: " + notApplied.getMessage() //$NON-NLS-1$
                + ". The module was not created.", notApplied); //$NON-NLS-1$
        }
    }

    String opCreateObject(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectType = JsonUtils.extractStringArgument(params, "objectType"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(objectType, "objectType") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(name, "name"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        String englishType = MetadataTypeCatalog.toEnglishSingular(objectType);
        if (englishType == null)
        {
            return ToolResult.error("Unknown objectType: " + objectType //$NON-NLS-1$
                + ". Use English singular (Catalog, Document, ...) or Russian equivalent.") //$NON-NLS-1$
                .toJson();
        }
        // ExternalDataProcessor / ExternalReport are standalone DT projects (.epf/.erf),
        // not configuration objects: Configuration has no collection to attach them, so a
        // generic create would fail at the attach step. Reject early with a clear hint.
        if ("ExternalDataProcessor".equals(englishType) //$NON-NLS-1$
            || "ExternalReport".equals(englishType)) //$NON-NLS-1$
        {
            return ToolResult.error(englishType + " is a standalone DT project (.epf/.erf), not a " //$NON-NLS-1$
                + "configuration object. Create a separate External Data Processor / External Report " //$NON-NLS-1$
                + "DT project, then build the .epf/.erf with export_object.").toJson(); //$NON-NLS-1$
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

        // A property named twice - once as an argument, once in the properties object - with two
        // different values is a contradiction, and picking one of them silently would write
        // something the caller did not ask for. Equal values are not a contradiction.
        Map<String, String> declared = JsonUtils.extractObjectArgument(params, "properties"); //$NON-NLS-1$
        String contradiction = contradictingProperty(params, declared);
        if (contradiction != null)
        {
            return ToolResult.error(contradiction).toJson();
        }
        // Folded into the arguments rather than applied separately, so a flag written this way
        // reaches the same defaulting the named argument goes through. Applying it afterwards
        // would let the default for an unnamed context overwrite it.
        Map<String, String> effective = new LinkedHashMap<>(params);
        for (Map.Entry<String, String> given : declared.entrySet())
        {
            effective.putIfAbsent(given.getKey(), given.getValue());
        }

        // 3.8.2: extension CommonModule guards (privileged, global+server). The flags are
        // hoisted to finals so the BM task below can also APPLY them to the created module
        // - without that the module is created with no execution context and fails EDT's
        // "common-module-type" check on UpdateDBCfg (hindsight A10).
        final boolean isCommonModule = "CommonModule".equals(englishType);

        final Map<String, String> remaining = propertiesToApply(declared, isCommonModule);
        // Named in the answer, because "success" on its own does not say which properties reached
        // the object - and that is exactly what could not be told apart before.
        final List<String> applied = new ArrayList<>();
        final Boolean cmPrivileged = isCommonModule
            ? JsonUtils.extractBooleanArgumentNullable(effective, "privileged") : null; //$NON-NLS-1$
        final Boolean cmGlobal = isCommonModule
            ? JsonUtils.extractBooleanArgumentNullable(effective, "global") : null; //$NON-NLS-1$
        final Boolean cmServer = isCommonModule
            ? JsonUtils.extractBooleanArgumentNullable(effective, "server") : null; //$NON-NLS-1$
        final Boolean cmExternalConnection = isCommonModule
            ? JsonUtils.extractBooleanArgumentNullable(effective, "externalConnection") : null; //$NON-NLS-1$
        final Boolean cmClientOrdinaryApplication = isCommonModule
            ? JsonUtils.extractBooleanArgumentNullable(effective, "clientOrdinaryApplication") : null; //$NON-NLS-1$
        final Boolean cmServerCall = isCommonModule
            ? JsonUtils.extractBooleanArgumentNullable(effective, "serverCall") : null; //$NON-NLS-1$
        if (isCommonModule)
        {
            try
            {
                BmCommonModuleGuards.validate(project, cmPrivileged, cmGlobal, cmServer);
            }
            catch (MetadataGuards.BlockedGuardException blocked)
            {
                MetadataGuards.Verdict v = blocked.verdict;
                ToolResult result = ToolResult.error(v.error != null ? v.error : "blocked")
                    .put("operation", "create_object")
                    .put("hint", v.hint != null ? v.hint : "");
                if (v.tag != null)
                {
                    result.put(v.tag.name, v.tag.data);
                }
                return result.toJson();
            }
        }

        // Create+add inside a write task
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        IBmModel bmModel = bmModelManager != null ? bmModelManager.getModel(project) : null;
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available").toJson(); //$NON-NLS-1$
        }

        // 3.8.4: track inner-form creation for CommonForm
        AtomicReference<String> innerFormFqn = new AtomicReference<>(null);
        AtomicReference<String> innerFormCreateError = new AtomicReference<>(null);
        // Expose synonym outcome to the agent (no silent skip on best-effort failure).
        AtomicReference<EditMetadataTool.SynonymResult> synonymRef = new AtomicReference<>(EditMetadataTool.SynonymResult.skipped());

        StringBuilder finalErr = new StringBuilder();
        try
        {
            // EDT 2026.1 top-object attach pattern: use IBmGlobalEditingContext.execute()
            // and call IBmTransaction.attachTopObject(IBmObject, fqn) to register the new
            // object in BM. This mirrors the path used by ConfigurationProjectManager and
            // ExtensionProjectManager when attaching the root Configuration. The previous
            // path (bmModel.execute() + getCatalogs().add() only) worked in regular
            // configurations through an implicit hook, but failed in extension projects
            // with "Failed to persist reference value" because that hook does not fire
            // on extension Configuration containers.
            bmModel.getGlobalContext().execute((IBmTask) new AbstractBmTask<Void>("edit_metadata.createObject") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    MdObject created = BmObjectHelper.createGenericObject(englishType);
                    if (created == null)
                    {
                        finalErr.append("Cannot create '" + englishType //$NON-NLS-1$
                            + "' - neither MdClassFactory.create" + englishType //$NON-NLS-1$
                            + "() nor MdClassPackage.eINSTANCE.get" + englishType //$NON-NLS-1$
                            + "() resolves on this EDT runtime."); //$NON-NLS-1$
                        return null;
                    }
                    created.setName(name);
                    // Auto-fill synonym (explicit or generated from the name) like
                    // the EDT wizard - createObject previously left it empty, which
                    // produced synonym-less EventSubscriptions / Catalogs / etc.
                    synonymRef.set(EditMetadataTool.applyMdObjectSynonym(created, synonym, name, project));
                    if (isCommonModule)
                    {
                        applyCommonModuleFlags(created, cmPrivileged, cmGlobal, cmServer,
                            cmExternalConnection, cmClientOrdinaryApplication, cmServerCall);
                    }
                    // Applied BEFORE the object joins the configuration and before it is attached
                    // as a top object. A property that cannot be written then stops the call while
                    // the object exists nowhere but in this transaction, so a refusal leaves no
                    // half-made object behind - which is what "the whole request or none of it"
                    // means here. A scheduled job created with a name and nothing else is the case
                    // this exists for: it fails EDT's own check the moment it appears.
                    for (Map.Entry<String, String> property : remaining.entrySet())
                    {
                        String refused =
                            BmObjectHelper.setProperty(created, property.getKey(), property.getValue());
                        if (refused != null)
                        {
                            throw new RuntimeException(refused + " Nothing was created."); //$NON-NLS-1$
                        }
                        applied.add(property.getKey());
                    }
                    if (!BmObjectHelper.addToConfiguration(config, created))
                    {
                        finalErr.append("Created object but failed to attach it to the configuration. " //$NON-NLS-1$
                            + "Configuration may not have the matching collection for this type."); //$NON-NLS-1$
                        return null;
                    }
                    // Register the new object as a top-object so BM can persist a
                    // new .mdo file for it (extension projects require this explicit
                    // attach; a regular config worked through an implicit hook).
                    String fqn = englishType + "." + name; //$NON-NLS-1$
                    tx.attachTopObject((IBmObject) created, fqn);
                    // 3.8.4 + issue #15: a CommonForm needs its inner Form built AND
                    // registered as its OWN top-object in the same transaction, AFTER
                    // the wrapper has a namespace. The inner Form is a separate
                    // non-containment top-object (like a report's DCS schema); without
                    // the explicit attach an EXTENSION-project commit fails with
                    // "Failed to persist reference value FormImpl". The create_form path
                    // for regular owners already attaches it, which is why a CommonForm
                    // could previously only be built via a carrier object.
                    if ("CommonForm".equals(englishType)) //$NON-NLS-1$
                    {
                        BmCommonFormPostCreate.PostCreateResult pcr
                            = BmCommonFormPostCreate.createInnerForm(created);
                        if (pcr.ok && pcr.innerFormFqn != null)
                        {
                            innerFormFqn.set(pcr.innerFormFqn);
                            if (pcr.innerForm instanceof IBmObject)
                            {
                                try
                                {
                                    tx.attachTopObject((IBmObject) pcr.innerForm, pcr.innerFormFqn);
                                }
                                catch (Exception innerAttachEx)
                                {
                                    Activator.logWarning("CommonForm inner-form attachTopObject(" //$NON-NLS-1$
                                        + pcr.innerFormFqn + ") failed: " //$NON-NLS-1$
                                        + innerAttachEx.getMessage());
                                }
                            }
                        }
                        else if (pcr.error != null)
                        {
                            Activator.logWarning("CommonForm createObject: " + pcr.error); //$NON-NLS-1$
                            innerFormCreateError.set(pcr.error);
                        }
                    }
                    if (dryRun)
                    {
                        // abort to discard
                        throw new RuntimeException("__DRY_RUN__"); //$NON-NLS-1$
                    }
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            if (!"__DRY_RUN__".equals(e.getCause() != null ? e.getCause().getMessage() //$NON-NLS-1$
                : e.getMessage()))
            {
                return ToolResult.error("createObject failed: " //$NON-NLS-1$
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()))
                    .toJson();
            }
        }
        if (finalErr.length() > 0)
        {
            return ToolResult.error(finalErr.toString()).toJson();
        }
        ToolResult ok = ToolResult.success()
            .put("operation", "create_object") //$NON-NLS-1$ //$NON-NLS-2$
            .put("objectType", englishType) //$NON-NLS-1$
            .put("name", name) //$NON-NLS-1$
            .put("propertiesApplied", applied) //$NON-NLS-1$
            .put("dryRun", dryRun) //$NON-NLS-1$
            .put("message", dryRun //$NON-NLS-1$
                ? "Dry run: " + englishType + "." + name + " would be created." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : englishType + "." + name + " created."); //$NON-NLS-1$ //$NON-NLS-2$
        // Expose synonym outcome so the agent does not have to guess - explicit
        // value, auto-generated value, or a setter failure are all reported.
        EditMetadataTool.SynonymResult sr = synonymRef.get();
        if (sr.applied)
        {
            ok.put("synonym", sr.value).put("synonymApplied", true); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (sr.error != null)
        {
            ok.put("synonymApplied", false); //$NON-NLS-1$
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("reason", sr.error); //$NON-NLS-1$
            ok.put("synonymNotSet", reason); //$NON-NLS-1$
        }
        // Audit B2/G10: surface a failed CommonForm inner-form creation (was only
        // logged). Without the inner Form the new .mdo opens as a blank form, so
        // the agent must know it needs to retry rather than see success:true.
        if (innerFormCreateError.get() != null)
        {
            ok.put("innerFormCreateWarning", innerFormCreateError.get()); //$NON-NLS-1$
        }
        // CommonForm completeness: the create_object path builds AND attaches the
        // inner Form in BM (defensive layer 3.8.4 + issue #15), but the OWNER
        // forceExport writes only the .mdo, not the inner Form top-object. Mirror
        // opCreateForm's Step C: force-export the inner Form FQN so Form.form holds
        // the built FormBaseSetup content (not a bare <form:Form/> stub EDT flags
        // as invalid), then ensure Module.bsl. Fall back to an empty stub only when
        // forceExport could not target the inner form. dryRun rolled back the txn.
        if ("CommonForm".equals(englishType) && !dryRun) //$NON-NLS-1$
        {
            String cfInnerFormFqn = "CommonForm." + name + ".Form"; //$NON-NLS-1$ //$NON-NLS-2$
            ok.put("innerFormFqn", cfInnerFormFqn); //$NON-NLS-1$
            boolean cfSerialized = false;
            try
            {
                IBmModelManager cfBmm = Activator.getDefault().getBmModelManager();
                if (cfBmm != null)
                {
                    ru.aiedt.mcp.server.support.BmExportHelper.Result cfExp =
                        ru.aiedt.mcp.server.support.BmExportHelper.forceExportAndWait(
                            cfBmm, project, cfInnerFormFqn);
                    // Keep cfSerialized on isOk() even when the flush is pending:
                    // the form WAS exported (the save finishes in the background),
                    // and the false branch would write an EMPTY stub that clobbers
                    // the serialized form (see below). Only surface the pending
                    // state for transparency (Row 42).
                    cfSerialized = cfExp != null && cfExp.isOk();
                    if (cfExp != null && cfExp.syncFlushPending)
                    {
                        ok.put("diskFlushPending", Boolean.TRUE); //$NON-NLS-1$
                    }
                }
            }
            catch (Exception cfExpEx)
            {
                Activator.logWarning("create_object CommonForm forceExport(" //$NON-NLS-1$
                    + cfInnerFormFqn + "): " + cfExpEx.getMessage()); //$NON-NLS-1$
            }
            if (cfSerialized)
            {
                // Form.form now holds the BM content - write only Module.bsl next
                // to it (an empty-form stub here would clobber the serialized form).
                String moduleErr =
                    BmFormResourceHelper.writeModuleResourceOnly(project, "CommonForm." + name, name); //$NON-NLS-1$
                if (moduleErr != null)
                {
                    Activator.logWarning("create_object CommonForm Module.bsl for " //$NON-NLS-1$
                        + name + ": " + moduleErr); //$NON-NLS-1$
                    ok.put("formResourceInitWarning", moduleErr); //$NON-NLS-1$
                }
            }
            else
            {
                // Inner form not force-exportable - fall back to a minimal stub so
                // the form at least exists on disk for follow-up edits.
                String resErr = BmFormResourceHelper.writeEmptyFormResources(
                    project, "CommonForm." + name, name); //$NON-NLS-1$
                if (resErr != null)
                {
                    Activator.logWarning("create_object CommonForm resource init for " //$NON-NLS-1$
                        + name + ": " + resErr); //$NON-NLS-1$
                    ok.put("formResourceInitWarning", resErr); //$NON-NLS-1$
                }
            }
        }
        // Subsystem completeness: a subsystem with includeInCommandInterface=true
        // (the EDT default) needs a CommandInterface.cmi next to its .mdo. BM
        // persists the .mdo but not the .cmi, so the incremental configuration
        // export to the infobase fails with "Файл не обнаружен
        // 'zip:///...CommandInterface.xml'" even though get_project_errors stays
        // clean (the validator inspects the model, not the export). Write a
        // minimal empty command interface post-commit (harmless when
        // includeInCommandInterface=false). Mirrors the CommonForm block above.
        if ("Subsystem".equals(englishType) && !dryRun) //$NON-NLS-1$
        {
            String ciErr = BmSubsystemHelper.writeEmptyCommandInterface(project, name);
            if (ciErr != null)
            {
                Activator.logWarning("create_object Subsystem command-interface init for " //$NON-NLS-1$
                    + name + ": " + ciErr); //$NON-NLS-1$
                ok.put("commandInterfaceInitWarning", ciErr); //$NON-NLS-1$
            }
            else
            {
                ok.put("commandInterfaceCreated", true); //$NON-NLS-1$
            }
        }
        return ok.toJson();
    }
    String opSetObjectProperty(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String propertyName = JsonUtils.extractStringArgument(params, "propertyName"); //$NON-NLS-1$
        String propertyValue = JsonUtils.extractStringArgument(params, "propertyValue"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(propertyName, "propertyName"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        // Child-FQN support: when ownerFqn addresses a child element
        // (Type.Name.<Kind>.<ChildName>[.<Kind>.<ChildName>...] - e.g.
        // Document.X.Attribute.Y, Document.X.TabularSection.T.Attribute.Y,
        // InformationRegister.X.Dimension.D), set the property on that child.
        // executeWriteOnObject only resolves the TOP object, so we resolve the
        // top here and navigate to the child inside the transaction. This makes
        // per-attribute properties (fillChecking / fullTextSearch / toolTip /
        // indexing / ...) settable, which were previously "Owner not found".
        final String[] segs = MetadataTypeCatalog.normalizeFqn(ownerFqn).split("\\."); //$NON-NLS-1$
        final boolean childTarget = segs.length > 2 && BmObjectHelper.isChildKind(segs[2]);
        final String topFqn = childTarget ? segs[0] + "." + segs[1] : ownerFqn; //$NON-NLS-1$
        final String childPath = childTarget
            ? String.join(".", java.util.Arrays.copyOfRange(segs, 2, segs.length)) : null; //$NON-NLS-1$
        // inputByString needs project context (to resolve Field cross-references
        // by FQN) and populates a list-valued property - handled specially, not
        // through the scalar setProperty path.
        final boolean isInputByString = "inputByString".equalsIgnoreCase(propertyName); //$NON-NLS-1$
        final List<String> ibsResolved = new ArrayList<>();
        final List<String> ibsUnresolved = new ArrayList<>();
        final List<String> ibsDiag = new ArrayList<>();
        // choiceParameters / choiceParameterLinks: structured list-valued props
        // whose items are {name,value} / {name,field} - parsed from a JSON-array
        // propertyValue and applied to the (child) attribute target.
        final boolean isChoiceParams = "choiceParameters".equalsIgnoreCase(propertyName); //$NON-NLS-1$
        final boolean isChoiceLinks = "choiceParameterLinks".equalsIgnoreCase(propertyName); //$NON-NLS-1$
        // synonym: a MultiLanguageText (EMap<lang,text>), not a plain scalar. A
        // JSON object {"ru":"...","en":"..."} replaces the whole map; a plain
        // string fills the default language; empty clears. The generic reflective
        // setProperty cannot reach getSynonym(), so this is a dedicated branch.
        final boolean isSynonym = "synonym".equalsIgnoreCase(propertyName); //$NON-NLS-1$
        final EditMetadataTool.SynonymResult[] synonymOut = new EditMetadataTool.SynonymResult[1];
        final List<java.util.Map<String, String>> choiceItems =
            (isChoiceParams || isChoiceLinks) ? EditMetadataTool.parseStructArray(propertyValue) : null;
        final List<String> choiceApplied = new ArrayList<>();
        final List<String> choiceUnresolved = new ArrayList<>();
        final List<String> choiceDiag = new ArrayList<>();
        if ((isChoiceParams || isChoiceLinks) && (choiceItems == null || choiceItems.isEmpty()))
        {
            return ToolResult.error(propertyName + " requires propertyValue as a JSON array, e.g. " //$NON-NLS-1$
                + (isChoiceLinks ? "[{\"name\":\"Отбор.Владелец\",\"field\":\"Owner\"}]" //$NON-NLS-1$
                    : "[{\"name\":\"Отбор.ЭтоГруппа\",\"value\":\"false\"}]")).toJson(); //$NON-NLS-1$
        }

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, topFqn, dryRun,
            (tx, owner) -> {
                org.eclipse.emf.ecore.EObject target = owner;
                if (childTarget)
                {
                    target = BmObjectHelper.resolveChildByPath(owner, segs, 2);
                    if (target == null)
                    {
                        throw new RuntimeException("Child element not found: " + childPath //$NON-NLS-1$
                            + " in " + topFqn); //$NON-NLS-1$
                    }
                }
                if (isInputByString)
                {
                    String ibsErr = BmDefinedTypeHelper.applyInputByString(target, topFqn,
                        propertyValue, project, ibsResolved, ibsUnresolved, ibsDiag);
                    if (ibsErr != null)
                    {
                        throw new RuntimeException(ibsErr);
                    }
                    return "inputByString=" + propertyValue; //$NON-NLS-1$
                }
                if (isChoiceParams)
                {
                    String cpErr = BmDefinedTypeHelper.applyChoiceParameters(target, choiceItems,
                        choiceApplied, choiceDiag);
                    if (cpErr != null)
                    {
                        throw new RuntimeException(cpErr);
                    }
                    return "choiceParameters set (" + choiceApplied.size() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (isChoiceLinks)
                {
                    // owner is the FieldSource (top object); target is the (child)
                    // attribute the links are set on.
                    String cplErr = BmDefinedTypeHelper.applyChoiceParameterLinks(target, owner,
                        choiceItems, project, choiceApplied, choiceUnresolved, choiceDiag);
                    if (cplErr != null)
                    {
                        throw new RuntimeException(cplErr);
                    }
                    return "choiceParameterLinks set (" + choiceApplied.size() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (isSynonym)
                {
                    if (!(target instanceof MdObject))
                    {
                        throw new RuntimeException("synonym is only valid on metadata objects, not " //$NON-NLS-1$
                            + target.getClass().getSimpleName());
                    }
                    MdObject md = (MdObject) target;
                    if (propertyValue == null || propertyValue.trim().isEmpty())
                    {
                        md.getSynonym().clear();
                        synonymOut[0] = EditMetadataTool.SynonymResult.ok("<cleared>"); //$NON-NLS-1$
                        return "synonym=<cleared>"; //$NON-NLS-1$
                    }
                    EditMetadataTool.SynonymResult sr = EditMetadataTool.applyMdObjectSynonym(md,
                        propertyValue, null, project);
                    synonymOut[0] = sr;
                    if (sr.applied)
                    {
                        return "synonym=" + sr.value; //$NON-NLS-1$
                    }
                    throw new RuntimeException("synonym not applied" //$NON-NLS-1$
                        + (sr.error != null ? ": " + sr.error : "")); //$NON-NLS-1$ //$NON-NLS-2$
                }
                String setErr = BmObjectHelper.setProperty(target, propertyName, propertyValue);
                if (setErr != null)
                {
                    throw new RuntimeException(setErr);
                }
                return propertyName + "=" + propertyValue; //$NON-NLS-1$
            });
        if (isInputByString)
        {
            // resolved is a success-claim - only on r.ok; unresolved / diagnostics
            // stay unconditional so a failure response still explains why.
            if (r.ok && !ibsResolved.isEmpty())
            {
                r.tags.put("inputByStringResolved", ibsResolved); //$NON-NLS-1$
            }
            if (!ibsUnresolved.isEmpty())
            {
                r.tags.put("inputByStringUnresolved", ibsUnresolved); //$NON-NLS-1$
            }
            if (!ibsDiag.isEmpty())
            {
                r.tags.put("inputByStringProxyDiagnostics", ibsDiag); //$NON-NLS-1$
            }
        }
        if (isChoiceParams || isChoiceLinks)
        {
            // applied is a success-claim - only on r.ok; unresolved / diagnostics
            // stay unconditional so a failure response still explains why.
            if (r.ok && !choiceApplied.isEmpty())
            {
                r.tags.put("choiceApplied", choiceApplied); //$NON-NLS-1$
            }
            if (!choiceUnresolved.isEmpty())
            {
                r.tags.put("choiceUnresolved", choiceUnresolved); //$NON-NLS-1$
            }
            if (!choiceDiag.isEmpty())
            {
                r.tags.put("choiceDiagnostics", choiceDiag); //$NON-NLS-1$
            }
        }
        if (isSynonym)
        {
            EditMetadataTool.addSynonymTags(r, synonymOut[0]);
        }
        return EditMetadataTool.formatResult(r, "set_object_property"); //$NON-NLS-1$
    }
    /**
     * 1.43.x audit A2: adds or removes an entry in a list-valued reference
     * property of a metadata object - collections that expose a {@code getXxx()}
     * EList of references to other top-level objects but no setter. Examples:
     * <ul>
     *   <li>{@code property=registerRecords} on a Document - the registers the
     *       document posts movements to (e.g. valueFqn=AccumulationRegister.X)</li>
     *   <li>{@code property=owners} on a Catalog - subordinate catalog owners</li>
     *   <li>{@code property=basedOn} on a ChartOfCharacteristicTypes</li>
     *   <li>{@code property=extDimensionTypes} on a ChartOfAccounts</li>
     * </ul>
     * The {@code valueFqn} is resolved to a top object inside the same BM
     * transaction. Idempotent: an already-present add / already-absent remove is
     * a no-op reported via {@code idempotentSkip}. For scalar properties use
     * set_object_property; for the value type use set_object_type.
     *
     * @param add true to add the reference, false to remove it
     */
    String opObjectReference(Map<String, String> params, boolean add)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String property = JsonUtils.extractStringArgument(params, "property"); //$NON-NLS-1$
        String valueFqn = JsonUtils.extractStringArgument(params, "valueFqn"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(property, "property") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(valueFqn, "valueFqn"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        final String opLabel = add ? "add_object_reference" : "remove_object_reference"; //$NON-NLS-1$ //$NON-NLS-2$
        final String getterName = "get" + Character.toUpperCase(property.charAt(0)) //$NON-NLS-1$
            + property.substring(1);
        final String normValueFqn = MetadataTypeCatalog.normalizeFqn(valueFqn);
        final boolean[] idempotentSkip = { false };

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                Object listObj;
                try
                {
                    listObj = owner.getClass().getMethod(getterName).invoke(owner);
                }
                catch (NoSuchMethodException nsm)
                {
                    throw new RuntimeException("Owner '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no '" + property + "' collection (expected " //$NON-NLS-1$ //$NON-NLS-2$
                        + getterName + "())."); //$NON-NLS-1$
                }
                catch (java.lang.reflect.InvocationTargetException ite)
                {
                    Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                    throw new RuntimeException(getterName + "() on " //$NON-NLS-1$
                        + owner.eClass().getName() + " threw: " //$NON-NLS-1$
                        + (cause.getMessage() != null ? cause.getMessage()
                            : cause.getClass().getSimpleName()), cause);
                }
                catch (IllegalAccessException iae)
                {
                    throw new RuntimeException(getterName + "() not accessible on " //$NON-NLS-1$
                        + owner.eClass().getName(), iae);
                }
                if (!(listObj instanceof EList))
                {
                    throw new RuntimeException("Property '" + property //$NON-NLS-1$
                        + "' is not a list-valued reference" //$NON-NLS-1$
                        + (listObj == null ? "" : " (" + listObj.getClass().getSimpleName() + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + ". Use set_object_property for scalar properties."); //$NON-NLS-1$
                }
                @SuppressWarnings("unchecked")
                EList<Object> list = (EList<Object>) listObj;
                // J2: resolve child FQNs (Type.Name.Kind.Child - e.g. a
                // FunctionalOption content target Document.X.Attribute.Y) in
                // addition to top-level objects. resolveReferenceTarget tries
                // getTopObjectByFqn first, so existing top-level calls are
                // unchanged; this only lets a previously-failing child FQN
                // resolve.
                Object value = EditMetadataTool.resolveReferenceTarget(tx, normValueFqn);
                if (value == null)
                {
                    // Throw (not a side-channel) so r.ok stays false and the
                    // helper skips the post-mutation forceExport on the
                    // unchanged owner.
                    throw new RuntimeException("Referenced object not found: " + normValueFqn //$NON-NLS-1$
                        + " (valueFqn=" + valueFqn + "). Expected a top-level object FQN " //$NON-NLS-1$ //$NON-NLS-2$
                        + "(Type.Name) or a child object FQN (Type.Name.Kind.Child)."); //$NON-NLS-1$
                }
                // Guard element-type compatibility for a clear error instead of
                // a raw ArrayStoreException / a silently corrupt model (no-op
                // when the feature is not a resolvable EReference).
                EditMetadataTool.assertAssignableToRef(owner, property, value, normValueFqn);
                boolean present = list.contains(value);
                if (add)
                {
                    if (present)
                    {
                        idempotentSkip[0] = true;
                        return valueFqn;
                    }
                    list.add(value);
                }
                else
                {
                    if (!present)
                    {
                        idempotentSkip[0] = true;
                        return valueFqn;
                    }
                    list.remove(value);
                }
                return valueFqn;
            });

        if (idempotentSkip[0])
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("property", property); //$NON-NLS-1$
            idem.put("valueFqn", valueFqn); //$NON-NLS-1$
            idem.put("action", add ? "add" : "remove"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            r.tags.put("idempotentSkip", idem); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, opLabel);
    }
    /**
     * 1.43.x audit A2: sets (or clears) a SCALAR reference property whose value
     * is another top-level metadata object - e.g.
     * {@code ChartOfAccounts.extDimensionTypes} (a ChartOfCharacteristicTypes)
     * or {@code ChartOfCharacteristicTypes.characteristicExtValues} (a Catalog).
     * Distinct from add_object_reference (list-valued): a scalar reference uses
     * {@code set<Property>(EObject)} instead of a collection. The {@code valueFqn}
     * target may be a top-level object ({@code Type.Name}) or a child object
     * ({@code Type.Name.Kind.Child}) - e.g. a task's
     * {@code mainAddressingAttribute}={@code Task.X.AddressingAttribute.Y}, an
     * {@code addressingDimension}={@code InformationRegister.X.Dimension.Y}, or a
     * default form {@code Catalog.X.Form.Y} - resolved through
     * {@link #resolveReferenceTarget}.
     *
     * @param set true to set the reference to valueFqn, false to clear it (null)
     */
    String opSetObjectReference(Map<String, String> params, boolean set)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String property = JsonUtils.extractStringArgument(params, "property"); //$NON-NLS-1$
        String valueFqn = JsonUtils.extractStringArgument(params, "valueFqn"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(property, "property") //$NON-NLS-1$
            + (set ? EditMetadataTool.requireNonEmpty(valueFqn, "valueFqn") : ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        final String opLabel = set ? "set_object_reference" : "clear_object_reference"; //$NON-NLS-1$ //$NON-NLS-2$
        final String cap = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        final String getterName = "get" + cap; //$NON-NLS-1$
        final String setterName = "set" + cap; //$NON-NLS-1$
        final String normValueFqn = set ? MetadataTypeCatalog.normalizeFqn(valueFqn) : null;
        final boolean[] idempotentSkip = { false };

        // Child-FQN owner support (symmetric with set_object_property /
        // set_object_type): when ownerFqn addresses a child element
        // (Type.Name.<Kind>.<Child> - e.g. Task.X.AddressingAttribute.Y whose
        // addressingDimension is a scalar reference), resolve the top object
        // here and navigate to the child inside the transaction
        // (executeWriteOnObject resolves only the top). This makes a scalar
        // reference ON a child settable, not just a scalar reference TO a child.
        final String[] ownerSegs = MetadataTypeCatalog.normalizeFqn(ownerFqn).split("\\."); //$NON-NLS-1$
        final boolean childOwner = ownerSegs.length > 2 && BmObjectHelper.isChildKind(ownerSegs[2]);
        final String topFqn = childOwner ? ownerSegs[0] + "." + ownerSegs[1] : ownerFqn; //$NON-NLS-1$
        final String ownerChildPath = childOwner
            ? String.join(".", java.util.Arrays.copyOfRange(ownerSegs, 2, ownerSegs.length)) : null; //$NON-NLS-1$

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, topFqn, dryRun,
            (tx, owner) -> {
                EObject target = owner;
                if (childOwner)
                {
                    target = BmObjectHelper.resolveChildByPath(owner, ownerSegs, 2);
                    if (target == null)
                    {
                        throw new RuntimeException("Child element not found: " + ownerChildPath //$NON-NLS-1$
                            + " in " + topFqn); //$NON-NLS-1$
                    }
                }
                java.lang.reflect.Method setter = EditMetadataTool.findSingleArgSetter(target.getClass(), setterName);
                if (setter == null)
                {
                    throw new RuntimeException("Owner '" + target.eClass().getName() //$NON-NLS-1$
                        + "' (" + ownerFqn + ") has no scalar reference property '" + property //$NON-NLS-1$ //$NON-NLS-2$
                        + "' (expected " + setterName + "(<object>)). Use add_object_reference for " //$NON-NLS-1$ //$NON-NLS-2$
                        + "list-valued references or set_object_property for primitive values."); //$NON-NLS-1$
                }
                Class<?> paramType = setter.getParameterTypes()[0];
                if (EList.class.isAssignableFrom(paramType))
                {
                    throw new RuntimeException("Property '" + property //$NON-NLS-1$
                        + "' is list-valued - use add_object_reference / remove_object_reference."); //$NON-NLS-1$
                }
                if (paramType.isPrimitive() || paramType == String.class
                    || Number.class.isAssignableFrom(paramType) || paramType == Boolean.class)
                {
                    throw new RuntimeException("Property '" + property //$NON-NLS-1$
                        + "' is a primitive value (" + paramType.getSimpleName() //$NON-NLS-1$
                        + ") - use set_object_property."); //$NON-NLS-1$
                }
                Object current = null;
                boolean getterRead = false;
                try
                {
                    current = target.getClass().getMethod(getterName).invoke(target);
                    getterRead = true;
                }
                catch (ReflectiveOperationException ignore)
                {
                    // No readable getter (missing / threw / inaccessible) -> we
                    // cannot check idempotency, so proceed with the mutation.
                }
                if (set)
                {
                    Object value = EditMetadataTool.resolveReferenceTarget(tx, normValueFqn);
                    if (value == null)
                    {
                        throw new RuntimeException("Referenced object not found: " + normValueFqn //$NON-NLS-1$
                            + " (valueFqn=" + valueFqn + "). Expected a top-level object FQN " //$NON-NLS-1$ //$NON-NLS-2$
                            + "(Type.Name) or a child object FQN (Type.Name.Kind.Child, e.g. " //$NON-NLS-1$
                            + "Task.X.AddressingAttribute.Y)."); //$NON-NLS-1$
                    }
                    if (!paramType.isInstance(value))
                    {
                        String valueType = (value instanceof EObject)
                            ? ((EObject) value).eClass().getName() : value.getClass().getSimpleName();
                        throw new RuntimeException("Referenced object " + normValueFqn + " (type " //$NON-NLS-1$ //$NON-NLS-2$
                            + valueType + ") is not assignable to property '" + property //$NON-NLS-1$
                            + "' (expects " + paramType.getSimpleName() + ")."); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    if (current == value)
                    {
                        idempotentSkip[0] = true;
                        return valueFqn;
                    }
                    EditMetadataTool.invokeSetterClearly(setter, target, value, property);
                    return valueFqn;
                }
                if (getterRead && current == null)
                {
                    idempotentSkip[0] = true;
                    return "(cleared)"; //$NON-NLS-1$
                }
                EditMetadataTool.invokeSetterClearly(setter, target, null, property);
                return "(cleared)"; //$NON-NLS-1$
            });

        if (idempotentSkip[0])
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("property", property); //$NON-NLS-1$
            if (set)
            {
                idem.put("valueFqn", valueFqn); //$NON-NLS-1$
            }
            idem.put("action", set ? "set" : "clear"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            r.tags.put("idempotentSkip", idem); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, opLabel);
    }
    /**
     * 1.43.x audit A2: sets the value type (TypeDescription) of a top-level
     * typed object - Constant or SessionParameter (both expose
     * {@code getType()/setType(TypeDescription)}). Reuses the same
     * type-application machinery as add_object_attribute (canonical primitive
     * proxy + qualifiers), so {@code set_object_type type=String length=150}
     * works in one call. For DefinedType use set_defined_type_types (multi-type
     * composition); an object that exposes no TypeDescription returns a clear
     * error.
     */
    String opSetObjectType(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        final boolean[] typeSkipped = { false };
        BmDefinedTypeHelper.QualifierOptions qualifiers = new BmDefinedTypeHelper.QualifierOptions();
        qualifiers.length = JsonUtils.extractIntegerArgument(params, "length"); //$NON-NLS-1$
        qualifiers.precision = JsonUtils.extractIntegerArgument(params, "precision"); //$NON-NLS-1$
        qualifiers.fractionDigits = JsonUtils.extractIntegerArgument(params, "fractionDigits"); //$NON-NLS-1$
        if (params != null && params.containsKey("nonNegative")) //$NON-NLS-1$
        {
            qualifiers.nonNegative = JsonUtils.extractBooleanArgument(params, "nonNegative", false); //$NON-NLS-1$
        }
        qualifiers.dateFractions = JsonUtils.extractStringArgument(params, "dateFractions"); //$NON-NLS-1$
        qualifiers.allowedLength = JsonUtils.extractStringArgument(params, "allowedLength"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(type, "type"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        // Child-FQN support: when ownerFqn addresses a child element (e.g.
        // Catalog.X.Attribute.Y, Document.X.TabularSection.T.Attribute.Y), change
        // the TYPE of that child. executeWriteOnObject resolves only the TOP object,
        // so resolve the top here and navigate to the child inside the transaction -
        // this lets an EXISTING attribute's type be changed or extended to a
        // composite (previously "Owner not found": set_object_type was
        // top-object-only and set_object_property could not build a TypeDescription).
        final String[] segs = MetadataTypeCatalog.normalizeFqn(ownerFqn).split("\\."); //$NON-NLS-1$
        final boolean childTarget = segs.length > 2 && BmObjectHelper.isChildKind(segs[2]);
        final String topFqn = childTarget ? segs[0] + "." + segs[1] : ownerFqn; //$NON-NLS-1$

        IConfigurationProvider cfgProvider = Activator.getDefault().getConfigurationProvider();
        final Configuration config = cfgProvider != null ? cfgProvider.getConfiguration(project) : null;
        final boolean[] typeApplied = { false };
        final List<String> typeResolved = new ArrayList<>();
        final List<String> typeUnresolved = new ArrayList<>();

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, topFqn, dryRun,
            (tx, owner) -> {
                if (config == null)
                {
                    throw new RuntimeException("Configuration unavailable - type not resolved"); //$NON-NLS-1$
                }
                MdObject target = owner;
                if (childTarget)
                {
                    org.eclipse.emf.ecore.EObject child =
                        BmObjectHelper.resolveChildByPath(owner, segs, 2);
                    if (child == null)
                    {
                        throw new RuntimeException("Child element not found: " //$NON-NLS-1$
                            + String.join(".", java.util.Arrays.copyOfRange(segs, 2, segs.length)) //$NON-NLS-1$
                            + " in " + topFqn); //$NON-NLS-1$
                    }
                    if (!(child instanceof MdObject))
                    {
                        throw new RuntimeException("Target is not a typed metadata object: " //$NON-NLS-1$
                            + child.eClass().getName());
                    }
                    target = (MdObject) child;
                }
                BmDefinedTypeHelper.TypesResult tr = BmDefinedTypeHelper.setTypes(
                    target, project, config, Collections.singletonList(type), qualifiers);
                typeApplied[0] = tr.ok;
                typeSkipped[0] = tr.idempotentSkip;
                if (tr.resolved != null)
                {
                    typeResolved.addAll(tr.resolved);
                }
                if (tr.unresolved != null)
                {
                    typeUnresolved.addAll(tr.unresolved);
                }
                if (!tr.ok)
                {
                    throw new RuntimeException("type not applied: " //$NON-NLS-1$
                        + (tr.error != null ? tr.error
                            : "object exposes no TypeDescription (getType)")); //$NON-NLS-1$
                }
                return ownerFqn;
            });

        Map<String, Object> typeApply = new LinkedHashMap<>();
        typeApply.put("requested", type); //$NON-NLS-1$
        // A type that was already what was asked for is not a type this call applied. Reporting the
        // two the same way is how a request that wrote nothing read as a success.
        typeApply.put("applied", Boolean.valueOf(typeApplied[0] && !typeSkipped[0])); //$NON-NLS-1$
        if (typeSkipped[0])
        {
            typeApply.put("idempotentSkip", Boolean.TRUE); //$NON-NLS-1$
        }
        if (!typeResolved.isEmpty())
        {
            typeApply.put("resolved", typeResolved); //$NON-NLS-1$
        }
        if (!typeUnresolved.isEmpty())
        {
            typeApply.put("unresolved", typeUnresolved); //$NON-NLS-1$
        }
        r.tags.put("typeApplication", typeApply); //$NON-NLS-1$
        return EditMetadataTool.formatResult(r, "set_object_type"); //$NON-NLS-1$
    }

    /**
     * Adds types to an object an extension has adopted, leaving the inherited ones alone.
     * <p>
     * An adopted object keeps its types in the extension block rather than in a type
     * property of its own, so setting a type there writes to nothing: measured on a clean
     * probe, {@code set_object_type} on an adopted DefinedType answered
     * {@code applied:true} and the file did not change. This writes where the types
     * actually live, marks each added one {@code Extended}, and reports the inherited ones
     * it left untouched.
     * </p>
     * <p>
     * Addresses the object itself ({@code DefinedType.Name}) or an adopted child
     * ({@code Catalog.Name.Attribute.Other}), and refuses a type whose name the platform
     * does not know, so a misspelling cannot reach the model.
     * </p>
     *
     * @param params projectName, ownerFqn, type (one name or a comma-separated list),
     *            optional dryRun.
     * @return the tool result, carrying what was added, what was already there, and which
     *         extension block was written.
     */
    String opExtendObjectType(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(type, "type"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }

        final String[] segs = MetadataTypeCatalog.normalizeFqn(ownerFqn).split("\\."); //$NON-NLS-1$
        final boolean childTarget = segs.length > 2 && BmObjectHelper.isChildKind(segs[2]);
        final String topFqn = childTarget ? segs[0] + "." + segs[1] : ownerFqn; //$NON-NLS-1$

        IConfigurationProvider cfgProvider = Activator.getDefault().getConfigurationProvider();
        final Configuration config = cfgProvider != null ? cfgProvider.getConfiguration(project) : null;
        final BmExtensionTypeHelper.ExtendResult[] outcome = { null };

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, topFqn, dryRun,
            (tx, owner) -> {
                if (config == null)
                {
                    throw new RuntimeException("Configuration unavailable - type not resolved"); //$NON-NLS-1$
                }
                MdObject target = owner;
                if (childTarget)
                {
                    org.eclipse.emf.ecore.EObject child =
                        BmObjectHelper.resolveChildByPath(owner, segs, 2);
                    if (child == null)
                    {
                        throw new RuntimeException("Child element not found: " //$NON-NLS-1$
                            + String.join(".", java.util.Arrays.copyOfRange(segs, 2, segs.length)) //$NON-NLS-1$
                            + " in " + topFqn); //$NON-NLS-1$
                    }
                    if (!(child instanceof MdObject))
                    {
                        throw new RuntimeException("Target is not a typed metadata object: " //$NON-NLS-1$
                            + child.eClass().getName());
                    }
                    target = (MdObject)child;
                }
                BmExtensionTypeHelper.ExtendResult ext = BmExtensionTypeHelper.extendTypes(
                    target, project, config, Collections.singletonList(type));
                outcome[0] = ext;
                if (!ext.ok)
                {
                    throw new RuntimeException(ext.error != null ? ext.error
                        : "type not extended"); //$NON-NLS-1$
                }
                return ownerFqn;
            });

        if (outcome[0] != null)
        {
            BmExtensionTypeHelper.ExtendResult ext = outcome[0];
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("requested", type); //$NON-NLS-1$
            // A preview rolls the transaction back, so nothing was written however far the pass
            // got. Reporting its intent as a change is how a dry run reads as a done deal.
            tag.put("mutated", ext.mutated && !dryRun); //$NON-NLS-1$
            if (!ext.added.isEmpty())
            {
                tag.put(dryRun ? "wouldAdd" : "added", ext.added); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!ext.alreadyPresent.isEmpty())
            {
                tag.put("alreadyPresent", ext.alreadyPresent); //$NON-NLS-1$
            }
            if (!ext.unresolved.isEmpty())
            {
                tag.put("unresolved", ext.unresolved); //$NON-NLS-1$
            }
            if (ext.blockKind != null)
            {
                tag.put("extensionBlock", ext.blockKind); //$NON-NLS-1$
            }
            if (ext.propertyState != null)
            {
                // Reported, never set. On the shape measured live the composition alone
                // carries the override and this stays unset; writing it on a guess is how
                // a half-applied change would look applied.
                tag.put("blockTypeState", ext.propertyState); //$NON-NLS-1$
            }
            r.tags.put("typeExtension", tag); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, "extend_object_type"); //$NON-NLS-1$
    }
    String opAddObjectAttribute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        String synonymArg = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        final String synonym = (synonymArg != null) ? synonymArg.trim() : null;
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        boolean autoBorrow = JsonUtils.extractBooleanArgument(params, "auto_borrow", true); //$NON-NLS-1$
        // 1.42.3: minimal qualifier (multiLine) - applied via setProperty
        // after type composition.
        boolean multiLine = JsonUtils.extractBooleanArgument(params, "multiLine", false); //$NON-NLS-1$
        boolean multiLineProvided = params != null && params.containsKey("multiLine"); //$NON-NLS-1$
        // 1.42.5: full TypeDescription qualifier wiring through QualifierOptions
        // so add_object_attribute can express String(150), Number(15,2),
        // Date(DateTime), nonNegative numbers, etc. without follow-up
        // setObjectProperty calls.
        BmDefinedTypeHelper.QualifierOptions attrQualifiers = new BmDefinedTypeHelper.QualifierOptions();
        Integer lengthArg = JsonUtils.extractIntegerArgument(params, "length"); //$NON-NLS-1$
        Integer precisionArg = JsonUtils.extractIntegerArgument(params, "precision"); //$NON-NLS-1$
        Integer fractionDigitsArg = JsonUtils.extractIntegerArgument(params, "fractionDigits"); //$NON-NLS-1$
        attrQualifiers.length = lengthArg;
        attrQualifiers.precision = precisionArg;
        attrQualifiers.fractionDigits = fractionDigitsArg;
        if (params != null && params.containsKey("nonNegative")) //$NON-NLS-1$
        {
            attrQualifiers.nonNegative = JsonUtils.extractBooleanArgument(params, "nonNegative", false); //$NON-NLS-1$
        }
        attrQualifiers.dateFractions = JsonUtils.extractStringArgument(params, "dateFractions"); //$NON-NLS-1$
        attrQualifiers.allowedLength = JsonUtils.extractStringArgument(params, "allowedLength"); //$NON-NLS-1$

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

        // Phase 6.6: auto-borrow referenced metadata objects when the attribute
        // type is a reference (CatalogRef.X / DocumentRef.X / etc.) inside an
        // extension project. Best-effort - failures surface as warning tags.
        java.util.List<String> autoBorrowed = new java.util.ArrayList<>();
        java.util.List<Map<String, Object>> autoBorrowSkipped = new java.util.ArrayList<>();
        // #2 (RSV 5.10 parity): auto-borrow the OWNER itself. In an extension project
        // executeWriteOnObject resolves ownerFqn only within the extension's own BM namespace,
        // so adding an attribute to a base object that was never borrowed would fail with
        // "Owner not found". Borrowing the owner first (idempotent - attemptBorrow short-circuits
        // when already adopted / extension-own) lets the write proceed. Skipped for the
        // Configuration-root sentinel. Best-effort: a failure is recorded, not fatal.
        maybeAutoBorrowOwner(project, ownerFqn, autoBorrow, dryRun, autoBorrowed, autoBorrowSkipped);
        if (type != null && !type.isEmpty() && BmDcsHelper.isExtensionProject(project))
        {
            for (String targetFqn : extractReferenceTargetFqns(type))
            {
                if (autoBorrow)
                {
                    BmExtensionHelper.BorrowResult br = BmExtensionHelper.attemptBorrow(project,
                        null, targetFqn, null);
                    if (br.ok)
                    {
                        autoBorrowed.add(targetFqn);
                    }
                    else
                    {
                        Map<String, Object> sk = new LinkedHashMap<>();
                        sk.put("targetFqn", targetFqn); //$NON-NLS-1$
                        sk.put("reason", br.error != null ? br.error : "unknown"); //$NON-NLS-1$ //$NON-NLS-2$
                        autoBorrowSkipped.add(sk);
                    }
                }
                else
                {
                    Map<String, Object> sk = new LinkedHashMap<>();
                    sk.put("targetFqn", targetFqn); //$NON-NLS-1$
                    sk.put("reason", "auto_borrow=false"); //$NON-NLS-1$ //$NON-NLS-2$
                    autoBorrowSkipped.add(sk);
                }
            }
        }

        // Capture configuration for type application inside the BM transaction.
        IConfigurationProvider attrConfigProvider = Activator.getDefault().getConfigurationProvider();
        final Configuration attrConfig = attrConfigProvider != null
            ? attrConfigProvider.getConfiguration(project) : null;
        // Lambda needs effectively-final containers to surface type-application
        // outcome back to the caller (visibility for BUG-2 troubleshooting).
        final boolean[] typeAppliedFlag = { false };
        final String[] typeApplyErrorRef = { null };
        final List<String> typeResolved = new ArrayList<>();
        final List<String> typeUnresolved = new ArrayList<>();
        // Idempotency outcome for the existing-attribute case.
        final boolean[] idempotentSkipFlag = { false };
        final Map<String, Object> propertyMismatchData = new LinkedHashMap<>();
        // Synonym outcome surfaced to the caller via r.tags (no silent skip
        // when the EMap setter declines the value).
        AtomicReference<EditMetadataTool.SynonymResult> synonymRef = new AtomicReference<>(EditMetadataTool.SynonymResult.skipped());
        // Optional per-attribute properties (fillChecking / fullTextSearch /
        // indexing / toolTip / comment) applied in the same call - collected for
        // the appliedProperties / failedProperties tags. Applied both to a newly
        // created attribute and, on the idempotent path, to an existing one.
        final List<String> appliedFeatureProps = new ArrayList<>();
        final Map<String, String> failedFeatureProps = new LinkedHashMap<>();
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> attrs = BmObjectHelper.getAttributes(owner);
                if (attrs == null)
                {
                    throw new RuntimeException("Unsupported owner type '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no Attributes collection. " //$NON-NLS-1$
                        + "Use add_metadata_attribute or addRegisterField for registers."); //$NON-NLS-1$
                }
                MdObject existingAttr = BmObjectHelper.findByName(attrs, name);
                if (existingAttr != null)
                {
                    // 1.42.3: idempotency support. When the same name is
                    // requested with the same type, return success with an
                    // idempotentSkip tag (the type is left untouched). Any
                    // supplied feature properties are still applied to the
                    // existing attribute so a re-call that adds e.g.
                    // fillChecking is not a silent no-op (declarative
                    // reconciliation). When the type differs, surface a
                    // propertyMismatch tag so the caller can branch (use
                    // setObjectProperty / removeObject / pick a different name)
                    // instead of blindly retrying.
                    if (type == null || type.isEmpty())
                    {
                        // No type requested - any same-name existing
                        // attribute counts as idempotent.
                        idempotentSkipFlag[0] = true;
                        EditMetadataTool.applyAttributeFeatureProperties(existingAttr, params,
                            "add_object_attribute", appliedFeatureProps, failedFeatureProps); //$NON-NLS-1$
                        return name;
                    }
                    BmDefinedTypeHelper.TypeComparison cmp = BmDefinedTypeHelper
                        .compareTypeNames(existingAttr,
                            Collections.singletonList(type));
                    if (cmp == BmDefinedTypeHelper.TypeComparison.MATCH)
                    {
                        idempotentSkipFlag[0] = true;
                        EditMetadataTool.applyAttributeFeatureProperties(existingAttr, params,
                            "add_object_attribute", appliedFeatureProps, failedFeatureProps); //$NON-NLS-1$
                        return name;
                    }
                    // MISMATCH or NOT_RESOLVED - report the existing set so
                    // the caller can pick the right follow-up.
                    Set<String> existingTypes = BmDefinedTypeHelper
                        .readExistingTypeNames(existingAttr);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("name", name); //$NON-NLS-1$
                    data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
                    data.put("kind", "attribute"); //$NON-NLS-1$ //$NON-NLS-2$
                    data.put("requestedType", type); //$NON-NLS-1$
                    data.put("existingTypes", new ArrayList<>(existingTypes)); //$NON-NLS-1$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "Attribute already exists with a different type: " + name //$NON-NLS-1$
                            + " (requested=" + type + ", existing=" + existingTypes + ")", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "Use set_object_type (ownerFqn=" + ownerFqn + ".Attribute." + name //$NON-NLS-1$ //$NON-NLS-2$
                            + ", type=" + type + ") to change / extend the existing type, " //$NON-NLS-1$ //$NON-NLS-2$
                            + "or removeObjectAttribute first.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.PROPERTY_MISMATCH.wire(), data)));
                }
                MdObject attribute = BmObjectHelper.createOwnerScopedObject(owner, "Attribute"); //$NON-NLS-1$
                if (attribute == null)
                {
                    throw new RuntimeException("Cannot create attribute under '" //$NON-NLS-1$
                        + owner.eClass().getName()
                        + "': no compatible MdClassFactory method found " //$NON-NLS-1$
                        + "(tried create" + owner.eClass().getName() + "Attribute, " //$NON-NLS-1$ //$NON-NLS-2$
                        + "createAttribute, MdClassPackage EClass lookup)."); //$NON-NLS-1$
                }
                attribute.setName(name);
                synonymRef.set(EditMetadataTool.applyMdObjectSynonym(attribute, synonym, name, project));
                attrs.add(attribute);
                // Apply type composition through the same TypeDescription helper
                // used by setDefinedTypeTypes - it works on any MdObject that
                // exposes a TypeDescription via getType()/getTypes()/getTypeDescription().
                // Failure is non-fatal: the attribute still exists, type is left
                // empty so the caller can investigate.
                // attrConfig may be null for external-object projects (.epf/.erf,
                // no Configuration): setTypes still resolves primitive types via
                // the project-aware canonical proxy; reference types surface as
                // unresolved rather than NPE-ing (no direct config dereference).
                if (type != null && !type.isEmpty())
                {
                    try
                    {
                        // 1.42.5 BUG-1424-A canonical proxy + full qualifier
                        // wiring (length / precision / fractionDigits /
                        // dateFractions / nonNegative).
                        IDtProject attrDtProject = Activator.getDefault()
                            .getDtProjectManager().getDtProject(project);
                        BmDefinedTypeHelper.TypesResult tr = BmDefinedTypeHelper.setTypes(
                            attribute, attrDtProject, attrConfig,
                            Collections.singletonList(type), attrQualifiers);
                        typeAppliedFlag[0] = tr.ok;
                        if (tr.resolved != null)
                        {
                            typeResolved.addAll(tr.resolved);
                        }
                        if (tr.unresolved != null)
                        {
                            typeUnresolved.addAll(tr.unresolved);
                        }
                        if (!tr.ok)
                        {
                            typeApplyErrorRef[0] = tr.error;
                            Activator.logWarning("addObjectAttribute: type='" + type //$NON-NLS-1$
                                + "' not applied: " + tr.error); //$NON-NLS-1$
                        }
                    }
                    catch (Exception typeEx)
                    {
                        typeApplyErrorRef[0] = typeEx.getClass().getSimpleName() + ": " //$NON-NLS-1$
                            + typeEx.getMessage();
                        Activator.logWarning("addObjectAttribute: type='" + type //$NON-NLS-1$
                            + "' threw: " + typeEx.getMessage()); //$NON-NLS-1$
                    }
                }
                // 1.42.3: minimal qualifier - multiLine on the attribute.
                // BasicFeature.setMultiLine(boolean) is a direct setter
                // (not a TypeDescription qualifier), so it works for any
                // attribute regardless of the underlying type. Other
                // qualifiers (length / precision / nonNegative /
                // dateFractions) require StringQualifiers /
                // NumberQualifiers / DateQualifiers construction and ship
                // in 1.43.
                if (multiLineProvided)
                {
                    String mlErr = BmObjectHelper.setProperty(attribute, "multiLine", //$NON-NLS-1$
                        multiLine);
                    if (mlErr != null)
                    {
                        Activator.logWarning("addObjectAttribute: multiLine=" //$NON-NLS-1$
                            + multiLine + " not applied: " + mlErr); //$NON-NLS-1$
                    }
                }
                EditMetadataTool.applyAttributeFeatureProperties(attribute, params,
                    "add_object_attribute", appliedFeatureProps, failedFeatureProps); //$NON-NLS-1$
                return name;
            },
            owner -> {
                // Supplier lock guard runs automatically inside the helper.
                // Caller-specific: standard attribute name collision.
                MetadataGuards.Verdict conflict = MetadataGuards
                    .checkStandardAttributeConflict(owner, name);
                if (conflict.blocked)
                {
                    throw new MetadataGuards.BlockedGuardException(conflict);
                }
            }, autoBorrow);
        // Surface auto-borrow telemetry into the response tags
        if (!autoBorrowed.isEmpty())
        {
            // Dedupe: the owner-borrow and the type-target loop can both surface the same FQN
            // (e.g. an attribute typed as a reference to its own owner). Order-preserving.
            // C5: on a failed action the eager borrow did not persist (forceExport is gated
            // on r.ok), so do not claim a successful adopt - relabel to
            // autoBorrowedButActionFailed, mirroring executeWriteOnObject's lazy-path tag.
            r.tags.put(r.ok ? "autoBorrowed" : "autoBorrowedButActionFailed", //$NON-NLS-1$ //$NON-NLS-2$
                new ArrayList<>(new java.util.LinkedHashSet<>(autoBorrowed)));
        }
        if (!autoBorrowSkipped.isEmpty())
        {
            r.tags.put("autoBorrowSkipped", autoBorrowSkipped); //$NON-NLS-1$
        }
        // Surface feature-prop outcome only on a successful (or idempotent-success,
        // which also sets r.ok) result - never claim appliedProperties on an error
        // response whose transaction rolled back after the helper ran.
        if (r.ok)
        {
            if (!appliedFeatureProps.isEmpty())
            {
                r.tags.put("appliedProperties", appliedFeatureProps); //$NON-NLS-1$
            }
            if (!failedFeatureProps.isEmpty())
            {
                r.tags.put("failedProperties", failedFeatureProps); //$NON-NLS-1$
            }
        }
        // 1.42.3: idempotency outcome - "no-op success" when the attribute
        // already exists with the requested type. Distinct from the
        // propertyMismatch path which throws above.
        if (idempotentSkipFlag[0])
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("name", name); //$NON-NLS-1$
            idem.put("ownerFqn", ownerFqn); //$NON-NLS-1$
            idem.put("kind", "attribute"); //$NON-NLS-1$ //$NON-NLS-2$
            if (type != null && !type.isEmpty())
            {
                idem.put("type", type); //$NON-NLS-1$
            }
            r.tags.put("idempotentSkip", idem); //$NON-NLS-1$
        }
        // Surface type-application outcome so the caller sees whether `type`
        // actually landed on the new attribute. Without this the caller has
        // to rely on a follow-up get_metadata_details / disk read.
        String typeFailure = null;
        if (type != null && !type.isEmpty() && !idempotentSkipFlag[0])
        {
            Map<String, Object> typeApply = TypeApplication.tag(type, typeAppliedFlag[0],
                typeResolved, typeUnresolved, typeApplyErrorRef[0]);
            // Last-resort typo guard, for the one runtime where the platform type
            // register cannot be reached: there the old shape rule stands, which
            // accepts any capitalized ASCII word, so a mistyped type ("Stirng",
            // "Numer") IS applied as an unresolved type while "applied" reads true.
            // Where the register answers, such a name is rejected outright and never
            // gets here - hence the guard on typeApplied: warning about a name that
            // was refused would contradict the refusal.
            if (typeAppliedFlag[0] && BmDefinedTypeHelper.isUnrecognizedPrimitive(type))
            {
                typeApply.put("unrecognizedType", "'" + type //$NON-NLS-1$ //$NON-NLS-2$
                    + "' is not a recognized primitive (String / Number / Date / Boolean / " //$NON-NLS-1$
                    + "UUID / ValueStorage). It was applied as an unresolved type - check the " //$NON-NLS-1$
                    + "spelling, or use a reference type such as CatalogRef.Name."); //$NON-NLS-1$
            }
            String lenWarn = BmDefinedTypeHelper.stringLengthRestructureWarning(type, attrQualifiers);
            if (lenWarn != null)
            {
                typeApply.put("warning", lenWarn); //$NON-NLS-1$
            }
            r.tags.put("typeApplication", typeApply); //$NON-NLS-1$
            if (r.ok && TypeApplication.failed(typeAppliedFlag[0], typeUnresolved))
            {
                typeFailure = TypeApplication.failureMessage("attribute '" + name + "'", //$NON-NLS-1$ //$NON-NLS-2$
                    type, typeApplyErrorRef[0], dryRun, typeAppliedFlag[0]);
            }
        }
        // Surface synonym outcome (auto-generated value, explicit value, or
        // setter failure). Skip on idempotent existing-attribute case to avoid
        // pretending we set a synonym on an object we did not touch.
        if (!idempotentSkipFlag[0])
        {
            EditMetadataTool.addSynonymTags(r, synonymRef.get());
        }
        // After the synonym tags, never before: addSynonymTags itself returns early on a
        // failed result, and the synonym IS committed - an unapplied type does not roll
        // the write back. Demoting any earlier dropped the record of what is actually on
        // disk from the very answer meant to help fix it.
        if (typeFailure != null)
        {
            r.ok = false;
            r.error = typeFailure;
        }
        return EditMetadataTool.formatResult(r, "add_object_attribute"); //$NON-NLS-1$
    }
    /**
     * All reference-type target FQNs in a type description, which may be COMPOSITE
     * (comma-separated, e.g. {@code "CatalogRef.A,DocumentRef.B"}). Each recognised
     * segment maps to its object FQN; unrecognised / primitive segments are dropped.
     * De-duplicated, order-preserving. Fixes the prior single-{@code indexOf('.')} parse
     * that turned a composite into one malformed FQN.
     */
    private java.util.List<String> extractReferenceTargetFqns(String typeDescription)
    {
        java.util.List<String> out = new ArrayList<>();
        if (typeDescription == null || typeDescription.isEmpty())
        {
            return out;
        }
        for (String seg : typeDescription.split(",")) //$NON-NLS-1$
        {
            String fqn = refFqnForSegment(seg.trim());
            if (fqn != null && !out.contains(fqn))
            {
                out.add(fqn);
            }
        }
        return out;
    }

    /** Maps ONE reference-type token ({@code CatalogRef.X}) to its object FQN ({@code Catalog.X}), else null. */
    private String refFqnForSegment(String t)
    {
        if (t == null || t.isEmpty())
        {
            return null;
        }
        int dot = t.indexOf('.');
        if (dot <= 0)
        {
            return null;
        }
        String prefix = t.substring(0, dot);
        String name = t.substring(dot + 1);
        if (name.isEmpty())
        {
            return null;
        }
        switch (prefix)
        {
            case "CatalogRef": return "Catalog." + name; //$NON-NLS-1$ //$NON-NLS-2$
            case "DocumentRef": return "Document." + name; //$NON-NLS-1$ //$NON-NLS-2$
            case "EnumRef": return "Enumeration." + name; //$NON-NLS-1$ //$NON-NLS-2$
            case "ChartOfAccountsRef": return "ChartOfAccounts." + name; //$NON-NLS-1$ //$NON-NLS-2$
            case "ChartOfCalculationTypesRef": //$NON-NLS-1$
                return "ChartOfCalculationTypes." + name; //$NON-NLS-1$
            case "ChartOfCharacteristicTypesRef": //$NON-NLS-1$
                return "ChartOfCharacteristicTypes." + name; //$NON-NLS-1$
            case "TaskRef": return "Task." + name; //$NON-NLS-1$ //$NON-NLS-2$
            case "BusinessProcessRef": return "BusinessProcess." + name; //$NON-NLS-1$ //$NON-NLS-2$
            case "ExchangePlanRef": return "ExchangePlan." + name; //$NON-NLS-1$ //$NON-NLS-2$
            default: return null;
        }
    }
    /**
     * #2 (RSV 5.10 parity): best-effort borrow of {@code ownerFqn} into an extension project
     * before an owner-scoped write. No-op outside an extension, when {@code autoBorrow} is off,
     * or for the {@code Configuration}-root sentinel. A fresh borrow is added to
     * {@code autoBorrowed}; an already-adopted / extension-own owner is a silent no-op
     * ({@code BorrowResult.alreadyBorrowed}); a failure (e.g. the owner is not in the base
     * config) is recorded in {@code autoBorrowSkipped} but is NOT fatal - the subsequent write
     * still reports the real "Owner not found" when the owner truly cannot be resolved.
     */
    private void maybeAutoBorrowOwner(IProject project, String ownerFqn, boolean autoBorrow,
        boolean dryRun, java.util.List<String> autoBorrowed,
        java.util.List<Map<String, Object>> autoBorrowSkipped)
    {
        // dryRun: skip the eager owner borrow. attemptBorrow persists outside the
        // rollback-protected BM transaction, so on a dry-run preview (or a later
        // operation failure) a borrowed owner would survive and the response would
        // falsely claim autoBorrowed. The lazy path inside executeWriteOnObject is
        // separately dryRun-aware.
        if (dryRun || !autoBorrow || ownerFqn == null || ownerFqn.isEmpty()
            || "Configuration".equalsIgnoreCase(ownerFqn)) //$NON-NLS-1$
        {
            return;
        }
        if (!BmDcsHelper.isExtensionProject(project))
        {
            return;
        }
        BmExtensionHelper.BorrowResult ob =
            BmExtensionHelper.attemptBorrow(project, null, ownerFqn, null);
        if (ob.ok)
        {
            if (!ob.alreadyBorrowed)
            {
                autoBorrowed.add(ownerFqn);
            }
        }
        else
        {
            Map<String, Object> sk = new LinkedHashMap<>();
            sk.put("ownerFqn", ownerFqn); //$NON-NLS-1$
            sk.put("reason", ob.error != null ? ob.error : "unknown"); //$NON-NLS-1$ //$NON-NLS-2$
            autoBorrowSkipped.add(sk);
        }
    }
    String opRemoveObjectAttribute(Map<String, String> params)
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
                EList<MdObject> attrs = BmObjectHelper.getAttributes(owner);
                if (attrs == null)
                {
                    throw new RuntimeException("Owner has no Attributes collection"); //$NON-NLS-1$
                }
                MdObject existing = BmObjectHelper.findByName(attrs, name);
                if (existing == null)
                {
                    throw BmObjectHelper.notFound(name, ownerFqn, "attribute"); //$NON-NLS-1$
                }
                attrs.remove(existing);
                return name;
            });
        return EditMetadataTool.formatResult(r, "remove_object_attribute"); //$NON-NLS-1$
    }
    String opAddTabularSection(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        // A tabular section is a titled object like any other - 181 of the 182 in a
        // reference configuration carry a synonym - so an omitted one is generated
        // from the name rather than left blank.
        final String tsSynonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        AtomicReference<EditMetadataTool.SynonymResult> tsSynonymRef =
            new AtomicReference<>(EditMetadataTool.SynonymResult.skipped());
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
                EList<MdObject> tcs = BmObjectHelper.getTabularSections(owner);
                if (tcs == null)
                {
                    throw new RuntimeException("Unsupported owner type '" + owner.eClass().getName() //$NON-NLS-1$
                        + "' has no TabularSections collection."); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(tcs, name) != null)
                {
                    throw BmObjectHelper.alreadyExists(name, ownerFqn, "tabularSection"); //$NON-NLS-1$
                }
                MdObject ts = BmObjectHelper.createOwnerScopedObject(owner, "TabularSection"); //$NON-NLS-1$
                if (ts == null)
                {
                    throw new RuntimeException("Cannot create tabular section under '" //$NON-NLS-1$
                        + owner.eClass().getName()
                        + "': no compatible MdClassFactory method found " //$NON-NLS-1$
                        + "(tried create" + owner.eClass().getName() + "TabularSection, " //$NON-NLS-1$ //$NON-NLS-2$
                        + "MdClassPackage EClass lookup)."); //$NON-NLS-1$
                }
                ts.setName(name);
                tsSynonymRef.set(EditMetadataTool.applyMdObjectSynonym(ts, tsSynonym, name, project));
                tcs.add(ts);
                return name;
            });
        EditMetadataTool.addSynonymTags(r, tsSynonymRef.get());
        return EditMetadataTool.formatResult(r, "add_tabular_section"); //$NON-NLS-1$
    }
    String opRemoveTabularSection(Map<String, String> params)
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
                EList<MdObject> tcs = BmObjectHelper.getTabularSections(owner);
                if (tcs == null)
                {
                    throw new RuntimeException("Owner has no TabularSections collection"); //$NON-NLS-1$
                }
                MdObject existing = BmObjectHelper.findByName(tcs, name);
                if (existing == null)
                {
                    throw BmObjectHelper.notFound(name, ownerFqn, "tabularSection"); //$NON-NLS-1$
                }
                tcs.remove(existing);
                return name;
            });
        return EditMetadataTool.formatResult(r, "remove_tabular_section"); //$NON-NLS-1$
    }
    String opAddTabularSectionAttribute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        // 1.42.4 BUG-T6: schema documents `tabularSection` as alias of
        // `tabularSectionName`, but runtime previously read only the long
        // form. Accept both names.
        String tcNameRaw = JsonUtils.extractStringArgument(params, "tabularSectionName"); //$NON-NLS-1$
        final String tcName = (tcNameRaw != null && !tcNameRaw.isEmpty())
            ? tcNameRaw
            : JsonUtils.extractStringArgument(params, "tabularSection"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        final String type = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        String tcSynonymArg = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        final String tcSynonym = (tcSynonymArg != null) ? tcSynonymArg.trim() : null;
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        // 1.43.x: tabular-section attributes were created typeless (MAJOR
        // md-legacy-emf-check "Тип не указан"). Wire the same TypeDescription
        // qualifier composition the object-attribute path uses - a
        // TabularSectionAttribute is a BasicFeature with identical getType()/setType().
        final boolean multiLine = JsonUtils.extractBooleanArgument(params, "multiLine", false); //$NON-NLS-1$
        final boolean multiLineProvided = params != null && params.containsKey("multiLine"); //$NON-NLS-1$
        BmDefinedTypeHelper.QualifierOptions tcQualifiers = new BmDefinedTypeHelper.QualifierOptions();
        tcQualifiers.length = JsonUtils.extractIntegerArgument(params, "length"); //$NON-NLS-1$
        tcQualifiers.precision = JsonUtils.extractIntegerArgument(params, "precision"); //$NON-NLS-1$
        tcQualifiers.fractionDigits = JsonUtils.extractIntegerArgument(params, "fractionDigits"); //$NON-NLS-1$
        if (params != null && params.containsKey("nonNegative")) //$NON-NLS-1$
        {
            tcQualifiers.nonNegative = JsonUtils.extractBooleanArgument(params, "nonNegative", false); //$NON-NLS-1$
        }
        tcQualifiers.dateFractions = JsonUtils.extractStringArgument(params, "dateFractions"); //$NON-NLS-1$
        tcQualifiers.allowedLength = JsonUtils.extractStringArgument(params, "allowedLength"); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(tcName, "tabularSectionName") //$NON-NLS-1$
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

        // Auto-borrow the referenced metadata object when a tabular-section
        // attribute's type is a reference (CatalogRef.X / DocumentRef.X / ...)
        // inside an extension project - mirrors opAddObjectAttribute. Best-effort;
        // failures surface as tags, never block the write.
        boolean autoBorrow = JsonUtils.extractBooleanArgument(params, "auto_borrow", true); //$NON-NLS-1$
        java.util.List<String> autoBorrowed = new java.util.ArrayList<>();
        java.util.List<Map<String, Object>> autoBorrowSkipped = new java.util.ArrayList<>();
        // #2 (RSV 5.10 parity): borrow BOTH the owning object AND the tabular section itself -
        // a tabular-section attribute needs its tabular section adopted, not just the object.
        // Idempotent / best-effort (see maybeAutoBorrowOwner).
        maybeAutoBorrowOwner(project, ownerFqn, autoBorrow, dryRun, autoBorrowed, autoBorrowSkipped);
        if (ownerFqn != null && !"Configuration".equalsIgnoreCase(ownerFqn) //$NON-NLS-1$
            && tcName != null && !tcName.isEmpty())
        {
            maybeAutoBorrowOwner(project, ownerFqn + ".TabularSection." + tcName, //$NON-NLS-1$
                autoBorrow, dryRun, autoBorrowed, autoBorrowSkipped);
        }
        if (type != null && !type.isEmpty() && BmDcsHelper.isExtensionProject(project))
        {
            for (String targetFqn : extractReferenceTargetFqns(type))
            {
                if (autoBorrow)
                {
                    BmExtensionHelper.BorrowResult br = BmExtensionHelper.attemptBorrow(project,
                        null, targetFqn, null);
                    if (br.ok)
                    {
                        autoBorrowed.add(targetFqn);
                    }
                    else
                    {
                        Map<String, Object> sk = new LinkedHashMap<>();
                        sk.put("targetFqn", targetFqn); //$NON-NLS-1$
                        sk.put("reason", br.error != null ? br.error : "unknown"); //$NON-NLS-1$ //$NON-NLS-2$
                        autoBorrowSkipped.add(sk);
                    }
                }
                else
                {
                    Map<String, Object> sk = new LinkedHashMap<>();
                    sk.put("targetFqn", targetFqn); //$NON-NLS-1$
                    sk.put("reason", "auto_borrow=false"); //$NON-NLS-1$ //$NON-NLS-2$
                    autoBorrowSkipped.add(sk);
                }
            }
        }

        // Capture configuration for type application inside the BM transaction.
        IConfigurationProvider tcConfigProvider = Activator.getDefault().getConfigurationProvider();
        final Configuration tcConfig = tcConfigProvider != null
            ? tcConfigProvider.getConfiguration(project) : null;
        final boolean[] tcTypeAppliedFlag = { false };
        final String[] tcTypeApplyErrorRef = { null };
        final List<String> tcTypeResolved = new ArrayList<>();
        final List<String> tcTypeUnresolved = new ArrayList<>();

        // Synonym outcome surfaced to the caller via r.tags (no silent skip
        // when the EMap setter declines the value).
        AtomicReference<EditMetadataTool.SynonymResult> tcSynonymRef = new AtomicReference<>(EditMetadataTool.SynonymResult.skipped());
        // Optional per-attribute properties applied in the same call (mirrors
        // add_object_attribute) - collected for the appliedProperties /
        // failedProperties tags.
        final List<String> appliedFeatureProps = new ArrayList<>();
        final Map<String, String> failedFeatureProps = new LinkedHashMap<>();
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                EList<MdObject> tcs = BmObjectHelper.getTabularSections(owner);
                if (tcs == null)
                {
                    throw new RuntimeException("Owner has no TabularSections collection"); //$NON-NLS-1$
                }
                MdObject ts = BmObjectHelper.findByName(tcs, tcName);
                if (ts == null)
                {
                    throw BmObjectHelper.notFound(tcName, ownerFqn, "tabularSection"); //$NON-NLS-1$
                }
                // Standard tabular-section attribute name guard (LineNumber/НомерСтроки on
                // Document tabular parts) - the candidate must not shadow a standard one.
                MetadataGuards.Verdict tcConflict = MetadataGuards
                    .checkStandardAttributeConflict(ts, name);
                if (tcConflict.blocked)
                {
                    throw new MetadataGuards.BlockedGuardException(tcConflict);
                }
                EList<MdObject> attrs = BmObjectHelper.getAttributes(ts);
                if (attrs == null)
                {
                    throw new RuntimeException("TabularSection has no Attributes collection"); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(attrs, name) != null)
                {
                    throw BmObjectHelper.alreadyExists(name, ownerFqn + "." + tcName, //$NON-NLS-1$
                        "tabularSectionAttribute"); //$NON-NLS-1$
                }
                MdObject attribute = BmObjectHelper.createOwnerScopedObject(ts, "Attribute"); //$NON-NLS-1$
                if (attribute == null)
                {
                    throw new RuntimeException("Cannot create tabular section attribute under '" //$NON-NLS-1$
                        + ts.eClass().getName()
                        + "': no compatible MdClassFactory method found " //$NON-NLS-1$
                        + "(tried createTabularSectionAttribute and " //$NON-NLS-1$
                        + "create" + ts.eClass().getName() + "Attribute)."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                attribute.setName(name);
                tcSynonymRef.set(EditMetadataTool.applyMdObjectSynonym(attribute, tcSynonym, name, project));
                attrs.add(attribute);
                // Apply the type via the shared TypeDescription helper (same call
                // the object-attribute path makes). Failure is non-fatal: the
                // attribute still exists, type left empty for the caller to inspect.
                // tcConfig may be null for external-object projects (.epf/.erf):
                // setTypes resolves primitives via the project-aware proxy;
                // reference types surface as unresolved rather than being skipped.
                if (type != null && !type.isEmpty())
                {
                    try
                    {
                        IDtProject tcDtProject = Activator.getDefault()
                            .getDtProjectManager().getDtProject(project);
                        BmDefinedTypeHelper.TypesResult tr = BmDefinedTypeHelper.setTypes(
                            attribute, tcDtProject, tcConfig,
                            Collections.singletonList(type), tcQualifiers);
                        tcTypeAppliedFlag[0] = tr.ok;
                        if (tr.resolved != null)
                        {
                            tcTypeResolved.addAll(tr.resolved);
                        }
                        if (tr.unresolved != null)
                        {
                            tcTypeUnresolved.addAll(tr.unresolved);
                        }
                        if (!tr.ok)
                        {
                            tcTypeApplyErrorRef[0] = tr.error;
                            Activator.logWarning("addTabularSectionAttribute: type='" + type //$NON-NLS-1$
                                + "' not applied: " + tr.error); //$NON-NLS-1$
                        }
                    }
                    catch (Exception typeEx)
                    {
                        tcTypeApplyErrorRef[0] = typeEx.getClass().getSimpleName() + ": " //$NON-NLS-1$
                            + typeEx.getMessage();
                        Activator.logWarning("addTabularSectionAttribute: type='" + type //$NON-NLS-1$
                            + "' threw: " + typeEx.getMessage()); //$NON-NLS-1$
                    }
                }
                // multiLine is a direct BasicFeature setter (not a TypeDescription
                // qualifier), so it applies regardless of the underlying type.
                if (multiLineProvided)
                {
                    String mlErr = BmObjectHelper.setProperty(attribute, "multiLine", multiLine); //$NON-NLS-1$
                    if (mlErr != null)
                    {
                        Activator.logWarning("addTabularSectionAttribute: multiLine=" //$NON-NLS-1$
                            + multiLine + " not applied: " + mlErr); //$NON-NLS-1$
                    }
                }
                EditMetadataTool.applyAttributeFeatureProperties(attribute, params,
                    "add_tabular_section_attribute", appliedFeatureProps, failedFeatureProps); //$NON-NLS-1$
                return tcName + "." + name; //$NON-NLS-1$
            }, null, autoBorrow);
        EditMetadataTool.addSynonymTags(r, tcSynonymRef.get());
        if (!autoBorrowed.isEmpty())
        {
            // Dedupe: the owner-borrow and the type-target loop can both surface the same FQN
            // (e.g. an attribute typed as a reference to its own owner). Order-preserving.
            // C5: on a failed action the eager borrow did not persist (forceExport is gated
            // on r.ok), so do not claim a successful adopt - relabel to
            // autoBorrowedButActionFailed, mirroring executeWriteOnObject's lazy-path tag.
            r.tags.put(r.ok ? "autoBorrowed" : "autoBorrowedButActionFailed", //$NON-NLS-1$ //$NON-NLS-2$
                new ArrayList<>(new java.util.LinkedHashSet<>(autoBorrowed)));
        }
        if (!autoBorrowSkipped.isEmpty())
        {
            r.tags.put("autoBorrowSkipped", autoBorrowSkipped); //$NON-NLS-1$
        }
        // Surface feature-prop outcome only on a successful (or idempotent-success,
        // which also sets r.ok) result - never claim appliedProperties on an error
        // response whose transaction rolled back after the helper ran.
        if (r.ok)
        {
            if (!appliedFeatureProps.isEmpty())
            {
                r.tags.put("appliedProperties", appliedFeatureProps); //$NON-NLS-1$
            }
            if (!failedFeatureProps.isEmpty())
            {
                r.tags.put("failedProperties", failedFeatureProps); //$NON-NLS-1$
            }
        }
        // Surface type-application outcome so the caller sees whether `type`
        // landed on the new tabular-section attribute (mirror add_object_attribute).
        if (type != null && !type.isEmpty())
        {
            Map<String, Object> typeApply = TypeApplication.tag(type, tcTypeAppliedFlag[0],
                tcTypeResolved, tcTypeUnresolved, tcTypeApplyErrorRef[0]);
            String tcLenWarn = BmDefinedTypeHelper.stringLengthRestructureWarning(type, tcQualifiers);
            if (tcLenWarn != null)
            {
                typeApply.put("warning", tcLenWarn); //$NON-NLS-1$
            }
            r.tags.put("typeApplication", typeApply); //$NON-NLS-1$
            if (r.ok && TypeApplication.failed(tcTypeAppliedFlag[0], tcTypeUnresolved))
            {
                r.ok = false;
                r.error = TypeApplication.failureMessage(
                    "tabular section attribute '" + name + "'", type, //$NON-NLS-1$ //$NON-NLS-2$
                    tcTypeApplyErrorRef[0], dryRun, tcTypeAppliedFlag[0]);
            }
        }
        return EditMetadataTool.formatResult(r, "add_tabular_section_attribute"); //$NON-NLS-1$
    }
    String opRemoveTabularSectionAttribute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String tcName = JsonUtils.extractStringArgument(params, "tabularSectionName"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(tcName, "tabularSectionName") //$NON-NLS-1$
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
                EList<MdObject> tcs = BmObjectHelper.getTabularSections(owner);
                MdObject ts = tcs != null ? BmObjectHelper.findByName(tcs, tcName) : null;
                if (ts == null)
                {
                    throw BmObjectHelper.notFound(tcName, ownerFqn, "tabularSection"); //$NON-NLS-1$
                }
                EList<MdObject> attrs = BmObjectHelper.getAttributes(ts);
                MdObject existing = attrs != null ? BmObjectHelper.findByName(attrs, name) : null;
                if (existing == null)
                {
                    throw BmObjectHelper.notFound(name, ownerFqn + "." + tcName, //$NON-NLS-1$
                        "tabularSectionAttribute"); //$NON-NLS-1$
                }
                attrs.remove(existing);
                return tcName + "." + name; //$NON-NLS-1$
            });
        return EditMetadataTool.formatResult(r, "remove_tabular_section_attribute"); //$NON-NLS-1$
    }
    /**
     * 1.42 (RSV 4.2 parity): removes a metadata object whole - the same
     * action behind {@code right-click -> Delete} in the EDT navigator.
     * Delegates to the dedicated {@link MetadataObjectDeleter} which
     * already handles the inbound-reference cleanup (registers detach from
     * documents, subsystems lose the entry, ...) and the destructive-action
     * confirmation contract.
     *
     * <p>Operation parameters (forwarded as-is):
     * {@code projectName}, {@code objectFqn} (or {@code ownerFqn}),
     * {@code dryRun}, {@code force}.
     */
    String opRemoveObject(Map<String, String> params)
    {
        // ownerFqn alias for symmetry with addObjectAttribute /
        // removeObjectAttribute - MetadataObjectDeleter already accepts
        // objectFqn, so we copy ownerFqn into objectFqn when the agent uses
        // the Object-group naming.
        Map<String, String> forwarded = new LinkedHashMap<>(params);
        if (!forwarded.containsKey("objectFqn") //$NON-NLS-1$
            && forwarded.containsKey("ownerFqn")) //$NON-NLS-1$
        {
            forwarded.put("objectFqn", forwarded.get("ownerFqn")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return new MetadataObjectDeleter().execute(forwarded);
    }
}
