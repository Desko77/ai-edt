/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * What an update would delete is read off the infobase's synchronization baseline, and the entity
 * that holds the data is the one named.
 *
 * <p>The comparison is by {@code id} and never by name: the baseline records the {@code uuid} the
 * model gave each entity, so an attribute renamed in the configuration keeps its identity and is not
 * a deletion, while an entity whose {@code id} is nowhere in the model is one the base holds and the
 * configuration does not.</p>
 *
 * <p>Only entities that hold data count. The platform's own confirmation window cannot say this -
 * measured 29.09, deleting a data-carrying attribute showed as a changed object with nothing under
 * it - so the kinds are decided here: an object of a kind the base stores rows for, plus the
 * entities under it that carry values. A form, a command and a template are metadata the base holds
 * no rows for, so losing one drops no data and is not named.</p>
 *
 * <p>An entity is named once. Naming a deleted catalog together with every attribute it had would
 * hand the caller a list of everything the object ever contained, and the object's own address is
 * what says the table is going; the same holds for a tabular section and its columns.</p>
 */
public class ADataLossIsNamedFromTheBaselineTest
{
    // The identities of the model, as the baseline writes them next to each name.
    private static final String CATALOG_VALUTY = "1d6b8425-0000-4000-8000-0000000000a1"; //$NON-NLS-1$
    private static final String ATTR_FULL_NAME = "1d6b8425-0000-4000-8000-0000000000a2"; //$NON-NLS-1$
    private static final String TS_ROWS = "1d6b8425-0000-4000-8000-0000000000a3"; //$NON-NLS-1$
    private static final String TS_ROWS_ATTR = "1d6b8425-0000-4000-8000-0000000000a4"; //$NON-NLS-1$
    private static final String FORM = "1d6b8425-0000-4000-8000-0000000000a5"; //$NON-NLS-1$
    private static final String COMMAND = "1d6b8425-0000-4000-8000-0000000000a6"; //$NON-NLS-1$
    private static final String CATALOG_TOVARY = "1d6b8425-0000-4000-8000-0000000000a7"; //$NON-NLS-1$
    private static final String TOVARY_PRICE = "1d6b8425-0000-4000-8000-0000000000a8"; //$NON-NLS-1$
    private static final String TOVARY_TS = "1d6b8425-0000-4000-8000-0000000000a9"; //$NON-NLS-1$
    private static final String CATALOG_PRICES = "1d6b8425-0000-4000-8000-0000000000b0"; //$NON-NLS-1$
    private static final String PRICES_RENAMED = "1d6b8425-0000-4000-8000-0000000000b1"; //$NON-NLS-1$
    private static final String PRICES_RATE = "1d6b8425-0000-4000-8000-0000000000b2"; //$NON-NLS-1$
    private static final String PRICES_OLD_TS = "1d6b8425-0000-4000-8000-0000000000b3"; //$NON-NLS-1$
    private static final String PRICES_OLD_TS_ATTR = "1d6b8425-0000-4000-8000-0000000000b4"; //$NON-NLS-1$
    private static final String REPORT_FIELD = "1d6b8425-0000-4000-8000-0000000000b5"; //$NON-NLS-1$
    private static final String PROCESSOR_TS = "1d6b8425-0000-4000-8000-0000000000b6"; //$NON-NLS-1$

    /** The identities the model reports: everything the baseline carries except the deleted ones. */
    private static final Set<String> MODEL = Set.of(CATALOG_VALUTY, ATTR_FULL_NAME, TS_ROWS, TS_ROWS_ATTR,
        FORM, COMMAND, CATALOG_PRICES, PRICES_RENAMED);

