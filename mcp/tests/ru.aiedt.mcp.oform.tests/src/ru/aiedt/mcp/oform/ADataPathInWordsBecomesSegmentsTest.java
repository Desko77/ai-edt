/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutNames;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.layout.TypePattern;
import ru.aiedt.mcp.oform.ops.DataPaths;

/**
 * A data path in words becomes the segments the layout records: an attribute by its id, a member
 * by its uuid, a standard attribute by its negative code - the words matched without regard to
 * their letter case, and the standard attributes of the main attribute named by the owner's kind
 * when the configuration cannot name them. A path that names nothing is refused with the reason:
 * the unknown attribute hears the form's own attributes, the unknown member hears where it was
 * looked for. A value named in words becomes its pattern, and an element kind without a box
 * answers the measured default.
 */
public class ADataPathInWordsBecomesSegmentsTest
{
    /** The produced-type uuid of the fixture's main attribute, a catalog object. */
    private static final String OBJECT_TYPE = "4634bd51-751b-45b1-a081-6373e6f1250b"; //$NON-NLS-1$

    /** The member uuid the fixture's configuration names the article by. */
    private static final String ARTICLE_UUID = "7a1f0c2e-5b6d-4e8f-9a0b-1c2d3e4f5a6b"; //$NON-NLS-1$

    private static String layoutText;

