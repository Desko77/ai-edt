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

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.DataLossPlan;
import ru.aiedt.mcp.server.support.DumpInfoProbe;
import ru.aiedt.mcp.server.support.InfobaseOutsideChange;

/**
 * An inspection answers what an update started now would delete, and it answers without starting
 * anything: the manager is a stand-in that records every question it is asked, so "the inspection
 * only read the update state" is a measurement and not a claim in the documentation.
 *
 * <p>The comparison is handed in rather than read here, which is what lets one test ask about a loss
 * found and another about a comparison that was never made - a base with no baseline - without a
 * workspace, a project or a model behind either.</p>
 */
public class AnInspectionNamesTheDataAnUpdateWouldLoseTest
{
    private static final String BASELINE = "E:/ws/.metadata/ib-sync/ss/<uuid>/ConfigDumpInfo.xml"; //$NON-NLS-1$

    private static final UUID INFOBASE = UUID.fromString("0f0e2b21-7d3a-4a5f-9d1c-2b3c4d5e6f70"); //$NON-NLS-1$

    /** The application manager, recording every question and answering the update state. */
    private static final class RecordingManager
    {
        private final List<String> calls = new ArrayList<>();

        private ApplicationUpdateState state = ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED;

        IApplicationManager asManager()
        {
            return (IApplicationManager)Proxy.newProxyInstance(RecordingManager.class.getClassLoader(),
                new Class<?>[] { IApplicationManager.class }, (proxy, method, args) -> {
                    if (method.getName().equals("equals")) //$NON-NLS-1$
                    {
                        return Boolean.valueOf(proxy == args[0]);
                    }
                    if (method.getName().equals("hashCode")) //$NON-NLS-1$
                    {
                        return Integer.valueOf(System.identityHashCode(proxy));
                    }
                    if (method.getName().equals("toString")) //$NON-NLS-1$
                    {
                        return "an application manager that only answers"; //$NON-NLS-1$
                    }
                    this.calls.add(method.getName());
                    if (method.getName().equals("getUpdateState")) //$NON-NLS-1$
                    {
                        return this.state;
                    }
                    return absent(method.getReturnType());
                });
        }
    }

