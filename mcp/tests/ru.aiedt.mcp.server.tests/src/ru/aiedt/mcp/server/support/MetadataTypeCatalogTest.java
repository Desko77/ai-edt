/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

import ru.aiedt.mcp.server.support.MetadataTypeCatalog.MetadataTypeInfo;

/**
 * Verifies the name-registry side of {@link MetadataTypeCatalog}: every spelling of a type resolves to
 * the same canonical form, directories round-trip, and FQN rewriting keeps the object name intact.
 * The EMF-bound side ({@code getObjects}/{@code findObject}/{@code findSimilarObjects}) needs a live
 * Configuration and is not exercised here.
 */
public class MetadataTypeCatalogTest
{
    // ---------- toEnglishSingular: English input ----------

    @Test
    public void englishSingularResolvesToItself()
    {
        assertEquals("Catalog", MetadataTypeCatalog.toEnglishSingular("Catalog"));
        assertEquals("Document", MetadataTypeCatalog.toEnglishSingular("Document"));
        assertEquals("CommonModule", MetadataTypeCatalog.toEnglishSingular("CommonModule"));
        assertEquals("InformationRegister", MetadataTypeCatalog.toEnglishSingular("InformationRegister"));
        assertEquals("AccumulationRegister", MetadataTypeCatalog.toEnglishSingular("AccumulationRegister"));
        assertEquals("Enum", MetadataTypeCatalog.toEnglishSingular("Enum"));
        assertEquals("Report", MetadataTypeCatalog.toEnglishSingular("Report"));
        assertEquals("DataProcessor", MetadataTypeCatalog.toEnglishSingular("DataProcessor"));
        assertEquals("ExchangePlan", MetadataTypeCatalog.toEnglishSingular("ExchangePlan"));
        assertEquals("BusinessProcess", MetadataTypeCatalog.toEnglishSingular("BusinessProcess"));
        assertEquals("Task", MetadataTypeCatalog.toEnglishSingular("Task"));
        assertEquals("Constant", MetadataTypeCatalog.toEnglishSingular("Constant"));
        assertEquals("HTTPService", MetadataTypeCatalog.toEnglishSingular("HTTPService"));
        assertEquals("WebService", MetadataTypeCatalog.toEnglishSingular("WebService"));
    }

    @Test
    public void englishPluralResolvesToSingular()
    {
        assertEquals("Catalog", MetadataTypeCatalog.toEnglishSingular("Catalogs"));
        assertEquals("Document", MetadataTypeCatalog.toEnglishSingular("Documents"));
        assertEquals("CommonModule", MetadataTypeCatalog.toEnglishSingular("CommonModules"));
        assertEquals("InformationRegister", MetadataTypeCatalog.toEnglishSingular("InformationRegisters"));
        assertEquals("BusinessProcess", MetadataTypeCatalog.toEnglishSingular("BusinessProcesses"));
        assertEquals("ChartOfCharacteristicTypes",
            MetadataTypeCatalog.toEnglishSingular("ChartsOfCharacteristicTypes"));
        assertEquals("ChartOfAccounts", MetadataTypeCatalog.toEnglishSingular("ChartsOfAccounts"));
        assertEquals("FilterCriterion", MetadataTypeCatalog.toEnglishSingular("FilterCriteria"));
    }

    // ---------- toEnglishSingular: Russian input ----------