    /** Reads the fixture once; every test resolves against the configuration's names or none. */
    @BeforeClass
    public static void theFixture() throws IOException
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
    }

    /**
     * @param names the names the configuration answers, or none
     * @return the fixture's layout, resolved against the names
     */
    private static FormLayout layout(LayoutNames names)
    {
        return LayoutReader.read(BraceTree.parse(layoutText), "Catalog", names); //$NON-NLS-1$
    }

    /**
     * Resolves a path against the names given.
     *
     * @param names the names, or {@link LayoutNames#NONE}
     * @param ownerKind the owner's kind for the main attribute's standards
     * @param data the path in words
     * @param path receives the segments
     * @param patternOut receives the pattern
     * @return the refusal, or {@code null} when the path resolved
     */
    private static String resolve(LayoutNames names, String ownerKind, String data,
        List<FormLayout.Segment> path, BraceTree.ListNode[] patternOut)
    {
        return DataPaths.resolve(layout(names), names, ownerKind, data, path, patternOut);
    }

    /** A member path becomes an attribute segment and a member segment, and ends at the member's pattern. */
    @Test
    public void aMemberPathBecomesTwoSegments()
    {
        List<FormLayout.Segment> path = new ArrayList<>();
        BraceTree.ListNode[] patternOut = new BraceTree.ListNode[1];
        String problem = resolve(ALayoutIsReadOffTheContainerTest.NAMES, "Catalog", "Объект.Артикул", path, //$NON-NLS-1$ //$NON-NLS-2$
            patternOut);
        assertNull(problem);
        assertEquals(2, path.size());
        assertEquals(FormLayout.Segment.ATTRIBUTE, path.get(0).kind);
        assertEquals("101", path.get(0).value); //$NON-NLS-1$
        assertEquals(FormLayout.Segment.MEMBER, path.get(1).kind);
        assertEquals(ARTICLE_UUID, path.get(1).value);
        assertEquals(List.of("S"), TypePattern.letters(patternOut[0])); //$NON-NLS-1$
    }

    /** The words are matched without regard to their letter case, the attribute's and the member's. */
    @Test
    public void theWordsAreMatchedCaseInsensitively()
    {
        List<FormLayout.Segment> path = new ArrayList<>();
        BraceTree.ListNode[] patternOut = new BraceTree.ListNode[1];
        String problem = resolve(ALayoutIsReadOffTheContainerTest.NAMES, "Catalog", "объект.артикул", path, //$NON-NLS-1$ //$NON-NLS-2$
            patternOut);
        assertNull(problem);
        assertEquals(List.of("101", ARTICLE_UUID), //$NON-NLS-1$
            List.of(path.get(0).value, path.get(1).value));
    }

    /** A whole attribute ends at its own pattern; a plain attribute ends at the pattern it carries. */
    @Test
    public void aWholeAttributeEndsAtItsOwnPattern()
    {
        List<FormLayout.Segment> path = new ArrayList<>();
        BraceTree.ListNode[] patternOut = new BraceTree.ListNode[1];
        String object = resolve(ALayoutIsReadOffTheContainerTest.NAMES, "Catalog", "Объект", path, patternOut); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(object);
        assertEquals(1, path.size());
        assertEquals("101", path.get(0).value); //$NON-NLS-1$
        assertEquals(OBJECT_TYPE, TypePattern.of(patternOut[0]).singleProducedType());

        List<FormLayout.Segment> plain = new ArrayList<>();
        BraceTree.ListNode[] plainOut = new BraceTree.ListNode[1];
        String article = resolve(ALayoutIsReadOffTheContainerTest.NAMES, "Catalog", "Артикул", plain, plainOut); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(article);
        assertEquals(1, plain.size());
        assertEquals("103", plain.get(0).value); //$NON-NLS-1$
        assertEquals("String(25)", TypePattern.of(plainOut[0]).describe(LayoutNames.NONE)); //$NON-NLS-1$
    }

    /** A standard attribute becomes its negative code and carries the pattern the corpus measured for it. */
    @Test
    public void aStandardAttributeBecomesItsNegativeCode()
    {
        List<FormLayout.Segment> path = new ArrayList<>();
        BraceTree.ListNode[] patternOut = new BraceTree.ListNode[1];
        String code = resolve(ALayoutIsReadOffTheContainerTest.NAMES, "Catalog", "Объект.Код", path, patternOut); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(code);
        assertEquals(2, path.size());
        assertEquals(FormLayout.Segment.STANDARD, path.get(1).kind);
        assertEquals("-2", path.get(1).value); //$NON-NLS-1$
        assertEquals(List.of("S"), TypePattern.letters(patternOut[0])); //$NON-NLS-1$

        List<FormLayout.Segment> parent = new ArrayList<>();
        BraceTree.ListNode[] parentOut = new BraceTree.ListNode[1];
        assertNull(resolve(ALayoutIsReadOffTheContainerTest.NAMES, "Catalog", "Объект.Родитель", parent, parentOut)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("-4", parent.get(1).value); //$NON-NLS-1$
    }

    /** The owner's kind names the main attribute's standards when the configuration answers nothing. */
    @Test
    public void theOwnerKindNamesTheStandardsWithoutAConfiguration()
    {
        List<FormLayout.Segment> path = new ArrayList<>();
        BraceTree.ListNode[] patternOut = new BraceTree.ListNode[1];
        String date = resolve(LayoutNames.NONE, "Document", "Объект.Дата", path, patternOut); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(date);
        assertEquals(2, path.size());
        assertEquals(FormLayout.Segment.STANDARD, path.get(1).kind);
        assertEquals("-3", path.get(1).value); //$NON-NLS-1$
        assertEquals(List.of("D"), TypePattern.letters(patternOut[0])); //$NON-NLS-1$

        List<FormLayout.Segment> number = new ArrayList<>();
        BraceTree.ListNode[] numberOut = new BraceTree.ListNode[1];
        assertNull(resolve(LayoutNames.NONE, "Document", "Объект.Номер", number, numberOut)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("-2", number.get(1).value); //$NON-NLS-1$
    }

    /** An unknown attribute is refused with the form's own attributes; nothing is resolved. */
    @Test
    public void anUnknownAttributeIsRefusedWithTheFormsOwn()
    {
        List<FormLayout.Segment> path = new ArrayList<>();
        String problem = resolve(ALayoutIsReadOffTheContainerTest.NAMES, "Catalog", "Нигде.Код", path, //$NON-NLS-1$ //$NON-NLS-2$
            new BraceTree.ListNode[1]);
        assertTrue(problem, problem.contains("No form attribute named Нигде")); //$NON-NLS-1$
        assertTrue(problem, problem.contains("Объект, Активен, Артикул")); //$NON-NLS-1$
        assertTrue("nothing is resolved on a refusal", path.isEmpty()); //$NON-NLS-1$
    }

    /** An unknown member is refused with the path it was looked under, and names the missing configuration. */
    @Test
    public void anUnknownMemberIsRefusedWithWhereItWasLooked()
    {
        List<FormLayout.Segment> path = new ArrayList<>();
        String problem = resolve(ALayoutIsReadOffTheContainerTest.NAMES, "Catalog", "Объект.Нигде", path, //$NON-NLS-1$ //$NON-NLS-2$
            new BraceTree.ListNode[1]);
        assertTrue(problem, problem.contains("No member named Нигде under Объект")); //$NON-NLS-1$

        String unloaded = resolve(LayoutNames.NONE, "Document", "Объект.Артикул", new ArrayList<>(), //$NON-NLS-1$ //$NON-NLS-2$
            new BraceTree.ListNode[1]);
        assertTrue(unloaded, unloaded.contains("No member named Артикул under Объект")); //$NON-NLS-1$
        assertTrue(unloaded, unloaded.contains("the configuration is not loaded in this environment")); //$NON-NLS-1$
    }

    /** A value named in words becomes its pattern, in English or in Russian; another word answers null. */
    @Test
    public void aValueWordBecomesItsPattern()
    {
        assertEquals(List.of("S"), TypePattern.letters(DataPaths.typePattern("String"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("S"), TypePattern.letters(DataPaths.typePattern("  строка  "))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Number(15,2)", TypePattern.of(DataPaths.typePattern("число")).describe(LayoutNames.NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("D"), TypePattern.letters(DataPaths.typePattern("Date"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("B"), TypePattern.letters(DataPaths.typePattern("БУЛЕВО"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DataPaths.typePattern("туман"));
    }

    /** An element kind without a box answers the measured default, in any letter case. */
    @Test
    public void aKindWithoutABoxAnswersItsDefault()
    {
        assertEquals(80, DataPaths.defaultWidth("button")); //$NON-NLS-1$
        assertEquals(23, DataPaths.defaultHeight("BUTTON")); //$NON-NLS-1$
        assertEquals(400, DataPaths.defaultWidth("Table")); //$NON-NLS-1$
        assertEquals(150, DataPaths.defaultHeight("table")); //$NON-NLS-1$
        assertEquals(120, DataPaths.defaultWidth("label")); //$NON-NLS-1$
        assertEquals(19, DataPaths.defaultHeight("checkbox")); //$NON-NLS-1$
        assertEquals(120, DataPaths.defaultWidth("radiobtn")); //$NON-NLS-1$
        assertEquals(300, DataPaths.defaultWidth("group")); //$NON-NLS-1$
        assertEquals(150, DataPaths.defaultHeight("Panel")); //$NON-NLS-1$
        assertEquals(200, DataPaths.defaultWidth("field")); //$NON-NLS-1$
        assertEquals(19, DataPaths.defaultHeight("field")); //$NON-NLS-1$
    }
}
