/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.version.Version;

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

    /** What the attempts at finding the generator have left behind. */
    private static final ResolutionCache CACHE = new ResolutionCache();

    private BmFormGeneratorHelper()
    {
        // utility
    }

    /**
     * The two facts an attempt at resolving the generator leaves behind: the generator, once one is
     * found, and whether this runtime carries one at all.
     * <p>
     * A runtime without the form bundle answers {@code null} for good, and asking it again would cost
     * a class lookup on every {@code create_form} call - so that answer is kept. A runtime that
     * carries the bundle but has not started it yet answers the same {@code null}, and that answer
     * must not be kept: the next call is the one that finds the generator. Keeping it made every form
     * created in that window fall back to the empty path for the rest of the session.
     * </p>
     */
    static final class ResolutionCache
    {
        private volatile Object generator;

        private volatile boolean runtimeLacksIt;

        /**
         * @return the generator found by an earlier attempt, or {@code null} when none was
         */
        Object generator()
        {
            return generator;
        }

        /**
         * @return true when this runtime has already answered that it does not carry the generator
         */
        boolean runtimeLacksIt()
        {
            return runtimeLacksIt;
        }

        /**
         * Records the outcome of one attempt. A generator, once found, is kept: a later miss cannot
         * undo it, and neither can it turn the runtime into one that lacks the generator.
         *
         * @param found the generator the attempt produced, or {@code null} when it produced none
         * @param absentOnThisRuntime true when the attempt could not have found one here - the bundle
         *            or the class is not on this runtime - and no later attempt will either
         */
        void record(Object found, boolean absentOnThisRuntime)
        {
            if (found != null)
            {
                generator = found;
                runtimeLacksIt = false;
                return;
            }
            if (generator == null)
            {
                runtimeLacksIt = absentOnThisRuntime;
            }
        }
    }

    /**
     * Outcome of {@link #generate}. Besides the ok / error verdict, the outcome carries what a
     * caller needs to judge the generated layout without opening the form: the item count of the
     * produced tree, which {@code getFormGeneratorFields} overload answered and how large its field
     * tree was, and every argument whose value did not fit the parameter type it was passed to.
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
        /** Items in the generated form's item tree, counted recursively; -1 when nothing was generated. */
        public int itemCount = -1;
        /** Parameter count of the {@code getFormGeneratorFields} overload that answered (5 preferred
         *  over 4); 0 when no matching overload was found. */
        public int fieldsOverloadArgs;
        /** Nodes in the field tree the overload returned, its root included; -1 when it did not answer. */
        public int fieldTreeSize = -1;
        /** One entry per {@code generateForm} / {@code getFormGeneratorFields} argument whose value
         *  failed its parameter type: "<code>call.argument</code>: expected &lt;type&gt;, got &lt;type&gt;". */
        public List<String> coercionMismatches = new ArrayList<>();
    }

    /**
     * What {@link #computeFormFields} got out of one {@code getFormGeneratorFields} call: the
     * returned tree root (possibly null), the overload that answered it, and that tree's node count.
     */
    static final class Fields
    {
        /** The {@code FormFieldInfo} root the overload returned, or null when it returned nothing. */
        final Object root;
        /** Parameter count of the overload that answered. */
        final int overloadArgs;
        /** Nodes in the tree, root included; 0 when the root is null. */
        final int treeSize;

        Fields(Object root, int overloadArgs, int treeSize)
        {
            this.root = root;
            this.overloadArgs = overloadArgs;
            this.treeSize = treeSize;
        }
    }

    /**
     * Resolves the {@code IFormGenerator} singleton through the
     * {@code FormPlugin} Guice injector by reflection (mirrors
     * {@link BmExtensionHelper#resolveModelObjectAdopter()}). The instance is
     * cached; a miss this runtime cannot recover from is kept, and one it can - the bundle present
     * but not started, a call that threw - is not, so the next call asks again.
     *
     * @return the generator instance, or {@code null} when the form bundle /
     *     plugin / injector is unreachable on this EDT runtime
     */
    public static Object resolveFormGenerator()
    {
        Object found = CACHE.generator();
        if (found != null)
        {
            return found;
        }
        if (CACHE.runtimeLacksIt())
        {
            return null;
        }
        try
        {
            Bundle b = Platform.getBundle(FORM_BUNDLE);
            if (b == null)
            {
                Activator.logWarning("Bundle " + FORM_BUNDLE + " not present - " //$NON-NLS-1$ //$NON-NLS-2$
                    + "create_form falls back to the empty path"); //$NON-NLS-1$
                CACHE.record(null, true);
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
                // The plugin starts later in the session, so this miss is not final.
                CACHE.record(null, false);
                return null;
            }
            Object injector = pluginClass.getMethod("getInjector").invoke(plugin); //$NON-NLS-1$
            if (injector == null)
            {
                Activator.logWarning("FormPlugin.getInjector() returned null"); //$NON-NLS-1$
                CACHE.record(null, false);
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
            CACHE.record(gen, false);
            return gen;
        }
        catch (ClassNotFoundException cnf)
        {
            Activator.logWarning("FormPlugin / IFormGenerator not on classpath: " //$NON-NLS-1$
                + cnf.getMessage());
            CACHE.record(null, true);
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveFormGenerator failed: " //$NON-NLS-1$
                + e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$
            CACHE.record(null, false);
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
            // --- The field-tree generator (same Guice injector as the wizard) --
            Object fieldGenerator = injectorService(b, IFORM_FIELD_GENERATOR);
            return invokeGeneration(generator, fieldGenerator, formTypeValue, owner, mdFormWrapper,
                purposeConst, config, project);
        }
        catch (ClassNotFoundException cnf)
        {
            r.error = "form.generator type missing: " + cnf.getMessage(); //$NON-NLS-1$
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
     * Drives one already-resolved {@code IFormGenerator} / {@code IFormFieldGenerator} pair through
     * the generateForm call. Split from {@link #generate} so the decision logic - overload choice,
     * the empty-field-tree refusal, coercion reporting, item counting - runs against any objects
     * exposing the two method shapes, which is what the fragment test exercises; {@code generate}
     * only adds the bundle / injector resolution around it.
     * <p>
     * Refuses (a {@link Result} with {@code ok=false} and an {@code error}) when the field
     * generator is unavailable or its tree is empty: a null root cannot be passed ({@code
     * generateForm} dereferences {@code rootField.getChildren()}), and a root without children
     * leaves the generator nothing to lay out, so answering success over such a tree would hide an
     * impoverished form behind {@code formGenerated=true}.
     *
     * @param generator       the resolved {@code IFormGenerator} instance
     * @param fieldGenerator  the resolved {@code IFormFieldGenerator} instance, or null
     * @param formTypeValue   the resolved {@code FormType} enum constant (or a stand-in in tests)
     * @param owner           the owner {@code MdObject}
     * @param mdFormWrapper   the {@code BasicForm} wrapper
     * @param purposeConst    the requested {@code FormType} constant name (fallback reporting)
     * @param config          the owning {@code Configuration}; may be null
     * @param project         the host project; may be null
     * @return a {@link Result}; never null
     */
    static Result invokeGeneration(Object generator, Object fieldGenerator, Object formTypeValue,
        MdObject owner, Object mdFormWrapper, String purposeConst, Configuration config,
        IProject project)
    {
        Result r = new Result();
        r.formPurpose = formTypeValue instanceof Enum<?> ? ((Enum<?>) formTypeValue).name()
            : formTypeValue != null ? String.valueOf(formTypeValue) : purposeConst;
        try
        {
            // --- ScriptVariant (from configuration, default RUSSIAN) ----------
            Object scriptVariant = resolveScriptVariant(config);

            // --- Platform Version (from IRuntimeVersionSupport) ---------------
            Object version = resolveRuntimeVersion(project);

            // --- Compatibility Version (wizard: parseCompatibilityMode) -------
            Object compatibilityVersion = resolveCompatibilityVersion(config, version);

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
            //                Version version, FormFieldInfo fields, Integer columnCount,
            //                InterfaceCompatibilityMode mode)
            // generateForm NPEs on a null FormFieldInfo (it dereferences
            // rootField.getChildren()). Build the default field tree via
            // IFormFieldGenerator.getFormGeneratorFields - the same fields the
            // New Form wizard computes, through the same overloads it calls.
            Fields fields = computeFormFields(fieldGenerator, owner, formTypeValue, scriptVariant,
                version, compatibilityVersion, r);
            if (fields == null || fields.root == null)
            {
                r.error = "IFormFieldGenerator.getFormGeneratorFields produced no field tree " //$NON-NLS-1$
                    + "(generateForm requires a non-null FormFieldInfo); field generator " //$NON-NLS-1$
                    + "available: " + (fieldGenerator != null); //$NON-NLS-1$
                return r;
            }
            if (fields.treeSize <= 1)
            {
                r.error = "IFormFieldGenerator.getFormGeneratorFields(" + fields.overloadArgs //$NON-NLS-1$
                    + "-arg) returned an empty field tree: the root carries no children, " //$NON-NLS-1$
                    + "so the form generator has nothing to lay out"; //$NON-NLS-1$
                return r;
            }
            Object[] args = new Object[pt.length];
            args[0] = owner;
            args[1] = mdFormWrapper;
            args[2] = coerceOrNull(pt[2], formTypeValue, "generateForm.formType", r); //$NON-NLS-1$
            args[3] = coerceOrNull(pt[3], scriptVariant, "generateForm.scriptVariant", r); //$NON-NLS-1$
            args[4] = languageCode;
            args[5] = coerceOrNull(pt[5], version, "generateForm.version", r); //$NON-NLS-1$
            args[6] = fields.root;
            // The wizard's COLUMN COUNT, not an item id: FormNewWizardRelatedModelsFactory
            // passes FormWizardContext.getColumnCount() here, and FormGeneratorCore.generateColumns
            // builds the Header group with LeftColumn / RightColumn subgroups only above 1 (the
            // count is capped at the number of fields). One column is the wizard's default, so 1
            // reproduces the default wizard layout; generateForm dereferences the value
            // (Integer.intValue()), so it cannot be null.
            args[7] = Integer.valueOf(1);
            args[8] = coerceOrNull(pt[8], compatMode, "generateForm.compatibilityMode", r); //$NON-NLS-1$

            generateForm.setAccessible(true);
            Object generated = generateForm.invoke(generator, args);
            if (generated == null)
            {
                r.error = "IFormGenerator.generateForm returned null"; //$NON-NLS-1$
                return r;
            }
            r.itemCount = countFormItems(generated);
            r.generatedForm = generated;
            r.ok = true;
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
     * <p>
     * The overload choice follows the wizard: {@code FormWizardModel} calls the 5-argument
     * {@code getFormGeneratorFields(owner, formType, scriptVariant, version, compatibilityVersion)}
     * when the runtime carries it (EDT 2026.2 does; the interface's own 4-argument overload then
     * delegates to it passing the runtime version as the compatibility version), and the 4-argument
     * one is the fallback for runtimes without the newer signature. Which overload answered and how
     * large the returned tree is are recorded on {@code outcome} so a caller can see what the
     * layout was built from.
     *
     * @param fieldGenerator       the {@code IFormFieldGenerator} instance, or null when unavailable
     * @param owner                the owner {@code MdObject}
     * @param formTypeValue        the resolved {@code FormType} constant
     * @param scriptVariant        the resolved {@code ScriptVariant}
     * @param version              the runtime {@code Version}
     * @param compatibilityVersion the compatibility {@code Version} (wizard's 5th argument)
     * @param outcome              the result the overload / tree-size observations are recorded on
     * @return the tree the overload returned with its shape facts, or null when the field
     *         generator is unavailable, matches no overload, or the call failed
     */
    static Fields computeFormFields(Object fieldGenerator, MdObject owner, Object formTypeValue,
        Object scriptVariant, Object version, Object compatibilityVersion, Result outcome)
    {
        if (fieldGenerator == null)
        {
            return null;
        }
        try
        {
            Method m = findFieldsOverload(fieldGenerator.getClass());
            if (m == null)
            {
                return null;
            }
            Class<?>[] p = m.getParameterTypes();
            Object[] args = new Object[p.length];
            args[0] = owner;
            args[1] = coerceOrNull(p[1], formTypeValue, "getFormGeneratorFields.formType", outcome); //$NON-NLS-1$
            args[2] = coerceOrNull(p[2], scriptVariant, "getFormGeneratorFields.scriptVariant", outcome); //$NON-NLS-1$
            args[3] = coerceOrNull(p[3], version, "getFormGeneratorFields.version", outcome); //$NON-NLS-1$
            if (p.length >= 5)
            {
                args[4] = coerceOrNull(p[4], compatibilityVersion,
                    "getFormGeneratorFields.compatibilityVersion", outcome); //$NON-NLS-1$
            }
            m.setAccessible(true);
            Object root = m.invoke(fieldGenerator, args);
            int treeSize = countFieldTreeNodes(root);
            outcome.fieldsOverloadArgs = p.length;
            outcome.fieldTreeSize = root != null ? treeSize : 0;
            return new Fields(root, p.length, root != null ? treeSize : 0);
        }
        catch (Exception e)
        {
            Activator.logWarning("computeFormFields failed: " + e.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Picks the {@code getFormGeneratorFields} overload to call: the 5-argument variant the New
     * Form wizard uses when the runtime has it, otherwise the 4-argument one. Matching is by name
     * and parameter count, as the generator types are resolved reflectively.
     *
     * @param fieldGeneratorClass the concrete field-generator class
     * @return the chosen method, or null when the class declares neither overload
     */
    static Method findFieldsOverload(Class<?> fieldGeneratorClass)
    {
        Method fourArg = null;
        for (Method m : fieldGeneratorClass.getMethods())
        {
            if (!"getFormGeneratorFields".equals(m.getName())) //$NON-NLS-1$
            {
                continue;
            }
            if (m.getParameterCount() == 5)
            {
                return m;
            }
            if (m.getParameterCount() == 4 && fourArg == null)
            {
                fourArg = m;
            }
        }
        return fourArg;
    }

    /**
     * Counts the nodes of a {@code FormFieldInfo} tree: the root plus every descendant reachable
     * through {@code getChildren()}. A tree whose count is 1 is a root without children - the
     * shape {@code getRootField()} produces for a purpose the field generator has no layout for.
     *
     * @param node a {@code FormFieldInfo} root, or null
     * @return the node count, 0 for null
     */
    static int countFieldTreeNodes(Object node)
    {
        if (node == null)
        {
            return 0;
        }
        int count = 1;
        Object children = invokeNoArg(node, "getChildren"); //$NON-NLS-1$
        if (children instanceof Collection<?>)
        {
            for (Object child : (Collection<?>) children)
            {
                count += countFieldTreeNodes(child);
            }
        }
        return count;
    }

    /**
     * Counts the items of a generated form's item tree: every entry under {@code getItems()},
     * plus the nested items of containers (a group, a table, a page also expose {@code getItems()}).
     * The form root itself is not counted, so a form with one field answers 1 and a wizard layout
     * with groups and standard fields answers more.
     *
     * @param form the generated {@code Form} root, or null
     * @return the item count, 0 for null
     */
    static int countFormItems(Object form)
    {
        if (form == null)
        {
            return 0;
        }
        int count = 0;
        Object items = invokeNoArg(form, "getItems"); //$NON-NLS-1$
        if (items instanceof Collection<?>)
        {
            for (Object item : (Collection<?>) items)
            {
                count += 1 + countFormItems(item);
            }
        }
        return count;
    }

    /**
     * Invokes a no-arg method by name; returns its result or null on any miss. A receiver without
     * the member reads as "no such property", not as a failure.
     *
     * @param target the receiver, may be null
     * @param method the no-arg method name
     * @return the invocation result, or null
     */
    private static Object invokeNoArg(Object target, String method)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            return target.getClass().getMethod(method).invoke(target);
        }
        catch (Exception ignored)
        {
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
        // Configuration declares getInterfaceCompatibilityMode(), so it is called rather
        // than looked up by name. The NoSuchMethodException arm went with the lookup: a
        // build without the method would not resolve at all.
        try
        {
            return config.getInterfaceCompatibilityMode();
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
     * type; otherwise null, with the mismatch recorded on {@code outcome} as
     * "<code>callAndArg</code>: expected &lt;parameter type&gt;, got &lt;value type&gt;". Keeps a
     * mismatched optional argument from breaking the call - the generator treats a null
     * script-variant / version / mode as "use the default" - while making the drop visible to the
     * caller instead of silent. A null value (nothing was resolved to pass) records nothing: no
     * value was dropped by a type check.
     *
     * @param paramType the parameter the value is being passed to
     * @param value     the resolved value, may be null
     * @param callAndArg call and argument name for the mismatch record (e.g. {@code
     *        generateForm.scriptVariant})
     * @param outcome   the result the mismatch is recorded on
     * @return the value when it fits the parameter, otherwise null
     */
    private static Object coerceOrNull(Class<?> paramType, Object value, String callAndArg,
        Result outcome)
    {
        if (value != null && paramType.isInstance(value))
        {
            return value;
        }
        if (value != null)
        {
            outcome.coercionMismatches.add(callAndArg + ": expected " + paramType.getName() //$NON-NLS-1$
                + ", got " + value.getClass().getName()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Resolves the compatibility {@code Version} the wizard passes as the fifth argument of the
     * 5-argument {@code getFormGeneratorFields}: {@code Version.parseCompatibilityMode} of the
     * configuration's {@code CompatibilityMode}, falling back to the runtime version when the
     * configuration or its mode is absent (the fallback {@code FormWizardModel} itself uses).
     *
     * @param config         the owning {@code Configuration}; may be null
     * @param runtimeVersion the runtime {@code Version} (the fallback value)
     * @return the compatibility version, or the runtime version when there is nothing to parse
     */
    private static Object resolveCompatibilityVersion(Configuration config, Object runtimeVersion)
    {
        try
        {
            if (config != null)
            {
                CompatibilityMode mode = config.getCompatibilityMode();
                if (mode != null)
                {
                    return Version.parseCompatibilityMode(mode);
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("resolveCompatibilityVersion failed: " //$NON-NLS-1$
                + e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$
        }
        return runtimeVersion;
    }
}
