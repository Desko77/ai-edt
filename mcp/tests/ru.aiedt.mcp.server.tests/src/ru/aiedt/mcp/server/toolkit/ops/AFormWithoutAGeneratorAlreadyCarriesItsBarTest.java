/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;

import ru.aiedt.mcp.server.support.FormBaseSetup;

/**
 * What the {@code autoCommandBarAdded} tag of {@code create_form} reports.
 * <p>
 * The tag answers whether the form the caller receives carries an auto command bar, and it is read
 * from the finished form. Two halves can put that bar there: the form generator builds one into the
 * layout it produces, and a form that came out without one receives the container together with its
 * base properties. A form created for an empty layout is of the second kind - it holds its bar
 * before the deterministic-content step looks - so a tag read from that step alone stayed absent
 * exactly where the bar was there.
 * </p>
 */
public class AFormWithoutAGeneratorAlreadyCarriesItsBarTest
{
    /** A form that received its base properties holds a bar, whether a generator built it or not. */
    @Test
    public void aFormWithItsBasePropertiesHoldsItsBar()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormBaseSetup.applyDefaults(form);

        assertTrue("the bar comes from the base properties and the tag has to see it", //$NON-NLS-1$
            FormCreateOps.carriesAutoCommandBar(form));
    }

    /** A form the model factory built and nothing else did carries no bar. */
    @Test
    public void aBareFactoryFormCarriesNoBar()
    {
        assertFalse(FormCreateOps.carriesAutoCommandBar(FormFactory.eINSTANCE.createForm()));
    }

    /** A call that built no form has no bar to report. */
    @Test
    public void noFormCarriesNoBar()
    {
        assertFalse(FormCreateOps.carriesAutoCommandBar(null));
    }
}
