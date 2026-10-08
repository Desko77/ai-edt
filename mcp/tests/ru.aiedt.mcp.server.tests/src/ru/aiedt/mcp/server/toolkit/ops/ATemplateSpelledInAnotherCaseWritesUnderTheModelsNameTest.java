/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Template;

import ru.aiedt.mcp.server.support.BmTemplateHelper;

/**
 * Which folder a template write lands in when the caller spells the owner or the template in
 * another case.
 * <p>
 * An FQN and a template name both resolve to their object whatever case they were written in, so
 * the folder has to be built from the objects the call found rather than from the words that
 * addressed them: a file system that keeps case apart would otherwise take the content into a
 * second folder beside the one the template's own files live in, while the template the call
 * addressed went on reading the first.
 * </p>
 */
public class ATemplateSpelledInAnotherCaseWritesUnderTheModelsNameTest
{
    /** The owner name in the FQN is written the way the model holds it; the type keeps the caller's words. */
    @Test
    public void theFqnTakesTheOwnersOwnName()
    {
        Catalog goods = catalog("Товары"); //$NON-NLS-1$

        assertEquals("Catalog.Товары", //$NON-NLS-1$
            BmTemplateHelper.modelOwnerFqn("Catalog.товары", goods)); //$NON-NLS-1$
        assertEquals("the folder of a name in another case is the model's folder", //$NON-NLS-1$
            BmTemplateHelper.templateDirRelativePath("Catalog.Товары", "Макет"), //$NON-NLS-1$ //$NON-NLS-2$
            BmTemplateHelper.templateDirRelativePath(
                BmTemplateHelper.modelOwnerFqn("Catalog.товары", goods), "Макет")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A Russian type prefix is a recognized spelling of the same folder and stays as it came. */
    @Test
    public void theTypePrefixKeepsTheWordTheCallerWrote()
    {
        Catalog goods = catalog("Товары"); //$NON-NLS-1$

        String fqn = BmTemplateHelper.modelOwnerFqn("Справочник.товары", goods); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Справочник.Товары", fqn); //$NON-NLS-1$
        assertEquals(BmTemplateHelper.templateDirRelativePath("Catalog.Товары", "Макет"), //$NON-NLS-1$ //$NON-NLS-2$
            BmTemplateHelper.templateDirRelativePath(fqn, "Макет")); //$NON-NLS-1$
    }

    /** An FQN that resolved to nothing, or to an object that holds no name, is left as it came. */
    @Test
    public void anFqnThatNamesNoObjectIsLeftAlone()
    {
        Catalog unnamed = catalog(null);

        assertEquals("Catalog.товары", //$NON-NLS-1$
            BmTemplateHelper.modelOwnerFqn("Catalog.товары", null)); //$NON-NLS-1$
        assertEquals("Catalog.товары", //$NON-NLS-1$
            BmTemplateHelper.modelOwnerFqn("Catalog.товары", unnamed)); //$NON-NLS-1$
        assertEquals("БезТочки", BmTemplateHelper.modelOwnerFqn("БезТочки", catalog("Товары"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Catalog.", BmTemplateHelper.modelOwnerFqn("Catalog.", catalog("Товары"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The template name in the folder is the one the template itself carries. */
    @Test
    public void theTemplateNameIsTheOneTheModelHolds()
    {
        Catalog goods = catalog("Товары"); //$NON-NLS-1$
        Template print = template("Печать"); //$NON-NLS-1$
        goods.getTemplates().add(print);

        assertEquals("Печать", MxlWorkshopTool.modelTemplateName(goods, "печать")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("both names come from the model, so the folder is the template's own", //$NON-NLS-1$
            BmTemplateHelper.templateDirRelativePath("Catalog.Товары", "Печать"), //$NON-NLS-1$ //$NON-NLS-2$
            BmTemplateHelper.templateDirRelativePath(
                BmTemplateHelper.modelOwnerFqn("Catalog.товары", goods), //$NON-NLS-1$
                MxlWorkshopTool.modelTemplateName(goods, "печать"))); //$NON-NLS-1$
    }

    /** An owner that does not hold the named template is refused rather than written under a made-up name. */
    @Test
    public void anOwnerThatDoesNotHoldTheTemplateIsRefused()
    {
        Catalog goods = catalog("Товары"); //$NON-NLS-1$
        goods.getTemplates().add(template("Печать")); //$NON-NLS-1$

        try
        {
            MxlWorkshopTool.modelTemplateName(goods, "НетТакогоМакета"); //$NON-NLS-1$
            fail("a template the model does not hold has no folder to write into"); //$NON-NLS-1$
        }
        catch (RuntimeException refused)
        {
            assertNotNull(refused.getMessage());
        }
    }

    /** A common template is the template itself: the case of its name addresses it and not another object. */
    @Test
    public void aCommonTemplateIsItsOwnTemplateInEitherCase()
    {
        CommonTemplate common = MdClassFactory.eINSTANCE.createCommonTemplate();
        common.setName("Общий"); //$NON-NLS-1$

        assertSame("the name in another case resolves to the object itself", //$NON-NLS-1$
            common, MxlWorkshopTool.resolveTemplate(common, "общий")); //$NON-NLS-1$
        assertEquals("Общий", MxlWorkshopTool.modelTemplateName(common, "общий")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(BmTemplateHelper.templateDirRelativePath("CommonTemplate.Общий", "Общий"), //$NON-NLS-1$ //$NON-NLS-2$
            BmTemplateHelper.templateDirRelativePath(
                BmTemplateHelper.modelOwnerFqn("CommonTemplate.общий", common), //$NON-NLS-1$
                MxlWorkshopTool.modelTemplateName(common, "общий"))); //$NON-NLS-1$
    }

    /** A catalog of the model, named or not. */
    private static Catalog catalog(String name)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        if (name != null)
        {
            catalog.setName(name);
        }
        return catalog;
    }

    /** A template of the model. */
    private static Template template(String name)
    {
        Template template = MdClassFactory.eINSTANCE.createTemplate();
        assertNotNull("this runtime cannot create a Template", template); //$NON-NLS-1$
        template.setName(name);
        return template;
    }
}
