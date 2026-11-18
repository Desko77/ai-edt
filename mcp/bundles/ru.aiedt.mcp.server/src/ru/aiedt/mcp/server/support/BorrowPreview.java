/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * What a fresh borrow would do, read from the model and nothing else.
 * <p>
 * The plan is built through {@link ChildBorrow#plan(String, Object, boolean,
 * ChildBorrow.ChildCollections)} - the same containment walk the writer uses - so the address a
 * preview names is the address a borrow would ask for. The walk reads the base object; nothing
 * here calls the adopt service, locks the project or reserves a name, and the source port has no
 * method that could.
 * </p>
 * <p>
 * The answer does not predict the future. A child borrow is one adopt call per child with no
 * batch rollback, objects pulled in by an attribute's type cannot be known before the adopt
 * service runs, and module flags and form contents are not part of this plan. Those limits are
 * printed in the answer rather than left for the caller to discover after a write.
 * </p>
 */
public final class BorrowPreview
{
    /** The base has no such address. */
    public static final String ABSENT = "absent"; //$NON-NLS-1$

    /** The address is already in the extension. */
    public static final String ATTACHED = "attached"; //$NON-NLS-1$

    /** The extension could not be read, so neither of the above is established. */
    public static final String UNKNOWN = "unknown"; //$NON-NLS-1$

    /** The address resolves in the base but the platform cannot adopt it. */
    public static final String NOT_ADOPTABLE = "notAdoptable"; //$NON-NLS-1$

    private BorrowPreview()
    {
    }

    /**
     * One answer from the base model: present, known absent, or the read failed.
     * <p>
     * A read that failed is not a base that holds nothing. The preview must be able to say the
     * first, and an address the base was read for and does not hold is the second.
     * </p>
     */
    public static final class Lookup
    {
        /** What the read established. */
        public enum State
        {
            /** The base holds the object. */
            PRESENT,
            /** The base was read and does not hold the object. */
            ABSENT,
            /** The base could not be read, so neither of the above is established. */
            UNKNOWN
        }

        /** What the read established. */
        public final State state;

        /** The object when the state is {@link State#PRESENT}; otherwise <code>null</code>. */
        public final Object object;

        private Lookup(State state, Object object)
        {
            this.state = state;
            this.object = object;
        }

        /**
         * @param object the object the base holds
         * @return a present lookup
         */
        public static Lookup present(Object object)
        {
            return new Lookup(State.PRESENT, object);
        }

        /**
         * @return a lookup for an address the base was read for and does not hold
         */
        public static Lookup absent()
        {
            return new Lookup(State.ABSENT, null);
        }

        /**
         * @return a lookup for a base that could not be read
         */
        public static Lookup unknown()
        {
            return new Lookup(State.UNKNOWN, null);
        }
    }

    /**
     * Everything the preview is allowed to ask the live model.
     * <p>
     * Deliberately free of mutating methods: the structural guarantee that a preview writes nothing
     * is this interface, not a comment. The production source reads the base object through
     * {@link BmExtensionHelper#sourceLookup}, asks {@link BmExtensionHelper#attachmentState} about
     * an address, and gets the child collections from {@link ChildBorrow#fromTheModel()}.
     * </p>
     */
    public interface Source
    {
        /**
         * Reads the base object at an address.
         *
         * @param fqn the address
         * @return what the read established, never <code>null</code>
         */
        Lookup objectAt(String fqn);

        /**
         * How the extension holds an address.
         *
         * @param fqn the address
         * @return the attachment state, never <code>null</code>
         */
        BmExtensionHelper.Attachment attachmentOf(String fqn);

        /**
         * Whether the adopt service is reachable in this runtime.
         *
         * @return <code>true</code> when the service was found
         */
        boolean adoptServiceAvailable();

        /**
         * Where the child walk reads a kind's children from.
         *
         * @return the collections, never <code>null</code>
         */
        ChildBorrow.ChildCollections collections();
    }

    /**
     * The read-only plan for one root address.
     */
    public static final class Plan
    {
        /** The root the caller named. */
        public final String rootFqn;

        /** One of {@link #ABSENT}, {@link #ATTACHED}, {@link #UNKNOWN}, {@link #NOT_ADOPTABLE}. */
        public String rootStatus;

        /** Whether the base held the root address at all. */
        public boolean rootResolved;

        /** Whether reading the base failed, so the root's absence is not established either. */
        public boolean sourceUnknown;

        /** Kind to the addresses the child walk would ask for. */
        public final Map<String, List<String>> candidates = new LinkedHashMap<>();

        /** Kind to the candidate addresses the extension already holds. */
        public final Map<String, List<String>> alreadyAttached = new LinkedHashMap<>();

