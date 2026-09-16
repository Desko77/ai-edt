/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A letter of one alphabet inside a word of the other is named, with where it sits.
 * <p>
 * A Latin C at the front of Сумма makes an object that is printed exactly like the one a person
 * meant and is found by no search for it. The platform takes the name, the editor shows it, and the
 * only sign is that references come back empty.
 * </p>
 * <p>
 * The check has to stay narrow: ЗагрузкаXML, ОбменSMS and HTTPЗапрос are ordinary names in a
 * configuration, and a check that refused them would be turned off.
 * </p>
 */
public class ALetterOfTheOtherAlphabetIsNamedTest
{
    /** One Latin letter at the front of a Cyrillic word: the case this exists for. */
    @Test
    public void aLatinLetterInsideACyrillicWordIsNamed()
    {
        String wrong = OneAlphabetPerWord.whatIsWrong("Cумма"); //$NON-NLS-1$
        assertNotNull("a Latin C before умма", wrong); //$NON-NLS-1$
        assertTrue(wrong, wrong.contains("position 1")); //$NON-NLS-1$
        assertTrue(wrong, wrong.contains("Latin")); //$NON-NLS-1$
        assertTrue("and the name it was meant to be", wrong.contains("Сумма")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** It is found in the middle of a word too, where nobody would look. */
    @Test
    public void aLatinLetterInTheMiddleIsNamedWithItsPosition()
    {
        String wrong = OneAlphabetPerWord.whatIsWrong("Кoнтрагент"); //$NON-NLS-1$
        assertNotNull("a Latin o in Контрагент", wrong); //$NON-NLS-1$
        assertTrue(wrong, wrong.contains("position 2")); //$NON-NLS-1$
        assertTrue(wrong, wrong.contains("Контрагент")); //$NON-NLS-1$
    }

    /** And the other way round: a Cyrillic letter inside a Latin word. */
    @Test
    public void aCyrillicLetterInsideALatinWordIsNamed()
    {
        String wrong = OneAlphabetPerWord.whatIsWrong("Dосument"); //$NON-NLS-1$
        assertNotNull("a Cyrillic о in Document", wrong); //$NON-NLS-1$
        assertTrue(wrong, wrong.contains("Cyrillic")); //$NON-NLS-1$
    }

    /** A name written in one alphabet passes, whichever it is. */
    @Test
    public void aNameInOneAlphabetPasses()
    {
        assertNull(OneAlphabetPerWord.whatIsWrong("Сумма")); //$NON-NLS-1$
        assertNull(OneAlphabetPerWord.whatIsWrong("Document")); //$NON-NLS-1$
        assertNull(OneAlphabetPerWord.whatIsWrong("СуммаДокумента2")); //$NON-NLS-1$
        assertNull(OneAlphabetPerWord.whatIsWrong("")); //$NON-NLS-1$
        assertNull(OneAlphabetPerWord.whatIsWrong(null));
    }

    /** The names a configuration is actually written with are not touched. */
    @Test
    public void anOrdinaryMixedNamePasses()
    {
        assertNull("a word of the other alphabet is a word", //$NON-NLS-1$
            OneAlphabetPerWord.whatIsWrong("ЗагрузкаXML")); //$NON-NLS-1$
        assertNull(OneAlphabetPerWord.whatIsWrong("ОбменSMS")); //$NON-NLS-1$
        assertNull(OneAlphabetPerWord.whatIsWrong("HTTPЗапрос")); //$NON-NLS-1$
        assertNull(OneAlphabetPerWord.whatIsWrong("ТаблицаSQL")); //$NON-NLS-1$
        assertNull("separated by an underscore, they are two words", //$NON-NLS-1$
            OneAlphabetPerWord.whatIsWrong("ВТ_Data")); //$NON-NLS-1$
    }
}
