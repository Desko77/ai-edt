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
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import ru.aiedt.mcp.server.Activator;

/**
 * Keeps a data path assigned to an element of an extension form reachable from
 * the database: the base-form attribute the path starts at is borrowed into the
 * extension in the same write, and a path the form does not resolve is refused
 * before a single change is saved.
 *
 * <p>The whole decision lives behind {@link Port}, which stands for the three
 * EDT services that own the questions -
 * {@code com.e1c.g5.v8.dt.form.extension.IFormExtensionManagementService} (is an
 * object adopted, adopt it),
 * {@code com._1c.g5.v8.dt.form.service.extension.IFormExtensionService} (does
 * this path survive export of an extension form) and
 * {@code com._1c.g5.v8.dt.form.service.datasourceinfo.IDataSourceInfoAssociationService}
 * (does the form resolve the path at all). The runtime implementation reaches
 * all three reflectively, so the bundle needs no compile dependency on them; a
 * test installs its own port and sees the entire decision.
 *
 * <p>Why the form's own resolution of the path, and not the export answer
 * alone, decides the refusal. EDT's export decision reads the path's referred
 * objects first: {@code IFormExtensionService.shouldSkipForExport} calls a
 * private {@code shouldSkipDataPathForExport(form, path, true)}, which asks
 * {@code getAttributeRef(path)} - and that method returns an empty reference as
 * soon as one referred object is null or the list itself is empty, on which the
 * decision answers "drop". The referred objects are derived data: a transient
 * list filled by the form's path index after the edit, which a path built here
 * out of segments never carries. The export answer is therefore "drop" for
 * every path the guard assembles, whatever the path names - it cannot tell a
 * path to a live attribute from a path to nothing. What does separate them is
 * the form's own resolution of the path ({@code findPropertyInfo} walks every
 * segment, ignoring case and the {@code [...]} index of a collection), and that
 * is the question the refusal hangs on. A path that resolves but that the
 * extension still cannot reference - a segment of another engine, a tail
 * past the borrowed attribute - is accepted: the referred-object list that
 * would decide it is the same derived data the guard cannot see, and no EDT
 * check reports it either - {@code form-data-path} fires only on a segment
 * nothing resolves. Such a path is written and can be dropped on export
 * without a marker to warn about it.
 *
 * <p>A form of a configuration project is left alone: the services are not
 * asked at all, because the guard recognises an extension form by its non-null
 * base form before it touches the port.
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
     * Decides whether the assignment of a data path may stand.
     *
     * <p>On an extension form it borrows the base-form attribute the path
     * starts at, asks whether EDT writes the path with the form, and refuses the
     * write when the form does not resolve the path either. A question that
     * cannot be asked refuses nothing and is named in the outcome, so a caller
     * reads what was checked instead of reading a check that never ran. On a
     * configuration form it answers immediately, without asking the port
     * anything.
     *
     * @param form the form the element belongs to, or null when unknown
     * @param dataPath the assigned {@code AbstractDataPath}, or null when the
     *            path was cleared
     * @param pathText the dotted path as the caller wrote it
     * @param itemName the element the path was assigned to, for the refusal text
     * @return what was borrowed, what could not be asked, and whether the write
     *         may stand
     */
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
        if (port == null)
        {
            outcome.notAskedChecks.add(CHECK_BELONGING);
            outcome.notAskedChecks.add(CHECK_EXPORT);
            outcome.notAskedChecks.add(CHECK_RESOLUTION);
            return outcome;
        }

        String root = firstSegment(pathText);
        Object attribute = findAttribute(form, root);
        // Whether the path starts at an object that is the extension's own - as
        // opposed to a base-form attribute this write borrowed. Only the first
        // kind is left out of the resolution question below: an operation that
        // creates an attribute and binds a path to it in the same transaction
        // (a dynamic list table, a settings composer) is asking about an object
        // the model has not indexed yet, and a "no" there would undo a write that
        // is right.
        boolean ownAttribute = false;
        if (attribute != null)
        {
            Answer belongs = port.isExtensionBelongingObject(attribute);
            if (belongs == Answer.NOT_ASKED)
            {
                // Whether the path survives export depends on this answer, so
                // asking further would compare answers about paths that may belong
                // to the base form. The write goes through as if the guard were
                // not there, and the answer says the check was not performed.
                outcome.notAskedChecks.add(CHECK_BELONGING);
                return outcome;
            }
            if (belongs == Answer.TRUE)
            {
                ownAttribute = true;
            }
            else
            {
                try
                {
                    port.adoptObject(attribute);
                }
                catch (Exception e)
                {
                    outcome.refusal = refusal(itemName, pathText,
                        "заимствовать реквизит " + nameOf(attribute, root) + " не удалось: " //$NON-NLS-1$ //$NON-NLS-2$
                            + reasonOf(e));
                    return outcome;
                }
                outcome.adoptedAttributes.add(nameOf(attribute, root));
                Answer took = port.isExtensionBelongingObject(attribute);
                if (took == Answer.NOT_ASKED)
                {
                    // The borrow call returned but this answer did not: the model
                    // was changed and the write must not be undone on a question
                    // that failed, so the path is left alone and the check named.
                    outcome.notAskedChecks.add(CHECK_BELONGING);
                    return outcome;
                }
                if (took == Answer.FALSE)
                {
                    // The call returned but the attribute still does not belong
                    // to the extension: the path would be dropped on export.
                    outcome.refusal = refusal(itemName, pathText,
                        "реквизит " + nameOf(attribute, root) + " не заимствован расширением"); //$NON-NLS-1$ //$NON-NLS-2$
                    return outcome;
                }
            }
        }

        Answer skip = port.exportSkip(form, dataPath);
        if (skip == Answer.NOT_ASKED)
        {
            outcome.notAskedChecks.add(CHECK_EXPORT);
            return outcome;
        }
        if (skip == Answer.FALSE)
        {
            // EDT writes the path with the form: there is nothing to refuse.
            return outcome;
        }
        if (ownAttribute)
        {
            // The path starts at an object that already belongs to the extension -
            // for the operations that create their own attribute it is this very
            // write's object, and the form's resolution answer about it inside the
            // same transaction says nothing about the file.
            return outcome;
        }
        Answer resolved = port.pathResolved(form, dataPath);
        if (resolved == Answer.NOT_ASKED)
        {
            outcome.notAskedChecks.add(CHECK_RESOLUTION);
            return outcome;
        }
        if (resolved == Answer.FALSE)
        {
            // The export answer alone cannot condemn a path (see the class
            // comment): the path the form cannot resolve leads nowhere, and that
            // is what is refused - nothing reaches Form.form.
            outcome.refusal = refusal(itemName, pathText,
                "EDT его не выгружает, и форма его не разрешает"); //$NON-NLS-1$
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
            + ": путь данных " + pathText //$NON-NLS-1$
            + " не будет выгружен с формой расширения (" + reason + "); ничего не записано"; //$NON-NLS-1$ //$NON-NLS-2$
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
     * @param pathText a dotted data path
     * @return the first segment, or an empty string when there is none
     */
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
     * Tells an extension form from a configuration one. A form belongs to an
     * extension exactly when it declares a base form (Form.baseForm); that is a
     * fact of the form model, not of the services, so the check costs nothing
     * and asks the port nothing - which is what keeps configuration forms out
     * of this guard entirely.
     *
     * @param form the form
     * @return true when the form has a base form, so it is an extension form
     */
    private static boolean isExtensionForm(Object form)
    {
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
                        if (service != null)
                        {
                            return service;
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
