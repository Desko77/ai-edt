package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;

/** Public preflight contracts do not require a launched client or an infobase. */
public class AVanessaScenarioDefaultsToTestManagerTest
{
    @Test public void schemaDocumentsManagerDefaultForArbitraryScenarios()
    {
        assertFalse(new VanessaTool().getInputSchema().contains("Start as test manager (default false)"));
    }
    @Test public void malformedAddinRefusesBeforeRuntimeLookup()
    {
        String answer = new VanessaTool().execute(Map.of("vanessaExt", "perhaps"));
        assertTrue(answer, answer.contains("vanessaExt must be a boolean"));
    }
    @Test public void timeoutIsDocumentedAsObservationNotProcessKill()
    {
        assertTrue(new VanessaTool().getInputSchema().contains("observation deadline"));
    }
    @Test public void schemaExposesExplicitAddinForAllInputModes()
    {
        assertTrue(new VanessaTool().getInputSchema().contains("\"vanessaExt\""));
    }
}
