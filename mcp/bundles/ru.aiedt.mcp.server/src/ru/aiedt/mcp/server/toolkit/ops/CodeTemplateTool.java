/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * 1.40.x: Code-template generator for typical 1C boilerplate scenarios.
 * <p>
 * The AI agent picks a {@code template} name and supplies parameters
 * (function name, message text, etc.). The tool returns a ready-to-write
 * BSL snippet that follows 1C conventions: pragmas, server/client
 * separation, БСП-friendly patterns, comments in Russian.
 * <p>
 * Templates are fully parameterised - no scaffolding leaks. Output is BSL
 * source ready to drop into a CommonModule, ObjectModule, or Form module.
 */
public class CodeTemplateTool implements IMcpTool
{
    public static final String NAME = "code_template"; //$NON-NLS-1$

    private static final List<String> AVAILABLE_TEMPLATES = Arrays.asList(
        "http_service_handler",
        "scheduled_job",
        "subscription_before_write",
        "subscription_on_write",
        "common_module_api",
        "form_at_server_action",
        "object_event_handlers",
        "print_form_handler",
        "background_long_action",
        "external_data_processor_command",
        "yaxunit_test_skeleton",
        "list");

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Returns a ready-to-paste BSL boilerplate snippet for typical 1C scenarios " //$NON-NLS-1$
            + "(HTTP service, scheduled job, event subscription, form server action, etc.). " //$NON-NLS-1$
            + "Pass template=list to see available templates. " //$NON-NLS-1$
            + "Output has proper pragmas, Russian header comments, and БСП-friendly patterns."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("template", "Template name. Pass 'list' to see all.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("name", "Method/object name (snake_case or PascalCase)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("description", "Method description (Russian, used in header comment)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("subsystem", "Optional subsystem name (for header)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String template = JsonUtils.extractStringArgument(params, "template");
        if (template == null || template.isEmpty())
        {
            return ToolResult.error("template parameter is required. Pass 'list' to see options.")
                .put("availableTemplates", AVAILABLE_TEMPLATES)
                .toJson();
        }
        template = template.trim().toLowerCase();
        if ("list".equals(template))
        {
            return ToolResult.success()
                .put("operation", NAME)
                .put("availableTemplates", AVAILABLE_TEMPLATES)
                .put("hint", "Pass template=<name> with optional name/description/subsystem to render.")
                .toJson();
        }

        String name = orDefault(JsonUtils.extractStringArgument(params, "name"), "ИмяМетода");
        String description = orDefault(JsonUtils.extractStringArgument(params, "description"),
            "Краткое описание метода");
        String subsystem = JsonUtils.extractStringArgument(params, "subsystem");

        String body = renderTemplate(template, name, description, subsystem);
        if (body == null)
        {
            return ToolResult.error("Unknown template: '" + template + "'")
                .put("availableTemplates", AVAILABLE_TEMPLATES)
                .toJson();
        }
        return ToolResult.success()
            .put("operation", NAME)
            .put("template", template)
            .put("name", name)
            .put("body", body)
            .put("hint", "Drop the body into the appropriate module. Adjust parameters and "
                + "implementation per project conventions.")
            .toJson();
    }

    private String renderTemplate(String template, String name, String description, String subsystem)
    {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("{{NAME}}", name);
        placeholders.put("{{DESCRIPTION}}", description);
        placeholders.put("{{SUBSYSTEM}}", subsystem == null ? "ОбщегоНазначения" : subsystem);

        String raw;
        switch (template)
        {
            case "http_service_handler":
                raw = HTTP_SERVICE_HANDLER;
                break;
            case "scheduled_job":
                raw = SCHEDULED_JOB;
                break;
            case "subscription_before_write":
                raw = SUBSCRIPTION_BEFORE_WRITE;
                break;
            case "subscription_on_write":
                raw = SUBSCRIPTION_ON_WRITE;
                break;
            case "common_module_api":
                raw = COMMON_MODULE_API;
                break;
            case "form_at_server_action":
                raw = FORM_AT_SERVER_ACTION;
                break;
            case "object_event_handlers":
                raw = OBJECT_EVENT_HANDLERS;
                break;
            case "print_form_handler":
                raw = PRINT_FORM_HANDLER;
                break;
            case "background_long_action":
                raw = BACKGROUND_LONG_ACTION;
                break;
            case "external_data_processor_command":
                raw = EXTERNAL_DATA_PROCESSOR_COMMAND;
                break;
            case "yaxunit_test_skeleton":
                raw = YAXUNIT_TEST_SKELETON;
                break;
            default:
                return null;
        }
        for (Map.Entry<String, String> e : placeholders.entrySet())
        {
            raw = raw.replace(e.getKey(), e.getValue());
        }
        return raw;
    }

    private static String orDefault(String s, String fallback)
    {
        return s == null || s.isEmpty() ? fallback : s;
    }

    // -- TEMPLATES --

    private static final String HTTP_SERVICE_HANDLER =
        "// {{DESCRIPTION}}\n"
            + "// Подсистема: {{SUBSYSTEM}}\n"
            + "//\n"
            + "// Параметры:\n"
            + "//  Запрос - HTTPСервисЗапрос - входящий запрос\n"
            + "//\n"
            + "// Возвращаемое значение:\n"
            + "//  HTTPСервисОтвет - ответ сервиса\n"
            + "Функция {{NAME}}(Запрос) Экспорт\n\n"
            + "    Ответ = Новый HTTPСервисОтвет(200);\n"
            + "    Ответ.Заголовки.Вставить(\"Content-Type\", \"application/json; charset=utf-8\");\n\n"
            + "    Попытка\n"
            + "        ТелоЗапроса = Запрос.ПолучитьТелоКакСтроку();\n"
            + "        ДанныеЗапроса = ОбщегоНазначения.JSONВСтруктуру(ТелоЗапроса);\n\n"
            + "        // TODO: бизнес-логика обработки запроса\n"
            + "        Результат = Новый Структура;\n"
            + "        Результат.Вставить(\"status\", \"ok\");\n\n"
            + "        Ответ.УстановитьТелоИзСтроки(ОбщегоНазначения.СтруктуруВJSON(Результат));\n"
            + "    Исключение\n"
            + "        Ответ.КодСостояния = 500;\n"
            + "        Ответ.УстановитьТелоИзСтроки(\n"
            + "            ОбщегоНазначения.СтруктуруВJSON(Новый Структура(\"error\", ОписаниеОшибки())));\n"
            + "        ЗаписьЖурналаРегистрации(\"{{NAME}}\", УровеньЖурналаРегистрации.Ошибка,,, ОписаниеОшибки());\n"
            + "    КонецПопытки;\n\n"
            + "    Возврат Ответ;\n"
            + "КонецФункции\n";

    private static final String SCHEDULED_JOB =
        "// {{DESCRIPTION}}\n"
            + "// Регламентное задание для подсистемы {{SUBSYSTEM}}.\n"
            + "// Привязка: добавить в Метаданные → Регламентные задания → ИмяРегламентного → ИмяМетода.\n"
            + "Процедура {{NAME}}() Экспорт\n\n"
            + "    УстановитьПривилегированныйРежим(Истина);\n"
            + "    Попытка\n"
            + "        ЗаписьЖурналаРегистрации(\"{{NAME}}\", УровеньЖурналаРегистрации.Информация,,,\n"
            + "            \"Запуск регламентного задания\");\n\n"
            + "        // TODO: бизнес-логика\n\n"
            + "        ЗаписьЖурналаРегистрации(\"{{NAME}}\", УровеньЖурналаРегистрации.Информация,,,\n"
            + "            \"Завершено успешно\");\n"
            + "    Исключение\n"
            + "        ЗаписьЖурналаРегистрации(\"{{NAME}}\", УровеньЖурналаРегистрации.Ошибка,,,\n"
            + "            ПодробноеПредставлениеОшибки(ИнформацияОбОшибке()));\n"
            + "        ВызватьИсключение;\n"
            + "    КонецПопытки;\n\n"
            + "КонецПроцедуры\n";

    private static final String SUBSCRIPTION_BEFORE_WRITE =
        "// {{DESCRIPTION}}\n"
            + "// Обработчик подписки на ПередЗаписью объекта.\n"
            + "// Привязка: Метаданные → Подписки на события → ИмяПодписки → ОбщийМодуль.{{NAME}}\n"
            + "Процедура {{NAME}}(Источник, Отказ) Экспорт\n\n"
            + "    Если Источник.ОбменДанными.Загрузка Тогда\n"
            + "        Возврат;\n"
            + "    КонецЕсли;\n\n"
            + "    // TODO: проверки и модификации перед записью\n\n"
            + "КонецПроцедуры\n";

    private static final String SUBSCRIPTION_ON_WRITE =
        "// {{DESCRIPTION}}\n"
            + "// Обработчик подписки на ПриЗаписи объекта.\n"
            + "// Привязка: Метаданные → Подписки на события → ИмяПодписки → ОбщийМодуль.{{NAME}}\n"
            + "Процедура {{NAME}}(Источник, Отказ) Экспорт\n\n"
            + "    Если Источник.ОбменДанными.Загрузка Тогда\n"
            + "        Возврат;\n"
            + "    КонецЕсли;\n\n"
            + "    // TODO: побочные эффекты после успешной записи\n"
            + "    // Внимание: транзакция активна, изменения откатятся при Отказ=Истина\n\n"
            + "КонецПроцедуры\n";

    private static final String COMMON_MODULE_API =
        "////////////////////////////////////////////////////////////////////////////////\n"
            + "// {{DESCRIPTION}}\n"
            + "// Модуль подсистемы: {{SUBSYSTEM}}\n"
            + "////////////////////////////////////////////////////////////////////////////////\n\n"
            + "#Область ПрограммныйИнтерфейс\n\n"
            + "// {{DESCRIPTION}}\n"
            + "//\n"
            + "// Параметры:\n"
            + "//  Параметр - Тип - описание\n"
            + "//\n"
            + "// Возвращаемое значение:\n"
            + "//  Тип - описание возвращаемого значения\n"
            + "Функция {{NAME}}(Параметр) Экспорт\n\n"
            + "    // TODO: реализация\n"
            + "    Возврат Неопределено;\n\n"
            + "КонецФункции\n\n"
            + "#КонецОбласти\n\n"
            + "#Область СлужебныеПроцедурыИФункции\n\n"
            + "// Внутренние утилиты модуля.\n\n"
            + "#КонецОбласти\n";

    private static final String FORM_AT_SERVER_ACTION =
        "// {{DESCRIPTION}}\n"
            + "// Серверный обработчик команды формы.\n"
            + "&НаСервере\n"
            + "Процедура {{NAME}}НаСервере()\n\n"
            + "    // TODO: серверная логика\n"
            + "    // Доступ к Объект и реквизитам формы возможен напрямую\n\n"
            + "КонецПроцедуры\n\n"
            + "&НаКлиенте\n"
            + "Процедура {{NAME}}(Команда)\n\n"
            + "    {{NAME}}НаСервере();\n\n"
            + "    // TODO: клиентская реакция (сообщение, открытие формы, и т.п.)\n\n"
            + "КонецПроцедуры\n";

    private static final String OBJECT_EVENT_HANDLERS =
        "////////////////////////////////////////////////////////////////////////////////\n"
            + "// {{DESCRIPTION}}\n"
            + "// Обработчики событий модуля объекта.\n"
            + "////////////////////////////////////////////////////////////////////////////////\n\n"
            + "Процедура ПередЗаписью(Отказ)\n\n"
            + "    Если ОбменДанными.Загрузка Тогда\n"
            + "        Возврат;\n"
            + "    КонецЕсли;\n\n"
            + "    // TODO: проверки и автозаполнение реквизитов\n\n"
            + "КонецПроцедуры\n\n"
            + "Процедура ПриЗаписи(Отказ)\n\n"
            + "    Если ОбменДанными.Загрузка Тогда\n"
            + "        Возврат;\n"
            + "    КонецЕсли;\n\n"
            + "    // TODO: побочные эффекты записи (записать связанные регистры и т.д.)\n\n"
            + "КонецПроцедуры\n\n"
            + "Процедура ПередУдалением(Отказ)\n\n"
            + "    Если ОбменДанными.Загрузка Тогда\n"
            + "        Возврат;\n"
            + "    КонецЕсли;\n\n"
            + "    // TODO: проверка возможности удаления (зависимости)\n\n"
            + "КонецПроцедуры\n\n"
            + "Процедура ОбработкаЗаполнения(ДанныеЗаполнения, СтандартнаяОбработка)\n\n"
            + "    // TODO: автозаполнение реквизитов из ДанныеЗаполнения\n\n"
            + "КонецПроцедуры\n";

    private static final String PRINT_FORM_HANDLER =
        "// {{DESCRIPTION}}\n"
            + "// Печать табличного документа на основании макета.\n"
            + "// Использовать совместно с подсистемой Печать БСП.\n"
            + "Функция {{NAME}}(МассивОбъектов, ОбъектыПечати) Экспорт\n\n"
            + "    ТабличныйДокумент = Новый ТабличныйДокумент;\n"
            + "    ТабличныйДокумент.КлючПараметровПечати = \"{{NAME}}\";\n\n"
            + "    Макет = ПолучитьОбщийМакет(\"{{NAME}}\");\n"
            + "    ОбластьЗаголовка = Макет.ПолучитьОбласть(\"Заголовок\");\n"
            + "    ОбластьСтроки = Макет.ПолучитьОбласть(\"Строка\");\n\n"
            + "    Для Каждого Ссылка Из МассивОбъектов Цикл\n"
            + "        ТабличныйДокумент.Вывести(ОбластьЗаголовка);\n"
            + "        // TODO: заполнить параметры макета по Ссылка\n"
            + "        ОбластьСтроки.Параметры.Заполнить(Ссылка);\n"
            + "        ТабличныйДокумент.Вывести(ОбластьСтроки);\n"
            + "        ТабличныйДокумент.ВывестиГоризонтальныйРазделительСтраниц();\n"
            + "    КонецЦикла;\n\n"
            + "    Возврат ТабличныйДокумент;\n"
            + "КонецФункции\n";

    private static final String BACKGROUND_LONG_ACTION =
        "// {{DESCRIPTION}}\n"
            + "// Запуск длительной операции через подсистему ДлительныеОперации (БСП).\n\n"
            + "&НаСервере\n"
            + "Функция Запустить{{NAME}}НаСервере()\n\n"
            + "    ПараметрыВыполнения = ДлительныеОперации.ПараметрыВыполненияВФоне(УникальныйИдентификатор);\n"
            + "    ПараметрыВыполнения.НаименованиеФоновогоЗадания = \"{{DESCRIPTION}}\";\n\n"
            + "    Параметры = Новый Структура;\n"
            + "    // TODO: заполнить параметры для серверной процедуры\n\n"
            + "    Возврат ДлительныеОперации.ВыполнитьВФоне(\n"
            + "        \"{{SUBSYSTEM}}.{{NAME}}\",\n"
            + "        Параметры,\n"
            + "        ПараметрыВыполнения);\n\n"
            + "КонецФункции\n\n"
            + "&НаКлиенте\n"
            + "Процедура Запустить{{NAME}}(Команда)\n\n"
            + "    Результат = Запустить{{NAME}}НаСервере();\n"
            + "    Оповещение = Новый ОписаниеОповещения(\"После{{NAME}}\", ЭтотОбъект);\n"
            + "    ПараметрыОжидания = ДлительныеОперацииКлиент.ПараметрыОжидания(ЭтотОбъект);\n"
            + "    ДлительныеОперацииКлиент.ОжидатьЗавершение(Результат, Оповещение, ПараметрыОжидания);\n\n"
            + "КонецПроцедуры\n\n"
            + "&НаКлиенте\n"
            + "Процедура После{{NAME}}(Результат, ДополнительныеПараметры) Экспорт\n\n"
            + "    Если Результат = Неопределено Тогда\n"
            + "        Возврат;\n"
            + "    КонецЕсли;\n"
            + "    Если Результат.Статус = \"Ошибка\" Тогда\n"
            + "        ПоказатьПредупреждение(, Результат.КраткоеПредставлениеОшибки);\n"
            + "        Возврат;\n"
            + "    КонецЕсли;\n\n"
            + "    // TODO: обработка успешного результата\n\n"
            + "КонецПроцедуры\n";

    private static final String EXTERNAL_DATA_PROCESSOR_COMMAND =
        "// {{DESCRIPTION}}\n"
            + "// Обработчик команды дополнительной обработки БСП.\n\n"
            + "Процедура ВыполнитьКоманду(ИдентификаторКоманды, ПараметрыВыполнения) Экспорт\n\n"
            + "    Если ИдентификаторКоманды = \"{{NAME}}\" Тогда\n"
            + "        Выполнить{{NAME}}(ПараметрыВыполнения);\n"
            + "    КонецЕсли;\n\n"
            + "КонецПроцедуры\n\n"
            + "Процедура Выполнить{{NAME}}(ПараметрыВыполнения)\n\n"
            + "    // TODO: основная логика команды\n"
            + "    // ПараметрыВыполнения.ОбъектыНазначения - ссылки выбранных объектов\n\n"
            + "КонецПроцедуры\n";

    private static final String YAXUNIT_TEST_SKELETON =
        "////////////////////////////////////////////////////////////////////////////////\n"
            + "// {{DESCRIPTION}}\n"
            + "// YAxUnit-тесты для подсистемы {{SUBSYSTEM}}.\n"
            + "////////////////////////////////////////////////////////////////////////////////\n\n"
            + "Процедура ИсполняемыеСценарии(СписокТестов) Экспорт\n"
            + "    СписокТестов.Добавить(\"Тест_{{NAME}}_БазовыйСценарий\");\n"
            + "    СписокТестов.Добавить(\"Тест_{{NAME}}_ГраничныйСлучай\");\n"
            + "КонецПроцедуры\n\n"
            + "Процедура ПередКаждымТестом() Экспорт\n"
            + "    // Подготовка состояния перед каждым тестом\n"
            + "КонецПроцедуры\n\n"
            + "Процедура Тест_{{NAME}}_БазовыйСценарий() Экспорт\n\n"
            + "    // Arrange\n"
            + "    ВходныеДанные = \"тест\";\n\n"
            + "    // Act\n"
            + "    Результат = {{SUBSYSTEM}}.{{NAME}}(ВходныеДанные);\n\n"
            + "    // Assert\n"
            + "    ЮТест.ОжидаетЧто(Результат).Заполнено().НеНеопределено();\n\n"
            + "КонецПроцедуры\n\n"
            + "Процедура Тест_{{NAME}}_ГраничныйСлучай() Экспорт\n\n"
            + "    Действие = Новый ВызовФункции(\"{{SUBSYSTEM}}.{{NAME}}\", Новый Массив);\n"
            + "    ЮТест.ОжидаетЧто(Действие).ВыбрасываетИсключение(\"параметр\");\n\n"
            + "КонецПроцедуры\n";
}
