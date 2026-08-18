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

import com._1c.g5.v8.dt.platform.version.Version;

import ru.aiedt.mcp.server.support.PlatformTypeNames.Verdict;

/**
 * Every bare type name a real configuration uses, put to the register that now decides
 * whether such a name may be written.
 * <p>
 * Refusing an unknown bare name is only safe if the register's vocabulary covers what
 * configurations actually contain. Believing that without checking is how the previous
 * attempt went wrong: a hand-written allowlist looked complete and lost to
 * {@code DynamicList} and {@code StandardPeriod} the moment it met a real form. So the
 * list below is not a sample - it is the whole set of no-dot {@code <types>} values
 * found across the demonstration workspace, its extensions included, and the test
 * exists to fail loudly if the register does not know one of them.
 * </p>
 * <p>
 * The one name deliberately left out of the list is {@code Stirng}, which the census
 * also turned up: it is the typo this whole change is about, written onto an attribute
 * by an earlier verification run and reported at the time as applied.
 * </p>
 */
public class PlatformTypeNamesCorpusTest
{
    /** The version whose bundle the test launch pulls in; see this fragment's pom. */
    private static final Version PRESENT = Version.V8_3_22;

    /**
     * Bare type names in live use, censused off disk rather than recalled. Reference and
     * object kinds appear here without a dot because a configuration does use them that
     * way - {@code AnyRef} and {@code BusinessProcessRoutePointRef} stand for "any of
     * that kind".
     */
    private static final String[] IN_USE = { "AccountingRegisterRecordSet", //$NON-NLS-1$
        "AccumulationRegisterRecordSet", "AnyRef", "Boolean", "BusinessProcessObject", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "BusinessProcessRef", "BusinessProcessRoutePointRef", "CalculationRegisterRecordSet", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "CatalogObject", "CatalogRef", "Chart", "ChartOfAccountsObject", "ChartOfAccountsRef", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "ChartOfCalculationTypesObject", "ChartOfCalculationTypesRef", //$NON-NLS-1$ //$NON-NLS-2$
        "ChartOfCharacteristicTypesObject", "ChartOfCharacteristicTypesRef", "Color", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "ConstantValueManager", "ConstantsSet", "DataCompositionComparisonType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "DataCompositionField", "DataCompositionFieldPlacement", "DataCompositionFilter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "DataCompositionGroupType", "DataCompositionPeriodAdditionType", //$NON-NLS-1$ //$NON-NLS-2$
        "DataCompositionSettingsComposer", "DataCompositionSortDirection", "Date", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "DocumentObject", "DocumentRef", "DynamicList", "EnumRef", "ExchangePlanObject", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "ExchangePlanRef", "FixedArray", "FixedMap", "FixedStructure", "Font", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "FormattedDocument", "FormattedString", "GraphicalSchema", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "InformationRegisterRecordSet", "Null", "Number", "Picture", "RecalculationRecordSet", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "ReportBuilder", "ReportObject", "SequenceRecordSet", "SpreadsheetDocument", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "StandardPeriod", "String", "TaskObject", "TaskRef", "TextDocument", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "TypeDescription", "UUID", "ValueList", "ValueStorage", "ValueTable", "ValueTree", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "VerticalAlign" }; //$NON-NLS-1$

    @Test
    public void everyNameARealConfigurationUsesIsAcceptedOnItsOwnMerits()
    {
        // Whether through the register or through the primitive floor beneath it, none of
        // these may be refused. This is the test that says the rejection is safe to ship.
        List<String> refused = new ArrayList<>();
        for (String name : IN_USE)
        {
            if (!BmDefinedTypeHelper.isAcceptableBareName(name,
                PlatformTypeNames.checkForVersion(name, PRESENT)))
            {
                refused.add(name);
            }
        }
        assertEquals("names in live use that the check would now refuse: " + refused, //$NON-NLS-1$
            0, refused.size());
    }

    @Test
    public void theTypoTheCensusAlsoFoundIsStillRefused()
    {
        // Found in the workspace beside the 63 legitimate names, written there by an
        // earlier run of this very tool and reported as applied at the time.
        assertTrue(!BmDefinedTypeHelper.isAcceptableBareName("Stirng", //$NON-NLS-1$
            PlatformTypeNames.checkForVersion("Stirng", PRESENT))); //$NON-NLS-1$
    }

    @Test
    public void theCensusIsWhatItSaysItIs()
    {
        // Guards against a name being dropped from the list while editing it: 63
        // legitimate names were censused, and a shorter list would quietly test less.
        assertEquals(63, IN_USE.length);
    }
}
