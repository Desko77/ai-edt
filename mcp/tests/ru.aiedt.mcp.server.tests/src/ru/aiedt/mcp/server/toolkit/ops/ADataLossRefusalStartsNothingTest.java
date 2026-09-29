/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.DataLossPlan;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * An update that would delete data is refused before it starts, and the refusal is a measurement
 * rather than a reading of the source: the gate takes what follows it as a supplier, so a test hands
 * in its own recording stand-in and asks whether it was called.
 *
 * <p>The production call site passes a supplier answering {@code null}, because there the code after
 * the gate IS the path that claims the infobase and starts the update. What the gate guarantees is
 * the order: the comparison is read and judged before the claim, so a refusal leaves the base
 * unclaimed, free of clients and exactly as it was.</p>
 *
 * <p>Three plans let the update through and each for its own reason: an empty one means the two
 * sides agreed, an uncompared one means nothing was claimed either way, and an unasked-for one is
 * the caller having switched the protection off. The refusal is for exactly one state - a comparison
 * that was made, found data the base holds and the model does not, and was not accepted.</p>
 */
public class ADataLossRefusalStartsNothingTest
{
    private static final String BASELINE = "E:/ws/.metadata/ib-sync/ss/<uuid>/ConfigDumpInfo.xml"; //$NON-NLS-1$

