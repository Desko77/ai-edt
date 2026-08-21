/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Test;

/**
 * Guards the check that the sides of a comparison belong together.
 * <p>
 * <b>The failure it exists to catch leaves no trace.</b> Point the ancestor at a different
 * configuration and the comparison still runs: the counts look plausible and every attribution is
 * inverted, so what we changed reads as the vendor's work. A bulk decision taken on that applies the
 * wrong rule to everything at once.
 * </p>
 * <p>
 * The project half of the check needs a workspace and is exercised on a stand. What is tested here
 * is the part that decides, from two identities alone, whether they can be compared - and the part
 * that keeps an unreadable path from being dressed up as a provenance mismatch.
 * </p>
 */
public class OriginCheckTest
{
    private final List<Path> made = new ArrayList<>();

    /** Every directory this test made, removed afterwards. */
    @After
    public void removeWhatWasMade()
    {
        for (Path root : made)
        {
            try (Stream<Path> walk = Files.walk(root))
            {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try
                    {
                        Files.deleteIfExists(path);
                    }
                    catch (IOException leftBehind)
                    {
                        // Untidy, not a failure.
                    }
                });
            }
            catch (IOException gone)
            {
                // Already removed.
            }
        }
    }

    private String delivery(String name, String vendor, String version, String uuid)
        throws IOException
    {
        Path root = Files.createTempDirectory("aiedt-origin"); //$NON-NLS-1$
        made.add(root);
        Path configuration = root.resolve("src").resolve("Configuration");
        Files.createDirectories(configuration);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<mdclass:Configuration xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" "
            + "uuid=\"" + uuid + "\">\n  <name>" + name + "</name>\n");
        if (vendor != null)
        {
            xml.append("  <vendor>").append(vendor).append("</vendor>\n");
        }
        if (version != null)
        {
            xml.append("  <version>").append(version).append("</version>\n");
        }
        xml.append("</mdclass:Configuration>\n");
        Files.write(configuration.resolve("Configuration.mdo"),
            xml.toString().getBytes(StandardCharsets.UTF_8));
        return root.toString();
    }

    /** A project name no workspace has, so only the file-side checks run. */
    private static final String NO_PROJECT = "no such project for an origin test"; //$NON-NLS-1$

    @Test
    public void twoVersionsOfOneConfigurationAgree() throws IOException
    {
        String uuid = "66193438-abc5-410b-a1f1-a204102d1a62";
        String newer = delivery("Демо", "Фирма 1С", "3.2.1.505", uuid);
        String older = delivery("Демо", "Фирма 1С", "3.1.5.446", uuid);
        OriginCheck.Verdict verdict = OriginCheck.check(NO_PROJECT, newer, older);
        assertTrue(verdict.mismatches.toString(), verdict.agrees());
        assertNull(OriginCheck.refusal(verdict));
    }

    @Test
    public void differentConfigurationsAreRefused() throws IOException
    {
        // The whole point. Nothing downstream would notice this.
        String newer = delivery("Демо", "Фирма 1С", "3.2.1.505",
            "66193438-abc5-410b-a1f1-a204102d1a62");
        String foreign = delivery("НечтоДругое", "Фирма 1С", "1.0.0.1",
            "11111111-2222-3333-4444-555555555555");
        OriginCheck.Verdict verdict = OriginCheck.check(NO_PROJECT, newer, foreign);
        assertFalse(verdict.agrees());
        String refusal = OriginCheck.refusal(verdict);
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("different configurations"));
        assertTrue("the refusal must offer the way past it, because legitimate cases exist",
            refusal.contains("ignoreOriginMismatch"));
    }

    @Test
    public void theSameVersionOnBothSidesIsRefused() throws IOException
    {
        // Comparing a delivery with itself reports no vendor changes at all, which reads exactly
        // like a delivery that changed nothing. Silence here would be mistaken for good news.
        String uuid = "66193438-abc5-410b-a1f1-a204102d1a62";
        String one = delivery("Демо", "Фирма 1С", "3.2.1.505", uuid);
        String same = delivery("Демо", "Фирма 1С", "3.2.1.505", uuid);
        OriginCheck.Verdict verdict = OriginCheck.check(NO_PROJECT, one, same);
        assertFalse(verdict.agrees());
        assertTrue(verdict.mismatches.toString(),
            verdict.mismatches.toString().contains("3.2.1.505"));
    }

    @Test
    public void differentVendorsAreRefused() throws IOException
    {
        String uuid = "66193438-abc5-410b-a1f1-a204102d1a62";
        String newer = delivery("Демо", "Фирма 1С", "3.2.1.505", uuid);
        String older = delivery("Демо", "Кто-то другой", "3.1.5.446", uuid);
        OriginCheck.Verdict verdict = OriginCheck.check(NO_PROJECT, newer, older);
        assertFalse(verdict.agrees());
        assertTrue(verdict.mismatches.toString(),
            verdict.mismatches.toString().contains("vendors"));
    }

    @Test
    public void anUnstatedVendorIsNotDisagreement() throws IOException
    {
        // A configuration may state no vendor, and its export will not either. Treating absence as
        // disagreement would refuse every such pair - a check nobody could use.
        String uuid = "66193438-abc5-410b-a1f1-a204102d1a62";
        String newer = delivery("Демо", null, "3.2.1.505", uuid);
        String older = delivery("Демо", "Фирма 1С", "3.1.5.446", uuid);
        OriginCheck.Verdict verdict = OriginCheck.check(NO_PROJECT, newer, older);
        assertTrue(verdict.mismatches.toString(), verdict.agrees());
    }

    @Test
    public void anUnreadablePathIsNotAProvenanceMismatch()
    {
        // It is a mistyped path, and saying "the sides do not belong together - pass
        // ignoreOriginMismatch" would send the caller to override a check instead of fixing the
        // argument.
        OriginCheck.Verdict verdict =
            OriginCheck.check(NO_PROJECT, "no such directory anywhere", null);
        assertTrue("an absent directory must not read as disagreement", verdict.agrees());
        assertFalse("but it must still be recorded", verdict.unreadable.isEmpty());
        assertTrue(verdict.unreadable.toString(),
            verdict.unreadable.toString().contains("otherPath"));
    }

    @Test
    public void twoSidedComparisonHasNothingToCompare() throws IOException
    {
        // No ancestor is the ordinary two-sided case, not a fault.
        String newer = delivery("Демо", "Фирма 1С", "3.2.1.505",
            "66193438-abc5-410b-a1f1-a204102d1a62");
        OriginCheck.Verdict verdict = OriginCheck.check(NO_PROJECT, newer, null);
        assertTrue(verdict.mismatches.toString(), verdict.agrees());
        assertNull(verdict.ancestor);
    }

    @Test
    public void theVerdictCarriesWhatEachSideTurnedOutToBe() throws IOException
    {
        String uuid = "66193438-abc5-410b-a1f1-a204102d1a62";
        String newer = delivery("Демо", "Фирма 1С", "3.2.1.505", uuid);
        OriginCheck.Verdict verdict = OriginCheck.check(NO_PROJECT, newer, null);
        assertEquals("Демо 3.2.1.505 by Фирма 1С", verdict.other.toString());
    }

    @Test
    public void pathOfTreatsBlankAsNothing()
    {
        assertNull(OriginCheck.pathOf(null));
        assertNull(OriginCheck.pathOf("   ")); //$NON-NLS-1$
        assertEquals(Paths.get("somewhere"), OriginCheck.pathOf(" somewhere ")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
