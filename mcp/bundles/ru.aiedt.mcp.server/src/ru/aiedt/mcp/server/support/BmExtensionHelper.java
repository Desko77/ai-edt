/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdObjectExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;
import ru.aiedt.mcp.server.Activator;

/**
 * Configuration-extension borrowing operations for {@code extension_workshop}.
 * <p>
 * <b>1.37 status:</b> probes the EDT adopt service across known candidate
 * packages and exposes a single best-effort entry point
 * {@link #attemptBorrow(IProject, String, String, String)} that returns a
 * structured {@link BorrowResult}. When the service or its API contract is
 * not reachable the result carries {@code adoptServiceNotFound=true} with a
 * GUI workaround hint - callers (the tool dispatcher) surface this as a
 * structured response tag so AI agents can branch on it.
 */
public final class BmExtensionHelper
{
    private static final String[] CANDIDATE_PACKAGES = {
        "com._1c.g5.v8.dt.md.extension.adopt.IMdAdoptObjectsService", //$NON-NLS-1$
        "com._1c.g5.v8.dt.metadata.extension.IMdAdoptObjectsService", //$NON-NLS-1$
        "com._1c.g5.v8.dt.md.extension.IMdAdoptObjectsService", //$NON-NLS-1$
        "com._1c.g5.v8.dt.md.adopt.IMdAdoptObjectsService" //$NON-NLS-1$
    };

    private static volatile String cachedClassName;
    private static volatile Boolean cachedProbed;

    private BmExtensionHelper()
    {
        // utility class
    }

    public static String resolvedAdoptServiceClass()
    {
        if (cachedProbed != null)
        {
            return cachedClassName;
        }
        synchronized (BmExtensionHelper.class)
        {
            if (cachedProbed != null)
            {
                return cachedClassName;
            }
            // 1.42.3: prefer the EDT 2026.1 IModelObjectAdopter contract (the
            // interface every md-extension consumer @Inject's). The old OSGi
            // service classes (IMdAdoptObjectsService) were never part of the
            // public API and are absent in 2026.1.
            String preferred = "com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter"; //$NON-NLS-1$
            try
            {
                Class.forName(preferred);
                cachedClassName = preferred;
            }
            catch (ClassNotFoundException ignored)
            {
                // fall through to legacy candidates
            }
            if (cachedClassName == null)
            {
                for (String candidate : CANDIDATE_PACKAGES)
                {
                    try
                    {
                        Class.forName(candidate);
                        cachedClassName = candidate;
                        break;
                    }
                    catch (ClassNotFoundException ignored)
                    {
                        // try next
                    }
                }
            }
            cachedProbed = Boolean.TRUE;
            if (cachedClassName == null)
            {
                Activator.logWarning(
                    "BmExtensionHelper: adopt service not found in any candidate package"); //$NON-NLS-1$
            }
        }
        return cachedClassName;
    }

