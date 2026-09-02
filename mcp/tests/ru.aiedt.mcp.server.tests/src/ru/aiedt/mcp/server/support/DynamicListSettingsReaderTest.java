/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;

/**
 * Reading a dynamic list's settings back.
 * <p>
 * Built from the composition factory, which needs neither a workspace nor a project, so what the
 * reader does with a real settings object is settled by the build rather than by a stand.
 * </p>
 * <p>
 * One branch is deliberately not tested here: a section whose container carries no item list. Every
 * section of this model does carry one, and the model refuses an object of another type in its
 * place, so the case cannot be built. It is guarded in the reader against a runtime shaped
 * differently, and a test that could only be skipped would show coverage where there is none.
 * </p>
 */
public class DynamicListSettingsReaderTest
{
    private EObject settings;

    @Before
    public void buildSettings()
    {
        Object built = BmDcsHelper.createElement("createDataCompositionSettings"); //$NON-NLS-1$
        Assume.assumeTrue("the composition model is not in this runtime", built instanceof EObject); //$NON-NLS-1$
        settings = (EObject)built;
    }

    private static void set(EObject on, String feature, Object value)
    {
        EStructuralFeature found = on.eClass().getEStructuralFeature(feature);
        Assume.assumeTrue("this runtime's model has no " + feature, found != null); //$NON-NLS-1$
        on.eSet(found, value);
    }

    @SuppressWarnings("unchecked")
    private static void add(EObject on, String feature, Object value)
    {
        EStructuralFeature found = on.eClass().getEStructuralFeature(feature);
        Assume.assumeTrue("this runtime's model has no " + feature, found != null); //$NON-NLS-1$
        ((java.util.List<Object>)on.eGet(found)).add(value);
    }

    @Test
    public void nothingToReadIsSaidRatherThanShownAsEmpty()
    {
        JsonObject read = DynamicListSettingsReader.read(null);

        assertFalse("no settings is not the same as settings with nothing in them", //$NON-NLS-1$
            read.get("settingsRead").getAsBoolean()); //$NON-NLS-1$
        assertTrue(read.has("why")); //$NON-NLS-1$
    }

