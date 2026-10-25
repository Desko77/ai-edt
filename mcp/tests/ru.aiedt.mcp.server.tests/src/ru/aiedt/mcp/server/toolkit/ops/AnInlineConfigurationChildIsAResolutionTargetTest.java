/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EClass;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

/**
 * Tests top-object, inline-containment and child-path reference resolution.
 * Uses metadata factory objects and a recording transaction double; live BM lookup and
 * transaction rollback are verified separately.
 */
public class AnInlineConfigurationChildIsAResolutionTargetTest
{
    /** What the stand-in transaction was asked for, so a test can assert on it. */
    private static final class Recording
    {
        boolean containedWalkStarted;

        EClass containedWalkType;
    }

    /**
     * Builds a read-only stand-in transaction: the top-object route answers from a map, the
     * contained-object route answers from a list and records that it was asked.
     *
     * @param byFqn what the fully qualified name route answers, name to instance
     * @param contained what the contained-object route iterates
     * @param recording where the stand-in records the contained-object ask
     * @return the stand-in transaction
     */
    private static IBmTransaction transactionReading(Map<String, Object> byFqn, List<IBmObject> contained,
        Recording recording)
    {
        return (IBmTransaction)Proxy.newProxyInstance(IBmTransaction.class.getClassLoader(),
            new Class<?>[] {IBmTransaction.class},
            (proxy, method, args) -> {
                if ("getTopObjectByFqn".equals(method.getName()) && args != null) //$NON-NLS-1$
                {
                    return byFqn.get(args[0]);
                }
                if ("getContainedObjectIterator".equals(method.getName())) //$NON-NLS-1$
                {
                    recording.containedWalkStarted = true;
                    recording.containedWalkType = (EClass)args[0];
                    return contained.iterator();
                }
                throw new UnsupportedOperationException("Unexpected transaction call: "
                    + method.getName()); //$NON-NLS-1$
            });
    }

    /**
     * Reads the configuration's own languages back as the transaction's contained objects.
     *
     * @param configuration the configuration holding the languages
     * @return its languages, in the shape the contained-object iterator answers in
     */
    private static List<IBmObject> containedLanguagesOf(Configuration configuration)
    {
        // The generated Language does not extend IBmObject statically; the cast is what the
        // iterator's signature demands and the runtime object satisfies.
        List<IBmObject> contained = new ArrayList<>();
        for (Language language : configuration.getLanguages())
        {
            contained.add((IBmObject)language);
        }
        return contained;
    }

    /**
     * The language the configuration holds is answered by the shared resolver, with the walk
     * running over the Language type itself.
     */
    @Test
    public void aLanguageIsResolvedFromTheConfigurationThatHoldsIt()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Language english = MdClassFactory.eINSTANCE.createLanguage();
        english.setName("English"); //$NON-NLS-1$
        configuration.getLanguages().add(english);

        Recording recording = new Recording();
        IBmTransaction tx = transactionReading(Map.of(), containedLanguagesOf(configuration), recording);