    @Test
    public void russianSingularResolvesToEnglishSingular()
    {
        assertEquals("Catalog", MetadataTypeCatalog.toEnglishSingular("Справочник"));
        assertEquals("Document", MetadataTypeCatalog.toEnglishSingular("Документ"));
        assertEquals("CommonModule", MetadataTypeCatalog.toEnglishSingular("ОбщийМодуль"));
        assertEquals("InformationRegister",
            MetadataTypeCatalog.toEnglishSingular("РегистрСведений"));
        assertEquals("AccumulationRegister",
            MetadataTypeCatalog.toEnglishSingular("РегистрНакопления"));
        assertEquals("Enum", MetadataTypeCatalog.toEnglishSingular("Перечисление"));
        assertEquals("Report", MetadataTypeCatalog.toEnglishSingular("Отчет"));
        assertEquals("DataProcessor", MetadataTypeCatalog.toEnglishSingular("Обработка"));
        assertEquals("ExchangePlan", MetadataTypeCatalog.toEnglishSingular("ПланОбмена"));
        assertEquals("BusinessProcess", MetadataTypeCatalog.toEnglishSingular("БизнесПроцесс"));
        assertEquals("Task", MetadataTypeCatalog.toEnglishSingular("Задача"));
        assertEquals("Role", MetadataTypeCatalog.toEnglishSingular("Роль"));
        assertEquals("Subsystem", MetadataTypeCatalog.toEnglishSingular("Подсистема"));
        assertEquals("CommonCommand", MetadataTypeCatalog.toEnglishSingular("ОбщаяКоманда"));
        assertEquals("CommonForm", MetadataTypeCatalog.toEnglishSingular("ОбщаяФорма"));
        assertEquals("WebService", MetadataTypeCatalog.toEnglishSingular("ВебСервис"));
        assertEquals("HTTPService", MetadataTypeCatalog.toEnglishSingular("HTTPСервис"));
        assertEquals("Constant", MetadataTypeCatalog.toEnglishSingular("Константа"));
        assertEquals("ChartOfCharacteristicTypes",
            MetadataTypeCatalog.toEnglishSingular("ПланВидовХарактеристик"));
        assertEquals("ChartOfAccounts", MetadataTypeCatalog.toEnglishSingular("ПланСчетов"));
        assertEquals("AccountingRegister",
            MetadataTypeCatalog.toEnglishSingular("РегистрБухгалтерии"));
        assertEquals("CalculationRegister",
            MetadataTypeCatalog.toEnglishSingular("РегистрРасчета"));
        assertEquals("EventSubscription",
            MetadataTypeCatalog.toEnglishSingular("ПодпискаНаСобытие"));
        assertEquals("ScheduledJob",
            MetadataTypeCatalog.toEnglishSingular("РегламентноеЗадание"));
    }

    @Test
    public void russianPluralResolvesToSingular()
    {
        assertEquals("Catalog", MetadataTypeCatalog.toEnglishSingular("Справочники"));
        assertEquals("Document", MetadataTypeCatalog.toEnglishSingular("Документы"));
        assertEquals("InformationRegister",
            MetadataTypeCatalog.toEnglishSingular("РегистрыСведений"));
        assertEquals("AccumulationRegister",
            MetadataTypeCatalog.toEnglishSingular("РегистрыНакопления"));
        assertEquals("Report", MetadataTypeCatalog.toEnglishSingular("Отчеты"));
        assertEquals("DataProcessor", MetadataTypeCatalog.toEnglishSingular("Обработки"));
        assertEquals("ExchangePlan", MetadataTypeCatalog.toEnglishSingular("ПланыОбмена"));
        assertEquals("BusinessProcess", MetadataTypeCatalog.toEnglishSingular("БизнесПроцессы"));
        assertEquals("Task", MetadataTypeCatalog.toEnglishSingular("Задачи"));
        assertEquals("Constant", MetadataTypeCatalog.toEnglishSingular("Константы"));
        assertEquals("Enum", MetadataTypeCatalog.toEnglishSingular("Перечисления"));
    }

    // ---------- case insensitivity ----------

    @Test
    public void resolutionIgnoresCaseLatinAndCyrillic()
    {
        assertEquals("Catalog", MetadataTypeCatalog.toEnglishSingular("catalog"));
        assertEquals("Catalog", MetadataTypeCatalog.toEnglishSingular("CATALOG"));
        assertEquals("Catalog", MetadataTypeCatalog.toEnglishSingular("CaTaLoG"));
        assertEquals("Document", MetadataTypeCatalog.toEnglishSingular("document"));
        assertEquals("Document", MetadataTypeCatalog.toEnglishSingular("DOCUMENTS"));
        assertEquals("Catalog", MetadataTypeCatalog.toEnglishSingular("справочник"));
        assertEquals("Catalog", MetadataTypeCatalog.toEnglishSingular("СПРАВОЧНИК"));
    }

