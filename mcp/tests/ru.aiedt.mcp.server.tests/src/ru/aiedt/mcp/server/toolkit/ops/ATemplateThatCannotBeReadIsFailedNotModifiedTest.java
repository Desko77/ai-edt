/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A template that cannot be read is named as failed, not reported as modified.
 * <p>
 * The template diff compared both sides as byte streams, and a read failure on either side came
 * back as "the bytes differ" - so a template nobody had compared landed in the modified list, and
 * an agent took a change into account that was never read.
 * </p>
 */
public class ATemplateThatCannotBeReadIsFailedNotModifiedTest
{
    private static final String FIRST = "AiEdtCompareTemplateFirst"; //$NON-NLS-1$

    private static final String SECOND = "AiEdtCompareTemplateSecond"; //$NON-NLS-1$

    private static final String UNREAD = "Catalogs/Goods/Templates/Unread.mxl"; //$NON-NLS-1$

    private static final String SAME = "Catalogs/Goods/Templates/Same.mxl"; //$NON-NLS-1$

    private static final String CHANGED = "Catalogs/Goods/Templates/Changed.mxl"; //$NON-NLS-1$

    private static Path firstRoot;

    private static Path secondRoot;

    private static IProject first;

    private static IProject second;

    @BeforeClass
    public static void twoProjectsWhoseTemplatesDiffer() throws Exception
    {
        firstRoot = Files.createTempDirectory("aiedt-compare-template-a"); //$NON-NLS-1$
        secondRoot = Files.createTempDirectory("aiedt-compare-template-b"); //$NON-NLS-1$
        write(firstRoot, SAME, "same"); //$NON-NLS-1$
        write(secondRoot, SAME, "same"); //$NON-NLS-1$
        write(firstRoot, CHANGED, "one"); //$NON-NLS-1$
        write(secondRoot, CHANGED, "two"); //$NON-NLS-1$
        write(firstRoot, UNREAD, "left"); //$NON-NLS-1$
        write(secondRoot, UNREAD, "right"); //$NON-NLS-1$
        first = openProject(FIRST, firstRoot);
        second = openProject(SECOND, secondRoot);
        // The workspace lists the template; the disk does not have it any more.
        Files.delete(secondRoot.resolve("src").resolve(UNREAD)); //$NON-NLS-1$
    }

    @AfterClass
    public static void theProjectsGo() throws Exception
    {
        deleteProject(first);
        deleteProject(second);
        deleteTree(firstRoot);
        deleteTree(secondRoot);
    }

    private static void write(Path root, String srcRelative, String content) throws Exception
    {
        Path target = root.resolve("src").resolve(srcRelative); //$NON-NLS-1$
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private static IProject openProject(String name, Path location) throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject opened = workspace.getRoot().getProject(name);
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocation(new org.eclipse.core.runtime.Path(location.toString()));
        opened.create(description, new NullProgressMonitor());
        opened.open(new NullProgressMonitor());
        opened.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        return opened;
    }

    private static void deleteProject(IProject project) throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
    }

    private static void deleteTree(Path root) throws Exception
    {
        if (root == null)
        {
            return;
        }
        try (Stream<Path> entries = Files.walk(root))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** The unread template is named in the failed list, not in the modified list. */
    @Test
    public void anUnreadTemplateIsFailedNotModified()
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "projects"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", FIRST); //$NON-NLS-1$
        params.put("target", SECOND); //$NON-NLS-1$
        params.put("level", "template"); //$NON-NLS-1$
        String answer = new CompareConfigurationsTool().execute(params);

        assertTrue(answer, answer.contains("\"failedCount\":1")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("src/" + UNREAD)); //$NON-NLS-1$
        assertFalse("the failed key is not also in the modified list", //$NON-NLS-1$
            modifiedListsName(com.google.gson.JsonParser.parseString(answer), "src/" + UNREAD)); //$NON-NLS-1$
    }

    /**
     * Whether any {@code modified} list in the answer names the key. The failed entry carries the
     * read error, whose text holds the file path, so the key is looked for in the lists only.
     *
     * @param node the answer, or a part of it
     * @param key the key looked for
     * @return whether a modified list names it
     */
    private static boolean modifiedListsName(com.google.gson.JsonElement node, String key)
    {
        if (node.isJsonObject())
        {
            for (Map.Entry<String, com.google.gson.JsonElement> entry : node.getAsJsonObject().entrySet())
            {
                if ("modified".equals(entry.getKey()) && entry.getValue().toString().contains(key)) //$NON-NLS-1$
                {
                    return true;
                }
                if (modifiedListsName(entry.getValue(), key))
                {
                    return true;
                }
            }
        }
        else if (node.isJsonArray())
        {
            for (com.google.gson.JsonElement item : node.getAsJsonArray())
            {
                if (modifiedListsName(item, key))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /** The templates that could be read keep their answers: same and changed. */
    @Test
    public void readableTemplatesKeepTheirAnswers()
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "projects"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("projectName", FIRST); //$NON-NLS-1$
        params.put("target", SECOND); //$NON-NLS-1$
        params.put("level", "template"); //$NON-NLS-1$
        String answer = new CompareConfigurationsTool().execute(params);

        assertTrue(answer, answer.contains("src/" + CHANGED)); //$NON-NLS-1$
        assertFalse(answer, answer.contains("src/" + SAME)); //$NON-NLS-1$
    }
}
