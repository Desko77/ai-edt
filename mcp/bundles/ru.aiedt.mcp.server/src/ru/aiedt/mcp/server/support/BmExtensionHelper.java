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
        String baseProjectName, String targetFqn)
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
            r.tags.put("borrowed", okTag); //$NON-NLS-1$
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
                return resolveAlreadyException(extension, targetFqn, msg,
                    "IModelObjectAdopter.adoptAndAttach", r); //$NON-NLS-1$
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
            r.alreadyBorrowed = true;
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("targetFqn", targetFqn); //$NON-NLS-1$
            r.tags.put("alreadyBorrowed", tag); //$NON-NLS-1$
            r.ok = true;
            return r;
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
                targetFqn);
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
                r.tags.put("borrowed", okTag); //$NON-NLS-1$
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
                    return resolveAlreadyException(extension, targetFqn, msg, null, r);
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
    private static BorrowResult resolveAlreadyException(IProject extension, String targetFqn,
        String edtMessage, String invocationApi, BorrowResult r)
    {
        if (isAttachedInExtension(extension, targetFqn))
        {
            r.alreadyBorrowed = true;
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("targetFqn", targetFqn); //$NON-NLS-1$
            r.tags.put("alreadyBorrowed", tag); //$NON-NLS-1$
            r.ok = true;
            return r;
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
