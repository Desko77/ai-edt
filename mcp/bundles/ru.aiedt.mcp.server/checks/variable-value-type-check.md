# variable-value-type-check

**Category:** Strict Types  ·  **Severity:** Major (Code style)

In `@strict-types` modules, flags a `Var` declaration that never ends up with an inferable type - no immediate initialization and no explicit `// @type: TypeName` annotation.

## Why it matters
Strict-type checking can only reason about what it can infer. A bare `Var Result;` with no assignment and no annotation gives the checker nothing to go on, so every later use of `Result` effectively falls outside the strict-typing guarantee the module claims to have.

## How to fix
Initialize the variable with a typed value as close to the declaration as practical, or, when immediate initialization is not possible, add a `// @type: TypeName` comment directly above the `Var` declaration.

## Example
```bsl
// @strict-types
Var Counter;  // no type - not initialized, no annotation
If Condition Then
    Counter = 0;
EndIf;
```
Either initialize directly or annotate the type:
```bsl
// @strict-types
Counter = 0;  // type inferred from the value
If Condition Then
    Counter = Counter + 1;
EndIf;
```
