/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * Helper for BM-transaction operations on metadata objects (Catalog, Document,
 * Register, etc.). Used by {@code EditMetadataTool} as a backing for the
 * operations in the "Objects" group.
 * <p>
 * Most operations are implemented via reflection on the EMF-generated
 * {@link MdObject} hierarchy: each metadata type has slightly different
 * containment lists (e.g. {@code getAttributes()} vs {@code getDimensions()}),
 * so we look the method up by name and invoke it. Failures are surfaced as
 * informative error strings instead of stack traces.
 * <p>
 * <b>State:</b> Phase 3 skeleton. Initial revision exposes the most-needed
 * operations (createObject, setObjectProperty, addObjectAttribute,
 * removeObjectAttribute, addTabularSection, removeTabularSection,
 * addTabularSectionAttribute, removeTabularSectionAttribute). Per-type
 * specifics (Catalog vs ChartOfAccounts vs Register) are added incrementally.
 */
public final class BmObjectHelper
{
    private BmObjectHelper()
    {
        // utility class
    }

    /**
     * Result of a metadata-mutation operation.
     * <p>
     * {@link #tags} carries machine-readable structured fields (e.g.
     * {@code supportLock}, {@code standardAttributeConflict},
     * {@code alreadyExists}, {@code notFound}) surfaced into the JSON
     * response so AI agents can branch on them without parsing the
     * {@link #error} text.
     */
    public static final class Result
    {
        public boolean ok;
        public String error;
        public String fqn;
        public String message;
        public Map<String, Object> tags = new LinkedHashMap<>();
    }

    /**
     * Functional callback executed inside a BM read-write transaction with the
     * resolved owner already in hand.
     */
    @FunctionalInterface
    public interface MdObjectAction
    {
        Object execute(IBmTransaction tx, MdObject owner) throws Exception;
    }

    /**
     * Optional pre-flight guard executed inside the BM read-write transaction
     * after {@link MetadataGuards#checkSupplierLock} but before the
     * {@link MdObjectAction}. Use it to add context-specific guards that need
     * the candidate name / kind known to the caller (e.g.
     * {@link MetadataGuards#checkStandardAttributeConflict}).
     * <p>
     * Throw {@link MetadataGuards.BlockedGuardException} to abort with a
     * structured {@code Verdict} that propagates into {@link Result#tags}.
     */
    @FunctionalInterface
    public interface PreExecuteCheck
    {
        void validate(MdObject owner) throws Exception;
    }

    /**
     * Executes the given action inside a BM read-write transaction against the
     * resolved owner FQN. {@code dryRun=true} runs the action then rolls back
     * by throwing a sentinel exception that {@link IBmModel#execute} treats as
     * normal abort - changes never reach the model.
     * <p>
     * This overload preserves the legacy contract (no centralized guards). It
     * delegates to the {@link #executeWriteOnObject(IProject, String, boolean,
     * MdObjectAction, PreExecuteCheck)} variant with a {@code null} preCheck,
     * but the supplier-lock guard always runs.
     */
    public static Result executeWriteOnObject(IProject project, String ownerFqn, boolean dryRun,
        MdObjectAction action)
    {
        return executeWriteOnObject(project, ownerFqn, dryRun, action, null);
    }

