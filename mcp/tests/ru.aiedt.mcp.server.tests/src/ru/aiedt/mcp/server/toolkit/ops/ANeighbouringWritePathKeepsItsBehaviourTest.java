/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

import ru.aiedt.mcp.server.support.ALocalizedPresentationIsWrittenPerLanguageTest;
import ru.aiedt.mcp.server.support.ConfigurationListProperties;
import ru.aiedt.mcp.server.support.ConfigurationListProperties.ListMode;
import ru.aiedt.mcp.server.support.ConfigurationListProperties.Outcome;

/**
 * The write paths that share the dispatch with the localized presentations keep their behaviour
 * while those learn to be written.
 * <p>
 * Everything in this class is a control: it passes on the current behaviour and must pass the
 * same way afterwards. The ordinary list of literals and the composite list of the
 * configuration root are the two list shapes the same {@link ConfigurationListProperties#apply}
 * call already serves, and the synonym write is the neighbour the presentations are being
 * brought in line with - the operation's own synonym branch uses it, and so does the creation
 * path. The premise of the presentation battery is checked here as well, so one run answers
 * which names the live model carries on which type.
 * </p>
 */
public class ANeighbouringWritePathKeepsItsBehaviourTest
{
    /**
     * The premise of the presentation battery, checked on the live model and answered with the
     * full registry: the two probed types really carry the requested presentations as maps of
     * one text per language, and the four applicable properties the refusal was observed on are
     * there.
     */
    @Test
    public void theProbedTypesCarryTheApplicablePresentations()
    {
        List<Object[]> pairs = ALocalizedPresentationIsWrittenPerLanguageTest
            .applicablePropertyNamePairs();
        Set<String> names = new LinkedHashSet<>();
        StringBuilder registry = new StringBuilder();
        for (Object[] pair : pairs)
        {
            if (registry.length() > 0)
            {
                registry.append("; "); //$NON-NLS-1$
            }
            registry.append(pair[0]).append('.').append(pair[1]);
            names.add((String)pair[1]);
        }
        assertTrue("Catalog carries none of the requested presentations: " + registry, //$NON-NLS-1$
            countOf(pairs, "Catalog") > 0); //$NON-NLS-1$
        assertTrue("InformationRegister carries none of the requested presentations: " + registry, //$NON-NLS-1$
            countOf(pairs, "InformationRegister") > 0); //$NON-NLS-1$
        assertTrue("four applicable properties were reported; the live model carries " + names //$NON-NLS-1$
            + " (" + registry + ")", names.size() >= 4); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The ordinary list of the configuration root still takes its literals as a JSON array and
     * nothing else: an array writes them in order, a bare word is refused, and a clear empties
     * the list. A fix for the localized maps must not widen or narrow this.
     */
    @Test
    public void anOrdinaryEnumListStillTakesItsLiterals()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        EStructuralFeature usePurposes = featureNamed(configuration.eClass(), "usePurposes"); //$NON-NLS-1$
        assertNotNull("the configuration root must carry the usePurposes list", usePurposes); //$NON-NLS-1$
        EEnum type = (EEnum)usePurposes.getEType();
        assertTrue("the list needs at least two literals to tell an order from a set", //$NON-NLS-1$
            type.getELiterals().size() >= 2);
        String first = type.getELiterals().get(0).getName();
        String second = type.getELiterals().get(1).getName();

        Outcome written = ConfigurationListProperties.apply(configuration, usePurposes.getName(),
            "[\"" + first + "\",\"" + second + "\"]", ListMode.REPLACE, false, "ru"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue(written.refusal, written.ok());
        List<Object> held = values(configuration, usePurposes);
        assertEquals(2, held.size());
        assertEquals(first, String.valueOf(held.get(0)));
        assertEquals(second, String.valueOf(held.get(1)));

        assertFalse("a bare literal name is not a JSON array and stays refused", //$NON-NLS-1$
            ConfigurationListProperties.apply(configuration, usePurposes.getName(), first,
                ListMode.REPLACE, false, "ru").ok()); //$NON-NLS-1$

        Outcome cleared = ConfigurationListProperties.apply(configuration, usePurposes.getName(),
            null, ListMode.CLEAR, false, "ru"); //$NON-NLS-1$
        assertTrue(cleared.refusal, cleared.ok());
        assertTrue(values(configuration, usePurposes).isEmpty());
    }

    /**
     * The composite list of the configuration root still takes whole entries: an object naming
     * the entry's key and its flag is written by a replace, and a clear empties the list. The
     * fields are read off the live model, so the control does not depend on how a release
     * spells them.
     */
    @Test
    public void aCompositeListStillTakesWholeEntries()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        EStructuralFeature permissions = featureNamed(configuration.eClass(),
            "requiredMobileApplicationPermissions8315"); //$NON-NLS-1$
        assertNotNull("the configuration root must carry the 8.3.15 permissions list", permissions); //$NON-NLS-1$
        EClass entry = (EClass)permissions.getEType();
        EStructuralFeature key = identityOf(entry);
        assertNotNull("the entry must carry the key the write identifies it by", key); //$NON-NLS-1$
        EStructuralFeature flag = booleanFieldOf(entry);
        assertNotNull("the entry must carry a boolean field", flag); //$NON-NLS-1$
        String literal = ((EEnum)key.getEType()).getELiterals().get(0).getName();

        Outcome written = ConfigurationListProperties.apply(configuration, permissions.getName(),
            "[{\"" + key.getName() + "\":\"" + literal + "\",\"" + flag.getName() + "\":true}]", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ListMode.REPLACE, false, "ru"); //$NON-NLS-1$

        assertTrue(written.refusal, written.ok());
        List<Object> held = values(configuration, permissions);
        assertEquals(1, held.size());
        EObject stored = (EObject)held.get(0);
        assertEquals(Boolean.TRUE,
            stored.eGet(featureNamed(stored.eClass(), flag.getName())));

        Outcome cleared = ConfigurationListProperties.apply(configuration, permissions.getName(),
            null, ListMode.CLEAR, false, "ru"); //$NON-NLS-1$
        assertTrue(cleared.refusal, cleared.ok());
        assertTrue(values(configuration, permissions).isEmpty());
    }

