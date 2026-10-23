/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.support.BmObjectHelper;

/**
 * Tests containment-based registration using metadata factory objects and a recording transaction.
 * Verifies collection membership, language properties and top-object attachment calls. Live BM
 * transaction rollback and dry-run behavior require a separate workspace-level check.
 */
public class ALanguageIsCreatedInsideTheConfigurationTest
{
    /**
     * A transaction stand-in that records every top-object attach it is asked for. With the guard
     * on, it answers an object that already joined a container the way the real transaction service
     * does; any other call is a call the creation route does not make.
     *
     * @param attaches where the asked-for attaches are recorded as {@code fqn <- type}
     * @param guard whether an attach of an already contained object is refused
     * @return the transaction stand-in
     */
    private static IBmTransaction recordingTransaction(List<String> attaches, boolean guard)
    {
        return (IBmTransaction)Proxy.newProxyInstance(IBmTransaction.class.getClassLoader(),
            new Class<?>[] {IBmTransaction.class}, (proxy, method, args) -> {
                if ("attachTopObject".equals(method.getName())) //$NON-NLS-1$
                {
                    IBmObject candidate = (IBmObject)args[0];
                    if (guard && candidate.eContainer() != null)
                    {
                        throw new IllegalStateException("The object is already attached"); //$NON-NLS-1$
                    }
                    attaches.add(args[1] + " <- " + candidate.eClass().getName()); //$NON-NLS-1$
                    return null;
                }
                throw new UnsupportedOperationException("creation called " + method.getName()); //$NON-NLS-1$
            });
    }

    /**
     * A language built the way the creating task builds one: the server's own creation route, the
     * name, and the properties applied through the same setter the task uses.
     *
     * @param name the language name
     * @param code the language code property value
     * @return the built language
     */
    private static Language language(String name, String code)
    {
        Language language = (Language)BmObjectHelper.createGenericObject("Language"); //$NON-NLS-1$
        language.setName(name);
        assertNull("languageCode must apply through the creation setter", //$NON-NLS-1$
            BmObjectHelper.setProperty(language, "languageCode", code)); //$NON-NLS-1$
        return language;
    }

    /** One language, its code, and no top-object attach asked for: the add registers it alone. */
    @Test
    public void aLanguageJoinsTheConfigurationByTheCollectionAddAlone()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Language english = language("English", "en"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> attaches = new ArrayList<>();

        boolean registered = ObjectOps.registerCreatedObject(config, english, "Language", //$NON-NLS-1$
            "Language.English", recordingTransaction(attaches, false)); //$NON-NLS-1$

        assertTrue(registered);
        assertEquals(1, config.getLanguages().size());
        assertSame(english, config.getLanguages().get(0));
        assertEquals("en", english.getLanguageCode()); //$NON-NLS-1$
        assertEquals("English", english.getName()); //$NON-NLS-1$
        assertSame("a contained language lives inside the configuration", config, english.eContainer()); //$NON-NLS-1$
        assertTrue("no top-object attach may be asked for a contained language: " + attaches, //$NON-NLS-1$
            attaches.isEmpty());
    }

    /**
     * A reference-held type keeps the old contract: the collection add stores the reference, and
     * the separate top-object attach is still asked for, once and under the object's fqn.
     */
    @Test
    public void aReferenceHeldTypeIsStillRegisteredAsItsOwnTopObject()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        MdObject goods = BmObjectHelper.createGenericObject("Catalog"); //$NON-NLS-1$
        goods.setName("Goods"); //$NON-NLS-1$
        List<String> attaches = new ArrayList<>();

        boolean registered = ObjectOps.registerCreatedObject(config, goods, "Catalog", //$NON-NLS-1$
            "Catalog.Goods", recordingTransaction(attaches, false)); //$NON-NLS-1$

        assertTrue(registered);
        assertEquals(1, config.getCatalogs().size());
        assertSame(goods, config.getCatalogs().get(0));
        assertNull("a referenced member stays a free top object until the attach", goods.eContainer()); //$NON-NLS-1$
        assertEquals(List.of("Catalog.Goods <- Catalog"), attaches); //$NON-NLS-1$
    }

    /**
     * The attach the registration no longer asks for is the one the transaction refuses: with the
     * guard on, registering a language succeeds only because no attach happens, while the attach
     * itself is answered with the service's own refusal text.
     */
    @Test
    public void theAttachTheSkipAvoidsIsTheOneTheTransactionRefuses()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Language english = language("English", "en"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> attaches = new ArrayList<>();

        assertTrue(ObjectOps.registerCreatedObject(config, english, "Language", //$NON-NLS-1$
            "Language.English", recordingTransaction(attaches, true))); //$NON-NLS-1$
        assertTrue(attaches.isEmpty());

        try
        {
            recordingTransaction(new ArrayList<>(), true)
                .attachTopObject((IBmObject)english, "Language.English"); //$NON-NLS-1$
            fail("the guard must refuse an object that already joined the configuration"); //$NON-NLS-1$
        }
        catch (IllegalStateException refused)
        {
            assertEquals("The object is already attached", refused.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * The model premise the decision reads: languages is the containment collection, the
     * reference-held collections are not, and the decision follows the live model rather than a
     * list of names - including the acronym spelling the catalog writes differently.
     */
    @Test
    public void theLanguagesCollectionIsTheOneContainmentCollectionOfTheConfiguration()
    {
        assertTrue(MdClassPackage.eINSTANCE.getConfiguration_Languages().isContainment());
        assertFalse(MdClassPackage.eINSTANCE.getConfiguration_Catalogs().isContainment());
        assertFalse(MdClassPackage.eINSTANCE.getConfiguration_Subsystems().isContainment());
        assertFalse(MdClassPackage.eINSTANCE.getConfiguration_XDTOPackages().isContainment());

        assertTrue(BmObjectHelper.isInlineConfigurationChild("Language")); //$NON-NLS-1$
        assertFalse(BmObjectHelper.isInlineConfigurationChild("Catalog")); //$NON-NLS-1$
        assertFalse(BmObjectHelper.isInlineConfigurationChild("Subsystem")); //$NON-NLS-1$
        // The feature is spelled xDTOPackages while the catalog says xdtoPackages.
        assertFalse(BmObjectHelper.isInlineConfigurationChild("XDTOPackage")); //$NON-NLS-1$
        assertFalse(BmObjectHelper.isInlineConfigurationChild("NoSuchType")); //$NON-NLS-1$
    }

    /**
     * A property the model refuses stops the creation before anything registers: the route applies
     * properties to the still standalone object and a refusal aborts the task, so neither a
     * collection nor a container holds the language afterwards.
     */
    @Test
    public void aPropertyTheModelRefusesLeavesNoLanguageInTheConfiguration()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Language english = (Language)BmObjectHelper.createGenericObject("Language"); //$NON-NLS-1$
        english.setName("English"); //$NON-NLS-1$

        String refused = BmObjectHelper.setProperty(english, "noSuchProperty", "value"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("an unknown property is refused, not dropped: " + refused, refused != null); //$NON-NLS-1$
        assertTrue("the aborted creation registers nothing", config.getLanguages().isEmpty()); //$NON-NLS-1$
        assertNull("the language never joined a configuration", english.eContainer()); //$NON-NLS-1$
        assertNull(english.getLanguageCode());
    }
}
