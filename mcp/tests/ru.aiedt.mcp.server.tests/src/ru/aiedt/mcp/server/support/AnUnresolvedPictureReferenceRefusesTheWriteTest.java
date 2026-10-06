/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.PictureRef;
import com._1c.g5.v8.dt.moxel.MoxelFactory;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;

/**
 * A picture reference the project did not resolve refuses the write, and the file stays.
 * <p>
 * The serializer would write such a reference as {@code ref="v8ui:/"}, which the platform refuses
 * to load. An empty placeholder and a reference that resolves are not that refusal. The name is the
 * folder under {@code CommonPictures}, not the {@code .mdo} file.
 * </p>
 */
public class AnUnresolvedPictureReferenceRefusesTheWriteTest
{
    private static final String KEPT = "kept-bytes"; //$NON-NLS-1$

    @Test
    public void aProxyPictureIsNamedByItsCommonPicturesFolder()
    {
        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        doc.getPictures().add(proxy(
            "platform:/resource/P/src/CommonPictures/Stamp/Stamp.mdo#//")); //$NON-NLS-1$

        List<String> names = BmTemplateHelper.unresolvedPictureRefs(doc);

        assertEquals(Arrays.asList("Stamp"), names); //$NON-NLS-1$
    }

    @Test
    public void aCommonPictureMarkerAndABareMdoSegmentAreStripped()
    {
        SpreadsheetDocument marked = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        marked.getPictures().add(proxy("platform:/resource/Project/CommonPicture.Logo.mdo#//")); //$NON-NLS-1$
        assertEquals(Arrays.asList("Logo"), BmTemplateHelper.unresolvedPictureRefs(marked)); //$NON-NLS-1$

        SpreadsheetDocument bare = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        bare.getPictures().add(proxy("platform:/resource/Stamp.mdo#//")); //$NON-NLS-1$
        assertEquals(Arrays.asList("Stamp"), BmTemplateHelper.unresolvedPictureRefs(bare)); //$NON-NLS-1$
    }

    @Test
    public void anEmptyPlaceholderAndAResolvedReferenceAreNotUnresolved()
    {
        SpreadsheetDocument empty = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        empty.getPictures().add(McoreFactory.eINSTANCE.createPictureRef());
        assertTrue(BmTemplateHelper.unresolvedPictureRefs(empty).isEmpty());

        SpreadsheetDocument resolved = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        PictureRef outer = McoreFactory.eINSTANCE.createPictureRef();
        outer.setPicture(McoreFactory.eINSTANCE.createPictureRef());
        resolved.getPictures().add(outer);
        assertTrue(BmTemplateHelper.unresolvedPictureRefs(resolved).isEmpty());

        SpreadsheetDocument none = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        assertTrue(BmTemplateHelper.unresolvedPictureRefs(none).isEmpty());
    }

    @Test
    public void aNullDocumentIsRefused()
    {
        try
        {
            BmTemplateHelper.unresolvedPictureRefs(null);
            fail("a null document is refused"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("doc")); //$NON-NLS-1$
        }
    }

    @Test
    public void unresolvedReferencesRefuseTheWriteAndLeaveTheFile() throws Exception
    {
        Path root = Files.createTempDirectory("aiedt-mxl-picture-refuse"); //$NON-NLS-1$
        Path file = root.resolve("src/Catalogs/Goods/Templates/Print/Template.mxlx"); //$NON-NLS-1$
        Files.createDirectories(file.getParent());
        Files.writeString(file, KEPT, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);

        SpreadsheetDocument doc = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        doc.getPictures().add(proxy(
            "platform:/resource/P/src/CommonPictures/Stamp/Stamp.mdo#//")); //$NON-NLS-1$
        doc.getPictures().add(proxy(
            "platform:/resource/P/src/CommonPictures/Logo/Logo.mdo#//")); //$NON-NLS-1$
        doc.getPictures().add(proxy(
            "platform:/resource/P/src/CommonPictures/Seal/Seal.mdo#//")); //$NON-NLS-1$
        doc.getPictures().add(proxy(
            "platform:/resource/P/src/CommonPictures/Extra/Extra.mdo#//")); //$NON-NLS-1$

        String result = BmTemplateHelper.persistTemplateMxlx(projectAt(root), "Catalog.Goods", //$NON-NLS-1$
            "Print", doc); //$NON-NLS-1$

        assertNotRefusalOfTheFactory(result);
        assertTrue(result, result.contains("4 picture reference")); //$NON-NLS-1$
        assertTrue(result, result.contains("Stamp, Logo, Seal, ...")); //$NON-NLS-1$
        assertFalse(result, result.contains("Extra")); //$NON-NLS-1$
        assertTrue(result, result.contains("Template.mxlx was not changed")); //$NON-NLS-1$
        assertTrue(Arrays.equals(before, Files.readAllBytes(file)));
    }

