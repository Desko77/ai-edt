/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.ConfigurationListProperties.ListMode;
import ru.aiedt.mcp.server.support.ConfigurationListProperties.Outcome;

/**
 * The boundaries of the localized-presentation write that the frozen suite does not reach,
 * run over the whole fixed registry of applicable pairs.
 * <p>
 * The registry is fixed, not collected: two types times seven requested names give fourteen cells,
 * of which ten are applicable (a Catalog carries objectPresentation, extendedObjectPresentation,
 * listPresentation, extendedListPresentation and explanation; an InformationRegister carries
 * recordPresentation, extendedRecordPresentation, listPresentation, extendedListPresentation and
 * explanation) and four are not. Both halves are asserted: a model that drops an applicable
 * property fails, and a model that grows one of the four absent ones fails too, so the suite
 * cannot silently run on less than the registry.
 * </p>
 * <p>
 * Every behavioural test loops over the ten applicable pairs on real objects from
 * {@link MdClassFactory} and drives the same public seam the operation drives:
 * {@link ConfigurationListProperties#apply} with the same argument shapes
 * {@code set_object_property} hands it. What the seam cannot see - the project lookup, the
 * transaction, the response tags - is not claimed here.
 * </p>
 */
public class ALocalizedPresentationBoundaryReconciliationTest
{
    /** The seven property names a caller may ask for. */
    private static final String[] REQUESTED_NAMES = {
        "objectPresentation", //$NON-NLS-1$
        "extendedObjectPresentation", //$NON-NLS-1$
        "listPresentation", //$NON-NLS-1$
        "extendedListPresentation", //$NON-NLS-1$
        "recordPresentation", //$NON-NLS-1$
        "extendedRecordPresentation", //$NON-NLS-1$
        "explanation" //$NON-NLS-1$
    };

    /** The names a Catalog carries as maps of one text per language. */
    private static final String[] ON_CATALOG = {
        "objectPresentation", //$NON-NLS-1$
        "extendedObjectPresentation", //$NON-NLS-1$
        "listPresentation", //$NON-NLS-1$
        "extendedListPresentation", //$NON-NLS-1$
        "explanation" //$NON-NLS-1$
    };

    /** The names an InformationRegister carries as maps of one text per language. */
    private static final String[] ON_REGISTER = {
        "recordPresentation", //$NON-NLS-1$
        "extendedRecordPresentation", //$NON-NLS-1$
        "listPresentation", //$NON-NLS-1$
        "extendedListPresentation", //$NON-NLS-1$
        "explanation" //$NON-NLS-1$
    };

    /** One applicable {@code (typeName, propertyName)} pair of the fixed registry. */
    private static final class Pair
    {
        final String typeName;
        final String propertyName;

        /**
         * Names one applicable metadata property without constructing its owner.
         *
         * @param typeName the metadata type
         * @param propertyName the localized property
         */
        Pair(String typeName, String propertyName)
        {
            this.typeName = typeName;
            this.propertyName = propertyName;
        }

        /**
         * Returns the type and property label used by assertion messages.
         *
         * @return the qualified property label
         */
        @Override
        public String toString()
        {
            return typeName + '.' + propertyName;
        }
    }

    /**
     * The fixed registry of applicable pairs: five on a Catalog, five on an InformationRegister.
     *
     * @return the ten applicable pairs, in registry order
     */
    private static List<Pair> applicablePairs()
    {
        List<Pair> pairs = new ArrayList<>();
        for (String name : ON_CATALOG)
        {
            pairs.add(new Pair("Catalog", name)); //$NON-NLS-1$
        }
        for (String name : ON_REGISTER)
        {
            pairs.add(new Pair("InformationRegister", name)); //$NON-NLS-1$
        }
        return pairs;
    }

    /**
     * Builds a fresh object of a probed type, so no test inherits what another one wrote.
     *
     * @param typeName {@code Catalog} or {@code InformationRegister}
     * @return the new object
     */
    private static EObject newOwner(String typeName)
    {
        return "Catalog".equals(typeName) //$NON-NLS-1$
            ? MdClassFactory.eINSTANCE.createCatalog()
            : MdClassFactory.eINSTANCE.createInformationRegister();
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
     * Whether a feature holds one text per language - a map of language code to text. The entry
     * class must be a {@code java.util.Map.Entry} of two single strings and the held value must
     * really be an {@link EMap}; a list of literals or of keyed objects is not one of these.
     *
     * @param owner the object carrying the feature
     * @param feature the feature being asked about
     * @return true when the feature is a map of language code to text
     */
    private static boolean holdsOneTextPerLanguage(EObject owner, EStructuralFeature feature)
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
            && String.class.equals(value.getEType().getInstanceClass())
            && owner.eGet(feature) instanceof EMap;
    }

