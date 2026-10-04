/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.md.extension.MdExtensionTypeUtil;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeDescriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;

/**
 * The extension's own type entries survive an aligned rewrite of the attribute's type: every one
 * is carried into the new composition, and the composition they came from keeps them until it is
 * replaced.
 */
public class ABorrowedTypeEntryIsCopiedNotMovedTest
{
    /**
     * @return a composition of two extension-owned entries and one checked entry
     */
    private static TypeDescriptionExtension twoOwnAndOneChecked()
    {
        TypeDescriptionExtension current = MdExtensionTypeUtil.newTypeDescriptionExtension();
        current.getTypes().add(MdExtensionTypeUtil.newTypeExtension(
            McoreFactory.eINSTANCE.createType(), MdPropertyState.EXTENDED));
        current.getTypes().add(MdExtensionTypeUtil.newTypeExtension(
            McoreFactory.eINSTANCE.createType(), MdPropertyState.EXTENDED));
        current.getTypes().add(MdExtensionTypeUtil.newTypeExtension(
            McoreFactory.eINSTANCE.createType(), MdPropertyState.CHECKED));
        return current;
    }

    /** An entry added to another composition leaves the one it was in. */
    @Test
    public void aTypeEntryBelongsToOneComposition()
    {
        TypeDescriptionExtension current = twoOwnAndOneChecked();
        TypeDescriptionExtension other = MdExtensionTypeUtil.newTypeDescriptionExtension();
        other.getTypes().add(current.getTypes().get(0));
        assertEquals(2, current.getTypes().size());
    }

    /** Both extension-owned entries are carried over, and the source keeps all three. */
    @Test
    public void everyOwnEntryIsCarriedOver()
    {
        TypeDescriptionExtension current = twoOwnAndOneChecked();
        List<TypeExtension> own = BorrowedSyncWriter.extendedEntries(current);
        TypeDescriptionExtension next = MdExtensionTypeUtil.newTypeDescriptionExtension();
        next.getTypes().addAll(own);

        assertEquals(2, next.getTypes().size());
        for (Object entry : next.getTypes())
        {
            assertEquals(MdPropertyState.EXTENDED, ((TypeExtension)entry).getState());
        }
        assertEquals(3, current.getTypes().size());
    }
}
