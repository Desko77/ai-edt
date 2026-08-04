/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1.40 - Built-in YAxUnit help topics for the unified yaxunit_tests tool.
 * <p>
 * Six topics matching the unified surface (topics, writing, assertions, setup,
 * events, advanced). Content stays inline in this class for fast access; can
 * be moved to bundle resources in a follow-up patch when topics grow.
 */
public final class YaxunitHelp
{
    private static final Map<String, String> TOPICS = buildTopics();

    private YaxunitHelp()
    {
        // utility
    }

    /**
     * Returns the markdown body of a help topic, or null when the topic name
     * is unknown.
     */
    public static String getTopic(String name)
    {
        if (name == null || name.isEmpty())
        {
            return null;
        }
        return TOPICS.get(name.toLowerCase().trim());
    }

    /**
     * Returns the list of available topic names (in order suitable for the
     * 'topics' help index).
     */
    public static java.util.List<String> availableTopics()
    {
        return new java.util.ArrayList<>(TOPICS.keySet());
    }

    private static Map<String, String> buildTopics()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("topics", buildTopicsIndex());
        m.put("writing", buildWriting());
        m.put("assertions", buildAssertions());
        m.put("setup", buildSetup());
        m.put("events", buildEvents());
        m.put("advanced", buildAdvanced());
        return m;
    }

    private static String buildTopicsIndex()
    {
        return "# YAxUnit help — list of topics\n\n"
            + "- `help=writing` — how to write your first test (registration via `ИсполняемыеСценарии`, AAA pattern, minimal template)\n"
            + "- `help=assertions` — assertion catalog (`Равно`, `НеРавно`, `Содержит`, `Заполнено`, `Имеет`, `ВыбрасываетИсключение`)\n"
            + "- `help=setup` — how to attach the YAxUnit extension (`.cfe`) to an infobase\n"
            + "- `help=events` — `ПередКаждымТестом` / `ПослеТестовогоНабора`, test/suite/module contexts\n"
            + "- `help=advanced` — pointers to YAxUnit public docs (mocks, stubs, RPC mode, infobase checks)\n"
            + "\nUse `yaxunit_tests help=<topic>` to load a topic. Other parameters are ignored when help is set.\n";
    }

    private static String buildWriting()
    {
        return "# Writing your first YAxUnit test\n\n"
            + "## Minimal template (Russian)\n\n"
            + "```bsl\n"
            + "// Создай общий модуль ТестыПримеры (Server, Сервер).\n\n"
            + "Процедура ИсполняемыеСценарии(СписокТестов) Экспорт\n"
            + "    СписокТестов.Добавить(\"Тест_СложениеДваПлюсДва\");\n"
            + "КонецПроцедуры\n\n"
            + "// AAA-паттерн (Arrange / Act / Assert):\n"
            + "Процедура Тест_СложениеДваПлюсДва() Экспорт\n"
            + "    // Arrange\n"
            + "    А = 2;\n"
            + "    Б = 2;\n"
            + "    // Act\n"
            + "    Сумма = А + Б;\n"
            + "    // Assert\n"
            + "    ЮТест.ОжидаетЧто(Сумма).Равно(4);\n"
            + "КонецПроцедуры\n"
            + "```\n\n"
            + "## Important\n"
            + "- Procedures must be declared with `Экспорт`.\n"
            + "- A test will not run unless added to `ИсполняемыеСценарии(СписокТестов)`.\n"
            + "- For server-side tests, use `Сервер` directive (`&НаСервере` not needed in module).\n"
            + "- For client-side tests in managed app, use `Клиент` directive in the module configuration.\n"
            + "- Module name doesn't have to start with `Тест_` but methods do (`Тест_*` discovers automatically when filter `tests` is empty).\n";
    }

    private static String buildAssertions()
    {
        return "# YAxUnit assertion catalog\n\n"
            + "All assertions are accessed through `ЮТест.ОжидаетЧто(value).<Method>(args)`.\n\n"
            + "## Equality\n"
            + "- `Равно(Ожидаемое)` / `НеРавно(Неожидаемое)` — exact equality (string, number, date, bool, ref)\n"
            + "- `Идентично(Ожидаемое)` — strict identity (same reference, not just equal)\n\n"
            + "## Membership\n"
            + "- `Содержит(Подстрока)` / `НеСодержит(Подстрока)` — substring or array element\n"
            + "- `ИмеетДлину(N)` — collection or string length\n"
            + "- `Имеет(Свойство)` — object has the named property\n\n"
            + "## State\n"
            + "- `Заполнено()` / `НеЗаполнено()` — `ЗначениеЗаполнено` semantics\n"
            + "- `НеНеопределено()` — value is not Undefined\n"
            + "- `НеНеопределеноИНеNull()` — value is not Undefined and not Null\n\n"
            + "## Exceptions\n"
            + "- `ВыбрасываетИсключение()` — call wrapped in lambda raises any exception\n"
            + "- `ВыбрасываетИсключение(Сообщение)` — exception message contains the substring\n\n"
            + "## Numeric / dates\n"
            + "- `Больше(N)` / `Меньше(N)` / `БольшеИлиРавно(N)` / `МеньшеИлиРавно(N)`\n"
            + "- `Раньше(Дата)` / `Позже(Дата)`\n\n"
            + "## Sample (negative path)\n"
            + "```bsl\n"
            + "Процедура Тест_ДеленияНаНольПадаетСИсключением() Экспорт\n"
            + "    Действие = Новый ВызовФункции(\"Делить\", Параметры);\n"
            + "    ЮТест.ОжидаетЧто(Действие).ВыбрасываетИсключение(\"Деление на 0\");\n"
            + "КонецПроцедуры\n"
            + "```\n";
    }

    private static String buildSetup()
    {
        return "# Attaching YAxUnit to an infobase\n\n"
            + "## One call (recommended)\n"
            + "```\n"
            + "yaxunit_tests mode=run projectName=MyConfig installYaxunit=true\n"
            + "```\n"
            + "When the YAxUnit engine is not yet in the infobase, this downloads the latest\n"
            + "release from GitHub (bia-technologies/yaxunit), installs it as the `YAxUnit`\n"
            + "extension, updates the database, then runs the tests - all in one call. When\n"
            + "YAxUnit is already installed, nothing is downloaded. Needs network access to\n"
            + "GitHub plus a resolvable thick-client runtime and stored infobase credentials.\n"
            + "Override the source repo with `yaxunitRepo=owner/repo` if you mirror it.\n\n"
            + "To install the engine without running tests, use install_extension:\n"
            + "```\n"
            + "install_extension projectName=MyConfig extensionName=YAxUnit \\\n"
            + "  inputPath=github:bia-technologies/yaxunit#YAxUnit\n"
            + "```\n"
            + "The `#YAxUnit` prefix picks the engine `.cfe` (a release also ships a `Smoke`\n"
            + "example). Repeated calls update the extension in place.\n\n"
            + "## Manual steps (offline / closed network)\n"
            + "### Designer GUI\n"
            + "1. Download the latest YAxUnit `.cfe` from https://github.com/bia-technologies/yaxunit/releases\n"
            + "2. Open the infobase in Designer\n"
            + "3. Configuration - Configuration extensions - add - Load from file - pick the `.cfe`\n"
            + "4. Set the extension purpose to **Add-on (AddOn)**\n"
            + "5. F7 (Update database configuration). The extension is now active.\n\n"
            + "### EDT\n"
            + "1. Project - New configuration extension - use the `.cfe` source archive\n"
            + "2. Build / deploy the extension via Run Configuration\n"
            + "3. Run yaxunit_tests on the connected app\n\n"
            + "## Troubleshooting\n"
            + "- **`yaxunit_tests` returns 0 tests** - extension may be loaded but inactive.\n"
            + "  Check that YAxUnit is Active in Configuration - Configuration extensions.\n"
            + "- **Run hangs at \"Update configuration?\" modal** - pass `updateBeforeLaunch=true` (default).\n"
            + "- **installYaxunit reports a download/resolve failure** - GitHub may be\n"
            + "  unreachable or rate-limited (anonymous calls: ~60/hour per IP). Use the\n"
            + "  manual steps above, or mirror the repo and pass `yaxunitRepo`.\n"
            + "- **Different platform than configured** - open Run Configuration, check the\n"
            + "  platform matches the project's compatibility mode.\n";
    }

    private static String buildEvents()
    {
        return "# Test lifecycle hooks\n\n"
            + "Define these procedures in your test module:\n\n"
            + "```bsl\n"
            + "// Runs once before the entire suite\n"
            + "Процедура ПередТестовымНабором() Экспорт\n"
            + "    // Arrange shared state for all tests in this module\n"
            + "КонецПроцедуры\n\n"
            + "// Runs before EVERY test in this module\n"
            + "Процедура ПередКаждымТестом() Экспорт\n"
            + "    // Reset per-test state\n"
            + "КонецПроцедуры\n\n"
            + "// Runs after every test\n"
            + "Процедура ПослеКаждогоТеста() Экспорт\n"
            + "    // Cleanup\n"
            + "КонецПроцедуры\n\n"
            + "// Runs once after the entire suite\n"
            + "Процедура ПослеТестовогоНабора() Экспорт\n"
            + "КонецПроцедуры\n"
            + "```\n\n"
            + "## Test contexts\n"
            + "- `Server` — runs on server (default for `&НаСервере` modules)\n"
            + "- `Client` — runs on managed-application client\n"
            + "- `ExternalConnection` — runs via COM/COM+\n\n"
            + "Filter through tool params:\n"
            + "- `contexts=Server,Client` — run only Server and Client tests\n"
            + "- `tags=smoke,integration` — run only tagged tests\n"
            + "- `suites=ТестыПримеры,ТестыКадры` — restrict to named suites\n";
    }

    private static String buildAdvanced()
    {
        return "# Advanced YAxUnit topics\n\n"
            + "Topics not covered by built-in help — see public YAxUnit documentation:\n\n"
            + "## Mocks and stubs\n"
            + "- `ЮТест.МокДанныеИБ()` — simulate database query results\n"
            + "- `ЮТест.МокФункцию(\"Имя\")` — replace a function during test\n"
            + "- See https://github.com/bia-technologies/yaxunit/blob/main/docs/MOCK.md\n\n"
            + "## Tags\n"
            + "Tag a test with `// @smoke` / `// @integration` etc. before procedure declaration.\n"
            + "Filter via `yaxunit_tests tags=smoke,fast`.\n\n"
            + "## Suites\n"
            + "Group tests into suites by module name or via `// @suite=Кадры` annotation.\n\n"
            + "## RPC mode\n"
            + "When tests run on a remote server with no UI, configure the extension's RPC mode.\n"
            + "See https://github.com/bia-technologies/yaxunit#rpc-mode\n\n"
            + "## Infobase checks\n"
            + "YAxUnit can verify infobase consistency: catalog entries, register movements, document posting.\n"
            + "Use `ЮТест.ПроверкиИнформационнойБазы()` family of methods.\n\n"
            + "## Resources\n"
            + "- Project: https://github.com/bia-technologies/yaxunit\n"
            + "- Releases: https://github.com/bia-technologies/yaxunit/releases\n"
            + "- Documentation: https://github.com/bia-technologies/yaxunit/tree/main/docs\n"
            + "- License: Apache 2.0\n";
    }
}
