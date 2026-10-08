/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com._1c.g5.designer.ssh.client.DesignerClientException;
import com._1c.g5.designer.ssh.client.IDesignerSession;
import com._1c.g5.designer.ssh.client.operation.IDesignerOperation;
import com._1c.g5.designer.ssh.client.operation.IExtensionProperties;
import com._1c.g5.designer.ssh.client.operation.IExtensionPropertiesStep;
import com._1c.g5.designer.ssh.client.operation.IExtensionStep;
import com._1c.g5.designer.ssh.client.operation.ISetExtensionPropertiesStep;
import com._1c.g5.designer.ssh.client.operation.ISingleExtensionStep;

import ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.ExtensionFlags;
import ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.ExtensionFlagsResult;

/**
 * The designer-agent step lowers BOTH safety flags of an extension in ONE write, reads them back,
 * and answers an unconfirmed write with the actual values instead of a success.
 * <p>
 * The step is what makes a freshly installed YAxUnit engine able to run anything: the batch
 * install leaves the platform defaults on, and with either flag on the engine executes no tests.
 * A stub session stands in for the designer here, so the order - read, one write carrying both
 * flags, control read - and every refusal wording are held without an infobase.
 * </p>
 */
public class TheDesignerLowersBothExtensionFlagsTest
{
    /**
     * Both flags already off: the step answers ok without a write, and the read it did is the one
     * the answer reports.
     */
    @Test
    public void bothFlagsOffAlreadyAnswerWithoutWriting()
    {
        StubSession session = new StubSession();
        session.reads.add(page(extension("YAxUnit", false, false)));

        ExtensionFlagsResult r = BmInfobaseExtensionHelper.ensureUnsafeFlags(session, "YAxUnit"); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertFalse(r.wrote);
        assertEquals(0, session.writes);
        assertNotNull(r.flags);
        assertFalse(r.flags.safeMode.booleanValue());
        assertFalse(r.flags.unsafeActionProtection.booleanValue());
    }

    /**
     * Flags on: one write carries both flags, a control read follows it, and the answer reports
     * the flags as the control read saw them.
     */
    @Test
    public void onFlagsAreLoweredInOneWriteAndConfirmedByASecondRead()
    {
        StubSession session = new StubSession();
        session.reads.add(page(extension("YAxUnit", true, true)));
        session.reads.add(page(extension("YAxUnit", false, false)));

        ExtensionFlagsResult r = BmInfobaseExtensionHelper.ensureUnsafeFlags(session, "YAxUnit"); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertTrue(r.wrote);
        assertEquals("both flags go in ONE write", 1, session.writes); //$NON-NLS-1$
        assertEquals(Collections.singletonList(Boolean.FALSE), session.safeModeValues);
        assertEquals(Collections.singletonList(Boolean.FALSE), session.protectionValues);
        assertEquals("YAxUnit", session.writtenExtension); //$NON-NLS-1$
        assertEquals("the control read must happen after the write", 2, session.readsTaken); //$NON-NLS-1$
        assertNotNull(r.flags);
        assertFalse(r.flags.safeMode.booleanValue());
        assertFalse(r.flags.unsafeActionProtection.booleanValue());
    }

