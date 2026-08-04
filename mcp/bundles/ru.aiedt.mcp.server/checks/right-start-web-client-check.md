# right-start-web-client-check

**Category:** Role Rights  ·  **Severity:** Minor (Security)

Flags roles where `StartWebClient` is disabled in a way that leaves the role with no working connection method, or that contradicts how the role is meant to be reached (e.g. remote/browser-only users).

## Why it matters
The web client is the entry point for browser-based and remote access. A role that needs remote access but has this right off - with no other client right compensating - simply cannot connect, which is a functional gap disguised as a rights setting.

## How to fix
Enable `StartWebClient` for roles meant to be reached remotely or through a browser, and verify every role has at least one working client right. Record the intended access pattern per role so the setting stays traceable.

## Example
```xml
<!-- Role: User - no client right enabled anywhere, cannot connect at all -->
<Rights>
  <Right><Name>StartThinClient</Name><Value>false</Value></Right>
  <Right><Name>StartThickClient</Name><Value>false</Value></Right>
  <Right><Name>StartWebClient</Name><Value>false</Value></Right>
</Rights>
```
Give a remote/external role a working connection method:
```xml
<!-- Role: ExternalUser -->
<Rights>
  <Right><Name>StartWebClient</Name><Value>true</Value></Right>
</Rights>
```
