package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.eclipse.debug.core.*;
import org.junit.Test;

public class AClientNamedBindingMustAgreeTest
{
    @Test public void wrongApplicationRefusesBeforeWorkspaceAccess() {
        refuse(Map.of("launchConfigurationName", "named", "applicationId", "wrong"));
    }
    @Test public void wrongProjectRefusesBeforeWorkspaceAccess() {
        refuse(Map.of("launchConfigurationName", "named", "projectName", "wrong"));
    }
    @Test public void unknownNameNeverFallsBackToPair() {
        refuse(Map.of("launchConfigurationName", "missing", "projectName", "owner", "applicationId", "app"));
    }
    private static void refuse(Map<String,String> params) {
        ILaunchConfigurationType type = proxy(ILaunchConfigurationType.class, (m,a) ->
            m.equals("getIdentifier") ? "com._1c.g5.v8.dt.launching.core.RuntimeClient" : null);
        ILaunchConfiguration config = proxy(ILaunchConfiguration.class, (m,a) -> {
            if (m.equals("getName")) return "named";
            if (m.equals("getType")) return type;
            if (m.equals("getAttribute")) {
                if (a[0].toString().endsWith("ATTR_PROJECT_NAME")) return "owner";
                if (a[0].toString().endsWith("ATTR_APPLICATION_ID")) return "app";
                return a[1];
            }
            return null;
        });
        ILaunchManager manager = proxy(ILaunchManager.class, (m,a) -> {
            if (m.equals("getLaunchConfigurationType")) return type;
            if (m.equals("getLaunchConfigurations")) return new ILaunchConfiguration[]{config};
            throw new AssertionError("Unexpected launch-manager operation: " + m);
        });
        String answer = new ClientSessionStarter() {
            @Override protected ILaunchManager launchManager() { return manager; }
        }.execute(params);
        String compact = answer.replaceAll("\\s+", "");
        assertTrue(answer, compact.contains("\"started\":false"));
        assertTrue(answer, compact.contains("\"mutationStarted\":false"));
        assertTrue(answer, answer.contains(params.get("launchConfigurationName").equals("missing")
            ? "No launch configuration matched" : "does not match"));
    }
    private interface Call { Object invoke(String method, Object[] args); }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (p,m,a) -> call.invoke(m.getName(), a));
    }
}
