# right-save-user-data-check

**Category:** Role Rights  ·  **Severity:** Minor (Security)

Flags regular roles where `SaveUserData` is turned off - this right lets a user persist their own personal settings such as form layouts, report variants and desktop configuration.

## Why it matters
Unlike most rights on this list, this one is usually safe and expected to be on: it only affects a user's own preferences, not shared or business data. Disabling it for ordinary roles is more often an oversight than a deliberate hardening choice, and it degrades the working experience for no real security gain.

## How to fix
Leave `SaveUserData` enabled for regular roles. Turn it off only for special-purpose accounts where persisting settings makes no sense, such as shared kiosk or terminal logins, and note why those are the exception.

## Example
```xml
<!-- Role: User - disabling this without reason hurts usability -->
<Rights>
  <Right>
    <Name>SaveUserData</Name>
    <Value>false</Value>
  </Right>
</Rights>
```
Keep it on for regular users, reserve `false` for shared/kiosk-style accounts:
```xml
<!-- Role: User -->
<Rights>
  <Right>
    <Name>SaveUserData</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
