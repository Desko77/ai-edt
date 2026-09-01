/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.ConfigurationListProperties.ListMode;
import ru.aiedt.mcp.server.support.ConfigurationListProperties.Outcome;

/**
 * Writing the properties of the configuration root whose value is a list.
 * <p>
 * The model here is built for the test and mirrors the shapes the environment's own model carries:
 * a list of enumeration literals, a list of objects each keyed by a literal and carrying a flag and
 * a text per language, and a single object holding two lists of its own. The production code reads
 * those shapes off the model rather than naming any type, which is what lets the test stand in for
 * the real one - and what lets a change in a future release show up as a shape change rather than
 * as a compile error.
 * </p>
 */
public class AListPropertyIsWrittenWholeOrNotAtAllTest
{
    private static final String LANG = "ru"; //$NON-NLS-1$

    private EPackage pack;
    private EClass configurationClass;
    private EEnum purpose;
    private EEnum permission;
    private EObject configuration;

    @Before
    public void buildAModelOfTheSameShape()
    {
        EcoreFactory ef = EcoreFactory.eINSTANCE;
        pack = ef.createEPackage();
        pack.setName("test"); //$NON-NLS-1$
        pack.setNsPrefix("test"); //$NON-NLS-1$
        pack.setNsURI("http://aiedt.test/list-properties"); //$NON-NLS-1$

        purpose = ef.createEEnum();
        purpose.setName("ApplicationUsePurpose"); //$NON-NLS-1$
        addLiteral(purpose, "PersonalComputer", 0); //$NON-NLS-1$
        addLiteral(purpose, "MobileDevice", 1); //$NON-NLS-1$

        permission = ef.createEEnum();
        permission.setName("RequiredMobileApplicationPermissions"); //$NON-NLS-1$
        addLiteral(permission, "Camera", 0); //$NON-NLS-1$
        addLiteral(permission, "Geolocation", 1); //$NON-NLS-1$
        addLiteral(permission, "Microphone", 2); //$NON-NLS-1$

        // An entry of the permissions list: which permission, whether it is used, and what it says
        // to a person in each language.
        EClass requiredPermission = ef.createEClass();
        requiredPermission.setName("RequiredPermission"); //$NON-NLS-1$
        requiredPermission.getEStructuralFeatures().add(attribute("permission", permission, 1));
        requiredPermission.getEStructuralFeatures()
            .add(attribute("use", EcorePackage.Literals.EBOOLEAN, 1)); //$NON-NLS-1$
        requiredPermission.getEStructuralFeatures()
            .add(localisedText(ef, "description", "RequiredPermission")); //$NON-NLS-1$ //$NON-NLS-2$

        EClass functionalityFlag = ef.createEClass();
        functionalityFlag.setName("UsedFunctionalityFlag"); //$NON-NLS-1$
        functionalityFlag.getEStructuralFeatures().add(attribute("functionality", purpose, 1));
        functionalityFlag.getEStructuralFeatures()
            .add(attribute("use", EcorePackage.Literals.EBOOLEAN, 1)); //$NON-NLS-1$

        EClass permissionMessage = ef.createEClass();
        permissionMessage.setName("RequiredPermissionMessage"); //$NON-NLS-1$
        permissionMessage.getEStructuralFeatures().add(attribute("permission", permission, 1));
        permissionMessage.getEStructuralFeatures()
            .add(localisedText(ef, "description", "RequiredPermissionMessage")); //$NON-NLS-1$ //$NON-NLS-2$

        EClass usedFunctionality = ef.createEClass();
        usedFunctionality.setName("UsedFunctionality"); //$NON-NLS-1$
        usedFunctionality.getEStructuralFeatures()
            .add(containment("functionality", functionalityFlag, -1)); //$NON-NLS-1$
        usedFunctionality.getEStructuralFeatures()
            .add(containment("permissionMessage", permissionMessage, -1)); //$NON-NLS-1$

        configurationClass = ef.createEClass();
        configurationClass.setName("Configuration"); //$NON-NLS-1$
        configurationClass.getEStructuralFeatures().add(attribute("usePurposes", purpose, -1)); //$NON-NLS-1$
        configurationClass.getEStructuralFeatures()
            .add(attribute("requiredMobileApplicationPermissions", permission, -1)); //$NON-NLS-1$
        configurationClass.getEStructuralFeatures()
            .add(containment("requiredMobileApplicationPermissions8315", requiredPermission, -1)); //$NON-NLS-1$
        configurationClass.getEStructuralFeatures()
            .add(containment("usedMobileApplicationFunctionalities", usedFunctionality, 1)); //$NON-NLS-1$
        configurationClass.getEStructuralFeatures()
            .add(attribute("name", EcorePackage.Literals.ESTRING, 1)); //$NON-NLS-1$

        pack.getEClassifiers().add(purpose);
        pack.getEClassifiers().add(permission);
        pack.getEClassifiers().add(requiredPermission);
        pack.getEClassifiers().add(functionalityFlag);
        pack.getEClassifiers().add(permissionMessage);
        pack.getEClassifiers().add(usedFunctionality);
        pack.getEClassifiers().add(configurationClass);

        configuration = pack.getEFactoryInstance().create(configurationClass);
    }

