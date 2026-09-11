/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The arguments the repair and the import read are the arguments their schemas declare.
 * <p>
 * A schema is the only place a client learns an argument from; a strict client does not send what
 * is not declared, and an argument read from nowhere is an argument nobody can pass.
 * </p>
 */
public class TheRepairAndImportArgumentsAreDeclaredTest
{
    @Test
    public void theWorkshopDeclaresOverwriteModel()
    {
        JsonObject property = property(new DcsWorkshopTool().getInputSchema(), "overwriteModel"); //$NON-NLS-1$
        assertEquals("boolean", property.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(property.get("description").getAsString().contains("repair_schema")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theFacadeDeclaresOverwriteModel()
    {
        JsonObject property = property(new EditMetadataTool().getInputSchema(), "overwriteModel"); //$NON-NLS-1$
        assertEquals("boolean", property.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(property.get("description").getAsString().contains("repair_report_schema")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theImportDeclaresApplicationId()
    {
        JsonObject property = property(new ExternalObjectWorkshopTool().getInputSchema(), "applicationId"); //$NON-NLS-1$
        assertEquals("string", property.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(property.get("description").getAsString().contains("import_external_object")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static JsonObject property(String schema, String name)
    {
        JsonObject properties = JsonParser.parseString(schema).getAsJsonObject().getAsJsonObject("properties"); //$NON-NLS-1$
        assertTrue(name + " is declared", properties.has(name)); //$NON-NLS-1$
        return properties.getAsJsonObject(name);
    }
}