    @Test
    public void unrecognizedNamesReturnNull()
    {
        assertNull(MetadataTypeCatalog.toEnglishSingular("UnknownType"));
        assertNull(MetadataTypeCatalog.toEnglishSingular(""));
        assertNull(MetadataTypeCatalog.toEnglishSingular(null));
        assertNull(MetadataTypeCatalog.toEnglishSingular("Products"));
    }

    // ---------- isMetadataTypeName ----------

    @Test
    public void isMetadataTypeNameRecognizesEverySpelling()
    {
        assertTrue(MetadataTypeCatalog.isMetadataTypeName("Catalog"));
        assertTrue(MetadataTypeCatalog.isMetadataTypeName("Catalogs"));
        assertTrue(MetadataTypeCatalog.isMetadataTypeName("Document"));
        assertTrue(MetadataTypeCatalog.isMetadataTypeName("catalog"));
        assertTrue(MetadataTypeCatalog.isMetadataTypeName("Справочник"));
        assertTrue(MetadataTypeCatalog.isMetadataTypeName("Документ"));
        assertTrue(MetadataTypeCatalog.isMetadataTypeName("РегистрСведений"));
    }

    @Test
    public void isMetadataTypeNameRejectsUnknown()
    {
        assertFalse(MetadataTypeCatalog.isMetadataTypeName("Products"));
        assertFalse(MetadataTypeCatalog.isMetadataTypeName("Random"));
        assertFalse(MetadataTypeCatalog.isMetadataTypeName(""));
        assertFalse(MetadataTypeCatalog.isMetadataTypeName(null));
    }

    // ---------- getDirectoryName ----------

    @Test
    public void directoryNameFromEnglishSingular()
    {
        assertEquals("Catalogs", MetadataTypeCatalog.getDirectoryName("Catalog"));
        assertEquals("Documents", MetadataTypeCatalog.getDirectoryName("Document"));
        assertEquals("CommonModules", MetadataTypeCatalog.getDirectoryName("CommonModule"));
        assertEquals("InformationRegisters", MetadataTypeCatalog.getDirectoryName("InformationRegister"));
        assertEquals("AccumulationRegisters", MetadataTypeCatalog.getDirectoryName("AccumulationRegister"));
        assertEquals("Enums", MetadataTypeCatalog.getDirectoryName("Enum"));
        assertEquals("Reports", MetadataTypeCatalog.getDirectoryName("Report"));
        assertEquals("DataProcessors", MetadataTypeCatalog.getDirectoryName("DataProcessor"));
        assertEquals("ExchangePlans", MetadataTypeCatalog.getDirectoryName("ExchangePlan"));
        assertEquals("BusinessProcesses", MetadataTypeCatalog.getDirectoryName("BusinessProcess"));
        assertEquals("Tasks", MetadataTypeCatalog.getDirectoryName("Task"));
        assertEquals("Constants", MetadataTypeCatalog.getDirectoryName("Constant"));
        assertEquals("HTTPServices", MetadataTypeCatalog.getDirectoryName("HTTPService"));
        assertEquals("ChartsOfCharacteristicTypes", MetadataTypeCatalog.getDirectoryName("ChartOfCharacteristicTypes"));
        assertEquals("ChartsOfAccounts", MetadataTypeCatalog.getDirectoryName("ChartOfAccounts"));
        assertEquals("FilterCriteria", MetadataTypeCatalog.getDirectoryName("FilterCriterion"));
    }

    @Test
    public void directoryNameFromRussian()
    {
        assertEquals("Catalogs", MetadataTypeCatalog.getDirectoryName("Справочник"));
        assertEquals("Documents", MetadataTypeCatalog.getDirectoryName("Документ"));
        assertEquals("InformationRegisters",
            MetadataTypeCatalog.getDirectoryName("РегистрСведений"));
    }

    @Test
    public void directoryNameNullForUnknownAndForPathlessTypes()
    {
        assertNull(MetadataTypeCatalog.getDirectoryName("UnknownType"));
        assertNull(MetadataTypeCatalog.getDirectoryName(null));
        // Roles and subsystems have folders on disk but are not addressed by path through this API.
        assertNull(MetadataTypeCatalog.getDirectoryName("Role"));
        assertNull(MetadataTypeCatalog.getDirectoryName("Subsystem"));
    }

    // ---------- getConfigReferenceName ----------

