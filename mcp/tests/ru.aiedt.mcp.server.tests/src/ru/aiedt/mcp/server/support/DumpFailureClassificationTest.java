/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmExternalObjectDumpHelper.DumpInvocation;

/**
 * Covers how a failed binary build is explained back to the caller.
 * <p>
 * Two of these failures have an answer the agent can act on - install a runtime, attach an infobase -
 * and the rest do not. Telling them apart means reading the platform's own words, and the platform
 * speaks the language the IDE runs in. That is the whole risk here: the classification was written
 * against English text and silently answered "could not be built" to every Russian install, sending
 * the reader to look at the build when what was missing was an infobase.
 * </p>
 */
public class DumpFailureClassificationTest
{
    /** As EDT words it when the base project has no infobase application, in Russian. */
    private static final String NO_INFOBASE_RU =
        "\u041d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d\u043e " //$NON-NLS-1$
            + "\u0440\u0430\u0437\u0440\u0430\u0431\u0430\u0442\u044b\u0432\u0430\u0435\u043c\u044b\u0445 " //$NON-NLS-1$
            + "\u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u0439 " //$NON-NLS-1$
            + "\u0438\u043d\u0444\u043e\u0440\u043c\u0430\u0446\u0438\u043e\u043d\u043d\u043e\u0439 " //$NON-NLS-1$
            + "\u0431\u0430\u0437\u044b \u0434\u043b\u044f \u043f\u0440\u043e\u0435\u043a\u0442\u0430"; //$NON-NLS-1$

    /** The newline the platform separates its report with. */
    private static final String LINE = "\n"; //$NON-NLS-1$

    private static DumpInvocation classify(String message)
    {
        DumpInvocation invocation = new DumpInvocation();
        BmExternalObjectDumpHelper.classifyFailure(invocation, new IllegalStateException(message));
        return invocation;
    }

    @Test
    public void aMissingInfobaseIsNamedInEnglish()
    {
        assertEquals("noInfobase", classify("no developing infobase applications").failureKind); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aMissingInfobaseIsNamedInRussianToo()
    {
        // The case this test exists for. Before the Russian wording was matched, this landed on the
        // generic tag and the advice about attaching an infobase was never printed.
        DumpInvocation invocation = classify(NO_INFOBASE_RU);

        assertEquals("noInfobase", invocation.failureKind); //$NON-NLS-1$
        assertTrue(invocation.error, invocation.error.contains("infobase application")); //$NON-NLS-1$
    }

    @Test
    public void aMissingRuntimeIsNamed()
    {
        assertEquals("runtimeNotFound", classify("MatchingRuntimeNotFound").failureKind); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anythingElseStaysGeneric()
    {
        // Deliberately NOT classified: guessing at an unrecognized failure would hand the agent a
        // confident instruction to fix the wrong thing.
        DumpInvocation invocation = classify("disk full"); //$NON-NLS-1$

        assertEquals("dumpFailed", invocation.failureKind); //$NON-NLS-1$
        assertTrue(invocation.error, invocation.error.contains("could not be built")); //$NON-NLS-1$
    }

    @Test
    public void theCauseChainIsReadThrough()
    {
        // The platform message arrives wrapped, so only the outermost message being read would
        // classify nothing at all.
        Throwable wrapped = new IllegalStateException("build step failed", //$NON-NLS-1$
            new IllegalStateException(NO_INFOBASE_RU));
        DumpInvocation invocation = new DumpInvocation();

        BmExternalObjectDumpHelper.classifyFailure(invocation, wrapped);

        assertEquals("noInfobase", invocation.failureKind); //$NON-NLS-1$
    }

    /**
     * The platform reports in a block of lines, and the reason is not the first of them.
     * <p>
     * Measured on the stand: an attribute typed Stirng made the dump fail, and the platform said so
     * seven lines into its report - after the version header, the command and two paths. The answer
     * kept the first line alone, so the caller read a sentence ending in a colon and nothing else,
     * while the reason sat in the workspace log.
     * </p>
     */
    @Test
    public void theReasonBelowTheHeaderReachesTheCaller()
    {
        String platformReport = "Interaction with 1C:Enterprise 8.3.27.2214 failed:" //$NON-NLS-1$
            + LINE + "Platform process log [error code 1]:" //$NON-NLS-1$
            + LINE + "Loading an external data processor or report from XML." //$NON-NLS-1$
            + LINE + "Root export file: C:/Temp/whatever.xml" //$NON-NLS-1$
            + LINE + LINE + "Unknown type name - Stirng" //$NON-NLS-1$
            + LINE + "Unknown type name - Stirng"; //$NON-NLS-1$

        String answer = classify(platformReport).error;

        assertTrue(answer, answer.contains("Unknown type name - Stirng")); //$NON-NLS-1$
        assertTrue("the header is still there, it just is not all there is", //$NON-NLS-1$
            answer.contains("8.3.27.2214")); //$NON-NLS-1$
    }

    @Test
    public void theReasonIsNotRepeatedBackTwice()
    {
        // The platform says it once in its summary and once in the detail.
        String answer = classify("header:" + LINE + "same line" + LINE + "same line").error; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        int first = answer.indexOf("same line"); //$NON-NLS-1$
        assertEquals("one mention is enough", //$NON-NLS-1$
            -1, answer.indexOf("same line", first + 1)); //$NON-NLS-1$
    }
}
