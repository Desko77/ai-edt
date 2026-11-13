/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.support;

import java.util.List;

/** Client-only application selection within an explicitly resolved project. */
public final class ClientApplicationSelection
{
    private ClientApplicationSelection() { }

    /** Selects an explicit id, otherwise the registered default, otherwise the sole application. */
    public static String select(String explicitId, String defaultId, List<String> registeredIds)
    {
        if (explicitId != null && !explicitId.isEmpty())
        {
            if (!registeredIds.contains(explicitId))
            {
                throw new IllegalArgumentException("Explicit applicationId is not registered in the named project. Nothing was created or started.");
            }
            return explicitId;
        }
        if (defaultId != null && !defaultId.isEmpty())
        {
            if (!registeredIds.contains(defaultId))
            {
                throw new IllegalArgumentException("The configured default application is not registered in the named project. Repair the default or pass applicationId. Nothing was created or started.");
            }
            return defaultId;
        }
        if (registeredIds.size() == 1)
        {
            return registeredIds.get(0);
        }
        throw new IllegalArgumentException(registeredIds.isEmpty()
            ? "No registered application in the named project. Nothing was created or started."
            : "Several registered applications and no default in the named project. Pass applicationId. Nothing was created or started.");
    }
}
