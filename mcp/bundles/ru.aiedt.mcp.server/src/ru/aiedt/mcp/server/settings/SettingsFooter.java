/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import org.osgi.framework.Bundle;
import org.osgi.framework.Version;

/** Text and destinations shown in the footer of the main preference page. */
final class SettingsFooter
{
    /** Project repository. */
    static final String REPOSITORY_URL = "https://github.com/Desko77/ai-edt"; //$NON-NLS-1$

    /** Project Telegram group, as published in README.md. */
    static final String TELEGRAM_URL = "https://t.me/AI_EDT_1c"; //$NON-NLS-1$

    /** P2 update site. */
    static final String UPDATE_SITE_URL = "https://desko77.github.io/ai-edt/"; //$NON-NLS-1$

    private SettingsFooter()
    {
        // constants only
    }

    /**
     * Builds the non-link part of the footer without creating an SWT widget.
     * <p>
     * The version is deliberately read from the supplied runtime bundle. A release qualifier and
     * a locally built qualifier therefore appear exactly as OSGi sees them instead of drifting
     * behind a second version literal in the preference page.
     * </p>
     *
     * @param bundle the plugin bundle; may be {@code null} outside OSGi
     * @return copyright and runtime bundle version
     */
    static String text(Bundle bundle)
    {
        Version version = bundle == null ? null : bundle.getVersion();
        String rendered = version == null ? "unknown" : version.toString(); //$NON-NLS-1$
        return "(c) 2026 Desko77 | Version " + rendered; //$NON-NLS-1$
    }
}
