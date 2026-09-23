/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmInfobaseCredentialsHelper;
import ru.aiedt.mcp.server.support.BmInfobaseLifecycleHelper.CreateResult;
import ru.aiedt.mcp.server.support.BmInfobaseLifecycleHelper.DeleteResult;
import ru.aiedt.mcp.server.support.ErrorTags;

/** Lifecycle operation responses expose what their infobase-list guard repaired. */
public class InfobaseLifecycleLaunchIdsResponseTest
{
    @Test
    public void createInfobaseResponseCarriesLaunchApplicationIds()
    {
        CreateResult result = new CreateResult();
        result.ok = true;
        result.infobaseName = "new-base"; //$NON-NLS-1$
        result.path = "C:/bases/new-base"; //$NON-NLS-1$
        result.launchApplicationIds = "restored both foreign configurations"; //$NON-NLS-1$

        String json = InfobaseCreator.response("new-base", result); //$NON-NLS-1$

        assertTrue(json.contains("\"launchApplicationIds\":\"restored both foreign configurations\"")); //$NON-NLS-1$
    }

    @Test
    public void deleteInfobaseResponseCarriesLaunchApplicationIds()
    {
        DeleteResult result = new DeleteResult();
        result.ok = true;
        result.launchApplicationIds = "left deleted unbound; restored foreign"; //$NON-NLS-1$

        String json = InfobaseRemover.response("deleted-base", result); //$NON-NLS-1$

        assertTrue(json.contains("\"launchApplicationIds\":\"left deleted unbound; restored foreign\"")); //$NON-NLS-1$
    }

    @Test
    public void setInfobaseCredentialsResponseCarriesLaunchApplicationIds()
    {
        BmInfobaseCredentialsHelper.CredentialResult result =
            new BmInfobaseCredentialsHelper.CredentialResult();
        result.ok = true;
        result.applicationId = "app-one"; //$NON-NLS-1$
        result.infobaseName = "base-one"; //$NON-NLS-1$
        result.access = "INFOBASE"; //$NON-NLS-1$
        result.userName = "user"; //$NON-NLS-1$
        result.passwordStored = true;
        result.launchApplicationIds = "restored the application id of: Run one"; //$NON-NLS-1$

        String json = InfobaseCredentialsWriter.response("project-one", result); //$NON-NLS-1$

        assertTrue(json.contains(
            "\"launchApplicationIds\":\"restored the application id of: Run one\"")); //$NON-NLS-1$
    }

    @Test
    public void setInfobaseCredentialsFailureResponseCarriesLaunchApplicationIds()
    {
        // The write threw after the reload had already stripped the configurations: the failure
        // answer says what became of them too.
        BmInfobaseCredentialsHelper.CredentialResult result =
            new BmInfobaseCredentialsHelper.CredentialResult();
        result.ok = false;
        result.error = "Failed to store the credentials in secure storage"; //$NON-NLS-1$
        result.failureKind = ErrorTags.WRITE_FAILED.wire();
        result.launchApplicationIds = "restored the application id of: Run one"; //$NON-NLS-1$

        String json = InfobaseCredentialsWriter.response("project-one", result); //$NON-NLS-1$

        assertTrue(json.contains(
            "\"launchApplicationIds\":\"restored the application id of: Run one\"")); //$NON-NLS-1$
    }
}