    /**
     * Resolves the {@code IModelObjectAdopter} singleton through the
     * {@code com._1c.g5.v8.dt.internal.md.extension.MdExtensionPlugin}
     * Guice injector. This is how every EDT consumer gets an adopter
     * (the interface itself has no {@code @ImplementedBy}, so a manual
     * {@code Class.forName} + new is impossible). All access is reflective
     * to keep the MCP plugin from hard-linking against
     * {@code com._1c.g5.v8.dt.internal.md.extension}.
     *
     * @return the IModelObjectAdopter instance or null when the plugin /
     *     injector is not reachable on this EDT runtime
     */
    static Object resolveModelObjectAdopter()
    {
        try
        {
            // 1. First try OSGi service lookup. EDT may register
            // IModelObjectAdopter as an OSGi service in some configurations.
            BundleContext bc = FrameworkUtil.getBundle(BmExtensionHelper.class)
                .getBundleContext();
            if (bc != null)
            {
                ServiceReference<?> ref = bc.getServiceReference(
                    "com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter"); //$NON-NLS-1$
                if (ref != null)
                {
                    Object svc = bc.getService(ref);
                    if (svc != null)
                    {
                        return svc;
                    }
                }
            }
            // 2. Fall back to MdExtensionPlugin's Guice injector. The Plugin
            // class lives in com._1c.g5.v8.dt.internal.md.extension which is
            // x-internal, so Class.forName from this bundle hits a
            // ClassNotFoundException. Bundle.loadClass bypasses the OSGi
            // package-visibility check by going through the owning bundle's
            // classloader directly.
            org.osgi.framework.Bundle mdExtBundle = org.eclipse.core.runtime.Platform
                .getBundle("com._1c.g5.v8.dt.md.extension"); //$NON-NLS-1$
            if (mdExtBundle == null)
            {
                Activator.logWarning(
                    "Bundle com._1c.g5.v8.dt.md.extension not present"); //$NON-NLS-1$
                return null;
            }
            Class<?> pluginClass = mdExtBundle.loadClass(
                "com._1c.g5.v8.dt.internal.md.extension.MdExtensionPlugin"); //$NON-NLS-1$
            Method getDefault = pluginClass.getMethod("getDefault"); //$NON-NLS-1$
            Object plugin = getDefault.invoke(null);
            if (plugin == null)
            {
                Activator.logWarning(
                    "MdExtensionPlugin.getDefault() returned null - bundle not started yet"); //$NON-NLS-1$
                return null;
            }
            Method getInjector = pluginClass.getMethod("getInjector"); //$NON-NLS-1$
            Object injector = getInjector.invoke(plugin);
            if (injector == null)
            {
                Activator.logWarning(
                    "MdExtensionPlugin.getInjector() returned null"); //$NON-NLS-1$
                return null;
            }
            Class<?> adopterIface = mdExtBundle.loadClass(
                "com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter"); //$NON-NLS-1$
            Method getInstance = injector.getClass().getMethod("getInstance", Class.class); //$NON-NLS-1$
            return getInstance.invoke(injector, adopterIface);
        }
        catch (ClassNotFoundException cnf)
        {
            Activator.logWarning("MdExtensionPlugin / IModelObjectAdopter not on classpath: " //$NON-NLS-1$
                + cnf.getMessage());
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveModelObjectAdopter failed: " //$NON-NLS-1$
                + e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    public static boolean isAvailable()
    {
        return resolvedAdoptServiceClass() != null;
    }

    public static String deferredMessage(String operation)
    {
        String resolved = resolvedAdoptServiceClass();
        return "Extension operation '" + operation //$NON-NLS-1$
            + "' did not complete. " //$NON-NLS-1$
            + (resolved != null
                ? "Adopt service discovered: " + resolved //$NON-NLS-1$
                    + " - the API contract is best-effort in 1.37 and may need an update." //$NON-NLS-1$
                : "Adopt service NOT reachable in this EDT version. " //$NON-NLS-1$
                    + "Use EDT GUI: right-click base object - Borrow into extension."); //$NON-NLS-1$
    }

    /**
     * Outcome of a borrow attempt.
     */
    public static final class BorrowResult
    {
        public boolean ok;
        public boolean adoptServiceNotFound;
        public boolean alreadyBorrowed;
        public String error;
        public String discoveredApi;
        public Map<String, Object> tags = new LinkedHashMap<>();
    }

    /**
     * Borrows {@code targetFqn} via the EDT 2026.1 IModelObjectAdopter
     * contract: resolves the adopter through {@code MdExtensionPlugin}'s
     * Guice injector, finds the source MdObject in the base configuration,
     * locates the IExtensionProject for the target extension, and invokes
     * {@code adoptAndAttach(source, extension, monitor)}.
     *
     * <p>Returns {@code BorrowResult} with one of three outcomes:
     * <ul>
     *   <li>{@code ok=true} - object successfully borrowed (or already
     *       borrowed - we treat that as idempotent success).</li>
     *   <li>{@code adoptServiceNotFound=true} - the adopter or extension
     *       project resolver was not reachable via reflection (caller may
     *       fall back to the legacy OSGi path).</li>
     *   <li>{@code adoptServiceNotFound=false, error=...} - the adopter
     *       was found but invocation failed (real error worth surfacing).</li>
     * </ul>
     */
    private static BorrowResult attemptBorrowViaAdopter(IProject extension,
        String baseProjectName, String targetFqn, String childKind)
    {
        BorrowResult r = new BorrowResult();
        r.discoveredApi = "com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter"; //$NON-NLS-1$
        Object adopter = resolveModelObjectAdopter();
        if (adopter == null)
        {
            r.adoptServiceNotFound = true;
            r.error = "MdExtensionPlugin Guice injector could not produce IModelObjectAdopter"; //$NON-NLS-1$
            populateNotFoundTag(r, "borrow", targetFqn); //$NON-NLS-1$
            return r;
        }
        // 1. Resolve IExtensionProject for the extension. Needed for adoptAndAttach and,
        // when baseProjectName is omitted, to auto-derive the base = its parent configuration.
        Object extProject = resolveExtensionProject(extension);
        if (extProject == null)
        {
            r.adoptServiceNotFound = false;
            r.error = "Could not resolve IExtensionProject for " + extension.getName() //$NON-NLS-1$
                + " (project may not be an extension or DT project layer not initialised)"; //$NON-NLS-1$
            return r;
        }
        // 2. Resolve base project. An explicit baseProjectName wins; otherwise auto-resolve it
        // from the extension's parent configuration (IExtensionProject.getParentProject(),
        // declared on IDependentProject) - completing what callers previously had to supply on
        // every borrow.
        boolean explicitBase = baseProjectName != null && !baseProjectName.isEmpty();
        IProject baseProject = explicitBase
            ? org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()
                .getProject(baseProjectName)
            : deriveParentProject(extProject);
        if (baseProject == null || !baseProject.exists())
        {
            r.adoptServiceNotFound = false;
            r.error = explicitBase
                ? "baseProjectName '" + baseProjectName + "' does not resolve to an existing project." //$NON-NLS-1$ //$NON-NLS-2$
                : "Could not auto-resolve the base configuration from extension " //$NON-NLS-1$
                    + extension.getName() + " (IExtensionProject.getParentProject() returned null). " //$NON-NLS-1$
                    + "Pass baseProjectName explicitly."; //$NON-NLS-1$
            return r;
        }
        // 3. Find source EObject by FQN in the base configuration.
        // 1.43.1: resolveSourceEObject supports child segments
        // (Catalog.X.Form.Y, Document.X.Attribute.Z, ...). The previous
        // resolveSourceMdObject only handled two-segment FQNs and silently
        // returned null for any deeper path.
        Object source = resolveSourceEObject(baseProject, targetFqn);
        if (source == null)
        {
            r.adoptServiceNotFound = false;
            r.error = "Source object " + targetFqn + " not found in base project " //$NON-NLS-1$ //$NON-NLS-2$
                + baseProject.getName()
                + ". Expected FQN forms: 'Catalog.Name', 'Catalog.Name.Form.FormName', " //$NON-NLS-1$
                + "'Document.Name.Attribute.AttrName', 'InformationRegister.Name.Resource.ResName' etc."; //$NON-NLS-1$
            return r;
        }
        // 4. Call adoptAndAttach(source, extension, monitor).
        try
        {
            Method adoptAndAttach = adopter.getClass().getMethod("adoptAndAttach", //$NON-NLS-1$
                org.eclipse.emf.ecore.EObject.class,
                Class.forName("com._1c.g5.v8.dt.core.platform.IExtensionProject"), //$NON-NLS-1$
                org.eclipse.core.runtime.IProgressMonitor.class);
            Object result = adoptAndAttach.invoke(adopter, source, extProject, null);
            Map<String, Object> okTag = new LinkedHashMap<>();
            okTag.put("targetFqn", targetFqn); //$NON-NLS-1$
            okTag.put("baseProject", baseProject.getName()); //$NON-NLS-1$
            okTag.put("baseProjectAutoResolved", !explicitBase); //$NON-NLS-1$
            okTag.put("api", "IModelObjectAdopter.adoptAndAttach"); //$NON-NLS-1$ //$NON-NLS-2$
            if (result != null)
            {
                okTag.put("returned", result.getClass().getSimpleName()); //$NON-NLS-1$
            }
            LinkageOutcome linkage =
                completeExtensionLinkage(extension, result, source, childKind, targetFqn);
            reportLinkage(linkage, okTag);
            r.tags.put("borrowed", okTag); //$NON-NLS-1$
            if (!linkage.complete)
            {
                // The adopt is committed and the object is in the extension; only the link
                // is missing. Reporting success here would hand back exactly the working-looking
                // dead extension this method exists to prevent.
                r.error = linkageErrorMessage(targetFqn, extension, linkage);
                return r;
            }
            r.ok = true;
            return r;
        }
        catch (Exception invokeEx)
        {
            Throwable cause = invokeEx.getCause() != null ? invokeEx.getCause() : invokeEx;
            String msg = cause.getMessage() != null ? cause.getMessage()
                : cause.getClass().getSimpleName();
            if (msg != null && msg.toLowerCase().contains("already")) //$NON-NLS-1$
            {
                return resolveAlreadyException(extension, baseProjectName, targetFqn, childKind,
                    msg, "IModelObjectAdopter.adoptAndAttach", r); //$NON-NLS-1$
            }
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("targetFqn", targetFqn); //$NON-NLS-1$
            tag.put("error", msg); //$NON-NLS-1$
            tag.put("api", "IModelObjectAdopter.adoptAndAttach"); //$NON-NLS-1$ //$NON-NLS-2$
            r.tags.put("adoptInvocationFailed", tag); //$NON-NLS-1$
            r.error = "adoptAndAttach failed: " + msg; //$NON-NLS-1$
            return r;
        }
    }

    /**
     * What a linkage pass found: what it wrote, whether the object really extends
     * afterwards, and - kept apart from that - whether the pass ever got to look.
     * <p>
     * The three states are deliberately distinct. "Wrote nothing because everything
     * was already in place" and "wrote nothing because the object was not there" both
     * used to come back as an empty list and got reported as success; that is the
     * defect this whole repair exists to remove, reproduced one level up.
     * </p>
     */
    private static final class LinkageOutcome
    {
        /** True only when the link was read back off the object after the pass. */
        boolean complete;

        /** True once the pass actually had the object in hand. */
        boolean checked;

        /** What this pass wrote. Empty when the link was already in place. */
        final List<String> written = new java.util.ArrayList<>();

        /** Why the link is not in place. Null when {@link #complete}. */
        String problem;

        /** Set when the write landed in the model but not yet in the file. */
        String flushNote;
    }

    /**
     * Writes the link that makes an adopted object actually extend the one it was
     * adopted from, and then reads it back.
     * <p>
     * {@code adoptAndAttach} puts the object into the extension and stops there. What it
     * leaves out is the whole point of adopting: the {@code .mdo} comes back without
     * {@code extendedConfigurationObject} on the root, without
     * {@code <extendedConfigurationObject>Checked</extendedConfigurationObject>} in the
     * extension block, and - for a module - without {@code <module>Extended</module>}.
     * Such an extension does not extend anything, and <b>nothing says so</b>: measured on
     * a clean probe, borrowing a common module returned success and
     * {@code get_project_errors severity=ALL} then reported zero findings. Whoever wrote
     * the module afterwards had a working-looking project and dead code.
     * </p>
     * <p>
     * The two properties that apply to every kind - the base object's uuid and Checked -
     * are written for any adopted object. {@code module=Extended} is written only when a
     * MODULE was asked for and the extension block is a common module's; for other kinds
     * the module flag lives under a different property, so it is reported as not applied
     * rather than guessed at.
     * </p>
     * <p>
     * Anything already set is left alone: EDT may fill some of this itself on a runtime
     * that behaves differently, and overwriting its answer would be the opposite of the
     * repair.
     * </p>
     *
     * @param extension the extension project the object now lives in.
     * @param adopted what {@code adoptAndAttach} returned.
     * @param source the base object it was adopted from.
     * @param childKind what the caller asked to borrow ({@code Module} for a module).
     * @param targetFqn the object as the caller named it, for flushing what was written.
     * @return what the pass wrote and whether the object extends afterwards.
     */
    private static LinkageOutcome completeExtensionLinkage(IProject extension, Object adopted,
        Object source, String childKind, String targetFqn)
    {
        LinkageOutcome outcome = new LinkageOutcome();
        final String moduleKind = moduleKindOf(childKind);
        if (!(adopted instanceof MdObject) || !(adopted instanceof IBmObject))
        {
            outcome.problem = adopted == null
                ? "the adopter returned nothing to link" //$NON-NLS-1$
                : "the adopted object is a " + adopted.getClass().getSimpleName() //$NON-NLS-1$
                    + ", not a metadata object in the object model"; //$NON-NLS-1$
            return outcome;
        }
        if (!(source instanceof MdObject) || ((MdObject)source).getUuid() == null)
        {
            outcome.problem = "the base object carries no uuid, so there is nothing to link to"; //$NON-NLS-1$
            return outcome;
        }
        final java.util.UUID sourceUuid = ((MdObject)source).getUuid();
        final long bmId = ((IBmObject)adopted).bmGetId();
        try
        {
            IBmModelManager modelManager = Activator.getDefault().getBmModelManager();
            IBmModel model = modelManager == null ? null : modelManager.getModel(extension);
            if (model == null)
            {
                outcome.problem = "no object model for " + extension.getName(); //$NON-NLS-1$
                return outcome;
            }
            LinkageOutcome applied =
                model.execute(new AbstractBmTask<LinkageOutcome>("completeExtensionLinkage") //$NON-NLS-1$
                {
                    @Override
                    public LinkageOutcome execute(IBmTransaction tx,
                        org.eclipse.core.runtime.IProgressMonitor pm)
                    {
                        return applyLinkage(tx.getObjectById(bmId), sourceUuid, moduleKind);
                    }
                });
            if (applied != null)
            {
                flushLinkage(extension, targetFqn, applied);
                return applied;
            }
            outcome.problem = "the object model ran the linkage task and returned nothing"; //$NON-NLS-1$
            return outcome;
        }
        catch (Exception e)
        {
            // Said out loud rather than swallowed: without this link the extension does
            // not extend, and a silent failure here is the exact defect being repaired.
            outcome.problem = e.getClass().getSimpleName() + ": " + e.getMessage(); //$NON-NLS-1$
            Activator.logWarning("completeExtensionLinkage failed for " //$NON-NLS-1$
                + extension.getName() + ": " + outcome.problem); //$NON-NLS-1$
            return outcome;
        }
    }

    /**
     * Runs the same linkage pass over an object that is ALREADY in the extension,
     * addressed by FQN instead of by what an adopter just returned.
     * <p>
     * Needed because a borrow that landed before this repair existed - or one whose
     * linkage failed halfway - is otherwise unreachable: the second call short-circuits
     * on "already borrowed" and the caller is left hand-editing the {@code .mdo}, which
     * is the thing this plugin exists to stop. Calling borrow again now repairs it.
     * </p>
     *
     * @param extension the extension project holding the adopted object.
     * @param baseProjectName base configuration, or empty to derive it from the parent.
     * @param targetFqn the object to check, as the caller named it.
     * @param childKind what the caller asked to borrow ({@code Module} for a module).
     * @return what the pass wrote and whether the object extends afterwards.
     */
    private static LinkageOutcome repairExtensionLinkage(IProject extension, String baseProjectName,
        String targetFqn, String childKind)
    {
        LinkageOutcome outcome = new LinkageOutcome();
        final String moduleKind = moduleKindOf(childKind);
        try
        {
            Object extProject = resolveExtensionProject(extension);
            boolean explicitBase = baseProjectName != null && !baseProjectName.isEmpty();
            IProject baseProject = explicitBase
                ? org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()
                    .getProject(baseProjectName)
                : (extProject == null ? null : deriveParentProject(extProject));
            if (baseProject == null || !baseProject.exists())
            {
                outcome.problem =
                    "could not reach the base configuration to read the uuid to link to"; //$NON-NLS-1$
                return outcome;
            }
            Object source = resolveSourceEObject(baseProject, targetFqn);
            if (!(source instanceof MdObject) || ((MdObject)source).getUuid() == null)
            {
                outcome.problem = targetFqn + " does not resolve to a uuid-carrying object in " //$NON-NLS-1$
                    + baseProject.getName();
                return outcome;
            }
            final java.util.UUID sourceUuid = ((MdObject)source).getUuid();
            Object held = resolveInExtension(extension, normalizeFqnSafely(targetFqn));
            if (!(held instanceof IBmObject))
            {
                outcome.problem = held == null
                    ? "the extension does not hold " + targetFqn + " in a form this can link" //$NON-NLS-1$ //$NON-NLS-2$
                    : "what the extension holds under that name is a " //$NON-NLS-1$
                        + held.getClass().getSimpleName() + ", which the object model does not track"; //$NON-NLS-1$
                return outcome;
            }
            final long bmId = ((IBmObject)held).bmGetId();
            IBmModelManager modelManager = Activator.getDefault().getBmModelManager();
            IBmModel model = modelManager == null ? null : modelManager.getModel(extension);
            if (model == null)
            {
                outcome.problem = "no object model for " + extension.getName(); //$NON-NLS-1$
                return outcome;
            }
            LinkageOutcome applied =
                model.execute(new AbstractBmTask<LinkageOutcome>("repairExtensionLinkage") //$NON-NLS-1$
                {
                    @Override
                    public LinkageOutcome execute(IBmTransaction tx,
                        org.eclipse.core.runtime.IProgressMonitor pm)
                    {
                        return applyLinkage(tx.getObjectById(bmId), sourceUuid, moduleKind);
                    }
                });
            if (applied != null)
            {
                flushLinkage(extension, targetFqn, applied);
                return applied;
            }
            outcome.problem = "the object model ran the linkage task and returned nothing"; //$NON-NLS-1$
            return outcome;
        }
        catch (Exception e)
        {
            outcome.problem = e.getClass().getSimpleName() + ": " + e.getMessage(); //$NON-NLS-1$
            Activator.logWarning("repairExtensionLinkage failed for " + targetFqn //$NON-NLS-1$
                + " in " + extension.getName() + ": " + outcome.problem); //$NON-NLS-1$ //$NON-NLS-2$
            return outcome;
        }
    }

    /**
     * Reads the caller's {@code childKind} as a module kind, or nothing when a module was not
     * what was asked for.
     *
     * @param childKind what the caller asked to borrow.
     * @return the module kind, or null.
     */
    private static String moduleKindOf(String childKind)
    {
        if (childKind == null)
        {
            return null;
        }
        String kind = childKind.trim();
        return kind.toLowerCase().endsWith("module") ? kind : null; //$NON-NLS-1$
    }

    /**
     * Finds what the extension holds under a name, the same way the attach check finds it.
     * <p>
     * They have to agree, and they did not. The attach check reaches a form and a child through
     * the model tree; this reached only a top object, so a form or an attribute borrowed by an
     * older build passed the check, found nothing to repair, and came back a success that had
     * repaired nothing - the very defect the repair exists to remove, reproduced by the repair.
     * </p>
     * <p>
     * Measured live: a form borrowed into an extension is not a top object there at all, under
     * either spelling. Only the tree finds it.
     * </p>
     *
     * @param extension the extension project.
     * @param normalized the FQN, already normalized.
     * @return the object, or null when the extension does not hold it.
     */
    private static Object resolveInExtension(IProject extension, String normalized)
    {
        EObject viaBm = resolveViaBmTransaction(extension, normalized);
        if (viaBm instanceof MdObject)
        {
            return viaBm;
        }
        EObject viaTree = resolveViaEmfFallback(extension, normalized);
        if (viaTree instanceof MdObject)
        {
            return viaTree;
        }
        // A form also answers under a .Form suffix, but with the form's CONTENT rather than its
        // metadata entry; the entry is the one that carries the link, and it is that object's
        // container.
        if (looksLikeFormMetadataFqn(normalized))
        {
            EObject withSuffix = resolveViaBmTransaction(extension, normalized + ".Form"); //$NON-NLS-1$
            if (withSuffix != null)
            {
                EObject entry = ensureMdObjectForForm(withSuffix);
                if (entry instanceof MdObject)
                {
                    return entry;
                }
            }
        }
        return viaBm != null ? viaBm : viaTree;
    }

    /**
     * Writes the missing halves of the link onto an object already attached to a
     * transaction, then reads them back off it.
     * <p>
     * The read-back is the point. Trusting the setters is how the original defect
     * stayed invisible: a pass that writes and never looks cannot tell a runtime that
     * refused the property from one that accepted it.
     * </p>
     *
     * @param inTx the object as the transaction holds it, or null when it is not there.
     * @param sourceUuid uuid of the base object being extended.
     * @param wantModule true when the caller asked for a module override.
     * @return what was written and whether the object extends afterwards.
     */
    private static LinkageOutcome applyLinkage(Object inTx, java.util.UUID sourceUuid,
        String moduleKind)
    {
        LinkageOutcome outcome = new LinkageOutcome();
        if (!(inTx instanceof MdObject))
        {
            outcome.problem = inTx == null
                ? "the object is not in the extension's object model" //$NON-NLS-1$
                : "the extension holds a " + inTx.getClass().getSimpleName() //$NON-NLS-1$
                    + " under that name, not a metadata object"; //$NON-NLS-1$
            return outcome;
        }
        outcome.checked = true;
        MdObject object = (MdObject)inTx;
        java.util.UUID current = object.getExtendedConfigurationObject();
        if (current == null)
        {
            object.setExtendedConfigurationObject(sourceUuid);
            outcome.written.add("extendedConfigurationObject=" + sourceUuid); //$NON-NLS-1$
        }
        else if (!current.equals(sourceUuid))
        {
            // Pointing at the wrong object is worse than pointing at nothing, and it is not ours
            // to overwrite: whatever put it there knew something we do not. Named, not corrected.
            outcome.problem = "the object is linked to " + current + ", not to " + sourceUuid //$NON-NLS-1$ //$NON-NLS-2$
                + " which is the uuid of the base object it was adopted from. It extends " //$NON-NLS-1$
                + "something else, or it was adopted from a different configuration."; //$NON-NLS-1$
            return outcome;
        }
        Object block = object.getExtension();
        if (!(block instanceof MdObjectExtension))
        {
            outcome.problem = block == null
                ? "the object carries no extension block" //$NON-NLS-1$
                : "the extension block is a " + block.getClass().getSimpleName() //$NON-NLS-1$
                    + ", which has no extendedConfigurationObject to set"; //$NON-NLS-1$
            return outcome;
        }
        MdObjectExtension ext = (MdObjectExtension)block;
        if (ext.getExtendedConfigurationObject() != MdPropertyState.CHECKED)
        {
            ext.setExtendedConfigurationObject(MdPropertyState.CHECKED);
            outcome.written.add("extension.extendedConfigurationObject=Checked"); //$NON-NLS-1$
        }
        org.eclipse.emf.ecore.EStructuralFeature moduleFeature = moduleFeatureFor(ext, moduleKind);
        if (moduleKind != null && moduleFeature == null)
        {
            outcome.problem = "a " + moduleKind + " was asked for, but a " + ext.eClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
                + " has no such module to override. It keeps " + moduleFeatureNames(ext) //$NON-NLS-1$
                + " - name one of those as moduleType."; //$NON-NLS-1$
            return outcome;
        }
        if (moduleFeature != null && ext.eGet(moduleFeature) != MdPropertyState.EXTENDED)
        {
            ext.eSet(moduleFeature, MdPropertyState.EXTENDED);
            outcome.written.add("extension." + moduleFeature.getName() + "=Extended"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (object.getExtendedConfigurationObject() == null)
        {
            outcome.problem = "extendedConfigurationObject did not stay on the object"; //$NON-NLS-1$
            return outcome;
        }
        if (ext.getExtendedConfigurationObject() != MdPropertyState.CHECKED)
        {
            outcome.problem =
                "the extension block's extendedConfigurationObject did not become Checked"; //$NON-NLS-1$
            return outcome;
        }
        if (moduleFeature != null && ext.eGet(moduleFeature) != MdPropertyState.EXTENDED)
        {
            outcome.problem = "the module flag did not become Extended, so the module does not " //$NON-NLS-1$
                + "override the base one"; //$NON-NLS-1$
            return outcome;
        }
        outcome.complete = true;
        return outcome;
    }

    /**
     * The property that says "this extension overrides that module", for the module asked for.
     * <p>
     * Each kind of object keeps its own: a common module has {@code module}, a catalog has
     * {@code objectModule} and {@code managerModule}, a register has {@code recordSetModule}. Only
     * the common module was ever set, so borrowing an object module reported success and left the
     * override unwritten - the module sat in the extension and the base one kept running.
     * </p>
     *
     * @param ext the extension block.
     * @param moduleKind what the caller asked to borrow, or null when it was not a module.
     * @return the feature to set, or null when this block has no such module.
     */
    private static org.eclipse.emf.ecore.EStructuralFeature moduleFeatureFor(MdObjectExtension ext,
        String moduleKind)
    {
        if (moduleKind == null)
        {
            return null;
        }
        String kind = moduleKind.trim();
        // A bare "Module" is what borrow_module sends when the caller named no moduleType. It only
        // means something where the object has exactly one module - a common module.
        String name = "Module".equalsIgnoreCase(kind) ? "module" //$NON-NLS-1$ //$NON-NLS-2$
            : Character.toLowerCase(kind.charAt(0)) + kind.substring(1);
        // ValueModule is what the borrow argument calls it; the model calls it valueManagerModule.
        if ("valueModule".equals(name)) //$NON-NLS-1$
        {
            name = "valueManagerModule"; //$NON-NLS-1$
        }
        org.eclipse.emf.ecore.EStructuralFeature feature = ext.eClass().getEStructuralFeature(name);
        return feature != null && feature.getEType().getInstanceClass() == MdPropertyState.class
            ? feature : null;
    }

    /**
     * The module properties this block does have, for an error that can be acted on.
     *
     * @param ext the extension block.
     * @return their names, or a note that it has none.
     */
    private static String moduleFeatureNames(MdObjectExtension ext)
    {
        List<String> names = new java.util.ArrayList<>();
        for (org.eclipse.emf.ecore.EStructuralFeature feature : ext.eClass().getEAllStructuralFeatures())
        {
            if (feature.getName().toLowerCase().endsWith("module") //$NON-NLS-1$
                && feature.getEType().getInstanceClass() == MdPropertyState.class)
            {
                names.add(feature.getName());
            }
        }
        return names.isEmpty() ? "no modules at all" : String.join(", ", names); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Writes what the linkage pass changed out to the {@code .mdo}.
     * <p>
     * The model is not the file. On the fresh-adopt path the EDT adopter exports right after
     * it runs, so the link written beside it went to disk with it; the repair path has no such
     * companion, and measured live it left the model correct and the file untouched - a caller
     * reading the {@code .mdo}, or exporting a {@code .cfe} before the workspace got round to
     * saving, would have seen the old state while being told the new one.
     * </p>
     * <p>
     * Only run when something was written: exporting an object nothing changed on is pure cost.
     * A failure is reported on the outcome rather than thrown, because the model IS correct at
     * that point and the honest answer is "written, not yet saved".
     * </p>
     *
     * @param extension the extension project holding the object.
     * @param targetFqn the object as the caller named it.
     * @param outcome the pass whose writes need flushing.
     */
    private static void flushLinkage(IProject extension, String targetFqn, LinkageOutcome outcome)
    {
        if (outcome == null || outcome.written.isEmpty() || targetFqn == null)
        {
            return;
        }
        try
        {
            IBmModelManager manager = Activator.getDefault().getBmModelManager();
            // The top object, not the child: a form's or an attribute's link is written into the
            // file of the object that owns it, and asking to export the child's own name exported
            // nothing and reported a failure over a link that had in fact been saved.
            BmExportHelper.Result exported =
                BmExportHelper.forceExportAndWait(manager, extension, topObjectFqn(targetFqn));
            if (exported == null || !exported.isOk())
            {
                outcome.flushNote = "the link is written in the model but the .mdo is not saved " //$NON-NLS-1$
                    + "yet - read it after the workspace settles, or force a resync before " //$NON-NLS-1$
                    + "exporting the extension."; //$NON-NLS-1$
            }
            else if (exported.syncFlushPending)
            {
                outcome.flushNote = "the link is written and the save is running, but it did not " //$NON-NLS-1$
                    + "confirm within the wait - check the .mdo before exporting the extension."; //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            outcome.flushNote = "the link is written in the model but saving it to the .mdo " //$NON-NLS-1$
                + "failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * The object whose file holds a link, given the name the caller used.
     *
     * @param targetFqn the object or child, as the caller named it.
     * @return the owning top object's FQN.
     */
    private static String topObjectFqn(String targetFqn)
    {
        String normalized = normalizeFqnSafely(targetFqn);
        String[] segs = normalized.split("\\."); //$NON-NLS-1$
        return segs.length > 2 ? segs[0] + "." + segs[1] : normalized; //$NON-NLS-1$
    }

    /**
     * Puts the outcome of a linkage pass on the response tag, in the words the caller
     * needs: what was written, or what stopped it, or that there was nothing to do.
     *
     * @param outcome the pass to report.
     * @param okTag the tag being built for the response.
     */
    private static void reportLinkage(LinkageOutcome outcome, Map<String, Object> okTag)
    {
        if (outcome.complete)
        {
            okTag.put("extensionLinkage", outcome.written.isEmpty() //$NON-NLS-1$
                ? "already in place - nothing to write" : outcome.written); //$NON-NLS-1$
        }
        else
        {
            okTag.put("extensionLinkageFailed", outcome.problem); //$NON-NLS-1$
            if (!outcome.written.isEmpty())
            {
                okTag.put("extensionLinkagePartial", outcome.written); //$NON-NLS-1$
            }
        }
        if (outcome.flushNote != null)
        {
            okTag.put("extensionLinkageNotSaved", outcome.flushNote); //$NON-NLS-1$
        }
    }

    /**
     * The sentence a caller gets when an object sits in the extension without the link
     * that makes it extend. Shared so the fresh-adopt and already-borrowed paths cannot
     * describe the same condition two different ways.
     *
     * @param targetFqn the object concerned.
     * @param extension the extension project holding it.
     * @param outcome the pass that found the problem.
     * @return the error sentence.
     */
    private static String linkageErrorMessage(String targetFqn, IProject extension,
        LinkageOutcome outcome)
    {
        String problem = outcome.problem == null ? "no reason was recorded" : outcome.problem.trim(); //$NON-NLS-1$
        while (problem.endsWith(".")) //$NON-NLS-1$
        {
            problem = problem.substring(0, problem.length() - 1);
        }
        return targetFqn + " is in " + extension.getName() //$NON-NLS-1$
            + ", but the link that makes the extension extend it is not written: " //$NON-NLS-1$
            + problem + ". The extension overrides nothing in this state. Nothing here needs " //$NON-NLS-1$
            + "undoing - fix what is named above and call borrow again to retry just the link."; //$NON-NLS-1$
    }

    /**
     * Finishes an idempotent "it is already there" answer.
     * <p>
     * Attached is not the same as linked. A borrow that landed before the linkage repair
     * existed is attached and does not extend, and the short-circuit above used to hand
     * that back as success - leaving the caller nothing to do but edit the {@code .mdo}
     * by hand. So the link is repaired here, and a confirmed-unlinked object is reported
     * as the failure it is. An object the pass could not examine at all (a borrowed child
     * that is no BM top object, a model not yet up) keeps the idempotent success and says
     * on the tag that the link was not verified.
     * </p>
     *
     * @param extension the extension project holding the object.
     * @param baseProjectName base configuration, or empty to derive it from the parent.
     * @param targetFqn the object the caller asked for.
     * @param childKind what the caller asked to borrow ({@code Module} for a module).
     * @param r the result being built.
     * @return the same result, settled.
     */
    private static BorrowResult settleAlreadyBorrowed(IProject extension, String baseProjectName,
        String targetFqn, String childKind, BorrowResult r)
    {
        r.alreadyBorrowed = true;
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("targetFqn", targetFqn); //$NON-NLS-1$
        LinkageOutcome linkage =
            repairExtensionLinkage(extension, baseProjectName, targetFqn, childKind);
        reportLinkage(linkage, tag);
        if (!linkage.complete && !linkage.checked)
        {
            tag.put("extensionLinkageUnverified", //$NON-NLS-1$
                "the object is attached, but the link could not be examined - read the .mdo " //$NON-NLS-1$
                    + "before relying on the override."); //$NON-NLS-1$
        }
        r.tags.put("alreadyBorrowed", tag); //$NON-NLS-1$
        if (!linkage.complete && linkage.checked)
        {
            r.error = linkageErrorMessage(targetFqn, extension, linkage);
            return r;
        }
        r.ok = true;
        return r;
    }

    /**
     * Resolves an EObject by FQN inside the base project's configuration.
     * <p>
     * Supported FQN forms:
     * <ul>
     *   <li>Top-level: {@code "Catalog.Users"}, {@code "Document.Order"} -
     *       resolved via {@link MetadataTypeCatalog#findObject}.</li>
     *   <li>Form: {@code "Catalog.Users.Form.UserForm"} - resolves the Form
     *       metadata object (.mdo container; the BaseForm root has FQN
     *       {@code "...Form.UserForm.Form"} which is also accepted).</li>
     *   <li>Child element: {@code "Catalog.Users.Attribute.Email"},
     *       {@code "Document.Order.TabularSection.Items"},
     *       {@code "InformationRegister.Rates.Resource.Rate"},
     *       {@code "Catalog.Users.Template.PrintForm"},
     *       {@code "Catalog.Users.Command.Open"}.</li>
     * </ul>
     * <p>
     * Strategy: first try {@code IBmTransaction.getTopObjectByFqn(fqn)} on the
     * base project's BM model (forms / templates / many child types are
     * registered as BM top objects in EDT 2026.1). If that returns null,
     * fall back to splitting the FQN, finding the parent MdObject via
     * {@link MetadataTypeCatalog}, and walking child collections by reflection
     * ({@code getForms()}, {@code getAttributes()}, ...).
     */
    private static EObject resolveSourceEObject(IProject baseProject, String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }
        // 1.43.1: try BM top-object resolver first. Forms and templates in
        // EDT 2026.1 are top objects, indexed in the base project's BM
        // namespace. Normalize the FQN so Russian type names ("Справочник.X")
        // are converted to canonical English ("Catalog.X") - the BM index
        // only knows the canonical form.
        String normalized = normalizeFqnSafely(fqn);
        EObject viaBm = resolveViaBmTransaction(baseProject, normalized);
        if (viaBm != null)
        {
            return ensureMdObjectForForm(viaBm);
        }
        // A form metadata FQN ("Catalog.X.Form.Y") resolves to the BasicForm
        // MdObject, which is NOT a BM top object - it lives under the parent's
        // getForms(). Resolve it via the EMF tree (which returns the BasicForm)
        // BEFORE the ".Form"-suffixed BM lookup. The suffixed lookup returns the
        // INNER form content (form.model.Form), which is the WRONG object for
        // adoption: the adopter (IModelObjectAdopter) operates on the BasicForm and
        // adopts the inner form itself as a side effect - passing the inner form
        // directly fails isAdoptable and collides ("FQN ...Form.Y.Form already in use").
        if (looksLikeFormMetadataFqn(normalized))
        {
            EObject viaEmf = resolveViaEmfFallback(baseProject, normalized);
            if (viaEmf != null)
            {
                return viaEmf;
            }
            EObject withSuffix = resolveViaBmTransaction(baseProject, normalized + ".Form"); //$NON-NLS-1$
            if (withSuffix != null)
            {
                return ensureMdObjectForForm(withSuffix);
            }
            return null;
        }
        // Fall back to EMF tree walk via the configuration provider.
        return resolveViaEmfFallback(baseProject, normalized);
    }

    /**
     * If {@code obj} is an inner form content object ({@code form.model.Form} /
     * AbstractForm, not an {@code mdclass.MdObject}), climbs to its containing
     * BasicForm MdObject - the adoptable metadata object the EDT adopter expects.
     * Returns {@code obj} unchanged when it is already an MdObject (covers every
     * non-form object) or has no MdObject ancestor.
     */
    private static EObject ensureMdObjectForForm(EObject obj)
    {
        if (obj == null || obj instanceof com._1c.g5.v8.dt.metadata.mdclass.MdObject)
        {
            return obj;
        }
        EObject c = obj.eContainer();
        while (c != null && !(c instanceof com._1c.g5.v8.dt.metadata.mdclass.MdObject))
        {
            c = c.eContainer();
        }
        return c != null ? c : obj;
    }

    /**
     * Resolves a top-object FQN through the base project's BM namespace.
     * Works for any BM top object - including forms (e.g.
     * {@code "Catalog.X.Form.Y.Form"}), templates, and the Configuration
     * itself - because EDT registers every metadata top object in the BM
     * index with its dotted path as the FQN.
     */
    private static EObject resolveViaBmTransaction(IProject baseProject, String fqn)
    {
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return null;
        }
        IBmModel bmModel = bmModelManager.getModel(baseProject);
        if (bmModel == null)
        {
            return null;
        }
        AtomicReference<EObject> ref = new AtomicReference<>();
        try
        {
            bmModel.executeReadonlyTask(new AbstractBmTask<Void>("ResolveSourceEObject") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, org.eclipse.core.runtime.IProgressMonitor pm)
                {
                    IBmObject obj = tx.getTopObjectByFqn(fqn);
                    if (obj instanceof EObject)
                    {
                        ref.set((EObject) obj);
                    }
                    return null;
                }
            });
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveViaBmTransaction(" + fqn + ") failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
        }
        return ref.get();
    }

    /**
     * Walks the EMF Configuration tree to resolve children that are not
     * BM top objects (attributes, tabular sections, dimensions, resources,
     * commands and stored templates that EDT places under their parent).
     * <p>
     * FQN format: {@code Type.Name(.ChildKind.ChildName)*}. For example
     * {@code Document.Order.TabularSection.Items.Attribute.Quantity}.
     */
    private static EObject resolveViaEmfFallback(IProject baseProject, String fqn)
    {
        try
        {
            String[] parts = fqn.split("\\."); //$NON-NLS-1$
            if (parts.length < 2)
            {
                return null;
            }
            Object configProvider = ru.aiedt.mcp.server.Activator.getDefault()
                .getConfigurationProvider();
            if (configProvider == null)
            {
                return null;
            }
            Method getConfiguration = configProvider.getClass().getMethod("getConfiguration", //$NON-NLS-1$
                IProject.class);
            Object config = getConfiguration.invoke(configProvider, baseProject);
            if (config == null)
            {
                return null;
            }
            EObject current = MetadataTypeCatalog.findObject(
                (com._1c.g5.v8.dt.metadata.mdclass.Configuration) config, parts[0], parts[1]);
            if (current == null)
            {
                return null;
            }
            // Walk remaining (childKind, childName) pairs.
            for (int i = 2; i + 1 < parts.length; i += 2)
            {
                String childKind = parts[i];
                String childName = parts[i + 1];
                // Tail "...Form" (BaseForm marker) is appended to a Form
                // metadata FQN by EDT BM. When walking the EMF tree the
                // parent is already the Form object, so a trailing ".Form"
                // does not address a child - return what we have.
                if ("Form".equalsIgnoreCase(childKind) && i + 1 == parts.length - 1 //$NON-NLS-1$
                    && childName.equalsIgnoreCase("Form")) //$NON-NLS-1$
                {
                    break;
                }
                EObject next = findChildByKindAndName(current, childKind, childName);
                if (next == null)
                {
                    return null;
                }
                current = next;
            }
            return current;
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveViaEmfFallback(" + fqn + ") failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
            return null;
        }
    }

    /**
     * Reflectively locates a child of {@code parent} by {@code childKind}
     * (Form / Attribute / TabularSection / Template / Command / Dimension /
     * Resource and Russian aliases) and {@code childName}. Searches in the
     * collection returned by {@code get<ChildKind>s()}, comparing
     * case-insensitively against {@code getName()}.
     */
    private static EObject findChildByKindAndName(EObject parent, String childKind, String childName)
    {
        if (parent == null || childKind == null || childName == null)
        {
            return null;
        }
        String getterName = childKindGetter(childKind);
        if (getterName == null)
        {
            return null;
        }
        try
        {
            Method getter = parent.getClass().getMethod(getterName);
            Object children = getter.invoke(parent);
            if (!(children instanceof List))
            {
                return null;
            }
            for (Object child : (List<?>) children)
            {
                if (!(child instanceof EObject))
                {
                    continue;
                }
                String name = invokeNameGetter(child);
                if (childName.equalsIgnoreCase(name))
                {
                    return (EObject) child;
                }
            }
        }
        catch (NoSuchMethodException nsme)
        {
            // Not every parent type exposes every child kind - that's normal.
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("findChildByKindAndName(" + childKind + "." //$NON-NLS-1$ //$NON-NLS-2$
                + childName + ") failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    private static final Map<String, String> CHILD_KIND_GETTERS = buildChildKindGetters();

    private static Map<String, String> buildChildKindGetters()
    {
        Map<String, String> m = new HashMap<>();
        m.put("form", "getForms"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("attribute", "getAttributes"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("tabularsection", "getTabularSections"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("template", "getTemplates"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("command", "getCommands"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("dimension", "getDimensions"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("resource", "getResources"); //$NON-NLS-1$ //$NON-NLS-2$
        // 1.43: HTTP services + Web services children
        m.put("urltemplate", "getUrlTemplates"); // HTTPService -> URLTemplate //$NON-NLS-1$ //$NON-NLS-2$
        m.put("method", "getMethods"); // URLTemplate -> HTTPServiceMethod //$NON-NLS-1$ //$NON-NLS-2$
        m.put("operation", "getOperations"); // WebService -> WebServiceOperation //$NON-NLS-1$ //$NON-NLS-2$
        m.put("форма", "getForms"); // форма //$NON-NLS-1$
        m.put("реквизит", "getAttributes"); // реквизит //$NON-NLS-1$
        m.put("табличнаячасть", "getTabularSections"); // табличнаячасть //$NON-NLS-1$
        m.put("макет", "getTemplates"); // макет //$NON-NLS-1$
        m.put("команда", "getCommands"); // команда //$NON-NLS-1$
        m.put("измерение", "getDimensions"); // измерение //$NON-NLS-1$
        m.put("ресурс", "getResources"); // ресурс //$NON-NLS-1$
        m.put("шаблонurl", "getUrlTemplates"); // шаблонURL //$NON-NLS-1$
        m.put("метод", "getMethods"); // метод //$NON-NLS-1$
        m.put("операция", "getOperations"); // операция //$NON-NLS-1$
        return m;
    }

    private static String childKindGetter(String childKind)
    {
        return CHILD_KIND_GETTERS.get(childKind.toLowerCase());
    }

    private static String invokeNameGetter(Object obj)
    {
        try
        {
            Method m = obj.getClass().getMethod("getName"); //$NON-NLS-1$
            Object v = m.invoke(obj);
            return v != null ? v.toString() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Heuristic: a 4-segment FQN of shape {@code Type.Name.Form.X} is a Form
     * metadata object whose BM-side BaseForm root carries the suffix
     * {@code .Form}.
     */
    private static boolean looksLikeFormMetadataFqn(String fqn)
    {
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        return parts.length == 4 && parts[2].equalsIgnoreCase("Form"); //$NON-NLS-1$
    }

    private static String normalizeFqnSafely(String fqn)
    {
        try
        {
            return MetadataTypeCatalog.normalizeFqn(fqn);
        }
        catch (Throwable t)
        {
            return fqn;
        }
    }

    /**
     * Resolves the {@code IExtensionProject} for an {@link IProject}. EDT 2026.1
     * exposes IExtensionProject as an {@link IV8Project} subtype, looked up
     * through {@code IV8ProjectManager.getProject(IDtProject)} - the same
     * sequence used by {@code ConfigurationInfoReader} (line 148-162).
     * <p>
     * 1.43.1: previous attempts went through {@code IDtProjectManager.getDtProject()}
     * and probed {@code IDtProject.getAdapter(IExtensionProject)}, which always
     * returned null on EDT 2026.1 because {@code IDtProject} is not an
     * {@code IAdaptable} target for {@code IExtensionProject}. The right
     * accessor lives on {@code IV8ProjectManager}.
     */
    /**
     * Auto-resolves the base configuration project of an extension by calling
     * {@code IExtensionProject.getParentProject()} (declared on IDependentProject)
     * reflectively on the already-resolved extension-project object. Returns null when
     * the parent cannot be determined (caller then asks for an explicit baseProjectName).
     */
    private static IProject deriveParentProject(Object extProject)
    {
        if (extProject == null)
        {
            return null;
        }
        try
        {
            Object parent = extProject.getClass().getMethod("getParentProject").invoke(extProject); //$NON-NLS-1$
            if (parent instanceof IProject)
            {
                return (IProject) parent;
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("deriveParentProject: getParentProject() failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    private static Object resolveExtensionProject(IProject project)
    {
        try
        {
            // 1.43.1: wait for the project's lifecycle to reach STARTED so
            // both DT and V8 services have indexed the project before we
            // resolve. Avoids "not initialised" misses on the first call
            // after workspace open / clean.
            waitForProjectStartedQuietly(project, 30_000L);

            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
            if (dtProjectManager == null || v8ProjectManager == null)
            {
                Activator.logWarning("resolveExtensionProject(" + project.getName() //$NON-NLS-1$
                    + "): DT or V8 project manager not available"); //$NON-NLS-1$
                return null;
            }
            IDtProject dtProject = dtProjectManager.getDtProject(project);
            if (dtProject == null)
            {
                Activator.logWarning("resolveExtensionProject(" + project.getName() //$NON-NLS-1$
                    + "): IDtProjectManager returned null - project not yet a DT project"); //$NON-NLS-1$
                return null;
            }
            IV8Project v8Project = v8ProjectManager.getProject(dtProject);
            Class<?> extIface = Class.forName(
                "com._1c.g5.v8.dt.core.platform.IExtensionProject"); //$NON-NLS-1$
            if (extIface.isInstance(v8Project))
            {
                return v8Project;
            }
            // Fallback: some adapter factories expose IExtensionProject on
            // the raw IProject (Eclipse IAdaptable contract).
            try
            {
                Object adapted = project.getAdapter(extIface);
                if (adapted != null)
                {
                    return adapted;
                }
            }
            catch (Exception adapterEx)
            {
                Activator.logWarning("IProject.getAdapter(IExtensionProject) failed: " //$NON-NLS-1$
                    + adapterEx.getMessage());
            }
            Activator.logWarning("resolveExtensionProject(" + project.getName() //$NON-NLS-1$
                + "): IV8Project is " //$NON-NLS-1$
                + (v8Project != null ? v8Project.getClass().getName() : "null") //$NON-NLS-1$
                + " - not an IExtensionProject. Project may not be an extension."); //$NON-NLS-1$
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveExtensionProject(" + project.getName() + ") failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
            return null;
        }
    }

    /**
     * Best-effort borrow of {@code targetFqn} from {@code baseProjectName}
     * into the {@code extension} project. Resolves the adopt service via
     * OSGi {@link BundleContext} and invokes the discovered method through
     * reflection.
     * <p>
     * On success returns {@code BorrowResult.ok = true} and populates
     * {@code tags.borrowed} with the FQN that was actually borrowed.
     * <p>
     * On failure populates either {@code adoptServiceNotFound=true} (probe
     * failed) or {@code tags.adoptInvocationFailed} (probe found service
     * but invocation threw).
     */
    public static BorrowResult attemptBorrow(IProject extension, String baseProjectName,
        String targetFqn, String childKind)
    {
        BorrowResult r = new BorrowResult();
        r.discoveredApi = resolvedAdoptServiceClass();
        if (r.discoveredApi == null)
        {
            r.adoptServiceNotFound = true;
            r.error = "Adopt service not available in this EDT runtime"; //$NON-NLS-1$
            populateNotFoundTag(r, "borrow", targetFqn); //$NON-NLS-1$
            return r;
        }
        // 1.43.x: pre-check the extension model. When the target is already
        // attached we return alreadyBorrowed without invoking the adopter -
        // this saves a Guice/reflection round trip and removes the dependency
        // on EDT's exception message wording for the legitimate idempotent
        // case. Stale partial borrows still fall through to adoptAndAttach;
        // if it later throws "already", the catch block surfaces
        // partialBorrowDetected via resolveAlreadyException.
        if (isAttachedInExtension(extension, targetFqn))
        {
            return settleAlreadyBorrowed(extension, baseProjectName, targetFqn, childKind, r);
        }
        // 1.42.3: try the EDT 2026.1 IModelObjectAdopter contract first.
        // adoptAndAttach(EObject source, IExtensionProject ext, IProgressMonitor)
        // is the canonical API every md-extension consumer uses (and the
        // path the EDT GUI's "Borrow into extension" menu hits). On older
        // runtimes / probe failure we fall through to the legacy OSGi
        // service lookup below.
        if ("com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter".equals(r.discoveredApi)) //$NON-NLS-1$
        {
            BorrowResult viaAdopter = attemptBorrowViaAdopter(extension, baseProjectName,
                targetFqn, childKind);
            if (viaAdopter.ok || (viaAdopter.error != null && !viaAdopter.adoptServiceNotFound))
            {
                // Either succeeded, already-borrowed, or failed for a
                // concrete reason worth reporting. Don't fall through.
                return viaAdopter;
            }
            // adoptServiceNotFound on the new path - keep r tagged as the
            // legacy probe and let the OSGi lookup try.
        }
        BundleContext bc = FrameworkUtil.getBundle(BmExtensionHelper.class).getBundleContext();
        if (bc == null)
        {
            r.adoptServiceNotFound = true;
            r.error = "BundleContext not available"; //$NON-NLS-1$
            populateNotFoundTag(r, "borrow", targetFqn); //$NON-NLS-1$
            return r;
        }
        ServiceReference<?> ref = bc.getServiceReference(r.discoveredApi);
        if (ref == null)
        {
            r.adoptServiceNotFound = true;
            r.error = "Adopt service registered class not exposed via OSGi"; //$NON-NLS-1$
            populateNotFoundTag(r, "borrow", targetFqn); //$NON-NLS-1$
            return r;
        }
        Object service = bc.getService(ref);
        if (service == null)
        {
            r.adoptServiceNotFound = true;
            r.error = "OSGi service instance is null"; //$NON-NLS-1$
            populateNotFoundTag(r, "borrow", targetFqn); //$NON-NLS-1$
            bc.ungetService(ref);
            return r;
        }
        try
        {
            Method adoptMethod = findAdoptMethod(service.getClass(), childKind);
            if (adoptMethod == null)
            {
                Map<String, Object> tag = new LinkedHashMap<>();
                tag.put("targetFqn", targetFqn); //$NON-NLS-1$
                tag.put("baseProject", baseProjectName); //$NON-NLS-1$
                tag.put("discoveredApi", r.discoveredApi); //$NON-NLS-1$
                tag.put("hint", //$NON-NLS-1$
                    "Service found but no `adopt` / `borrow` method recognised. " //$NON-NLS-1$
                        + "Use EDT GUI as a workaround."); //$NON-NLS-1$
                r.tags.put("adoptInvocationFailed", tag); //$NON-NLS-1$
                r.error = "Adopt method not found on service"; //$NON-NLS-1$
                return r;
            }
            // Best-effort invocation: most adopt APIs accept (IProject, String) for
            // (extension, base FQN). Wider variants (project, project, fqn) are
            // tried by reflection on a per-method basis below.
            try
            {
                Object result;
                Class<?>[] paramTypes = adoptMethod.getParameterTypes();
                if (paramTypes.length == 2 && paramTypes[0].equals(IProject.class)
                    && paramTypes[1].equals(String.class))
                {
                    result = adoptMethod.invoke(service, extension, targetFqn);
                }
                else
                {
                    Map<String, Object> tag = new LinkedHashMap<>();
                    tag.put("targetFqn", targetFqn); //$NON-NLS-1$
                    tag.put("methodSignature", adoptMethod.toString()); //$NON-NLS-1$
                    tag.put("hint", //$NON-NLS-1$
                        "Adopt service method has an unexpected signature. " //$NON-NLS-1$
                            + "1.37 supports (IProject, String). Update to bridge."); //$NON-NLS-1$
                    r.tags.put("adoptInvocationFailed", tag); //$NON-NLS-1$
                    r.error = "Unsupported adopt method signature: " + adoptMethod; //$NON-NLS-1$
                    return r;
                }
                Map<String, Object> okTag = new LinkedHashMap<>();
                okTag.put("targetFqn", targetFqn); //$NON-NLS-1$
                okTag.put("baseProject", baseProjectName); //$NON-NLS-1$
                if (result != null)
                {
                    okTag.put("returned", result.toString()); //$NON-NLS-1$
                }
                // This older service hands back no model object, so the link is written by
                // FQN instead. Same rule as the adopter path: confirmed unlinked is a failure,
                // unexaminable is a success that says so.
                LinkageOutcome linkage = repairExtensionLinkage(extension, baseProjectName,
                    targetFqn, childKind);
                reportLinkage(linkage, okTag);
                if (!linkage.complete && !linkage.checked)
                {
                    okTag.put("extensionLinkageUnverified", //$NON-NLS-1$
                        "the object is in the extension, but the link could not be examined - " //$NON-NLS-1$
                            + "read the .mdo before relying on the override."); //$NON-NLS-1$
                }
                r.tags.put("borrowed", okTag); //$NON-NLS-1$
                if (!linkage.complete && linkage.checked)
                {
                    r.error = linkageErrorMessage(targetFqn, extension, linkage);
                    return r;
                }
                r.ok = true;
                return r;
            }
            catch (Exception invokeEx)
            {
                Throwable cause = invokeEx.getCause() != null ? invokeEx.getCause() : invokeEx;
                String msg = cause.getMessage() != null ? cause.getMessage()
                    : cause.getClass().getSimpleName();
                if (msg != null && msg.toLowerCase().contains("already")) //$NON-NLS-1$
                {
                    return resolveAlreadyException(extension, baseProjectName, targetFqn,
                        childKind, msg, null, r);
                }
                Map<String, Object> tag = new LinkedHashMap<>();
                tag.put("targetFqn", targetFqn); //$NON-NLS-1$
                tag.put("error", msg); //$NON-NLS-1$
                r.tags.put("adoptInvocationFailed", tag); //$NON-NLS-1$
                r.error = "Adopt invocation failed: " + msg; //$NON-NLS-1$
                return r;
            }
        }
        finally
        {
            try
            {
                bc.ungetService(ref);
            }
            catch (Throwable ignored)
            {
                // best-effort
            }
        }
    }

    private static Method findAdoptMethod(Class<?> serviceClass, String childKind)
    {
        // 1) named match: adopt + childKind (e.g. "adoptForm", "adopt_child")
        if (childKind != null && !childKind.isEmpty())
        {
            String capitalized = Character.toUpperCase(childKind.charAt(0)) + childKind.substring(1);
            Method m = findMethodIgnoringCase(serviceClass, "adopt" + capitalized); //$NON-NLS-1$
            if (m != null)
            {
                return m;
            }
            m = findMethodIgnoringCase(serviceClass, "borrow" + capitalized); //$NON-NLS-1$
            if (m != null)
            {
                return m;
            }
        }
        // 2) generic adopt / borrow
        Method m = findMethodIgnoringCase(serviceClass, "adopt"); //$NON-NLS-1$
        if (m != null)
        {
            return m;
        }
        return findMethodIgnoringCase(serviceClass, "borrow"); //$NON-NLS-1$
    }

    private static Method findMethodIgnoringCase(Class<?> cls, String prefix)
    {
        for (Method m : cls.getMethods())
        {
            if (m.getName().equalsIgnoreCase(prefix))
            {
                return m;
            }
        }
        // 2-arg public method whose name starts with the prefix
        for (Method m : cls.getMethods())
        {
            if (m.getName().toLowerCase().startsWith(prefix.toLowerCase())
                && m.getParameterCount() == 2)
            {
                return m;
            }
        }
        return null;
    }

    private static void populateNotFoundTag(BorrowResult r, String operation, String targetFqn)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", operation); //$NON-NLS-1$
        data.put("targetFqn", targetFqn); //$NON-NLS-1$
        data.put("hint", //$NON-NLS-1$
            "EDT adopt service not reachable. Workaround: open base + extension in EDT, " //$NON-NLS-1$
                + "right-click the base object - Borrow into extension."); //$NON-NLS-1$
        r.tags.put(ErrorTags.ADOPT_SERVICE_NOT_FOUND.wire(), data);
    }

    /**
     * Best-effort wait for the project's lifecycle to reach STARTED. Avoids
     * the race where the IExtensionProject adapter is queried before the
     * project's DT services have come up. Failures are logged but never
     * propagated - callers should still attempt the resolution.
     */
    private static void waitForProjectStartedQuietly(IProject project, long timeoutMs)
    {
        try
        {
            IDtProjectManager mgr = Activator.getDefault().getDtProjectManager();
            if (mgr == null)
            {
                return;
            }
            IDtProject dtProject = mgr.getDtProject(project);
            if (dtProject == null)
            {
                return;
            }
            ProjectReadinessGate.waitForProjectStarted(dtProject, timeoutMs);
        }
        catch (Exception e)
        {
            Activator.logWarning("waitForProjectStartedQuietly(" + project.getName() //$NON-NLS-1$
                + ") failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * 1.43.x: verifies the {@code targetFqn} actually resolves inside the
     * {@code extension} project's metadata model. Used both as a pre-check
     * (skip the adopt call when the object is already borrowed) and as a
     * post-check after EDT throws an "already" exception, to tell a genuine
     * idempotent success from a stale partial-borrow on disk.
     * <p>
     * Detection strategy:
     * <ol>
     *   <li>Probe the extension's BM index via
     *       {@code IBmTransaction.getTopObjectByFqn(normalized)} - catches
     *       forms, templates and other top objects.</li>
     *   <li>For form-shaped FQNs ({@code Type.Name.Form.X}) retry with a
     *       trailing {@code .Form} - the BaseForm root is indexed there.</li>
     *   <li>Walk the extension's Configuration EMF tree for non-top children
     *       (attributes, tabular sections, dimensions, resources).</li>
     * </ol>
     * Returns {@code false} when verification cannot be performed (BM not
     * ready, lifecycle race, reflection failure). The caller treats that as
     * "cannot confirm attached" - the safer default since blindly trusting
     * EDT's "already" message is what caused the false-positive in the first
     * place.
     */
    static boolean isAttachedInExtension(IProject extension, String targetFqn)
    {
        if (extension == null || targetFqn == null || targetFqn.isEmpty())
        {
            return false;
        }
        String normalized = normalizeFqnSafely(targetFqn);
        try
        {
            IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
            if (bmModelManager != null)
            {
                IBmModel bmModel = bmModelManager.getModel(extension);
                if (bmModel != null)
                {
                    AtomicReference<Boolean> ref = new AtomicReference<>(Boolean.FALSE);
                    bmModel.executeReadonlyTask(
                        new AbstractBmTask<Void>("VerifyAttachedInExtension") //$NON-NLS-1$
                        {
                            @Override
                            public Void execute(IBmTransaction tx,
                                org.eclipse.core.runtime.IProgressMonitor pm)
                            {
                                IBmObject obj = tx.getTopObjectByFqn(normalized);
                                if (obj != null)
                                {
                                    ref.set(Boolean.TRUE);
                                    return null;
                                }
                                if (looksLikeFormMetadataFqn(normalized))
                                {
                                    IBmObject withSuffix = tx.getTopObjectByFqn(
                                        normalized + ".Form"); //$NON-NLS-1$
                                    if (withSuffix != null)
                                    {
                                        ref.set(Boolean.TRUE);
                                    }
                                }
                                return null;
                            }
                        });
                    if (Boolean.TRUE.equals(ref.get()))
                    {
                        return true;
                    }
                }
            }
            // EMF fallback for children that are not registered as BM top
            // objects (attributes, tabular sections, dimensions, resources).
            EObject viaEmf = resolveViaEmfFallback(extension, normalized);
            return viaEmf != null;
        }
        catch (Exception e)
        {
            Activator.logWarning("isAttachedInExtension(" + targetFqn + ") failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
            return false;
        }
    }

    /**
     * 1.43.x: common handling for "already" exceptions thrown by the EDT
     * adopt service. Verifies the target is genuinely attached in the
     * extension model before claiming idempotent success - falls back to a
     * {@code partialBorrowDetected} diagnostic when the file system carries
     * stale leftovers (orphan Forms/&lt;name&gt;/ folder, missing Form.form,
     * parent .mdo without the child entry).
     */
    private static BorrowResult resolveAlreadyException(IProject extension, String baseProjectName,
        String targetFqn, String childKind, String edtMessage, String invocationApi, BorrowResult r)
    {
        if (isAttachedInExtension(extension, targetFqn))
        {
            return settleAlreadyBorrowed(extension, baseProjectName, targetFqn, childKind, r);
        }
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("targetFqn", targetFqn); //$NON-NLS-1$
        tag.put("edtMessage", edtMessage); //$NON-NLS-1$
        if (invocationApi != null)
        {
            tag.put("api", invocationApi); //$NON-NLS-1$
        }
        tag.put("hint", //$NON-NLS-1$
            "EDT reported 'already' but the target is not resolvable in the " //$NON-NLS-1$
                + "extension model. Likely cause: a previous borrow left stale " //$NON-NLS-1$
                + "files on disk (orphan Forms/<name>/ folder, missing Form.form, " //$NON-NLS-1$
                + "or parent .mdo without the child entry). Inspect the extension " //$NON-NLS-1$
                + "filesystem, remove orphan files, and retry. If the BM index is " //$NON-NLS-1$
                + "stale, clean_project or an EDT restart may help."); //$NON-NLS-1$
        r.tags.put("partialBorrowDetected", tag); //$NON-NLS-1$
        r.error = "Borrow blocked: EDT reported 'already' but target not attached in extension. " //$NON-NLS-1$
            + edtMessage;
        return r;
    }
}
