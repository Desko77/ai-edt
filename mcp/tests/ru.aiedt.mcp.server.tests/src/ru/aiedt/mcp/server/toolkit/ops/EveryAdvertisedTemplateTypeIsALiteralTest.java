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

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.BmTemplateHelper;

/**
 * Whether every template type the schema advertises is one this EDT model has.
 * <p>
 * The description is what a caller builds its call from, so a value in it is a promise that the
 * call works. Two of them were not one: {@code GraphicalScheme} and {@code Geographical Schema}
 * name no literal of {@code TemplateType}, so the reflective setter answered an error that
 * add_template logged and dropped - the template was created with no type at all while the answer
 * named the type that was asked for. Measured against EDT 2025.2.3: the enum holds
 * {@code GraphicalSchema} and {@code GeographicalSchema}, and both advertised spellings answer
 * {@code null} to {@code getByName}.
 * </p>
 */
public class EveryAdvertisedTemplateTypeIsALiteralTest
{
    /** Where the description starts naming the types it accepts. */
    private static final String LISTED_AFTER = "addTemplate: "; //$NON-NLS-1$

    @Test
    public void everyTypeTheDescriptionNamesIsOneTheModelHas()
    {
        List<String> advertised = advertisedTemplateTypes();
        assertFalse("the description has to name the types it accepts", advertised.isEmpty()); //$NON-NLS-1$
        for (String value : advertised)
        {
            String canonical = BmTemplateHelper.canonicalTemplateType(value);
            assertNotNull("the description advertises '" + value + "', and no TemplateType literal " //$NON-NLS-1$ //$NON-NLS-2$
                + "answers to it; the model has: " + BmTemplateHelper.templateTypeValues(), //$NON-NLS-1$
                BmTemplateHelper.resolveTemplateTypeLiteral(canonical));
        }
    }

    @Test
    public void everyLiteralTheModelHasIsAcceptedAsWritten()
    {
        String[] literals = BmTemplateHelper.templateTypeValues().split(", "); //$NON-NLS-1$
        assertTrue("the model has template types", literals.length > 1); //$NON-NLS-1$
        for (String literal : literals)
        {
            assertEquals(literal, BmTemplateHelper.resolveTemplateTypeLiteral(literal));
        }
    }

    @Test
    public void theTwoSpellingsThatWereAdvertisedAreSpelledTheWayTheModelSpellsThem()
    {
        // Kept as aliases, so a caller that learned them from an older description still gets the
        // type it meant rather than a refusal.
        assertEquals("GraphicalSchema", BmTemplateHelper.canonicalTemplateType("GraphicalScheme")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("GeographicalSchema", //$NON-NLS-1$
            BmTemplateHelper.canonicalTemplateType("Geographical Schema")); //$NON-NLS-1$

        // The canonical form is what the model answers to - the alias is not a literal itself.
        assertNotNull(BmTemplateHelper.resolveTemplateTypeLiteral(
            BmTemplateHelper.canonicalTemplateType("GraphicalScheme"))); //$NON-NLS-1$
    }

    @Test
    public void aTypeTheModelDoesNotHaveIsRefused()
    {
        assertNull(BmTemplateHelper.resolveTemplateTypeLiteral("GraphicalScheme")); //$NON-NLS-1$
        assertNull(BmTemplateHelper.resolveTemplateTypeLiteral("NotATemplateType")); //$NON-NLS-1$
        assertNull(BmTemplateHelper.resolveTemplateTypeLiteral("")); //$NON-NLS-1$
        assertNull(BmTemplateHelper.resolveTemplateTypeLiteral(null));
    }

    /**
     * The values the templateType description lists, read out of the schema the tool publishes.
     *
     * @return the values, in the order the description has them
     */
    private static List<String> advertisedTemplateTypes()
    {
        JsonObject schema =
            JsonParser.parseString(new EditMetadataTool().getInputSchema()).getAsJsonObject();
        String description = schema.getAsJsonObject("properties").getAsJsonObject("templateType") //$NON-NLS-1$ //$NON-NLS-2$
            .get("description").getAsString(); //$NON-NLS-1$
        int listed = description.indexOf(LISTED_AFTER);
        assertTrue("the templateType description must list its values after '" + LISTED_AFTER //$NON-NLS-1$ //$NON-NLS-2$
            + "': " + description, listed >= 0); //$NON-NLS-1$
        String values = description.substring(listed + LISTED_AFTER.length());
        if (values.endsWith(".")) //$NON-NLS-1$
        {
            values = values.substring(0, values.length() - 1);
        }
        List<String> advertised = new ArrayList<>();
        for (String value : values.split("/")) //$NON-NLS-1$
        {
            advertised.add(value.trim());
        }
        return advertised;
    }
}
