/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmInfobaseRegistrationHelper.RegisterResult;
import ru.aiedt.mcp.server.support.ErrorTags;

/**
 * The argument refusals of {@code register_infobase}, which run before anything is read or
 * written, and the shape of its answers.
 */
public class InfobaseRegistrarTest
{
    @Test
    public void projectNameIsRequired()
    {
        String result = new InfobaseRegistrar().execute(Map.of("path", "C:/bases/base")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void pathAndConnectionStringTogetherAreRefused()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "project-one"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("path", "C:/bases/base"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("connectionString", "Srvr=srv;Ref=base;"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new InfobaseRegistrar().execute(params);

        assertTrue(result.contains("exactly one of path")); //$NON-NLS-1$
        assertTrue(result.contains("both were passed")); //$NON-NLS-1$
    }

    @Test
    public void neitherPathNorConnectionStringIsRefused()
    {
        String result = new InfobaseRegistrar().execute(Map.of("projectName", "project-one")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(result.contains("exactly one of path")); //$NON-NLS-1$
        assertTrue(result.contains("neither was")); //$NON-NLS-1$
    }

    @Test
    public void aServerInfobaseWithoutANameIsRefused()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "project-one"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("connectionString", "Srvr=srv;Ref=base;"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new InfobaseRegistrar().execute(params);

        assertTrue(result.contains("name is required for a server infobase")); //$NON-NLS-1$
    }

    @Test
    public void credentialsInTheConnectionStringAreRefused()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "project-one"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("connectionString", "Srvr=srv;Ref=base;Usr=admin;"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "Base"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new InfobaseRegistrar().execute(params);

        assertTrue(result.contains("credentials are not taken here")); //$NON-NLS-1$
        assertTrue("the refusal names the operation that stores them", //$NON-NLS-1$
            result.contains("set_infobase_credentials")); //$NON-NLS-1$
        assertFalse(result.contains("admin")); //$NON-NLS-1$
    }

    @Test
    public void theSuccessAnswerCarriesWhatWasAddedBoundAndSet()
    {
        RegisterResult r = new RegisterResult();
        r.ok = true;
        r.infobaseName = "new-base"; //$NON-NLS-1$
        r.uuid = "9b1f6c2e-0000-0000-0000-000000000000"; //$NON-NLS-1$
        r.added = true;
        r.applicationId = "app-one"; //$NON-NLS-1$
        r.defaultApplication = true;
        r.ordinaryApplicationFlag = "added"; //$NON-NLS-1$
        r.launchApplicationIds = "restored the application id of: Foreign run"; //$NON-NLS-1$

        String json = InfobaseRegistrar.response(r);

        assertTrue(json.contains("\"added\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"infobaseName\":\"new-base\"")); //$NON-NLS-1$
        assertTrue(json.contains("\"uuid\":\"9b1f6c2e-0000-0000-0000-000000000000\"")); //$NON-NLS-1$
        assertTrue(json.contains("\"applicationId\":\"app-one\"")); //$NON-NLS-1$
        assertTrue(json.contains("\"defaultApplication\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"ordinaryApplicationFlag\":\"added\"")); //$NON-NLS-1$
        assertTrue(json.contains( //$NON-NLS-1$
            "\"launchApplicationIds\":\"restored the application id of: Foreign run\"")); //$NON-NLS-1$
    }

    @Test
    public void aReusedEntryIsAnsweredAsReusedWithTheStandingDefaultNamed()
    {
        RegisterResult r = new RegisterResult();
        r.ok = true;
        r.infobaseName = "existing-base"; //$NON-NLS-1$
        r.added = false;
        r.applicationId = "app-two"; //$NON-NLS-1$
        r.defaultApplication = false;
        r.previousDefault = "old-base"; //$NON-NLS-1$

        String json = InfobaseRegistrar.response(r);

        assertTrue(json.contains("\"reused\":true")); //$NON-NLS-1$
        assertFalse(json.contains("\"added\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"previousDefaultApplication\":\"old-base\"")); //$NON-NLS-1$
        assertTrue(json.contains("\"defaultApplication\":false")); //$NON-NLS-1$
        assertFalse("no other projects were named, so the field stays out", //$NON-NLS-1$
            json.contains("alsoAssociatedWith")); //$NON-NLS-1$
    }

    @Test
    public void aReusedEntryNamesTheProjectsItWasAlreadyBoundTo()
    {
        RegisterResult r = new RegisterResult();
        r.ok = true;
        r.infobaseName = "existing-base"; //$NON-NLS-1$
        r.added = false;
        r.applicationId = "app-two"; //$NON-NLS-1$
        r.alsoAssociatedWith = List.of("project-two"); //$NON-NLS-1$

        String json = InfobaseRegistrar.response(r);

        assertTrue(json.contains("\"reused\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"alsoAssociatedWith\":[\"project-two\"]")); //$NON-NLS-1$
    }

    @Test
    public void theFailureAnswerNamesTheRollbackAndTheGuardOutcome()
    {
        RegisterResult r = new RegisterResult();
        r.error = "Failed to associate the infobase to the project: refused. " //$NON-NLS-1$
            + "The list entry added for it was removed again; the infobase itself was not touched."; //$NON-NLS-1$
        r.failureKind = ErrorTags.ASSOCIATE_FAILED.wire();
        r.infobaseName = "new-base"; //$NON-NLS-1$
        r.rolledBack = true;
        r.launchApplicationIds = "restored the application id of: Foreign run"; //$NON-NLS-1$

        String json = InfobaseRegistrar.response(r);

        assertTrue(json.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(json.contains("\"associateFailed\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"rolledBack\":true")); //$NON-NLS-1$
        assertTrue(json.contains( //$NON-NLS-1$
            "\"launchApplicationIds\":\"restored the application id of: Foreign run\"")); //$NON-NLS-1$
    }
}