    private static void addLiteral(EEnum target, String name, int value)
    {
        EEnumLiteral literal = EcoreFactory.eINSTANCE.createEEnumLiteral();
        literal.setName(name);
        literal.setLiteral(name);
        literal.setValue(value);
        target.getELiterals().add(literal);
    }

    private static EAttribute attribute(String name,
        org.eclipse.emf.ecore.EClassifier type, int upperBound)
    {
        EAttribute a = EcoreFactory.eINSTANCE.createEAttribute();
        a.setName(name);
        a.setEType(type);
        a.setUpperBound(upperBound);
        return a;
    }

    private static EReference containment(String name, EClass type, int upperBound)
    {
        EReference r = EcoreFactory.eINSTANCE.createEReference();
        r.setName(name);
        r.setEType(type);
        r.setUpperBound(upperBound);
        r.setContainment(true);
        return r;
    }

    /**
     * A text carried once per language, the shape a synonym has.
     * <p>
     * The entry class is put into the package as it is built: an entry map asks its entry class for
     * a package to make entries with, and a class outside every package cannot answer.
     * </p>
     */
    private EReference localisedText(EcoreFactory ef, String name, String owner)
    {
        EClass entry = ef.createEClass();
        entry.setName(owner + name + "Entry"); //$NON-NLS-1$
        entry.setInstanceClassName("java.util.Map$Entry"); //$NON-NLS-1$
        entry.getEStructuralFeatures().add(attribute("key", EcorePackage.Literals.ESTRING, 1)); //$NON-NLS-1$
        entry.getEStructuralFeatures().add(attribute("value", EcorePackage.Literals.ESTRING, 1)); //$NON-NLS-1$
        pack.getEClassifiers().add(entry);
        EReference r = ef.createEReference();
        r.setName(name);
        r.setEType(entry);
        r.setUpperBound(-1);
        r.setContainment(true);
        return r;
    }

    private Outcome write(String property, String value, ListMode mode)
    {
        return ConfigurationListProperties.apply(configuration, property, value, mode, false, LANG);
    }

    @SuppressWarnings("unchecked")
    private List<Object> listOf(String property)
    {
        return (List<Object>)configuration
            .eGet(configuration.eClass().getEStructuralFeature(property));
    }

    // -- which properties this covers -------------------------------------

    @Test
    public void aListPropertyIsRecognisedAndAScalarIsNot()
    {
        assertTrue(ConfigurationListProperties.isListShaped(configuration, "usePurposes")); //$NON-NLS-1$
        assertTrue(ConfigurationListProperties.isListShaped(configuration,
            "requiredMobileApplicationPermissions8315")); //$NON-NLS-1$
        assertTrue("the one that holds lists inside it counts too", //$NON-NLS-1$
            ConfigurationListProperties.isListShaped(configuration,
                "usedMobileApplicationFunctionalities")); //$NON-NLS-1$
        assertFalse(ConfigurationListProperties.isListShaped(configuration, "name")); //$NON-NLS-1$
        assertFalse(ConfigurationListProperties.isListShaped(configuration, "noSuchThing")); //$NON-NLS-1$
    }

