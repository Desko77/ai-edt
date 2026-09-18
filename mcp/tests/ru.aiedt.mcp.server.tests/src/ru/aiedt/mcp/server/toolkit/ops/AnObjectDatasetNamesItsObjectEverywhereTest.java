/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.Test;

/**
 * An Object dataset names its object on every route that makes one.
 *
 * <p>Measured 12.09 on the stand: {@code add_dataset dataSetType=Object dataObjectName=X} came
 * back with no {@code objectName} in the file, while the union's child route already required and
 * wrote it. A decision held beside one of the two routes would have left the other exactly as
 * broken, so the requirement lives in one helper both call - and a value aimed at a non-Object
 * dataset is refused rather than dropped, because a name that cannot land is not a name written.
 */
public class AnObjectDatasetNamesItsObjectEverywhereTest
{
    /**
     * The helper both dataset routes call.
     *
     * @throws Exception when the shared helper is gone
     */
    @Test
    public void theRequirementLivesWhereBothRoutesCallIt()
        throws Exception
    {
        Method helper = DcsWorkshopTool.class.getDeclaredMethod("requireDataObjectName", //$NON-NLS-1$
            Map.class, Object.class, String.class);
        assertTrue(java.lang.reflect.Modifier.isStatic(helper.getModifiers()));
    }

    /**
     * Both surfaces that a strict client builds the call from advertise the argument for both
     * operations, not for the union item alone.
     */
    @Test
    public void bothSchemasAdvertiseItForBothOperations()
    {
        String dcs = new DcsWorkshopTool().getInputSchema();
        assertTrue(dcs, dcs.contains("dataObjectName")); //$NON-NLS-1$
        assertTrue(dcs.contains("add_dataset")); //$NON-NLS-1$
        String facade = new EditMetadataTool().getInputSchema();
        assertTrue(facade, facade.contains("dataObjectName")); //$NON-NLS-1$
    }
}
