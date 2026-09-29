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
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * A write aimed at an object the support registry closed for changes is refused, and the refusal
 * names the object, the mode and what lifts the ban.
 * <p>
 * The support service cannot be put into either state from a unit test - a project on support is
 * arranged by the user, not by test code - so the answers are supplied through the guard's probe
 * seam. What the seam does not stand in for is the decision itself: which object is judged, which
 * answer blocks and which passes, and what the caller is told.
 * </p>
 */
public class ModelEditabilityGuardTest
{
    /** A probe that answers with what the test set and remembers what it was asked about. */
    private static final class Probe implements ModelEditabilityGuard.Probe
    {
        final ModelEditabilityGuard.Editability answer = new ModelEditabilityGuard.Editability();

        final List<MdObject> asked = new ArrayList<>();

        @Override
        public ModelEditabilityGuard.Editability ask(IProject project, MdObject top)
        {
            asked.add(top);
            return answer;
        }
    }

    private final Probe probe = new Probe();

    @After
    public void theSupportServiceAnswersAgain()
    {
        ModelEditabilityGuard.useProbeForTest(null);
        ModelEditabilityGuard.useOwnerResolverForTest(null);
    }

    /**
     * One answer from a registry that holds a record for the object.
     *
     * @param userMode the mode the registry records
     * @param canEdit what the environment says about editing it
     * @return the answer
     */
    private static ModelEditabilityGuard.Editability answer(String userMode, boolean canEdit)
    {
        ModelEditabilityGuard.Editability answer = new ModelEditabilityGuard.Editability();
        answer.answered = true;
        answer.userMode = userMode;
        answer.canEdit = canEdit;
        answer.route = "test"; //$NON-NLS-1$
        return answer;
    }

    /** An answer from an environment that could not be asked at all. */
    private static ModelEditabilityGuard.Editability notAnswered()
    {
        ModelEditabilityGuard.Editability answer = new ModelEditabilityGuard.Editability();
        answer.cannotTell = "the support subsystem could not be reached"; //$NON-NLS-1$
        return answer;
    }

