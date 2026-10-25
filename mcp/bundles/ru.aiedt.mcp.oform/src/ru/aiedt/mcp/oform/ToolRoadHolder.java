/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import ru.aiedt.mcp.server.toolkit.IToolRoad;

/**
 * The plugin's tool road, held for this bundle's calls into the plugin's tools.
 *
 * <p>The plugin publishes {@code IToolRoad} as an OSGi service; this component binds it the
 * declarative way, the same {@code OSGI-INF/} the bundle's own services are published by, and
 * holds it for as long as the service is registered. A call through the road costs what a call
 * from an agent costs - the preset gate, the heavy-call limiter, the cancellation - which is why
 * no code of this bundle calls a tool object directly.</p>
 */
public final class ToolRoadHolder
{
    /** The symbolic name recorded as the origin of this bundle's calls. */
    public static final String ORIGIN = "ru.aiedt.mcp.oform"; //$NON-NLS-1$

    private static volatile IToolRoad road;

    /**
     * Binds the service. Called by the component's reference.
     *
     * @param service the road
     */
    public void bind(IToolRoad service)
    {
        road = service;
    }

    /**
     * Releases the service. Called when the plugin's registration goes.
     *
     * @param service the road that is going
     */
    public void unbind(IToolRoad service)
    {
        if (road == service)
        {
            road = null;
        }
    }

    /**
     * @return the road, or {@code null} while the plugin has not published it
     */
    public static IToolRoad road()
    {
        return road;
    }
}
