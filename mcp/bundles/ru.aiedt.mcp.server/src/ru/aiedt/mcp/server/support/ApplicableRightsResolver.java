/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com._1c.g5.v8.dt.rights.IRightInfosService;
import com._1c.g5.v8.dt.rights.model.Right;

/**
 * Decides whether one canonical right may be stored on one metadata object.
 * <p>
 * The platform registry ({@link IRightInfosService}) is the authority when EDT has registered it
 * and it answers with a non-empty set. A headless runtime has no such service, so a fixed table of
 * the rights each ordinary object kind carries is the fallback. A kind the table does not know, and
 * a registry that is not there, are not a refusal: an unknown combination is left for EDT to
 * validate rather than rejected on a guess. A right already stored on an object of the same kind in
 * the role file being edited is a precedent and is accepted as well, so a table that lags the
 * platform does not block a change to a right the configuration already uses.
 * </p>
 */
public final class ApplicableRightsResolver
{
    private static final String[] HISTORY = {
        "ReadDataHistory", //$NON-NLS-1$
        "ViewDataHistory", //$NON-NLS-1$
        "ReadDataHistoryOfMissingData", //$NON-NLS-1$
        "UpdateDataHistory", //$NON-NLS-1$
        "UpdateDataHistoryOfMissingData", //$NON-NLS-1$
        "UpdateDataHistorySettings", //$NON-NLS-1$
        "UpdateDataHistoryVersionComment", //$NON-NLS-1$
        "EditDataHistoryVersionComment", //$NON-NLS-1$
        "SwitchToDataHistoryVersion" //$NON-NLS-1$
    };

    private static final Map<String, Set<String>> KNOWN = buildKnown();

    private ApplicableRightsResolver()
    {
        // utility
    }

    /**
     * The outcome of {@link #decide(String, String, Set, boolean)}.
     */
    public static final class Decision
    {
        /** Whether the right may be written. */
        public final boolean allowed;

        /**
         * Canonical names that apply, when the registry answered. Empty when the registry was not
         * ready or the right was allowed for another reason.
         */
        public final List<String> applicableRights;

        /** Why the right was refused, or {@code null} when it was allowed. */
        public final String error;

        private Decision(boolean allowed, List<String> applicableRights, String error)
        {
            this.allowed = allowed;
            this.applicableRights = applicableRights;
            this.error = error;
        }
    }

