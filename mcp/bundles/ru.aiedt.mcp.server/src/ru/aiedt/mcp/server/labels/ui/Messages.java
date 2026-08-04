/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import org.eclipse.osgi.util.NLS;

/**
 * Translatable text for the marker filter dialog and manager, bound to {@code messages.properties}.
 */
public final class Messages
    extends NLS
{
    private static final String BUNDLE_NAME = "ru.aiedt.mcp.server.labels.ui.messages"; //$NON-NLS-1$

    /** Title of the filter dialog. */
    public static String FilterByMarkerDialog_Title;

    /** Explanatory line under the filter dialog title. */
    public static String FilterByMarkerDialog_Description;

    /** Label of the button that applies the filter. */
    public static String FilterByMarkerDialog_SetButton;

    /** Label of the button that turns the filter off. */
    public static String FilterByMarkerDialog_TurnOffButton;

    /** Tooltip of the select-all toolbar button. */
    public static String FilterByMarkerDialog_SelectAll;

    /** Tooltip of the deselect-all toolbar button. */
    public static String FilterByMarkerDialog_DeselectAll;

    /** Placeholder shown in the empty marker search field. */
    public static String FilterByMarkerDialog_SearchPlaceholder;

    /** Label of the context action that edits a marker. */
    public static String FilterByMarkerDialog_EditMarker;

    /** Label of the "show unmarked only" checkbox. */
    public static String FilterByMarkerDialog_ShowUnmarkedOnly;

    /** Tooltip of the "show unmarked only" checkbox. */
    public static String FilterByMarkerDialog_ShowUnmarkedOnlyTooltip;

    /** Name the Navigator shows for the applied marker filter. */
    public static String FilterByMarkerManager_FilterName;

    static
    {
        NLS.initializeMessages(BUNDLE_NAME, Messages.class);
    }

    private Messages()
    {
        // Static holder.
    }
}