    /**
     * Lazy owner auto-borrow for {@link #executeWriteOnObject}. Invoked only when
     * {@code findObject} returned {@code null} for a {@code Type.Name} owner: if the
     * project is an extension, adopts the base-config object (idempotent -
     * {@code attemptBorrow} short-circuits when already adopted / extension-own) and
     * re-resolves it against a FRESH Configuration read (the pre-borrow
     * {@code Configuration} reference may not reflect the newly adopted object).
     * Returns the re-resolved owner, or {@code null} when the project is not an
     * extension, the borrow failed, or the object still does not resolve (the caller
     * then falls through to the original {@code notFound} error). Records
     * {@code autoBorrowed} / {@code autoBorrowSkipped} in {@code r.tags} so the outcome
     * is visible in the response. Generalizes the RSV owner auto-borrow ergonomics from
     * the two per-op call sites (add_object_attribute / add_tabular_section_attribute)
     * to every {@code executeWriteOnObject} caller with a single hook, without adding a
     * parameter to the ~50-site signature.
     */
    private static MdObject maybeLazyBorrowOwner(IProject project,
        IConfigurationProvider configProvider, String[] parts, String normalized, Result r,
        boolean dryRun)
    {
        if (!BmDcsHelper.isExtensionProject(project))
        {
            return null;
        }
        if (dryRun)
        {
            // Preserve the dryRun contract (this shared path must not mutate on a
            // preview): do NOT adopt on dryRun. Record that a real run would auto-borrow
            // and leave the owner unresolved, so the caller reports the honest
            // "Owner not found" for the preview - the wouldAutoBorrow tag tells the agent
            // a real run will adopt-and-proceed.
            r.tags.put("wouldAutoBorrow", normalized); //$NON-NLS-1$
            return null;
        }
        try
        {
            BmExtensionHelper.BorrowResult ob =
                BmExtensionHelper.attemptBorrow(project, null, normalized, null);
            if (!ob.ok)
            {
                Map<String, Object> sk = new LinkedHashMap<>();
                sk.put("ownerFqn", normalized); //$NON-NLS-1$
                sk.put("reason", ob.error != null ? ob.error : "unknown"); //$NON-NLS-1$ //$NON-NLS-2$
                r.tags.put("autoBorrowSkipped", java.util.Collections.singletonList(sk)); //$NON-NLS-1$
                return null;
            }
            Configuration fresh = configProvider.getConfiguration(project);
            if (fresh == null)
            {
                r.tags.put("autoBorrowResolveFailed", normalized); //$NON-NLS-1$
                return null;
            }
            MdObject reResolved = MetadataTypeCatalog.findObject(fresh, parts[0], parts[1]);
            if (reResolved == null)
            {
                // Borrow reported ok, but the object still does not resolve (provider
                // staleness, or the adopter's attach-check disagreeing with findObject).
                // Surface it structurally instead of masquerading as a clean not-found.
                r.tags.put("autoBorrowResolveFailed", normalized); //$NON-NLS-1$
            }
            else if (!ob.alreadyBorrowed)
            {
                r.tags.put("autoBorrowed", //$NON-NLS-1$
                    java.util.Collections.singletonList(normalized));
            }
            return reResolved;
        }
        catch (Exception e)
        {
            // Defensive: this runs on the resolution path for every executeWriteOnObject
            // caller. A borrow failure must degrade to the original "Owner not found"
            // (return null), never throw out of the resolve phase. Surface the cause as a
            // tag so the agent can tell a borrow crash from a plain typo.
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            r.tags.put("autoBorrowError", msg); //$NON-NLS-1$
            Activator.logWarning("lazy auto-borrow of '" + normalized + "' failed: " + msg); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /**
     * Executes the given action inside a BM read-write transaction with two
     * automatic guards:
     * <ol>
     *   <li>{@link MetadataGuards#checkSupplierLock} - always.</li>
     *   <li>{@code preCheck.validate(owner)} - optional, caller-provided.</li>
     * </ol>
     * Both guards may throw {@link MetadataGuards.BlockedGuardException} with
     * a structured {@link MetadataGuards.Verdict}. The verdict's
     * {@link MetadataGuards.ErrorTag} is captured into {@link Result#tags} so
     * the response carries a machine-readable field next to the {@code error}
     * string.
     */
    public static Result executeWriteOnObject(IProject project, String ownerFqn, boolean dryRun,
        MdObjectAction action, PreExecuteCheck preCheck)
    {
        return executeWriteOnObject(project, ownerFqn, dryRun, action, preCheck, true);
    }

    /**
     * As {@link #executeWriteOnObject(IProject, String, boolean, MdObjectAction, PreExecuteCheck)}
     * but with explicit control over lazy owner auto-borrow. {@code autoBorrowOwner=false}
     * suppresses the adopt-on-not-found step, so a caller that exposes its own
     * {@code auto_borrow=false} opt-out (add_object_attribute /
     * add_tabular_section_attribute) is honored end to end instead of being defeated by
     * this shared path. The public 4-/5-arg overloads pass {@code true}, so the ~48 other
     * callers keep the ergonomic default (adopt-on-not-found) with zero changes.
     */
    public static Result executeWriteOnObject(IProject project, String ownerFqn, boolean dryRun,
        MdObjectAction action, PreExecuteCheck preCheck, boolean autoBorrowOwner)
    {
        Result r = new Result();
        r.fqn = ownerFqn;
        if (project == null || ownerFqn == null || ownerFqn.isEmpty())
        {
            r.error = "project and ownerFqn are required"; //$NON-NLS-1$
            return r;
        }

        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            r.error = "Error: configuration provider is not published as a service"; //$NON-NLS-1$
            return r;
        }
        Configuration config = configProvider.getConfiguration(project);

        String normalized = MetadataTypeCatalog.normalizeFqn(ownerFqn);
        MdObject owner;
        // J2/J3 foundation: the Configuration root itself as a mutation owner.
        // Config-level properties (logo / splash / mainSectionPicture /
        // compatibilityMode) and the command interface live on Configuration,
        // not on any Type.Name child. The sentinel "Configuration" resolves to
        // the project's own Configuration - the same object every read path uses
        // via IConfigurationProvider.getConfiguration. (An extension project
        // exposes its own Adopted Configuration.mdo here.)
        boolean externalProject = ExternalProjectResolver.isExternalProject(project);
        if ("Configuration".equalsIgnoreCase(normalized)) //$NON-NLS-1$
        {
            // An external-object project has no configuration of its own. If it was created against
            // one it still ANSWERS getConfiguration() - with that other project's configuration - so
            // accepting the sentinel here would quietly write into a foreign configuration.
            if (externalProject)
            {
                r.error = "'Configuration' does not exist in the external-object project '" //$NON-NLS-1$
                    + project.getName() + "': it holds external object roots, not a configuration. " //$NON-NLS-1$
                    + "Address the root instead, e.g. ExternalDataProcessor.<Name> or ExternalReport.<Name>."; //$NON-NLS-1$
                return r;
            }
            if (config == null)
            {
                r.error = "No Configuration root in project '" + project.getName() + "'."; //$NON-NLS-1$ //$NON-NLS-2$
                return r;
            }
            owner = config;
        }
        else
        {
            String[] parts = normalized.split("\\.", 2); //$NON-NLS-1$
            if (parts.length < 2)
            {
                r.error = "ownerFqn must be 'Type.Name' (or 'Configuration' for the config root)"; //$NON-NLS-1$
                return r;
            }
            if (externalProject)
            {
                // The roots of an external-object project live in the project's own model, never in
                // a Configuration. This test must come first: with a base project present, config is
                // non-null (it is the BASE configuration), so a check that waits for null never runs
                // and the owner is hunted for in the wrong model - which is what answered
                // "Owner not found" for every external object that has a base project.
                owner = resolveExternalObjectOwner(project, parts[1]);
                if (owner == null)
                {
                    r.error = "Owner not found: '" + normalized + "' - project '" + project.getName() //$NON-NLS-1$ //$NON-NLS-2$
                        + "' holds no external object root under that name."; //$NON-NLS-1$
                    return r;
                }
            }
            else if (config != null)
            {
                // Nested subsystem owner (Subsystem.A.Subsystem.B): findObject only walks
                // the top-level subsystem list and compares names literally, so a nested
                // FQN resolves to null there even though the subsystem exists. Resolve via
                // the nested walker the read path (get_command_interface) uses, so write ops
                // target the same nested subsystem reads already return. Only activates for
                // a nested Subsystem FQN; every other type and a bare Subsystem.X keep the
                // flat path unchanged (no blast radius on the ~48 other ops).
                if ("Subsystem".equalsIgnoreCase(parts[0]) //$NON-NLS-1$
                    && parts[1].toLowerCase().contains(".Subsystem.".toLowerCase())) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    owner = MetadataTypeCatalog.findNestedSubsystem(config, normalized);
                }
                else
                {
                    owner = MetadataTypeCatalog.findObject(config, parts[0], parts[1]);
                }
                if (owner == null && autoBorrowOwner)
                {
                    // #3 (RSV parity): lazy owner auto-borrow. In an extension
                    // project an owner-scoped write on a base-config object that was
                    // never adopted resolves to null here. Adopt it (idempotent) and
                    // re-resolve so the write proceeds instead of failing with
                    // "Owner not found". Fires ONLY on the not-found path, so objects
                    // that already resolve (extension-own / adopted / base project)
                    // are untouched. Borrow runs before the write transaction opens -
                    // the same ordering the legacy per-op eager borrow relied on - so
                    // the re-resolved owner carries a committed bmId the transaction
                    // re-fetches. Gated by autoBorrowOwner (honors a caller's
                    // auto_borrow=false). On dryRun it does NOT adopt: it records a
                    // wouldAutoBorrow tag and leaves owner null so the preview keeps the
                    // "no mutation" contract; a real run adopts and surfaces autoBorrowed.
                    owner = maybeLazyBorrowOwner(project, configProvider, parts, normalized, r, dryRun);
                }
            }
            else
            {
                r.error = "Could not resolve owner '" + normalized + "' in project '" //$NON-NLS-1$ //$NON-NLS-2$
                    + project.getName() + "': the project has no configuration and holds no " //$NON-NLS-1$
                    + "external objects."; //$NON-NLS-1$
                return r;
            }
        }
        if (owner == null)
        {
            r.error = "Owner not found: " + normalized; //$NON-NLS-1$
            Map<String, Object> notFoundData = new LinkedHashMap<>();
            notFoundData.put("ownerFqn", normalized); //$NON-NLS-1$
            r.tags.put(ErrorTags.NOT_FOUND.wire(), notFoundData);
            return r;
        }
        if (!(owner instanceof IBmObject))
        {
            r.error = "Owner is not a BM object: " + normalized; //$NON-NLS-1$
            return r;
        }

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            r.error = "object model manager is not published as a service"; //$NON-NLS-1$
            return r;
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            r.error = "object model not loaded for project: " + project.getName(); //$NON-NLS-1$
            return r;
        }

