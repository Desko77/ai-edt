/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.upkeep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.metadata.Version;
import org.junit.Test;

/**
 * Covers what {@link ReleaseFeed} answers about this installation, and what it reads from a site.
 * <p>
 * The remote half is exercised against a real p2 repository the test writes into a temporary
 * directory rather than against the build output: the tests module is built before the repository
 * module, so <code>target/repository</code> does not exist yet while these run. A hand-written
 * repository also lets the fixture contain exactly the shapes worth checking - two versions of our
 * own unit, and a foreign unit numbered above both.
 * </p>
 * <p>
 * The local half has no fixture available. This suite runs inside a headless Equinox launched
 * without a p2 data area, so there is normally no current agent and no profile to read; that is the
 * same answer a PDE launch or a dropins install produces, and the feature is required to stay quiet
 * there rather than fail. Those assertions therefore accept either environment and pin the contract
 * that matters.
 * </p>
 */
public class ReleaseFeedTest
{
    /** An address nothing listens on, so contacting it would be unmistakable in the result. */
    private static final URI UNREACHABLE = URI.create("https://127.0.0.1:1/site/"); //$NON-NLS-1$

    private static final String OUR_UNIT = "ru.aiedt.mcp.server.feature.feature.group"; //$NON-NLS-1$
    private static final String OLD_VERSION = "1.0.0.201001010000"; //$NON-NLS-1$
    private static final String NEW_VERSION = "9.9.9.202612312359"; //$NON-NLS-1$

    @Test
    public void featureUnitIsTheOneThisPluginShipsAs()
    {
        assertEquals(OUR_UNIT, ReleaseFeed.FEATURE_IU);
    }

    @Test
    public void currentAgentLookupAnswersInsteadOfThrowing()
    {
        // No assertion on the value: with a p2 data area there is an agent, without one there is
        // not, and both are correct. The call simply must not blow up in either environment.
        ReleaseFeed.currentAgent();
    }

    @Test
    public void installedVersionIsAbsentWhenThereIsNoAgentToAsk()
    {
        IProvisioningAgent agent = ReleaseFeed.currentAgent();
        Version installed = ReleaseFeed.installedVersion();
        if (agent == null)
        {
            assertNull("without a provisioning agent no version can be known", installed); //$NON-NLS-1$
        }
        else if (installed != null)
        {
            // When p2 does answer, the version has to be a real one rather than a placeholder: the
            // whole comparison downstream depends on it carrying the build qualifier.
            assertTrue("a p2 version must not be empty", installed.toString().length() > 0); //$NON-NLS-1$
        }
    }

    @Test
    public void anUnconfiguredSiteIsAnsweredWithoutTouchingAnything()
    {
        ReleaseOffer offer = ReleaseFeed.inspect(null, null);
        assertEquals(ReleaseOffer.State.DORMANT, offer.state());
        assertNull(offer.site());
    }

    @Test
    public void anInstallationP2DoesNotManageIsNotContactedOverTheNetwork()
    {
        // The address refuses connections, so reaching it would surface as a failure rather than as
        // an unmanaged answer. This is how the ordering gets checked: the cheap local question is
        // settled first, and an installation that could not act on any answer is never asked.
        if (ReleaseFeed.installedVersion() != null)
        {
            return; // a managed installation: this ordering is not observable here
        }
        ReleaseOffer offer = ReleaseFeed.inspect(UNREACHABLE, null);
        assertFalse("p2 does not manage this installation", offer.managed()); //$NON-NLS-1$
        assertEquals(ReleaseOffer.State.NO_DATA, offer.state());
        assertNotNull("the answer has to say why", offer.note()); //$NON-NLS-1$
    }

    @Test
    public void theNewestVersionOfOurOwnUnitIsRead() throws Exception
    {
        Path repository = writeRepository();
        IProvisioningAgent agent = ReleaseFeed.createPrivateAgent();
        assertNotNull("provisioning must be available in the test runtime", agent); //$NON-NLS-1$
        try
        {
            Version offered = ReleaseFeed.offeredVersionVia(agent, repository.toUri(), null);

            assertNotNull("the fixture publishes our unit", offered); //$NON-NLS-1$
            assertEquals("the newest of ours, not the oldest", Version.create(NEW_VERSION), offered); //$NON-NLS-1$
            // The same fixture carries a foreign unit numbered 99.0.0, above both of ours. Asking
            // for "the latest unit" instead of "the latest of our unit" would answer with that one
            // and report a spectacular update that does not exist.
        }
        finally
        {
            agent.stop();
            removeTree(repository);
        }
    }

    @Test
    public void aSiteWithoutOurUnitOffersNothing() throws Exception
    {
        Path repository = writeRepository(unit("org.example.other", "42.0.0")); //$NON-NLS-1$ //$NON-NLS-2$
        IProvisioningAgent agent = ReleaseFeed.createPrivateAgent();
        assertNotNull("provisioning must be available in the test runtime", agent); //$NON-NLS-1$
        try
        {
            assertNull("a site that publishes something else offers us nothing", //$NON-NLS-1$
                ReleaseFeed.offeredVersionVia(agent, repository.toUri(), null));
        }
        finally
        {
            agent.stop();
            removeTree(repository);
        }
    }

    /**
     * Writes the standard fixture: two versions of our unit plus a foreign unit numbered above
     * both, so a query that ignores the identifier gives itself away.
     *
     * @return the repository directory
     * @throws IOException when the temporary directory cannot be written
     */
    private static Path writeRepository() throws IOException
    {
        return writeRepository(unit(OUR_UNIT, OLD_VERSION) + unit(OUR_UNIT, NEW_VERSION)
            + unit("org.example.other", "99.0.0")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Path writeRepository(String units) throws IOException
    {
        int count = units.split("<unit ", -1).length - 1; //$NON-NLS-1$
        String content = "<?xml version='1.0' encoding='UTF-8'?>\n" //$NON-NLS-1$
            + "<?metadataRepository version='1.2.0'?>\n" //$NON-NLS-1$
            + "<repository name='AI-EDT upkeep fixture'" //$NON-NLS-1$
            + " type='org.eclipse.equinox.internal.p2.metadata.repository.LocalMetadataRepository'" //$NON-NLS-1$
            + " version='1'>\n" //$NON-NLS-1$
            + "  <properties size='1'>\n" //$NON-NLS-1$
            + "    <property name='p2.timestamp' value='1'/>\n" //$NON-NLS-1$
            + "  </properties>\n" //$NON-NLS-1$
            + "  <units size='" + count + "'>\n" //$NON-NLS-1$ //$NON-NLS-2$
            + units
            + "  </units>\n" //$NON-NLS-1$
            + "</repository>\n"; //$NON-NLS-1$
        Path directory = Files.createTempDirectory("aiedt-upkeep-fixture"); //$NON-NLS-1$
        Files.write(directory.resolve("content.xml"), //$NON-NLS-1$
            content.getBytes(StandardCharsets.UTF_8));
        return directory;
    }

    private static String unit(String id, String version)
    {
        return "    <unit id='" + id + "' version='" + version + "' singleton='false'>\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "      <provides size='1'>\n" //$NON-NLS-1$
            + "        <provided namespace='org.eclipse.equinox.p2.iu' name='" + id //$NON-NLS-1$
            + "' version='" + version + "'/>\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "      </provides>\n" //$NON-NLS-1$
            + "    </unit>\n"; //$NON-NLS-1$
    }

    private static void removeTree(Path root)
    {
        try (Stream<Path> entries = Files.walk(root))
        {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
        catch (IOException e)
        {
            // A leftover temporary directory is not worth failing a passing test over.
        }
    }
}
