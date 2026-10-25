/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.BraceTree.Node;
import ru.aiedt.mcp.oform.BraceTree.Token;
import ru.aiedt.mcp.oform.layout.ElementKinds;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.layout.TableColumns;
import ru.aiedt.mcp.oform.ops.CoreOperations;
import ru.aiedt.mcp.oform.ops.LayoutContext;
import ru.aiedt.mcp.oform.ops.PropertyHandlers;
import ru.aiedt.mcp.oform.ops.StyleOperations;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Every property {@code set_property} knows is driven through its registered handler - the
 * handlers the core and style catalogs register as they load, never a fill-in of the values
 * the creation of an element offers - on a real form of each measured version, and what the
 * handler wrote is proven by reading the serialized text back, not by trusting its outcome.
 * The applicability of each property is an explicit registry of this test - five caption
 * kinds, fourteen tooltip kinds, twelve style kinds, a field alone for the read-only flag and
 * the format, three kinds for the alignment, the panel and the form for the tabs - and every
 * registry entry is written and read back while every kind outside it is refused by name with
 * the text unchanged, so a writer and a reader wrong the same way cannot hide behind each
 * other: the raw slots the platform reads - the box, the visibility flag, the style block, the
 * font, the colours, the anchors, the tabs flag, the size after the root panel - are spelled
 * here with their measured positions and compared as literals. The edit format is always
 * refused and never written.
 */
public class EveryApplicablePropertyIsReadBackOnItsMeasuredRecordTest
{
    /** A real form of each measured version, as the earlier version cycle reads them. */
    private static final List<String> FIXTURES = List.of("fixtures/settings-form-v25.form", //$NON-NLS-1$
        "fixtures/settings-form-v26.form", "fixtures/processing-form-v27.form"); //$NON-NLS-1$ //$NON-NLS-2$

    /** The version each fixture records, asserted off the text itself and not off the name. */
    private static final Map<String, String> VERSIONS = Map.of("fixtures/settings-form-v25.form", "25", //$NON-NLS-1$ //$NON-NLS-2$
        "fixtures/settings-form-v26.form", "26", //$NON-NLS-1$ //$NON-NLS-2$
        "fixtures/processing-form-v27.form", "27"); //$NON-NLS-1$ //$NON-NLS-2$

