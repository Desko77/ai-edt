/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/** The settings footer takes its version from the installed bundle and publishes fixed links. */
public class TheMainSettingsPageFooterTextTest
{
    /** The rendered version is the manifest version of the bundle running this test. */
    @Test
    public void theFooterUsesTheRuntimeBundleVersionInsteadOfASecondConstant()
    {
        Bundle bundle = FrameworkUtil.getBundle(SettingsFooter.class);
        assertNotNull("the OSGi test runtime must expose the plugin bundle", bundle); //$NON-NLS-1$

        String footer = SettingsFooter.text(bundle);

        assertEquals("(c) 2026 Desko77 | Version " + bundle.getVersion(), footer); //$NON-NLS-1$
        assertTrue(footer.contains("(c) 2026 Desko77")); //$NON-NLS-1$
    }

    /** The three destinations are the public addresses, including Telegram copied from README. */
    @Test
    public void everyFooterDestinationIsPublishedInOnePlace()
    {
        assertEquals("https://github.com/Desko77/ai-edt", SettingsFooter.REPOSITORY_URL); //$NON-NLS-1$
        assertEquals("https://t.me/AI_EDT_1c", SettingsFooter.TELEGRAM_URL); //$NON-NLS-1$
        assertEquals("https://desko77.github.io/ai-edt/", SettingsFooter.UPDATE_SITE_URL); //$NON-NLS-1$
    }

    /** A plain-classpath caller still receives printable text instead of a null version. */
    @Test
    public void theFooterHasAnExplicitFallbackOutsideOsgi()
    {
        assertEquals("(c) 2026 Desko77 | Version unknown", SettingsFooter.text(null)); //$NON-NLS-1$
    }
}
