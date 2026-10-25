/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.BraceTree.Node;
import ru.aiedt.mcp.oform.BraceTree.Token;
import ru.aiedt.mcp.oform.layout.LayoutEditor.Outcome;

/**
 * Writes the command panels of an ordinary form the way the Designer records them: the panel
 * itself with its source of actions and its auto-fill, buttons that run a procedure or a standard
 * action, submenus with their own buttons block, separators, and a button removed with its action.
 * <p>
 * Every record comes from a template cut out of a real container of the corpus
 * ({@code layout/templates/Panel*.form}, {@code scripts/corpus/cut_panel_records.py}), the
 * standard actions from {@link StandardActions}; the numbers the corpus varies are the majority
 * of its 15071 buttons on 2474 panels: a button record of versions 25 and 26 opens with {@code 7}
 * and holds thirteen items, of version 27 with {@code 8} and holds sixteen; the flags of a
 * submenu button are {@code 99} on 25 and {@code 1e2} on 26 and 27; a fresh submenu block is
 * named by a fresh uuid and a number of the same id space the panel's buttons take their ids
 * from - the allocator at content[2] stays above every id in use, as the body's last number does
 * for elements. The buttons of a submenu sit in a block of their own beside the main one, paired
 * with the submenu action's payload by the uuid and the number.
 * </p>
 */
public final class CommandPanels
{
    /** The element type uuid of a command panel. */
    public static final String PANEL_TYPE = "e69bf21d-97b2-4f37-86db-675aea9ec2cb"; //$NON-NLS-1$

    /** The type uuid of an action that runs a standard action. */
    static final String ACTION_STANDARD = "fbe38877-b914-4fd5-8540-07dde06ba2e1"; //$NON-NLS-1$

    /** The type uuid of an action that runs a procedure. */
    static final String ACTION_PROCEDURE = "e1692cc2-605b-4535-84dd-28440238746c"; //$NON-NLS-1$

    /** The type uuid of a separator action. */
    static final String ACTION_SEPARATOR = "abde0c9a-18a6-4e0c-bbaa-af26b911b3e6"; //$NON-NLS-1$

    /** The type uuid of a submenu action. */
    static final String ACTION_SUBMENU = "c93a51ed-b2d1-47f1-948d-38373f8710af"; //$NON-NLS-1$

    /** The uuid and the number of the one block every panel keeps its own buttons in. */
    static final String MAIN_BLOCK = "b78f2e80-ec68-11d4-9dcf-0050bae2bc79"; //$NON-NLS-1$

    static final String MAIN_BLOCK_NUM = "4"; //$NON-NLS-1$

    /**
     * The pictures a button can carry, by index: the full five-item list as the corpus writes it.
     * Measured over the standard actions of the corpus: index 0 (b78f2e80, 4, 1) on 1995 buttons,
     * 1 (b78f2e80, 55, 1) on 1108, 2 (357c6a54, 2147483647, 0) on 1073 - stored as the {1,0,...}
     * shape, the one the select action's majority record carries - and 3 (b78f2e80, 4, 0) on 324.
     */
    private static final String[] PICTURES = {
        "{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,1}", //$NON-NLS-1$
        "{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,55,1}", //$NON-NLS-1$
        "{1,0,357c6a54-357d-425d-a2bd-22f4f6e86c87,2147483647,0}", //$NON-NLS-1$
        "{1,99,b78f2e80-ec68-11d4-9dcf-0050bae2bc79,4,0}" }; //$NON-NLS-1$

    private CommandPanels()
    {
        // static writer
    }

    /** A command panel of the form: its element record and the inner list that holds the content. */
    static final class Panel
    {
        final ListNode record;

        final ListNode inner;

        Panel(ListNode record, ListNode inner)
        {
            this.record = record;
            this.inner = inner;
        }

        String name()
        {
            return LayoutReader.elementName(LayoutReader.identityOf(record));
        }

        ListNode content()
        {
            return LayoutReader.asList(inner.get(7));
        }
    }

