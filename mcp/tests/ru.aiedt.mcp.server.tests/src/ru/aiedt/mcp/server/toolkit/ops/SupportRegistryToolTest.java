/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmSupportRegistryHelper;
import ru.aiedt.mcp.server.support.SupportSnapshot;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Contract of the vendor-support reader.
 * <p>
 * Everything asserted here holds without a workspace: the argument handling, the refusals, and the
 * promises the description and schema make to a client. What the tool reports about a real
 * configuration needs an open project and is checked on a stand instead.
 * </p>
 */
public class SupportRegistryToolTest
{
    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    public void itIsNamedAndReadOnlyInItsDescription()
    {
        SupportRegistryTool tool = new SupportRegistryTool();
        assertEquals("support_registry", tool.getName());
        assertEquals(IMcpTool.ResponseType.JSON, tool.getResponseType());
        String description = tool.getDescription();
        assertTrue("the one operation that writes has to be named as such: a support mode decides "
            + "what a vendor update may overwrite, and a caller reading this description is how "
            + "the difference between reporting and writing gets noticed",
            description.contains("restore_modes"));
        assertTrue("and it has to say that writing needs asking for - the default reports and "
            + "changes nothing", description.contains("apply=true"));
    }

    @Test
    public void theDescriptionSeparatesDeclarationFromMeasurement()
    {
        // The single most misleading reading of this tool is that CHANGES_ALLOWED means somebody
        // changed the object. It does not, and the description is where a client learns that.
        String description = new SupportRegistryTool().getDescription();
        assertTrue("the description must say a mode is what was declared, not whether the object "
            + "was modified", description.contains("not whether the object was modified"));
        assertTrue("and it must name the tool that answers the other question",
            description.contains("compare_three_way"));
    }

    @Test
    public void missingOperationIsRefusedWithTheChoices()
    {
        String answer = new SupportRegistryTool().execute(args());
        assertTrue(answer.contains("operation is required"));
        assertTrue("a refusal that does not list the choices makes the caller guess",
            answer.contains("status") && answer.contains("list_objects")
                && answer.contains("object_mode"));
    }

    @Test
    public void anUnknownOperationIsRefusedRatherThanIgnored()
    {
        String answer = new SupportRegistryTool().execute(args("operation", "set_mode"));
        assertTrue(answer.contains("Unknown operation"));
        assertTrue(answer.contains("set_mode"));
    }

    @Test
    public void camelCaseOperationsAreAccepted()
    {
        // listObjects and list_objects must not be two different answers.
        String answer = new SupportRegistryTool().execute(args("operation", "listObjects"));
        assertFalse("camelCase must resolve to the canonical operation, not be rejected as unknown",
            answer.contains("Unknown operation"));
    }

    @Test
    public void helpListsEveryOperation()
    {
        String help = new SupportRegistryTool().execute(args("operation", "help"));
        assertTrue(help.contains("status"));
        assertTrue(help.contains("list_objects"));
        assertTrue(help.contains("object_mode"));
    }

    @Test
    public void helpExplainsWhatTheModesMean()
    {
        String help = new SupportRegistryTool().execute(args("operation", "help", "topic", "modes"));
        assertTrue(help.contains("CHANGES_NOT_ALLOWED"));
        assertTrue(help.contains("CHANGES_ALLOWED"));
        assertTrue(help.contains("CANCELLED"));
        assertTrue("the modes topic must say the configuration root has to be open first, which is "
            + "the rule people hit before any other", help.contains("Configuration"));
    }

    @Test
    public void anUnknownHelpTopicNamesTheAvailableOnes()
    {
        String help = new SupportRegistryTool().execute(args("operation", "help", "topic", "sql"));
        assertTrue(help.contains("Unknown topic"));
        assertTrue(help.contains("modes"));
        assertTrue(help.contains("workflow"));
    }

    @Test
    public void theSchemaDeclaresEveryParameterTheToolReads()
    {
        // A parameter the tool reads but does not declare is unreachable: the schema is the only
        // place a client learns it exists.
        String schema = new SupportRegistryTool().getInputSchema();
        for (String declared : new String[] {"operation", "topic", "projectName", "objectFqn",
            "userMode", "parentId", "offset", "limit"})
        {
            assertTrue(declared + " is read by execute() but missing from the schema",
                schema.contains('"' + declared + '"'));
        }
    }

    @Test
    public void theSchemaExplainsWhyAVendorHasToBeNamed()
    {
        // A configuration can sit on several supports at once, and then a mode belongs to a pair of
        // object and vendor. The schema has to say so, or the argument reads like noise.
        String schema = new SupportRegistryTool().getInputSchema();
        assertTrue(schema.contains("more than one"));
    }

    @Test
    public void aRestoreThatFoundDriftAndWroteNothingWarns()
    {
        // apply=true over a drift where nothing could be written used to close with the all-clear
        // sentence - "no mode needed putting back" - over the drift it had just reported, because
        // every object it tried had been refused or was missing.
        BmSupportRegistryHelper.Restore restore = new BmSupportRegistryHelper.Restore();
        restore.restored = 0;
        SupportSnapshot.Drift drift = new SupportSnapshot.Drift();
        drift.changed.add("00000000-0000-0000-0000-000000000001: ChangesAllowed -> "
            + "ChangesNotAllowed");
        restore.drift = drift;

        String note = SupportRegistryTool.restoreNote(true, restore);

        assertTrue("the note has to name what stands between the caller and a finished restore: "
            + note, note.contains("refused"));
        assertTrue("the note has to say how many objects lost their mode: " + note,
            note.contains("1 object(s)"));
        assertTrue("the all-clear sentence belongs to a clean restore, and this one found drift: "
            + note, !note.contains("no mode needed putting back"));
    }

    @Test
    public void aCleanAppliedRestoreThatWroteNothingSaysSo()
    {
        BmSupportRegistryHelper.Restore restore = new BmSupportRegistryHelper.Restore();
        restore.restored = 0;
        restore.drift = new SupportSnapshot.Drift();

        String note = SupportRegistryTool.restoreNote(true, restore);

        assertTrue("a clean restore with nothing to write is the one case the all-clear sentence"
            + " describes: " + note, note.contains("no mode needed putting back"));
    }

    @Test
    public void aDryRunSaysItWroteNothing()
    {
        BmSupportRegistryHelper.Restore restore = new BmSupportRegistryHelper.Restore();
        restore.drift = new SupportSnapshot.Drift();

        String note = SupportRegistryTool.restoreNote(false, restore);

        assertTrue(note, note.contains("pass apply=true"));
    }
}