    @Test
    public void freshSettingsReadAsReadWithEmptySections()
    {
        JsonObject read = DynamicListSettingsReader.read(settings);

        assertTrue("the object is there and was read", //$NON-NLS-1$
            read.get("settingsRead").getAsBoolean()); //$NON-NLS-1$
        assertTrue("a section this runtime has is reported even when it holds nothing", //$NON-NLS-1$
            read.has("order") || read.has("filter")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anOrderItemIsReportedByTheWordsAddOrderUses()
    {
        Object order = BmDcsHelper.createElement("createDataCompositionOrder"); //$NON-NLS-1$
        Object item = BmDcsHelper.createElement("createDataCompositionOrderItem"); //$NON-NLS-1$
        Object field = BmDcsHelper.createElement("createDataCompositionField"); //$NON-NLS-1$
        Assume.assumeTrue("this runtime cannot build an order item", //$NON-NLS-1$
            order instanceof EObject && item instanceof EObject && field instanceof EObject);
        set((EObject)field, "value", "Номер"); //$NON-NLS-1$ //$NON-NLS-2$
        set((EObject)item, "field", field); //$NON-NLS-1$
        add((EObject)order, "items", item); //$NON-NLS-1$
        set(settings, "order", order); //$NON-NLS-1$

        JsonObject read = DynamicListSettingsReader.read(settings);

        assertEquals("one item was put there and one comes back", //$NON-NLS-1$
            1, read.getAsJsonArray("order").size()); //$NON-NLS-1$
        JsonObject reported = read.getAsJsonArray("order").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("add_order names it field, so reading names it field", //$NON-NLS-1$
            "Номер", reported.get("field").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the item says what kind it is", reported.has("kind")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anEnumerationIsReportedByItsLiteralName()
    {
        Object order = BmDcsHelper.createElement("createDataCompositionOrder"); //$NON-NLS-1$
        Object item = BmDcsHelper.createElement("createDataCompositionOrderItem"); //$NON-NLS-1$
        Assume.assumeTrue("this runtime cannot build an order item", //$NON-NLS-1$
            order instanceof EObject && item instanceof EObject);
        EStructuralFeature direction = ((EObject)item).eClass().getEStructuralFeature("orderType"); //$NON-NLS-1$
        Assume.assumeTrue("this runtime's order item has no orderType", direction != null); //$NON-NLS-1$
        add((EObject)order, "items", item); //$NON-NLS-1$
        set(settings, "order", order); //$NON-NLS-1$

        JsonObject read = DynamicListSettingsReader.read(settings);
        JsonObject reported = read.getAsJsonArray("order").get(0).getAsJsonObject(); //$NON-NLS-1$

        if (reported.has("direction")) //$NON-NLS-1$
        {
            String reportedDirection = reported.get("direction").getAsString(); //$NON-NLS-1$
            assertFalse("an enumeration rendered by toString carries its class and its ordinal, " //$NON-NLS-1$
                + "and that shape has reached a written file here before: " + reportedDirection, //$NON-NLS-1$
                reportedDirection.contains("@") || reportedDirection.contains("(")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void aGroupingReportsTheFieldItGroupsBy()
    {
        Object group = BmDcsHelper.createElement("createDataCompositionGroup"); //$NON-NLS-1$
        Object fields = BmDcsHelper.createElement("createDataCompositionGroupFields"); //$NON-NLS-1$
        Object groupField = BmDcsHelper.createElement("createDataCompositionGroupField"); //$NON-NLS-1$
        Object field = BmDcsHelper.createElement("createDataCompositionField"); //$NON-NLS-1$
        Assume.assumeTrue("this runtime cannot build a grouping", group instanceof EObject //$NON-NLS-1$
            && fields instanceof EObject && groupField instanceof EObject && field instanceof EObject);
        set((EObject)field, "value", "Контрагент"); //$NON-NLS-1$ //$NON-NLS-2$
        set((EObject)groupField, "field", field); //$NON-NLS-1$
        add((EObject)fields, "items", groupField); //$NON-NLS-1$
        set((EObject)group, "groupFields", fields); //$NON-NLS-1$
        add(settings, "items", group); //$NON-NLS-1$

        JsonObject read = DynamicListSettingsReader.read(settings);

        JsonObject reported = read.getAsJsonArray("structure").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertTrue("one item list holds groups, tables and charts - the kind has to be said", //$NON-NLS-1$
            reported.get("kind").getAsString().contains("Group")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("add_grouping writes the field one level down, so reading goes down after it", //$NON-NLS-1$
            reported.has("groupFields")); //$NON-NLS-1$
        assertEquals("Контрагент", reported.getAsJsonArray("groupFields").get(0).getAsJsonObject() //$NON-NLS-1$
            .get("field").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void aGroupingKeepsItsFieldWhenItAlsoHasASelection()
    {
        Object group = BmDcsHelper.createElement("createDataCompositionGroup"); //$NON-NLS-1$
        Object fields = BmDcsHelper.createElement("createDataCompositionGroupFields"); //$NON-NLS-1$
        Object groupField = BmDcsHelper.createElement("createDataCompositionGroupField"); //$NON-NLS-1$
        Object field = BmDcsHelper.createElement("createDataCompositionField"); //$NON-NLS-1$
        Object selection = BmDcsHelper.createElement("createDataCompositionSelectedFields"); //$NON-NLS-1$
        Object selected = BmDcsHelper.createElement("createDataCompositionSelectedField"); //$NON-NLS-1$
        Assume.assumeTrue("this runtime cannot build a grouping with a selection", //$NON-NLS-1$
            group instanceof EObject && fields instanceof EObject && groupField instanceof EObject
                && field instanceof EObject && selection instanceof EObject
                && selected instanceof EObject);
        set((EObject)field, "value", "Склад"); //$NON-NLS-1$ //$NON-NLS-2$
        set((EObject)groupField, "field", field); //$NON-NLS-1$
        add((EObject)fields, "items", groupField); //$NON-NLS-1$
        set((EObject)group, "groupFields", fields); //$NON-NLS-1$
        add((EObject)selection, "items", selected); //$NON-NLS-1$
        set((EObject)group, "selection", selection); //$NON-NLS-1$
        add(settings, "items", group); //$NON-NLS-1$

        JsonObject read = DynamicListSettingsReader.read(settings);
        JsonObject reported = read.getAsJsonArray("structure").get(0).getAsJsonObject(); //$NON-NLS-1$

        assertTrue("a group has both, and one name for both loses whichever comes second", //$NON-NLS-1$
            reported.has("groupFields") && reported.has("selection")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Склад", reported.getAsJsonArray("groupFields").get(0).getAsJsonObject() //$NON-NLS-1$ //$NON-NLS-2$
            .get("field").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void aCallerCanSayWhyThereAreNoSettings()
    {
        JsonObject read = DynamicListSettingsReader.read(null, "this model has no such feature"); //$NON-NLS-1$

        assertFalse(read.get("settingsRead").getAsBoolean()); //$NON-NLS-1$
        assertEquals("a missing feature is not a list without settings", //$NON-NLS-1$
            "this model has no such feature", read.get("why").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void somethingThatIsNotAModelObjectIsRefusedRatherThanRead()
    {
        JsonObject read = DynamicListSettingsReader.read("not a settings object"); //$NON-NLS-1$

        assertFalse(read.get("settingsRead").getAsBoolean()); //$NON-NLS-1$
        assertTrue(read.get("why").getAsString().contains("not a model object")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
