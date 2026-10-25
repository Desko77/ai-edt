/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Covers the built-in YAxUnit help topics.
 * <p>
 * The index is the only way an agent discovers what help exists, so an index that advertises a
 * topic the lookup cannot serve is worse than no index at all - it produces a confident call that
 * returns nothing. That reconciliation, in both directions, is what these tests are for.
 * </p>
 */
public class YaxunitHelpTest
{
    @Test
    public void everyAdvertisedTopicCanBeFetched()
    {
        List<String> topics = YaxunitHelp.availableTopics();

        assertFalse("an empty index means the help is unreachable", topics.isEmpty()); //$NON-NLS-1$
        for (String topic : topics)
        {
            String body = YaxunitHelp.getTopic(topic);
            assertNotNull("advertised but not served: " + topic, body); //$NON-NLS-1$
            assertFalse("served empty: " + topic, body.isBlank()); //$NON-NLS-1$
        }
    }

    @Test
    public void theIndexTopicNamesTheOthers()
    {
        String index = YaxunitHelp.getTopic("topics"); //$NON-NLS-1$

        assertNotNull(index);
        for (String topic : YaxunitHelp.availableTopics())
        {
            assertTrue("the index has to mention " + topic, index.contains(topic)); //$NON-NLS-1$
        }
    }

    @Test
    public void aTopicNameIsMatchedRegardlessOfCaseAndSurroundingSpace()
    {
        String expected = YaxunitHelp.getTopic("assertions"); //$NON-NLS-1$

        assertEquals(expected, YaxunitHelp.getTopic("Assertions")); //$NON-NLS-1$
        assertEquals(expected, YaxunitHelp.getTopic("  ASSERTIONS  ")); //$NON-NLS-1$
    }

    @Test
    public void anUnknownTopicResolvesToNothing()
    {
        assertNull(YaxunitHelp.getTopic("teleportation")); //$NON-NLS-1$
        assertNull(YaxunitHelp.getTopic(null));
        assertNull(YaxunitHelp.getTopic("")); //$NON-NLS-1$
    }

    @Test
    public void theTopicListIsACopyTheCallerCannotBreak()
    {
        // It is handed straight to a tool response; a caller that sorts or clears it must not be
        // able to damage the registry every later call reads.
        List<String> first = YaxunitHelp.availableTopics();
        first.clear();

        assertFalse("the registry was emptied through its own accessor", //$NON-NLS-1$
            YaxunitHelp.availableTopics().isEmpty());
    }

