/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormStandardCommand;
import com._1c.g5.v8.dt.form.model.Table;
import com._1c.g5.v8.dt.mcore.CommandRef;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCommand;
import com._1c.g5.v8.dt.metadata.mdclass.CommonCommand;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.StandardCommand;

/**
 * A button's command is answered by the path the form file writes it under, for every kind of
 * command a button can run.
 */
public class AButtonNamesItsCommandByPathTest
{
    private static FormCommand formCommand(String name)
    {
        FormCommand command = FormFactory.eINSTANCE.createFormCommand();
        command.setName(name);
        return command;
    }

    private static FormStandardCommand formStandardCommand(String name)
    {
        FormStandardCommand command = FormFactory.eINSTANCE.createFormStandardCommand();
        command.setName(name);
        return command;
    }

    @Test
    public void aFormCommandIsNamedUnderTheForm()
    {
        assertEquals("Form.Command.Провести", //$NON-NLS-1$
            GetFormStructureTool.commandPath(formCommand("Провести"))); //$NON-NLS-1$
    }

    @Test
    public void aButtonAnswersTheCommandItRuns()
    {
        Button button = FormFactory.eINSTANCE.createButton();
        button.setCommandName(formCommand("Заполнить")); //$NON-NLS-1$

        assertEquals("Form.Command.Заполнить", GetFormStructureTool.commandPath(button.getCommandName())); //$NON-NLS-1$
    }

    @Test
    public void aStandardCommandOfTheFormIsNamedUnderTheForm()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormStandardCommand close = formStandardCommand("Close"); //$NON-NLS-1$
        form.getCommands().add(close);

        assertEquals("Form.StandardCommand.Close", GetFormStructureTool.commandPath(close)); //$NON-NLS-1$
        assertEquals("Form.StandardCommand.Help", //$NON-NLS-1$
            GetFormStructureTool.commandPath(formStandardCommand("Help"))); //$NON-NLS-1$
    }

    @Test
    public void aStandardCommandOfAnItemIsNamedUnderTheItem()
    {
        Table table = FormFactory.eINSTANCE.createTable();
        table.setName("Список"); //$NON-NLS-1$
        FormStandardCommand create = formStandardCommand("Create"); //$NON-NLS-1$
        table.getCommands().add(create);

        assertEquals("Form.Item.Список.StandardCommand.Create", GetFormStructureTool.commandPath(create)); //$NON-NLS-1$
    }

    @Test
    public void aCommonCommandIsNamedByItsType()
    {
        CommonCommand command = MdClassFactory.eINSTANCE.createCommonCommand();
        command.setName("ОбщаяКоманда"); //$NON-NLS-1$

        assertEquals("CommonCommand.ОбщаяКоманда", GetFormStructureTool.commandPath(command)); //$NON-NLS-1$
    }

    @Test
    public void aCommandOfAMetadataObjectIsNamedUnderItsOwner()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$
        CatalogCommand print = MdClassFactory.eINSTANCE.createCatalogCommand();
        print.setName("Печать"); //$NON-NLS-1$
        catalog.getCommands().add(print);
        StandardCommand create = MdClassFactory.eINSTANCE.createStandardCommand();
        create.setName("Create"); //$NON-NLS-1$
        catalog.getStandardCommands().add(create);

        assertEquals("Catalog.Товары.Command.Печать", GetFormStructureTool.commandPath(print)); //$NON-NLS-1$
        assertEquals("Catalog.Товары.StandardCommand.Create", GetFormStructureTool.commandPath(create)); //$NON-NLS-1$
    }

    @Test
    public void aCommandReferenceIsFollowedToItsCommand()
    {
        CommandRef ref = McoreFactory.eINSTANCE.createCommandRef();
        ref.setCommand(formCommand("Обновить")); //$NON-NLS-1$

        assertEquals("Form.Command.Обновить", GetFormStructureTool.commandPath(ref)); //$NON-NLS-1$
    }

    @Test
    public void noCommandAndANamelessCommandAnswerNothing()
    {
        assertNull(GetFormStructureTool.commandPath(null));
        assertNull(GetFormStructureTool.commandPath(FormFactory.eINSTANCE.createFormCommand()));
        assertNull(GetFormStructureTool.commandPath(McoreFactory.eINSTANCE.createCommandRef()));
    }
}
