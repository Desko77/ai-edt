/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * An interceptor scan reports itself truncated only when there is an interceptor past its cap. A
 * scan that found exactly as many as the cap found them all.
 */
public class AnInterceptorScanIsTruncatedOnlyWhenMoreWereFoundTest
{
    private static final String PROJECT = "AiEdtInterceptorCap"; //$NON-NLS-1$

    private static final String MODULE = "&Перед(\"Записать\")\r\n" //$NON-NLS-1$
        + "Процедура Расш_Записать()\r\n" //$NON-NLS-1$
        + "КонецПроцедуры\r\n" //$NON-NLS-1$
        + "\r\n" //$NON-NLS-1$
        + "&После(\"Записать\")\r\n" //$NON-NLS-1$
        + "Процедура Расш_ПослеЗаписать()\r\n" //$NON-NLS-1$
        + "КонецПроцедуры\r\n" //$NON-NLS-1$
        + "\r\n" //$NON-NLS-1$
        + "&Вместо(\"Удалить\")\r\n" //$NON-NLS-1$
        + "Процедура Расш_Удалить()\r\n" //$NON-NLS-1$
        + "КонецПроцедуры\r\n"; //$NON-NLS-1$

    private static IProject project;

    /**
     * Opens a project with one module holding three interceptors.
     *
     * @throws Exception when the workspace cannot create the project or the module
     */
    @BeforeClass
    public static void aModuleWithThreeInterceptors() throws Exception
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT);
        if (project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        IFile module = project.getFile("Module.bsl"); //$NON-NLS-1$
        module.create(new ByteArrayInputStream(MODULE.getBytes(StandardCharsets.UTF_8)), true,
            new NullProgressMonitor());
    }

    /**
     * Removes the project.
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
    }

    /** As many interceptors as the cap is every interceptor, not a truncated list. */
    @Test
    public void exactlyTheCapIsNotTruncated()
    {
        ListInterceptorsTool.Scan scan = ListInterceptorsTool.scan(project, null, null, 3);
        assertEquals(3, scan.hits.size());
        assertFalse(scan.truncated);
    }

    /** One interceptor past the cap truncates the list, which keeps the cap's worth. */
    @Test
    public void oneMoreThanTheCapIsTruncated()
    {
        ListInterceptorsTool.Scan scan = ListInterceptorsTool.scan(project, null, null, 2);
        assertEquals(2, scan.hits.size());
        assertTrue(scan.truncated);
    }

    /** Fewer interceptors than the cap is not truncated either. */
    @Test
    public void fewerThanTheCapIsNotTruncated()
    {
        ListInterceptorsTool.Scan scan = ListInterceptorsTool.scan(project, null, null, 5);
        assertEquals(3, scan.hits.size());
        assertFalse(scan.truncated);
    }
}
