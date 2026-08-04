/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool.ResponseType;

/**
 * Unit tests for {@link ModuleSourceWriter}.
 * <p>
 * Covers the pure, headless surface: tool metadata and schema, the result-file-name derivation, and
 * the parameter-validation pipeline up to the point it needs a live Eclipse workspace. Validations
 * that run <em>before</em> {@code ProjectResolver.resolve} (required params, source length, mode
 * gate, searchReplace oldSource, path traversal, the .bsl extension gate, and the objectName +
 * moduleType path resolver) are asserted by their exact messages. Cases where the pipeline resolves
 * a path successfully are asserted to reach workspace validation, which in the headless test
 * environment reports the project as not found.
 * </p>
 */
public class ModuleSourceWriterTest
{
    private static ModuleSourceWriter freshTool()
    {
        return new ModuleSourceWriter();
    }

    private static Map<String, String> params(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static String run(Map<String, String> params)
    {
        return freshTool().execute(params);
    }

    // ---------- metadata ----------

    @Test
    public void nameIsWriteModuleSource()
    {
        assertEquals("write_module_source", freshTool().getName());
    }

    @Test
    public void responseTypeIsMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, freshTool().getResponseType());
    }

    @Test
    public void descriptionHasContent()
    {
        String description = freshTool().getDescription();
        assertNotNull(description);
        assertFalse(description.isEmpty());
    }

    @Test
    public void schemaAdvertisesEveryParameter()
    {
        String schema = freshTool().getInputSchema();
        assertNotNull(schema);
        for (String key : new String[] {"projectName", "modulePath", "objectName", "moduleType",
            "source", "oldSource", "mode", "formName", "commandName", "skipSyntaxCheck"})
        {
            assertTrue("schema should declare " + key, schema.contains("\"" + key + "\""));
        }
    }

    @Test
    public void schemaDeclaresReplaceLinesAndReplaceMethodParameters()
    {
        String schema = freshTool().getInputSchema();
        assertTrue(schema.contains("\"lineFrom\""));
        assertTrue(schema.contains("\"lineTo\""));
        assertTrue(schema.contains("\"methodName\""));
        assertTrue(schema.contains("\"dryRun\""));
    }

    @Test
    public void schemaMarksProjectNameAsRequired()
    {
        // The current SUT marks only projectName as schema-required; source is enforced at runtime
        // in execute() (it is optional for replaceMethods), so it is NOT in the required array.
        String schema = freshTool().getInputSchema();
        assertTrue(schema.contains("\"required\":[\"projectName\"]"));
    }

    // ---------- result file name ----------

    @Test
    public void resultFileNameIsDerivedFromModulePath()
    {
        Map<String, String> params = params("modulePath", "Documents/MyDoc/ObjectModule.bsl");
        assertEquals("write-documents-mydoc-objectmodule.bsl.md",
            freshTool().getResultFileName(params));
    }

    @Test
    public void resultFileNameFallsBackWithoutModulePath()
    {
        assertEquals("write-module-source.md", freshTool().getResultFileName(new HashMap<>()));
    }

    /**
     * Asserts the call was refused and that the message identifies what was wrong.
     *
     * @param result the tool's answer
     * @param subject the parameter, value or rule the diagnostic has to name; the phrasing around
     *            it is free to change without touching this test
     */
    private static void assertRejects(String result, String subject)
    {
        assertTrue("expected a refusal, got: " + result, result.startsWith("Error:")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("refusal should name " + subject + ", got: " + result, //$NON-NLS-1$ //$NON-NLS-2$
            result.contains(subject));
    }

    // ---------- required parameter pipeline ----------

    @Test
    public void missingProjectNameIsRejected()
    {
        String result = run(params("source", "a = 1;", "modulePath", "Documents/D/ObjectModule.bsl"));
        assertRejects(result, "projectName"); //$NON-NLS-1$
    }

    @Test
    public void blankProjectNameIsRejected()
    {
        String result = run(params("projectName", "", "source", "a = 1;"));
        assertRejects(result, "projectName"); //$NON-NLS-1$
    }

    @Test
    public void missingSourceIsRejected()
    {
        String result = run(params("projectName", "P",
            "modulePath", "Documents/D/ObjectModule.bsl"));
        assertRejects(result, "source"); //$NON-NLS-1$
    }

    @Test
    public void neitherModulePathNorObjectNameIsRejected()
    {
        String result = run(params("projectName", "P", "source", "a = 1;", "oldSource", "old"));
        assertRejects(result, "modulePath"); //$NON-NLS-1$
        assertTrue(result.contains("objectName"));
    }

    // ---------- source length cap ----------

    @Test
    public void sourceAboveHalfAMillionCharsIsRejected()
    {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i <= 500_000; i++)
        {
            huge.append('q');
        }
        String result = run(params("projectName", "P", "modulePath",
            "Documents/D/ObjectModule.bsl", "source", huge.toString()));
        assertRejects(result, "maximum"); //$NON-NLS-1$
    }

    // ---------- mode gate ----------

    @Test
    public void unknownModeIsRejectedWithNameEchoed()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "modulePath", "Documents/D/ObjectModule.bsl", "mode", "obliterate"));
        assertRejects(result, "mode"); //$NON-NLS-1$
        assertTrue("echoes the value that was refused", result.contains("obliterate")); //$NON-NLS-1$
    }

    @Test
    public void replaceLinesPassesModeGate()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "modulePath", "Documents/D/ObjectModule.bsl", "mode", "replaceLines"));
        assertFalse(result.contains("Invalid mode")); //$NON-NLS-1$
    }

    @Test
    public void replaceMethodPassesModeGate()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "modulePath", "Documents/D/ObjectModule.bsl", "mode", "replaceMethod"));
        assertFalse(result.contains("Invalid mode")); //$NON-NLS-1$
    }

    @Test
    public void replaceLinesWithoutRangeReachesWorkspaceOrRangeCheck()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "modulePath", "Documents/D/ObjectModule.bsl", "mode", "replaceLines"));
        assertTrue(result.contains("Project not found") || result.contains("invalid line range"));
    }

    @Test
    public void replaceMethodWithoutNameReachesWorkspaceOrNameCheck()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "modulePath", "Documents/D/ObjectModule.bsl", "mode", "replaceMethod"));
        assertTrue(result.contains("Project not found") || result.contains("methodName is required"));
    }

    // ---------- searchReplace oldSource gate ----------

    @Test
    public void searchReplaceDemandsOldSource()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "modulePath", "Documents/D/ObjectModule.bsl", "mode", "searchReplace"));
        assertRejects(result, "oldSource"); //$NON-NLS-1$
    }

    @Test
    public void searchReplaceRejectsBlankOldSource()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "modulePath", "Documents/D/ObjectModule.bsl", "mode", "searchReplace",
            "oldSource", ""));
        assertRejects(result, "oldSource"); //$NON-NLS-1$
    }

    @Test
    public void omittedModeDefaultsToSearchReplaceAndDemandsOldSource()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "modulePath", "Documents/D/ObjectModule.bsl"));
        assertRejects(result, "oldSource"); //$NON-NLS-1$
    }

    // ---------- path-traversal and extension gates ----------

    @Test
    public void modulePathWithParentTraversalIsRejected()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "modulePath", "../../etc/passwd.bsl", "mode", "replace"));
        assertRejects(result, "'..'"); //$NON-NLS-1$
    }

    @Test
    public void nonBslModulePathIsRejected()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "modulePath", "Configuration/Configuration.mdo", "mode", "replace"));
        assertRejects(result, ".bsl"); //$NON-NLS-1$
    }

    // ---------- resolveModulePath via objectName ----------

    @Test
    public void objectNameWithoutDotIsRejected()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "objectName", "NoDotHere", "mode", "replace"));
        assertRejects(result, "'Type.Name'"); //$NON-NLS-1$
    }

    @Test
    public void objectNameWithUnknownTypeIsRejected()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "objectName", "Martian.Foo", "mode", "replace"));
        assertRejects(result, "Martian"); //$NON-NLS-1$
    }

    @Test
    public void unknownModuleTypeIsRejected()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "objectName", "Document.D", "moduleType", "AlienModule", "mode", "replace"));
        assertRejects(result, "AlienModule"); //$NON-NLS-1$
    }

    @Test
    public void formModuleWithoutFormNameIsRejected()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "objectName", "Document.D", "moduleType", "FormModule", "mode", "replace"));
        assertRejects(result, "formName"); //$NON-NLS-1$
    }

    @Test
    public void commonFormDoesNotNeedAFormName()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "objectName", "CommonForm.F", "moduleType", "FormModule", "mode", "replace"));
        assertFalse(result.contains("formName is required"));
        assertTrue(result.contains("Project not found"));
    }

    @Test
    public void commandModuleWithoutCommandNameIsRejected()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "objectName", "Document.D", "moduleType", "CommandModule", "mode", "replace"));
        assertRejects(result, "commandName"); //$NON-NLS-1$
    }

    @Test
    public void commonCommandDoesNotNeedACommandName()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "objectName", "CommonCommand.C", "moduleType", "CommandModule", "mode", "replace"));
        assertFalse(result.contains("commandName is required"));
        assertTrue(result.contains("Project not found"));
    }

    // ---------- paths that resolve and reach workspace validation ----------

    @Test
    public void documentObjectModuleReachesWorkspace()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "objectName", "Document.D", "mode", "replace"));
        assertTrue(result.contains("Project not found"));
    }

    @Test
    public void commonModuleReachesWorkspace()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "objectName", "CommonModule.M", "mode", "replace"));
        assertTrue(result.contains("Project not found"));
    }

    @Test
    public void russianObjectNameReachesWorkspace()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "objectName", "Документ.МойДок",
            "mode", "replace"));
        assertTrue(result.contains("Project not found"));
    }

    @Test
    public void managerModuleReachesWorkspace()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "objectName", "Catalog.Goods", "moduleType", "ManagerModule", "mode", "replace"));
        assertTrue(result.contains("Project not found"));
    }

    @Test
    public void recordSetModuleReachesWorkspace()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "objectName", "InformationRegister.Rates", "moduleType", "RecordSetModule",
            "mode", "replace"));
        assertTrue(result.contains("Project not found"));
    }

    @Test
    public void formModuleWithFormNameReachesWorkspace()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "objectName", "Document.D", "moduleType", "FormModule", "formName", "ItemForm",
            "mode", "replace"));
        assertTrue(result.contains("Project not found"));
    }

    @Test
    public void commandModuleWithCommandNameReachesWorkspace()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "objectName", "Document.D", "moduleType", "CommandModule", "commandName", "Fill",
            "mode", "replace"));
        assertTrue(result.contains("Project not found"));
    }

    @Test
    public void directModulePathReachesWorkspace()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "modulePath", "Documents/D/ObjectModule.bsl", "mode", "replace"));
        assertTrue(result.contains("Project not found"));
    }

    @Test
    public void replaceAppendAndSearchReplaceAllReachWorkspace()
    {
        ModuleSourceWriter tool = freshTool();
        assertTrue(tool.execute(params("projectName", "P", "source", "a = 1;",
            "modulePath", "Documents/D/ObjectModule.bsl", "mode", "replace")).contains("Project not found"));
        assertTrue(tool.execute(params("projectName", "P", "source", "a = 1;",
            "modulePath", "Documents/D/ObjectModule.bsl", "mode", "append")).contains("Project not found"));
        assertTrue(tool.execute(params("projectName", "P", "source", "a = 1;", "oldSource", "old",
            "modulePath", "Documents/D/ObjectModule.bsl", "mode", "searchReplace")).contains(
            "Project not found"));
    }

    @Test
    public void replaceModeDoesNotDemandOldSource()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "modulePath", "Documents/D/ObjectModule.bsl", "mode", "replace"));
        assertFalse(result.contains("oldSource is required"));
        assertTrue(result.contains("Project not found"));
    }

    @Test
    public void appendModeDoesNotDemandOldSource()
    {
        String result = run(params("projectName", "P", "source", "a = 1;",
            "modulePath", "Documents/D/ObjectModule.bsl", "mode", "append"));
        assertFalse(result.contains("oldSource is required"));
        assertTrue(result.contains("Project not found"));
    }
}
