/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.support;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.Hashtable;
import java.util.Collections;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import com.e1c.g5.dt.core.api.platform.IDtProjectRuntimeVersionManager;
import com._1c.g5.v8.dt.platform.version.Version;
import org.junit.Test;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.sheet.SheetFactory;
import com._1c.g5.v8.dt.moxel.util.V8MoxelSerializer;

public class ParameterCellsKeepTheirFormatTest
{
    @Test public void parameterOnlyRowKeepsNamesFillWrapAndBordersAfterRealRoundtrip() throws Exception
    {
        SpreadsheetDocument doc = SheetFactory.createSpreadsheetDocument();
        assertNull(BmTemplateHelper.setCellContent(doc, 1, 1, null, false, "ru", "parameter", "Amount"));
        assertNull(BmTemplateHelper.setCellContent(doc, 1, 2, null, false, "ru", "parameter", "Total"));
        BmTemplateHelper.CellLook look = new BmTemplateHelper.CellLook();
        look.border = "Solid";
        assertNull(BmTemplateHelper.applyCellFormat(doc, 1, 1, 1, 2, "wrap", null, null,
            null, null, null, look).error);
        IDtProject project = (IDtProject) Proxy.newProxyInstance(IDtProject.class.getClassLoader(),
            new Class<?>[] { IDtProject.class }, (proxy, method, args) -> {
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("toString".equals(method.getName())) return "Synthetic MXL project";
                return null;
            });
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        ServiceReference<IDtProjectRuntimeVersionManager> previousRef =
            context.getServiceReference(IDtProjectRuntimeVersionManager.class);
        IDtProjectRuntimeVersionManager previous = previousRef == null ? null : context.getService(previousRef);
        IDtProjectRuntimeVersionManager versions = (IDtProjectRuntimeVersionManager) Proxy.newProxyInstance(
            IDtProjectRuntimeVersionManager.class.getClassLoader(),
            new Class<?>[] { IDtProjectRuntimeVersionManager.class }, (proxy, method, args) -> {
                if ("getRuntimeVersion".equals(method.getName()) && args != null
                    && args.length > 0 && args[0] == project) return Version.V8_3_27;
                if ("getSupportedVersions".equals(method.getName()) && previous == null)
                    return Collections.singletonList(Version.V8_3_27);
                if (method.getDeclaringClass() == Object.class)
                {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return "Synthetic MXL runtime version fixture";
                }
                if (previous != null)
                {
                    try { return method.invoke(previous, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                }
                throw new UnsupportedOperationException("Not the synthetic MXL project: " + method.getName());
            });
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(Constants.SERVICE_RANKING, Integer.MAX_VALUE);
        ServiceRegistration<IDtProjectRuntimeVersionManager> registration =
            context.registerService(IDtProjectRuntimeVersionManager.class, versions, properties);
        try
        {
            ServiceReference<IDtProjectRuntimeVersionManager> selected =
                context.getServiceReference(IDtProjectRuntimeVersionManager.class);
            assertNotNull("runtime version fixture must be published before serialization", selected);
            IDtProjectRuntimeVersionManager selectedService = context.getService(selected);
            try { assertSame("the synthetic fixture must be selected", versions, selectedService); }
            finally { context.ungetService(selected); }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            // The String argument is the line separator, not the encoding (the serializer
            // always creates an UTF-8 OutputStreamWriter).
            new V8MoxelSerializer(project, doc).serializeXML(bytes, "\n");
            SpreadsheetDocument reloaded = SheetFactory.createSpreadsheetDocument();
            new V8MoxelSerializer(project, reloaded).deserializeXML(new ByteArrayInputStream(bytes.toByteArray()));
            assertEquals(BmTemplateHelper.readSpreadsheet(doc, "ru").get("cells"),
                BmTemplateHelper.readSpreadsheet(reloaded, "ru").get("cells"));
            // The serializer may compact unused/duplicate format slots. Compare the effective
            // format referenced by each parameter cell, not the size of the backing format table.
            for (int col = 0; col < 2; col++)
            {
                com._1c.g5.v8.dt.moxel.Cell before = doc.getRows().map().get(Integer.valueOf(0)).getCells().map().get(Integer.valueOf(col));
                com._1c.g5.v8.dt.moxel.Cell after = reloaded.getRows().map().get(Integer.valueOf(0)).getCells().map().get(Integer.valueOf(col));
                assertEquals(col == 0 ? "Amount" : "Total", after.getParameter());
                com._1c.g5.v8.dt.moxel.Format original = doc.getFormats().get(before.getFormatIndex());
                com._1c.g5.v8.dt.moxel.Format restored = reloaded.getFormats().get(after.getFormatIndex());
                assertEquals(com._1c.g5.v8.dt.moxel.content.FillType.PARAMETER, restored.getFillType());
                assertEquals(original.getTextPlacement(), restored.getTextPlacement());
                assertEquals("Wrap", restored.getTextPlacement().getName());
                int[] sourceBorders = { original.getLeftBorder(), original.getTopBorder(),
                    original.getRightBorder(), original.getBottomBorder() };
                int[] restoredBorders = { restored.getLeftBorder(), restored.getTopBorder(),
                    restored.getRightBorder(), restored.getBottomBorder() };
                for (int side = 0; side < 4; side++)
                {
                    assertTrue("border must reference a line", sourceBorders[side] >= 0
                        && sourceBorders[side] < doc.getLines().size());
                    assertTrue("restored border must reference a line", restoredBorders[side] >= 0
                        && restoredBorders[side] < reloaded.getLines().size());
                    com._1c.g5.v8.dt.moxel.content.CellLine line =
                        (com._1c.g5.v8.dt.moxel.content.CellLine) reloaded.getLines().get(restoredBorders[side]);
                    assertEquals("Solid", line.getStyle().getName());
                    assertEquals(1, line.getWidth());
                    assertTrue("border style must survive real serialization",
                        org.eclipse.emf.ecore.util.EcoreUtil.equals(
                            doc.getLines().get(sourceBorders[side]),
                            reloaded.getLines().get(restoredBorders[side])));
                }
            }
            assertTrue(bytes.toString("UTF-8").contains("Amount"));
            assertTrue(bytes.toString("UTF-8").contains("Total"));
        }
        finally
        {
            registration.unregister();
            if (previousRef != null && previous != null) context.ungetService(previousRef);
        }
    }
}
