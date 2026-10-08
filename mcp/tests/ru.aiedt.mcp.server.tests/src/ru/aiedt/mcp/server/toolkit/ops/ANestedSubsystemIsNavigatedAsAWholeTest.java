/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;

/**
 * A nested subsystem FQN is navigated as a whole: {@code Subsystem.A.B} reaches the subsystem
 * nested in A, the same one {@code Subsystem.A.Subsystem.B} names.
 * <p>
 * The walk used to read only every second segment, so an address written without repeating the
 * kind stopped at the first name - {@code Subsystem.Sales.Retail} answered the interface of
 * {@code Sales} - and the answer was signed with the FQN the caller asked for, so the wrong
 * interface read as the right one.
 * </p>
 */
public class ANestedSubsystemIsNavigatedAsAWholeTest
{
    private GetCommandInterfaceTool tool;

    private Configuration configuration;

    private Subsystem retail;

    /**
     * A configuration with Sales holding Retail nested inside it.
     */
    @Before
    public void salesHoldingRetail()
    {
        tool = new GetCommandInterfaceTool();
        configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Subsystem sales = MdClassFactory.eINSTANCE.createSubsystem();
        sales.setName("Sales"); //$NON-NLS-1$
        retail = MdClassFactory.eINSTANCE.createSubsystem();
        retail.setName("Retail"); //$NON-NLS-1$
        sales.getSubsystems().add(retail);
        configuration.getSubsystems().add(sales);
    }

    /** The kind written once per level reaches the nested subsystem. */
    @Test
    public void theKindWrittenOnceReachesTheNestedSubsystem()
    {
        assertSame(retail, tool.navigateSubsystem(configuration, "Subsystem.Sales.Retail")); //$NON-NLS-1$
    }

    /** The kind repeated per level keeps reaching the same subsystem. */
    @Test
    public void theKindRepeatedReachesTheSameSubsystem()
    {
        assertSame(retail, tool.navigateSubsystem(configuration, "Subsystem.Sales.Subsystem.Retail")); //$NON-NLS-1$
    }

    /** A name that names nothing under the level it sits at resolves to nothing. */
    @Test
    public void aMissingNestedNameResolvesToNothing()
    {
        assertNull(tool.navigateSubsystem(configuration, "Subsystem.Sales.Wholesale")); //$NON-NLS-1$
        assertNull(tool.navigateSubsystem(configuration, "Subsystem.Wholesale.Retail")); //$NON-NLS-1$
    }

    /** A top-level subsystem still resolves on its own. */
    @Test
    public void aTopLevelSubsystemResolves()
    {
        Subsystem sales = configuration.getSubsystems().get(0);
        assertNotNull(tool.navigateSubsystem(configuration, "Subsystem.Sales")); //$NON-NLS-1$
        assertSame(sales, tool.navigateSubsystem(configuration, "Subsystem.Sales")); //$NON-NLS-1$
    }
}
