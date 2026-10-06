/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CommonTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Constant;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Template;

/**
 * Which object the spreadsheet operations take for the template.
 * <p>
 * A common template is a top-level object with no owner and no Templates collection, and the
 * operations refused it as an owner that holds no templates. {@code ownerFqn=CommonTemplate.X}
 * with {@code templateName=X} addresses the common template itself.
 * </p>
 */
public class ACommonTemplateIsItsOwnTemplateTest
{
    private static CommonTemplate commonTemplate(String name)
    {
        CommonTemplate template = MdClassFactory.eINSTANCE.createCommonTemplate();
        template.setName(name);
        return template;
    }

    @Test
    public void aCommonTemplateResolvesToItself()
    {
        CommonTemplate template = commonTemplate("ПечатнаяФорма"); //$NON-NLS-1$
        assertSame(template, MxlWorkshopTool.resolveTemplate(template, "ПечатнаяФорма")); //$NON-NLS-1$
    }

    @Test
    public void aNameInAnotherCaseIsRefused()
    {
        // The write operations build the template folder from the name the caller passed, and a
        // folder in another case is another folder on a case-sensitive file system.
        CommonTemplate template = commonTemplate("ПечатнаяФорма"); //$NON-NLS-1$
        try
        {
            MxlWorkshopTool.resolveTemplate(template, "печатнаяформа"); //$NON-NLS-1$
            fail("a name in another case would be written to another folder"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("templateName='ПечатнаяФорма'")); //$NON-NLS-1$
        }
    }

    @Test
    public void anotherNameIsRefusedAndTheAnswerNamesTheOneToPass()
    {
        CommonTemplate template = commonTemplate("ПечатнаяФорма"); //$NON-NLS-1$
        try
        {
            MxlWorkshopTool.resolveTemplate(template, "Другой"); //$NON-NLS-1$
            fail("a name that is not the common template's own addresses nothing"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("templateName='ПечатнаяФорма'")); //$NON-NLS-1$
            assertTrue(e.getMessage(), e.getMessage().contains("'Другой'")); //$NON-NLS-1$
        }
    }

    @Test
    public void aMissingNameIsRefused()
    {
        try
        {
            MxlWorkshopTool.resolveTemplate(commonTemplate("ПечатнаяФорма"), null); //$NON-NLS-1$
            fail("no name addresses nothing"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("templateName='ПечатнаяФорма'")); //$NON-NLS-1$
        }
    }

    @Test
    public void anOwnerStillAnswersFromItsCollection()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$
        Template template = MdClassFactory.eINSTANCE.createTemplate();
        template.setName("Ценник"); //$NON-NLS-1$
        catalog.getTemplates().add(template);
        assertSame(template, MxlWorkshopTool.resolveTemplate(catalog, "Ценник")); //$NON-NLS-1$
    }

    @Test
    public void anObjectThatHoldsNoTemplatesIsStillRefused()
    {
        Constant constant = MdClassFactory.eINSTANCE.createConstant();
        constant.setName("Валюта"); //$NON-NLS-1$
        try
        {
            MxlWorkshopTool.resolveTemplate(constant, "Ценник"); //$NON-NLS-1$
            fail("a constant holds no templates"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("Constant")); //$NON-NLS-1$
        }
    }
}
