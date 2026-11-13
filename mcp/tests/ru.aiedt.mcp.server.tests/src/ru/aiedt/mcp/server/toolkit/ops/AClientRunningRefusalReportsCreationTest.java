package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import org.eclipse.debug.core.*;
import org.junit.Test;

public class AClientRunningRefusalReportsCreationTest
{
    @Test public void existingConfigurationRefusalReportsNoMutation() { check(false); }
    @Test public void createdConfigurationRefusalDoesNotClaimNoMutation() { check(true); }
    private static void check(boolean created) {
        ILaunchConfiguration config = proxy(ILaunchConfiguration.class, (m,a) -> {
            if(m.equals("getName")) return "named";
            if(m.equals("getAttribute")) return "app";
            return null;
        });
        ILaunch launch = proxy(ILaunch.class, (m,a) -> {
            if(m.equals("isTerminated")) return false;
            if(m.equals("getLaunchConfiguration")) return config;
            if(m.equals("getAttribute")) return "app";
            return null;
        });
        ILaunchManager manager = proxy(ILaunchManager.class, (m,a) -> {
            if(m.equals("getLaunches")) return new ILaunch[]{launch};
            throw new AssertionError("Must not launch or update: " + m);
        });
        String answer = ClientSessionStarter.decideAndLaunch(manager, config, null,
            DebugSessionStarter.launchUpdate(false, false), false, "request", null, null,
            null, false, created, null).replaceAll("\\s+", "");
        assertTrue(answer, answer.contains("\"alreadyRunning\":true"));
        assertTrue(answer, answer.contains("\"started\":false"));
        assertTrue(answer, answer.contains("\"mutationStarted\":" + created));
        assertTrue(answer, answer.contains("\"configurationCreated\":" + created));
    }
    @Test public void preUpdateRefusalReportsCreatedConfiguration() {
        for (boolean created : new boolean[]{false, true}) {
            ILaunchConfiguration config = proxy(ILaunchConfiguration.class, (m,a) ->
                m.equals("getName") ? "named" : m.equals("getAttribute") ? "app" : null);
            ILaunchManager manager = proxy(ILaunchManager.class, (m,a) -> {
                if(m.equals("getLaunches")) return new ILaunch[0];
                throw new AssertionError("Must not update or launch: " + m);
            });
            String answer = ClientSessionStarter.decideAndLaunch(manager, config, null,
                new DebugSessionStarter.LaunchUpdate(true, false, false, "update refused"),
                false, null, null, null, null, false, created, null).replaceAll("\\s+", "");
            assertTrue(answer, answer.contains("\"started\":false"));
            assertTrue(answer, answer.contains("\"configurationCreated\":" + created));
            assertTrue(answer, answer.contains("\"mutationStarted\":" + created));
        }
    }

    @Test public void exceptionDuringDecisionDoesNotClaimNothingStarted() {
        ILaunchConfiguration config = proxy(ILaunchConfiguration.class, (m,a) -> {
            throw new IllegalStateException("decision failed");
        });
        String answer = ClientSessionStarter.decideAndLaunch(null, config, null,
            DebugSessionStarter.launchUpdate(false, false), false, null, null, null,
            null, false, true, null).replaceAll("\\s+", "");
        assertFalse(answer, answer.contains("\"started\":false"));
        assertTrue(answer, answer.contains("\"launchAttempted\":false"));
        assertTrue(answer, answer.contains("\"mutationOutcome\":\"unknown\""));
        assertTrue(answer, answer.contains("\"configurationCreated\":true"));
    }

    private interface Call { Object invoke(String method, Object[] args); }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Call call) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (p,m,a) -> call.invoke(m.getName(),a));
    }
}
