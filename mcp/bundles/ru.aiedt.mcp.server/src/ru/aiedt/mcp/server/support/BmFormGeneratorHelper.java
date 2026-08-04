/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * Resolves and invokes EDT's {@code IFormGenerator} so {@code create_form}
 * produces a RENDERABLE managed form (main attribute + default layout),
 * identical to the EDT "New Form" wizard. <p>
 *
 * Background: the headless empty path ({@link FormBaseSetup#buildEmptyForm})
 * builds a bare {@code Form} root with the 11 base properties but without the
 * owner's main attribute or any default fields, so the form opens "empty". The
 * EDT wizard instead calls {@code IFormGenerator.generateForm(...)}, which
 * computes the default field set from the owner metadata and the form purpose.
 * This helper mirrors {@link BmExtensionHelper#resolveModelObjectAdopter()}:
 * the generator is obtained through the {@code FormPlugin} Guice injector by
 * reflection, so the MCP plugin survives even when
 * {@code com._1c.g5.v8.dt.internal.form} is not reachable on the runtime. <p>
 *
 * All form.generator types (the {@code IFormGenerator} interface, the
 * {@code FormType} enum, {@code FormFieldInfo}) are referenced reflectively.
 * The mdclass types ({@code Configuration}, {@code ScriptVariant},
 * {@code InterfaceCompatibilityMode}) are already imported and used directly
 * where convenient.
 */
public final class BmFormGeneratorHelper
{
    /** Bundle hosting IFormGenerator / FormType / FormFieldInfo. */
    private static final String FORM_BUNDLE = "com._1c.g5.v8.dt.form"; //$NON-NLS-1$

    /** FormPlugin (internal) - exposes getDefault()/getInjector(). */
    private static final String FORM_PLUGIN =
        "com._1c.g5.v8.dt.internal.form.FormPlugin"; //$NON-NLS-1$

    /** The public form-generator interface. */
    private static final String IFORM_GENERATOR =
        "com._1c.g5.v8.dt.form.generator.IFormGenerator"; //$NON-NLS-1$

    /** The FormType enum (form purpose). */
    private static final String FORM_TYPE =
        "com._1c.g5.v8.dt.form.generator.FormType"; //$NON-NLS-1$

    /** The field-tree generator - produces the FormFieldInfo generateForm requires. */
    private static final String IFORM_FIELD_GENERATOR =
        "com._1c.g5.v8.dt.form.generator.IFormFieldGenerator"; //$NON-NLS-1$

    /** Cached generator instance; resolved once per runtime. */
    private static volatile Object cachedGenerator;

    /** Guards a second resolve attempt after a confirmed miss. */
    private static volatile boolean resolveAttempted;

    private BmFormGeneratorHelper()
    {
        // utility
    }

    /**
     * Outcome of {@link #generate}.
     */
    public static final class Result
    {
        /** True when a form was generated and is ready to attach. */
        public boolean ok;
        /** The generated {@code Form} root (an {@code IBmObject}); null on miss. */
        public Object generatedForm;
        /** True when the generator service itself is not available on this runtime. */
        public boolean generatorNotFound;
        /** Error message when the generator was found but the call failed. */
        public String error;
        /** The FormType constant name actually used (e.g. {@code OBJECT}). */
        public String formPurpose;
    }

    /**
     * Resolves the {@code IFormGenerator} singleton through the
     * {@code FormPlugin} Guice injector by reflection (mirrors
     * {@link BmExtensionHelper#resolveModelObjectAdopter()}). The instance is
     * cached; on a confirmed miss a single warning is logged and {@code null}
     * is returned without re-probing.
     *
     * @return the generator instance, or {@code null} when the form bundle /
     *     plugin / injector is unreachable on this EDT runtime
     */
    public static Object resolveFormGenerator()
    {
        if (cachedGenerator != null)
        {
            return cachedGenerator;
        }
        if (resolveAttempted)
        {
            return null;
        }
        resolveAttempted = true;
        try
        {
            Bundle b = Platform.getBundle(FORM_BUNDLE);
            if (b == null)
            {
                Activator.logWarning("Bundle " + FORM_BUNDLE + " not present - " //$NON-NLS-1$ //$NON-NLS-2$
                    + "create_form falls back to the empty path"); //$NON-NLS-1$
                return null;
            }
            // FormPlugin lives in com._1c.g5.v8.dt.internal.form (x-internal),
            // so Class.forName from this bundle would fail the OSGi visibility
            // check. Bundle.loadClass goes through the owning bundle classloader.
            Class<?> pluginClass = b.loadClass(FORM_PLUGIN);
            Object plugin = pluginClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
            if (plugin == null)
            {
                Activator.logWarning("FormPlugin.getDefault() returned null - " //$NON-NLS-1$
                    + "bundle not started yet"); //$NON-NLS-1$
                return null;
            }
            Object injector = pluginClass.getMethod("getInjector").invoke(plugin); //$NON-NLS-1$
            if (injector == null)
            {
                Activator.logWarning("FormPlugin.getInjector() returned null"); //$NON-NLS-1$
                return null;
            }
            Class<?> ifg = b.loadClass(IFORM_GENERATOR);
            // Invoke getInstance via the com.google.inject.Injector INTERFACE, not
            // injector.getClass(): the concrete class is the x-internal
            // com.google.inject.internal.InjectorImpl, whose public getInstance is
            // NOT accessible from this bundle (IllegalAccessException). The exported
            // Injector interface method resolves and invokes cleanly.
            Class<?> injectorIface = b.loadClass("com.google.inject.Injector"); //$NON-NLS-1$
            Object gen = injectorIface.getMethod("getInstance", Class.class) //$NON-NLS-1$
                .invoke(injector, ifg);
            cachedGenerator = gen;
            return gen;
        }
        catch (ClassNotFoundException cnf)
        {
            Activator.logWarning("FormPlugin / IFormGenerator not on classpath: " //$NON-NLS-1$
                + cnf.getMessage());
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveFormGenerator failed: " //$NON-NLS-1$
                + e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * True when the form generator is resolvable on the current runtime.
     */
    public static boolean isAvailable()
    {
        return resolveFormGenerator() != null;
    }

    /**
     * Invokes {@code IFormGenerator.generateForm(...)} to build a renderable
     * form root for the given owner and form wrapper.
     * <p>
     * Must run INSIDE the same BM read-write transaction that created the
     * wrapper - the generated EMF objects belong to the model graph. The caller
     * attaches the returned form to the wrapper ({@code wrapper.setForm(...)})
     * and registers it as a BM top-object.
     *
     * @param owner          the owner {@code MdObject} (Catalog / Document / ...)
     *     resolved inside the transaction
     * @param mdFormWrapper  the {@code BasicForm} wrapper created via
     *     {@code createOwnerScopedObject(owner, "Form")}
     * @param purposeConst   the {@code FormType} constant NAME to use
     *     (e.g. {@code "OBJECT"}, {@code "LIST"}, {@code "GENERIC"})
     * @param config         the owning {@code Configuration} (for script variant
     *     and interface-compatibility mode); may be null
     * @param project        the host project (for the runtime {@code Version})
     * @return a {@link Result}; never null
     */
    public static Result generate(MdObject owner, Object mdFormWrapper, String purposeConst,
        Configuration config, IProject project)
    {
        Result r = new Result();
        Object generator = resolveFormGenerator();
        if (generator == null)
        {
            r.generatorNotFound = true;
            return r;
        }
        try
        {
            Bundle b = Platform.getBundle(FORM_BUNDLE);
            if (b == null)
            {
                r.generatorNotFound = true;
                return r;
            }
            // --- Resolve the FormType enum constant ---------------------------
            Class<?> formTypeClass = b.loadClass(FORM_TYPE);
            Object formTypeValue = resolveEnumConstant(formTypeClass, purposeConst);
            if (formTypeValue == null)
            {
                // Last-resort: GENERIC always exists.
                formTypeValue = resolveEnumConstant(formTypeClass, "GENERIC"); //$NON-NLS-1$
            }
            r.formPurpose = formTypeValue != null
                ? ((Enum<?>) formTypeValue).name() : purposeConst;

            // --- ScriptVariant (from configuration, default RUSSIAN) ----------
            Object scriptVariant = resolveScriptVariant(config);

            // --- Platform Version (from IRuntimeVersionSupport) ---------------
            Object version = resolveRuntimeVersion(project);

            // --- InterfaceCompatibilityMode (from configuration) --------------
            Object compatMode = resolveCompatibilityMode(config);

            String languageCode = "ru"; //$NON-NLS-1$

            // --- Find generateForm(...) by name + arity (9 params) ------------
            Method generateForm = findGenerateForm(generator.getClass());
            if (generateForm == null)
            {
                r.error = "IFormGenerator.generateForm(9-arg) not found on " //$NON-NLS-1$
                    + generator.getClass().getName();
                return r;
            }
            Class<?>[] pt = generateForm.getParameterTypes();
            // Positional mapping below assumes the documented 9-arg signature.
            // If a different overload was resolved (no 9-arg present), bail with
            // an error so the caller falls back to the empty path - building the
            // argument array blindly for an unknown shape would be unsafe.
            if (pt.length != 9)
            {
                r.error = "IFormGenerator.generateForm has unexpected arity " //$NON-NLS-1$
                    + pt.length + " (expected 9)"; //$NON-NLS-1$
                return r;
            }
            // Signature:
            //   generateForm(MdObject owner, BasicForm mdForm, FormType formType,
            //                ScriptVariant scriptVariant, String languageCode,
            //                Version version, FormFieldInfo fields, Integer startId,
            //                InterfaceCompatibilityMode mode)
            // generateForm NPEs on a null FormFieldInfo (it dereferences
            // rootField.getChildren()). Build the default field tree via
            // IFormFieldGenerator.getFormGeneratorFields(owner, formType,
            // scriptVariant, version) - the same fields the New Form wizard uses.
            Object fields = computeFormFields(b, owner, formTypeValue, scriptVariant, version);
            if (fields == null)
            {
                r.error = "IFormFieldGenerator.getFormGeneratorFields produced no field tree " //$NON-NLS-1$
                    + "(generateForm requires a non-null FormFieldInfo)"; //$NON-NLS-1$
                return r;
            }
            Object[] args = new Object[pt.length];
            args[0] = owner;
            args[1] = mdFormWrapper;
            args[2] = coerceOrNull(pt[2], formTypeValue);
            args[3] = coerceOrNull(pt[3], scriptVariant);
            args[4] = languageCode;
            args[5] = coerceOrNull(pt[5], version);
            args[6] = fields;
            args[7] = Integer.valueOf(1); // startId
            args[8] = coerceOrNull(pt[8], compatMode);

            generateForm.setAccessible(true);
            Object generated = generateForm.invoke(generator, args);
            if (generated == null)
            {
                r.error = "IFormGenerator.generateForm returned null"; //$NON-NLS-1$
                return r;
            }
            r.generatedForm = generated;
            r.ok = true;
            return r;
        }
        catch (ClassNotFoundException cnf)
        {
            r.error = "form.generator type missing: " + cnf.getMessage(); //$NON-NLS-1$
            return r;
        }
        catch (java.lang.reflect.InvocationTargetException ite)
        {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            r.error = "generateForm threw " + cause.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + cause.getMessage(); //$NON-NLS-1$
            return r;
        }
        catch (Exception e)
        {
            r.error = "generateForm failed: " + e.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + e.getMessage(); //$NON-NLS-1$
            return r;
        }
    }

    /**
     * Builds the default form-field tree via {@code IFormFieldGenerator} (the same
     * fields EDT's New Form wizard computes). {@code generateForm} requires a
     * non-null {@code FormFieldInfo} (it dereferences {@code rootField.getChildren()}).
     * Returns null when the field generator is unavailable or yields nothing.
     */
    private static Object computeFormFields(Bundle b, MdObject owner, Object formTypeValue,
        Object scriptVariant, Object version)
    {
        try
        {
            Object fieldGen = injectorService(b, IFORM_FIELD_GENERATOR);
            if (fieldGen == null)
            {
                return null;
            }
            for (Method m : fieldGen.getClass().getMethods())
            {
                if ("getFormGeneratorFields".equals(m.getName()) && m.getParameterCount() == 4) //$NON-NLS-1$
                {
                    Class<?>[] p = m.getParameterTypes();
                    m.setAccessible(true);
                    return m.invoke(fieldGen, owner, coerceOrNull(p[1], formTypeValue),
                        coerceOrNull(p[2], scriptVariant), coerceOrNull(p[3], version));
                }
            }
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("computeFormFields failed: " + e.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Resolves a singleton (service) from the FormPlugin Guice injector by its
     * class / interface FQN. Returns {@code null} when the form bundle, plugin
     * or injector is unavailable on this runtime. Public so other form helpers
     * (e.g. event resolution via {@code FormItemInformationService}) can reuse
     * the same injector path.
     */
    public static Object resolveFormService(String serviceFqn)
    {
        try
        {
            Bundle b = Platform.getBundle(FORM_BUNDLE);
            if (b == null)
            {
                return null;
            }
            return injectorService(b, serviceFqn);
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveFormService(" + serviceFqn + ") failed: " //$NON-NLS-1$
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return null;
        }
    }

    /**
     * Resolves a singleton from the FormPlugin Guice injector by interface FQN.
     * Uses the public {@code com.google.inject.Injector} interface method (the
     * concrete InjectorImpl is x-internal and not accessible).
     */
    private static Object injectorService(Bundle b, String ifaceFqn) throws Exception
    {
        Class<?> pluginClass = b.loadClass(FORM_PLUGIN);
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
        Class<?> iface = b.loadClass(ifaceFqn);
        Class<?> injectorIface = b.loadClass("com.google.inject.Injector"); //$NON-NLS-1$
        return injectorIface.getMethod("getInstance", Class.class).invoke(injector, iface); //$NON-NLS-1$
    }

    /**
     * Locates {@code generateForm} by name. Prefers the 9-arg overload (the
     * full wizard signature); falls back to the highest-arity {@code generateForm}
     * when only a single overload is present.
     */
    private static Method findGenerateForm(Class<?> generatorClass)
    {
        Method best = null;
        for (Method m : generatorClass.getMethods())
        {
            if (!"generateForm".equals(m.getName())) //$NON-NLS-1$
            {
                continue;
            }
            if (m.getParameterCount() == 9)
            {
                return m;
            }
            if (best == null || m.getParameterCount() > best.getParameterCount())
            {
                best = m;
            }
        }
        return best;
    }

    /**
     * Resolves an enum constant by name on the given enum class (case-insensitive
     * fallback across declared constants). Returns null when the class is not an
     * enum or no constant matches.
     */
    private static Object resolveEnumConstant(Class<?> enumClass, String name)
    {
        if (enumClass == null || name == null || !enumClass.isEnum())
        {
            return null;
        }
        for (Object c : enumClass.getEnumConstants())
        {
            if (((Enum<?>) c).name().equalsIgnoreCase(name))
            {
                return c;
            }
        }
        return null;
    }

    /**
     * Reads {@code configuration.getScriptVariant()}; defaults to the
     * {@code RUSSIAN} constant of the {@code ScriptVariant} enum when the
     * configuration is null or exposes no value.
     */
    private static Object resolveScriptVariant(Configuration config)
    {
        try
        {
            if (config != null)
            {
                Object sv = config.getScriptVariant();
                if (sv != null)
                {
                    return sv;
                }
            }
            // Default to RUSSIAN via the mdclass enum.
            Class<?> svClass =
                Class.forName("com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant"); //$NON-NLS-1$
            return resolveEnumConstant(svClass, "RUSSIAN"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveScriptVariant failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Reads {@code configuration.getInterfaceCompatibilityMode()} reflectively
     * (the getter name is stable, but the type is resolved at runtime). Returns
     * null when unavailable - the generator tolerates a null mode.
     */
    private static Object resolveCompatibilityMode(Configuration config)
    {
        if (config == null)
        {
            return null;
        }
        try
        {
            Method m = config.getClass().getMethod("getInterfaceCompatibilityMode"); //$NON-NLS-1$
            return m.invoke(config);
        }
        catch (NoSuchMethodException nsm)
        {
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveCompatibilityMode failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Resolves the platform {@code Version} for the project through EDT's
     * {@code IRuntimeVersionSupport} - the exact path used by
     * {@code BmDefinedTypeHelper.createCanonicalPrimitiveProxy}. Returns null
     * when the service or call is unavailable; the generator falls back to a
     * default version in that case.
     */
    private static Object resolveRuntimeVersion(IProject project)
    {
        try
        {
            Activator activator = Activator.getDefault();
            if (activator == null || project == null)
            {
                return null;
            }
            Object versionSupport = activator.getRuntimeVersionSupport();
            if (versionSupport == null)
            {
                return null;
            }
            Method getRuntimeVersion = versionSupport.getClass()
                .getMethod("getRuntimeVersion", IProject.class); //$NON-NLS-1$
            return getRuntimeVersion.invoke(versionSupport, project);
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveRuntimeVersion failed: " //$NON-NLS-1$
                + e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Returns {@code value} when it is assignment-compatible with the parameter
     * type (or the parameter is non-primitive and {@code value} is null);
     * otherwise null. Keeps a mismatched optional argument from breaking the
     * call - the generator treats a null script-variant / version / mode as
     * "use the default".
     */
    private static Object coerceOrNull(Class<?> paramType, Object value)
    {
        if (value != null && paramType.isInstance(value))
        {
            return value;
        }
        return null;
    }
}
