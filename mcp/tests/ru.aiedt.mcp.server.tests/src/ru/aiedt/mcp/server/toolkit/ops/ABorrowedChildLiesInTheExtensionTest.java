/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.BmExtensionHelper;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.ChildBorrow;

/**
 * The children a real metadata object owns are the ones the walk asks to place.
 * <p>
 * Headless CI has no configuration project to adopt into, which is the same limit the other borrow
 * tests live with: they assert the address, not a live {@code adoptAndAttach}. Here the extension
 * is the set of addresses the walk hands to the borrow. A second catalog, present in the same
 * factory and not a containment child, is not among them.
 * </p>
 */
public class ABorrowedChildLiesInTheExtensionTest
{
    @SuppressWarnings("unchecked")
    private static MdObject add(MdObject owner, String kind, String name)
    {
        MdObject child = BmObjectHelper.createOwnerScopedObject(owner, kind);
        assertNotNull(kind + " was not created on " + owner.eClass().getName(), child);
        child.setName(name);
        EList<EObject> list = (EList<EObject>)BmObjectHelper.getChildListByKind(owner, kind);
        assertNotNull("no " + kind + " collection on " + owner.eClass().getName(), list);
        list.add(child);
        return child;
    }

    @SuppressWarnings("unchecked")
    private static boolean addStandardAttribute(Catalog catalog)
    {
        EList<? extends EObject> list = BmObjectHelper.getChildListByKind(catalog, "StandardAttribute");
        if (list == null)
        {
            return false;
        }
        try
        {
            Object factory = MdClassFactory.eINSTANCE;
            Method create = factory.getClass().getMethod("createStandardAttribute");
            Object attribute = create.invoke(factory);
            attribute.getClass().getMethod("setName", String.class).invoke(attribute, "Description");
            ((EList<EObject>)list).add((EObject)attribute);
            return true;
        }
        catch (ReflectiveOperationException | ClassCastException notThisBuild)
        {
            return false;
        }
    }

    /**
     * The owned children, including an attribute of a tabular section, are what the extension
     * receives. The other catalog is not.
     */
    @Test
    public void theOwnedChildrenAreTheOnesAskedFor()
    {
        Catalog goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Goods");
        add(goods, "Attribute", "Code");
        MdObject lines = add(goods, "TabularSection", "Lines");
        add(lines, "Attribute", "Qty");
        add(goods, "Form", "ItemForm");
        add(goods, "Template", "Print");
        add(goods, "Command", "Open");
        boolean standard = addStandardAttribute(goods);

        Catalog currencies = MdClassFactory.eINSTANCE.createCatalog();
        currencies.setName("Currencies");
        add(currencies, "Attribute", "Code");

        Set<String> extension = new LinkedHashSet<>();
        List<ChildBorrow.Address> plan =
            ChildBorrow.plan("Catalog.Goods", goods, true, ChildBorrow.fromTheModel());
        ChildBorrow.Outcome outcome = ChildBorrow.run(plan, fqn -> {
            extension.add(fqn);
            return ChildBorrow.Attempted.placed();
        });
        Set<String> before = new LinkedHashSet<>();
        before.add("Catalog.Goods");
        Set<String> after = new LinkedHashSet<>();
        after.add("Catalog.Goods");
        after.add("Catalog.Currencies");
        ChildBorrow.notePulledByType(outcome, "Catalog.Goods", plan, before, after);

        assertTrue(extension.toString(), extension.contains("Catalog.Goods.Attribute.Code"));
        assertTrue(extension.toString(), extension.contains("Catalog.Goods.TabularSection.Lines"));
        assertTrue(extension.toString(),
            extension.contains("Catalog.Goods.TabularSection.Lines.Attribute.Qty"));
        assertTrue(extension.toString(), extension.contains("Catalog.Goods.Form.ItemForm"));
        assertTrue(extension.toString(), extension.contains("Catalog.Goods.Template.Print"));
        assertTrue(extension.toString(), extension.contains("Catalog.Goods.Command.Open"));
        assertFalse(extension.contains("Catalog.Currencies"));
        assertFalse(extension.contains("Catalog.Currencies.Attribute.Code"));
        assertEquals(List.of("Catalog.Currencies"), outcome.pulledByType);
        if (standard)
        {
            assertFalse(extension.toString(), extension.stream().anyMatch(fqn -> fqn.contains("StandardAttribute")));
            assertEquals(ChildBorrow.NOT_ADOPTABLE, outcome.skipped.get(0).get("reason"));
        }
    }

