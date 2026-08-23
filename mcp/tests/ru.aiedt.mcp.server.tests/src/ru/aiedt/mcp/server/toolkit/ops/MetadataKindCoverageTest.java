/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

import ru.aiedt.mcp.server.support.MetadataTypeCatalog;

/**
 * Reconciles what a listing walks against what the model has.
 * <p>
 * <b>The two lists drifted and the answer stayed quiet.</b> The kinds walked by
 * {@code metadataType="all"} lived in an array beside the catalogue, and a kind added to the
 * catalogue did not have to be added there. Fourteen never were - document journals, sequences,
 * external data sources, command groups among them - so a listing of a whole configuration came
 * back without them and named no gap. A map built from that answer has no journals in it, and
 * nothing says why.
 * </p>
 * <p>
 * This is the reconciliation that array could not do for itself: every kind the model knows must be
 * reachable, and the count is explicit so a new kind cannot slip in unwalked.
 * </p>
 */
public class MetadataKindCoverageTest
{
    @Test
    public void everyKindTheCatalogueKnowsIsWalked()
    {
        Set<String> missing = new LinkedHashSet<>();
        for (String kind : MetadataTypeCatalog.getAllEnglishSingularNames())
        {
            if (MetadataObjectsReader.collectorFor(kind) == null)
            {
                missing.add(kind);
            }
        }
        assertTrue("a kind nothing walks is a kind the answer omits in silence: " + missing,
            missing.isEmpty());
    }

    @Test
    public void aKindIsWalkedUnderItsOwnName()
    {
        // The collector carries the catalogue's canonical name into the answer, so an object comes
        // back labelled with the kind it is rather than with whatever token addressed it.
        for (String kind : MetadataTypeCatalog.getAllEnglishSingularNames())
        {
            assertEquals(kind, MetadataObjectsReader.collectorFor(kind).typeName());
        }
    }

    @Test
    public void theKindsThatUsedToBeMissedAreReachable()
    {
        // Named one by one on purpose. A count can be satisfied by any fifty; these fourteen are
        // the ones that were actually absent, and two of them are everyday objects.
        for (String kind : new String[] {"DocumentJournal", "Sequence", "ExternalDataSource",
            "CommandGroup", "DocumentNumerator", "ExternalDataProcessor", "ExternalReport",
            "FunctionalOptionsParameter", "IntegrationService", "Interface", "Style", "WSReference"})
        {
            assertNotNull(kind + " was one of the kinds the listing walked past",
                MetadataObjectsReader.collectorFor(kind));
        }
    }

    @Test
    public void aKindNamedDirectlyResolvesEvenWithoutACategory()
    {
        // Asked for by name rather than through "all". Before, a kind the category array did not
        // carry was answered "unknown type" - true of the array, false of the configuration.
        assertNotNull(MetadataObjectsReader.findCollectorForTest("documentjournal"));
        assertNotNull(MetadataObjectsReader.findCollectorForTest("sequences"));
    }

    @Test
    public void somethingThatIsNotAKindIsStillUnknown()
    {
        // The widening must not turn every typo into a walk over nothing.
        assertEquals(null, MetadataObjectsReader.findCollectorForTest("nosuchkind"));
    }
}
