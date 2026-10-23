/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.support.ConfigurationListProperties.ListMode;
import ru.aiedt.mcp.server.support.ConfigurationListProperties.Outcome;

/**
 * A localized presentation of an existing object is written per language, the way a synonym is.
 * <p>
 * The object write of {@code edit_metadata} refuses these properties today: a presentation is a
 * map of language code to text, the map is a many-valued feature, and the operation hands every
 * many-valued feature to {@link ConfigurationListProperties#apply}, which answers that the
 * property takes a JSON array and then that a map entry has no single value telling one entry
 * from another. The dispatch through {@link ConfigurationListProperties#isListShaped} and
 * {@link ConfigurationListProperties#apply} is the executable part of that path - the operation
 * calls exactly these with exactly the arguments used here - so this suite drives that seam on
 * real generated metadata objects and states the contract the write owes a caller.
 * </p>
 * <p>
 * The contract is the synonym contract, because a presentation is the same kind of map a synonym
 * is: a plain string writes the default language entry and leaves the other languages alone, a
 * JSON object of language code to text replaces the whole map, an empty value clears it, and a
 * value that is not a string is refused before anything changes. What the seam cannot see - the
 * project lookup, the transaction a dry run rolls back, the tag the operation adds to its
 * answer - stays for a check of the operation over a live project.
 * </p>
 * <p>
 * Which of the requested names a metadata type actually carries differs from type to type, so
 * the pairs this runs on are collected from the live model: a name belongs here when the object
 * carries it as a map of one text per language. The collected pairs are the registry of what is
 * applicable, and the collection itself is asserted, so a model that carries nothing fails
 * loudly instead of running nothing.
 * </p>
 */
@RunWith(Parameterized.class)
public class ALocalizedPresentationIsWrittenPerLanguageTest
{
    /**
     * The presentation names a caller asks for. Not every type carries every one of them; which
     * apply is read off the live model, not fixed here.
     */
    private static final String[] REQUESTED_PRESENTATION_PROPERTIES = {
        "objectPresentation", //$NON-NLS-1$
        "extendedObjectPresentation", //$NON-NLS-1$
        "listPresentation", //$NON-NLS-1$
        "extendedListPresentation", //$NON-NLS-1$
        "recordPresentation", //$NON-NLS-1$
        "extendedRecordPresentation", //$NON-NLS-1$
        "explanation" //$NON-NLS-1$
    };

    /** The type whose property is under test, {@code Catalog} or {@code InformationRegister}. */
    private final String typeName;

    /** The applicable property name under test. */
    private final String propertyName;

    /**
     * Carries one applicable property of one probed type.
     *
     * @param typeName the probed type's name
     * @param propertyName the applicable property name
     */
    public ALocalizedPresentationIsWrittenPerLanguageTest(String typeName, String propertyName)
    {
        this.typeName = typeName;
        this.propertyName = propertyName;
    }

    /**
     * Collects the requested names the probed types carry as maps of one text per language, and
     * asserts the collection is not empty: the refusal this suite is about was observed on both
     * types and named four applicable properties, and a model that answers with nothing means
     * the collection is broken, not that there is nothing to test.
     *
     * @return one {@code (typeName, propertyName)} pair per applicable property
     */
    @Parameterized.Parameters(name = "{0}.{1}")
    public static List<Object[]> applicablePropertyNamePairs()
    {
        List<Object[]> pairs = new ArrayList<>();
        int onCatalog = collectInto(MdClassFactory.eINSTANCE.createCatalog(), "Catalog", pairs); //$NON-NLS-1$
        int onRegister = collectInto(MdClassFactory.eINSTANCE.createInformationRegister(),
            "InformationRegister", pairs); //$NON-NLS-1$
        Set<String> names = new LinkedHashSet<>();
        for (Object[] pair : pairs)
        {
            names.add((String)pair[1]);
        }
        assertTrue("the refusal was observed on existing Catalog objects, so the live model must " //$NON-NLS-1$
            + "carry at least one of the requested presentations", onCatalog > 0); //$NON-NLS-1$
        assertTrue("the refusal was observed on existing InformationRegister objects, so the " //$NON-NLS-1$
            + "live model must carry at least one of the requested presentations", //$NON-NLS-1$
            onRegister > 0);
        assertTrue("four applicable properties were reported; the live model carries " + names, //$NON-NLS-1$
            names.size() >= 4);
        return pairs;
    }

