/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;

import ru.aiedt.mcp.server.Activator;

/**
 * DCS recovery: read .dcs from disk and replace BM in-memory schema.
 * Used by {@code dcs_workshop repair_schema} for projects whose
 * {@code .dcs} files exist on disk but were not imported into BM
 * (the EDT 2025.2 silent-drop bug after the first save in extensions).
 */
public final class DcsExtensionImportHelper
{
    private static final String SVC_RUNTIME_VERSION_SUPPORT =
        "com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport"; //$NON-NLS-1$
    private static final String SVC_RESOURCE_LOOKUP =
        "com._1c.g5.v8.dt.core.platform.IResourceLookup"; //$NON-NLS-1$
    private static final String CLS_DCS_V8_SERIALIZER =
        "com._1c.g5.v8.dt.dcs.util.DcsV8Serializer"; //$NON-NLS-1$
    private static final String CLS_VERSION =
        "com._1c.g5.v8.dt.platform.version.Version"; //$NON-NLS-1$
    private static final String CLS_DT_PROJECT =
        "com._1c.g5.v8.dt.core.platform.IDtProject"; //$NON-NLS-1$

    private DcsExtensionImportHelper()
    {
    }

    public static final class Result
    {
        public boolean ok;
        public boolean alreadyValid;
        public String error;
        public String filePath;
        public int readBytes;
        public long readMs;
    }

