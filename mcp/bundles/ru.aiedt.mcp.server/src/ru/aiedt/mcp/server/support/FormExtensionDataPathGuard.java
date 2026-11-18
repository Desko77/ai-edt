/*
 * Copyright (c) 2026 Desko77
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

import ru.aiedt.mcp.server.Activator;

/**
 * Preflights extension data paths without adopting a root or metadata dependencies.
 * Own locally typed paths can be checked before derived indexes exist. Other paths
 * require affirmative ownership, resolution and persistence checks; unknown is not success.
 */
public final class FormExtensionDataPathGuard
{
    /**
     * The check that tells whether the attribute the path starts at already
     * belongs to the extension (the name travels into an answer when the
     * question could not be asked).
     */
    public static final String CHECK_BELONGING = "attributeBelongsToExtension"; //$NON-NLS-1$

    /** The check that tells whether EDT writes the path with the form. */
    public static final String CHECK_EXPORT = "exportOfExtensionForm"; //$NON-NLS-1$

    /** The check that tells whether the form resolves the path. */
    public static final String CHECK_RESOLUTION = "pathResolutionInForm"; //$NON-NLS-1$

    /**
     * What a question of the port answered. A question that could not be asked -
     * the service is not installed, the method is not there, the call failed - is
     * {@link #NOT_ASKED} and not an answer: a guard that turns it into a yes or a
     * no reports a fact it never learned, and every refusal built on it would be
     * a guess.
     */
    public enum Answer
    {
        /** The question was asked and the answer is yes. */
        TRUE,

        /** The question was asked and the answer is no. */
        FALSE,

        /** The question could not be asked, so nothing is known. */
        NOT_ASKED
    }

    /**
     * The seam over the EDT services. An implementation answers only; the
     * order of the questions and what is done with the answers belongs to
     * {@link #assign}.
     */
    public interface Port
    {
        /**
         * @param attribute a form attribute
         * @return TRUE when the attribute already belongs to the extension, so a
         *         path through it survives export; FALSE when it is the base
         *         form's and has to be borrowed; NOT_ASKED when the service that
         *         knows is not there
         */
        Answer isExtensionBelongingObject(Object attribute);

        /**
         * Borrows a base-form object into the extension. Called inside the
         * write transaction: the EDT implementation asserts a current BM
         * transaction and mutates the model, opening none of its own.
         *
         * @param attribute the base-form attribute to borrow
         * @throws Exception when the adoption cannot be performed
         */
        void adoptObject(Object attribute) throws Exception;

        /**
         * @param form the extension form
         * @param dataPath the assigned {@code AbstractDataPath}
         * @return TRUE when EDT will not write the path with the form. The answer
         *         is TRUE for every path the guard assembles - the referred
         *         objects the decision reads are derived data such a path does not
         *         carry - so it never condemns a path on its own; NOT_ASKED when
         *         the question could not be asked
         */
        Answer exportSkip(Object form, Object dataPath);

        /**
         * @param form the extension form
         * @param dataPath the assigned {@code AbstractDataPath}
         * @return TRUE when the form resolves the path: every segment of it
         *         names something the form carries. A path assembled here holds
         *         segments only, and this is the question that tells a nested
         *         path to something that exists from a path leading nowhere;
         *         NOT_ASKED when the question could not be asked
         */
        Answer pathResolved(Object form, Object dataPath);
    }

    /**
     * What the assignment did: the attributes it borrowed, the checks that could
     * not be asked, and - when the path cannot reach the database - the refusal
     * that must undo the write.
     */
    public static final class Outcome
    {
        private final List<String> adoptedAttributes = new ArrayList<>();

        private final List<String> notAskedChecks = new ArrayList<>();

        private String refusal;

        /** @return the borrowed attribute names, in the order they were borrowed */
        public List<String> getAdoptedAttributes()
        {
            return Collections.unmodifiableList(adoptedAttributes);
        }

        /**
         * @return the checks the guard could not ask about this path, named by
         *         {@link #CHECK_BELONGING}, {@link #CHECK_EXPORT} and
         *         {@link #CHECK_RESOLUTION}; empty when every one of them
         *         answered
         */
        public List<String> getNotAskedChecks()
        {
            return Collections.unmodifiableList(notAskedChecks);
        }

        /** @return the refusal to report, or null when the path was accepted */
        public String getRefusal()
        {
            return refusal;
        }