    /**
     * Reads the property of a pair as the live map of language code to text it is.
     *
     * @param owner the object carrying the property
     * @param propertyName the property being read
     * @return the live map
     */
    @SuppressWarnings("unchecked")
    private static EMap<String, String> mapOf(EObject owner, String propertyName)
    {
        return (EMap<String, String>)owner.eGet(featureNamed(owner.eClass(), propertyName));
    }

    /**
     * Reads the property of a pair as a plain map, for assertions.
     *
     * @param owner the object carrying the property
     * @param propertyName the property being read
     * @return a copy of the live map as a {@link Map}
     */
    private static Map<String, String> snapshotOf(EObject owner, String propertyName)
    {
        return new LinkedHashMap<>(mapOf(owner, propertyName).map());
    }

    /**
     * Applies the write the operation applies: the same helper, the same property and value.
     *
     * @param pair the applicable pair under test
     * @param owner the object carrying the property
     * @param value the propertyValue argument of the call
     * @param mode the listMode argument of the call
     * @param dryRun the dryRun argument of the call
     * @param defaultLanguageCode the default language the operation hands down
     * @return the outcome of the write
     */
    private static Outcome write(Pair pair, EObject owner, String value, ListMode mode,
        boolean dryRun, String defaultLanguageCode)
    {
        return ConfigurationListProperties.apply(owner, pair.propertyName, value, mode, dryRun,
            defaultLanguageCode);
    }

