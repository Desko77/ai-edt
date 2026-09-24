/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The child walk, planned over a tree this test owns.
 * <p>
 * No configuration project and no adopt service: the plan is which addresses would be borrowed,
 * and the groups are what the answer says about each of them.
 * </p>
 */
public class ChildBorrowPlanTest
{
    /** A named object and the collections a walk reads off it. */
    public static final class Node
    {
        private final String name;

        private final Map<String, List<Node>> byKind = new LinkedHashMap<>();

        Node(String name)
        {
            this.name = name;
        }

        public String getName()
        {
            return name;
        }

        Node child(String kind, String childName)
        {
            Node created = new Node(childName);
            byKind.computeIfAbsent(kind, key -> new ArrayList<>()).add(created);
            return created;
        }
    }

    private static ChildBorrow.ChildCollections collections()
    {
        return (owner, kind) -> {
            if (!(owner instanceof Node))
            {
                return List.of();
            }
            List<Node> children = ((Node)owner).byKind.get(kind);
            return children == null ? List.of() : children;
        };
    }

    private static List<String> fqns(List<ChildBorrow.Address> plan)
    {
        List<String> names = new ArrayList<>();
        for (ChildBorrow.Address address : plan)
        {
            names.add(address.fqn);
        }
        return names;
    }

    /** Absent and false borrow nothing beyond the root, and the collections are not read. */
    @Test
    public void omittingTheFlagPlansNothing()
    {
        Node goods = new Node("Goods");
        goods.child("Attribute", "Code");
        ChildBorrow.ChildCollections failing = (owner, kind) -> {
            throw new AssertionError("a walk that was not asked for read " + kind);
        };

        assertTrue(ChildBorrow.plan("Catalog.Goods", goods, false, failing).isEmpty());
        assertTrue(ChildBorrow.plan("Catalog.Goods", null, true, failing).isEmpty());
        assertTrue(ChildBorrow.plan(null, goods, true, failing).isEmpty());
    }

    /** An attribute of a tabular section is a step, named under the section. */
    @Test
    public void aTabularSectionAttributeIsInThePlan()
    {
        Node goods = new Node("Goods");
        goods.child("Attribute", "Code");
        Node lines = goods.child("TabularSection", "Lines");
        lines.child("Attribute", "Qty");

        List<String> planned = fqns(ChildBorrow.plan("Catalog.Goods", goods, true, collections()));

        assertTrue(planned.toString(), planned.contains("Catalog.Goods.Attribute.Code"));
        assertTrue(planned.toString(), planned.contains("Catalog.Goods.TabularSection.Lines"));
        assertTrue(planned.toString(),
            planned.contains("Catalog.Goods.TabularSection.Lines.Attribute.Qty"));
        assertFalse(planned.contains("Catalog.Goods"));
    }

    /** A subsystem inside a subsystem is walked. A module is not a kind the walk has. */
    @Test
    public void aNestedSubsystemIsInThePlan()
    {
        Node sales = new Node("Sales");
        Node orders = sales.child("Subsystem", "Orders");
        orders.child("Subsystem", "Retail");

        List<String> planned = fqns(ChildBorrow.plan("Subsystem.Sales", sales, true, collections()));

        assertTrue(planned.contains("Subsystem.Sales.Subsystem.Orders"));
        assertTrue(planned.contains("Subsystem.Sales.Subsystem.Orders.Subsystem.Retail"));
        assertFalse(BmObjectHelper.childCollectionKinds().contains("Module"));
        assertFalse(BmObjectHelper.childCollectionKinds().contains("Predefined"));
    }

    /** The one list of kinds is the list the walk uses, and a standard attribute is on it. */
    @Test
    public void theChildKindsAreTheOneList()
    {
        List<String> kinds = BmObjectHelper.childCollectionKinds();

        assertEquals(17, kinds.size());
        assertTrue(kinds.contains("StandardAttribute"));
        assertTrue(kinds.contains("URLTemplate"));
        for (String kind : kinds)
        {
            assertNotNull(kind, BmObjectHelper.childKindGetter(kind));
        }
    }