    /**
     * One slice of a real baseline: an object the model still has with its attribute, tabular section
     * and that section's attribute; a form and a command; a catalog the model no longer has, with the
     * attribute and the tabular section it had; a catalog the model has whose attribute and tabular
     * section it does not; a report and a data processor, which hold no rows; and a record without an
     * identity.
     */
    private static String baseline()
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<ConfigDumpInfo version=\"2.20\">\n" //$NON-NLS-1$
            + record("Catalog.Валюты", CATALOG_VALUTY) //$NON-NLS-1$
            + record("Catalog.Валюты.Attribute.НаименованиеПолное", ATTR_FULL_NAME) //$NON-NLS-1$
            + record("Catalog.Валюты.TabularSection.Строки", TS_ROWS) //$NON-NLS-1$
            + record("Catalog.Валюты.TabularSection.Строки.Attribute.Товар", TS_ROWS_ATTR) //$NON-NLS-1$
            + record("Catalog.Валюты.Form.ФормаЭлемента", FORM) //$NON-NLS-1$
            + record("Catalog.Валюты.Command.Открыть", COMMAND) //$NON-NLS-1$
            + record("Catalog.Товары", CATALOG_TOVARY) //$NON-NLS-1$
            + record("Catalog.Товары.Attribute.Цена", TOVARY_PRICE) //$NON-NLS-1$
            + record("Catalog.Товары.TabularSection.Строки", TOVARY_TS) //$NON-NLS-1$
            + record("Catalog.Цены", CATALOG_PRICES) //$NON-NLS-1$
            + record("Catalog.Цены.Attribute.СтароеИмя", PRICES_RENAMED) //$NON-NLS-1$
            + record("Catalog.Цены.Attribute.Ставка", PRICES_RATE) //$NON-NLS-1$
            + record("Catalog.Цены.TabularSection.Старая", PRICES_OLD_TS) //$NON-NLS-1$
            + record("Catalog.Цены.TabularSection.Старая.Attribute.Кол", PRICES_OLD_TS_ATTR) //$NON-NLS-1$
            + record("Report.Отчет.Attribute.Поле", REPORT_FIELD) //$NON-NLS-1$
            + record("DataProcessor.Обр.TabularSection.Т", PROCESSOR_TS) //$NON-NLS-1$
            + "<Metadata name=\"Catalog.БезИдентификатора\"/>\n" //$NON-NLS-1$
            + "</ConfigDumpInfo>\n"; //$NON-NLS-1$
    }

    /** One {@code Metadata} element of the fixture. */
    private static String record(String name, String id)
    {
        return "<Metadata name=\"" + name + "\" id=\"" + id + "\"/>\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The comparison over the fixture, as the update makes it. */
    private static DataLossPlan.Plan planOver(String xml, Set<String> model)
        throws IOException
    {
        try (ByteArrayInputStream stream =
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
        {
            return DataLossPlan.plan(stream, model, "E:/ws/.metadata/ib-sync/ss/<uuid>/ConfigDumpInfo.xml"); //$NON-NLS-1$
        }
    }

    /**
     * The deleted entities, and only them: the object the model lost is named, the attribute and the
     * tabular section under it are not named again, and what the model still holds is not named at
     * all.
     */
    @Test
    public void theEntitiesTheModelLostAreNamedAndNothingElseIs() throws IOException
    {
        DataLossPlan.Plan plan = planOver(baseline(), MODEL);

        assertTrue("the baseline and the model were both read, so the comparison stands", //$NON-NLS-1$
            plan.compared);
        assertNull(plan.notComparedBecause);
        assertEquals(List.of("Catalog.Товары", "Catalog.Цены.Attribute.Ставка", //$NON-NLS-1$ //$NON-NLS-2$
            "Catalog.Цены.TabularSection.Старая"), plan.dataLoss); //$NON-NLS-1$
        assertEquals("every record of the fixture was read", 17, plan.records); //$NON-NLS-1$
        assertEquals("the model identities are the ones handed in", 8, plan.modelIdentities); //$NON-NLS-1$
    }

    /**
     * A renamed entity is not a loss. The baseline carries the old name, the model the new one, and
     * the {@code uuid} is the same on both sides - which is the whole reason the comparison is by
     * {@code id}. Comparing names would report every rename as a table about to be dropped.
     */
    @Test
    public void aRenamedEntityIsNotALoss() throws IOException
    {
        DataLossPlan.Plan plan = planOver(baseline(), MODEL);

        assertFalse("the rename kept the uuid, so nothing was lost", //$NON-NLS-1$
            plan.dataLoss.toString().contains("СтароеИмя")); //$NON-NLS-1$
    }

    /**
     * A form and a command are metadata, not rows: the base holds nothing for them, so losing one
     * deletes no data. The fixture carries both with identities the model does NOT have, and neither
     * is named - which is what separates a data loss from a change of shape.
     */
    @Test
    public void aFormAndACommandAreNotData() throws IOException
    {
        DataLossPlan.Plan plan = planOver(baseline(), MODEL);

        assertFalse("a form holds no rows", plan.dataLoss.toString().contains("Form.")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a command holds no rows", plan.dataLoss.toString().contains("Command.")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an object that stores no rows is not a data loss either", //$NON-NLS-1$
            plan.dataLoss.toString().contains("Report.")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nor is a data processor's tabular section", //$NON-NLS-1$
            plan.dataLoss.toString().contains("DataProcessor.")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A deleted object is named once. Its attributes and its tabular section were part of it and go
     * with it, so naming them would answer with the object's whole past contents rather than with
     * what is about to be dropped.
     */
    @Test
    public void aDeletedObjectIsNamedOnce() throws IOException
    {
        DataLossPlan.Plan plan = planOver(baseline(), MODEL);

        long namedUnderIt = plan.dataLoss.stream()
            .filter(name -> name.startsWith("Catalog.Товары")) //$NON-NLS-1$
            .count();
        assertEquals("the catalog is named, and nothing under it", 1L, namedUnderIt); //$NON-NLS-1$
        assertTrue(plan.dataLoss.contains("Catalog.Товары")); //$NON-NLS-1$
    }

    /**
     * A record the baseline carries without an identity is not counted. Nothing was compared for it,
     * and an entity nobody compared is not an entity found missing - a guess here would refuse an
     * update on a record the file itself did not identify.
     */
    @Test
    public void aRecordWithoutAnIdentityIsNotAFinding() throws IOException
    {
        DataLossPlan.Plan plan = planOver(baseline(), MODEL);

        assertFalse("nothing was compared for it, so nothing is claimed", //$NON-NLS-1$
            plan.dataLoss.toString().contains("БезИдентификатора")); //$NON-NLS-1$
    }

    /**
     * With no record missing the comparison is empty and still compared: an empty list means two
     * sides that agreed, which is what lets the update go ahead, and the sentence says which of the
     * two that is.
     */
    @Test
    public void anAgreeingComparisonSaysWhatItCompared() throws IOException
    {
        String agreeing = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<ConfigDumpInfo version=\"2.20\">\n" //$NON-NLS-1$
            + record("Catalog.Валюты", CATALOG_VALUTY) //$NON-NLS-1$
            + record("Catalog.Валюты.Attribute.НаименованиеПолное", ATTR_FULL_NAME) //$NON-NLS-1$
            + "</ConfigDumpInfo>\n"; //$NON-NLS-1$
        DataLossPlan.Plan plan = planOver(agreeing, MODEL);

        assertEquals(List.of(), plan.dataLoss); //$NON-NLS-1$
        assertTrue(plan.isEmpty());
        assertTrue(plan.check(), plan.check().startsWith("compared 2 records")); //$NON-NLS-1$
        assertTrue("an agreeing comparison says so rather than staying silent", //$NON-NLS-1$
            plan.check().contains("no entity that holds data is missing from the model")); //$NON-NLS-1$

        DataLossPlan.Plan nothing = planOver(baseline(), Set.of());
        assertTrue("a model with none of these identities lost all of them", //$NON-NLS-1$
            nothing.dataLoss.contains("Catalog.Валюты")); //$NON-NLS-1$
    }

    /**
     * A comparison that was not made carries no addresses and says why. This is the shape a caller
     * reads as "nothing is claimed", and it must never be mistaken for "nothing was lost".
     */
    @Test
    public void anUncomparedPlanCarriesNoAddresses()
    {
        DataLossPlan.Plan plan = DataLossPlan.notCompared("E:/ws/ConfigDumpInfo.xml", //$NON-NLS-1$
            "this infobase has no synchronization baseline in this workspace"); //$NON-NLS-1$

        assertFalse(plan.compared);
        assertEquals(List.of(), plan.dataLoss); //$NON-NLS-1$
        assertTrue(plan.isEmpty());
        assertEquals(-1, plan.records);
        assertTrue(plan.check(), plan.check().startsWith("not compared: ")); //$NON-NLS-1$
        assertTrue("the reason is the one the plan was built with", //$NON-NLS-1$
            plan.check().contains("no synchronization baseline")); //$NON-NLS-1$
    }

    /**
     * A baseline that is not well-formed XML is a comparison that could not be made, not an exception
     * escaping into the caller: the file lives under a project and can be truncated or half-written.
     */
    @Test
    public void aBaselineThatDoesNotParseIsAnAnswer()
    {
        try
        {
            planOver("<ConfigDumpInfo><Metadata name=\"Catalog.A\" id=\"x\"/>", MODEL); //$NON-NLS-1$
            throw new AssertionError("a truncated baseline must not read as a comparison"); //$NON-NLS-1$
        }
        catch (IOException expected)
        {
            assertTrue("the failure names what could not be read", //$NON-NLS-1$
                expected.getMessage().contains("not well-formed")); //$NON-NLS-1$
        }
    }

    /**
     * A document type in the file reaches nothing. The reader is told to open no DTD and resolve no
     * external entity, so an entity declared in the file is either left as written or the file is
     * refused outright - and in neither case does its replacement text become an address this server
     * would report.
     */
    @Test
    public void aDocumentTypeReachesNothing() throws IOException
    {
        String hostile = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<!DOCTYPE ConfigDumpInfo [ <!ENTITY xxe \"Catalog.Зловредный\"> ]>\n" //$NON-NLS-1$
            + "<ConfigDumpInfo version=\"2.20\">\n" //$NON-NLS-1$
            + record("Catalog.Валюты", CATALOG_VALUTY) //$NON-NLS-1$
            + record("&xxe;", "1d6b8425-0000-4000-8000-0000000000c0") //$NON-NLS-1$ //$NON-NLS-2$
            + "</ConfigDumpInfo>\n"; //$NON-NLS-1$
        try
        {
            DataLossPlan.Plan plan = planOver(hostile, MODEL);
            assertFalse("an entity declared in the file never becomes a finding", //$NON-NLS-1$
                plan.dataLoss.toString().contains("Зловредный")); //$NON-NLS-1$
        }
        catch (IOException refused)
        {
            // A parser that opens no document type at all is the same answer by another road: the
            // file was read to no effect, so nothing was claimed. Which road an implementation takes
            // is its own business; what is pinned here is that neither of them resolves the entity.
            assertTrue("the file was refused as a baseline", //$NON-NLS-1$
                refused.getMessage().contains("not well-formed")); //$NON-NLS-1$
        }
    }

    /**
     * The kind is read off the name rather than guessed from the nesting, and the object that owns a
     * name is its first two segments - a 1C object name cannot contain a dot. A name with no owner
     * segment is an object itself.
     */
    @Test
    public void theKindAndTheOwnerComeOffTheName()
    {
        assertTrue(DataLossPlan.carriesData("Catalog.Валюты")); //$NON-NLS-1$
        assertTrue(DataLossPlan.carriesData("Catalog.Валюты.Attribute.Код")); //$NON-NLS-1$
        assertTrue(DataLossPlan.carriesData("InformationRegister.Цены.Resource.Сумма")); //$NON-NLS-1$
        assertTrue(DataLossPlan.carriesData("AccountingRegister.Хоз.AccountingFlag.Валютный")); //$NON-NLS-1$
        assertTrue("a constant carries its own value", DataLossPlan.carriesData("Constant.Курс")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a form is metadata", DataLossPlan.carriesData("Catalog.Валюты.Form.Ф")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a template is metadata", DataLossPlan.carriesData("Report.Р.Template.М")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an object that stores no rows", DataLossPlan.carriesData("Enum.Пол")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a filter criterion is not a table", //$NON-NLS-1$
            DataLossPlan.carriesData("FilterCriterion.Отбор")); //$NON-NLS-1$
        assertFalse("a name with no kind is nothing", DataLossPlan.carriesData("Валюты")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(DataLossPlan.carriesData(null));

        assertEquals("the owner is the object, not the section", //$NON-NLS-1$
            "Catalog.Валюты", DataLossPlan.ownerOf("Catalog.Валюты.Attribute.Код")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a column above a section still belongs to the object", //$NON-NLS-1$
            "Catalog.Валюты", //$NON-NLS-1$
            DataLossPlan.ownerOf("Catalog.Валюты.TabularSection.Строки.Attribute.Товар")); //$NON-NLS-1$
        assertNull("an object owns nothing", DataLossPlan.ownerOf("Catalog.Валюты")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
