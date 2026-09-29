/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.common.util.EList;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormCommandInterface;
import com._1c.g5.v8.dt.form.model.FormCommandInterfaceItem;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCommand;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.StandardCommand;

/**
 * A command-interface item is keyed by the address of the command it runs, and the two operations
 * that find an item again - changing a property and removing it - use that same address.
 *
 * <p>Two facts are pinned without an EDT runtime, on a form model built from the factories:
 * <ul>
 *   <li>an address that names no command is refused before the item exists, so a refusal leaves the
 *       panel as it was;</li>
 *   <li>the address of a nested command - one belonging to the form or to a metadata object - is
 *       answered by walking its container, because {@code bmGetFqn()} is defined for top BM objects
 *       only and throws for these.</li>
 * </ul>
 */
public class ACommandInterfaceItemAnswersTheAddressItWasAddedUnderTest
{
    /** An item whose bound command is the only thing a lookup can read. */
    public static final class ItemWithCommand
    {
        private final Object command;

        public ItemWithCommand(Object command)
        {
            this.command = command;
        }

        public Object getCommand()
        {
            return command;
        }
    }

    /** A form carrying the navigation panel every mutation of this cluster writes into. */
    private static Form formWithPanel()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormCommandInterface commandInterface = FormFactory.eINSTANCE.createFormCommandInterface();
        commandInterface.setNavigationPanel(FormFactory.eINSTANCE.createFormCommandInterfaceItems());
        form.setCommandInterface(commandInterface);
        return form;
    }

    private static FormCommand formCommand(Form form, String name)
    {
        FormCommand command = FormFactory.eINSTANCE.createFormCommand();
        command.setName(name);
        form.getFormCommands().add(command);
        return command;
    }

    private static EList<FormCommandInterfaceItem> panelItems(Form form)
    {
        return form.getCommandInterface().getNavigationPanel().getCmiFragmentRecord();
    }

    private static String add(Form form, String address)
    {
        return FormCommandInterfaceOps.applyCommandInterfaceMutation(null, form, "navigation", //$NON-NLS-1$
            address, "add", null, null, null, null); //$NON-NLS-1$
    }

    private static String change(Form form, String address, String property, String value)
    {
        return FormCommandInterfaceOps.applyCommandInterfaceMutation(null, form, "navigation", //$NON-NLS-1$
            address, "set_property", null, null, null, new String[] { property, value }); //$NON-NLS-1$
    }

    @Test
    public void anAddressThatNamesNoCommandIsRefusedBeforeThePanelIsTouched()
    {
        Form form = formWithPanel();

        String result = add(form, "CommonCommand.НетТакой"); //$NON-NLS-1$

        assertTrue(result, result.contains("formApiNotFound")); //$NON-NLS-1$
        assertTrue(result, result.contains("CommonCommand.НетТакой")); //$NON-NLS-1$
        assertTrue(result, panelItems(form).isEmpty());
    }

    @Test
    public void aCommandOfTheFormIsAddedAndAnswersItsAddress()
    {
        Form form = formWithPanel();
        formCommand(form, "Печать"); //$NON-NLS-1$

        String result = add(form, "Form.Command.Печать"); //$NON-NLS-1$

        assertFalse(result, result.startsWith("Error")); //$NON-NLS-1$
        assertEquals(result, 1, panelItems(form).size());
        assertEquals("Form.Command.Печать", //$NON-NLS-1$
            FormCommandInterfaceOps.commandFqnOfItem(panelItems(form).get(0)));
    }

    @Test
    public void aCommandNestedInAMetadataObjectAnswersItsAddress()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$
        CatalogCommand print = MdClassFactory.eINSTANCE.createCatalogCommand();
        print.setName("Печать"); //$NON-NLS-1$
        catalog.getCommands().add(print);

        assertEquals("Catalog.Товары.Command.Печать", //$NON-NLS-1$
            FormCommandInterfaceOps.commandFqnOfItem(new ItemWithCommand(print)));
    }

    @Test
    public void aStandardCommandNestedInAMetadataObjectAnswersItsAddress()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$
        StandardCommand create = MdClassFactory.eINSTANCE.createStandardCommand();
        create.setName("Create"); //$NON-NLS-1$
        catalog.getStandardCommands().add(create);

        assertEquals("Catalog.Товары.StandardCommand.Create", //$NON-NLS-1$
            FormCommandInterfaceOps.commandFqnOfItem(new ItemWithCommand(create)));
    }

    @Test
    public void theAnsweredAddressChangesAPropertyAndRemovesTheItem()
    {
        Form form = formWithPanel();
        formCommand(form, "Печать"); //$NON-NLS-1$
        add(form, "Form.Command.Печать"); //$NON-NLS-1$
        String address = FormCommandInterfaceOps.commandFqnOfItem(panelItems(form).get(0));

        String indexResult = change(form, address, "index", "3"); //$NON-NLS-1$ //$NON-NLS-2$
        String visibleResult = change(form, address, "visible", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        String groupResult = change(form, address, "group", "FormNavigationPanelImportant"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(indexResult, indexResult.contains("WARNING")); //$NON-NLS-1$
        assertFalse(visibleResult, visibleResult.contains("WARNING")); //$NON-NLS-1$
        assertFalse(groupResult, groupResult.contains("WARNING")); //$NON-NLS-1$
        FormCommandInterfaceItem item = panelItems(form).get(0);
        assertEquals(Integer.valueOf(3), item.getIndex());
        assertNotNull(item.getUserVisible());
        assertTrue(item.getUserVisible().isCommon());
        assertNotNull(item.getGroup());
        assertEquals("FormNavigationPanelImportant", //$NON-NLS-1$
            ((StandardCommandGroup)item.getGroup()).getName());

        String removeResult = FormCommandInterfaceOps.applyCommandInterfaceMutation(null, form,
            "navigation", address, "remove", null, null, null, null); //$NON-NLS-1$

        assertFalse(removeResult, removeResult.startsWith("Error")); //$NON-NLS-1$
        assertTrue(panelItems(form).isEmpty());
    }
}
