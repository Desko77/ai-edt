/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import ru.aiedt.mcp.server.settings.ToolCategory;
import ru.aiedt.mcp.server.settings.ToolProfile;

/**
 * Holds the presets that promise not to write to that promise.
 * <p>
 * {@code ToolCategoryCoverageTest} checks that every tool belongs to a group, which is what makes a
 * tool switchable at all. It says nothing about whether the right ones are switched off, and that is
 * the half that failed: {@code extension_lifecycle} borrows an object into an extension and appends a
 * handler stub, but it lives in the agent-composites group, which Read-only, Debug &amp; Test and Code
 * Review all leave enabled. Two of its three siblings were named individually in those presets and it
 * was not, so all three presets wrote through it - and through both routes, since the
 * {@code extension_workshop} operation of the same name was the one case in that facade with no gate
 * around it.
 * </p>
 * <p>
 * The list below is deliberately written out here rather than read from {@code ToolProfile}: a test
 * that asks the code under test what it considers a writer would agree with it by construction.
 * </p>
 */
public class PresetWriteBlockingTest
{
    /**
     * Tools that change sources, metadata or an infobase and do not sit in a group that a
     * write-blocking preset switches off wholesale. Every preset that claims to block writing has to
     * name each of them.
     */
    private static final List<String> COMPOSITE_WRITERS =
        Arrays.asList("write_module_source", "generate_event_handlers", "extension_lifecycle",
            "project_admin", "infobase_admin", "config_io");

    /** Agent composites that only read or only return text, and may stay on under any preset. */
    private static final List<String> NON_WRITING_COMPOSITES =
        Arrays.asList("generate_health_snapshot");

    /** The presets whose description promises that nothing writes. */
    private static final List<ToolProfile> WRITE_BLOCKING =
        Arrays.asList(ToolProfile.READ_ONLY, ToolProfile.DEBUG_AND_TEST, ToolProfile.CODE_REVIEW);

    @Test
    public void everyWriteBlockingPresetDisablesEveryCompositeWriter()
    {
        List<String> escapes = new ArrayList<>();
        for (ToolProfile preset : WRITE_BLOCKING)
        {
            Set<String> disabled = preset.getDisabledTools();
            for (String writer : COMPOSITE_WRITERS)
            {
                if (!disabled.contains(writer))
                {
                    escapes.add(preset.name() + " leaves " + writer + " enabled");
                }
            }
        }
        escapes.sort(null);

        assertTrue("These presets promise not to write and then leave a writing tool enabled. A tool "
            + "that writes from a group the preset keeps on has to be named in the preset by hand - "
            + "see ToolProfile.writersOutsideWriteGroups: " + escapes, escapes.isEmpty());
    }

    @Test
    public void readOnlyDisablesEveryToolThatCanReachTheModel()
    {
        Set<String> disabled = ToolProfile.READ_ONLY.getDisabledTools();
        List<String> enabledWriters = new ArrayList<>();
        for (ToolCategory group : Arrays.asList(ToolCategory.REFACTORING, ToolCategory.CONSTRUCTORS,
            ToolCategory.APPLICATIONS, ToolCategory.DEBUG))
        {
            for (String name : group.getToolNames())
            {
                if (!disabled.contains(name))
                {
                    enabledWriters.add(name + " (" + group.name() + ")");
                }
            }
        }
        enabledWriters.sort(null);

        assertTrue("Read-only means look, do not touch. Every member of the editing, building, "
            + "infobase and debug groups has to be off under it: " + enabledWriters,
            enabledWriters.isEmpty());
    }

    @Test
    public void everyAgentCompositeIsEitherDisabledOrDeclaredNonWriting()
    {
        Set<String> disabled = ToolProfile.READ_ONLY.getDisabledTools();
        List<String> unclassified = new ArrayList<>();
        for (String name : ToolCategory.AI_HELPERS.getToolNames())
        {
            if (!disabled.contains(name) && !NON_WRITING_COMPOSITES.contains(name))
            {
                unclassified.add(name);
            }
        }
        unclassified.sort(null);

        assertTrue("A composite reaches other tools as Java calls, which never pass the router where "
            + "the preset is enforced - so a writing composite has to be disabled by name, and a "
            + "reading one has to say so here. These are neither: " + unclassified,
            unclassified.isEmpty());
    }
}
