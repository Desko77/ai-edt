/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;

import ru.aiedt.mcp.server.support.BmInfobaseCredentialsHelper.CredentialResult;

/**
 * Storing infobase credentials: a failure text that echoes a password the call handled comes
 * back masked, for the new password and for the one that stood before.
 *
 * <p>The write runs against a fake access manager the way the registration tests fake theirs;
 * the secure-storage prime is passed in as answered, so no real secure store is touched.</p>
 */
public class BmInfobaseCredentialsHelperTest
{
    @Test
    public void aWriteFailureThatEchoesTheNewPasswordIsAnsweredWithoutIt()
    {
        CredentialResult r = writeFailsWith("secure storage refused the password s3cret", //$NON-NLS-1$
            "s3cret-old"); //$NON-NLS-1$

        assertFalse(r.ok);
        assertEquals(ErrorTags.WRITE_FAILED.wire(), r.failureKind);
        assertTrue("the write's own reason is still named: " + r.error, //$NON-NLS-1$
            r.error.contains("secure storage refused the password")); //$NON-NLS-1$
        assertFalse("the answer carries the new password: " + r.error, //$NON-NLS-1$
            r.error.contains("s3cret")); //$NON-NLS-1$
    }

    @Test
    public void aWriteFailureThatEchoesThePreviousPasswordMasksItWhole()
    {
        CredentialResult r = writeFailsWith("the store still holds s3cret-old", "s3cret-old"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(r.ok);
        assertEquals(ErrorTags.WRITE_FAILED.wire(), r.failureKind);
        assertFalse("the answer carries the previous password: " + r.error, //$NON-NLS-1$
            r.error.contains("s3cret-old")); //$NON-NLS-1$
        assertFalse("the longer secret is not masked only in its head: " + r.error, //$NON-NLS-1$
            r.error.contains("***-old")); //$NON-NLS-1$
    }

    /**
     * Runs the credentials write against a manager whose pre-read answers the settings that stood
     * before (with {@code previousPassword}) and whose write fails with {@code message}.
     *
     * @param message          what {@code updateSettings} throws with
     * @param previousPassword the password the settings that stood before carry
     * @return what the write ended with
     */
    private static CredentialResult writeFailsWith(String message, String previousPassword)
    {
        InfobaseReference infobase = fileInfobase("C:/bases/existing", "Existing base"); //$NON-NLS-1$ //$NON-NLS-2$
        IInfobaseAccessManager mgr = (IInfobaseAccessManager)Proxy.newProxyInstance(
            BmInfobaseCredentialsHelperTest.class.getClassLoader(),
            new Class<?>[] { IInfobaseAccessManager.class }, (proxy, method, args) -> {
                switch (method.getName())
                {
                case "resolveSettings": //$NON-NLS-1$
                    return new InfobaseAccessSettings(InfobaseAccess.INFOBASE, "keeper", //$NON-NLS-1$
                        previousPassword, null);
                case "updateSettings": //$NON-NLS-1$
                    throw new IllegalStateException(message);
                default:
                    return FakeLaunchConfigurations.defaultValue(method.getReturnType());
                }
            });
        return BmInfobaseCredentialsHelper.setCredentialsForInfobase(infobase, "INFOBASE", //$NON-NLS-1$
            "admin", "s3cret", mgr, null); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A file infobase reference as an existing list entry carries one. */
    private static InfobaseReference fileInfobase(String path, String name)
    {
        InfobaseReference reference = ModelFactory.eINSTANCE.createInfobaseReference();
        FileConnectionString connection = ModelFactory.eINSTANCE.createFileConnectionString();
        connection.setFile(path);
        reference.setConnectionString(connection);
        reference.setUuid(UUID.randomUUID());
        reference.setName(name);
        return reference;
    }
}
