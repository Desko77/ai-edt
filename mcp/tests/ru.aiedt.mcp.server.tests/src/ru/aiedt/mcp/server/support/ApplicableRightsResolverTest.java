/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;

/**
 * Which rights a kind of metadata object may carry, and which refusals a missing registry must not
 * invent.
 */
public class ApplicableRightsResolverTest
{
    /**
     * Insert applies to a catalog and does not apply to an enumeration. The refusal names the
     * rights that do apply.
     */
    @Test
    public void insertIsRefusedOnAnEnumAndAllowedOnACatalog()
    {
        ApplicableRightsResolver.Decision enumeration = ApplicableRightsResolver.decide(
            "Enum", "Insert", ApplicableRightsResolver.knownRights("Enum"), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(enumeration.allowed);
        assertNotNull(enumeration.applicableRights);
        assertFalse(enumeration.applicableRights.contains("Insert")); //$NON-NLS-1$
        assertTrue(enumeration.applicableRights.contains("Read")); //$NON-NLS-1$
        assertTrue(enumeration.error.contains("Insert")); //$NON-NLS-1$
        assertTrue(enumeration.error.contains("Enum")); //$NON-NLS-1$

        ApplicableRightsResolver.Decision catalog = ApplicableRightsResolver.decide(
            "Catalog", "Insert", ApplicableRightsResolver.knownRights("Catalog"), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(catalog.allowed);
    }

    /**
     * Posting applies to a document and does not apply to a catalog. The catalog's list contains
     * Read and does not contain Posting.
     */
    @Test
    public void postingIsRefusedOnACatalogAndAllowedOnADocument()
    {
        Set<String> catalogRights = ApplicableRightsResolver.knownRights("Catalog"); //$NON-NLS-1$
        ApplicableRightsResolver.Decision catalog = ApplicableRightsResolver.decide(
            "Catalog", "Posting", catalogRights, false); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(catalog.allowed);
        assertTrue(catalog.applicableRights.contains("Read")); //$NON-NLS-1$
        assertFalse(catalog.applicableRights.contains("Posting")); //$NON-NLS-1$
        assertTrue(catalog.error.contains("applicable rights")); //$NON-NLS-1$

        ApplicableRightsResolver.Decision document = ApplicableRightsResolver.decide(
            "Document", "Posting", ApplicableRightsResolver.knownRights("Document"), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(document.allowed);
    }

    /**
     * No list means the registry is not ready. That is not a refusal, and neither is a right the
     * file already stores on this kind.
     */
    @Test
    public void anUnreadyRegistryAndAPrecedentAreNotRefusals()
    {
        ApplicableRightsResolver.Decision unready = ApplicableRightsResolver.decide(
            "Catalog", "Posting", null, false); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(unready.allowed);

        ApplicableRightsResolver.Decision precedent = ApplicableRightsResolver.decide(
            "Catalog", "Posting", ApplicableRightsResolver.knownRights("Catalog"), true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(precedent.allowed);

        assertNull(ApplicableRightsResolver.knownRights("NotAKind")); //$NON-NLS-1$
        assertNull(ApplicableRightsResolver.rightsFor(null));
    }

    /**
     * A right already stored on an object of the same kind is a precedent.
     *
     * @throws Exception when the fixture document cannot be parsed
     */
    @Test
    public void aRightAlreadyStoredOnTheSameKindIsSeen() throws Exception
    {
        String xml = "<Rights><object><name>Catalog.Товары</name>" //$NON-NLS-1$
            + "<right><name>Posting</name><value>true</value></right></object></Rights>"; //$NON-NLS-1$
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
            .getDocumentElement();

        assertTrue(ApplicableRightsResolver.seen(root, "Catalog", "Posting")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(ApplicableRightsResolver.seen(root, "Document", "Posting")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(ApplicableRightsResolver.seen(root, "Catalog", "Insert")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
