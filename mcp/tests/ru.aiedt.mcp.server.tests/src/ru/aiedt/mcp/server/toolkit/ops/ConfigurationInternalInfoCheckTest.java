/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the pre-export check for a configuration carrying no internal information.
 * <p>
 * The defect it catches is invisible everywhere else: EDT validates such a project clean, and only
 * the infobase update fails - with a message naming a {@code /Configuration.xml} an EDT project
 * does not have, so the search for the cause starts in the wrong place.
 * </p>
 */
public class ConfigurationInternalInfoCheckTest
{
    private static final String CONFIG_TAG =
        "<mdclass:Configuration xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"x\">";

    private static final String EXTENSION_TAG = "<mdclass:Configuration "
        + "xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" "
        + "xmlns:mdclassExtension=\"http://g5.1c.ru/v8/dt/metadata/mdclass/extension\" uuid=\"x\">";

    private static final String ENTRY =
        "  <containedObjects classId=\"9cd510cd-abfc-11d4-9434-004095e12fc7\" objectId=\"a\"/>";

    @Test
    public void aConfigurationDeclaringNoneIsFlagged()
    {
        assertTrue(ValidateForExportTool.configurationLacksInternalInfo(
            CONFIG_TAG, CONFIG_TAG + "\n  <name>Probe</name>\n"));
    }

    @Test
    public void aConfigurationDeclaringSomeIsLeftAlone()
    {
        assertFalse(ValidateForExportTool.configurationLacksInternalInfo(
            CONFIG_TAG, CONFIG_TAG + "\n" + ENTRY + "\n"));
    }

    /**
     * Six is not a defect, so the test is "declares none" rather than "declares seven".
     * <p>
     * Two configurations in the census carry six, both on 8.2.16 compatibility, because the seventh
     * class id came with a later platform. A check that counted would fail both of them for having
     * nothing wrong.
     * </p>
     */
    @Test
    public void aShorterButNonEmptyListIsNotADefect()
    {
        StringBuilder six = new StringBuilder(CONFIG_TAG);
        for (int i = 0; i < 6; i++)
        {
            six.append('\n').append(ENTRY);
        }
        assertFalse(ValidateForExportTool.configurationLacksInternalInfo(CONFIG_TAG, six.toString()));
    }

    @Test
    public void anExtensionRootIsCheckedTheSameWay()
    {
        // All 54 extension roots in the census carry them, so an extension missing them is as
        // broken as a configuration missing them.
        assertTrue(ValidateForExportTool.configurationLacksInternalInfo(
            EXTENSION_TAG, EXTENSION_TAG + "\n  <name>Probe</name>\n"));
        assertFalse(ValidateForExportTool.configurationLacksInternalInfo(
            EXTENSION_TAG, EXTENSION_TAG + "\n" + ENTRY + "\n"));
    }

    @Test
    public void anOrdinaryObjectIsNotACandidate()
    {
        // Only a root carries contained objects. Flagging a catalogue for lacking them would fire
        // on every object in every project.
        assertFalse(ValidateForExportTool.configurationLacksInternalInfo(
            "<mdclass:Catalog uuid=\"x\">", "<mdclass:Catalog uuid=\"x\">\n  <name>Products</name>\n"));
    }

    @Test
    public void afileWithNoRootTagIsNotGuessedAbout()
    {
        assertFalse(ValidateForExportTool.configurationLacksInternalInfo(null, "anything"));
        assertFalse(ValidateForExportTool.configurationLacksInternalInfo(CONFIG_TAG, null));
    }
}
