/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.BmDcsHelper;

/**
 * A grouping nested inside another.
 * <p>
 * Every grouping went to {@code settings.getItems()}, so a second call put a second grouping beside
 * the first rather than inside it, and a hierarchy could only be built by moving the XML by hand.
 * {@code parentPath} names the group to go inside.
 * </p>
 */
public class AGroupingGoesInsideAnotherTest
{
    private DcsWorkshopTool tool;
    private EObject schema;

    @Before
    public void buildASchema()
    {
        Object built = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            built instanceof EObject);
        schema = (EObject)built;
        tool = new DcsWorkshopTool();
    }

    private Object run(String op, String... keysAndValues) throws Exception
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2)
        {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return tool.applyToSchemaForTest(op, params, schema);
    }

    /** The structure items of the first settings variant. */
    private EList<EObject> rootStructure()
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        assertNotNull("the schema should have a variant by now", variants); //$NON-NLS-1$
        EObject variant = variants.get(0);
        Object settings = variant.eGet(variant.eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
        assertNotNull("a variant with no settings holds no structure", settings); //$NON-NLS-1$
        return BmDcsHelper.getEObjectList(settings, "getItems"); //$NON-NLS-1$
    }

    private static EList<EObject> inside(EObject group)
    {
        return BmDcsHelper.getEObjectList(group, "getItems"); //$NON-NLS-1$
    }

    private static String nameOf(EObject group)
    {
        Object name = group.eGet(group.eClass().getEStructuralFeature("name")); //$NON-NLS-1$
        return name == null ? "" : String.valueOf(name); //$NON-NLS-1$
    }

    @Test
    public void withoutAPathAGroupingStillGoesToTheRoot() throws Exception
    {
        run("add_grouping", "name", "Объект", "field", "ОбъектСтроительства"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_grouping", "name", "Статья", "field", "СтатьяБюджетов"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals("two groupings with no path are two groupings at the root", //$NON-NLS-1$
            2, rootStructure().size());
    }

    @Test
    public void aPathPutsTheGroupingInsideTheOneItNames() throws Exception
    {
        run("add_grouping", "name", "Объект", "field", "ОбъектСтроительства"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("add_grouping", "name", "Статья", "field", "СтатьяБюджетов", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "parentPath", "Объект"); //$NON-NLS-1$ //$NON-NLS-2$

        EList<EObject> root = rootStructure();
        assertEquals("the second grouping is not a second root", 1, root.size()); //$NON-NLS-1$
        EList<EObject> children = inside(root.get(0));
        assertEquals("it is inside the first", 1, children.size()); //$NON-NLS-1$
        assertEquals("and it is the one that was added", "Статья", nameOf(children.get(0))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aPathOfTwoStepsReachesTheThirdLevel() throws Exception
    {
        run("add_grouping", "name", "Объект", "field", "ОбъектСтроительства"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        run("add_grouping", "name", "Статья", "field", "СтатьяБюджетов", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "parentPath", "Объект"); //$NON-NLS-1$ //$NON-NLS-2$

        run("add_grouping", "name", "Период", "field", "Период", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "parentPath", "Объект.Статья"); //$NON-NLS-1$ //$NON-NLS-2$

        EObject object = rootStructure().get(0);
        EObject article = inside(object).get(0);
        assertEquals("the third grouping goes under the second", 1, inside(article).size()); //$NON-NLS-1$
        assertEquals("Период", nameOf(inside(article).get(0))); //$NON-NLS-1$
    }

    @Test
    public void aPathThatNamesNothingIsRefusedAndWhatIsThereGetsNamed() throws Exception
    {
        run("add_grouping", "name", "Объект", "field", "ОбъектСтроительства"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            run("add_grouping", "name", "Статья", "field", "СтатьяБюджетов", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "parentPath", "Склад"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("a grouping added inside a group that is not there lands somewhere unasked for"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String message = String.valueOf(e.getMessage());
            assertTrue("the refusal names the groups that are there: " + message, //$NON-NLS-1$
                message.contains("Объект")); //$NON-NLS-1$
        }
    }

    @Test
    public void aPathThatEndsInASeparatorIsRefusedRatherThanTrimmed() throws Exception
    {
        run("add_grouping", "name", "Объект", "field", "ОбъектСтроительства"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            run("add_grouping", "name", "Статья", "field", "СтатьяБюджетов", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "parentPath", "Объект."); //$NON-NLS-1$ //$NON-NLS-2$
            fail("a path ending in a separator names a step that is not there"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            assertTrue("the refusal says which path was empty: " + e.getMessage(), //$NON-NLS-1$
                String.valueOf(e.getMessage()).contains("empty step")); //$NON-NLS-1$
        }
    }

    @Test
    public void spaceAroundAStepIsIgnored() throws Exception
    {
        run("add_grouping", "name", "Объект", "field", "ОбъектСтроительства"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("add_grouping", "name", "Статья", "field", "СтатьяБюджетов", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "parentPath", " Объект "); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("a path typed with spaces addresses the same group", //$NON-NLS-1$
            1, inside(rootStructure().get(0)).size());
    }

    @Test
    public void anUnnamedGroupIsReachedByItsPosition() throws Exception
    {
        run("add_grouping", "field", "ОбъектСтроительства"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        run("add_grouping", "name", "Статья", "field", "СтатьяБюджетов", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "parentPath", "[0]"); //$NON-NLS-1$ //$NON-NLS-2$

        EList<EObject> root = rootStructure();
        assertEquals("a group with no name is still a group to nest inside", 1, root.size()); //$NON-NLS-1$
        assertEquals(1, inside(root.get(0)).size());
    }
}