        /** Candidates the platform cannot adopt, each {@code fqn}, {@code kind}, {@code reason}. */
        public final List<Map<String, String>> skipped = new ArrayList<>();

        /** Candidate addresses whose attachment could not be established. */
        public final List<String> attachmentUnknown = new ArrayList<>();

        private Plan(String rootFqn)
        {
            this.rootFqn = rootFqn;
        }
    }

    /**
     * Builds the plan for a root address.
     * <p>
     * The root is observed first. An address the base does not hold is {@link #ABSENT}; an address
     * the platform cannot adopt is {@link #NOT_ADOPTABLE}; otherwise the extension's own state is
     * asked for. The children are walked only when {@code includeChildren} is true and the root
     * was read.
     * </p>
     *
     * @param objectFqn the root address
     * @param includeChildren whether the caller asked for the children
     * @param source where the model is read
     * @return the plan, never <code>null</code>
     */
    public static Plan plan(String objectFqn, boolean includeChildren, Source source)
    {
        Plan plan = new Plan(objectFqn);
        if (objectFqn == null || objectFqn.isEmpty() || source == null)
        {
            plan.rootStatus = UNKNOWN;
            return plan;
        }
        Object root = null;
        Lookup lookup = source.objectAt(objectFqn);
        if (lookup == null || lookup.state == Lookup.State.UNKNOWN
            || (lookup.state == Lookup.State.PRESENT && lookup.object == null))
        {
            // A failed read is not a base that holds nothing. Neither the object nor its absence
            // was established, so the answer must say unknown rather than not found or fresh.
            plan.rootStatus = UNKNOWN;
            plan.sourceUnknown = true;
            return plan;
        }
        if (lookup.state == Lookup.State.ABSENT)
        {
            plan.rootStatus = NOT_ADOPTABLE;
            return plan;
        }
        root = lookup.object;
        plan.rootResolved = true;
        if (isStandardAttribute(objectFqn))
        {
            plan.rootStatus = NOT_ADOPTABLE;
            return plan;
        }
        plan.rootStatus = statusOf(source.attachmentOf(objectFqn));
        if (!includeChildren)
        {
            return plan;
        }
        List<ChildBorrow.Address> planned =
            ChildBorrow.plan(objectFqn, root, true, source.collections());
        for (ChildBorrow.Address address : planned)
        {
            if (address == null || address.fqn == null)
            {
                continue;
            }
            if (ChildBorrow.STANDARD_ATTRIBUTE.equals(address.kind))
            {
                plan.skipped.add(skipped(address));
                continue;
            }
            BmExtensionHelper.Attachment attachment = source.attachmentOf(address.fqn);
            if (attachment == BmExtensionHelper.Attachment.ATTACHED)
            {
                add(plan.alreadyAttached, address.kind, address.fqn);
            }
            else if (attachment == BmExtensionHelper.Attachment.UNKNOWN || attachment == null)
            {
                plan.attachmentUnknown.add(address.fqn);
            }
            else
            {
                add(plan.candidates, address.kind, address.fqn);
            }
        }
        return plan;
    }

