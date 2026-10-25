/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Locale;

import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.BraceTree.Token;
import ru.aiedt.mcp.oform.layout.LayoutNames;
import ru.aiedt.mcp.oform.layout.TypePattern;

/**
 * A type pattern as the layout records it: a node headed by {@code "Pattern"} is one, another
 * node is not; the first pattern under a node is found depth first; the produced-type uuids are
 * read lowercased and in order, and one uuid alone is the pattern's single produced type; the
 * letters name every item; and the words of a pattern - the types it admits with their
 * qualifiers, a platform type by its id, a produced type through the configuration's names - or
 * {@code Any} for a pattern without items.
 */
public class ATypePatternReadsItsQualifiersTest
{
    /** The produced-type uuid the fixture's configuration names a catalog object by. */
    private static final String OBJECT_TYPE = "4634bd51-751b-45b1-a081-6373e6f1250b"; //$NON-NLS-1$

    /** The id the platform gives a value table, in the upper case a record may carry. */
    private static final String VALUE_TABLE = "ACF6192E-81CA-46EF-93A6-5A6968B78663"; //$NON-NLS-1$

    /** A node headed by "Pattern" is a pattern; another node, a bare token and null are not. */
    @Test
    public void aPatternIsRecognizedByItsHead()
    {
        ListNode pattern = BraceTree.parse("{\"Pattern\",{\"S\",100,1}}"); //$NON-NLS-1$
        TypePattern of = TypePattern.of(pattern);
        assertNotNull(of);
        assertEquals(2, of.node().size());
        assertNull(TypePattern.of(BraceTree.parse("{\"Items\",{1}}"))); //$NON-NLS-1$
        assertNull(TypePattern.of(new Token("\"S\""))); //$NON-NLS-1$
        assertNull(TypePattern.of(null));
    }

    /** The first pattern under a node is found depth first, and a node without one answers null. */
    @Test
    public void theFirstPatternIsFoundDepthFirst()
    {
        ListNode holder = BraceTree.parse("{\"Element\",{\"Pattern\",{\"B\"}},{\"Pattern\",{\"S\"}}}"); //$NON-NLS-1$
        TypePattern found = TypePattern.find(holder);
        assertNotNull(found);
        assertEquals(List.of("B"), TypePattern.letters(found.node())); //$NON-NLS-1$
        assertNull(TypePattern.find(BraceTree.parse("{\"Element\",{1}}"))); //$NON-NLS-1$
        assertNull(TypePattern.find(null));
    }

    /** The produced-type uuids are read lowercased and in order, one or many. */
    @Test
    public void producedTypesAreLowercasedAndOrdered()
    {
        ListNode two = BraceTree.parse("{\"Pattern\",{\"#\",A" + OBJECT_TYPE.toUpperCase(Locale.ROOT) + "}," //$NON-NLS-1$ //$NON-NLS-2$
            + "{\"#\",B" + VALUE_TABLE + "}}"); //$NON-NLS-1$ //$NON-NLS-2$
        TypePattern of = TypePattern.of(two);
        assertEquals(List.of("a" + OBJECT_TYPE, "b" + VALUE_TABLE.toLowerCase(Locale.ROOT)), //$NON-NLS-1$ //$NON-NLS-2$
            of.producedTypeUuids());
        assertNull("two types are not a single one", of.singleProducedType()); //$NON-NLS-1$
        ListNode one = BraceTree.parse("{\"Pattern\",{\"#\"," + OBJECT_TYPE + "}}"); //$NON-NLS-1$ //$NON-NLS-2$
        TypePattern single = TypePattern.of(one);
        assertEquals(OBJECT_TYPE, single.singleProducedType());
        ListNode withPlainItem = BraceTree.parse("{\"Pattern\",{\"#\"," + OBJECT_TYPE + "},{\"S\"}}"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a pattern with another item is not a single produced type", //$NON-NLS-1$
            TypePattern.of(withPlainItem).singleProducedType());
        ListNode without = BraceTree.parse("{\"Pattern\"}"); //$NON-NLS-1$
        assertNull(TypePattern.of(without).singleProducedType());
    }