        assertSame("a language is held inside the configuration, so the shared resolver has to"
            + " answer the transaction's own instance of it", //$NON-NLS-1$
            english, EditMetadataTool.resolveReferenceTarget(tx, "Language.English")); //$NON-NLS-1$
        assertTrue("the contained walk has to run for a type the model holds by containment", //$NON-NLS-1$
            recording.containedWalkStarted);
        assertSame("the walk asks for the type the languages collection holds, not for every"
            + " object in the transaction", //$NON-NLS-1$
            MdClassPackage.eINSTANCE.getLanguage(), recording.containedWalkType);
    }

    /**
     * A Cyrillic name, and a differently-cased one, do not turn a present language into an
     * absent one.
     */
    @Test
    public void aCyrillicOrDifferentlyCasedNameIsResolved()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Language russian = MdClassFactory.eINSTANCE.createLanguage();
        russian.setName("Русский"); //$NON-NLS-1$
        configuration.getLanguages().add(russian);
        Language english = MdClassFactory.eINSTANCE.createLanguage();
        english.setName("English"); //$NON-NLS-1$
        configuration.getLanguages().add(english);

        IBmTransaction tx = transactionReading(Map.of(), containedLanguagesOf(configuration),
            new Recording());

        assertSame("the object's own name is data, and a Cyrillic name is the norm", //$NON-NLS-1$
            russian, EditMetadataTool.resolveReferenceTarget(tx, "Language.Русский")); //$NON-NLS-1$
        assertSame("the name segment matches without regard to case", //$NON-NLS-1$
            russian, EditMetadataTool.resolveReferenceTarget(tx, "Language.РУССКИЙ")); //$NON-NLS-1$
        assertSame(english, EditMetadataTool.resolveReferenceTarget(tx, "Language.english")); //$NON-NLS-1$
    }

    /** A language that is not there is the former answer: null, with the walk finding nothing. */
    @Test
    public void aLanguageThatIsNotThereAnswersNull()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Language english = MdClassFactory.eINSTANCE.createLanguage();
        english.setName("English"); //$NON-NLS-1$
        configuration.getLanguages().add(english);

        Recording recording = new Recording();
        IBmTransaction tx = transactionReading(Map.of(), containedLanguagesOf(configuration), recording);

        assertNull(EditMetadataTool.resolveReferenceTarget(tx, "Language.Deutsch")); //$NON-NLS-1$
        assertTrue("the walk runs and comes back empty, it does not skip the type", //$NON-NLS-1$
            recording.containedWalkStarted);
    }

    /** A top object is answered by the top route alone; the contained walk is never started for it. */
    @Test
    public void aTopObjectIsAnsweredWithoutTheContainedWalk()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$

        Recording recording = new Recording();
        IBmTransaction tx = transactionReading(Map.of("Catalog.Товары", catalog), //$NON-NLS-1$
            List.of(), recording);

        assertSame(catalog, EditMetadataTool.resolveReferenceTarget(tx, "Catalog.Товары")); //$NON-NLS-1$
        assertFalse("a hit on the top route costs no object walk", recording.containedWalkStarted); //$NON-NLS-1$
    }

    /**
     * A reference-held kind, an unknown kind and a bare type name keep the former answer and
     * start no walk.
     */
    @Test
    public void aReferenceHeldKindAnUnknownKindAndABareTypeStartNoWalk()
    {
        Recording recording = new Recording();
        IBmTransaction tx = transactionReading(Map.of(), List.of(), recording);

        assertNull("a catalog is held by a plain reference, so a missing one is answered null"
            + " without walking the transaction", //$NON-NLS-1$
            EditMetadataTool.resolveReferenceTarget(tx, "Catalog.НигдеТакогоНет")); //$NON-NLS-1$
        assertNull(EditMetadataTool.resolveReferenceTarget(tx, "НезнакомыйВид.ЧтоУгодно")); //$NON-NLS-1$
        assertNull("a name with no object segment is not a two-segment address", //$NON-NLS-1$
            EditMetadataTool.resolveReferenceTarget(tx, "Language")); //$NON-NLS-1$
        assertFalse(recording.containedWalkStarted);
    }

    /** A null target returns null without accessing the transaction. */
    @Test
    public void aNullTargetDoesNotAccessTheTransaction()
    {
        assertNull(EditMetadataTool.resolveReferenceTarget(null, null));
    }

    /** A child of a top object keeps being resolved by its owner and path, exactly as before. */
    @Test
    public void aChildOfATopObjectIsResolvedByItsPathAsBefore()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$
        CatalogAttribute author = MdClassFactory.eINSTANCE.createCatalogAttribute();
        author.setName("Автор"); //$NON-NLS-1$
        catalog.getAttributes().add(author);

        Recording recording = new Recording();
        IBmTransaction tx = transactionReading(Map.of("Catalog.Товары", catalog), //$NON-NLS-1$
            List.of(), recording);

        assertSame("an attribute is reached through its owner, the route the child lookup"
            + " already took", //$NON-NLS-1$
            author, EditMetadataTool.resolveReferenceTarget(tx, "Catalog.Товары.Attribute.Автор")); //$NON-NLS-1$
        assertFalse("a child address is not a two-segment one and starts no walk", //$NON-NLS-1$
            recording.containedWalkStarted);
    }
}
