/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * A picture listing that names a project answers for that project.
 * <p>
 * A {@code projectName} no open project carries used to fall through to the newest platform
 * version inside {@code StockPictures.versionOf} - the same answer a call without a project name
 * gives, so a typo read back as a deliberate versionless listing. The operation resolves the name
 * first and refuses with {@code projectNotFound}, the way its neighbouring operations in the same
 * cluster already do.
 * </p>
 */
public class AListPicturesRefusesAProjectThatIsNotThereTest
{
    /**
     * A project name that resolves to nothing is refused, and the refusal names it.
     */
    @Test
    public void aProjectNameThatResolvesToNothingIsRefused()
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", "aiedt-tests-no-such-project"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = new FormItemsOps().opListPictures(params);

        assertTrue(answer, answer.contains("projectNotFound")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("aiedt-tests-no-such-project")); //$NON-NLS-1$
    }

    /**
     * A call without a project name is the documented versionless listing, not a refusal.
     */
    @Test
    public void aCallWithoutAProjectNameIsNotARefusal()
    {
        String answer = new FormItemsOps().opListPictures(new LinkedHashMap<>());

        assertFalse(answer, answer.contains("projectNotFound")); //$NON-NLS-1$
    }
}
