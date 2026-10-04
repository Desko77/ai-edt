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

import java.io.Serializable;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.support.LaunchConfigAccess;

/**
 * What {@code get_applications} says about one application.
 * <p>
 * Besides its id and update state, an application names what it is, where its infobase lives, which
 * operations refuse it when it is not an infobase and what it still answers to, the launch
 * configurations bound to it, and the next call when it is not up to date.
 * </p>
 */
public class AnApplicationSaysWhatItIsAndWhatReachesItTest
{
    /** Stands in for an interface declared by another bundle. */
    public interface ServerLike
    {
        // marker
    }

    /** Implements the stand-in through a superclass. */
    public static class Base implements ServerLike, Serializable
    {
        private static final long serialVersionUID = 1L;
    }

    /** Implements nothing directly. */
    public static final class Derived extends Base
    {
        private static final long serialVersionUID = 1L;
    }

    private static ApplicationsReader.ApplicationFacts fileInfobase()
    {
        ApplicationsReader.ApplicationFacts facts = new ApplicationsReader.ApplicationFacts();
        facts.id = "app-1"; //$NON-NLS-1$
        facts.name = "Demo"; //$NON-NLS-1$
        facts.typeId = "com.e1c.g5.dt.applications.type.infobase"; //$NON-NLS-1$
        facts.typeName = "Infobase"; //$NON-NLS-1$
        facts.kind = "infobase"; //$NON-NLS-1$
        facts.updateState = "INCREMENTAL_UPDATE_REQUIRED"; //$NON-NLS-1$
        facts.location = "file"; //$NON-NLS-1$
        facts.path = "C:/bases/demo"; //$NON-NLS-1$
        facts.infobaseTitle = "Demo base"; //$NON-NLS-1$
        facts.launchConfigurations.add(new String[] { "Demo thin", "thin" }); //$NON-NLS-1$ //$NON-NLS-2$
        return facts;
    }