    /** A standard attribute is skipped and the borrow is not asked. The others are placed. */
    @Test
    public void aStandardAttributeIsSkippedAndNotBorrowed()
    {
        Node goods = new Node("Goods");
        goods.child("Attribute", "Code");
        goods.child("StandardAttribute", "Description");
        List<ChildBorrow.Address> plan = ChildBorrow.plan("Catalog.Goods", goods, true, collections());
        int[] calls = new int[1];

        ChildBorrow.Outcome outcome = ChildBorrow.run(plan, fqn -> {
            calls[0]++;
            assertFalse(fqn.contains("StandardAttribute"));
            return ChildBorrow.Attempted.placed();
        });

        assertEquals(1, calls[0]);
        assertEquals(List.of("Catalog.Goods.Attribute.Code"), outcome.children.get("Attribute"));
        assertEquals(1, outcome.skipped.size());
        assertEquals("Catalog.Goods.StandardAttribute.Description", outcome.skipped.get(0).get("fqn"));
        assertEquals("StandardAttribute", outcome.skipped.get(0).get("kind"));
        assertEquals(ChildBorrow.NOT_ADOPTABLE, outcome.skipped.get(0).get("reason"));
    }

    /** A second top object is not a child, even when this test is holding it. */
    @Test
    public void aReferenceTargetIsNotAChild()
    {
        Node goods = new Node("Goods");
        goods.child("Attribute", "Currency");
        Node currencies = new Node("Currencies");
        currencies.child("Attribute", "Code");

        List<String> planned = fqns(ChildBorrow.plan("Catalog.Goods", goods, true, collections()));

        assertTrue(planned.contains("Catalog.Goods.Attribute.Currency"));
        for (String fqn : planned)
        {
            assertFalse(fqn, fqn.startsWith("Catalog.Currencies"));
        }
        assertFalse(planned.contains("Catalog.Currencies.Attribute.Code"));
    }

    /** Placed, already there, and refused stay in three different groups. */
    @Test
    public void threeOutcomesStayApart()
    {
        Node goods = new Node("Goods");
        goods.child("Attribute", "Code");
        goods.child("Attribute", "Name");
        goods.child("Form", "Item");
        List<ChildBorrow.Address> plan = ChildBorrow.plan("Catalog.Goods", goods, true, collections());

        ChildBorrow.Outcome outcome = ChildBorrow.run(plan, fqn -> {
            if (fqn.endsWith(".Code"))
            {
                return ChildBorrow.Attempted.placed();
            }
            if (fqn.endsWith(".Name"))
            {
                return ChildBorrow.Attempted.alreadyThere();
            }
            return ChildBorrow.Attempted.failed("not found");
        });

        assertEquals(List.of("Catalog.Goods.Attribute.Code"), outcome.children.get("Attribute"));
        assertEquals(List.of("Catalog.Goods.Attribute.Name"), outcome.alreadyBorrowed.get("Attribute"));
        assertEquals("not found", outcome.skipped.get(0).get("reason"));
        assertEquals("Catalog.Goods.Form.Item", outcome.skipped.get(0).get("fqn"));
        assertFalse(outcome.children.containsKey("Form"));
    }

    /** A root that is already borrowed is still a success, and the new child is in children. */
    @Test
    public void anAlreadyBorrowedRootStillReportsTheNewChild()
    {
        BmExtensionHelper.BorrowResult root = new BmExtensionHelper.BorrowResult();
        root.ok = true;
        root.alreadyBorrowed = true;
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("targetFqn", "Catalog.Goods");
        tag.put("extensionLinkage", "already in place - nothing to write");
        root.tags.put("alreadyBorrowed", tag);
        ChildBorrow.Outcome outcome = new ChildBorrow.Outcome();
        outcome.children.computeIfAbsent("Attribute", key -> new ArrayList<>())
            .add("Catalog.Goods.Attribute.Code");
        outcome.pulledByType = List.of();

        JsonObject json = JsonParser.parseString(
            ChildBorrow.answer("borrow_object", "Catalog.Goods", root, outcome)).getAsJsonObject();

        assertTrue(json.get("success").getAsBoolean());
        assertEquals("already borrowed", json.get("message").getAsString());
        assertEquals("Catalog.Goods.Attribute.Code",
            json.getAsJsonObject("children").getAsJsonArray("Attribute").get(0).getAsString());
        assertFalse(json.has("error"));
        assertTrue(json.getAsJsonArray("pulledByType").isEmpty());
    }