        long bmId = ((IBmObject) owner).bmGetId();
        try
        {
            bmModel.execute(new AbstractBmTask<Void>("BmObjectHelper.write") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    try
                    {
                        MdObject txOwner = (MdObject) tx.getObjectById(bmId);
                        if (txOwner == null)
                        {
                            throw new RuntimeException("Owner not found in transaction"); //$NON-NLS-1$
                        }

                        // GUARD 1: supplier lock - always
                        MetadataGuards.Verdict lock = MetadataGuards.checkSupplierLock(txOwner);
                        if (lock.blocked)
                        {
                            throw new MetadataGuards.BlockedGuardException(lock);
                        }

                        // GUARD 2: caller-provided preCheck (optional)
                        if (preCheck != null)
                        {
                            preCheck.validate(txOwner);
                        }

                        Object actionResult = action.execute(tx, txOwner);
                        if (actionResult != null)
                        {
                            r.message = actionResult.toString();
                        }
                        if (dryRun)
                        {
                            // Throw to abort the transaction - the model preserves no state.
                            throw new DryRunAbort("dryRun"); //$NON-NLS-1$
                        }
                    }
                    catch (DryRunAbort e)
                    {
                        throw e;
                    }
                    catch (MetadataGuards.BlockedGuardException e)
                    {
                        throw e;
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName(), e);
                    }
                    return null;
                }
            });
            r.ok = true;
        }
        catch (DryRunAbort dra)
        {
            // Expected for dryRun
            r.ok = true;
            if (r.message == null || r.message.isEmpty())
            {
                r.message = "Dry run completed without applying changes."; //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            MetadataGuards.BlockedGuardException blocked = MetadataGuards.BlockedGuardException
                .unwrap(e);
            if (blocked != null)
            {
                MetadataGuards.Verdict v = blocked.verdict;
                r.error = v.error != null ? v.error : "blocked"; //$NON-NLS-1$
                if (v.hint != null && !v.hint.isEmpty())
                {
                    r.error = r.error + " - " + v.hint; //$NON-NLS-1$
                }
                if (v.tag != null)
                {
                    r.tags.put(v.tag.name, v.tag.data);
                }
            }
            else
            {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                r.error = cause.getMessage() != null ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            }
            Activator.logWarning("BmObjectHelper write failed for " + normalized + ": " + r.error); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // C5: when a lazy owner auto-borrow adopted the object but the action then FAILED,
        // the adopt was not force-exported (forceExport below is gated on r.ok) - it is only
        // a transient in-BM adoption that clean_project / resync_to_disk clears. Relabel the
        // tag so the response does not claim a successful adopt that persisted nothing.
        if (!r.ok && r.tags.containsKey("autoBorrowed")) //$NON-NLS-1$
        {
            r.tags.put("autoBorrowedButActionFailed", r.tags.remove("autoBorrowed")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // Persist mutation to disk. Without forceExport, child mutations
        // (add_object_attribute, add_tabular_section_attribute, add_form,
        // add_template, set_object_property, ...) live only in the BM in-memory
        // index - they are visible to get_metadata_details and to validators,
        // but the parent .mdo file on disk does not reflect them. dryRun is
        // skipped because the transaction was rolled back.
        if (r.ok && !dryRun)
        {
            try
            {
                BmExportHelper.Result exp = BmExportHelper.forceExportAndWait(bmModelManager,
                    project, normalized);
                if (exp != null && !exp.isOk())
                {
                    String detail = exp.error != null ? exp.error : "forceExport returned not-ok"; //$NON-NLS-1$
                    Activator.logWarning("forceExport after mutation on " + normalized //$NON-NLS-1$
                        + " did not complete cleanly: " + detail); //$NON-NLS-1$
                    r.tags.put("forceExportWarning", detail); //$NON-NLS-1$
                }
                // Row 42: the mutation IS committed to the BM model, but the
                // asynchronous on-disk flush did not confirm within the budget
                // (backlogged/stuck synchronization manager on a large or
                // post-hang config). Tell the agent truthfully instead of a bare
                // timeout: the object exists in BM, do not re-add, re-drive the
                // disk flush via resync_to_disk.
                if (exp != null && exp.syncFlushPending)
                {
                    r.tags.put("committedToBm", Boolean.TRUE); //$NON-NLS-1$
                    r.tags.put("diskFlushPending", Boolean.TRUE); //$NON-NLS-1$
                    r.tags.put("diskFlushHint", "Change is committed to the in-memory BM " //$NON-NLS-1$
                        + "model but the .mdo disk flush did not confirm within the wait budget " //$NON-NLS-1$
                        + "(large or still-settling config). The object IS present - " //$NON-NLS-1$
                        + "get_metadata_details will show it; do NOT re-add (it will report " //$NON-NLS-1$
                        + "'already exists'). To complete the on-disk write: re-run " //$NON-NLS-1$
                        + "resync_to_disk objects=[\"" + normalized + "\"] once EDT settles, then " //$NON-NLS-1$ //$NON-NLS-2$
                        + "re-check the .mdo. If it stays stale, restart EDT and resync."); //$NON-NLS-1$
                }
            }
            catch (Exception persistEx)
            {
                Activator.logWarning("forceExport after mutation on " + normalized //$NON-NLS-1$
                    + " threw: " + persistEx.getMessage()); //$NON-NLS-1$
                r.tags.put("forceExportWarning", persistEx.getMessage()); //$NON-NLS-1$
            }
        }
        return r;
    }

    /**
     * Resolves the root external object (ExternalDataProcessor / ExternalReport)
     * of an external-object project by name. External-object projects
     * (.epf/.erf) have no Configuration - the roots live as top objects in the
     * project's own BM model, exposed via
     * {@link IExternalObjectProject#getExternalObjects()}. An external-object
     * project normally holds exactly one root, so a name match is sufficient.
     *
     * @param project the external-object project.
     * @param name the root object name (the {@code Name} part of the FQN).
     * @return the matching root {@link MdObject}, or {@code null} when the
     *         project is not an external-object project or no root matches.
     */
    private static MdObject resolveExternalObjectOwner(IProject project, String name)
    {
        try
        {
            IV8ProjectManager pm = Activator.getDefault().getV8ProjectManager();
            if (pm == null)
            {
                return null;
            }
            IV8Project v8 = pm.getProject(project);
            if (!(v8 instanceof IExternalObjectProject))
            {
                return null;
            }
            for (MdObject o : ((IExternalObjectProject) v8).getExternalObjects())
            {
                if (name != null && name.equals(o.getName()))
                {
                    return o;
                }
            }
        }
        catch (Exception e)
        {
            // Resolution failed - caller treats this as "no configuration".
            Activator.logWarning("resolveExternalObjectOwner failed for '" //$NON-NLS-1$
                + name + "': " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Marker exception used to abort a transaction cleanly when {@code dryRun=true}.
     */
    private static final class DryRunAbort extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        DryRunAbort(String msg)
        {
            super(msg);
        }
    }

    // -----------------------------------------------------------------------
    // Operation shortcuts
    // -----------------------------------------------------------------------

    /**
     * Backward-compatible entry point. Delegates to
     * {@link #createGenericObject(String)} - which adds an EPackage-based
     * fallback path to the original reflection-only implementation.
     *
     * <p>For child objects (Attribute, TabularSection, Form, Command, ...),
     * prefer {@link #createOwnerScopedObject(MdObject, String)} - in EDT
     * 2026.1 those kinds have no generic factory method (e.g. there is no
     * {@code createAttribute()}, only {@code createCatalogAttribute()},
     * {@code createDocumentAttribute()} etc.).
     */
    public static MdObject createObject(String englishType)
    {
        return createGenericObject(englishType);
    }

    /**
     * Creates a top-level or generic-shape metadata object via
     * {@link MdClassFactory}.
     *
     * <p>Strategy:
     * <ol>
     *   <li>{@code MdClassFactory.create<typeName>()} via reflection</li>
     *   <li>{@code MdClassFactory.eINSTANCE.create(eClass)} where the EClass
     *       is resolved through {@code MdClassPackage.eINSTANCE.get<typeName>()}</li>
     * </ol>
     * The second path covers EDT runtimes where the factory either renames
     * the type-specific create method or only exposes the EClass.
     *
     * @param typeName English bare type name, e.g. {@code "Catalog"},
     *     {@code "Document"}, {@code "Template"}, {@code "URLTemplate"},
     *     {@code "Method"}.
     * @return new {@link MdObject} or {@code null} when neither strategy
     *     resolves a usable EClass.
     */
    public static MdObject createGenericObject(String typeName)
    {
        if (typeName == null || typeName.isEmpty())
        {
            return null;
        }
        MdObject obj = createViaFactory("create" + typeName); //$NON-NLS-1$
        if (obj != null)
        {
            return obj;
        }
        return createViaPackage(typeName);
    }

    /**
     * Creates a child metadata object (Attribute, TabularSection, Form,
     * Command, Predefined, PredefinedItem, Dimension, Resource, ContentItem)
     * inside the given owner.
     *
     * <p>EDT 2026.1 has NO generic {@code createAttribute()} /
     * {@code createForm()} / etc. - those names exist only as type-specific
     * methods like {@code createCatalogAttribute()} or
     * {@code createInformationRegisterForm()}. This method dispatches to the
     * correct one based on the owner's EClass.
     *
     * <p>Strategy:
     * <ol>
     *   <li>{@code create<owner.eClass().getName()><kind>()} -
     *       e.g. owner=Catalog + kind=Attribute -> {@code createCatalogAttribute()}.</li>
     *   <li>{@code create<kind>()} - generic fallback for kinds that DO have a
     *       generic method (e.g. {@code createTemplate}, {@code createTabularSectionAttribute}).</li>
     *   <li>{@code MdClassFactory.eINSTANCE.create(eClass)} via
     *       {@code MdClassPackage.eINSTANCE.get<typeName>()} - tried for both
     *       the type-specific name and the bare kind name.</li>
     * </ol>
     *
     * <p>Special case: a {@code TabularSection} owner. The attribute under a
     * standard tabular section uses the generic
     * {@code createTabularSectionAttribute()}; DataProcessor and Report have
     * their own subclasses ({@code createDataProcessorTabularSectionAttribute},
     * {@code createReportTabularSectionAttribute}).
     */
    public static MdObject createOwnerScopedObject(MdObject owner, String kind)
    {
        if (owner == null || kind == null || kind.isEmpty())
        {
            return null;
        }
        String ownerType = owner.eClass().getName();

        // TabularSection-attribute special case: dispatch by the TS subclass.
        if ("Attribute".equals(kind) && ownerType.endsWith("TabularSection")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            // DataProcessorTabularSection / ReportTabularSection MUST use their
            // own concrete subclasses - falling back to the generic
            // TabularSectionAttribute would corrupt the BM model on persist.
            if ("DataProcessorTabularSection".equals(ownerType) //$NON-NLS-1$
                || "ReportTabularSection".equals(ownerType)) //$NON-NLS-1$
            {
                MdObject obj = createViaFactory("create" + ownerType + "Attribute"); //$NON-NLS-1$ //$NON-NLS-2$
                if (obj != null)
                {
                    return obj;
                }
                obj = createViaPackage(ownerType + "Attribute"); //$NON-NLS-1$
                // Do NOT fall through to generic createTabularSectionAttribute
                // - DP/Report tabular sections require the concrete subclass.
                return obj;
            }
            // Catalog/Document/etc. tabular sections share generic TabularSectionAttribute
            MdObject obj = createViaFactory("createTabularSectionAttribute"); //$NON-NLS-1$
            if (obj != null)
            {
                return obj;
            }
            return createViaPackage("TabularSectionAttribute"); //$NON-NLS-1$
        }

        // External-object roots reuse the DataProcessor/Report child factories:
        // ExternalDataProcessor.getAttributes() is EList<DataProcessorAttribute>,
        // ExternalReport.getForms() is EList<ReportForm>, etc. The concrete child
        // classes drop the "External" prefix, so the factory is
        // create<DataProcessor|Report><kind>, not create<ownerType><kind>. Template
        // is generic for both (createTemplate) and falls through to Strategy 2.
        String factoryOwnerType = ownerType;
        if ("ExternalDataProcessor".equals(ownerType)) //$NON-NLS-1$
        {
            factoryOwnerType = "DataProcessor"; //$NON-NLS-1$
        }
        else if ("ExternalReport".equals(ownerType)) //$NON-NLS-1$
        {
            factoryOwnerType = "Report"; //$NON-NLS-1$
        }

        // Strategy 1: type-specific factory (createCatalogAttribute, etc.)
        String typeSpecific = "create" + factoryOwnerType + kind; //$NON-NLS-1$
        MdObject obj = createViaFactory(typeSpecific);
        if (obj != null)
        {
            return obj;
        }
        // Strategy 2: generic factory (createTemplate, createTabularSectionAttribute, ...)
        obj = createViaFactory("create" + kind); //$NON-NLS-1$
        if (obj != null)
        {
            return obj;
        }
        // Strategy 3: EPackage lookup (type-specific then bare kind)
        obj = createViaPackage(factoryOwnerType + kind);
        if (obj != null)
        {
            return obj;
        }
        return createViaPackage(kind);
    }

    /**
     * Invokes a no-arg factory method on {@link MdClassFactory#eINSTANCE} via
     * reflection. Sets a fresh UUID on the returned object.
     *
     * @return new {@link MdObject} or {@code null} when the method is missing
     *     or fails to return an MdObject.
     */
    private static MdObject createViaFactory(String methodName)
    {
        try
        {
            Method m = MdClassFactory.class.getMethod(methodName);
            Object result = m.invoke(MdClassFactory.eINSTANCE);
            if (result instanceof MdObject)
            {
                MdObject created = (MdObject) result;
                created.setUuid(UUID.randomUUID());
                return created;
            }
        }
        catch (NoSuchMethodException nsme)
        {
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("MdClassFactory." + methodName //$NON-NLS-1$
                + " failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Creates a metadata-model {@link org.eclipse.emf.ecore.EObject} of the
     * given type that is NOT an {@link MdObject} - e.g. a predefined-data
     * container ({@code CatalogPredefined}) or a predefined item
     * ({@code CatalogPredefinedItem}), which extend {@code Predefined} /
     * {@code PredefinedItem} rather than MdObject. Tries
     * {@code MdClassFactory.create<typeName>()} first, then the EClass path
     * ({@code MdClassPackage.get<typeName>()} + {@code MdClassFactory.create(EClass)}).
     * Unlike {@link #createGenericObject(String)} it does NOT set a UUID
     * (predefined items use {@code setId(UUID)}, a different feature) and returns
     * the raw object so the caller can populate it reflectively.
     *
     * @return the created EObject, or {@code null} when neither path resolves.
     */
    public static Object createMdClassEObject(String typeName)
    {
        if (typeName == null || typeName.isEmpty())
        {
            return null;
        }
        try
        {
            Method m = MdClassFactory.class.getMethod("create" + typeName); //$NON-NLS-1$
            Object res = m.invoke(MdClassFactory.eINSTANCE);
            if (res != null)
            {
                return res;
            }
        }
        catch (NoSuchMethodException nsme)
        {
            // fall through to the EClass path
        }
        catch (Exception e)
        {
            Activator.logWarning("createMdClassEObject create" + typeName //$NON-NLS-1$
                + " failed: " + e.getMessage()); //$NON-NLS-1$
        }
        try
        {
            Method getter = MdClassPackage.class.getMethod("get" + typeName); //$NON-NLS-1$
            Object lookup = getter.invoke(MdClassPackage.eINSTANCE);
            if (lookup instanceof EClass)
            {
                EClass eClass = (EClass) lookup;
                if (!eClass.isAbstract() && !eClass.isInterface())
                {
                    return MdClassFactory.eINSTANCE.create(eClass);
                }
            }
        }
        catch (NoSuchMethodException nsme)
        {
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("createMdClassEObject EClass path for " + typeName //$NON-NLS-1$
                + " failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * EPackage-based fallback: looks up the EClass for {@code typeName} via
     * {@code MdClassPackage.eINSTANCE.get<typeName>()} and instantiates it
     * through {@code MdClassFactory.eINSTANCE.create(EClass)}.
     *
     * <p>This works even when the factory does not expose a no-arg
     * {@code create<typeName>()} method - which is the case for EDT runtimes
     * that only register the EClass but not a wrapper in the EFactory
     * interface.
     */
    private static MdObject createViaPackage(String typeName)
    {
        if (typeName == null || typeName.isEmpty())
        {
            return null;
        }
        EClass eClass = null;
        try
        {
            Method getter = MdClassPackage.class.getMethod("get" + typeName); //$NON-NLS-1$
            Object lookup = getter.invoke(MdClassPackage.eINSTANCE);
            if (lookup instanceof EClass)
            {
                eClass = (EClass) lookup;
            }
        }
        catch (NoSuchMethodException nsme)
        {
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("createViaPackage(" + typeName //$NON-NLS-1$
                + ") - EClass lookup failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
        if (eClass == null)
        {
            return null;
        }
        // Distinguish abstract/interface EClasses (EFactory.create() throws
        // IllegalArgumentException on those) from genuine instantiation
        // failures - the caller might want to know the difference for picking
        // a more concrete subtype.
        if (eClass.isAbstract() || eClass.isInterface())
        {
            Activator.logWarning("createViaPackage(" + typeName //$NON-NLS-1$
                + ") - EClass is abstract/interface, cannot instantiate. " //$NON-NLS-1$
                + "Caller must use a concrete subtype."); //$NON-NLS-1$
            return null;
        }
        try
        {
            Object created = MdClassFactory.eINSTANCE.create(eClass);
            if (created instanceof MdObject)
            {
                MdObject result = (MdObject) created;
                result.setUuid(UUID.randomUUID());
                return result;
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("createViaPackage(" + typeName //$NON-NLS-1$
                + ") - factory create failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Adds the given object to the configuration's collection for its type
     * (e.g. {@code config.getCatalogs()}).
     *
     * @return {@code true} when added.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static boolean addToConfiguration(Configuration config, MdObject obj)
    {
        if (config == null || obj == null)
        {
            return false;
        }
        String typeName = obj.eClass().getName();
        String collection = MetadataTypeCatalog.getConfigReferenceName(typeName);
        if (collection == null)
        {
            return false;
        }
        // collection is e.g. "catalogs" - the EMF getter is "getCatalogs"
        String getter = "get" + Character.toUpperCase(collection.charAt(0)) //$NON-NLS-1$
            + collection.substring(1);
        try
        {
            // Acronym collections do NOT capitalize cleanly from the camelCase
            // config-reference name: xdtoPackages -> getXDTOPackages,
            // wsReferences -> getWSReferences, httpServices -> getHTTPServices.
            // Try the exact getter first, then a case-insensitive match, so
            // create_object for XDTOPackage / WSReference / HTTPService actually
            // registers the object instead of silently failing to attach.
            Method m = findNoArgGetter(config, getter);
            if (m == null)
            {
                Activator.logWarning("addToConfiguration: Configuration has no getter '" //$NON-NLS-1$
                    + getter + "' (or case-insensitive match) for " + typeName); //$NON-NLS-1$
                return false;
            }
            Object list = m.invoke(config);
            if (list instanceof EList)
            {
                ((EList) list).add(obj);
                return true;
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("addToConfiguration failed for " + typeName //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
        }
        return false;
    }

    /**
     * Finds a no-arg getter by name, falling back to a case-insensitive match.
     * EMF acronym collections (XDTOPackages / WSReferences / HTTPServices) have
     * getters whose casing does not match a naive capitalize of the camelCase
     * config-reference name, so the exact lookup misses them.
     */
    private static Method findNoArgGetter(Object target, String getterName)
    {
        try
        {
            return target.getClass().getMethod(getterName);
        }
        catch (NoSuchMethodException nsm)
        {
            for (Method m : target.getClass().getMethods())
            {
                if (m.getParameterCount() == 0 && m.getName().equalsIgnoreCase(getterName))
                {
                    return m;
                }
            }
            return null;
        }
    }

    /**
     * Sets a generic EMF-feature-backed property on an object via
     * {@code setXxx(...)} reflection. Returns {@code null} on success or an
     * error message.
     */
    public static String setProperty(EObject obj, String propertyName, Object value)
    {
        if (obj == null || propertyName == null || propertyName.isEmpty())
        {
            return "owner and propertyName are required"; //$NON-NLS-1$
        }
        // fillValue is an mcore.Value EObject (not a scalar) whose subtype
        // depends on the attribute's own primitive type - delegate to the
        // defined-type helper which builds the matching Value (Boolean / Number
        // / String / Undefined). coerceValue cannot do this: it sees only the
        // setFillValue(Value) parameter type, not the attribute's type.
        if ("fillValue".equalsIgnoreCase(propertyName)) //$NON-NLS-1$
        {
            return BmDefinedTypeHelper.applyFillValue(obj, value == null ? null : value.toString());
        }
        String setter = "set" + Character.toUpperCase(propertyName.charAt(0)) //$NON-NLS-1$
            + propertyName.substring(1);
        for (Method m : obj.getClass().getMethods())
        {
            if (!setter.equals(m.getName()) || m.getParameterCount() != 1)
            {
                continue;
            }
            try
            {
                Class<?> paramType = m.getParameterTypes()[0];
                Object converted = coerceValue(value, paramType);
                if (converted == null && paramType.isPrimitive())
                {
                    // A primitive has no null. Passing one reaches the JDK as
                    // "Cannot invoke java.lang.Number.intValue() because the return value of
                    // sun.invoke.util.ValueConversions.primitiveConversion(...) is null" - a message
                    // about JDK internals, which reads as a defect in the model rather than as a
                    // value that was never supplied. Say which value is missing instead.
                    return "Cannot set " + propertyName + ": it takes a " //$NON-NLS-1$ //$NON-NLS-2$
                        + paramType.getName() + " and no value was supplied. Pass propertyValue."; //$NON-NLS-1$
                }
                m.invoke(obj, converted);
                return null;
            }
            catch (Exception e)
            {
                return "Failed to set " + propertyName + ": " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        // No setXxx() found. Localized properties (synonym, toolTip, explanation)
        // have no setter on the object - they are an EMap<lang,text> reached via
        // getXxx() (synonym is an EMF EMap, NOT a LocalString-with-setter). When
        // the getter yields such an EMap (or a LocalString whose getContent() is
        // one), fill it directly so set_object_property synonym=... works.
        String emapResult = setLocalizedEMapProperty(obj, propertyName, value);
        if (!NOT_AN_EMAP.equals(emapResult))
        {
            return emapResult; // null = success, or a concrete error message
        }
        java.util.TreeSet<String> settable = new java.util.TreeSet<>();
        for (Method setterMethod : obj.getClass().getMethods())
        {
            if (setterMethod.getName().startsWith("set") && setterMethod.getName().length() > 3 //$NON-NLS-1$
                && setterMethod.getParameterCount() == 1)
            {
                settable.add(Character.toLowerCase(setterMethod.getName().charAt(3))
                    + setterMethod.getName().substring(4));
            }
        }
        return TextSuggest.propertyNotFound(propertyName, obj.eClass().getName(), settable);
    }

    /** Sentinel: the property is not an EMap-backed localized string. */
    private static final String NOT_AN_EMAP = "notAnEMap"; //$NON-NLS-1$

    /**
     * Fills a localized {@code EMap<lang,text>} property (synonym / toolTip /
     * explanation) that has a getter but no setter. Returns {@code null} on
     * success, an error message on failure, or {@link #NOT_AN_EMAP} when the
     * property is not an EMap-backed string (so the caller can keep its
     * "not found" handling).
     */
    private static String setLocalizedEMapProperty(EObject obj, String propertyName, Object value)
    {
        String getter = "get" + Character.toUpperCase(propertyName.charAt(0)) //$NON-NLS-1$
            + propertyName.substring(1);
        try
        {
            Object holder = obj.getClass().getMethod(getter).invoke(obj);
            Object emapObj = holder;
            if (holder != null && !(holder instanceof org.eclipse.emf.common.util.EMap))
            {
                // LocalString wrapper exposes its EMap via getContent().
                try
                {
                    emapObj = holder.getClass().getMethod("getContent").invoke(holder); //$NON-NLS-1$
                }
                catch (NoSuchMethodException noContent)
                {
                    emapObj = null;
                }
            }
            if (emapObj instanceof org.eclipse.emf.common.util.EMap)
            {
                @SuppressWarnings("unchecked")
                org.eclipse.emf.common.util.EMap<String, String> emap =
                    (org.eclipse.emf.common.util.EMap<String, String>) emapObj;
                // Default to "ru": the configuration default language is not
                // reachable here, and RU covers the common case (consistent with
                // applyAttributeSynonym / createLocalString).
                emap.put("ru", value == null ? "" : value.toString()); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
        }
        catch (NoSuchMethodException noGetter)
        {
            // No getter at all -> not an EMap-backed property. Let the caller
            // fall back to its "property not found" message.
            return NOT_AN_EMAP;
        }
        catch (Exception e)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return "Failed to set " + propertyName + " (localized): " + cause.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return NOT_AN_EMAP;
    }

    /**
     * Best-effort coercion from a String parameter value to the EMF setter's
     * expected primitive / boxed type. Other types are passed through.
     */
    private static Object coerceValue(Object value, Class<?> targetType)
    {
        if (value == null || targetType.isInstance(value))
        {
            return value;
        }
        String s = value.toString();
        if (targetType == String.class)
        {
            return s;
        }
        if (targetType == boolean.class || targetType == Boolean.class)
        {
            return Boolean.valueOf(s);
        }
        if (targetType == int.class || targetType == Integer.class)
        {
            return Integer.valueOf(s);
        }
        if (targetType == long.class || targetType == Long.class)
        {
            return Long.valueOf(s);
        }
        if (targetType.isEnum())
        {
            // EMF enums (TemplateType, FormType, RightType, ...) name their
            // Java literals in SCREAMING_SNAKE while exposing the BSL/UI name
            // ("SpreadsheetDocument") through getName()/getLiteral(). Enum.valueOf
            // searches Java names and fails on the human-readable form. EMF
            // generates a static get(String) that resolves the literal, so try
            // it first and fall back to Enum.valueOf for plain Java enums.
            try
            {
                Method getter = targetType.getMethod("get", String.class); //$NON-NLS-1$
                if (java.lang.reflect.Modifier.isStatic(getter.getModifiers())
                    && targetType.isAssignableFrom(getter.getReturnType()))
                {
                    Object resolved = getter.invoke(null, s);
                    if (resolved != null)
                    {
                        return resolved;
                    }
                    // Some EMF enums also expose getByName when name != literal.
                    try
                    {
                        Method byName = targetType.getMethod("getByName", String.class); //$NON-NLS-1$
                        if (java.lang.reflect.Modifier.isStatic(byName.getModifiers())
                            && targetType.isAssignableFrom(byName.getReturnType()))
                        {
                            Object byNameResolved = byName.invoke(null, s);
                            if (byNameResolved != null)
                            {
                                return byNameResolved;
                            }
                        }
                    }
                    catch (NoSuchMethodException ignored)
                    {
                        // not an EMF enum with getByName, drop through
                    }
                }
            }
            catch (NoSuchMethodException ignored)
            {
                // plain Java enum, fall through to Enum.valueOf
            }
            catch (Exception emfLookupEx)
            {
                // reflective failure: surface the original Enum.valueOf path
                // so the caller still sees a recognisable error message.
            }
            try
            {
                @SuppressWarnings({ "rawtypes", "unchecked" })
                Object e = Enum.valueOf((Class<Enum>) targetType, s);
                return e;
            }
            catch (IllegalArgumentException badEnum)
            {
                // Enum.valueOf rejects the human-readable literal with a bare
                // "No enum constant X.Y" and no valid list. Surface the enum's
                // literals (EMF getLiteral / getName when present, else the Java
                // constant name) so the caller can self-correct in one round-trip.
                throw new IllegalArgumentException(TextSuggest.invalidValue(
                    "enum value", s, enumLiterals(targetType))); //$NON-NLS-1$
            }
        }
        // 1.43.x: wrap raw String into a LocalString EObject when the setter
        // expects one (synonym / tooltip / ...). Without this branch every
        // localised-string property fails with IllegalArgumentException because
        // the reflective setter only accepts LocalString, never a plain String.
        if (isLocalStringType(targetType))
        {
            Object localString = createLocalString(s, "ru"); //$NON-NLS-1$
            if (localString != null)
            {
                return localString;
            }
        }
        return value;
    }

    /**
     * Collects the human-readable literals of an enum type for diagnostic
     * listing: prefers the EMF {@code getLiteral()}, then {@code getName()},
     * falling back to the Java constant {@code name()} for plain enums. Used
     * by {@link #coerceValue} to turn a bare "No enum constant X.Y" into a
     * self-correcting "Invalid enum value 'Y'. Did you mean ...? Valid: ...".
     */
    private static List<String> enumLiterals(Class<?> targetType)
    {
        Object[] consts = targetType.getEnumConstants();
        if (consts == null || consts.length == 0)
        {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(consts.length);
        for (Object c : consts)
        {
            String lit = reflectiveEnumLabel(c, "getLiteral"); //$NON-NLS-1$
            if (lit == null)
            {
                lit = reflectiveEnumLabel(c, "getName"); //$NON-NLS-1$
            }
            if (lit == null)
            {
                lit = ((Enum<?>) c).name();
            }
            out.add(lit);
        }
        return out;
    }

    /** Invokes a no-arg String getter on an enum constant; null on any failure. */
    private static String reflectiveEnumLabel(Object enumConstant, String getter)
    {
        try
        {
            Method m = enumConstant.getClass().getMethod(getter);
            Object v = m.invoke(enumConstant);
            if (v instanceof String && !((String) v).isEmpty())
            {
                return (String) v;
            }
        }
        catch (Exception ignored)
        {
            // not an EMF enum with this getter
        }
        return null;
    }

    private static boolean isLocalStringType(Class<?> targetType)
    {
        if (targetType == null)
        {
            return false;
        }
        // EDT exposes LocalString through com._1c.g5.v8.dt.mcore.LocalString.
        // Match by full class name so we do not introduce a compile-time
        // dependency from BmObjectHelper to the EDT mcore package (the
        // existing helpers stay reflection-only for the same reason). Exact
        // FQN only - a fuzzy endsWith(".LocalString") would also match
        // unrelated classes from third-party packages, leading to confusing
        // IllegalArgumentException downstream.
        return "com._1c.g5.v8.dt.mcore.LocalString".equals(targetType.getName()); //$NON-NLS-1$
    }

    /**
     * Builds a LocalString EObject through reflection and seeds it with the
     * given text under the requested language code (defaults to {@code ru}).
     * Returns {@code null} when McoreFactory is unavailable on the runtime -
     * the caller falls back to passing the raw String, which lets reflection
     * surface a meaningful "argument type mismatch" error rather than silently
     * succeeding without setting the property.
     */
    private static Object createLocalString(String text, String lang)
    {
        if (text == null)
        {
            return null;
        }
        try
        {
            Class<?> factoryCls = null;
            try
            {
                factoryCls = Class.forName("com._1c.g5.v8.dt.mcore.McoreFactory"); //$NON-NLS-1$
            }
            catch (ClassNotFoundException ignored)
            {
                // Try the alternative capitalisation seen in some EDT builds.
            }
            if (factoryCls == null)
            {
                try
                {
                    factoryCls = Class.forName("com._1c.g5.v8.dt.mcore.MCoreFactory"); //$NON-NLS-1$
                }
                catch (ClassNotFoundException ignored)
                {
                    // Neither spelling of the factory resolving means this EDT has no mcore
                    // factory at all - nothing to build a LocalString with, and the caller
                    // falls back to the raw String.
                    return null;
                }
            }
            Object factory = factoryCls.getField("eINSTANCE").get(null); //$NON-NLS-1$
            Object localString = factory.getClass().getMethod("createLocalString") //$NON-NLS-1$
                .invoke(factory);
            if (localString == null)
            {
                return null;
            }
            Object content = localString.getClass().getMethod("getContent") //$NON-NLS-1$
                .invoke(localString);
            if (!(content instanceof Map))
            {
                // Unexpected shape: return null so the caller falls back to
                // passing the raw String, which surfaces a meaningful
                // "argument type mismatch" rather than silently writing an
                // empty LocalString to the property.
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> contentMap = (Map<Object, Object>) content;
            contentMap.put(lang != null && !lang.isEmpty() ? lang : "ru", text); //$NON-NLS-1$
            return localString;
        }
        catch (Exception ignored)
        {
            // Measured 2026-08-18 against the mcore bundle: McoreFactory carries 78
            // create* methods and createLocalString is NOT among them, and the jar has
            // no LocalString class either. This branch therefore never succeeds - it
            // always lands here and the caller passes the raw String instead. Kept
            // rather than deleted because removing it is a product decision that has
            // been deferred; the measurement is recorded so nobody repeats it.
            return null;
        }
    }

    /**
     * Returns the {@code getAttributes()} list reflectively, or {@code null}
     * when the type does not carry one.
     */
    @SuppressWarnings("unchecked")
    public static EList<MdObject> getAttributes(MdObject owner)
    {
        try
        {
            Method m = owner.getClass().getMethod("getAttributes"); //$NON-NLS-1$
            Object result = m.invoke(owner);
            if (result instanceof EList)
            {
                return (EList<MdObject>) result;
            }
        }
        catch (Exception ignored)
        {
            // type does not expose getAttributes()
        }
        return null;
    }

    /**
     * Returns the {@code getTabularSections()} list reflectively, or {@code null}
     * when the type does not carry one.
     */
    @SuppressWarnings("unchecked")
    public static EList<MdObject> getTabularSections(MdObject owner)
    {
        try
        {
            Method m = owner.getClass().getMethod("getTabularSections"); //$NON-NLS-1$
            Object result = m.invoke(owner);
            if (result instanceof EList)
            {
                return (EList<MdObject>) result;
            }
        }
        catch (Exception ignored)
        {
            // type does not expose getTabularSections()
        }
        return null;
    }

    /**
     * Looks up a child by case-insensitive name in the given list.
     */
    public static MdObject findByName(EList<? extends MdObject> list, String name)
    {
        if (list == null || name == null)
        {
            return null;
        }
        for (MdObject child : list)
        {
            if (name.equalsIgnoreCase(child.getName()))
            {
                return child;
            }
        }
        return null;
    }

    /**
     * Maps a child-FQN kind segment to its containment-list getter on the
     * parent metadata object. Accepts the English kind name and the Russian
     * aliases used in FQNs / adopt_child. Returns {@code null} for an unknown
     * kind (the caller then treats the FQN as a plain top-object FQN).
     *
     * @param kind the kind segment (e.g. {@code Attribute} / {@code Реквизит})
     * @return the getter name (e.g. {@code getAttributes}) or {@code null}
     */
    public static String childKindGetter(String kind)
    {
        if (kind == null)
        {
            return null;
        }
        switch (kind)
        {
            case "Attribute": //$NON-NLS-1$
            case "Реквизит": //$NON-NLS-1$
                return "getAttributes"; //$NON-NLS-1$
            case "TabularSection": //$NON-NLS-1$
            case "ТабличнаяЧасть": //$NON-NLS-1$
                return "getTabularSections"; //$NON-NLS-1$
            case "Dimension": //$NON-NLS-1$
            case "Измерение": //$NON-NLS-1$
                return "getDimensions"; //$NON-NLS-1$
            case "Resource": //$NON-NLS-1$
            case "Ресурс": //$NON-NLS-1$
                return "getResources"; //$NON-NLS-1$
            case "AccountingFlag": //$NON-NLS-1$
            case "ПризнакУчета": //$NON-NLS-1$
                return "getAccountingFlags"; //$NON-NLS-1$
            case "ExtDimensionAccountingFlag": //$NON-NLS-1$
            case "ПризнакУчетаСубконто": //$NON-NLS-1$
                return "getExtDimensionAccountingFlags"; //$NON-NLS-1$
            case "Command": //$NON-NLS-1$
            case "Команда": //$NON-NLS-1$
                return "getCommands"; //$NON-NLS-1$
            case "EnumValue": //$NON-NLS-1$
            case "ЗначениеПеречисления": //$NON-NLS-1$
                return "getEnumValues"; //$NON-NLS-1$
            case "AddressingAttribute": //$NON-NLS-1$
            case "РеквизитАдресации": //$NON-NLS-1$
                return "getAddressingAttributes"; //$NON-NLS-1$
            case "Column": //$NON-NLS-1$
            case "Графа": //$NON-NLS-1$
                return "getColumns"; //$NON-NLS-1$
            case "StandardAttribute": //$NON-NLS-1$
            case "СтандартныйРеквизит": //$NON-NLS-1$
                return "getStandardAttributes"; //$NON-NLS-1$
            default:
                return null;
        }
    }

    /** {@code true} when the segment names a known child kind (see {@link #childKindGetter}). */
    public static boolean isChildKind(String segment)
    {
        return childKindGetter(segment) != null;
    }

    /**
     * Returns the containment list for a child kind reflectively (e.g.
     * {@code getAttributes()} / {@code getDimensions()}), or {@code null} when
     * the owner has no such collection.
     *
     * @param owner the parent metadata object
     * @param kind the child kind (English or Russian)
     * @return the EList, or {@code null}
     */
    @SuppressWarnings("unchecked")
    public static EList<? extends EObject> getChildListByKind(EObject owner, String kind)
    {
        String getter = childKindGetter(kind);
        if (getter == null || owner == null)
        {
            return null;
        }
        try
        {
            Object res = owner.getClass().getMethod(getter).invoke(owner);
            if (res instanceof EList)
            {
                // Children are EObjects; most are MdObject, but some (e.g.
                // StandardAttribute) extend only DataHistorySupport - so the
                // element type is EObject, not MdObject.
                return (EList<? extends EObject>) res;
            }
        }
        catch (Exception ignored)
        {
            // type does not expose this collection
        }
        return null;
    }

    /**
     * Resolves a nested child metadata object from a child-FQN tail: walks
     * pairs of {@code (kind, name)} in {@code segments} starting at
     * {@code startIdx}, navigating containment lists from {@code top}. Supports
     * nested paths such as {@code TabularSection.T.Attribute.Y}. Returns
     * {@code null} when any segment fails to resolve.
     *
     * @param top the resolved top metadata object
     * @param segments the full FQN split on '.'
     * @param startIdx index of the first kind segment (usually 2)
     * @return the resolved child, or {@code null}
     */
    public static EObject resolveChildByPath(EObject top, String[] segments, int startIdx)
    {
        // Reject a malformed child tail: child segments must come in (kind, name)
        // pairs. An odd count (e.g. "Document.X.Attribute" - a trailing kind with
        // no name) or no child segments at all returns null instead of silently
        // falling back to `top` (which would write the property to the parent).
        if (top == null || segments == null
            || segments.length <= startIdx
            || (segments.length - startIdx) % 2 != 0)
        {
            return null;
        }
        EObject current = top;
        for (int i = startIdx; i + 1 < segments.length; i += 2)
        {
            EList<? extends EObject> list = getChildListByKind(current, segments[i]);
            EObject found = findChildByNameReflective(list, segments[i + 1]);
            if (found == null && isStandardAttributeKind(segments[i]))
            {
                // Standard attributes (Date / Number / Posted / ...) are lazy on
                // a freshly-created object - not in getStandardAttributes() until
                // customized. Materialize the entry so its property can be set.
                found = materializeStandardAttribute(current, segments[i + 1]);
            }
            if (found == null)
            {
                return null;
            }
            current = found;
        }
        return current;
    }

    /**
     * Finds a child EObject by case-insensitive name reflectively via
     * {@code getName()}. Works for both MdObject children (Attribute /
     * TabularSection / Dimension / ...) and non-MdObject children such as
     * {@code StandardAttribute} (which exposes {@code getName()} but extends
     * only DataHistorySupport, not MdObject).
     *
     * @param list the containment list (elements are EObjects)
     * @param name the child name to match
     * @return the child that matched, or {@code null} when the collection holds no such name
     */
    private static EObject findChildByNameReflective(EList<? extends EObject> list, String name)
    {
        if (list == null || name == null)
        {
            return null;
        }
        for (EObject child : list)
        {
            try
            {
                Object nm = child.getClass().getMethod("getName").invoke(child); //$NON-NLS-1$
                if (nm != null && name.equalsIgnoreCase(nm.toString()))
                {
                    return child;
                }
            }
            catch (Exception ignored)
            {
                // child exposes no getName() - skip
            }
        }
        return null;
    }

    /** {@code true} when the kind segment names the StandardAttribute collection. */
    private static boolean isStandardAttributeKind(String kind)
    {
        return "StandardAttribute".equals(kind) || "СтандартныйРеквизит".equals(kind); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * {@code true} for a recognized predefined StandardAttribute name across the
     * common metadata types (Catalog / Document / Register / ChartOf* / ...).
     * Used to avoid materializing a garbage entry from a typo'd FQN. Not
     * exhaustive for every exotic type, but covers the names that actually carry
     * customizable per-attribute properties.
     */
    private static boolean isKnownStandardAttributeName(String name)
    {
        if (name == null)
        {
            return false;
        }
        switch (name)
        {
            case "Ref": //$NON-NLS-1$
            case "DeletionMark": //$NON-NLS-1$
            case "Code": //$NON-NLS-1$
            case "Description": //$NON-NLS-1$
            case "Date": //$NON-NLS-1$
            case "Number": //$NON-NLS-1$
            case "Posted": //$NON-NLS-1$
            case "Owner": //$NON-NLS-1$
            case "Parent": //$NON-NLS-1$
            case "IsFolder": //$NON-NLS-1$
            case "Predefined": //$NON-NLS-1$
            case "PredefinedDataName": //$NON-NLS-1$
            case "Presentation": //$NON-NLS-1$
            case "Period": //$NON-NLS-1$
            case "Recorder": //$NON-NLS-1$
            case "LineNumber": //$NON-NLS-1$
            case "Active": //$NON-NLS-1$
                return true;
            default:
                return false;
        }
    }

    /**
     * Materializes a predefined {@code StandardAttribute} (Date / Number /
     * Posted / Ref / DeletionMark / ...) into the owner's
     * {@code getStandardAttributes()} list so its properties (fillChecking /
     * fillValue / fullTextSearch) can be set. On a freshly-created object the
     * standard attributes are NOT present in the list - they are lazy and EDT
     * only materializes one when it is customized. We create the entry with the
     * given name (EDT binds it to the predefined attribute by name on load).
     *
     * @param owner the metadata object (Document / Catalog / ...)
     * @param name the standard attribute name (e.g. {@code Date})
     * @return the existing or freshly-created StandardAttribute, or {@code null}
     */
    @SuppressWarnings("unchecked")
    public static EObject materializeStandardAttribute(EObject owner, String name)
    {
        EList<? extends EObject> list = getChildListByKind(owner, "StandardAttribute"); //$NON-NLS-1$
        if (list == null)
        {
            return null;
        }
        EObject existing = findChildByNameReflective(list, name);
        if (existing != null)
        {
            return existing;
        }
        // Guard against a typo creating a garbage standard attribute: only
        // materialize a recognized predefined name. An unknown name returns null
        // (the caller reports "Child element not found"), which is safer than
        // committing a bogus <standardAttributes> entry EDT cannot bind.
        if (!isKnownStandardAttributeName(name))
        {
            return null;
        }
        try
        {
            Class<?> factoryClass =
                Class.forName("com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory"); //$NON-NLS-1$
            Object factory = factoryClass.getField("eINSTANCE").get(null); //$NON-NLS-1$
            Object stdAttr = factoryClass.getMethod("createStandardAttribute").invoke(factory); //$NON-NLS-1$
            stdAttr.getClass().getMethod("setName", String.class).invoke(stdAttr, name); //$NON-NLS-1$
            ((EList<EObject>) list).add((EObject) stdAttr);
            return (EObject) stdAttr;
        }
        catch (Exception e)
        {
            Activator.logWarning("materializeStandardAttribute(" + name + ") failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
            return null;
        }
    }

    /**
     * Builds a {@link MetadataGuards.BlockedGuardException} carrying an
     * {@code alreadyExists} tag. Use this in mutation lambdas instead of a
     * plain {@code RuntimeException} so the response surfaces a structured
     * field next to the human-readable error.
     */
    public static MetadataGuards.BlockedGuardException alreadyExists(String childName,
        String ownerFqn, String kind)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", childName); //$NON-NLS-1$
        data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
        data.put("kind", kind); //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            kind + " already exists: " + childName, //$NON-NLS-1$
            "Use the matching remove operation first, or pick a different name.", //$NON-NLS-1$
            new MetadataGuards.ErrorTag(ErrorTags.ALREADY_EXISTS.wire(), data)));
    }

    /**
     * Builds a {@link MetadataGuards.BlockedGuardException} carrying a
     * {@code notFound} tag.
     */
    public static MetadataGuards.BlockedGuardException notFound(String childName, String ownerFqn,
        String kind)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", childName); //$NON-NLS-1$
        data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
        data.put("kind", kind); //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            kind + " not found: " + childName, //$NON-NLS-1$
            "Verify the name and try again.", //$NON-NLS-1$
            new MetadataGuards.ErrorTag(ErrorTags.NOT_FOUND.wire(), data)));
    }

    // -----------------------------------------------------------------------
    // 1.40: Idempotency property comparison
    // -----------------------------------------------------------------------

    /**
     * Compares the requested properties against the existing object's getters
     * via reflection. Returns a list of mismatch records (one per non-matching
     * property) or an empty list when every requested property matches.
     * <p>
     * Property names are EMF/JavaBean style ({@code lengthOfDescription},
     * {@code hierarchical}, {@code type}, ...). Values are stringified via
     * {@code toString()} for comparison - sufficient for primitives, enums and
     * String-valued properties. For deep type compositions use specific
     * comparators outside this helper.
     *
     * @param existing  the object found in the model (must not be null)
     * @param requested map propertyName -&gt; requested-value-as-string (case-sensitive on key)
     * @return list of mismatch records, each shaped
     *         {@code {name, requested, existing}} - never null
     */
    public static java.util.List<Map<String, Object>> compareProperties(MdObject existing,
        Map<String, String> requested)
    {
        java.util.List<Map<String, Object>> mismatches = new java.util.ArrayList<>();
        if (existing == null || requested == null || requested.isEmpty())
        {
            return mismatches;
        }
        for (Map.Entry<String, String> entry : requested.entrySet())
        {
            String name = entry.getKey();
            String requestedValue = entry.getValue();
            if (name == null || name.isEmpty())
            {
                continue;
            }
            String existingValue = readProperty(existing, name);
            if (existingValue == null && requestedValue == null)
            {
                continue;
            }
            if (existingValue != null && existingValue.equals(requestedValue))
            {
                continue;
            }
            // case-insensitive equality for boolean/enum-like strings
            if (existingValue != null && requestedValue != null
                && existingValue.equalsIgnoreCase(requestedValue))
            {
                continue;
            }
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("name", name); //$NON-NLS-1$
            mm.put("requested", requestedValue == null ? "" : requestedValue); //$NON-NLS-1$ //$NON-NLS-2$
            mm.put("existing", existingValue == null ? "" : existingValue); //$NON-NLS-1$ //$NON-NLS-2$
            mismatches.add(mm);
        }
        return mismatches;
    }

    /**
     * Reads a property by JavaBean getter via reflection. Returns the value
     * stringified via {@code toString()}, or {@code null} when no matching
     * getter exists or the value is null.
     */
    private static String readProperty(Object obj, String propertyName)
    {
        if (obj == null || propertyName == null || propertyName.isEmpty())
        {
            return null;
        }
        String capName = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        // Try get<Name>() and is<Name>() (boolean)
        for (String prefix : new String[] { "get", "is" })
        {
            try
            {
                Method m = obj.getClass().getMethod(prefix + capName);
                Object result = m.invoke(obj);
                return result == null ? null : result.toString();
            }
            catch (NoSuchMethodException ignored)
            {
                // try next
            }
            catch (Exception e)
            {
                Activator.logWarning("readProperty " + propertyName + " failed: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
        }
        return null;
    }

    /**
     * Builds a {@code propertyMismatch} {@link MetadataGuards.BlockedGuardException}
     * for use in idempotent mutation lambdas: when an existing object is found
     * AND its actual properties differ from the requested ones, throw this
     * exception instead of {@link #alreadyExists(String, String, String)}.
     * <p>
     * The resulting tag carries {@code mismatches=[{name,requested,existing}]}
     * plus context fields - AI agents branch on it to call
     * {@code setObjectProperty} for each diff and re-run.
     */
    public static MetadataGuards.BlockedGuardException propertyMismatch(String childName,
        String ownerFqn, String kind, java.util.List<Map<String, Object>> mismatches)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", childName); //$NON-NLS-1$
        data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
        data.put("kind", kind); //$NON-NLS-1$
        data.put("mismatches", mismatches); //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            kind + " '" + childName + "' already exists with different properties.", //$NON-NLS-1$ //$NON-NLS-2$
            "Use setObjectProperty to update each mismatching property, or remove the object and recreate.", //$NON-NLS-1$
            new MetadataGuards.ErrorTag(ErrorTags.PROPERTY_MISMATCH.wire(), data)));
    }

    /**
     * Builds a {@code idempotentSkip} success-style tag (not an exception):
     * surface this in the mutation lambda's return value to indicate
     * "object already exists with matching properties - nothing to do".
     * <p>
     * Caller does NOT throw; instead returns the result string and adds the
     * tag to {@link Result#tags} after the lambda completes successfully.
     */
    public static Map<String, Object> idempotentSkipTag(String childName, String ownerFqn,
        String kind)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", childName); //$NON-NLS-1$
        data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
        data.put("kind", kind); //$NON-NLS-1$
        return data;
    }
}