    /** A value that is not a boolean is refused by name, and the project is not looked up. */
    @Test
    public void aBadIncludeChildrenIsRefusedBeforeTheProject()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "borrow_object");
        params.put("projectName", "NoSuchProjectAtAll");
        params.put("objectFqn", "Catalog.Goods");
        params.put("includeChildren", "maybe");

        JsonObject json = JsonParser.parseString(new ExtensionWorkshopTool().execute(params)).getAsJsonObject();

        assertFalse(json.get("success").getAsBoolean());
        assertTrue(json.get("error").getAsString(), json.get("error").getAsString().contains("includeChildren"));
        assertFalse(json.get("error").getAsString().contains("NoSuchProjectAtAll"));
        assertFalse(json.has("children"));
    }

    /** Omitted and false are the same answer, and it carries none of the child fields. */
    @Test
    public void omittedAndFalseBorrowTheSameAndNameNoChildren()
    {
        Map<String, String> omitted = call("borrow_object");
        Map<String, String> off = call("borrow_object");
        off.put("includeChildren", "false");
        ExtensionWorkshopTool tool = new ExtensionWorkshopTool();

        String without = tool.execute(omitted);
        String disabled = tool.execute(off);

        assertEquals(without, disabled);
        assertTrue(without, without.contains("not found"));
        assertFalse(without.contains("\"children\""));
        assertFalse(without.contains("pulledByType"));
        assertFalse(without.contains("\"skipped\""));
        assertFalse(without.contains("includeChildren"));
    }

    /** A missing project with the flag on is not found, and no child field is invented. */
    @Test
    public void includeChildrenOnAMissingProjectNamesNotFoundAndNoChildren()
    {
        Map<String, String> params = call("borrow_object");
        params.put("includeChildren", "true");

        String json = new ExtensionWorkshopTool().execute(params);

        assertTrue(json, json.contains("not found"));
        assertFalse(json.contains("\"children\""));
        assertFalse(json.contains("includeChildren"));
        assertFalse(json.contains("pulledByType"));
    }

    /** The schema keeps one sentence. The rules are what help says. */
    @Test
    public void theRulesLiveInHelpAndNotInTheSchema()
    {
        ExtensionWorkshopTool tool = new ExtensionWorkshopTool();
        String schema = tool.getInputSchema();

        assertTrue(schema.contains("includeChildren"));
        assertFalse(schema.contains("notAdoptable"));
        assertFalse(schema.contains("pulledByType"));

        String borrowObject = tool.execute(help("borrow_object"));
        assertTrue(borrowObject, borrowObject.contains("notAdoptable"));
        assertTrue(borrowObject, borrowObject.contains("includeChildren"));

        String borrowChild = tool.execute(help("borrow_child"));
        assertTrue(borrowChild, borrowChild.contains("does not read it"));
        assertFalse(borrowChild.contains("Unknown topic"));
    }

    /** A base object that cannot be read is not reported as an object that had no children. */
    @Test
    public void anUnreadableBaseDoesNotPretendTheChildrenWereWalked() throws Exception
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("AiEdtIncludeChildrenProbe");
        if (project.exists())
        {
            project.delete(true, true, null);
        }
        project.create(null);
        project.open(null);
        try
        {
            BmExtensionHelper.BorrowResult root = new BmExtensionHelper.BorrowResult();
            root.ok = true;
            int[] calls = new int[1];
            String json = ChildBorrow.finish("borrow_object", "Catalog.Goods", root, project,
                project.getName(), fqn -> {
                    calls[0]++;
                    return ChildBorrow.Attempted.placed();
                });
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();

            assertEquals(0, calls[0]);
            assertTrue(parsed.has("pulledByTypeUnverified"));
            assertFalse(parsed.has("children"));
            assertTrue(parsed.get("includeChildren").getAsBoolean());
        }
        finally
        {
            if (project.exists())
            {
                project.delete(true, true, null);
            }
        }
    }

    private static Map<String, String> call(String operation)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", operation);
        params.put("projectName", "NoSuchProjectAtAll");
        params.put("objectFqn", "Catalog.Goods");
        return params;
    }

    private static Map<String, String> help(String topic)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "help");
        params.put("topic", topic);
        return params;
    }
}
