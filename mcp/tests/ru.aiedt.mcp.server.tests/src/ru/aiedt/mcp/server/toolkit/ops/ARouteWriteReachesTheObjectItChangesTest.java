/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Task;

import ru.aiedt.mcp.server.support.BmRouteMapHelper;

/**
 * A write about a business process lands on the object it changes: the Task addressing is
 * written through the linked Task, whose transaction exports it to disk, and the handler
 * procedures go to the object module by its path, which exists before the module file does.
 */
public class ARouteWriteReachesTheObjectItChangesTest
{
    /**
     * A configuration with one business process linked to a task and one without a task.
     *
     * @return the model
     */
    private static Configuration model()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        configuration.setName("Cfg"); //$NON-NLS-1$
        Task task = MdClassFactory.eINSTANCE.createTask();
        task.setName("Задача"); //$NON-NLS-1$
        configuration.getTasks().add(task);
        BusinessProcess linked = MdClassFactory.eINSTANCE.createBusinessProcess();
        linked.setName("Маршрут"); //$NON-NLS-1$
        linked.setTask(task);
        configuration.getBusinessProcesses().add(linked);
        BusinessProcess bare = MdClassFactory.eINSTANCE.createBusinessProcess();
        bare.setName("БезЗадачи"); //$NON-NLS-1$
        configuration.getBusinessProcesses().add(bare);
        return configuration;
    }

    /**
     * A business process is written through its task, in any spelling of the address; a task is
     * written as named.
     */
    @Test
    public void theAddressingIsWrittenThroughTheLinkedTask()
    {
        Configuration configuration = model();
        assertEquals("Task.Задача", //$NON-NLS-1$
            SpecializedOps.linkedTaskOf(configuration, null, "BusinessProcess.Маршрут").fqn); //$NON-NLS-1$
        assertEquals("Task.Задача", //$NON-NLS-1$
            SpecializedOps.linkedTaskOf(configuration, null, "БизнесПроцесс.маршрут").fqn); //$NON-NLS-1$
        assertEquals("Task.Задача", //$NON-NLS-1$
            SpecializedOps.linkedTaskOf(configuration, null, "Task.Задача").fqn); //$NON-NLS-1$
    }

    /**
     * A business process neither configuration holds is refused rather than written through as
     * named: the write would land on the business process itself and export an object the caller
     * never named.
     */
    @Test
    public void anUnknownBusinessProcessIsRefused()
    {
        SpecializedOps.LinkedTask miss =
            SpecializedOps.linkedTaskOf(model(), null, "BusinessProcess.Нет"); //$NON-NLS-1$
        assertNull(miss.fqn);
        assertTrue(miss.refusal, miss.refusal.contains("BusinessProcess.Нет")); //$NON-NLS-1$
        assertTrue(miss.refusal, miss.refusal.contains("Nothing was changed")); //$NON-NLS-1$
    }

    /**
     * A business process without a task has nothing to write through.
     */
    @Test
    public void aBusinessProcessWithoutATaskHasNothingToWriteThrough()
    {
        SpecializedOps.LinkedTask bare =
            SpecializedOps.linkedTaskOf(model(), null, "BusinessProcess.БезЗадачи"); //$NON-NLS-1$
        assertNull(bare.fqn);
        assertTrue(bare.refusal, bare.refusal.contains("has no linked Task")); //$NON-NLS-1$
    }

    /**
     * A business process of the base configuration is reached through the extension's own
     * configuration and the base one it adopts.
     */
    @Test
    public void aBaseConfigurationBusinessProcessIsReachedThroughTheBase()
    {
        Configuration own = MdClassFactory.eINSTANCE.createConfiguration();
        own.setName("Ext"); //$NON-NLS-1$
        SpecializedOps.LinkedTask target =
            SpecializedOps.linkedTaskOf(own, model(), "BusinessProcess.Маршрут"); //$NON-NLS-1$
        assertNull(target.refusal);
        assertEquals("Task.Задача", target.fqn); //$NON-NLS-1$

        SpecializedOps.LinkedTask nowhere =
            SpecializedOps.linkedTaskOf(own, model(), "BusinessProcess.Другой"); //$NON-NLS-1$
        assertNull(nowhere.fqn);
        assertNotNull(nowhere.refusal);
        assertTrue(nowhere.refusal, nowhere.refusal.contains("base configuration")); //$NON-NLS-1$
    }

    /**
     * The object module is named by its path under src/, and only for a business process.
     */
    @Test
    public void theObjectModuleIsNamedByItsPath()
    {
        assertEquals("BusinessProcesses/ProbeRoute/ObjectModule.bsl", //$NON-NLS-1$
            BmRouteMapHelper.objectModulePath("BusinessProcess.ProbeRoute")); //$NON-NLS-1$
        assertEquals("BusinessProcesses/Маршрут/ObjectModule.bsl", //$NON-NLS-1$
            BmRouteMapHelper.objectModulePath("БизнесПроцесс.Маршрут")); //$NON-NLS-1$
        assertNull(BmRouteMapHelper.objectModulePath("Catalog.Goods")); //$NON-NLS-1$
        assertNull(BmRouteMapHelper.objectModulePath("BusinessProcess.X.Form.Y")); //$NON-NLS-1$
    }
}
