/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.ApplicableRightsResolver;
import ru.aiedt.mcp.server.support.BmRightsHelper;

/**
 * set_role_right and the RLS writers refuse a role, an object or a right the configuration does
 * not have, and they do that before the rights file is created.
 */
public class RoleOpsRightGuardsTest
{
    private static final String PROJECT = "AiEdtRoleOpsProbe"; //$NON-NLS-1$

    private static Path projectDir;

    private static IProject project;

    private final RoleOps ops = new RoleOps();

    /**
     * Opens a plain workspace project.
     *
     * @throws Exception when the workspace cannot create the project
     */
    @BeforeClass
    public static void aPlainProject() throws Exception
    {
        projectDir = Files.createTempDirectory("aiedt-role-ops"); //$NON-NLS-1$
        project = openProject(PROJECT, projectDir);
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
        deleteTree(projectDir);
    }

    /**
     * A role the model does not have is {@code notFound}, and no rights file appears.
     */
    @Test
    public void anUnknownRoleIsNotFound()
    {
        String json = ops.opSetRoleRight(call("Role.ПолныеПраваИ", "Catalog.Товары", "Read")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();

        assertFalse(body.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(body.get("error").getAsString().contains("role not found")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(body.get("error").getAsString().contains("ПолныеПраваИ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(body.has("notFound")); //$NON-NLS-1$
        assertEqualsKind(body, "role"); //$NON-NLS-1$
        assertFalse(Files.exists(rightsFile("ПолныеПраваИ"))); //$NON-NLS-1$
    }

    /**
     * A missing object is {@code notFound} before any right, including a cascade, is written.
     */
    @Test
    public void aMissingObjectIsNotFoundBeforeTheCascade()
    {
        Map<String, String> params = call("Role.РольОбъект", "Catalog.НетТакого", "Update"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        params.put("cascadeDependencies", "true"); //$NON-NLS-1$ //$NON-NLS-2$

        String json = ops.opSetRoleRight(params, missingObject());
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();

        assertFalse(body.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(body.get("error").getAsString().contains("object not found")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(body.get("error").getAsString().contains("Catalog.НетТакого")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(body.has("notFound")); //$NON-NLS-1$
        assertEqualsKind(body, "object"); //$NON-NLS-1$
        assertFalse(body.has("cascadedRights")); //$NON-NLS-1$
        assertFalse(Files.exists(rightsFile("РольОбъект"))); //$NON-NLS-1$
    }

    /**
     * Posting does not apply to a catalog. The answer lists the rights that do, and the file is
     * not created.
     */
    @Test
    public void postingOnACatalogIsNotApplicable()
    {
        String json = ops.opSetRoleRight(
            call("Role.РольПроведение", "Catalog.Товары", "Posting"), present("Catalog")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();

        assertFalse(body.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(body.get("error").getAsString().contains("Posting")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(body.get("error").getAsString().contains("Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(body.has("notApplicableHere")); //$NON-NLS-1$
        assertTrue(body.has("applicableRights")); //$NON-NLS-1$
        assertTrue(body.get("applicableRights").toString().contains("Read")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(body.get("applicableRights").toString().contains("Posting")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(Files.exists(rightsFile("РольПроведение"))); //$NON-NLS-1$
    }

    /**
     * A right the kind does carry is written.
     *
     * @throws Exception when the written file cannot be read
     */
    @Test
    public void readOnACatalogIsWritten() throws Exception
    {
        String json = ops.opSetRoleRight(
            call("Role.РольЧтение", "Catalog.Товары", "Read"), present("Catalog")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();

        assertTrue(body.has("error") ? body.get("error").getAsString() : "written", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            body.get("success").getAsBoolean()); //$NON-NLS-1$
        String text = Files.readString(rightsFile("РольЧтение"), StandardCharsets.UTF_8); //$NON-NLS-1$
        assertTrue(text.contains("Catalog.Товары")); //$NON-NLS-1$
        assertTrue(text.contains("Read")); //$NON-NLS-1$
    }

    /**
     * With no applicable-rights list the call does not invent a refusal.
     */
    @Test
    public void anUnreadyRegistryDoesNotRefuse()
    {
        String json = ops.opSetRoleRight(
            call("Role.РольРеестр", "Catalog.Товары", "Posting"), present(null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();

        assertTrue(body.has("error") ? body.get("error").getAsString() : "written", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            body.get("success").getAsBoolean()); //$NON-NLS-1$
    }

    /**
     * A template on an unknown role is the same refusal, and it does not create the file.
     */
    @Test
    public void aTemplateOnAnUnknownRoleIsNotFound()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("ownerFqn", "Role.НетТакой"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("templateName", "ByOrg"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("condition", "True"); //$NON-NLS-1$ //$NON-NLS-2$

        String json = ops.opRestrictionTemplate(params, false);
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();

        assertFalse(body.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(body.get("error").getAsString().contains("role not found")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(body.has("notFound")); //$NON-NLS-1$
        assertFalse(Files.exists(rightsFile("НетТакой"))); //$NON-NLS-1$
    }

    /**
     * A restriction on a missing object is refused before the file is created.
     */
    @Test
    public void aRestrictionOnAMissingObjectIsNotFound()
    {
        Map<String, String> params = call("Role.РольОграничение", "Catalog.НетТакого", "Read"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        params.put("condition", "True"); //$NON-NLS-1$ //$NON-NLS-2$

        String json = ops.opRoleRestriction(params, false, missingObject());
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();

        assertFalse(body.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(body.get("error").getAsString().contains("object not found")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(body.has("notFound")); //$NON-NLS-1$
        assertFalse(Files.exists(rightsFile("РольОграничение"))); //$NON-NLS-1$
    }

    /**
     * A role name with a dot is refused and does not become a directory.
     */
    @Test
    public void aDottedRoleNameIsRefused()
    {
        String json = ops.opSetRoleRight(
            call("Роль.ПолныеПрава", "Catalog.Товары", "Read"), BmRightsHelper.RightsGate.allowAll()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();

        assertFalse(body.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(body.get("error").getAsString().contains("simple role name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(Files.exists(projectDir.resolve("src").resolve("Roles").resolve("Роль.ПолныеПрава"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * One set_role_right call.
     *
     * @param role the owner FQN
     * @param object the target FQN
     * @param right the right name
     * @return the parameters
     */
    private static Map<String, String> call(String role, String object, String right)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("ownerFqn", role); //$NON-NLS-1$
        params.put("targetFqn", object); //$NON-NLS-1$
        params.put("rightName", right); //$NON-NLS-1$
        return params;
    }

    /**
     * A gate that says the role is there and the object is not.
     *
     * @return the gate
     */
    private static BmRightsHelper.RightsGate missingObject()
    {
        return new BmRightsHelper.RightsGate()
        {
            /**
             * @param roleName the simple role name
             * @return {@code TRUE}
             */
            @Override
            public Boolean roleExists(String roleName)
            {
                return Boolean.TRUE;
            }

            /**
             * @param targetFqn the object address
             * @return {@code FALSE}
             */
            @Override
            public Boolean objectExists(String targetFqn)
            {
                return Boolean.FALSE;
            }

            /**
             * @param targetFqn the object address
             * @return {@code null}
             */
            @Override
            public Set<String> applicableRights(String targetFqn)
            {
                return null;
            }
        };
    }

    /**
     * A gate that says the role and the object are there. {@code kind} selects the static right
     * list; {@code null} leaves the right unjudged.
     *
     * @param kind the object kind, or {@code null}
     * @return the gate
     */
    private static BmRightsHelper.RightsGate present(String kind)
    {
        Set<String> rights = kind == null ? null : ApplicableRightsResolver.knownRights(kind);
        return new BmRightsHelper.RightsGate()
        {
            /**
             * @param roleName the simple role name
             * @return {@code TRUE}
             */
            @Override
            public Boolean roleExists(String roleName)
            {
                return Boolean.TRUE;
            }

            /**
             * @param targetFqn the object address
             * @return {@code TRUE}
             */
            @Override
            public Boolean objectExists(String targetFqn)
            {
                return Boolean.TRUE;
            }

            /**
             * @param targetFqn the object address
             * @return the static list for the kind, or {@code null}
             */
            @Override
            public Set<String> applicableRights(String targetFqn)
            {
                return rights;
            }
        };
    }

    /**
     * Asserts the notFound tag names {@code kind}.
     *
     * @param body the JSON body
     * @param kind the expected kind
     */
    private static void assertEqualsKind(JsonObject body, String kind)
    {
        assertTrue(body.getAsJsonObject("notFound").get("kind").getAsString().equals(kind)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The rights file of one role.
     *
     * @param roleName the simple role name
     * @return the path
     */
    private static Path rightsFile(String roleName)
    {
        return projectDir.resolve("src").resolve("Roles").resolve(roleName).resolve("Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Creates and opens a project at {@code location}.
     *
     * @param name the project name
     * @param location the directory
     * @return the opened project
     * @throws Exception when the workspace refuses the project
     */
    private static IProject openProject(String name, Path location) throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject opened = workspace.getRoot().getProject(name);
        if (opened.exists())
        {
            opened.delete(true, true, new NullProgressMonitor());
        }
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocation(new org.eclipse.core.runtime.Path(location.toString()));
        opened.create(description, new NullProgressMonitor());
        opened.open(new NullProgressMonitor());
        opened.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        return opened;
    }

    /**
     * Deletes a directory tree.
     *
     * @param root the directory
     * @throws IOException when a walk cannot be opened
     */
    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }
        try (var walk = Files.walk(root))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