    @Test
    public void configReferenceNameForAssortedTypes()
    {
        assertEquals("catalogs", MetadataTypeCatalog.getConfigReferenceName("Catalog"));
        assertEquals("documents", MetadataTypeCatalog.getConfigReferenceName("Document"));
        assertEquals("commonModules", MetadataTypeCatalog.getConfigReferenceName("CommonModule"));
        assertEquals("businessProcesses", MetadataTypeCatalog.getConfigReferenceName("BusinessProcess"));
        assertEquals("chartsOfCharacteristicTypes",
            MetadataTypeCatalog.getConfigReferenceName("ChartOfCharacteristicTypes"));
        assertEquals("chartsOfAccounts", MetadataTypeCatalog.getConfigReferenceName("ChartOfAccounts"));
        assertEquals("filterCriteria", MetadataTypeCatalog.getConfigReferenceName("FilterCriterion"));
        assertEquals("httpServices", MetadataTypeCatalog.getConfigReferenceName("HTTPService"));
        assertEquals("xdtoPackages", MetadataTypeCatalog.getConfigReferenceName("XDTOPackage"));
    }

    @Test
    public void configReferenceNameFromRussian()
    {
        assertEquals("catalogs", MetadataTypeCatalog.getConfigReferenceName("Справочник"));
        assertEquals("documents", MetadataTypeCatalog.getConfigReferenceName("Документ"));
    }

    // ---------- getTypeByDirectoryName (case-sensitive) ----------

    @Test
    public void typeByDirectoryNameRoundTrips()
    {
        assertEquals("Catalog", MetadataTypeCatalog.getTypeByDirectoryName("Catalogs"));
        assertEquals("Document", MetadataTypeCatalog.getTypeByDirectoryName("Documents"));
        assertEquals("CommonModule", MetadataTypeCatalog.getTypeByDirectoryName("CommonModules"));
        assertEquals("InformationRegister", MetadataTypeCatalog.getTypeByDirectoryName("InformationRegisters"));
        assertEquals("BusinessProcess", MetadataTypeCatalog.getTypeByDirectoryName("BusinessProcesses"));
        assertEquals("ChartOfAccounts", MetadataTypeCatalog.getTypeByDirectoryName("ChartsOfAccounts"));
        assertEquals("ChartOfCharacteristicTypes",
            MetadataTypeCatalog.getTypeByDirectoryName("ChartsOfCharacteristicTypes"));
        assertEquals("FilterCriterion", MetadataTypeCatalog.getTypeByDirectoryName("FilterCriteria"));
        assertEquals("HTTPService", MetadataTypeCatalog.getTypeByDirectoryName("HTTPServices"));
    }

    @Test
    public void typeByDirectoryNameUnknownReturnsNull()
    {
        assertNull(MetadataTypeCatalog.getTypeByDirectoryName("UnknownDir"));
        assertNull(MetadataTypeCatalog.getTypeByDirectoryName(null));
        assertNull(MetadataTypeCatalog.getTypeByDirectoryName(""));
    }

    // ---------- normalizeFqn ----------

    @Test
    public void normalizeRewritesRussianTypeSegmentKeepingObjectName()
    {
        assertEquals("Document.Встреча",
            MetadataTypeCatalog.normalizeFqn("Документ.Встреча"));
        assertEquals("Catalog.УслугиSLA",
            MetadataTypeCatalog.normalizeFqn("Справочник.УслугиSLA"));
        assertEquals("InformationRegister.РеквизитыSLA",
            MetadataTypeCatalog.normalizeFqn(
                "РегистрСведений.РеквизитыSLA"));
        assertEquals("Enum.TelegramВидКлавиатуры",
            MetadataTypeCatalog.normalizeFqn(
                "Перечисление.TelegramВидКлавиатуры"));
    }

    @Test
    public void normalizeLeavesAlreadyEnglishFqnUntouched()
    {
        assertEquals("Document.SalesOrder", MetadataTypeCatalog.normalizeFqn("Document.SalesOrder"));
        assertEquals("Catalog.Products", MetadataTypeCatalog.normalizeFqn("Catalog.Products"));
    }

