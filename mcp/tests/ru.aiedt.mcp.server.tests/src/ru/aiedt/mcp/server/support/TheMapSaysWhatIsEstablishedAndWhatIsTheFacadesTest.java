/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * The operation-parameter map answers two questions, and only one of them changed.
 * <p>
 * What an operation reads is what a call may carry, and {@link UnreadArguments} refuses a call over
 * anything outside it - so that set has to stay exactly as wide as it was. What an operation's own
 * help may print is narrower: a facade that resolves its subject before dispatching reads those
 * arguments on every call, and printing them under one operation says that operation takes them.
 * </p>
 * <p>
 * Held here because the two are one line apart in the resource and easy to conflate. Narrowing the
 * wide one turns working calls away, and widening the narrow one puts the facade's arguments under
 * an operation that never sees them.
 * </p>
 */
public class TheMapSaysWhatIsEstablishedAndWhatIsTheFacadesTest
{
    @Test
    public void theMapWasPackagedAndRead()
    {
        // Everything below reads the map. An absent one answers empty to every question, and every
        // assertion would pass over nothing.
        assertTrue("the operation-parameter map is not on the classpath, so nothing below proves "
            + "anything", OperationParameters.available());
    }

    @Test
    public void whatIsEstablishedIsAlwaysPartOfWhatAnOperationReads()
    {
        // The narrow answer is a subset of the wide one by construction. If it ever is not, help
        // would name a parameter the guard refuses - the two would be describing different tools.
        // Compared with kinds on both sides. `of` answers names alone - UnreadArguments matches
        // what a caller sent, and a caller sends `projectName`, not `projectName:string` - so
        // comparing the two directly says every established entry is unread, which is the test
        // being wrong rather than the map.
        List<String> offenders = new ArrayList<>();
        for (String[] pair : SAMPLE)
        {
            Set<String> wide = new HashSet<>(OperationParameters.withKinds(pair[0], pair[1]));
            for (String entry : OperationParameters.establishedFor(pair[0], pair[1]))
            {
                if (!wide.contains(entry))
                {
                    offenders.add(pair[0] + ":" + pair[1] + " established " + entry
                        + ", which it does not read");
                }
            }
        }
        assertTrue(offenders.toString(), offenders.isEmpty());
    }

    @Test
    public void anOperationThatIgnoresAnArgumentDoesNotEstablishIt()
    {
        // Measured on the sources: BranchInfobaseTool reads applicationId only inside bind and
        // branch only inside unbind, while current is called as current(projectName, project,
        // onDisk) and never sees the argument map. The map used to list all four for all four
        // operations, so help built from it would have told a caller that current takes them.
        Set<String> established =
            new HashSet<>(OperationParameters.establishedFor("BranchInfobaseTool", "current"));

        assertTrue("current does not read applicationId and must not claim it: " + established,
            !established.contains("applicationId:string"));
        assertTrue("current does not read the caller's branch and must not claim it: " + established,
            !established.contains("branch:string"));
    }

    @Test
    public void whatTheOperationIgnoresIsStillSomethingTheCallMayCarry()
    {
        // The other half of the same fact. Dropping it from the wide answer would make
        // UnreadArguments refuse a call carrying applicationId on any operation of this facade.
        Set<String> read = new HashSet<>(OperationParameters.of("BranchInfobaseTool", "current"));

        assertTrue("the facade reads applicationId before it dispatches, so a call may carry it: "
            + read, read.contains("applicationId"));
    }

    @Test
    public void anOperationThatDoesReadAnArgumentEstablishesIt()
    {
        // The positive case, so the narrow answer is not simply empty everywhere.
        Set<String> established =
            new HashSet<>(OperationParameters.establishedFor("BranchInfobaseTool", "bind"));

        assertTrue("bind reads applicationId at its own line and must claim it: " + established,
            established.contains("applicationId:string"));
    }

    @Test
    public void anOperationOutsideTheMapEstablishesNothingRatherThanThrowing()
    {
        assertTrue(OperationParameters.establishedFor("NoSuchTool", "no_such_operation").isEmpty());
        assertTrue(OperationParameters.establishedFor(null, null).isEmpty());
    }

    /** Pairs the assertions walk. Enough facades that one shape does not stand for all. */
    private static final String[][] SAMPLE = {
        {"BranchInfobaseTool", "current"},
        {"BranchInfobaseTool", "bind"},
        {"BranchInfobaseTool", "unbind"},
        {"InsightsFacadeTool", "compare_three_way"},
        {"InsightsFacadeTool", "project_metrics"},
        {"DiagnosticsFacadeTool", "get_project_errors"},
        {"EditMetadataTool", "create_object"},
        {"SyncControlTool", "status"},
    };
}
