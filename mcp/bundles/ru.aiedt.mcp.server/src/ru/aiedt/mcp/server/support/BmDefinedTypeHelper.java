/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * Helper for {@code DefinedType} (ОпределяемыйТип) operations: setting the
 * type composition (which metadata refs / primitive types are part of the
 * defined type) in one call. <p>
 *
 * <p>{@code setDefinedTypeTypes} accepts an array of FQNs like
 * {@code ["CatalogRef.Users", "CatalogRef.ExternalUsers"]} and rebuilds the
 * type composition atomically. After the call, the defined type can be
 * referenced from attribute types via {@code "DefinedType.X"}.
 *
 * <p>Implementation strategy (1.40.1):
 * <ol>
 *   <li>Validate every FQN against the known type shapes
 *       ({@code CatalogRef.X}, {@code DocumentRef.Y}, primitives).</li>
 *   <li>Build a new {@code TypeItem} for each FQN: prefer a fresh instance
 *       from the platform factory (probe), fall back to {@code EcoreUtil.copy}
 *       of an existing produced type pulled from the configuration.</li>
 *   <li>Idempotency: when the requested set already matches the current
 *       composition (compared by {@code McoreUtil.getTypeName}), return
 *       {@code idempotentSkip} and do not mutate.</li>
 *   <li>Otherwise clear the {@code TypeDescription.types} list and add the
 *       new TypeItems. The BM transaction commit handles persistence.</li>
 * </ol>
 *
 * <p>If a particular FQN cannot be resolved into a TypeItem (no produced type
 * is available - typical for primitives on older EDT builds), the result
 * carries a {@code partialMutation} tag and {@code unresolved} list so the
 * caller can surface the warning while the resolved subset is still applied.
 */
public final class BmDefinedTypeHelper
{
    private static final String[] FACTORY_CANDIDATES = {
        "com._1c.g5.v8.dt.mcore.McoreFactory", //$NON-NLS-1$
        "com._1c.g5.v8.dt.mcore.MCoreFactory" //$NON-NLS-1$
    };

    /** Cached probes (avoid Class.forName in hot loops). */
    private static volatile Class<?> cachedTypeItemClass;
    private static volatile Class<?> cachedMcoreUtilClass;
    private static volatile boolean classProbeDone;

    private BmDefinedTypeHelper()
    {
        // utility
    }

    private static void ensureClassProbeDone()
    {
        if (classProbeDone)
        {
            return;
        }
        synchronized (BmDefinedTypeHelper.class)
        {
            if (classProbeDone)
            {
                return;
            }
            cachedTypeItemClass = forNameOrNull("com._1c.g5.v8.dt.mcore.TypeItem"); //$NON-NLS-1$
            cachedMcoreUtilClass = forNameOrNull("com._1c.g5.v8.dt.mcore.util.McoreUtil"); //$NON-NLS-1$
            classProbeDone = true;
        }
    }

    /**
     * Result of a setTypes operation.
     */
    public static final class TypesResult
    {
        public boolean ok;
        public boolean mutated;
        public boolean idempotentSkip;
        public final List<String> resolved = new ArrayList<>();
        public final List<String> unresolved = new ArrayList<>();
        public String error;
    }

    /**
     * Backwards-compatible overload without project or qualifier options.
     * Delegates to the project-aware overload with {@code null} so primitive
     * types fall back to the {@code unresolved:/<typeName>} proxy URI scheme.
     * Persists OK but the validator cannot resolve to a real platform type -
     * prefer the project-aware overload from {@code add_object_attribute}.
     */
    public static TypesResult setTypes(MdObject definedType, Configuration config,
        List<String> typeFqns)
    {
        return setTypes(definedType, (IProject) null, config, typeFqns, null);
    }

    /**
     * Backwards-compatible IDtProject overload (1.42.5 transitional). Use the
     * IProject overload for new code: EDT's {@code IRuntimeVersionSupport}
     * exposes {@code getRuntimeVersion(IProject)}, not the IDtProject form,
     * and converting IProject -> IDtProject is one method call away.
     */
    public static TypesResult setTypes(MdObject definedType, IDtProject dtProject,
        Configuration config, List<String> typeFqns,
        QualifierOptions qualifierOptions)
    {
        IProject project = null;
        if (dtProject != null)
        {
            try
            {
                Method getProject = dtProject.getClass().getMethod("getWorkspaceProject"); //$NON-NLS-1$
                Object p = getProject.invoke(dtProject);
                if (p instanceof IProject)
                {
                    project = (IProject) p;
                }
            }
            catch (Exception ignored)
            {
                // try fallback name
            }
            if (project == null)
            {
                try
                {
                    Method getProject = dtProject.getClass().getMethod("getProject"); //$NON-NLS-1$
                    Object p = getProject.invoke(dtProject);
                    if (p instanceof IProject)
                    {
                        project = (IProject) p;
                    }
                }
                catch (Exception ignored)
                {
                    // give up
                }
            }
        }
        return setTypes(definedType, project, config, typeFqns, qualifierOptions);
    }

    /**
     * 1.42.5 BUG-1424-A canonical path: when {@code project} is provided, the
     * helper resolves primitive types ({@code String} / {@code Number} / ...)
     * through {@code IEObjectProvider.Registry.INSTANCE.get(...).getProxy(name)}
     * which is the same proxy scheme EDT uses internally. Without {@code project}
     * we fall back to the {@code unresolved:/<typeName>} URI - it persists OK
     * but the validator can't resolve to a real platform type. Use the
     * project-aware overload for {@code add_object_attribute} so the on-disk
     * type validates without "Неизвестный тип" markers.
     *
     * <p>{@code qualifierOptions} (optional) carries length / precision /
     * fractionDigits / dateFractions / nonNegative / multiLine. Not consumed
     * here directly - {@link #applyPrimitiveQualifiers} reads it after the
     * types list is populated. Pass {@code null} for default qualifiers.
     */
    public static TypesResult setTypes(MdObject definedType, IProject project,
        Configuration config, List<String> typeFqns,
        QualifierOptions qualifierOptions)
    {
        TypesResult r = new TypesResult();
        if (definedType == null || typeFqns == null)
        {
            r.error = "definedType and typeFqns are required";
            return r;
        }
        Object typeDesc = readTypeDescription(definedType);
        if (typeDesc == null)
        {
            // Brand-new attributes default the type feature to null.
            // Fabricate an empty TypeDescription via McoreFactory and
            // attach it through setType/setTypeDescription so we can
            // populate getTypes() below. setDefinedTypeTypes (the original
            // caller) does not hit this path since DefinedType objects
            // are constructed with a non-null TypeDescription.
            typeDesc = ensureTypeDescription(definedType);
            if (typeDesc == null)
            {
                r.error = "Cannot resolve or create TypeDescription on " //$NON-NLS-1$
                    + definedType.eClass().getName()
                    + " (no getType/getTypes/getTypeDescription on the object, " //$NON-NLS-1$
                    + "and McoreFactory.createTypeDescription is unavailable)."; //$NON-NLS-1$
                return r;
            }
        }
        return setTypesOnDescription(typeDesc, project, config, typeFqns,
            qualifierOptions, definedType);
    }

