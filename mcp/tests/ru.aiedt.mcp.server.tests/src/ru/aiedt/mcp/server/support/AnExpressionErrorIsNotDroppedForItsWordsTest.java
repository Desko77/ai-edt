/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * An expression check keeps the errors that are about the expression, whatever words they use.
 * <p>
 * The wrapper filter dropped every diagnostic whose text held "from" or " из ", and the platform's
 * own errors say "Поле из запроса ..." and "... from ...". A result with no ERROR diagnostic but a
 * parse the parser objected to read as clean.
 * </p>
 */
public class AnExpressionErrorIsNotDroppedForItsWordsTest
{
    @Test
    public void anErrorSayingIzOrFromIsKept()
    {
        List<QlValidator.QlIssue> raw = new ArrayList<>();
        raw.add(new QlValidator.QlIssue("ERROR", //$NON-NLS-1$
            "Поле из запроса для операции 'В' не может содержать составной тип", 1, 5, "compound")); //$NON-NLS-1$ //$NON-NLS-2$
        raw.add(new QlValidator.QlIssue("ERROR", "Field from different nested tables", 1, 9, "nested")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<QlValidator.QlIssue> kept = QlValidator.whatTheExpressionOwns(raw);

        assertEquals("an error about the expression is kept for its words", 2, kept.size()); //$NON-NLS-1$
        assertTrue(QlValidator.ValidationResult.of(kept).hasErrors());
    }

    @Test
    public void aDiagnosticAboutTheWrappersAliasIsDropped()
    {
        List<QlValidator.QlIssue> raw = new ArrayList<>();
        raw.add(new QlValidator.QlIssue("ERROR", //$NON-NLS-1$
            "Duplicate alias " + QlValidator.EXPRESSION_PROBE_ALIAS, 1, 20, "alias")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(0, QlValidator.whatTheExpressionOwns(raw).size());
    }

    @Test
    public void aParseTheParserObjectedToIsAnError()
    {
        QlValidator.ValidationResult result = QlValidator.ValidationResult.of(new ArrayList<>());
        result.parserObjected = true;

        assertTrue("a text that does not parse is not clean", result.hasErrors()); //$NON-NLS-1$
    }
}
