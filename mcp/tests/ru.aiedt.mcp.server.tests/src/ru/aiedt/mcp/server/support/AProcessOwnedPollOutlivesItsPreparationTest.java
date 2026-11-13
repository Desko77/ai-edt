/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.support;

import static org.junit.Assert.*;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.Test;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/** Routing proof for a process owner whose preparation entry no longer represents its lifetime. */
public class AProcessOwnedPollOutlivesItsPreparationTest
{
    private static final String KEY = "process-owned-poll-fixture";
    private static final String STARTER = "synthetic_process_owner";
    private static final IMcpTool TOOL = new IMcpTool()
    {
        public String getName() { return STARTER; }
        public String getDescription() { return "Synthetic owned poll route"; }
        public String getInputSchema() { return "{}"; }
        public String execute(Map<String, String> arguments) { throw new AssertionError("Routing must not execute"); }
        public String resumes(String domain, String operation)
        {
            return "vanessa".equals(domain) && "run".equals(operation) ? STARTER : null;
        }
    };

    private static boolean route(String key, IMcpTool tool, Map<String, String> arguments) throws Exception
    {
        Method method = ToolRoad.class.getDeclaredMethod("isOwnPoll", String.class, IMcpTool.class, Map.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, key, tool, arguments);
    }

    private static boolean owner(String key, String starter, Map<String, String> arguments)
    {
        return KEY.equals(key) && STARTER.equals(starter)
            && Map.of("operation", "run", "runKey", KEY).equals(arguments);
    }

    @Test public void declaredOwnerCanPollWithoutAnyPreparationEntry() throws Exception
    {
        PendingWorkRegistry domain = PendingWorkRegistry.VANESSA;
        PendingWorkRegistry.OwnedPoll before = domain.ownedPoll();
        try
        {
            domain.ownedPollsWith(AProcessOwnedPollOutlivesItsPreparationTest::owner);
            assertNull(domain.get(KEY));
            assertTrue("a live process-owned poll must outlive preparation tracking",
                route(KEY, TOOL, Map.of("operation", "run", "runKey", KEY)));
        }
        finally { domain.ownedPollsWith(before); }
    }

    @Test public void declaredOwnerCanPollAfterPreparationCompleted() throws Exception
    {
        PendingWorkRegistry domain = PendingWorkRegistry.VANESSA;
        PendingWorkRegistry.OwnedPoll before = domain.ownedPoll();
        PendingWorkRegistry.PendingEntry entry = domain.getOrStart(KEY, () -> "prepared");
        try
        {
            assertEquals("prepared", entry.await(2000));
            domain.ownedPollsWith(AProcessOwnedPollOutlivesItsPreparationTest::owner);
            assertTrue("a completed preparation is not proof that the owned process exited",
                route(KEY, TOOL, Map.of("operation", "run", "runKey", KEY)));
        }
        finally { domain.ownedPollsWith(before); domain.remove(KEY, entry); }
    }

    @Test public void wrongOperationStarterKeyAndExtraArgumentsFailClosed() throws Exception
    {
        PendingWorkRegistry domain = PendingWorkRegistry.VANESSA;
        PendingWorkRegistry.OwnedPoll before = domain.ownedPoll();
        try
        {
            domain.ownedPollsWith(AProcessOwnedPollOutlivesItsPreparationTest::owner);
            assertFalse(route(KEY, TOOL, Map.of("operation", "other", "runKey", KEY)));
            assertFalse(route("unknown-owned-key", TOOL, Map.of("operation", "run", "runKey", "unknown-owned-key")));
            assertFalse(route(KEY, TOOL, Map.of("operation", "run", "runKey", KEY, "featurePath", "different.feature")));
            assertFalse(domain.isOwnedLivePoll(KEY, "another_starter", Map.of("operation", "run", "runKey", KEY)));
            assertFalse(route(KEY, null, Map.of("operation", "run", "runKey", KEY)));
            IMcpTool undeclared = new IMcpTool()
            {
                public String getName() { return STARTER; }
                public String getDescription() { return "No declared resumption"; }
                public String getInputSchema() { return "{}"; }
                public String execute(Map<String, String> arguments) { throw new AssertionError("Must not execute"); }
            };
            assertFalse("name equality cannot replace an explicit resumption declaration",
                route(KEY, undeclared, Map.of("operation", "run", "runKey", KEY)));
        }
        finally { domain.ownedPollsWith(before); }
    }

    @Test public void missingOrThrowingOwnershipCallbackCannotGrantAPoll() throws Exception
    {
        PendingWorkRegistry domain = PendingWorkRegistry.VANESSA;
        PendingWorkRegistry.OwnedPoll before = domain.ownedPoll();
        try
        {
            domain.ownedPollsWith(null);
            assertFalse(route(KEY, TOOL, Map.of("operation", "run", "runKey", KEY)));
            domain.ownedPollsWith((key, starter, arguments) -> { throw new IllegalStateException("synthetic observer failure"); });
            assertFalse(route(KEY, TOOL, Map.of("operation", "run", "runKey", KEY)));
            assertFalse(domain.isOwnedLivePoll(KEY, STARTER, Map.of("operation", "run", "runKey", KEY)));
        }
        finally { domain.ownedPollsWith(before); }
    }
}
