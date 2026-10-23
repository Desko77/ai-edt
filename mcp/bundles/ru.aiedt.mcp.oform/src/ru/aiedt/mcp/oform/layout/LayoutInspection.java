/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.HandlerBindings;
import ru.aiedt.mcp.oform.OrdinaryFormModule;

/**
 * The read-only inspection of one layout the reader has yielded: what the layout says that the
 * platform would refuse or the client would render broken, and which parts of it no check reached.
 *
 * <p>Everything here reads the model {@link LayoutReader} builds and the raw records it came from;
 * nothing is written and nothing is corrected. The checks are the ones the model can actually
 * answer: duplicate ids and names inside their namespaces, references to pages, elements,
 * attributes and anchor targets that the layout does not hold, handlers the module does not
 * declare, boxes whose right lies before their left, and the two geometry warnings - siblings of
 * one holder and page that overlap, a child beyond its parent's box - in which a Group or a Panel
 * frame is never a party, because a frame wraps what sits inside it. What the model does not know
 * is not guessed at: every such part is named in the coverage, and findings absent over a part the
 * coverage names as not performed say nothing about it.</p>
 */
public final class LayoutInspection
{
    /** The anchor slots the reader reads as the four edges; the two past them are not read. */
    private static final int[] EDGE_SLOTS = { Anchors.SLOT_TOP, Anchors.SLOT_BOTTOM, Anchors.SLOT_LEFT,
        Anchors.SLOT_RIGHT };

    /** The ids a command panel records when its actions have no element source. */
    private static final Set<String> NO_ACTION_SOURCE = Set.of("0", "4294967295"); //$NON-NLS-1$ //$NON-NLS-2$

    private LayoutInspection()
    {
        // static inspection
    }

