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
            + "## Where the test module lives\n\n"
            + "YAxUnit discovers only common modules that belong to a configuration extension -\n"
            + "the YAxUnit engine extension or your own test extension. A module of the main\n"
            + "configuration is never scanned. Create a new common module there (not a borrowed\n"
            + "one) with flags:\n"
            + "- `Server`, `Client (managed application)`, `Client (ordinary application)` - check\n"
            + "  the ones the tests will run in;\n"
            + "- `Global` and `Server call` must stay off - the engine skips such modules.\n\n"
            + "## Minimal template (Russian)\n\n"
            + "```bsl\n"
            + "// Register the exported tests of a new extension common module.\n"
            + "Процедура ИсполняемыеСценарии() Экспорт\n"
            + "    ЮТТесты.ДобавитьТест(\"Тест_СложениеДваПлюсДва\");\n"
            + "КонецПроцедуры\n\n"
            + "// Add two values and verify their sum.\n"
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
            + "## Registration rules (YAxUnit 25.12)\n"
            + "- `ИсполняемыеСценарии` takes no parameters and must be `Экспорт`. With a legacy\n"
            + "  required parameter, the engine warns and retries with `Неопределено`;\n"
            + "  using that argument as a test list fails registration.\n"
            + "- Every test is registered by name: `ЮТТесты.ДобавитьТест(\"ИмяМетода\")`.\n"
            + "- Test procedures must be declared with `Экспорт`.\n"
            + "- The `Тест_` prefix is optional: a test runs because registration names it, nothing\n"
            + "  is discovered by name prefix.\n"
            + "- An exception raised inside a test marks it failed; assertions are the usual way to\n"
            + "  check (`ЮТест.ОжидаетЧто(Значение).Равно(Ожидаемое)`).\n"
            + "- Method directives like `&НаСервере` are not used in common modules: where a test\n"
            + "  runs is decided by the module flags above.\n";
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
            + "YAxUnit is already installed, nothing is downloaded. On both paths the call reads\n"
            + "safe mode and unsafe action protection through the designer session, lowers both\n"
            + "in one write when either is on, and confirms them before tests start. A value that\n"
            + "stays on refuses the run and is reported. Pass `yaxunitUnsafeMode=false` to leave\n"
            + "both flags untouched (neither read nor written) and manage them in Designer.\n"
            + "The install and flags step is refused by write-blocking presets before it touches\n"
            + "the infobase. Needs network access to\n"
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
            + "- **0 tests executed** - the answer ends with the runner's \"No tests were executed\"\n"
            + "  block; its checks: the YAxUnit extension is Active in the infobase (Configuration >\n"
            + "  Extensions), the extensions / modules / tests filter names existing tests, and a\n"
            + "  just-edited module compiles (get_project_errors) on a rebuilt project\n"
            + "  (clean_project). Engine-side causes the block cannot name: the test module is\n"
            + "  not in an extension, its `Global` or `Server call` flag is on (the engine skips\n"
            + "  such modules), or registration is empty - `ИсполняемыеСценарии` must take no\n"
            + "  parameters and register each test through `ЮТТесты.ДобавитьТест`. A run that ends\n"
            + "  without any report refuses with NoReport instead.\n"
            + "- **The run stops at a modal (\"Update configuration?\")** - the call answers\n"
            + "  `**Pending**` with a `runKey`; the pending launch or running client is retained.\n"
            + "  Poll the `runKey` to read its result. An EDT dialog is listed in the answer - use\n"
            + "  `answer_dialog`, then poll. A modal of the 1C:Enterprise client itself is not\n"
            + "  visible to the tool: answer it in the client window, then poll the same `runKey`.\n"
            + "  The client's update dialog appears when the pre-launch infobase update did not\n"
            + "  run: the call passed `updateBeforeLaunch=false`, or a write-blocking preset\n"
            + "  disabled `update_database` (answers of that run carry\n"
            + "  `databaseUpdate=SKIPPED_BY_PRESET`). With the default `updateBeforeLaunch=true`\n"
            + "  the update runs before the client starts, and an update that does not finish\n"
            + "  refuses the launch.\n"
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
            + "The tool filters a run by extension, module and test name only - the three the\n"
            + "YAXUnit launch configuration carries. A call naming any other filter is refused\n"
            + "rather than answered with a full run.\n";
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
            + "YAXUnit reads the annotations; `yaxunit_tests` has no argument that filters by them -\n"
            + "narrow the run with `modules=` / `tests=` instead.\n\n"
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