    /** An application bound to an infobase, the way the environment materializes one. */
    private static IApplication applicationWithInfobase(String id, String name)
    {
        InfobaseReference infobase = ModelFactory.eINSTANCE.createInfobaseReference();
        infobase.setUuid(INFOBASE);
        return (IApplication)Proxy.newProxyInstance(
            AnInspectionNamesTheDataAnUpdateWouldLoseTest.class.getClassLoader(),
            new Class<?>[] { IApplication.class, IInfobaseApplication.class }, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "getId": //$NON-NLS-1$
                    return id;
                case "getName": //$NON-NLS-1$
                    return name;
                case "getInfobase": //$NON-NLS-1$
                    return infobase;
                default:
                    return absent(method.getReturnType());
                }
            });
    }

    /** An application with no infobase of its own, which is every application of an extension. */
    private static IApplication applicationWithoutInfobase(String id, String name)
    {
        return (IApplication)Proxy.newProxyInstance(
            AnInspectionNamesTheDataAnUpdateWouldLoseTest.class.getClassLoader(),
            new Class<?>[] { IApplication.class }, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "getId": //$NON-NLS-1$
                    return id;
                case "getName": //$NON-NLS-1$
                    return name;
                default:
                    return absent(method.getReturnType());
                }
            });
    }

    /** What a proxy answers for a method it does not care about. */
    private static Object absent(Class<?> type)
    {
        if (type == Optional.class)
        {
            return Optional.empty();
        }
        if (type == List.class)
        {
            return List.of();
        }
        if (type == boolean.class || type == Boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == int.class || type == Integer.class)
        {
            return Integer.valueOf(0);
        }
        if (type == long.class || type == Long.class)
        {
            return Long.valueOf(0L);
        }
        return null;
    }

    /** The comparison an update would refuse on, as the reader would have built it. */
    private static DataLossPlan.Plan found()
    {
        return DataLossPlan.compared(BASELINE,
            List.of("Catalog.Товары", "Catalog.Цены.Attribute.Ставка"), 13389, 4210); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static JsonObject inspect(RecordingManager manager, DataLossPlan.Plan pending,
        IApplication application, DatabaseUpdater.PromptAccess preferences)
    {
        return JsonParser.parseString(DatabaseSyncInspector.inspect(manager.asManager(), preferences,
            pending, application, "Demo", "app-1", false, "Demo")).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The inspection names what would be lost, before the call that would be stopped by it, and it
     * asks the environment exactly one question while doing so: the addresses are read off the
     * baseline, and no infobase is claimed, no update started and no preference written.
     */
    @Test
    public void anInspectionNamesTheLossWithoutStartingAnything()
    {
        RecordingManager manager = new RecordingManager();
        boolean[] preferenceRead = { false };
        DatabaseUpdater.PromptAccess preferences = infobaseId -> {
            preferenceRead[0] = true;
            assertEquals("the preference is read for the infobase of the application", INFOBASE, //$NON-NLS-1$
                infobaseId);
            return Boolean.FALSE;
        };

        JsonObject body = inspect(manager, found(),
            applicationWithInfobase("app-1", "Демо"), preferences); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(body.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("inspect_database_sync", body.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("INCREMENTAL_UPDATE_REQUIRED", body.get("updateState").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an update is what this state asks for", body.get("wouldUpdate").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the platform is not asking, so the loss is the only thing stopping it", //$NON-NLS-1$
            "off", body.get("restructureConfirmationPrompt").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the preference of this infobase was read", preferenceRead[0]); //$NON-NLS-1$

        JsonObject protection = body.getAsJsonObject("dataLossProtection"); //$NON-NLS-1$
        assertTrue(protection.get("dataLossCompared").getAsBoolean()); //$NON-NLS-1$
        assertTrue(protection.get("nextUpdateProtectsData").getAsBoolean()); //$NON-NLS-1$
        assertFalse("accepting a deletion is never the default", //$NON-NLS-1$
            protection.get("acceptDataLossDefault").getAsBoolean()); //$NON-NLS-1$
        assertEquals(2, protection.get("pendingDataLossCount").getAsInt()); //$NON-NLS-1$
        assertEquals("Catalog.Товары", //$NON-NLS-1$
            protection.getAsJsonArray("pendingDataLoss").get(0).getAsString()); //$NON-NLS-1$
        assertEquals(BASELINE, protection.get("baseline").getAsString()); //$NON-NLS-1$
        assertNotNull("the caller is told what a refused update would have said", //$NON-NLS-1$
            protection.get("nextStep")); //$NON-NLS-1$
        assertTrue(protection.get("dataLossCheck").getAsString().startsWith("compared 13389")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the inspection says the base was left alone", body.get("nothingStarted").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(body.get("note").getAsString().contains("starts no update")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("one question went to the environment, and it was the state", //$NON-NLS-1$
            List.of("getUpdateState"), manager.calls); //$NON-NLS-1$
    }

    /**
     * A comparison that was not made is answered as one: no addresses, no next step, and a sentence
     * that says the model was never matched against a baseline. An inspection that answered an empty
     * list here would read exactly like a base with nothing to lose.
     */
    @Test
    public void anInspectionThatCouldNotCompareSaysSo()
    {
        RecordingManager manager = new RecordingManager();
        DataLossPlan.Plan unknown = DataLossPlan.notCompared(BASELINE,
            "this infobase has no synchronization baseline in this workspace"); //$NON-NLS-1$

        JsonObject protection = inspect(manager, unknown, applicationWithInfobase("app-1", "Демо"), //$NON-NLS-1$ //$NON-NLS-2$
            infobaseId -> null).getAsJsonObject("dataLossProtection"); //$NON-NLS-1$

        assertFalse(protection.get("dataLossCompared").getAsBoolean()); //$NON-NLS-1$
        assertEquals(0, protection.get("pendingDataLossCount").getAsInt()); //$NON-NLS-1$
        assertTrue("no address is named for a comparison that was not made", //$NON-NLS-1$
            protection.getAsJsonArray("pendingDataLoss").isEmpty()); //$NON-NLS-1$
        assertTrue(protection.get("dataLossCheck").getAsString() //$NON-NLS-1$
            .startsWith("not compared: this infobase has no synchronization baseline")); //$NON-NLS-1$
        assertFalse("nothing is promised about a comparison that was not made", //$NON-NLS-1$
            protection.has("nextStep")); //$NON-NLS-1$
        assertEquals(List.of("getUpdateState"), manager.calls); //$NON-NLS-1$
    }

    /**
     * A load recorded on the stored copy is named in infobaseChangeCheck, and the inspection still
     * asks the environment for nothing beyond the update state.
     */
    @Test
    public void anInspectionNamesALoadThatReplacedTheInfobaseAndStartsNothing()
    {
        RecordingManager manager = new RecordingManager();
        DumpInfoProbe.Reading marked = DumpInfoProbe.reading("copy.xml", "2.7", "2.7", "8.3.27", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            InfobaseOutsideChange.of("file:e:/bases/demo", "same", 10), //$NON-NLS-1$ //$NON-NLS-2$
            InfobaseOutsideChange.of("file:e:/bases/demo", "same", 10) //$NON-NLS-1$ //$NON-NLS-2$
                .withLoad("E:/snaps/before.dt", "2026-09-29T10:00:00Z")); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject body = JsonParser.parseString(DatabaseSyncInspector.inspect(manager.asManager(),
            infobaseId -> Boolean.FALSE, found(), applicationWithInfobase("app-1", "Demo"), //$NON-NLS-1$ //$NON-NLS-2$
            "Demo", "app-1", false, "Demo", marked)).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(body.get("success").getAsBoolean()); //$NON-NLS-1$
        String line = body.get("infobaseChangeCheck").getAsString(); //$NON-NLS-1$
        assertTrue(line.contains("E:/snaps/before.dt")); //$NON-NLS-1$
        assertTrue(line.contains("fullUpdate=true")); //$NON-NLS-1$
        assertTrue(body.get("nothingStarted").getAsBoolean()); //$NON-NLS-1$
        assertEquals(List.of("getUpdateState"), manager.calls); //$NON-NLS-1$
    }

    /** With no comparison behind it at all, the answer says that rather than staying quiet. */
    @Test
    public void anInspectionWithNoComparisonSaysThereWasNone()
    {
        JsonObject protection = inspect(new RecordingManager(), null,
            applicationWithInfobase("app-1", "Демо"), infobaseId -> null) //$NON-NLS-1$ //$NON-NLS-2$
                .getAsJsonObject("dataLossProtection"); //$NON-NLS-1$

        assertFalse(protection.get("dataLossCompared").getAsBoolean()); //$NON-NLS-1$
        assertEquals("not compared: no comparison was made for this project", //$NON-NLS-1$
            protection.get("dataLossCheck").getAsString()); //$NON-NLS-1$
        assertFalse(protection.has("baseline")); //$NON-NLS-1$
    }

    /**
     * An application with no infobase of its own has no preference to read, and the answer says so
     * instead of asking with nothing: unknown is a state of the environment, not a guess.
     */
    @Test
    public void anApplicationWithoutAnInfobaseHasNoPreferenceToRead()
    {
        RecordingManager manager = new RecordingManager();
        DatabaseUpdater.PromptAccess preferences = infobaseId -> {
            throw new AssertionError("no infobase, so there is nothing to ask about"); //$NON-NLS-1$
        };

        JsonObject body = inspect(manager, found(), applicationWithoutInfobase("app-2", "Расширение"), //$NON-NLS-1$ //$NON-NLS-2$
            preferences);

        assertEquals("unknown", body.get("restructureConfirmationPrompt").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Расширение", body.get("applicationName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the loss is still named, preference or not", 2, //$NON-NLS-1$
            body.getAsJsonObject("dataLossProtection").get("pendingDataLossCount").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
