/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BorrowedSyncClassification.Qualifiers;
import ru.aiedt.mcp.server.support.BorrowedSyncClassification.Status;
import ru.aiedt.mcp.server.support.BorrowedSyncClassification.TypeItem;
import ru.aiedt.mcp.server.support.BorrowedSyncClassification.TypeSide;

/**
 * The drift rules of {@link BorrowedSyncClassification}, on plain values, one measured scenario
 * per status.
 * <p>
 * The scenarios are the drift table of the spike this operation was designed from: a borrowed
 * attribute controlled at {@code String(10)}, a base that moved under it seven different ways.
 * Each row here pins the status that table measured, so a rule change that reclassifies a known
 * case breaks the build instead of an agent's expectations.
 * </p>
 */
public class BorrowedSyncClassificationTest
{
    /** The controlled record of the spike scenario: String, ten characters, Checked. */
    private static TypeSide controlledString(int length)
    {
        Qualifiers qualifiers = new Qualifiers();
        qualifiers.stringLength = Integer.valueOf(length);
        return new TypeSide(List.of(BorrowedSyncClassification.primitive("String")), qualifiers); //$NON-NLS-1$
    }

    /** A base of one primitive with its qualifiers. */
    private static TypeSide base(TypeItem... items)
    {
        return new TypeSide(Arrays.asList(items), null);
    }

    /** A base of one String with its qualifiers. */
    private static TypeSide baseString(int length)
    {
        Qualifiers qualifiers = new Qualifiers();
        qualifiers.stringLength = Integer.valueOf(length);
        return new TypeSide(List.of(BorrowedSyncClassification.primitive("String")), qualifiers); //$NON-NLS-1$
    }

    /**
     * The base grew a reference type beside the unchanged String: accepted, nothing to align.
     */
    @Test
    public void aBaseThatGrewAReferenceTypeIsBaseWider()
    {
        TypeSide base = new TypeSide(
            Arrays.asList(BorrowedSyncClassification.primitive("String"), //$NON-NLS-1$
                BorrowedSyncClassification.reference("CatalogRef.Товары")), //$NON-NLS-1$
            controlledString(10).qualifiers);
        assertEquals(Status.BASE_WIDER, BorrowedSyncClassification.typeVsBase(
            controlledString(10), base));
    }

    /** The base's String grew from 10 to 50 characters: the platform refuses the extension. */
    @Test
    public void aLengthThatMovedIsATypeConflict()
    {
        assertEquals(Status.TYPE_CONFLICT, BorrowedSyncClassification.typeVsBase(
            controlledString(10), baseString(50)));
    }

    /**
     * The wider base and the conflict are told apart by the qualifiers, not by the width.
     * <p>
     * A composite base whose shared String moved its length is a conflict and not a wider base -
     * the one case the two statuses are easiest to confuse, because the type names still agree.
     * </p>
     */
    @Test
    public void aWiderBaseWithAMovedLengthIsNotBaseWider()
    {
        Qualifiers baseQualifiers = new Qualifiers();
        baseQualifiers.stringLength = Integer.valueOf(50);
        TypeSide base = new TypeSide(
            Arrays.asList(BorrowedSyncClassification.primitive("String"), //$NON-NLS-1$
                BorrowedSyncClassification.reference("CatalogRef.Товары")), //$NON-NLS-1$
            baseQualifiers);
        assertEquals(Status.TYPE_CONFLICT, BorrowedSyncClassification.typeVsBase(
            controlledString(10), base));
    }

    /** The base swapped the String for a Date: the controlled type itself no longer fits. */
    @Test
    public void aTypeSwapIsATypeConflict()
    {
        Qualifiers baseQualifiers = new Qualifiers();
        baseQualifiers.dateFractions = "DateTime"; //$NON-NLS-1$
        TypeSide dateBase = new TypeSide(
            List.of(BorrowedSyncClassification.primitive("Date")), baseQualifiers); //$NON-NLS-1$
        assertEquals(Status.TYPE_CONFLICT, BorrowedSyncClassification.typeVsBase(
            controlledString(10), dateBase));
    }

    /** The controlled record equals the base: nothing to align. */
    @Test
    public void anUntouchedRecordIsInSync()
    {
        assertEquals(Status.IN_SYNC, BorrowedSyncClassification.typeVsBase(
            controlledString(10), baseString(10)));
    }

    /**
     * A controlled record with no entries takes whatever the base has and cannot disagree.
     * <p>
     * Measured on a real extension: an adopted attribute the extension never retyped carries an
     * empty typeExtension. That is a record - not a failure to read one - and it stays in sync
     * whatever the base does to the type.
     * </p>
     */
    @Test
    public void anEmptyControlledRecordIsInSyncWhateverTheBaseHolds()
    {
        assertEquals(Status.IN_SYNC, BorrowedSyncClassification.typeVsBase(
            new TypeSide(BorrowedSyncClassification.noItems(), null), baseString(50)));
    }

