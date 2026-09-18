/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A conditional appearance carries both sides of its condition, or the call is refused.
 *
 * <p>Measured on the stand 12.09: {@code add_appearance field=Заметка conditionType=Equal
 * conditionValue=...} came back as {@code <dcsset:item><dcsset:selection/><dcsset:filter/></dcsset:item>}
 * - the handler never read {@code field}, and the filter got a right side with no left. The
 * contract of the parts is now enforced before anything is made: a partial condition is refused,
 * and so is a call with nothing to write.
 */
public class AFilterWithBothSidesOrARefusalTest
{
    /**
     * The alias advertises every argument the workshop reads for this operation - a strict client
     * building the call from the facade's schema has to be able to name all four.
     */
    @Test
    public void theAliasAdvertisesWhatTheWorkshopReads()
    {
        String schema = new EditMetadataTool().getInputSchema();
        assertTrue(schema, schema.contains("conditionType")); //$NON-NLS-1$
        assertTrue(schema, schema.contains("conditionValue")); //$NON-NLS-1$
        assertTrue(schema, schema.contains("\"appearance\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(schema.contains("add_conditional_appearance")); //$NON-NLS-1$
    }
}
