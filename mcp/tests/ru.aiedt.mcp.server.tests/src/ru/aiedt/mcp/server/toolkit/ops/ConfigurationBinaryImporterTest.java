/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Covers what the binary import refuses before it starts a Designer or creates an infobase.
 * <p>
 * The staging run itself needs a platform runtime and is verified against a live workspace. What is
 * pinned here is the guard in front of it: this operation costs minutes and leaves an infobase
 * behind while it works, so every reason it cannot succeed has to be found first.
 * </p>
 */
public class ConfigurationBinaryImporterTest
{
    @Test
    public void theNameSaysWhichDirectionItGoes()
    {
        assertEquals("import_configuration_from_binary", new ConfigurationBinaryImporter().getName()); //$NON-NLS-1$
        assertEquals(IMcpTool.ResponseType.JSON, new ConfigurationBinaryImporter().getResponseType());
    }

    @Test
    public void theDescriptionSaysWhatItCostsAndWhatItTouches()
    {
        // An agent picks by description, and the two things it must know before calling are that
        // this takes minutes and that it does not touch anybody's existing infobase.
        String description = new ConfigurationBinaryImporter().getDescription();
        assertTrue(description.contains(".cf")); //$NON-NLS-1$
        assertTrue(description.contains(".cfe")); //$NON-NLS-1$
        assertTrue("the staging infobase is the part a caller must be told about", //$NON-NLS-1$
            description.contains("staging infobase")); //$NON-NLS-1$
        assertTrue("say it is not free", description.contains("minutes")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theSchemaOffersTheStagingChoices()
    {
        String schema = new ConfigurationBinaryImporter().getInputSchema();
        assertTrue(schema.contains("\"binaryPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"platform\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"extensionName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"baseConfigurationPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"keepXmlPath\"")); //$NON-NLS-1$
    }

    @Test
    public void aCallWithoutItsParametersIsAnsweredNotThrown()
    {
        assertTrue(new ConfigurationBinaryImporter().execute(new HashMap<>()).contains("required")); //$NON-NLS-1$

        Map<String, String> noProject = new HashMap<>();
        noProject.put("binaryPath", "C:/tmp/ERP.cf"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(new ConfigurationBinaryImporter().execute(noProject).contains("required")); //$NON-NLS-1$
    }

    @Test
    public void theWrongKindOfBinaryIsSentToTheToolThatReadsIt()
    {
        // Refusing is not enough when a neighbouring tool does handle the file: an agent told only
        // "no" tries the same call again with a different parameter.
        Map<String, String> params = new HashMap<>();
        params.put("binaryPath", "C:/tmp/Обработка.epf"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "aiedt-tests-new-project"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ConfigurationBinaryImporter().execute(params);
        assertTrue("the answer must point at the tool that reads an .epf, got: " + result, //$NON-NLS-1$
            result.contains("external_object_workshop")); //$NON-NLS-1$
    }

    @Test
    public void anXmlDumpIsSentToTheXmlImport()
    {
        Map<String, String> params = new HashMap<>();
        params.put("binaryPath", "C:/tmp/dump"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "aiedt-tests-new-project"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ConfigurationBinaryImporter().execute(params);
        assertTrue("a directory of XML has its own import - name it, got: " + result, //$NON-NLS-1$
            result.contains("import_configuration_from_xml")); //$NON-NLS-1$
    }

    @Test
    public void aMissingFileIsReportedBeforeAnythingIsCreated()
    {
        Map<String, String> params = new HashMap<>();
        params.put("binaryPath", "C:/aiedt-tests-no-such-file.cf"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", "aiedt-tests-new-project"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ConfigurationBinaryImporter().execute(params);
        assertTrue("the answer must say the file is not there, got: " + result, //$NON-NLS-1$
            result.contains("does not exist")); //$NON-NLS-1$
        assertTrue(result.contains("inputMissing")); //$NON-NLS-1$
    }
}
