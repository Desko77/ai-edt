/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Assume;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.version.Version;

/**
 * The stock pictures of a platform version are read from the descriptions the platform registers:
 * the English and the Russian name of one picture are paired, standard and extended pictures are
 * kept apart, and the validator accepts a name the list carries and refuses one it does not.
 */
public class StockPicturesTest
{
    private static final String PRINT_URI = "platform:/plugin/v8.3.27/stdPictures.xml#//Print"; //$NON-NLS-1$

    /**
     * The two descriptions of one picture become one entry with both names; other prefixes are
     * left out; standard pictures come before extended ones.
     */
    @Test
    public void theNamesOfOnePictureArePaired()
    {
        List<StockPictures.Entry> pictures = StockPictures.fromDescriptions(List.of(
            new String[] { "StdExtPicture.Undo", "ext#//Undo" }, //$NON-NLS-1$ //$NON-NLS-2$
            new String[] { "StdPicture.Print", PRINT_URI }, //$NON-NLS-1$
            new String[] { "StdPicture.Печать", PRINT_URI }, //$NON-NLS-1$
            new String[] { "StdExtPicture.Отменить", "ext#//Undo" }, //$NON-NLS-1$ //$NON-NLS-2$
            new String[] { "CommonPicture.Logo", "cfg#//Logo" }, //$NON-NLS-1$ //$NON-NLS-2$
            new String[] { "StdPicture.", "broken" })); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(2, pictures.size());
        StockPictures.Entry print = pictures.get(0);
        assertEquals(StockPictures.STD, print.prefix);
        assertEquals("Print", print.name); //$NON-NLS-1$
        assertEquals("Печать", print.nameRu); //$NON-NLS-1$
        StockPictures.Entry undo = pictures.get(1);
        assertEquals(StockPictures.STD_EXT, undo.prefix);
        assertEquals("Undo", undo.name); //$NON-NLS-1$
        assertEquals("Отменить", undo.nameRu); //$NON-NLS-1$
    }

    /**
     * A picture answers to either name, whatever the case, and a filter reads both names.
     */
    @Test
    public void aPictureAnswersToEitherName()
    {
        StockPictures.Entry print = StockPictures.fromDescriptions(List.of(
            new String[] { "StdPicture.Print", PRINT_URI }, //$NON-NLS-1$
            new String[] { "StdPicture.Печать", PRINT_URI })).get(0); //$NON-NLS-1$

        assertTrue(print.answersTo("print")); //$NON-NLS-1$
        assertTrue(print.answersTo("ПЕЧАТЬ")); //$NON-NLS-1$
        assertFalse(print.answersTo("Erase")); //$NON-NLS-1$
        assertTrue(print.matches("печ")); //$NON-NLS-1$
        assertTrue(print.matches(null));
        assertFalse(print.matches("Delete")); //$NON-NLS-1$
    }

    /**
     * A picture registered under one language only keeps that name.
     */
    @Test
    public void aPictureWithOneNameKeepsIt()
    {
        StockPictures.Entry only = StockPictures.fromDescriptions(List.<String[]>of(
            new String[] { "StdPicture.Report", "u#//Report" })).get(0); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Report", only.name); //$NON-NLS-1$
        assertNull(only.nameRu);
    }

    /**
     * The list names only the pictures of the prefix asked for, filtered by either name.
     */
    @Test
    public void theListNamesThePicturesOfOnePrefix()
    {
        List<StockPictures.Entry> pictures = StockPictures.fromDescriptions(List.of(
            new String[] { "StdPicture.Print", PRINT_URI }, //$NON-NLS-1$
            new String[] { "StdPicture.Печать", PRINT_URI }, //$NON-NLS-1$
            new String[] { "StdExtPicture.Undo", "ext#//Undo" })); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of("Print"), StockPictures.names(pictures, StockPictures.STD, "печ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("Undo"), StockPictures.names(pictures, StockPictures.STD_EXT, null)); //$NON-NLS-1$
        assertEquals(List.of(), StockPictures.names(pictures, StockPictures.STD, "Undo")); //$NON-NLS-1$
    }

    /**
     * A stock picture is written under its English name whichever name it was given by; a name
     * the list does not carry, and a common picture, are written as given.
     */
    @Test
    public void aStockPictureIsWrittenUnderItsEnglishName()
    {
        List<StockPictures.Entry> pictures = StockPictures.fromDescriptions(List.of(
            new String[] { "StdPicture.Print", PRINT_URI }, //$NON-NLS-1$
            new String[] { "StdPicture.Печать", PRINT_URI })); //$NON-NLS-1$

        assertEquals("StdPicture.Print", StockPictures.writtenName(pictures, "StdPicture.Печать")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("StdPicture.Print", StockPictures.writtenName(pictures, "StdPicture.print")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("StdExtPicture.Печать", //$NON-NLS-1$
            StockPictures.writtenName(pictures, "StdExtPicture.Печать")); //$NON-NLS-1$
        assertEquals("CommonPicture.Logo", StockPictures.writtenName(pictures, "CommonPicture.Logo")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Print", StockPictures.writtenName(pictures, "Print")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Where the runtime registers the stock pictures of a version, the list is not empty, carries
     * {@code Print} with its Russian name, and the validator accepts it while refusing a name no
     * picture has.
     */
    @Test
    public void theRegistryOfTheNewestVersionIsRead()
    {
        List<StockPictures.Entry> pictures = StockPictures.read(Version.LATEST);
        Assume.assumeFalse("this runtime registers no stock pictures", pictures.isEmpty()); //$NON-NLS-1$

        assertTrue(pictures.stream().anyMatch(p -> StockPictures.STD.equals(p.prefix) && p.answersTo("Print") //$NON-NLS-1$
            && "Печать".equals(p.nameRu))); //$NON-NLS-1$
        assertNull(PictureValidator.validate(null, "StdPicture.Print")); //$NON-NLS-1$
        assertNull(PictureValidator.validate(null, "StdPicture.Печать")); //$NON-NLS-1$
        assertTrue(PictureValidator.validate(null, "StdPicture.NoSuchPictureAnywhere") != null); //$NON-NLS-1$
    }
}
