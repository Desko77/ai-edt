/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Role;

import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * The role audit and the RLS scan refuse a role the configuration does not have, name the rights
 * files they could not read instead of answering over them, and describe only the checks they run.
 */
public class ASecurityAuditNamesWhatItCouldNotCheckTest
{
    private static final String PROJECT = "AiEdtSecurityAuditProbe"; //$NON-NLS-1$

    private static Path projectDir;

    private static IProject project;

    /**
     * Opens a plain project the rights files are read from.
     *
     * @throws Exception when the workspace cannot create the project
     */
    @BeforeClass
    public static void aProject() throws Exception
    {
        projectDir = Files.createTempDirectory("aiedt-security-audit"); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        if (project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

    /**
     * Removes the project and the directory under it.
     *
     * @throws Exception when the workspace cannot delete the project
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        if (projectDir != null && Files.exists(projectDir))
        {
            try (var walk = Files.walk(projectDir))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    /**
     * mode=impact with a name that is not a role refuses, naming it, instead of counting the
     * remaining roles as the whole list.
     */
    @Test
    public void anImpactWithAnUnknownRoleIsRefusedNamingIt()
    {
        Configuration config = configuration("Clerk", "Keeper"); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new AuditRoleRightsTool().runImpact(null, config, List.of(),
            Map.of("roleNames", "Clerk, NoSuchRole, Keepr"), "json"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(answer, answer.contains("\"roleNotFound\"")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("NoSuchRole, Keepr")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("\"exclusive\"")); //$NON-NLS-1$
    }

    /**
     * A markdown request in a mode that answers JSON says so in the answer.
     */
    @Test
    public void aMarkdownRequestOutsideRightsModeSaysTheAnswerIsJson()
    {
        String asked = AuditRoleRightsTool.withFormatNote(ToolResult.success(), "markdown").toJson(); //$NON-NLS-1$
        assertTrue(asked, asked.contains("\"formatNote\"")); //$NON-NLS-1$

        String plain = AuditRoleRightsTool.withFormatNote(ToolResult.success(), "json").toJson(); //$NON-NLS-1$
        assertFalse(plain, plain.contains("formatNote")); //$NON-NLS-1$
    }

    /**
     * A roleName the configuration does not have refuses the RLS scan with the roles it does have,
     * rather than reading as "no rights file could be read".
     */
    @Test
    public void anUnknownRoleForTheRlsFlagIsRefused()
    {
        FindRlsViolationsTool.RlsVerdict verdict = FindRlsViolationsTool.rlsOf(project,
            configuration("Clerk"), "NoSuchRole"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(verdict.refusal);
        assertTrue(verdict.refusal, verdict.refusal.contains("NoSuchRole")); //$NON-NLS-1$
        assertTrue(verdict.refusal, verdict.refusal.contains("Clerk")); //$NON-NLS-1$
    }

    /**
     * A role whose rights file cannot be parsed keeps "no role restricts rows" from being answered,
     * and is named.
     *
     * @throws IOException when a rights file cannot be written
     */
    @Test
    public void anUnreadRightsFileLeavesTheRlsFlagUndetermined() throws IOException
    {
        writeRights("Broken", "<Rights><object><name>Catalog.Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        writeRights("Plain", rights("<right><name>Read</name><value>true</value></right>")); //$NON-NLS-1$ //$NON-NLS-2$

        FindRlsViolationsTool.RlsVerdict verdict = FindRlsViolationsTool.rlsOf(project,
            configuration("Broken", "Plain"), null); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(verdict.refusal);
        assertNull(verdict.noRlsConfigured);
        assertEquals(List.of("Broken"), verdict.unreadRoles); //$NON-NLS-1$
        ToolResult answer = ToolResult.success();
        verdict.putInto(answer);
        String json = answer.toJson();
        assertTrue(json, json.contains("\"rlsNotDetermined\":true")); //$NON-NLS-1$
        assertTrue(json, json.contains("\"rightsNotRead\":[\"Broken\"]")); //$NON-NLS-1$
        assertFalse(json, json.contains("noRlsConfigured")); //$NON-NLS-1$
    }

    /**
     * A restriction in a file that was read answers "some role restricts rows" even while another
     * role's file could not be read.
     *
     * @throws IOException when a rights file cannot be written
     */
    @Test
    public void aRestrictionThatWasReadIsAnsweredOverAnUnreadFile() throws IOException
    {
        writeRights("Broken", "<Rights><object><name>Catalog.Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        writeRights("Guarded", rights("<right><name>Read</name><value>true</value>" //$NON-NLS-1$ //$NON-NLS-2$
            + "<restrictionByCondition><condition>WHERE TRUE</condition></restrictionByCondition></right>")); //$NON-NLS-1$

        FindRlsViolationsTool.RlsVerdict verdict = FindRlsViolationsTool.rlsOf(project,
            configuration("Broken", "Guarded"), null); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Boolean.FALSE, verdict.noRlsConfigured);
        ToolResult answer = ToolResult.success();
        verdict.putInto(answer);
        String json = answer.toJson();
        assertFalse(json, json.contains("noRlsConfigured")); //$NON-NLS-1$
        assertFalse(json, json.contains("rlsNotDetermined")); //$NON-NLS-1$
    }

    /**
     * The description of find_rls_violations and the facade's help name no check the scan does not
     * run.
     */
    @Test
    public void theRlsScanPromisesOnlyWhatItDetects()
    {
        String description = new FindRlsViolationsTool().getDescription();
        String help = new SecurityAuditFacadeTool().execute(Map.of("operation", "help")); //$NON-NLS-1$ //$NON-NLS-2$
        String workflow = new SecurityAuditFacadeTool()
            .execute(Map.of("operation", "help", "topic", "workflow")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (String text : List.of(description, help, workflow))
        {
            assertFalse(text, text.contains("WHERE")); //$NON-NLS-1$
            assertFalse(text, text.contains("undefined field")); //$NON-NLS-1$
            assertFalse(text, text.contains("always false")); //$NON-NLS-1$
            assertFalse(text, text.contains("broken or missing")); //$NON-NLS-1$
        }
        assertTrue(description, description.contains("PRIVILEGED_MODE")); //$NON-NLS-1$
    }

    /**
     * A configuration with one catalog and the named roles.
     *
     * @param roleNames the roles
     * @return the configuration
     */
    private static Configuration configuration(String... roleNames)
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        configuration.setName("Cfg"); //$NON-NLS-1$
        Catalog goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Goods"); //$NON-NLS-1$
        configuration.getCatalogs().add(goods);
        for (String name : roleNames)
        {
            Role role = MdClassFactory.eINSTANCE.createRole();
            role.setName(name);
            configuration.getRoles().add(role);
        }
        return configuration;
    }

    /**
     * A rights document with one object, {@code Catalog.Goods}, carrying the given right elements.
     *
     * @param rightsXml the {@code <right>} elements
     * @return the document text
     */
    private static String rights(String rightsXml)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" //$NON-NLS-1$
            + "<Rights xmlns=\"http://v8.1c.ru/8.2/roles\"><object><name>Catalog.Goods</name>" //$NON-NLS-1$
            + rightsXml + "</object></Rights>"; //$NON-NLS-1$
    }

    /**
     * Writes {@code src/Roles/<role>/Rights.rights}.
     *
     * @param role the role directory
     * @param text the file text
     * @throws IOException when the file cannot be written
     */
    private static void writeRights(String role, String text) throws IOException
    {
        Path dir = projectDir.resolve("src").resolve("Roles").resolve(role); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Rights.rights"), text, StandardCharsets.UTF_8); //$NON-NLS-1$
    }
}