    @Test
    public void normalizeCollapsesPluralTypeToSingular()
    {
        assertEquals("Catalog.Products", MetadataTypeCatalog.normalizeFqn("Catalogs.Products"));
        assertEquals("Document.SalesOrder", MetadataTypeCatalog.normalizeFqn("Documents.SalesOrder"));
    }

    @Test
    public void normalizePassesThroughUnrecognizedType()
    {
        assertEquals("UnknownType.Name", MetadataTypeCatalog.normalizeFqn("UnknownType.Name"));
        assertEquals("MyModule.Method", MetadataTypeCatalog.normalizeFqn("MyModule.Method"));
    }

    @Test
    public void normalizeHandlesDegenerateInputs()
    {
        assertNull(MetadataTypeCatalog.normalizeFqn(null));
        assertEquals("", MetadataTypeCatalog.normalizeFqn(""));
        assertEquals("NoDotHere", MetadataTypeCatalog.normalizeFqn("NoDotHere"));
    }

    // ---------- getAllEnglishSingularNames ----------

    @Test
    public void allEnglishSingularNamesIsCompleteAndLowercaseFree()
    {
        Set<String> names = MetadataTypeCatalog.getAllEnglishSingularNames();
        assertNotNull(names);
        assertTrue(names.contains("Catalog"));
        assertTrue(names.contains("Document"));
        assertTrue(names.contains("CommonModule"));
        assertTrue(names.contains("ChartOfCharacteristicTypes"));
        assertTrue(names.contains("FilterCriterion"));
        assertTrue("expected a broad registry, got " + names.size(), names.size() >= 40);
    }

    // ---------- resolve ----------

    @Test
    public void resolveReturnsInfoWithAllAliases()
    {
        MetadataTypeInfo info = MetadataTypeCatalog.resolve("Catalog");
        assertNotNull(info);
        assertEquals("Catalog", info.getEnglishSingular());
        assertEquals("Catalogs", info.getEnglishPlural());
        assertEquals("catalogs", info.getConfigReferenceName());
        assertEquals("Catalogs", info.getDirectoryName());
    }

    @Test
    public void resolveFromRussian()
    {
        MetadataTypeInfo info = MetadataTypeCatalog.resolve("Документ");
        assertNotNull(info);
        assertEquals("Document", info.getEnglishSingular());
    }

    @Test
    public void resolveUnknownReturnsNull()
    {
        assertNull(MetadataTypeCatalog.resolve("UnknownType"));
        assertNull(MetadataTypeCatalog.resolve(null));
    }

    @Test
    public void russianNamesArrayIsDefensiveCopy()
    {
        MetadataTypeInfo info = MetadataTypeCatalog.resolve("Catalog");
        assertNotNull(info);
        String[] first = info.getRussianNames();
        String[] second = info.getRussianNames();
        assertFalse("getRussianNames must return a fresh array", first == second);
        assertEquals(first.length, second.length);
    }

    // ---------- registry round-trip / invariants ----------

    @Test
    public void directoryRoundTripHoldsForEveryTypeWithADirectory()
    {
        for (MetadataTypeInfo info : MetadataTypeInfo.values())
        {
            if (info.getDirectoryName() == null)
            {
                continue;
            }
            String dir = MetadataTypeCatalog.getDirectoryName(info.getEnglishSingular());
            assertNotNull("no directory for " + info.getEnglishSingular(), dir);
            assertEquals(info.getDirectoryName(), dir);
            assertEquals(info.getEnglishSingular(), MetadataTypeCatalog.getTypeByDirectoryName(dir));
        }
    }

    @Test
    public void everyTypeHasANonEmptyConfigReferenceName()
    {
        for (MetadataTypeInfo info : MetadataTypeInfo.values())
        {
            assertNotNull("null config reference for " + info.getEnglishSingular(), info.getConfigReferenceName());
            assertFalse("empty config reference for " + info.getEnglishSingular(),
                info.getConfigReferenceName().isEmpty());
        }
    }

    @Test
    public void everyEnglishNameResolvesBackToItself()
    {
        for (MetadataTypeInfo info : MetadataTypeInfo.values())
        {
            assertEquals(info.getEnglishSingular(), MetadataTypeCatalog.toEnglishSingular(info.getEnglishSingular()));
            assertEquals(info.getEnglishSingular(), MetadataTypeCatalog.toEnglishSingular(info.getEnglishPlural()));
        }
    }

