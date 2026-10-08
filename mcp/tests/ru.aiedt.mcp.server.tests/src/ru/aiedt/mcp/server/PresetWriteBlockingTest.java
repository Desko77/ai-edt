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
     * Tools that change sources, metadata, an infobase or the repository and do not sit in a group
     * that a write-blocking preset switches off wholesale. Every preset that claims to block writing
     * has to name each of them.
     * <p>
     * The git doors are here on the same terms: they live in the VCS group, which all three
     * presets keep on because the reads of that facade belong to them - so a commit, a checkout, a
     * file put back and a merge restore point taken or dropped each have to be named by hand, or
     * the preset writes through the one group it left open.
     * </p>
     * <p>
     * The {@code *_writes} names are the constructor doors: capability names, registered nowhere,
     * that each constructor facade asks about before its first write. Code Review keeps the
     * constructors group on so a review can read what a constructor would produce, which is why
     * the writes of each facade have to be named here by hand.
     * </p>
     */
    private static final List<String> COMPOSITE_WRITERS =
        Arrays.asList("write_module_source", "generate_event_handlers", "extension_lifecycle",
            "naparnik", "project_admin", "infobase_admin", "config_io", "git_commit", "git_checkout",
            "git_revert_file", "git_create_merge_restore_point", "git_delete_merge_restore_point",
            "create_cluster", "update_cluster", "delete_cluster",
            "add_to_cluster", "remove_from_cluster",
            "create_tag", "update_tag", "delete_tag", "assign_tag", "unassign_tag",
            "edit_form_writes", "edit_metadata_writes", "dcs_workshop_writes", "mxl_workshop_writes",
            "xdto_workshop_writes", "external_object_workshop_writes",
            "external_data_source_workshop_writes",
            // The installYaxunit pre-step of yaxunit_tests writes the infobase through this name,
            // and the one write-blocking preset that keeps the facade on (Debug & Test) keeps the
            // whole applications group on as well - the name is disabled by hand there or the
            // pre-step writes under a preset that promises it will not.
            "install_extension");

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
