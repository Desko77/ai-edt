/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.CommandGroup;
import com._1c.g5.v8.dt.mcore.CommandGroupCategory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCommand;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

import ru.aiedt.mcp.server.support.BmCommandInterfaceHelper;

/**
 * An object command carries a command-interface group, and that group is written even when the
 * caller names none: the platform refuses to load a configuration whose command has no group
 * ("Не указана группа, в которую входит команда по умолчанию").
 *
 * <p>Pinned without an EDT runtime, on a metadata model built from the factories: the group an
 * unnamed argument writes, the group a named argument writes, the custom group object the caller
 * resolved, and the refusal an unknown name gets - which happens before the project is read, so no
 * command is created for it.
 */
public class ACommandIsCreatedInACommandGroupTest
{
    /** A command of a catalog, the owner every object-command write hangs off. */
    private static CatalogCommand command(String name)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$
        CatalogCommand command = MdClassFactory.eINSTANCE.createCatalogCommand();
        command.setName(name);
        catalog.getCommands().add(command);
        return command;
    }

    /**
     * The name of the group the command carries, or null when it carries none. A group object
     * other than a built-in one has no name of its own - a custom group is addressed by FQN - so
     * this answers null for it.
     */
    private static String groupNameOf(CatalogCommand command)
    {
        CommandGroup group = command.getGroup();
        return group instanceof StandardCommandGroup ? ((StandardCommandGroup)group).getName() : null;
    }

    @Test
    public void anUnnamedGroupIsWrittenAsThePlatformDefault()
    {
        CatalogCommand command = command("Печать"); //$NON-NLS-1$

        SpecializedOps.applyObjectCommandGroup(command, null, null);

        assertEquals("FormCommandBarImportant", groupNameOf(command)); //$NON-NLS-1$
        assertTrue(String.valueOf(command.getGroup()),
            command.getGroup() instanceof StandardCommandGroup);
    }

    @Test
    public void aNamedGroupIsWrittenAsGiven()
    {
        CatalogCommand command = command("Открыть"); //$NON-NLS-1$
        String resolved = BmCommandInterfaceHelper.resolveObjectCommandGroupName(
            "FormNavigationPanelGoTo"); //$NON-NLS-1$

        SpecializedOps.applyObjectCommandGroup(command, resolved, null);

        assertEquals("FormNavigationPanelGoTo", groupNameOf(command)); //$NON-NLS-1$
        assertEquals(CommandGroupCategory.FORM_NAVIGATION_PANEL,
            ((StandardCommandGroup)command.getGroup()).getCategory());
    }

    @Test
    public void aFriendlyNameResolvesToTheGroupItNames()
    {
        assertEquals("FormCommandBarImportant", //$NON-NLS-1$
            BmCommandInterfaceHelper.resolveObjectCommandGroupName("CommandBar")); //$NON-NLS-1$
        assertEquals("NavigationPanelImportant", //$NON-NLS-1$
            BmCommandInterfaceHelper.resolveObjectCommandGroupName("important")); //$NON-NLS-1$
        assertEquals("FormCommandBarImportant", //$NON-NLS-1$
            BmCommandInterfaceHelper.resolveObjectCommandGroupName(null));
        assertEquals("FormCommandBarImportant", //$NON-NLS-1$
            BmCommandInterfaceHelper.resolveObjectCommandGroupName("  ")); //$NON-NLS-1$
        assertNull(BmCommandInterfaceHelper.resolveObjectCommandGroupName(
            "CommandGroup.МояГруппа")); //$NON-NLS-1$
    }

    @Test
    public void anUnknownGroupNameIsRefusedBeforeTheCommandIsTouched()
    {
        CatalogCommand command = command("Удалить"); //$NON-NLS-1$

        try
        {
            String resolved = BmCommandInterfaceHelper.resolveObjectCommandGroupName("Bogus"); //$NON-NLS-1$
            fail("refusal expected, resolved to " + resolved); //$NON-NLS-1$
        }
        catch (RuntimeException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("Bogus")); //$NON-NLS-1$
            assertTrue(refused.getMessage(),
                refused.getMessage().contains("FormCommandBarImportant")); //$NON-NLS-1$
        }
        assertNull(groupNameOf(command));
    }

    @Test
    public void anUnknownGroupNameIsAnsweredBeforeTheProjectIsRead()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", "НетТакогоПроекта"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ownerFqn", "Catalog.Товары"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "Печать"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("group", "Bogus"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = new SpecializedOps().opCreateObjectCommand(params);

        assertTrue(answer, answer.contains("Unknown command group")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Bogus")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("НетТакогоПроекта")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aCommandWithAParameterIsRefusedOutsideAFormGroup()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", "НетТакогоПроекта"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ownerFqn", "Catalog.Товары"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "Печать"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("group", "Reports"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("commandParameterType", "CatalogRef.Товары"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = new SpecializedOps().opCreateObjectCommand(params);

        assertTrue(answer, answer.contains("commandParameterType")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("FormCommandBarImportant")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Nothing was changed")); //$NON-NLS-1$
    }

    @Test
    public void onlyTheFiveFormGroupsTakeACommandWithAParameter()
    {
        assertTrue(BmCommandInterfaceHelper.isFormObjectCommandGroup("FormCommandBarImportant")); //$NON-NLS-1$
        assertTrue(BmCommandInterfaceHelper.isFormObjectCommandGroup("FormCommandBarCreateBasedOn")); //$NON-NLS-1$
        assertTrue(BmCommandInterfaceHelper.isFormObjectCommandGroup("FormNavigationPanelGoTo")); //$NON-NLS-1$
        assertTrue(BmCommandInterfaceHelper.isFormObjectCommandGroup("FormNavigationPanelImportant")); //$NON-NLS-1$
        assertTrue(BmCommandInterfaceHelper.isFormObjectCommandGroup("FormNavigationPanelSeeAlso")); //$NON-NLS-1$
        String reports = BmCommandInterfaceHelper.resolveObjectCommandGroupName("Reports"); //$NON-NLS-1$
        assertTrue(reports, !BmCommandInterfaceHelper.isFormObjectCommandGroup(reports));
        assertTrue(!BmCommandInterfaceHelper.isFormObjectCommandGroup("Bogus")); //$NON-NLS-1$
    }

    @Test
    public void theCustomGroupObjectIsTheOneWritten()
    {
        CatalogCommand command = command("Печать"); //$NON-NLS-1$
        StandardCommandGroup custom = McoreFactory.eINSTANCE.createStandardCommandGroup();
        custom.setName("МояГруппа"); //$NON-NLS-1$

        SpecializedOps.applyObjectCommandGroup(command, null, custom);

        assertSame(custom, command.getGroup());
    }

    @Test
    public void anObjectThatIsNotACommandGroupIsRefused()
    {
        CatalogCommand command = command("Печать"); //$NON-NLS-1$

        try
        {
            SpecializedOps.applyObjectCommandGroup(command, null, "ФормаКоманднаяПанель"); //$NON-NLS-1$
            fail("refusal expected"); //$NON-NLS-1$
        }
        catch (RuntimeException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("Not a command group")); //$NON-NLS-1$
        }
        assertNull(groupNameOf(command));
    }

    @Test
    public void aGroupIsWrittenOnlyOnACommand()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$

        try
        {
            SpecializedOps.applyObjectCommandGroup(catalog, "FormCommandBarImportant", null); //$NON-NLS-1$
            fail("refusal expected"); //$NON-NLS-1$
        }
        catch (RuntimeException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("Not a command")); //$NON-NLS-1$
        }
    }
}