    /**
     * The help does not teach a filter the tool refuses.
     * <p>
     * Measured: the events and advanced topics told the reader to narrow a run with {@code tags=},
     * {@code suites=} and {@code contexts=} long after the launch configuration stopped carrying
     * them, and a call built from that advice answered a full-suite run as if it had been narrowed.
     * The three topics below are every place the help discusses filtering.
     */
    @Test
    public void noTopicOffersAFilterTheToolRefuses()
    {
        for (String topic : YaxunitHelp.availableTopics())
        {
            String body = YaxunitHelp.getTopic(topic);
            assertFalse(topic + " offers tags=", body.contains("tags=")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(topic + " offers suites=", body.contains("suites=")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(topic + " offers contexts=", body.contains("contexts=")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // And the filter that does work is still offered where a run is narrowed.
        boolean namedSomewhere = YaxunitHelp.availableTopics().stream()
            .anyMatch(topic -> YaxunitHelp.getTopic(topic).contains("modules=")); //$NON-NLS-1$
        assertTrue("the help no longer names any filter that works", namedSomewhere); //$NON-NLS-1$
    }

    /**
     * The writing topic teaches the registration protocol the 25.12 engine actually runs.
     * <p>
     * Measured: the topic showed the legacy {@code ИсполняемыеСценарии(СписокТестов)} signature,
     * which 25.12 answers with a deprecation warning and a retry that passes {@code Неопределено} -
     * a module written from that help registers no tests at all. The current protocol is a
     * parameter-less {@code ИсполняемыеСценарии} that registers every test by name through
     * {@code ЮТТесты.ДобавитьТест}.
     * </p>
     */
    @Test
    public void theWritingTopicTeachesTheCurrentRegistrationProtocol()
    {
        String body = YaxunitHelp.getTopic("writing"); //$NON-NLS-1$

        assertTrue("the no-parameter signature is missing", body.contains("ИсполняемыеСценарии() Экспорт")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("registration through YUTTesty is missing", body.contains("ЮТТесты.ДобавитьТест(")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the legacy signature is still taught", body.contains("ИсполняемыеСценарии(СписокТестов)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the legacy list filling is still taught", body.contains("СписокТестов.Добавить")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The writing topic says where the engine looks for test modules.
     * <p>
     * Measured: 25.12 scans only common modules owned by a configuration extension and skips a
     * module whose Global or Server call flag is on. The old topic said "create a common module"
     * without the extension, so a module of the main configuration looked right and was never run.
     * </p>
     */
    @Test
    public void theWritingTopicPlacesTestModulesInAnExtension()
    {
        String body = YaxunitHelp.getTopic("writing"); //$NON-NLS-1$

        assertTrue("the extension placement is missing", body.contains("extension")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the Global flag rule is missing", body.contains("Global")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the Server call flag rule is missing", body.contains("Server call")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the module flags must be told to stay off", body.contains("must stay off")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The writing topic must not demand a test name prefix.
     * <p>
     * Measured: the old topic claimed methods "have to" start with {@code Тест_} and that such
     * names are discovered automatically - 25.12 discovers modules by their export
     * ИсполняемыеСценарии and runs exactly the tests registered by name; the engine's own test
     * modules carry no prefix.
     * </p>
     */
    @Test
    public void theWritingTopicDoesNotDemandATestNamePrefix()
    {
        String body = YaxunitHelp.getTopic("writing"); //$NON-NLS-1$

        assertTrue("the prefix optionality is missing", body.contains("prefix is optional")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the automatic discovery claim is still there", body.contains("discovers automatically")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The setup topic explains a stalled run by what the runner actually answers.
     * <p>
     * Measured: a launch that outlives the deadline or stands on an EDT dialog answers Pending
     * with a runKey, and the EDT dialog is answered with answer_dialog before polling that key.
     * The old topic told the reader to "check the client window" and never named the recovery.
     * </p>
     */
    @Test
    public void theSetupTopicNamesTheRealPendingRecovery()
    {
        String body = YaxunitHelp.getTopic("setup"); //$NON-NLS-1$

        assertTrue("Pending is missing", body.contains("Pending")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("runKey is missing", body.contains("runKey")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("answer_dialog is missing", body.contains("answer_dialog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the stale window advice is still there", body.contains("check the client window")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The setup topic names the update dialog causes the runner knows.
     * <p>
     * Measured: the client's update modal shows up when the pre-launch infobase update did not
     * run - the call passed updateBeforeLaunch=false, or a write-blocking preset disabled
     * update_database and every answer of that run carries databaseUpdate=SKIPPED_BY_PRESET.
     * </p>
     */
    @Test
    public void theSetupTopicNamesTheUpdateDialogCauses()
    {
        String body = YaxunitHelp.getTopic("setup"); //$NON-NLS-1$

        assertTrue("updateBeforeLaunch=false is missing", body.contains("updateBeforeLaunch=false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the preset cause is missing", body.contains("SKIPPED_BY_PRESET")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The setup topic explains zero tests with the causes the engine and the runner really have.
     * <p>
     * Measured: beyond the runner's own block (extension Active, filter names existing tests,
     * module compiles), a run stays empty when the module is outside an extension, its Global or
     * Server call flag is on, or registration is empty - the last is the legacy signature defect
     * again, so the troubleshooting has to point at ЮТТесты.ДобавитьТест.
     * </p>
     */
    @Test
    public void theSetupTopicExplainsZeroTestsByRealCauses()
    {
        String body = YaxunitHelp.getTopic("setup"); //$NON-NLS-1$

        assertTrue("the Active check is missing", body.contains("Active")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the extension-module cause is missing", body.contains("not in an extension")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the registration cause is missing", body.contains("ЮТТесты.ДобавитьТест")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
