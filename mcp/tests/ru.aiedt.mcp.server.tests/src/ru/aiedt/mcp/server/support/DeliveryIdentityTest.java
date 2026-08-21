/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Test;

/**
 * Guards the reading of a delivery's identity.
 * <p>
 * <b>Why it matters that this is right.</b> The identity is what stops a comparison being run
 * against the wrong ancestor. That failure is silent - the run succeeds and every attribution comes
 * out inverted - so a reader that quietly returns nothing would disable the check without disabling
 * the comparison.
 * </p>
 */
public class DeliveryIdentityTest
{
    private final java.util.List<Path> made = new java.util.ArrayList<>();

    /** Every directory this test made, removed afterwards. */
    @After
    public void removeWhatWasMade()
    {
        for (Path root : made)
        {
            try (java.util.stream.Stream<Path> walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try
                    {
                        Files.deleteIfExists(path);
                    }
                    catch (IOException leftBehind)
                    {
                        // A temporary directory that survives the run is untidy, not a failure.
                    }
                });
            }
            catch (IOException gone)
            {
                // Already removed.
            }
        }
    }

    private Path newFolder() throws IOException
    {
        Path root = Files.createTempDirectory("aiedt-delivery"); //$NON-NLS-1$
        made.add(root);
        return root;
    }

    private Path edtDelivery(String name, String vendor, String version, String uuid)
        throws IOException
    {
        Path root = newFolder();
        Path configuration = root.resolve("src").resolve("Configuration");
        Files.createDirectories(configuration);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<mdclass:Configuration xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" "
            + "uuid=\"" + uuid + "\">\n"
            + "  <name>" + name + "</name>\n"
            + "  <vendor>" + vendor + "</vendor>\n"
            + "  <version>" + version + "</version>\n"
            + "</mdclass:Configuration>\n";
        Files.write(configuration.resolve("Configuration.mdo"), xml.getBytes(StandardCharsets.UTF_8));
        return root;
    }

    private Path designerDelivery(String name, String vendor, String uuid) throws IOException
    {
        Path root = newFolder();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<MetaDataObject xmlns=\"http://v8.1c.ru/8.3/MDClasses\">\n"
            + "  <Configuration uuid=\"" + uuid + "\">\n"
            + "    <Properties>\n"
            + "      <Name>" + name + "</Name>\n"
            + "      <Vendor>" + vendor + "</Vendor>\n"
            + "    </Properties>\n"
            + "  </Configuration>\n"
            + "</MetaDataObject>\n";
        Files.write(root.resolve("Configuration.xml"), xml.getBytes(StandardCharsets.UTF_8));
        return root;
    }

    @Test
    public void readsAnEdtProject() throws IOException
    {
        Path root = edtDelivery("БиблиотекаСтандартныхПодсистемДемо", "Фирма \"1С\"", "3.1.5.446",
            "66193438-abc5-410b-a1f1-a204102d1a62");
        DeliveryIdentity identity = DeliveryIdentity.read(root);
        assertNull(identity.cannotTell, identity.cannotTell);
        assertEquals("БиблиотекаСтандартныхПодсистемДемо", identity.name);
        assertEquals("Фирма \"1С\"", identity.vendor);
        assertEquals("3.1.5.446", identity.version);
        assertEquals("66193438-abc5-410b-a1f1-a204102d1a62", identity.uuid);
        assertEquals("EDT project", identity.shape);
    }

    @Test
    public void readsADesignerExport() throws IOException
    {
        // Both layouts are legitimate sides of a comparison, and a reader that knew only one would
        // leave half the real cases unchecked.
        Path root = designerDelivery("AIDT_Poligon", "Acme",
            "b3ca37cc-d629-401d-8489-09861e261783");
        DeliveryIdentity identity = DeliveryIdentity.read(root);
        assertNull(identity.cannotTell, identity.cannotTell);
        assertEquals("AIDT_Poligon", identity.name);
        assertEquals("Acme", identity.vendor);
        assertEquals("b3ca37cc-d629-401d-8489-09861e261783", identity.uuid);
        assertEquals("Designer export", identity.shape);
    }

    @Test
    public void anUnsetVendorReadsAsAbsentRatherThanEmpty() throws IOException
    {
        // A configuration may state no vendor, and its export will not either. Absence has to be
        // distinguishable from disagreement, or every such pair would be refused.
        Path root = edtDelivery("配置", "", "", "11111111-1111-1111-1111-111111111111");
        DeliveryIdentity identity = DeliveryIdentity.read(root);
        assertNull(identity.cannotTell, identity.cannotTell);
        assertNull(identity.vendor);
        assertNull(identity.version);
    }

    @Test
    public void aDirectoryThatIsNoConfigurationIsRefusedByName() throws IOException
    {
        Path root = newFolder();
        DeliveryIdentity identity = DeliveryIdentity.read(root);
        assertNotNull("silence here would disable the origin check without disabling the "
            + "comparison", identity.cannotTell);
        assertTrue(identity.cannotTell, identity.cannotTell.contains("Configuration"));
    }

    @Test
    public void anAbsentDirectoryIsRefusedByName()
    {
        DeliveryIdentity identity =
            DeliveryIdentity.read(java.nio.file.Paths.get("no such directory at all"));
        assertNotNull(identity.cannotTell);
        assertTrue(identity.cannotTell, identity.cannotTell.contains("no directory"));
    }

    @Test
    public void unreadableXmlIsRefusedRatherThanReadAsEmpty() throws IOException
    {
        Path root = newFolder();
        Path configuration = root.resolve("src").resolve("Configuration");
        Files.createDirectories(configuration);
        Files.write(configuration.resolve("Configuration.mdo"),
            "this is not xml".getBytes(StandardCharsets.UTF_8));
        DeliveryIdentity identity = DeliveryIdentity.read(root);
        assertNotNull("a broken file must not pass as a configuration with no name",
            identity.cannotTell);
    }

    @Test
    public void theDescriptionNamesWhatWasFound() throws IOException
    {
        Path root = edtDelivery("Demo", "Acme", "1.2.3", "22222222-2222-2222-2222-222222222222");
        assertEquals("Demo 1.2.3 by Acme", DeliveryIdentity.read(root).toString());
    }
}
