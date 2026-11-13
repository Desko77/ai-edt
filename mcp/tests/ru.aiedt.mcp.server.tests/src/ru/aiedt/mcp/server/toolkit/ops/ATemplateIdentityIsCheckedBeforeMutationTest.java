/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.*;
import org.junit.Test;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

public class ATemplateIdentityIsCheckedBeforeMutationTest
{
    @Test public void callbackRechecksIdentityBeforeCallingMutationOrPersistence() throws Exception
    {
        Catalog replacement = MdClassFactory.eINSTANCE.createCatalog();
        replacement.setName("Goods");
        java.util.concurrent.atomic.AtomicInteger mutations = new java.util.concurrent.atomic.AtomicInteger();
        ru.aiedt.mcp.server.support.BmObjectHelper.MdObjectAction callback =
            MxlWorkshopTool.guardedAction(
                new MxlWorkshopTool.ExpectedIdentity(Long.MAX_VALUE, 0, "Print", false),
                (tx, owner) -> { mutations.incrementAndGet(); return null; });
        try { callback.execute(null, replacement); fail("replacement reached mutation"); }
        catch (IllegalStateException expected) { }
        assertEquals(0, mutations.get());
        assertTrue(replacement.getTemplates().isEmpty());
    }

    @Test public void changedOwnerRefusesBeforeTemplateLookupOrAttachment()
    {
        Catalog owner = MdClassFactory.eINSTANCE.createCatalog();
        owner.setName("Goods");
        MxlWorkshopTool.ExpectedIdentity expected =
            new MxlWorkshopTool.ExpectedIdentity(Long.MAX_VALUE, 0, "Print", false);
        try { expected.verify(owner); fail("changed identity accepted"); }
        catch (IllegalStateException refusal)
        { assertTrue(refusal.getMessage().contains("owner changed")); }
        assertTrue(owner.getTemplates().isEmpty());
    }
}