    /**
     * 1.43.x A6: a String attribute of fixed length over 1024 can fail SDBL
     * database restructuring on {@code update_database}. Returns a warning string
     * for {@code type=String} with {@code length>1024}, or {@code null} otherwise
     * ({@code length=0} = unlimited/variable is the safe default and never warns).
     * Callers surface it on the typeApplication tag so the agent sees it before
     * the IB restructure fails.
     */
    public static String stringLengthRestructureWarning(String type, QualifierOptions q)
    {
        if (type == null || q == null || q.length == null)
        {
            return null;
        }
        if (!"String".equalsIgnoreCase(type.trim())) //$NON-NLS-1$
        {
            return null;
        }
        if (q.length > 1024)
        {
            return "String length=" + q.length + " exceeds 1024 - a fixed-length String " //$NON-NLS-1$ //$NON-NLS-2$
                + "over 1024 can fail SDBL restructuring on update_database. Use length=0 " //$NON-NLS-1$
                + "(unlimited / variable length) or length<=1024."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Description-level core of {@link #setTypes}: validates the requested
     * FQNs, builds fresh TypeItems, replaces the TypeDescription's types
     * list, deep-resolves the proxies against {@code contextObject}'s
     * resource, and attaches primitive qualifiers. Split out so non-MdObject
     * carriers of a TypeDescription - notably form attributes
     * ({@code FormAttribute.valueType}) - apply a type through the exact same
     * machinery object attributes use. See {@link #setFormAttributeTypes}.
     *
     * @param typeDesc        the TypeDescription EObject to mutate (non-null)
     * @param project         owning project for canonical primitive proxy
     *                        resolution (nullable - falls back to URI hack)
     * @param config          configuration for ref-type resolution
     * @param typeFqns        requested type FQNs
     * @param qualifierOptions optional qualifier customisation (null = defaults)
     * @param contextObject   EObject owning the TypeDescription, used as the
     *                        {@code IBmModelManager.resolve} context
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static TypesResult setTypesOnDescription(Object typeDesc, IProject project,
        Configuration config, List<String> typeFqns,
        QualifierOptions qualifierOptions, EObject contextObject)
    {
        TypesResult r = new TypesResult();
        if (typeDesc == null || typeFqns == null)
        {
            r.error = "typeDesc and typeFqns are required";
            return r;
        }
        EList<?> typesList = readTypesList(typeDesc);
        if (typesList == null)
        {
            r.error = "TypeDescription does not expose getTypes()";
            return r;
        }

        // 1. Validate FQNs. 1.43.x: callers pass the `type` argument as a single
        // list element; a COMPOSITE type arrives comma-joined ("CatalogRef.X,EnumRef.Y").
        // Expand on commas here so every caller (object / TS / form attributes,
        // columns, register fields) gets a proper multi-<types> composite instead of
        // one malformed <types> element with a comma ("Неизвестный тип").
        List<String> expandedFqns = new ArrayList<>();
        for (String raw : typeFqns)
        {
            if (raw == null)
            {
                continue;
            }
            for (String part : raw.split(",")) //$NON-NLS-1$
            {
                String s = part.trim();
                if (!s.isEmpty())
                {
                    // Normalize a Russian primitive name (Строка / Число / ...) to its
                    // English canonical form so it resolves like the English spelling;
                    // non-primitive tokens pass through unchanged.
                    expandedFqns.add(normalizePrimitiveFqn(s));
                }
            }
        }
        List<String> requested = new ArrayList<>();
        for (String fqn : expandedFqns)
        {
            if (isKnownTypeShape(fqn, config))
            {
                requested.add(fqn);
            }
            else
            {
                r.unresolved.add(fqn);
            }
        }
        if (!r.unresolved.isEmpty())
        {
            r.error = "Some types could not be resolved: " + String.join(", ", r.unresolved);
            return r;
        }

        // 2. Idempotency check
        Set<String> currentSet = readCurrentTypeNames(typesList);
        Set<String> requestedSet = new HashSet<>(requested);
        if (currentSet.equals(requestedSet))
        {
            r.ok = true;
            r.idempotentSkip = true;
            r.resolved.addAll(requested);
            return r;
        }

        // 3. Build new TypeItem objects (fresh, no containment)
        List<Object> newItems = new ArrayList<>();
        List<String> partial = new ArrayList<>();
        for (String fqn : requested)
        {
            Object typeItem = createTypeItem(fqn, project, config);
            if (typeItem != null)
            {
                newItems.add(typeItem);
                r.resolved.add(fqn);
            }
            else
            {
                partial.add(fqn);
            }
        }
        if (newItems.isEmpty())
        {
            r.error = "Could not resolve any TypeItem for the requested FQNs: "
                + String.join(", ", partial);
            r.unresolved.addAll(partial);
            return r;
        }

        // 4. Mutate: clear + add
        try
        {
            typesList.clear();
            for (Object item : newItems)
            {
                ((EList) typesList).add(item);
            }
            // 1.43 BUG-1424-A deep resolution: after the proxy is attached
            // to the TypeDescription's getTypes() list, call
            // IBmModelManager.resolve(proxy, contextResource, contextObject)
            // to materialise it into the real platform Type instance.
            // Without this the in-session BM keeps the proxy in unresolved
            // state and the validator flags "Неизвестный тип" markers
            // until clean_project / EDT restart triggers a full reload.
            //
            // 1.43.x BUG-2: skip this for REFERENCE types. A primitive proxy
            // carries a fake "unresolved:/" URI, so resolve() is a no-op and the
            // proxy survives to be persisted. A reference proxy (from
            // IEObjectProvider.getProxy) carries a REAL resolvable URI, so
            // resolve() materialises it into a concrete platform Type whose
            // non-containment cross-references have no proxy URI - which BM then
            // refuses to persist ("Failed to persist reference value TypeImpl@...").
            // Left as a proxy, the reference TypeItem persists via its URI and
            // serialises to <types>CatalogRef.X</types> correctly.
            boolean hasReference = false;
            for (String fqn : requested)
            {
                if (fqn.indexOf('.') >= 0)
                {
                    hasReference = true;
                    break;
                }
            }
            if (!hasReference)
            {
                resolveTypeItemsInPlace(typesList, contextObject);
            }
            // 1.42.5 BUG-1424-A: EDT validation flags primitive types as
            // "Неизвестный тип" when the matching empty qualifier element
            // (<stringQualifiers/> / <numberQualifiers/> / <dateQualifiers/>
            // / <binaryDataQualifiers/>) is missing on the TypeDescription.
            // Existing well-formed .mdo files always carry the qualifier even
            // when the qualifier itself is empty (default length / precision).
            // We attach an empty qualifier object built via McoreFactory for
            // every primitive in `requested` that needs one. Boolean / UUID
            // and ref-types do not carry qualifiers in EDT.
            applyPrimitiveQualifiers(typeDesc, requested, qualifierOptions);
            r.ok = true;
            r.mutated = true;
            r.unresolved.addAll(partial);
            return r;
        }
        catch (Exception e)
        {
            Activator.logWarning("setTypes mutation failed: " + e.getMessage()); //$NON-NLS-1$
            r.error = "Mutation failed: " + e.getMessage();
            return r;
        }
    }

    /**
     * Applies a type composition to a form attribute (or form attribute
     * column) by populating its {@code valueType} TypeDescription, reusing
     * the object-attribute machinery in {@link #setTypesOnDescription} so the
     * type resolves at validation and IB-load time and carries the same
     * primitive qualifiers EDT writes itself. An empty
     * {@code <stringQualifiers/>} matches EDT's own output for an unlimited
     * String form attribute - the IB-load failure
     * "Несоответствие свойства XDTO: Type" is about an empty / unresolved
     * {@code <types>} element, which this resolves.
     * <p>
     * Before this, {@code add_form_attribute} produced a typeless attribute.
     *
     * @param formAttribute the FormAttribute / column EObject (must expose a
     *                      {@code valueType} TypeDescription feature)
     * @param project       owning project (canonical primitive proxy)
     * @param config        configuration (ref-type resolution)
     * @param typeFqns      requested type FQNs (e.g. {@code ["String"]})
     * @param qualifierOptions optional qualifier customisation (null = defaults)
     */
    public static TypesResult setFormAttributeTypes(Object formAttribute, IProject project,
        Configuration config, List<String> typeFqns, QualifierOptions qualifierOptions)
    {
        TypesResult r = new TypesResult();
        if (!(formAttribute instanceof EObject))
        {
            r.error = "formAttribute is not an EObject";
            return r;
        }
        if (typeFqns == null || typeFqns.isEmpty())
        {
            r.error = "typeFqns is required";
            return r;
        }
        EObject attr = (EObject) formAttribute;
        org.eclipse.emf.ecore.EStructuralFeature vtFeature = findTypeDescriptionFeature(attr);
        if (vtFeature == null)
        {
            r.error = "FormAttribute exposes no TypeDescription (valueType) feature " //$NON-NLS-1$
                + "on this EDT runtime"; //$NON-NLS-1$
            return r;
        }
        Object typeDesc = attr.eGet(vtFeature);
        if (typeDesc == null)
        {
            typeDesc = createEmptyTypeDescription();
            if (typeDesc == null)
            {
                r.error = "Cannot create TypeDescription " //$NON-NLS-1$
                    + "(McoreFactory.createTypeDescription unavailable)"; //$NON-NLS-1$
                return r;
            }
            try
            {
                attr.eSet(vtFeature, typeDesc);
            }
            catch (Exception e)
            {
                r.error = "Failed to attach valueType TypeDescription: " + e.getMessage(); //$NON-NLS-1$
                return r;
            }
        }
        return setTypesOnDescription(typeDesc, project, config,
            normalizeFormCollectionFqns(typeFqns), qualifierOptions, attr);
    }

    /**
     * Populates an {@code EventSubscription}'s {@code source} TypeDescription
     * with the given source value-types (e.g. {@code DocumentObject.X},
     * {@code InformationRegisterRecordSet.Y}, {@code ConstantValueManager.Z},
     * {@code DefinedType.W}). Subscription sources use {@code *Object} /
     * {@code *RecordSet} / {@code *Manager} / {@code ConstantValueManager}
     * value-types whose produced-type feature names are <b>inconsistent</b>
     * across register kinds (InformationRegister exposes {@code recordType},
     * Sequence {@code recordSetType}, Constant {@code valueManagerType}), and
     * the attribute-tuned {@link #isKnownTypeShape} rejects {@code *RecordSet}
     * outright. This method therefore resolves each source FQN by its
     * <b>serialized produced-type name</b> (content-based) rather than by
     * feature-name heuristic. It is deliberately isolated from the object /
     * form-attribute type path: it changes none of {@code isKnownTypeShape},
     * {@code stripTypeKindSuffix}, {@code pickProducedTypeForKind} or
     * {@code createTypeItem}, so the 20+ attribute callers are unaffected.
     *
     * <p>Replace semantics: the requested list becomes the whole source. When
     * a valid-shape FQN cannot be resolved (object missing / wrong kind) the
     * resolved subset is still applied and the FQN is reported in
     * {@link TypesResult#unresolved} (mirrors {@code setDefinedTypeTypes}).
     *
     * @param eventSubscription the EventSubscription EObject (exposes get/setSource)
     * @param project           owning project (kept for symmetry; source ref-types
     *                          resolve via produced types, not the primitive proxy)
     * @param config            configuration for produced-type resolution
     * @param sourceFqns        requested source value-type FQNs (comma tokens split)
     * @return TypesResult with ok / mutated / idempotentSkip / resolved / unresolved
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static TypesResult setEventSubscriptionSource(EObject eventSubscription,
        IProject project, Configuration config, List<String> sourceFqns)
    {
        TypesResult r = new TypesResult();
        if (eventSubscription == null)
        {
            r.error = "eventSubscription is null"; //$NON-NLS-1$
            return r;
        }
        if (sourceFqns == null || sourceFqns.isEmpty())
        {
            r.error = "sourceFqns is required"; //$NON-NLS-1$
            return r;
        }
        org.eclipse.emf.ecore.EStructuralFeature srcFeature =
            eventSubscription.eClass().getEStructuralFeature("source"); //$NON-NLS-1$
        if (srcFeature == null)
        {
            r.error = "EventSubscription exposes no 'source' feature on this EDT runtime"; //$NON-NLS-1$
            return r;
        }
        Object typeDesc = eventSubscription.eGet(srcFeature);
        if (typeDesc == null)
        {
            typeDesc = createEmptyTypeDescription();
            if (typeDesc == null)
            {
                r.error = "Cannot create source TypeDescription " //$NON-NLS-1$
                    + "(McoreFactory.createTypeDescription unavailable)"; //$NON-NLS-1$
                return r;
            }
            try
            {
                eventSubscription.eSet(srcFeature, typeDesc);
            }
            catch (Exception e)
            {
                r.error = "Failed to attach source TypeDescription: " + e.getMessage(); //$NON-NLS-1$
                return r;
            }
        }
        EList<?> typesList = readTypesList(typeDesc);
        if (typesList == null)
        {
            r.error = "source TypeDescription does not expose getTypes()"; //$NON-NLS-1$
            return r;
        }

        // 1. Split comma tokens + shape-gate (the authoritative check is the
        // content resolution below - this only rejects clearly-malformed FQNs).
        List<String> requested = new ArrayList<>();
        for (String raw : sourceFqns)
        {
            if (raw == null)
            {
                continue;
            }
            for (String part : raw.split(",")) //$NON-NLS-1$
            {
                String s = part.trim();
                if (s.isEmpty())
                {
                    continue;
                }
                if (isKnownSourceShape(s))
                {
                    requested.add(s);
                }
                else
                {
                    r.unresolved.add(s);
                }
            }
        }
        if (!r.unresolved.isEmpty())
        {
            r.error = "Not a valid EventSubscription source type: " //$NON-NLS-1$
                + String.join(", ", r.unresolved) //$NON-NLS-1$
                + " (expected e.g. DocumentObject.X, CatalogObject.Y, " //$NON-NLS-1$
                + "InformationRegisterRecordSet.Z, ConstantValueManager.W, DefinedType.V)"; //$NON-NLS-1$
            return r;
        }

        // 2. Idempotency
        Set<String> currentSet = readCurrentTypeNames(typesList);
        Set<String> requestedSet = new HashSet<>(requested);
        if (currentSet.equals(requestedSet))
        {
            r.ok = true;
            r.idempotentSkip = true;
            r.resolved.addAll(requested);
            return r;
        }

        // 3. Resolve each to a produced-type proxy (content-based, name-matched)
        List<Object> newItems = new ArrayList<>();
        List<String> partial = new ArrayList<>();
        for (String fqn : requested)
        {
            Object item = createSourceTypeItem(fqn, config);
            if (item != null)
            {
                newItems.add(item);
                r.resolved.add(fqn);
            }
            else
            {
                partial.add(fqn);
            }
        }
        if (newItems.isEmpty())
        {
            r.error = "Could not resolve any source type (object not found or wrong kind): " //$NON-NLS-1$
                + String.join(", ", partial); //$NON-NLS-1$
            r.unresolved.addAll(partial);
            return r;
        }

        // 4. Mutate: clear + add
        try
        {
            typesList.clear();
            for (Object item : newItems)
            {
                ((EList) typesList).add(item);
            }
            r.ok = true;
            r.mutated = true;
            r.unresolved.addAll(partial);
            return r;
        }
        catch (Exception e)
        {
            Activator.logWarning("setEventSubscriptionSource mutation failed: " + e.getMessage()); //$NON-NLS-1$
            r.error = "source mutation failed: " + e.getMessage(); //$NON-NLS-1$
            return r;
        }
    }

    /**
     * Maps an EventSubscription source kind ({@code DocumentObject},
     * {@code InformationRegisterRecordSet}, {@code ConstantValueManager}, ...)
     * to its owning metadata type ({@code Document}, {@code InformationRegister},
     * {@code Constant}). Longest-first suffix strip so {@code ValueManager} /
     * {@code RecordManager} beat the shorter {@code Manager}, and {@code RecordSet}
     * is recognized (the shared {@link #stripTypeKindSuffix} knows neither).
     * Returns {@code null} for an unrecognized kind.
     *
     * <p>The accepted suffixes are exactly the value-type kinds the platform
     * offers as a subscription source: {@code *Object}, {@code *RecordSet},
     * {@code *RecordManager}, {@code *Manager} and {@code *ValueManager} (plus
     * {@code DefinedType}). Reference / selection / list kinds ({@code *Ref},
     * {@code *Selection}, {@code *List}) are deliberately NOT accepted - a bare
     * {@code CatalogRef.X} content-resolves against the object's ref produced
     * type and would be written into {@code <source>} silently, yet it is not a
     * valid EventSubscription source and the platform rejects it. Excluding the
     * shape here fails such a request with a clear "not a valid source" error.
     */
    private static String sourceMetadataTypePrefix(String kind)
    {
        if (kind == null || kind.isEmpty())
        {
            return null;
        }
        if ("DefinedType".equals(kind)) //$NON-NLS-1$
        {
            return "DefinedType"; //$NON-NLS-1$
        }
        // ValueManager / RecordManager MUST precede the shorter Manager so
        // ConstantValueManager -> Constant (not ConstantValue) and
        // InformationRegisterRecordManager -> InformationRegister.
        for (String suffix : new String[] { "ValueManager", "RecordManager", //$NON-NLS-1$ //$NON-NLS-2$
            "RecordSet", "Manager", "Object" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            if (kind.endsWith(suffix))
            {
                String prefix = kind.substring(0, kind.length() - suffix.length());
                if (!prefix.isEmpty())
                {
                    return prefix;
                }
            }
        }
        return null;
    }

    /**
     * Shape gate for a subscription source FQN: two dotted parts whose kind
     * maps to a known metadata type. Lenient by design - the authoritative
     * check is {@link #createSourceTypeItem} matching a real produced-type name.
     */
    private static boolean isKnownSourceShape(String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return false;
        }
        int dot = fqn.indexOf('.');
        if (dot <= 0 || dot >= fqn.length() - 1)
        {
            return false;
        }
        return sourceMetadataTypePrefix(fqn.substring(0, dot)) != null;
    }

    /**
     * Resolves a subscription source FQN to a persistable produced-type proxy.
     * Primary path is {@link #createProducedTypeProxyByName} (matches the
     * produced TypeItem by its serialized name - correct for every
     * {@code *Object} / {@code *Manager} / {@code *RecordSet} /
     * {@code *ValueManager} kind regardless of feature naming). Falls back to
     * the shared {@link #createFromProducedTypes} for {@code DefinedType} (which
     * has no {@code getProducedTypes}) and any kind the name match misses.
     */
    private static Object createSourceTypeItem(String fqn, Configuration config)
    {
        if (config == null || fqn == null || fqn.isEmpty())
        {
            return null;
        }
        int dot = fqn.indexOf('.');
        if (dot <= 0 || dot >= fqn.length() - 1)
        {
            return null;
        }
        String kind = fqn.substring(0, dot);
        String name = fqn.substring(dot + 1);
        String prefix = sourceMetadataTypePrefix(kind);
        if (prefix != null && !"DefinedType".equals(prefix)) //$NON-NLS-1$
        {
            MdObject target = MetadataTypeCatalog.findObject(config, prefix, name);
            if (target != null)
            {
                Object byName = createProducedTypeProxyByName(target, fqn);
                if (byName != null)
                {
                    return byName;
                }
            }
        }
        // DefinedType (and any residual) via the shared resolver, which has an
        // explicit DefinedType branch in stripTypeKindSuffix.
        return createFromProducedTypes(fqn, config);
    }

    /**
     * Iterates a metadata object's produced types and returns a persistable
     * PROXY to the produced TypeItem whose serialized name equals {@code fqn}
     * (e.g. {@code InformationRegisterRecordSet.X}). Content-based, so it is
     * robust to the inconsistent produced-type feature names across register
     * kinds. The name is read off the REAL produced TypeItem (resolved), then a
     * proxy to its URI is built - the same by-reference persistence trick
     * {@link #createFromProducedTypes} uses (a detached copy carries
     * non-containment cross-refs with no URI and BM rejects it at commit).
     * Returns {@code null} when no produced type serializes to that name.
     */
    private static Object createProducedTypeProxyByName(MdObject target, String fqn)
    {
        if (target == null || fqn == null)
        {
            return null;
        }
        Object producedTypes = invokeNoArg(target, "getProducedTypes"); //$NON-NLS-1$
        if (!(producedTypes instanceof EObject))
        {
            return null;
        }
        for (EObject entry : ((EObject) producedTypes).eContents())
        {
            Object typeItem = invokeNoArg(entry, "getType"); //$NON-NLS-1$
            if (typeItem == null)
            {
                typeItem = invokeNoArg(entry, "getTypeSet"); //$NON-NLS-1$
            }
            if (!(typeItem instanceof EObject))
            {
                continue;
            }
            if (fqn.equals(readTypeName(typeItem)))
            {
                EObject src = (EObject) typeItem;
                org.eclipse.emf.common.util.URI proxyUri = EcoreUtil.getURI(src);
                if (proxyUri == null)
                {
                    return EcoreUtil.copy(src);
                }
                EObject proxy = EcoreUtil.create(src.eClass());
                ((org.eclipse.emf.ecore.InternalEObject) proxy).eSetProxyURI(proxyUri);
                return proxy;
            }
        }
        return null;
    }

    /**
     * Finds the structural feature carrying the {@code TypeDescription} on an
     * arbitrary EObject. Prefers a feature literally named {@code valueType}
     * (form attributes / columns), falling back to the first feature whose
     * EType is named {@code TypeDescription}. Returns {@code null} when none
     * exists.
     */
    private static org.eclipse.emf.ecore.EStructuralFeature findTypeDescriptionFeature(EObject obj)
    {
        org.eclipse.emf.ecore.EStructuralFeature byType = null;
        for (org.eclipse.emf.ecore.EStructuralFeature f : obj.eClass().getEAllStructuralFeatures())
        {
            if ("valueType".equals(f.getName())) //$NON-NLS-1$
            {
                return f;
            }
            org.eclipse.emf.ecore.EClassifier etype = f.getEType();
            if (byType == null && etype != null && "TypeDescription".equals(etype.getName())) //$NON-NLS-1$
            {
                byType = f;
            }
        }
        return byType;
    }

    /**
     * Builds a fresh, detached {@code TypeDescription} via the platform
     * factory ({@code McoreFactory.eINSTANCE.createTypeDescription()}).
     * Returns {@code null} when the factory chain is unreachable on this
     * EDT runtime.
     */
    private static Object createEmptyTypeDescription()
    {
        for (String factoryClassName : FACTORY_CANDIDATES)
        {
            try
            {
                Class<?> clazz = Class.forName(factoryClassName);
                Field eInstance = clazz.getField("eINSTANCE"); //$NON-NLS-1$
                Object factory = eInstance.get(null);
                Method createTd = factory.getClass().getMethod("createTypeDescription"); //$NON-NLS-1$
                Object freshTd = createTd.invoke(factory);
                if (freshTd != null)
                {
                    return freshTd;
                }
            }
            catch (ClassNotFoundException ignored)
            {
                // try next factory
            }
            catch (Exception e)
            {
                Activator.logWarning("createEmptyTypeDescription " + factoryClassName //$NON-NLS-1$
                    + " failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Builds a {@code com._1c.g5.v8.dt.mcore.QName} via the McoreFactory
     * ({@code McoreFactory.eINSTANCE.createQName()}) and populates its namespace
     * URI and local name. XDTO-typed metadata (web-service operation return
     * value and operation parameters) reference their value type by QName, not
     * by a {@code TypeDescription}, so this is the construction path for
     * {@code Operation.setXdtoReturningValueType} / {@code Parameter.setXdtoValueType}.
     *
     * @param nsUri namespace URI (e.g. the XSD namespace or an XDTO package URI);
     *            may be {@code null}
     * @param localName local type name (e.g. {@code string}); may be {@code null}
     * @return the QName EObject, or {@code null} when the factory chain is
     *         unreachable on this EDT runtime
     */
    public static Object createQName(String nsUri, String localName)
    {
        for (String factoryClassName : FACTORY_CANDIDATES)
        {
            try
            {
                Class<?> clazz = Class.forName(factoryClassName);
                Field eInstance = clazz.getField("eINSTANCE"); //$NON-NLS-1$
                Object factory = eInstance.get(null);
                Method createQName = factory.getClass().getMethod("createQName"); //$NON-NLS-1$
                Object qn = createQName.invoke(factory);
                if (qn != null)
                {
                    if (nsUri != null)
                    {
                        qn.getClass().getMethod("setNsUri", String.class).invoke(qn, nsUri); //$NON-NLS-1$
                    }
                    if (localName != null)
                    {
                        qn.getClass().getMethod("setName", String.class).invoke(qn, localName); //$NON-NLS-1$
                    }
                    return qn;
                }
            }
            catch (ClassNotFoundException ignored)
            {
                // try next factory candidate
            }
            catch (Exception e)
            {
                Activator.logWarning("createQName " + factoryClassName //$NON-NLS-1$
                    + " failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Reads the canonical type names out of a {@code TypeDescription}.
     * <p>
     * Exposed for callers that already hold a TypeDescription from somewhere other than a metadata
     * object - the query result schema gets one straight from
     * {@code QuerySchemaExpression.getType(...)}. The reflection probes that turn a
     * {@code TypeItem} into a name live here and are worth reusing rather than repeating.
     * </p>
     *
     * @param typeDescription the description to read, may be <code>null</code>
     * @return the names, empty when there is nothing resolvable
     */
    public static Set<String> readTypeDescriptionNames(Object typeDescription)
    {
        if (typeDescription == null)
        {
            return new HashSet<>();
        }
        EList<?> typesList = readTypesList(typeDescription);
        if (typesList == null)
        {
            return new HashSet<>();
        }
        return readCurrentTypeNames(typesList);
    }

    /**
     * Reads the type-name set currently on a form attribute's {@code valueType}
     * TypeDescription. Returns an empty set when the attribute has no
     * resolvable type. Used by {@code add_form_attribute} to tell whether an
     * existing same-named attribute already has the requested type (idempotent)
     * or a different one (the caller should remove + re-add to change it).
     */
    public static Set<String> readValueTypeNames(Object formAttribute)
    {
        if (!(formAttribute instanceof EObject))
        {
            return new HashSet<>();
        }
        EObject attr = (EObject) formAttribute;
        org.eclipse.emf.ecore.EStructuralFeature vtFeature = findTypeDescriptionFeature(attr);
        if (vtFeature == null)
        {
            return new HashSet<>();
        }
        Object typeDesc = attr.eGet(vtFeature);
        if (typeDesc == null)
        {
            return new HashSet<>();
        }
        EList<?> typesList = readTypesList(typeDesc);
        if (typesList == null)
        {
            return new HashSet<>();
        }
        return readCurrentTypeNames(typesList);
    }

    /**
     * 1.42.5 BUG-1424-A: builds and attaches the empty qualifier object on
     * a TypeDescription for each primitive in {@code requested}. Without
     * the qualifier, {@code md-legacy-check-type-description-types} flags
     * the type as unknown even though the primitive is correctly written
     * to {@code <types>}. Mirrors the EDT 2026.1 default-value initialisation
     * that happens when a TypeDescription is built through the editor UI.
     *
     * <p>Mapping:
     * <ul>
     *   <li>{@code String} -> {@code setStringQualifiers(createStringQualifiers())}</li>
     *   <li>{@code Number} -> {@code setNumberQualifiers(createNumberQualifiers())}</li>
     *   <li>{@code Date} -> {@code setDateQualifiers(createDateQualifiers())}</li>
     *   <li>{@code BinaryData} -> {@code setBinaryDataQualifiers(createBinaryDataQualifiers())}</li>
     *   <li>{@code Boolean} / {@code UUID} / ref-types -> no qualifier</li>
     * </ul>
     *
     * <p>All resolution is reflective so the helper survives factory class
     * renames between EDT versions. Failures degrade silently with a log
     * warning - the typesList content is already correct, the qualifier is
     * just a presentational hint EDT validation reads back.
     */
    private static void applyPrimitiveQualifiers(Object typeDesc, List<String> requested,
        QualifierOptions options)
    {
        if (typeDesc == null || requested == null || requested.isEmpty())
        {
            return;
        }
        for (String fqn : requested)
        {
            String[] mapping = qualifierMapping(fqn);
            if (mapping == null)
            {
                continue;
            }
            // 1.42.5 fix: when a primitive Type is added through the canonical
            // proxy, EDT lazily creates an empty qualifier on the
            // TypeDescription (BasicFeature default-init). If we just create
            // a fresh qualifier and try to attach it, our populated values
            // are lost because the setter sees an existing qualifier and
            // either silently ignores the call or overwrites with the empty
            // copy. We now read the existing qualifier and mutate it in
            // place; only when none exists do we create + attach.
            String getterName = "get" + mapping[1].substring(3); //$NON-NLS-1$
            Object qualifier = invokeNoArg(typeDesc, getterName);
            if (qualifier == null)
            {
                Object created = createQualifier(mapping[0]);
                if (created == null)
                {
                    continue;
                }
                attachQualifier(typeDesc, mapping[1], created);
                // Re-read the qualifier actually attached to the TypeDescription.
                // EDT's canonical proxy can lazily materialise its OWN empty
                // qualifier between the null-check above and attachQualifier, in
                // which case attachQualifier's idempotency guard skips our
                // `created` and the live qualifier is the proxy's. Populating
                // that live object (not the orphaned `created`) is what makes
                // values written via a setter - notably the Date `dateFractions`
                // enum - actually persist. (String/Number masked this bug because
                // their qualifier is non-null on the first getter call, so they
                // were mutated in place.)
                Object attached = invokeNoArg(typeDesc, getterName);
                qualifier = attached != null ? attached : created;
            }
            // 1.42.5: set sane defaults that match what EDT itself writes for
            // a freshly-created attribute via the editor wizard. Empty
            // qualifiers (precision=0 / dateFractions=null) trigger
            // "Тип <X> не установлен" markers because EDT considers them
            // semantically incomplete. User-provided values from
            // QualifierOptions take precedence over the defaults. Populating the
            // LIVE attached qualifier (see above) guarantees the values persist.
            populateQualifierDefaults(qualifier, fqn, options);
        }
    }

    /**
     * Populates a freshly-created qualifier object with sane defaults +
     * caller-supplied {@link QualifierOptions}. All access reflective so
     * setters can rename between EDT releases without breaking the helper.
     */
    private static void populateQualifierDefaults(Object qualifier, String fqn,
        QualifierOptions options)
    {
        if (qualifier == null || fqn == null)
        {
            return;
        }
        switch (fqn)
        {
            case "String": //$NON-NLS-1$
            {
                int length = (options != null && options.length != null) ? options.length : 0;
                if (length > 0)
                {
                    invokeIntSetter(qualifier, "setLength", length); //$NON-NLS-1$
                }
                // 1.42.5 fix: StringQualifiers exposes setFixed(boolean), not
                // an AllowedLength enum. AllowedLength.Variable -> false,
                // AllowedLength.Fixed -> true. The metadata.common
                // AllowedLength enum exists but is not consumed by the EMF
                // qualifier shape.
                if (options != null && options.allowedLength != null
                    && !options.allowedLength.isEmpty())
                {
                    boolean fixed = "Fixed".equalsIgnoreCase(options.allowedLength); //$NON-NLS-1$
                    invokeBooleanSetter(qualifier, "setFixed", fixed); //$NON-NLS-1$
                }
                break;
            }
            case "Number": //$NON-NLS-1$
            {
                int precision = (options != null && options.precision != null)
                    ? options.precision : 10;
                int fractionDigits = (options != null && options.fractionDigits != null)
                    ? options.fractionDigits : 0;
                invokeIntSetter(qualifier, "setPrecision", precision); //$NON-NLS-1$
                invokeIntSetter(qualifier, "setScale", fractionDigits); //$NON-NLS-1$
                // 1.42.5 fix: NumberQualifiers exposes setNonNegative(boolean),
                // not setAllowedSign with an AllowedSign enum (the enum does
                // not exist in EDT 2026.1 mcore).
                if (options != null && Boolean.TRUE.equals(options.nonNegative))
                {
                    invokeBooleanSetter(qualifier, "setNonNegative", true); //$NON-NLS-1$
                }
                break;
            }
            case "Date": //$NON-NLS-1$
            {
                String dateFractions = (options != null && options.dateFractions != null
                    && !options.dateFractions.isEmpty()) ? options.dateFractions : "Date"; //$NON-NLS-1$
                invokeEnumSetter(qualifier, "setDateFractions", //$NON-NLS-1$
                    "com._1c.g5.v8.dt.mcore.DateFractions", //$NON-NLS-1$
                    dateFractions);
                break;
            }
            case "BinaryData": //$NON-NLS-1$
            {
                int length = (options != null && options.length != null) ? options.length : 0;
                if (length > 0)
                {
                    invokeIntSetter(qualifier, "setLength", length); //$NON-NLS-1$
                }
                break;
            }
            default:
                // no qualifier needed for Boolean / UUID / ref-types
                break;
        }
    }

    private static void invokeIntSetter(Object target, String methodName, int value)
    {
        if (target == null)
        {
            return;
        }
        try
        {
            Method m = target.getClass().getMethod(methodName, int.class);
            m.invoke(target, value);
        }
        catch (NoSuchMethodException ignored)
        {
            // setter not available on this EDT version
        }
        catch (Exception e)
        {
            Activator.logWarning("invokeIntSetter " + methodName + " failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
        }
    }

    private static void invokeBooleanSetter(Object target, String methodName, boolean value)
    {
        if (target == null)
        {
            return;
        }
        try
        {
            Method m = target.getClass().getMethod(methodName, boolean.class);
            m.invoke(target, value);
        }
        catch (NoSuchMethodException ignored)
        {
            // setter not available on this EDT version
        }
        catch (Exception e)
        {
            Activator.logWarning("invokeBooleanSetter " + methodName + " failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
        }
    }

    private static void invokeEnumSetter(Object target, String methodName,
        String enumClassName, String literalName)
    {
        if (target == null || literalName == null || literalName.isEmpty())
        {
            return;
        }
        try
        {
            Class<?> enumClass = Class.forName(enumClassName);
            // EMF enums typically expose get(String) / getByName(String)
            Object literal = null;
            for (String resolver : new String[] { "get", "getByName" }) //$NON-NLS-1$ //$NON-NLS-2$
            {
                try
                {
                    Method m = enumClass.getMethod(resolver, String.class);
                    Object v = m.invoke(null, literalName);
                    if (v != null)
                    {
                        literal = v;
                        break;
                    }
                }
                catch (NoSuchMethodException ignored)
                {
                    // try next resolver
                }
            }
            if (literal == null)
            {
                // Fall back to Java enum lookup by name (SCREAMING_SNAKE)
                @SuppressWarnings({ "unchecked", "rawtypes" })
                Object viaJavaEnum = Enum.valueOf((Class<Enum>) enumClass,
                    literalName.toUpperCase());
                literal = viaJavaEnum;
            }
            if (literal == null)
            {
                Activator.logWarning("invokeEnumSetter could not resolve literal '" //$NON-NLS-1$
                    + literalName + "' on " + enumClassName); //$NON-NLS-1$
                return;
            }
            Method setter = null;
            for (Method candidate : target.getClass().getMethods())
            {
                if (candidate.getName().equals(methodName)
                    && candidate.getParameterTypes().length == 1
                    && candidate.getParameterTypes()[0].isInstance(literal))
                {
                    setter = candidate;
                    break;
                }
            }
            if (setter == null)
            {
                Activator.logWarning("invokeEnumSetter " + methodName //$NON-NLS-1$
                    + " not present on " + target.getClass().getSimpleName()); //$NON-NLS-1$
                return;
            }
            setter.invoke(target, literal);
        }
        catch (ClassNotFoundException cnf)
        {
            Activator.logWarning("invokeEnumSetter: enum class missing - " //$NON-NLS-1$
                + enumClassName);
        }
        catch (Exception e)
        {
            Activator.logWarning("invokeEnumSetter " + methodName + " failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
        }
    }

    /**
     * Optional qualifier customization for {@code add_object_attribute}:
     * length / precision / fractionDigits / dateFractions / nonNegative.
     * Pass {@code null} to use sensible defaults (Number precision=10,
     * Date dateFractions=Date, String length=0 = unlimited).
     */
    public static final class QualifierOptions
    {
        public Integer length;            // String / BinaryData
        public Integer precision;         // Number total digit count
        public Integer fractionDigits;    // Number digits after the decimal point
        public Boolean nonNegative;       // Number sign restriction
        public String dateFractions;      // Date / DateTime / Time
        public String allowedLength;      // Variable / Fixed (String)
    }

    /**
     * Returns {@code [factoryMethodName, setterName]} for primitive types
     * that need a qualifier element, or {@code null} for types without one.
     */
    private static String[] qualifierMapping(String fqn)
    {
        if (fqn == null || fqn.isEmpty() || fqn.indexOf('.') >= 0)
        {
            // ref-types contain a dot (CatalogRef.X / DefinedType.X)
            return null;
        }
        switch (fqn)
        {
            case "String": //$NON-NLS-1$
                return new String[] { "createStringQualifiers", "setStringQualifiers" }; //$NON-NLS-1$ //$NON-NLS-2$
            case "Number": //$NON-NLS-1$
                return new String[] { "createNumberQualifiers", "setNumberQualifiers" }; //$NON-NLS-1$ //$NON-NLS-2$
            case "Date": //$NON-NLS-1$
                return new String[] { "createDateQualifiers", "setDateQualifiers" }; //$NON-NLS-1$ //$NON-NLS-2$
            case "BinaryData": //$NON-NLS-1$
                return new String[] { "createBinaryDataQualifiers", "setBinaryDataQualifiers" }; //$NON-NLS-1$ //$NON-NLS-2$
            default:
                return null;
        }
    }

    private static Object createQualifier(String factoryMethodName)
    {
        for (String factoryClassName : FACTORY_CANDIDATES)
        {
            try
            {
                Class<?> clazz = Class.forName(factoryClassName);
                Field eInstance = clazz.getField("eINSTANCE"); //$NON-NLS-1$
                Object factory = eInstance.get(null);
                Method m = factory.getClass().getMethod(factoryMethodName);
                Object q = m.invoke(factory);
                if (q != null)
                {
                    return q;
                }
            }
            catch (ClassNotFoundException ignored)
            {
                // try next factory
            }
            catch (NoSuchMethodException ignored)
            {
                // factory does not expose this qualifier on this EDT version
            }
            catch (Exception e)
            {
                Activator.logWarning("createQualifier " + factoryMethodName //$NON-NLS-1$
                    + " failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return null;
    }

    private static void attachQualifier(Object typeDesc, String setterName, Object qualifier)
    {
        if (typeDesc == null || qualifier == null)
        {
            return;
        }
        // Idempotency: if an existing qualifier is already attached and the
        // setter requires the qualifier interface, skip. Reading the existing
        // qualifier through the matching getter avoids overwriting properties
        // a previous call may have set (length / precision / etc.).
        String getterName = "get" + setterName.substring(3); //$NON-NLS-1$
        Object existing = invokeNoArg(typeDesc, getterName);
        if (existing != null)
        {
            return;
        }
        // Find the setter by name + 1-arg signature (qualifier interface type
        // is stable across EDT versions but its class object lives in a
        // different bundle, so look up by name).
        for (Method m : typeDesc.getClass().getMethods())
        {
            if (!m.getName().equals(setterName))
            {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 1)
            {
                continue;
            }
            if (!params[0].isInstance(qualifier))
            {
                continue;
            }
            try
            {
                m.invoke(typeDesc, qualifier);
                return;
            }
            catch (Exception e)
            {
                Activator.logWarning("attachQualifier " + setterName + " failed: " //$NON-NLS-1$ //$NON-NLS-2$
                    + e.getMessage());
                return;
            }
        }
    }

    /**
     * Outcome of {@link #compareTypeNames}: whether a target object's
     * existing type composition matches the requested set without mutating
     * the model.
     */
    public enum TypeComparison
    {
        /** Same set of type names - safe to skip the mutation. */
        MATCH,
        /** Different type names - caller should report propertyMismatch. */
        MISMATCH,
        /** Target has no resolvable TypeDescription - cannot compare. */
        NOT_RESOLVED
    }

    /**
     * Compares the requested type FQNs against the target object's current
     * TypeDescription without mutating it. Used by
     * {@code add_object_attribute} to decide between {@code idempotentSkip}
     * and {@code propertyMismatch} when an attribute with the same name
     * already exists.
     *
     * @param target       MdObject exposing a TypeDescription via
     *                     {@code getType()/getTypes()/getTypeDescription()}
     * @param requestedFqns single-element or multi-element type FQN list
     * @return MATCH when the existing names equal the requested set;
     *     MISMATCH otherwise; NOT_RESOLVED when the TypeDescription is
     *     missing or empty (treat as MISMATCH-leaning at the call site)
     */
    public static TypeComparison compareTypeNames(MdObject target, List<String> requestedFqns)
    {
        if (target == null || requestedFqns == null)
        {
            return TypeComparison.NOT_RESOLVED;
        }
        Object typeDesc = readTypeDescription(target);
        if (typeDesc == null)
        {
            return TypeComparison.NOT_RESOLVED;
        }
        EList<?> typesList = readTypesList(typeDesc);
        if (typesList == null)
        {
            return TypeComparison.NOT_RESOLVED;
        }
        Set<String> existing = readCurrentTypeNames(typesList);
        Set<String> requested = new HashSet<>();
        for (String fqn : requestedFqns)
        {
            if (fqn != null && !fqn.isEmpty())
            {
                requested.add(fqn);
            }
        }
        if (existing.isEmpty() && requested.isEmpty())
        {
            return TypeComparison.NOT_RESOLVED;
        }
        return existing.equals(requested) ? TypeComparison.MATCH : TypeComparison.MISMATCH;
    }

    /**
     * Reads the existing type FQN-set on the target. Convenience wrapper
     * for callers that need to surface the existing set in a
     * {@code propertyMismatch} payload.
     */
    public static Set<String> readExistingTypeNames(MdObject target)
    {
        if (target == null)
        {
            return new HashSet<>();
        }
        Object typeDesc = readTypeDescription(target);
        if (typeDesc == null)
        {
            return new HashSet<>();
        }
        EList<?> typesList = readTypesList(typeDesc);
        if (typesList == null)
        {
            return new HashSet<>();
        }
        return readCurrentTypeNames(typesList);
    }

    /**
     * Sets an attribute's fill value (the platform "Default value" /
     * "Значение заполнения"). The mcore {@code Value} subtype is chosen from the
     * attribute's own primitive type: Boolean -&gt; BooleanValue, Number -&gt;
     * NumberValue, String -&gt; StringValue. An empty raw value (or "Undefined" /
     * "Неопределено") -&gt; UndefinedValue (the platform default). Date and
     * reference types are intentionally not handled (a date default is usually a
     * StandardBeginningDate; a reference default needs empty-ref / predefined-item
     * resolution) and return a clear message. Reliable on an EXISTING, fully
     * resolved attribute (set_object_property); not wired into creation because a
     * freshly created in-session type may not resolve its name yet.
     *
     * @return {@code null} on success, or an error message (mirrors
     *         {@link ru.aiedt.mcp.server.support.BmObjectHelper#setProperty}).
     */
    public static String applyFillValue(EObject target, String raw)
    {
        if (target == null)
        {
            return "fillValue: target is null"; //$NON-NLS-1$
        }
        Method setter = null;
        for (Method m : target.getClass().getMethods())
        {
            if ("setFillValue".equals(m.getName()) && m.getParameterCount() == 1) //$NON-NLS-1$
            {
                setter = m;
                break;
            }
        }
        if (setter == null)
        {
            return "fillValue is not supported on " + target.eClass().getName() //$NON-NLS-1$
                + " (only attributes and register dimensions/resources carry a fill value)"; //$NON-NLS-1$
        }
        String v = raw == null ? "" : raw.trim(); //$NON-NLS-1$
        Object value;
        if (v.isEmpty() || "Undefined".equalsIgnoreCase(v) || "Неопределено".equalsIgnoreCase(v)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            value = createMcoreValue("createUndefinedValue", null, null); //$NON-NLS-1$
        }
        else
        {
            String kind = (target instanceof MdObject)
                ? primitiveValueKind(readExistingTypeNames((MdObject) target)) : null;
            if ("Boolean".equals(kind)) //$NON-NLS-1$
            {
                value = createMcoreValue("createBooleanValue", boolean.class, //$NON-NLS-1$
                    Boolean.valueOf(parseBooleanLiteral(v)));
            }
            else if ("Number".equals(kind)) //$NON-NLS-1$
            {
                java.math.BigDecimal bd;
                try
                {
                    bd = new java.math.BigDecimal(v);
                }
                catch (NumberFormatException nfe)
                {
                    return "fillValue '" + v + "' is not a valid Number"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                value = createMcoreValue("createNumberValue", java.math.BigDecimal.class, bd); //$NON-NLS-1$
            }
            else if ("String".equals(kind)) //$NON-NLS-1$
            {
                value = createMcoreValue("createStringValue", String.class, v); //$NON-NLS-1$
            }
            else if ("Date".equals(kind)) //$NON-NLS-1$
            {
                return "fillValue for Date attributes is not supported " //$NON-NLS-1$
                    + "(a date default is usually a StandardBeginningDate); " //$NON-NLS-1$
                    + "supported: String / Number / Boolean, or empty for Undefined"; //$NON-NLS-1$
            }
            else
            {
                return "fillValue is supported only for String / Number / Boolean primitive attributes " //$NON-NLS-1$
                    + "(or empty = Undefined); this attribute's type is " //$NON-NLS-1$
                    + (kind == null ? "a reference / composite / unresolved type" : kind); //$NON-NLS-1$
            }
        }
        if (value == null)
        {
            return "fillValue: McoreFactory unavailable on this EDT runtime"; //$NON-NLS-1$
        }
        try
        {
            setter.invoke(target, value);
            return null;
        }
        catch (Exception e)
        {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            return "fillValue not applied: " + (c.getMessage() != null //$NON-NLS-1$
                ? c.getMessage() : c.getClass().getSimpleName());
        }
    }

    /** True for the common true-literals (EN + RU); everything else is false. */
    private static boolean parseBooleanLiteral(String v)
    {
        String s = v.trim().toLowerCase();
        return "true".equals(s) || "истина".equals(s) || "да".equals(s) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || "1".equals(s) || "yes".equals(s); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Maps a TypeDescription's type-name set to one primitive kind
     * (String / Number / Boolean / Date), or {@code null} when it is empty,
     * composite (more than one type), or a non-primitive (reference) type.
     * Matches both English and the Russian platform names.
     */
    private static String primitiveValueKind(Set<String> typeNames)
    {
        if (typeNames == null || typeNames.size() != 1)
        {
            return null;
        }
        String n = typeNames.iterator().next().trim().toLowerCase();
        if ("boolean".equals(n) || "булево".equals(n)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "Boolean"; //$NON-NLS-1$
        }
        if ("number".equals(n) || "число".equals(n)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "Number"; //$NON-NLS-1$
        }
        if ("string".equals(n) || "строка".equals(n)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "String"; //$NON-NLS-1$
        }
        if ("date".equals(n) || "дата".equals(n)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "Date"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Builds an mcore {@code Value} via {@code McoreFactory.eINSTANCE.<createMethod>()}
     * and, when {@code valueParamType} is non-null, calls {@code setValue(valueArg)}
     * on it. Returns {@code null} when the factory chain is unreachable.
     */
    private static Object createMcoreValue(String createMethod, Class<?> valueParamType, Object valueArg)
    {
        for (String factoryClassName : FACTORY_CANDIDATES)
        {
            try
            {
                Class<?> clazz = Class.forName(factoryClassName);
                Field eInstance = clazz.getField("eINSTANCE"); //$NON-NLS-1$
                Object factory = eInstance.get(null);
                Object value = factory.getClass().getMethod(createMethod).invoke(factory);
                if (value == null)
                {
                    continue;
                }
                if (valueParamType != null)
                {
                    value.getClass().getMethod("setValue", valueParamType).invoke(value, valueArg); //$NON-NLS-1$
                }
                return value;
            }
            catch (ClassNotFoundException ignored)
            {
                // try next factory candidate
            }
            catch (Exception e)
            {
                Activator.logWarning("createMcoreValue " + createMethod //$NON-NLS-1$
                    + " failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Sets an object's {@code inputByString} - the ordered list of fields used
     * for input-by-string lookup (serialized as
     * {@code <inputByString>Catalog.X.StandardAttribute.Code</inputByString>}).
     * The owner ({@code BasicDbObject}) is itself a {@code mcore.FieldSource}: its
     * own resolved {@code Field}s are read through {@code getFields()} and matched
     * by {@code fieldId} against the comma-separated {@code rawNames} (Code,
     * Description, a custom attribute name, ...). The matched real fields are
     * referenced from {@code inputByString} - no proxy-URI construction, so the
     * cross-reference resolves and serializes as the qualified name. The
     * available field-id list plus resolved / unresolved names are collected for
     * the caller's tags. Returns {@code null} on success, or an error message.
     */
    public static String applyInputByString(EObject owner, String ownerFqn, String rawNames,
        IProject project, List<String> resolved, List<String> unresolved, List<String> diagnostics)
    {
        if (owner == null)
        {
            return "inputByString: owner is null"; //$NON-NLS-1$
        }
        Method ibsGetter = null;
        Method fieldsGetter = null;
        for (Method m : owner.getClass().getMethods())
        {
            if (m.getParameterCount() != 0)
            {
                continue;
            }
            if ("getInputByString".equals(m.getName())) //$NON-NLS-1$
            {
                ibsGetter = m;
            }
            else if ("getFields".equals(m.getName())) //$NON-NLS-1$
            {
                fieldsGetter = m;
            }
        }
        if (ibsGetter == null)
        {
            return "inputByString is not supported on " + owner.eClass().getName(); //$NON-NLS-1$
        }
        if (fieldsGetter == null)
        {
            return owner.eClass().getName() + " is not a FieldSource (no getFields)"; //$NON-NLS-1$
        }
        EList<Object> available;
        EList<Object> ibsList;
        try
        {
            Object fobj = fieldsGetter.invoke(owner);
            Object lobj = ibsGetter.invoke(owner);
            if (!(fobj instanceof EList) || !(lobj instanceof EList))
            {
                return "inputByString / getFields did not return an EList"; //$NON-NLS-1$
            }
            @SuppressWarnings("unchecked")
            EList<Object> fcast = (EList<Object>) fobj;
            @SuppressWarnings("unchecked")
            EList<Object> lcast = (EList<Object>) lobj;
            available = fcast;
            ibsList = lcast;
        }
        catch (Exception e)
        {
            return "inputByString: getFields / getInputByString failed: " + e.getMessage(); //$NON-NLS-1$
        }
        java.util.Map<String, Object> byId = new java.util.LinkedHashMap<>();
        for (Object f : available)
        {
            String fid = fieldIdOf(f);
            if (fid != null && !byId.containsKey(fid))
            {
                byId.put(fid, f);
            }
        }
        diagnostics.add("availableFieldIds=" + byId.keySet()); //$NON-NLS-1$
        List<Object> picked = new ArrayList<>();
        for (String partRaw : (rawNames == null ? "" : rawNames).split(",")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            String name = partRaw.trim();
            if (name.isEmpty())
            {
                continue;
            }
            Object f = matchFieldById(byId, name);
            if (f != null)
            {
                picked.add(f);
                resolved.add(name);
            }
            else
            {
                unresolved.add(name);
            }
        }
        if (picked.isEmpty())
        {
            return "inputByString: no fields matched (input: " + rawNames //$NON-NLS-1$
                + "; available fieldIds: " + byId.keySet() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        try
        {
            ibsList.clear();
            ibsList.addAll(picked);
        }
        catch (Exception e)
        {
            return "inputByString: failed to populate the list: " + e.getMessage(); //$NON-NLS-1$
        }
        return null;
    }

    /** Reads {@code mcore.Field.getFieldId()} reflectively; {@code null} on failure. */
    private static String fieldIdOf(Object field)
    {
        try
        {
            Object id = field.getClass().getMethod("getFieldId").invoke(field); //$NON-NLS-1$
            return id == null ? null : id.toString();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Matches a requested attribute name against the field-id map. The field-id
     * format is {@code <Kind>:<Name>} (e.g. {@code StandardAttribute:Code},
     * {@code Attribute:Прим}), so the name is matched against the tail after the
     * last {@code :} (also tolerating a {@code .} separator and exact id).
     */
    private static Object matchFieldById(java.util.Map<String, Object> byId, String name)
    {
        Object exact = byId.get(name);
        if (exact != null)
        {
            return exact;
        }
        for (java.util.Map.Entry<String, Object> e : byId.entrySet())
        {
            String id = e.getKey();
            String tail = id;
            int sep = Math.max(id.lastIndexOf(':'), id.lastIndexOf('.'));
            if (sep >= 0 && sep + 1 < id.length())
            {
                tail = id.substring(sep + 1);
            }
            if (tail.equalsIgnoreCase(name) || id.equalsIgnoreCase(name))
            {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Sets an attribute's {@code choiceParameters} - fixed choice-filter values
     * (serialized as {@code <choiceParameters><name>Отбор.X</name><value .../></choiceParameters>}).
     * Each item is a {@code {name, value}} map; the value kind is inferred from
     * the string (true/false -> Boolean, numeric -> Number, empty -> Undefined,
     * else String). Replaces the existing list. Returns {@code null} on success
     * or an error message.
     */
    public static String applyChoiceParameters(EObject target,
        List<java.util.Map<String, String>> items, List<String> applied, List<String> diag)
    {
        if (target == null)
        {
            return "choiceParameters: target is null"; //$NON-NLS-1$
        }
        Object listObj = invokeNoArg(target, "getChoiceParameters"); //$NON-NLS-1$
        if (!(listObj instanceof EList))
        {
            return "choiceParameters is not supported on " + target.eClass().getName(); //$NON-NLS-1$
        }
        @SuppressWarnings("unchecked")
        EList<Object> list = (EList<Object>) listObj;
        List<Object> built = new ArrayList<>();
        for (java.util.Map<String, String> it : items)
        {
            String name = it.get("name"); //$NON-NLS-1$
            if (name == null || name.trim().isEmpty())
            {
                continue;
            }
            String rawVal = it.get("value"); //$NON-NLS-1$
            Object cp = createCommonObject("createChoiceParameter"); //$NON-NLS-1$
            if (cp == null)
            {
                return "choiceParameters: CommonFactory unavailable on this EDT runtime"; //$NON-NLS-1$
            }
            try
            {
                cp.getClass().getMethod("setName", String.class).invoke(cp, name.trim()); //$NON-NLS-1$
                Object val = buildInferredValue(rawVal);
                Method setVal = singleArgMethod(cp, "setValue"); //$NON-NLS-1$
                if (val != null && setVal != null)
                {
                    setVal.invoke(cp, val);
                }
            }
            catch (Exception e)
            {
                return "choiceParameters: failed for '" + name + "': " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            built.add(cp);
            applied.add(name.trim() + "=" + rawVal); //$NON-NLS-1$
        }
        if (built.isEmpty())
        {
            return "choiceParameters: nothing to set (each item needs a name)"; //$NON-NLS-1$
        }
        list.clear();
        list.addAll(built);
        return null;
    }

    /**
     * Sets an attribute's {@code choiceParameterLinks} - dynamic choice filters
     * linked to a field of the owner (serialized as {@code <choiceParameterLinks>
     * <name>Отбор.X</name><field>Catalog.X.Attribute.Y</field></choiceParameterLinks>}).
     * Each item is a {@code {name, field}} map; the field name is resolved against
     * the owner's FieldSource fields (same machinery as inputByString). Replaces
     * the existing list. Returns {@code null} on success or an error message.
     */
    public static String applyChoiceParameterLinks(EObject target, EObject owner,
        List<java.util.Map<String, String>> items, IProject project,
        List<String> applied, List<String> unresolved, List<String> diag)
    {
        if (target == null)
        {
            return "choiceParameterLinks: target is null"; //$NON-NLS-1$
        }
        if (owner == null)
        {
            return "choiceParameterLinks: owner (field source) is null"; //$NON-NLS-1$
        }
        Object listObj = invokeNoArg(target, "getChoiceParameterLinks"); //$NON-NLS-1$
        if (!(listObj instanceof EList))
        {
            return "choiceParameterLinks is not supported on " + target.eClass().getName(); //$NON-NLS-1$
        }
        @SuppressWarnings("unchecked")
        EList<Object> list = (EList<Object>) listObj;
        java.util.Map<String, Object> byId = fieldIdMap(owner);
        diag.add("availableFieldIds=" + byId.keySet()); //$NON-NLS-1$
        List<Object> built = new ArrayList<>();
        for (java.util.Map<String, String> it : items)
        {
            String name = it.get("name"); //$NON-NLS-1$
            String fieldName = it.get("field"); //$NON-NLS-1$
            if (name == null || name.trim().isEmpty() || fieldName == null || fieldName.trim().isEmpty())
            {
                continue;
            }
            Object field = matchFieldById(byId, fieldName.trim());
            if (field == null)
            {
                unresolved.add(name.trim() + " -> " + fieldName.trim()); //$NON-NLS-1$
                continue;
            }
            Object cpl = createCommonObject("createChoiceParameterLink"); //$NON-NLS-1$
            if (cpl == null)
            {
                return "choiceParameterLinks: CommonFactory unavailable on this EDT runtime"; //$NON-NLS-1$
            }
            try
            {
                cpl.getClass().getMethod("setName", String.class).invoke(cpl, name.trim()); //$NON-NLS-1$
                Method setField = singleArgMethod(cpl, "setField"); //$NON-NLS-1$
                if (setField != null)
                {
                    setField.invoke(cpl, field);
                }
            }
            catch (Exception e)
            {
                return "choiceParameterLinks: failed for '" + name + "': " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            built.add(cpl);
            applied.add(name.trim() + " -> " + fieldName.trim()); //$NON-NLS-1$
        }
        if (built.isEmpty())
        {
            return "choiceParameterLinks: no links built (available fieldIds: " //$NON-NLS-1$
                + byId.keySet() + ")"; //$NON-NLS-1$
        }
        list.clear();
        list.addAll(built);
        return null;
    }

    /** Builds an object via {@code CommonFactory.eINSTANCE.<createMethod>()}; null on failure. */
    private static Object createCommonObject(String createMethod)
    {
        try
        {
            Class<?> f = Class.forName("com._1c.g5.v8.dt.metadata.common.CommonFactory"); //$NON-NLS-1$
            Object inst = f.getField("eINSTANCE").get(null); //$NON-NLS-1$
            return inst.getClass().getMethod(createMethod).invoke(inst);
        }
        catch (Exception e)
        {
            Activator.logWarning("createCommonObject " + createMethod + " failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
            return null;
        }
    }

    /**
     * Builds an mcore {@code Value} whose subtype is inferred from a raw string:
     * true/false (EN+RU) -> BooleanValue, numeric -> NumberValue, empty ->
     * UndefinedValue, otherwise StringValue. Used for choice-parameter values
     * where there is no attribute type to consult.
     */
    private static Object buildInferredValue(String raw)
    {
        String v = raw == null ? "" : raw.trim(); //$NON-NLS-1$
        if (v.isEmpty())
        {
            return createMcoreValue("createUndefinedValue", null, null); //$NON-NLS-1$
        }
        String low = v.toLowerCase();
        if ("true".equals(low) || "false".equals(low) || "истина".equals(low) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || "ложь".equals(low) || "да".equals(low) || "нет".equals(low)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return createMcoreValue("createBooleanValue", boolean.class, //$NON-NLS-1$
                Boolean.valueOf(parseBooleanLiteral(v)));
        }
        try
        {
            java.math.BigDecimal bd = new java.math.BigDecimal(v);
            return createMcoreValue("createNumberValue", java.math.BigDecimal.class, bd); //$NON-NLS-1$
        }
        catch (NumberFormatException nfe)
        {
            // not numeric - fall through to String
        }
        return createMcoreValue("createStringValue", String.class, v); //$NON-NLS-1$
    }

    /** Builds a {@code fieldId -> Field} map from a FieldSource owner. */
    private static java.util.Map<String, Object> fieldIdMap(EObject owner)
    {
        java.util.Map<String, Object> byId = new java.util.LinkedHashMap<>();
        Object fobj = invokeNoArg(owner, "getFields"); //$NON-NLS-1$
        if (fobj instanceof EList)
        {
            for (Object f : (EList<?>) fobj)
            {
                String fid = fieldIdOf(f);
                if (fid != null && !byId.containsKey(fid))
                {
                    byId.put(fid, f);
                }
            }
        }
        return byId;
    }

    /** First public 1-argument method with the given name, or {@code null}. */
    private static Method singleArgMethod(Object obj, String name)
    {
        for (Method m : obj.getClass().getMethods())
        {
            if (m.getName().equals(name) && m.getParameterCount() == 1)
            {
                return m;
            }
        }
        return null;
    }

    /**
     * Builds a fresh TypeItem for the given FQN. Strategy:
     * <ol>
     *   <li>For {@code <X>Ref.<Name>} or {@code <X>Object.<Name>}: locate the
     *       referenced MdObject in {@code config}, pull its produced types,
     *       pick the matching kind, and {@link EcoreUtil#copy} it (so the
     *       resulting TypeItem is detached from its original container).</li>
     *   <li>For primitives: probe the platform factory and try a generic
     *       {@code create<Name>TypeItem} method.</li>
     * </ol>
     * Returns {@code null} when none of the above resolves.
     */
    private static Object createTypeItem(String fqn, IProject project, Configuration config)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }
        // Reference / Object / Selection / Manager types - via produced types.
        // Returns a PROXY pointing at the target's produced TypeItem (see
        // createFromProducedTypes) so BM can persist the reference by URI.
        Object copy = createFromProducedTypes(fqn, config);
        if (copy != null)
        {
            return copy;
        }
        // 1.42.5 BUG-1424-A: primitive Type proxy resolution chain.
        // Plain {@code McoreFactory.createType + setName} (no URI) was tried
        // and reverted - it matches EDT's GUI default-value display path
        // (UnfilledParentValueModel.createTypeDescription) but fails BM
        // commit with "Failed to persist reference value TypeImpl@..." for
        // fresh attributes. BM requires the Type to have a proxyURI.
        //
        // Canonical proxy via IEObjectProvider produces a registry-resolved
        // proxy, but the validator still flags the resulting Type as
        // "Неизвестный тип" - the proxy is intended for adoption / borrow
        // workflow, not fresh-attribute creation. Falling back to the
        // unresolved:/ URI hack (factory probe) gives identical validator
        // behaviour but commits cleanly. Both paths produce the same
        // on-disk XML; the validator marker is a cosmetic limitation we
        // accept until a deeper EDT API path is found.
        if (project != null && fqn.indexOf('.') < 0)
        {
            Object canonical = createCanonicalPrimitiveProxy(fqn, project);
            if (canonical != null)
            {
                return canonical;
            }
        }
        Object byFactory = createViaFactoryProbe(fqn);
        if (byFactory != null)
        {
            return byFactory;
        }
        return null;
    }

    /**
     * 1.42.5 GUI-editor pattern: builds a primitive Type the same way
     * {@code UnfilledParentValueModel.createTypeDescription} does in
     * {@code com._1c.g5.v8.dt.md.ui}. No proxy URI is set - EDT's serializer
     * writes {@code <types>X</types>} and the platform Type registry
     * resolves the literal back to the real primitive on the next .mdo
     * load. This is the path that produces a Type instance the
     * {@code md-legacy-check-type-description-types} marker accepts.
     *
     * @return Type instance with {@code name=fqn}, or null when the EMF
     *     factory chain is unreachable on this EDT runtime
     */
    private static Object createPlainPrimitiveType(String fqn)
    {
        if (fqn == null || fqn.isEmpty() || fqn.indexOf('.') >= 0)
        {
            return null;
        }
        for (String factoryClassName : FACTORY_CANDIDATES)
        {
            try
            {
                Class<?> clazz = Class.forName(factoryClassName);
                Field eInstance = clazz.getField("eINSTANCE"); //$NON-NLS-1$
                Object factory = eInstance.get(null);
                Method createType = factory.getClass().getMethod("createType"); //$NON-NLS-1$
                Object item = createType.invoke(factory);
                if (item instanceof EObject)
                {
                    if (!trySetterName((EObject) item, fqn))
                    {
                        tryEsetName((EObject) item, fqn);
                    }
                    return item;
                }
            }
            catch (ClassNotFoundException ignored)
            {
                // try next factory
            }
            catch (NoSuchMethodException nsm)
            {
                // factory does not expose createType() on this EDT version
                return null;
            }
            catch (Exception e)
            {
                Activator.logWarning("createPlainPrimitiveType('" + fqn //$NON-NLS-1$
                    + "') failed: " + e.getClass().getSimpleName() + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + e.getMessage());
            }
        }
        return null;
    }

    /**
     * 1.43 BUG-1424-A: walks the TypeDescription's types list and replaces
     * each unresolved Type proxy with the actual platform instance via
     * {@code IBmModelManager.resolve(proxy, contextResource, contextObject)}.
     * The validator reads the in-session BM, not the on-disk XML, so we
     * need the resolved instance there - otherwise
     * {@code md-legacy-check-type-description-types} flags "Неизвестный
     * тип" until {@code clean_project} forces a full reload.
     *
     * <p>Best-effort: failures degrade silently with a log warning. The
     * underlying types list still has the proxy, BM commit still
     * succeeds, on-disk XML still serializes correctly. The only
     * regression is the validator marker that {@code clean_project}
     * works around.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void resolveTypeItemsInPlace(EList<?> typesList, EObject contextObject)
    {
        if (typesList == null || typesList.isEmpty() || contextObject == null)
        {
            return;
        }
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return;
        }
        Object bmManager = activator.getBmModelManager();
        if (bmManager == null)
        {
            return;
        }
        Method resolveMethod;
        try
        {
            resolveMethod = bmManager.getClass().getMethod("resolve", //$NON-NLS-1$
                EObject.class, org.eclipse.emf.ecore.resource.Resource.class, EObject.class);
        }
        catch (NoSuchMethodException nsm)
        {
            Activator.logWarning("IBmModelManager.resolve(EObject, Resource, EObject) " //$NON-NLS-1$
                + "missing on this EDT runtime"); //$NON-NLS-1$
            return;
        }
        org.eclipse.emf.ecore.resource.Resource contextResource = contextObject.eResource();
        if (contextResource == null)
        {
            return;
        }
        // Replace each proxy in-place. We cannot call set() on the
        // EList while holding an Iterator - collect first, then mutate
        // by index.
        int len = typesList.size();
        for (int i = 0; i < len; i++)
        {
            Object item = typesList.get(i);
            if (!(item instanceof EObject))
            {
                continue;
            }
            EObject eo = (EObject) item;
            if (!eo.eIsProxy())
            {
                continue;
            }
            try
            {
                Object resolved = resolveMethod.invoke(bmManager, eo, contextResource,
                    contextObject);
                if (resolved instanceof EObject && resolved != eo
                    && !((EObject) resolved).eIsProxy())
                {
                    ((EList) typesList).set(i, resolved);
                }
            }
            catch (Exception e)
            {
                Activator.logWarning("resolveTypeItemsInPlace failed for index " //$NON-NLS-1$
                    + i + ": " + e.getClass().getSimpleName() + ": " //$NON-NLS-1$ //$NON-NLS-2$
                    + e.getMessage());
            }
        }
    }

    /**
     * Builds a canonical primitive Type proxy via
     * {@code IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version).getProxy(typeName)}.
     * This is the same path used by EDT's
     * {@code TypeDescriptionAdoptSupport.adoptTypeItem} so the resulting
     * Type instance resolves to the actual platform primitive at validation
     * time and the {@code md-legacy-check-type-description-types} marker is
     * not raised. All access through reflection so the helper survives
     * package renames between EDT releases.
     *
     * @return TypeItem proxy when the canonical chain resolves, null on any
     *     failure (caller falls back to the {@code unresolved:/} URI scheme)
     */
    private static Object createCanonicalPrimitiveProxy(String typeName, IProject project)
    {
        try
        {
            Activator activator = Activator.getDefault();
            if (activator == null)
            {
                return null;
            }
            Object versionSupport = activator.getRuntimeVersionSupport();
            if (versionSupport == null)
            {
                return null;
            }
            // EDT's IRuntimeVersionSupport.getRuntimeVersion(IProject) is the
            // canonical signature on the impl class
            // (com.e1c.g5.dt.core.legacy.internal.platform.RuntimeVersionSupport).
            // The interface also declares getRuntimeVersion(EObject) but
            // we have the project-level call site here.
            Method getRuntimeVersion = versionSupport.getClass()
                .getMethod("getRuntimeVersion", IProject.class); //$NON-NLS-1$
            Object version = getRuntimeVersion.invoke(versionSupport, project);
            if (version == null)
            {
                return null;
            }
            // McorePackage.Literals.TYPE_ITEM
            Class<?> mcorePackageLiterals = Class
                .forName("com._1c.g5.v8.dt.mcore.McorePackage$Literals"); //$NON-NLS-1$
            Object typeItemEClass = mcorePackageLiterals.getField("TYPE_ITEM").get(null); //$NON-NLS-1$
            // IEObjectProvider.Registry.INSTANCE
            Class<?> providerClass = Class
                .forName("com._1c.g5.v8.dt.platform.IEObjectProvider"); //$NON-NLS-1$
            Class<?> registryClass = Class
                .forName("com._1c.g5.v8.dt.platform.IEObjectProvider$Registry"); //$NON-NLS-1$
            Object registryInstance = registryClass.getField("INSTANCE").get(null); //$NON-NLS-1$
            // registry.get(EClass, Version)
            Method getMethod = registryClass.getMethod("get", //$NON-NLS-1$
                org.eclipse.emf.ecore.EClass.class, version.getClass());
            // Try the actual Version superclass too
            Object provider;
            try
            {
                provider = getMethod.invoke(registryInstance, typeItemEClass, version);
            }
            catch (NoSuchMethodError | IllegalArgumentException primaryFail)
            {
                // Some EDT releases declare get(EClass, Version) where Version
                // is the superclass. Fall back to a name-based lookup.
                Method fallback = null;
                for (Method m : registryClass.getMethods())
                {
                    if ("get".equals(m.getName()) && m.getParameterTypes().length == 2 //$NON-NLS-1$
                        && m.getParameterTypes()[0]
                            .isAssignableFrom(org.eclipse.emf.ecore.EClass.class))
                    {
                        fallback = m;
                        break;
                    }
                }
                if (fallback == null)
                {
                    throw primaryFail;
                }
                provider = fallback.invoke(registryInstance, typeItemEClass, version);
            }
            if (provider == null)
            {
                return null;
            }
            // provider.getProxy(typeName)
            Method getProxy = providerClass.getMethod("getProxy", String.class); //$NON-NLS-1$
            Object proxy = getProxy.invoke(provider, typeName);
            return proxy;
        }
        catch (ClassNotFoundException cnf)
        {
            Activator.logWarning("createCanonicalPrimitiveProxy: required class missing - " //$NON-NLS-1$
                + cnf.getMessage());
            return null;
        }
        catch (NoSuchFieldException | NoSuchMethodException nsf)
        {
            Activator.logWarning("createCanonicalPrimitiveProxy: API method missing - " //$NON-NLS-1$
                + nsf.getMessage());
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("createCanonicalPrimitiveProxy('" + typeName //$NON-NLS-1$
                + "') failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /**
     * Resolves a {@code <Kind>.<Name>} FQN to an existing produced TypeItem,
     * then makes a deep copy so the result has no container.
     */
    private static Object createFromProducedTypes(String fqn, Configuration config)
    {
        if (config == null || fqn == null || fqn.isEmpty())
        {
            return null;
        }
        String[] parts = fqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length != 2 || parts[1].isEmpty())
        {
            return null;
        }
        String kind = parts[0];
        String typePrefix = stripTypeKindSuffix(kind);
        if (typePrefix == null)
        {
            return null;
        }
        // Locate the MdObject by short type name + name (CatalogRef -> Catalog)
        MdObject target = MetadataTypeCatalog.findObject(config, typePrefix, parts[1]);
        if (target == null)
        {
            return null;
        }
        // Get producedTypes via reflection (avoids hard import of MdClassUtil
        // for builds where it lives in a different bundle)
        Object producedTypes = invokeNoArg(target, "getProducedTypes"); //$NON-NLS-1$
        if (producedTypes == null)
        {
            // Try MdClassUtil.getProducedTypes via reflection
            producedTypes = invokeStatic("com._1c.g5.v8.dt.metadata.mdclass.util.MdClassUtil", //$NON-NLS-1$
                "getProducedTypes", new Class<?>[] { MdObject.class }, target); //$NON-NLS-1$
        }
        if (!(producedTypes instanceof EObject))
        {
            return null;
        }
        // Iterate eContents() looking for the matching kind
        Collection<EObject> contents = ((EObject) producedTypes).eContents();
        Object matched = pickProducedTypeForKind(contents, kind);
        if (!(matched instanceof EObject))
        {
            return null;
        }
        Object typeItem = invokeNoArg(matched, "getType"); //$NON-NLS-1$
        if (typeItem == null)
        {
            typeItem = invokeNoArg(matched, "getTypeSet"); //$NON-NLS-1$
        }
        if (!(typeItem instanceof EObject))
        {
            return null;
        }
        // 1.43.x BUG-2: build a PROXY pointing at the produced TypeItem's URI
        // rather than a detached EcoreUtil.copy. The copy carries the produced
        // type's non-containment cross-references with no proxy URI, which BM
        // rejects at commit ("Failed to persist reference value TypeImpl@...").
        // A proxy with the produced-type URI is persisted by reference and
        // resolves to the real type on reload (serialises as <types>Kind.Name</types>).
        // setTypesOnDescription skips deep-resolution for reference FQNs so this
        // proxy survives to the commit instead of being materialised back to a
        // detached instance.
        EObject src = (EObject) typeItem;
        org.eclipse.emf.common.util.URI proxyUri = EcoreUtil.getURI(src);
        if (proxyUri == null)
        {
            return EcoreUtil.copy(src);
        }
        EObject proxy = EcoreUtil.create(src.eClass());
        ((org.eclipse.emf.ecore.InternalEObject) proxy).eSetProxyURI(proxyUri);
        return proxy;
    }

    /**
     * Maps an FQN-kind prefix ({@code CatalogRef}, {@code DocumentObject},
     * {@code DefinedType}, ...) to the metadata type (Catalog, Document,
     * DefinedType). Returns {@code null} for unknown prefixes.
     */
    private static String stripTypeKindSuffix(String kind)
    {
        if (kind == null)
        {
            return null;
        }
        for (String suffix : new String[] { "Ref", "Object", "Selection", "Manager", "Cache", "List" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            if (kind.endsWith(suffix))
            {
                String stripped = kind.substring(0, kind.length() - suffix.length());
                if (!stripped.isEmpty())
                {
                    return stripped;
                }
            }
        }
        // DefinedType / characteristics are matched directly
        if ("DefinedType".equals(kind) || "Characteristic".equals(kind)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return kind;
        }
        return null;
    }

    /**
     * Picks the produced type entry matching the requested kind
     * (Ref/Object/Selection/Manager/Cache/List) by inspecting the EClass name.
     */
    private static Object pickProducedTypeForKind(Collection<EObject> contents, String kind)
    {
        if (contents == null || contents.isEmpty())
        {
            return null;
        }
        // Map the FQN kind to its producedTypes containment FEATURE name. Matching
        // by feature is reliable; the EClass-name heuristic ("RefMdType") did not
        // match the actual produced-type classes and silently fell back to the
        // first element (objectType), so CatalogRef.X resolved to CatalogObject.X.
        // CatalogRef -> refType, CatalogObject -> objectType,
        // CatalogSelection -> selectionType, CatalogList -> listType,
        // CatalogManager -> managerType. Bare / DefinedType -> refType.
        String wantFeature = "refType"; //$NON-NLS-1$
        for (String s : new String[] { "Ref", "Object", "Selection", "Manager", "Cache", "List" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            if (kind.endsWith(s))
            {
                wantFeature = Character.toLowerCase(s.charAt(0)) + s.substring(1) + "Type"; //$NON-NLS-1$
                break;
            }
        }
        EObject first = null;
        EObject byClassName = null;
        for (EObject e : contents)
        {
            if (first == null)
            {
                first = e;
            }
            org.eclipse.emf.ecore.EStructuralFeature feat = e.eContainingFeature();
            if (feat != null && wantFeature.equals(feat.getName()))
            {
                return e;
            }
            // Secondary: EClass-name prefix (some runtimes expose RefMdType etc.).
            if (byClassName == null && e.eClass().getName().toLowerCase()
                .startsWith(wantFeature.substring(0, wantFeature.length() - 4)))
            {
                byClassName = e;
            }
        }
        return byClassName != null ? byClassName : first;
    }

    /**
     * Probes platform factories for {@code createType()} - the constructor
     * used internally by EDT to build a TypeItem for a primitive
     * ({@code String}, {@code Number}, {@code Date}, ...). The pattern
     * mirrors what {@code UnfilledParentValueModel.createTypeDescription}
     * does in EDT 2026.1:
     * <pre>
     *   Type t = McoreFactory.eINSTANCE.createType();
     *   t.setName("String");
     * </pre>
     * The legacy {@code createTypeItem()} variant is kept as a fallback for
     * older EDT versions, with an {@code eSet("name", ...)} best-effort
     * because they may not expose a typed setter.
     */
    private static Object createViaFactoryProbe(String fqn)
    {
        for (String factoryClassName : FACTORY_CANDIDATES)
        {
            try
            {
                Class<?> clazz = Class.forName(factoryClassName);
                Field eInstance = clazz.getField("eINSTANCE"); //$NON-NLS-1$
                Object factory = eInstance.get(null);
                // Prefer createType() - it returns a Type with a typed
                // setName(String) setter available on EDT 2026.1.
                for (String method : new String[] { "createType", "createTypeItem" }) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    try
                    {
                        Method m = factory.getClass().getMethod(method);
                        Object item = m.invoke(factory);
                        if (item instanceof EObject)
                        {
                            // Try the typed setName(String) first - it engages
                            // the proper EMF notification chain. Fall back to
                            // eSet for older shapes (createTypeItem path).
                            if (!trySetterName((EObject) item, fqn))
                            {
                                tryEsetName((EObject) item, fqn);
                            }
                            // EDT primitive types live in the platform type
                            // registry. A freshly-built Type without a proxy
                            // URI cannot be persisted by BM ("Failed to
                            // persist reference value TypeImpl@..."). Setting
                            // proxyURI=unresolved:/<name> matches the pattern
                            // used by EDT itself in
                            // TypeDescriptionAdoptSupport.adoptTypeItem so
                            // the platform resolver picks up the right
                            // primitive on commit.
                            attachUnresolvedProxyUri((EObject) item, fqn);
                            return item;
                        }
                    }
                    catch (NoSuchMethodException ignored)
                    {
                        // try next
                    }
                }
            }
            catch (ClassNotFoundException ignored)
            {
                // try next factory
            }
            catch (Exception e)
            {
                Activator.logWarning("createViaFactoryProbe " + factoryClassName //$NON-NLS-1$
                    + " failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Attaches an {@code unresolved:/<typeName>} proxy URI to the freshly
     * created Type so EDT's platform type resolver can substitute it for
     * the real primitive at commit time. Matches the pattern used by
     * {@code TypeDescriptionAdoptSupport.adoptTypeItem} in EDT 2026.1.
     */
    private static void attachUnresolvedProxyUri(EObject item, String typeName)
    {
        if (item == null || typeName == null || typeName.isEmpty())
        {
            return;
        }
        try
        {
            Class<?> internalEObject = Class
                .forName("org.eclipse.emf.ecore.InternalEObject"); //$NON-NLS-1$
            if (!internalEObject.isInstance(item))
            {
                return;
            }
            org.eclipse.emf.common.util.URI uri = org.eclipse.emf.common.util.URI
                .createURI("unresolved:/" + typeName); //$NON-NLS-1$
            Method eSetProxyURI = internalEObject.getMethod("eSetProxyURI", //$NON-NLS-1$
                org.eclipse.emf.common.util.URI.class);
            eSetProxyURI.invoke(item, uri);
        }
        catch (Exception e)
        {
            Activator.logWarning("attachUnresolvedProxyUri('" + typeName //$NON-NLS-1$
                + "') failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Attempts {@code item.setName(String)} via reflection. Returns true on
     * successful invocation, false when no such setter exists.
     */
    private static boolean trySetterName(EObject item, String value)
    {
        try
        {
            Method setter = item.getClass().getMethod("setName", String.class); //$NON-NLS-1$
            setter.invoke(item, value);
            return true;
        }
        catch (NoSuchMethodException nsme)
        {
            return false;
        }
        catch (Exception e)
        {
            Activator.logWarning("trySetterName failed: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Ensures the given MdObject (typically a freshly-created attribute) has
     * a non-null TypeDescription that {@link #setTypes} can populate. EDT
     * defaults a brand-new attribute's {@code type} feature to {@code null};
     * we fabricate an empty {@code TypeDescription} via
     * {@code McoreFactory.eINSTANCE.createTypeDescription()} and attach it
     * through {@code setType(TypeDescription)}. Returns the resolved
     * (existing or freshly-attached) TypeDescription, or {@code null} when
     * neither the factory nor the setter is available on this EDT runtime.
     */
    private static Object ensureTypeDescription(MdObject target)
    {
        Object existing = readTypeDescription(target);
        if (existing != null)
        {
            return existing;
        }
        // Build a new empty TypeDescription via the platform factory.
        Object freshTd = null;
        Class<?> tdClass = null;
        for (String factoryClassName : FACTORY_CANDIDATES)
        {
            try
            {
                Class<?> clazz = Class.forName(factoryClassName);
                Field eInstance = clazz.getField("eINSTANCE"); //$NON-NLS-1$
                Object factory = eInstance.get(null);
                Method createTd = factory.getClass().getMethod("createTypeDescription"); //$NON-NLS-1$
                freshTd = createTd.invoke(factory);
                if (freshTd != null)
                {
                    tdClass = createTd.getReturnType();
                    break;
                }
            }
            catch (ClassNotFoundException ignored)
            {
                // try next factory
            }
            catch (Exception e)
            {
                Activator.logWarning("ensureTypeDescription factory probe " //$NON-NLS-1$
                    + factoryClassName + " failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        if (freshTd == null)
        {
            return null;
        }
        // Attach via setType / setTypeDescription. The target setter expects
        // the TypeDescription interface, not its impl; resolving it through
        // the factory return type matches that contract.
        for (String setterName : new String[] { "setType", "setTypeDescription" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            try
            {
                Method setter = target.getClass().getMethod(setterName, tdClass);
                setter.invoke(target, freshTd);
                return freshTd;
            }
            catch (NoSuchMethodException ignored)
            {
                // try next setter
            }
            catch (Exception e)
            {
                Activator.logWarning("ensureTypeDescription " + setterName //$NON-NLS-1$
                    + " failed: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        // Setter not available - return null so the caller can surface a
        // structured error instead of leaving a detached TypeDescription.
        return null;
    }

    private static void tryEsetName(EObject item, String value)
    {
        for (String f : new String[] { "name", "typeName", "primitiveType" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            org.eclipse.emf.ecore.EStructuralFeature feature = item.eClass().getEStructuralFeature(f);
            if (feature != null)
            {
                try
                {
                    item.eSet(feature, value);
                    return;
                }
                catch (Exception ignored)
                {
                    // try next feature
                }
            }
        }
    }

    private static Object invokeNoArg(Object target, String methodName)
    {
        if (target == null || methodName == null)
        {
            return null;
        }
        try
        {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        }
        catch (NoSuchMethodException ignored)
        {
            return null;
        }
        catch (Exception e)
        {
            Activator.logWarning("invokeNoArg " + methodName + " failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
            return null;
        }
    }

    private static Object invokeStatic(String className, String methodName,
        Class<?>[] paramTypes, Object... args)
    {
        try
        {
            Class<?> clazz = Class.forName(className);
            Method m = clazz.getMethod(methodName, paramTypes);
            return m.invoke(null, args);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Reads canonical type names from the existing TypeDescription.types list
     * (via {@code McoreUtil.getTypeName} reflection). Used for idempotency.
     */
    @SuppressWarnings("unchecked")
    private static Set<String> readCurrentTypeNames(EList<?> typesList)
    {
        Set<String> names = new HashSet<>();
        if (typesList == null || typesList.isEmpty())
        {
            return names;
        }
        for (Object item : (EList<Object>) typesList)
        {
            String name = readTypeName(item);
            if (name != null && !name.isEmpty())
            {
                names.add(name);
            }
        }
        return names;
    }

    private static String readTypeName(Object typeItem)
    {
        ensureClassProbeDone();
        // McoreUtil.getTypeName(TypeItem) - prefer if available (cached probes)
        if (cachedTypeItemClass != null && cachedMcoreUtilClass != null)
        {
            try
            {
                Method m = cachedMcoreUtilClass.getMethod("getTypeName", cachedTypeItemClass); //$NON-NLS-1$
                Object viaUtil = m.invoke(null, typeItem);
                if (viaUtil instanceof String)
                {
                    return (String) viaUtil;
                }
            }
            catch (Exception ignored)
            {
                // fall through to direct getter probe
            }
        }
        // Fallback: try common getter names directly on the item
        for (String getter : new String[] { "getName", "getTypeName" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Object v = invokeNoArg(typeItem, getter);
            if (v instanceof String)
            {
                return (String) v;
            }
        }
        return null;
    }

    private static Class<?> forNameOrNull(String className)
    {
        try
        {
            return Class.forName(className);
        }
        catch (ClassNotFoundException e)
        {
            return null;
        }
    }

    private static Object readTypeDescription(MdObject definedType)
    {
        for (String getter : new String[] { "getType", "getTypes", "getTypeDescription" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            try
            {
                Method m = definedType.getClass().getMethod(getter);
                Object result = m.invoke(definedType);
                if (result != null)
                {
                    return result;
                }
            }
            catch (NoSuchMethodException ignored)
            {
                // try next
            }
            catch (Exception e)
            {
                Activator.logWarning("readTypeDescription " + getter + " failed: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return null;
    }

    private static EList<?> readTypesList(Object typeDesc)
    {
        try
        {
            Method m = typeDesc.getClass().getMethod("getTypes"); //$NON-NLS-1$
            Object list = m.invoke(typeDesc);
            if (list instanceof EList)
            {
                return (EList<?>) list;
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // try alternative
        }
        catch (Exception e)
        {
            Activator.logWarning("readTypesList failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Best-effort check that the FQN looks like a valid type reference.
     * Accepts {@code <Type>Ref.<Name>} (CatalogRef, DocumentRef, EnumRef, etc.)
     * and a small set of primitive type names.
     */
    /**
     * Recognized bare (no-dot) platform primitive type names. {@link #isKnownTypeShape}
     * accepts ANY capitalized ASCII word as a primitive shape, so a typo like
     * {@code "Stirng"} passes and is materialized into an unresolved-type proxy that
     * the tool otherwise reports as applied. This allowlist backs
     * {@link #isUnrecognizedPrimitive} so callers can surface a warning without
     * changing the resolution behaviour. Lower-cased for locale-independent lookup.
     */
    private static final Set<String> KNOWN_PRIMITIVE_NAMES = new HashSet<>(Arrays.asList(
        "string", "number", "date", "boolean", "uuid", "valuestorage", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "type", "binarydata", "null", "arbitrary")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * Russian platform primitive type names mapped to their English canonical
     * form. Bare-name type resolution runs through {@link #isKnownTypeShape},
     * whose shape regex is ASCII-only, so a VALID Russian primitive ("Строка",
     * "Число") failed to resolve (reported unresolved) while an ASCII typo
     * ("Stirng") slipped through as an unresolved proxy. {@link #normalizePrimitiveFqn}
     * maps these to English before shape validation so RU and EN primitive names
     * behave identically. Keys are lower-cased for locale-independent lookup.
     */
    private static final Map<String, String> RU_PRIMITIVE_TO_EN;
    static
    {
        Map<String, String> m = new HashMap<>();
        m.put("строка", "String"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("число", "Number"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("дата", "Date"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("булево", "Boolean"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("уникальныйидентификатор", "UUID"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("хранилищезначения", "ValueStorage"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("двоичныеданные", "BinaryData"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("тип", "Type"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("произвольный", "Arbitrary"); //$NON-NLS-1$ //$NON-NLS-2$
        RU_PRIMITIVE_TO_EN = m;
    }

    /**
     * Russian names of the collection types a FORM attribute may carry, mapped to
     * their English canonical form. Deliberately NOT part of
     * {@link #RU_PRIMITIVE_TO_EN}: that map feeds every carrier, and an object
     * attribute typed ValueTable is invalid metadata. Worse, the object path's
     * typo warning inspects the ORIGINAL token, so a Cyrillic spelling normalized
     * in the shared path would be applied without even the warning the English
     * spelling gets. Applied only by {@link #setFormAttributeTypes}.
     * <p>
     * Three names, chosen by counting a real configuration rather than by
     * recollection: across the demo configuration's forms ValueTable appears 295
     * times as an attribute type, ValueList 282 and ValueTree 83, while Array,
     * Structure, Map and FixedArray appear zero times - the platform does not
     * accept those as form attribute types, so translating their Russian names
     * would only help a caller write something the platform rejects.
     * </p>
     */
    private static final Map<String, String> RU_FORM_COLLECTION_TO_EN;
    static
    {
        Map<String, String> m = new HashMap<>();
        m.put("деревозначений", "ValueTree"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("таблицазначений", "ValueTable"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("списокзначений", "ValueList"); //$NON-NLS-1$ //$NON-NLS-2$
        RU_FORM_COLLECTION_TO_EN = m;
    }

    /**
     * Maps the Russian name of a form-attribute collection type to its English
     * canonical form, leaving every other token alone. Each element may be a
     * comma-joined composite, so the mapping runs per comma-separated part and the
     * element is rebuilt in its original shape - the caller downstream splits on
     * commas itself and must see exactly what it would have seen before.
     *
     * @param typeFqns requested type tokens (nullable).
     * @return a new list with Russian collection names translated, or {@code typeFqns}
     *         itself when there is nothing to translate.
     */
    // Package-visible for BmDefinedTypeHelperTest: which carrier gets these aliases is
    // the whole point of the split, and a test is the only thing that keeps them here.
    static List<String> normalizeFormCollectionFqns(List<String> typeFqns)
    {
        if (typeFqns == null || typeFqns.isEmpty())
        {
            return typeFqns;
        }
        List<String> out = new ArrayList<>(typeFqns.size());
        boolean changed = false;
        for (String raw : typeFqns)
        {
            if (raw == null || raw.indexOf(',') < 0)
            {
                String en = raw == null ? null
                    : RU_FORM_COLLECTION_TO_EN.get(raw.trim().toLowerCase(Locale.ROOT));
                changed |= en != null;
                out.add(en != null ? en : raw);
                continue;
            }
            StringBuilder rebuilt = new StringBuilder();
            for (String part : raw.split(",")) //$NON-NLS-1$
            {
                String en = RU_FORM_COLLECTION_TO_EN.get(part.trim().toLowerCase(Locale.ROOT));
                changed |= en != null;
                if (rebuilt.length() > 0)
                {
                    rebuilt.append(','); // $NON-NLS-1$
                }
                rebuilt.append(en != null ? en : part);
            }
            out.add(rebuilt.toString());
        }
        return changed ? out : typeFqns;
    }

    /**
     * Maps a bare Russian primitive type name to its English canonical form (via
     * {@link #RU_PRIMITIVE_TO_EN}); returns any other token (dotted reference /
     * defined types, English names, unknown words) unchanged. Applied to each
     * expanded FQN before shape validation so "Строка" / "Число" resolve exactly
     * like "String" / "Number".
     *
     * @param fqn a single (already comma-split) requested type token.
     * @return the English primitive name when {@code fqn} is a Russian primitive,
     *         otherwise {@code fqn} unchanged.
     */
    // Package-visible for BmDefinedTypeHelperTest: the map shipped without a test,
    // and what it must NOT map matters as much as what it must.
    static String normalizePrimitiveFqn(String fqn)
    {
        if (fqn == null || fqn.indexOf('.') >= 0)
        {
            return fqn;
        }
        String en = RU_PRIMITIVE_TO_EN.get(fqn.trim().toLowerCase(Locale.ROOT));
        return en != null ? en : fqn;
    }

    /**
     * True when {@code fqn} is a bare (no-dot, no-comma) capitalized-ASCII token that
     * is NOT a recognized platform primitive - i.e. a probable typo that
     * {@link #isKnownTypeShape} lets through as a valid primitive shape. Reference
     * types (dotted), composite types (comma), defined types, and non-ASCII / Russian
     * tokens all return {@code false} (the last are already rejected as unresolved).
     * Intended for a non-fatal caller warning; it does not change what gets created.
     *
     * @param fqn the requested type token.
     * @return {@code true} when the token looks like a mistyped primitive.
     */
    public static boolean isUnrecognizedPrimitive(String fqn)
    {
        if (fqn == null)
        {
            return false;
        }
        String s = fqn.trim();
        if (s.isEmpty() || s.indexOf('.') >= 0 || s.indexOf(',') >= 0)
        {
            return false;
        }
        if (!s.matches("[A-Z][A-Za-z]+")) //$NON-NLS-1$
        {
            return false;
        }
        return !KNOWN_PRIMITIVE_NAMES.contains(s.toLowerCase(Locale.ROOT));
    }

    private static boolean isKnownTypeShape(String fqn, Configuration config)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return false;
        }
        String[] parts = fqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length == 1)
        {
            // primitive: String / Number / Date / Boolean / UUID / ...
            return parts[0].matches("[A-Z][A-Za-z]+"); //$NON-NLS-1$
        }
        if (parts[0].endsWith("Ref") || parts[0].endsWith("Object")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return parts[1] != null && !parts[1].isEmpty();
        }
        if (parts[0].equals("DefinedType") || parts[0].equals("ОпределяемыйТип")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return true;
        }
        return false;
    }
}