    /**
     * Puts the caption into a standard action's record, where the platform reads it from.
     *
     * The record ends either in {@code 0} - the action keeps the text the platform gives it - or in
     * {@code 6, <caption>, <tooltip>}, which is what 8 of the 263 write buttons of the corpus carry.
     * Measured on the stand: a caption written only into the button record is not shown; the
     * platform shows the action's own text.
     *
     * @param record the action record, its tail replaced in place
     * @param language the language code of the new text
     * @param caption the caption
     */
    private static void captionIntoAction(ListNode record, String language, String caption)
    {
        List<Node> items = record.items();
        String lang = language == null || language.isEmpty() ? "ru" : language; //$NON-NLS-1$
        ListNode text = BraceTree.parse("{1,1,{\"" + lang + "\",\"" + caption.replace("\"", "\"\"") + "\"}}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        ListNode tooltip = BraceTree.parse("{1,1,{\"" + lang + "\",\"" + caption.replace("\"", "\"\"") + "\"}}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        // the tail is the last item when it is the token 0, or the last three when it is 6 + two
        // localized strings
        int last = items.size() - 1;
        if (last >= 2 && "6".equals(text(items.get(last - 2)))) //$NON-NLS-1$
        {
            items.set(last - 1, text);
            items.set(last, tooltip);
            return;
        }
        items.set(last, new Token("6")); //$NON-NLS-1$
        items.add(text);
        items.add(tooltip);
    }

    /** A buttons block of a panel: the list itself, with its pairs at every second position. */
    private static final class Block
    {
        final ListNode list;

        Block(ListNode list)
        {
            this.list = list;
        }

        String uuid()
        {
            return text(list.get(1));
        }

        String num()
        {
            return text(list.get(2));
        }

        int count()
        {
            Integer count = LayoutReader.intOf(list.get(4));
            return count == null ? 0 : count.intValue();
        }

        /** @return the name of the button of the pair at the index, or {@code null} */
        String buttonName(int pair)
        {
            ListNode button = LayoutReader.asList(list.get(5 + pair * 2 + 1));
            if (button == null || button.size() < 2)
            {
                return null;
            }
            Node name = button.get(1);
            return name instanceof Token && ((Token)name).isString() ? ((Token)name).value() : text(name);
        }

        String actionUuid(int pair)
        {
            return text(list.get(5 + pair * 2));
        }
    }

    // ---- the panel --------------------------------------------------------------------------

    /**
     * Finishes a panel record cut from the template: a fresh uuid for the panel's content, the
     * allocator at zero, no auto-fill and no source. Called by the editor's {@code addElement}
     * for a {@code CommandPanel}; the operations set the source and the auto-fill after.
     *
     * Measured: 831 panels of version 27 and 427 of version 26 carry this inner shape
     * ({@code 8,2,0,1,1} and a tail of four); the template is that record with an empty block.
     *
     * @param record the template record, already filled with the element's own values
     */
    public static void prepareNew(ListNode record)
    {
        ListNode own = LayoutReader.asList(record.get(2));
        ListNode inner = own == null ? null : LayoutReader.asList(own.get(1));
        if (inner == null || inner.size() < 8)
        {
            return;
        }
        substitute(inner, "@PANELUUID", new Token(UUID.randomUUID().toString())); //$NON-NLS-1$
        substitute(inner, "@AUTOFILL", new Token("0")); //$NON-NLS-1$ //$NON-NLS-2$
        substitute(inner, "@ALLOC", new Token("0")); //$NON-NLS-1$ //$NON-NLS-2$
        substitute(record, "@SOURCE", new Token("0")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Sets Автозаполнение of a panel.
     *
     * Measured: 1194 of 2474 panels record 1 at this position, 1280 record 0.
     *
     * @param root the layout tree
     * @param panelName the panel
     * @param autoFill whether the platform fills the panel's buttons itself
     * @return the outcome
     */
    public static Outcome setAutoFill(ListNode root, String panelName, boolean autoFill)
    {
        Panel panel = panelNamed(root, panelName);
        if (panel == null)
        {
            return noElement(panelName);
        }
        panel.inner.items().set(3, new Token(autoFill ? "1" : "0")); //$NON-NLS-1$ //$NON-NLS-2$
        return Outcome.done("autoFill of " + panel.name() + " set to " + autoFill); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Sets or clears the source of a panel's actions. The source is recorded as the element id in
     * the panel's identity, and a panel with a source is auto-filled unless said otherwise.
     *
     * Measured: the source is the element id at identity index 2; {@code 0} and {@code 4294967295}
     * are no source. A panel with a source is auto-filled unless the caller says otherwise.
     *
     * @param root the layout tree
     * @param panelName the panel
     * @param table the name of the table the panel serves, or {@code null}/{@code none} to clear
     * @param autoFill whether to auto-fill; {@code null} for the default (true with a source,
     *        false without one)
     * @return the outcome
     */
    public static Outcome setActionSource(ListNode root, String panelName, String table, Boolean autoFill)
    {
        Panel panel = panelNamed(root, panelName);
        if (panel == null)
        {
            return noElement(panelName);
        }
        String sourceId = "0"; //$NON-NLS-1$
        String sourceName = null;
        if (table != null && !table.isEmpty() && !"none".equalsIgnoreCase(table)) //$NON-NLS-1$
        {
            ListNode element = elementNamed(root, table);
            if (element == null)
            {
                return noElement(table);
            }
            String type = LayoutReader.typeName(text(element.get(0)));
            if (!"Table".equals(type)) //$NON-NLS-1$
            {
                return Outcome.refused(table + " is a " + type + ", not a Table; a panel takes its actions from one."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sourceId = text(element.get(1));
            sourceName = LayoutReader.elementName(LayoutReader.asList(element.get(4)));
        }
        LayoutReader.asList(panel.record.get(4)).items().set(2, new Token(sourceId));
        boolean fill = autoFill != null ? autoFill.booleanValue() : sourceName != null;
        panel.inner.items().set(3, new Token(fill ? "1" : "0")); //$NON-NLS-1$ //$NON-NLS-2$
        return Outcome.done("action source of " + panel.name() + " " //$NON-NLS-1$ //$NON-NLS-2$
            + (sourceName != null ? "set to " + sourceName : "cleared") + ", autoFill " + fill); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** @return the names of the panels whose source of actions the element is */
    public static List<String> panelsSourcing(ListNode root, String elementId)
    {
        List<String> out = new ArrayList<>();
        for (Panel panel : panels(root))
        {
            ListNode identity = LayoutReader.asList(panel.record.get(4));
            if (identity != null && identity.size() > 2 && elementId != null && elementId.equals(text(identity.get(2))))
            {
                out.add(panel.name());
            }
        }
        return out;
    }

    /**
     * @param panel the panel
     * @param name the name a button, a submenu or a separator is about to take
     * @return the refusal when the panel already holds that name, or {@code null}
     */
    private static Outcome duplicateName(Panel panel, String name)
    {
        for (Block block : blocks(panel))
        {
            for (int pair = 0; pair < block.count(); pair++)
            {
                if (name.equalsIgnoreCase(block.buttonName(pair)))
                {
                    return Outcome.refused("The panel already holds " + block.buttonName(pair) //$NON-NLS-1$
                        + "; remove_button, parent and before name an item, so two of a name cannot be told apart."); //$NON-NLS-1$
                }
            }
        }
        return null;
    }

    // ---- buttons ----------------------------------------------------------------------------

    /**
     * Adds a button to a panel: the action it runs - exactly one of a procedure and a registered
     * standard action - and the button record beside the others of its block.
     *
     * @param root the layout tree
     * @param language the language code of the new texts
     * @param panelName the panel
     * @param name the button's name
     * @param caption the caption, or {@code null} for the name
     * @param procedure the procedure the button runs, or {@code null}
     * @param action the standard action the button runs, or {@code null}
     * @param picture the index of the picture in the measured dictionary, or {@code null} for the
     *        action's own picture
     * @param parent the name of the submenu to put the button in, or {@code null} for the panel
     * Measured: 15071 buttons. A procedure button carries {@code 1} at position 5 (the majority of
     * the procedure buttons) and a standard-action button carries {@code 0}; the action record is
     * the majority record of {@link StandardActions}.
     *
     * @param before the name of a button of the same block to put the button before, or
     *        {@code null} for the end
     * @return the outcome
     */
    public static Outcome addButton(ListNode root, String language, String panelName, String name, String caption,
        String procedure, StandardActions.Action action, Integer picture, String parent, String before)
    {
        Panel panel = panelNamed(root, panelName);
        if (panel == null)
        {
            return noElement(panelName);
        }
        if (procedure != null && action != null)
        {
            return Outcome.refused("Give either procedure or action, not both."); //$NON-NLS-1$
        }
        if (procedure == null && action == null)
        {
            return Outcome.refused("Give one of procedure and action: the button has to run something."); //$NON-NLS-1$
        }
        if (name == null || name.isEmpty() || !name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) //$NON-NLS-1$
        {
            return Outcome.refused("The button's name must be an identifier."); //$NON-NLS-1$
        }
        if (picture != null && procedure != null)
        {
            return Outcome.refused("A picture is carried by a standard action's record; the procedure actions of " //$NON-NLS-1$
                + "the corpus hold none, so a procedure button takes no picture."); //$NON-NLS-1$
        }
        Outcome taken = duplicateName(panel, name);
        if (taken != null)
        {
            return taken;
        }
        Block block = targetBlock(panel, parent);
        if (block == null)
        {
            return noBlock(panelName, parent);
        }
        int at = insertionAt(block, before);
        if (at < 0)
        {
            return noSuchButton(before);
        }
        ListNode actionRecord;
        if (action != null)
        {
            actionRecord = action.freshRecord();
            if (picture != null)
            {
                ListNode pictureNode = pictureNode(picture.intValue());
                if (pictureNode == null)
                {
                    return Outcome.refused("The picture dictionary holds indexes 0 to " + (PICTURES.length - 1) + "."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                replacePicture(actionRecord, pictureNode);
            }
        }
        else
        {
            actionRecord = resource("PanelProcedureAction"); //$NON-NLS-1$
            substitute(actionRecord, "@U", new Token(UUID.randomUUID().toString())); //$NON-NLS-1$
            String title = caption == null || caption.isEmpty() ? LayoutEditor.presentation(procedure) : caption;
            substitute(actionRecord, "@PROC", Token.string(procedure)); //$NON-NLS-1$
            substitute(actionRecord, "@TITLE", LayoutReader.localizedNode(language, title)); //$NON-NLS-1$
        }
        if (action != null && caption != null && !caption.trim().isEmpty())
        {
            captionIntoAction(actionRecord, language, caption.trim());
        }
        int buttonId = appendAction(panel, actionRecord);
        ListNode button = buttonRecord(root, false);
        String problem = fillButtonRecord(button, language, name, caption, procedure != null, text(panel.content().get(1)),
            buttonId);
        if (problem != null)
        {
            return Outcome.refused(problem);
        }
        insertPair(block, text(actionRecord.get(1)), button, at);
        return Outcome.done("button " + name + " added to " + panel.name() //$NON-NLS-1$ //$NON-NLS-2$
            + (parent == null || parent.isEmpty() ? "" : " under " + parent) + " with id " + buttonId //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + ", running " + (action != null ? "the standard action " + action.name : "the procedure " + procedure)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Adds a submenu to a panel: the submenu button in its block, the action that names it, and
     * an empty buttons block of its own for the buttons that go under it.
     *
     * @param root the layout tree
     * @param language the language code of the new texts
     * @param panelName the panel
     * @param name the submenu's name
     * @param caption the caption, or {@code null} for the name
     * @param parent the name of the submenu to put it in, or {@code null} for the panel
     * Measured: 1489 submenu buttons (kind 1). The flags are {@code 99} on version 25 (89 of 119)
     * and {@code 1e2} on 26 (179 of 260) and 27 (675 of 1106); position 3 is {@code 0} on 25 and
     * 27 and {@code 1} on 26. The submenu's buttons sit in a block of their own in the panel
     * content, named by the action, not inside the button record.
     *
     * @param before the name of a button of the same block to put it before, or {@code null}
     * @return the outcome
     */
    public static Outcome addSubmenu(ListNode root, String language, String panelName, String name, String caption,
        String parent, String before)
    {
        Panel panel = panelNamed(root, panelName);
        if (panel == null)
        {
            return noElement(panelName);
        }
        Block block = targetBlock(panel, parent);
        if (block == null)
        {
            return noBlock(panelName, parent);
        }
        int at = insertionAt(block, before);
        if (at < 0)
        {
            return noSuchButton(before);
        }
        if (name == null || name.isEmpty() || !name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) //$NON-NLS-1$
        {
            return Outcome.refused("The button's name must be an identifier."); //$NON-NLS-1$
        }
        Outcome taken = duplicateName(panel, name);
        if (taken != null)
        {
            return taken;
        }
        // the submenu's own block: a fresh uuid and a number of the button id space, as every
        // measured block of a form's own carries
        String blockUuid = UUID.randomUUID().toString();
        int blockNum = allocateId(panel);
        ListNode action = resource("PanelSubmenuAction"); //$NON-NLS-1$
        substitute(action, "@U", new Token(UUID.randomUUID().toString())); //$NON-NLS-1$
        substitute(action, "@BLOCKUUID", new Token(blockUuid)); //$NON-NLS-1$
        substitute(action, "@BLOCKNUM", new Token(Integer.toString(blockNum))); //$NON-NLS-1$
        appendAction(panel, action);
        ListNode own = resource("PanelSubmenuBlock"); //$NON-NLS-1$
        substitute(own, "@BLOCKUUID", new Token(blockUuid)); //$NON-NLS-1$
        substitute(own, "@BLOCKNUM", new Token(Integer.toString(blockNum))); //$NON-NLS-1$
        substitute(own, "@BLOCKAUX", new Token("0")); //$NON-NLS-1$ //$NON-NLS-2$
        panel.content().items().add(own);
        bumpBlockCount(panel);
        ListNode button = buttonRecord(root, true);
        int buttonId = allocateId(panel);
        String problem = fillButtonRecord(button, language, name, caption, false, text(panel.content().get(1)), buttonId);
        if (problem != null)
        {
            return Outcome.refused(problem);
        }
        substitute(button, "@KIND", new Token("1")); //$NON-NLS-1$ //$NON-NLS-2$
        // the flags and the representation of a submenu differ by version: 99 and 0 on 25, 1e2
        // and 1 on 26, 1e2 and 0 on 27 - the majority of each version's submenus
        String version = text(root.get(0));
        substitute(button, "@FLAGS", new Token("25".equals(version) ? "99" : "1e2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        substitute(button, "@REP", new Token("26".equals(version) ? "1" : "0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        insertPair(block, text(action.get(1)), button, at);
        return Outcome.done("submenu " + name + " added to " + panel.name() + " with id " + buttonId); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Adds a separator to a panel, between the buttons of its block.
     *
     * @param root the layout tree
     * @param panelName the panel
     * @param name the separator's name
     * @param parent the name of the submenu to put it in, or {@code null} for the panel
     * Measured: 4972 separator buttons and 5048 six-item separator actions. The button carries an
     * empty caption {@code {1,0}}, {@code 0} at position 5 and kind {@code 2}.
     *
     * @param before the name of a button of the same block to put it before, or {@code null}
     * @return the outcome
     */
    public static Outcome addSeparator(ListNode root, String panelName, String name, String parent, String before)
    {
        Panel panel = panelNamed(root, panelName);
        if (panel == null)
        {
            return noElement(panelName);
        }
        Block block = targetBlock(panel, parent);
        if (block == null)
        {
            return noBlock(panelName, parent);
        }
        int at = insertionAt(block, before);
        if (at < 0)
        {
            return noSuchButton(before);
        }
        if (name == null || name.isEmpty() || !name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) //$NON-NLS-1$
        {
            return Outcome.refused("The separator's name must be an identifier."); //$NON-NLS-1$
        }
        Outcome taken = duplicateName(panel, name);
        if (taken != null)
        {
            return taken;
        }
        ListNode action = resource("PanelSeparatorAction"); //$NON-NLS-1$
        substitute(action, "@U", new Token(UUID.randomUUID().toString())); //$NON-NLS-1$
        int buttonId = appendAction(panel, action);
        ListNode button = buttonRecord(root, false);
        substitute(button, "@NAME", Token.string(name)); //$NON-NLS-1$
        substitute(button, "@CAPTION", BraceTree.parse("{1,0}")); //$NON-NLS-1$ //$NON-NLS-2$
        substitute(button, "@PIC", new Token("0")); //$NON-NLS-1$ //$NON-NLS-2$
        substitute(button, "@PANEL", new Token(text(panel.content().get(1)))); //$NON-NLS-1$
        substitute(button, "@ID", new Token(Integer.toString(buttonId))); //$NON-NLS-1$
        substitute(button, "@KIND", new Token("2")); //$NON-NLS-1$ //$NON-NLS-2$
        insertPair(block, text(action.get(1)), button, at);
        return Outcome.done("separator " + name + " added to " + panel.name() + " with id " + buttonId); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Removes a button of a panel - a button, a separator or a submenu with every button under
     * it - together with its own buttons block and the action records nothing references any more.
     *
     * @param root the layout tree
     * @param panelName the panel
     * Measured over the 15071 buttons: a submenu takes its block and every action the removed
     * buttons were the last to reference.
     *
     * @param name the button's name
     * @return the outcome, naming the actions that went with it
     */
    public static Outcome removeButton(ListNode root, String panelName, String name)
    {
        Panel panel = panelNamed(root, panelName);
        if (panel == null)
        {
            return noElement(panelName);
        }
        Block found = null;
        int foundPair = -1;
        for (Block block : blocks(panel))
        {
            for (int pair = 0; pair < block.count(); pair++)
            {
                if (name != null && name.equalsIgnoreCase(block.buttonName(pair)))
                {
                    found = block;
                    foundPair = pair;
                }
            }
        }
        if (found == null)
        {
            return Outcome.refused("The panel " + panelName + " holds no button named " + name + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        List<String> uuids = new ArrayList<>();
        String actionUuid = found.actionUuid(foundPair);
        uuids.add(actionUuid);
        collectSubtreeActions(panel, found, foundPair, uuids);
        found.list.items().remove(5 + foundPair * 2 + 1);
        found.list.items().remove(5 + foundPair * 2);
        found.list.items().set(4, new Token(Integer.toString(found.count() - 1)));
        // a submenu that went takes its own block with it
        List<Block> gone = new ArrayList<>();
        for (String uuid : uuids)
        {
            ListNode submenuAction = actionByUuid(panel.content(), uuid);
            ListNode payload = submenuAction == null ? null : LayoutReader.asList(submenuAction.get(4));
            if (payload == null || !ACTION_SUBMENU.equalsIgnoreCase(text(submenuAction.get(3))))
            {
                continue;
            }
            for (Block block : blocks(panel))
            {
                if (block.list == found.list)
                {
                    continue;
                }
                if (text(payload.get(1)).equalsIgnoreCase(block.uuid()) && text(payload.get(2)).equals(block.num()))
                {
                    gone.add(block);
                }
            }
        }
        for (Block block : gone)
        {
            panel.content().items().remove(block.list);
            bumpBlockCount(panel);
        }
        List<String> dropped = dropActions(panel, uuids);
        String message = "button " + name + " removed from " + panel.name(); //$NON-NLS-1$ //$NON-NLS-2$
        if (!gone.isEmpty())
        {
            message += " with its submenu block"; //$NON-NLS-1$
        }
        if (!dropped.isEmpty())
        {
            message += "; the action records went with it: " + String.join(", ", dropped); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return Outcome.done(message);
    }

    /** Gathers the action uuids of a submenu button and of every button under it. */
    private static void collectSubtreeActions(Panel panel, Block block, int pair, List<String> out)
    {
        ListNode action = actionByUuid(panel.content(), block.actionUuid(pair));
        ListNode payload = action == null ? null : LayoutReader.asList(action.get(4));
        if (action == null || payload == null || !ACTION_SUBMENU.equalsIgnoreCase(text(action.get(3))))
        {
            return;
        }
        Block own = blockOf(panel, payload);
        if (own == null)
        {
            return;
        }
        for (int k = 0; k < own.count(); k++)
        {
            String uuid = own.actionUuid(k);
            if (!out.contains(uuid))
            {
                out.add(uuid);
            }
            collectSubtreeActions(panel, own, k, out);
        }
    }

    /** Removes the action records of the uuids that no block references any more. */
    private static List<String> dropActions(Panel panel, List<String> uuids)
    {
        List<String> dropped = new ArrayList<>();
        for (Block block : blocks(panel))
        {
            for (int pair = 0; pair < block.count(); pair++)
            {
                uuids.remove(block.actionUuid(pair));
            }
        }
        ListNode content = panel.content();
        Integer count = LayoutReader.intOf(content.get(4));
        int actions = count == null ? 0 : count.intValue();
        for (int i = 5 + actions - 1; i >= 5; i--)
        {
            ListNode action = LayoutReader.asList(content.get(i));
            if (action != null && uuids.contains(text(action.get(1))))
            {
                dropped.add(describeAction(action));
                content.items().remove(i);
                actions--;
            }
        }
        content.items().set(4, new Token(Integer.toString(actions)));
        return dropped;
    }

    private static String describeAction(ListNode action)
    {
        String type = text(action.get(3));
        if (ACTION_PROCEDURE.equalsIgnoreCase(type))
        {
            return "procedure:" + LayoutReader.handlerProcedure(action.get(4)); //$NON-NLS-1$
        }
        ListNode payload = LayoutReader.asList(action.get(4));
        if (payload != null && payload.size() > 3 && ACTION_STANDARD.equalsIgnoreCase(type))
        {
            StandardActions.Action standard = StandardActions.bySetAndCode(text(payload.get(2)), text(payload.get(3)));
            return "standard:" + (standard != null ? standard.name : "code" + text(payload.get(3))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return type.substring(0, 8);
    }

    // ---- shared pieces ----------------------------------------------------------------------

    /** @return the panel's own buttons block, or the block of the submenu named, or {@code null} */
    private static Block targetBlock(Panel panel, String parent)
    {
        if (parent == null || parent.isEmpty())
        {
            for (Block block : blocks(panel))
            {
                if (MAIN_BLOCK.equalsIgnoreCase(block.uuid()) && MAIN_BLOCK_NUM.equals(block.num()))
                {
                    return block;
                }
            }
            return null;
        }
        for (Block block : blocks(panel))
        {
            for (int pair = 0; pair < block.count(); pair++)
            {
                if (!parent.equalsIgnoreCase(block.buttonName(pair)))
                {
                    continue;
                }
                ListNode action = actionByUuid(panel.content(), block.actionUuid(pair));
                ListNode payload = action == null ? null : LayoutReader.asList(action.get(4));
                if (action != null && payload != null && ACTION_SUBMENU.equalsIgnoreCase(text(action.get(3))))
                {
                    return blockOf(panel, payload);
                }
            }
        }
        return null;
    }

    /** @return the block a submenu action's payload names, or {@code null} */
    private static Block blockOf(Panel panel, ListNode payload)
    {
        String uuid = text(payload.get(1));
        String num = text(payload.get(2));
        for (Block block : blocks(panel))
        {
            if (uuid != null && uuid.equalsIgnoreCase(block.uuid()) && num != null && num.equals(block.num()))
            {
                return block;
            }
        }
        return null;
    }

    /** @return every buttons block of the panel, in the order the content holds them */
    private static List<Block> blocks(Panel panel)
    {
        List<Block> out = new ArrayList<>();
        ListNode content = panel.content();
        for (Node item : content.items())
        {
            ListNode list = LayoutReader.asList(item);
            if (list != null && list.size() >= 6 && "5".equals(text(list.get(0))) && text(list.get(1)) != null) //$NON-NLS-1$
            {
                out.add(new Block(list));
            }
        }
        return out;
    }

    private static ListNode actionByUuid(ListNode content, String uuid)
    {
        Integer count = LayoutReader.intOf(content.get(4));
        int actions = count == null ? 0 : count.intValue();
        for (int i = 5; i < 5 + actions && i < content.size(); i++)
        {
            ListNode action = LayoutReader.asList(content.get(i));
            if (action != null && uuid != null && uuid.equalsIgnoreCase(text(action.get(1))))
            {
                return action;
            }
        }
        return null;
    }

    /** Adds an action record after the last one and returns the id its button takes. */
    private static int appendAction(Panel panel, ListNode action)
    {
        int id = allocateId(panel);
        ListNode content = panel.content();
        Integer count = LayoutReader.intOf(content.get(4));
        int actions = count == null ? 0 : count.intValue();
        content.items().add(5 + actions, action);
        content.items().set(4, new Token(Integer.toString(actions + 1)));
        return id;
    }

    /**
     * The next id of the panel's button id space: above every id in use and the allocator, which
     * is then set to it - the corpus holds the highest id in use there.
     */
    private static int allocateId(Panel panel)
    {
        ListNode content = panel.content();
        int max = 0;
        Integer allocator = LayoutReader.intOf(content.get(2));
        if (allocator != null)
        {
            max = allocator.intValue();
        }
        for (Block block : blocks(panel))
        {
            for (int pair = 0; pair < block.count(); pair++)
            {
                ListNode button = LayoutReader.asList(block.list.get(5 + pair * 2 + 1));
                Integer id = button == null || button.size() < 8 ? null : LayoutReader.intOf(button.get(7));
                if (id != null)
                {
                    max = Math.max(max, id.intValue());
                }
            }
            Integer num = LayoutReader.intOf(block.list.get(2));
            if (num != null)
            {
                max = Math.max(max, num.intValue());
            }
        }
        int id = max + 1;
        if (allocator == null || allocator.intValue() < id)
        {
            content.items().set(2, new Token(Integer.toString(id)));
        }
        return id;
    }

    /** The counted token between the actions and the blocks, one per block. */
    private static void bumpBlockCount(Panel panel)
    {
        ListNode content = panel.content();
        Integer count = LayoutReader.intOf(content.get(4));
        int actions = count == null ? 0 : count.intValue();
        int blocks = 0;
        for (int i = 5 + actions; i < content.size(); i++)
        {
            if (LayoutReader.asList(content.get(i)) != null)
            {
                blocks++;
            }
        }
        content.items().set(5 + actions, new Token(Integer.toString(blocks)));
    }

    /** @return where the new pair goes, or {@code -1} when {@code before} names no button there */
    private static int insertionAt(Block block, String before)
    {
        if (before == null || before.isEmpty())
        {
            return 5 + block.count() * 2;
        }
        for (int pair = 0; pair < block.count(); pair++)
        {
            if (before.equalsIgnoreCase(block.buttonName(pair)))
            {
                return 5 + pair * 2;
            }
        }
        return -1;
    }

    private static void insertPair(Block block, String actionUuid, ListNode button, int at)
    {
        block.list.items().add(at, button);
        block.list.items().add(at, new Token(actionUuid));
        block.list.items().set(4, new Token(Integer.toString(block.count() + 1)));
    }

    /** The button record of the layout's version: lead 7 with thirteen items on 25 and 26, 8 with sixteen on 27. */
    private static ListNode buttonRecord(ListNode root, boolean submenu)
    {
        boolean v27 = "27".equals(text(root.get(0))); //$NON-NLS-1$
        String name = submenu ? (v27 ? "PanelSubmenu8" : "PanelSubmenu7") : (v27 ? "PanelButton8" : "PanelButton7"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return resource(name);
    }

    private static String fillButtonRecord(ListNode button, String language, String name, String caption,
        boolean procedure, String panelUuid, int id)
    {
        if (name == null || name.isEmpty() || !name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) //$NON-NLS-1$
        {
            return "The button's name must be an identifier."; //$NON-NLS-1$
        }
        substitute(button, "@NAME", Token.string(name)); //$NON-NLS-1$
        substitute(button, "@CAPTION", LayoutReader.localizedNode(language, caption == null || caption.isEmpty() //$NON-NLS-1$
            ? name : caption));
        // the number at [5]: 1 on the buttons that run a procedure (4477 of 5330), 0 on the ones
        // that run a standard action (3693 of 4796) - the majority of each kind
        substitute(button, "@PIC", new Token(procedure ? "1" : "0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        substitute(button, "@PANEL", new Token(panelUuid)); //$NON-NLS-1$
        substitute(button, "@ID", new Token(Integer.toString(id))); //$NON-NLS-1$
        substitute(button, "@KIND", new Token("0")); //$NON-NLS-1$ //$NON-NLS-2$
        return null;
    }

    /** Replaces the picture of a standard action's payload. */
    private static void replacePicture(ListNode action, ListNode picture)
    {
        ListNode payload = LayoutReader.asList(action.get(4));
        if (payload != null && payload.size() > 4)
        {
            payload.items().set(4, picture);
        }
    }

    /** @return the picture list of a dictionary index, or {@code null} when the index is outside */
    public static ListNode pictureNode(int index)
    {
        if (index < 0 || index >= PICTURES.length)
        {
            return null;
        }
        return BraceTree.parse(PICTURES[index]);
    }

    /** @return how many pictures the measured dictionary holds */
    public static int pictureCount()
    {
        return PICTURES.length;
    }

    // ---- lookups ----------------------------------------------------------------------------

    private static List<Panel> panels(ListNode root)
    {
        List<Panel> out = new ArrayList<>();
        ListNode children = rootChildren(root);
        if (children != null)
        {
            collectPanels(children, out);
        }
        return out;
    }

    private static void collectPanels(ListNode holder, List<Panel> out)
    {
        for (Node item : holder.items())
        {
            ListNode record = LayoutReader.asList(item);
            if (record == null)
            {
                continue;
            }
            if (isElementRecord(record))
            {
                if (PANEL_TYPE.equalsIgnoreCase(text(record.get(0))))
                {
                    ListNode own = LayoutReader.asList(record.get(2));
                    ListNode inner = own == null ? null : LayoutReader.asList(own.get(1));
                    if (inner != null && inner.size() >= 8)
                    {
                        out.add(new Panel(record, inner));
                    }
                }
                ListNode nested = LayoutReader.childrenOf(record);
                if (nested != null)
                {
                    collectPanels(nested, out);
                }
            }
            else
            {
                collectPanels(record, out);
            }
        }
    }

    private static Panel panelNamed(ListNode root, String name)
    {
        if (name == null)
        {
            return null;
        }
        for (Panel panel : panels(root))
        {
            if (name.equalsIgnoreCase(panel.name()))
            {
                return panel;
            }
        }
        return null;
    }

    private static ListNode elementNamed(ListNode root, String name)
    {
        ListNode children = rootChildren(root);
        return children == null || name == null ? null : findElement(children, name);
    }

    private static ListNode findElement(ListNode holder, String name)
    {
        for (Node item : holder.items())
        {
            ListNode record = LayoutReader.asList(item);
            if (record == null)
            {
                continue;
            }
            if (isElementRecord(record))
            {
                if (name.equalsIgnoreCase(LayoutReader.elementName(LayoutReader.identityOf(record))))
                {
                    return record;
                }
                ListNode nested = LayoutReader.childrenOf(record);
                if (nested != null)
                {
                    ListNode found = findElement(nested, name);
                    if (found != null)
                    {
                        return found;
                    }
                }
            }
            else
            {
                ListNode found = findElement(record, name);
                if (found != null)
                {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isElementRecord(ListNode record)
    {
        return record.size() >= 6 && text(record.get(0)) != null && text(record.get(0)).length() == 36
            && LayoutReader.intOf(record.get(1)) != null;
    }

    private static ListNode rootChildren(ListNode root)
    {
        ListNode body = root.size() > 1 ? LayoutReader.asList(root.get(1)) : null;
        ListNode panel = body == null || body.size() < 3 ? null : LayoutReader.asList(body.get(2));
        return panel == null ? null : LayoutReader.asList(panel.get(2));
    }

    private static Outcome noElement(String name)
    {
        return Outcome.refused("No element named " + name + " on the form."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Outcome noSuchButton(String before)
    {
        return Outcome.refused("No button named " + before + " in that block."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Outcome noBlock(String panelName, String parent)
    {
        return Outcome.refused(parent == null || parent.isEmpty()
            ? "The panel " + panelName + " holds no buttons block where the corpus keeps one." //$NON-NLS-1$ //$NON-NLS-2$
            : "No submenu named " + parent + " in the panel " + panelName + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String text(Node node)
    {
        return LayoutReader.tokenText(node);
    }

    static ListNode resource(String name)
    {
        try (InputStream in = CommandPanels.class.getResourceAsStream("templates/" + name + ".form")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            if (in == null)
            {
                throw new IllegalStateException("no template " + name); //$NON-NLS-1$
            }
            return BraceTree.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            throw new IllegalStateException("the template " + name + " could not be read: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    static void substitute(ListNode node, String placeholder, Node value)
    {
        for (int i = 0; i < node.size(); i++)
        {
            Node item = node.get(i);
            if (item instanceof ListNode)
            {
                substitute((ListNode)item, placeholder, value);
            }
            else if (item instanceof Token && placeholder.equals(((Token)item).isString()
                ? ((Token)item).value() : ((Token)item).text()))
            {
                // a placeholder inside quotes ("@NAME") is the whole string token
                node.items().set(i, value);
            }
        }
    }
}
