/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support.naparnik;

/**
 * A step toward the 1C:Naparnik facade failed, and the answer has to say which step.
 * <p>
 * The link id is the name {@code status} publishes in {@code links[].link}. The message is the
 * detail. Neither is dropped: a reflection failure that becomes an empty success is a bridge that
 * looks installed and is not.
 * </p>
 */
public final class NaparnikAccessException
    extends Exception
{
    private static final long serialVersionUID = 1L;

    private final String link;

    /**
     * @param link the step that failed, as {@code status} will name it
     * @param detail what the step reported; not {@code null}
     * @param cause the failure underneath, when there is one
     */
    public NaparnikAccessException(String link, String detail, Throwable cause)
    {
        super(detail, cause);
        this.link = link;
    }

    /**
     * @return the step that failed
     */
    public String link()
    {
        return link;
    }
}