    /** A comparison that found two entities the base holds and the model does not. */
    private static DataLossPlan.Plan found()
    {
        return DataLossPlan.compared(BASELINE,
            List.of("Catalog.Товары", "Catalog.Цены.Attribute.Ставка"), 13389, 4210); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A stand-in for what follows the gate, which records whether it ran. */
    private static final class Launch implements Supplier<String>
    {
        private final AtomicInteger runs = new AtomicInteger();

        @Override
        public String get()
        {
            this.runs.incrementAndGet();
            return "started"; //$NON-NLS-1$
        }

        boolean ran()
        {
            return this.runs.get() > 0;
        }
    }

    /** One JSON member of an answer. */
    private static JsonObject parse(String answer)
    {
        return JsonParser.parseString(answer).getAsJsonObject();
    }

    /**
     * The refusal: the comparison found data that would go, the caller had not accepted it, and the
     * update was never started. The launch stands in for the whole of the rest of the call - the
     * claim, the clients, the update itself - so "it was not called" is the statement that nothing
     * happened.
     */
    @Test
    public void aDeletionStopsTheUpdateBeforeTheLaunch()
    {
        Launch launch = new Launch();

        String answer = DatabaseUpdater.passTheDataLossGate(found(), true, false, launch);

        assertFalse("nothing that follows the gate ran, so nothing was started", launch.ran()); //$NON-NLS-1$
        JsonObject body = parse(answer);
        assertFalse("the refusal is an error, not a success carrying a warning", //$NON-NLS-1$
            body.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("confirmationRequired", body.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the caller must be able to tell this from an update that ran and failed", //$NON-NLS-1$
            body.get("nothingStarted").getAsBoolean()); //$NON-NLS-1$
        assertEquals("resend the same call with acceptDataLoss=true", //$NON-NLS-1$
            body.get("nextStep").getAsString()); //$NON-NLS-1$
    }

    /**
     * The refusal names the addresses, one per entity, and the count beside them - a caller reads
     * the addresses to decide, and a count is what a machine can check without parsing prose.
     */
    @Test
    public void theRefusalNamesTheAddresses()
    {
        JsonObject body = parse(DatabaseUpdater.dataLossRefusal(found()));

        assertEquals(2, body.get("dataLossCount").getAsInt()); //$NON-NLS-1$
        assertEquals("Catalog.Товары", //$NON-NLS-1$
            body.getAsJsonArray("dataLossTables").get(0).getAsString()); //$NON-NLS-1$
        assertEquals("Catalog.Цены.Attribute.Ставка", //$NON-NLS-1$
            body.getAsJsonArray("dataLossTables").get(1).getAsString()); //$NON-NLS-1$
        assertEquals(BASELINE, body.get("dataLossFile").getAsString()); //$NON-NLS-1$
        assertTrue("the answer says what was compared", //$NON-NLS-1$
            body.get("dataLossCheck").getAsString().startsWith("compared 13389 records")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and says nothing was started", //$NON-NLS-1$
            body.get("error").getAsString().contains("Nothing was started")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Accepting the loss lets the update start, and the acceptance is the caller's word for this
     * call: the same comparison that refused a moment ago now passes, and nothing else about it
     * changed.
     */
    @Test
    public void acceptingTheLossStartsTheUpdate()
    {
        Launch launch = new Launch();

        String answer = DatabaseUpdater.passTheDataLossGate(found(), true, true, launch);

        assertTrue("the caller accepted the deletion, so the update runs", launch.ran()); //$NON-NLS-1$
        assertEquals("started", answer); //$NON-NLS-1$
    }

    /**
     * A comparison that agreed stops nothing. Both sides were read and neither lost anything, which
     * is the ordinary case and has to keep working - a gate that refused here would make every
     * update unusable.
     */
    @Test
    public void anEmptyComparisonStartsTheUpdate()
    {
        Launch launch = new Launch();
        DataLossPlan.Plan agreed = DataLossPlan.compared(BASELINE, List.of(), 13389, 4210);

        String answer = DatabaseUpdater.passTheDataLossGate(agreed, true, false, launch);

        assertTrue(launch.ran());
        assertEquals("started", answer); //$NON-NLS-1$
    }

    /**
     * A comparison that was not made stops nothing either. "Nothing was claimed" and "nothing was
     * lost" are different answers, and only the second one is a reason to hold an update back - so a
     * base with no baseline, an unreadable file or a model that would not open costs the caller the
     * protection, not the update.
     */
    @Test
    public void anUncomparedComparisonStartsTheUpdate()
    {
        Launch launch = new Launch();
        DataLossPlan.Plan unknown = DataLossPlan.notCompared(BASELINE,
            "the objects of kind Catalog were not enumerated"); //$NON-NLS-1$

        String answer = DatabaseUpdater.passTheDataLossGate(unknown, true, false, launch);

        assertTrue("a comparison that could not be made refuses nothing", launch.ran()); //$NON-NLS-1$
        assertEquals("started", answer); //$NON-NLS-1$
    }

    /**
     * With the protection off the update is not held back, however much would be lost: the caller
     * asked for the platform's own behaviour, and the answer says so rather than staying silent.
     */
    @Test
    public void withProtectionOffTheUpdateIsNotStopped()
    {
        Launch launch = new Launch();

        String answer = DatabaseUpdater.passTheDataLossGate(found(), false, false, launch);

        assertTrue(launch.ran());
        assertEquals("started", answer); //$NON-NLS-1$
    }

    /** The gate with nothing to judge: no plan at all, which is what {@code protectData=false} reads. */
    @Test
    public void aPlanThatWasNeverReadIsNotARefusal()
    {
        Launch launch = new Launch();

        assertEquals("started", DatabaseUpdater.passTheDataLossGate(null, true, false, launch)); //$NON-NLS-1$
        assertTrue(launch.ran());
        assertNull("the refusal is built from a plan, so there is none without one", //$NON-NLS-1$
            refusalOf(null));
    }

    /** The refusal of a plan, or {@code null} when that plan would not be refused. */
    private static String refusalOf(DataLossPlan.Plan plan)
    {
        return DatabaseUpdater.passTheDataLossGate(plan, true, false, () -> null);
    }

    /**
     * An update that ran says what was compared and how many records it covered, so a caller can
     * tell an update that checked and found nothing from one that never checked.
     */
    @Test
    public void theAnswerOfAProtectedUpdateSaysWhatWasCompared()
    {
        ToolResult answer = ToolResult.success();
        DataLossPlan.Plan found = DataLossPlan.compared(BASELINE,
            List.of("Catalog.Цены.Attribute.Ставка"), 13389, 4210); //$NON-NLS-1$
        DatabaseUpdater.putDataLossCheck(answer, found, true, true);

        JsonObject body = parse(answer.toJson());
        assertTrue(body.get("protectData").getAsBoolean()); //$NON-NLS-1$
        assertTrue(body.get("dataLossCompared").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, body.get("dataLossCount").getAsInt()); //$NON-NLS-1$
        assertTrue("the answer says the loss was carried through, not that nothing was lost", //$NON-NLS-1$
            body.get("dataLossCheck").getAsString().endsWith("; acceptDataLoss=true carried it through")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An agreeing comparison in the answer of an update that ran. */
    @Test
    public void theAnswerOfAGreenComparisonSaysThereWasNothingToLose()
    {
        ToolResult answer = ToolResult.success();
        DatabaseUpdater.putDataLossCheck(answer, DataLossPlan.compared(BASELINE, List.of(), 13389, 4210),
            true, false);

        String check = parse(answer.toJson()).get("dataLossCheck").getAsString(); //$NON-NLS-1$
        assertTrue(check, check.contains("no entity that holds data is missing from the model")); //$NON-NLS-1$
        assertFalse("nothing was carried through, because nothing was found", //$NON-NLS-1$
            check.contains("carried it through")); //$NON-NLS-1$
    }

    /**
     * An unprotected update and an uncompared one are two different answers, and both say which they
     * are: a caller reading {@code dataLossCheck} must never take "the comparison was switched off"
     * for "the comparison found nothing".
     */
    @Test
    public void aComparisonThatWasNotMadeSaysWhy()
    {
        ToolResult unprotected = ToolResult.success();
        DatabaseUpdater.putDataLossCheck(unprotected, null, false, false);
        JsonObject off = parse(unprotected.toJson());
        assertFalse(off.get("dataLossCompared").getAsBoolean()); //$NON-NLS-1$
        assertTrue(off.get("dataLossCheck").getAsString().contains("protectData=false")); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult unread = ToolResult.success();
        DatabaseUpdater.putDataLossCheck(unread, DataLossPlan.notCompared(BASELINE, "no baseline"), //$NON-NLS-1$
            true, false);
        JsonObject unknown = parse(unread.toJson());
        assertFalse(unknown.get("dataLossCompared").getAsBoolean()); //$NON-NLS-1$
        assertTrue(unknown.get("dataLossCheck").getAsString().startsWith("not compared: no baseline")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("no addresses are claimed for a comparison that was not made", //$NON-NLS-1$
            unknown.has("dataLossTables")); //$NON-NLS-1$
    }
}