    /**
     * The registry is exactly the ten applicable pairs and the four remaining cells are absent:
     * an applicable name resolves to a feature that really is a map of one text per language, and
     * a name a type does not carry resolves to nothing, is not list-shaped and is refused by the
     * write with "not a property".
     */
    @Test
    public void theRegistryIsExactlyTenApplicablePairsAndFourAbsentCells()
    {
        List<Pair> pairs = applicablePairs();
        assertEquals("the fixed registry holds ten applicable pairs", 10, pairs.size()); //$NON-NLS-1$
        for (Pair pair : pairs)
        {
            EObject owner = newOwner(pair.typeName);
            EStructuralFeature feature = featureNamed(owner.eClass(), pair.propertyName);
            assertNotNull(pair + " must be carried by the live model", feature); //$NON-NLS-1$
            assertTrue(pair + " must be a map of one text per language", //$NON-NLS-1$
                holdsOneTextPerLanguage(owner, feature));
            assertTrue(pair + " must be list-shaped for the dispatch", //$NON-NLS-1$
                ConfigurationListProperties.isListShaped(owner, pair.propertyName));
        }
        int absent = 0;
        for (String typeName : new String[] { "Catalog", "InformationRegister" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            List<String> carried = new ArrayList<>();
            for (Pair pair : pairs)
            {
                if (pair.typeName.equals(typeName))
                {
                    carried.add(pair.propertyName);
                }
            }
            for (String requested : REQUESTED_NAMES)
            {
                if (carried.contains(requested))
                {
                    continue;
                }
                absent++;
                EObject owner = newOwner(typeName);
                assertNull(typeName + '.' + requested + " must not be carried by the live model", //$NON-NLS-1$
                    featureNamed(owner.eClass(), requested));
                assertFalse(typeName + '.' + requested + " must not be list-shaped", //$NON-NLS-1$
                    ConfigurationListProperties.isListShaped(owner, requested));
                Outcome refused = ConfigurationListProperties.apply(owner, requested, "text", //$NON-NLS-1$
                    ListMode.REPLACE, false, "ru"); //$NON-NLS-1$
                assertFalse("an absent property is refused, not written", refused.ok()); //$NON-NLS-1$
                assertTrue(refused.refusal, refused.refusal.contains("is not a property")); //$NON-NLS-1$
            }
        }
        assertEquals("fourteen cells minus ten applicable leaves four absent", 4, absent); //$NON-NLS-1$
    }

    /**
     * A plain word that would parse as a JSON scalar - {@code 123}, {@code true} - is stored as
     * the text it reads as, under the default language, and every other language keeps its entry:
     * only a value opening like an object or an array is read as JSON.
     */
    @Test
    public void aJsonLookingScalarWordIsPlainTextAndKeepsTheOtherLanguages()
    {
        for (Pair pair : applicablePairs())
        {
            for (String word : new String[] { "123", "true" }) //$NON-NLS-1$ //$NON-NLS-2$
            {
                EObject owner = newOwner(pair.typeName);
                mapOf(owner, pair.propertyName).put("en", "Kept"); //$NON-NLS-1$ //$NON-NLS-2$

                Outcome written = write(pair, owner, word, ListMode.REPLACE, false, "ru"); //$NON-NLS-1$

                assertTrue(pair + " must take the plain word " + word + ": " + written.refusal, //$NON-NLS-1$ //$NON-NLS-2$
                    written.ok());
                Map<String, String> after = snapshotOf(owner, pair.propertyName);
                assertEquals(word, after.get("ru")); //$NON-NLS-1$
                assertEquals("a language the call did not name keeps its entry", //$NON-NLS-1$
                    "Kept", after.get("en")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(2, after.size());
            }
        }
    }

    /**
     * A missing or empty default language falls back to {@code ru}: the operation asks the
     * configuration and hands the answer down, but a caller of the seam that answers with nothing
     * still gets a stored entry, under the fallback code.
     */
    @Test
    public void aMissingOrEmptyDefaultLanguageFallsBackToRu()
    {
        for (Pair pair : applicablePairs())
        {
            for (String missing : new String[] { null, "" }) //$NON-NLS-1$
            {
                EObject owner = newOwner(pair.typeName);

                Outcome written = write(pair, owner, "Text", ListMode.REPLACE, false, missing); //$NON-NLS-1$

                assertTrue(pair + " with no default language must still write: " + written.refusal, //$NON-NLS-1$
                    written.ok());
                Map<String, String> after = snapshotOf(owner, pair.propertyName);
                assertEquals("Text", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(1, after.size());
            }
        }
    }

    /**
     * Language objects accept the extended syntax recognized by the synonym path's Gson parser.
     * Explicit language keys determine the written entries independently of the default language.
     */
    @Test
    public void aLanguageObjectKeepsTheExistingParserSyntax()
    {
        for (Pair pair : applicablePairs())
        {
            for (String input : new String[] { "{ru:Text}", "{'ru':'Text'}" }) //$NON-NLS-1$ //$NON-NLS-2$
            {
                EObject owner = newOwner(pair.typeName);

                Outcome written = write(pair, owner, input, ListMode.REPLACE, false, "en"); //$NON-NLS-1$

                assertTrue(pair + ": " + written.refusal, written.ok()); //$NON-NLS-1$
                Map<String, String> after = snapshotOf(owner, pair.propertyName);
                assertEquals("Text", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(1, after.size());
            }
        }
    }

    /**
     * A JSON null as a language's value becomes the empty text, the way the synonym object reader
     * treats it: the entry exists afterwards and holds nothing.
     */
    @Test
    public void aJsonNullLanguageValueBecomesEmptyText()
    {
        for (Pair pair : applicablePairs())
        {
            EObject owner = newOwner(pair.typeName);

            Outcome written = write(pair, owner,
                "{\"ru\":\"Text\",\"en\":null}", ListMode.REPLACE, false, "ru"); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue(pair + " must take a null language value: " + written.refusal, //$NON-NLS-1$
                written.ok());
            Map<String, String> after = snapshotOf(owner, pair.propertyName);
            assertEquals(2, after.size());
            assertEquals("Text", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("the nulled language keeps its entry", after.containsKey("en")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("", after.get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * A whitespace-only value clears the map, the way a missing and an empty value do: trimming
     * decides before anything is written.
     */
    @Test
    public void aWhitespaceOnlyValueClearsTheMap()
    {
        for (Pair pair : applicablePairs())
        {
            EObject owner = newOwner(pair.typeName);
            mapOf(owner, pair.propertyName).put("ru", "Old"); //$NON-NLS-1$ //$NON-NLS-2$
            mapOf(owner, pair.propertyName).put("en", "Kept"); //$NON-NLS-1$ //$NON-NLS-2$

            Outcome cleared = write(pair, owner, "   ", ListMode.REPLACE, false, "ru"); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue(pair + " must clear on a blank value: " + cleared.refusal, cleared.ok()); //$NON-NLS-1$
            assertTrue(pair + " must be empty afterwards", //$NON-NLS-1$
                snapshotOf(owner, pair.propertyName).isEmpty());
        }
    }

    /**
     * A boolean or a nested object as a language's value is refused whole, and so is a blank
     * language key standing after a valid entry: the refusal leaves the map exactly as it was,
     * valid first entries included.
     */
    @Test
    public void nonStringValuesAndBlankKeysAreRefusedAtomically()
    {
        String[] refusedValues = {
            "{\"ru\":\"New\",\"en\":true}", //$NON-NLS-1$
            "{\"ru\":{\"x\":\"y\"}}", //$NON-NLS-1$
            "{\"ru\":\"New\",\"  \":\"x\"}", //$NON-NLS-1$
            "{\"ru\":\"New\",\"\":\"x\"}" //$NON-NLS-1$
        };
        for (Pair pair : applicablePairs())
        {
            for (String bad : refusedValues)
            {
                EObject owner = newOwner(pair.typeName);
                mapOf(owner, pair.propertyName).put("ru", "Old"); //$NON-NLS-1$ //$NON-NLS-2$
                mapOf(owner, pair.propertyName).put("en", "Kept"); //$NON-NLS-1$ //$NON-NLS-2$

                Outcome out = write(pair, owner, bad, ListMode.REPLACE, false, "ru"); //$NON-NLS-1$

                assertFalse(pair + " must refuse " + bad, out.ok()); //$NON-NLS-1$
                assertNotNull(pair + " must say why", out.refusal); //$NON-NLS-1$
                Map<String, String> after = snapshotOf(owner, pair.propertyName);
                assertEquals("a refusal changes nothing", 2, after.size()); //$NON-NLS-1$
                assertEquals("Old", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals("Kept", after.get("en")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /**
     * The list modes add and remove are refused for a map of one text per language, live and in
     * a dry run alike, and the refusal touches nothing: a map has no entries to add or remove one
     * at a time.
     */
    @Test
    public void addAndRemoveAreRefusedLiveAndInDryRunWithoutChanges()
    {
        for (Pair pair : applicablePairs())
        {
            for (ListMode mode : new ListMode[] { ListMode.ADD, ListMode.REMOVE })
            {
                for (boolean dryRun : new boolean[] { false, true })
                {
                    EObject owner = newOwner(pair.typeName);
                    mapOf(owner, pair.propertyName).put("ru", "Old"); //$NON-NLS-1$ //$NON-NLS-2$

                    Outcome out = write(pair, owner, "Text", mode, dryRun, "ru"); //$NON-NLS-1$

                    assertFalse(pair + " must refuse " + mode + " (dryRun=" + dryRun + ')', //$NON-NLS-1$ //$NON-NLS-2$
                        out.ok());
                    assertNotNull(pair + " must say why", out.refusal); //$NON-NLS-1$
                    Map<String, String> after = snapshotOf(owner, pair.propertyName);
                    assertEquals("a refused mode changes nothing", 1, after.size()); //$NON-NLS-1$
                    assertEquals("Old", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
    }

    /**
     * A dry-run clear succeeds, empties nothing and previews the empty object: the caller sees
     * what would have been - no text in any language - while the source keeps every entry.
     */
    @Test
    public void aDryRunClearPreservesTheSourceAndPreviewsAnEmptyObject()
    {
        for (Pair pair : applicablePairs())
        {
            EObject owner = newOwner(pair.typeName);
            mapOf(owner, pair.propertyName).put("ru", "Old"); //$NON-NLS-1$ //$NON-NLS-2$
            mapOf(owner, pair.propertyName).put("en", "Kept"); //$NON-NLS-1$ //$NON-NLS-2$

            Outcome preview = write(pair, owner, null, ListMode.CLEAR, true, "ru"); //$NON-NLS-1$

            assertTrue(pair + " must preview a clear: " + preview.refusal, preview.ok()); //$NON-NLS-1$
            assertNotNull(pair + " must preview a value", preview.value); //$NON-NLS-1$
            assertTrue("the preview is the empty object: " + preview.value, //$NON-NLS-1$
                preview.value.isJsonObject()
                    && preview.value.getAsJsonObject().entrySet().isEmpty());
            Map<String, String> after = snapshotOf(owner, pair.propertyName);
            assertEquals("a dry run writes nothing", 2, after.size()); //$NON-NLS-1$
            assertEquals("Old", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("Kept", after.get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * A dry run of a plain string and of a language object succeeds, writes nothing, and hands
     * back exactly the map the live write would have produced - not merely a non-null value.
     */
    @Test
    public void aDryRunPreviewsTheExactMapAndWritesNothing()
    {
        for (Pair pair : applicablePairs())
        {
            EObject owner = newOwner(pair.typeName);
            mapOf(owner, pair.propertyName).put("ru", "Old"); //$NON-NLS-1$ //$NON-NLS-2$
            mapOf(owner, pair.propertyName).put("en", "Kept"); //$NON-NLS-1$ //$NON-NLS-2$

            Outcome plain = write(pair, owner, "New", ListMode.REPLACE, true, "ru"); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue(pair + " must preview a plain string: " + plain.refusal, plain.ok()); //$NON-NLS-1$
            JsonObject expectedPlain = JsonParser
                .parseString("{\"ru\":\"New\",\"en\":\"Kept\"}").getAsJsonObject(); //$NON-NLS-1$
            assertEquals("the preview is exactly the map the write would leave", //$NON-NLS-1$
                expectedPlain, plain.value);

            Outcome object = write(pair, owner,
                "{\"ru\":\"Other\",\"kz\":\"Basqa\"}", ListMode.REPLACE, true, "ru"); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue(pair + " must preview a language object: " + object.refusal, object.ok()); //$NON-NLS-1$
            JsonObject expectedObject = JsonParser
                .parseString("{\"ru\":\"Other\",\"kz\":\"Basqa\"}").getAsJsonObject(); //$NON-NLS-1$
            assertEquals("the preview replaces the whole map, it does not merge", //$NON-NLS-1$
                expectedObject, object.value);

            Map<String, String> after = snapshotOf(owner, pair.propertyName);
            assertEquals("two dry runs wrote nothing", 2, after.size()); //$NON-NLS-1$
            assertEquals("Old", after.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("Kept", after.get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * A dry run leaves every neighbour alone as well: the object's UUID, name and synonym, and an
     * unrelated localized property of the same object, keep exactly what they had.
     */
    @Test
    public void aDryRunPreservesIdentityAndUnrelatedLocalizedProperties()
    {
        for (Pair pair : applicablePairs())
        {
            String bystander = "explanation".equals(pair.propertyName) //$NON-NLS-1$
                ? "listPresentation" : "explanation"; //$NON-NLS-1$ //$NON-NLS-2$
            EObject owner = newOwner(pair.typeName);
            MdObject md = (MdObject)owner;
            md.setName("Goods"); //$NON-NLS-1$
            UUID uuid = UUID.randomUUID();
            md.setUuid(uuid);
            md.getSynonym().put("ru", "SynonymText"); //$NON-NLS-1$ //$NON-NLS-2$
            mapOf(owner, bystander).put("ru", "NeighbourText"); //$NON-NLS-1$ //$NON-NLS-2$
            mapOf(owner, pair.propertyName).put("ru", "Old"); //$NON-NLS-1$ //$NON-NLS-2$

            Outcome preview = write(pair, owner, "{\"ru\":\"New\"}", ListMode.REPLACE, true, "ru"); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue(pair + " must preview: " + preview.refusal, preview.ok()); //$NON-NLS-1$
            assertSame("the UUID stays as it was", uuid, md.getUuid()); //$NON-NLS-1$
            assertEquals("Goods", md.getName()); //$NON-NLS-1$
            assertEquals("SynonymText", md.getSynonym().map().get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(1, md.getSynonym().map().size());
            assertEquals("an unrelated localized property keeps its text", //$NON-NLS-1$
                "NeighbourText", snapshotOf(owner, bystander).get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("Old", snapshotOf(owner, pair.propertyName).get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * The shape detector does not take a list of objects for a language map: the attribute lists
     * of both probed types are many-valued and class-typed, but their entries are not string map
     * entries, so a plain text - which a real language map would accept - is refused.
     */
    @Test
    public void aListOfObjectsIsNotConfusedWithALanguageMap()
    {
        for (String typeName : new String[] { "Catalog", "InformationRegister" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            EObject owner = newOwner(typeName);
            EStructuralFeature attributes = featureNamed(owner.eClass(), "attributes"); //$NON-NLS-1$
            assertNotNull(typeName + " must carry its attribute list", attributes); //$NON-NLS-1$
            assertTrue(attributes.isMany());
            assertFalse(typeName + ".attributes is not a map of one text per language", //$NON-NLS-1$
                holdsOneTextPerLanguage(owner, attributes));

            Outcome out = ConfigurationListProperties.apply(owner, "attributes", "Text", //$NON-NLS-1$ //$NON-NLS-2$
                ListMode.REPLACE, false, "ru"); //$NON-NLS-1$

            assertFalse(typeName + ".attributes must refuse a plain text", out.ok()); //$NON-NLS-1$
        }
    }
}
