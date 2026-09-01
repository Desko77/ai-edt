/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Freezes every {@link ErrorTags} constant to its wire string with a hardcoded golden map, so that an
 * accidental rename of a constant's value - which would silently change what a client observes on the
 * wire - fails this test instead of shipping.
 *
 * <p>This is a pure POJO test: it touches only the {@link ErrorTags} enum, no EDT/OSGi/SWT runtime.
 *
 * <p>The golden map below is intentionally hardcoded (not derived from {@link ErrorTags} itself) so a
 * bulk rename cannot drag the golden values along with it - the map must be edited by hand alongside any
 * legitimate change to a wire string, and reviewers see that edit in the diff.
 */
public class ErrorTagsContractTest
{
    /** Total number of {@link ErrorTags} constants this test covers: 6 reliability + 52 folded. */
    private static final int EXPECTED_CONSTANT_COUNT = 58;

    private static Map<String, String> goldenWireStrings()
    {
        Map<String, String> golden = new LinkedHashMap<>();

        // -- Reliability set (2026-07 waves 1-2) --
        golden.put("UI_BUSY", "uiBusy"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("BUSY", "busy"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("DEGRADED", "degraded"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("TRUNCATED", "truncated"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("CANCELLED", "cancelled"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("CLIENT_GONE", "clientGone"); //$NON-NLS-1$ //$NON-NLS-2$

        // -- Folded set: MetadataGuards.ErrorTag / BM guards --
        golden.put("PROPERTY_MISMATCH", "propertyMismatch"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("KIND_MISMATCH", "kindMismatch"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("NOT_APPLICABLE_HERE", "notApplicableHere"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("REQUIRES_CASCADE_FORMS", "requiresCascadeForms"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("NOT_FOUND", "notFound"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("ALREADY_EXISTS", "alreadyExists"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("STANDARD_ATTRIBUTE_CONFLICT", "standardAttributeConflict"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("SUPPORT_LOCK", "supportLock"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("TARGET_NOT_FOUND", "targetNotFound"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("HANDLER_INVALID", "handlerInvalid"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("ADOPTED_OWNER", "adoptedOwner"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("QUERY_VALIDATION", "queryValidation"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("EXPRESSION_VALIDATION", "expressionValidation"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("PRIVILEGED_NOT_ALLOWED_IN_EXTENSION", "privilegedNotAllowedInExtension"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("GLOBAL_SERVER_NOT_ALLOWED_IN_EXTENSION", "globalServerNotAllowedInExtension"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("COMMON_MODULE_NOT_FOUND", "commonModuleNotFound"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("FONT_COLOR_GUARD", "fontColorGuard"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("MULTI_STATEMENT_UNSUPPORTED", "multiStatementUnsupported"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("DCS_FACTORY_METHOD_NOT_FOUND", "dcsFactoryMethodNotFound"); //$NON-NLS-1$ //$NON-NLS-2$

        // -- Folded set: *ApiNotFound family (bare literal keys only) --
        golden.put("FORM_API_NOT_FOUND", "formApiNotFound"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("MXL_API_NOT_FOUND", "mxlApiNotFound"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("CLI_API_NOT_FOUND", "cliApiNotFound"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("ADOPT_SERVICE_NOT_FOUND", "adoptServiceNotFound"); //$NON-NLS-1$ //$NON-NLS-2$

        // -- Folded set: misc single-site guards --
        golden.put("OPTIONS_IGNORED", "optionsIgnored"); //$NON-NLS-1$ //$NON-NLS-2$

        // -- Folded set: failureKind discriminator family --
        golden.put("INPUT_MISSING", "inputMissing"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("INVALID_INPUT_PATH", "invalidInputPath"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("GITHUB_ASSET_NOT_FOUND", "githubAssetNotFound"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("GITHUB_RESOLVE_FAILED", "githubResolveFailed"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("INPUT_DOWNLOAD_FAILED", "inputDownloadFailed"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("INSTALL_API_NOT_FOUND", "installApiNotFound"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("INVALID_OUTPUT_PATH", "invalidOutputPath"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("OUTPUT_DIRECTORY_ERROR", "outputDirectoryError"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("OUTPUT_MISSING", "outputMissing"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("MANAGER_UNAVAILABLE", "managerUnavailable"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("STORAGE_LOCKED", "storageLocked"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("PROJECT_NOT_FOUND", "projectNotFound"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("RESOLVE_FAILED", "resolveFailed"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("NOT_INFOBASE", "notInfobase"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("PLATFORM_VERSION_MISMATCH", "platformVersionMismatch"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("RUNTIME_NOT_FOUND", "runtimeNotFound"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("AUTH_FAILED", "authFailed"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("THICK_CLIENT_FAILED", "thickClientFailed"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("WRITE_FAILED", "writeFailed"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("READBACK_FAILED", "readbackFailed"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("CREATE_FAILED", "createFailed"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("ASSOCIATE_FAILED", "associateFailed"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("DELETE_FAILED", "deleteFailed"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("INFOBASE_NOT_FOUND", "infobaseNotFound"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("SERVICE_UNAVAILABLE", "serviceUnavailable"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("NO_INFOBASE", "noInfobase"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("INVOCATION", "invocation"); //$NON-NLS-1$ //$NON-NLS-2$
        golden.put("DUMP_FAILED", "dumpFailed"); //$NON-NLS-1$ //$NON-NLS-2$

        return golden;
    }

    @Test
    public void goldenMapCoversExactlyTheDeclaredConstants()
    {
        Map<String, String> golden = goldenWireStrings();
        assertEquals("golden map size must match the number of ErrorTags constants " //$NON-NLS-1$
            + "(update this test alongside any new/removed constant)", //$NON-NLS-1$
            ErrorTags.values().length, golden.size());
        assertEquals(EXPECTED_CONSTANT_COUNT, ErrorTags.values().length);
        assertEquals(EXPECTED_CONSTANT_COUNT, golden.size());
    }

    @Test
    public void everyConstantMatchesItsGoldenWireString()
    {
        Map<String, String> golden = goldenWireStrings();
        for (ErrorTags tag : ErrorTags.values())
        {
            String expected = golden.get(tag.name());
            if (expected == null)
            {
                fail("No golden wire string registered for ErrorTags." + tag.name() //$NON-NLS-1$
                    + " - add it to goldenWireStrings() in this test."); //$NON-NLS-1$
            }
            assertEquals("ErrorTags." + tag.name() + ".wire() changed value - this is a wire-breaking " //$NON-NLS-1$ //$NON-NLS-2$
                + "change for any client branching on the old string. If the rename is intentional, " //$NON-NLS-1$
                + "add a NEW constant instead of renaming this one.", //$NON-NLS-1$
                expected, tag.wire());
        }
    }

    @Test
    public void noGoldenEntryIsOrphaned()
    {
        Set<String> liveNames = new HashSet<>();
        for (ErrorTags tag : ErrorTags.values())
        {
            liveNames.add(tag.name());
        }
        for (String goldenName : goldenWireStrings().keySet())
        {
            assertTrue("Golden map has an entry '" + goldenName //$NON-NLS-1$
                + "' that no longer matches any ErrorTags constant - remove it from goldenWireStrings().", //$NON-NLS-1$
                liveNames.contains(goldenName));
        }
    }

    @Test
    public void wireStringsAreAllUnique()
    {
        Set<String> seen = new HashSet<>();
        for (ErrorTags tag : ErrorTags.values())
        {
            assertTrue("Duplicate wire string '" + tag.wire() + "' - ErrorTags." + tag.name() //$NON-NLS-1$ //$NON-NLS-2$
                + " collides with an earlier constant. Every tag must have a distinct wire identifier.", //$NON-NLS-1$
                seen.add(tag.wire()));
        }
    }

    @Test
    public void tagFactoryProducesTheSameWireString()
    {
        for (ErrorTags value : ErrorTags.values())
        {
            assertEquals("ErrorTags." + value.name() + ".tag().name must match .wire()", //$NON-NLS-1$ //$NON-NLS-2$
                value.wire(), value.tag().name);
        }
    }
}
