/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.BraceTree.Node;
import ru.aiedt.mcp.oform.BraceTree.Token;

/**
 * The anchors of an element's four edges - to the form, to another element, or none - and the
 * counted back references the targets carry for them.
 *
 * <p>Measured over the corpus (1335 forms) and the platform dump: the anchor slots sit in the
 * common list at positions 6 to 9 (top, bottom, left, right), each a counted pair of records
 * {@code {2, <target>, <edge>, <offset>}} of which the first is the anchor: my edge equals the
 * target's edge plus the offset. The target {@code -1} with the edge 6 is no anchor; the target
 * {@code 0} is the form; the target naming the element itself carries its own size (the Designer
 * writes the bottom as the top plus the height, the right as the left plus the width) and is no
 * binding. Edge codes: 0 top, 1 bottom, 2 left, 3 right. Against the box geometry, 19596 anchors
 * whose target is the element itself match and 8 do not, and 6172 anchors to another element
 * match and 1 does not - 25768 and 9. The back references are four counted lists behind the
 * slots: the list of the target's edge holds {@code {0, <anchored element>, <that element's own
 * edge code>}} - 5038 anchors confirm both halves, 511 sit in that list with another code and
 * 624 sit in another list. A target that is the form keeps the same four lists in the root
 * panel's properties behind the style block, 16607 entries. Edge codes 4 and 5 occur (74 and 56
 * anchors to the form) and are not written.</p>
 */
public final class Anchors
{
    /** The slot of each edge in the common list; the edge code of an edge is its slot less six. */
    public static final int SLOT_TOP = 6;

    public static final int SLOT_BOTTOM = 7;

    public static final int SLOT_LEFT = 8;

    public static final int SLOT_RIGHT = 9;

    /** The head of the common list ends here; the four counted back-reference lists follow. */
    private static final int BACK_LISTS_FROM = 12;

    private static final String NO_ANCHOR = "{2,-1,6,0}"; //$NON-NLS-1$

    /** The id an anchor names when it binds the form itself. */
    private static final String FORM_ID = "0"; //$NON-NLS-1$

    /** An element record with the list that holds it and its parent's record. */
    static final class Located
    {
        final ListNode record;

        final ListNode holder;

        final ListNode parentRecord;

        Located(ListNode record, ListNode holder, ListNode parentRecord)
        {
            this.record = record;
            this.holder = holder;
            this.parentRecord = parentRecord;
        }

        String name()
        {
            return LayoutReader.elementName(LayoutReader.identityOf(record));
        }

        String type()
        {
            return LayoutReader.typeName(LayoutReader.tokenText(record.get(0)));
        }

        String id()
        {
            return LayoutReader.tokenText(record.get(1));
        }
    }

    private Anchors()
    {
        // static registry
    }

