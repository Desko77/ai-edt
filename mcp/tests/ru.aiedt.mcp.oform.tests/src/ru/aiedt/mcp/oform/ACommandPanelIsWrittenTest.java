/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.layout.CommandPanels;
import ru.aiedt.mcp.oform.ops.LayoutContext;
import ru.aiedt.mcp.oform.ops.OperationResult;
import ru.aiedt.mcp.oform.ops.OperationsCatalog;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.layout.StandardActions;

/**
 * A command panel is written as the corpus holds it: the panel with its source of actions and its
 * auto-fill, its buttons - a procedure, a standard action with the registry's record, a picture
 * from the measured dictionary - its submenus with a buttons block of their own, its separators,
 * and a button removed with its action. The fixtures are three real forms of the corpus (an
 * object panel with procedures, a table panel with a source and auto-fill, a panel with two
 * submenus), every one round-tripped byte for byte, and the reader names what they hold: the kind
 * and the action of every button, the picture, the buttons under a submenu, the auto-fill and the
 * source of the panel. What the operations refuse they refuse by name.
 */
public class ACommandPanelIsWrittenTest
{
    private static final List<String> FIXTURES = List.of("fixtures/panel-object-form-v26.form", //$NON-NLS-1$
        "fixtures/panel-table-form-v27.form", "fixtures/panel-submenu-form-v27.form"); //$NON-NLS-1$ //$NON-NLS-2$

    /** Each fixture is a real form: it round-trips byte for byte and the reader reads its panels. */
    @Test
    public void eachPanelFixtureRoundTrips()
    {
        for (int i = 0; i < FIXTURES.size(); i++)
        {
            String name = FIXTURES.get(i);
            String raw = text(i);
            ListNode tree = BraceTree.parse(raw);
            assertEquals(name, raw, BraceTree.serialize(tree));
            FormLayout layout = LayoutReader.read(tree, "Catalog"); //$NON-NLS-1$
            assertTrue(name, layout.unread.isEmpty());
            int panels = 0;
            for (FormLayout.Element element : layout.allElements())
            {
                if ("CommandPanel".equals(element.type)) //$NON-NLS-1$
                {
                    panels++;
                    assertTrue(name, element.buttons.size() > 0);
                    assertTrue(name, element.details.containsKey("autoFill")); //$NON-NLS-1$
                    assertTrue(name, element.details.containsKey("actionSource")); //$NON-NLS-1$
                }
            }
            assertTrue(name, panels > 0);
        }
    }