    /**
     * A uuid that answers under another name is a rename, not a loss.
     * <p>
     * Matching by name would read this as two unrelated facts; the uuid is what the link carries.
     * </p>
     */
    @Test
    public void aUuidFoundUnderAnotherNameIsRenamedNotGone()
    {
        assertEquals(Status.RENAMED_IN_BASE, BorrowedSyncClassification.byUuid("Артикул", "АртикулНовый")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A uuid the base no longer holds is a loss, whatever names the base grew since. */
    @Test
    public void aUuidTheBaseDroppedIsSourceGone()
    {
        assertEquals(Status.SOURCE_GONE, BorrowedSyncClassification.byUuid("Артикул", null)); //$NON-NLS-1$
    }

    /** Names that agree leave the contents to answer. */
    @Test
    public void agreeingNamesLeaveTheContentsToAnswer()
    {
        assertEquals(null, BorrowedSyncClassification.byUuid("Артикул", "Артикул")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A composite base holding reference types is aligned with one AnyRef; every other base with
     * its exact types.
     */
    @Test
    public void theWritePlanFollowsTheBaseShape()
    {
        Qualifiers baseQualifiers = new Qualifiers();
        baseQualifiers.stringLength = Integer.valueOf(50);
        TypeSide refComposite = new TypeSide(
            Arrays.asList(BorrowedSyncClassification.primitive("String"), //$NON-NLS-1$
                BorrowedSyncClassification.reference("CatalogRef.Товары")), //$NON-NLS-1$
            baseQualifiers);
        TypeSide plainComposite = new TypeSide(
            Arrays.asList(BorrowedSyncClassification.primitive("String"), //$NON-NLS-1$
                BorrowedSyncClassification.primitive("Number")), //$NON-NLS-1$
            baseQualifiers);
        assertEquals(BorrowedSyncClassification.TypePlan.ANY_REF,
            BorrowedSyncClassification.planFor(refComposite));
        assertEquals(BorrowedSyncClassification.TypePlan.EXACT,
            BorrowedSyncClassification.planFor(plainComposite));
        assertEquals(BorrowedSyncClassification.TypePlan.EXACT,
            BorrowedSyncClassification.planFor(baseString(50)));
        assertEquals(BorrowedSyncClassification.TypePlan.EXACT,
            BorrowedSyncClassification.planFor(new TypeSide(
                List.of(BorrowedSyncClassification.reference("CatalogRef.Товары")), null))); //$NON-NLS-1$
    }

    /**
     * After the AnyRef alignment the row reads as a base wider than the record, not as a
     * conflict: AnyRef stands for every reference the base holds, so there is nothing left to do.
     */
    @Test
    public void anyRefCoversTheBaseReferencesAfterTheAlignment()
    {
        Qualifiers baseQualifiers = new Qualifiers();
        baseQualifiers.stringLength = Integer.valueOf(50);
        TypeSide base = new TypeSide(
            Arrays.asList(BorrowedSyncClassification.primitive("String"), //$NON-NLS-1$
                BorrowedSyncClassification.reference("CatalogRef.Товары")), //$NON-NLS-1$
            baseQualifiers);
        TypeSide aligned = new TypeSide(
            List.of(BorrowedSyncClassification.primitive("AnyRef")), null); //$NON-NLS-1$
        assertEquals(Status.BASE_WIDER, BorrowedSyncClassification.typeVsBase(aligned, base));
    }

    /** A composition renders sorted, with each primitive carrying its qualifiers. */
    @Test
    public void aCompositionRendersSortedWithQualifiers()
    {
        Qualifiers qualifiers = new Qualifiers();
        qualifiers.stringLength = Integer.valueOf(10);
        TypeSide side = new TypeSide(
            Arrays.asList(BorrowedSyncClassification.primitive("String"), //$NON-NLS-1$
                BorrowedSyncClassification.primitive("Number")), //$NON-NLS-1$
            qualifiers);
        assertEquals("Number, String(10)", BorrowedSyncClassification.render(side)); //$NON-NLS-1$
    }

    /** A record against nothing the caller could read is a conflict, not a silent agreement. */
    @Test
    public void aRecordAgainstNoBaseIsATypeConflict()
    {
        assertEquals(Status.TYPE_CONFLICT, BorrowedSyncClassification.typeVsBase(
            controlledString(10), null));
    }

    /** The wire spelling is the one the answer carries. */
    @Test
    public void theWireSpellingsAreTheOnesTheAnswerCarries()
    {
        assertEquals("inSync", BorrowedSyncClassification.wire(Status.IN_SYNC)); //$NON-NLS-1$
        assertEquals("baseWider", BorrowedSyncClassification.wire(Status.BASE_WIDER)); //$NON-NLS-1$
        assertEquals("typeConflict", BorrowedSyncClassification.wire(Status.TYPE_CONFLICT)); //$NON-NLS-1$
        assertEquals("sourceGone", BorrowedSyncClassification.wire(Status.SOURCE_GONE)); //$NON-NLS-1$
        assertEquals("renamedInBase", BorrowedSyncClassification.wire(Status.RENAMED_IN_BASE)); //$NON-NLS-1$
        assertEquals("formOutOfDate", BorrowedSyncClassification.wire(Status.FORM_OUT_OF_DATE)); //$NON-NLS-1$
        assertEquals("handlerSignatureChanged", //$NON-NLS-1$
            BorrowedSyncClassification.wire(Status.HANDLER_SIGNATURE_CHANGED));
    }
}