    /**
     * Binds, rebinds or removes the anchor of one edge of an element.
     *
     * @param root the layout tree
     * @param elementName the element whose edge to bind
     * @param edge the edge as the argument names it: top, bottom, left or right
     * @param value {@code form}, {@code none}, or {@code <element>.<edge>} naming the target and
     *        its edge the offset counts from
     * @param offset the offset in the units of the form, 0 when absent
     * @return the outcome
     */
    public static LayoutEditor.Outcome set(ListNode root, String elementName, String edge, String value, Integer offset)
    {
        Located located = locate(root, elementName);
        if (located == null)
        {
            return LayoutEditor.refusal("No element named " + elementName + " on the form."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Integer slot = slotOf(edge);
        if (slot == null)
        {
            return LayoutEditor.refusal("The edge must be one of top, bottom, left, right: " + edge + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (value == null || value.isEmpty())
        {
            return LayoutEditor.refusal("set_property anchor needs value: form, none, or <element>.<edge>."); //$NON-NLS-1$
        }
        int off = offset == null ? 0 : offset.intValue();
        ListNode common = LayoutReader.commonOf(located.record);
        if (common == null || common.size() <= slot.intValue() || LayoutReader.asList(common.get(slot.intValue())) == null)
        {
            return LayoutEditor.refusal("The record of " + located.name() + " has no anchor slot where the corpus holds one."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String target;
        int targetEdge;
        if ("form".equalsIgnoreCase(value.trim())) //$NON-NLS-1$
        {
            target = FORM_ID;
            // the form's edge of the same name: my left binds the form's left
            targetEdge = slot.intValue() - SLOT_TOP;
        }
        else if ("none".equalsIgnoreCase(value.trim())) //$NON-NLS-1$
        {
            target = null;
            targetEdge = -1;
        }
        else
        {
            int dot = value.trim().lastIndexOf('.');
            if (dot <= 0 || dot == value.trim().length() - 1)
            {
                return LayoutEditor.refusal("The anchor target must be form, none, or <element>.<edge>: " + value + "."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String targetName = value.trim().substring(0, dot);
            String targetEdgeName = value.trim().substring(dot + 1);
            Integer targetSlot = slotOf(targetEdgeName);
            if (targetSlot == null)
            {
                return LayoutEditor.refusal("The target's edge must be one of top, bottom, left, right: " + targetEdgeName + "."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            Located targetLocated = locate(root, targetName);
            if (targetLocated == null)
            {
                return LayoutEditor.refusal("No element named " + targetName + " on the form."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            String problem = checkTarget(located, targetLocated, root);
            if (problem != null)
            {
                return LayoutEditor.refusal(problem);
            }
            target = targetLocated.id();
            targetEdge = targetSlot.intValue() - SLOT_TOP;
        }
        // the back reference of the binding this edge carries now goes first: a binding to the
        // form and a binding to another element each leave one; the canonical own-size anchors
        // (the bottom on the own top, the right on the own left) leave none, and a self anchor
        // that names the opposite edge leaves one in the element's own lists
        ListNode slotList = LayoutReader.asList(common.get(slot.intValue()));
        ListNode anchor = slotList != null && slotList.size() > 1 ? LayoutReader.asList(slotList.get(1)) : null;
        String oldTarget = anchor != null && anchor.size() == 4 ? LayoutReader.tokenText(anchor.get(1)) : null;
        int oldEdge = anchor != null && anchor.size() == 4 ? edgeCode(LayoutReader.tokenText(anchor.get(2))) : -1;
        if (oldTarget != null && !oldTarget.equals("-1") && oldEdge >= 0) //$NON-NLS-1$
        {
            removeBackReference(root, oldTarget, oldEdge, located.id(), slot.intValue() - SLOT_TOP);
        }
        if (target == null)
        {
            slotList.items().set(1, BraceTree.parse(NO_ANCHOR));
            return LayoutEditor.Outcome.done(edge + " of " + located.name() + " unanchored"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        slotList.items().set(1, LayoutEditor.list(new Token("2"), new Token(target), //$NON-NLS-1$
            new Token(Integer.toString(targetEdge)), new Token(Integer.toString(off))));
        String backProblem = addBackReference(root, target, targetEdge, located.id(), slot.intValue() - SLOT_TOP);
        if (backProblem != null)
        {
            return LayoutEditor.refusal(backProblem);
        }
        String words = FORM_ID.equals(target) ? "the form" //$NON-NLS-1$
            : nameOfTarget(root, target) + "." + edgeWord(targetEdge); //$NON-NLS-1$
        return LayoutEditor.Outcome.done(edge + " of " + located.name() + " anchored to " + words //$NON-NLS-1$ //$NON-NLS-2$
            + (off != 0 ? " with offset " + off : "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Why the target cannot take this element's binding: it is the element itself, or it does not
     * sit beside it in the same holder and on the same page, or the anchors of the target lead
     * back to the element and would close a cycle.
     */
    private static String checkTarget(Located me, Located target, ListNode root)
    {
        if (target.id().equals(me.id()))
        {
            return "An edge cannot anchor to the element itself: " + me.name() + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (target.holder != me.holder)
        {
            return "The anchor target " + target.name() + " does not sit in the same " //$NON-NLS-1$ //$NON-NLS-2$
                + (me.parentRecord == null ? "form" : "panel") + " as " + me.name() + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        Integer myPage = LayoutReader.pageIndex(LayoutReader.commonOf(me.record));
        Integer targetPage = LayoutReader.pageIndex(LayoutReader.commonOf(target.record));
        if (myPage != null && targetPage != null && !myPage.equals(targetPage))
        {
            return "The anchor target " + target.name() + " sits on another page than " + me.name() + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (cycleThrough(root, target, me.id()))
        {
            return "Anchoring to " + target.name() + " would close a cycle of anchors."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /** Whether the anchors of the target, followed target to target, reach the element. */
    private static boolean cycleThrough(ListNode root, Located from, String myId)
    {
        Map<String, ListNode> byId = new HashMap<>();
        for (Located one : allElements(root))
        {
            byId.put(one.id(), one.record);
        }
        Set<String> seen = new HashSet<>();
        List<ListNode> work = new ArrayList<>();
        work.add(from.record);
        while (!work.isEmpty())
        {
            ListNode record = work.remove(work.size() - 1);
            String id = LayoutReader.tokenText(record.get(1));
            if (!seen.add(id))
            {
                continue;
            }
            ListNode common = LayoutReader.commonOf(record);
            if (common == null)
            {
                continue;
            }
            for (int slot = SLOT_TOP; slot <= SLOT_RIGHT; slot++)
            {
                if (slot >= common.size())
                {
                    continue;
                }
                ListNode anchor = anchorAt(common, slot);
                if (anchor == null)
                {
                    continue;
                }
                String next = LayoutReader.tokenText(anchor.get(1));
                if (next == null || next.equals(FORM_ID) || next.equals("-1") || next.equals(id)) //$NON-NLS-1$
                {
                    continue;
                }
                if (next.equals(myId))
                {
                    return true;
                }
                ListNode nextRecord = byId.get(next);
                if (nextRecord != null)
                {
                    work.add(nextRecord);
                }
            }
        }
        return false;
    }

    /** The anchor record a slot's first position holds, or {@code null} when the slot holds none. */
    private static ListNode anchorAt(ListNode common, int slot)
    {
        ListNode slotList = LayoutReader.asList(common.get(slot));
        if (slotList == null || slotList.size() < 2)
        {
            return null;
        }
        ListNode anchor = LayoutReader.asList(slotList.get(1));
        return anchor != null && anchor.size() == 4 && "2".equals(LayoutReader.tokenText(anchor.get(0))) ? anchor : null; //$NON-NLS-1$
    }

    /** The four edges in words, for the answer and the reading. */
    private static String edgeWord(int code)
    {
        switch (code)
        {
        case 0:
            return "top"; //$NON-NLS-1$
        case 1:
            return "bottom"; //$NON-NLS-1$
        case 2:
            return "left"; //$NON-NLS-1$
        default:
            return "right"; //$NON-NLS-1$
        }
    }

    private static Integer slotOf(String edge)
    {
        if (edge == null)
        {
            return null;
        }
        switch (edge.trim().toLowerCase(java.util.Locale.ROOT))
        {
        case "top": //$NON-NLS-1$
            return Integer.valueOf(SLOT_TOP);
        case "bottom": //$NON-NLS-1$
            return Integer.valueOf(SLOT_BOTTOM);
        case "left": //$NON-NLS-1$
            return Integer.valueOf(SLOT_LEFT);
        case "right": //$NON-NLS-1$
            return Integer.valueOf(SLOT_RIGHT);
        default:
            return null;
        }
    }

    private static int edgeCode(String token)
    {
        if (token == null)
        {
            return -1;
        }
        try
        {
            return Integer.parseInt(token);
        }
        catch (NumberFormatException e)
        {
            return -1;
        }
    }

    // ---- releasing --------------------------------------------------------------------------

    /**
     * An anchor slot of {@code common} that names an id in {@code ids} becomes no anchor, and the
     * counted back references that name those ids go. {@link LayoutEditor#removeElement} walks
     * every remaining element with the ids of the removed subtree; a move walks the element and
     * its former neighbours with each other's ids. Slots 6 to 11 are cleared, the four edges and
     * the two the writer does not bind, which is what removal has always cleared.
     *
     * @param root the layout, so a target id can be named in the answer
     * @param common the common list whose slots to clear, or {@code null}
     * @param who the element's name, for the words of what went
     * @param ids the element ids an anchor may no longer name
     * @return the anchors that went, as {@code who.edge -> target.edge}; empty when nothing did
     */
    public static List<String> dropAnchorsNaming(ListNode root, ListNode common, String who, Set<String> ids)
    {
        List<String> released = new ArrayList<>();
        if (common == null || common.size() < BACK_LISTS_FROM || ids == null || ids.isEmpty())
        {
            return released;
        }
        for (int i = SLOT_TOP; i < BACK_LISTS_FROM; i++)
        {
            ListNode slot = LayoutReader.asList(common.get(i));
            if (slot == null)
            {
                continue;
            }
            for (int k = 1; k < slot.size(); k++)
            {
                ListNode anchor = LayoutReader.asList(slot.get(k));
                if (anchor != null && anchor.size() == 4 && ids.contains(LayoutReader.tokenText(anchor.get(1))))
                {
                    released.add(releasedWords(root, who, i, anchor));
                    slot.items().set(k, BraceTree.parse(NO_ANCHOR));
                }
            }
        }
        dropCountedBackReferences(common, BACK_LISTS_FROM, 4, ids);
        return released;
    }

    /**
     * Drops entries that name {@code ids} from the four counted back-reference lists the root
     * panel keeps for anchors to the form.
     *
     * @param root the layout
     * @param ids the element ids
     */
    public static void dropFormBackReferences(ListNode root, Set<String> ids)
    {
        ListNode panel = rootPanel(root);
        if (panel == null || ids == null || ids.isEmpty())
        {
            return;
        }
        ListNode own = LayoutReader.asList(panel.get(1));
        ListNode props = own == null || own.size() < 2 ? null : LayoutReader.asList(own.get(1));
        if (props == null || props.size() < 3)
        {
            return;
        }
        dropCountedBackReferences(props, 2, 4, ids);
    }

    /** {@code who.edge -> target.edge}, with the offset when it is not zero. */
    private static String releasedWords(ListNode root, String who, int slot, ListNode anchor)
    {
        String target = LayoutReader.tokenText(anchor.get(1));
        int code = edgeCode(LayoutReader.tokenText(anchor.get(2)));
        Integer off = LayoutReader.intOf(anchor.get(3));
        String targetWords;
        if (FORM_ID.equals(target))
        {
            targetWords = "form"; //$NON-NLS-1$
        }
        else if (target == null || "-1".equals(target)) //$NON-NLS-1$
        {
            targetWords = "none"; //$NON-NLS-1$
        }
        else
        {
            targetWords = nameOfTarget(root, target) + "." + edgeWordOrIndex(code); //$NON-NLS-1$
        }
        if (off != null && off.intValue() != 0 && !"none".equals(targetWords)) //$NON-NLS-1$
        {
            targetWords += off.intValue() > 0 ? "+" + off.intValue() : Integer.toString(off.intValue()); //$NON-NLS-1$
        }
        return (who == null ? "?" : who) + "." + edgeWordOrIndex(slot - SLOT_TOP) + " -> " + targetWords; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The four edges in words; a slot past them keeps its index, the way the corpus records it. */
    private static String edgeWordOrIndex(int code)
    {
        if (code >= 0 && code <= 3)
        {
            return edgeWord(code);
        }
        return "edge" + code; //$NON-NLS-1$
    }

    // ---- the back references -----------------------------------------------------------------

    /** Drops {@code {0, <id>, <code>}} of one element from one counted list of its target. */
    private static void removeBackReference(ListNode root, String targetId, int targetEdge, String elementId, int code)
    {
        ListNode lists = backReferenceHolder(root, targetId);
        if (lists == null)
        {
            return;
        }
        int at = backListAt(lists, targetEdge);
        if (at < 0)
        {
            return;
        }
        Integer count = LayoutReader.intOf(lists.get(at));
        if (count == null)
        {
            return;
        }
        int kept = 0;
        int k = at + 1;
        for (int seen = 0; seen < count.intValue() && k < lists.size(); seen++)
        {
            ListNode entry = LayoutReader.asList(lists.get(k));
            if (entry != null && entry.size() == 3 && elementId.equals(LayoutReader.tokenText(entry.get(1)))
                && Integer.toString(code).equals(LayoutReader.tokenText(entry.get(2))))
            {
                lists.items().remove(k);
            }
            else
            {
                kept++;
                k++;
            }
        }
        lists.items().set(at, new Token(Integer.toString(kept)));
    }

    /**
     * Adds {@code {0, <id>, <code>}} to the counted list of the target's edge.
     *
     * @return why it cannot, or {@code null} when it did
     */
    private static String addBackReference(ListNode root, String targetId, int targetEdge, String elementId, int code)
    {
        if (FORM_ID.equals(targetId))
        {
            ListNode panel = rootPanel(root);
            ListNode own = panel == null ? null : LayoutReader.asList(panel.get(1));
            ListNode props = own == null || own.size() < 2 ? null : LayoutReader.asList(own.get(1));
            if (props == null || props.size() < 6)
            {
                return "The form's root panel carries no back-reference lists where the corpus holds them."; //$NON-NLS-1$
            }
            return insertEntry(props, 2, targetEdge, elementId, code, "the form"); //$NON-NLS-1$
        }
        Located target = null;
        for (Located one : allElements(root))
        {
            if (targetId.equals(one.id()))
            {
                target = one;
                break;
            }
        }
        if (target == null)
        {
            return "No element with id " + targetId + " on the form."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        ListNode common = LayoutReader.commonOf(target.record);
        if (common == null || common.size() < BACK_LISTS_FROM + 4)
        {
            return target.name() + " carries no back-reference lists where the corpus holds them."; //$NON-NLS-1$
        }
        return insertEntry(common, BACK_LISTS_FROM, targetEdge, elementId, code, target.name());
    }

    /** Puts the entry into one of the four counted lists that start at {@code from}. */
    private static String insertEntry(ListNode lists, int from, int edge, String elementId, int code, String who)
    {
        int at = backListAtFrom(lists, from, edge);
        if (at < 0)
        {
            return "The back-reference lists of " + who + " were not found where the corpus holds them."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        Integer count = LayoutReader.intOf(lists.get(at));
        if (count == null || count.intValue() < 0)
        {
            return "The back-reference list of " + who + " does not count its entries."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (int i = 1; i <= count.intValue(); i++)
        {
            ListNode entry = LayoutReader.asList(lists.get(at + i));
            if (entry != null && entry.size() == 3 && elementId.equals(LayoutReader.tokenText(entry.get(1)))
                && LayoutReader.tokenText(entry.get(2)) != null && LayoutReader.tokenText(entry.get(2)).equals(Integer.toString(code)))
            {
                return null; // already there
            }
        }
        lists.items().add(at + 1 + count.intValue(),
            LayoutEditor.list(new Token("0"), new Token(elementId), new Token(Integer.toString(code)))); //$NON-NLS-1$
        lists.items().set(at, new Token(Integer.toString(count.intValue() + 1)));
        return null;
    }

    /** Drops every {@code {0, <id>, <code>}} that names {@code ids} from {@code lists} counted lists. */
    private static void dropCountedBackReferences(ListNode list, int from, int lists, Set<String> ids)
    {
        int i = from;
        for (int n = 0; n < lists; n++)
        {
            Integer count = i < list.size() ? LayoutReader.intOf(list.get(i)) : null;
            if (count == null)
            {
                return;
            }
            int kept = 0;
            int k = i + 1;
            for (int seen = 0; seen < count.intValue() && k < list.size(); seen++)
            {
                ListNode entry = LayoutReader.asList(list.get(k));
                if (entry != null && entry.size() == 3 && ids.contains(LayoutReader.tokenText(entry.get(1))))
                {
                    list.items().remove(k);
                }
                else
                {
                    kept++;
                    k++;
                }
            }
            list.items().set(i, new Token(Integer.toString(kept)));
            i = k;
        }
    }

    /** The list that holds an element's back references: its common list, or the root panel's properties. */
    private static ListNode backReferenceHolder(ListNode root, String targetId)
    {
        if (FORM_ID.equals(targetId))
        {
            ListNode panel = rootPanel(root);
            ListNode own = panel == null ? null : LayoutReader.asList(panel.get(1));
            return own == null || own.size() < 2 ? null : LayoutReader.asList(own.get(1));
        }
        for (Located one : allElements(root))
        {
            if (targetId.equals(one.id()))
            {
                return LayoutReader.commonOf(one.record);
            }
        }
        return null;
    }

    /** The index of the count token of back list {@code edge} in a common list, or -1. */
    private static int backListAt(ListNode common, int edge)
    {
        return backListAtFrom(common, BACK_LISTS_FROM, edge);
    }

    private static int backListAtFrom(ListNode list, int from, int edge)
    {
        int i = from;
        for (int k = 0; k < 4; k++)
        {
            if (i >= list.size())
            {
                return -1;
            }
            Integer count = LayoutReader.intOf(list.get(i));
            if (count == null)
            {
                return -1;
            }
            if (k == edge)
            {
                return i;
            }
            i += 1 + count.intValue();
        }
        return -1;
    }

    // ---- reading ---------------------------------------------------------------------------

    /**
     * The anchors of an element in words, for the layout answer: {@code left=form},
     * {@code top=Поле1.bottom+4}, {@code right=none}. An edge bound to the element itself carries
     * its own size and is the Designer's default, so it is not named.
     *
     * @param record the element's record
     * @param namesById the name of every element by its id
     * @return the words of the edges that carry a binding, empty when none does
     */
    static Map<String, Object> read(ListNode record, Map<String, String> namesById)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        ListNode common = LayoutReader.commonOf(record);
        String myId = LayoutReader.tokenText(record.get(1));
        if (common == null)
        {
            return out;
        }
        for (int slot = SLOT_TOP; slot <= SLOT_RIGHT && slot < common.size(); slot++)
        {
            ListNode anchor = anchorAt(common, slot);
            if (anchor == null)
            {
                continue;
            }
            String target = LayoutReader.tokenText(anchor.get(1));
            if (target == null || target.equals(myId))
            {
                continue;
            }
            Integer off = LayoutReader.intOf(anchor.get(3));
            String words;
            if (target.equals("-1")) //$NON-NLS-1$
            {
                words = "none"; //$NON-NLS-1$
            }
            else if (target.equals(FORM_ID))
            {
                words = "form"; //$NON-NLS-1$
            }
            else
            {
                Integer code = LayoutReader.intOf(anchor.get(2));
                String name = namesById.get(target);
                words = (name != null ? name : "element#" + target) + "." //$NON-NLS-1$ //$NON-NLS-2$
                    + (code != null ? edgeWord(code.intValue()) : "?"); //$NON-NLS-1$
            }
            if (off != null && off.intValue() != 0 && !words.equals("none")) //$NON-NLS-1$
            {
                words += off.intValue() > 0 ? "+" + off.intValue() : Integer.toString(off.intValue()); //$NON-NLS-1$
            }
            out.put(edgeWord(slot - SLOT_TOP), words);
        }
        return out;
    }

    // ---- walking ---------------------------------------------------------------------------

    /** The element records of the whole form, the nested after their parent. */
    static List<Located> allElements(ListNode root)
    {
        List<Located> out = new ArrayList<>();
        ListNode children = rootChildren(root);
        if (children != null)
        {
            collect(children, null, out);
        }
        return out;
    }

    static Located locate(ListNode root, String name)
    {
        if (name == null)
        {
            return null;
        }
        for (Located located : allElements(root))
        {
            if (name.equalsIgnoreCase(located.name()))
            {
                return located;
            }
        }
        return null;
    }

    private static void collect(ListNode holder, ListNode parentRecord, List<Located> out)
    {
        for (Node item : holder.items())
        {
            ListNode record = LayoutReader.asList(item);
            if (record == null)
            {
                continue;
            }
            if (LayoutReader.isElementRecord(record))
            {
                out.add(new Located(record, holder, parentRecord));
                ListNode nested = LayoutReader.childrenOf(record);
                if (nested != null)
                {
                    collect(nested, record, out);
                }
            }
            else
            {
                collect(record, parentRecord, out);
            }
        }
    }

    private static ListNode body(ListNode root)
    {
        return root != null && root.size() > 1 ? LayoutReader.asList(root.get(1)) : null;
    }

    private static ListNode rootPanel(ListNode root)
    {
        ListNode body = body(root);
        ListNode panel = body == null || body.size() < 3 ? null : LayoutReader.asList(body.get(2));
        return panel != null && panel.size() >= 3 && LayoutReader.PANEL_TYPE.equalsIgnoreCase(LayoutReader.tokenText(panel.get(0)))
            ? panel : null;
    }

    private static ListNode rootChildren(ListNode root)
    {
        ListNode panel = rootPanel(root);
        return panel == null ? null : LayoutReader.asList(panel.get(2));
    }

    private static String nameOfTarget(ListNode root, String targetId)
    {
        for (Located one : allElements(root))
        {
            if (targetId.equals(one.id()))
            {
                return one.name();
            }
        }
        return "element#" + targetId; //$NON-NLS-1$
    }
}
