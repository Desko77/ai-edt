/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.support;

import static org.junit.Assert.*;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.EcoreFactory;
import org.junit.Test;

public class ATemplateRefreshFailureIsNotAcknowledgedTest
{
    @Test public void serializationFailureBeforeFileWriteReportsNotAttempted() throws Exception
    {
        Path root = Files.createTempDirectory("aiedt-serialize-failure");
        Map<String, Object> factories = Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap();
        Object previous = factories.put("mxlx", (Resource.Factory) uri -> new ResourceImpl(uri) {
            @Override protected void doSave(OutputStream stream, Map<?, ?> options) throws java.io.IOException
            { throw new java.io.IOException("serializer refused"); }
        });
        IProject project = (IProject) Proxy.newProxyInstance(IProject.class.getClassLoader(),
            new Class<?>[] { IProject.class }, (proxy, method, args) -> {
                if ("getLocation".equals(method.getName()))
                    return new org.eclipse.core.runtime.Path(root.toString());
                if (method.getReturnType() == boolean.class) return false;
                return null;
            });
        try
        {
            BmTemplateHelper.PersistOutcome outcome = BmTemplateHelper.persistTemplateMxlxDetailed(
                project, "Catalog.Goods", "Print", EcoreFactory.eINSTANCE.createEClass());
            assertNotNull(outcome.error);
            assertFalse(outcome.fileWriteAttempted);
            assertFalse(Files.exists(root.resolve("src/Catalogs/Goods/Templates/Print/Template.mxlx")));
        }
        finally { if (previous == null) factories.remove("mxlx"); else factories.put("mxlx", previous); }
    }

    @Test public void fileWriteFailureReportsAttemptedWithoutClaimingRollback() throws Exception
    {
        Path root = Files.createTempDirectory("aiedt-write-failure");
        Files.createDirectories(root.resolve("src/Catalogs/Goods/Templates/Print/Template.mxlx"));
        Map<String, Object> factories = Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap();
        Object previous = factories.put("mxlx", (Resource.Factory) uri -> new ResourceImpl(uri) {
            @Override protected void doSave(OutputStream stream, Map<?, ?> options) throws java.io.IOException
            { stream.write(1); }
        });
        IProject project = (IProject) Proxy.newProxyInstance(IProject.class.getClassLoader(),
            new Class<?>[] { IProject.class }, (proxy, method, args) -> {
                if ("getLocation".equals(method.getName()))
                    return new org.eclipse.core.runtime.Path(root.toString());
                if (method.getReturnType() == boolean.class) return false;
                return null;
            });
        try
        {
            BmTemplateHelper.PersistOutcome outcome = BmTemplateHelper.persistTemplateMxlxDetailed(
                project, "Catalog.Goods", "Print", EcoreFactory.eINSTANCE.createEClass());
            assertNotNull(outcome.error);
            assertTrue(outcome.fileWriteAttempted);
            assertTrue(outcome.error.contains("may be partial"));
            assertNull(outcome.refreshWarning);
        }
        finally { if (previous == null) factories.remove("mxlx"); else factories.put("mxlx", previous); }
    }

    @Test public void refreshFailureAfterWritingBytesCannotReturnSuccess() throws Exception
    {
        Path root = Files.createTempDirectory("aiedt-refresh-failure");
        Map<String, Object> factories = Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap();
        Object previous = factories.put("mxlx", (Resource.Factory) uri -> new ResourceImpl(uri) {
            @Override protected void doSave(OutputStream stream, Map<?, ?> options) throws java.io.IOException
            { stream.write("serialized-template".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        });
        IProject project = (IProject) Proxy.newProxyInstance(IProject.class.getClassLoader(),
            new Class<?>[] { IProject.class }, (proxy, method, args) -> {
                if ("getFolder".equals(method.getName()))
                    return Proxy.newProxyInstance(org.eclipse.core.resources.IFolder.class.getClassLoader(),
                        new Class<?>[] { org.eclipse.core.resources.IFolder.class },
                        (folder, member, values) -> {
                            if ("getFolder".equals(member.getName())) return folder;
                            if (member.getReturnType() == boolean.class) return false;
                            return null;
                        });
                if ("getLocation".equals(method.getName()))
                    return new org.eclipse.core.runtime.Path(root.toString());
                if ("refreshLocal".equals(method.getName()))
                    throw new CoreException(new Status(IStatus.ERROR, "test", "refresh failed"));
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                return null;
            });
        try
        {
            BmTemplateHelper.PersistOutcome outcome = BmTemplateHelper.persistTemplateMxlxDetailed(
                project, "Catalog.Goods", "Print", EcoreFactory.eINSTANCE.createEClass());
            assertNull("refresh warning must not abort the model commit", outcome.error);
            assertNotNull("refresh failure must not disappear", outcome.refreshWarning);
            assertTrue(outcome.refreshWarning.contains("refresh"));
            assertTrue(outcome.fileWriteAttempted);
            assertEquals("serialized-template", Files.readString(root.resolve(
                "src/Catalogs/Goods/Templates/Print/Template.mxlx")));
        }
        finally
        {
            if (previous == null) factories.remove("mxlx"); else factories.put("mxlx", previous);
        }
    }
}
