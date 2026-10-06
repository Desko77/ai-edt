/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.moxel.Columns;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;

/**
 * An empty model over a real template file is a mismatch, and the empty spreadsheet skeleton is not.
 * <p>
 * A write that serialized the unloaded model would replace the file on disk. The skeleton
 * {@code create_template} writes, and the same bytes with {@code indexTo} on the first row, are the
 * file a new template already has, so the first {@code set_cell} is not refused for that reason.
 * A document with no column set receives a set of size 0 before it is saved.
 * </p>
 */
public class AnEmptyTemplateSkeletonIsNotAModelMismatchTest
{
    private static final String REAL = "<document>not-a-skeleton</document>\n"; //$NON-NLS-1$

    @Test
    public void aRealFileUnderAnEmptyModelIsAMismatch() throws Exception
    {
        Path file = fileWith(REAL);
        SpreadsheetDocument empty = MoxelFactory.eINSTANCE.createSpreadsheetDocument();

        String mismatch = BmTemplateHelper.modelFileMismatch(empty, file);

        assertNotNull(mismatch);
        assertTrue(mismatch, mismatch.contains("Template.mxlx is not empty")); //$NON-NLS-1$
        assertEquals(REAL, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    public void aNullDocumentReadsAsAnEmptyModel() throws Exception
    {
        assertNotNull(BmTemplateHelper.modelFileMismatch(null, fileWith(REAL)));
    }

    @Test
    public void aDocumentThatHoldsARowIsNotAMismatch() throws Exception
    {
        Path file = fileWith(REAL);
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        BmTemplateHelper.setCellText(doc, 1, 1, "x", "en"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(BmTemplateHelper.modelFileMismatch(doc, file));
        assertEquals(REAL, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    public void aDocumentThatHoldsAColumnSetIsNotAMismatch() throws Exception
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        Columns columns = MoxelFactory.eINSTANCE.createColumns();
        columns.setSize(0);
        doc.setColumns(columns);

        assertNull(BmTemplateHelper.modelFileMismatch(doc, fileWith(REAL)));
    }

    @Test
    public void aMissingOrEmptyFileIsNotAMismatch() throws Exception
    {
        SpreadsheetDocument empty = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        Path dir = Files.createTempDirectory("aiedt-mxl-missing"); //$NON-NLS-1$
        assertNull(BmTemplateHelper.modelFileMismatch(empty, dir.resolve("Template.mxlx"))); //$NON-NLS-1$
        Path emptyFile = dir.resolve("empty.mxlx"); //$NON-NLS-1$
        Files.write(emptyFile, new byte[0]);
        assertNull(BmTemplateHelper.modelFileMismatch(empty, emptyFile));
        assertNull(BmTemplateHelper.modelFileMismatch(empty, null));
    }

    @Test
    public void theEmptySkeletonAndItsIndexToVariantAreNotAMismatch() throws Exception
    {
        String skeleton = BmTemplateHelper.emptySpreadsheetSkeleton();
        assertTrue(BmTemplateHelper.isEmptySpreadsheetSkeleton(skeleton));
        assertTrue(BmTemplateHelper.isEmptySpreadsheetSkeleton(skeleton.replace("\n", "\r\n"))); //$NON-NLS-1$ //$NON-NLS-2$
        String withIndexTo = skeleton.replace(
            "\t\t<index>0</index>\n", //$NON-NLS-1$
            "\t\t<index>0</index>\n\t\t<indexTo>1</indexTo>\n"); //$NON-NLS-1$
        assertTrue(BmTemplateHelper.isEmptySpreadsheetSkeleton(withIndexTo));
        assertFalse(BmTemplateHelper.isEmptySpreadsheetSkeleton(null));
        assertFalse(BmTemplateHelper.isEmptySpreadsheetSkeleton(" " + skeleton)); //$NON-NLS-1$
        assertFalse(BmTemplateHelper.isEmptySpreadsheetSkeleton(skeleton + "\n<extra/>")); //$NON-NLS-1$

        SpreadsheetDocument empty = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        assertNull(BmTemplateHelper.modelFileMismatch(empty, fileWith(skeleton)));
        assertNull(BmTemplateHelper.modelFileMismatch(empty, fileWith(skeleton.replace("\n", "\r\n")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(BmTemplateHelper.modelFileMismatch(empty, fileWith(withIndexTo)));
        assertNotNull(BmTemplateHelper.modelFileMismatch(empty, fileWith(skeleton + "\n<extra/>"))); //$NON-NLS-1$
        assertNotNull(BmTemplateHelper.modelFileMismatch(empty, fileWith(" " + skeleton))); //$NON-NLS-1$
    }

    @Test
    public void aDocumentWithNoColumnSetReceivesASetOfSizeZero()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        assertNull(doc.getColumns());

        BmTemplateHelper.ensureColumnSet(doc);

        assertNotNull(doc.getColumns());
        assertEquals(0, doc.getColumns().getSize());
        Columns kept = doc.getColumns();
        kept.setSize(4);
        BmTemplateHelper.ensureColumnSet(doc);
        assertSame(kept, doc.getColumns());
        assertEquals(4, doc.getColumns().getSize());
    }

    @Test
    public void aNullDocumentIsRefusedByTheColumnSet()
    {
        try
        {
            BmTemplateHelper.ensureColumnSet(null);
            fail("a null document is refused"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("doc")); //$NON-NLS-1$
        }
    }

    @Test
    public void anEmptyCommonTemplateFileIsTheSkeletonAndNotAMismatch() throws Exception
    {
        Path root = Files.createTempDirectory("aiedt-mxl-common-skeleton"); //$NON-NLS-1$
        IProject project = projectAt(root);

        String written = BmTemplateHelper.writeEmptyMxlxFile(project, "CommonTemplate.Print", //$NON-NLS-1$
            "Print", "SpreadsheetDocument"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(written);
        Path file = root.resolve("src/CommonTemplates/Print/Template.mxlx"); //$NON-NLS-1$
        String bytes = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(BmTemplateHelper.emptySpreadsheetSkeleton(), bytes);
        SpreadsheetDocument empty = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        assertNull(BmTemplateHelper.modelFileMismatch(empty, file));

        byte[] before = Files.readAllBytes(file);
        assertNull(BmTemplateHelper.writeEmptyMxlxFile(project, "CommonTemplate.Print", //$NON-NLS-1$
            "Print", "SpreadsheetDocument")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a second write leaves the file", java.util.Arrays.equals(before, //$NON-NLS-1$
            Files.readAllBytes(file)));
    }

    private static Path fileWith(String content) throws Exception
    {
        Path dir = Files.createTempDirectory("aiedt-mxl-mismatch"); //$NON-NLS-1$
        Path file = dir.resolve("Template.mxlx"); //$NON-NLS-1$
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static IProject projectAt(Path root)
    {
        InvocationHandler folderHandler = (proxy, method, args) -> {
            Object objectAnswer = objectAnswer(proxy, method, args);
            if (objectAnswer != NOT_OBJECT)
            {
                return objectAnswer;
            }
            switch (method.getName())
            {
                case "getFolder": //$NON-NLS-1$
                    return proxy;
                case "exists": //$NON-NLS-1$
                    return Boolean.FALSE;
                case "refreshLocal": //$NON-NLS-1$
                    return null;
                default:
                    return fallback(method);
            }
        };
        ClassLoader loader = IFolder.class.getClassLoader();
        IFolder folder = (IFolder) Proxy.newProxyInstance(loader, new Class<?>[] { IFolder.class },
            folderHandler);
        InvocationHandler projectHandler = (proxy, method, args) -> {
            Object objectAnswer = objectAnswer(proxy, method, args);
            if (objectAnswer != NOT_OBJECT)
            {
                return objectAnswer;
            }
            switch (method.getName())
            {
                case "getLocation": //$NON-NLS-1$
                    return new org.eclipse.core.runtime.Path(root.toAbsolutePath().toString());
                case "getFolder": //$NON-NLS-1$
                    return folder;
                case "refreshLocal": //$NON-NLS-1$
                    return null;
                default:
                    return fallback(method);
            }
        };
        return (IProject) Proxy.newProxyInstance(loader, new Class<?>[] { IProject.class },
            projectHandler);
    }

    private static final Object NOT_OBJECT = new Object();

    private static Object objectAnswer(Object proxy, Method method, Object[] args)
    {
        if (method.getDeclaringClass() != Object.class)
        {
            return NOT_OBJECT;
        }
        if ("toString".equals(method.getName())) //$NON-NLS-1$
        {
            return "template-project-proxy"; //$NON-NLS-1$
        }
        if ("hashCode".equals(method.getName())) //$NON-NLS-1$
        {
            return Integer.valueOf(System.identityHashCode(proxy));
        }
        if ("equals".equals(method.getName())) //$NON-NLS-1$
        {
            return Boolean.valueOf(proxy == args[0]);
        }
        return NOT_OBJECT;
    }

    private static Object fallback(Method method)
    {
        Class<?> type = method.getReturnType();
        if (type == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == int.class)
        {
            return Integer.valueOf(0);
        }
        if (type == void.class)
        {
            return null;
        }
        throw new UnsupportedOperationException(method.getName());
    }
}