    /** The reader names the kind and the action of every button, the picture, the submenu items. */
    @Test
    public void theReaderNamesWhatThePanelsHold()
    {
        FormLayout object = LayoutReader.read(BraceTree.parse(text(0)), "Catalog"); //$NON-NLS-1$
        FormLayout.Element panel = object.elementNamed("Text7"); //$NON-NLS-1$
        assertEquals("CommandPanel", panel.type); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, panel.details.get("autoFill")); //$NON-NLS-1$
        assertEquals("none", panel.details.get("actionSource")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(3, panel.buttons.size());
        assertEquals("button", panel.buttons.get(0).details.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Text2", panel.buttons.get(0).procedure); //$NON-NLS-1$
        assertEquals("separator", panel.buttons.get(1).details.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("button", panel.buttons.get(2).details.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$

        FormLayout table = LayoutReader.read(BraceTree.parse(text(1)), "Catalog"); //$NON-NLS-1$
        FormLayout.Element sourced = table.elementNamed("Text3"); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, sourced.details.get("autoFill")); //$NON-NLS-1$
        assertEquals("Text12", sourced.details.get("actionSource")); //$NON-NLS-1$ //$NON-NLS-2$
        FormLayout.Element plain = table.elementNamed("Text10"); //$NON-NLS-1$
        assertEquals("button", plain.buttons.get(1).details.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("standard:close", plain.buttons.get(1).details.get("action")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(2), plain.buttons.get(1).details.get("picture")); //$NON-NLS-1$
        assertEquals("standard:help", plain.buttons.get(3).details.get("action")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(3), plain.buttons.get(3).details.get("picture")); //$NON-NLS-1$

        FormLayout submenus = LayoutReader.read(BraceTree.parse(text(2)), "Catalog"); //$NON-NLS-1$
        FormLayout.Element withSubmenus = submenus.elementNamed("Text24"); //$NON-NLS-1$
        assertEquals(10, withSubmenus.buttons.size());
        FormLayout.Button empty = null;
        FormLayout.Button full = null;
        for (FormLayout.Button button : withSubmenus.buttons)
        {
            if (!"submenu".equals(button.details.get("kind"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                continue;
            }
            List<?> children = (List<?>)button.details.get("children"); //$NON-NLS-1$
            if (children == null || children.isEmpty())
            {
                empty = button;
            }
            else
            {
                full = button;
            }
        }
        assertNotNull(empty);
        assertEquals("Text18", empty.name); //$NON-NLS-1$
        assertNotNull(full);
        assertEquals("Text11", full.name); //$NON-NLS-1$
        List<?> children = (List<?>)full.details.get("children"); //$NON-NLS-1$
        assertEquals(10, children.size());
        Map<?, ?> first = (Map<?, ?>)children.get(0);
        assertEquals("standard:findInList", first.get("action")); //$NON-NLS-1$ //$NON-NLS-2$
        Map<?, ?> last = (Map<?, ?>)children.get(children.size() - 1);
        assertEquals("Text10", last.get("procedure")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("procedure:Text10", last.get("action")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A panel is added from its template and takes a procedure button, a standard one and a separator. */
    @Test
    public void aPanelIsAddedAndFilledByButtons()
    {
        for (int f = 0; f < FIXTURES.size(); f++)
        {
            String name = FIXTURES.get(f);
            LayoutEditor editor = editor(f);
            LayoutEditor.Outcome panel = editor.addElement("CommandPanel", "ПанельДействий", null, null, //$NON-NLS-1$ //$NON-NLS-2$
                new int[] { 0, 300, 500, 325 }, null, null, null);
            assertTrue(name + ": " + panel.message, panel.done); //$NON-NLS-1$
            assertTrue(name, editor.addElement("CommandPanel", "ПанельДействий", null, null, //$NON-NLS-1$ //$NON-NLS-2$
                new int[] { 0, 300, 500, 325 }, null, null, null).message.contains("already has")); //$NON-NLS-1$
            LayoutEditor.Outcome procedure = CommandPanels.addButton(editor.root(), "ru", "ПанельДействий", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "КнопкаПечать", "Печать", "ПечатьНажатие", null, null, null, null);
            assertTrue(name + ": " + procedure.message, procedure.done); //$NON-NLS-1$
            LayoutEditor.Outcome standard = CommandPanels.addButton(editor.root(), "ru", "ПанельДействий", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "КнопкаЗакрыть", "Закрыть", null, StandardActions.find("close"), null, null, null); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(name + ": " + standard.message, standard.done); //$NON-NLS-1$
            LayoutEditor.Outcome picture = CommandPanels.addButton(editor.root(), "ru", "ПанельДействий", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "КнопкаСправка", "Справка", null, StandardActions.find("help"), Integer.valueOf(0), null, null); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(name + ": " + picture.message, picture.done); //$NON-NLS-1$
            LayoutEditor.Outcome separator = CommandPanels.addSeparator(editor.root(), "ПанельДействий", //$NON-NLS-1$ //$NON-NLS-2$
                "Разделитель1", null, null); //$NON-NLS-1$
            assertTrue(name + ": " + separator.message, separator.done); //$NON-NLS-1$

            String text = editor.text();
            assertEquals(name, text, BraceTree.serialize(BraceTree.parse(text)));
            FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
            FormLayout.Element added = layout.elementNamed("ПанельДействий"); //$NON-NLS-1$
            assertNotNull(name, added);
            assertEquals(4, added.buttons.size());
            assertEquals("ПечатьНажатие", added.buttons.get(0).procedure); //$NON-NLS-1$
            assertEquals("procedure:ПечатьНажатие", added.buttons.get(0).details.get("action")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("none", added.buttons.get(0).details.get("picture")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("standard:close", added.buttons.get(1).details.get("action")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("standard:help", added.buttons.get(2).details.get("action")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(Integer.valueOf(0), added.buttons.get(2).details.get("picture")); //$NON-NLS-1$
            assertEquals("separator", added.buttons.get(3).details.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(Boolean.FALSE, added.details.get("autoFill")); //$NON-NLS-1$
            assertEquals("none", added.details.get("actionSource")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(name, added.buttons.get(0).caption.get("ru").equals("Печать")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** A submenu takes a buttons block of its own; parent nests, before inserts in front. */
    @Test
    public void aSubmenuTakesItsOwnButtons()
    {
        LayoutEditor editor = editor(2);
        LayoutEditor.Outcome submenu = CommandPanels.addSubmenu(editor.root(), "ru", "Text24", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "ПодменюНовое", "Действия новые", null, null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(submenu.message, submenu.done);
        LayoutEditor.Outcome nested = CommandPanels.addButton(editor.root(), "ru", "Text24", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "КнопкаВнутри", "Внутри", null, StandardActions.find("add"), null, "ПодменюНовое", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(nested.message, nested.done);
        LayoutEditor.Outcome innerMenu = CommandPanels.addSubmenu(editor.root(), "ru", "Text24", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "ПодменюВнутри", "Внутри", "ПодменюНовое", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(innerMenu.message, innerMenu.done);
        LayoutEditor.Outcome before = CommandPanels.addSubmenu(editor.root(), "ru", "Text24", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "ПодменюПеред", "Перед", null, "ПодменюНовое"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(before.message, before.done);

        String text = editor.text();
        assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
        FormLayout.Element panel = layout.elementNamed("Text24"); //$NON-NLS-1$
        assertEquals(12, panel.buttons.size());
        FormLayout.Button last = panel.buttons.get(panel.buttons.size() - 1);
        assertEquals("ПодменюНовое", last.name); //$NON-NLS-1$
        assertEquals("Действия новые", last.caption.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        List<?> children = (List<?>)last.details.get("children"); //$NON-NLS-1$
        assertEquals(2, children.size());
        Map<?, ?> inside = (Map<?, ?>)children.get(0);
        assertEquals("standard:add", inside.get("action")); //$NON-NLS-1$ //$NON-NLS-2$
        Map<?, ?> inner = (Map<?, ?>)children.get(1);
        assertEquals("submenu", inner.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ПодменюВнутри", inner.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(inner.get("children") == null || ((List<?>)inner.get("children")).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
        FormLayout.Button ahead = panel.buttons.get(panel.buttons.size() - 2);
        assertEquals("ПодменюПеред", ahead.name); //$NON-NLS-1$
        List<?> aheadChildren = (List<?>)ahead.details.get("children"); //$NON-NLS-1$
        assertTrue(aheadChildren == null || aheadChildren.isEmpty());
    }

    /** A removed button takes its action with it; a submenu takes its block and its buttons' actions. */
    @Test
    public void aButtonIsRemovedWithItsAction()
    {
        LayoutEditor editor = editor(2);
        LayoutEditor.Outcome removed = CommandPanels.removeButton(editor.root(), "Text24", "Text11"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(removed.message, removed.done);
        assertTrue(removed.message, removed.message.contains("submenu block")); //$NON-NLS-1$
        assertTrue(removed.message, removed.message.contains("standard:writeAndClose")); //$NON-NLS-1$
        String text = editor.text();
        assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
        FormLayout.Element panel = layout.elementNamed("Text24"); //$NON-NLS-1$
        assertEquals(9, panel.buttons.size());
        // the submenu and every button under it went; the other submenu keeps its own empty block
        for (FormLayout.Button button : panel.buttons)
        {
            assertFalse(button.name, "Text11".equals(button.name)); //$NON-NLS-1$
            assertFalse(button.name, "standard:writeAndClose".equals(button.details.get("action"))); //$NON-NLS-1$ //$NON-NLS-2$
            Object nested = button.details.get("children"); //$NON-NLS-1$
            if (nested instanceof List)
            {
                for (Object child : (List<?>)nested)
                {
                    assertFalse(button.name, "standard:writeAndClose".equals(((Map<?, ?>)child).get("action"))); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        LayoutEditor.Outcome separator = CommandPanels.removeButton(editor.root(), "Text24", "Text12"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(separator.message, separator.done);
        FormLayout after = LayoutReader.read(BraceTree.parse(editor.text()), "Catalog"); //$NON-NLS-1$
        assertEquals(8, after.elementNamed("Text24").buttons.size()); //$NON-NLS-1$
        // the separator's action record went: the actions count dropped by the subtree's records
        String removed2 = CommandPanels.removeButton(editor.root(), "Text31", "Text27").message; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(removed2, removed2.contains("abde0c9a")); //$NON-NLS-1$
        assertFalse(CommandPanels.removeButton(editor.root(), "Text24", "Нигде").done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The source of a panel's actions comes and goes, and an element that is one is refused until cascade. */
    @Test
    public void theSourceOfActionsComesAndGoes()
    {
        LayoutEditor editor = editor(1);
        LayoutEditor.Outcome set = CommandPanels.setActionSource(editor.root(), "Text3", "Text12", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(set.message, set.done);
        assertTrue(set.message, set.message.contains("autoFill true")); //$NON-NLS-1$
        LayoutEditor.Outcome cleared = CommandPanels.setActionSource(editor.root(), "Text3", "none", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(cleared.message, cleared.done);
        assertTrue(cleared.message, cleared.message.contains("autoFill false")); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(BraceTree.parse(editor.text()), "Catalog"); //$NON-NLS-1$
        assertEquals("none", layout.elementNamed("Text3").details.get("actionSource")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Boolean.FALSE, layout.elementNamed("Text3").details.get("autoFill")); //$NON-NLS-1$ //$NON-NLS-2$

        CommandPanels.setActionSource(editor.root(), "Text3", "Text12", Boolean.FALSE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        FormLayout withSource = LayoutReader.read(BraceTree.parse(editor.text()), "Catalog"); //$NON-NLS-1$
        assertEquals("Text12", withSource.elementNamed("Text3").details.get("actionSource")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Boolean.FALSE, withSource.elementNamed("Text3").details.get("autoFill")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("Text3"), CommandPanels.panelsSourcing(editor.root(), "4")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // the refusal and the cascade are the operation's work: it runs the handler with the tree
        ru.aiedt.mcp.oform.ops.OperationSpec remove = ru.aiedt.mcp.oform.ops.OperationsCatalog.find("remove_element"); //$NON-NLS-1$
        assertNotNull(remove);
        ToolResult answer = ToolResult.success();
        ListNode tree = editor.root();
        LayoutContext context = new LayoutContext(null, "Catalog.Products.Form.ItemForm", "Catalog", null, tree, //$NON-NLS-1$ //$NON-NLS-2$
            editor, Map.of("elementName", "Text12"), answer); //$NON-NLS-1$
        OperationResult refused = remove.layout.apply(context);
        assertFalse(refused.outcome.message, refused.outcome.done);
        assertTrue(refused.outcome.message, refused.outcome.message.contains("cascade=true")); //$NON-NLS-1$
        assertTrue(refused.outcome.message, refused.outcome.message.contains("Text3")); //$NON-NLS-1$
        OperationResult cascaded = remove.layout.apply(new LayoutContext(null, "f", "Catalog", null, tree, editor, //$NON-NLS-1$ //$NON-NLS-2$
            Map.of("elementName", "Text12", "cascade", "true"), answer)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(cascaded.outcome.message, cascaded.outcome.done);
        FormLayout lost = LayoutReader.read(tree, "Catalog"); //$NON-NLS-1$
        assertNull(lost.elementNamed("Text12")); //$NON-NLS-1$
        assertEquals("none", lost.elementNamed("Text3").details.get("actionSource")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Boolean.FALSE, lost.elementNamed("Text3").details.get("autoFill")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(answer.toJson().contains("Text3")); //$NON-NLS-1$
    }

    /** What the operations cannot do they refuse by name. */
    @Test
    public void theRefusalsOfTheContract()
    {
        LayoutEditor editor = editor(0);
        ListNode root = editor.root();
        assertTrue(CommandPanels.addButton(root, "ru", "Text7", "Повтор", null, "Прочерк", null, null, null, null) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .done);
        assertTrue(CommandPanels.addButton(root, "ru", "Text7", "повтор", null, "Прочерк", null, null, null, null) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .message.contains("already holds")); //$NON-NLS-1$
        assertTrue(CommandPanels.addSubmenu(root, "ru", "Text7", "Повтор", "Подменю", null, null) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .message.contains("already holds")); //$NON-NLS-1$
        assertTrue(CommandPanels.addSeparator(root, "Text7", "Повтор", null, null) //$NON-NLS-1$ //$NON-NLS-2$
            .message.contains("already holds")); //$NON-NLS-1$
        assertTrue(CommandPanels.removeButton(root, "Text7", "Повтор").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(CommandPanels.addButton(root, "ru", "Text7", "Обе", "Обе", "Прочерк", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            StandardActions.find("close"), null, null, null).message.contains("not both")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(CommandPanels.addButton(root, "ru", "Text7", "НиОдной", "Ни одной", null, null, null, null, null) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .message.contains("one of procedure and action")); //$NON-NLS-1$
        assertTrue(CommandPanels.addButton(root, "ru", "Text7", "Неизвестное", null, null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            StandardActions.find("close"), Integer.valueOf(9), null, null).message.contains("indexes 0 to 3")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(CommandPanels.addButton(root, "ru", "Text7", "СКартинкой", null, "Прочерк", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Integer.valueOf(0), null, null).message.contains("procedure button takes no picture")); //$NON-NLS-1$
        assertTrue(CommandPanels.addButton(root, "ru", "Text7", "ВПодменю", null, "Прочерк", null, null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Нигде", null).message.contains("No submenu named Нигде")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(CommandPanels.addButton(root, "ru", "Text7", "Перед", null, "Прочерк", null, null, null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "НетТакой").message.contains("No button named НетТакой")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(CommandPanels.addButton(root, "ru", "Нигде", "Кнопка", null, "Прочерк", null, null, null, null) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .message.contains("No element named Нигде")); //$NON-NLS-1$
        assertTrue(CommandPanels.setActionSource(root, "Text7", "Text8", null).message.contains("not a Table")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(CommandPanels.setActionSource(root, "Text7", "Нигде", null).message.contains("No element named Нигде")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(StandardActions.find("doEverything")); //$NON-NLS-1$
        OperationResult unknown = OperationsCatalog.find("add_button").layout.apply(new LayoutContext(null, "f", //$NON-NLS-1$ //$NON-NLS-2$
            "Catalog", null, root, editor, Map.of("panel", "Text7", "name", "Кнопка", "action", "doEverything"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            ToolResult.success()));
        assertFalse(unknown.outcome.done);
        assertTrue(unknown.outcome.message, unknown.outcome.message.contains("doEverything")); //$NON-NLS-1$
        assertTrue(unknown.outcome.message, unknown.outcome.message.contains("close")); //$NON-NLS-1$
        OperationResult missingTable = OperationsCatalog.find("add_command_panel").layout.apply(new LayoutContext(null, //$NON-NLS-1$
            "f", "Catalog", null, root, editor, Map.of("name", "ПанельЛишняя", "actionSource", "НетТаблицы"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            ToolResult.success()));
        assertFalse(missingTable.outcome.done);
        OperationResult noTableArgument = OperationsCatalog.find("set_action_source").layout.apply( //$NON-NLS-1$
            new LayoutContext(null, "f", "Catalog", null, root, editor, Map.of("panel", "Text7"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                ToolResult.success()));
        assertFalse(noTableArgument.outcome.done);
        assertTrue(noTableArgument.outcome.message, noTableArgument.outcome.message.contains("needs table")); //$NON-NLS-1$
        assertTrue(missingTable.outcome.message, missingTable.outcome.message.contains("НетТаблицы")); //$NON-NLS-1$
        assertNull(LayoutReader.read(root, "Catalog").elementNamed("ПанельЛишняя")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** stub=true appends a procedure with the parameter Кнопка, and skips one the module already has. */
    @Test
    public void aStubIsAppendedToTheModule()
    {
        LayoutEditor editor = editor(0);
        String declared = "Процедура ПечатьНажатие(Кнопка)\r\nКонецПроцедуры\r\n"; //$NON-NLS-1$
        OperationResult present = OperationsCatalog.find("add_button").layout.apply(new LayoutContext(null, "f", //$NON-NLS-1$ //$NON-NLS-2$
            "Catalog", moduleFile(declared), editor.root(), editor, Map.of("panel", "Text7", "name", "КнопкаЕсть", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "procedure", "ПечатьНажатие", "stub", "true"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ToolResult.success()));
        assertTrue(present.outcome.message, present.outcome.done);
        assertNull(present.moduleText);
        OperationResult added = OperationsCatalog.find("add_button").layout.apply(new LayoutContext(null, "f", //$NON-NLS-1$ //$NON-NLS-2$
            "Catalog", moduleFile(""), editor.root(), editor, Map.of("panel", "Text7", "name", "КнопкаНовая", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "caption", "Печать", "procedure", "ПечатьНажатие", "stub", "true"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            ToolResult.success()));
        assertTrue(added.outcome.message, added.outcome.done);
        assertNotNull(added.moduleText);
        assertTrue(added.moduleText, added.moduleText.contains("Процедура ПечатьНажатие(Кнопка)")); //$NON-NLS-1$
        assertTrue(added.moduleText, added.moduleText.contains("КонецПроцедуры")); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(editor.root(), "Catalog"); //$NON-NLS-1$
        boolean found = false;
        for (FormLayout.Button button : layout.elementNamed("Text7").buttons) //$NON-NLS-1$
        {
            if ("КнопкаНовая".equals(button.name)) //$NON-NLS-1$
            {
                found = true;
                assertEquals("procedure:ПечатьНажатие", button.details.get("action")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        assertTrue(found);
    }

    /** The registry holds the measured actions, and every record it hands out parses. */
    @Test
    public void theRegistryHoldsTheMeasuredActions()
    {
        assertEquals(41, StandardActions.all().size());
        assertEquals(41, StandardActions.names().size());
        StandardActions.Action close = StandardActions.find("CLOSE"); //$NON-NLS-1$
        assertEquals("close", close.name); //$NON-NLS-1$
        assertEquals(StandardActions.NULL_SET, close.set);
        assertEquals("142", close.code); //$NON-NLS-1$
        assertEquals("Закрыть", close.title); //$NON-NLS-1$
        assertEquals(665, close.buttons);
        StandardActions.Action setFilter = StandardActions.find("setFilter"); //$NON-NLS-1$
        assertEquals("6b7291bf-bcd2-41af-bac7-414d47cc6e6a", setFilter.set); //$NON-NLS-1$
        assertEquals("53", setFilter.code); //$NON-NLS-1$
        assertEquals("55c7732c-0c33-4394-adca-9d15082552b6", StandardActions.find("newGroup").set); //$NON-NLS-1$ //$NON-NLS-2$
        for (StandardActions.Action action : StandardActions.all())
        {
            String record = action.record;
            assertTrue(action.name, record.startsWith("{6,@U,1,fbe38877-")); //$NON-NLS-1$
            assertEquals(action.name, 1, count(record, "@U")); //$NON-NLS-1$
            ListNode parsed = action.freshRecord();
            assertEquals(action.name, BraceTree.serialize(parsed), BraceTree.serialize(BraceTree.parse(BraceTree.serialize(parsed))));
        }
        // the picture dictionary: four measured entries, an index outside is refused
        assertEquals(4, CommandPanels.pictureCount());
        assertNotNull(CommandPanels.pictureNode(0));
        assertNotNull(CommandPanels.pictureNode(3));
        assertNull(CommandPanels.pictureNode(4));
        assertNull(CommandPanels.pictureNode(-1));
    }

    private static String text(int fixture)
    {
        try
        {
            return ALayoutIsReadOffTheContainerTest.fixture(FIXTURES.get(fixture));
        }
        catch (java.io.IOException e)
        {
            throw new AssertionError(e);
        }
    }

    private static OrdinaryFormFile moduleFile(String module)
    {
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("module", module.getBytes(StandardCharsets.UTF_8))); //$NON-NLS-1$
        return OrdinaryFormFile.of(Path.of("form.oform"), container); //$NON-NLS-1$
    }

    private static LayoutEditor editor(int fixture)
    {
        return LayoutEditor.over(BraceTree.parse(text(fixture)), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int count(String text, String part)
    {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length()))
        {
            n++;
        }
        return n;
    }
}