    @Test
    public void anEmptyPlaceholderDoesNotRefuseTheWrite() throws Exception
    {
        assertWriteIsNotRefusedForPictures(MoxelFactory.eINSTANCE.createSpreadsheetDocument());
        SpreadsheetDocument placeholder = MoxelFactory.eINSTANCE.createSpreadsheetDocument();
        placeholder.getPictures().add(McoreFactory.eINSTANCE.createPictureRef());
        assertWriteIsNotRefusedForPictures(placeholder);
    }

    private static void assertWriteIsNotRefusedForPictures(SpreadsheetDocument doc) throws Exception
    {
        Path root = Files.createTempDirectory("aiedt-mxl-picture-allow"); //$NON-NLS-1$
        Path file = root.resolve("src/Catalogs/Goods/Templates/Print/Template.mxlx"); //$NON-NLS-1$
        Files.createDirectories(file.getParent());
        Files.writeString(file, KEPT, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);

        String result = BmTemplateHelper.persistTemplateMxlx(projectAt(root), "Catalog.Goods", //$NON-NLS-1$
            "Print", doc); //$NON-NLS-1$

        if (result != null)
        {
            assertFalse(result, result.contains("do not resolve")); //$NON-NLS-1$
            assertFalse(result, result.contains("Columns.getColumnsId")); //$NON-NLS-1$
            assertTrue("a refused save leaves the file: " + result, //$NON-NLS-1$
                Arrays.equals(before, Files.readAllBytes(file)));
        }
        else
        {
            assertFalse("a completed save replaced the placeholder bytes", //$NON-NLS-1$
                Arrays.equals(before, Files.readAllBytes(file)));
        }
        assertNull(doc.getColumns() == null ? "columns" : null); //$NON-NLS-1$
        assertTrue(doc.getColumns() == null || doc.getColumns().getSize() >= 0);
        assertNotNullColumns(doc);
    }

    private static void assertNotNullColumns(SpreadsheetDocument doc)
    {
        assertTrue("the save path gives a document with no column set a set", //$NON-NLS-1$
            doc.getColumns() != null);
    }

    private static void assertNotRefusalOfTheFactory(String result)
    {
        assertTrue(result, result != null && !result.contains("No EMF Resource factory")); //$NON-NLS-1$
    }

    private static PictureRef proxy(String uri)
    {
        // The pictures list is a containment and rejects a proxy element. The unresolved
        // common picture is the reference's target, which is not a containment.
        PictureRef holder = McoreFactory.eINSTANCE.createPictureRef();
        PictureRef target = McoreFactory.eINSTANCE.createPictureRef();
        ((InternalEObject) target).eSetProxyURI(URI.createURI(uri));
        holder.setPicture(target);
        return holder;
    }

    private static IProject projectAt(Path root)
    {
        return (IProject) Proxy.newProxyInstance(IProject.class.getClassLoader(),
            new Class<?>[] { IProject.class }, (proxy, method, args) -> {
                if ("getLocation".equals(method.getName())) //$NON-NLS-1$
                {
                    return new org.eclipse.core.runtime.Path(root.toAbsolutePath().toString());
                }
                if ("toString".equals(method.getName())) //$NON-NLS-1$
                {
                    return "picture-project-proxy"; //$NON-NLS-1$
                }
                if ("hashCode".equals(method.getName())) //$NON-NLS-1$
                {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                if ("equals".equals(method.getName())) //$NON-NLS-1$
                {
                    return Boolean.valueOf(proxy == args[0]);
                }
                if (method.getReturnType() == boolean.class)
                {
                    return Boolean.FALSE;
                }
                if (method.getReturnType() == int.class)
                {
                    return Integer.valueOf(0);
                }
                if (method.getReturnType() == void.class)
                {
                    return null;
                }
                return null;
            });
    }
}
