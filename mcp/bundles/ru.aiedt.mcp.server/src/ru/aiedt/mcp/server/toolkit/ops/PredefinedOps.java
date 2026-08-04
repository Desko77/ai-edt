/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;

import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.ErrorTags;
import ru.aiedt.mcp.server.support.MetadataGuards;
import ru.aiedt.mcp.server.support.ProjectResolver;

/**
 * Predefined-data operations (add_predefined_item, add_predefined_account_subconto,
 * remove_predefined_account_subconto), extracted from {@link EditMetadataTool} as the fifth cluster
 * of the god-class split (Inc4). Predefined items are NOT MdObject (they extend
 * {@code PredefinedItem}: id + name + description), so they are built via
 * {@link BmObjectHelper#createMdClassEObject} and populated reflectively inside the BM write. The
 * cluster ships its own cluster-local reflection helpers ({@link #reflectNoArg},
 * {@link #findPredefinedItemByName}, {@link #coerceEnumLiteral}, {@link #normalizeAccountType},
 * {@link #applyChartOfAccountsPredefinedFields}) and the {@link #PREDEFINED_OWNERS} constant, since
 * they are used nowhere else. Shared setters ({@code findSingleArgSetter}, {@code invokeSetterClearly})
 * and the validation/format helpers ({@code requireNonEmpty}, {@code formatResult}) are called on
 * {@link EditMetadataTool} statically.
 */
