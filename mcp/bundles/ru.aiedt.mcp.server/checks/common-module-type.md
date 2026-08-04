# common-module-type

**Category:** Common modules  ·  **Severity:** MAJOR

Flags a common module whose execution-context properties (Server, Client, Server call, and related flags) are left at their platform defaults instead of being deliberately configured.

## Why it matters
Standard 469 expects the module type - server-only, client-only, client-server, or server-call - to be a conscious decision, not whatever the designer defaults to. A module left unconfigured is easy to misclassify during review, and its actual behavior can surprise whoever calls into it later.

## How to fix
Decide what the module is for and where it needs to run, then set `Server` / `Client (managed application)` / `ServerCall` explicitly to match, and give the module the matching name suffix from standard 469 (`Client`, `ClientServer`, `ServerCall`, or none for a plain server module).

## Example

```xml
<mdclass:CommonModule xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>DataProcessingServer</name>
  <server>true</server>
  <clientManagedApplication>false</clientManagedApplication>
  <serverCall>false</serverCall>
</mdclass:CommonModule>
```
