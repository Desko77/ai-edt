/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * {@code ownerFqn=CommonTemplate.<Name>} addresses the common template itself.
 * <p>
 * {@code templateName} has to be that name, exact case. A different name is refused before any
 * project is opened. A catalog is not this address.
 * </p>
 */
public class ACommonTemplateIsCreatedAsTheTemplateItselfTest
{
    private static final String NO_SUCH = "AiEdtMxlNoSuchProjectZz"; //$NON-NLS-1$

    private static final String MISMATCH =
        "ownerFqn addresses the common template 'Print', which is the template itself: " //$NON-NLS-1$
            + "pass templateName='Print', got 'Other'."; //$NON-NLS-1$

    @Test
    public void aMatchingCommonTemplateNameIsTheTemplateItself()
    {
        MxlWorkshopTool.CommonTemplateAddress address =
            MxlWorkshopTool.commonTemplateAddress("CommonTemplate.Print", "Print"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(address);
        assertEquals("Print", address.name); //$NON-NLS-1$
        assertTrue(address.nameMatches);
        assertNull(MxlWorkshopTool.commonTemplateNameMismatch("CommonTemplate.Print", "Print")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theNameMatchIsExactCaseAndANestedNameIsStillAnAddress()
    {
        MxlWorkshopTool.CommonTemplateAddress lower =
            MxlWorkshopTool.commonTemplateAddress("CommonTemplate.Print", "print"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(lower);
        assertFalse(lower.nameMatches);
        assertEquals(MISMATCH.replace("Other", "print"), //$NON-NLS-1$ //$NON-NLS-2$
            MxlWorkshopTool.commonTemplateNameMismatch("CommonTemplate.Print", "print")); //$NON-NLS-1$ //$NON-NLS-2$

        MxlWorkshopTool.CommonTemplateAddress nested =
            MxlWorkshopTool.commonTemplateAddress("CommonTemplate.A.B", "A.B"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(nested);
        assertEquals("A.B", nested.name); //$NON-NLS-1$
        assertTrue(nested.nameMatches);
    }

    @Test
    public void anotherOwnerIsNotACommonTemplateAddress()
    {
        assertNull(MxlWorkshopTool.commonTemplateAddress("Catalog.Goods", "Print")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(MxlWorkshopTool.commonTemplateAddress("CommonTemplate.", "Print")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(MxlWorkshopTool.commonTemplateAddress(null, "Print")); //$NON-NLS-1$
        assertNull(MxlWorkshopTool.commonTemplateNameMismatch("Catalog.Goods", "Other")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aNameThatDoesNotMatchIsRefusedBeforeAnyProjectIsOpened()
    {
        String error = errorOf(new MxlWorkshopTool().execute(call("Other"))); //$NON-NLS-1$

        assertTrue(error, error.contains(MISMATCH));
        assertTrue(error, error.contains("create_template failed: ")); //$NON-NLS-1$
        assertFalse(error, error.contains("Project not found")); //$NON-NLS-1$
    }

    @Test
    public void aMatchingNameWithNoProjectIsNotFound()
    {
        String error = errorOf(new MxlWorkshopTool().execute(call("Print"))); //$NON-NLS-1$

        assertTrue(error, error.contains("Project not found: '" + NO_SUCH + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(error, error.contains("pass templateName=")); //$NON-NLS-1$
    }

    @Test
    public void createTemplateAsksReadinessAndAReadDoesNotRefuseOnMismatch()
    {
        assertTrue(MxlWorkshopTool.gatesOnReadiness("create_template")); //$NON-NLS-1$
        assertTrue(MxlWorkshopTool.gatesOnReadiness("set_cell")); //$NON-NLS-1$
        assertFalse(MxlWorkshopTool.gatesOnReadiness("help")); //$NON-NLS-1$
        assertFalse(MxlWorkshopTool.gatesOnReadiness(null));

        String mismatch = "model and file diverge"; //$NON-NLS-1$
        assertEquals(mismatch, MxlWorkshopTool.writeRefusal("set_cell", mismatch)); //$NON-NLS-1$
        assertNull(MxlWorkshopTool.writeRefusal("read_template", mismatch)); //$NON-NLS-1$
        assertNull(MxlWorkshopTool.writeRefusal("list_named_areas", mismatch)); //$NON-NLS-1$
        assertNull(MxlWorkshopTool.writeRefusal("check_print_width", mismatch)); //$NON-NLS-1$
        assertNull(MxlWorkshopTool.writeRefusal("set_cell", null)); //$NON-NLS-1$
    }

    @Test
    public void aCreateObjectAnswerIsReshapedAsCreateTemplate()
    {
        String reshaped = MxlWorkshopTool.reshapeCommonTemplateAnswer(
            "{\"operation\":\"create_object\",\"name\":\"Print\"}", //$NON-NLS-1$
            "CommonTemplate.Print", "Print"); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject object = JsonParser.parseString(reshaped).getAsJsonObject();
        assertEquals("create_template", object.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("CommonTemplate.Print", object.get("ownerFqn").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Print", object.get("templateName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Print", object.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("not-json", //$NON-NLS-1$
            MxlWorkshopTool.reshapeCommonTemplateAnswer("not-json", "CommonTemplate.Print", //$NON-NLS-1$ //$NON-NLS-2$
                "Print")); //$NON-NLS-1$
    }

    private static String errorOf(String answer)
    {
        return JsonParser.parseString(answer).getAsJsonObject().get("error").getAsString(); //$NON-NLS-1$
    }

    private static Map<String, String> call(String templateName)
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "create_template"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", NO_SUCH); //$NON-NLS-1$
        params.put("ownerFqn", "CommonTemplate.Print"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("templateName", templateName); //$NON-NLS-1$
        return params;
    }
}
