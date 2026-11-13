/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Offline XMI fixtures: feature names follow MdObject and Document's cached EDT metamodel. */
public class AnAdoptedChildWithoutItsBaseIsAnExportFindingTest
{
    private static final String FQN = "Document.Order";
    private static String document(String body)
    {
        return "<mdclass:Document xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" objectBelonging=\"Adopted\" extendedConfigurationObject=\"11111111-1111-1111-1111-111111111111\">\n<name>Order</name>\n" + body + "</mdclass:Document>";
    }
    private static List<Map<String, Object>> scan(String xml)
    {
        return ValidateForExportTool.mdoFindings(xml, "src/Documents/Order/Order.mdo", FQN);
    }
    @Test public void missingAttributeBaseNamesTheExactChild()
    {
        List<Map<String, Object>> result = scan(document("<attributes objectBelonging=\"Adopted\"><name>Partner</name></attributes>"));
        assertEquals(result.toString(), 1, result.size());
        assertEquals("mdo-adopted-child-without-base", result.get(0).get("check"));
        assertEquals("Document.Order.Attribute.Partner", result.get(0).get("fqn"));
        assertEquals("ERROR", result.get(0).get("severity"));
        assertEquals(3, result.get(0).get("line"));
    }
    @Test public void emptyTabularBaseNamesTheExactSection()
    {
        List<Map<String, Object>> result = scan(document("<tabularSections objectBelonging=\"Adopted\" extendedConfigurationObject=\" \"><name>Lines</name></tabularSections>"));
        assertEquals(1, result.size());
        assertEquals("Document.Order.TabularSection.Lines", result.get(0).get("fqn"));
    }
    @Test public void nestedAttributeDoesNotBorrowItsParentsLink()
    {
        List<Map<String, Object>> result = scan(document("<tabularSections objectBelonging=\"Adopted\" extendedConfigurationObject=\"11111111-1111-1111-1111-111111111111\"><name>Lines</name><attributes objectBelonging=\"Adopted\"><name>Goods</name></attributes></tabularSections>"));
        assertEquals(1, result.size());
        assertEquals("Document.Order.TabularSection.Lines.Attribute.Goods", result.get(0).get("fqn"));
    }
    @Test public void ownChildrenAndValidAdoptedChildrenAreClean()
    {
        assertTrue(scan(document("<attributes><name>Own</name></attributes><attributes objectBelonging=\"Native\"><name>Own2</name></attributes><attributes objectBelonging=\"Adopted\" extendedConfigurationObject=\"11111111-1111-1111-1111-111111111111\"><name>Partner</name></attributes>")).isEmpty());
    }
    @Test public void nestedExtensionCheckIsNotTheBaseLink()
    {
        assertEquals(1, scan(document("<attributes objectBelonging=\"Adopted\"><name>Partner</name><extension><extendedConfigurationObject>Checked</extendedConfigurationObject></extension></attributes>")).size());
    }
    @Test public void wrappersAndWrongRootContextAreNotMetadataChildren()
    {
        String child = "<attributes objectBelonging=\"Adopted\"><name>Inherited</name></attributes>";
        assertTrue(scan(document("<extension>" + child + "</extension><forms><name>Form</name>" + child + "</forms>")).isEmpty());
        assertTrue(scan("<wrapper>" + child + "</wrapper>").isEmpty());
        assertTrue(scan(document(child).replace("mdclass:Document", "mdclass:Catalog")).isEmpty());
    }
    @Test public void actualSyntheticXmiAttributeNameIsResolved()
    {
        List<Map<String, Object>> result = scan("<mdclass:Document xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" name=\"Order\"><attributes name=\"Partner\" objectBelonging=\"Adopted\"/></mdclass:Document>");
        assertEquals(1, result.size());
        assertEquals("Document.Order.Attribute.Partner", result.get(0).get("fqn"));
    }
    @Test public void directBelongingElementIsNotInheritedByOwnSiblings()
    {
        List<Map<String, Object>> result = scan(document("<attributes><name>Partner</name><objectBelonging>Adopted</objectBelonging></attributes><attributes><name>Own</name></attributes>"));
        assertEquals(1, result.size());
        assertEquals("Document.Order.Attribute.Partner", result.get(0).get("fqn"));
    }
    @Test public void uppercaseBelongingAttributeIsRecognized()
    {
        assertBelongingSpelling("<attributes objectBelonging=\"ADOPTED\"><name>Partner</name></attributes>");
    }
    @Test public void uppercaseBelongingElementIsRecognized()
    {
        assertBelongingSpelling("<attributes><name>Partner</name><objectBelonging>ADOPTED</objectBelonging></attributes>");
    }
    @Test public void mixedCaseBelongingAttributeIsRecognized()
    {
        assertBelongingSpelling("<attributes objectBelonging=\"aDoPtEd\"><name>Partner</name></attributes>");
    }
    @Test public void mixedCaseBelongingElementIsRecognized()
    {
        assertBelongingSpelling("<attributes><name>Partner</name><objectBelonging>aDoPtEd</objectBelonging></attributes>");
    }
    private static void assertBelongingSpelling(String child)
    {
        List<Map<String, Object>> result = scan(document(child));
        assertEquals(result.toString(), 1, result.size());
        assertEquals("mdo-adopted-child-without-base", result.get(0).get("check"));
        assertEquals("Document.Order.Attribute.Partner", result.get(0).get("fqn"));
    }
    @Test public void commentsAndCdataAreNotChildMarkup()
    {
        assertTrue(scan(document("<!-- <attributes objectBelonging=\"Adopted\"><name>Fake</name></attributes> --><comment><![CDATA[<attributes objectBelonging=\"Adopted\"><name>Fake</name></attributes>]]></comment>")).isEmpty());
    }
    @Test public void externalEntitiesAndMalformedXmlAreNotExpanded()
    {
        assertTrue(scan("<!DOCTYPE x [<!ENTITY external SYSTEM 'http://127.0.0.1:1/not-read'>]>" + document("<attributes objectBelonging=\"Adopted\"><name>&external;</name></attributes>")).isEmpty());
        assertTrue(scan(document("<attributes objectBelonging=\"Adopted\"><name>Broken</name></attributes>") + "<broken").isEmpty());
    }
}
