/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Structural diff engine for two metadata Configurations. Compares two trees
 * of {@link MdObject}s by name and reports added / removed / modified items
 * at the requested level.
 * <p>
 * Levels:
 * <ul>
 *   <li>{@code object} - configuration-level MdObject collection diff</li>
 *   <li>{@code attribute} - per-MdObject attribute / tabular section diff</li>
 *   <li>{@code form} - form structure diff via JSON tree (when available)</li>
 *   <li>{@code module} - delegated to caller (text diff)</li>
 *   <li>{@code template} - binary compare (delegated to caller)</li>
 * </ul>
 */
public final class MetadataDiffEngine
{
    private MetadataDiffEngine()
    {
        // utility class
    }

    /**
     * Result of a configuration diff.
     */
    public static final class DiffResult
    {
        public final List<String> added = new ArrayList<>();
        public final List<String> removed = new ArrayList<>();
        public final List<Map<String, Object>> modified = new ArrayList<>();
        public final List<Map<String, Object>> renamed = new ArrayList<>();

        public Map<String, Object> toMap()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("added", added); //$NON-NLS-1$
            m.put("removed", removed); //$NON-NLS-1$
            m.put("modified", modified); //$NON-NLS-1$
            m.put("renamed", renamed); //$NON-NLS-1$
            m.put("addedCount", added.size()); //$NON-NLS-1$
            m.put("removedCount", removed.size()); //$NON-NLS-1$
            m.put("modifiedCount", modified.size()); //$NON-NLS-1$
            m.put("renamedCount", renamed.size()); //$NON-NLS-1$
            return m;
        }
    }

    /**
     * Object-level diff between two configurations. Compares each MdObject
     * collection by FQN.
     */
    public static DiffResult diffObjects(Configuration a, Configuration b, boolean detectRenames)
    {
        DiffResult result = new DiffResult();
        if (a == null || b == null)
        {
            return result;
        }
        Map<String, MdObject> aObjects = collectMdObjects(a);
        Map<String, MdObject> bObjects = collectMdObjects(b);
        Set<String> all = new TreeSet<>();
        all.addAll(aObjects.keySet());
        all.addAll(bObjects.keySet());
        Set<String> consumedAdded = new TreeSet<>();
        for (String fqn : all)
        {
            MdObject inA = aObjects.get(fqn);
            MdObject inB = bObjects.get(fqn);
            if (inA == null && inB != null)
            {
                if (detectRenames)
                {
                    String renamedFrom = findRenameCandidate(inB, aObjects, bObjects);
                    if (renamedFrom != null && !consumedAdded.contains(renamedFrom))
                    {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("from", renamedFrom); //$NON-NLS-1$
                        r.put("to", fqn); //$NON-NLS-1$
                        result.renamed.add(r);
                        consumedAdded.add(renamedFrom);
                        continue;
                    }
                }
                result.added.add(fqn);
            }
            else if (inA != null && inB == null)
            {
                if (consumedAdded.contains(fqn))
                {
                    continue; // already accounted for as rename
                }
                result.removed.add(fqn);
            }
            else if (inA != null && inB != null)
            {
                if (!structurallyEqual(inA, inB))
                {
                    Map<String, Object> mod = new LinkedHashMap<>();
                    mod.put("fqn", fqn); //$NON-NLS-1$
                    mod.put("changes", listChanges(inA, inB)); //$NON-NLS-1$
                    result.modified.add(mod);
                }
            }
        }
        return result;
    }

    /**
     * Attribute-level diff for a single matching MdObject pair.
     */
    public static DiffResult diffAttributes(MdObject a, MdObject b)
    {
        DiffResult result = new DiffResult();
        if (a == null || b == null)
        {
            return result;
        }
        Map<String, EObject> aAttrs = collectChildrenByName(a, "getAttributes"); //$NON-NLS-1$
        Map<String, EObject> bAttrs = collectChildrenByName(b, "getAttributes"); //$NON-NLS-1$
        diffNamedMaps(aAttrs, bAttrs, "Attribute", result); //$NON-NLS-1$

        Map<String, EObject> aTs = collectChildrenByName(a, "getTabularSections"); //$NON-NLS-1$
        Map<String, EObject> bTs = collectChildrenByName(b, "getTabularSections"); //$NON-NLS-1$
        diffNamedMaps(aTs, bTs, "TabularSection", result); //$NON-NLS-1$

        Map<String, EObject> aForms = collectChildrenByName(a, "getForms"); //$NON-NLS-1$
        Map<String, EObject> bForms = collectChildrenByName(b, "getForms"); //$NON-NLS-1$
        diffNamedMaps(aForms, bForms, "Form", result); //$NON-NLS-1$

        return result;
    }

    private static void diffNamedMaps(Map<String, EObject> a, Map<String, EObject> b,
        String kind, DiffResult result)
    {
        Set<String> all = new TreeSet<>();
        all.addAll(a.keySet());
        all.addAll(b.keySet());
        for (String name : all)
        {
            if (!a.containsKey(name) && b.containsKey(name))
            {
                result.added.add(kind + "." + name); //$NON-NLS-1$
            }
            else if (a.containsKey(name) && !b.containsKey(name))
            {
                result.removed.add(kind + "." + name); //$NON-NLS-1$
            }
            // For modified: compare structural equality
            else if (a.containsKey(name) && b.containsKey(name))
            {
                if (!structurallyEqual(a.get(name), b.get(name)))
                {
                    Map<String, Object> mod = new LinkedHashMap<>();
                    mod.put("fqn", kind + "." + name); //$NON-NLS-1$ //$NON-NLS-2$
                    mod.put("changes", listChanges(a.get(name), b.get(name))); //$NON-NLS-1$
                    result.modified.add(mod);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, EObject> collectChildrenByName(EObject parent, String getterName)
    {
        Map<String, EObject> map = new LinkedHashMap<>();
        try
        {
            java.lang.reflect.Method m = parent.getClass().getMethod(getterName);
            Object value = m.invoke(parent);
            if (value instanceof EList)
            {
                for (EObject child : (EList<EObject>) value)
                {
                    String name = nameOf(child);
                    if (name != null)
                    {
                        map.put(name, child);
                    }
                }
            }
        }
        catch (Throwable ignored)
        {
            // type may not have the getter
        }
        return map;
    }

    private static String nameOf(EObject obj)
    {
        if (obj instanceof MdObject)
        {
            return ((MdObject) obj).getName();
        }
        try
        {
            java.lang.reflect.Method m = obj.getClass().getMethod("getName"); //$NON-NLS-1$
            Object value = m.invoke(obj);
            if (value instanceof String)
            {
                return (String) value;
            }
        }
        catch (Throwable ignored)
        {
            // not a named element
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MdObject> collectMdObjects(Configuration config)
    {
        Map<String, MdObject> result = new LinkedHashMap<>();
        for (java.lang.reflect.Method m : config.getClass().getMethods())
        {
            if (m.getParameterCount() != 0)
            {
                continue;
            }
            String name = m.getName();
            if (!name.startsWith("get") || "getClass".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                continue;
            }
            if (!java.util.List.class.isAssignableFrom(m.getReturnType()))
            {
                continue;
            }
            try
            {
                Object value = m.invoke(config);
                if (value instanceof java.util.List)
                {
                    String type = name.substring(3);
                    for (Object item : (java.util.List<Object>) value)
                    {
                        if (item instanceof MdObject)
                        {
                            String objName = ((MdObject) item).getName();
                            if (objName != null)
                            {
                                result.put(type + "." + objName, (MdObject) item); //$NON-NLS-1$
                            }
                        }
                    }
                }
            }
            catch (Throwable ignored)
            {
                // skip inaccessible getters
            }
        }
        return result;
    }

    /**
     * Structural equality check via {@code features.eGet()} traversal.
     * Compares non-derived non-transient EAttributes and contained EReferences
     * by name. Cross-tree EObject equality uses reference identity within the
     * same Configuration.
     */
    public static boolean structurallyEqual(EObject a, EObject b)
    {
        if (a == null || b == null)
        {
            return a == b;
        }
        if (!a.eClass().equals(b.eClass()))
        {
            return false;
        }
        EClass ec = a.eClass();
        for (EStructuralFeature feature : ec.getEAllStructuralFeatures())
        {
            if (feature.isTransient() || feature.isDerived())
            {
                continue;
            }
            Object av = a.eGet(feature);
            Object bv = b.eGet(feature);
            if (feature instanceof EAttribute)
            {
                if (!java.util.Objects.equals(av, bv))
                {
                    return false;
                }
            }
            else if (feature instanceof EReference)
            {
                EReference eref = (EReference) feature;
                if (eref.isContainment())
                {
                    if (eref.isMany())
                    {
                        @SuppressWarnings("unchecked")
                        EList<EObject> aList = (EList<EObject>) av;
                        @SuppressWarnings("unchecked")
                        EList<EObject> bList = (EList<EObject>) bv;
                        if (aList.size() != bList.size())
                        {
                            return false;
                        }
                        // For containment many lists, we keep comparison shallow-by-name to avoid
                        // deep recursion that EMF would explode. Caller's diffAttributes does a
                        // detailed pass for the named children.
                        for (int i = 0; i < aList.size(); i++)
                        {
                            if (!java.util.Objects.equals(nameOf(aList.get(i)),
                                nameOf(bList.get(i))))
                            {
                                return false;
                            }
                        }
                    }
                    else
                    {
                        if (!java.util.Objects.equals(nameOf((EObject) av), nameOf((EObject) bv)))
                        {
                            return false;
                        }
                    }
                }
                else
                {
                    // Cross-references: compare by name (qualified names should match between configs)
                    if (eref.isMany())
                    {
                        @SuppressWarnings("unchecked")
                        EList<EObject> aList = (EList<EObject>) av;
                        @SuppressWarnings("unchecked")
                        EList<EObject> bList = (EList<EObject>) bv;
                        if (aList.size() != bList.size())
                        {
                            return false;
                        }
                    }
                    else if (av != null && bv != null)
                    {
                        if (!java.util.Objects.equals(nameOf((EObject) av), nameOf((EObject) bv)))
                        {
                            return false;
                        }
                    }
                    else if (av != bv)
                    {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static List<String> listChanges(EObject a, EObject b)
    {
        List<String> changes = new ArrayList<>();
        EClass ec = a.eClass();
        for (EStructuralFeature feature : ec.getEAllStructuralFeatures())
        {
            if (feature.isTransient() || feature.isDerived())
            {
                continue;
            }
            if (feature instanceof EReference && ((EReference) feature).isContainment())
            {
                continue; // handled by diffAttributes at the next level
            }
            Object av = a.eGet(feature);
            Object bv = b.eGet(feature);
            if (!java.util.Objects.equals(av, bv))
            {
                changes.add(feature.getName());
            }
        }
        return changes;
    }

    /**
     * Heuristic rename detection: a removed object and an added object with
     * identical structure suggest a rename. Returns the FQN of the removed
     * candidate or {@code null}.
     */
    private static String findRenameCandidate(MdObject added, Map<String, MdObject> aObjects,
        Map<String, MdObject> bObjects)
    {
        for (Map.Entry<String, MdObject> entry : aObjects.entrySet())
        {
            if (bObjects.containsKey(entry.getKey()))
            {
                continue; // not removed
            }
            if (structurallyEqual(entry.getValue(), added))
            {
                return entry.getKey();
            }
        }
        return null;
    }
}
