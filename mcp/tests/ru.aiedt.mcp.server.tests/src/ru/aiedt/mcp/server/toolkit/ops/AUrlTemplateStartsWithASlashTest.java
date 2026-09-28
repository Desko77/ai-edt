/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * A URL template of an HTTP service is addressed from the root of the service, so a template
 * without a leading slash is refused before anything is written.
 */
public class AUrlTemplateStartsWithASlashTest
{
    /**
     * Only a template that starts with a slash passes.
     */
    @Test
    public void onlyATemplateThatStartsWithASlashPasses()
    {
        assertNull(ServiceOps.urlTemplateRefusal("/bbk/")); //$NON-NLS-1$
        assertNull(ServiceOps.urlTemplateRefusal("/*")); //$NON-NLS-1$
        String refusal = ServiceOps.urlTemplateRefusal("bbk"); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("'bbk'")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was created")); //$NON-NLS-1$
    }

    /**
     * add_url_template refuses a template without a slash before it resolves the project, so the
     * refusal is about the template and nothing is written.
     */
    @Test
    public void addUrlTemplateRefusesBeforeWriting()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "NoSuchProject_" + System.nanoTime()); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("ownerFqn", "HTTPService.Api"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "Items"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("urlTemplate", "bbk"); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new ServiceOps().opAddUrlTemplate(params);
        assertTrue(answer, answer.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Nothing was created")); //$NON-NLS-1$
    }
}
