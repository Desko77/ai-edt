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

    /**
     * The kind halves of the dotted type names in live use, censused the same way. A
     * reference type is written {@code <Kind>.<Name>}, and the gate currently believes
     * any kind at all - so a misspelled {@code CatalolgRef} goes through. Refusing an
     * unknown kind is only safe if the register knows every kind a real configuration
     * writes, which is what this list is here to find out.
     */
    private static final String[] KINDS_IN_USE = { "AccountingRegisterRecordSet", //$NON-NLS-1$
        "AccumulationRegisterRecordSet", "BusinessProcessManager", "BusinessProcessObject", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "BusinessProcessRef", "CalculationRegisterRecordSet", "CatalogManager", "CatalogObject", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "CatalogRef", "Characteristic", "ChartOfAccountsObject", "ChartOfAccountsRef", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "ChartOfCalculationTypesObject", "ChartOfCalculationTypesRef", //$NON-NLS-1$ //$NON-NLS-2$
        "ChartOfCharacteristicTypesObject", "ChartOfCharacteristicTypesRef", //$NON-NLS-1$ //$NON-NLS-2$
        "ConstantValueManager", "DataProcessorObject", "DefinedType", "DocumentManager", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "DocumentObject", "DocumentRef", "EnumRef", "ExchangePlanObject", "ExchangePlanRef", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "ExternalDataProcessor", "ExternalDataSourceCubeDimensionTableRef", //$NON-NLS-1$ //$NON-NLS-2$
        "ExternalDataSourceTableRecordManager", "InformationRegisterRecordManager", //$NON-NLS-1$ //$NON-NLS-2$
        "InformationRegisterRecordSet", "ReportObject", "SequenceRecordSet", "TaskObject", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "TaskRef" }; //$NON-NLS-1$

    /**
     * The kinds the register does NOT know, though a real configuration writes them.
     * Measured 2026-08-18 across the census above.
     */
    private static final String[] KINDS_THE_REGISTER_MISSES = { "DataProcessorObject", //$NON-NLS-1$
        "DefinedType", "ExternalDataSourceCubeDimensionTableRef", //$NON-NLS-1$ //$NON-NLS-2$
        "ExternalDataSourceTableRecordManager" }; //$NON-NLS-1$

    @Test
    public void theRegisterCannotBeAskedAboutTheKindHalfOfAReferenceType()
    {
        // This test exists to keep a door shut, and to say why.
        //
        // A misspelled kind - CatalolgRef.Валюты - still goes through the type gate,
        // because the collection it strips to is not found and that reads as "no idea"
        // rather than "wrong". The obvious repair is to put the kind itself to the
        // platform register, since CatalogRef and AnyRef are both in there.
        //
        // It does not work. Of the 34 kinds a real configuration writes, the register
        // does not know these four - so a gate built on it would refuse four legal kinds
        // to catch one typo. Measured before writing the code, not after.
        //
        // Should a later EDT start answering to them, this test fails and the repair
        // becomes available. That is the point of pinning it rather than writing a note.
        List<String> unexpectedlyKnown = new ArrayList<>();
        for (String kind : KINDS_THE_REGISTER_MISSES)
        {
            if (PlatformTypeNames.checkForVersion(kind, PRESENT) == Verdict.KNOWN)
            {
                unexpectedlyKnown.add(kind);
            }
        }
        assertEquals("the register has started answering to these kinds - the kind half of a " //$NON-NLS-1$
            + "reference type may now be checkable: " + unexpectedlyKnown, //$NON-NLS-1$
            0, unexpectedlyKnown.size());

        // And the rest of the census is known, which is what made the idea look workable.
        List<String> unknown = new ArrayList<>();
        for (String kind : KINDS_IN_USE)
        {
            if (PlatformTypeNames.checkForVersion(kind, PRESENT) != Verdict.KNOWN)
            {
                unknown.add(kind);
            }
        }
        assertEquals("kinds the register does not know: " + unknown, //$NON-NLS-1$
            KINDS_THE_REGISTER_MISSES.length, unknown.size());
    }

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
