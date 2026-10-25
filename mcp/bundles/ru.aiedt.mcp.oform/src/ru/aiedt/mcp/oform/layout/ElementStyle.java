/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.BraceTree.Token;

/**
 * The style of an element: visibility, enablement, the read-only flag of a field, the font, the
 * three colours, the alignment and the format - each written at the position the corpus measured
 * and the platform read back, and each refused for a kind or a value that was not measured.
 *
 * <p>The style block sits in the element's type-specific list at a position that depends on the
 * kind; the block itself is the same thirteen items for every kind that carries one:
 * {@code {<flags>, enabled, backColor, textColor, font, 0, borderColor, ...}}. The item constants
 * and kind sets below select the positions each writer accepts.</p>
 */
public final class ElementStyle
{
    /** The positions of the block's items this class writes. */
    private static final int ENABLED = 1;

    private static final int BACK_COLOR = 2;

    private static final int TEXT_COLOR = 3;

    private static final int FONT = 4;

    private static final int BORDER_COLOR = 6;

    /** The position of the visibility flag in the common list. */
    private static final int VISIBLE = 5;

    /** The kinds whose style block was measured; every other kind is refused by name. */
    private static final Set<String> STYLE_KINDS = Set.of("Label", "Button", "Group", "Image", "Separator", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "SelectField", "Panel", "CommandPanel", "CheckBox", "Field", "RadioBtn", "Table"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    private static final Set<String> READ_ONLY_KINDS = Set.of("Field"); //$NON-NLS-1$

    private static final Set<String> ALIGNMENT_KINDS = Set.of("Label", "Button", "Field"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final Set<String> FORMAT_KINDS = Set.of("Field"); //$NON-NLS-1$

    /**
     * The style elements the corpus carries in a colour record: a negative index is all the
     * record holds, the platform names none of them (the dump reads the kind and no name).
     */
    private static final Set<Integer> STYLE_ELEMENTS = Set.of(Integer.valueOf(-1), Integer.valueOf(-3),
        Integer.valueOf(-7), Integer.valueOf(-10), Integer.valueOf(-11), Integer.valueOf(-14), Integer.valueOf(-16),
        Integer.valueOf(-21), Integer.valueOf(-22), Integer.valueOf(-24));

    private ElementStyle()
    {
        // static registry
    }

    // ---- the writes -------------------------------------------------------------------------

    /**
     * Sets the visibility of an element at position 5 of the head of its common list, as 0 or 1.
     */
    public static LayoutEditor.Outcome setVisible(ListNode root, String elementName, boolean visible)
    {
        Anchors.Located located = Anchors.locate(root, elementName);
        if (located == null)
        {
            return noElement(elementName);
        }
        ListNode common = LayoutReader.commonOf(located.record);
        if (common == null || common.size() <= VISIBLE || LayoutReader.intOf(common.get(VISIBLE)) == null)
        {
            return badRecord(located.name(), "no visibility flag where the corpus holds one"); //$NON-NLS-1$
        }
        common.items().set(VISIBLE, flag(visible));
        return LayoutEditor.Outcome.done(located.name() + (visible ? " made visible" : " hidden")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Sets the enablement of an element: the second item of its style block, 0 / 1 - exact on
     * every kind that varies in the corpus.
     */
    public static LayoutEditor.Outcome setEnabled(ListNode root, String elementName, boolean enabled)
    {
        Anchors.Located located = Anchors.locate(root, elementName);
        if (located == null)
        {
            return noElement(elementName);
        }
        ListNode style = styleBlock(located.record, located.type());
        if (style == null)
        {
            return noStyle(located);
        }
        style.items().set(ENABLED, flag(enabled));
        return LayoutEditor.Outcome.done(located.name() + (enabled ? " enabled" : " disabled")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Sets the read-only flag of a field: position 13 of the block the field's own list carries
     * the style in. Measured on Field alone (169, 807 and 3146 samples of versions 25, 26, 27);
     * another kind is refused by name.
     */
    public static LayoutEditor.Outcome setReadOnly(ListNode root, String elementName, boolean readOnly)
    {
        Anchors.Located located = Anchors.locate(root, elementName);
        if (located == null)
        {
            return noElement(elementName);
        }
        if (!READ_ONLY_KINDS.contains(located.type()))
        {
            return LayoutEditor.refusal("An element of type " + located.type() //$NON-NLS-1$
                + " has no readOnly property the corpus measured; measured on: " + String.join(", ", READ_ONLY_KINDS) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        ListNode block = ownBlock(located.record);
        if (block == null || block.size() <= 13 || LayoutReader.intOf(block.get(13)) == null)
        {
            return badRecord(located.name(), "no read-only flag where the corpus holds one"); //$NON-NLS-1$
        }
        block.items().set(13, flag(readOnly));
        return LayoutEditor.Outcome.done(located.name() + (readOnly ? " made read-only" : " made editable")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Sets the font of an element. Two shapes are written, both measured: {@code value=auto}
     * writes the plain auto font {@code {6,3,0,1}} the corpus carries 13248 times, and a family
     * with a size (and the three flags, false when absent) writes the absolute font of eighteen
     * items. Name at item 16, size in tenths at item 3, weight at item 7, italic at item 8 and
     * underline at item 9 match the dump on all 54 samples, every one a label of version 27.
     * A flag without a family and a size has no measured shape and is refused.
     */
    public static LayoutEditor.Outcome setFont(ListNode root, String elementName, String auto, String family,
        Integer size, Boolean bold, Boolean italic, Boolean underline)
    {
        Anchors.Located located = Anchors.locate(root, elementName);
        if (located == null)
        {
            return noElement(elementName);
        }
        ListNode style = styleBlock(located.record, located.type());
        if (style == null)
        {
            return noStyle(located);
        }
        String value = auto == null ? null : auto.trim();
        boolean named = value != null && !value.isEmpty();
        boolean reset = named && "auto".equalsIgnoreCase(value); //$NON-NLS-1$
        if (named && !reset)
        {
            return LayoutEditor.refusal("set_property font: value takes auto and nothing else (value=" + value //$NON-NLS-1$
                + " was asked); a font of its own is named by fontFamily with fontSize."); //$NON-NLS-1$
        }
        boolean hasFlags = bold != null || italic != null || underline != null;
        boolean hasFamily = family != null && !family.trim().isEmpty();
        if (reset && (hasFamily || size != null || hasFlags))
        {
            return LayoutEditor.refusal("set_property font: value=auto stands alone; the family, the size and the " //$NON-NLS-1$
                + "flags each name a font of their own."); //$NON-NLS-1$
        }
        if (!reset && (!hasFamily || size == null))
        {
            return LayoutEditor.refusal("set_property font: only two shapes are measured - value=auto, or a family " //$NON-NLS-1$
                + "with a size (and the flags bold, italic, underline); a flag or a size alone is not."); //$NON-NLS-1$
        }
        if (reset)
        {
            style.items().set(FONT, BraceTree.parse("{6,3,0,1}")); //$NON-NLS-1$
            return LayoutEditor.Outcome.done("font of " + located.name() + " set to the form's font"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (size.intValue() <= 0 || size.intValue() > 100)
        {
            return LayoutEditor.refusal("The font size must be between 1 and 100: " + size + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        boolean isBold = bold != null && bold.booleanValue();
        boolean isItalic = italic != null && italic.booleanValue();
        boolean isUnderline = underline != null && underline.booleanValue();
        // the absolute font the corpus carries 54 times among the writable kinds: the size in
        // tenths, the Windows weight, the flags, the charset 204 of the corpus and the name
        ListNode font = LayoutEditor.list(new Token("6"), new Token("0"), new Token("0"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new Token(Integer.toString(size.intValue() * 10)), new Token("0"), new Token("0"), new Token("0"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new Token(isBold ? "700" : "400"), new Token(isItalic ? "1" : "0"), new Token(isUnderline ? "1" : "0"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            new Token("0"), new Token("204"), new Token("3"), new Token("2"), new Token("1"), new Token("34"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            Token.string(family.trim()), new Token("0")); //$NON-NLS-1$
        style.items().set(FONT, font);
        return LayoutEditor.Outcome.done("font of " + located.name() + " set to " + family.trim() + " " + size //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (isBold ? " bold" : "") + (isItalic ? " italic" : "") + (isUnderline ? " underlined" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    /**
     * Sets one of the three colours of an element: {@code auto} writes {@code {3,4,{0}}},
     * {@code #RRGGBB} writes the absolute colour {@code {3,0,{<integer>}}} whose byte order the
     * platform confirmed on 539 samples (the integer is {@code B<<16 | G<<8 | R}), and a style
     * element index from the measured set writes {@code {3,3,{<index>}}}. Anything else is
     * refused by name.
     *
     * @param slot {@code text}, {@code back} or {@code border}
     */
    public static LayoutEditor.Outcome setColor(ListNode root, String elementName, String slot, String value)
    {
        Anchors.Located located = Anchors.locate(root, elementName);
        if (located == null)
        {
            return noElement(elementName);
        }
        ListNode style = styleBlock(located.record, located.type());
        if (style == null)
        {
            return noStyle(located);
        }
        Integer position;
        String what;
        switch (slot == null ? "" : slot) //$NON-NLS-1$
        {
        case "text": //$NON-NLS-1$
            position = Integer.valueOf(TEXT_COLOR);
            what = "text colour"; //$NON-NLS-1$
            break;
        case "back": //$NON-NLS-1$
            position = Integer.valueOf(BACK_COLOR);
            what = "background colour"; //$NON-NLS-1$
            break;
        case "border": //$NON-NLS-1$
            position = Integer.valueOf(BORDER_COLOR);
            what = "border colour"; //$NON-NLS-1$
            break;
        default:
            return LayoutEditor.refusal("The colour slot must be text, back or border: " + slot + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String text = value == null ? "" : value.trim(); //$NON-NLS-1$
        if (text.isEmpty())
        {
            return LayoutEditor.refusal("set_property needs a value: auto, #RRGGBB, or a style element index."); //$NON-NLS-1$
        }
        if ("auto".equalsIgnoreCase(text)) //$NON-NLS-1$
        {
            style.items().set(position.intValue(), BraceTree.parse("{3,4,{0}}")); //$NON-NLS-1$
            return LayoutEditor.Outcome.done(what + " of " + located.name() + " set to auto"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (text.charAt(0) == '#')
        {
            Integer colour = absolute(text);
            if (colour == null)
            {
                return LayoutEditor.refusal("An absolute colour is #RRGGBB: " + text + "."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            style.items().set(position.intValue(), LayoutEditor.list(new Token("3"), new Token("0"), //$NON-NLS-1$ //$NON-NLS-2$
                LayoutEditor.list(new Token(Integer.toString(colour.intValue())))));
            return LayoutEditor.Outcome.done(what + " of " + located.name() + " set to " + text.toLowerCase(java.util.Locale.ROOT)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Integer index = null;
        try
        {
            index = Integer.valueOf(text);
        }
        catch (NumberFormatException e)
        {
            index = null;
        }
        if (index == null || !STYLE_ELEMENTS.contains(index))
        {
            return LayoutEditor.refusal("The style element must be one of the measured indexes: " //$NON-NLS-1$
                + STYLE_ELEMENTS.toString() + "; " + text + " is not one."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        style.items().set(position.intValue(), LayoutEditor.list(new Token("3"), new Token("3"), //$NON-NLS-1$ //$NON-NLS-2$
            LayoutEditor.list(new Token(index.toString()))));
        return LayoutEditor.Outcome.done(what + " of " + located.name() + " set to style element " + index); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Sets the horizontal alignment of a label, a button or a field. Label and Button carry the
     * four measured values at position 3 of their own list; a Field carries left, right and auto
     * at position 17 of the block its own list carries the style in - a field has no centre.
     */
    public static LayoutEditor.Outcome setAlignment(ListNode root, String elementName, String value)
    {
        Anchors.Located located = Anchors.locate(root, elementName);
        if (located == null)
        {
            return noElement(elementName);
        }
        if (!ALIGNMENT_KINDS.contains(located.type()))
        {
            return LayoutEditor.refusal("An element of type " + located.type() + " has no alignment the corpus " //$NON-NLS-1$ //$NON-NLS-2$
                + "measured; measured on: " + String.join(", ", ALIGNMENT_KINDS) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        Integer code;
        String what;
        switch (value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT)) //$NON-NLS-1$
        {
        case "left": //$NON-NLS-1$
            code = Integer.valueOf(0);
            break;
        case "center": //$NON-NLS-1$
        case "centre": //$NON-NLS-1$
            if ("Field".equals(located.type())) //$NON-NLS-1$
            {
                return LayoutEditor.refusal("A field has no centre alignment the corpus measured; " //$NON-NLS-1$
                    + "a field aligns left, right or auto."); //$NON-NLS-1$
            }
            code = Integer.valueOf(1);
            break;
        case "right": //$NON-NLS-1$
            code = Integer.valueOf(2);
            break;
        case "auto": //$NON-NLS-1$
            code = Integer.valueOf(4);
            break;
        default:
            return LayoutEditor.refusal("The alignment must be left, center, right or auto: " + value + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if ("Field".equals(located.type())) //$NON-NLS-1$
        {
            ListNode block = ownBlock(located.record);
            if (block == null || block.size() <= 17 || LayoutReader.intOf(block.get(17)) == null)
            {
                return badRecord(located.name(), "no alignment where the corpus holds one"); //$NON-NLS-1$
            }
            block.items().set(17, new Token(code.toString()));
        }
        else
        {
            // Label and Button keep the alignment at the fourth item of the list their own list
            // carries the style block in: [2][1][3]
            ListNode holder = blockHolder(located.record);
            if (holder == null || holder.size() <= 3 || LayoutReader.intOf(holder.get(3)) == null)
            {
                return badRecord(located.name(), "no alignment where the corpus holds one"); //$NON-NLS-1$
            }
            holder.items().set(3, new Token(code.toString()));
        }
        what = value.trim().toLowerCase(java.util.Locale.ROOT);
        return LayoutEditor.Outcome.done(located.name() + " aligned " + ("center".equals(what) ? "centre" : what)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Sets the format of a field: position 34 of the block the field's own list carries the style
     * in, a localized string the platform read back exactly on every version the editor writes.
     * An empty value clears it.
     */
    public static LayoutEditor.Outcome setFormat(ListNode root, String elementName, String value, String language)
    {
        Anchors.Located located = Anchors.locate(root, elementName);
        if (located == null)
        {
            return noElement(elementName);
        }
        if (!FORMAT_KINDS.contains(located.type()))
        {
            return LayoutEditor.refusal("An element of type " + located.type() + " has no format property the corpus " //$NON-NLS-1$ //$NON-NLS-2$
                + "measured; measured on: " + String.join(", ", FORMAT_KINDS) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        ListNode block = ownBlock(located.record);
        if (block == null || block.size() <= 34 || LayoutReader.localized(block.get(34)) == null)
        {
            return badRecord(located.name(), "no format where the corpus holds one"); //$NON-NLS-1$
        }
        String text = value == null ? "" : value; //$NON-NLS-1$
        block.items().set(34, text.isEmpty() ? LayoutEditor.list(new Token("1"), new Token("0")) //$NON-NLS-1$ //$NON-NLS-2$
            : LayoutReader.localizedNode(language == null || language.isEmpty() ? "ru" : language, text)); //$NON-NLS-1$
        return LayoutEditor.Outcome.done("format of " + located.name() + (text.isEmpty() ? " cleared" : " set to " + text)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ---- the reading ------------------------------------------------------------------------

    /**
     * The style of an element for the layout answer: the properties that differ from their
     * default alone - a hidden or disabled element, a read-only field, a font that is not the
     * form's own, a colour that is not auto, an alignment that is not auto, a format that is set.
     *
     * @param record the element's record
     * @param type the element's kind
     * @return the properties under their argument names, empty when all are default
     */
    static Map<String, Object> read(ListNode record, String type)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        ListNode common = LayoutReader.commonOf(record);
        Integer visible = common == null || common.size() <= VISIBLE ? null : LayoutReader.intOf(common.get(VISIBLE));
        if (visible != null && visible.intValue() == 0)
        {
            out.put("visible", Boolean.FALSE); //$NON-NLS-1$
        }
        ListNode style = styleBlock(record, type);
        if (style == null)
        {
            return out;
        }
        Integer enabled = LayoutReader.intOf(style.get(ENABLED));
        if (enabled != null && enabled.intValue() == 0)
        {
            out.put("enabled", Boolean.FALSE); //$NON-NLS-1$
        }
        if ("Field".equals(type)) //$NON-NLS-1$
        {
            ListNode block = ownBlock(record);
            Integer readOnly = block == null || block.size() <= 13 ? null : LayoutReader.intOf(block.get(13));
            if (readOnly != null && readOnly.intValue() == 1)
            {
                out.put("readOnly", Boolean.TRUE); //$NON-NLS-1$
            }
            if (block != null && block.size() > 34)
            {
                Map<String, String> format = LayoutReader.localized(block.get(34));
                if (format != null && !format.isEmpty())
                {
                    Object text = FormLayout.text(format);
                    if (text != null && !text.toString().isEmpty())
                    {
                        out.put("format", text); //$NON-NLS-1$
                    }
                }
            }
        }
        Map<String, Object> font = fontOf(style.get(FONT));
        if (!font.isEmpty())
        {
            out.put("font", font); //$NON-NLS-1$
        }
        String textColor = colourOf(style.get(TEXT_COLOR));
        if (textColor != null)
        {
            out.put("textColor", textColor); //$NON-NLS-1$
        }
        String backColor = colourOf(style.get(BACK_COLOR));
        if (backColor != null)
        {
            out.put("backColor", backColor); //$NON-NLS-1$
        }
        String borderColor = colourOf(style.get(BORDER_COLOR));
        if (borderColor != null)
        {
            out.put("borderColor", borderColor); //$NON-NLS-1$
        }
        String alignment = alignmentOf(record, type);
        if (alignment != null)
        {
            out.put("alignment", alignment); //$NON-NLS-1$
        }
        return out;
    }

    /** The font in words: the fields the record carries; an empty map is the plain auto font. */
    private static Map<String, Object> fontOf(ru.aiedt.mcp.oform.BraceTree.Node node)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        ListNode font = LayoutReader.asList(node);
        if (font == null || font.size() < 2)
        {
            return out;
        }
        String kind = LayoutReader.tokenText(font.get(1));
        if ("3".equals(kind)) //$NON-NLS-1$
        {
            // the auto font: beyond the plain shape the corpus carries the Windows weight 700 alone
            for (ru.aiedt.mcp.oform.BraceTree.Node item : font.items())
            {
                if ("700".equals(LayoutReader.tokenText(item))) //$NON-NLS-1$
                {
                    out.put("bold", Boolean.TRUE); //$NON-NLS-1$
                }
            }
            return out;
        }
        if ("0".equals(kind) && font.size() == 18) //$NON-NLS-1$
        {
            Integer size10 = LayoutReader.intOf(font.get(3));
            Integer weight = LayoutReader.intOf(font.get(7));
            String name = LayoutReader.isString(font.get(16)) ? LayoutReader.stringValue(font.get(16)) : null;
            if (name != null && !name.isEmpty())
            {
                out.put("family", name); //$NON-NLS-1$
            }
            if (size10 != null && size10.intValue() > 0)
            {
                out.put("size", Integer.valueOf(size10.intValue() / 10)); //$NON-NLS-1$
            }
            out.put("bold", Boolean.valueOf(weight != null && weight.intValue() >= 700)); //$NON-NLS-1$
            out.put("italic", Boolean.valueOf("1".equals(LayoutReader.tokenText(font.get(8))))); //$NON-NLS-1$ //$NON-NLS-2$
            out.put("underline", Boolean.valueOf("1".equals(LayoutReader.tokenText(font.get(9))))); //$NON-NLS-1$ //$NON-NLS-2$
            return out;
        }
        if ("2".equals(kind) && font.size() > 3) //$NON-NLS-1$
        {
            ListNode element = LayoutReader.asList(font.get(3));
            Integer index = element == null || element.size() == 0 ? null : LayoutReader.intOf(element.get(0));
            if (index != null)
            {
                out.put("styleElement", index); //$NON-NLS-1$
            }
        }
        return out;
    }

    /** A colour in words: {@code null} for auto, {@code #RRGGBB} for an absolute colour. */
    private static String colourOf(ru.aiedt.mcp.oform.BraceTree.Node node)
    {
        ListNode colour = LayoutReader.asList(node);
        if (colour == null || colour.size() < 3)
        {
            return null;
        }
        // {3, <kind>, {<value>}}: the tag 3, the kind - 4 auto, 3 a style element, 0 absolute,
        // 2 a web colour, 1 a Windows colour - then the value
        String kind = LayoutReader.tokenText(colour.get(1));
        ListNode value = LayoutReader.asList(colour.get(2));
        Integer number = value == null || value.size() == 0 ? null : LayoutReader.intOf(value.get(0));
        if ("3".equals(kind) && number != null) //$NON-NLS-1$
        {
            return number.toString(); // a style element index; the platform names none
        }
        if ("0".equals(kind) && number != null) //$NON-NLS-1$
        {
            // the integer is blue high, red low, the order the dump confirmed on 539 samples
            int packed = number.intValue() & 0xFFFFFF;
            int blue = packed >> 16 & 0xFF;
            int green = packed >> 8 & 0xFF;
            int red = packed & 0xFF;
            return String.format("#%02X%02X%02X", Integer.valueOf(red), Integer.valueOf(green), Integer.valueOf(blue)); //$NON-NLS-1$
        }
        if ("2".equals(kind) && number != null) //$NON-NLS-1$
        {
            return "web:" + number; //$NON-NLS-1$
        }
        if ("1".equals(kind) && number != null) //$NON-NLS-1$
        {
            return "windows:" + number; //$NON-NLS-1$
        }
        return null;
    }

    /** The alignment in words: {@code null} for auto and for a kind without one. */
    private static String alignmentOf(ListNode record, String type)
    {
        Integer code = null;
        if ("Label".equals(type) || "Button".equals(type)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            ListNode holder = blockHolder(record);
            code = holder == null || holder.size() <= 3 ? null : LayoutReader.intOf(holder.get(3));
        }
        else if ("Field".equals(type)) //$NON-NLS-1$
        {
            ListNode block = ownBlock(record);
            code = block == null || block.size() <= 17 ? null : LayoutReader.intOf(block.get(17));
        }
        if (code == null)
        {
            return null;
        }
        switch (code.intValue())
        {
        case 0:
            return "left"; //$NON-NLS-1$
        case 1:
            return "center"; //$NON-NLS-1$
        case 2:
            return "right"; //$NON-NLS-1$
        default:
            return null; // 4 is auto
        }
    }

    // ---- the record -------------------------------------------------------------------------

    /** The style block of an element's record, at the position its kind carries one. */
    private static ListNode styleBlock(ListNode record, String type)
    {
        if (record == null || type == null || !STYLE_KINDS.contains(type))
        {
            return null;
        }
        ListNode at = LayoutReader.ownOf(record);
        for (int step : stylePathOf(type))
        {
            at = at == null || at.size() <= step ? null : LayoutReader.asList(at.get(step));
        }
        return isStyleBlock(at) ? at : null;
    }

    /** The list an element's own list carries the style block in: {@code [2][1]} for Label and
     * Button, whose fourth item is the alignment. */
    private static ListNode blockHolder(ListNode record)
    {
        if (record == null)
        {
            return null;
        }
        ListNode own = LayoutReader.ownOf(record);
        return own == null || own.size() < 2 ? null : LayoutReader.asList(own.get(1));
    }

    /** The block a Field's own list carries the style in: {@code [2][2][0]}, the style its first item. */
    private static ListNode ownBlock(ListNode record)
    {
        if (record == null)
        {
            return null;
        }
        ListNode own = LayoutReader.ownOf(record);
        ListNode holder = own == null || own.size() < 3 ? null : LayoutReader.asList(own.get(2));
        return holder == null || holder.size() == 0 ? null : LayoutReader.asList(holder.get(0));
    }

    private static int[] stylePathOf(String type)
    {
        switch (type)
        {
        case "CheckBox": //$NON-NLS-1$
            return new int[] { 1, 0, 0 };
        case "Field": //$NON-NLS-1$
        case "RadioBtn": //$NON-NLS-1$
            return new int[] { 2, 0, 0 };
        case "Table": //$NON-NLS-1$
            return new int[] { 2, 0 };
        default:
            // Label, Button, Group, Image, Separator, SelectField, Panel, CommandPanel
            return new int[] { 1, 0 };
        }
    }

    /** Whether a list is a style block: two numbers, then colour and font records. */
    private static boolean isStyleBlock(ListNode list)
    {
        if (list == null || list.size() < 13 || LayoutReader.intOf(list.get(0)) == null
            || LayoutReader.intOf(list.get(1)) == null)
        {
            return false;
        }
        for (int position : new int[] {ENABLED + 1, TEXT_COLOR, FONT, BORDER_COLOR})
        {
            ListNode record = LayoutReader.asList(list.get(position));
            if (record == null || record.size() == 0 || LayoutReader.tokenText(record.get(0)) == null)
            {
                return false;
            }
        }
        return true;
    }

    private static Token flag(boolean value)
    {
        return new Token(value ? "1" : "0"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The integer of {@code #RRGGBB} as the platform writes it: blue high, red low. */
    private static Integer absolute(String text)
    {
        String digits = text.substring(1);
        if (digits.length() != 6 || !digits.matches("[0-9a-fA-F]{6}")) //$NON-NLS-1$
        {
            return null;
        }
        int rgb = Integer.parseInt(digits, 16);
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        return Integer.valueOf(blue << 16 | green << 8 | red);
    }

    private static LayoutEditor.Outcome noElement(String name)
    {
        return LayoutEditor.refusal("No element named " + name + " on the form."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static LayoutEditor.Outcome noStyle(Anchors.Located located)
    {
        if (located.type() == null || !STYLE_KINDS.contains(located.type()))
        {
            return LayoutEditor.refusal("An element of type " + located.type() //$NON-NLS-1$
                + " carries no style block the corpus measured; measured on: " //$NON-NLS-1$
                + String.join(", ", STYLE_KINDS) + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return badRecord(located.name(), "no style block where the corpus holds one"); //$NON-NLS-1$
    }

    private static LayoutEditor.Outcome badRecord(String name, String what)
    {
        return LayoutEditor.refusal("The record of " + name + " has " + what + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