    /**
     * Adds one pair for every requested name the sample carries as a map of one text per
     * language.
     *
     * @param sample a freshly created object of the type being probed
     * @param typeName the type's name, for the test name and the registry
     * @param pairs where the found pairs are added
     * @return how many of the requested names this type carries
     */
    private static int collectInto(EObject sample, String typeName, List<Object[]> pairs)
    {
        int found = 0;
        for (String requested : REQUESTED_PRESENTATION_PROPERTIES)
        {
            EStructuralFeature feature = featureNamed(sample.eClass(), requested);
            if (feature != null && holdsOneTextPerLanguage(feature))
            {
                pairs.add(new Object[] { typeName, requested });
                found++;
            }
        }
        return found;
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
     * Whether a feature holds one text per language - a map of language code to text, the shape
     * a synonym has. The entry class must be a {@code java.util.Map.Entry} of two strings,
     * which is what the localized maps of the metadata model are built of; a scalar, a list of
     * literals or a list of keyed objects is not one of these.
     *
     * @param feature the feature being asked about
     * @return true when the feature is a map of language code to text
     */
    private static boolean holdsOneTextPerLanguage(EStructuralFeature feature)
    {
        if (!feature.isMany() || !(feature.getEType() instanceof EClass))
        {
            return false;
        }
        EClass entry = (EClass)feature.getEType();
        if (!"java.util.Map$Entry".equals(entry.getInstanceClassName())) //$NON-NLS-1$
        {
            return false;
        }
        EStructuralFeature key = featureNamed(entry, "key"); //$NON-NLS-1$
        EStructuralFeature value = featureNamed(entry, "value"); //$NON-NLS-1$
        return key != null && value != null && !key.isMany() && !value.isMany()
            && String.class.equals(key.getEType().getInstanceClass())
            && String.class.equals(value.getEType().getInstanceClass());
    }

    /**
     * Builds a fresh object of the probed type, so every test starts from a model no earlier
     * test has touched.
     *
     * @return a new object carrying the property under test
     */
    private EObject newOwner()
    {
        return "Catalog".equals(typeName) //$NON-NLS-1$
            ? MdClassFactory.eINSTANCE.createCatalog()
            : MdClassFactory.eINSTANCE.createInformationRegister();
    }

    /**
     * Reads the property under test as the live map of language code to text it is.
     *
     * @param owner the object carrying the property
     * @return the live map
     */
    @SuppressWarnings("unchecked")
    private EMap<String, String> mapOf(EObject owner)
    {
        return (EMap<String, String>)owner.eGet(featureNamed(owner.eClass(), propertyName));
    }

    /**
     * Applies the write the operation applies: the same helper, the same property and value, the
     * mode a call that names no listMode arrives with, and the default language of a project
     * whose configuration cannot be asked.
     *
     * @param owner the object carrying the property
     * @param value the propertyValue argument of the call
     * @param mode the listMode argument of the call
     * @param dryRun the dryRun argument of the call
     * @return the outcome of the write
     */
    private Outcome write(EObject owner, String value, ListMode mode, boolean dryRun)
    {
        return ConfigurationListProperties.apply(owner, propertyName, value, mode, dryRun,
            defaultLanguage());
    }

    /**
     * The language a plain text is written under when the caller names none: what the operation
     * hands the write for a project whose configuration answers nothing, which is {@code ru}.
     *
     * @return the default language code
     */
    private static String defaultLanguage()
    {
        return DefaultLanguage.codeFor((IProject)null);
    }

    /**
     * A plain string writes the default language entry and nothing else: the entry under the
     * default language gets the text, every other language keeps what it had. This is what the
     * synonym write does with a plain string, and a presentation is the same kind of map.
     */
    @Test
    public void aPlainTextWritesTheDefaultLanguageAndKeepsTheOtherLanguages()
    {
        EObject owner = newOwner();
        mapOf(owner).put("en", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome written = write(owner, "Номенклатура", ListMode.REPLACE, false); //$NON-NLS-1$

        assertTrue("a plain string must write the default language entry, but was refused: " //$NON-NLS-1$
            + written.refusal, written.ok());
        Map<String, String> after = mapOf(owner).map();
        assertEquals("Номенклатура", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a language the call did not name keeps its entry", "Goods", after.get("en")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(2, after.size());
    }

    /**
     * The language a plain string lands under is the one the caller of the write hands down, not
     * a word the write picks for itself: the operation passes the configuration's default
     * language, and whatever that is, the entry appears under it.
     */
    @Test
    public void aPlainTextLandsUnderTheDefaultLanguageTheCallerNames()
    {
        EObject owner = newOwner();

        Outcome written = ConfigurationListProperties.apply(owner, propertyName, "Items", //$NON-NLS-1$
            ListMode.REPLACE, false, "en"); //$NON-NLS-1$

        assertTrue("the write must honour the default language it is given: " + written.refusal, //$NON-NLS-1$
            written.ok());
        Map<String, String> after = mapOf(owner).map();
        assertEquals("Items", after.get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, after.size());
    }

    /**
     * A JSON object of language code to text replaces the whole map: the languages it names get
     * exactly the text given, and the languages it does not name are gone.
     */
    @Test
    public void aLanguageObjectReplacesTheWholeMap()
    {
        EObject owner = newOwner();
        mapOf(owner).put("ru", "Старое"); //$NON-NLS-1$ //$NON-NLS-2$
        mapOf(owner).put("en", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        mapOf(owner).put("kz", "Тауарлар"); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome written = write(owner,
            "{\"ru\":\"Номенклатура\",\"en\":\"Items\"}", ListMode.REPLACE, false); //$NON-NLS-1$

        assertTrue("an object of language codes must replace the map, but was refused: " //$NON-NLS-1$
            + written.refusal, written.ok());
        Map<String, String> after = mapOf(owner).map();
        assertEquals(2, after.size());
        assertEquals("Номенклатура", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Items", after.get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a language the object does not name is not kept", after.get("kz")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A dry run writes nothing: the map keeps exactly what it had, and the call still succeeds,
     * because the operation reports a preview, not a refusal.
     */
    @Test
    public void aDryRunLeavesTheMapAsItWas()
    {
        EObject owner = newOwner();
        mapOf(owner).put("ru", "Старое"); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome preview = write(owner, "{\"ru\":\"Новое\"}", ListMode.REPLACE, true); //$NON-NLS-1$

        assertTrue("a preview must not be refused: " + preview.refusal, preview.ok()); //$NON-NLS-1$
        Map<String, String> after = mapOf(owner).map();
        assertEquals("Старое", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, after.size());
    }

    /**
     * An empty value clears the map, exactly as it does for a synonym: a value that is not
     * there and a value that is blank both mean no text in any language.
     */
    @Test
    public void anEmptyValueClearsTheMap()
    {
        EObject owner = newOwner();
        mapOf(owner).put("ru", "Старое"); //$NON-NLS-1$ //$NON-NLS-2$
        mapOf(owner).put("en", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome cleared = write(owner, "", ListMode.REPLACE, false); //$NON-NLS-1$

        assertTrue("an empty string must clear the map: " + cleared.refusal, cleared.ok()); //$NON-NLS-1$
        assertTrue(mapOf(owner).map().isEmpty());

        EObject second = newOwner();
        mapOf(second).put("ru", "Старое"); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome clearedToo = write(second, null, ListMode.REPLACE, false);

        assertTrue("a missing value must clear the map as well: " + clearedToo.refusal, //$NON-NLS-1$
            clearedToo.ok());
        assertTrue(mapOf(second).map().isEmpty());
    }

    /**
     * An explicit listMode=clear already empties the map today, and must keep doing so: this is
     * the one input shape the current dispatch answers with success.
     */
    @Test
    public void anExplicitClearStillEmptiesTheMap()
    {
        EObject owner = newOwner();
        mapOf(owner).put("ru", "Старое"); //$NON-NLS-1$ //$NON-NLS-2$
        mapOf(owner).put("en", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome cleared = write(owner, null, ListMode.CLEAR, false);

        assertTrue(cleared.refusal, cleared.ok());
        assertTrue(mapOf(owner).map().isEmpty());
    }

    /**
     * A map value that is not a string is refused whole, before the map is touched - the same
     * all-or-nothing rule the synonym object reader applies.
     */
    @Test
    public void aNonStringValueIsRefusedAndTheMapKeepsWhatItHad()
    {
        EObject owner = newOwner();
        mapOf(owner).put("ru", "Старое"); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome number = write(owner, "{\"ru\":\"Новое\",\"en\":123}", ListMode.REPLACE, false); //$NON-NLS-1$
        Outcome nested = write(owner, "{\"ru\":[\"Новое\"]}", ListMode.REPLACE, false); //$NON-NLS-1$

        assertFalse("a number is not a text", number.ok()); //$NON-NLS-1$
        assertFalse("an array is not a text either", nested.ok()); //$NON-NLS-1$
        Map<String, String> after = mapOf(owner).map();
        assertEquals(1, after.size());
        assertEquals("Старое", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Text that is meant to look like an object but is not JSON is refused and changes nothing,
     * the same way it is for a synonym.
     */
    @Test
    public void textThatIsNotJsonIsRefusedAndTheMapKeepsWhatItHad()
    {
        EObject owner = newOwner();
        mapOf(owner).put("ru", "Старое"); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome out = write(owner, "{\"ru\": \"Новое\"", ListMode.REPLACE, false); //$NON-NLS-1$

        assertFalse("unclosed JSON is not a value", out.ok()); //$NON-NLS-1$
        Map<String, String> after = mapOf(owner).map();
        assertEquals(1, after.size());
        assertEquals("Старое", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An object that names no language is refused, as it is for a synonym: it declares a change
     * that has no content.
     */
    @Test
    public void anObjectThatNamesNoLanguageIsRefused()
    {
        EObject owner = newOwner();
        mapOf(owner).put("ru", "Старое"); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome out = write(owner, "{}", ListMode.REPLACE, false); //$NON-NLS-1$

        assertFalse("an empty object names no language", out.ok()); //$NON-NLS-1$
        Map<String, String> after = mapOf(owner).map();
        assertEquals(1, after.size());
        assertEquals("Старое", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A JSON array stays a refusal: the contract of a localized map is a plain string or an
     * object of language codes, and an array of map entries has no single value telling one
     * entry from another - which is the refusal the current dispatch already answers with.
     */
    @Test
    public void anArrayOfEntriesStaysRefusedAndChangesNothing()
    {
        EObject owner = newOwner();
        mapOf(owner).put("ru", "Старое"); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome out = write(owner, "[{\"key\":\"ru\",\"value\":\"Новое\"}]", ListMode.REPLACE, false); //$NON-NLS-1$

        assertFalse("an array is not one of the two forms this property takes", out.ok()); //$NON-NLS-1$
        Map<String, String> after = mapOf(owner).map();
        assertEquals(1, after.size());
        assertEquals("Старое", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Writing a presentation changes that one property and nothing else: the object keeps its
     * UUID, its name and its synonym, and the map is filled where it lives instead of the
     * feature being handed a different list instance.
     */
    @Test
    public void writingChangesThatPropertyAlone()
    {
        EObject owner = newOwner();
        MdObject md = (MdObject)owner;
        md.setName("Товары"); //$NON-NLS-1$
        UUID uuid = UUID.randomUUID();
        md.setUuid(uuid);
        md.getSynonym().put("ru", "Синоним"); //$NON-NLS-1$ //$NON-NLS-2$
        mapOf(owner).put("ru", "Старое"); //$NON-NLS-1$ //$NON-NLS-2$
        Object mapInstance = owner.eGet(featureNamed(owner.eClass(), propertyName));

        Outcome written = write(owner, "{\"ru\":\"Новое\",\"en\":\"Items\"}", ListMode.REPLACE, false); //$NON-NLS-1$

        assertTrue("the write must go through: " + written.refusal, written.ok()); //$NON-NLS-1$
        assertSame("the UUID is the object's identity and stays as it was", uuid, md.getUuid()); //$NON-NLS-1$
        assertEquals("Товары", md.getName()); //$NON-NLS-1$
        assertEquals("an unrelated localized property keeps its text", //$NON-NLS-1$
            "Синоним", md.getSynonym().map().get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, md.getSynonym().map().size());
        Map<String, String> after = mapOf(owner).map();
        assertEquals(2, after.size());
        assertEquals("Новое", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Items", after.get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame("the map is filled in place, not swapped for a new list", mapInstance, //$NON-NLS-1$
            owner.eGet(featureNamed(owner.eClass(), propertyName)));
    }
}
