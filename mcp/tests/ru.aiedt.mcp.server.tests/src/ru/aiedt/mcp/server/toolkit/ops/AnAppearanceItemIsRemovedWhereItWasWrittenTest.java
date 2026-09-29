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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.BmDcsHelper;

/**
 * A conditional appearance is removed from the settings an addition wrote it into.
 * <p>
 * {@code add_conditional_appearance} writes into the settings of the default variant, while
 * {@code remove_conditional_appearance} asked the schema object for a {@code ConditionalAppearance}
 * - a property a schema does not have - so removal with the default {@code target} refused with
 * {@code schema.ConditionalAppearance not available} and nothing could be taken back out. Both
 * target spellings now resolve to those settings, and a variant named there is read from its own
 * settings.
 * </p>
 */
public class AnAppearanceItemIsRemovedWhereItWasWrittenTest
{
    private static final String FIELD = "Контрагент"; //$NON-NLS-1$

    private static final String VALUE = "10"; //$NON-NLS-1$

    private DcsWorkshopTool tool;

    private EObject schema;

    /**
     * Builds an empty composition schema.
     */
    @Before
    public void buildASchema()
    {
        Object built = BmDcsHelper.createElement("createDataCompositionSchema"); //$NON-NLS-1$
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            built instanceof EObject);
        schema = (EObject)built;
        tool = new DcsWorkshopTool();
        // The operation checks the parameter name against the platform's output parameters. The
        // platform's set is built by the platform-version bundles of the EDT installation, which
        // answer only for the versions they carry, so the set the check reads is pinned here to
        // the spelling pair this test writes.
        DcsWorkshopTool.outputParameterNamesForTests =
            Collections.unmodifiableList(Arrays.<String[]>asList(new String[] { "Заголовок", "Title" })); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Puts the output parameter names back the way the production path reads them.
     */
    @After
    public void clearThePinnedNames()
    {
        DcsWorkshopTool.outputParameterNamesForTests = null;
    }

    /**
     * Runs one schema operation.
     *
     * @param op the operation name
     * @param keysAndValues argument names and values, alternating
     * @return what the operation reports
     * @throws Exception if the operation refuses
     */
    private Object run(String op, String... keysAndValues) throws Exception
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2)
        {
            params.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return tool.applyToSchemaForTest(op, params, schema);
    }

    /**
     * The appearance container of the first settings variant.
     *
     * @return the container, or <code>null</code> when the settings carry none
     */
    private Object appearanceContainer()
    {
        EList<EObject> variants = BmDcsHelper.getEObjectList(schema, "getSettingsVariants"); //$NON-NLS-1$
        assertNotNull("the add reached the settings", variants); //$NON-NLS-1$
        assertTrue("the add reached the settings", !variants.isEmpty()); //$NON-NLS-1$
        EObject variant = variants.get(0);
        Object settings = variant.eGet(variant.eClass().getEStructuralFeature("settings")); //$NON-NLS-1$
        assertNotNull("a variant with no settings holds no appearance", settings); //$NON-NLS-1$
        EObject asObject = (EObject)settings;
        return asObject.eGet(asObject.eClass().getEStructuralFeature("conditionalAppearance")); //$NON-NLS-1$
    }

    /**
     * How many appearance items the settings hold.
     *
     * @return the item count, 0 when the settings carry no appearance at all
     */
    private int itemCount()
    {
        Object container = appearanceContainer();
        if (container == null)
        {
            return 0;
        }
        EList<EObject> items = BmDcsHelper.getEObjectList(container, "getItems"); //$NON-NLS-1$
        return items == null ? 0 : items.size();
    }

    /**
     * Removal without {@code target} takes out the item the addition wrote.
     *
     * @throws Exception if either call refuses
     */
    @Test
    public void theDefaultTargetRemovesTheItemTheAddWrote() throws Exception
    {
        run("add_appearance", "field", FIELD, "conditionValue", VALUE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals("the addition wrote one item", 1, itemCount()); //$NON-NLS-1$

        run("remove_conditional_appearance", "index", "0"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("the removal takes back what the addition wrote", 0, itemCount()); //$NON-NLS-1$
    }

    /**
     * The {@code settings} spelling addresses the same container.
     *
     * @throws Exception if either call refuses
     */
    @Test
    public void theSettingsSpellingAddressesTheSameContainer() throws Exception
    {
        run("add_appearance", "field", FIELD, "conditionValue", VALUE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("remove_conditional_appearance", "index", "0", "target", "settings"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals("both spellings mean the settings of the default variant", 0, itemCount()); //$NON-NLS-1$
    }

    /**
     * The variant the addition created can be named, and the item comes out of its settings.
     *
     * @throws Exception if either call refuses
     */
    @Test
    public void anExplicitVariantNameRemovesFromThatVariant() throws Exception
    {
        run("add_appearance", "field", FIELD, "conditionValue", VALUE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        run("remove_conditional_appearance", "index", "0", "target", "Основной"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertEquals("a named variant is read from its own settings", 0, itemCount()); //$NON-NLS-1$
    }

    /**
     * A variant that is not there is refused by name, and the container keeps its item.
     *
     * @throws Exception if the call that should succeed refuses
     */
    @Test
    public void anUnknownVariantIsRefusedByName() throws Exception
    {
        run("add_appearance", "field", FIELD, "conditionValue", VALUE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        try
        {
            run("remove_conditional_appearance", "index", "0", "target", "НетТакого"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            fail("a variant that is not in the schema has no settings to remove from"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String message = String.valueOf(e.getMessage());
            assertTrue("the refusal names the variant: " + message, //$NON-NLS-1$
                message.contains("НетТакого")); //$NON-NLS-1$
            assertTrue("and it is a variant that was looked up and not found, rather than a " //$NON-NLS-1$
                + "container that happened to be empty: " + message, //$NON-NLS-1$
                message.contains("settingsVariant not found")); //$NON-NLS-1$
        }

        assertEquals("and nothing was taken out of the settings that do exist", 1, itemCount()); //$NON-NLS-1$
    }

    /**
     * Removal from settings that never carried an appearance refuses, and leaves no container
     * behind: a call that removed nothing must not add one.
     *
     * @throws Exception if the call that should succeed refuses
     */
    @Test
    public void removalFromSettingsWithoutAnAppearanceLeavesNoContainer() throws Exception
    {
        // Another settings operation opens the default variant and its settings, and puts no
        // appearance container on them - the shape a schema has before an appearance is added.
        run("set_output_parameter", "name", "Заголовок", "value", "Отчет"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertEquals("these settings carry no appearance yet", null, appearanceContainer()); //$NON-NLS-1$

        try
        {
            run("remove_conditional_appearance", "index", "0"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("there is no appearance item to remove"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            assertTrue("the refusal says the container is empty: " + e.getMessage(), //$NON-NLS-1$
                String.valueOf(e.getMessage()).contains("nothing to remove")); //$NON-NLS-1$
        }

        assertEquals("a removal that removed nothing adds no container", null, //$NON-NLS-1$
            appearanceContainer());
    }
}
