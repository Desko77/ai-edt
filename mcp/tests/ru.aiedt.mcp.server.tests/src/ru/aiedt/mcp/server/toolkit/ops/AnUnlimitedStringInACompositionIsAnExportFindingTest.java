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
 * An .mdo element typed as a composition that includes an unbounded String is an export finding.
 * <p>
 * The platform loads such a description and then refuses the database update with "Ошибка SDBL:
 * Строка без спецификации длины недопустима" - a class the EDT validator does not see. A String
 * of its own without a length is valid and is not this finding.
 * </p>
 */
public class AnUnlimitedStringInACompositionIsAnExportFindingTest
{
    private static final String FILE = "src/Catalogs/Goods/Goods.mdo"; //$NON-NLS-1$
    private static final String FQN = "Catalog.Goods"; //$NON-NLS-1$

    /**
     * Wraps an attribute of the given name and type block into a catalog .mdo.
     *
     * @param name the attribute name
     * @param typeBlock the {@code <type>} element to place inside
     * @return the .mdo text
     */
    private static String catalogWith(String name, String typeBlock)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<mdclass:Catalog xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" " //$NON-NLS-1$
            + "uuid=\"11111111-1111-1111-1111-111111111111\">\n" //$NON-NLS-1$
            + "  <name>Goods</name>\n" //$NON-NLS-1$
            + "  <attributes uuid=\"22222222-2222-2222-2222-222222222222\">\n" //$NON-NLS-1$
            + "    <name>" + name + "</name>\n" //$NON-NLS-1$ //$NON-NLS-2$
            + typeBlock
            + "  </attributes>\n" //$NON-NLS-1$
            + "</mdclass:Catalog>\n"; //$NON-NLS-1$
    }

    /**
     * The offending composition, in both spellings of an empty qualifier.
     */
    @Test
    public void aCompositionWithAnUnboundedStringIsOneFindingThatNamesTheElement()
    {
        String content = catalogWith("Kind", "    <type>\n" //$NON-NLS-1$
            + "      <types>String</types>\n" //$NON-NLS-1$
            + "      <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
            + "      <stringQualifiers/>\n" //$NON-NLS-1$
            + "    </type>\n"); //$NON-NLS-1$

        List<Map<String, Object>> findings = ValidateForExportTool.mdoFindings(content, FILE, FQN);

        assertEquals(findings.toString(), 1, findings.size());
        Map<String, Object> finding = findings.get(0);
        assertEquals("mdo-composite-string-without-length", finding.get("check")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ERROR", finding.get("severity")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(FILE, finding.get("file")); //$NON-NLS-1$
        assertEquals(FQN, finding.get("fqn")); //$NON-NLS-1$
        String message = String.valueOf(finding.get("message")); //$NON-NLS-1$
        // The caller has to be able to find both what carries it and where it lives.
        assertTrue(message, message.contains("'Kind'")); //$NON-NLS-1$
        assertTrue(message, message.contains(FQN));
        assertTrue(message, message.contains("length")); //$NON-NLS-1$
    }

    /**
     * An absent qualifier element is the same unlimited string as an empty one.
     */
    @Test
    public void aMissingQualifierElementIsReadAsUnlimitedToo()
    {
        String content = catalogWith("Kind", "    <type>\n" //$NON-NLS-1$
            + "      <types>String</types>\n" //$NON-NLS-1$
            + "      <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
            + "    </type>\n"); //$NON-NLS-1$

        assertEquals(1, ValidateForExportTool.mdoFindings(content, FILE, FQN).size());
        // And so is a length written as zero.
        String declaredZero = catalogWith("Kind", "    <type>\n" //$NON-NLS-1$
            + "      <types>String</types>\n" //$NON-NLS-1$
            + "      <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
            + "      <stringQualifiers>\n" //$NON-NLS-1$
            + "        <length>0</length>\n" //$NON-NLS-1$
            + "      </stringQualifiers>\n" //$NON-NLS-1$
            + "    </type>\n"); //$NON-NLS-1$
        assertEquals(1, ValidateForExportTool.mdoFindings(declaredZero, FILE, FQN).size());
    }

    /**
     * A bounded composition and a lone String are what the platform accepts.
     */
    @Test
    public void aBoundedCompositionAndALoneStringAreNotFindings()
    {
        String bounded = catalogWith("Kind", "    <type>\n" //$NON-NLS-1$
            + "      <types>String</types>\n" //$NON-NLS-1$
            + "      <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
            + "      <stringQualifiers>\n" //$NON-NLS-1$
            + "        <length>20</length>\n" //$NON-NLS-1$
            + "      </stringQualifiers>\n" //$NON-NLS-1$
            + "    </type>\n"); //$NON-NLS-1$
        assertTrue(ValidateForExportTool.mdoFindings(bounded, FILE, FQN).toString(),
            ValidateForExportTool.mdoFindings(bounded, FILE, FQN).isEmpty());

        String lone = catalogWith("Note", "    <type>\n" //$NON-NLS-1$
            + "      <types>String</types>\n" //$NON-NLS-1$
            + "      <stringQualifiers/>\n" //$NON-NLS-1$
            + "    </type>\n"); //$NON-NLS-1$
        assertTrue(ValidateForExportTool.mdoFindings(lone, FILE, FQN).isEmpty());

        // No String at all: an unbounded composition of other types is not this check's business.
        String numbers = catalogWith("Amount", "    <type>\n" //$NON-NLS-1$
            + "      <types>Number</types>\n" //$NON-NLS-1$
            + "      <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
            + "    </type>\n"); //$NON-NLS-1$
        assertTrue(ValidateForExportTool.mdoFindings(numbers, FILE, FQN).isEmpty());
    }

    /**
     * A type that is not stored is not this check's business.
     * <p>
     * A command names its parameter type at {@code <commandParameterType>}, a session parameter
     * and an external data source table field carry a {@code <type>} that never becomes a
     * configuration column; a form attribute and a DCS field carry theirs in another file. The
     * platform loads all of them as they stand, so an unbounded String inside a composition there
     * is not a finding.
     * </p>
     */
    @Test
    public void aTypeThatIsNotStoredIsNotAFinding()
    {
        // The command spells the composition under its own element, with no <type> block at all.
        String command = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<mdclass:Catalog xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" " //$NON-NLS-1$
            + "uuid=\"11111111-1111-1111-1111-111111111111\">\n" //$NON-NLS-1$
            + "  <commands uuid=\"55555555-5555-5555-5555-555555555555\">\n" //$NON-NLS-1$
            + "    <name>FillKind</name>\n" //$NON-NLS-1$
            + "    <commandParameterType>\n" //$NON-NLS-1$
            + "      <types>String</types>\n" //$NON-NLS-1$
            + "      <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
            + "      <stringQualifiers/>\n" //$NON-NLS-1$
            + "    </commandParameterType>\n" //$NON-NLS-1$
            + "  </commands>\n" //$NON-NLS-1$
            + "</mdclass:Catalog>\n"; //$NON-NLS-1$
        assertTrue(ValidateForExportTool.mdoFindings(command, FILE, FQN).toString(),
            ValidateForExportTool.mdoFindings(command, FILE, FQN).isEmpty());

        // A session parameter carries its type under its own root, and is stored nowhere.
        String sessionParameter = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<mdclass:SessionParameter xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" " //$NON-NLS-1$
            + "uuid=\"66666666-6666-6666-6666-666666666666\">\n" //$NON-NLS-1$
            + "  <name>Kind</name>\n" //$NON-NLS-1$
            + "  <type>\n" //$NON-NLS-1$
            + "    <types>String</types>\n" //$NON-NLS-1$
            + "    <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
            + "    <stringQualifiers/>\n" //$NON-NLS-1$
            + "  </type>\n" //$NON-NLS-1$
            + "</mdclass:SessionParameter>\n"; //$NON-NLS-1$
        assertTrue(ValidateForExportTool.mdoFindings(sessionParameter,
            "src/SessionParameters/Kind/Kind.mdo", "SessionParameter.Kind").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$

        // An external data source table field holds the type of the source's own table.
        String tableField = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<mdclass:ExternalDataSource xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" " //$NON-NLS-1$
            + "uuid=\"77777777-7777-7777-7777-777777777777\">\n" //$NON-NLS-1$
            + "  <name>Source</name>\n" //$NON-NLS-1$
            + "  <tables uuid=\"88888888-8888-8888-8888-888888888888\">\n" //$NON-NLS-1$
            + "    <name>Goods</name>\n" //$NON-NLS-1$
            + "    <tableFields uuid=\"99999999-9999-9999-9999-999999999999\">\n" //$NON-NLS-1$
            + "      <name>Kind</name>\n" //$NON-NLS-1$
            + "      <type>\n" //$NON-NLS-1$
            + "        <types>String</types>\n" //$NON-NLS-1$
            + "        <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
            + "        <stringQualifiers/>\n" //$NON-NLS-1$
            + "      </type>\n" //$NON-NLS-1$
            + "    </tableFields>\n" //$NON-NLS-1$
            + "  </tables>\n" //$NON-NLS-1$
            + "</mdclass:ExternalDataSource>\n"; //$NON-NLS-1$
        assertTrue(ValidateForExportTool.mdoFindings(tableField,
            "src/ExternalDataSources/Source/Source.mdo", "ExternalDataSource.Source").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A stored container is judged wherever it sits in the file.
     */
    @Test
    public void aStoredContainerIsJudgedUnderItsObject()
    {
        // A register keeps its dimensions and resources beside its attributes, and all three are
        // table columns.
        String register = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<mdclass:InformationRegister xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" " //$NON-NLS-1$
            + "uuid=\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\">\n" //$NON-NLS-1$
            + "  <name>Facts</name>\n" //$NON-NLS-1$
            + "  <resources uuid=\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\">\n" //$NON-NLS-1$
            + "    <name>Kind</name>\n" //$NON-NLS-1$
            + "    <type>\n" //$NON-NLS-1$
            + "      <types>String</types>\n" //$NON-NLS-1$
            + "      <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
            + "      <stringQualifiers/>\n" //$NON-NLS-1$
            + "    </type>\n" //$NON-NLS-1$
            + "  </resources>\n" //$NON-NLS-1$
            + "  <dimensions uuid=\"cccccccc-cccc-cccc-cccc-cccccccccccc\">\n" //$NON-NLS-1$
            + "    <name>Source</name>\n" //$NON-NLS-1$
            + "    <type>\n" //$NON-NLS-1$
            + "      <types>String</types>\n" //$NON-NLS-1$
            + "      <types>CatalogRef.Sources</types>\n" //$NON-NLS-1$
            + "      <stringQualifiers/>\n" //$NON-NLS-1$
            + "    </type>\n" //$NON-NLS-1$
            + "  </dimensions>\n" //$NON-NLS-1$
            + "</mdclass:InformationRegister>\n"; //$NON-NLS-1$
        List<Map<String, Object>> findings = ValidateForExportTool.mdoFindings(register,
            "src/InformationRegisters/Facts/Facts.mdo", "InformationRegister.Facts"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(findings.toString(), 2, findings.size());
        assertTrue(String.valueOf(findings.get(0).get("message")), //$NON-NLS-1$
            String.valueOf(findings.get(0).get("message")).contains("'Kind'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(String.valueOf(findings.get(1).get("message")), //$NON-NLS-1$
            String.valueOf(findings.get(1).get("message")).contains("'Source'")); //$NON-NLS-1$ //$NON-NLS-2$

        // A constant stores its one value in a table of its own.
        String constant = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<mdclass:Constant xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" " //$NON-NLS-1$
            + "uuid=\"dddddddd-dddd-dddd-dddd-dddddddddddd\">\n" //$NON-NLS-1$
            + "  <name>Kind</name>\n" //$NON-NLS-1$
            + "  <type>\n" //$NON-NLS-1$
            + "    <types>String</types>\n" //$NON-NLS-1$
            + "    <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
            + "    <stringQualifiers/>\n" //$NON-NLS-1$
            + "  </type>\n" //$NON-NLS-1$
            + "</mdclass:Constant>\n"; //$NON-NLS-1$
        assertEquals(1, ValidateForExportTool.mdoFindings(constant,
            "src/Constants/Kind/Kind.mdo", "Constant.Kind").size()); //$NON-NLS-1$ //$NON-NLS-2$

        // And a tabular section's attribute sits one level deeper than the object's own.
        String tabular = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<mdclass:Catalog xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" " //$NON-NLS-1$
            + "uuid=\"11111111-1111-1111-1111-111111111111\">\n" //$NON-NLS-1$
            + "  <name>Goods</name>\n" //$NON-NLS-1$
            + "  <tabularSections uuid=\"eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee\">\n" //$NON-NLS-1$
            + "    <name>Prices</name>\n" //$NON-NLS-1$
            + "    <attributes uuid=\"ffffffff-ffff-ffff-ffff-ffffffffffff\">\n" //$NON-NLS-1$
            + "      <name>Kind</name>\n" //$NON-NLS-1$
            + "      <type>\n" //$NON-NLS-1$
            + "        <types>String</types>\n" //$NON-NLS-1$
            + "        <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
            + "        <stringQualifiers/>\n" //$NON-NLS-1$
            + "      </type>\n" //$NON-NLS-1$
            + "    </attributes>\n" //$NON-NLS-1$
            + "  </tabularSections>\n" //$NON-NLS-1$
            + "</mdclass:Catalog>\n"; //$NON-NLS-1$
        assertEquals(1, ValidateForExportTool.mdoFindings(tabular, FILE, FQN).size());
    }

    /**
     * Every carrier of a type block is judged, and each is reported on its own.
     */
    @Test
    public void aDefinedTypeAndARepeatedOffenceAreEachReported()
    {
        // A defined type has the same <type> shape under its own <name>, with no attributes.
        String definedType = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<mdclass:DefinedType xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" " //$NON-NLS-1$
            + "uuid=\"33333333-3333-3333-3333-333333333333\">\n" //$NON-NLS-1$
            + "  <name>KindOrText</name>\n" //$NON-NLS-1$
            + "  <type>\n" //$NON-NLS-1$
            + "    <types>String</types>\n" //$NON-NLS-1$
            + "    <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
            + "    <stringQualifiers/>\n" //$NON-NLS-1$
            + "  </type>\n" //$NON-NLS-1$
            + "</mdclass:DefinedType>\n"; //$NON-NLS-1$
        List<Map<String, Object>> findings = ValidateForExportTool.mdoFindings(definedType,
            "src/DefinedTypes/KindOrText/KindOrText.mdo", "DefinedType.KindOrText"); //$NON-NLS-1$
        assertEquals(findings.toString(), 1, findings.size());
        assertTrue(String.valueOf(findings.get(0).get("message")), //$NON-NLS-1$
            String.valueOf(findings.get(0).get("message")).contains("'KindOrText'")); //$NON-NLS-1$ //$NON-NLS-2$

        // Two attributes carrying it are two findings, not one.
        String twice = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<mdclass:Catalog xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" " //$NON-NLS-1$
            + "uuid=\"11111111-1111-1111-1111-111111111111\">\n" //$NON-NLS-1$
            + "  <name>Goods</name>\n" //$NON-NLS-1$
            + "  <attributes uuid=\"22222222-2222-2222-2222-222222222222\">\n" //$NON-NLS-1$
            + "    <name>Kind</name>\n" //$NON-NLS-1$
            + "    <type>\n" //$NON-NLS-1$
            + "      <types>String</types>\n" //$NON-NLS-1$
            + "      <types>CatalogRef.Kinds</types>\n" //$NON-NLS-1$
            + "      <stringQualifiers/>\n" //$NON-NLS-1$
            + "    </type>\n" //$NON-NLS-1$
            + "  </attributes>\n" //$NON-NLS-1$
            + "  <attributes uuid=\"44444444-4444-4444-4444-444444444444\">\n" //$NON-NLS-1$
            + "    <name>Source</name>\n" //$NON-NLS-1$
            + "    <type>\n" //$NON-NLS-1$
            + "      <types>CatalogRef.Sources</types>\n" //$NON-NLS-1$
            + "      <types>String</types>\n" //$NON-NLS-1$
            + "      <stringQualifiers/>\n" //$NON-NLS-1$
            + "    </type>\n" //$NON-NLS-1$
            + "  </attributes>\n" //$NON-NLS-1$
            + "</mdclass:Catalog>\n"; //$NON-NLS-1$
        List<Map<String, Object>> both = ValidateForExportTool.mdoFindings(twice, FILE, FQN);
        assertEquals(both.toString(), 2, both.size());
        assertTrue(String.valueOf(both.get(0).get("message")), //$NON-NLS-1$
            String.valueOf(both.get(0).get("message")).contains("'Kind'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(String.valueOf(both.get(1).get("message")), //$NON-NLS-1$
            String.valueOf(both.get(1).get("message")).contains("'Source'")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