    /** The letters name every item in order; a null pattern has none. */
    @Test
    public void lettersNameEveryItem()
    {
        ListNode pattern = BraceTree.parse("{\"Pattern\",{\"S\",100,1},{\"N\",15,2,0},{\"D\"},{\"B\"}}"); //$NON-NLS-1$
        assertEquals(List.of("S", "N", "D", "B"), TypePattern.letters(pattern)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(List.of(), TypePattern.letters(null));
    }

    /** The words name the qualifiers: lengths, digits and fractions, the date fractions, the platform types. */
    @Test
    public void theWordsNameTheQualifiers()
    {
        assertEquals("String(100)", TypePattern.of(BraceTree.parse("{\"Pattern\",{\"S\",100,1}}")) //$NON-NLS-1$ //$NON-NLS-2$
            .describe(ALayoutIsReadOffTheContainerTest.NAMES));
        assertEquals("String", TypePattern.of(BraceTree.parse("{\"Pattern\",{\"S\",0,1}}")) //$NON-NLS-1$ //$NON-NLS-2$
            .describe(LayoutNames.NONE));
        assertEquals("String", TypePattern.of(BraceTree.parse("{\"Pattern\",{\"S\"}}")) //$NON-NLS-1$ //$NON-NLS-2$
            .describe(LayoutNames.NONE));
        assertEquals("Number(15,2)", TypePattern.of(BraceTree.parse("{\"Pattern\",{\"N\",15,2,0}}")) //$NON-NLS-1$ //$NON-NLS-2$
            .describe(LayoutNames.NONE));
        assertEquals("Number(10)", TypePattern.of(BraceTree.parse("{\"Pattern\",{\"N\",10,0,1}}")) //$NON-NLS-1$ //$NON-NLS-2$
            .describe(LayoutNames.NONE));
        assertEquals("Number", TypePattern.of(BraceTree.parse("{\"Pattern\",{\"N\"}}")) //$NON-NLS-1$ //$NON-NLS-2$
            .describe(LayoutNames.NONE));
        assertEquals("Date", TypePattern.of(BraceTree.parse("{\"Pattern\",{\"D\"}}")).describe(LayoutNames.NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Time", TypePattern.of(BraceTree.parse("{\"Pattern\",{\"D\",\"T\"}}")).describe(LayoutNames.NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("DateTime", TypePattern.of(BraceTree.parse("{\"Pattern\",{\"D\",\"DT\"}}")).describe(LayoutNames.NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Boolean", TypePattern.of(BraceTree.parse("{\"Pattern\",{\"B\"}}")).describe(LayoutNames.NONE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Any", TypePattern.of(BraceTree.parse("{\"Pattern\"}")).describe(LayoutNames.NONE)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A produced type is named through the configuration, a platform type by its id, an unknown one stays its uuid. */
    @Test
    public void typesAreNamedThroughTheNamesOrThePlatformTable()
    {
        assertEquals("CatalogObject.Products", TypePattern //$NON-NLS-1$
            .of(BraceTree.parse("{\"Pattern\",{\"#\"," + OBJECT_TYPE + "}}")) //$NON-NLS-1$ //$NON-NLS-2$
            .describe(ALayoutIsReadOffTheContainerTest.NAMES));
        // the same uuid with no names behind it stays the uuid, lowercased the way it was read
        assertEquals(OBJECT_TYPE, TypePattern.of(BraceTree.parse("{\"Pattern\",{\"#\"," + OBJECT_TYPE + "}}")) //$NON-NLS-1$ //$NON-NLS-2$
            .describe(LayoutNames.NONE));
        assertEquals("ValueTable", TypePattern.of(BraceTree.parse("{\"Pattern\",{\"#\"," + VALUE_TABLE + "}}")) //$NON-NLS-1$ //$NON-NLS-2$
            .describe(LayoutNames.NONE));
        assertEquals("ValueTable", TypePattern.platformType(VALUE_TABLE)); //$NON-NLS-1$
        assertNull(TypePattern.platformType("00000000-0000-0000-0000-000000000000")); //$NON-NLS-1$
        assertNull(TypePattern.platformType(null));
    }

    /** A produced type's own pattern carries the uuid alone, read back lowercase as the one type. */
    @Test
    public void aProducedTypePatternIsBuiltForOneType()
    {
        ListNode pattern = TypePattern.ofProducedType(OBJECT_TYPE.toUpperCase(Locale.ROOT));
        assertEquals(List.of("#"), TypePattern.letters(pattern)); //$NON-NLS-1$
        TypePattern of = TypePattern.of(pattern);
        assertEquals(List.of(OBJECT_TYPE), of.producedTypeUuids());
        assertEquals(OBJECT_TYPE, of.singleProducedType());
        assertEquals("CatalogObject.Products", of.describe(ALayoutIsReadOffTheContainerTest.NAMES)); //$NON-NLS-1$
    }
}
