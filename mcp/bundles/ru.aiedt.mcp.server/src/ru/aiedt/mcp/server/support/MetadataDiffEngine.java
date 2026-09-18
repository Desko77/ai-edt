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
        // Renames are paired BEFORE the walk: it visits the union in sorted order, and a removed
        // name sorted before its added one would be emitted as removed long before its pair is
        // seen - an object reporting itself both removed and renamed.
        Map<String, String> renamePairs = new java.util.LinkedHashMap<>();
        if (detectRenames)
        {
            for (Map.Entry<String, MdObject> added : bObjects.entrySet())
            {
                if (aObjects.containsKey(added.getKey()))
                {
                    continue;
                }
                String from = findRenameCandidate(added.getValue(), aObjects, bObjects);
                if (from != null && !renamePairs.containsKey(from))
                {
                    renamePairs.put(from, added.getKey());
                }
            }
        }
        Set<String> all = new TreeSet<>();
        all.addAll(aObjects.keySet());
        all.addAll(bObjects.keySet());
        for (String fqn : all)
        {
            MdObject inA = aObjects.get(fqn);
            MdObject inB = bObjects.get(fqn);
            if (renamePairs.containsKey(fqn))
            {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("from", fqn); //$NON-NLS-1$
                r.put("to", renamePairs.get(fqn)); //$NON-NLS-1$
                result.renamed.add(r);
                continue;
            }
            if (renamePairs.containsValue(fqn))
            {
                continue; // the added half of a pair, already reported
            }
            if (inA == null && inB != null)
            {
                result.added.add(fqn);
            }
            else if (inA != null && inB == null)
            {
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
        return structurallyEqual(a, b, false);
    }

    /**
     * The comparison itself, with the name taken out of the evidence when the caller says so.
     * <p>
     * A rename hunt compares two objects that are SUPPOSED to differ by name - excluded from the
     * evidence, a pure rename reads equal, and everything else that changed still reads as a
     * change. Left in, every candidate would read modified, and {@code renamed} would never hold
     * anything.
     * </p>
     *
     * @param a one side.
     * @param b the other.
     * @param ignoreName <code>true</code> when the name is not evidence of change.
     * @return whether they carry the same content
     */
    public static boolean structurallyEqual(EObject a, EObject b, boolean ignoreName)
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
            if (ignoreName && "name".equals(feature.getName())) //$NON-NLS-1$
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
                        // By name to MATCH the elements, then by content to compare them. A name
                        // alone proved nothing about the content: an attribute whose type changed
                        // compared equal under the same name, and the change was invisible at
                        // every level of the comparison. When the name is not evidence (a rename
                        // hunt), it is not asked of the children either.
                        for (int i = 0; i < aList.size(); i++)
                        {
                            if (!ignoreName
                                && !java.util.Objects.equals(nameOf(aList.get(i)),
                                    nameOf(bList.get(i))))
                            {
                                return false;
                            }
                            if (!structurallyEqual(aList.get(i), bList.get(i), ignoreName))
                            {
                                return false;
                            }
                        }
                    }
                    else
                    {
                        if (!ignoreName
                            && !java.util.Objects.equals(nameOf((EObject) av), nameOf((EObject) bv)))
                        {
                            return false;
                        }
                        if (!structurallyEqual((EObject) av, (EObject) bv, ignoreName))
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
            boolean same = feature instanceof EReference
                ? sameTarget(av, bv) : java.util.Objects.equals(av, bv);
            if (!same)
            {
                changes.add(feature.getName());
            }
        }
        return changes;
    }

    /**
     * Whether two reference values point at the same thing.
     * <p>
     * The two sides are two projects, so the object a reference of one points at is never the same
     * instance as the object the other points at. Compared by identity, every reference feature
     * came back as changed: on a pair of demonstration configurations, 250 objects were reported
     * modified and twelve of them had no difference at all. What is compared is the address - the
     * name the model gives the target inside its own project.
     * </p>
     *
     * @param ours the value on one side
     * @param theirs the value on the other
     * @return whether they name the same target, in the same order for a list
     */
    private static boolean sameTarget(Object ours, Object theirs)
    {
        if (ours == null || theirs == null)
        {
            return ours == theirs;
        }
        if (ours instanceof EList && theirs instanceof EList)
        {
            EList<?> mine = (EList<?>)ours;
            EList<?> yours = (EList<?>)theirs;
            if (mine.size() != yours.size())
            {
                return false;
            }
            for (int at = 0; at < mine.size(); at++)
            {
                if (!java.util.Objects.equals(addressOf(mine.get(at)), addressOf(yours.get(at))))
                {
                    return false;
                }
            }
            return true;
        }
        return java.util.Objects.equals(addressOf(ours), addressOf(theirs));
    }

    /**
     * The address a project gives an object, for comparing one project's reference with another's.
     * <p>
     * A top object answers with its own FQN. A child - a form, an attribute - has none of its own,
     * so it is named by the FQN of the object that holds it followed by its own name. Anything that
     * can say neither falls back to its class, which compares equal for two objects of the same
     * kind and different for two of different kinds; that is less than the truth, and it is what
     * can be told without the project.
     * </p>
     *
     * @param value one end of a reference
     * @return the address, never <code>null</code>
     */
    private static String addressOf(Object value)
    {
        if (!(value instanceof EObject))
        {
            return String.valueOf(value);
        }
        EObject object = (EObject)value;
        if (object instanceof com._1c.g5.v8.bm.core.IBmObject)
        {
            com._1c.g5.v8.bm.core.IBmObject bm = (com._1c.g5.v8.bm.core.IBmObject)object;
            try
            {
                String fqn = bm.bmGetFqn();
                if (fqn != null && !fqn.isEmpty())
                {
                    return fqn;
                }
            }
            catch (Exception notATopObject)
            {
                // Only a top object answers that; a child is named through its owner below.
            }
            com._1c.g5.v8.bm.core.IBmObject top = BmReferencesHelper.findTopContainer(bm);
            if (top != null && top != bm)
            {
                try
                {
                    return top.bmGetFqn() + "/" + nameOf(object); //$NON-NLS-1$
                }
                catch (Exception unnamed)
                {
                    // Falls through to the name alone.
                }
            }
        }
        String name = nameOf(object);
        return name != null ? object.eClass().getName() + "." + name //$NON-NLS-1$
            : object.eClass().getName();
    }

    /**
     * Heuristic rename detection: a removed object and an added object with
     * identical structure suggest a rename. Returns the FQN of the removed
     * candidate or {@code null}.
     * <p>
     * The name is taken out of the evidence - a candidate pair differs by name
     * by definition, and compared WITH it no pair ever reads equal, which is
     * why {@code renamed} came back empty on every comparison.
     * </p>
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
            if (structurallyEqual(entry.getValue(), added, true))
            {
                return entry.getKey();
            }
        }
        return null;
    }
}
