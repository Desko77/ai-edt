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
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * Cleanup of the form items that reference an attribute, a tabular section or a tabular-section
 * column being removed from a metadata object.
 * <p>
 * {@code remove_object_attribute}, {@code remove_tabular_section} and
 * {@code remove_tabular_section_attribute} call {@link #previewAffected} before the removal: when
 * a form of the owner holds an item whose data path reaches the member, the call is refused with
 * {@link #requiresCascadeForms}, listing the forms and items. With {@code cascadeForms=true} they
 * call {@link #cleanupReferencesToMember} instead, and the items go in the same transaction as the
 * member.
 * </p>
 * <p>
 * An item references the member when its data path is the member under one of the form's data
 * roots, or runs below it: {@code Объект.Товары} and {@code Объект.Товары.Номенклатура} for the
 * tabular section {@code Товары}. The data roots are the form's main attributes; a form that
 * declares none is read with {@code Object} and {@code Объект}. A path that merely ends in the
 * member's name, such as a column {@code Товары} of another tabular section, is not a reference.
 * </p>
 */
public final class BmFormCleanupHelper
{
    private BmFormCleanupHelper()
    {
        // utility
    }

    /** The data roots of a form that declares no main attribute. */
    private static final List<String> DEFAULT_DATA_ROOTS = List.of("Object", "Объект"); //$NON-NLS-1$ //$NON-NLS-2$

    /** How long the export of the cleaned forms is waited for. */
    private static final long FORM_EXPORT_WAIT_MS = 10_000L;

    /**
     * Result of a cleanup pass.
     */
    public static final class CleanupResult
    {
        /** Map formFqn -&gt; list of removed item names. */
        public final Map<String, List<String>> removedByForm = new LinkedHashMap<>();

        /** Top-object FQNs of the form models the pass changed, for the export to disk. */
        final List<String> formModelFqns = new ArrayList<>();

        /**
         * Counts the items across every form.
         *
         * @return how many items the pass removed or would remove
         */
        public int totalRemoved()
        {
            int n = 0;
            for (List<String> v : removedByForm.values())
            {
                n += v.size();
            }
            return n;
        }

        /**
         * Lists the forms the pass touched.
         *
         * @return the form FQNs, in the order the forms were read
         */
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
     *                     for a column inside a tabular section. Compared ignoring
     *                     case, under each data root of the form.
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
            List<String> removed = removeItemsReferencing(formRoot, memberPath);
            if (!removed.isEmpty())
            {
                String fqn = computeFqn(owner, txForm);
                result.removedByForm.put(fqn, removed);
                result.formModelFqns.add(fqn + ".Form"); //$NON-NLS-1$
            }
        }
        return result;
    }

    /**
     * Computes the same {@link CleanupResult} as {@link #cleanupReferencesToMember} without
     * changing any form.
     *
     * @param tx live BM transaction
     * @param owner the metadata object whose forms are scanned
     * @param memberPath dot-segment path of the member about to be removed
     * @return the items that reference the member, per form; never null
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
            List<String> matches = itemsReferencing(formRoot, memberPath);
            if (!matches.isEmpty())
            {
                String fqn = computeFqn(owner, txForm);
                result.removedByForm.put(fqn, matches);
            }
        }
        return result;
    }

    /**
     * Builds the refusal of a removal whose member is still referenced by form items.
     *
     * @param memberPath dot-segment path of the member
     * @param preview the referencing items, from {@link #previewAffected}
     * @return the exception to throw from the write, carrying the {@code requiresCascadeForms}
     *         tag with {@code affectedForms}
     */
    public static MetadataGuards.BlockedGuardException requiresCascadeForms(String memberPath,
        CleanupResult preview)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("memberPath", memberPath); //$NON-NLS-1$
        data.put("affectedForms", preview.toTagData()); //$NON-NLS-1$
        data.put("affectedFormCount", preview.removedByForm.size()); //$NON-NLS-1$
        data.put("affectedItemCount", preview.totalRemoved()); //$NON-NLS-1$
        String hint = "Pass cascadeForms=true to remove these items together with the member. " //$NON-NLS-1$
            + "Nothing was removed."; //$NON-NLS-1$
        return new MetadataGuards.BlockedGuardException(MetadataGuards.Verdict.block(
            "Cannot remove '" + memberPath + "' - " + preview.totalRemoved() //$NON-NLS-1$ //$NON-NLS-2$
                + " form item(s) in " + preview.removedByForm.size() + " form(s) reference it.", //$NON-NLS-1$ //$NON-NLS-2$
            hint,
            new MetadataGuards.ErrorTag(ErrorTags.REQUIRES_CASCADE_FORMS.wire(), data)));
    }

    /**
     * Refuses the removal of a member that form items still reference, or removes those items.
     * <p>
     * Called inside the write, before the member is removed. Without {@code cascadeForms} a
     * referenced member is refused with {@link #requiresCascadeForms} and nothing in the
     * transaction has changed; with it the referencing items are removed.
     * </p>
     *
     * @param tx live BM transaction
     * @param owner the metadata object whose forms are scanned
     * @param memberPath dot-segment path of the member about to be removed
     * @param cascadeForms whether the caller asked for the referencing items to be removed
     * @return the removed items, per form; empty when no item referenced the member
     */
    public static CleanupResult clearOrRefuse(IBmTransaction tx, MdObject owner, String memberPath,
        boolean cascadeForms)
    {
        if (cascadeForms)
        {
            return cleanupReferencesToMember(tx, owner, memberPath);
        }
        CleanupResult preview = previewAffected(tx, owner, memberPath);
        if (preview.totalRemoved() > 0)
        {
            throw requiresCascadeForms(memberPath, preview);
        }
        return preview;
    }

    /**
     * Writes the form models a committed cleanup changed to disk.
     *
     * @param project the project the forms belong to
     * @param cleaned the result of {@link #cleanupReferencesToMember} after the transaction
     *            committed
     * @return the export's error text, or <code>null</code> when every form was written or there
     *         was nothing to write
     */
    public static String exportCleanedForms(IProject project, CleanupResult cleaned)
    {
        if (project == null || cleaned == null || cleaned.formModelFqns.isEmpty())
        {
            return null;
        }
        IBmModelManager manager = Activator.getDefault().getBmModelManager();
        if (manager == null)
        {
            return "the model manager is unavailable, the cleaned forms were not written to disk"; //$NON-NLS-1$
        }
        BmExportHelper.Result exported = BmExportHelper.forceExportAndWait(manager, project,
            cleaned.formModelFqns, FORM_EXPORT_WAIT_MS);
        if (exported != null && !exported.isOk())
        {
            return exported.error != null ? exported.error : "forceExport returned not-ok"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Names the items of a form whose data path references a member.
     *
     * @param formRoot the form model, the object exposing {@code getItems()}
     * @param memberPath dot-segment path of the member
     * @return the item names, depth first; empty when none references it
     */
    static List<String> itemsReferencing(Object formRoot, String memberPath)
    {
        return listMatchingItems(formRoot, dataPathsOf(formRoot, memberPath));
    }

    /**
     * Removes the items of a form whose data path references a member.
     *
     * @param formRoot the form model, the object exposing {@code getItems()}
     * @param memberPath dot-segment path of the member
     * @return the names of the removed items, depth first
     */
    static List<String> removeItemsReferencing(Object formRoot, String memberPath)
    {
        return removeMatchingItems(formRoot, dataPathsOf(formRoot, memberPath));
    }

    /**
     * The data paths of a member on a form, one per data root, lower-cased.
     *
     * @param formRoot the form model
     * @param memberPath dot-segment path of the member
     * @return {@code <root>.<memberPath>} for each data root of the form
     */
    private static List<String> dataPathsOf(Object formRoot, String memberPath)
    {
        List<String> paths = new ArrayList<>();
        for (String root : dataRoots(formRoot))
        {
            paths.add((root + "." + memberPath).toLowerCase(Locale.ROOT)); //$NON-NLS-1$
        }
        return paths;
    }

    /**
     * The names of the form's main attributes, the roots its data paths start from.
     *
     * @param formRoot the form model
     * @return the main attribute names; {@code Object} and {@code Объект} when the form declares
     *         no main attribute
     */
    static List<String> dataRoots(Object formRoot)
    {
        List<String> roots = new ArrayList<>();
        try
        {
            Object attributes = formRoot.getClass().getMethod("getAttributes").invoke(formRoot); //$NON-NLS-1$
            if (attributes instanceof List)
            {
                for (Object attribute : (List<?>) attributes)
                {
                    Object main = attribute.getClass().getMethod("isMain").invoke(attribute); //$NON-NLS-1$
                    Object name = attribute.getClass().getMethod("getName").invoke(attribute); //$NON-NLS-1$
                    if (Boolean.TRUE.equals(main) && name != null && !name.toString().isEmpty())
                    {
                        roots.add(name.toString());
                    }
                }
            }
        }
        catch (NoSuchMethodException ignored)
        {
            // The receiver is an Object whose shape this helper does not control, so a
            // missing member is an answer, not a failure: the form declares no attributes,
            // and its paths are read with the default roots.
        }
        catch (Exception e)
        {
            Activator.logWarning("dataRoots failed: " + e.getMessage()); //$NON-NLS-1$
        }
        return roots.isEmpty() ? DEFAULT_DATA_ROOTS : roots;
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
            // The receiver is an Object whose shape this helper does not control, so a
            // missing member is an answer, not a failure - the caller reads the null as
            // "this element has no such property".
            return false;
        }
    }

    /**
     * Walks the form tree from {@code container} and removes every form item whose
     * {@code dataPath} references one of the member paths.
     *
     * @param container the form or a group inside it
     * @param memberPaths the member's lower-cased data paths, one per data root
     * @return the names of the removed items
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static List<String> removeMatchingItems(Object container, List<String> memberPaths)
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
                if (matchesByDataPath(item, memberPaths))
                {
                    toRemove.add(item);
                    removed.add(itemName(item));
                    continue;
                }
                // A group that stays is walked: its items may still reference the member.
                if (hasGetItems(item))
                {
                    removed.addAll(removeMatchingItems(item, memberPaths));
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
     * Same traversal as {@link #removeMatchingItems}, read-only.
     *
     * @param container the form or a group inside it
     * @param memberPaths the member's lower-cased data paths, one per data root
     * @return the names of the items that would be removed
     */
    private static List<String> listMatchingItems(Object container, List<String> memberPaths)
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
                if (matchesByDataPath(item, memberPaths))
                {
                    matches.add(itemName(item));
                    continue;
                }
                if (hasGetItems(item))
                {
                    matches.addAll(listMatchingItems(item, memberPaths));
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
     * Whether a form item's data path is one of the member paths or runs below one of them.
     *
     * @param item the form item
     * @param memberPaths the member's lower-cased data paths, one per data root
     * @return <code>true</code> when the item references the member
     */
    private static boolean matchesByDataPath(Object item, List<String> memberPaths)
    {
        String dataPath = readDataPathString(item);
        if (dataPath == null || dataPath.isEmpty())
        {
            return false;
        }
        String dp = dataPath.toLowerCase(Locale.ROOT);
        for (String mp : memberPaths)
        {
            if (dp.equals(mp) || dp.startsWith(mp + ".")) //$NON-NLS-1$
            {
                return true;
            }
        }
        return false;
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
            // The receiver is an Object whose shape this helper does not control, so a
            // missing member is an answer, not a failure - the caller reads the null as
            // "this element has no such property".
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
     * Computes the FQN of a form of the given owner, in the shape the form operations take.
     *
     * @param owner the metadata object
     * @param form the form
     * @return {@code <OwnerType>.<OwnerName>.Form.<FormName>}
     */
    private static String computeFqn(MdObject owner, MdObject form)
    {
        String type = owner.eClass().getName();
        String ownerName = owner.getName();
        String formName = form.getName();
        return type + "." + ownerName + ".Form." + formName; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
