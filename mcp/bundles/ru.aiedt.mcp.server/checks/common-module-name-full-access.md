# common-module-name-full-access

**Category:** Naming conventions (common modules)  ·  **Severity:** CRITICAL

Flags a common module with `Privileged = True` whose name doesn't end in `FullAccess` (or `ПолныеПрава`).

## Why it matters
Privileged mode bypasses every access-rights check in the database - code in such a module runs with full permissions regardless of the current user's role. Standard 469 requires the suffix precisely so this security-sensitive property is obvious from the module name alone, both to reviewers and to anyone auditing where privileged code lives.

## How to fix
Rename the module to end with `FullAccess` (or `ПолныеПрава`) and update all references. While renaming, it's worth double-checking that privileged mode is actually required for that logic rather than something that could run under normal user rights.

## Example

```bsl
// Before
AdminFunctions.DeleteExpiredSessions();

// After (module renamed to AdminFunctionsFullAccess)
AdminFunctionsFullAccess.DeleteExpiredSessions();
```