    /**
     * Inspects one layout.
     *
     * @param root the brace tree of the {@code form} entry
     * @param layout the model the reader built of that tree
     * @param layoutText the layout's text, for the procedures bound anywhere in it
     * @param moduleText the module's text, or {@code null} when the container holds no module entry
     * @param names what the configuration knows, or {@code null} for nothing
     * @return the answer as a map: {@code findings} and {@code coverage}, each a list of entries,
     *         and the counts of findings, errors and warnings
     */
    public static Map<String, Object> inspect(ListNode root, FormLayout layout, String layoutText, String moduleText,
        LayoutNames names)
    {
        List<Map<String, Object>> findings = new ArrayList<>();
        List<FormLayout.Element> elements = layout.allElements();
        List<Anchors.Located> records = Anchors.allElements(root);
        Set<String> recordIds = new LinkedHashSet<>();
        Map<String, String> nameOfRecord = new TreeMap<>();
        for (Anchors.Located one : records)
        {
            recordIds.add(one.id());
            if (one.name() != null)
            {
                nameOfRecord.put(one.id(), one.name());
            }
        }
        checkNamespaces(layout, elements, findings);
        checkPageReferences(layout, findings);
        checkMainAttribute(layout, findings);
        int memberSegments = checkBindings(layout, recordIds, findings);
        checkColumnMembers(layout, names, findings);
        checkHandlers(layout, layoutText, moduleText, findings);
        checkActionSources(records, recordIds, findings);
        checkAnchors(records, recordIds, nameOfRecord, findings);
        checkBoxes(elements, findings);
        int[] geometryCounts = checkGeometry(layout, elements, findings);
        List<Map<String, Object>> coverage = coverage(layout, elements, moduleText, names, memberSegments,
            geometryCounts);
        int errors = 0;
        int warnings = 0;
        for (Map<String, Object> finding : findings)
        {
            if ("warning".equals(finding.get("severity"))) //$NON-NLS-1$
            {
                warnings++;
            }
            else
            {
                errors++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("findings", findings); //$NON-NLS-1$
        out.put("coverage", coverage); //$NON-NLS-1$
        out.put("findingCount", Integer.valueOf(findings.size())); //$NON-NLS-1$
        out.put("errorCount", Integer.valueOf(errors)); //$NON-NLS-1$
        out.put("warningCount", Integer.valueOf(warnings)); //$NON-NLS-1$
        out.put("elementCount", Integer.valueOf(elements.size())); //$NON-NLS-1$
        boolean complete = true;
        for (Map<String, Object> entry : coverage)
        {
            if (!"performed".equals(entry.get("status"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                complete = false;
                break;
            }
        }
        if (!complete)
        {
            out.put("note", "the parts coverage names as not performed were not checked: no findings over them " //$NON-NLS-1$
                + "is not a statement that the form is correct"); //$NON-NLS-1$
        }
        return out;
    }

    // ---- namespaces -------------------------------------------------------------------------

    /** Duplicate ids, element names, page names per holder, attribute names, button names per panel. */
    private static void checkNamespaces(FormLayout layout, List<FormLayout.Element> elements,
        List<Map<String, Object>> findings)
    {
        Map<String, List<String>> byId = new TreeMap<>();
        Map<String, List<String>> byName = new TreeMap<>();
        for (FormLayout.Element element : elements)
        {
            if (element.id != null)
            {
                byId.computeIfAbsent(element.id, k -> new ArrayList<>()).add(word(element));
            }
            if (element.name != null)
            {
                byName.computeIfAbsent(element.name.toLowerCase(), k -> new ArrayList<>()).add(word(element));
            }
        }
        reportDuplicates(byId, "duplicate-element-id", "Two elements carry the id", findings); //$NON-NLS-1$ //$NON-NLS-2$
        reportDuplicates(byName, "duplicate-element-name", "Two elements carry the name", //$NON-NLS-1$ //$NON-NLS-2$
            findings);
        checkPageNames(layout.pages, "the form", findings); //$NON-NLS-1$
        Map<String, List<String>> attributeNames = new TreeMap<>();
        for (FormLayout.Attribute attribute : layout.attributes)
        {
            attributeNames.computeIfAbsent(attribute.name.toLowerCase(), k -> new ArrayList<>())
                .add(attribute.name);
        }
        reportDuplicates(attributeNames, "duplicate-attribute-name", "Two attributes carry the name", //$NON-NLS-1$ //$NON-NLS-2$
            findings);
        for (FormLayout.Element element : elements)
        {
            checkPageNames(element.pages, word(element), findings);
            if (!element.buttons.isEmpty())
            {
                Map<String, List<String>> buttonNames = new TreeMap<>();
                collectButtonNames(element.buttons, buttonNames);
                reportDuplicates(buttonNames, "duplicate-button-name", //$NON-NLS-1$
                    "Two buttons of " + word(element) + " carry the name", findings); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /** The pages of one holder: their names must differ inside that holder. */
    private static void checkPageNames(List<FormLayout.Page> pages, String holder, List<Map<String, Object>> findings)
    {
        if (pages.size() < 2)
        {
            return;
        }
        Map<String, List<String>> byName = new TreeMap<>();
        for (FormLayout.Page page : pages)
        {
            byName.computeIfAbsent(page.name.toLowerCase(), k -> new ArrayList<>()).add(page.name);
        }
        reportDuplicates(byName, "duplicate-page-name", "Two pages of " + holder + " carry the name", findings); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The names of a panel's buttons, the buttons of a submenu included. */
    private static void collectButtonNames(List<FormLayout.Button> buttons, Map<String, List<String>> into)
    {
        for (FormLayout.Button button : buttons)
        {
            into.computeIfAbsent(button.name.toLowerCase(), k -> new ArrayList<>()).add(button.name);
            Object children = button.details.get("children"); //$NON-NLS-1$
            if (children instanceof List<?> nested)
            {
                collectButtonNameMaps(nested, into);
            }
        }
    }

    /** The nested buttons a submenu holds, which the reader answers as maps. */
    private static void collectButtonNameMaps(List<?> maps, Map<String, List<String>> into)
    {
        for (Object one : maps)
        {
            if (!(one instanceof Map<?, ?> map) || !(map.get("name") instanceof String name)) //$NON-NLS-1$
            {
                continue;
            }
            into.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(name);
            if (map.get("children") instanceof List<?> nested) //$NON-NLS-1$
            {
                collectButtonNameMaps(nested, into);
            }
        }
    }

    /** One finding per name two members of a namespace carry. */
    private static void reportDuplicates(Map<String, List<String>> byKey, String check, String lead,
        List<Map<String, Object>> findings)
    {
        for (List<String> carriers : byKey.values())
        {
            if (carriers.size() > 1)
            {
                finding(findings, check, "error", carriers.get(0), //$NON-NLS-1$
                    lead + ": " + String.join(", ", carriers)); //$NON-NLS-1$
            }
        }
    }

    /** A page ordinal past the pages the element's holder reads; a holder without pages is not judged. */
    private static void checkPageReferences(FormLayout layout, List<Map<String, Object>> findings)
    {
        checkPagesOfHolder(layout.elements, layout.pages, findings);
        for (FormLayout.Element element : layout.allElements())
        {
            checkPagesOfHolder(element.children, element.pages, findings);
        }
    }

    private static void checkPagesOfHolder(List<FormLayout.Element> children, List<FormLayout.Page> pages,
        List<Map<String, Object>> findings)
    {
        if (pages.isEmpty())
        {
            return;
        }
        for (FormLayout.Element child : children)
        {
            if (child.pageIndex != null
                && (child.pageIndex.intValue() < 0 || child.pageIndex.intValue() >= pages.size()))
            {
                finding(findings, "page-reference-missing", "error", word(child), //$NON-NLS-1$ //$NON-NLS-2$
                    "the page ordinal " + child.pageIndex + " is past the " + pages.size() //$NON-NLS-1$ //$NON-NLS-2$
                        + " pages its holder reads"); //$NON-NLS-1$
            }
        }
    }

    /** The main attribute the layout names must be one the attribute table holds. */
    private static void checkMainAttribute(FormLayout layout, List<Map<String, Object>> findings)
    {
        if (layout.mainAttributeId == null)
        {
            return;
        }
        for (FormLayout.Attribute attribute : layout.attributes)
        {
            if (attribute.id.equals(layout.mainAttributeId))
            {
                return;
            }
        }
        finding(findings, "main-attribute-missing", "error", "the form", //$NON-NLS-1$ //$NON-NLS-2$
            "the main attribute id " + layout.mainAttributeId + " is not one of the attribute table's"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- references -------------------------------------------------------------------------

    /**
     * The data paths: a binding whose element no record carries, a first segment naming an
     * attribute the table does not hold, a current-row segment naming an element that is not there.
     *
     * @return how many segments name a metadata object by uuid
     */
    private static int checkBindings(FormLayout layout, Set<String> recordIds, List<Map<String, Object>> findings)
    {
        int memberSegments = 0;
        for (Map.Entry<String, List<FormLayout.Segment>> binding : layout.paths.entrySet())
        {
            String elementId = binding.getKey();
            FormLayout.Element element = elementOf(layout, elementId);
            String subject = element != null ? word(element) : "element#" + elementId; //$NON-NLS-1$
            if (element == null)
            {
                finding(findings, "binding-element-missing", "error", subject, //$NON-NLS-1$ //$NON-NLS-2$
                    "the attribute registry binds a data path for an element the layout does not hold"); //$NON-NLS-1$
            }
            List<FormLayout.Segment> path = binding.getValue();
            if (!path.isEmpty() && path.get(0).kind == FormLayout.Segment.ATTRIBUTE
                && attributeOf(layout, path.get(0).value) == null)
            {
                finding(findings, "binding-attribute-missing", "error", subject, //$NON-NLS-1$ //$NON-NLS-2$
                    "the data path opens with the attribute id " + path.get(0).value //$NON-NLS-1$
                        + ", which the attribute table does not hold"); //$NON-NLS-1$
            }
            for (FormLayout.Segment segment : path)
            {
                if (segment.kind == FormLayout.Segment.MEMBER)
                {
                    memberSegments++;
                }
                if (segment.kind == FormLayout.Segment.ELEMENT_ROW && !recordIds.contains(segment.value))
                {
                    finding(findings, "binding-row-element-missing", "error", subject, //$NON-NLS-1$ //$NON-NLS-2$
                        "the data path reads the current row of element#" + segment.value //$NON-NLS-1$
                            + ", which the layout does not hold"); //$NON-NLS-1$
                }
            }
        }
        return memberSegments;
    }

    /** A column's member segments name members of the row's type; the configuration names them or nothing does. */
    private static void checkColumnMembers(FormLayout layout, LayoutNames names,
        List<Map<String, Object>> findings)
    {
        if (names == null || names == LayoutNames.NONE)
        {
            return;
        }
        for (FormLayout.Element element : layout.allElements())
        {
            String rowType = element.pattern == null ? null : element.pattern.singleProducedType();
            if (rowType == null)
            {
                continue;
            }
            for (FormLayout.Column column : element.columns)
            {
                for (FormLayout.Segment segment : column.path)
                {
                    if (segment.kind == FormLayout.Segment.MEMBER
                        && names.memberName(rowType, segment.value) == null)
                    {
                        finding(findings, "column-member-missing", "error", word(element), //$NON-NLS-1$ //$NON-NLS-2$
                            "the column " + column.name + " names the member uuid " + segment.value //$NON-NLS-1$ //$NON-NLS-2$
                                + " of the row's type, which the configuration does not know"); //$NON-NLS-1$
                    }
                }
            }
        }
    }

    /** The procedures bound anywhere in the layout the module does not declare, each with where it is bound. */
    private static void checkHandlers(FormLayout layout, String layoutText, String moduleText,
        List<Map<String, Object>> findings)
    {
        if (moduleText == null)
        {
            return;
        }
        Set<String> declared = HandlerBindings.declaredProcedures(OrdinaryFormModule.splitLines(moduleText));
        for (String bound : HandlerBindings.boundProcedures(layoutText))
        {
            if (declared.contains(bound))
            {
                continue;
            }
            List<String> where = new ArrayList<>();
            for (FormLayout.Handler handler : layout.formHandlers)
            {
                if (bound.equalsIgnoreCase(handler.procedure))
                {
                    where.add("the form's " + eventWord(handler)); //$NON-NLS-1$
                }
            }
            for (FormLayout.Element element : layout.allElements())
            {
                for (FormLayout.Handler handler : element.handlers)
                {
                    if (bound.equalsIgnoreCase(handler.procedure))
                    {
                        where.add(word(element) + " " + eventWord(handler)); //$NON-NLS-1$
                    }
                }
                for (FormLayout.Button button : element.buttons)
                {
                    if (bound.equalsIgnoreCase(button.procedure))
                    {
                        where.add("the button " + button.name + " of " + word(element)); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }
            finding(findings, "handler-procedure-missing", "error", bound, //$NON-NLS-1$ //$NON-NLS-2$
                where.isEmpty() ? "bound in a record the reader did not model, and the module declares no such procedure" //$NON-NLS-1$
                    : "bound at " + String.join("; ", where) //$NON-NLS-1$
                        + ", and the module declares no such procedure"); //$NON-NLS-1$
        }
    }

    /** A command panel's source of actions must name an element when it names one at all. */
    private static void checkActionSources(List<Anchors.Located> records, Set<String> recordIds,
        List<Map<String, Object>> findings)
    {
        for (Anchors.Located one : records)
        {
            if (!"CommandPanel".equals(one.type())) //$NON-NLS-1$
            {
                continue;
            }
            ListNode identity = LayoutReader.identityOf(one.record);
            String source = identity != null && identity.size() > 2 ? LayoutReader.tokenText(identity.get(2)) : null;
            if (source == null || NO_ACTION_SOURCE.contains(source) || recordIds.contains(source))
            {
                continue;
            }
            finding(findings, "action-source-missing", "error", word(one), //$NON-NLS-1$ //$NON-NLS-2$
                "the panel takes its actions from element#" + source + ", which the layout does not hold"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // ---- anchors ----------------------------------------------------------------------------

    /**
     * The anchors of the four edges the reader reads: a target that names no element, and a chain
     * of targets that closes a cycle. Whether a target sits beside the element it anchors is not
     * judged here - the reader binds neighbours, but what a written form may carry was not measured.
     */
    private static void checkAnchors(List<Anchors.Located> records, Set<String> recordIds,
        Map<String, String> nameOfRecord, List<Map<String, Object>> findings)
    {
        Map<String, TreeSet<String>> targetsOf = new TreeMap<>();
        for (Anchors.Located one : records)
        {
            ListNode common = LayoutReader.commonOf(one.record);
            if (common == null)
            {
                continue;
            }
            for (int slot : EDGE_SLOTS)
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
                String target = LayoutReader.tokenText(anchor.get(1));
                if (target == null || target.equals("-1") || target.equals("0") //$NON-NLS-1$ //$NON-NLS-2$
                    || target.equals(one.id()))
                {
                    continue;
                }
                if (!recordIds.contains(target))
                {
                    finding(findings, "anchor-target-missing", "error", word(one), //$NON-NLS-1$ //$NON-NLS-2$
                        "the " + edgeWord(slot) + " edge names element#" + target //$NON-NLS-1$ //$NON-NLS-2$
                            + ", which the layout does not hold"); //$NON-NLS-1$
                    continue;
                }
                targetsOf.computeIfAbsent(one.id(), k -> new TreeSet<>()).add(target);
            }
        }
        Map<String, Integer> colour = new LinkedHashMap<>();
        List<String> path = new ArrayList<>();
        for (String start : targetsOf.keySet())
        {
            walkAnchors(start, targetsOf, colour, path, nameOfRecord, findings);
        }
    }

    /** Depth-first walk of the anchor graph; a grey target closes a cycle, which is reported once. */
    private static void walkAnchors(String id, Map<String, TreeSet<String>> targetsOf, Map<String, Integer> colour,
        List<String> path, Map<String, String> nameOfRecord, List<Map<String, Object>> findings)
    {
        Integer state = colour.get(id);
        if (state != null)
        {
            if (state.intValue() == 1)
            {
                List<String> cycle = new ArrayList<>(path.subList(path.indexOf(id), path.size()));
                cycle.add(id);
                List<String> words = new ArrayList<>();
                for (String step : cycle)
                {
                    words.add(nameOfRecord.getOrDefault(step, "element#" + step)); //$NON-NLS-1$
                }
                finding(findings, "anchor-cycle", "error", words.get(0), //$NON-NLS-1$ //$NON-NLS-2$
                    String.join(" -> ", words)); //$NON-NLS-1$
            }
            return;
        }
        colour.put(id, Integer.valueOf(1));
        path.add(id);
        TreeSet<String> targets = targetsOf.get(id);
        if (targets != null)
        {
            for (String target : targets)
            {
                walkAnchors(target, targetsOf, colour, path, nameOfRecord, findings);
            }
        }
        path.remove(path.size() - 1);
        colour.put(id, Integer.valueOf(2));
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
        return anchor != null && anchor.size() == 4 && "2".equals(LayoutReader.tokenText(anchor.get(0))) //$NON-NLS-1$
            ? anchor : null;
    }

    // ---- geometry ---------------------------------------------------------------------------

    /** A box whose right lies before its left or whose bottom lies above its top. */
    private static void checkBoxes(List<FormLayout.Element> elements, List<Map<String, Object>> findings)
    {
        for (FormLayout.Element element : elements)
        {
            if (element.box != null && (element.box[2] < element.box[0] || element.box[3] < element.box[1]))
            {
                finding(findings, "invalid-box", "error", word(element), boxWord(element)); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /**
     * The two geometry warnings: siblings of one holder and one page whose boxes overlap, and a
     * child whose box runs past its parent's. A Group or a Panel frame is never a party to an
     * overlap - a frame wraps what sits inside it - and neither check says anything about how a
     * form behaves when its size changes.
     *
     * @return how many sibling pairs were judged and how many children against their parent's box
     */
    private static int[] checkGeometry(FormLayout layout, List<FormLayout.Element> elements,
        List<Map<String, Object>> findings)
    {
        int pairs = 0;
        int children = 0;
        pairs += judgeSiblings(layout.elements, findings);
        for (FormLayout.Element element : elements)
        {
            pairs += judgeSiblings(element.children, findings);
            if (element.box == null)
            {
                continue;
            }
            int width = element.box[2] - element.box[0];
            int height = element.box[3] - element.box[1];
            for (FormLayout.Element child : element.children)
            {
                if (child.box == null)
                {
                    continue;
                }
                children++;
                if (child.box[0] < 0 || child.box[1] < 0 || child.box[2] > width || child.box[3] > height)
                {
                    finding(findings, "box-outside-parent", "warning", word(child), //$NON-NLS-1$ //$NON-NLS-2$
                        "the box " + boxWord(child) + " runs past the box of " + word(element) //$NON-NLS-1$ //$NON-NLS-2$
                            + " (" + width + "x" + height + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }
        }
        return new int[] { pairs, children };
    }

    /**
     * Overlaps between siblings that show together: one holder's elements grouped by the page they
     * sit on. Siblings whose page ordinal the reader did not read are not judged - which page they
     * show on together is exactly what is not known.
     */
    private static int judgeSiblings(List<FormLayout.Element> siblings, List<Map<String, Object>> findings)
    {
        Map<Integer, List<FormLayout.Element>> byPage = new TreeMap<>();
        for (FormLayout.Element element : siblings)
        {
            if (element.pageIndex != null && element.box != null)
            {
                byPage.computeIfAbsent(element.pageIndex, k -> new ArrayList<>()).add(element);
            }
        }
        int pairs = 0;
        for (List<FormLayout.Element> shown : byPage.values())
        {
            for (int i = 0; i < shown.size(); i++)
            {
                for (int k = i + 1; k < shown.size(); k++)
                {
                    FormLayout.Element left = shown.get(i);
                    FormLayout.Element right = shown.get(k);
                    if (isFrame(left) || isFrame(right))
                    {
                        continue;
                    }
                    pairs++;
                    int across = Math.min(left.box[2], right.box[2]) - Math.max(left.box[0], right.box[0]);
                    int down = Math.min(left.box[3], right.box[3]) - Math.max(left.box[1], right.box[1]);
                    if (across > 0 && down > 0)
                    {
                        finding(findings, "sibling-overlap", "warning", word(left), //$NON-NLS-1$ //$NON-NLS-2$
                            "the box " + boxWord(left) + " overlaps the box " + boxWord(right) //$NON-NLS-1$ //$NON-NLS-2$
                                + " of " + word(right) + ", both on the page " + left.page); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }
        }
        return pairs;
    }

    /** Whether an element is a frame that wraps what sits inside it rather than a control that covers. */
    private static boolean isFrame(FormLayout.Element element)
    {
        return "Group".equals(element.type) || "Panel".equals(element.type); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- coverage ---------------------------------------------------------------------------

    /**
     * What was and was not checked, with the reason for every part no check reached.
     *
     * @param memberSegments how many segments of the form's data paths name a metadata object by uuid
     * @param geometryCounts the sibling pairs and parent-child boxes the geometry judged
     * @return the coverage entries
     */
    private static List<Map<String, Object>> coverage(FormLayout layout, List<FormLayout.Element> elements,
        String moduleText, LayoutNames names, int memberSegments, int[] geometryCounts)
    {
        List<Map<String, Object>> coverage = new ArrayList<>();
        if (layout.unread.isEmpty())
        {
            coverage.add(entry("records", "performed", "every element record's type is one the reader knows")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        else
        {
            coverage.add(entry("records", "partial", layout.unread.values().stream().mapToInt(Integer::intValue).sum() //$NON-NLS-1$ //$NON-NLS-2$
                + " record(s) of type(s) " + String.join(", ", layout.unread.keySet()) //$NON-NLS-1$ //$NON-NLS-2$
                + " the reader does not know; whatever they hold is outside every check here")); //$NON-NLS-1$
        }
        coverage.add(entry("layout-version", layout.version == null ? "notPerformed" : "performed", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            layout.version == null ? "the root holds no version token" //$NON-NLS-1$
                : "the layout records version " + layout.version //$NON-NLS-1$
                    + "; every check ran over the model the reader yields for it")); //$NON-NLS-1$
        int withOrdinal = 0;
        for (FormLayout.Element element : elements)
        {
            if (element.pageIndex != null)
            {
                withOrdinal++;
            }
        }
        int withoutOrdinal = elements.size() - withOrdinal;
        coverage.add(entry("page-references", "performed", withOrdinal + " of " + elements.size() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " elements carry a page ordinal; the page of the other " + withoutOrdinal //$NON-NLS-1$
            + " the reader did not read, so their page was not judged")); //$NON-NLS-1$
        if (moduleText == null)
        {
            coverage.add(entry("handler-procedures", "notPerformed", //$NON-NLS-1$ //$NON-NLS-2$
                "the container holds no module entry, so no bound procedure was looked for in one")); //$NON-NLS-1$
        }
        else
        {
            coverage.add(entry("handler-procedures", "performed", //$NON-NLS-1$ //$NON-NLS-2$
                "every procedure the layout binds anywhere in its text was compared with the module's declarations")); //$NON-NLS-1$
        }
        String memberWord = memberSegments == 0 ? "no member segment names a metadata object by uuid" //$NON-NLS-1$
            : memberSegments + " member segment(s) name metadata objects by uuid, which were not verified: " //$NON-NLS-1$
                + "they name members of an attribute's type, which only the configuration can name";
        coverage.add(entry("data-paths", names == null || names == LayoutNames.NONE ? "partial" : "performed", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "attribute ids and current-row element ids were checked against the layout itself; " + memberWord //$NON-NLS-1$
                + (names == null || names == LayoutNames.NONE ? ", and the configuration is not loaded here" : ""))); //$NON-NLS-1$ //$NON-NLS-2$
        coverage.add(entry("column-paths", names == null || names == LayoutNames.NONE ? "notPerformed" : "performed", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            names == null || names == LayoutNames.NONE
                ? "the members a table column names belong to the row's type, which the configuration names and it is not loaded here" //$NON-NLS-1$
                : "the member uuids of table columns were resolved against the row's type through the configuration's names")); //$NON-NLS-1$
        coverage.add(entry("anchors", "partial", //$NON-NLS-1$ //$NON-NLS-2$
            "the four edge slots were checked for their targets and for cycles; the two slots past them the reader does not read were not, and whether a target sits beside the element it anchors was not judged")); //$NON-NLS-1$
        coverage.add(entry("geometry", "partial", //$NON-NLS-1$ //$NON-NLS-2$
            geometryCounts[0] + " sibling pair(s) of one holder and page and " + geometryCounts[1] //$NON-NLS-1$
                + " child box(es) against their parent's were judged; the root elements were not judged against the form's own area, which the reader does not measure, and nothing here says how the form behaves when its size changes")); //$NON-NLS-1$
        return coverage;
    }

    /** One coverage entry. */
    private static Map<String, Object> entry(String check, String status, String detail)
    {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("check", check); //$NON-NLS-1$
        entry.put("status", status); //$NON-NLS-1$
        entry.put("detail", detail); //$NON-NLS-1$
        return entry;
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** One finding. */
    private static void finding(List<Map<String, Object>> findings, String check, String severity, String subject,
        String detail)
    {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("check", check); //$NON-NLS-1$
        finding.put("severity", severity); //$NON-NLS-1$
        finding.put("subject", subject); //$NON-NLS-1$
        finding.put("detail", detail); //$NON-NLS-1$
        findings.add(finding);
    }

    /** The element's name when it has one, its id otherwise. */
    private static String word(FormLayout.Element element)
    {
        return element.name != null ? element.name : "element#" + element.id; //$NON-NLS-1$
    }

    /** The element's name when it has one, its id otherwise. */
    private static String word(Anchors.Located one)
    {
        return one.name() != null ? one.name() : "element#" + one.id(); //$NON-NLS-1$
    }

    /** The element an id names, the first in document order. */
    private static FormLayout.Element elementOf(FormLayout layout, String id)
    {
        for (FormLayout.Element element : layout.allElements())
        {
            if (id.equals(element.id))
            {
                return element;
            }
        }
        return null;
    }

    /** The attribute an id names. */
    private static FormLayout.Attribute attributeOf(FormLayout layout, String id)
    {
        for (FormLayout.Attribute attribute : layout.attributes)
        {
            if (attribute.id.equals(id))
            {
                return attribute;
            }
        }
        return null;
    }

    /** The event a handler is bound to, as well as the layout records it. */
    private static String eventWord(FormLayout.Handler handler)
    {
        return handler.event != null ? handler.event
            : handler.code != null ? "event of code " + handler.code : "event"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A box in words. */
    private static String boxWord(FormLayout.Element element)
    {
        return element.box[0] + "," + element.box[1] + "," + element.box[2] + "," + element.box[3]; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The edge a slot reads, in words. */
    private static String edgeWord(int slot)
    {
        switch (slot - Anchors.SLOT_TOP)
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
}
