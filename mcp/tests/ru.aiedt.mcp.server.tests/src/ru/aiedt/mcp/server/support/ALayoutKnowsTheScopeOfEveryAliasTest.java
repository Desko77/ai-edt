/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.xtext.resource.IResourceFactory;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.junit.Test;

import ru.aiedt.mcp.server.support.QlValidator.FieldSite;

/**
 * The layout of a parsed query reads its scopes off the model itself, not off a structure built
 * for the test.
 * <p>
 * Every query of a package, every union part and every nested query is a scope of its own: an
 * alias belongs to the scope that declared it, and the site of a field reference carries that
 * scope. One map for the whole batch would let a later query's same-named alias overwrite an
 * earlier one, and the sites of the earlier query would then be settled by a table that query
 * never named.
 * </p>
 * <p>
 * The query is parsed here through the same QlDcs resource the validation uses, in a plain
 * resource set - no project, no metadata. Nothing resolves in such a parse, so what these tests
 * read is the shape: which scopes exist, which aliases each declared, and which scope each field
 * reference belongs to. Which alias resolves WHERE is a property of a model that holds metadata,
 * and reading it is a live check.
 * </p>
 */
public class ALayoutKnowsTheScopeOfEveryAliasTest
{
    /**
     * Parses a query text the way the validation does, without a project.
     *
     * @param text the query text
     * @return the parsed query
     * @throws Exception when the text cannot be loaded
     */
    private static Resource parse(String text) throws Exception
    {
        URI lookup = URI.createURI("/nopr/dcs_workshop_validate.qldcs"); //$NON-NLS-1$
        IResourceServiceProvider provider =
            IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(lookup);
        assertNotNull("the QlDcs language is registered in this runtime", provider); //$NON-NLS-1$
        IResourceFactory factory = provider.get(IResourceFactory.class);
        assertNotNull("the QlDcs resource factory is registered in this runtime", factory); //$NON-NLS-1$
        ResourceSet set = new ResourceSetImpl();
        Resource resource = factory.createResource(URI.createURI("synthetic://layout-test.qldcs")); //$NON-NLS-1$
        set.getResources().add(resource);
        try (InputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))
        {
            resource.load(in, null);
        }
        return resource;
    }

    /**
     * The layout of a text, parsed here and now.
     *
     * @param text the query text
     * @return the layout
     * @throws Exception when the text cannot be parsed
     */
    private static QlValidator.QueryLayout layoutOf(String text) throws Exception
    {
        return QlValidator.layoutOf(parse(text));
    }

    /**
     * The one site of a layout that begins with a given alias.
     *
     * @param layout the layout
     * @param alias the alias, as written
     * @return the site
     */
    private static FieldSite siteOf(QlValidator.QueryLayout layout, String alias)
    {
        for (FieldSite site : layout.sites)
        {
            if (site.alias.equals(alias))
            {
                return site;
            }
        }
        throw new AssertionError("no site of alias " + alias); //$NON-NLS-1$
    }

    @Test
    public void aPackageDeclaresTheSameAliasInTwoScopes() throws Exception
    {
        // The reproduced package: an extension's own object in the first query, a borrowed one in
        // the second, the same alias in both. One flat map would hold one declaration; the layout
        // holds one per query, and each field reference belongs to its own query's declaration.
        String text = "ВЫБРАТЬ Расш.НетТакогоРеквизита КАК Поле ПОМЕСТИТЬ ВТ" //$NON-NLS-1$
            + " ИЗ Справочник.ОбъектРасширения КАК Расш;" //$NON-NLS-1$
            + "ВЫБРАТЬ ВТ.Поле ИЗ ВТ КАК ВТ" //$NON-NLS-1$
            + " ВНУТРЕННЕЕ СОЕДИНЕНИЕ Справочник.Виды КАК Расш ПО Расш.Ссылка = ВТ.Поле"; //$NON-NLS-1$
        QlValidator.QueryLayout layout = layoutOf(text);

        assertEquals("one scope per query of the package", 2, //$NON-NLS-1$
            layout.aliasResolvesByScope.size());
        assertTrue("the first query declares its own Расш", //$NON-NLS-1$
            layout.aliasResolvesByScope.get(Integer.valueOf(0)).containsKey("расш")); //$NON-NLS-1$
        assertTrue("the second query declares its own Расш", //$NON-NLS-1$
            layout.aliasResolvesByScope.get(Integer.valueOf(1)).containsKey("расш")); //$NON-NLS-1$
        assertTrue("the second query also declares ВТ", //$NON-NLS-1$
            layout.aliasResolvesByScope.get(Integer.valueOf(1)).containsKey("вт")); //$NON-NLS-1$

        FieldSite first = siteOf(layout, "Расш"); //$NON-NLS-1$
        FieldSite temp = siteOf(layout, "ВТ"); //$NON-NLS-1$
        assertEquals("a reference of the first query belongs to the first query's scope", 0, //$NON-NLS-1$
            first.scope);
        assertEquals("a reference of the second query belongs to the second query's scope", 1, //$NON-NLS-1$
            temp.scope);
    }

    @Test
    public void aUnionDeclaresTheSameAliasInTwoScopes() throws Exception
    {
        // The same alias in both parts of a union. The parts name different tables, so one flat
        // map would settle a field of the first part by the second part's table.
        String text = "ВЫБРАТЬ Расш.Код ИЗ Справочник.Виды КАК Расш" //$NON-NLS-1$
            + " ОБЪЕДИНИТЬ ВЫБРАТЬ Расш.Код ИЗ Справочник.ОбъектРасширения КАК Расш"; //$NON-NLS-1$
        QlValidator.QueryLayout layout = layoutOf(text);

        assertEquals("one scope per union part", 2, layout.aliasResolvesByScope.size()); //$NON-NLS-1$
        assertTrue(layout.aliasResolvesByScope.get(Integer.valueOf(0)).containsKey("расш")); //$NON-NLS-1$
        assertTrue(layout.aliasResolvesByScope.get(Integer.valueOf(1)).containsKey("расш")); //$NON-NLS-1$

        List<FieldSite> both = layout.sites;
        assertEquals(2, both.size());
        assertTrue("the two references belong to different parts", //$NON-NLS-1$
            both.get(0).scope != both.get(1).scope);
    }

    @Test
    public void aNestedQueryIsAScopeOfItsOwn() throws Exception
    {
        // A field of the nested query is settled by the nested query's own sources, not by the
        // outer query's; a field of the outer query never sees the nested query's aliases.
        String text = "ВЫБРАТЬ Внутр.Код" //$NON-NLS-1$
            + " ИЗ (ВЫБРАТЬ Спр.Код КАК Код ИЗ Справочник.Виды КАК Спр) КАК Внутр"; //$NON-NLS-1$
        QlValidator.QueryLayout layout = layoutOf(text);

        FieldSite outer = siteOf(layout, "Внутр"); //$NON-NLS-1$
        FieldSite inner = siteOf(layout, "Спр"); //$NON-NLS-1$
        assertEquals("the outer reference belongs to the outer query's scope", 0, outer.scope); //$NON-NLS-1$
        assertEquals("the nested reference belongs to the nested query's scope", 1, inner.scope); //$NON-NLS-1$
        assertTrue("the outer scope declares Внутр", //$NON-NLS-1$
            layout.aliasResolvesByScope.get(Integer.valueOf(outer.scope)).containsKey("внутр")); //$NON-NLS-1$
        assertTrue("the nested scope declares Спр", //$NON-NLS-1$
            layout.aliasResolvesByScope.get(Integer.valueOf(inner.scope)).containsKey("спр")); //$NON-NLS-1$
    }

    @Test
    public void aSiteCarriesTheColumnsOfItsReference() throws Exception
    {
        // The columns are what tells two references of one line apart when an issue carries no
        // offset. The node begins at the whitespace before the alias, so the span covers the
        // alias word with room to spare.
        String text = "ВЫБРАТЬ Таб.Код, Таб.Наименование ИЗ Справочник.Номенклатура КАК Таб"; //$NON-NLS-1$
        QlValidator.QueryLayout layout = layoutOf(text);

        assertEquals(2, layout.sites.size());
        int firstWord = text.indexOf("Таб.Код") + 1; //$NON-NLS-1$
        int secondWord = text.indexOf("Таб.Наименование") + 1; //$NON-NLS-1$
        FieldSite first = layout.sites.get(0);
        FieldSite second = layout.sites.get(1);
        assertEquals(1, first.line);
        assertTrue("the first reference's columns cover its alias", //$NON-NLS-1$
            first.column <= firstWord && first.endColumn >= firstWord + 2);
        assertTrue("the second reference's columns cover its alias", //$NON-NLS-1$
            second.column <= secondWord && second.endColumn >= secondWord + 2);
        assertTrue("the two references of one line hold different columns", //$NON-NLS-1$
            first.endColumn < second.column);
    }
}
