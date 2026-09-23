/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.support.naparnik.BundleCopy;
import ru.aiedt.mcp.server.support.naparnik.NaparnikAccessException;
import ru.aiedt.mcp.server.support.naparnik.NaparnikHost;
import ru.aiedt.mcp.server.support.naparnik.OsgiNaparnikHost;

/**
 * The 1C:Naparnik bridge answers {@code status} from a seam the test replaces.
 * <p>
 * Refusal sentences are English. The specification wrote them in Russian; the facts those sentences
 * carry (not installed, the found version, the supported version, both copies, the failed link) are
 * what these tests hold.
 * </p>
 */
public class NaparnikStatusTest
{
    private static final String SUPPORTED = "1.0.7.v202608311234"; //$NON-NLS-1$

    private static final String OLDER = "1.0.5.v202601010000"; //$NON-NLS-1$

    @Test
    public void onlyTheSupportedVersionIsInPolicy()
    {
        JsonObject doc = status(installation(SUPPORTED, "RESOLVED"), false); //$NON-NLS-1$

        assertTrue(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(doc.get("inPolicy").getAsBoolean()); //$NON-NLS-1$
        assertEquals(NaparnikTool.SUPPORTED_VERSION, doc.get("supportedVersion").getAsString()); //$NON-NLS-1$
        assertFalse(doc.get("bridgeEnabled").getAsBoolean()); //$NON-NLS-1$
        assertFalse(doc.has("refusal")); //$NON-NLS-1$
        assertFalse(doc.has("links")); //$NON-NLS-1$
        assertEquals(4, doc.getAsJsonArray("bundles").size()); //$NON-NLS-1$
        for (JsonElement element : doc.getAsJsonArray("bundles")) //$NON-NLS-1$
        {
            JsonObject bundle = element.getAsJsonObject();
            assertEquals(SUPPORTED, bundle.get("version").getAsString()); //$NON-NLS-1$
            assertTrue(bundle.get("chosen").getAsBoolean()); //$NON-NLS-1$
        }
    }

    @Test
    public void anOlderVersionIsRefusedByName()
    {
        JsonObject doc = status(installation(OLDER, "RESOLVED"), false); //$NON-NLS-1$

        assertFalse(doc.get("inPolicy").getAsBoolean()); //$NON-NLS-1$
        String refusal = doc.get("refusal").getAsString(); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("1.0.5")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("1.0.7")); //$NON-NLS-1$
        assertFalse(refusal, refusal.contains("not installed")); //$NON-NLS-1$
    }

