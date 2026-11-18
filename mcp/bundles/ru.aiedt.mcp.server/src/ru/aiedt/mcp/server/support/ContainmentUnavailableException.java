/*
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

/**
 * The owned client cannot be started under an authoritative containment on this machine.
 * <p>
 * This is a refusal, not a fallback signal: the caller must not start the client without
 * containment and must not release the infobase on a weaker liveness heuristic.
 * </p>
 */
public final class ContainmentUnavailableException extends Exception
{
    private static final long serialVersionUID = 1L;

    /**
     * Records why containment is unavailable.
     *
     * @param reason the sentence the caller returns
     */
    public ContainmentUnavailableException(String reason)
    {
        super(reason);
    }
}
