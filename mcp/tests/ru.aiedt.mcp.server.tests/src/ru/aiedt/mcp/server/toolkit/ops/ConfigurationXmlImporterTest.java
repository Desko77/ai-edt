/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * What the XML import hands to the EDT import API, and what it refuses before calling it.
 * <p>
 * The implementation of {@code IImportConfigurationFilesApi} declares
 * {@code importProject(Path configurationFilesLocation, String projectName, String version,
 * String baseProjectName)} next to an overload taking a project location {@code Path}. The platform
 * version goes third and the base project of an extension fourth.
 * </p>
 */
public class ConfigurationXmlImporterTest
{
    /** Stands in for the EDT service and records what each overload received. */
    public static class RecordingImportApi
    {
        String projectName;
        String version;
        String baseProjectName;
        boolean locationOverloadCalled;

        /**
         * Records the arguments of the overload the importer must call.
         *
         * @param configurationFilesLocation the source folder
         * @param projectName the project to create
         * @param version the platform version
         * @param baseProjectName the base project of an extension
         */
        public void importProject(Path configurationFilesLocation, String projectName, String version,
            String baseProjectName)
        {
            this.projectName = projectName;
            this.version = version;
            this.baseProjectName = baseProjectName;
        }

        /**
         * Marks that the overload taking a project location was called.
         *
         * @param configurationFilesLocation the source folder
         * @param projectLocation the project location
         * @param version the platform version
         * @param baseProjectName the base project of an extension
         */
        public void importProject(Path configurationFilesLocation, Path projectLocation, String version,
            String baseProjectName)
        {
            locationOverloadCalled = true;
        }
    }

    @Test
    public void theVersionGoesThirdAndTheBaseProjectFourth() throws Exception
    {
        RecordingImportApi api = new RecordingImportApi();

        ConfigurationXmlImporter.invokeImport(api, Paths.get("C:/tmp/xml"), "Расширение", "8.3.24", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Конфигурация"); //$NON-NLS-1$

        assertEquals("Расширение", api.projectName); //$NON-NLS-1$
        assertEquals("8.3.24", api.version); //$NON-NLS-1$
        assertEquals("Конфигурация", api.baseProjectName); //$NON-NLS-1$
        assertFalse("the overload taking a project location is not the one to call", //$NON-NLS-1$
            api.locationOverloadCalled);
    }

    @Test
    public void omittedArgumentsReachTheApiAsNull() throws Exception
    {
        RecordingImportApi api = new RecordingImportApi();

        ConfigurationXmlImporter.invokeImport(api, Paths.get("C:/tmp/xml"), "Конфигурация", null, null); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(api.version);
        assertNull(api.baseProjectName);
    }

    @Test
    public void theSchemaOffersTheBaseProjectAndNoNature()
    {
        String schema = new ConfigurationXmlImporter().getInputSchema();
        assertTrue(schema.contains("\"baseProjectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"xmlVersion\"")); //$NON-NLS-1$
        assertFalse("the import API takes no nature", schema.contains("\"projectNature\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aBaseProjectThatIsNotInTheWorkspaceIsRefusedBeforeTheImport() throws Exception
    {
        Path in = Files.createTempDirectory("aiedt-xml-import-"); //$NON-NLS-1$
        try
        {
            Map<String, String> params = new HashMap<>();
            params.put("importPath", in.toString()); //$NON-NLS-1$
            params.put("projectName", "aiedt-tests-new-extension"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("baseProjectName", "aiedt-tests-no-such-base"); //$NON-NLS-1$ //$NON-NLS-2$

            String result = new ConfigurationXmlImporter().execute(params);

            assertTrue(result, result.contains("aiedt-tests-no-such-base")); //$NON-NLS-1$
            assertTrue(result, result.contains("projectNotFound")); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(in);
        }
    }

    @Test
    public void noBaseProjectNeedsNoCheck()
    {
        assertNull(ConfigurationXmlImporter.baseProjectRefusal(null));
        assertNull(ConfigurationXmlImporter.baseProjectRefusal("")); //$NON-NLS-1$
    }
}