    /** When a child was already there too, the grouping takes the name and the link stays visible. */
    @Test
    public void anAlreadyBorrowedChildKeepsTheLinkageBesideTheGrouping()
    {
        BmExtensionHelper.BorrowResult root = new BmExtensionHelper.BorrowResult();
        root.ok = true;
        root.alreadyBorrowed = true;
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("targetFqn", "Catalog.Goods");
        tag.put("extensionLinkage", "already in place - nothing to write");
        root.tags.put("alreadyBorrowed", tag);
        ChildBorrow.Outcome outcome = new ChildBorrow.Outcome();
        outcome.alreadyBorrowed.computeIfAbsent("Attribute", key -> new ArrayList<>())
            .add("Catalog.Goods.Attribute.Name");
        outcome.pulledByType = List.of();

        JsonObject json = JsonParser.parseString(
            ChildBorrow.answer("borrow_object", "Catalog.Goods", root, outcome)).getAsJsonObject();

        assertEquals("Catalog.Goods.Attribute.Name",
            json.getAsJsonObject("alreadyBorrowed").getAsJsonArray("Attribute").get(0).getAsString());
        assertEquals("already in place - nothing to write", json.get("extensionLinkage").getAsString());
        assertFalse(json.getAsJsonObject("alreadyBorrowed").has("targetFqn"));
    }

    /** A snapshot that cannot be taken is named. An empty one is written as an empty list. */
    @Test
    public void anUnreadableSnapshotIsNamedAndAnEmptyPullIsWritten()
    {
        ChildBorrow.Outcome unread = new ChildBorrow.Outcome();
        ChildBorrow.notePulledByType(unread, "Catalog.Goods", List.of(), null, null);
        JsonObject unverified = JsonParser.parseString(
            ChildBorrow.answer("borrow_object", "Catalog.Goods", borrowed(), unread)).getAsJsonObject();

        assertTrue(unverified.get("pulledByTypeUnverified").getAsString().length() > 0);
        assertFalse(unverified.has("pulledByType"));

        ChildBorrow.Outcome none = new ChildBorrow.Outcome();
        Set<String> tops = new LinkedHashSet<>();
        tops.add("Catalog.Goods");
        ChildBorrow.notePulledByType(none, "Catalog.Goods", List.of(), tops, tops);
        JsonObject empty = JsonParser.parseString(
            ChildBorrow.answer("borrow_object", "Catalog.Goods", borrowed(), none)).getAsJsonObject();

        assertTrue(empty.getAsJsonArray("pulledByType").isEmpty());
        assertFalse(empty.has("pulledByTypeUnverified"));
    }

    /** A top object that appeared, and was not requested, is the only pulled name. */
    @Test
    public void aPulledTopObjectIsNotOneOfTheChildren()
    {
        ChildBorrow.Address form = new ChildBorrow.Address("Catalog.Goods.Form.Item", "Form");
        ChildBorrow.Outcome outcome = new ChildBorrow.Outcome();
        Set<String> before = new LinkedHashSet<>();
        before.add("Catalog.Goods");
        Set<String> after = new LinkedHashSet<>();
        after.add("Catalog.Goods");
        after.add("Catalog.Currencies");
        after.add("Catalog.Goods.Form.Item.Form");

        ChildBorrow.notePulledByType(outcome, "Catalog.Goods", List.of(form), before, after);

        assertEquals(List.of("Catalog.Currencies"), outcome.pulledByType);
    }

    private static BmExtensionHelper.BorrowResult borrowed()
    {
        BmExtensionHelper.BorrowResult root = new BmExtensionHelper.BorrowResult();
        root.ok = true;
        return root;
    }
}