    @Test
    public void everyRussianNameResolvesToItsEnglishSingular()
    {
        for (MetadataTypeInfo info : MetadataTypeInfo.values())
        {
            for (String russian : info.getRussianNames())
            {
                assertEquals("Russian name " + russian + " did not resolve",
                    info.getEnglishSingular(), MetadataTypeCatalog.toEnglishSingular(russian));
            }
        }
    }

    // ---------- getAllFqnVariants ----------

    @Test
    public void fqnVariantsFromRussianInput()
    {
        Set<String> variants = MetadataTypeCatalog.getAllFqnVariants(
            "Документ.Расходы");
        assertTrue(variants.contains("документ.расходы"));
        assertTrue(variants.contains("document.расходы"));
    }

    @Test
    public void fqnVariantsFromEnglishInput()
    {
        Set<String> variants = MetadataTypeCatalog.getAllFqnVariants("Document.SalesOrder");
        assertTrue(variants.contains("document.salesorder"));
        assertTrue(variants.contains("документ.salesorder"));
    }

    @Test
    public void fqnVariantsFromEnglishPlural()
    {
        Set<String> variants = MetadataTypeCatalog.getAllFqnVariants("Catalogs.Products");
        assertTrue(variants.contains("catalogs.products"));
        assertTrue(variants.contains("catalog.products"));
        assertTrue(variants.contains("справочник.products"));
    }

    @Test
    public void fqnVariantsLowercaseMixedCase()
    {
        Set<String> variants = MetadataTypeCatalog.getAllFqnVariants("DOCUMENT.SalesOrder");
        assertTrue(variants.contains("document.salesorder"));
        assertTrue(variants.contains("документ.salesorder"));
    }

    @Test
    public void fqnVariantsUnknownTypeYieldsOnlyOriginal()
    {
        Set<String> variants = MetadataTypeCatalog.getAllFqnVariants("UnknownType.Name");
        assertEquals(1, variants.size());
        assertTrue(variants.contains("unknowntype.name"));
    }

    @Test
    public void fqnVariantsNoDotYieldsOne()
    {
        Set<String> variants = MetadataTypeCatalog.getAllFqnVariants("BareName");
        assertEquals(1, variants.size());
        assertTrue(variants.contains("barename"));
    }

    @Test
    public void fqnVariantsNullOrEmptyIsEmpty()
    {
        assertTrue(MetadataTypeCatalog.getAllFqnVariants(null).isEmpty());
        assertTrue(MetadataTypeCatalog.getAllFqnVariants("").isEmpty());
    }

    @Test
    public void fqnVariantsDeduplicateWhenEnglishSingularMatchesOriginal()
    {
        Set<String> variants = MetadataTypeCatalog.getAllFqnVariants("Document.Test");
        assertEquals(2, variants.size());
    }

    @Test
    public void fqnVariantsAreAllLowercase()
    {
        Set<String> variants = MetadataTypeCatalog.getAllFqnVariants("Catalog.MyObject");
        for (String v : variants)
        {
            assertEquals("variant was not lowercased: " + v, v.toLowerCase(), v);
        }
    }

    // ---------- levenshtein (package-private, but the SUT doc says the numbers are real) ----------

    @Test
    public void levenshteinEqualStringsAreZero()
    {
        assertEquals(0, MetadataTypeCatalog.levenshtein("abc", "abc"));
    }

    @Test
    public void levenshteinNullIsMaxValue()
    {
        assertEquals(Integer.MAX_VALUE, MetadataTypeCatalog.levenshtein(null, "x"));
        assertEquals(Integer.MAX_VALUE, MetadataTypeCatalog.levenshtein("x", null));
    }

    @Test
    public void levenshteinEmptyVersusNonEmpty()
    {
        assertEquals(3, MetadataTypeCatalog.levenshtein("", "abc"));
        assertEquals(3, MetadataTypeCatalog.levenshtein("abc", ""));
    }

    @Test
    public void levenshteinOneSubstitution()
    {
        assertEquals(1, MetadataTypeCatalog.levenshtein("cat", "cot"));
    }
}
