/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * DCS schema operations for {@code dcs_workshop}. Reflection-based to stay
 * compatible across EDT versions.
 * <p>
 * <b>1.35 status:</b> in-memory schema mutation through a write transaction.
 * Direct save to disk in extension projects is delegated to
 * {@link DcsExtensionExportHelper}; recovery from disk to BM is in
 * {@link DcsExtensionImportHelper}.
 */
public final class BmDcsHelper
{
    // EDT 9.x splits the DCS model factory across subpackages. Schema-level
    // elements (DataCompositionSchema, data sets, fields, parameters, calculated /
    // total fields) live in .schema; settings-level elements (filters, groups,
    // order, selection, conditional appearance, variants) in .settings.
    // createElement() tries both factories.
    private static final String DCS_FACTORY = "com._1c.g5.v8.dt.dcs.model.schema.DcsFactory"; //$NON-NLS-1$
    private static final String DCS_SETTINGS_FACTORY = "com._1c.g5.v8.dt.dcs.model.settings.DcsFactory"; //$NON-NLS-1$
    // 1.43.x DCS catch-up foundation: the core factory holds the value-carrier
    // elements (DataCompositionField, Presentation, appearance) that filter /
    // order / selection / grouping fields and titles need. It is a third factory
    // alongside schema/settings; without it those properties cannot be wired
    // (a String cannot be assigned where a DataCompositionField/Presentation is
    // expected). Package com._1c.g5.v8.dt.dcs.model.core must be in Import-Package.
    private static final String DCS_CORE_FACTORY = "com._1c.g5.v8.dt.dcs.model.core.DcsFactory"; //$NON-NLS-1$
    private static final String DCS_SCHEMA = "com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema"; //$NON-NLS-1$
    // 1.43.x DCS batch 4a: the mcore value model holds the literal value carriers
    // (StringValue / Value) that settings parameter values and filter right-values
    // are typed as - a bare String does not persist where an mcore.Value is
    // expected. Package com._1c.g5.v8.dt.mcore must be in Import-Package.
    private static final String MCORE_FACTORY = "com._1c.g5.v8.dt.mcore.McoreFactory"; //$NON-NLS-1$

    /** Default DCS template name on Reports / DataProcessors / etc. */
    public static final String DEFAULT_TEMPLATE_NAME = "MainDataCompositionSchema"; //$NON-NLS-1$

    private static volatile Boolean cachedAvailable;
    private static volatile Object cachedFactory;
    private static volatile Object cachedSettingsFactory;
    private static volatile Object cachedCoreFactory;
    private static volatile Object cachedMcoreFactory;

    private BmDcsHelper()
    {
        // utility class
    }

    public static boolean isAvailable()
    {
        Boolean cached = cachedAvailable;
        if (cached != null)
        {
            return cached.booleanValue();
        }
        boolean ok = false;
        try
        {
            Class.forName(DCS_FACTORY);
            Class.forName(DCS_SCHEMA);
            ok = true;
        }
        catch (ClassNotFoundException e)
        {
            Activator.logWarning("BmDcsHelper: DCS API not available - " + e.getMessage()); //$NON-NLS-1$
        }
        cachedAvailable = Boolean.valueOf(ok);
        return ok;
    }

    public static String deferredMessage(String operation)
    {
        return "DCS operation '" + operation //$NON-NLS-1$
            + "' is not yet implemented in this build. " //$NON-NLS-1$
            + (isAvailable()
                ? "DCS API is reachable - implementation pending." //$NON-NLS-1$
                : "DCS API is NOT reachable in this EDT version."); //$NON-NLS-1$
    }

