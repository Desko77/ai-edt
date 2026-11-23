/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

public class MxlChangeSetTest
{
    @Test
    public void appliesMixedChangesToOneDocumentInOrder()
    {
        var document = com._1c.g5.v8.dt.moxel.sheet.SheetFactory.createSpreadsheetDocument();
        var tool = new MxlWorkshopTool();
        var changes = MxlWorkshopTool.parseChanges("""
            [{"operation":"set_cell","row":1,"col":1,"text":"Header"},
             {"operation":"merge_cells","fromRow":1,"toRow":1,"fromCol":1,"toCol":2},
             {"operation":"set_cell","row":2,"col":1,"parameter":"Amount"},
             {"operation":"format_cells","row":2,"col":1,"textPlacement":"wrap"},
             {"operation":"add_named_area","areaName":"Header","areaKind":"rows","fromRow":1,"toRow":1}]
            """);
        for (var change : changes)
        {
            org.junit.Assert.assertNull(tool.prepareChange(change, "Fixture", "Document.Fixture", "Print")
                .apply(document));
        }
        var cell = document.getRows().map().get(1).getCells().map().get(0);
        org.junit.Assert.assertEquals("Amount", cell.getParameter());
        var format = document.getFormats().get(cell.getFormatIndex());
        org.junit.Assert.assertEquals("Wrap", format.getTextPlacement().getName());
        org.junit.Assert.assertEquals(com._1c.g5.v8.dt.moxel.content.FillType.PARAMETER, format.getFillType());
        var content = ru.aiedt.mcp.server.support.BmTemplateHelper.readSpreadsheet(document, "ru");
        assertTrue(content.get("namedAreas").toString().contains("Header"));
        assertTrue(content.get("cells").toString().contains("Header"));
        org.junit.Assert.assertEquals(1, ((java.util.List<?>) content.get("merges")).size());
    }

    @Test
    public void refusesAnEmptyChangeSetBeforeResolvingTheProject()
    {
        String answer = new MxlWorkshopTool().execute(Map.of(
            "operation", "apply_changes", "projectName", "MissingFixtureProject",
            "ownerFqn", "Document.Fixture", "templateName", "Print", "changes", "[]"));
        assertTrue(answer, answer.contains("invalidTemplateChange"));
        assertTrue(answer, answer.contains("\"mutationStarted\":false"));
    }

    @Test
    public void refusesTargetOverridesInsideAChange()
    {
        String answer = new MxlWorkshopTool().execute(Map.of(
            "operation", "apply_changes", "projectName", "MissingFixtureProject",
            "ownerFqn", "Document.Fixture", "templateName", "Print", "dryRun", "true",
            "changes", "[{\"operation\":\"set_cell\",\"row\":1,\"col\":1,\"text\":\"Header\","
                + "\"templateName\":\"Other\"}]"));
        assertTrue(answer, answer.contains("invalidTemplateChange"));
        assertTrue(answer, answer.contains("\"changeIndex\":0"));
        assertTrue(answer, answer.contains("\"mutationStarted\":false"));
    }

    @Test
    public void rejectsAnUnknownSecondChangeBeforeResolvingTheProject() throws Exception
    {
        String answer = new MxlWorkshopTool().execute(Map.of(
            "operation", "apply_changes", "projectName", "MissingFixtureProject",
            "ownerFqn", "Document.Fixture", "templateName", "Print",
            "changes", "[{\"operation\":\"set_cell\",\"row\":1,\"col\":1,\"text\":\"Header\"},"
                + "{\"operation\":\"not_an_operation\"}]"));
        assertTrue(answer, answer.contains("invalidTemplateChange"));
        assertTrue(answer, answer.contains("\"changeIndex\":1"));
        assertTrue(answer, answer.contains("\"mutationStarted\":false"));
        assertFalse(answer, answer.contains("\"success\":true"));
    }
}
