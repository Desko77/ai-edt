/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
     * A business process is written through its task, in any spelling of the address; a task
     * and an unknown business process are written as named.
     */
    @Test
    public void theAddressingIsWrittenThroughTheLinkedTask()
    {
        Configuration configuration = model();
        assertEquals("Task.Задача", //$NON-NLS-1$
            SpecializedOps.linkedTaskFqn(configuration, "BusinessProcess.Маршрут")); //$NON-NLS-1$
        assertEquals("Task.Задача", //$NON-NLS-1$
            SpecializedOps.linkedTaskFqn(configuration, "БизнесПроцесс.маршрут")); //$NON-NLS-1$
        assertEquals("Task.Задача", SpecializedOps.linkedTaskFqn(configuration, "Task.Задача")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("BusinessProcess.Нет", //$NON-NLS-1$
            SpecializedOps.linkedTaskFqn(configuration, "BusinessProcess.Нет")); //$NON-NLS-1$
    }

    /**
     * A business process without a task has nothing to write through.
     */
    @Test
    public void aBusinessProcessWithoutATaskHasNothingToWriteThrough()
    {
        assertNull(SpecializedOps.linkedTaskFqn(model(), "BusinessProcess.БезЗадачи")); //$NON-NLS-1$
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
