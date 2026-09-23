/*
 * Copyright (c) 2026 Desko77
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
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
 * extension in the same write, and a path EDT would drop on export is refused
 * before a single change is saved.
 *
 * <p>The whole decision lives behind {@link Port}, which stands for the two EDT
 * services that own the question -
 * {@code com.e1c.g5.v8.dt.form.extension.IFormExtensionManagementService} (is an
 * object adopted, adopt it) and
 * {@code com._1c.g5.v8.dt.form.service.extension.IFormExtensionService} (does
 * this path survive export of an extension form). The runtime implementation
 * reaches both reflectively, so the bundle needs no compile dependency on them;
 * a test installs its own port and sees the entire decision.
 *
 * <p>A form of a configuration project is left alone: the services are not
 * asked at all, because the guard recognises an extension form by its non-null
 * base form before it touches the port.
 */
public final class FormExtensionDataPathGuard
{
    /**
     * The seam over the two EDT services. An implementation answers only; the
     * order of the questions and what is done with the answers belongs to
     * {@link #assign}.
     */
    public interface Port
    {
        /**
         * @param attribute a form attribute
         * @return true when the attribute already belongs to the extension
         *         (adopted), so a path through it survives export
         */
        boolean isExtensionBelongingObject(Object attribute);

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
         * @return a human-readable reason when EDT will not write the path with
         *         the form, or null when the path survives export - and also
         *         when the question cannot be answered at all, because refusing
         *         on an unanswerable question would refuse every path
         */
        String exportSkipReason(Object form, Object dataPath);
    }

    /**
     * What the assignment did: the attributes it borrowed, and - when the path
     * cannot reach the database - the refusal that must undo the write.
     */
    public static final class Outcome
    {
        private final List<String> adoptedAttributes = new ArrayList<>();

        private String refusal;

        /** @return the borrowed attribute names, in the order they were borrowed */
        public List<String> getAdoptedAttributes()
        {
            return Collections.unmodifiableList(adoptedAttributes);
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
     * starts at, then asks whether the path survives export. On a configuration
     * form it answers immediately, without asking the port anything.
     *
     * @param form the form the element belongs to, or null when unknown
     * @param dataPath the assigned {@code AbstractDataPath}, or null when the
     *            path was cleared
     * @param pathText the dotted path as the caller wrote it
     * @param itemName the element the path was assigned to, for the refusal text
     * @return what was borrowed and whether the write may stand
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
            return outcome;
        }

        String root = firstSegment(pathText);
        Object attribute = findAttribute(form, root);
        if (attribute == null)
        {
            // The first segment names no form attribute. Whether such a path is
            // legal is EDT's own form-data-path check, not this guard's.
            return outcome;
        }

        try
        {
            if (!port.isExtensionBelongingObject(attribute))
            {
                port.adoptObject(attribute);
                outcome.adoptedAttributes.add(root);
                if (!port.isExtensionBelongingObject(attribute))
                {
                    // The call returned but the attribute still does not belong
                    // to the extension: the path would be dropped on export.
                    outcome.refusal = refusal(itemName, pathText,
                        "реквизит " + root + " не заимствован расширением");
                    return outcome;
                }
            }
        }
        catch (Exception e)
        {
            String reason = e.getMessage() != null && !e.getMessage().isEmpty()
                ? e.getMessage() : e.getClass().getSimpleName();
            outcome.refusal = refusal(itemName, pathText,
                "заимствовать реквизит " + root + " не удалось: " + reason);
            return outcome;
        }

        String skip = port.exportSkipReason(form, dataPath);
        if (skip != null)
        {
            outcome.refusal = refusal(itemName, pathText, skip);
        }
        return outcome;
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
     * compile time.
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
                if (name.equals(attributeName))
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
     * The runtime port: the two EDT services reached by name, so the bundle
     * gains no compile dependency on their packages.
     *
     * <p>Every method answers "do not refuse" when its service is missing: a
     * guard that cannot see EDT must not block a write, and the alternative -
     * refusing because the question could not be asked - would refuse every
     * path on any install whose bundles are named differently.
     */
    private static final class EdtPort implements Port
    {
        private static final String MANAGEMENT_SERVICE =
            "com.e1c.g5.v8.dt.form.extension.IFormExtensionManagementService"; //$NON-NLS-1$

        private static final String EXTENSION_SERVICE =
            "com._1c.g5.v8.dt.form.service.extension.IFormExtensionService"; //$NON-NLS-1$

        private static final String MANAGEMENT_BUNDLE =
            "com.e1c.g5.v8.dt.form.extension"; //$NON-NLS-1$