    /**
     * The synonym write keeps its contract on its object form: an object of language codes
     * replaces the whole map, and a value that is not a string is refused with the map
     * untouched.
     */
    @Test
    public void theSynonymWriteKeepsItsObjectContract()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();

        EditMetadataTool.SynonymResult written = EditMetadataTool.applyMdObjectSynonym(catalog,
            "{\"en\":\"Goods\",\"ru\":\"Номенклатура\"}", null, null); //$NON-NLS-1$

        assertTrue("the object form must apply: " + written.error, written.applied); //$NON-NLS-1$
        assertEquals(2, catalog.getSynonym().map().size());
        assertEquals("Номенклатура", catalog.getSynonym().map().get("ru")); //$NON-NLS-1$ //$NON-NLS-2$

        EditMetadataTool.SynonymResult replaced = EditMetadataTool.applyMdObjectSynonym(catalog,
            "{\"kz\":\"Тауарлар\"}", null, null); //$NON-NLS-1$

        assertTrue(replaced.applied);
        Map<String, String> afterReplace = catalog.getSynonym().map();
        assertEquals(1, afterReplace.size());
        assertEquals("Тауарлар", afterReplace.get("kz")); //$NON-NLS-1$ //$NON-NLS-2$

        EditMetadataTool.SynonymResult refused = EditMetadataTool.applyMdObjectSynonym(catalog,
            "{\"ru\":\"Новое\",\"en\":123}", null, null); //$NON-NLS-1$

        assertFalse("a value that is not a string must be refused", refused.applied); //$NON-NLS-1$
        Map<String, String> afterRefusal = catalog.getSynonym().map();
        assertEquals("the refused write must change nothing", 1, afterRefusal.size()); //$NON-NLS-1$
        assertEquals("Тауарлар", afterRefusal.get("kz")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A plain synonym string lands under the default language and keeps the other languages -
     * the exact behaviour the presentations are being brought in line with. This path asks the
     * running plugin for its configuration provider, which the headless test runtime starts
     * together with the bundle itself.
     */
    @Test
    public void aPlainSynonymStringGoesUnderTheDefaultLanguage()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.getSynonym().put("kz", "Тауарлар"); //$NON-NLS-1$ //$NON-NLS-2$

        EditMetadataTool.SynonymResult plain = EditMetadataTool.applyMdObjectSynonym(catalog,
            "Товары", null, null); //$NON-NLS-1$

        assertTrue("the plain form must apply: " + plain.error, plain.applied); //$NON-NLS-1$
        Map<String, String> after = catalog.getSynonym().map();
        assertEquals("Товары", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the language the call did not name keeps its entry", //$NON-NLS-1$
            "Тауарлар", after.get("kz")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, after.size());
    }

    /**
     * Counts the pairs that belong to one probed type.
     *
     * @param pairs the collected {@code (typeName, propertyName)} pairs
     * @param typeName the type being counted
     * @return how many pairs belong to that type
     */
    private static int countOf(List<Object[]> pairs, String typeName)
    {
        int count = 0;
        for (Object[] pair : pairs)
        {
            if (typeName.equals(pair[0]))
            {
                count++;
            }
        }
        return count;
    }

    /**
     * Finds a feature by the same tolerance the write path applies: the exact name first, then
     * any casing of it.
     *
     * @param ownerClass the class being asked
     * @param name the property name a caller would pass
     * @return the feature, or {@code null} when the class does not carry it
     */
    private static EStructuralFeature featureNamed(EClass ownerClass, String name)
    {
        EStructuralFeature exact = ownerClass.getEStructuralFeature(name);
        if (exact != null)
        {
            return exact;
        }
        for (EStructuralFeature feature : ownerClass.getEAllStructuralFeatures())
        {
            if (feature.getName().equalsIgnoreCase(name))
            {
                return feature;
            }
        }
        return null;
    }

    /**
     * The field that tells one entry of a composite list from another: the first single-valued
     * field whose type is an enumeration, which is how the write path itself identifies an
     * entry.
     *
     * @param entry the entry class of the list
     * @return the key field, or {@code null} when the entry has none
     */
    private static EStructuralFeature identityOf(EClass entry)
    {
        for (EStructuralFeature field : entry.getEAllStructuralFeatures())
        {
            if (!field.isMany() && field.getEType() instanceof EEnum)
            {
                return field;
            }
        }
        return null;
    }

    /**
     * The first single-valued boolean field of an entry, wherever a release puts it.
     *
     * @param entry the entry class of the list
     * @return the boolean field, or {@code null} when the entry has none
     */
    private static EStructuralFeature booleanFieldOf(EClass entry)
    {
        for (EStructuralFeature field : entry.getEAllStructuralFeatures())
        {
            if (!field.isMany()
                && (Boolean.class.equals(field.getEType().getInstanceClass())
                    || Boolean.TYPE.equals(field.getEType().getInstanceClass())))
            {
                return field;
            }
        }
        return null;
    }

    /**
     * Reads a many-valued feature as the live list it is.
     *
     * @param owner the object carrying the feature
     * @param feature the feature being read
     * @return the live list of held values
     */
    @SuppressWarnings("unchecked")
    private static List<Object> values(EObject owner, EStructuralFeature feature)
    {
        return (List<Object>)owner.eGet(feature);
    }
}