    /**
     * The answer the operation returns.
     * <p>
     * The plan is reported as it was read, with the places the caller must not read as promises
     * named beside it. {@code preview} and {@code nothingWasWritten} are stated rather than
     * implied: this document is what a caller decides to write from.
     * </p>
     *
     * @param objectFqn the root address
     * @param includeChildren whether the caller asked for the children
     * @param plan the plan, never <code>null</code>
     * @param source where the model was read
     * @param adoptServiceApi the discovered adopt API class, or <code>null</code>
     * @return the JSON answer
     */
    public static String answer(String objectFqn, boolean includeChildren, Plan plan,
        Source source, String adoptServiceApi)
    {
        boolean available = source != null && source.adoptServiceAvailable();
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("available", available); //$NON-NLS-1$
        if (adoptServiceApi != null && !adoptServiceApi.isEmpty())
        {
            service.put("api", adoptServiceApi); //$NON-NLS-1$
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("fqn", objectFqn); //$NON-NLS-1$
        root.put("status", plan == null ? UNKNOWN : plan.rootStatus); //$NON-NLS-1$

        Map<String, Object> planJson = new LinkedHashMap<>();
        planJson.put("root", root); //$NON-NLS-1$
        planJson.put("candidates", plan == null ? Map.of() : plan.candidates); //$NON-NLS-1$
        planJson.put("skipped", plan == null ? List.of() : plan.skipped); //$NON-NLS-1$
        if (plan != null && !plan.alreadyAttached.isEmpty())
        {
            planJson.put("alreadyAttached", plan.alreadyAttached); //$NON-NLS-1$
        }
        planJson.put("attachmentUnknown", //$NON-NLS-1$
            plan == null ? List.of() : plan.attachmentUnknown);

        return ToolResult.success()
            .put("operation", "preview_borrow") //$NON-NLS-1$ //$NON-NLS-2$
            .put("objectFqn", objectFqn) //$NON-NLS-1$
            .put("includeChildren", includeChildren) //$NON-NLS-1$
            .put("preview", true) //$NON-NLS-1$
            .put("nothingWasWritten", true) //$NON-NLS-1$
            .put("adoptService", service) //$NON-NLS-1$
            .put("plan", planJson) //$NON-NLS-1$
            .put("blockers", blockers(plan, available)) //$NON-NLS-1$
            .put("limits", limits()) //$NON-NLS-1$
            .toJson();
    }

    private static List<String> blockers(Plan plan, boolean adoptServiceAvailable)
    {
        List<String> blockers = new ArrayList<>();
        if (!adoptServiceAvailable)
        {
            blockers.add("adoptServiceNotFound"); //$NON-NLS-1$
        }
        if (plan != null)
        {
            if (plan.sourceUnknown)
            {
                blockers.add("rootSourceUnknown"); //$NON-NLS-1$
            }
            else if (!plan.rootResolved)
            {
                blockers.add("objectNotFound"); //$NON-NLS-1$
            }
            else if (ATTACHED.equals(plan.rootStatus))
            {
                blockers.add("rootAlreadyBorrowed"); //$NON-NLS-1$
            }
            else if (UNKNOWN.equals(plan.rootStatus))
            {
                blockers.add("rootAttachmentUnknown"); //$NON-NLS-1$
            }
            else if (NOT_ADOPTABLE.equals(plan.rootStatus))
            {
                blockers.add("rootNotAdoptable"); //$NON-NLS-1$
            }
            if (!plan.attachmentUnknown.isEmpty())
            {
                blockers.add("childAttachmentUnknown"); //$NON-NLS-1$
            }
        }
        return blockers;
    }

    private static List<String> limits()
    {
        List<String> limits = new ArrayList<>();
        limits.add("candidates are the live model at the moment of the call; the writer may " //$NON-NLS-1$
            + "see a different list"); //$NON-NLS-1$
        limits.add("objects pulled in by an attribute type (pulledByType) cannot be predicted " //$NON-NLS-1$
            + "before the adopt service runs and are not listed"); //$NON-NLS-1$
        limits.add("each child is a separate adopt call; a failed child does not roll back the " //$NON-NLS-1$
            + "children already placed"); //$NON-NLS-1$
        limits.add("module flags and the contents of forms are not part of this plan"); //$NON-NLS-1$
        limits.add("this preview does not lock the project, reserve names or call the adopt " //$NON-NLS-1$
            + "service"); //$NON-NLS-1$
        limits.add("under a write-blocking preset the whole extension_workshop facade, preview " //$NON-NLS-1$
            + "included, is unavailable"); //$NON-NLS-1$
        return limits;
    }

    private static String statusOf(BmExtensionHelper.Attachment attachment)
    {
        if (attachment == BmExtensionHelper.Attachment.ATTACHED)
        {
            return ATTACHED;
        }
        if (attachment == BmExtensionHelper.Attachment.ABSENT)
        {
            return ABSENT;
        }
        return UNKNOWN;
    }

    private static boolean isStandardAttribute(String fqn)
    {
        String upper = fqn.toUpperCase(Locale.ROOT);
        return upper.contains("." + ChildBorrow.STANDARD_ATTRIBUTE.toUpperCase(Locale.ROOT) + ".") //$NON-NLS-1$ //$NON-NLS-2$
            // The same kind under the Russian name a caller may use.
            || upper.contains(".\u0421\u0422\u0410\u041D\u0414\u0410\u0420\u0422\u041D\u042B\u0419" //$NON-NLS-1$
                + "\u0420\u0415\u041A\u0412\u0418\u0417\u0418\u0422."); //$NON-NLS-1$
    }

    private static Map<String, String> skipped(ChildBorrow.Address address)
    {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("fqn", address.fqn); //$NON-NLS-1$
        row.put("kind", address.kind); //$NON-NLS-1$
        row.put("reason", ChildBorrow.NOT_ADOPTABLE); //$NON-NLS-1$
        return row;
    }

    private static void add(Map<String, List<String>> groups, String kind, String fqn)
    {
        if (kind == null || kind.isEmpty())
        {
            return;
        }
        groups.computeIfAbsent(kind, key -> new ArrayList<>()).add(fqn);
    }
}
