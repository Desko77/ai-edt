/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import java.util.UUID;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;

/**
 * An object under the borrowed address is the borrowed identity only when it links to the base
 * object the borrow would copy.
 * <p>
 * The extension can hold its own object under the same name. Its FQN matches, but it is not the
 * base object: it carries no {@code extendedConfigurationObject}, or one that names another base.
 * Treating that collision as attached makes a repeat borrow look idempotent, and treating it as
 * absent makes it look fresh. It is neither.
 * </p>
 */
public class BmExtensionHelperAttachmentIdentityTest
{
    private static final UUID BASE_UUID =
        UUID.fromString("00000000-0000-0000-0000-000000000001"); //$NON-NLS-1$

    private static final UUID OTHER_BASE_UUID =
        UUID.fromString("00000000-0000-0000-0000-000000000002"); //$NON-NLS-1$

    private static Catalog object(ObjectBelonging belonging, UUID link)
    {
        Catalog object = MdClassFactory.eINSTANCE.createCatalog();
        object.setName("Goods"); //$NON-NLS-1$
        object.setObjectBelonging(belonging);
        object.setExtendedConfigurationObject(link);
        return object;
    }

    /** The extension's own object under the address is not the borrowed identity. */
    @Test
    public void anOwnObjectUnderTheAddressIsNotBorrowedIdentity()
    {
        Catalog own = object(ObjectBelonging.NATIVE, null);

        assertEquals(BmExtensionHelper.Attachment.UNKNOWN,
            BmExtensionHelper.attachmentIdentity(own, BASE_UUID));
    }

    /** An adopted object linked to the base object's uuid is the borrowed identity. */
    @Test
    public void anObjectLinkedToTheBaseUuidIsBorrowedIdentity()
    {
        Catalog borrowed = object(ObjectBelonging.ADOPTED, BASE_UUID);

        assertEquals(BmExtensionHelper.Attachment.ATTACHED,
            BmExtensionHelper.attachmentIdentity(borrowed, BASE_UUID));
    }

    /** An object adopted from another base is a collision, not the identity being previewed. */
    @Test
    public void anObjectLinkedToAnotherBaseIsNotBorrowedIdentity()
    {
        Catalog collision = object(ObjectBelonging.ADOPTED, OTHER_BASE_UUID);

        assertEquals(BmExtensionHelper.Attachment.UNKNOWN,
            BmExtensionHelper.attachmentIdentity(collision, BASE_UUID));
    }

    /** An adopted object without its link cannot be claimed as the borrowed identity. */
    @Test
    public void anAdoptedObjectWithoutItsLinkIsNotBorrowedIdentity()
    {
        Catalog broken = object(ObjectBelonging.ADOPTED, null);

        assertEquals(BmExtensionHelper.Attachment.UNKNOWN,
            BmExtensionHelper.attachmentIdentity(broken, BASE_UUID));
    }
}