    /** All seventeen writable kinds, spelled here once each. */
    private static final List<String> ALL_KINDS = List.of("Label", "Field", "Button", "CheckBox", "Group", "Panel", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "Table", "RadioBtn", "Separator", "Image", "SelectField", "CommandPanel", "TableField", "FieldHtml", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "ListField", "TextDocumentField", "Chart"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** The kinds whose measured record carries a caption node: the element route of title and caption. */
    private static final List<String> CAPTION_KINDS = List.of("Label", "Button", "Group", "CheckBox", "RadioBtn"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    /** The kinds whose measured record carries a style block with a tooltip slot. */
    private static final List<String> TOOLTIP_KINDS = List.of("Label", "Field", "Button", "CheckBox", "Group", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "Panel", "Table", "RadioBtn", "Separator", "Image", "SelectField", "CommandPanel", "ListField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "TextDocumentField"); //$NON-NLS-1$

    /** The kinds whose measured record carries the thirteen-item style block. */
    private static final List<String> STYLE_KINDS = List.of("Label", "Button", "Group", "Image", "Separator", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "SelectField", "Panel", "CommandPanel", "CheckBox", "Field", "RadioBtn", "Table"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    /** The read-only flag is measured on a field alone. */
    private static final List<String> READ_ONLY_KINDS = List.of("Field"); //$NON-NLS-1$

    /** The alignment is measured on a label, a button and a field. */
    private static final List<String> ALIGNMENT_KINDS = List.of("Label", "Button", "Field"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** The format is measured on a field alone. */
    private static final List<String> FORMAT_KINDS = List.of("Field"); //$NON-NLS-1$

    /** The kinds with no caption node, the complement of the caption kinds. */
    private static final List<String> NO_CAPTION_KINDS = List.of("Field", "Panel", "Table", "Separator", "Image", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "SelectField", "CommandPanel", "TableField", "FieldHtml", "ListField", "TextDocumentField", "Chart"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

    /** The kinds whose measured record holds no slot for a tooltip, the complement of the tooltip kinds. */
    private static final List<String> NO_TOOLTIP_KINDS = List.of("TableField", "FieldHtml", "Chart"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** The kinds without the style block, the complement of the style kinds. */
    private static final List<String> NO_STYLE_KINDS = List.of("TableField", "FieldHtml", "ListField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "TextDocumentField", "Chart"); //$NON-NLS-1$

    /** The uuid of the value list the table probe shows; the measured settings block comes with it. */
    private static final String LIST_TYPE = "5b7c8d9e-1f2a-4b3c-8d4e-5f6a7b8c9d0e"; //$NON-NLS-1$

    /** The pattern of the value list every list field of the corpus is bound to. */
    private static final String VALUE_LIST = "{\"Pattern\",{\"#\",4772b3b4-f4a3-49c0-a1a5-8cb5961511a3}}"; //$NON-NLS-1$

    /** The name of the one element every property is set on; a fresh editor holds one probe. */
    private static final String PROBE = "RegProbe"; //$NON-NLS-1$

    /** The neighbour the anchor edges of the probe bind to, on the same page of the same parent. */
    private static final String ANCHOR_TARGET = "RegAnchorTarget"; //$NON-NLS-1$

    /** The box the probe is born with, below the elements the fixtures hold. */
    private static final int[] PROBE_BOX = { 10, 500, 410, 540 };

    // ---- the measured positions of the slots this test reads raw -----------------------------------

    /** The box of an element: items 1 to 4 of the head of its common list. */
    private static final int COMMON_BOX_FROM = 1;

    /** The visibility flag: item 5 of the head of the common list. */
    private static final int COMMON_VISIBLE = 5;

    /** The left edge anchor slot of the common list. */
    private static final int COMMON_ANCHOR_LEFT = 8;

    /** The enabled flag: item 1 of the style block. */
    private static final int STYLE_ENABLED = 1;

    /** The background colour: item 2 of the style block. */
    private static final int STYLE_BACK = 2;

    /** The text colour: item 3 of the style block. */
    private static final int STYLE_TEXT = 3;

    /** The font: item 4 of the style block. */
    private static final int STYLE_FONT = 4;

    /** The border colour: item 6 of the style block. */
    private static final int STYLE_BORDER = 6;

    /** The read-only flag: item 13 of the block a field's own list carries the style in. */
    private static final int FIELD_READ_ONLY = 13;

    /** The alignment of a field: item 17 of the block a field's own list carries the style in. */
    private static final int FIELD_ALIGNMENT = 17;

    /** The format of a field: item 34 of the block a field's own list carries the style in. */
    private static final int FIELD_FORMAT = 34;

    /** The alignment of a label and a button: item 3 of the list their own list carries the style in. */
    private static final int HOLDER_ALIGNMENT = 3;

    /**
     * The position of the style block in the own list of each kind that carries one, measured
     * over the corpus and spelled here, so the raw slot checks of this test stand on their own.
     */
    private static final Map<String, int[]> STYLE_PATH = Map.ofEntries(
        Map.entry("Label", new int[] { 1, 0 }), //$NON-NLS-1$
        Map.entry("Button", new int[] { 1, 0 }), //$NON-NLS-1$
        Map.entry("Group", new int[] { 1, 0 }), //$NON-NLS-1$
        Map.entry("Image", new int[] { 1, 0 }), //$NON-NLS-1$
        Map.entry("Separator", new int[] { 1, 0 }), //$NON-NLS-1$
        Map.entry("SelectField", new int[] { 1, 0 }), //$NON-NLS-1$
        Map.entry("Panel", new int[] { 1, 0 }), //$NON-NLS-1$
        Map.entry("CommandPanel", new int[] { 1, 0 }), //$NON-NLS-1$
        Map.entry("CheckBox", new int[] { 1, 0, 0 }), //$NON-NLS-1$
        Map.entry("Field", new int[] { 2, 0, 0 }), //$NON-NLS-1$
        Map.entry("RadioBtn", new int[] { 2, 0, 0 }), //$NON-NLS-1$
        Map.entry("Table", new int[] { 2, 0 })); //$NON-NLS-1$

    /** The fixture texts, read once; every edit works on its own parse of its own copy. */
    private static final Map<String, String> TEXTS = new LinkedHashMap<>();

    /** Reads the fixtures once and loads the catalogs the standard way, so their properties register. */
    @BeforeClass
    public static void theFixturesAndTheCatalogs() throws IOException
    {
        for (String fixture : FIXTURES)
        {
            String text = ALayoutIsReadOffTheContainerTest.fixture(fixture);
            assertTrue(fixture, text.startsWith("{" + VERSIONS.get(fixture) + ",")); //$NON-NLS-1$ //$NON-NLS-2$
            TEXTS.put(fixture, text);
        }
        CoreOperations.specs();
        StyleOperations.specs();
    }

    // ---- the registry ------------------------------------------------------------------------

    /**
     * The applicability registry of this test is explicit and complete: every list spells its
     * kinds once each out of the seventeen, the complements miss no kind, and the counts close
     * at five, fourteen, twelve, one, three and one. Every property the matrix drives is
     * registered by the catalogs that loaded in the fixture method.
     */
    @Test
    public void theApplicabilityRegistryIsExplicitAndCounted()
    {
        assertEquals(17, ALL_KINDS.size());
        assertEquals(17, new HashSet<>(ALL_KINDS).size());
        assertEquals(new HashSet<>(ALL_KINDS), ElementKinds.WRITABLE);
        assertEquals(5, CAPTION_KINDS.size());
        assertEquals(14, TOOLTIP_KINDS.size());
        assertEquals(12, STYLE_KINDS.size());
        assertEquals(1, READ_ONLY_KINDS.size());
        assertEquals(3, ALIGNMENT_KINDS.size());
        assertEquals(1, FORMAT_KINDS.size());
        assertEquals(12, NO_CAPTION_KINDS.size());
        assertEquals(3, NO_TOOLTIP_KINDS.size());
        assertEquals(5, NO_STYLE_KINDS.size());
        List<List<String>> registries = List.of(CAPTION_KINDS, TOOLTIP_KINDS, STYLE_KINDS, READ_ONLY_KINDS,
            ALIGNMENT_KINDS, FORMAT_KINDS, NO_CAPTION_KINDS, NO_TOOLTIP_KINDS, NO_STYLE_KINDS);
        for (List<String> registry : registries)
        {
            assertEquals(registry.toString(), registry.size(), new HashSet<>(registry).size());
            for (String kind : registry)
            {
                assertTrue(registry.toString(), ALL_KINDS.contains(kind));
            }
        }
        for (String kind : ALL_KINDS)
        {
            assertTrue(kind, CAPTION_KINDS.contains(kind) ^ NO_CAPTION_KINDS.contains(kind));
            assertTrue(kind, TOOLTIP_KINDS.contains(kind) ^ NO_TOOLTIP_KINDS.contains(kind));
            assertTrue(kind, STYLE_KINDS.contains(kind) ^ NO_STYLE_KINDS.contains(kind));
        }
        for (String kind : READ_ONLY_KINDS)
        {
            assertTrue(kind, STYLE_KINDS.contains(kind));
        }
        for (String kind : FORMAT_KINDS)
        {
            assertTrue(kind, STYLE_KINDS.contains(kind));
        }
        for (String kind : ALIGNMENT_KINDS)
        {
            assertTrue(kind, STYLE_KINDS.contains(kind));
        }
        for (String property : List.of("title", "caption", "tooltip", "box", "size", "visible", "enabled", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
            "readOnly", "tabs", "font", "textColor", "backColor", "borderColor", "alignment", "format", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "editFormat", "anchor")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertNotNull("the property " + property + " is registered", PropertyHandlers.find(property)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // ---- the form and the caption route of the title -------------------------------------------

    /** The title handler without an element writes the form's title, and the reader reads it. */
    @Test
    public void theFormTitleIsWrittenAndReadBack()
    {
        for (String fixture : FIXTURES)
        {
            String literal = "Registry form title " + VERSIONS.get(fixture); //$NON-NLS-1$
            String label = fixture + " form title"; //$NON-NLS-1$
            LayoutEditor editor = probe(fixture, "Label"); //$NON-NLS-1$
            LayoutEditor.Outcome set = setProperty(editor, "title", Map.of("value", literal)); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(label + ": " + set.message, set.done); //$NON-NLS-1$
            assertEquals(label, literal, formOf(editor).title.get("ru")); //$NON-NLS-1$
            // the raw slot: the localized pair the head carries, as the text holds it
            assertTrue(label, editor.text().contains("{\"ru\",\"" + literal + "\"}")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * The title handler with an element writes that element's caption - the branch the first
     * review missed - on every caption kind and every version; the kinds without a caption node
     * are refused by name, with the text unchanged.
     */
    @Test
    public void theTitleHandlerWritesTheElementCaptionOnEveryCaptionKind()
    {
        for (String fixture : FIXTURES)
        {
            for (String kind : CAPTION_KINDS)
            {
                String literal = "Registry title of " + kind + " on " + VERSIONS.get(fixture); //$NON-NLS-1$ //$NON-NLS-2$
                String label = fixture + " " + kind + " title"; //$NON-NLS-1$ //$NON-NLS-2$
                LayoutEditor editor = probe(fixture, kind);
                LayoutEditor.Outcome set = setProperty(editor, "title", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", literal)); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(label + ": " + set.message, set.done); //$NON-NLS-1$
                assertEquals(label, literal, elementOf(editor, PROBE).title.get("ru")); //$NON-NLS-1$
                assertTrue(label, editor.text().contains("{\"ru\",\"" + literal + "\"}")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        for (String fixture : FIXTURES)
        {
            for (String kind : NO_CAPTION_KINDS)
            {
                refusedAndUnchanged(fixture, kind, "title", Map.of("elementName", PROBE, "value", "Denied"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "has no caption"); //$NON-NLS-1$
            }
        }
    }

    /**
     * The caption handler writes and clears the caption of every caption kind on every version;
     * the clear the corpus measured is the empty text. The kinds without a caption node are
     * refused by name, with the text unchanged.
     */
    @Test
    public void theCaptionIsWrittenAndClearedOnEveryCaptionKind()
    {
        for (String fixture : FIXTURES)
        {
            for (String kind : CAPTION_KINDS)
            {
                String literal = "Registry caption of " + kind + " on " + VERSIONS.get(fixture); //$NON-NLS-1$ //$NON-NLS-2$
                String label = fixture + " " + kind + " caption"; //$NON-NLS-1$ //$NON-NLS-2$
                LayoutEditor editor = probe(fixture, kind);
                LayoutEditor.Outcome set = setProperty(editor, "caption", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", literal)); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(label + ": " + set.message, set.done); //$NON-NLS-1$
                assertEquals(label, literal, elementOf(editor, PROBE).title.get("ru")); //$NON-NLS-1$
                LayoutEditor.Outcome cleared = setProperty(editor, "caption", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(label + ": " + cleared.message, cleared.done); //$NON-NLS-1$
                assertEquals(label, "", elementOf(editor, PROBE).title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        for (String fixture : FIXTURES)
        {
            for (String kind : NO_CAPTION_KINDS)
            {
                refusedAndUnchanged(fixture, kind, "caption", Map.of("elementName", PROBE, "value", "Denied"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "has no caption"); //$NON-NLS-1$
            }
        }
    }

    // ---- the tooltip -------------------------------------------------------------------------

    /**
     * The tooltip handler writes the tooltip of every kind whose measured record carries a slot
     * for one and reads it back; the empty value removes it. The three kinds without the slot
     * are refused by name, with the text unchanged.
     */
    @Test
    public void theTooltipIsWrittenAndClearedOnEveryTooltipKind()
    {
        for (String fixture : FIXTURES)
        {
            for (String kind : TOOLTIP_KINDS)
            {
                String literal = "Registry tooltip of " + kind + " on " + VERSIONS.get(fixture); //$NON-NLS-1$ //$NON-NLS-2$
                String label = fixture + " " + kind + " tooltip"; //$NON-NLS-1$ //$NON-NLS-2$
                LayoutEditor editor = probe(fixture, kind);
                LayoutEditor.Outcome set = setProperty(editor, "tooltip", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", literal)); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(label + ": " + set.message, set.done); //$NON-NLS-1$
                assertEquals(label, literal, elementOf(editor, PROBE).tooltip.get("ru")); //$NON-NLS-1$
                assertTrue(label, editor.text().contains("{\"ru\",\"" + literal + "\"}")); //$NON-NLS-1$ //$NON-NLS-2$
                LayoutEditor.Outcome cleared = setProperty(editor, "tooltip", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(label + ": " + cleared.message, cleared.done); //$NON-NLS-1$
                assertNull(label, elementOf(editor, PROBE).tooltip);
            }
        }
        for (String fixture : FIXTURES)
        {
            for (String kind : NO_TOOLTIP_KINDS)
            {
                refusedAndUnchanged(fixture, kind, "tooltip", Map.of("elementName", PROBE, "value", "Denied"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "no style block to hold a tooltip"); //$NON-NLS-1$
            }
        }
    }

    // ---- the box ------------------------------------------------------------------------------

    /**
     * The box handler rewrites all four coordinates of every kind on every version, and the
     * reader reads the box back; the raw slots at the head of the common list carry the four
     * literals. A width and a height stand for the right and the bottom over the box the
     * element holds, and a box that folds onto itself is refused with nothing written.
     */
    @Test
    public void theBoxIsRewrittenOnEveryKind()
    {
        for (String fixture : FIXTURES)
        {
            for (String kind : ALL_KINDS)
            {
                String label = fixture + " " + kind + " box"; //$NON-NLS-1$ //$NON-NLS-2$
                LayoutEditor editor = probe(fixture, kind);
                LayoutEditor.Outcome set = setProperty(editor, "box", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "left", "31", "top", "47", "right", "313", "bottom", "189")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
                assertTrue(label + ": " + set.message, set.done); //$NON-NLS-1$
                assertArrayEquals(label, new int[] { 31, 47, 313, 189 }, elementOf(editor, PROBE).box);
                ListNode common = commonOfProbe(editor.text());
                assertEquals(label, "31", token(common.get(COMMON_BOX_FROM))); //$NON-NLS-1$
                assertEquals(label, "47", token(common.get(COMMON_BOX_FROM + 1))); //$NON-NLS-1$
                assertEquals(label, "313", token(common.get(COMMON_BOX_FROM + 2))); //$NON-NLS-1$
                assertEquals(label, "189", token(common.get(COMMON_BOX_FROM + 3))); //$NON-NLS-1$
            }
        }
        for (String fixture : FIXTURES)
        {
            String label = fixture + " Label box by width and height"; //$NON-NLS-1$
            LayoutEditor editor = probe(fixture, "Label"); //$NON-NLS-1$
            LayoutEditor.Outcome set = setProperty(editor, "box", //$NON-NLS-1$
                Map.of("elementName", PROBE, "width", "200", "height", "50")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertTrue(label + ": " + set.message, set.done); //$NON-NLS-1$
            assertArrayEquals(label, new int[] { PROBE_BOX[0], PROBE_BOX[1], PROBE_BOX[0] + 200, PROBE_BOX[1] + 50 },
                elementOf(editor, PROBE).box);
        }
        for (String fixture : FIXTURES)
        {
            refusedAndUnchanged(fixture, "Label", "box", //$NON-NLS-1$ //$NON-NLS-2$
                Map.of("elementName", PROBE, "left", "300", "right", "100"), "right at or beyond its left"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
    }

    // ---- the size of the form ------------------------------------------------------------------

    /**
     * The size handler writes the width and the height of the form on every version. The reader
     * yields no size of the form, so the two numbers after the root panel - the slots the
     * platform reads - are asserted raw, as the text carries them and as tokens of the parsed
     * tree; a width alone keeps the height the fixture held. An element takes no size.
     */
    @Test
    public void theFormSizeIsWrittenToItsMeasuredSlots()
    {
        for (String fixture : FIXTURES)
        {
            String label = fixture + " form size"; //$NON-NLS-1$
            LayoutEditor editor = probe(fixture, "Label"); //$NON-NLS-1$
            LayoutEditor.Outcome set = setProperty(editor, "size", Map.of("width", "777", "height", "333")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertTrue(label + ": " + set.message, set.done); //$NON-NLS-1$
            String written = editor.text();
            assertTrue(label, written.contains("},777,333,")); //$NON-NLS-1$
            ListNode body = list(BraceTree.parse(written).get(1));
            assertNotNull(label, body);
            assertEquals(label, "777", token(body.get(3))); //$NON-NLS-1$
            assertEquals(label, "333", token(body.get(4))); //$NON-NLS-1$
            LayoutEditor again = probe(fixture, "Label"); //$NON-NLS-1$
            // the height the fixture holds is the input state, kept as it is by a width-only write
            String keptHeight = token(list(BraceTree.parse(again.text()).get(1)).get(4));
            assertNotNull(label, keptHeight);
            LayoutEditor.Outcome widened = setProperty(again, "size", Map.of("width", "640")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(label + ": " + widened.message, widened.done); //$NON-NLS-1$
            ListNode bodyAfter = list(BraceTree.parse(again.text()).get(1));
            assertEquals(label, "640", token(bodyAfter.get(3))); //$NON-NLS-1$
            assertEquals(label, keptHeight, token(bodyAfter.get(4)));
        }
        for (String fixture : FIXTURES)
        {
            refusedAndUnchanged(fixture, "Field", "size", Map.of("elementName", PROBE, "width", "800"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "size is a property of the form"); //$NON-NLS-1$
        }
    }

    // ---- the visibility ------------------------------------------------------------------------

    /**
     * The visibility handler hides and shows an element of every kind on every version: the
     * reader names a hidden element alone, and the raw flag at item 5 of the common list
     * carries the literal both ways.
     */
    @Test
    public void theVisibilityIsSetAndResetOnEveryKind()
    {
        for (String fixture : FIXTURES)
        {
            for (String kind : ALL_KINDS)
            {
                String label = fixture + " " + kind + " visible"; //$NON-NLS-1$ //$NON-NLS-2$
                LayoutEditor editor = probe(fixture, kind);
                LayoutEditor.Outcome hidden = setProperty(editor, "visible", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", "false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(label + ": " + hidden.message, hidden.done); //$NON-NLS-1$
                assertEquals(label, Boolean.FALSE, elementOf(editor, PROBE).details.get("visible")); //$NON-NLS-1$
                assertEquals(label, "0", token(commonOfProbe(editor.text()).get(COMMON_VISIBLE))); //$NON-NLS-1$
                LayoutEditor.Outcome shown = setProperty(editor, "visible", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(label + ": " + shown.message, shown.done); //$NON-NLS-1$
                assertNull(label, elementOf(editor, PROBE).details.get("visible")); //$NON-NLS-1$
                assertEquals(label, "1", token(commonOfProbe(editor.text()).get(COMMON_VISIBLE))); //$NON-NLS-1$
            }
        }
    }

    // ---- the enablement ------------------------------------------------------------------------

    /**
     * The enabled handler disables and enables an element of every style kind on every version,
     * with the raw flag at item 1 of the measured style block; the kinds without the block are
     * refused by name, with the text unchanged.
     */
    @Test
    public void theEnablementIsSetAndResetOnEveryStyleKind()
    {
        for (String fixture : FIXTURES)
        {
            for (String kind : STYLE_KINDS)
            {
                String label = fixture + " " + kind + " enabled"; //$NON-NLS-1$ //$NON-NLS-2$
                LayoutEditor editor = probe(fixture, kind);
                LayoutEditor.Outcome disabled = setProperty(editor, "enabled", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", "false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(label + ": " + disabled.message, disabled.done); //$NON-NLS-1$
                assertEquals(label, Boolean.FALSE, elementOf(editor, PROBE).details.get("enabled")); //$NON-NLS-1$
                assertEquals(label, "0", token(styleBlockIn(editor.text(), kind).get(STYLE_ENABLED))); //$NON-NLS-1$
                LayoutEditor.Outcome enabled = setProperty(editor, "enabled", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(label + ": " + enabled.message, enabled.done); //$NON-NLS-1$
                assertNull(label, elementOf(editor, PROBE).details.get("enabled")); //$NON-NLS-1$
                assertEquals(label, "1", token(styleBlockIn(editor.text(), kind).get(STYLE_ENABLED))); //$NON-NLS-1$
            }
        }
        for (String fixture : FIXTURES)
        {
            for (String kind : NO_STYLE_KINDS)
            {
                refusedAndUnchanged(fixture, kind, "enabled", Map.of("elementName", PROBE, "value", "false"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "carries no style block"); //$NON-NLS-1$
            }
        }
    }

    // ---- the read-only flag --------------------------------------------------------------------

    /**
     * The read-only handler locks and unlocks a field on every version, with the raw flag at
     * item 13 of the block its own list carries the style in; every other kind is refused by
     * name, with the text unchanged.
     */
    @Test
    public void theReadOnlyFlagIsAFieldAlone()
    {
        for (String fixture : FIXTURES)
        {
            String label = fixture + " Field readOnly"; //$NON-NLS-1$
            LayoutEditor editor = probe(fixture, "Field"); //$NON-NLS-1$
            LayoutEditor.Outcome locked = setProperty(editor, "readOnly", //$NON-NLS-1$
                Map.of("elementName", PROBE, "value", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(label + ": " + locked.message, locked.done); //$NON-NLS-1$
            assertEquals(label, Boolean.TRUE, elementOf(editor, PROBE).details.get("readOnly")); //$NON-NLS-1$
            assertEquals(label, "1", token(fieldBlockIn(editor.text()).get(FIELD_READ_ONLY))); //$NON-NLS-1$
            LayoutEditor.Outcome open = setProperty(editor, "readOnly", //$NON-NLS-1$
                Map.of("elementName", PROBE, "value", "false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(label + ": " + open.message, open.done); //$NON-NLS-1$
            assertNull(label, elementOf(editor, PROBE).details.get("readOnly")); //$NON-NLS-1$
            assertEquals(label, "0", token(fieldBlockIn(editor.text()).get(FIELD_READ_ONLY))); //$NON-NLS-1$
        }
        for (String fixture : FIXTURES)
        {
            for (String kind : ALL_KINDS)
            {
                if (!READ_ONLY_KINDS.contains(kind))
                {
                    refusedAndUnchanged(fixture, kind, "readOnly", Map.of("elementName", PROBE, "value", "true"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        "no readOnly property"); //$NON-NLS-1$
                }
            }
        }
    }

    // ---- the tabs -------------------------------------------------------------------------------

    /**
     * The tabs handler turns the tab strip of a panel and of the form itself on and off on every
     * version. A panel born with one page draws none; the reader names the flag of a panel, and
     * the raw slot two items before the pages list carries the literal. The reader names no
     * tabs of the form's root, so the root's own slot - found the way the measured reader finds
     * it, at the same index - is read raw. An element that is no panel takes no tab strip.
     */
    @Test
    public void theTabsFlagIsWrittenOnThePanelAndOnTheForm()
    {
        for (String fixture : FIXTURES)
        {
            String label = fixture + " tabs"; //$NON-NLS-1$
            LayoutEditor editor = probe(fixture, "Panel"); //$NON-NLS-1$
            assertEquals(label, Boolean.FALSE, elementOf(editor, PROBE).details.get("tabs")); //$NON-NLS-1$
            LayoutEditor.Outcome off = setProperty(editor, "tabs", Map.of("elementName", PROBE, "value", "false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertTrue(label + ": " + off.message, off.done); //$NON-NLS-1$
            assertEquals(label, Boolean.FALSE, elementOf(editor, PROBE).details.get("tabs")); //$NON-NLS-1$
            assertEquals(label, "0", panelTabsFlag(editor.text(), PROBE)); //$NON-NLS-1$
            LayoutEditor.Outcome on = setProperty(editor, "tabs", Map.of("elementName", PROBE, "value", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertTrue(label + ": " + on.message, on.done); //$NON-NLS-1$
            assertEquals(label, Boolean.TRUE, elementOf(editor, PROBE).details.get("tabs")); //$NON-NLS-1$
            assertEquals(label, "1", panelTabsFlag(editor.text(), PROBE)); //$NON-NLS-1$
            LayoutEditor.Outcome back = setProperty(editor, "tabs", Map.of("elementName", PROBE, "value", "false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertTrue(label + ": " + back.message, back.done); //$NON-NLS-1$
            assertEquals(label, Boolean.FALSE, elementOf(editor, PROBE).details.get("tabs")); //$NON-NLS-1$
            assertEquals(label, "0", panelTabsFlag(editor.text(), PROBE)); //$NON-NLS-1$
            LayoutEditor.Outcome formOn = setProperty(editor, "tabs", Map.of("value", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(label + ": " + formOn.message, formOn.done); //$NON-NLS-1$
            assertEquals(label, "1", rootTabsFlag(editor.text())); //$NON-NLS-1$
            LayoutEditor.Outcome formOff = setProperty(editor, "tabs", Map.of("value", "false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(label + ": " + formOff.message, formOff.done); //$NON-NLS-1$
            assertEquals(label, "0", rootTabsFlag(editor.text())); //$NON-NLS-1$
        }
        for (String fixture : FIXTURES)
        {
            refusedAndUnchanged(fixture, "Label", "tabs", Map.of("elementName", PROBE, "value", "true"), PROBE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
    }

    // ---- the font -------------------------------------------------------------------------------

    /**
     * The font handler writes the absolute font of the corpus - the family, the size in tenths,
     * the weight and the three flags - on every style kind and every version, with every flag
     * on and with none, and auto resets it to the form's own font; the raw node at item 4 of
     * the style block carries the literal of each shape. The kinds without the block are
     * refused by name, with the text unchanged.
     */
    @Test
    public void theFontIsSetWithEveryFlagAndResetToAuto()
    {
        String withFlags = "{6,0,0,120,0,0,0,700,1,1,0,204,3,2,1,34,\"Verdana\",0}"; //$NON-NLS-1$
        String withoutFlags = "{6,0,0,120,0,0,0,400,0,0,0,204,3,2,1,34,\"Verdana\",0}"; //$NON-NLS-1$
        for (String fixture : FIXTURES)
        {
            for (String kind : STYLE_KINDS)
            {
                String label = fixture + " " + kind + " font"; //$NON-NLS-1$ //$NON-NLS-2$
                LayoutEditor editor = probe(fixture, kind);
                LayoutEditor.Outcome flags = setProperty(editor, "font", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "fontFamily", "Verdana", "fontSize", "12", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        "bold", "true", "italic", "true", "underline", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                assertTrue(label + ": " + flags.message, flags.done); //$NON-NLS-1$
                Map<?, ?> font = fontOf(elementOf(editor, PROBE), label);
                assertEquals(label, "Verdana", font.get("family")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(label, Integer.valueOf(12), font.get("size")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(label, Boolean.TRUE, font.get("bold")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(label, Boolean.TRUE, font.get("italic")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(label, Boolean.TRUE, font.get("underline")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(label, withFlags, slotSerialized(styleBlockIn(editor.text(), kind), STYLE_FONT));
                LayoutEditor.Outcome plain = setProperty(editor, "font", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "fontFamily", "Verdana", "fontSize", "12")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                assertTrue(label + ": " + plain.message, plain.done); //$NON-NLS-1$
                Map<?, ?> plainFont = fontOf(elementOf(editor, PROBE), label);
                assertEquals(label, Boolean.FALSE, plainFont.get("bold")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(label, Boolean.FALSE, plainFont.get("italic")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(label, Boolean.FALSE, plainFont.get("underline")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(label, withoutFlags, slotSerialized(styleBlockIn(editor.text(), kind), STYLE_FONT));
                LayoutEditor.Outcome auto = setProperty(editor, "font", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", "auto")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(label + ": " + auto.message, auto.done); //$NON-NLS-1$
                assertNull(label, elementOf(editor, PROBE).details.get("font")); //$NON-NLS-1$
                assertEquals(label, "{6,3,0,1}", slotSerialized(styleBlockIn(editor.text(), kind), STYLE_FONT)); //$NON-NLS-1$
            }
        }
        for (String fixture : FIXTURES)
        {
            for (String kind : NO_STYLE_KINDS)
            {
                refusedAndUnchanged(fixture, kind, "font", Map.of("elementName", PROBE, "value", "auto"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "carries no style block"); //$NON-NLS-1$
            }
        }
    }

    // ---- the three colours ----------------------------------------------------------------------

    /** The text colour is written as an absolute value, a measured style index and auto on every style kind. */
    @Test
    public void theTextColourIsWrittenOnEveryStyleKind()
    {
        colourRoundTrip("textColor", STYLE_TEXT, "#3366FF", 16737843, "-3"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    /** The background colour is written as an absolute value, a measured style index and auto on every style kind. */
    @Test
    public void theBackColourIsWrittenOnEveryStyleKind()
    {
        colourRoundTrip("backColor", STYLE_BACK, "#66CC00", 52326, "-7"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    /** The border colour is written as an absolute value, a measured style index and auto on every style kind. */
    @Test
    public void theBorderColourIsWrittenOnEveryStyleKind()
    {
        colourRoundTrip("borderColor", STYLE_BORDER, "#FF0000", 255, "-22"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    /** A kind without a style block takes none of the three colours: each is refused by name, with nothing written. */
    @Test
    public void theColoursRefuseAKindWithoutAStyleBlock()
    {
        for (String fixture : FIXTURES)
        {
            for (String kind : NO_STYLE_KINDS)
            {
                for (String property : List.of("textColor", "backColor", "borderColor")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                {
                    refusedAndUnchanged(fixture, kind, property, //$NON-NLS-1$
                        Map.of("elementName", PROBE, "value", "#3366FF"), "carries no style block"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                }
            }
        }
    }

    // ---- the alignment --------------------------------------------------------------------------

    /**
     * The alignment handler writes left, centre, right and auto on a label and a button - the
     * fourth item of the list their own list carries the style in - and left, right and auto on
     * a field, whose slot sits at item 17 of its own block; a field has no centre and is refused.
     * Every kind outside the three measured ones is refused by name, with the text unchanged.
     */
    @Test
    public void theAlignmentIsWrittenOnLabelButtonAndField()
    {
        for (String fixture : FIXTURES)
        {
            for (String kind : List.of("Label", "Button")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                String label = fixture + " " + kind + " alignment"; //$NON-NLS-1$ //$NON-NLS-2$
                LayoutEditor editor = probe(fixture, kind);
                align(editor, kind, label, "left", "0"); //$NON-NLS-1$ //$NON-NLS-2$
                align(editor, kind, label, "center", "1"); //$NON-NLS-1$ //$NON-NLS-2$
                align(editor, kind, label, "right", "2"); //$NON-NLS-1$ //$NON-NLS-2$
                LayoutEditor.Outcome auto = setProperty(editor, "alignment", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", "auto")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(label + ": " + auto.message, auto.done); //$NON-NLS-1$
                assertNull(label, elementOf(editor, PROBE).details.get("alignment")); //$NON-NLS-1$
                assertEquals(label, "4", holderAlignment(editor.text(), kind)); //$NON-NLS-1$
            }
            String label = fixture + " Field alignment"; //$NON-NLS-1$
            LayoutEditor editor = probe(fixture, "Field"); //$NON-NLS-1$
            LayoutEditor.Outcome left = setProperty(editor, "alignment", //$NON-NLS-1$
                Map.of("elementName", PROBE, "value", "left")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(label + ": " + left.message, left.done); //$NON-NLS-1$
            assertEquals(label, "left", elementOf(editor, PROBE).details.get("alignment")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(label, "0", token(fieldBlockIn(editor.text()).get(FIELD_ALIGNMENT))); //$NON-NLS-1$
            LayoutEditor.Outcome right = setProperty(editor, "alignment", //$NON-NLS-1$
                Map.of("elementName", PROBE, "value", "right")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(label + ": " + right.message, right.done); //$NON-NLS-1$
            assertEquals(label, "right", elementOf(editor, PROBE).details.get("alignment")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(label, "2", token(fieldBlockIn(editor.text()).get(FIELD_ALIGNMENT))); //$NON-NLS-1$
            LayoutEditor.Outcome auto = setProperty(editor, "alignment", //$NON-NLS-1$
                Map.of("elementName", PROBE, "value", "auto")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(label + ": " + auto.message, auto.done); //$NON-NLS-1$
            assertNull(label, elementOf(editor, PROBE).details.get("alignment")); //$NON-NLS-1$
            assertEquals(label, "4", token(fieldBlockIn(editor.text()).get(FIELD_ALIGNMENT))); //$NON-NLS-1$
            LayoutEditor.Outcome centre = setProperty(editor, "alignment", //$NON-NLS-1$
                Map.of("elementName", PROBE, "value", "center")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertFalse(label, centre.done);
            assertTrue(label, centre.message.contains("centre")); //$NON-NLS-1$
        }
        for (String fixture : FIXTURES)
        {
            for (String kind : ALL_KINDS)
            {
                if (!ALIGNMENT_KINDS.contains(kind))
                {
                    refusedAndUnchanged(fixture, kind, "alignment", //$NON-NLS-1$
                        Map.of("elementName", PROBE, "value", "left"), "no alignment"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                }
            }
        }
    }

    // ---- the format -----------------------------------------------------------------------------

    /**
     * The format handler writes the localized format string of a field on every version and the
     * empty value clears it; the raw node at item 34 of the field's own block carries the
     * literal of both shapes. Every other kind is refused by name, with the text unchanged.
     */
    @Test
    public void theFormatIsWrittenAndClearedOnAField()
    {
        for (String fixture : FIXTURES)
        {
            String label = fixture + " Field format"; //$NON-NLS-1$
            LayoutEditor editor = probe(fixture, "Field"); //$NON-NLS-1$
            LayoutEditor.Outcome set = setProperty(editor, "format", //$NON-NLS-1$
                Map.of("elementName", PROBE, "value", "ДФ=dd.MM.yyyy")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(label + ": " + set.message, set.done); //$NON-NLS-1$
            assertEquals(label, "ДФ=dd.MM.yyyy", elementOf(editor, PROBE).details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(label, "{1,1,\r\n{\"ru\",\"ДФ=dd.MM.yyyy\"}\r\n}", //$NON-NLS-1$
                slotSerialized(fieldBlockIn(editor.text()), FIELD_FORMAT));
            LayoutEditor.Outcome cleared = setProperty(editor, "format", //$NON-NLS-1$
                Map.of("elementName", PROBE, "value", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(label + ": " + cleared.message, cleared.done); //$NON-NLS-1$
            assertNull(label, elementOf(editor, PROBE).details.get("format")); //$NON-NLS-1$
            assertEquals(label, "{1,0}", slotSerialized(fieldBlockIn(editor.text()), FIELD_FORMAT)); //$NON-NLS-1$
        }
        for (String fixture : FIXTURES)
        {
            for (String kind : ALL_KINDS)
            {
                if (!FORMAT_KINDS.contains(kind))
                {
                    refusedAndUnchanged(fixture, kind, "format", Map.of("elementName", PROBE, "value", "ДФ=dd"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        "no format property"); //$NON-NLS-1$
                }
            }
        }
    }

    // ---- the anchors ----------------------------------------------------------------------------

    /**
     * The anchor handler binds all four edges of an element of every kind on every version: the
     * top to the neighbour's bottom with a positive offset, the left to the form, the right to
     * none and the bottom to the neighbour's top with a negative offset. The reader reads the
     * words back, and the raw slot of the left edge carries the record the platform keeps.
     */
    @Test
    public void theFourEdgesAreAnchoredOnEveryKind()
    {
        for (String fixture : FIXTURES)
        {
            for (String kind : ALL_KINDS)
            {
                String label = fixture + " " + kind + " anchors"; //$NON-NLS-1$ //$NON-NLS-2$
                LayoutEditor editor = probe(fixture, kind);
                assertTrue(label, editor.addElement("Label", ANCHOR_TARGET, null, null, //$NON-NLS-1$ //$NON-NLS-2$
                    new int[] { 10, 560, 410, 584 }, null, null, null).done);
                LayoutEditor.Outcome top = setProperty(editor, "anchor", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "edge", "top", "value", ANCHOR_TARGET + ".bottom", "offset", "4")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                assertTrue(label + ": " + top.message, top.done); //$NON-NLS-1$
                LayoutEditor.Outcome left = setProperty(editor, "anchor", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "edge", "left", "value", "form")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                assertTrue(label + ": " + left.message, left.done); //$NON-NLS-1$
                LayoutEditor.Outcome right = setProperty(editor, "anchor", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "edge", "right", "value", "none")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                assertTrue(label + ": " + right.message, right.done); //$NON-NLS-1$
                LayoutEditor.Outcome bottom = setProperty(editor, "anchor", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "edge", "bottom", "value", ANCHOR_TARGET + ".top", "offset", "-3")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                assertTrue(label + ": " + bottom.message, bottom.done); //$NON-NLS-1$
                FormLayout.Element element = elementOf(editor, PROBE);
                Map<?, ?> anchors = anchorsOf(element, label);
                assertEquals(label, ANCHOR_TARGET + ".bottom+4", anchors.get("top")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(label, "form", anchors.get("left")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(label, "none", anchors.get("right")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(label, ANCHOR_TARGET + ".top-3", anchors.get("bottom")); //$NON-NLS-1$ //$NON-NLS-2$
                ListNode slot = list(commonOfProbe(editor.text()).get(COMMON_ANCHOR_LEFT));
                assertNotNull(label, slot);
                assertEquals(label, "{2,0,2,0}", BraceTree.serialize(list(slot.get(1)))); //$NON-NLS-1$
            }
        }
    }

    // ---- the edit format ------------------------------------------------------------------------

    /** The edit format is not measured: the handler refuses it on every version, and nothing is written. */
    @Test
    public void theEditFormatIsAlwaysRefusedWithNothingWritten()
    {
        for (String fixture : FIXTURES)
        {
            for (String kind : List.of("Field", "Label")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                refusedAndUnchanged(fixture, kind, "editFormat", //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", "ДФ=dd.MM.yyyy"), "not measured"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
        }
    }

    // ---- the colour round trip -------------------------------------------------------------------

    /**
     * One colour property on every style kind and every version: an absolute {@code #RRGGBB},
     * a measured style element index, and auto - each written through the registered handler,
     * read back, and pinned to the raw node the style block carries, whose integer the platform
     * writes blue high and red low.
     *
     * @param property the property name
     * @param slot the item of the style block the colour sits at
     * @param absolute the absolute colour, as the argument and the answer spell it
     * @param packed the integer the platform writes for the absolute colour
     * @param index a measured style element index
     */
    private static void colourRoundTrip(String property, int slot, String absolute, int packed, String index)
    {
        for (String fixture : FIXTURES)
        {
            for (String kind : STYLE_KINDS)
            {
                String label = fixture + " " + kind + " " + property; //$NON-NLS-1$ //$NON-NLS-2$
                LayoutEditor editor = probe(fixture, kind);
                LayoutEditor.Outcome absoluteSet = setProperty(editor, property, //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", absolute)); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(label + ": " + absoluteSet.message, absoluteSet.done); //$NON-NLS-1$
                assertEquals(label, absolute, elementOf(editor, PROBE).details.get(property));
                assertEquals(label, "{3,0,\r\n{" + packed + "}\r\n}", //$NON-NLS-1$ //$NON-NLS-2$
                    slotSerialized(styleBlockIn(editor.text(), kind), slot));
                LayoutEditor.Outcome indexSet = setProperty(editor, property, //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", index)); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(label + ": " + indexSet.message, indexSet.done); //$NON-NLS-1$
                assertEquals(label, index, elementOf(editor, PROBE).details.get(property));
                assertEquals(label, "{3,3,\r\n{" + index + "}\r\n}", //$NON-NLS-1$ //$NON-NLS-2$
                    slotSerialized(styleBlockIn(editor.text(), kind), slot));
                LayoutEditor.Outcome auto = setProperty(editor, property, //$NON-NLS-1$
                    Map.of("elementName", PROBE, "value", "auto")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(label + ": " + auto.message, auto.done); //$NON-NLS-1$
                assertNull(label, elementOf(editor, PROBE).details.get(property));
                assertEquals(label, "{3,4,\r\n{0}\r\n}", //$NON-NLS-1$
                    slotSerialized(styleBlockIn(editor.text(), kind), slot));
            }
        }
    }

    // ---- one alignment step ----------------------------------------------------------------------

    /**
     * Writes one alignment value on a label or a button and reads it back with its raw slot.
     *
     * @param editor the editor over a fresh parse of a fixture
     * @param kind the kind of the probe
     * @param label the assertion label
     * @param value the alignment as the argument spells it
     * @param raw the integer the measured holder slot carries
     */
    private static void align(LayoutEditor editor, String kind, String label, String value, String raw)
    {
        LayoutEditor.Outcome set = setProperty(editor, "alignment", Map.of("elementName", PROBE, "value", value)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(label + ": " + set.message, set.done); //$NON-NLS-1$
        assertEquals(label, value, elementOf(editor, PROBE).details.get("alignment")); //$NON-NLS-1$
        assertEquals(label, raw, holderAlignment(editor.text(), kind));
    }

    // ---- the editors and the handlers ------------------------------------------------------------

    /**
     * A fresh editor over a fixture with one probe element of the kind, created empty of every
     * property this class writes: what the handlers write is all the record carries of it.
     *
     * @param fixture the fixture
     * @param kind the kind of the probe
     * @return the editor with the probe added
     */
    private static LayoutEditor probe(String fixture, String kind)
    {
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(TEXTS.get(fixture)), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        LayoutEditor.Outcome added = addKind(editor, kind, PROBE, PROBE_BOX);
        assertTrue(fixture + " " + kind + ": " + added.message, added.done); //$NON-NLS-1$ //$NON-NLS-2$
        return editor;
    }

    /**
     * Adds an element of a kind over the routes the measured templates take: a list field is
     * given the value list pattern it requires, a table takes the pattern of the list it shows
     * together with the settings block of those rows.
     *
     * @param editor the editor over a fresh parse of a fixture
     * @param kind the kind to create
     * @param name the name of the new element, unique on the form
     * @param box left, top, right, bottom
     * @return the outcome of the addition
     */
    private static LayoutEditor.Outcome addKind(LayoutEditor editor, String kind, String name, int[] box)
    {
        ListNode data = null;
        ListNode settings = null;
        if ("ListField".equals(kind)) //$NON-NLS-1$
        {
            data = BraceTree.parse(VALUE_LIST);
        }
        else if ("Table".equals(kind)) //$NON-NLS-1$
        {
            data = BraceTree.parse("{\"Pattern\",{\"#\"," + LIST_TYPE + "}}"); //$NON-NLS-1$ //$NON-NLS-2$
            settings = TableColumns.settingsBlock("CatalogList.Products", LIST_TYPE); //$NON-NLS-1$
        }
        return editor.addElement(kind, name, null, null, box, null, null, data, settings);
    }

    /**
     * Applies a registered property handler over the editor's tree, the way {@code set_property}
     * does: the handler is found by name and takes the element name out of the arguments.
     *
     * @param editor the editor the handler edits
     * @param property the property name
     * @param params the arguments of the call
     * @return what the handler answered
     */
    private static LayoutEditor.Outcome setProperty(LayoutEditor editor, String property, Map<String, String> params)
    {
        PropertyHandlers.PropertyHandler handler = PropertyHandlers.find(property);
        assertNotNull("the property " + property + " is registered", handler); //$NON-NLS-1$ //$NON-NLS-2$
        LayoutContext context = new LayoutContext(null, "Report.Settings.Form.Probe", "Report", null, editor.root(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            editor, params, ToolResult.success());
        return handler.apply(context, params.get("elementName")); //$NON-NLS-1$
    }

    /**
     * Asserts that a property outside its applicability is refused by name and writes nothing.
     *
     * @param fixture the fixture
     * @param kind the kind of the probe
     * @param property the property name
     * @param params the arguments of the call
     * @param words the words the refusal carries
     */
    private static void refusedAndUnchanged(String fixture, String kind, String property, Map<String, String> params,
        String words)
    {
        String label = fixture + " " + kind + " " + property; //$NON-NLS-1$ //$NON-NLS-2$
        LayoutEditor editor = probe(fixture, kind);
        String before = editor.text();
        LayoutEditor.Outcome outcome = setProperty(editor, property, params);
        assertFalse(label, outcome.done);
        assertTrue(label, outcome.message.contains(words));
        assertEquals(label + " wrote nothing", before, editor.text()); //$NON-NLS-1$
    }

    // ---- the readback ---------------------------------------------------------------------------

    /**
     * The element of the written text: the text round-trips, is parsed again and read, and the
     * element answers by its name.
     *
     * @param editor the editor that was written to
     * @param name the element's name
     * @return the element as the reader sees it
     */
    private static FormLayout.Element elementOf(LayoutEditor editor, String name)
    {
        String written = editor.text();
        assertEquals(written, written, BraceTree.serialize(BraceTree.parse(written)));
        FormLayout.Element element = LayoutReader.read(BraceTree.parse(written), "Report").elementNamed(name); //$NON-NLS-1$
        assertNotNull(name, element);
        return element;
    }

    /**
     * The form of the written text: the text round-trips, is parsed again and read.
     *
     * @param editor the editor that was written to
     * @return the layout as the reader sees it
     */
    private static FormLayout formOf(LayoutEditor editor)
    {
        String written = editor.text();
        assertEquals(written, written, BraceTree.serialize(BraceTree.parse(written)));
        return LayoutReader.read(BraceTree.parse(written), "Report"); //$NON-NLS-1$
    }

    /**
     * The font of an element, as the reader names it.
     *
     * @param element the element
     * @param label the assertion label
     * @return the font map
     */
    private static Map<?, ?> fontOf(FormLayout.Element element, String label)
    {
        Map<?, ?> font = (Map<?, ?>)element.details.get("font"); //$NON-NLS-1$
        assertNotNull(label, font);
        return font;
    }

    /**
     * The anchors of an element, as the reader names them.
     *
     * @param element the element
     * @param label the assertion label
     * @return the anchors map
     */
    private static Map<?, ?> anchorsOf(FormLayout.Element element, String label)
    {
        Map<?, ?> anchors = (Map<?, ?>)element.details.get("anchors"); //$NON-NLS-1$
        assertNotNull(label, anchors);
        return anchors;
    }

    // ---- the raw slots ---------------------------------------------------------------------------

    /**
     * The element record of a written text, found by its name in the tree the text parses to.
     *
     * @param written the text the editor wrote
     * @param name the element's name
     * @return the record, or {@code null} when the text holds none by that name
     */
    private static ListNode recordIn(String written, String name)
    {
        ListNode root = BraceTree.parse(written);
        ListNode body = list(root.get(1));
        ListNode panel = body == null ? null : list(body.get(2));
        ListNode children = panel == null ? null : list(panel.get(2));
        return children == null ? null : recordInHolder(children, name);
    }

    /**
     * The element record with a name below a holder, the nested after their parent.
     *
     * @param holder the list that holds element records
     * @param name the element's name
     * @return the record, or {@code null}
     */
    private static ListNode recordInHolder(ListNode holder, String name)
    {
        for (int i = 1; i < holder.size(); i++)
        {
            ListNode record = list(holder.get(i));
            if (record == null)
            {
                continue;
            }
            if (LayoutReader.isElementRecord(record))
            {
                if (name.equals(LayoutReader.elementName(LayoutReader.identityOf(record))))
                {
                    return record;
                }
                ListNode nested = LayoutReader.childrenOf(record);
                if (nested != null)
                {
                    ListNode found = recordInHolder(nested, name);
                    if (found != null)
                    {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The common list of the probe of a written text.
     *
     * @param written the text the editor wrote
     * @return the common list
     */
    private static ListNode commonOfProbe(String written)
    {
        ListNode record = recordIn(written, PROBE);
        assertNotNull(PROBE, record);
        ListNode common = LayoutReader.commonOf(record);
        assertNotNull(PROBE, common);
        return common;
    }

    /**
     * The style block of the probe of a written text, at the position this class spells for the
     * kind.
     *
     * @param written the text the editor wrote
     * @param kind the kind of the probe
     * @return the style block
     */
    private static ListNode styleBlockIn(String written, String kind)
    {
        ListNode record = recordIn(written, PROBE);
        assertNotNull(PROBE, record);
        ListNode block = at(LayoutReader.ownOf(record), STYLE_PATH.get(kind));
        assertNotNull(kind + " style block", block); //$NON-NLS-1$
        return block;
    }

    /**
     * The block a field's own list carries the style in, {@code [2][0]} of the own list.
     *
     * @param written the text the editor wrote
     * @return the block
     */
    private static ListNode fieldBlockIn(String written)
    {
        ListNode record = recordIn(written, PROBE);
        assertNotNull(PROBE, record);
        ListNode block = at(LayoutReader.ownOf(record), 2, 0);
        assertNotNull(block);
        return block;
    }

    /**
     * The alignment slot of a label or a button: item 3 of the list its own list carries the
     * style in.
     *
     * @param written the text the editor wrote
     * @param kind the kind of the probe
     * @return the token the slot holds
     */
    private static String holderAlignment(String written, String kind)
    {
        ListNode holder = at(LayoutReader.ownOf(recordIn(written, PROBE)), 1);
        assertNotNull(kind, holder);
        return token(holder.get(HOLDER_ALIGNMENT));
    }

    /**
     * The tabs flag of a panel of a written text, raw: the token two items before its pages list.
     *
     * @param written the text the editor wrote
     * @param panel the panel's name
     * @return the token the flag holds
     */
    private static String panelTabsFlag(String written, String panel)
    {
        ListNode record = recordIn(written, panel);
        assertNotNull(panel, record);
        ListNode props = at(LayoutReader.ownOf(record), 1);
        assertNotNull(panel, props);
        int flag = LayoutReader.tabsFlagAt(props);
        assertTrue("the panel holds a pages list", flag >= 0); //$NON-NLS-1$
        return token(props.get(flag));
    }

    /**
     * The tabs flag of the form's root panel of a written text, raw: the token two items before
     * the root's pages list. The reader names no tabs of the form, so this slot is the only
     * witness of the write.
     *
     * @param written the text the editor wrote
     * @return the token the flag holds
     */
    private static String rootTabsFlag(String written)
    {
        ListNode body = list(BraceTree.parse(written).get(1));
        ListNode rootPanel = body == null ? null : list(body.get(2));
        ListNode own = rootPanel == null ? null : list(rootPanel.get(1));
        ListNode props = own == null ? null : list(own.get(1));
        int flag = LayoutReader.tabsFlagAt(props);
        assertTrue("the form holds a pages list", flag >= 0); //$NON-NLS-1$
        return token(props.get(flag));
    }

    /**
     * The node a slot of a block holds, serialized as the platform writes it.
     *
     * @param block the block
     * @param slot the slot's index
     * @return the serialized node
     */
    private static String slotSerialized(ListNode block, int slot)
    {
        return BraceTree.serialize(list(block.get(slot)));
    }

    // ---- the node helpers ------------------------------------------------------------------------

    /**
     * The list a node is.
     *
     * @param node the node
     * @return the list, or {@code null}
     */
    private static ListNode list(Node node)
    {
        return node instanceof ListNode asList ? asList : null;
    }

    /**
     * The text of a token.
     *
     * @param node the node
     * @return the text, or {@code null} for a list
     */
    private static String token(Node node)
    {
        return node instanceof Token text ? text.text() : null;
    }

    /**
     * The list a path of indexes leads to.
     *
     * @param node the list to walk
     * @param path the indexes, one per step
     * @return the list at the end of the path, or {@code null} when a step falls out
     */
    private static ListNode at(ListNode node, int... path)
    {
        ListNode found = node;
        for (int step : path)
        {
            found = found == null || found.size() <= step ? null : list(found.get(step));
        }
        return found;
    }
}