        /** Stable classification of this refusal, independent of human-readable text. */
        public String getErrorCode()
        {
            if (refusal == null) { return null; }
            for (String code : new String[] { "EXTENSION_BINDING_UNSUPPORTED",
                "EXTENSION_BINDING_UNVERIFIED", "EXTENSION_BINDING_UNRESOLVED" })
            {
                if (refusal.contains(code + ":")) { return code; }
            }
            return null;
        }

        /** @return true when the path was accepted and nothing has to be undone */
        public boolean isAccepted()
        {
            return refusal == null;
        }
    }

    /**
     * Thrown when the guard refuses the assignment. It travels out of the BM
     * transaction action uncaught, so the transaction rolls back and no part of
     * the write reaches Form.form.
     */
    public static final class RefusalException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        private final transient Outcome outcome;

        RefusalException(Outcome outcome)
        {
            super(outcome.getRefusal());
            this.outcome = outcome;
        }

        /** @return what the guard borrowed and why it refused */
        public Outcome getOutcome()
        {
            return outcome;
        }
    }

    private static volatile Port installed;

    private static volatile ProjectKind installedProjectKind;

    private FormExtensionDataPathGuard()
    {
    }

    /**
     * Replaces the port. For tests: the fake sees every question the guard asks
     * and answers all of them.
     *
     * @param port the port to install, or null to fall back to the EDT one
     */
    public static void installPort(Port port)
    {
        installed = port;
    }

    /**
     * The seam over the project lookup, parallel to {@link Port}. The one
     * question it owns - does the form's project make it an extension form -
     * is decided before the port is touched, and a test installs its own
     * answer to drive the branches a form built in memory cannot reach: such
     * a form belongs to no project, so the runtime lookup would always fall
     * through to the base-form fallback.
     */
    public interface ProjectKind
    {
        /**
         * @param form the form
         * @return TRUE when the form's project is an extension project, FALSE
         *         when it is a configuration project, null when the project
         *         cannot be told
         */
        Boolean inExtensionProject(Object form);
    }

    /**
     * Replaces the project lookup. For tests: the decision the lookup owns has
     * to be drivable directly, because a form built in memory is in no
     * project.
     *
     * @param kind the lookup to install, or null to fall back to the EDT one
     */
    public static void installProjectKind(ProjectKind kind)
    {
        installedProjectKind = kind;
    }

    /** Checks the binding before its setter runs; never mutates or adopts model objects. */
    public static Outcome assign(Object form, Object dataPath, String pathText, String itemName)
    {
        Outcome outcome = new Outcome();
        if (form == null || dataPath == null || pathText == null || pathText.isEmpty())
        {
            return outcome;
        }
        if (!isExtensionForm(form))
        {
            return outcome;
        }
        Port port = port();
        String root = firstSegment(pathText);
        Object attribute = findAttribute(form, root);
        if (port == null)
        {
            outcome.notAskedChecks.add(CHECK_BELONGING);
            outcome.refusal = refusal(itemName, pathText,
                "EXTENSION_BINDING_UNVERIFIED: ownership service unavailable");
            return outcome;
        }
        Answer belongs = attribute == null ? Answer.NOT_ASKED : port.isExtensionBelongingObject(attribute);
        if (belongs != Answer.TRUE)
        {
            if (belongs == Answer.NOT_ASKED)
            {
                outcome.notAskedChecks.add(CHECK_BELONGING);
            }
            outcome.refusal = refusal(itemName, pathText, belongs == Answer.FALSE
                ? "EXTENSION_BINDING_UNSUPPORTED: inherited root requires explicit adoption; automatic adoption is disabled"
                : "EXTENSION_BINDING_UNVERIFIED: root ownership could not be established");
            return outcome;
        }
        if (locallyTypedPath(attribute, pathText))
        {
            return outcome;
        }
        Answer resolved = port.pathResolved(form, dataPath);
        if (resolved == Answer.FALSE)
        {
            outcome.refusal = refusal(itemName, pathText,
                "EXTENSION_BINDING_UNRESOLVED: the form does not resolve the path");
            return outcome;
        }
        if (resolved == Answer.NOT_ASKED)
        {
            outcome.notAskedChecks.add(CHECK_RESOLUTION);
            outcome.refusal = refusal(itemName, pathText,
                "EXTENSION_BINDING_UNVERIFIED: path resolution service unavailable");
        }
        if (outcome.refusal == null && port.exportSkip(form, dataPath) != Answer.FALSE)
        {
            outcome.notAskedChecks.add(CHECK_EXPORT);
            outcome.refusal = refusal(itemName, pathText,
                "EXTENSION_BINDING_UNVERIFIED: extension persistence is not established by derived resolution alone");
        }
        return outcome;
    }

    /**
     * Lets a refusal out of a catch block that would otherwise turn it into a
     * best-effort note or a warning - a column of a table generated over a
     * refused path, a data path wired after the attribute was created. Every
     * other exception keeps the behaviour the caller had for it.
     *
     * <p>The refusal is looked for underneath the wrappers too: a catch block
     * cannot know how many frames of reflection the failure travelled through,
     * and a swallowed refusal is a committed write with a rolled-back borrow.
     * </p>
     *
     * @param e the exception a catch block is holding
     */
    public static void rethrowIfRefusal(Exception e)
    {
        RefusalException refused = findRefusal(e);
        if (refused != null)
        {
            throw refused;
        }
    }

    /**
     * Finds a refusal under whatever the reflective call and the proxy wrapped
     * it in - the BM transaction runs behind {@code Method.invoke} on a proxy, so
     * the exception arrives as an InvocationTargetException or an
     * UndeclaredThrowableException holding it.
     *
     * @param t the exception an operation failed with
     * @return the refusal, or null when the failure is not one
     */
    public static RefusalException findRefusal(Throwable t)
    {
        Throwable current = t;
        while (current != null)
        {
            if (current instanceof RefusalException)
            {
                return (RefusalException) current;
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        return null;
    }

    /**
     * The contract's refusal text, spelled once so every operation reports the
     * same thing.
     *
     * @param itemName the element the path was assigned to
     * @param pathText the dotted path
     * @param reason why the path would not be written with the form
     * @return the refusal text
     */
    private static String refusal(String itemName, String pathText, String reason)
    {
        return (itemName != null && !itemName.isEmpty() ? itemName : "?")
            + ": data path " + pathText //$NON-NLS-1$
            + " cannot be safely bound to the extension form (" + reason + "); binding was not applied"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The text of a failed call, unwrapped from the reflection wrappers it
     * arrives in: {@code Method.invoke} reports a failure of the called method as
     * an {@link InvocationTargetException} whose own message is empty, so the
     * reason EDT gave would otherwise never reach the refusal.
     *
     * @param e the exception the port call failed with
     * @return the message of the cause, or the class name when it has none
     */
    private static String reasonOf(Throwable e)
    {
        Throwable named = e;
        while ((named instanceof InvocationTargetException
            || named instanceof UndeclaredThrowableException) && named.getCause() != null)
        {
            named = named.getCause();
        }
        String message = named.getMessage();
        return message != null && !message.isEmpty() ? message : named.getClass().getSimpleName();
    }

    /**
     * Proves only explicit local attributes/columns, never metadata dependencies or inferred tails.
     * SettingsComposer.Settings is not inferred from its root type: absent a complete local model,
     * that path requires affirmative runtime resolution and persistence checks.
     */
    private static boolean locallyTypedPath(Object attribute, String pathText)
    {
        try
        {
            Object current = attribute;
            String[] segments = pathText.split("\\.");
            for (int i = 1; i < segments.length; i++)
            {
                Object columns = current.getClass().getMethod("getColumns").invoke(current);
                Object found = null;
                for (Object column : (Iterable<?>) columns)
                {
                    if (segments[i].equalsIgnoreCase(nameOf(column, "")))
                    {
                        if (found != null) { return false; }
                        found = column;
                    }
                }
                if (found == null) { return false; }
                current = found;
            }
            Object description = current.getClass().getMethod("getValueType").invoke(current);
            Object types = description == null ? null : description.getClass().getMethod("getTypes").invoke(description);
            if (!(types instanceof java.util.Collection) || ((java.util.Collection<?>) types).isEmpty())
            {
                return false;
            }
            for (Object type : (Iterable<?>) types)
            {
                // Only self-contained platform types are a local persistence proof.
                String name = nameOf(type, "");
                if (!java.util.Set.of("String", "Number", "Boolean", "Date", "UUID", "ValueTable",
                    "ValueTree", "DynamicList", "DataCompositionSettingsComposer").contains(name))
                {
                    return false;
                }
            }
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static String firstSegment(String pathText)
    {
        int dot = pathText.indexOf('.');
        return dot < 0 ? pathText : pathText.substring(0, dot);
    }

    /**
     * Finds a form attribute by name, without depending on the form model at
     * compile time. The name is compared ignoring case: the form's own lookup
     * ({@code BmFormHelper.findFormAttributeByName}) does, and a path whose first
     * segment differs in case from the attribute it names is the same path.
     *
     * @param form the form
     * @param name the attribute name
     * @return the attribute, or null when the form has no attribute of that name
     */
    private static Object findAttribute(Object form, String name)
    {
        try
        {
            Object attributes = form.getClass().getMethod("getAttributes").invoke(form); //$NON-NLS-1$
            if (!(attributes instanceof Iterable))
            {
                return null;
            }
            for (Object attribute : (Iterable<?>) attributes)
            {
                Object attributeName;
                try
                {
                    attributeName = attribute.getClass().getMethod("getName").invoke(attribute); //$NON-NLS-1$
                }
                catch (NoSuchMethodException e)
                {
                    continue;
                }
                if (attributeName instanceof String && name.equalsIgnoreCase((String) attributeName))
                {
                    return attribute;
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("FormExtensionDataPathGuard: cannot list form attributes: " //$NON-NLS-1$
                + e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * @param attribute the attribute a name is wanted for
     * @param fallback the name the caller wrote, used when the attribute has none
     * @return the attribute's own name, so what is reported is what the form
     *         carries and not the spelling the caller used
     */
    private static String nameOf(Object attribute, String fallback)
    {
        try
        {
            Object name = attribute.getClass().getMethod("getName").invoke(attribute); //$NON-NLS-1$
            if (name instanceof String && !((String) name).isEmpty())
            {
                return (String) name;
            }
        }
        catch (Exception e)
        {
            // The attribute does not answer for its name: the caller's spelling is
            // reported rather than nothing.
        }
        return fallback;
    }

    /**
     * Tells an extension form from a configuration one. The project's kind
     * decides: a form belongs to an extension exactly when its project is an
     * extension project. The non-null base form (Form.baseForm) is only the
     * fallback for a form whose project cannot be told - not in a model, or
     * the project service is not there - because a configuration form can
     * answer a non-null base form too. Either way the check asks the port
     * nothing, which is what keeps configuration forms out of this guard
     * entirely.
     *
     * @param form the form
     * @return true when the form is an extension form
     */
    private static boolean isExtensionForm(Object form)
    {
        Boolean byProject = projectKind(form);
        if (byProject != null)
        {
            return byProject.booleanValue();
        }
        try
        {
            Method getBaseForm = form.getClass().getMethod("getBaseForm"); //$NON-NLS-1$
            return getBaseForm.invoke(form) != null;
        }
        catch (Exception e)
        {
            // No base form to read: treat the form as a configuration one, the
            // conservative side - the guard then changes nothing.
            return false;
        }
    }

    /**
     * Asks which kind of project the form belongs to: the installed lookup
     * first, then {@code IV8ProjectManager} - the same answer
     * {@code BmCommonModuleGuards.isExtensionProject} reads, here read off the
     * model object instead of the workspace project.
     *
     * @param form the form
     * @return TRUE for an extension project, FALSE for a configuration one,
     *         null when the project cannot be told (no service, or the form is
     *         not in a model)
     */
    private static Boolean projectKind(Object form)
    {
        ProjectKind mine = installedProjectKind;
        if (mine != null)
        {
            return mine.inExtensionProject(form);
        }
        try
        {
            Activator activator = Activator.getDefault();
            if (activator == null || !(form instanceof EObject))
            {
                return null;
            }
            IV8ProjectManager manager = activator.getV8ProjectManager();
            if (manager == null)
            {
                return null;
            }
            IV8Project project = manager.getProject((EObject) form);
            if (project == null)
            {
                return null;
            }
            return Boolean.valueOf(project instanceof IExtensionProject);
        }
        catch (Exception e)
        {
            Activator.logWarning("FormExtensionDataPathGuard: project lookup failed: " //$NON-NLS-1$
                + e.getMessage());
            return null;
        }
    }

    /**
     * @return the port to ask: the installed one, else the EDT one, else null
     *         when the runtime does not have it
     */
    private static Port port()
    {
        Port mine = installed;
        return mine != null ? mine : EdtPort.instance();
    }

    /**
     * The runtime port: the three EDT services reached by name, so the bundle
     * gains no compile dependency on their packages.
     *
     * <p>A question whose service, method or call is not there is
     * {@link Answer#NOT_ASKED}, never a yes or a no: a guard that cannot see EDT
     * must not block a write, and it must not claim either that the path was
     * checked. What was not asked travels to the caller in the outcome of the
     * assignment.
     *
     * <p>Where the services come from is a seam ({@link ServiceLookup}): the
     * runtime reaches them through the OSGi registry and the plugin injector,
     * and a test installs its own lookup to drive the branches a live EDT
     * reaches only when a service is absent or a call fails.
     */
    static final class EdtPort implements Port
    {
        private static final String MANAGEMENT_SERVICE =
            "com.e1c.g5.v8.dt.form.extension.IFormExtensionManagementService"; //$NON-NLS-1$

        private static final String EXTENSION_SERVICE =
            "com._1c.g5.v8.dt.form.service.extension.IFormExtensionService"; //$NON-NLS-1$

        private static final String DATASOURCE_INFO_SERVICE =
            "com._1c.g5.v8.dt.form.service.datasourceinfo.IDataSourceInfoAssociationService"; //$NON-NLS-1$

        private static final String MANAGEMENT_BUNDLE =
            "com.e1c.g5.v8.dt.form.extension"; //$NON-NLS-1$

        private static final String MANAGEMENT_PLUGIN =
            "com.e1c.g5.v8.dt.internal.form.extension.FormExtensionPlugin"; //$NON-NLS-1$

        private static final String FORM_BUNDLE = "com._1c.g5.v8.dt.form"; //$NON-NLS-1$

        private static final String FORM_PLUGIN = "com._1c.g5.v8.dt.internal.form.FormPlugin"; //$NON-NLS-1$

        /**
         * Where the three EDT services come from. The implementation answers
         * only; what is done with a missing service belongs to the question
         * methods of the port.
         */
        interface ServiceLookup
        {
            /**
             * @param serviceName the interface the service is registered under
             * @param bundleName the bundle to ask the injector of when the
             *            service registry has nothing
             * @param pluginName the plugin class holding that injector
             * @return the service instance, or null when this EDT has none
             */
            Object find(String serviceName, String bundleName, String pluginName);
        }

        private static volatile EdtPort instance;

        private final ServiceLookup services;

        /** The port the runtime uses: the services come from the registry and the injector. */
        EdtPort()
        {
            this(EdtPort::service);
        }

        /**
         * @param services where the three EDT services come from
         */
        EdtPort(ServiceLookup services)
        {
            this.services = services;
        }

        static EdtPort instance()
        {
            EdtPort mine = instance;
            if (mine != null)
            {
                return mine;
            }
            synchronized (EdtPort.class)
            {
                if (instance == null)
                {
                    instance = new EdtPort();
                }
                return instance;
            }
        }

        /**
         * @param serviceName the interface the service is registered under
         * @param bundleName the bundle to ask the injector of when the service
         *            registry has nothing
         * @param pluginName the plugin class holding that injector
         * @return the service instance, or null when this EDT has none
         */
        private static Object service(String serviceName, String bundleName, String pluginName)
        {
            try
            {
                BundleContext context = FrameworkUtil.getBundle(EdtPort.class).getBundleContext();
                if (context != null)
                {
                    ServiceReference<?> reference = context.getServiceReference(serviceName);
                    if (reference != null)
                    {
                        Object service = context.getService(reference);
                        try
                        {
                            if (service != null)
                            {
                                return service;
                            }
                        }
                        finally
                        {
                            context.ungetService(reference);
                        }
                    }
                }
            }
            catch (Exception e)
            {
                Activator.logWarning("FormExtensionDataPathGuard: OSGi lookup of " //$NON-NLS-1$
                    + serviceName + " failed: " + e.getMessage()); //$NON-NLS-1$
            }
            return fromInjector(serviceName, bundleName, pluginName);
        }

        /**
         * @param serviceName the interface the injector is asked for
         * @param bundleName the bundle owning the injector
         * @param pluginName the plugin class holding it
         * @return the bound instance, or null when the bundle or the binding is
         *         absent
         */
        private static Object fromInjector(String serviceName, String bundleName, String pluginName)
        {
            try
            {
                Bundle bundle = Platform.getBundle(bundleName);
                if (bundle == null)
                {
                    return null;
                }
                // loadClass goes through the owning bundle's classloader, so a
                // plugin class in an x-internal package resolves even though
                // this bundle does not import it.
                Class<?> pluginClass = bundle.loadClass(pluginName);
                Object plugin = pluginClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
                if (plugin == null)
                {
                    return null;
                }
                Object injector = pluginClass.getMethod("getInjector").invoke(plugin); //$NON-NLS-1$
                if (injector == null)
                {
                    return null;
                }
                Class<?> serviceInterface = bundle.loadClass(serviceName);
                return injector.getClass().getMethod("getInstance", Class.class) //$NON-NLS-1$
                    .invoke(injector, serviceInterface);
            }
            catch (Exception e)
            {
                Activator.logWarning("FormExtensionDataPathGuard: injector lookup of " //$NON-NLS-1$
                    + serviceName + " failed: " + e.getMessage()); //$NON-NLS-1$
                return null;
            }
        }

        private Object management()
        {
            return services.find(MANAGEMENT_SERVICE, MANAGEMENT_BUNDLE, MANAGEMENT_PLUGIN);
        }

        private Object extension()
        {
            return services.find(EXTENSION_SERVICE, FORM_BUNDLE, FORM_PLUGIN);
        }

        private Object dataSourceInfo()
        {
            return services.find(DATASOURCE_INFO_SERVICE, FORM_BUNDLE, FORM_PLUGIN);
        }

        @Override
        public Answer isExtensionBelongingObject(Object attribute)
        {
            Object service = extension();
            if (service == null)
            {
                return Answer.NOT_ASKED;
            }
            try
            {
                Object answer = service.getClass()
                    .getMethod("isExtensionBelongingObject", Object.class) //$NON-NLS-1$
                    .invoke(service, attribute);
                if (!(answer instanceof Boolean))
                {
                    return Answer.NOT_ASKED;
                }
                return Boolean.TRUE.equals(answer) ? Answer.TRUE : Answer.FALSE;
            }
            catch (Exception e)
            {
                Activator.logWarning("FormExtensionDataPathGuard: isExtensionBelongingObject " //$NON-NLS-1$
                    + "failed: " + e.getMessage()); //$NON-NLS-1$
                return Answer.NOT_ASKED;
            }
        }

        @Override
        public void adoptObject(Object attribute) throws Exception
        {
            Object service = management();
            if (service == null)
            {
                throw new IllegalStateException(
                    "служба заимствования объектов формы недоступна"); //$NON-NLS-1$
            }
            service.getClass().getMethod("adoptObject", Object.class).invoke(service, attribute); //$NON-NLS-1$
        }

        @Override
        public Answer exportSkip(Object form, Object dataPath)
        {
            Object service = extension();
            if (service == null)
            {
                return Answer.NOT_ASKED;
            }
            try
            {
                // shouldSkipForExport delegates to the private
                // shouldSkipDataPathForExport(form, path, true) as soon as the
                // feature argument is null and the object is a DataPath - the
                // call the contract names, through the one public door onto it.
                Method shouldSkip = null;
                for (Method candidate : service.getClass().getMethods())
                {
                    if ("shouldSkipForExport".equals(candidate.getName()) //$NON-NLS-1$
                        && candidate.getParameterCount() == 4)
                    {
                        shouldSkip = candidate;
                        break;
                    }
                }
                if (shouldSkip == null)
                {
                    return Answer.NOT_ASKED;
                }
                Object answer = shouldSkip.invoke(service, form, dataPath, null, null);
                if (!(answer instanceof Boolean))
                {
                    return Answer.NOT_ASKED;
                }
                return Boolean.TRUE.equals(answer) ? Answer.TRUE : Answer.FALSE;
            }
            catch (Exception e)
            {
                Activator.logWarning("FormExtensionDataPathGuard: shouldSkipForExport failed: " //$NON-NLS-1$
                    + e.getMessage());
                return Answer.NOT_ASKED;
            }
        }

        @Override
        public Answer pathResolved(Object form, Object dataPath)
        {
            Object service = dataSourceInfo();
            if (service == null)
            {
                return Answer.NOT_ASKED;
            }
            try
            {
                Method resolve = null;
                for (Method candidate : service.getClass().getMethods())
                {
                    if ("isPathResolved".equals(candidate.getName()) //$NON-NLS-1$
                        && candidate.getParameterCount() == 2)
                    {
                        resolve = candidate;
                        break;
                    }
                }
                if (resolve == null)
                {
                    return Answer.NOT_ASKED;
                }
                Object answer = resolve.invoke(service, form, dataPath);
                if (!(answer instanceof Boolean))
                {
                    return Answer.NOT_ASKED;
                }
                return Boolean.TRUE.equals(answer) ? Answer.TRUE : Answer.FALSE;
            }
            catch (Exception e)
            {
                Activator.logWarning("FormExtensionDataPathGuard: isPathResolved failed: " //$NON-NLS-1$
                    + e.getMessage());
                return Answer.NOT_ASKED;
            }
        }
    }
}