    /**
     * The metadata kind of an object FQN: the English singular of the first segment.
     *
     * @param targetFqn an object address such as {@code Catalog.Goods}; may be {@code null}
     * @return the kind, or an empty string when there is no address
     */
    public static String kindOf(String targetFqn)
    {
        if (targetFqn == null || targetFqn.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        int dot = targetFqn.indexOf('.');
        String head = dot < 0 ? targetFqn : targetFqn.substring(0, dot);
        String english = MetadataTypeCatalog.toEnglishSingular(head);
        return english != null ? english : head;
    }

    /**
     * Whether {@code canonicalRight} may be written for {@code objectKind}.
     *
     * @param objectKind the English kind, from {@link #kindOf(String)}
     * @param canonicalRight the platform right name ({@code Read}, {@code Posting}, ...)
     * @param applicable the names the registry reported, or {@code null} when it is not ready
     * @param precedent {@code true} when this role file already stores the right on this kind
     * @return the decision; a not-ready registry allows the write
     */
    public static Decision decide(String objectKind, String canonicalRight, Set<String> applicable,
        boolean precedent)
    {
        if (applicable == null || canonicalRight == null || canonicalRight.isEmpty())
        {
            return new Decision(true, List.of(), null);
        }
        for (String name : applicable)
        {
            if (canonicalRight.equalsIgnoreCase(name))
            {
                return new Decision(true, List.of(), null);
            }
        }
        if (precedent)
        {
            return new Decision(true, List.of(), null);
        }
        List<String> listed = new ArrayList<>(applicable);
        Collections.sort(listed);
        String kind = objectKind == null || objectKind.isEmpty() ? "this object" : objectKind; //$NON-NLS-1$
        String error = "right " + canonicalRight + " does not apply to " + kind //$NON-NLS-1$ //$NON-NLS-2$
            + "; applicable rights: " + String.join(", ", listed); //$NON-NLS-1$ //$NON-NLS-2$
        return new Decision(false, List.copyOf(listed), error);
    }

    /**
     * The fixed table of rights for one object kind.
     *
     * @param objectKind the English kind; may be {@code null}
     * @return the canonical names, or {@code null} when this kind is not in the table
     */
    public static Set<String> knownRights(String objectKind)
    {
        if (objectKind == null)
        {
            return null;
        }
        return KNOWN.get(objectKind);
    }

    /**
     * The rights EDT's registry reports for one model object.
     *
     * @param object the metadata object; may be {@code null}
     * @return the canonical names, or {@code null} when the registry is absent, not ready, or answers
     *         nothing
     */
    public static Set<String> rightsFor(EObject object)
    {
        if (object == null)
        {
            return null;
        }
        Bundle bundle = FrameworkUtil.getBundle(ApplicableRightsResolver.class);
        if (bundle == null)
        {
            return null;
        }
        BundleContext context = bundle.getBundleContext();
        if (context == null)
        {
            return null;
        }
        ServiceReference<IRightInfosService> reference =
            context.getServiceReference(IRightInfosService.class);
        if (reference == null)
        {
            return null;
        }
        IRightInfosService service = context.getService(reference);
        try
        {
            if (service == null)
            {
                return null;
            }
            Set<Right> rights = service.getRights(object);
            // An empty answer is the registry not having loaded this version yet, not a claim that
            // the object has no rights. Treating it as "not ready" keeps the static table, and a
            // kind the table does not know, from refusing every right.
            if (rights == null || rights.isEmpty())
            {
                return null;
            }
            Set<String> names = new LinkedHashSet<>();
            for (Right right : rights)
            {
                if (right != null && right.getName() != null && !right.getName().isEmpty())
                {
                    names.add(right.getName());
                }
            }
            return names.isEmpty() ? null : Collections.unmodifiableSet(names);
        }
        catch (Throwable unavailable)
        {
            // The registry is an optional EDT service. A missing or incompatible implementation is
            // "not ready", and the caller falls back to the static table.
            return null;
        }
        finally
        {
            context.ungetService(reference);
        }
    }

    /**
     * Whether {@code root} already stores {@code canonicalRight} on an object of {@code kind}.
     *
     * @param root the {@code Rights} element; may be {@code null}
     * @param kind the English object kind
     * @param canonicalRight the platform right name
     * @return {@code true} when a matching object block holds that right
     */
    public static boolean seen(Element root, String kind, String canonicalRight)
    {
        if (root == null || kind == null || kind.isEmpty()
            || canonicalRight == null || canonicalRight.isEmpty())
        {
            return false;
        }
        String prefix = kind + "."; //$NON-NLS-1$
        NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++)
        {
            Node node = kids.item(i);
            if (!(node instanceof Element) || !"object".equals(((Element)node).getTagName())) //$NON-NLS-1$
            {
                continue;
            }
            String name = directText((Element)node, "name"); //$NON-NLS-1$
            if (name == null)
            {
                continue;
            }
            String trimmed = name.trim();
            if (!trimmed.equalsIgnoreCase(kind) && !startsWithIgnoreCase(trimmed, prefix))
            {
                continue;
            }
            if (rightStored((Element)node, canonicalRight))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an object element stores one right, matched without regard to case.
     *
     * @param objectEl the {@code object} element
     * @param canonicalRight the platform right name
     * @return {@code true} when a {@code right} child carries that name
     */
    private static boolean rightStored(Element objectEl, String canonicalRight)
    {
        NodeList kids = objectEl.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++)
        {
            Node node = kids.item(i);
            if (!(node instanceof Element) || !"right".equals(((Element)node).getTagName())) //$NON-NLS-1$
            {
                continue;
            }
            String name = directText((Element)node, "name"); //$NON-NLS-1$
            if (name != null && canonicalRight.equalsIgnoreCase(name.trim()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The text of the first direct child with {@code tag}, or {@code null}.
     *
     * @param parent the parent element
     * @param tag the child tag
     * @return the text, or {@code null} when there is no such child
     */
    private static String directText(Element parent, String tag)
    {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++)
        {
            Node node = kids.item(i);
            if (node instanceof Element && tag.equals(((Element)node).getTagName()))
            {
                return node.getTextContent();
            }
        }
        return null;
    }

    /**
     * Case-folded prefix test for an object name. The kind is ASCII; the rest of the name may be
     * Cyrillic. {@link String#equalsIgnoreCase(String)} folds both, so no word-class pattern is
     * involved.
     *
     * @param text the object name
     * @param prefix the kind plus a dot
     * @return {@code true} when {@code text} starts with {@code prefix}
     */
    private static boolean startsWithIgnoreCase(String text, String prefix)
    {
        return text.length() >= prefix.length()
            && text.substring(0, prefix.length()).equalsIgnoreCase(prefix);
    }

    /**
     * Builds the fallback table. Kinds that are not listed stay {@code null} from
     * {@link #knownRights(String)} so an unlisted kind is not refused.
     *
     * @return kind to canonical right names
     */
    private static Map<String, Set<String>> buildKnown()
    {
        Set<String> catalog = mutable(
            "Read", "Insert", "Update", "Delete", "View", "Edit", "InputByString", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "InteractiveInsert", "InteractiveDelete", //$NON-NLS-1$ //$NON-NLS-2$
            "InteractiveSetDeletionMark", "InteractiveClearDeletionMark", "InteractiveDeleteMarked", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "InteractiveDeletePredefinedData", "InteractiveSetDeletionMarkPredefinedData", //$NON-NLS-1$ //$NON-NLS-2$
            "InteractiveClearDeletionMarkPredefinedData", "InteractiveDeleteMarkedPredefinedData"); //$NON-NLS-1$ //$NON-NLS-2$
        addHistory(catalog);
        catalog = Collections.unmodifiableSet(catalog);

        Set<String> document = mutable(
            "Read", "Insert", "Update", "Delete", "View", "Edit", "InputByString", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "InteractiveInsert", "InteractiveDelete", //$NON-NLS-1$ //$NON-NLS-2$
            "InteractiveSetDeletionMark", "InteractiveClearDeletionMark", "InteractiveDeleteMarked", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Posting", "UndoPosting", //$NON-NLS-1$ //$NON-NLS-2$
            "InteractivePosting", "InteractivePostingRegular", "InteractiveUndoPosting", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "InteractiveChangeOfPosted"); //$NON-NLS-1$
        addHistory(document);
        document = Collections.unmodifiableSet(document);

        Set<String> enumeration = freeze("Read", "View", "InputByString"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Set<String> use = freeze("Use", "View"); //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> constant = mutable("Read", "Update", "View", "Edit"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        addHistory(constant);
        constant = Collections.unmodifiableSet(constant);
        Set<String> register = mutable("Read", "Update", "View", "Edit", "TotalsControl"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        addHistory(register);
        register = Collections.unmodifiableSet(register);
        Set<String> journal = freeze("Read", "View"); //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> viewOnly = freeze("View"); //$NON-NLS-1$
        Set<String> session = freeze("Get", "Set"); //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> task = mutable(
            "Read", "Insert", "Update", "Delete", "View", "Edit", "InputByString", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "InteractiveActivate", "Execute", "InteractiveExecute"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        task = Collections.unmodifiableSet(task);
        Set<String> businessProcess = mutable(
            "Read", "Insert", "Update", "Delete", "View", "Edit", "InputByString", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "Start", "InteractiveStart", "InteractiveActivate"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        businessProcess = Collections.unmodifiableSet(businessProcess);
        Set<String> sequence = freeze("Read", "Update"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Set<String>> known = new LinkedHashMap<>();
        for (String kind : new String[] {
            "Catalog", "ChartOfAccounts", "ChartOfCharacteristicTypes", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "ChartOfCalculationTypes", "ExchangePlan" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            known.put(kind, catalog);
        }
        known.put("Document", document); //$NON-NLS-1$
        known.put("Enum", enumeration); //$NON-NLS-1$
        known.put("Report", use); //$NON-NLS-1$
        known.put("DataProcessor", use); //$NON-NLS-1$
        known.put("Constant", constant); //$NON-NLS-1$
        known.put("InformationRegister", register); //$NON-NLS-1$
        known.put("AccumulationRegister", register); //$NON-NLS-1$
        known.put("AccountingRegister", register); //$NON-NLS-1$
        known.put("CalculationRegister", register); //$NON-NLS-1$
        known.put("DocumentJournal", journal); //$NON-NLS-1$
        known.put("Subsystem", viewOnly); //$NON-NLS-1$
        known.put("CommonForm", viewOnly); //$NON-NLS-1$
        known.put("CommonCommand", viewOnly); //$NON-NLS-1$
        known.put("FilterCriterion", viewOnly); //$NON-NLS-1$
        known.put("WebService", freeze("Use")); //$NON-NLS-1$ //$NON-NLS-2$
        known.put("HTTPService", freeze("Use")); //$NON-NLS-1$ //$NON-NLS-2$
        known.put("SessionParameter", session); //$NON-NLS-1$
        known.put("Task", task); //$NON-NLS-1$
        known.put("BusinessProcess", businessProcess); //$NON-NLS-1$
        known.put("Sequence", sequence); //$NON-NLS-1$
        return Collections.unmodifiableMap(known);
    }

    /**
     * A mutable set of the given names, so {@link #addHistory(Set)} can extend it.
     *
     * @param names canonical right names
     * @return the set
     */
    private static Set<String> mutable(String... names)
    {
        Set<String> set = new LinkedHashSet<>();
        Collections.addAll(set, names);
        return set;
    }

    /**
     * An unmodifiable set of the given names.
     *
     * @param names canonical right names
     * @return the set
     */
    private static Set<String> freeze(String... names)
    {
        return Collections.unmodifiableSet(mutable(names));
    }

    /**
     * Adds the data-history rights to a mutable set built by {@link #mutable(String...)}.
     *
     * @param set the set being built
     */
    private static void addHistory(Set<String> set)
    {
        Collections.addAll(set, HISTORY);
    }

}
