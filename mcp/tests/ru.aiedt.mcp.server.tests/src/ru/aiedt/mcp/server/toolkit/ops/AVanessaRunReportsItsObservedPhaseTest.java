package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;

public class AVanessaRunReportsItsObservedPhaseTest
{
    @Test public void arbitraryScenarioManagerDefaultsOnButExplicitFalseSurvives()
    {
        assertTrue(VanessaTool.wantsTestManager(null));
        assertFalse(VanessaTool.wantsTestManager(Boolean.FALSE));
        assertFalse(VanessaTool.wantsTheClientTheFormNeeds(false, null));
    }
    @Test public void addinIsExplicitAndComposedActionsCannotTurnItOff()
    {
        assertTrue(VanessaTool.addinKeys(false, Boolean.TRUE).get("ИспользоватьКомпонентуVanessaExt").getAsBoolean());
        assertNull(VanessaTool.addinKeys(false, null));
        assertNotNull(VanessaTool.addinRefusal(true, Boolean.FALSE));
        assertNull(VanessaTool.addinRefusal(false, Boolean.FALSE));
        assertFalse(VanessaTool.addinKeys(false, Boolean.FALSE).get("ИспользоватьКомпонентуVanessaExt").getAsBoolean());
        assertTrue(VanessaTool.addinKeys(true, null).get("ИспользоватьКомпонентуVanessaExt").getAsBoolean());
        for (String key : new String[] {"ИспользоватьКомпонентуVanessaExt", "useaddin"})
        {
            String[] refusal = new String[1];
            VanessaTool.extraParams("{\"" + key + "\":true}", refusal);
            assertNotNull(refusal[0]);
        }
    }
    @Test public void explicitManagerOffGetsActionableUiTypeHintOnlyForKnownError()
    {
        assertNotNull(VanessaTool.uiTypeHint(false, "Тип не определен (ТестируемаяГруппаФормы)"));
        assertNull(VanessaTool.uiTypeHint(true, "Тип не определен (ТестируемаяГруппаФормы)"));
        assertNull(VanessaTool.uiTypeHint(false, "Connection failed"));
    }
}