    /**
     * Resolved DcsFactory instance via {@code DcsFactory.eINSTANCE}, cached.
     */
    public static Object getFactory()
    {
        Object cached = cachedFactory;
        if (cached != null)
        {
            return cached;
        }
        try
        {
            Class<?> factoryClass = Class.forName(DCS_FACTORY);
            Object f = factoryClass.getField("eINSTANCE").get(null); //$NON-NLS-1$
            cachedFactory = f;
            return f;
        }
        catch (Exception e)
        {
            Activator.logWarning("BmDcsHelper.getFactory failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Resolved settings-level {@code DcsFactory} via {@code eINSTANCE}, cached.
     * Holds the factory for filter / group / order / selection / appearance /
     * variant elements (the {@code .settings} subpackage).
     */
    public static Object getSettingsFactory()
    {
        Object cached = cachedSettingsFactory;
        if (cached != null)
        {
            return cached;
        }
        try
        {
            Class<?> factoryClass = Class.forName(DCS_SETTINGS_FACTORY);
            Object f = factoryClass.getField("eINSTANCE").get(null); //$NON-NLS-1$
            cachedSettingsFactory = f;
            return f;
        }
        catch (Exception e)
        {
            Activator.logWarning("BmDcsHelper.getSettingsFactory failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Resolved core-level {@code DcsFactory} via {@code eINSTANCE}, cached. Holds
     * the value-carrier elements (DataCompositionField, Presentation, appearance)
     * that field/title/value properties on filter / order / selection / grouping
     * items require. 1.43.x DCS catch-up foundation. Returns {@code null} when the
     * {@code com._1c.g5.v8.dt.dcs.model.core} package is unavailable on the runtime.
     */
    public static Object getCoreFactory()
    {
        Object cached = cachedCoreFactory;
        if (cached != null)
        {
            return cached;
        }
        try
        {
            Class<?> factoryClass = Class.forName(DCS_CORE_FACTORY);
            Object f = factoryClass.getField("eINSTANCE").get(null); //$NON-NLS-1$
            cachedCoreFactory = f;
            return f;
        }
        catch (Exception e)
        {
            Activator.logWarning("BmDcsHelper.getCoreFactory failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Builds a {@code DataCompositionField} carrying the dotted field path
     * (e.g. "Номенклатура.Артикул"). DCS filter/order/selection/grouping items
     * store their field as a DataCompositionField (extends mcore.Value with
     * {@code setValue(String)}), not a bare String - so this is the construction
     * path for any "field" property. Returns {@code null} when the core factory
     * is unavailable.
     */
    public static Object createDataCompositionField(String fieldPath)
    {
        Object created = invokeFactoryMethod(getCoreFactory(), "createDataCompositionField"); //$NON-NLS-1$
        if (created == null)
        {
            return null;
        }
        if (fieldPath != null)
        {
            try
            {
                created.getClass().getMethod("setValue", String.class).invoke(created, fieldPath); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Activator.logWarning("createDataCompositionField setValue failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return created;
    }

    /**
     * Builds a {@code Presentation} carrying a title/caption string. DCS
     * {@code title}/{@code presentation} properties are Presentation objects
     * (getValue/setValue(String)), not bare Strings. Returns {@code null} when
     * the core factory is unavailable.
     */
    public static Object createPresentation(String text)
    {
        Object created = invokeFactoryMethod(getCoreFactory(), "createPresentation"); //$NON-NLS-1$
        if (created == null)
        {
            return null;
        }
        if (text != null)
        {
            try
            {
                created.getClass().getMethod("setValue", String.class).invoke(created, text); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Activator.logWarning("createPresentation setValue failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return created;
    }

    /**
     * Resolved {@code McoreFactory} instance via {@code McoreFactory.eINSTANCE},
     * cached like {@link #getCoreFactory()}. Holds the literal value carriers
     * (StringValue / Value) needed for settings-parameter values and filter
     * right-values. Returns {@code null} when the {@code com._1c.g5.v8.dt.mcore}
     * package is unavailable on the runtime.
     */
    public static Object getMcoreFactory()
    {
        Object cached = cachedMcoreFactory;
        if (cached != null)
        {
            return cached;
        }
        try
        {
            Class<?> factoryClass = Class.forName(MCORE_FACTORY);
            Object f = factoryClass.getField("eINSTANCE").get(null); //$NON-NLS-1$
            cachedMcoreFactory = f;
            return f;
        }
        catch (Exception e)
        {
            Activator.logWarning("BmDcsHelper.getMcoreFactory failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Creates an empty mcore {@code TypeDescription} via the McoreFactory, used to
     * carry the value type of a DCS schema parameter ({@code parameter.valueType}).
     * Returns {@code null} when the factory is unreachable.
     */
    public static Object createMcoreTypeDescription()
    {
        return invokeFactoryMethod(getMcoreFactory(), "createTypeDescription"); //$NON-NLS-1$
    }

    /**
     * Builds an mcore {@code StringValue} carrying a literal string. DCS settings
     * parameter values and filter right-values are typed as {@code mcore.Value}
     * (StringValue exposes {@code getValue()}/{@code setValue(String)}); a bare
     * String does not persist where a Value is expected. Null-safe: a {@code null}
     * argument still produces an empty StringValue. Returns {@code null} when the
     * mcore factory is unreachable.
     * <p>
     * <b>1.43.x batch 4a:</b> String-only for now - number / boolean / date typing
     * is a later refinement.
     */
    public static Object createLiteralValue(String s)
    {
        Object created = invokeFactoryMethod(getMcoreFactory(), "createStringValue"); //$NON-NLS-1$
        if (created == null)
        {
            return null;
        }
        try
        {
            created.getClass().getMethod("setValue", String.class).invoke(created, s); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logWarning("createLiteralValue setValue failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return created;
    }

    /**
     * Builds an mcore {@code ColorValue} wrapping a {@code ColorDef} parsed from
     * a {@code "#RRGGBB"} (or bare {@code "RRGGBB"}) hex string. DCS conditional
     * appearance color parameters (ЦветТекста / ЦветФона / ЦветГраницы) are typed
     * as {@code mcore.Value} carrying a {@code mcore.Color}; a bare String does not
     * persist where the value is expected.
     * <p>
     * <b>1.43.x batch 4b:</b> all four factory methods (createColorValue /
     * createColorDef / createFontValue / createFontDef) live on McoreFactory.
     * Returns {@code null} on parse failure or when the mcore factory is
     * unreachable.
     */
    public static Object createColorValueFromHex(String hex)
    {
        if (hex == null)
        {
            return null;
        }
        String h = hex.trim();
        if (h.startsWith("#")) //$NON-NLS-1$
        {
            h = h.substring(1);
        }
        if (h.length() != 6)
        {
            return null;
        }
        int red;
        int green;
        int blue;
        try
        {
            red = Integer.parseInt(h.substring(0, 2), 16);
            green = Integer.parseInt(h.substring(2, 4), 16);
            blue = Integer.parseInt(h.substring(4, 6), 16);
        }
        catch (NumberFormatException nfe)
        {
            return null;
        }
        Object colorValue = invokeFactoryMethod(getMcoreFactory(), "createColorValue"); //$NON-NLS-1$
        Object colorDef = invokeFactoryMethod(getMcoreFactory(), "createColorDef"); //$NON-NLS-1$
        if (colorValue == null || colorDef == null)
        {
            return null;
        }
        try
        {
            colorDef.getClass().getMethod("setRed", int.class).invoke(colorDef, red); //$NON-NLS-1$
            colorDef.getClass().getMethod("setGreen", int.class).invoke(colorDef, green); //$NON-NLS-1$
            colorDef.getClass().getMethod("setBlue", int.class).invoke(colorDef, blue); //$NON-NLS-1$
            // ColorValue.setValue takes the model interface (mcore.Color), not the
            // concrete ColorDef subclass - resolve the setter by assignable param type.
            if (!invokeSingleArgSetter(colorValue, "setValue", colorDef)) //$NON-NLS-1$
            {
                return null;
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("createColorValueFromHex setValue failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
        return colorValue;
    }

    /**
     * Builds an mcore {@code FontValue} wrapping a {@code FontDef} parsed from a
     * {@code "FaceName,height[,bold][,italic]"} spec (e.g. {@code "Arial,12,bold"}).
     * DCS conditional appearance font parameter (Шрифт) is typed as
     * {@code mcore.Value} carrying a {@code mcore.Font}. Height is optional; bold /
     * italic are recognised case-insensitively anywhere in the spec. Returns
     * {@code null} on parse failure or when the mcore factory is unreachable.
     */
    public static Object createFontValueFromSpec(String spec)
    {
        if (spec == null || spec.trim().isEmpty())
        {
            return null;
        }
        String[] parts = spec.split(","); //$NON-NLS-1$
        String faceName = parts[0].trim();
        if (faceName.isEmpty())
        {
            return null;
        }
        Float height = null;
        if (parts.length > 1)
        {
            try
            {
                String h = parts[1].trim();
                if (!h.isEmpty())
                {
                    height = Float.valueOf(h);
                }
            }
            catch (NumberFormatException ignored)
            {
                // height stays null - face name only
            }
        }
        String lower = spec.toLowerCase();
        boolean bold = lower.contains("bold"); //$NON-NLS-1$
        boolean italic = lower.contains("italic"); //$NON-NLS-1$
        Object fontValue = invokeFactoryMethod(getMcoreFactory(), "createFontValue"); //$NON-NLS-1$
        Object fontDef = invokeFactoryMethod(getMcoreFactory(), "createFontDef"); //$NON-NLS-1$
        if (fontValue == null || fontDef == null)
        {
            return null;
        }
        try
        {
            fontDef.getClass().getMethod("setFaceName", String.class).invoke(fontDef, faceName); //$NON-NLS-1$
            if (height != null)
            {
                fontDef.getClass().getMethod("setHeight", float.class) //$NON-NLS-1$
                    .invoke(fontDef, height.floatValue());
            }
            fontDef.getClass().getMethod("setBold", boolean.class).invoke(fontDef, bold); //$NON-NLS-1$
            fontDef.getClass().getMethod("setItalic", boolean.class).invoke(fontDef, italic); //$NON-NLS-1$
            if (!invokeSingleArgSetter(fontValue, "setValue", fontDef)) //$NON-NLS-1$
            {
                return null;
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("createFontValueFromSpec failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
        return fontValue;
    }

    /**
     * Invokes a single-argument setter by name whose declared parameter type is
     * assignable from {@code arg}'s class. Used for {@code Value.setValue(Color)} /
     * {@code Value.setValue(Font)} where the parameter type is the model interface
     * (mcore.Color / mcore.Font), not the concrete *Def subclass. Returns
     * {@code true} on a successful invocation.
     */
    private static boolean invokeSingleArgSetter(Object target, String setterName, Object arg)
    {
        if (target == null || arg == null)
        {
            return false;
        }
        for (Method m : target.getClass().getMethods())
        {
            if (!setterName.equals(m.getName()) || m.getParameterCount() != 1)
            {
                continue;
            }
            if (m.getParameterTypes()[0].isAssignableFrom(arg.getClass()))
            {
                try
                {
                    m.invoke(target, arg);
                    return true;
                }
                catch (Exception e)
                {
                    Activator.logWarning(setterName + " invocation failed: " + e.getMessage()); //$NON-NLS-1$
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Result of a schema-mutation operation.
     * <p>
     * {@link #tags} carries machine-readable structured fields surfaced into
     * the JSON response (e.g. {@code notFound}, {@code alreadyExists},
     * {@code queryValidation}, {@code expressionValidation}). Always non-null.
     */
    public static final class Result
    {
        public boolean ok;
        public String error;
        public String schemaFqn;
        public String message;
        public DcsExtensionExportHelper.Result directSave; // populated for all non-dry-run writes
        public Map<String, Object> tags = new LinkedHashMap<>();
    }

    /**
     * Action executed inside a BM read-write transaction with the schema EObject
     * already resolved.
     */
    @FunctionalInterface
    public interface DcsAction
    {
        Object execute(IBmTransaction tx, EObject schema) throws Exception;
    }

    /**
     * Resolves the DCS schema by FQN and runs the action under a BM write
     * transaction. {@code dryRun=true} discards the changes.
     *
     * @param ownerFqn FQN of the metadata object (e.g. "Report.Sales") or the
     *            full schema FQN ("Report.Sales.Template.MainDCS.Template").
     */
    public static Result executeWriteOnSchema(IProject project, String ownerFqn, String templateName,
        boolean dryRun, DcsAction action)
    {
        Result r = new Result();
        if (project == null || ownerFqn == null || ownerFqn.isEmpty())
        {
            r.error = "project and ownerFqn are required"; //$NON-NLS-1$
            return r;
        }
        if (!isAvailable())
        {
            r.error = "DCS API is not available in this EDT runtime"; //$NON-NLS-1$
            return r;
        }
        // Resolve the actual DCS template when the caller gave none: a report's
        // default template name now follows the script variant
        // (ОсновнаяСхемаКомпоновкиДанных on Russian configs), so the hard-coded
        // English DEFAULT_TEMPLATE_NAME in buildSchemaFqn would miss it and the
        // op would fail "DCS schema not found". Prefer the owner's actual macro.
        if ((templateName == null || templateName.isEmpty()) && !ownerFqn.contains(".Template")) //$NON-NLS-1$
        {
            templateName = resolveOwnerDcsTemplateName(project, ownerFqn);
        }
        String schemaFqn = buildSchemaFqn(ownerFqn, templateName);
        r.schemaFqn = schemaFqn;

        IBmModelManager mm = Activator.getDefault().getBmModelManager();
        if (mm == null)
        {
            r.error = "Error: object model manager is not published as a service"; //$NON-NLS-1$
            return r;
        }
        IBmModel model = mm.getModel(project);
        if (model == null)
        {
            r.error = "Error: object model not loaded for project: " + project.getName(); //$NON-NLS-1$
            return r;
        }

        try
        {
            model.execute(new AbstractBmTask<Void>("dcs_workshop.write") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    try
                    {
                        IBmObject top = tx.getTopObjectByFqn(schemaFqn);
                        if (top == null)
                        {
                            throw new RuntimeException("DCS schema not found by FQN: " + schemaFqn); //$NON-NLS-1$
                        }
                        Object res = action.execute(tx, (EObject) top);
                        if (res != null)
                        {
                            r.message = res.toString();
                        }
                        if (dryRun)
                        {
                            throw new DryRunAbort();
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
            // Force-write the .dcs to disk for BOTH extensions and configurations:
            // EDT does not auto-sync a programmatic BM write back to the .dcs source,
            // so without this the schema stays BM-only (the file on disk stays empty,
            // the editor shows nothing, and nothing reaches git / the infobase).
            if (!dryRun)
            {
                r.directSave = DcsExtensionExportHelper.exportSchemaToDisk(mm, project, schemaFqn);
                noteDiskSave(r);
            }
        }
        catch (DryRunAbort dra)
        {
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
            Activator.logWarning("BmDcsHelper.executeWriteOnSchema failed: " + r.error); //$NON-NLS-1$
        }
        return r;
    }

    /**
     * Read-only compact overview of a report / data-processor DCS schema.
     * Returns a JSON-ready map (dataSets with field counts + query length,
     * calculated / total / parameter counts, settings-variant names), or
     * {@code null} when the owner has no DCS schema or the DCS API is
     * unavailable.
     * <p>
     * <b>H4:</b> lets {@code ai_context} / {@code object_summary} show the shape
     * of a large schema without dumping the full query+settings payload, which
     * floods the context on ЗУП / ERP reports.
     *
     * @param templateName explicit DCS template; resolved from the owner's
     *            main schema / single DCS template when {@code null} or empty.
     */
    public static Map<String, Object> summarizeSchema(IProject project, String ownerFqn,
        String templateName)
    {
        if (project == null || ownerFqn == null || ownerFqn.isEmpty() || !isAvailable())
        {
            return null;
        }
        String tpl = templateName;
        if ((tpl == null || tpl.isEmpty()) && !ownerFqn.contains(".Template")) //$NON-NLS-1$
        {
            tpl = resolveOwnerDcsTemplateName(project, ownerFqn);
        }
        final String schemaFqn = buildSchemaFqn(ownerFqn, tpl);
        IBmModelManager mm = Activator.getDefault().getBmModelManager();
        if (mm == null)
        {
            return null;
        }
        IBmModel model = mm.getModel(project);
        if (model == null)
        {
            return null;
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        try
        {
            model.executeReadonlyTask(new AbstractBmTask<Void>("dcs_workshop.overview") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    IBmObject top = tx.getTopObjectByFqn(schemaFqn);
                    if (top != null)
                    {
                        buildSchemaOverview((EObject)top, schemaFqn, out);
                    }
                    return null;
                }
            }, true);
        }
        catch (Exception e)
        {
            Activator.logWarning("BmDcsHelper.summarizeSchema failed: " //$NON-NLS-1$
                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return null;
        }
        // buildSchemaOverview writes "schemaFqn" as its first key whenever it runs
        // (only when the schema EObject was found), so its presence is the explicit
        // "schema exists" marker - more refactor-proof than checking out.isEmpty().
        return out.containsKey("schemaFqn") ? out : null; //$NON-NLS-1$
    }

    /** Walks a {@code DataCompositionSchema} EObject into the compact overview map. */
    private static void buildSchemaOverview(EObject schema, String schemaFqn, Map<String, Object> out)
    {
        out.put("schemaFqn", schemaFqn); //$NON-NLS-1$

        List<Map<String, Object>> dataSets = new ArrayList<>();
        int dataSetFieldCount = 0;
        EList<EObject> dsList = getEObjectList(schema, "getDataSets"); //$NON-NLS-1$
        if (dsList != null)
        {
            for (EObject ds : dsList)
            {
                Map<String, Object> dsMap = new LinkedHashMap<>();
                String dsName = stringValue(ds, "getName"); //$NON-NLS-1$
                dsMap.put("name", dsName != null ? dsName : "(unnamed)"); //$NON-NLS-1$ //$NON-NLS-2$
                dsMap.put("kind", dataSetKind(ds)); //$NON-NLS-1$
                EList<EObject> fields = getEObjectList(ds, "getFields"); //$NON-NLS-1$
                int fc = fields != null ? fields.size() : 0;
                dsMap.put("fieldCount", fc); //$NON-NLS-1$
                dataSetFieldCount += fc;
                String query = stringValue(ds, "getQuery"); //$NON-NLS-1$
                if (query != null && !query.isEmpty())
                {
                    dsMap.put("queryLength", query.length()); //$NON-NLS-1$
                    dsMap.put("queryPreview", compactPreview(query, 160)); //$NON-NLS-1$
                }
                dataSets.add(dsMap);
            }
        }
        out.put("dataSetCount", dataSets.size()); //$NON-NLS-1$
        out.put("dataSetFieldCount", dataSetFieldCount); //$NON-NLS-1$
        out.put("calculatedFieldCount", listSize(schema, "getCalculatedFields")); //$NON-NLS-1$ //$NON-NLS-2$
        out.put("totalFieldCount", listSize(schema, "getTotalFields")); //$NON-NLS-1$ //$NON-NLS-2$
        EList<EObject> params = getEObjectList(schema, "getParameters"); //$NON-NLS-1$
        out.put("parameterCount", params != null ? params.size() : 0); //$NON-NLS-1$
        EList<EObject> variants = getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        out.put("settingsVariantCount", variants != null ? variants.size() : 0); //$NON-NLS-1$
        out.put("dataSets", dataSets); //$NON-NLS-1$
        out.put("parameters", cappedNames(params, 40)); //$NON-NLS-1$
        out.put("settingsVariants", cappedNames(variants, 40)); //$NON-NLS-1$
    }

    /** Reflective no-arg getter that yields a non-empty String, else {@code null}. */
    private static String stringValue(Object target, String getter)
    {
        Object v = invokeNoArgQuiet(target, getter);
        if (v == null)
        {
            return null;
        }
        String s = v.toString();
        return s.isEmpty() ? null : s;
    }

    /** Dataset kind (Query / Object / Union) derived from the EClass name. */
    private static String dataSetKind(EObject ds)
    {
        if (ds == null || ds.eClass() == null)
        {
            return "Unknown"; //$NON-NLS-1$
        }
        String cn = ds.eClass().getName();
        int idx = cn.lastIndexOf("DataSet"); //$NON-NLS-1$
        if (idx >= 0)
        {
            String tail = cn.substring(idx + "DataSet".length()); //$NON-NLS-1$
            if (!tail.isEmpty())
            {
                return tail;
            }
        }
        return cn;
    }

    /** Size of a reflective EObject list getter, 0 when absent. */
    private static int listSize(Object target, String getter)
    {
        EList<EObject> list = getEObjectList(target, getter);
        return list != null ? list.size() : 0;
    }

    /** Names of the elements (via getName), capped with a "+N more" marker. */
    private static List<String> cappedNames(EList<EObject> list, int cap)
    {
        List<String> names = new ArrayList<>();
        if (list == null || list.isEmpty())
        {
            return names;
        }
        int i = 0;
        for (EObject e : list)
        {
            if (i >= cap)
            {
                names.add("...(+" + (list.size() - cap) + " more)"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            }
            String n = stringValue(e, "getName"); //$NON-NLS-1$
            names.add(n != null ? n : "(unnamed)"); //$NON-NLS-1$
            i++;
        }
        return names;
    }

    /** Single-line preview: collapse whitespace, cut to {@code max} chars with ellipsis. */
    private static String compactPreview(String s, int max)
    {
        String flat = s.replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        if (flat.length() <= max)
        {
            return flat;
        }
        return flat.substring(0, max) + "..."; //$NON-NLS-1$
    }

    /**
     * Resolves the parent metadata object (Report / DataProcessor / etc.)
     * and creates a default DCS schema as one of its templates. Returns
     * the resulting schema FQN.
     */
    public static Result createSchemaOnObject(IProject project, String objectFqn,
        String templateName, boolean dryRun)
    {
        Result r = new Result();
        if (!isAvailable())
        {
            r.error = "DCS API not available"; //$NON-NLS-1$
            return r;
        }
        IConfigurationProvider cp = Activator.getDefault().getConfigurationProvider();
        Configuration config = cp != null ? cp.getConfiguration(project) : null;
        if (config == null)
        {
            r.error = "Configuration not available"; //$NON-NLS-1$
            return r;
        }
        // Default template name follows the configuration script variant: the
        // EDT report wizard names it ОсновнаяСхемаКомпоновкиДанных on a Russian
        // config and MainDataCompositionSchema on an English one. An explicit
        // templateName always wins.
        if (templateName == null || templateName.isEmpty())
        {
            templateName = defaultDcsTemplateName(config);
        }
        r.schemaFqn = buildSchemaFqn(objectFqn, templateName);
        String[] parts = MetadataTypeCatalog.normalizeFqn(objectFqn).split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            r.error = "objectFqn must be 'Type.Name'"; //$NON-NLS-1$
            return r;
        }
        MdObject owner = MetadataTypeCatalog.findObject(config, parts[0], parts[1]);
        if (owner == null)
        {
            r.error = "No such object: " + objectFqn; //$NON-NLS-1$
            return r;
        }
        if (!(owner instanceof IBmObject))
        {
            r.error = "Owner is not a BM object"; //$NON-NLS-1$
            return r;
        }
        long ownerId = ((IBmObject) owner).bmGetId();

        IBmModelManager mm = Activator.getDefault().getBmModelManager();
        IBmModel model = mm != null ? mm.getModel(project) : null;
        if (model == null)
        {
            r.error = "BM model not available"; //$NON-NLS-1$
            return r;
        }

        final String finalTemplateName = templateName;
        try
        {
            // EDT 2026.1 top-object attach pattern (mirrors create_object's C1 fix):
            // use the global editing context + attachTopObject, otherwise the new
            // Template's DataCompositionSchema reference is deferred and the commit
            // fails with "Failed to persist reference value ...DataCompositionSchemaImpl".
            model.getGlobalContext().execute((IBmTask) new AbstractBmTask<Void>("dcs_workshop.create_schema") //$NON-NLS-1$
            {
                @Override
                public Void execute(IBmTransaction tx, IProgressMonitor pm)
                {
                    try
                    {
                        MdObject obj = (MdObject) tx.getObjectById(ownerId);
                        if (obj == null)
                        {
                            throw new RuntimeException("Owner not found in transaction"); //$NON-NLS-1$
                        }
                        // Templates list lookup
                        EList<MdObject> templates = invokeListGetter(obj, "getTemplates"); //$NON-NLS-1$
                        if (templates == null)
                        {
                            throw new RuntimeException("Unsupported object type '" + obj.eClass().getName() //$NON-NLS-1$
                                + "' has no Templates collection"); //$NON-NLS-1$
                        }
                        if (BmObjectHelper.findByName(templates, finalTemplateName) != null)
                        {
                            throw new RuntimeException("Template already exists: " //$NON-NLS-1$
                                + finalTemplateName);
                        }
                        MdObject template = BmObjectHelper.createGenericObject("Template"); //$NON-NLS-1$
                        if (template == null)
                        {
                            throw new RuntimeException("Cannot create template: " //$NON-NLS-1$
                                + "MdClassFactory.createTemplate() and MdClassPackage " //$NON-NLS-1$
                                + "lookup both unavailable on this EDT runtime."); //$NON-NLS-1$
                        }
                        template.setName(finalTemplateName);
                        BmObjectHelper.setProperty(template,
                            "templateType", "DataCompositionSchema"); //$NON-NLS-1$ //$NON-NLS-2$
                        // Create a minimal DCS schema and set it as Template.template.
                        Object factory = getFactory();
                        Object schema = null;
                        if (factory != null)
                        {
                            try
                            {
                                Method create = factory.getClass()
                                    .getMethod("createDataCompositionSchema"); //$NON-NLS-1$
                                schema = create.invoke(factory);
                                BmObjectHelper.setProperty(template, "template", schema); //$NON-NLS-1$
                                // The EDT wizard always creates an "Основной" settings variant;
                                // a schema with no variant opens read-only in the report editor.
                                addDefaultSettingsVariant(schema);
                            }
                            catch (NoSuchMethodException nsme)
                            {
                                // Newer factories may use a different method name; not fatal
                                Activator.logWarning("DcsFactory.createDataCompositionSchema not found"); //$NON-NLS-1$
                            }
                        }
                        templates.add(template);
                        // C4 fix: Report -> Templates is a CONTAINMENT reference, so templates.add()
                        // already attaches the Template as a top-object (an explicit attach errors
                        // "already attached"). The DataCompositionSchema (Template.template) is a
                        // SEPARATE non-containment top-object - DCS ops read it via
                        // getTopObjectByFqn(<owner>.Template.<name>.Template). Without attaching it,
                        // the commit fails "Failed to persist reference value ...DataCompositionSchemaImpl".
                        if (schema instanceof IBmObject)
                        {
                            tx.attachTopObject((IBmObject) schema, r.schemaFqn);
                        }
                        // Wire the TEMPLATE as the owner's main composition schema
                        // so the platform picks it up when the report opens (the
                        // EDT wizard does this - the vast majority of stock
                        // report objects carry <mainDataCompositionSchema>; without
                        // it the report
                        // opens with no schema). Report.setMainDataCompositionSchema
                        // takes a BasicTemplate (the macro/template), NOT the
                        // DataCompositionSchema - it references the template object.
                        // Only Report exposes the setter; other DCS-template owners
                        // are skipped by the reflection probe.
                        if (template != null)
                        {
                            trySetMainDataCompositionSchema(obj, template);
                        }
                        if (dryRun)
                        {
                            throw new DryRunAbort();
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
            r.message = "Schema created: " + r.schemaFqn; //$NON-NLS-1$
            if (!dryRun)
            {
                r.directSave = DcsExtensionExportHelper.exportSchemaToDisk(mm, project, r.schemaFqn);
                noteDiskSave(r);
                // Persist the owner .mdo too: trySetMainDataCompositionSchema mutated
                // the Report's mainDataCompositionSchema reference, but
                // exportSchemaToDisk only writes the .dcs. Without exporting the owner
                // the <mainDataCompositionSchema> line never reaches the .mdo and the
                // platform opens the report with no schema.
                BmExportHelper.Result ownerExport =
                    BmExportHelper.forceExportAndWait(mm, project, objectFqn);
                if (ownerExport != null && ownerExport.syncFlushPending)
                {
                    // Row 42: committed to BM, disk flush pending - the
                    // <mainDataCompositionSchema> line may briefly lag on disk.
                    // Surface as a tag (Result.tags reaches the JSON response),
                    // not just a log line.
                    r.tags.put("diskFlushPending", Boolean.TRUE); //$NON-NLS-1$
                    r.tags.put("diskFlushHint", "Schema written; owner .mdo " //$NON-NLS-1$ //$NON-NLS-2$
                        + "<mainDataCompositionSchema> disk flush pending - re-run resync_to_disk " //$NON-NLS-1$
                        + "once EDT settles if the report opens without the schema."); //$NON-NLS-1$
                    Activator.logWarning("create_schema: owner .mdo flush pending for " //$NON-NLS-1$
                        + objectFqn + " - <mainDataCompositionSchema> may briefly lag on disk"); //$NON-NLS-1$
                }
                else if (ownerExport != null && !ownerExport.isOk())
                {
                    Activator.logWarning("create_schema: owner .mdo export failed for " //$NON-NLS-1$
                        + objectFqn + " - <mainDataCompositionSchema> may not reach disk: " //$NON-NLS-1$
                        + ownerExport.error);
                }
            }
        }
        catch (DryRunAbort dra)
        {
            r.ok = true;
            r.message = "Dry run: schema would be created at " + r.schemaFqn; //$NON-NLS-1$
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
                r.error = cause.getMessage();
            }
        }
        return r;
    }

    /**
     * Picks the default DCS template name to match the EDT report wizard:
     * {@code ОсновнаяСхемаКомпоновкиДанных} for a Russian-script configuration,
     * {@code MainDataCompositionSchema} for an English one. Defaults to the
     * Russian name when the script variant cannot be read (most 1C configs are
     * Russian).
     *
     * @param config the configuration (may be null)
     * @return the wizard-style default template name
     */
    private static String defaultDcsTemplateName(Configuration config)
    {
        try
        {
            Object sv = config != null ? config.getScriptVariant() : null;
            if (sv != null && sv.toString().toUpperCase().contains("ENGLISH")) //$NON-NLS-1$
            {
                return DEFAULT_TEMPLATE_NAME; // "MainDataCompositionSchema"
            }
        }
        catch (Exception ignored)
        {
            // fall through to the Russian default
        }
        return "ОсновнаяСхемаКомпоновкиДанных"; //$NON-NLS-1$
    }

    /**
     * Resolves the DCS template name to operate on when the caller gave none.
     * The report wizard now names the macro per script variant
     * (ОсновнаяСхемаКомпоновкиДанных on Russian configs), so the hard-coded
     * English DEFAULT_TEMPLATE_NAME no longer matches. Resolution order:
     * <ol>
     *   <li>the owner's {@code mainDataCompositionSchema} template name;</li>
     *   <li>the owner's single {@code DataCompositionSchema} template;</li>
     *   <li>the script-variant default ({@link #defaultDcsTemplateName}).</li>
     * </ol>
     *
     * @param project the project
     * @param ownerFqn the metadata object FQN (e.g. {@code Report.Sales})
     * @return the resolved template name (never null/empty)
     */
    private static String resolveOwnerDcsTemplateName(IProject project, String ownerFqn)
    {
        Configuration config = null;
        try
        {
            IConfigurationProvider cp = Activator.getDefault().getConfigurationProvider();
            config = cp != null ? cp.getConfiguration(project) : null;
            if (config != null)
            {
                String[] parts = MetadataTypeCatalog.normalizeFqn(ownerFqn).split("\\.", 2); //$NON-NLS-1$
                if (parts.length == 2)
                {
                    MdObject owner = MetadataTypeCatalog.findObject(config, parts[0], parts[1]);
                    if (owner != null)
                    {
                        // 1. mainDataCompositionSchema reference -> its template name.
                        Object main = invokeNoArgQuiet(owner, "getMainDataCompositionSchema"); //$NON-NLS-1$
                        if (main != null)
                        {
                            Object nm = invokeNoArgQuiet(main, "getName"); //$NON-NLS-1$
                            if (nm instanceof String && !((String) nm).isEmpty())
                            {
                                return (String) nm;
                            }
                        }
                        // 2. the single DataCompositionSchema template, if unambiguous.
                        EList<MdObject> templates = invokeListGetter(owner, "getTemplates"); //$NON-NLS-1$
                        if (templates != null)
                        {
                            String only = null;
                            int dcsCount = 0;
                            for (MdObject t : templates)
                            {
                                Object tt = invokeNoArgQuiet(t, "getTemplateType"); //$NON-NLS-1$
                                if (tt != null && tt.toString().replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                                    .equalsIgnoreCase("DataCompositionSchema")) //$NON-NLS-1$
                                {
                                    dcsCount++;
                                    only = t.getName();
                                }
                            }
                            if (dcsCount == 1 && only != null && !only.isEmpty())
                            {
                                return only;
                            }
                        }
                    }
                }
            }
        }
        catch (Exception ignored)
        {
            // fall through to the script-variant default
        }
        return defaultDcsTemplateName(config);
    }

    /** Quiet no-arg reflective getter; returns {@code null} on any failure. */
    private static Object invokeNoArgQuiet(Object target, String method)
    {
        try
        {
            return target.getClass().getMethod(method).invoke(target);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Best-effort {@code owner.setMainDataCompositionSchema(template)} via
     * reflection. {@code Report.setMainDataCompositionSchema} takes a
     * {@code BasicTemplate} (the macro/template object), NOT the
     * {@code DataCompositionSchema} - the .mdo reference points at the template.
     * Only the {@code Report} metadata object exposes this feature; other
     * DCS-template owners have no such setter and are silently skipped. Without
     * this the report's .mdo lacks the {@code <mainDataCompositionSchema>}
     * reference and the platform opens the report with no schema.
     *
     * @param owner the metadata object owning the template (a Report)
     * @param template the BasicTemplate (DCS macro) just created
     */
    private static void trySetMainDataCompositionSchema(Object owner, Object template)
    {
        try
        {
            for (Method m : owner.getClass().getMethods())
            {
                if ("setMainDataCompositionSchema".equals(m.getName()) //$NON-NLS-1$
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(template))
                {
                    m.invoke(owner, template);
                    return;
                }
            }
            Activator.logWarning("setMainDataCompositionSchema(BasicTemplate) not applicable on " //$NON-NLS-1$
                + owner.getClass().getName());
        }
        catch (Exception e)
        {
            Activator.logWarning("setMainDataCompositionSchema skipped: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Builds the BM-canonical schema FQN.
     *
     * @param ownerFqn metadata object FQN (e.g. {@code "Report.Sales"}) or already-full
     *            schema FQN (e.g. {@code "Report.Sales.Template.MainDCS.Template"}).
     */
    public static String buildSchemaFqn(String ownerFqn, String templateName)
    {
        if (ownerFqn == null)
        {
            return null;
        }
        String tn = templateName != null && !templateName.isEmpty() ? templateName
            : DEFAULT_TEMPLATE_NAME;
        if (ownerFqn.endsWith(".Template")) //$NON-NLS-1$
        {
            return ownerFqn;
        }
        if (ownerFqn.contains(".Template.")) //$NON-NLS-1$
        {
            return ownerFqn.endsWith(".Template") ? ownerFqn : ownerFqn + ".Template"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ownerFqn + ".Template." + tn + ".Template"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @SuppressWarnings("unchecked")
    static EList<MdObject> invokeListGetter(Object target, String methodName)
    {
        try
        {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof EList)
            {
                return (EList<MdObject>) v;
            }
        }
        catch (Exception ignored)
        {
            // type does not expose this collection
        }
        return null;
    }

    /**
     * Reflection-based generic list getter. Returns the {@link EList} returned
     * by {@code target.<methodName>()} or {@code null} when the method does
     * not exist or returns something else.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static EList<EObject> getEObjectList(Object target, String methodName)
    {
        if (target == null)
        {
            return null;
        }
        try
        {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof EList)
            {
                return (EList) v;
            }
        }
        catch (Exception ignored)
        {
            // missing collection - caller decides
        }
        return null;
    }

    /**
     * Looks up an EObject child by case-insensitive {@code getName()} match in
     * the list returned by {@code container.<getterName>()}.
     */
    public static EObject findByNameInList(Object container, String getterName, String name)
    {
        EList<EObject> list = getEObjectList(container, getterName);
        if (list == null || name == null)
        {
            return null;
        }
        for (EObject child : list)
        {
            try
            {
                Method getName = child.getClass().getMethod("getName"); //$NON-NLS-1$
                Object n = getName.invoke(child);
                if (n != null && name.equalsIgnoreCase(n.toString()))
                {
                    return child;
                }
            }
            catch (Exception ignored)
            {
                // not a Named element - skip
            }
        }
        return null;
    }

    /**
     * Returns a singular factory method name for a DCS schema element class,
     * e.g. {@code "createDataSetQuery"}. Resolves it on the cached
     * {@link #getFactory()} instance through reflection.
     */
    public static Object createElement(String factoryMethodName)
    {
        // Schema-level elements live on the schema factory, settings-level on the
        // settings factory. Try schema first, then settings; a NoSuchMethod on the
        // first is expected, not an error.
        Object result = invokeFactoryMethod(getFactory(), factoryMethodName);
        if (result != null)
        {
            return result;
        }
        result = invokeFactoryMethod(getSettingsFactory(), factoryMethodName);
        if (result != null)
        {
            return result;
        }
        // 1.43.x DCS catch-up: core factory (DataCompositionField / Presentation /
        // appearance value-carriers) is the third element source.
        result = invokeFactoryMethod(getCoreFactory(), factoryMethodName);
        if (result == null)
        {
            Activator.logWarning("DcsFactory." + factoryMethodName //$NON-NLS-1$
                + " not found on schema, settings, or core factory"); //$NON-NLS-1$
        }
        return result;
    }

    private static Object invokeFactoryMethod(Object factory, String factoryMethodName)
    {
        if (factory == null)
        {
            return null;
        }
        try
        {
            Method m = factory.getClass().getMethod(factoryMethodName);
            return m.invoke(factory);
        }
        catch (NoSuchMethodException nsme)
        {
            return null; // try the other factory
        }
        catch (Exception e)
        {
            Activator.logWarning("DcsFactory." + factoryMethodName //$NON-NLS-1$
                + " invocation failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Calls {@code target.set<PropertyName>(value)} via reflection. Best-effort
     * coercion to common types (boolean, int, enum). Returns {@code null} on
     * success or a short error message describing what went wrong.
     */
    public static String setProperty(Object target, String propertyName, Object value)
    {
        if (target == null || propertyName == null || propertyName.isEmpty())
        {
            return "target and propertyName are required"; //$NON-NLS-1$
        }
        String setter = "set" + Character.toUpperCase(propertyName.charAt(0)) //$NON-NLS-1$
            + propertyName.substring(1);
        for (Method m : target.getClass().getMethods())
        {
            if (!setter.equals(m.getName()) || m.getParameterCount() != 1)
            {
                continue;
            }
            try
            {
                Object converted = coerceValue(value, m.getParameterTypes()[0]);
                m.invoke(target, converted);
                return null;
            }
            catch (Exception e)
            {
                return "Failed to set " + propertyName + ": " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return "Property '" + propertyName + "' is absent on " + target.getClass().getSimpleName(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Coerces a String / wrapper value to the EMF setter's expected type.
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
            // EMF enums expose two retrieval patterns: `get(String literal)` -
            // returns the enum for the display literal (e.g. "Equal"); and
            // `getByName(String name)` for the Java enum constant name. Both
            // are tried before falling back to a constant scan.
            try
            {
                Method get = targetType.getMethod("get", String.class); //$NON-NLS-1$
                Object val = get.invoke(null, s);
                if (val != null)
                {
                    return val;
                }
            }
            catch (Exception ignored)
            {
                // fall through
            }
            try
            {
                Method getByName = targetType.getMethod("getByName", String.class); //$NON-NLS-1$
                Object val = getByName.invoke(null, s);
                if (val != null)
                {
                    return val;
                }
            }
            catch (Exception ignored)
            {
                // fall through
            }
            // Iterate constants comparing toString() (literal) and name() case-
            // insensitively. Covers EDT EMF enums with non-Java-identifier
            // literals (e.g. "=" for Equal in some versions).
            Object[] constants = targetType.getEnumConstants();
            if (constants != null)
            {
                for (Object c : constants)
                {
                    if (c.toString().equalsIgnoreCase(s)
                        || ((Enum<?>) c).name().equalsIgnoreCase(s))
                    {
                        return c;
                    }
                }
            }
            throw new RuntimeException("Unknown enum value '" + s //$NON-NLS-1$
                + "' for type " + targetType.getSimpleName()); //$NON-NLS-1$
        }
        return value;
    }

    /**
     * Adds the "Основной" SettingsVariant (with an empty settings tree) to a freshly
     * created schema, mirroring the EDT wizard. The report's schema editor renders
     * settings from variants; a schema with no variant opens read-only / empty.
     * Best-effort: silently skips if the API is unavailable (settings ops then create
     * the variant lazily via ensureDefaultSettings).
     */
    private static void addDefaultSettingsVariant(Object schema)
    {
        try
        {
            EList<EObject> variants = getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
            if (variants == null || !variants.isEmpty())
            {
                return;
            }
            Object variant = createElement("createSettingsVariant"); //$NON-NLS-1$
            if (variant == null)
            {
                return;
            }
            setProperty(variant, "name", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$
            Object pres = createPresentation("Основной"); //$NON-NLS-1$
            if (pres != null)
            {
                setProperty(variant, "presentation", pres); //$NON-NLS-1$
            }
            Object settings = createElement("createDataCompositionSettings"); //$NON-NLS-1$
            if (settings != null)
            {
                setProperty(variant, "settings", settings); //$NON-NLS-1$
            }
            variants.add((EObject) variant);
        }
        catch (Throwable ignored)
        {
            // best-effort; settings ops will still create the variant lazily
        }
    }

    /**
     * Judges the disk-save and refuses a write that changed nothing.
     * <p>
     * Two outcomes are not a success. A save that FAILED is tagged {@code diskSaveFailed}. A save
     * that wrote a file byte-identical to the one already there is tagged {@code schemaUnchanged}
     * and refused: the call asked for a change and the schema does not carry one.
     * </p>
     *
     * @param r the result to annotate, whose {@code ok} this may clear
     */
    static void noteDiskSave(Result r)
    {
        DcsExtensionExportHelper.Result ds = r.directSave;
        if (ds != null && !ds.ok)
        {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("error", ds.error); //$NON-NLS-1$
            info.put(ErrorTags.NOT_FOUND.wire(), ds.notFound);
            if (ds.filePath != null)
            {
                info.put("filePath", ds.filePath); //$NON-NLS-1$
            }
            r.tags.put("diskSaveFailed", info); //$NON-NLS-1$
            return;
        }
        if (ds != null && ds.ok && ds.contentUnchanged)
        {
            // The serialization of the model matches the file byte for byte, so this call
            // changed nothing - either the value asked for was already there, or the write
            // to the model was lost. Which of the two it is cannot be told from here, and
            // reporting either one as a plain success is what makes a lost write invisible.
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("filePath", ds.filePath); //$NON-NLS-1$
            info.put("bytes", ds.bytesWritten); //$NON-NLS-1$
            r.tags.put("schemaUnchanged", info); //$NON-NLS-1$
            r.ok = false;
            r.error = "the schema on disk is byte-identical to what it held before this call, " //$NON-NLS-1$
                + "so nothing was changed. Either the value asked for was already set, or the " //$NON-NLS-1$
                + "write did not reach the model. Read the schema back before repeating it."; //$NON-NLS-1$
        }
    }

    /**
     * Whether the project is a configuration extension.
     * <p>
     * By project type, not by reading {@code Configuration.getConfigurationExtensionPurpose()}: that
     * value is an EMF enum and is never null, so the old reflective test answered "extension" for
     * every project whose configuration resolved - including plain configurations, and including
     * external-object projects, which answer with the configuration they were created against. The
     * consequence was a misleading auto-borrow attempt (and an {@code autoBorrowResolveFailed} tag)
     * on projects that have nothing to borrow into.
     * </p>
     *
     * @param project the project, may be <code>null</code>
     * @return <code>true</code> only for an extension project
     */
    public static boolean isExtensionProject(IProject project)
    {
        if (project == null || !project.isAccessible())
        {
            return false;
        }
        try
        {
            Activator activator = Activator.getDefault();
            if (activator == null)
            {
                return false;
            }
            IV8ProjectManager projectManager = activator.getV8ProjectManager();
            if (projectManager == null)
            {
                return false;
            }
            return projectManager.getProject(project) instanceof IExtensionProject;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    /**
     * Sentinel exception to abort a BM transaction cleanly for dryRun=true.
     */
    static final class DryRunAbort extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
    }
}
