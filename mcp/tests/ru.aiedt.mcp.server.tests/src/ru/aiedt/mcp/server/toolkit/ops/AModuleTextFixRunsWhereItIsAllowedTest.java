/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A correction is routed by what kind of fix it is, and the kind is read off the variant object.
 *
 * <p>Measured 16.09 on the stand: all fourteen corrections that change module text answered "this is
 * an EDT interface action, not an edit" and changed nothing. The judgement was the defect - any SWT
 * exception was read as "this opens a panel", and a fix that edits the module's Xtext document
 * throws exactly that away from the display thread.
 *
 * <p>The descriptor of a variant carries only a localised description, so it cannot be the
 * discriminator; the variant object can, because the Xtext module fixes are declared in
 * <code>com.e1c.g5.v8.dt.bsl.check.qfix</code> and the model fixes are not.
 */
public class AModuleTextFixRunsWhereItIsAllowedTest
{
    @Test
    public void aFixDeclaredAmongTheXtextModuleFixesEditsModuleText()
    {
        assertTrue(MarkerCorrectionTool.isXtextModuleFixName(
            "com.e1c.g5.v8.dt.bsl.check.qfix.SingleVariantXtextBslModuleFix")); //$NON-NLS-1$
    }

    @Test
    public void aVariantNestedInsideSuchAFixCountsToo()
    {
        // The manager hands back the variant, which is an inner class of the fix - so the question
        // has to hold for the inner name as well.
        assertTrue(MarkerCorrectionTool.isXtextModuleFixName(
            "com.e1c.g5.v8.dt.bsl.check.qfix.MultiVariantXtextBslModuleFix$MultiVariantXtextBslModuleVariant")); //$NON-NLS-1$
    }

    @Test
    public void aModelFixDoesNotEditModuleText()
    {
        assertFalse(MarkerCorrectionTool.isXtextModuleFixName(
            "com.e1c.g5.v8.dt.check.qfix.components.MultiVariantModelBasicFix$BasicModelVariant")); //$NON-NLS-1$
    }

    @Test
    public void anythingElseKeepsTheOldPath()
    {
        assertFalse("an unknown kind takes the path that refuses rather than the one that writes", //$NON-NLS-1$
            MarkerCorrectionTool.isXtextModuleFixName("java.lang.String")); //$NON-NLS-1$
        assertFalse(MarkerCorrectionTool.isXtextModuleFixName(null));
    }

    @Test
    public void aClassOfThatFamilyIsRecognisedThroughItsEnclosingClasses()
    {
        assertFalse("a class from anywhere else answers no whatever it is nested in", //$NON-NLS-1$
            MarkerCorrectionTool.namesAnXtextModuleFix(String.class));
    }
}
