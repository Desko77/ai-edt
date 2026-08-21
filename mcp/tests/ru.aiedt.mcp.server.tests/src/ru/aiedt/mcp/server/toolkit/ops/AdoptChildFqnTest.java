/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Guards the naming of the child an extension borrows.
 * <p>
 * <b>It borrowed the owner instead, and said "borrowed".</b> The schema had promised since 1.43
 * that a child's name is composed from the owner, the kind and the name when no full FQN is given;
 * nothing composed it, so the name taken was whichever alias the caller had filled in - and for a
 * child call that is the owner. An extension meant to be coupled to one attribute came away coupled
 * to a whole catalogue, and the answer reported success either way.
 * </p>
 * <p>
 * Measured on a stand before the fix:
 * {@code borrow_child objectFqn=Catalog.F3Formed childKind=Template name=F3Text} returned
 * {@code targetFqn: Catalog.F3Formed, returned: CatalogImpl}, and the template appeared nowhere.
 * </p>
 */
public class AdoptChildFqnTest
{
    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    public void anOwnerWithAKindAndANameNamesTheChild()
    {
        assertEquals("Catalog.Products.Attribute.Price",
            MiscOps.composeChildFqn("adopt_child", "Catalog.Products",
                args("childKind", "Attribute", "name", "Price")));
    }

    @Test
    public void theOlderSpellingOfTheNameIsUnderstoodToo()
    {
        assertEquals("Catalog.Products.Template.Invoice",
            MiscOps.composeChildFqn("adopt_child", "Catalog.Products",
                args("childKind", "Template", "childName", "Invoice")));
    }

    @Test
    public void aFullChildFqnIsLeftAlone()
    {
        // Appending to a name that already reaches the child would build a name nothing has.
        assertEquals("Catalog.Products.Form.ItemForm",
            MiscOps.composeChildFqn("adopt_child", "Catalog.Products.Form.ItemForm",
                args("childKind", "Form", "name", "ItemForm")));
    }

    @Test
    public void anOperationAboutObjectsIsNotTouched()
    {
        assertEquals("Catalog.Products", MiscOps.composeChildFqn("adopt_object", "Catalog.Products",
            args("childKind", "Attribute", "name", "Price")));
    }

    @Test
    public void withoutAKindOrANameTheOwnerStandsAsGiven()
    {
        // Half the information composes half a name, which would address nothing. The call is left
        // as the caller made it and fails on its own terms.
        assertEquals("Catalog.Products",
            MiscOps.composeChildFqn("adopt_child", "Catalog.Products", args("childKind", "Attribute")));
        assertEquals("Catalog.Products",
            MiscOps.composeChildFqn("adopt_child", "Catalog.Products", args("name", "Price")));
    }

    @Test
    public void aFormItemIsComposedTheSameWay()
    {
        assertEquals("Catalog.Products.Form.ItemForm.Item.Price",
            MiscOps.composeChildFqn("adopt_form_item", "Catalog.Products.Form.ItemForm.Item.Price",
                args("childKind", "Item", "name", "Price")));
    }

    @Test
    public void nothingAtAllIsHandledRatherThanThrown()
    {
        assertEquals(null, MiscOps.composeChildFqn("adopt_child", null,
            args("childKind", "Attribute", "name", "Price")));
    }
}
