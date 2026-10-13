/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * A report and a data processor keep the type composition their .mdo declares.
 * <p>
 * Both write their attributes and their tabular sections into the same {@code <attributes>} and
 * {@code <tabularSections>} elements a database object does, and the platform builds no column
 * from either. An unbounded String inside a composition there is not an export finding, and a
 * finding would block the database update.
 * </p>
 */
public class AReportKeepsItsCompositionOnExportTest
{
    /** An attribute carrying the unbounded composition, as an .mdo element. */
    private static final String UNBOUNDED_ATTRIBUTE = "    <type>\n" //$NON-NLS-1$
        + "      <types>String</types>\n" //$NON-NLS-1$
        + "      <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
        + "      <stringQualifiers/>\n" //$NON-NLS-1$
        + "    </type>\n"; //$NON-NLS-1$

    /**
     * The findings of a root object with one attribute and one tabular-section attribute, both
     * carrying the unbounded composition.
     *
     * @param root the .mdo root element name, without the namespace prefix
     * @param path the file path the findings name
     * @param fqn the object FQN
     * @return the findings
     */
    private static List<Map<String, Object>> findingsOf(String root, String path, String fqn)
    {
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<mdclass:" + root + " xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" " //$NON-NLS-1$ //$NON-NLS-2$
            + "uuid=\"11111111-1111-1111-1111-111111111111\">\n" //$NON-NLS-1$
            + "  <name>Object</name>\n" //$NON-NLS-1$
            + "  <attributes uuid=\"22222222-2222-2222-2222-222222222222\">\n" //$NON-NLS-1$
            + "    <name>Kind</name>\n" //$NON-NLS-1$
            + UNBOUNDED_ATTRIBUTE
            + "  </attributes>\n" //$NON-NLS-1$
            + "  <tabularSections uuid=\"33333333-3333-3333-3333-333333333333\">\n" //$NON-NLS-1$
            + "    <name>Prices</name>\n" //$NON-NLS-1$
            + "    <attributes uuid=\"44444444-4444-4444-4444-444444444444\">\n" //$NON-NLS-1$
            + "      <name>Kind</name>\n" //$NON-NLS-1$
            + "      <type>\n" //$NON-NLS-1$
            + "        <types>String</types>\n" //$NON-NLS-1$
            + "        <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
            + "        <stringQualifiers/>\n" //$NON-NLS-1$
            + "      </type>\n" //$NON-NLS-1$
            + "    </attributes>\n" //$NON-NLS-1$
            + "  </tabularSections>\n" //$NON-NLS-1$
            + "</mdclass:" + root + ">\n"; //$NON-NLS-1$ //$NON-NLS-2$
        return ValidateForExportTool.mdoFindings(content, path, fqn);
    }

    /**
     * Neither kind of object is a carrier of a table column, so neither composition is a finding.
     */
    @Test
    public void aReportAndADataProcessorKeepTheComposition()
    {
        assertTrue(findingsOf("Report", "src/Reports/Sales/Sales.mdo", "Report.Sales") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toString(),
            findingsOf("Report", "src/Reports/Sales/Sales.mdo", "Report.Sales").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(findingsOf("DataProcessor", "src/DataProcessors/Filler/Filler.mdo", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "DataProcessor.Filler").toString(), //$NON-NLS-1$
            findingsOf("DataProcessor", "src/DataProcessors/Filler/Filler.mdo", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "DataProcessor.Filler").isEmpty()); //$NON-NLS-1$

        // An external report and an external data processor root their own .mdo project.
        assertTrue(findingsOf("ExternalReport", "src/ExternalReports/Sales/Sales.mdo", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "ExternalReport.Sales").toString(), //$NON-NLS-1$
            findingsOf("ExternalReport", "src/ExternalReports/Sales/Sales.mdo", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "ExternalReport.Sales").isEmpty()); //$NON-NLS-1$

        assertTrue(findingsOf("ExternalDataProcessor", //$NON-NLS-1$
            "src/ExternalDataProcessors/Filler/Filler.mdo", "ExternalDataProcessor.Filler").toString(), //$NON-NLS-1$ //$NON-NLS-2$
            findingsOf("ExternalDataProcessor", //$NON-NLS-1$
                "src/ExternalDataProcessors/Filler/Filler.mdo", "ExternalDataProcessor.Filler").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An external data source cube writes dimensions and resources under the element names a
     * register uses, and none of them becomes a column of the infobase.
     */
    @Test
    public void anExternalDataSourceCubeKeepsTheComposition()
    {
        for (String root : new String[] { "Cube", "Table", "DimensionTable", "ExternalDataSource" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
                + "<mdclass:" + root + " xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" " //$NON-NLS-1$ //$NON-NLS-2$
                + "uuid=\"11111111-1111-1111-1111-111111111111\">\n" //$NON-NLS-1$
                + "  <name>Sales</name>\n" //$NON-NLS-1$
                + "  <dimensions uuid=\"22222222-2222-2222-2222-222222222222\">\n" //$NON-NLS-1$
                + "    <name>Kind</name>\n" //$NON-NLS-1$
                + UNBOUNDED_ATTRIBUTE
                + "  </dimensions>\n" //$NON-NLS-1$
                + "  <resources uuid=\"33333333-3333-3333-3333-333333333333\">\n" //$NON-NLS-1$
                + "    <name>Amount</name>\n" //$NON-NLS-1$
                + UNBOUNDED_ATTRIBUTE
                + "  </resources>\n" //$NON-NLS-1$
                + "</mdclass:" + root + ">\n"; //$NON-NLS-1$ //$NON-NLS-2$
            List<Map<String, Object>> findings = ValidateForExportTool.mdoFindings(content,
                "src/ExternalDataSources/Source/Cubes/Sales/Sales.mdo", "ExternalDataSource.Source"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(root + ": " + findings, findings.isEmpty()); //$NON-NLS-1$
        }
    }

    /**
     * A database object with the same elements is still judged: the composition becomes a column
     * for its attribute and for its tabular section's.
     */
    @Test
    public void aDatabaseObjectIsStillJudged()
    {
        List<Map<String, Object>> findings = findingsOf("Catalog", "src/Catalogs/Goods/Goods.mdo", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.Goods"); //$NON-NLS-1$
        assertEquals(findings.toString(), 2, findings.size());
        assertEquals("mdo-composite-string-without-length", findings.get(0).get("check")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ERROR", findings.get(0).get("severity")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
