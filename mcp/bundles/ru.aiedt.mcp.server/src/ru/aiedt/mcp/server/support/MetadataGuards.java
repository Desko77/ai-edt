/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * Two safety checks reused by {@code edit_metadata}, {@code add_metadata_attribute}
 * and {@code write_module_source}:
 * <ol>
 *   <li>{@link #checkStandardAttributeConflict(MdObject, String)} - rejects
 *       attribute names that collide with platform-standard attributes
 *       (Date / Number / Posted / Code / Description / Owner / etc.) before
 *       the object becomes invalid in EDT.</li>
 *   <li>{@link #checkSupplierLock(MdObject)} - reports "supplier-locked"
 *       state by reading whatever support-mode getter the EDT runtime
 *       exposes (different versions name it differently). When no API is
 *       reachable, the guard returns {@code null} so callers do not block
 *       on a missing probe.</li>
 * </ol>
 */
public final class MetadataGuards
{
    // -----------------------------------------------------------------------
    // Owner-family fallback standard-attribute sets (lowercased, EN + RU).
    //
    // checkStandardAttributeConflict inspects the live
    // MdObject.getStandardAttributes() list FIRST (a materialized match blocks
    // with source=live). It then ALWAYS applies these family sets too, because
    // the live list is LAZY - empty on a freshly-created object until a standard
    // attribute is customized - so the live list alone cannot be the authority.
    // The family sets are the COMPLETE reserved-name net (the source=fallback
    // path). They are scoped PER OWNER FAMILY and SELF-CONTAINED (no shared
    // ref-like base) so a name that is standard for one family but not another
    // is not falsely blocked: a register field named "Description" is valid
    // (Description is a catalog/document standard, NOT a register one), and a
    // register field named "LineNumber" is valid too (LineNumber is a tabular-
    // section row standard, not a register one).

    // Catalog / chart-like (Catalog, ChartOfCharacteristicTypes,
    // ChartOfAccounts, ChartOfCalculationTypes, ExchangePlan): Ref /
    // DeletionMark / Predefined + Code / Description / Owner / Parent.
    private static final Set<String> CATALOG_STANDARD = Set.of(
        "ref", "deletionmark", "predefined", "predefineddataname", "ismarked", "isfolder", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "code", "description", "owner", "parent", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "ссылка", "пометкаудаления", "предопределенный", "имяпредопределенныхданных", "этогруппа", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "код", "наименование", "владелец", "родитель"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    // Document / BusinessProcess / Sequence: Ref / DeletionMark + Number /
    // Posted / Date.
    private static final Set<String> DOCUMENT_STANDARD = Set.of(
        "ref", "deletionmark", "ismarked", "number", "posted", "date", "moment", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "ссылка", "пометкаудаления", "номер", "проведен", "дата", "момент"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    // Registers (Information / Accumulation / Accounting / Calculation):
    // Recorder / Period / Active / RecordType. NOT Description / Code / Owner
    // / Parent / Ref / DeletionMark / LineNumber - none of those are register
    // standards, so a register Dimension / Resource / Attribute may freely
    // reuse them.
    private static final Set<String> REGISTER_STANDARD = Set.of(
        "recorder", "period", "active", "recordtype", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "регистратор", "период", "активность", "видзаписи"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    // Task: Ref / DeletionMark / Number / Description / Completed / Date (addressing attributes
    // are user-defined, not platform-standard names). "наименование" is included alongside
    // "описание" to retain the coverage of the pre-family global fallback (catalog-centric naming
    // applied uniformly); blocking either on a Task is harmless since neither is a sensible custom
    // attribute name there.
    private static final Set<String> TASK_STANDARD = Set.of(
        "ref", "deletionmark", "ismarked", "number", "description", "completed", "date", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "ссылка", "пометкаудаления", "номер", "описание", "выполнена", "дата", "наименование"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

    // Tabular section row: LineNumber.
    private static final Set<String> TS_STANDARD = Set.of(
        "linenumber", "номерстроки"); //$NON-NLS-1$ //$NON-NLS-2$

    private MetadataGuards()
    {
        // utility class
    }

    /**
     * Result of a guard check.
     * {@code error == null} -> the operation may proceed.
     */
    public static final class Verdict
    {
        public boolean blocked;
        public String error;
        public String hint;
        public String discoveredApi; // when applicable
        public ErrorTag tag; // structured tag surfaced into the JSON response

        public static Verdict pass()
        {
            return new Verdict();
        }

        public static Verdict block(String error, String hint)
        {
            Verdict v = new Verdict();
            v.blocked = true;
            v.error = error;
            v.hint = hint;
            return v;
        }

        public static Verdict block(String error, String hint, ErrorTag tag)
        {
            Verdict v = block(error, hint);
            v.tag = tag;
            return v;
        }
    }

    /**
     * Machine-readable tag attached to a {@link Verdict}. Surfaces as a
     * top-level field on the tool's JSON response so that AI agents can
     * branch on it (e.g. {@code response.standardAttributeConflict != null}).
     * <p>
     * Standard names:
     * <ul>
     *   <li>{@code supportLock} - object on vendor support, editing not allowed</li>
     *   <li>{@code standardAttributeConflict} - candidate name shadows a standard one</li>
     *   <li>{@code alreadyExists} - target child already exists at the destination</li>
     *   <li>{@code notFound} - target child does not exist</li>
     *   <li>{@code dryRunNotSupported} - operation lacks a dryRun preview path</li>
     * </ul>
     */
    public static final class ErrorTag
    {
        public final String name;
        public final Map<String, Object> data;

        public ErrorTag(String name, Map<String, Object> data)
        {
            this.name = name;
            this.data = data != null ? data : new LinkedHashMap<>();
        }

        public ErrorTag(String name)
        {
            this(name, new LinkedHashMap<>());
        }

        public ErrorTag put(String key, Object value)
        {
            this.data.put(key, value);
            return this;
        }
    }

    /**
     * Sentinel exception used by helpers to abort a BM transaction with a
     * structured {@link Verdict}. Callers (helpers + dispatchers) catch this
     * to surface {@code verdict.tag} into the response.
     */
    public static final class BlockedGuardException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        public final Verdict verdict;

        public BlockedGuardException(Verdict v)
        {
            super(v != null && v.error != null ? v.error : "blocked"); //$NON-NLS-1$
            this.verdict = v != null ? v : Verdict.pass();
        }

        /**
         * Walk the cause chain looking for a {@code BlockedGuardException}.
         * BM's {@code IBmModel.execute} can wrap our throwable in an
         * intermediate {@link RuntimeException}; this helper unwraps it.
         */
        public static BlockedGuardException unwrap(Throwable t)
        {
            Throwable cur = t;
            while (cur != null)
            {
                if (cur instanceof BlockedGuardException)
                {
                    return (BlockedGuardException) cur;
                }
                cur = cur.getCause();
            }
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Standard attribute conflict
    // -----------------------------------------------------------------------

    /**
     * Returns a {@link Verdict#block(String, String)} when the candidate
     * attribute name collides with a standard one on the given owner.
     * Comparison is case-insensitive and accepts both English and Russian
     * variants ("Date" / "Дата" / "date" / "ДАТА").
     */
    @SuppressWarnings("unchecked")
    public static Verdict checkStandardAttributeConflict(MdObject owner, String candidate)
    {
        if (owner == null || candidate == null || candidate.isEmpty())
        {
            return Verdict.pass();
        }
        String norm = candidate.trim().toLowerCase();

        // Live API path: MdObject.getStandardAttributes() returns a list of
        // StandardAttribute, each carrying a getName(). When the runtime
        // exposes it, we use the actual list - this captures
        // configuration-specific tweaks (e.g. UseStandardCommands hides some).
        try
        {
            Method m = owner.getClass().getMethod("getStandardAttributes"); //$NON-NLS-1$
            Object v = m.invoke(owner);
            if (v instanceof EList)
            {
                // StandardAttribute exposes getName() but is NOT an MdObject
                // (it extends DataHistorySupport), so the elements cannot be
                // cast to MdObject - iterate as Object and read the name via
                // the helper. A materialized match blocks here (source=live);
                // the family fallback below STILL runs afterwards because the
                // live list is lazy and empty on a freshly-created object until
                // a standard attribute is customized, so it cannot be the sole
                // authority (a fresh Catalog would otherwise accept "Code").
                EList<?> list = (EList<?>) v;
                for (Object sa : list)
                {
                    String saName = standardAttributeName(sa);
                    if (saName != null && saName.equalsIgnoreCase(norm))
                    {
                        ErrorTag tag = new ErrorTag(ErrorTags.STANDARD_ATTRIBUTE_CONFLICT.wire())
                            .put("name", candidate) //$NON-NLS-1$
                            .put("conflictsWith", saName) //$NON-NLS-1$
                            .put("ownerType", owner.eClass().getName()) //$NON-NLS-1$
                            .put("source", "live"); //$NON-NLS-1$ //$NON-NLS-2$
                        return Verdict.block(
                            "Name '" + candidate + "' clashes with the standard attribute '" //$NON-NLS-1$ //$NON-NLS-2$
                                + saName + "'", //$NON-NLS-1$
                            "Pick a different name. The standard attribute is " //$NON-NLS-1$
                                + "controlled by the parent object's properties.", //$NON-NLS-1$
                            tag);
                    }
                }
            }
        }
        catch (NoSuchMethodException nsme)
        {
            // type does not expose standard attributes - fall through to fallback list
        }
        catch (Exception e)
        {
            Activator.logWarning("checkStandardAttributeConflict reflection failed: " //$NON-NLS-1$
                + e.getMessage());
        }

        // Fallback: owner-family-scoped standard names (EN + RU). Runs after
        // the live path (whether or not live matched) because the live list is
        // lazy and may not yet contain a standard attribute the platform still
        // reserves for this owner family - so the fallback is the complete
        // reserved-name net, scoped per family to avoid blocking names that are
        // standard for one family but valid for another (e.g. "Description" on
        // a register, which is a catalog/document standard, not a register one).
        if (fallbackStandardNamesFor(owner).contains(norm))
        {
            ErrorTag tag = new ErrorTag(ErrorTags.STANDARD_ATTRIBUTE_CONFLICT.wire())
                .put("name", candidate) //$NON-NLS-1$
                .put("conflictsWith", norm) //$NON-NLS-1$
                .put("ownerType", owner.eClass().getName()) //$NON-NLS-1$
                .put("source", "fallback"); //$NON-NLS-1$ //$NON-NLS-2$
            return Verdict.block(
                "Name '" + candidate + "' matches a known platform-standard attribute", //$NON-NLS-1$ //$NON-NLS-2$
                "Use a different name. Standard attributes are managed by the platform " //$NON-NLS-1$
                    + "and cannot be shadowed by user-defined ones.", //$NON-NLS-1$
                tag);
        }
        return Verdict.pass();
    }

    // -----------------------------------------------------------------------
    // Supplier lock
    // -----------------------------------------------------------------------

    /**
     * Returns a {@link Verdict#block(String, String)} when the object is
     * locked by a supplier configuration ("On vendor support" with
     * "Editing not allowed"). Returns a pass with {@code discoveredApi}
     * filled when the support-mode API exists but the object is editable;
     * returns a plain pass when no API is reachable (best-effort).
     */
    public static Verdict checkSupplierLock(MdObject owner)
    {
        Verdict v = new Verdict();
        if (owner == null)
        {
            return v;
        }

        String resolved = null;
        Object mode = null;

        // EDT exposes the support mode under various names depending on
        // version. Try the common ones in order.
        String[] modeGetters = {
            "getUserSupportMode", "getSupportMode", "getSupport", "isOnSupport" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        };
        for (String getter : modeGetters)
        {
            try
            {
                Method m = owner.getClass().getMethod(getter);
                Object value = m.invoke(owner);
                if (value != null)
                {
                    resolved = getter;
                    mode = value;
                    break;
                }
            }
            catch (NoSuchMethodException ignored)
            {
                // try next
            }
            catch (Exception ignored)
            {
                // best-effort
            }
        }

        if (mode == null)
        {
            // No support API in this EDT runtime - we cannot block.
            return v;
        }
        v.discoveredApi = resolved;

        String modeStr = mode.toString();
        // Heuristic: any string mentioning "NotAllowed" or "Запрещено" or
        // ending with "_DISABLED" is a hard block. EDT enums:
        // CHANGES_NOT_ALLOWED / EDITING_NOT_ALLOWED / DENIED.
        String upper = modeStr.toUpperCase();
        if (upper.contains("NOT_ALLOWED") //$NON-NLS-1$
            || upper.contains("DENIED") //$NON-NLS-1$
            || upper.contains("DISABLED")) //$NON-NLS-1$
        {
            v.blocked = true;
            v.error = "Object '" + owner.getName() //$NON-NLS-1$
                + "' is on vendor support and editing is not allowed (mode=" //$NON-NLS-1$
                + modeStr + ")"; //$NON-NLS-1$
            v.hint = "Either enable editing in EDT (right-click -> Support -> " //$NON-NLS-1$
                + "Enable change), or work via a configuration extension " //$NON-NLS-1$
                + "(adoptObject + extension-side operations)."; //$NON-NLS-1$
            v.tag = new ErrorTag(ErrorTags.SUPPORT_LOCK.wire())
                .put("target", owner.getName()) //$NON-NLS-1$
                .put("ownerType", owner.eClass().getName()) //$NON-NLS-1$
                .put("userSupportMode", modeStr) //$NON-NLS-1$
                .put("discoveredApi", resolved) //$NON-NLS-1$
                .put("hint", v.hint); //$NON-NLS-1$
        }
        return v;
    }

    /**
     * Reads the name of a standard-attribute list element. The live
     * {@code getStandardAttributes()} list holds {@code StandardAttribute}
     * entries, which expose {@code getName()} but do NOT extend {@code MdObject}
     * (they extend {@code DataHistorySupport}); a plain {@code MdObject} cast
     * would throw. Falls back to {@code MdObject#getName()} for any element
     * that genuinely is an MdObject.
     */
    private static String standardAttributeName(Object standardAttribute)
    {
        if (standardAttribute instanceof MdObject)
        {
            return ((MdObject) standardAttribute).getName();
        }
        try
        {
            Object name = standardAttribute.getClass().getMethod("getName").invoke(standardAttribute); //$NON-NLS-1$
            return name instanceof String ? (String) name : null;
        }
        catch (Exception ignored)
        {
            // The receiver is an Object whose shape this helper does not control, so a
            // missing member is an answer, not a failure - the caller reads the null as
            // "this element has no such property".
            return null;
        }
    }

    /**
     * Selects the fallback set of standard-attribute names (lowercased, EN +
     * RU) that genuinely apply to the given owner's family, by matching the
     * owner's EMF class name. Each family set is self-contained (catalog-like
     * carries its own Ref / DeletionMark / Predefined, registers carry only
     * Recorder / Period / Active / RecordType) so no ref-like base leaks into
     * families that do not have it. Returns an empty set for families with no
     * known reserved names, so the fallback never blocks a valid name just
     * because the live {@code getStandardAttributes()} list was lazy/empty.
     * Applied by {@link #checkStandardAttributeConflict} AFTER the live path,
     * on every call, to net the reserved names the lazy live list may not yet
     * contain.
     */
    private static Set<String> fallbackStandardNamesFor(MdObject owner)
    {
        String cn = owner.eClass().getName().toLowerCase(); //$NON-NLS-1$
        // Tabular sections must be classified first: their EMF class names embed the parent family
        // (CatalogTabularSection, DocumentTabularSection, ...) so a later catalog/document check
        // would otherwise match the parent family and mis-classify them, blocking valid names like
        // Code while allowing the reserved LineNumber.
        if (cn.contains("tabularsection")) //$NON-NLS-1$
        {
            return TS_STANDARD;
        }
        if (cn.contains("catalog") //$NON-NLS-1$
            || cn.contains("characteristictype") //$NON-NLS-1$
            || cn.contains("chartofaccount") //$NON-NLS-1$
            || cn.contains("calculationtype") //$NON-NLS-1$
            || cn.contains("exchangeplan")) //$NON-NLS-1$
        {
            return CATALOG_STANDARD;
        }
        if (cn.contains("document") //$NON-NLS-1$
            || cn.contains("businessprocess") //$NON-NLS-1$
            || cn.contains("sequence")) //$NON-NLS-1$
        {
            return DOCUMENT_STANDARD;
        }
        if (cn.contains("register")) //$NON-NLS-1$
        {
            return REGISTER_STANDARD;
        }
        if (cn.contains("task")) //$NON-NLS-1$
        {
            return TASK_STANDARD;
        }
        return Set.of();
    }

    /**
     * Returns the list of standard attribute names matching a typical
     * Catalog/Document/Register profile. Used by `edit_metadata help` for
     * the "types" topic.
     */
    public static List<String> commonStandardAttributeNames()
    {
        return List.of("Ref", "DeletionMark", "Predefined", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Code", "Description", "Owner", "Parent", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Date", "Number", "Posted", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "LineNumber", "Recorder", "Period"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
