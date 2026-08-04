# statement-type-change-check

**Category:** Strict Types  ·  **Severity:** Major (Code style)

In modules marked `@strict-types`, flags a variable being reassigned to a value of a different type than the one it first held - including reassigning a typed variable to `Undefined`.

## Why it matters
Strict typing is a contract: tooling built on top of it assumes a variable's type stays stable once assigned. Letting the same variable drift from `Number` to `String`, or from a typed value to `Undefined`, breaks that assumption and defeats the point of annotating the module as strictly typed in the first place.

## How to fix
Use a separate, appropriately named variable for a value of a different type instead of reusing one. If a function can legitimately return `Undefined` alongside another type, express that as a union in the return type annotation (`Structure, Undefined`) rather than reassigning a typed variable to `Undefined` mid-function.

## Example
```bsl
// @strict-types
Result = 0;
Result = "Done"; // type changed from Number to String
```
Use a distinct variable instead:
```bsl
// @strict-types
Result = 0;
Status = "Done";
```