    /**
     * A write the designer refuses is an error that still reports what the first read saw; no
     * control read runs over a write that did not happen.
     */
    @Test
    public void aRefusedWriteStopsTheStepAndReportsTheFirstRead()
    {
        StubSession session = new StubSession();
        session.reads.add(page(extension("YAxUnit", true, false)));
        session.refuseWrites = true;

        ExtensionFlagsResult r = BmInfobaseExtensionHelper.ensureUnsafeFlags(session, "YAxUnit"); //$NON-NLS-1$

        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("failed")); //$NON-NLS-1$
        assertNotNull("the answer carries what the first read saw", r.flags); //$NON-NLS-1$
        assertTrue(r.flags.safeMode.booleanValue());
        assertFalse(r.flags.unsafeActionProtection.booleanValue());
        assertEquals(1, session.readsTaken);
    }

    /**
     * A control read that finds one flag back on - here the safe mode keeps its previous value -
     * is an error naming the ACTUAL value of each flag, and no second write is attempted over the
     * state the read just reported.
     */
    @Test
    public void anUnconfirmedWriteIsAnErrorWithBothActualValuesAndNoSecondWrite()
    {
        StubSession session = new StubSession();
        session.reads.add(page(extension("YAxUnit", true, true)));
        session.reads.add(page(extension("YAxUnit", true, false)));

        ExtensionFlagsResult r = BmInfobaseExtensionHelper.ensureUnsafeFlags(session, "YAxUnit"); //$NON-NLS-1$

        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("safe mode on")); //$NON-NLS-1$
        assertTrue(r.error, r.error.contains("unsafe action protection off")); //$NON-NLS-1$
        assertEquals("no reverse write over a state nobody has looked at", 1, session.writes); //$NON-NLS-1$
        assertNotNull(r.flags);
        assertTrue(r.flags.safeMode.booleanValue());
        assertFalse(r.flags.unsafeActionProtection.booleanValue());
    }

    /**
     * An extension the infobase does not hold is refused before any write, and the refusal says
     * the extension is not there rather than that its flags were lowered.
     */
    @Test
    public void anExtensionTheInfobaseDoesNotHoldIsRefused()
    {
        StubSession session = new StubSession();
        session.reads.add(page(extension("SomethingElse", true, true)));

        ExtensionFlagsResult r = BmInfobaseExtensionHelper.ensureUnsafeFlags(session, "YAxUnit"); //$NON-NLS-1$

        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("not among the infobase's extensions")); //$NON-NLS-1$
        assertEquals(0, session.writes);
        assertNotNull(r.flags);
        assertFalse(r.flags.found);
    }

    /**
     * A read that itself fails is an error on its own: the answer never reads as "the flags are
     * fine" over a session that said nothing.
     */
    @Test
    public void aFailingReadIsAnErrorBeforeAnythingWrites()
    {
        StubSession session = new StubSession();
        session.failReads = true;
        session.reads.add(page(extension("YAxUnit", true, true)));

        ExtensionFlagsResult r = BmInfobaseExtensionHelper.ensureUnsafeFlags(session, "YAxUnit"); //$NON-NLS-1$

        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("Reading the extension properties")); //$NON-NLS-1$
        assertEquals(0, session.writes);
    }

    /** The write names the extension the way the infobase spells it, not the way the caller did. */
    @Test
    public void theWriteNamesTheExtensionAsTheInfobaseSpellsIt()
    {
        StubSession session = new StubSession();
        session.reads.add(page(extension("YAXUNIT", true, true))); //$NON-NLS-1$
        session.reads.add(page(extension("YAXUNIT", false, false))); //$NON-NLS-1$

        ExtensionFlagsResult r = BmInfobaseExtensionHelper.ensureUnsafeFlags(session, "YAxUnit"); //$NON-NLS-1$

        assertTrue(r.error, r.ok);
        assertEquals("YAXUNIT", session.writtenExtension); //$NON-NLS-1$
    }

    /**
     * The name match between the extension asked for and the list the designer returns ignores
     * case and surrounding space - the same reading the install probe gives an extension name.
     */
    @Test
    public void theNameMatchIgnoresCaseAndSurroundingSpace()
    {
        ExtensionFlags matched = BmInfobaseExtensionHelper.flagsOf(
            page(extension(" yaxunit ", true, false)), "YAxUnit"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(matched.found);
        assertTrue(matched.safeMode.booleanValue());
        assertFalse(matched.unsafeActionProtection.booleanValue());

        assertFalse(BmInfobaseExtensionHelper.flagsOf(Collections.emptyList(), "YAxUnit").found); //$NON-NLS-1$
        assertFalse(BmInfobaseExtensionHelper.flagsOf(null, "YAxUnit").found); //$NON-NLS-1$
        assertFalse(BmInfobaseExtensionHelper.flagsOf(
            page((IExtensionProperties)null), "YAxUnit").found); //$NON-NLS-1$
    }

    /**
     * One page of extension properties.
     *
     * @param properties the extensions the page lists
     * @return the page
     */
    private static List<IExtensionProperties> page(IExtensionProperties... properties)
    {
        return new ArrayList<>(Arrays.asList(properties));
    }

    /**
     * The stand-in extension properties.
     *
     * @param name the extension name as the designer returns it
     * @param safeMode the safe-mode flag
     * @param protection the unsafe-action-protection flag
     * @return properties answering with exactly these values
     */
    private static IExtensionProperties extension(String name, boolean safeMode, boolean protection)
    {
        return new IExtensionProperties()
        {
            @Override
            public String getName()
            {
                return name;
            }

            @Override
            public String getVersion()
            {
                return ""; //$NON-NLS-1$
            }

            @Override
            public com._1c.g5.designer.ssh.client.operation.ExtensionPurpose getPurpose()
            {
                return null;
            }

            @Override
            public boolean isActive()
            {
                return true;
            }

            @Override
            public boolean isSafeMode()
            {
                return safeMode;
            }

            @Override
            public String getSecurityProfile()
            {
                return null;
            }

            @Override
            public boolean isUnsafeActionProtected()
            {
                return protection;
            }

            @Override
            public boolean isUsedInDistributedInfobase()
            {
                return false;
            }

            @Override
            public com._1c.g5.designer.ssh.client.operation.ExtensionScope getScope()
            {
                return null;
            }

            @Override
            public String getHashSum()
            {
                return null;
            }
        };
    }

    /**
     * A designer session that answers reads from a queue of pages and records every write.
     * <p>
     * Reads hand out the pages in order and repeat the last one when the queue runs dry, so a
     * test that cares only about the first read does not have to stage the control read's page.
     * Writes are counted, their two flag values are kept per call, and
     * {@link #refuseWrites} makes the write step throw the protocol's own exception.
     * </p>
     */
    private static final class StubSession implements IDesignerSession
    {
        /** The pages the reads hand out, in order. */
        final List<List<IExtensionProperties>> reads = new ArrayList<>();

        /** How many reads were taken. */
        int readsTaken;

        /** How many writes ran to their exec. */
        int writes;

        /** The extension name the write step was given. */
        String writtenExtension;

        /** The safe-mode value of every write, one entry per write. */
        final List<Boolean> safeModeValues = new ArrayList<>();

        /** The protection value of every write, one entry per write. */
        final List<Boolean> protectionValues = new ArrayList<>();

        /** Makes every write throw the protocol's refusal. */
        boolean refuseWrites;

        /** Makes every read throw instead of answering. */
        boolean failReads;

        @Override
        public com._1c.g5.designer.ssh.client.operation.IExtensionsQuery extensions()
        {
            return new com._1c.g5.designer.ssh.client.operation.IExtensionsQuery()
            {
                @Override
                public IExtensionPropertiesStep properties()
                {
                    return new IExtensionPropertiesStep()
                    {
                        @Override
                        public ISingleExtensionStep<ISetExtensionPropertiesStep> set()
                        {
                            return new ISingleExtensionStep<>()
                            {
                                @Override
                                public ISetExtensionPropertiesStep extension(String name)
                                {
                                    StubSession.this.writtenExtension = name;
                                    return writeStep();
                                }
                            };
                        }

                        @Override
                        public IExtensionStep<IDesignerOperation<List<IExtensionProperties>>> get()
                        {
                            return new IExtensionStep<>()
                            {
                                @Override
                                public IDesignerOperation<List<IExtensionProperties>> allExtensions()
                                {
                                    return readStep();
                                }

                                @Override
                                public IDesignerOperation<List<IExtensionProperties>> extension(String name)
                                {
                                    throw new UnsupportedOperationException(
                                        "the step under test reads allExtensions only"); //$NON-NLS-1$
                                }
                            };
                        }
                    };
                }

                @Override
                public com._1c.g5.designer.ssh.client.operation.ICreateExtensionStep create(String name,
                    String prefix)
                {
                    throw new UnsupportedOperationException("not part of the step under test"); //$NON-NLS-1$
                }

                @Override
                public IExtensionStep<? extends IDesignerOperation<Void>> delete()
                {
                    throw new UnsupportedOperationException("not part of the step under test"); //$NON-NLS-1$
                }
            };
        }

        /**
         * The write step: records both flag values and answers, or throws the protocol's own
         * refusal when the test asked for one.
         *
         * @return the step
         */
        private ISetExtensionPropertiesStep writeStep()
        {
            return new ISetExtensionPropertiesStep()
            {
                private Boolean safeMode;

                private Boolean protection;

                @Override
                public ISetExtensionPropertiesStep active(boolean value)
                {
                    throw new UnsupportedOperationException("not part of the step under test"); //$NON-NLS-1$
                }

                @Override
                public ISetExtensionPropertiesStep safeMode(boolean value)
                {
                    safeMode = Boolean.valueOf(value);
                    return this;
                }

                @Override
                public ISetExtensionPropertiesStep securityProfile(String profile)
                {
                    throw new UnsupportedOperationException("not part of the step under test"); //$NON-NLS-1$
                }

                @Override
                public ISetExtensionPropertiesStep unsafeActionProtection(boolean value)
                {
                    protection = Boolean.valueOf(value);
                    return this;
                }

                @Override
                public ISetExtensionPropertiesStep usedInDistributedInfobase(boolean value)
                {
                    throw new UnsupportedOperationException("not part of the step under test"); //$NON-NLS-1$
                }

                @Override
                public ISetExtensionPropertiesStep scope(
                    com._1c.g5.designer.ssh.client.operation.ExtensionScope value)
                {
                    throw new UnsupportedOperationException("not part of the step under test"); //$NON-NLS-1$
                }

                @Override
                public Void exec()
                {
                    if (safeMode == null || protection == null)
                    {
                        throw new IllegalStateException(
                            "the write went to exec with one flag missing: safeMode=" + safeMode //$NON-NLS-1$
                                + ", protection=" + protection); //$NON-NLS-1$
                    }
                    StubSession.this.safeModeValues.add(safeMode);
                    StubSession.this.protectionValues.add(protection);
                    StubSession.this.writes++;
                    if (refuseWrites)
                    {
                        throw new DesignerClientException("the designer refused the write"); //$NON-NLS-1$
                    }
                    return null;
                }

                @Override
                public Void exec(Duration timeout)
                {
                    return exec();
                }
            };
        }

        /**
         * The read step: hands out the next page, repeating the last one when the queue is dry.
         *
         * @return the step
         */
        private IDesignerOperation<List<IExtensionProperties>> readStep()
        {
            return new IDesignerOperation<>()
            {
                @Override
                public List<IExtensionProperties> exec()
                {
                    readsTaken++;
                    if (failReads)
                    {
                        throw new DesignerClientException("the designer refused the read"); //$NON-NLS-1$
                    }
                    if (reads.isEmpty())
                    {
                        return Collections.emptyList();
                    }
                    int index = Math.min(readsTaken - 1, reads.size() - 1);
                    return reads.get(index);
                }

                @Override
                public List<IExtensionProperties> exec(Duration timeout)
                {
                    return exec();
                }
            };
        }

        @Override
        public com._1c.g5.designer.ssh.client.operation.IHelpQuery help()
        {
            throw new UnsupportedOperationException("not part of the step under test"); //$NON-NLS-1$
        }

        @Override
        public com._1c.g5.designer.ssh.client.operation.ICommonQuery common()
        {
            throw new UnsupportedOperationException("not part of the step under test"); //$NON-NLS-1$
        }

        @Override
        public com._1c.g5.designer.ssh.client.operation.IOptionsQuery options()
        {
            throw new UnsupportedOperationException("not part of the step under test"); //$NON-NLS-1$
        }

        @Override
        public com._1c.g5.designer.ssh.client.operation.IConfigureQuery configure()
        {
            throw new UnsupportedOperationException("not part of the step under test"); //$NON-NLS-1$
        }

        @Override
        public com._1c.g5.designer.ssh.client.operation.IInfobaseToolsQuery infobaseTools()
        {
            throw new UnsupportedOperationException("not part of the step under test"); //$NON-NLS-1$
        }

        @Override
        public void setLogAcceptor(com._1c.g5.designer.ssh.client.ILogAcceptor acceptor)
        {
            // nothing recorded
        }

        @Override
        public void setAbstractQuestionHandler(
            com._1c.g5.designer.ssh.client.IQuestionHandler handler)
        {
            // nothing recorded
        }

        @Override
        public String getWelcomeMessage()
        {
            return ""; //$NON-NLS-1$
        }

        @Override
        public void close()
        {
            // nothing to close
        }
    }
}