    @Test
    public void aResolvedOlderDonorVersionIsRefusedByName()
    {
        JsonObject doc = status(installation("1.0.2.v202501010000", "RESOLVED"), false); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(doc.get("inPolicy").getAsBoolean()); //$NON-NLS-1$
        String refusal = doc.get("refusal").getAsString(); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("1.0.2")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("1.0.7")); //$NON-NLS-1$
    }

    @Test
    public void aResolvedSupportedCopyBeatsAnUnresolvedOlderOne()
    {
        FakeHost host = installation(SUPPORTED, "RESOLVED"); //$NON-NLS-1$
        host.add(new BundleCopy("com.e1c.edt.ai", OLDER, "INSTALLED", new Object())); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject doc = status(host, false);

        assertTrue(doc.get("inPolicy").getAsBoolean()); //$NON-NLS-1$
        assertFalse(doc.has("refusal")); //$NON-NLS-1$
        JsonObject chosen = bundle(doc, "com.e1c.edt.ai", true); //$NON-NLS-1$
        JsonObject left = bundle(doc, "com.e1c.edt.ai", false); //$NON-NLS-1$
        assertEquals(SUPPORTED, chosen.get("version").getAsString()); //$NON-NLS-1$
        assertEquals(OLDER, left.get("version").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void twoResolvedSingletonCopiesAreRefusedByName()
    {
        FakeHost host = installation("1.0.7.left", "RESOLVED"); //$NON-NLS-1$ //$NON-NLS-2$
        host.add(new BundleCopy("com.e1c.edt.ai", "1.0.7.right", "RESOLVED", new Object())); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject doc = status(host, false);

        assertFalse(doc.get("inPolicy").getAsBoolean()); //$NON-NLS-1$
        String refusal = doc.get("refusal").getAsString(); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("1.0.7.left")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("1.0.7.right")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("1.0.7")); //$NON-NLS-1$
    }

    @Test
    public void nothingInstalledIsRefused()
    {
        JsonObject doc = status(new FakeHost(), false);

        assertFalse(doc.get("inPolicy").getAsBoolean()); //$NON-NLS-1$
        assertEquals(0, doc.getAsJsonArray("bundles").size()); //$NON-NLS-1$
        assertTrue(doc.get("refusal").getAsString().contains("not installed")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void installedButNotResolvedIsRefused()
    {
        JsonObject doc = status(installation(SUPPORTED, "INSTALLED"), false); //$NON-NLS-1$

        assertFalse(doc.get("inPolicy").getAsBoolean()); //$NON-NLS-1$
        String refusal = doc.get("refusal").getAsString(); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("not resolved")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains(SUPPORTED));
        for (JsonElement element : doc.getAsJsonArray("bundles")) //$NON-NLS-1$
        {
            assertFalse(element.getAsJsonObject().get("chosen").getAsBoolean()); //$NON-NLS-1$
        }
    }

    @Test
    public void anUnresolvedOlderVersionNamesBothVersions()
    {
        JsonObject doc = status(installation("1.0.2.v202501010000", "INSTALLED"), false); //$NON-NLS-1$ //$NON-NLS-2$

        String refusal = doc.get("refusal").getAsString(); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("not resolved")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("1.0.2")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("1.0.7")); //$NON-NLS-1$
    }

    @Test
    public void passiveStatusDoesNotTouchTheBridge()
    {
        FakeHost host = installation(SUPPORTED, "RESOLVED"); //$NON-NLS-1$

        status(host, false);

        assertEquals(0, host.loads);
        assertEquals(0, host.injectors);
        assertEquals(0, host.facades);
        assertEquals(0, host.toolLists);
    }

    @Test
    public void probeDoesNotActivateAnOutOfPolicyInstall()
    {
        FakeHost host = installation(OLDER, "RESOLVED"); //$NON-NLS-1$

        JsonObject doc = status(host, true);

        assertFalse(doc.get("inPolicy").getAsBoolean()); //$NON-NLS-1$
        assertFalse(doc.has("links")); //$NON-NLS-1$
        assertEquals(0, host.loads);
        assertEquals(0, host.injectors);
    }

    @Test
    public void probeNamesEveryLink()
    {
        FakeHost host = installation(SUPPORTED, "RESOLVED"); //$NON-NLS-1$
        host.tools = List.of("Read", "GetProjects", "Execute"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject doc = status(host, true);

        assertTrue(doc.get("inPolicy").getAsBoolean()); //$NON-NLS-1$
        assertFalse(doc.has("refusal")); //$NON-NLS-1$
        List<String> names = new ArrayList<>();
        for (JsonElement element : doc.getAsJsonArray("links")) //$NON-NLS-1$
        {
            JsonObject link = element.getAsJsonObject();
            assertTrue(link.get("link").getAsString(), link.get("ok").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
            names.add(link.get("link").getAsString()); //$NON-NLS-1$
        }
        assertEquals(List.of(
            "loadClass:com.e1c.edt.ai.IConversationFacade", //$NON-NLS-1$
            "loadClass:com.e1c.edt.ai.IMcpTools", //$NON-NLS-1$
            "loadClass:com.e1c.edt.ai.ui.BaseActivator", //$NON-NLS-1$
            "injector", //$NON-NLS-1$
            "facade", //$NON-NLS-1$
            "tools"), names); //$NON-NLS-1$
        JsonObject tools = doc.getAsJsonObject("tools"); //$NON-NLS-1$
        assertTrue(texts(tools.getAsJsonArray("available")).contains("Execute")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(texts(tools.getAsJsonArray("allowed")).contains("GetProjects")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(texts(tools.getAsJsonArray("allowed")).contains("1C_Find")); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> missing = texts(tools.getAsJsonArray("missing")); //$NON-NLS-1$
        assertTrue(missing.contains("SearchText")); //$NON-NLS-1$
        assertFalse(missing.contains("Read")); //$NON-NLS-1$
        assertFalse(missing.contains("Execute")); //$NON-NLS-1$
        assertEquals(1, host.injectors);
    }

    @Test
    public void aFailedLinkNamesItself()
    {
        FakeHost host = installation(SUPPORTED, "RESOLVED"); //$NON-NLS-1$
        host.failLink = "loadClass:com.e1c.edt.ai.IConversationFacade"; //$NON-NLS-1$

        JsonObject doc = status(host, true);

        assertEquals(1, doc.getAsJsonArray("links").size()); //$NON-NLS-1$
        JsonObject link = doc.getAsJsonArray("links").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("loadClass:com.e1c.edt.ai.IConversationFacade", link.get("link").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(link.get("ok").getAsBoolean()); //$NON-NLS-1$
        assertTrue(link.get("detail").getAsString().contains("IConversationFacade")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(doc.get("refusal").getAsString().contains("IConversationFacade")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(doc.has("tools")); //$NON-NLS-1$
        assertEquals(0, host.injectors);
    }

    @Test
    public void aFacadeFromAnotherBundleIsRefused()
    {
        FakeHost host = installation(SUPPORTED, "RESOLVED"); //$NON-NLS-1$
        host.facadeOwner = new BundleCopy("com.e1c.edt.ai", "9.9.9", "ACTIVE", new Object()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject doc = status(host, true);

        assertFalse(doc.get("inPolicy").getAsBoolean()); //$NON-NLS-1$
        JsonObject link = linkNamed(doc, "facade"); //$NON-NLS-1$
        assertFalse(link.get("ok").getAsBoolean()); //$NON-NLS-1$
        String refusal = doc.get("refusal").getAsString(); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("9.9.9")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains(SUPPORTED));
        assertFalse(doc.has("tools")); //$NON-NLS-1$
    }

    @Test
    public void anInjectorFromAnotherBundleIsRefused()
    {
        FakeHost host = installation(SUPPORTED, "RESOLVED"); //$NON-NLS-1$
        host.activatorOwner = new BundleCopy("com.e1c.edt.ai.ui", "9.9.9", "ACTIVE", new Object()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject doc = status(host, true);

        assertFalse(doc.get("inPolicy").getAsBoolean()); //$NON-NLS-1$
        JsonObject link = linkNamed(doc, "injector"); //$NON-NLS-1$
        assertFalse(link.get("ok").getAsBoolean()); //$NON-NLS-1$
        assertTrue(doc.get("refusal").getAsString().contains("9.9.9")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(linkNamed(doc, "facade") == null); //$NON-NLS-1$
        assertEquals(0, host.facades);
    }

    @Test
    public void aReflectionFailureIsNamed()
    {
        FakeHost host = new FakeHost();
        host.failCopies = "com.e1c.edt.ai"; //$NON-NLS-1$

        JsonObject doc = status(host, false);

        String refusal = doc.get("refusal").getAsString(); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("copies:com.e1c.edt.ai")); //$NON-NLS-1$
        assertFalse(doc.get("inPolicy").getAsBoolean()); //$NON-NLS-1$
        assertFalse(refusal.contains("not installed")); //$NON-NLS-1$
    }

    @Test
    public void theBridgeIsOffByDefault()
    {
        assertFalse(PrefKeys.DEFAULT_NAPARNIK_BRIDGE_ENABLED);
        assertEquals("mcpNaparnikBridgeEnabled", PrefKeys.PREF_NAPARNIK_BRIDGE_ENABLED); //$NON-NLS-1$
        JsonObject doc = status(installation(SUPPORTED, "RESOLVED"), false); //$NON-NLS-1$
        assertFalse(doc.get("bridgeEnabled").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void statusShowsTheBridgeSetting()
    {
        FakeHost host = installation(SUPPORTED, "RESOLVED"); //$NON-NLS-1$
        JsonObject doc = parse(new NaparnikTool(host, Boolean.TRUE).execute(call(false)));

        assertTrue(doc.get("bridgeEnabled").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void readOnlyPresetDisablesNaparnik()
    {
        assertTrue(ToolProfile.READ_ONLY.getDisabledTools().contains("naparnik")); //$NON-NLS-1$
        assertTrue(ToolProfile.DEBUG_AND_TEST.getDisabledTools().contains("naparnik")); //$NON-NLS-1$
        assertTrue(ToolProfile.CODE_REVIEW.getDisabledTools().contains("naparnik")); //$NON-NLS-1$
    }

    @Test
    public void theHostManifestDoesNotNameNaparnik()
        throws Exception
    {
        Path manifest = findManifest();
        String text = Files.readString(manifest);

        assertTrue(manifest.toString(), text.contains("Bundle-SymbolicName: ru.aiedt.mcp.server")); //$NON-NLS-1$
        assertFalse(text, text.contains("com.e1c.edt.ai")); //$NON-NLS-1$
    }

    @Test
    public void helpNamesStatus()
    {
        JsonObject doc = parse(new NaparnikTool(new FakeHost()).execute(Map.of("operation", "help"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        String text = doc.get("text").getAsString(); //$NON-NLS-1$
        assertTrue(text, text.contains("status")); //$NON-NLS-1$
        assertTrue(text, text.contains("probe")); //$NON-NLS-1$
    }

    @Test
    public void anUnknownOperationIsRefused()
    {
        JsonObject doc = parse(new NaparnikTool(new FakeHost()).execute(Map.of("operation", "ask"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(doc.get("error").getAsString().contains("ask")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void statusWithoutAFakeAnswersFromTheRuntime()
    {
        NaparnikHost live = new OsgiNaparnikHost();
        JsonObject doc = parse(new NaparnikTool(live).execute(Map.of("operation", "status"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(doc.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(NaparnikTool.SUPPORTED_VERSION, doc.get("supportedVersion").getAsString()); //$NON-NLS-1$
        assertFalse(doc.has("links")); //$NON-NLS-1$
        String refusal = doc.has("refusal") ? doc.get("refusal").getAsString() : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (doc.toString().contains("1.0.2")) //$NON-NLS-1$
        {
            assertFalse(doc.get("inPolicy").getAsBoolean()); //$NON-NLS-1$
            assertTrue(refusal, refusal.contains("1.0.2")); //$NON-NLS-1$
            assertTrue(refusal, refusal.contains("1.0.7")); //$NON-NLS-1$
        }
        else
        {
            assertFalse(doc.get("inPolicy").getAsBoolean()); //$NON-NLS-1$
            assertTrue(refusal, refusal.contains("not installed") || refusal.contains("not resolved")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static JsonObject status(FakeHost host, boolean probe)
    {
        return parse(new NaparnikTool(host).execute(call(probe)));
    }

    private static Map<String, String> call(boolean probe)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "status"); //$NON-NLS-1$ //$NON-NLS-2$
        if (probe)
        {
            params.put("probe", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return params;
    }

    private static JsonObject parse(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject bundle(JsonObject doc, String name, boolean chosen)
    {
        for (JsonElement element : doc.getAsJsonArray("bundles")) //$NON-NLS-1$
        {
            JsonObject bundle = element.getAsJsonObject();
            if (name.equals(bundle.get("name").getAsString()) //$NON-NLS-1$
                && bundle.get("chosen").getAsBoolean() == chosen) //$NON-NLS-1$
            {
                return bundle;
            }
        }
        fail("no " + name + " chosen=" + chosen + " in " + doc); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return null;
    }

    private static JsonObject linkNamed(JsonObject doc, String name)
    {
        for (JsonElement element : doc.getAsJsonArray("links")) //$NON-NLS-1$
        {
            JsonObject link = element.getAsJsonObject();
            if (name.equals(link.get("link").getAsString())) //$NON-NLS-1$
            {
                return link;
            }
        }
        return null;
    }

    private static List<String> texts(JsonArray array)
    {
        List<String> texts = new ArrayList<>();
        for (JsonElement element : array)
        {
            texts.add(element.getAsString());
        }
        return texts;
    }

    private static Path findManifest()
    {
        Path dir = Path.of("").toAbsolutePath();
        for (int hop = 0; hop < 6 && dir != null; hop++)
        {
            Path nested = dir.resolve("bundles/ru.aiedt.mcp.server/META-INF/MANIFEST.MF"); //$NON-NLS-1$
            if (Files.isRegularFile(nested))
            {
                return nested;
            }
            Path fromRoot = dir.resolve("mcp/bundles/ru.aiedt.mcp.server/META-INF/MANIFEST.MF"); //$NON-NLS-1$
            if (Files.isRegularFile(fromRoot))
            {
                return fromRoot;
            }
            dir = dir.getParent();
        }
        fail("host manifest not found from " + Path.of("").toAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
        return null;
    }

    private static FakeHost installation(String version, String state)
    {
        FakeHost host = new FakeHost();
        host.ai = host.add("com.e1c.edt.ai", version, state); //$NON-NLS-1$
        host.add("com.e1c.edt.ai.context", version, state); //$NON-NLS-1$
        host.ui = host.add("com.e1c.edt.ai.ui", version, state); //$NON-NLS-1$
        host.add("com.e1c.edt.ai.ui.common", version, state); //$NON-NLS-1$
        host.facadeOwner = host.ai;
        host.activatorOwner = host.ui;
        return host;
    }

    /**
     * A stand-in installation. Counts the calls a passive status must not make.
     */
    private static final class FakeHost
        implements NaparnikHost
    {
        private final Map<String, List<BundleCopy>> copies = new LinkedHashMap<>();

        private BundleCopy ai;

        private BundleCopy ui;

        private BundleCopy facadeOwner;

        private BundleCopy activatorOwner;

        private String failCopies;

        private String failLink;

        private List<String> tools = List.of("Read"); //$NON-NLS-1$

        private int loads;

        private int injectors;

        private int facades;

        private int toolLists;

        private BundleCopy add(String name, String version, String state)
        {
            BundleCopy copy = new BundleCopy(name, version, state, new Object());
            add(copy);
            return copy;
        }

        private void add(BundleCopy copy)
        {
            copies.computeIfAbsent(copy.name(), name -> new ArrayList<>()).add(copy);
        }

        @Override
        public List<BundleCopy> copiesOf(String symbolicName)
            throws NaparnikAccessException
        {
            if (symbolicName.equals(failCopies))
            {
                throw new NaparnikAccessException("copies:" + symbolicName, //$NON-NLS-1$
                    "Platform.getBundles failed for " + symbolicName, null); //$NON-NLS-1$
            }
            List<BundleCopy> found = copies.get(symbolicName);
            return found == null ? List.of() : found;
        }

        @Override
        public Class<?> loadClass(BundleCopy bundle, String className)
            throws NaparnikAccessException
        {
            loads++;
            String link = NaparnikHost.loadClassLink(className);
            if (link.equals(failLink))
            {
                throw new NaparnikAccessException(link, "failed to load " + className, null); //$NON-NLS-1$
            }
            return String.class;
        }

        @Override
        public InjectorDoor openInjector(BundleCopy uiBundle, Class<?> baseActivator)
            throws NaparnikAccessException
        {
            injectors++;
            if (NaparnikHost.LINK_INJECTOR.equals(failLink))
            {
                throw new NaparnikAccessException(NaparnikHost.LINK_INJECTOR, "injector failed", null); //$NON-NLS-1$
            }
            return new InjectorDoor(new Object(), activatorOwner);
        }

        @Override
        public FacadeDoor openFacade(Object injector, Class<?> facadeType)
            throws NaparnikAccessException
        {
            facades++;
            if (NaparnikHost.LINK_FACADE.equals(failLink))
            {
                throw new NaparnikAccessException(NaparnikHost.LINK_FACADE, "facade failed", null); //$NON-NLS-1$
            }
            return new FacadeDoor(new Object(), facadeOwner);
        }

        @Override
        public List<String> toolNames(Object injector, Class<?> mcpToolsType)
            throws NaparnikAccessException
        {
            toolLists++;
            if (NaparnikHost.LINK_TOOLS.equals(failLink))
            {
                throw new NaparnikAccessException(NaparnikHost.LINK_TOOLS, "tools failed", null); //$NON-NLS-1$
            }
            return tools;
        }
    }
}
