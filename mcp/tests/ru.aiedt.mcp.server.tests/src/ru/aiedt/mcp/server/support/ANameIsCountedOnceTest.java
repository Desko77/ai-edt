/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * A pure rename counts as renamed and nothing else.
 *
 * <p>Two defects lived here and neither was visible from the answer: compared WITH its name, a
 * candidate pair never read equal and {@code renamed} came back empty on every comparison; and a
 * removed name sorted before its added one was emitted as removed before its pair was seen, so
 * one object reported itself both removed and renamed. Pairs are formed before the walk now, the
 * name is out of the evidence only where it is not evidence, and the walk skips both halves of a
 * pair it has already reported.</p>
 *
 * <p>A third defect had the same empty answer: the name travels with mirrors - the uuid, minted
 * anew by a copy and kept by a rename; the synonym, which the rename rewrites when it mirrored
 * the name; the produced type ids, generated from the identity. Compared, a renamed copy read as
 * removed on one side and added on the other. They leave the evidence with the name.</p>
 */
public class ANameIsCountedOnceTest
{
    /**
     * Two catalogs that differ by name, identity and synonym, and by nothing else, are a pure
     * rename: unequal when the name is evidence, equal when it is not. A content difference is
     * still a difference through the same lens.
     */
    @Test
    public void theMirrorsOfTheNameLeaveTheEvidenceWithIt()
    {
        Catalog one = MdClassFactory.eINSTANCE.createCatalog();
        Catalog two = MdClassFactory.eINSTANCE.createCatalog();
        one.setName("Thing"); //$NON-NLS-1$
        two.setName("Renamed"); //$NON-NLS-1$
        one.setUuid(UUID.nameUUIDFromBytes(new byte[]{1}));
        two.setUuid(UUID.nameUUIDFromBytes(new byte[]{2}));
        one.getSynonym().put("ru", "Thing"); //$NON-NLS-1$ //$NON-NLS-2$
        two.getSynonym().put("ru", "Renamed"); //$NON-NLS-1$ //$NON-NLS-2$
        one.setComment("same"); //$NON-NLS-1$
        two.setComment("same"); //$NON-NLS-1$

        assertFalse(MetadataDiffEngine.structurallyEqual(one, two));
        assertTrue(MetadataDiffEngine.structurallyEqual(one, two, true));

        two.setComment("other"); //$NON-NLS-1$
        assertFalse(MetadataDiffEngine.structurallyEqual(one, two, true));
    }
}