final class PredefinedOps
{
    /** Invokes a no-arg getter reflectively, returning {@code null} on any failure. */
    private static Object reflectNoArg(Object target, String getterName)
    {
        try
        {
            return target.getClass().getMethod(getterName).invoke(target);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** Metadata types exposing a {@code getPredefined()} predefined-data container. */
    private static final Set<String> PREDEFINED_OWNERS = Set.of(
        "Catalog", "ChartOfCharacteristicTypes", "ChartOfAccounts", "ChartOfCalculationTypes"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * 1.43.x (audit A3): adds a predefined-data item to a Catalog /
     * ChartOfCharacteristicTypes / ChartOfAccounts / ChartOfCalculationTypes via
     * {@code getPredefined().getItems()}. The container is lazily created
     * ({@code setPredefined}) when absent. Predefined items extend
     * {@code PredefinedItem} (id + name + description), not MdObject, so they are
     * built with {@link BmObjectHelper#createMdClassEObject} and populated
     * reflectively. Refuses adopted (borrowed) owners - predefined items belong
     * on the native object, not its extension adoption. {@code code} is applied
     * only when the item's code feature is a String (ChartOfAccounts /
     * ChartOfCharacteristicTypes); Value-typed codes (Catalog /
     * ChartOfCalculationTypes) are surfaced as a warning and left unset.
     * Idempotent on the item name.
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opAddPredefinedItem(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String description = JsonUtils.extractStringArgument(params, "description"); //$NON-NLS-1$
        String code = JsonUtils.extractStringArgument(params, "code"); //$NON-NLS-1$
        boolean isFolder = JsonUtils.extractBooleanArgument(params, "isFolder", false); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        // 1.43.x RSV-5.2 Tier 2: ChartOfAccounts predefined-account fields.
        // accountType (Active/Passive/ActivePassive, RU aliases), offBalance,
        // order are applied only when the owner is a ChartOfAccounts; ignored
        // otherwise. effectively-final for capture in the BM-write lambda.
        final String accountType = JsonUtils.extractStringArgument(params, "accountType"); //$NON-NLS-1$
        final Boolean offBalance = JsonUtils.extractBooleanArgumentNullable(params, "offBalance"); //$NON-NLS-1$
        final String order = JsonUtils.extractStringArgument(params, "order"); //$NON-NLS-1$

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

        final String fDescription = description;
        final String fCode = code;
        final boolean fIsFolder = isFolder;
        final boolean[] idempotentSkip = { false };
        final boolean[] codeApplied = { false };
        final List<String> warn = new ArrayList<>();

        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                String ec = owner.eClass().getName();
                if (!PREDEFINED_OWNERS.contains(ec))
                {
                    throw new RuntimeException("Unsupported owner type '" + ec //$NON-NLS-1$
                        + "' has no predefined data (supported: Catalog, ChartOfCharacteristicTypes, " //$NON-NLS-1$
                        + "ChartOfAccounts, ChartOfCalculationTypes)."); //$NON-NLS-1$
                }
                Object belonging = reflectNoArg(owner, "getObjectBelonging"); //$NON-NLS-1$
                if (belonging != null
                    && belonging.toString().toLowerCase(java.util.Locale.ROOT).contains("adopt")) //$NON-NLS-1$
                {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("ownerFqn", ownerFqn); //$NON-NLS-1$
                    throw new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
                        "Cannot add a predefined item to an adopted (borrowed) object: " + ownerFqn, //$NON-NLS-1$
                        "Add the predefined item to the native object in the base configuration, " //$NON-NLS-1$
                            + "then re-adopt if needed.", //$NON-NLS-1$
                        new MetadataGuards.ErrorTag(ErrorTags.ADOPTED_OWNER.wire(), data)));
                }
                Object container = reflectNoArg(owner, "getPredefined"); //$NON-NLS-1$
                if (container == null)
                {
                    container = BmObjectHelper.createMdClassEObject(ec + "Predefined"); //$NON-NLS-1$
                    if (container == null)
                    {
                        throw new RuntimeException("Cannot create " + ec //$NON-NLS-1$
                            + "Predefined container on this EDT runtime."); //$NON-NLS-1$
                    }
                    java.lang.reflect.Method setPre =
                        EditMetadataTool.findSingleArgSetter(owner.getClass(), "setPredefined"); //$NON-NLS-1$
                    if (setPre == null)
                    {
                        throw new RuntimeException(ec + " has no setPredefined."); //$NON-NLS-1$
                    }
                    EditMetadataTool.invokeSetterClearly(setPre, owner, container, "predefined"); //$NON-NLS-1$
                }
                Object itemsObj = reflectNoArg(container, "getItems"); //$NON-NLS-1$
                if (!(itemsObj instanceof EList))
                {
                    throw new RuntimeException("Predefined container exposes no getItems() list."); //$NON-NLS-1$
                }
                @SuppressWarnings("unchecked")
                EList<Object> items = (EList<Object>) itemsObj;
                for (Object it : items)
                {
                    if (name.equals(reflectNoArg(it, "getName"))) //$NON-NLS-1$
                    {
                        idempotentSkip[0] = true;
                        return name;
                    }
                }
                Object item = BmObjectHelper.createMdClassEObject(ec + "PredefinedItem"); //$NON-NLS-1$
                if (item == null)
                {
                    throw new RuntimeException("Cannot create " + ec //$NON-NLS-1$
                        + "PredefinedItem on this EDT runtime."); //$NON-NLS-1$
                }
                java.lang.reflect.Method setId = EditMetadataTool.findSingleArgSetter(item.getClass(), "setId"); //$NON-NLS-1$
                if (setId == null)
                {
                    // The predefined id (UUID) is mandatory - an item without one
                    // fails to load in the infobase. Treat a missing setter as a
                    // hard error, symmetrically with setName below.
                    throw new RuntimeException(ec + "PredefinedItem has no setId - cannot assign " //$NON-NLS-1$
                        + "the required predefined id (the item would fail to load)."); //$NON-NLS-1$
                }
                EditMetadataTool.invokeSetterClearly(setId, item, java.util.UUID.randomUUID(), "id"); //$NON-NLS-1$
                java.lang.reflect.Method setName = EditMetadataTool.findSingleArgSetter(item.getClass(), "setName"); //$NON-NLS-1$
                if (setName == null)
                {
                    throw new RuntimeException(ec + "PredefinedItem has no setName."); //$NON-NLS-1$
                }
                EditMetadataTool.invokeSetterClearly(setName, item, name, "name"); //$NON-NLS-1$
                // Auto-generate a presentation (the EDT "synonym" for a predefined item) from the
                // name when the caller passed none - the same split-words rule the EDT wizard uses
                // for metadata objects. An explicit description is honored as-is (even when it
                // equals the name); a generated one is skipped when it adds nothing over the name.
                boolean hasExplicitDescription = fDescription != null && !fDescription.isEmpty();
                String effectiveDescription = hasExplicitDescription
                    ? fDescription : EditMetadataTool.generateSynonymFromName(name);
                if (effectiveDescription != null && !effectiveDescription.isEmpty()
                    && (hasExplicitDescription || !effectiveDescription.equals(name)))
                {
                    java.lang.reflect.Method sd = EditMetadataTool.findSingleArgSetter(item.getClass(), "setDescription"); //$NON-NLS-1$
                    if (sd != null && sd.getParameterTypes()[0] == String.class)
                    {
                        EditMetadataTool.invokeSetterClearly(sd, item, effectiveDescription, "description"); //$NON-NLS-1$
                    }
                }
                if (fCode != null && !fCode.isEmpty())
                {
                    java.lang.reflect.Method sc = EditMetadataTool.findSingleArgSetter(item.getClass(), "setCode"); //$NON-NLS-1$
                    if (sc != null && sc.getParameterTypes()[0] == String.class)
                    {
                        EditMetadataTool.invokeSetterClearly(sc, item, fCode, "code"); //$NON-NLS-1$
                        codeApplied[0] = true;
                    }
                    else
                    {
                        warn.add("code not set: this object's predefined code is a Value type " //$NON-NLS-1$
                            + "(Catalog / ChartOfCalculationTypes); set it manually for now."); //$NON-NLS-1$
                    }
                }
                if (fIsFolder)
                {
                    java.lang.reflect.Method sf = EditMetadataTool.findSingleArgSetter(item.getClass(), "setIsFolder"); //$NON-NLS-1$
                    if (sf != null)
                    {
                        EditMetadataTool.invokeSetterClearly(sf, item, Boolean.TRUE, "isFolder"); //$NON-NLS-1$
                    }
                    else
                    {
                        warn.add("isFolder ignored: this object's predefined items are not hierarchical."); //$NON-NLS-1$
                    }
                }
                if ("ChartOfAccounts".equals(ec)) //$NON-NLS-1$
                {
                    applyChartOfAccountsPredefinedFields(item, accountType, offBalance, order, warn);
                }
                items.add(item);
                return name;
            });

