# variable-name-invalid-check

**Category:** BSL  ·  **Severity:** Minor (Code smell)

Flags variable names that break the naming convention: starting with a lowercase letter, starting with an underscore, or falling below the configured minimum length (2 characters by default).

## Why it matters
Consistent capitalization sets variables apart from keywords and reads uniformly across the codebase; names that are too short (`x`, `i`, `a`) or underscore-prefixed usually signal leftover debug code or habits carried over from another language's convention, and they are harder to search for meaningfully.

## How to fix
Rename to start with an uppercase letter, drop any leading underscore, and use at least a short descriptive name instead of a single letter. Use the IDE's rename refactoring so every usage updates together.

## Example
```bsl
_Result = 0;   // starts with underscore
x = GetX();    // too short, lowercase
```
Renamed to follow the convention:
```bsl
Result = 0;
XCoordinate = GetX();
```
