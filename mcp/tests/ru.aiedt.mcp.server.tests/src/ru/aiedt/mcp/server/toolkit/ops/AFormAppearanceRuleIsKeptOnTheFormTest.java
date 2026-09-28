/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;

import ru.aiedt.mcp.server.support.BmDcsHelper;

/**
 * A conditional-appearance rule of a managed form: added to the form's conditional appearance with
 * the items it styles, described back, and removed by index or by the field of its condition.
 */
public class AFormAppearanceRuleIsKeptOnTheFormTest
{
    private Form form;

    private DcsWorkshopTool builder;

    /**
     * Builds an empty form in memory.
     */
    @Before
    public void anEmptyForm()
    {
        Assume.assumeTrue("the composition model is not in this runtime", //$NON-NLS-1$
            BmDcsHelper.createElement("createDataCompositionConditionalAppearanceItem") != null); //$NON-NLS-1$
        form = FormFactory.eINSTANCE.createForm();
        builder = new DcsWorkshopTool();
    }

    /**
     * A rule with a condition and an appearance is added with the items it styles and read back
     * with the same parts.
     */
    @Test
    public void aRuleIsAddedAndDescribed()
    {
        DcsWorkshopTool.AppearanceItem built = builder.newAppearanceItem("Объект.Флаг", "Equal", //$NON-NLS-1$ //$NON-NLS-2$
            "true", "TextColor=#FF0000"); //$NON-NLS-1$ //$NON-NLS-2$
        FormAppearanceOps.selectItems(built.item, Arrays.asList("Наименование", "Код")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(0, FormAppearanceOps.addToForm(form, built.item));

        List<Map<String, Object>> rules = FormAppearanceOps.describe(form);
        assertEquals(1, rules.size());
        Map<String, Object> rule = rules.get(0);
        assertEquals(Arrays.asList("Наименование", "Код"), rule.get("itemNames")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<?> condition = (List<?>)rule.get("condition"); //$NON-NLS-1$
        assertEquals(1, condition.size());
        Map<?, ?> comparison = (Map<?, ?>)condition.get(0);
        assertEquals("Объект.Флаг", comparison.get("field")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Equal", String.valueOf(comparison.get("comparisonType"))); //$NON-NLS-1$ //$NON-NLS-2$
        Map<?, ?> appearance = (Map<?, ?>)rule.get("appearance"); //$NON-NLS-1$
        assertTrue(appearance.toString(), appearance.containsKey("ЦветТекста")); //$NON-NLS-1$
    }

    /**
     * A rule without items styles the whole form: its item list is empty.
     */
    @Test
    public void aRuleWithoutItemsStylesTheWholeForm()
    {
        DcsWorkshopTool.AppearanceItem built = builder.newAppearanceItem(null, null, null, "Format=ЧДЦ=2"); //$NON-NLS-1$
        FormAppearanceOps.selectItems(built.item, Collections.emptyList());
        FormAppearanceOps.addToForm(form, built.item);

        Map<String, Object> rule = FormAppearanceOps.describe(form).get(0);
        assertEquals(Collections.emptyList(), rule.get("itemNames")); //$NON-NLS-1$
        assertEquals(Collections.emptyList(), rule.get("condition")); //$NON-NLS-1$
    }

    /**
     * Rules are removed by index, or every rule whose condition reads a field; a wrong index or a
     * field no rule reads is refused.
     */
    @Test
    public void rulesAreRemovedByIndexOrByField()
    {
        FormAppearanceOps.addToForm(form, builder.newAppearanceItem("Объект.Флаг", null, "true", null).item); //$NON-NLS-1$ //$NON-NLS-2$
        FormAppearanceOps.addToForm(form, builder.newAppearanceItem(null, null, null, "Font=Arial,10,bold").item); //$NON-NLS-1$
        FormAppearanceOps.addToForm(form, builder.newAppearanceItem("объект.флаг", null, "false", null).item); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Arrays.asList(0, 2), FormAppearanceOps.removeFromForm(form, null, "Объект.Флаг")); //$NON-NLS-1$
        assertEquals(1, FormAppearanceOps.describe(form).size());

        assertRefused(() -> FormAppearanceOps.removeFromForm(form, Integer.valueOf(5), null));
        assertRefused(() -> FormAppearanceOps.removeFromForm(form, null, "Объект.Нет")); //$NON-NLS-1$

        assertEquals(Arrays.asList(0), FormAppearanceOps.removeFromForm(form, Integer.valueOf(0), null));
        assertEquals(0, FormAppearanceOps.describe(form).size());
    }

    /**
     * Half a condition and a call with nothing to write are refused by the builder the form shares
     * with the composition schema.
     */
    @Test
    public void anIncompleteRuleIsRefused()
    {
        assertRefused(() -> builder.newAppearanceItem("Объект.Флаг", null, null, null)); //$NON-NLS-1$
        assertRefused(() -> builder.newAppearanceItem(null, null, null, null));
    }

    /**
     * Item names are split on commas and trimmed; empty entries are dropped.
     */
    @Test
    public void itemNamesAreSplitOnCommas()
    {
        assertEquals(Arrays.asList("А", "Б"), FormAppearanceOps.splitNames(" А, ,Б ")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Collections.emptyList(), FormAppearanceOps.splitNames(null));
    }

    /**
     * Asserts that a call is refused with a runtime exception.
     *
     * @param call the call
     */
    private static void assertRefused(Runnable call)
    {
        try
        {
            call.run();
            fail("the call was expected to be refused"); //$NON-NLS-1$
        }
        catch (RuntimeException expected)
        {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }
}