        private static final String MANAGEMENT_PLUGIN =
            "com.e1c.g5.v8.dt.internal.form.extension.FormExtensionPlugin"; //$NON-NLS-1$

        private static final String FORM_BUNDLE = "com._1c.g5.v8.dt.form"; //$NON-NLS-1$

        private static final String FORM_PLUGIN = "com._1c.g5.v8.dt.internal.form.FormPlugin"; //$NON-NLS-1$

        private static volatile EdtPort instance;

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
        private Object service(String serviceName, String bundleName, String pluginName)
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
        private Object fromInjector(String serviceName, String bundleName, String pluginName)
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
            return service(MANAGEMENT_SERVICE, MANAGEMENT_BUNDLE, MANAGEMENT_PLUGIN);
        }

        private Object extension()
        {
            return service(EXTENSION_SERVICE, FORM_BUNDLE, FORM_PLUGIN);
        }

        @Override
        public boolean isExtensionBelongingObject(Object attribute)
        {
            Object service = extension();
            if (service == null)
            {
                return true;
            }
            try
            {
                Object answer = service.getClass()
                    .getMethod("isExtensionBelongingObject", Object.class) //$NON-NLS-1$
                    .invoke(service, attribute);
                return Boolean.TRUE.equals(answer);
            }
            catch (Exception e)
            {
                Activator.logWarning("FormExtensionDataPathGuard: isExtensionBelongingObject " //$NON-NLS-1$
                    + "failed: " + e.getMessage()); //$NON-NLS-1$
                return true;
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
        public String exportSkipReason(Object form, Object dataPath)
        {
            Object service = extension();
            if (service == null)
            {
                return null;
            }
            try
            {
                // shouldSkipForExport delegates to the private
                // shouldSkipDataPathForExport(form, path, true) as soon as the
                // feature argument is null and the object is a DataPath - the
                // call the contract names, through the one public door onto it.
                Class<?> dataPathClass = dataPath.getClass();
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
                    return null;
                }
                Object answer = shouldSkip.invoke(service, form, dataPath, null, null);
                if (!Boolean.TRUE.equals(answer))
                {
                    return null;
                }
                // The answer says "dropped". Its first step reads the path's
                // referred objects, a transient list a path built here never
                // fills, and reports "dropped" for an empty one whatever the
                // attribute is. So the belonging question is asked directly -
                // that is the condition the export actually turns on.
                if (hasReferredObjects(dataPath, dataPathClass))
                {
                    return "путь не выгружается расширением"; //$NON-NLS-1$
                }
                Object root = rootAttribute(form, dataPath);
                if (root != null && !isExtensionBelongingObject(root))
                {
                    return "реквизит " + nameOf(root) + " не принадлежит расширению"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                return null;
            }
            catch (Exception e)
            {
                Activator.logWarning("FormExtensionDataPathGuard: shouldSkipForExport failed: " //$NON-NLS-1$
                    + e.getMessage());
                return null;
            }
        }

        /**
         * @param dataPath the assigned path
         * @param dataPathClass its class, for the reflective call
         * @return true when the path carries at least one referred object
         */
        private boolean hasReferredObjects(Object dataPath, Class<?> dataPathClass)
        {
            try
            {
                Object objects = dataPathClass.getMethod("getObjects").invoke(dataPath); //$NON-NLS-1$
                if (!(objects instanceof java.util.Collection))
                {
                    return false;
                }
                return !((java.util.Collection<?>) objects).isEmpty();
            }
            catch (Exception e)
            {
                return false;
            }
        }

        /**
         * @param form the form
         * @param dataPath the assigned path
         * @return the form attribute the path starts at, or null
         */
        private Object rootAttribute(Object form, Object dataPath)
        {
            try
            {
                Object segments = dataPath.getClass().getMethod("getSegments").invoke(dataPath); //$NON-NLS-1$
                if (!(segments instanceof java.util.List) || ((java.util.List<?>) segments).isEmpty())
                {
                    return null;
                }
                Object first = ((java.util.List<?>) segments).get(0);
                return first instanceof String ? findAttribute(form, (String) first) : null;
            }
            catch (Exception e)
            {
                return null;
            }
        }

        /**
         * @param attribute a form attribute
         * @return its name, or its class name when it has none to read
         */
        private String nameOf(Object attribute)
        {
            try
            {
                Object name = attribute.getClass().getMethod("getName").invoke(attribute); //$NON-NLS-1$
                return name instanceof String ? (String) name : attribute.getClass().getSimpleName();
            }
            catch (Exception e)
            {
                return attribute.getClass().getSimpleName();
            }
        }
    }
}
