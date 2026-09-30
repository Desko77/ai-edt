/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.common.util.EList;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormCommandInterface;
import com._1c.g5.v8.dt.form.model.FormCommandInterfaceItem;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormStandardCommand;

/**
 * The address {@code get_form_structure} reports for a standard command of a form item -
 * {@code Form.Item.<Item>.StandardCommand.<Name>} - is the address the command-interface mutation
 * accepts back, and the two addresses that resolved before keep resolving:
 * {@code Form.StandardCommand.<Name>} for a command the form itself carries and an item address
 * whose command the form's own list holds.
 *
 * <p>Pinned without an EDT runtime, on a form model built from the factories: the standard
 * commands of a form item are that item's own children (its
 * {@code FormStandardCommandSource.getCommands()} list), so resolving the item address walks the
 * item, not only the form.
 */
public class AFormItemStandardCommandResolvesByItsItemAddressTest
{
    /** A form carrying the navigation panel every mutation of this cluster writes into. */
    private static Form formWithPanel()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormCommandInterface commandInterface = FormFactory.eINSTANCE.createFormCommandInterface();
        commandInterface.setNavigationPanel(FormFactory.eINSTANCE.createFormCommandInterfaceItems());
        form.setCommandInterface(commandInterface);
        return form;
    }

    /** A form item holding a standard command in its own command list. */
    private static FormStandardCommand itemStandardCommand(Form form, String itemName,
        String commandName)
    {
        FormField item = FormFactory.eINSTANCE.createFormField();
        item.setName(itemName);
        form.getItems().add(item);
        FormStandardCommand command = FormFactory.eINSTANCE.createFormStandardCommand();
        command.setName(commandName);
        item.getCommands().add(command);
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

    @Test
    public void aStandardCommandOfAFormItemResolvesByItsItemAddress()
    {
        Form form = formWithPanel();
        FormStandardCommand command = itemStandardCommand(form, "Товары", "Create"); //$NON-NLS-1$ //$NON-NLS-2$
        String address = GetFormStructureTool.commandPath(command);
        assertEquals("Form.Item.Товары.StandardCommand.Create", address); //$NON-NLS-1$

        String result = add(form, address);

        assertFalse(result, result.startsWith("Error")); //$NON-NLS-1$
        assertEquals(result, 1, panelItems(form).size());
        assertEquals(address, FormCommandInterfaceOps.commandFqnOfItem(panelItems(form).get(0)));
    }

    @Test
    public void theResolvedItemAddressChangesAProperty()
    {
        Form form = formWithPanel();
        itemStandardCommand(form, "Товары", "Create"); //$NON-NLS-1$ //$NON-NLS-2$
        String address = "Form.Item.Товары.StandardCommand.Create"; //$NON-NLS-1$
        add(form, address);

        String result = FormCommandInterfaceOps.applyCommandInterfaceMutation(null, form,
            "navigation", address, "set_property", null, null, null, //$NON-NLS-1$ //$NON-NLS-2$
            new String[] { "visible", "true" }); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result, result.startsWith("Error")); //$NON-NLS-1$
        assertFalse(result, result.contains("WARNING")); //$NON-NLS-1$
    }

    @Test
    public void aStandardCommandOfTheFormResolvesWithoutAnItem()
    {
        Form form = formWithPanel();
        FormStandardCommand command = FormFactory.eINSTANCE.createFormStandardCommand();
        command.setName("Post"); //$NON-NLS-1$
        form.getCommands().add(command);

        String result = add(form, "Form.StandardCommand.Post"); //$NON-NLS-1$

        assertFalse(result, result.startsWith("Error")); //$NON-NLS-1$
        assertEquals(result, 1, panelItems(form).size());
    }

    @Test
    public void anItemAddressNamingACommandTheFormCarriesStillResolves()
    {
        Form form = formWithPanel();
        FormStandardCommand command = FormFactory.eINSTANCE.createFormStandardCommand();
        command.setName("Create"); //$NON-NLS-1$
        form.getCommands().add(command);

        String result = add(form, "Form.Item.Список.StandardCommand.Create"); //$NON-NLS-1$

        assertFalse(result, result.startsWith("Error")); //$NON-NLS-1$
        assertEquals(result, 1, panelItems(form).size());
    }
}
