/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

import ru.aiedt.mcp.server.labels.ui.MarkerFilterController;

/**
 * Opens the "Filter by Marker" dialog, from either the Navigator toolbar toggle or the view menu.
 */
public class FilterByMarkerCommand
    extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        MarkerFilterController.getInstance().openFilterDialog();
        return null;
    }
}