    /**
     * Reads {@code .dcs} from disk, deserializes via {@link CLS_DCS_V8_SERIALIZER}
     * and stores the resulting EObject as the schema content for the given FQN.
     * Idempotent: returns {@code alreadyValid=true} when BM already has a
     * non-empty schema with bytes matching the on-disk content.
     */
    public static Result importSchemaFromDisk(IBmModelManager manager, IProject project,
        IFile dcsFile, String schemaFqn)
    {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        if (manager == null)
        {
            r.error = "IBmModelManager is null"; //$NON-NLS-1$
            return r;
        }
        if (dcsFile == null || !dcsFile.exists())
        {
            r.error = ".dcs file not found on disk"; //$NON-NLS-1$
            return r;
        }
        r.filePath = dcsFile.getFullPath().toOSString();

        BundleContext bc = org.osgi.framework.FrameworkUtil.getBundle(DcsExtensionImportHelper.class)
            .getBundleContext();
        if (bc == null)
        {
            r.error = "BundleContext is null"; //$NON-NLS-1$
            return r;
        }

        ServiceReference rvsRef = bc.getServiceReference(SVC_RUNTIME_VERSION_SUPPORT);
        ServiceReference lookupRef = bc.getServiceReference(SVC_RESOURCE_LOOKUP);
        if (rvsRef == null || lookupRef == null)
        {
            r.error = "Required OSGi services missing for DCS deserialization"; //$NON-NLS-1$
            return r;
        }
        Object rvs = bc.getService(rvsRef);
        Object lookup = bc.getService(lookupRef);
        try
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            try (InputStream in = dcsFile.getContents())
            {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) > 0)
                {
                    baos.write(buf, 0, read);
                }
            }
            byte[] diskBytes = baos.toByteArray();
            r.readBytes = diskBytes.length;

            // Resolve dtProject + version + serializer
            Method getDt = manager.getClass().getMethod("getDtProject", String.class); //$NON-NLS-1$
            Object dtProject = getDt.invoke(manager, project.getName());
            if (dtProject == null)
            {
                r.error = "IDtProject not resolved"; //$NON-NLS-1$
                return r;
            }
            Object version = resolveVersion(rvs, project);
            if (version == null)
            {
                r.error = "Cannot resolve runtime Version"; //$NON-NLS-1$
                return r;
            }
            ClassLoader cl = DcsExtensionImportHelper.class.getClassLoader();
            Class<?> serializerClass = cl.loadClass(CLS_DCS_V8_SERIALIZER);
            Class<?> dtProjectIface = cl.loadClass(CLS_DT_PROJECT);
            Class<?> versionClass = cl.loadClass(CLS_VERSION);
            Class<?> lookupClass = cl.loadClass(SVC_RESOURCE_LOOKUP);
            Constructor<?> ctor = serializerClass.getConstructor(dtProjectIface, versionClass,
                lookupClass);
            Object serializer = ctor.newInstance(dtProject, version, lookup);

            // Try deserializeXML(InputStream, IDtProject) — exact signature varies.
            Method deserialize = findDeserializeMethod(serializerClass, dtProjectIface);
            if (deserialize == null)
            {
                r.error = "DcsV8Serializer.deserializeXML not found"; //$NON-NLS-1$
                return r;
            }
            try (InputStream in = new java.io.ByteArrayInputStream(diskBytes))
            {
                Object schema = deserialize.invoke(serializer, in, dtProject);
                if (schema == null)
                {
                    r.error = "Deserialized schema is null"; //$NON-NLS-1$
                    return r;
                }
                // Idempotency check: serialize current BM schema (via export helper) and compare bytes
                DcsExtensionExportHelper.Result current =
                    DcsExtensionExportHelper.exportSchemaToDisk(manager, project, schemaFqn);
                if (current != null && current.ok && current.bytesWritten == diskBytes.length)
                {
                    // Practical heuristic: if current export equals on-disk size and the
                    // export already wrote disk, BM is in sync — no need to re-import.
                    r.alreadyValid = true;
                    r.ok = true;
                    return r;
                }
                // Write the schema back into BM's template.
                writeSchemaIntoBm(manager, project, schemaFqn, schema);
                r.ok = true;
            }
        }
        catch (Throwable t)
        {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            r.error = "importSchemaFromDisk failed: " + cause.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + cause.getMessage(); //$NON-NLS-1$
            Activator.logWarning(r.error);
        }
        finally
        {
            try
            {
                bc.ungetService(rvsRef);
            }
            catch (Throwable ignored)
            {
            }
            try
            {
                bc.ungetService(lookupRef);
            }
            catch (Throwable ignored)
            {
            }
            r.readMs = System.currentTimeMillis() - t0;
        }
        return r;
    }

    private static Method findDeserializeMethod(Class<?> serializerClass, Class<?> dtProjectIface)
    {
        for (Method m : serializerClass.getMethods())
        {
            if (!"deserializeXML".equals(m.getName())) //$NON-NLS-1$
            {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length >= 1 && InputStream.class.isAssignableFrom(params[0]))
            {
                return m;
            }
        }
        return null;
    }

    private static Object resolveVersion(Object rvs, IProject project)
    {
        try
        {
            Method m = rvs.getClass().getMethod("getRuntimeVersion", IProject.class); //$NON-NLS-1$
            Object v = m.invoke(rvs, project);
            if (v != null)
            {
                return v;
            }
        }
        catch (Throwable ignored)
        {
        }
        try
        {
            Class<?> versionClass = Class.forName(CLS_VERSION);
            return versionClass.getField("LATEST").get(null); //$NON-NLS-1$
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static void writeSchemaIntoBm(IBmModelManager manager, IProject project, String fqn,
        Object schema) throws Exception
    {
        // Acquire the BM model and run a write task that replaces Template.template
        // on the resolved top object.
        com._1c.g5.v8.bm.integration.IBmModel model = manager.getModel(project);
        if (model == null)
        {
            throw new RuntimeException("BM model not available"); //$NON-NLS-1$
        }
        model.execute(new com._1c.g5.v8.bm.integration.AbstractBmTask<Void>("repair_schema.write") //$NON-NLS-1$
        {
            @Override
            public Void execute(com._1c.g5.v8.bm.core.IBmTransaction tx,
                org.eclipse.core.runtime.IProgressMonitor pm)
            {
                try
                {
                    com._1c.g5.v8.bm.core.IBmObject top = tx.getTopObjectByFqn(fqn);
                    if (top == null)
                    {
                        throw new RuntimeException("Top-object not found in BM: " + fqn); //$NON-NLS-1$
                    }
                    // Set Template.template = schema via reflection
                    Method setTemplate = top.getClass().getMethod("setTemplate", schema.getClass().getInterfaces().length > 0 //$NON-NLS-1$
                        ? schema.getClass().getInterfaces()[0] : schema.getClass());
                    setTemplate.invoke(top, schema);
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e.getMessage(), e);
                }
                return null;
            }
        });
    }
}
