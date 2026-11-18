/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The read-only borrow preview tells a failed source read from a known absence, keeps an already
 * attached or unreadable child out of the fresh candidates, and has no path that writes.
 * <p>
 * The test source is a plain object tree. {@link ChildBorrow#plan} walks it through the same
 * collections method the production source supplies, so the planner is exercised without a
 * configuration project.
 * </p>
 */
public class BorrowPreviewTest
{
    private static final String ROOT = "Catalog.Goods"; //$NON-NLS-1$

    private static final String FRESH = "Catalog.Goods.Attribute.Code"; //$NON-NLS-1$

    private static final String ATTACHED = "Catalog.Goods.Attribute.Price"; //$NON-NLS-1$

    private static final String UNKNOWN = "Catalog.Goods.Attribute.Stock"; //$NON-NLS-1$

    /**
     * A metadata-shaped object the walk can name. Public because {@link ChildBorrow#plan} reads
     * {@code getName} reflectively, and a non-public class would make that call inaccessible.
     */
    public static class Node
    {
        private final String name;

        private final List<Object> attributes = new ArrayList<>();

        /**
         * @param name the object's name
         */
        public Node(String name)
        {
            this.name = name;
        }

        /**
         * @return the name the walk puts into the address
         */
        public String getName()
        {
            return name;
        }
    }

    /** The source the planner is allowed to read. Every method here is a read. */
    private static final class FakeSource implements BorrowPreview.Source
    {
        private final Node root;

        private final Map<String, BorrowPreview.Lookup> lookups = new LinkedHashMap<>();

        private final Map<String, BmExtensionHelper.Attachment> attachments = new LinkedHashMap<>();

        private final List<String> objectReads = new ArrayList<>();

        private final List<String> attachmentReads = new ArrayList<>();

        private boolean adoptServiceAvailable = true;

        FakeSource(Node root)
        {
            this.root = root;
        }

        @Override
        public BorrowPreview.Lookup objectAt(String fqn)
        {
            objectReads.add(fqn);
            BorrowPreview.Lookup found = lookups.get(fqn);
            return found == null ? BorrowPreview.Lookup.absent() : found;
        }

        @Override
        public BmExtensionHelper.Attachment attachmentOf(String fqn)
        {
            attachmentReads.add(fqn);
            BmExtensionHelper.Attachment found = attachments.get(fqn);
            return found == null ? BmExtensionHelper.Attachment.ABSENT : found;
        }

        @Override
        public boolean adoptServiceAvailable()
        {
            return adoptServiceAvailable;
        }

        @Override
        public ChildBorrow.ChildCollections collections()
        {
            return (owner, kind) -> "Attribute".equals(kind) && owner instanceof Node //$NON-NLS-1$
                ? ((Node)owner).attributes : List.of();
        }
    }

    private static FakeSource source(Node root, BorrowPreview.Lookup rootLookup,
        BmExtensionHelper.Attachment rootAttachment)
    {
        FakeSource source = new FakeSource(root);
        source.lookups.put(ROOT, rootLookup);
        source.attachments.put(ROOT, rootAttachment);
        return source;
    }

    private static JsonObject json(String answer)
    {
        return JsonParser.parseString(answer).getAsJsonObject();
    }

    private static Set<String> blockers(JsonObject answer)
    {
        Set<String> names = new LinkedHashSet<>();
        JsonArray array = answer.getAsJsonArray("blockers"); //$NON-NLS-1$
        if (array != null)
        {
            for (JsonElement element : array)
            {
                names.add(element.getAsString());
            }
        }
        return names;
    }

    private static Set<String> flatten(Map<String, List<String>> groups)
    {
        Set<String> names = new LinkedHashSet<>();
        for (List<String> group : groups.values())
        {
            names.addAll(group);
        }
        return names;
    }

    private static Set<String> flatten(JsonObject groups)
    {
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> group : groups.entrySet())
        {
            for (JsonElement element : group.getValue().getAsJsonArray())
            {
                names.add(element.getAsString());
            }
        }
        return names;
    }

    private static List<String> strings(JsonArray array)
    {
        List<String> names = new ArrayList<>();
        for (JsonElement element : array)
        {
            names.add(element.getAsString());
        }
        return names;
    }

    /** A failed read is not a read that found nothing; the answer says which happened. */
    @Test
    public void aFailedBaseReadIsNotAKnownAbsence()
    {
        Node root = new Node("Goods"); //$NON-NLS-1$
        FakeSource failedRead = source(root, BorrowPreview.Lookup.unknown(),
            BmExtensionHelper.Attachment.ABSENT);
        BorrowPreview.Plan failed = BorrowPreview.plan(ROOT, false, failedRead);
        assertEquals(BorrowPreview.UNKNOWN, failed.rootStatus);
        assertTrue(failed.sourceUnknown);
        assertFalse(failed.rootResolved);

        FakeSource readIt = source(root, BorrowPreview.Lookup.absent(),
            BmExtensionHelper.Attachment.ABSENT);
        BorrowPreview.Plan absent = BorrowPreview.plan(ROOT, false, readIt);
        assertEquals(BorrowPreview.NOT_ADOPTABLE, absent.rootStatus);
        assertFalse(absent.sourceUnknown);
        assertFalse(absent.rootResolved);

        JsonObject failedAnswer = json(BorrowPreview.answer(ROOT, false, failed, failedRead, null));
        assertTrue(blockers(failedAnswer).contains("rootSourceUnknown")); //$NON-NLS-1$
        assertFalse(blockers(failedAnswer).contains("objectNotFound")); //$NON-NLS-1$

        JsonObject absentAnswer = json(BorrowPreview.answer(ROOT, false, absent, readIt, null));
        assertTrue(blockers(absentAnswer).contains("objectNotFound")); //$NON-NLS-1$
        assertFalse(blockers(absentAnswer).contains("rootSourceUnknown")); //$NON-NLS-1$
    }

    /** An attached root and a root whose attachment cannot be read are both not fresh. */
    @Test
    public void anAttachedOrUnknownRootIsNotReportedFresh()
    {
        Node root = new Node("Goods"); //$NON-NLS-1$
        FakeSource attachedSource = source(root, BorrowPreview.Lookup.present(root),
            BmExtensionHelper.Attachment.ATTACHED);
        BorrowPreview.Plan attached = BorrowPreview.plan(ROOT, false, attachedSource);
        assertEquals(BorrowPreview.ATTACHED, attached.rootStatus);
        assertFalse(attached.sourceUnknown);
        JsonObject attachedAnswer = json(BorrowPreview.answer(ROOT, false, attached,
            attachedSource, null));
        assertTrue(blockers(attachedAnswer).contains("rootAlreadyBorrowed")); //$NON-NLS-1$

        FakeSource unknownSource = source(root, BorrowPreview.Lookup.present(root),
            BmExtensionHelper.Attachment.UNKNOWN);
        BorrowPreview.Plan unknown = BorrowPreview.plan(ROOT, false, unknownSource);
        assertEquals(BorrowPreview.UNKNOWN, unknown.rootStatus);
        assertFalse(unknown.sourceUnknown);
        JsonObject unknownAnswer = json(BorrowPreview.answer(ROOT, false, unknown, unknownSource,
            null));
        assertTrue(blockers(unknownAnswer).contains("rootAttachmentUnknown")); //$NON-NLS-1$
        assertFalse(blockers(unknownAnswer).contains("objectNotFound")); //$NON-NLS-1$
    }

    /** Only the fresh child is a candidate; attached and unknown children are reported apart. */
    @Test
    public void attachedAndUnknownChildrenAreNotPresentedAsFresh()
    {
        Node root = new Node("Goods"); //$NON-NLS-1$
        root.attributes.add(new Node("Code")); //$NON-NLS-1$
        root.attributes.add(new Node("Price")); //$NON-NLS-1$
        root.attributes.add(new Node("Stock")); //$NON-NLS-1$
        FakeSource source = source(root, BorrowPreview.Lookup.present(root),
            BmExtensionHelper.Attachment.ABSENT);
        source.attachments.put(FRESH, BmExtensionHelper.Attachment.ABSENT);
        source.attachments.put(ATTACHED, BmExtensionHelper.Attachment.ATTACHED);
        source.attachments.put(UNKNOWN, BmExtensionHelper.Attachment.UNKNOWN);

        BorrowPreview.Plan plan = BorrowPreview.plan(ROOT, true, source);
        assertEquals(Set.of(FRESH), flatten(plan.candidates));
        assertEquals(Set.of(ATTACHED), flatten(plan.alreadyAttached));
        assertEquals(List.of(UNKNOWN), plan.attachmentUnknown);

        JsonObject answer = json(BorrowPreview.answer(ROOT, true, plan, source, null));
        JsonObject planJson = answer.getAsJsonObject("plan"); //$NON-NLS-1$
        assertEquals(Set.of(FRESH), flatten(planJson.getAsJsonObject("candidates"))); //$NON-NLS-1$
        assertEquals(Set.of(ATTACHED),
            flatten(planJson.getAsJsonObject("alreadyAttached"))); //$NON-NLS-1$
        assertEquals(List.of(UNKNOWN), strings(planJson.getAsJsonArray("attachmentUnknown"))); //$NON-NLS-1$
    }

    /** The source interface itself carries no method that could write. */
    @Test
    public void theSourceContractCarriesNoWritingMethod()
    {
        Set<String> methods = new TreeSet<>();
        for (Method method : BorrowPreview.Source.class.getDeclaredMethods())
        {
            methods.add(method.getName());
        }
        assertEquals(Set.of("objectAt", "attachmentOf", "adoptServiceAvailable", "collections"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            methods);
    }

    /** Planning and answering do not change the objects they read, and the answer says so. */
    @Test
    public void planningAndAnsweringDoNotChangeTheObjectsTheyRead()
    {
        Node root = new Node("Goods"); //$NON-NLS-1$
        Node code = new Node("Code"); //$NON-NLS-1$
        root.attributes.add(code);
        FakeSource source = source(root, BorrowPreview.Lookup.present(root),
            BmExtensionHelper.Attachment.ABSENT);
        source.attachments.put(FRESH, BmExtensionHelper.Attachment.ABSENT);
        List<Object> before = new ArrayList<>(root.attributes);

        BorrowPreview.Plan plan = BorrowPreview.plan(ROOT, true, source);
        JsonObject answer = json(BorrowPreview.answer(ROOT, true, plan, source, null));

        assertEquals(before, root.attributes);
        assertSame(code, root.attributes.get(0));
        assertEquals(List.of(ROOT), source.objectReads);
        assertEquals(Arrays.asList(ROOT, FRESH), source.attachmentReads);
        assertTrue(answer.get("nothingWasWritten").getAsBoolean()); //$NON-NLS-1$
    }
}
