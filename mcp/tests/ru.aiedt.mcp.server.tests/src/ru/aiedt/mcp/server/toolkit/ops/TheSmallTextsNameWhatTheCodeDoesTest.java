/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonParser;
import com._1c.g5.v8.dt.moxel.content.ContentFactory;
import com._1c.g5.v8.dt.moxel.content.LocalString;

import ru.aiedt.mcp.server.support.RoleRightsAnalyzer;

/**
 * Descriptions name the forms of a call that work, a rights table has a column only for a right the
 * role states, and an appearance text reads by language.
 */
public class TheSmallTextsNameWhatTheCodeDoesTest
{
    /**
     * A value carrying another object, as an mcore value carries its value.
     */
    public static final class Carrier
    {
        private final Object value;

        /**
         * @param value what the carrier carries
         */
        Carrier(Object value)
        {
            this.value = value;
        }

        /**
         * @return what the carrier carries
         */
        public Object getValue()
        {
            return value;
        }
    }

    /** Called directly, sync_control takes the inner action in operation, and says so. */
    @Test
    public void syncControlNamesBothForms()
    {
        String description = new SyncControlTool().getDescription();
        assertTrue(description, description.contains("this tool takes it in `operation`")); //$NON-NLS-1$
        assertTrue(description, description.contains("Through the facade the inner action goes in `syncOperation`")); //$NON-NLS-1$
    }

    /** The dump-info fix named by update_database is the call that runs. */
    @Test
    public void theDumpInfoFixIsNamedInFull()
    {
        String description = JsonParser.parseString(new DatabaseUpdater().getInputSchema())
            .getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("ignoreDumpInfoFormat") //$NON-NLS-1$ //$NON-NLS-2$
            .get("description").getAsString(); //$NON-NLS-1$
        assertTrue(description, description.endsWith(
            "infobase_admin operation=sync_control syncOperation=rebuild_dump_info.")); //$NON-NLS-1$
    }

    /** config_io lists unpack_external_binary in its description and in its refusal. */
    @Test
    public void configIoListsEveryOperation()
    {
        ConfigIoFacadeTool tool = new ConfigIoFacadeTool();
        assertTrue(tool.getDescription().contains("unpack_external_binary")); //$NON-NLS-1$
        String refusal = tool.execute(Collections.emptyMap());
        assertTrue(refusal, refusal.contains("unpack_external_binary")); //$NON-NLS-1$
    }

    /** A right no object states has no column; a stated one has. */
    @Test
    public void aRightsTableShowsOnlyTheRightsTheRoleStates()
    {
        RoleRightsAnalyzer.RightsTable table = new RoleRightsAnalyzer.RightsTable("Manager"); //$NON-NLS-1$
        Map<String, RoleRightsAnalyzer.Verdict> goods = new LinkedHashMap<>();
        goods.put("Read", RoleRightsAnalyzer.Verdict.ALLOW); //$NON-NLS-1$
        goods.put("Update", RoleRightsAnalyzer.Verdict.UNSPECIFIED); //$NON-NLS-1$
        table.rights.put("Catalog.Goods", goods); //$NON-NLS-1$
        Map<String, RoleRightsAnalyzer.Verdict> orders = new LinkedHashMap<>();
        orders.put("Delete", RoleRightsAnalyzer.Verdict.DENY); //$NON-NLS-1$
        table.rights.put("Document.Order", orders); //$NON-NLS-1$

        String markdown = AuditRoleRightsTool.renderRightsMarkdown(table, false);

        String header = markdown.split("\n")[2]; //$NON-NLS-1$
        assertEquals("| Object | Read | Delete | ", header); //$NON-NLS-1$
        assertFalse(markdown, markdown.contains("Update")); //$NON-NLS-1$
        assertTrue(markdown, markdown.contains("| Catalog.Goods | + |   |")); //$NON-NLS-1$
        assertTrue(markdown, markdown.contains("| Document.Order |   | - |")); //$NON-NLS-1$
    }

    /** A localized appearance text reads as its texts by language. */
    @Test
    public void aLocalizedAppearanceTextReadsByLanguage()
    {
        LocalString text = ContentFactory.eINSTANCE.createLocalString();
        text.getContent().put("ru", "Нет данных"); //$NON-NLS-1$ //$NON-NLS-2$
        text.getContent().put("en", "No data"); //$NON-NLS-1$ //$NON-NLS-2$

        Object read = FormAppearanceOps.appearanceValue(new Carrier(text));

        assertTrue(String.valueOf(read), read instanceof Map);
        Map<?, ?> byLanguage = (Map<?, ?>)read;
        assertEquals("Нет данных", byLanguage.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("No data", byLanguage.get("en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Any other carried value reads as its text, as before. */
    @Test
    public void anotherValueReadsAsItsText()
    {
        assertEquals("42", FormAppearanceOps.appearanceValue(new Carrier(Integer.valueOf(42)))); //$NON-NLS-1$
    }
}
