# Подключение AI-клиентов

AI-EDT слушает `http://localhost:12250/mcp` (Streamable HTTP + SSE). Ниже - готовые конфиги для популярных клиентов. Порт по умолчанию 12250; если поменяли его в **Window → Preferences → AI-EDT**, подставьте свой.

Каждый запрос несет bearer-токен: он создается при первом запуске сервера и показан в **Window → Preferences → AI-EDT**, поле **Bearer token**. Без него сервер отвечает `401`. В примерах ниже вместо `<токен>` подставьте значение с этой страницы; в файл, который попадает в репозиторий, токен не записывайте - у VS Code для этого есть `inputs`, у Cursor `${env:AI_EDT_TOKEN}`.

> В Cursor и других клиентах без поддержки MCP-resources включите **Plain text mode** в **Window → Preferences → AI-EDT → General** - результаты возвращаются простым текстом вместо embedded-resources.

## Claude Code

Секция `mcpServers` в `~/.claude.json` (Windows: `%USERPROFILE%\.claude.json`):

```json
{
  "mcpServers": {
    "AI-EDT": {
      "type": "http",
      "url": "http://localhost:12250/mcp",
      "headers": {"Authorization": "Bearer <токен>"}
    }
  }
}
```

## Cursor

`.cursor/mcp.json` в корне проекта:

```json
{
  "mcpServers": {
    "AI-EDT": {
      "url": "http://localhost:12250/mcp",
      "headers": {"Authorization": "Bearer ${env:AI_EDT_TOKEN}"}
    }
  }
}
```

`AI_EDT_TOKEN` - переменная окружения с токеном; Cursor подставляет ее в `${env:...}`. Можно вписать значение и напрямую, если файл не уходит в репозиторий.

Новый сервер Cursor не загружает, пока он не одобрен: `cursor-agent mcp list` показывает его как `not loaded (needs approval)`, а в редакторе он остаётся пустым. Одобрить - в интерфейсе Cursor либо командой `cursor-agent mcp enable AI-EDT`. После одобрения `cursor-agent mcp list-tools AI-EDT` перечисляет инструменты.

`"type": "sse"` в записи **не указывать**. Замерено 02.09.2026 на Cursor CLI: с одним `url` сервер отвечает `ready` и отдаёт полный список инструментов, а с `"type": "sse"` - `Error: Connection failed`. Сервер отвечает по Streamable HTTP, и значение `sse` переводит клиента на прежний транспорт, которого он здесь не получит.

## VS Code / GitHub Copilot

`.vscode/mcp.json`:

```json
{
  "inputs": [
    {"id": "ai-edt-token", "type": "promptString", "description": "AI-EDT bearer token", "password": true}
  ],
  "servers": {
    "AI-EDT": {
      "type": "http",
      "url": "http://localhost:12250/mcp",
      "headers": {"Authorization": "Bearer ${input:ai-edt-token}"}
    }
  }
}
```

VS Code запрашивает токен при первом подключении и хранит его сам; в файл он не попадает.

## Claude Desktop

`claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "AI-EDT": {
      "url": "http://localhost:12250/mcp",
      "headers": {"Authorization": "Bearer <токен>"}
    }
  }
}
```

## Cline (VS Code extension)

```json
{
  "mcpServers": {
    "AI-EDT": {
      "type": "streamableHttp",
      "url": "http://localhost:12250/mcp",
      "headers": {"Authorization": "Bearer <токен>"}
    }
  }
}
```

## Antigravity

```json
{
  "mcpServers": {
    "AI-EDT": {
      "serverUrl": "http://localhost:12250/mcp",
      "headers": {"Authorization": "Bearer <токен>"}
    }
  }
}
```

## Проверка

После подключения спросите ассистента версию EDT или список проектов - должен прийти осмысленный ответ, а не "инструмент недоступен". Если клиент не видит инструменты - проверьте `/health`:

```bash
curl http://localhost:12250/health
# {"status":"ok","edt_version":"2026.2.0.289","phase":"ready"}
curl -H "Authorization: Bearer <токен>" http://localhost:12250/health
# ... плюс имя рабочей области, текущий инструмент, куча, очередь
```

`phase: ready` означает, что EDT загрузила проект и инструменты работают. `indexing`/`unknown` - подождите окончания индексации. Ответ `401` от `/mcp` при живом `/health` - клиент не передает токен или передает не тот.
