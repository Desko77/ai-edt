package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.*;
import org.junit.Test;
import com._1c.g5.v8.dt.form.model.*;
import ru.aiedt.mcp.server.support.BmFormHelper;

/** A placement refusal must not attach the new item or infer another parent. */
public class AFieldPlacementMustNameASiblingTest
{
    @Test public void nestedSiblingRequiresItsDirectParent() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup group = FormFactory.eINSTANCE.createFormGroup();
        group.setName("Header");
        FormField sibling = FormFactory.eINSTANCE.createFormField();
        sibling.setName("Partner");
        group.getItems().add(sibling);
        form.getItems().add(group);
        FormField added = FormFactory.eINSTANCE.createFormField();
        try
        {
            EditFormTool.placeFormItem(helper, form, added, "Partner");
            fail("a nested sibling must not become an append at root");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Header"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("form root"));
        }
        assertEquals(1, form.getItems().size());
        assertNull(added.eContainer());
        assertNull(EditFormTool.placeFormItem(helper, group, added, "partner"));
        assertSame(added, group.getItems().get(0));
    }

    @Test public void wrongExplicitParentNamesTheActualParentWithoutMovingAnything() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup actual = FormFactory.eINSTANCE.createFormGroup();
        actual.setName("ActualHeader");
        FormGroup requested = FormFactory.eINSTANCE.createFormGroup();
        requested.setName("RequestedFooter");
        FormField sibling = FormFactory.eINSTANCE.createFormField();
        sibling.setName("Partner");
        actual.getItems().add(sibling);
        form.getItems().add(actual);
        form.getItems().add(requested);
        FormField added = FormFactory.eINSTANCE.createFormField();
        try
        {
            EditFormTool.placeFormItem(helper, requested, added, "Partner");
            fail("a wrong explicit parent must refuse instead of appending");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ActualHeader"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("RequestedFooter"));
        }
        assertEquals(2, form.getItems().size());
        assertSame(sibling, actual.getItems().get(0));
        assertTrue(requested.getItems().isEmpty());
        assertNull(added.eContainer());
    }

    @Test public void duplicateDirectSiblingNamesRefuseWithoutPickingTheFirst() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = FormFactory.eINSTANCE.createForm();
        for (String name : new String[] { "Partner", "partner" })
        {
            FormField sibling = FormFactory.eINSTANCE.createFormField();
            sibling.setName(name);
            form.getItems().add(sibling);
        }
        FormField added = FormFactory.eINSTANCE.createFormField();
        try
        {
            EditFormTool.placeFormItem(helper, form, added, "PARTNER");
            fail("ambiguous sibling must not select the first matching item");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("PARTNER"));
        }
        assertEquals(2, form.getItems().size());
        assertNull(added.eContainer());
    }

    @Test public void missingSiblingRefusesWithoutMutation() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = FormFactory.eINSTANCE.createForm();
        FormField added = FormFactory.eINSTANCE.createFormField();
        try
        {
            EditFormTool.placeFormItem(helper, form, added, "Missing");
            fail("missing sibling must refuse");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage().contains("Missing"));
        }
        assertTrue(form.getItems().isEmpty());
        assertNull(added.eContainer());
    }
}
