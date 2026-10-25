/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.layout;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The standard attributes an ordinary form's data path names by a negative code, per kind of
 * the object the form's main attribute holds.
 *
 * <p>Measured over the bindings of a configuration on platform 8.2 compatibility (1335 forms):
 * the element bound to a code is named after the attribute in every case counted, so the table
 * below is the corpus, not a specification. A code outside it stays a code in the answer.</p>
 */
public final class StandardAttributes
{
    private static final Map<String, Map<Integer, String>> BY_KIND = new HashMap<>();

    static
    {
        Map<String, String> ordinal = Map.of("Catalog", "-2 Код, -3 Наименование, -4 Родитель, -5 Владелец", //$NON-NLS-1$ //$NON-NLS-2$
            "Document", "-2 Номер, -3 Дата", //$NON-NLS-1$ //$NON-NLS-2$
            "BusinessProcess", "-2 Номер, -3 Дата", //$NON-NLS-1$ //$NON-NLS-2$
            "Task", "-3 Дата, -9 Наименование, -10 Выполнена", //$NON-NLS-1$ //$NON-NLS-2$
            "ExchangePlan", "-2 Код, -3 Наименование, -9 НомерОтправленного, -10 НомерПринятого", //$NON-NLS-1$ //$NON-NLS-2$
            "ChartOfCharacteristicTypes", "-6 Родитель, -8 Код, -9 Наименование, -11 ТипЗначения", //$NON-NLS-1$ //$NON-NLS-2$
            "InformationRegister", "-2 Период"); //$NON-NLS-1$ //$NON-NLS-2$
        for (Map.Entry<String, String> entry : ordinal.entrySet())
        {
            Map<Integer, String> codes = new HashMap<>();
            for (String pair : entry.getValue().split(", ")) //$NON-NLS-1$
            {
                String[] parts = pair.split(" "); //$NON-NLS-1$
                codes.put(Integer.valueOf(parts[0]), parts[1]);
            }
            BY_KIND.put(entry.getKey().toLowerCase(Locale.ROOT), codes);
        }
    }

    private StandardAttributes()
    {
        // static table
    }

    /**
     * The name of a standard attribute.
     *
     * @param kind the metadata kind of the object, in EDT's English spelling ({@code Catalog},
     *        {@code Document}, ...); case does not matter
     * @param code the negative code the layout records
     * @return the name, or {@code null} when the corpus did not measure the pair
     */
    public static String nameOf(String kind, int code)
    {
        if (kind == null)
        {
            return null;
        }
        Map<Integer, String> codes = BY_KIND.get(kind.toLowerCase(Locale.ROOT));
        return codes == null ? null : codes.get(Integer.valueOf(code));
    }

    /**
     * The code of a standard attribute by name.
     *
     * @param kind the metadata kind of the object, case does not matter
     * @param name the attribute's name, case-insensitive
     * @return the code, or {@code null} when the corpus did not measure the pair
     */
    public static Integer codeOf(String kind, String name)
    {
        if (kind == null || name == null)
        {
            return null;
        }
        Map<Integer, String> codes = BY_KIND.get(kind.toLowerCase(Locale.ROOT));
        if (codes == null)
        {
            return null;
        }
        for (Map.Entry<Integer, String> entry : codes.entrySet())
        {
            if (entry.getValue().equalsIgnoreCase(name))
            {
                return entry.getKey();
            }
        }
        return null;
    }
}
