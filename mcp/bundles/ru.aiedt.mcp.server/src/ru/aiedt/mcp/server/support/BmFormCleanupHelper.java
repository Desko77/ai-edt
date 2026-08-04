/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * 1.40 — Cascade cleanup of form items that reference an attribute / tabular
 * section / column being removed from a metadata object. <p>
 *
 * extension framework parity: when {@code removeObjectAttribute} / {@code removeTabularSection}
 * / {@code removeTabularSectionAttribute} is called with {@code cascadeForms=true}
 * (or {@code force=true}), the helper sweeps every form belonging to the owner
 * and removes any FormField/FormGroup/Table item whose {@code dataPath} starts
 * with the deleted member, so the resulting forms remain valid and
 * UpdateDBCfg succeeds without "broken-form" errors.
 *
 * <p>Without {@code cascadeForms=true}, callers should use {@link #previewAffected}
 * to obtain a {@code affectedForms} dry-run list and refuse the operation with
 * {@code requiresCascadeForms} tag - the user explicitly opts in to cascade
 * deletion only after seeing what it touches.
 *
 * <p>All operations execute inside an existing BM read-write transaction
 * supplied by the caller (typically the {@code executeWriteOnObject} lambda
 * in {@code EditMetadataTool}) so cleanup and removal commit atomically.
 */
public final class BmFormCleanupHelper
{
    private BmFormCleanupHelper()
    {
        // utility
    }

    /**
     * Result of a cleanup pass.
     */
    public static final class CleanupResult
    {
        /** Map formFqn -&gt; list of removed item names. */
        public final Map<String, List<String>> removedByForm = new LinkedHashMap<>();

        public int totalRemoved()
        {
            int n = 0;
            for (List<String> v : removedByForm.values())
            {
                n += v.size();
            }
            return n;
        }

        public List<String> formFqns()
        {
            return new ArrayList<>(removedByForm.keySet());
        }

        /**
         * Builds an {@code affectedForms} structure suitable for the response
         * tag - array of {@code {formFqn, items: [...]}}.
         */
        public List<Map<String, Object>> toTagData()
        {
            List<Map<String, Object>> arr = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : removedByForm.entrySet())
            {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("formFqn", e.getKey()); //$NON-NLS-1$
                entry.put("items", new ArrayList<>(e.getValue())); //$NON-NLS-1$
                arr.add(entry);
            }
            return arr;
        }
    }

    /**
     * Removes form items whose dataPath references the given member.
     * <p>
     * Call this from inside an existing BM read-write transaction with the
     * owner already resolved.
     *
     * @param tx           live BM transaction
     * @param owner        the metadata object whose forms are scanned
     * @param memberPath   dot-segment path of the removed member, e.g.
     *                     {@code "Goods"} for an attribute, {@code "Items.Price"}
     *                     for a column inside a tabular section. Comparison is
     *                     case-insensitive and prefix-aware ({@code Object.Goods}
     *                     and {@code Object.Items.Price.*} both match).
     * @return cleanup result with per-form item lists; never null.
     */
    public static CleanupResult cleanupReferencesToMember(IBmTransaction tx, MdObject owner,
        String memberPath)
    {
        CleanupResult result = new CleanupResult();
        if (tx == null || owner == null || memberPath == null || memberPath.isEmpty())
        {
            return result;
        }
        List<MdObject> forms = listForms(owner);
        for (MdObject form : forms)
        {
            // Re-fetch via tx so we mutate inside the same transaction graph.
            MdObject txForm = form;
            if (form instanceof IBmObject)
            {
                Object loaded = tx.getObjectById(((IBmObject) form).bmGetId());
                if (loaded instanceof MdObject)
                {
                    txForm = (MdObject) loaded;
                }
            }
            // The Form-on-disk top object is reachable via the FormSettings;
            // for the cleanup we only need the .form Form root, which we get
            // via the form-attached FormForm (formAttachedForm).
            Object formRoot = resolveFormRoot(txForm);
            if (formRoot == null)
            {
                continue;
            }
            List<String> removed = removeMatchingItems(formRoot, memberPath);
            if (!removed.isEmpty())
            {
                String fqn = computeFqn(owner, txForm);
                result.removedByForm.put(fqn, removed);
            }
        }
        return result;
    }

    /**
     * Dry-run: compute the same {@link CleanupResult} without actually mutating
     * anything. Useful to surface {@code affectedForms} preview when caller did
     * not pass {@code cascadeForms=true}.
     */
    public static CleanupResult previewAffected(IBmTransaction tx, MdObject owner,
        String memberPath)
    {
        CleanupResult result = new CleanupResult();
        if (tx == null || owner == null || memberPath == null || memberPath.isEmpty())
        {
            return result;
        }
        List<MdObject> forms = listForms(owner);
        for (MdObject form : forms)
        {
            MdObject txForm = form;
            if (form instanceof IBmObject)
            {
                Object loaded = tx.getObjectById(((IBmObject) form).bmGetId());
                if (loaded instanceof MdObject)
                {
                    txForm = (MdObject) loaded;
                }
            }
            Object formRoot = resolveFormRoot(txForm);
            if (formRoot == null)
            {
                continue;
            }
            List<String> matches = listMatchingItems(formRoot, memberPath);
            if (!matches.isEmpty())
            {
                String fqn = computeFqn(owner, txForm);
                result.removedByForm.put(fqn, matches);
            }
        }
        return result;
    }

    /**
     * Builds a {@code requiresCascadeForms} tag carrying the
     * {@code affectedForms} preview. Use this in mutation lambdas to refuse
     * a remove operation when {@code cascadeForms=true} was not passed.
     */
    public static MetadataGuards.BlockedGuardException requiresCascadeForms(String memberPath,
        CleanupResult preview)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("memberPath", memberPath); //$NON-NLS-1$
        data.put("affectedForms", preview.toTagData()); //$NON-NLS-1$
        data.put("affectedFormCount", preview.removedByForm.size()); //$NON-NLS-1$
        data.put("affectedItemCount", preview.totalRemoved()); //$NON-NLS-1$
        String hint = "Pass cascadeForms=true (or force=true) to delete these items along with the member. "
            + "Form fields/columns/tables that reference '" + memberPath
            + "' will be removed automatically.";
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "Cannot remove '" + memberPath + "' - " + preview.totalRemoved()
                + " form item(s) in " + preview.removedByForm.size() + " form(s) reference it.",
            hint,
            new MetadataGuards.ErrorTag(ErrorTags.REQUIRES_CASCADE_FORMS.wire(), data)));
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the list of {@code Form} child objects of the owner (the value
     * exposed via {@code getForms()} EMF getter). Empty list when the owner
     * does not have forms.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static List<MdObject> listForms(MdObject owner)
    {
        try
        {
            Method m = owner.getClass().getMethod("getForms"); //$NON-NLS-1$
            Object list = m.invoke(owner);
            if (list instanceof EList)
            {
                List<MdObject> out = new ArrayList<>();
                for (Object o : (EList) list)
                {
                    if (o instanceof MdObject)
                    {
                        out.add((MdObject) o);
                    }
                }
                return out;
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // owner has no forms
        }
        catch (Exception e)
        {
            Activator.logWarning("listForms failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return Collections.emptyList();
    }

    /**
     * Resolves the {@code Form} root (the object exposing {@code getItems()})
     * from the metadata-level Form wrapper. Falls back to the wrapper itself
     * when the loader-loaded form attribute is not available.
     * <p>
     * EDT's MD-level {@code Form} usually exposes {@code getFormAttachedForm()}
     * or similar accessor; we probe by name to remain compatible.
     */
    private static Object resolveFormRoot(MdObject formMdObject)
    {
        // Try common accessor names
        for (String getter : new String[] { "getFormAttachedForm", "getForm", "getRootContainer" })
        {
            try
            {
                Method m = formMdObject.getClass().getMethod(getter);
                Object result = m.invoke(formMdObject);
                if (result != null && hasGetItems(result))
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
                Activator.logWarning("resolveFormRoot " + getter + " failed: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return hasGetItems(formMdObject) ? formMdObject : null;
    }

    private static boolean hasGetItems(Object o)
    {
        if (o == null)
        {
            return false;
        }
        try
        {
            o.getClass().getMethod("getItems"); //$NON-NLS-1$
            return true;
        }
        catch (NoSuchMethodException ignored)
        {
            return false;
        }
    }

    /**
     * Walks the form tree from {@code container} and removes every form item
     * whose {@code dataPath} matches {@code memberPath}. Returns the list of
     * removed item names.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static List<String> removeMatchingItems(Object container, String memberPath)
    {
        List<String> removed = new ArrayList<>();
        try
        {
            Object items = container.getClass().getMethod("getItems").invoke(container); //$NON-NLS-1$
            if (!(items instanceof EList))
            {
                return removed;
            }
            EList list = (EList) items;
            // Gather first, then remove (avoid CME)
            List<Object> toRemove = new ArrayList<>();
            for (Object item : list)
            {
                if (matchesByDataPath(item, memberPath))
                {
                    toRemove.add(item);
                    removed.add(itemName(item));
                }
                // recurse into containers regardless (groups always recurse,
                // even when not themselves matching)
                if (hasGetItems(item))
                {
                    removed.addAll(removeMatchingItems(item, memberPath));
                }
            }
            for (Object o : toRemove)
            {
                list.remove(o);
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("removeMatchingItems failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return removed;
    }

    /**
     * Same traversal as {@link #removeMatchingItems} but read-only - returns
     * names of items that would be removed.
     */
    private static List<String> listMatchingItems(Object container, String memberPath)
    {
        List<String> matches = new ArrayList<>();
        try
        {
            Object items = container.getClass().getMethod("getItems").invoke(container); //$NON-NLS-1$
            if (!(items instanceof EList))
            {
                return matches;
            }
            for (Object item : (EList<?>) items)
            {
                if (matchesByDataPath(item, memberPath))
                {
                    matches.add(itemName(item));
                }
                if (hasGetItems(item))
                {
                    matches.addAll(listMatchingItems(item, memberPath));
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("listMatchingItems failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return matches;
    }

    /**
     * Returns true when the form item's dataPath references the removed
     * member. Matches both direct ({@code Object.Goods}) and child-of-tabular-
     * section ({@code Object.Items.Price}) shapes.
     */
    private static boolean matchesByDataPath(Object item, String memberPath)
    {
        String dataPath = readDataPathString(item);
        if (dataPath == null || dataPath.isEmpty())
        {
            return false;
        }
        String dp = dataPath.toLowerCase(java.util.Locale.ROOT);
        String mp = memberPath.toLowerCase(java.util.Locale.ROOT);
        // Common 1C dataPath shape: Object.<member> or Object.<TS>.<column>
        // We accept match in any segment for robustness.
        if (dp.equals("object." + mp))
        {
            return true;
        }
        if (dp.startsWith("object." + mp + "."))
        {
            return true;
        }
        // Also match the trimmed form (without "Object." prefix)
        if (dp.equals(mp))
        {
            return true;
        }
        if (dp.startsWith(mp + "."))
        {
            return true;
        }
        // Last segment match (column inside tabular section that was removed
        // as part of removeTabularSection cascade)
        return dp.endsWith("." + mp);
    }

    /**
     * Reads the form item's {@code dataPath} property to a flat string.
     * dataPath is typically a {@code DataPath} EObject with
     * {@code getSegments()} returning a List&lt;String&gt;; we join with dots.
     * Falls back to {@code String.valueOf} when shape is unexpected.
     */
    private static String readDataPathString(Object item)
    {
        try
        {
            Method m = item.getClass().getMethod("getDataPath"); //$NON-NLS-1$
            Object dp = m.invoke(item);
            if (dp == null)
            {
                return null;
            }
            // Try DataPath.getSegments()
            try
            {
                Method seg = dp.getClass().getMethod("getSegments"); //$NON-NLS-1$
                Object segs = seg.invoke(dp);
                if (segs instanceof List)
                {
                    StringBuilder sb = new StringBuilder();
                    for (Object s : (List<?>) segs)
                    {
                        if (sb.length() > 0)
                        {
                            sb.append('.');
                        }
                        sb.append(s);
                    }
                    return sb.toString();
                }
            }
            catch (NoSuchMethodException ignored)
            {
                // DataPath without getSegments - fall through to toString
            }
            return dp.toString();
        }
        catch (NoSuchMethodException ignored)
        {
            return null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String itemName(Object item)
    {
        try
        {
            Method m = item.getClass().getMethod("getName"); //$NON-NLS-1$
            Object n = m.invoke(item);
            return n == null ? item.getClass().getSimpleName() : n.toString();
        }
        catch (Exception e)
        {
            return item.getClass().getSimpleName();
        }
    }

    /**
     * Computes a printable FQN for a Form belonging to the given owner.
     * Format: {@code <OwnerType>.<OwnerName>.Forms.<FormName>}.
     */
    private static String computeFqn(MdObject owner, MdObject form)
    {
        String type = owner.eClass().getName();
        String ownerName = owner.getName();
        String formName = form.getName();
        return type + "." + ownerName + ".Forms." + formName;
    }
}