    @Test
    public void aFileInfobaseNamesItsPathItsLaunchesAndTheNextStep()
    {
        JsonObject json = ApplicationsReader.render(fileInfobase());

        assertEquals("app-1", json.get("id").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("infobase", json.get("applicationKind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Infobase", json.get("typeName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Needs an incremental update", json.get("updateStateDescription").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject infobase = json.getAsJsonObject("infobase"); //$NON-NLS-1$
        assertEquals("file", infobase.get("location").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("C:/bases/demo", infobase.get("path").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an infobase refuses none of them", json.has("operationsNotAvailable")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an infobase answers to everything", json.has("capabilities")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray launches = json.getAsJsonArray("launchConfigurations"); //$NON-NLS-1$
        assertEquals(1, launches.size());
        assertEquals("Demo thin", launches.get(0).getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("thin", launches.get(0).getAsJsonObject().get("client").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("infobase_admin operation=update_database applicationId=app-1", //$NON-NLS-1$
            json.get("updateNextStep").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void aServerApplicationListsTheOperationsThatRefuseIt()
    {
        ApplicationsReader.ApplicationFacts facts = new ApplicationsReader.ApplicationFacts();
        facts.id = "srv-1"; //$NON-NLS-1$
        facts.name = "Standalone"; //$NON-NLS-1$
        facts.kind = "server"; //$NON-NLS-1$
        facts.updateState = "UNKNOWN"; //$NON-NLS-1$

        JsonObject json = ApplicationsReader.render(facts);

        assertEquals("server", json.get("applicationKind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(json.has("infobase")); //$NON-NLS-1$
        JsonArray refused = json.getAsJsonArray("operationsNotAvailable"); //$NON-NLS-1$
        assertEquals(ApplicationsReader.INFOBASE_ONLY_OPERATIONS.size(), refused.size());
        assertTrue(refused.toString().contains("extension_workshop install_extension")); //$NON-NLS-1$
        assertTrue(refused.toString().contains("infobase_admin read_event_log")); //$NON-NLS-1$
        assertTrue(refused.toString().contains("config_io export_database_configuration")); //$NON-NLS-1$
        assertEquals("sync_control is not suggested to an application that refuses it", //$NON-NLS-1$
            "infobase_admin operation=update_database applicationId=srv-1", //$NON-NLS-1$
            json.get("updateNextStep").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void aServerApplicationStillLaunchesUpdatesAndRunsTests()
    {
        ApplicationsReader.ApplicationFacts facts = new ApplicationsReader.ApplicationFacts();
        facts.id = "srv-2"; //$NON-NLS-1$
        facts.name = "Standalone"; //$NON-NLS-1$
        facts.kind = "server"; //$NON-NLS-1$

        JsonObject capabilities = ApplicationsReader.render(facts).getAsJsonObject("capabilities"); //$NON-NLS-1$

        assertTrue(capabilities.get("launch").getAsBoolean()); //$NON-NLS-1$
        assertTrue(capabilities.get("update").getAsBoolean()); //$NON-NLS-1$
        assertTrue(capabilities.get("debug").getAsBoolean()); //$NON-NLS-1$
        assertTrue(capabilities.get("testRuns").getAsBoolean()); //$NON-NLS-1$
        assertFalse("extension management drives the Designer of an infobase it does not hold", //$NON-NLS-1$
            capabilities.get("extensionManagement").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void anApplicationOfAnUnknownTypeAnswersUnknownButForExtensions()
    {
        ApplicationsReader.ApplicationFacts facts = new ApplicationsReader.ApplicationFacts();
        facts.id = "ext-1"; //$NON-NLS-1$
        facts.name = "Something else"; //$NON-NLS-1$
        facts.kind = "other"; //$NON-NLS-1$

        JsonObject capabilities = ApplicationsReader.render(facts).getAsJsonObject("capabilities"); //$NON-NLS-1$

        assertEquals("unknown", capabilities.get("launch").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("unknown", capabilities.get("update").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("unknown", capabilities.get("debug").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("unknown", capabilities.get("testRuns").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(capabilities.get("extensionManagement").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void anUnreadableStateKeepsItsErrorAndAnUpToDateOneHasNoNextStep()
    {
        ApplicationsReader.ApplicationFacts failed = fileInfobase();
        failed.updateState = "ERROR"; //$NON-NLS-1$
        failed.updateStateError = "infobase is locked"; //$NON-NLS-1$
        JsonObject json = ApplicationsReader.render(failed);
        assertEquals("infobase is locked", json.get("updateStateError").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(json.has("updateStateDescription")); //$NON-NLS-1$

        ApplicationsReader.ApplicationFacts current = fileInfobase();
        current.updateState = "UPDATED"; //$NON-NLS-1$
        assertFalse(ApplicationsReader.render(current).has("updateNextStep")); //$NON-NLS-1$
    }

    @Test
    public void theClientIsNamedByTheLaunchAttributes()
    {
        assertEquals("auto", ApplicationsReader.clientOf(true, LaunchConfigAccess.CLIENT_TYPE_THIN)); //$NON-NLS-1$
        assertEquals("thin", ApplicationsReader.clientOf(false, LaunchConfigAccess.CLIENT_TYPE_THIN)); //$NON-NLS-1$
        assertEquals("thick", ApplicationsReader.clientOf(false, LaunchConfigAccess.CLIENT_TYPE_THICK)); //$NON-NLS-1$
        assertEquals("web", ApplicationsReader.clientOf(false, LaunchConfigAccess.CLIENT_TYPE_WEB)); //$NON-NLS-1$
        assertEquals("", ApplicationsReader.clientOf(false, "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anInterfaceIsFoundThroughTheSuperclass()
    {
        assertTrue(ApplicationsReader.implementsInterfaceNamed(Derived.class, ServerLike.class.getName()));
        assertFalse(ApplicationsReader.implementsInterfaceNamed(String.class, ServerLike.class.getName()));
        assertFalse(ApplicationsReader.implementsInterfaceNamed(null, ServerLike.class.getName()));
    }

    @Test
    public void theDebuggerSchemaSendsTheCallerToAToolThatExists()
    {
        String schema = new LaunchDebuggerTool().getInputSchema();
        assertFalse(schema.contains("list_applications")); //$NON-NLS-1$
        assertTrue(schema.contains("get_applications")); //$NON-NLS-1$
        assertNull(ApplicationsReader.updateNextStep("UPDATED", "x", "infobase")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