    @Test
    public void theModeWordIsReadOrRefused()
    {
        assertEquals(ListMode.REPLACE, ListMode.parse(null));
        assertEquals(ListMode.REPLACE, ListMode.parse("")); //$NON-NLS-1$
        assertEquals(ListMode.ADD, ListMode.parse("add")); //$NON-NLS-1$
        assertEquals(ListMode.REMOVE, ListMode.parse("REMOVE")); //$NON-NLS-1$
        assertEquals(ListMode.CLEAR, ListMode.parse(" clear ")); //$NON-NLS-1$
        assertEquals("an unknown word is not quietly treated as the default", //$NON-NLS-1$
            null, ListMode.parse("append")); //$NON-NLS-1$
    }

    // -- lists of literals ------------------------------------------------

    @Test
    public void literalsAreWrittenInTheOrderGiven()
    {
        Outcome out = write("usePurposes", "[\"MobileDevice\",\"PersonalComputer\"]", //$NON-NLS-1$ //$NON-NLS-2$
            ListMode.REPLACE);

        assertTrue(out.refusal, out.ok());
        assertEquals(2, listOf("usePurposes").size()); //$NON-NLS-1$
        assertEquals("MobileDevice", String.valueOf(listOf("usePurposes").get(0))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("PersonalComputer", String.valueOf(listOf("usePurposes").get(1))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aLiteralIsMatchedWhateverItsCaseAndStoredCanonically()
    {
        assertTrue(write("usePurposes", "[\"mobiledevice\"]", ListMode.REPLACE).ok()); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("MobileDevice", String.valueOf(listOf("usePurposes").get(0))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void addingAppendsAtTheEndAndRemovingKeepsTheOrderOfTheRest()
    {
        write("requiredMobileApplicationPermissions", //$NON-NLS-1$
            "[\"Camera\",\"Geolocation\",\"Microphone\"]", ListMode.REPLACE); //$NON-NLS-1$
        assertTrue(write("requiredMobileApplicationPermissions", "[\"Geolocation\"]", //$NON-NLS-1$ //$NON-NLS-2$
            ListMode.REMOVE).ok());

        List<Object> left = listOf("requiredMobileApplicationPermissions"); //$NON-NLS-1$
        assertEquals(2, left.size());
        assertEquals("Camera", String.valueOf(left.get(0))); //$NON-NLS-1$
        assertEquals("Microphone", String.valueOf(left.get(1))); //$NON-NLS-1$

        assertTrue(write("requiredMobileApplicationPermissions", "[\"Geolocation\"]", //$NON-NLS-1$ //$NON-NLS-2$
            ListMode.ADD).ok());
        assertEquals("an added value goes to the end", "Geolocation", //$NON-NLS-1$ //$NON-NLS-2$
            String.valueOf(listOf("requiredMobileApplicationPermissions").get(2))); //$NON-NLS-1$
    }

    @Test
    public void clearingEmptiesTheListAndDoesNotReadTheValue()
    {
        write("usePurposes", "[\"MobileDevice\"]", ListMode.REPLACE); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome out = write("usePurposes", null, ListMode.CLEAR); //$NON-NLS-1$

        assertTrue(out.refusal, out.ok());
        assertTrue(listOf("usePurposes").isEmpty()); //$NON-NLS-1$
    }

    // -- what is refused ---------------------------------------------------

    @Test
    public void aLiteralThatDoesNotExistIsRefusedAndTheRealOnesAreNamed()
    {
        Outcome out = write("usePurposes", "[\"Toaster\"]", ListMode.REPLACE); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(out.ok());
        assertTrue(out.refusal, out.refusal.contains("Toaster")); //$NON-NLS-1$
        assertTrue("the refusal must name what the property does take", //$NON-NLS-1$
            out.refusal.contains("MobileDevice")); //$NON-NLS-1$
    }

    @Test
    public void oneBadLiteralWritesNoneOfTheGoodOnes()
    {
        write("usePurposes", "[\"PersonalComputer\"]", ListMode.REPLACE); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome out = write("usePurposes", "[\"MobileDevice\",\"Toaster\"]", ListMode.REPLACE); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(out.ok());
        assertEquals("the list must be exactly as it was", 1, listOf("usePurposes").size()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("PersonalComputer", String.valueOf(listOf("usePurposes").get(0))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aValueRepeatedInOneCallIsRefused()
    {
        Outcome out = write("usePurposes", "[\"MobileDevice\",\"mobiledevice\"]", //$NON-NLS-1$ //$NON-NLS-2$
            ListMode.REPLACE);

        assertFalse("the same value twice in one array is a mistake, not an intent", out.ok()); //$NON-NLS-1$
        assertTrue(out.refusal, out.refusal.contains("MobileDevice")); //$NON-NLS-1$
    }

    @Test
    public void addingWhatIsAlreadyThereIsRefusedAndSoIsRemovingWhatIsNot()
    {
        write("usePurposes", "[\"MobileDevice\"]", ListMode.REPLACE); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome added = write("usePurposes", "[\"MobileDevice\"]", ListMode.ADD); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(added.ok());
        assertTrue(added.refusal, added.refusal.contains("already")); //$NON-NLS-1$

        Outcome removed = write("usePurposes", "[\"PersonalComputer\"]", ListMode.REMOVE); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(removed.ok());
        assertTrue(removed.refusal, removed.refusal.contains("PersonalComputer")); //$NON-NLS-1$
    }

    @Test
    public void aValueThatIsNotJsonIsRefusedSaying()
    {
        Outcome out = write("usePurposes", "MobileDevice", ListMode.REPLACE); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("a bare word is not an array", out.ok()); //$NON-NLS-1$
        assertNotNull(out.refusal);

        assertFalse(write("usePurposes", "[\"unclosed\"", ListMode.REPLACE).ok()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(write("usePurposes", null, ListMode.REPLACE).ok()); //$NON-NLS-1$
    }

    // -- lists of objects --------------------------------------------------

    @Test
    public void anObjectEntryCarriesItsKeyItsFlagAndItsText()
    {
        Outcome out = write("requiredMobileApplicationPermissions8315", //$NON-NLS-1$
            "[{\"permission\":\"Camera\",\"use\":true,\"description\":\"Snapshots\"}]", //$NON-NLS-1$
            ListMode.REPLACE);

        assertTrue(out.refusal, out.ok());
        List<Object> written = listOf("requiredMobileApplicationPermissions8315"); //$NON-NLS-1$
        assertEquals(1, written.size());
        EObject entry = (EObject)written.get(0);
        assertEquals("Camera", String.valueOf(entry.eGet(entry.eClass() //$NON-NLS-1$
            .getEStructuralFeature("permission")))); //$NON-NLS-1$
        assertEquals(Boolean.TRUE,
            entry.eGet(entry.eClass().getEStructuralFeature("use"))); //$NON-NLS-1$
        assertTrue("the value it hands back describes what it wrote", //$NON-NLS-1$
            out.value.toString().contains("Snapshots")); //$NON-NLS-1$
    }

    @Test
    public void aTextGivenPerLanguageIsKeptPerLanguage()
    {
        Outcome out = write("requiredMobileApplicationPermissions8315", //$NON-NLS-1$
            "[{\"permission\":\"Camera\",\"use\":true," //$NON-NLS-1$
                + "\"description\":{\"ru\":\"Снимки\",\"en\":\"Snapshots\"}}]", //$NON-NLS-1$
            ListMode.REPLACE);

        assertTrue(out.refusal, out.ok());
        assertTrue(out.value.toString(), out.value.toString().contains("Snapshots")); //$NON-NLS-1$
        assertTrue(out.value.toString(), out.value.toString().contains("en")); //$NON-NLS-1$
    }

    @Test
    public void anEntryWithoutItsKeyIsRefused()
    {
        Outcome out = write("requiredMobileApplicationPermissions8315", //$NON-NLS-1$
            "[{\"use\":true}]", ListMode.REPLACE); //$NON-NLS-1$

        assertFalse(out.ok());
        assertTrue(out.refusal, out.refusal.contains("permission")); //$NON-NLS-1$
    }

    @Test
    public void anEntryWithAFieldTheClassDoesNotHaveIsRefused()
    {
        Outcome out = write("requiredMobileApplicationPermissions8315", //$NON-NLS-1$
            "[{\"permission\":\"Camera\",\"colour\":\"red\"}]", ListMode.REPLACE); //$NON-NLS-1$

        assertFalse(out.ok());
        assertTrue(out.refusal, out.refusal.contains("colour")); //$NON-NLS-1$
    }

    @Test
    public void anObjectEntryIsRemovedByNamingItsKey()
    {
        write("requiredMobileApplicationPermissions8315", //$NON-NLS-1$
            "[{\"permission\":\"Camera\",\"use\":true},{\"permission\":\"Microphone\"," //$NON-NLS-1$
                + "\"use\":false}]", ListMode.REPLACE); //$NON-NLS-1$

        Outcome out = write("requiredMobileApplicationPermissions8315", "[\"Camera\"]", //$NON-NLS-1$ //$NON-NLS-2$
            ListMode.REMOVE);

        assertTrue(out.refusal, out.ok());
        List<Object> left = listOf("requiredMobileApplicationPermissions8315"); //$NON-NLS-1$
        assertEquals(1, left.size());
        EObject entry = (EObject)left.get(0);
        assertEquals("Microphone", String.valueOf(entry.eGet(entry.eClass() //$NON-NLS-1$
            .getEStructuralFeature("permission")))); //$NON-NLS-1$
    }

    @Test
    public void addingAnEntryWhoseKeyIsAlreadyThereIsRefused()
    {
        write("requiredMobileApplicationPermissions8315", //$NON-NLS-1$
            "[{\"permission\":\"Camera\",\"use\":true}]", ListMode.REPLACE); //$NON-NLS-1$

        Outcome out = write("requiredMobileApplicationPermissions8315", //$NON-NLS-1$
            "[{\"permission\":\"Camera\",\"use\":false}]", ListMode.ADD); //$NON-NLS-1$

        assertFalse("an entry is the permission it is about, whatever else it carries", out.ok()); //$NON-NLS-1$
    }

    // -- the property that holds lists inside it ---------------------------

    @Test
    public void bothInnerListsAreWrittenAndHandedBackAsAnObject()
    {
        Outcome out = write("usedMobileApplicationFunctionalities", //$NON-NLS-1$
            "{\"functionality\":[{\"functionality\":\"MobileDevice\",\"use\":true}]," //$NON-NLS-1$
                + "\"permissionMessage\":[{\"permission\":\"Camera\"," //$NON-NLS-1$
                + "\"description\":\"Why\"}]}", ListMode.REPLACE); //$NON-NLS-1$

        assertTrue(out.refusal, out.ok());
        assertTrue("it comes back as an object, not an array", //$NON-NLS-1$
            out.value.isJsonObject());
        assertTrue(out.value.toString(), out.value.toString().contains("MobileDevice")); //$NON-NLS-1$
        assertTrue(out.value.toString(), out.value.toString().contains("Camera")); //$NON-NLS-1$
    }

    @Test
    public void anInnerListLeftOutOfAReplaceIsEmptied()
    {
        write("usedMobileApplicationFunctionalities", //$NON-NLS-1$
            "{\"functionality\":[{\"functionality\":\"MobileDevice\",\"use\":true}]," //$NON-NLS-1$
                + "\"permissionMessage\":[{\"permission\":\"Camera\"}]}", ListMode.REPLACE); //$NON-NLS-1$

        Outcome out = write("usedMobileApplicationFunctionalities", //$NON-NLS-1$
            "{\"functionality\":[{\"functionality\":\"PersonalComputer\",\"use\":false}]}", //$NON-NLS-1$
            ListMode.REPLACE);

        assertTrue(out.refusal, out.ok());
        assertFalse("the list not named was replaced by nothing", //$NON-NLS-1$
            out.value.toString().contains("Camera")); //$NON-NLS-1$
    }

    @Test
    public void anInnerListLeftOutOfAnAddIsLeftAlone()
    {
        write("usedMobileApplicationFunctionalities", //$NON-NLS-1$
            "{\"functionality\":[]," //$NON-NLS-1$
                + "\"permissionMessage\":[{\"permission\":\"Camera\"}]}", ListMode.REPLACE); //$NON-NLS-1$

        Outcome out = write("usedMobileApplicationFunctionalities", //$NON-NLS-1$
            "{\"functionality\":[{\"functionality\":\"MobileDevice\",\"use\":true}]}", //$NON-NLS-1$
            ListMode.ADD);

        assertTrue(out.refusal, out.ok());
        assertTrue("adding to one list must not empty the other", //$NON-NLS-1$
            out.value.toString().contains("Camera")); //$NON-NLS-1$
    }

    @Test
    public void clearingEmptiesBothInnerLists()
    {
        write("usedMobileApplicationFunctionalities", //$NON-NLS-1$
            "{\"functionality\":[{\"functionality\":\"MobileDevice\",\"use\":true}]," //$NON-NLS-1$
                + "\"permissionMessage\":[{\"permission\":\"Camera\"}]}", ListMode.REPLACE); //$NON-NLS-1$

        Outcome out = write("usedMobileApplicationFunctionalities", null, ListMode.CLEAR); //$NON-NLS-1$

        assertTrue(out.refusal, out.ok());
        assertFalse(out.value.toString().contains("MobileDevice")); //$NON-NLS-1$
        assertFalse(out.value.toString().contains("Camera")); //$NON-NLS-1$
    }

    @Test
    public void anInnerListThatDoesNotExistIsRefusedAndTheRealOnesAreNamed()
    {
        Outcome out = write("usedMobileApplicationFunctionalities", //$NON-NLS-1$
            "{\"invented\":[]}", ListMode.REPLACE); //$NON-NLS-1$

        assertFalse(out.ok());
        assertTrue(out.refusal, out.refusal.contains("invented")); //$NON-NLS-1$
        assertTrue(out.refusal, out.refusal.contains("permissionMessage")); //$NON-NLS-1$
    }

    @Test
    public void anArrayWhereAnObjectIsExpectedIsRefused()
    {
        Outcome out = write("usedMobileApplicationFunctionalities", "[]", ListMode.REPLACE); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(out.ok());
        assertTrue(out.refusal, out.refusal.contains("functionality")); //$NON-NLS-1$
    }

    // -- a run that writes nothing ----------------------------------------

    @Test
    public void aDryRunWritesNothingAndStillShowsTheShape()
    {
        write("usePurposes", "[\"PersonalComputer\"]", ListMode.REPLACE); //$NON-NLS-1$ //$NON-NLS-2$

        Outcome out = ConfigurationListProperties.apply(configuration, "usePurposes", //$NON-NLS-1$
            "[\"MobileDevice\"]", ListMode.REPLACE, true, LANG); //$NON-NLS-1$

        assertTrue(out.refusal, out.ok());
        assertTrue("the shape shows what would have been written", //$NON-NLS-1$
            out.value.toString().contains("MobileDevice")); //$NON-NLS-1$
        assertEquals("nothing was written", "PersonalComputer", //$NON-NLS-1$ //$NON-NLS-2$
            String.valueOf(listOf("usePurposes").get(0))); //$NON-NLS-1$
    }

    @Test
    public void aDryRunOnThePropertyThatHoldsListsWritesNothingEither()
    {
        Outcome out = ConfigurationListProperties.apply(configuration,
            "usedMobileApplicationFunctionalities", //$NON-NLS-1$
            "{\"functionality\":[{\"functionality\":\"MobileDevice\",\"use\":true}]}", //$NON-NLS-1$
            ListMode.REPLACE, true, LANG);

        assertTrue(out.refusal, out.ok());
        Object held = configuration.eGet(
            configuration.eClass().getEStructuralFeature("usedMobileApplicationFunctionalities")); //$NON-NLS-1$
        if (held instanceof EObject)
        {
            EObject holder = (EObject)held;
            assertTrue("a dry run must leave the inner lists empty", //$NON-NLS-1$
                ((List<?>)holder.eGet(holder.eClass().getEStructuralFeature("functionality"))) //$NON-NLS-1$
                    .isEmpty());
        }
    }

    // -- one refusal leaves every inner list alone -------------------------

    @Test
    public void aRefusalOnTheSecondInnerListLeavesTheFirstUnwritten()
    {
        write("usedMobileApplicationFunctionalities", //$NON-NLS-1$
            "{\"functionality\":[],\"permissionMessage\":[]}", ListMode.REPLACE); //$NON-NLS-1$

        Outcome out = write("usedMobileApplicationFunctionalities", //$NON-NLS-1$
            "{\"functionality\":[{\"functionality\":\"MobileDevice\",\"use\":true}]," //$NON-NLS-1$
                + "\"permissionMessage\":[{\"permission\":\"Toaster\"}]}", ListMode.REPLACE); //$NON-NLS-1$

        assertFalse("a bad entry in the second list refuses the whole call", out.ok()); //$NON-NLS-1$
        Object held = configuration.eGet(configuration.eClass()
            .getEStructuralFeature("usedMobileApplicationFunctionalities")); //$NON-NLS-1$
        EObject holder = (EObject)held;
        assertTrue("the first list must not have been written", //$NON-NLS-1$
            ((List<?>)holder.eGet(holder.eClass().getEStructuralFeature("functionality"))) //$NON-NLS-1$
                .isEmpty());
    }

    @Test
    public void aDryRunOverTheNestedPropertyShowsWhatWasAskedForNotWhatIsThere()
    {
        write("usedMobileApplicationFunctionalities", //$NON-NLS-1$
            "{\"functionality\":[{\"functionality\":\"PersonalComputer\",\"use\":false}]}", //$NON-NLS-1$
            ListMode.REPLACE);

        Outcome out = ConfigurationListProperties.apply(configuration,
            "usedMobileApplicationFunctionalities", //$NON-NLS-1$
            "{\"functionality\":[{\"functionality\":\"MobileDevice\",\"use\":true}]}", //$NON-NLS-1$
            ListMode.REPLACE, true, LANG);

        assertTrue(out.refusal, out.ok());
        assertTrue("the preview must show what would be written: " + out.value, //$NON-NLS-1$
            out.value.toString().contains("MobileDevice")); //$NON-NLS-1$
        assertFalse("the preview must not show what is there instead: " + out.value, //$NON-NLS-1$
            out.value.toString().contains("PersonalComputer")); //$NON-NLS-1$
    }

    @Test
    public void removingFromANestedPropertyThatWasNeverSetIsRefusedLikeAnyOtherRemoval()
    {
        Outcome out = write("usedMobileApplicationFunctionalities", //$NON-NLS-1$
            "{\"permissionMessage\":[\"Camera\"]}", ListMode.REMOVE); //$NON-NLS-1$

        assertFalse("removing what is not there is not a success", out.ok()); //$NON-NLS-1$
        assertTrue(out.refusal, out.refusal.contains("Camera")); //$NON-NLS-1$
    }

    @Test
    public void aValueThatIsNotJsonIsRefusedForTheNestedPropertyToo()
    {
        assertFalse(write("usedMobileApplicationFunctionalities", "{ not json", //$NON-NLS-1$ //$NON-NLS-2$
            ListMode.REMOVE).ok());
    }

    @Test
    public void clearingAPropertyThatWasNeverSetIsNotAnError()
    {
        Outcome out = write("usedMobileApplicationFunctionalities", null, ListMode.CLEAR); //$NON-NLS-1$

        assertTrue(out.refusal, out.ok());
    }

    // -- reading back ------------------------------------------------------

    @Test
    public void readingGivesTheSameShapeWritingHandsBack()
    {
        write("usePurposes", "[\"MobileDevice\"]", ListMode.REPLACE); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("[\"MobileDevice\"]", //$NON-NLS-1$
            ConfigurationListProperties.read(configuration, "usePurposes").toString()); //$NON-NLS-1$
        assertTrue(ConfigurationListProperties
            .read(configuration, "usedMobileApplicationFunctionalities").isJsonObject()); //$NON-NLS-1$
        assertEquals("a scalar is not read here", null, //$NON-NLS-1$
            ConfigurationListProperties.read(configuration, "name")); //$NON-NLS-1$
    }
}
