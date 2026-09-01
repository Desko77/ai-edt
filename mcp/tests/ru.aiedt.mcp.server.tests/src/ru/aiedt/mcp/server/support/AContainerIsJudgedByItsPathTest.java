/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * What an FQN says about the kind of container it names.
 * <p>
 * The two universal item operations used to work this out by searching the text for a substring,
 * and they disagreed: one counted {@code CommonForm.X} a form, the other did not. A substring is
 * also wrong on its own terms - {@code Catalog.TemplateSettings} contains {@code .Template} and was
 * read as a composition schema, so removing an attribute of that catalogue sent the caller to the
 * schema workshop.
 * </p>
 */
public class AContainerIsJudgedByItsPathTest
{
    @Test
    public void aFormOfAnObjectIsAForm()
    {
        assertEquals(ContainerScope.FORM, ContainerScope.of("Catalog.Товары.Form.ФормаСписка"));
        assertEquals(ContainerScope.FORM, ContainerScope.of("Catalog.Товары.Form.ФормаСписка.Form"));
        assertEquals(ContainerScope.FORM, ContainerScope.of("Document.Заказ.Forms.ФормаДокумента"));
    }

    /**
     * The spelling with the trailing kind and the one without both name the same common form, and
     * the neighbouring operation accepted only one of them.
     */
    @Test
    public void aCommonFormIsAFormInBothSpellings()
    {
        assertEquals(ContainerScope.FORM, ContainerScope.of("CommonForm.ПодборТоваров"));
        assertEquals(ContainerScope.FORM, ContainerScope.of("CommonForm.ПодборТоваров.Form"));
    }

    @Test
    public void aTemplateIsATemplate()
    {
        assertEquals(ContainerScope.TEMPLATE, ContainerScope.of("Catalog.Товары.Template.Печать"));
        assertEquals(ContainerScope.TEMPLATE, ContainerScope.of("CommonTemplate.ПечатьЭтикетки"));
    }

    /**
     * The name of an object is not a statement about its kind. Both of these are ordinary
     * catalogues, and the substring search called the first one a composition schema.
     */
    @Test
    public void anObjectNamedAfterAKindIsStillAnObject()
    {
        assertEquals(ContainerScope.METADATA_OBJECT, ContainerScope.of("Catalog.TemplateSettings"));
        assertEquals(ContainerScope.METADATA_OBJECT, ContainerScope.of("Catalog.НастройкиTemplate"));
        assertEquals(ContainerScope.METADATA_OBJECT, ContainerScope.of("Catalog.FormDesigner"));
    }

    /**
     * A kind segment sits at an even index. A name at an odd one carries no claim about the kind,
     * however much it reads like one.
     */
    @Test
    public void aNameThatReadsLikeAKindDecidesNothing()
    {
        assertEquals(ContainerScope.FORM, ContainerScope.of("Catalog.Товары.Form.Template"));
        assertEquals(ContainerScope.TEMPLATE, ContainerScope.of("Catalog.Товары.Template.Form"));
    }

    @Test
    public void anAttributeOfAnObjectIsAnObject()
    {
        assertEquals(ContainerScope.METADATA_OBJECT, ContainerScope.of("Catalog.Товары"));
        assertEquals(ContainerScope.METADATA_OBJECT,
            ContainerScope.of("Catalog.Товары.Attribute.Цена"));
        assertEquals(ContainerScope.METADATA_OBJECT,
            ContainerScope.of("Document.Заказ.TabularSection.Строки"));
    }

    /**
     * Nothing to read is not a reason to guess "form": the form operations would then be handed a
     * container they cannot resolve, and the refusal would name the wrong one.
     */
    @Test
    public void whatCannotBeReadIsNotAForm()
    {
        assertEquals(ContainerScope.METADATA_OBJECT, ContainerScope.of(null));
        assertEquals(ContainerScope.METADATA_OBJECT, ContainerScope.of(""));
        assertEquals(ContainerScope.METADATA_OBJECT, ContainerScope.of("Товары"));
    }
}
