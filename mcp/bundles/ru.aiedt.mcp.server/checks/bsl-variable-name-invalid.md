# bsl-variable-name-invalid

**Category:** Naming conventions  ·  **Severity:** MINOR

Flags a variable or method parameter whose name starts with a lowercase letter, starts with an underscore, or is shorter than the configured minimum length (3 characters by default). `For`/`For Each` loop iterators are exempt.

## Why it matters
1C development standard 454 expects variables to start with a capital letter in CamelCase, without a leading underscore, and to be long enough to be self-explanatory. Ignoring this makes code harder to scan and inconsistent with the rest of the project.

## How to fix
Capitalize the first letter, drop any leading underscore, and replace overly short names with something descriptive - then update every place the variable is used. The minimum length is configurable via the `minNameLength` parameter if 3 characters is too strict for your project.

## Example

```bsl
// Before
_InternalVariable = 0;
cnt = 0;

// After
InternalVariable = 0;
ItemCount = 0;
```
