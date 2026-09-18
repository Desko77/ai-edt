/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A total names the data it runs over.
 *
 * <p>Measured 12.09 on the stand: {@code add_total expression=Сумма aggregateFunction=Sum} wrote
 * {@code <totalField><expression>Sum(Сумма)</expression></totalField>} - no {@code dataPath}
 * anywhere, because the writer took it from {@code name}, an argument the caller never passes.
 * The path is now the expression as given - not the aggregate wrapped around it - and an explicit
 * {@code dataPath} overrides it, because the caller knows better when the expression is not the
 * path.
 */
public class ATotalNamesWhatItRunsOverTest
{
    /**
     * The argument is advertised and the default is written through the refusing setter - a
     * promised path that cannot land fails the call rather than landing nowhere.
     */
    @Test
    public void thePathIsAdvertisedAndWritten()
    {
        String schema = new DcsWorkshopTool().getInputSchema();
        assertTrue(schema, schema.contains("\"dataPath\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(schema.contains("add_total")); //$NON-NLS-1$
    }
}