    private static Catalog catalog(String name)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        return catalog;
    }

    // ---------- the decision ----------

    /** The measured state: a record that forbids changes and an environment that agrees. */
    @Test
    public void aClosedObjectIsRefusedByNameAndMode()
    {
        MetadataGuards.Verdict verdict =
            ModelEditabilityGuard.decide("Catalog.Валюты", answer("ChangesNotAllowed", false)); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(verdict.blocked);
        assertTrue(verdict.error, verdict.error.contains("Catalog.Валюты")); //$NON-NLS-1$
        assertTrue(verdict.error, verdict.error.contains("ChangesNotAllowed")); //$NON-NLS-1$
        assertNotNull("the caller is told what lifts the ban", verdict.hint); //$NON-NLS-1$
        assertFalse(verdict.hint.isEmpty());
        assertNotNull(verdict.tag);
        assertEquals(ErrorTags.SUPPORT_LOCK.wire(), verdict.tag.name);
        assertEquals("Catalog.Валюты", verdict.tag.data.get("object")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ChangesNotAllowed", verdict.tag.data.get("userSupportMode")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.FALSE, verdict.tag.data.get("canEdit")); //$NON-NLS-1$
        assertEquals(ModelEditabilityGuard.NAME, verdict.tag.data.get("guard")); //$NON-NLS-1$
    }

    /** An object the user opened for editing is written as it was before the guard existed. */
    @Test
    public void anObjectOpenedForChangesPasses()
    {
        assertFalse(ModelEditabilityGuard.decide("Catalog.Goods", answer("ChangesAllowed", true)) //$NON-NLS-1$ //$NON-NLS-2$
            .blocked);
        assertFalse("the registry's own record is what decides, not the environment alone", //$NON-NLS-1$
            ModelEditabilityGuard.decide("Catalog.Goods", answer("ChangesAllowed", false)).blocked); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A configuration written from scratch is on nobody's support and keeps being written. */
    @Test
    public void anObjectWithNoSupportRecordPasses()
    {
        assertFalse(ModelEditabilityGuard.decide("Catalog.Own", answer(null, true)).blocked); //$NON-NLS-1$
        assertFalse("no record means no ban to report", //$NON-NLS-1$
            ModelEditabilityGuard.decide("Catalog.Own", answer(null, false)).blocked); //$NON-NLS-1$
    }

    /** A support subsystem this server cannot reach is not evidence that anything is closed. */
    @Test
    public void aServiceThatCannotBeAskedDoesNotBlock()
    {
        assertFalse(ModelEditabilityGuard.decide("Catalog.X", notAnswered()).blocked); //$NON-NLS-1$
        assertFalse(ModelEditabilityGuard.decide("Catalog.X", null).blocked); //$NON-NLS-1$
    }

    // ---------- which object is judged ----------

    /**
     * A child that is a metadata object of its own - an attribute is - is judged by itself, the way
     * EDT judges it, and not by the object that holds it.
     */
    @Test
    public void aChildIsJudgedByItsOwnRecord()
    {
        Catalog catalog = catalog("Валюты"); //$NON-NLS-1$
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("КодВалюты"); //$NON-NLS-1$
        catalog.getAttributes().add(attribute);

        probe.answer.answered = true;
        probe.answer.userMode = "ChangesNotAllowed"; //$NON-NLS-1$
        probe.answer.canEdit = false;
        ModelEditabilityGuard.useProbeForTest(probe);

        MetadataGuards.Verdict verdict = ModelEditabilityGuard.checkObject(null, attribute);

        assertEquals("the attribute carries a record of its own", 1, probe.asked.size()); //$NON-NLS-1$
        assertSame(attribute, probe.asked.get(0));
        assertTrue(verdict.blocked);
        assertEquals("CatalogAttribute.КодВалюты", verdict.tag.data.get("object")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A form is judged by the form, not by the catalog that holds it. */
    @Test
    public void aFormIsJudgedByTheForm()
    {
        Catalog catalog = catalog("Валюты"); //$NON-NLS-1$
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        form.setName("ФормаЭлемента"); //$NON-NLS-1$
        catalog.getForms().add(form);

        ModelEditabilityGuard.useProbeForTest(probe);

        ModelEditabilityGuard.checkObject(null, form);

        assertEquals("the record of the form is the one that decides", 1, probe.asked.size()); //$NON-NLS-1$
        assertSame(form, probe.asked.get(0));
    }

    /** The configuration root is a metadata object and is judged by its own record. */
    @Test
    public void aConfigurationRootIsJudgedByItsOwnRecord()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        configuration.setName("Конфигурация"); //$NON-NLS-1$
        ModelEditabilityGuard.useProbeForTest(probe);

        MetadataGuards.Verdict verdict = ModelEditabilityGuard.checkObject(null, configuration);

        assertEquals("the root is asked about like any object", 1, probe.asked.size()); //$NON-NLS-1$
        assertSame(configuration, probe.asked.get(0));
        assertFalse(verdict.blocked);
    }

    /** The nearest metadata object is the object the walk stops at first, itself included. */
    @Test
    public void theNearestMetadataObjectIsTheFirstOneUpwards()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = catalog("Валюты"); //$NON-NLS-1$
        configuration.getCatalogs().add(catalog);
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("КодВалюты"); //$NON-NLS-1$
        catalog.getAttributes().add(attribute);

        assertSame(attribute, ModelEditabilityGuard.nearestMdObject(attribute));
        assertSame(catalog, ModelEditabilityGuard.nearestMdObject(catalog));
        assertSame("the root is a metadata object too", configuration, //$NON-NLS-1$
            ModelEditabilityGuard.nearestMdObject(configuration));
        assertNull(ModelEditabilityGuard.nearestMdObject(null));
    }

    /**
     * An address that names a child resolves to the child; the child's record is the one asked for.
     */
    @Test
    public void anAddressThatNamesAChildIsJudgedByTheChild()
    {
        Catalog catalog = catalog("Валюты"); //$NON-NLS-1$
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        form.setName("ФормаЭлемента"); //$NON-NLS-1$
        catalog.getForms().add(form);
        ModelEditabilityGuard.useOwnerResolverForTest((project, ownerFqn) -> catalog);

        probe.answer.answered = true;
        probe.answer.userMode = "ChangesNotAllowed"; //$NON-NLS-1$
        probe.answer.canEdit = false;
        ModelEditabilityGuard.useProbeForTest(probe);

        MetadataGuards.Verdict verdict =
            ModelEditabilityGuard.checkFqn(null, "Catalog.Валюты.Form.ФормаЭлемента"); //$NON-NLS-1$

        assertSame("the form the address spells is the object asked about", form, //$NON-NLS-1$
            probe.asked.get(0));
        assertTrue(verdict.blocked);
        assertEquals("the refusal names the address the caller used", //$NON-NLS-1$
            "Catalog.Валюты.Form.ФормаЭлемента", verdict.tag.data.get("object")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An address whose child segment names nothing is judged by the owner it did resolve. */
    @Test
    public void anAddressOfAnUnknownChildFallsBackToItsOwner()
    {
        Catalog catalog = catalog("Валюты"); //$NON-NLS-1$
        ModelEditabilityGuard.useOwnerResolverForTest((project, ownerFqn) -> catalog);
        ModelEditabilityGuard.useProbeForTest(probe);

        ModelEditabilityGuard.checkFqn(null, "Catalog.Валюты.Form.НетТакой"); //$NON-NLS-1$

        assertEquals(1, probe.asked.size());
        assertSame(catalog, probe.asked.get(0));
    }

    /** The BM trailing {@code .Form} segment and a module marker name no child of their own. */
    @Test
    public void aTrailingMarkerNamesNoChild()
    {
        Catalog catalog = catalog("Валюты"); //$NON-NLS-1$
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        form.setName("ФормаЭлемента"); //$NON-NLS-1$
        catalog.getForms().add(form);
        ModelEditabilityGuard.useOwnerResolverForTest((project, ownerFqn) -> catalog);
        ModelEditabilityGuard.useProbeForTest(probe);

        ModelEditabilityGuard.checkFqn(null, "Catalog.Валюты.Form.ФормаЭлемента.Form"); //$NON-NLS-1$
        ModelEditabilityGuard.checkFqn(null, "Catalog.Валюты.ObjectModule"); //$NON-NLS-1$

        assertSame(form, probe.asked.get(0));
        assertSame("the module of the object is judged by the object", catalog, //$NON-NLS-1$
            probe.asked.get(1));
    }

    /** The address of a module or a form belongs to the object its first two segments name. */
    @Test
    public void anAddressIsShortenedToTheObjectItNames()
    {
        assertEquals("Catalog.Валюты", //$NON-NLS-1$
            ModelEditabilityGuard.ownerFqnOf("Catalog.Валюты")); //$NON-NLS-1$
        assertEquals("Catalog.Валюты", //$NON-NLS-1$
            ModelEditabilityGuard.ownerFqnOf("Catalog.Валюты.Form.ItemForm")); //$NON-NLS-1$
        assertEquals("Document.Заказ", //$NON-NLS-1$
            ModelEditabilityGuard.ownerFqnOf(" Document.Заказ.ObjectModule")); //$NON-NLS-1$
        assertEquals("a single segment names no owner to shorten to", "Configuration", //$NON-NLS-1$ //$NON-NLS-2$
            ModelEditabilityGuard.ownerFqnOf("Configuration")); //$NON-NLS-1$
        assertNull(ModelEditabilityGuard.ownerFqnOf(null));
    }

    /** An address that names nothing this project holds is not judged. */
    @Test
    public void anAddressThatDoesNotResolvePasses()
    {
        assertFalse(ModelEditabilityGuard.checkFqn(null, "Catalog.Валюты").blocked); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(ModelEditabilityGuard.checkFqn(null, "justaname").blocked); //$NON-NLS-1$
    }

    /** The guard's name is not a tool, an operation or a preference key. */
    @Test
    public void theGuardNamesItselfInsideTheServer()
    {
        assertEquals("model_editability_guard", ModelEditabilityGuard.NAME); //$NON-NLS-1$
    }

    /**
     * The tag of a refusal survives a path that answers in text: the line carries the fields, and
     * the parse reads them back - the hint included, spaces and all.
     */
    @Test
    public void theTagSurvivesATextAnswerAndIsReadBack()
    {
        MetadataGuards.Verdict verdict =
            ModelEditabilityGuard.decide("Catalog.Валюты", answer("ChangesNotAllowed", false)); //$NON-NLS-1$ //$NON-NLS-2$

        String line = ModelEditabilityGuard.supportLockLine(verdict);

        assertTrue(line, line.startsWith("supportLock: ")); //$NON-NLS-1$
        assertTrue(line, line.contains("object=Catalog.Валюты")); //$NON-NLS-1$
        Map<String, Object> read = ModelEditabilityGuard.parseSupportLockLine(
            "Error: refused\n" + line + "\nmore text"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.Валюты", read.get("object")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ChangesNotAllowed", read.get("userSupportMode")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(verdict.hint, read.get("hint")); //$NON-NLS-1$
        assertNull("a text without the line answers nothing", //$NON-NLS-1$
            ModelEditabilityGuard.parseSupportLockLine("Error: something else")); //$NON-NLS-1$
    }
}