        if (idempotentSkip[0])
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("name", name); //$NON-NLS-1$
            r.tags.put("idempotentSkip", idem); //$NON-NLS-1$
        }
        if (r.ok && fCode != null && !fCode.isEmpty())
        {
            r.tags.put("codeApplied", codeApplied[0]); //$NON-NLS-1$
        }
        if (r.ok && !warn.isEmpty())
        {
            r.tags.put("warnings", warn); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, "add_predefined_item"); //$NON-NLS-1$
    }

    /**
     * 1.43.x RSV-5.2 Tier 2: applies the ChartOfAccounts predefined-account
     * fields (accountType / offBalance / order) on a freshly-created
     * ChartOfAccountsPredefinedItem. Best-effort - a missing setter or an
     * unresolvable enum literal is recorded in {@code warn}, not fatal.
     */
    private static void applyChartOfAccountsPredefinedFields(Object item, String accountType,
        Boolean offBalance, String order, List<String> warn)
    {
        if (accountType != null && !accountType.isEmpty())
        {
            java.lang.reflect.Method s = EditMetadataTool.findSingleArgSetter(item.getClass(), "setAccountType"); //$NON-NLS-1$
            Object val = s == null ? null
                : coerceEnumLiteral(s.getParameterTypes()[0], normalizeAccountType(accountType));
            if (s != null && val != null)
            {
                EditMetadataTool.invokeSetterClearly(s, item, val, "accountType"); //$NON-NLS-1$
            }
            else
            {
                warn.add("accountType '" + accountType //$NON-NLS-1$
                    + "' not applied (use Active / Passive / ActivePassive)"); //$NON-NLS-1$
            }
        }
        if (offBalance != null)
        {
            java.lang.reflect.Method s = EditMetadataTool.findSingleArgSetter(item.getClass(), "setOffBalance"); //$NON-NLS-1$
            if (s != null)
            {
                EditMetadataTool.invokeSetterClearly(s, item, offBalance, "offBalance"); //$NON-NLS-1$
            }
            else
            {
                warn.add("offBalance not applied (no setter)"); //$NON-NLS-1$
            }
        }
        if (order != null && !order.isEmpty())
        {
            java.lang.reflect.Method s = EditMetadataTool.findSingleArgSetter(item.getClass(), "setOrder"); //$NON-NLS-1$
            if (s != null && s.getParameterTypes()[0] == String.class)
            {
                EditMetadataTool.invokeSetterClearly(s, item, order, "order"); //$NON-NLS-1$
            }
            else
            {
                warn.add("order not applied (no String setter)"); //$NON-NLS-1$
            }
        }
    }

    /** Maps RU / underscore account-type aliases to the platform literal. */
    private static String normalizeAccountType(String v)
    {
        switch (v.trim().toLowerCase(java.util.Locale.ROOT))
        {
            case "active": //$NON-NLS-1$
            case "активный": //$NON-NLS-1$
                return "Active"; //$NON-NLS-1$
            case "passive": //$NON-NLS-1$
            case "пассивный": //$NON-NLS-1$
                return "Passive"; //$NON-NLS-1$
            case "activepassive": //$NON-NLS-1$
            case "active_passive": //$NON-NLS-1$
            case "активнопассивный": //$NON-NLS-1$
            case "активныйпассивный": //$NON-NLS-1$
                return "ActivePassive"; //$NON-NLS-1$
            default:
                return v;
        }
    }

    /**
     * Reflective enum coercion: EMF {@code get(literal)} first, then a
     * name / toString scan. Returns {@code null} when unresolvable.
     */
    private static Object coerceEnumLiteral(Class<?> enumType, String value)
    {
        if (enumType == null || !enumType.isEnum() || value == null)
        {
            return null;
        }
        try
        {
            Object r = enumType.getMethod("get", String.class).invoke(null, value); //$NON-NLS-1$
            if (r != null)
            {
                return r;
            }
        }
        catch (Exception ignored)
        {
            // fall through to constant scan
        }
        for (Object c : enumType.getEnumConstants())
        {
            if (value.equalsIgnoreCase(c.toString()) || value.equalsIgnoreCase(((Enum<?>) c).name()))
            {
                return c;
            }
        }
        return null;
    }

    /**
     * Finds a predefined item by name in an owner's predefined-data container
     * ({@code getPredefined().getItems()}). Returns null when absent. Used to
     * locate a predefined account and a characteristic-type item.
     */
    private static Object findPredefinedItemByName(Object owner, String name)
    {
        Object container = reflectNoArg(owner, "getPredefined"); //$NON-NLS-1$
        if (container == null)
        {
            return null;
        }
        Object items = reflectNoArg(container, "getItems"); //$NON-NLS-1$
        if (!(items instanceof EList))
        {
            return null;
        }
        for (Object it : (EList<?>) items)
        {
            if (name.equals(reflectNoArg(it, "getName"))) //$NON-NLS-1$
            {
                return it;
            }
        }
        return null;
    }

    /**
     * 1.43.x RSV-5.2 Tier 2: adds a subconto row (ВидыСубконто) to a predefined
     * account of a ChartOfAccounts. The subconto value is a predefined item of
     * the ChartOfCharacteristicTypes linked via the ChartOfAccounts'
     * {@code extDimensionTypes} reference (set it first with
     * set_object_reference). Idempotent on the characteristic-type name.
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opAddPredefinedAccountSubconto(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String accountName = JsonUtils.extractStringArgument(params, "accountName"); //$NON-NLS-1$
        String characteristicType = JsonUtils.extractStringArgument(params, "characteristicType"); //$NON-NLS-1$
        Boolean turnover = JsonUtils.extractBooleanArgumentNullable(params, "turnover"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(accountName, "accountName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(characteristicType, "characteristicType"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        final boolean[] idempotentSkip = { false };
        final Boolean fTurnover = turnover;
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                if (!"ChartOfAccounts".equals(owner.eClass().getName())) //$NON-NLS-1$
                {
                    throw new RuntimeException("add_predefined_account_subconto applies to a " //$NON-NLS-1$
                        + "ChartOfAccounts, not " + owner.eClass().getName()); //$NON-NLS-1$
                }
                Object account = findPredefinedItemByName(owner, accountName);
                if (account == null)
                {
                    throw new RuntimeException("predefined account '" + accountName //$NON-NLS-1$
                        + "' not found in " + ownerFqn + " (add it first via add_predefined_item)"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                Object cct = reflectNoArg(owner, "getExtDimensionTypes"); //$NON-NLS-1$
                if (cct == null)
                {
                    throw new RuntimeException(ownerFqn + " has no linked ChartOfCharacteristicTypes " //$NON-NLS-1$
                        + "(set it via set_object_reference property=extDimensionTypes)"); //$NON-NLS-1$
                }
                Object cctItem = findPredefinedItemByName(cct, characteristicType);
                if (cctItem == null)
                {
                    throw new RuntimeException("characteristic type '" + characteristicType //$NON-NLS-1$
                        + "' not found among the predefined items of the linked " //$NON-NLS-1$
                        + "ChartOfCharacteristicTypes"); //$NON-NLS-1$
                }
                Object subListObj = reflectNoArg(account, "getExtDimensionTypes"); //$NON-NLS-1$
                if (!(subListObj instanceof EList))
                {
                    throw new RuntimeException("predefined account exposes no extDimensionTypes list."); //$NON-NLS-1$
                }
                @SuppressWarnings("unchecked")
                EList<Object> subList = (EList<Object>) subListObj;
                for (Object ed : subList)
                {
                    Object ct = reflectNoArg(ed, "getCharacteristicType"); //$NON-NLS-1$
                    if (ct != null && characteristicType.equals(reflectNoArg(ct, "getName"))) //$NON-NLS-1$
                    {
                        idempotentSkip[0] = true;
                        return accountName + "." + characteristicType; //$NON-NLS-1$
                    }
                }
                Object ed = BmObjectHelper.createMdClassEObject("ExtDimensionType"); //$NON-NLS-1$
                if (ed == null)
                {
                    throw new RuntimeException("Cannot create ExtDimensionType on this EDT runtime."); //$NON-NLS-1$
                }
                java.lang.reflect.Method setCt = EditMetadataTool.findSingleArgSetter(ed.getClass(), "setCharacteristicType"); //$NON-NLS-1$
                if (setCt == null)
                {
                    throw new RuntimeException("ExtDimensionType has no setCharacteristicType."); //$NON-NLS-1$
                }
                EditMetadataTool.invokeSetterClearly(setCt, ed, cctItem, "characteristicType"); //$NON-NLS-1$
                if (fTurnover != null)
                {
                    java.lang.reflect.Method setT = EditMetadataTool.findSingleArgSetter(ed.getClass(), "setTurnover"); //$NON-NLS-1$
                    if (setT != null)
                    {
                        EditMetadataTool.invokeSetterClearly(setT, ed, fTurnover, "turnover"); //$NON-NLS-1$
                    }
                }
                subList.add(ed);
                return accountName + "." + characteristicType; //$NON-NLS-1$
            });
        if (idempotentSkip[0] && r.tags != null)
        {
            Map<String, Object> idem = new LinkedHashMap<>();
            idem.put("accountName", accountName); //$NON-NLS-1$
            idem.put("characteristicType", characteristicType); //$NON-NLS-1$
            r.tags.put("idempotentSkip", idem); //$NON-NLS-1$
        }
        return EditMetadataTool.formatResult(r, "add_predefined_account_subconto"); //$NON-NLS-1$
    }

    /**
     * 1.43.x RSV-5.2 Tier 2: removes a subconto row from a predefined account by
     * the characteristic-type name. Idempotent (a missing row is reported, not
     * an error).
     *
     * @param params the tool parameters
     * @return the JSON result document
     */
    String opRemovePredefinedAccountSubconto(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String ownerFqn = JsonUtils.extractStringArgument(params, "ownerFqn"); //$NON-NLS-1$
        String accountName = JsonUtils.extractStringArgument(params, "accountName"); //$NON-NLS-1$
        String characteristicType = JsonUtils.extractStringArgument(params, "characteristicType"); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$

        String err = EditMetadataTool.requireNonEmpty(projectName, "projectName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(ownerFqn, "ownerFqn") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(accountName, "accountName") //$NON-NLS-1$
            + EditMetadataTool.requireNonEmpty(characteristicType, "characteristicType"); //$NON-NLS-1$
        if (!err.isEmpty())
        {
            return ToolResult.error(err.trim()).toJson();
        }
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
        {
            return ToolResult.error(ProjectResolver.describeNotFound(projectName)).toJson();
        }
        final boolean[] removed = { false };
        BmObjectHelper.Result r = BmObjectHelper.executeWriteOnObject(project, ownerFqn, dryRun,
            (tx, owner) -> {
                if (!"ChartOfAccounts".equals(owner.eClass().getName())) //$NON-NLS-1$
                {
                    throw new RuntimeException("remove_predefined_account_subconto applies to a " //$NON-NLS-1$
                        + "ChartOfAccounts, not " + owner.eClass().getName()); //$NON-NLS-1$
                }
                Object account = findPredefinedItemByName(owner, accountName);
                if (account == null)
                {
                    throw new RuntimeException("predefined account '" + accountName //$NON-NLS-1$
                        + "' not found in " + ownerFqn); //$NON-NLS-1$
                }
                Object subListObj = reflectNoArg(account, "getExtDimensionTypes"); //$NON-NLS-1$
                if (subListObj instanceof EList)
                {
                    EList<?> subList = (EList<?>) subListObj;
                    for (int i = 0; i < subList.size(); i++)
                    {
                        Object ct = reflectNoArg(subList.get(i), "getCharacteristicType"); //$NON-NLS-1$
                        if (ct != null && characteristicType.equals(reflectNoArg(ct, "getName"))) //$NON-NLS-1$
                        {
                            subList.remove(i);
                            removed[0] = true;
                            break;
                        }
                    }
                }
                return accountName + "." + characteristicType; //$NON-NLS-1$
            });
        if (r.ok && !removed[0] && r.tags != null)
        {
            r.tags.put("idempotentSkip", "no subconto '" + characteristicType //$NON-NLS-1$ //$NON-NLS-2$
                + "' on account '" + accountName + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return EditMetadataTool.formatResult(r, "remove_predefined_account_subconto"); //$NON-NLS-1$
    }
}
