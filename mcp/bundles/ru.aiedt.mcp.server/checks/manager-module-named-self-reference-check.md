# manager-module-named-self-reference-check

**Category:** Module structure  ·  **Severity:** Minor (Code smell)

Flags a manager module that addresses its own object through the fully-qualified form - for example writing `Catalogs.Products.FindByCode(...)` inside `Catalog.Products`'s own manager module.

## Why it matters

The qualification is pure noise: from within its own manager module, a method can be called directly without repeating the object's name.

## How to fix

Drop the `Catalogs.X.`/`Documents.X.` prefix for same-object calls. Keep the full reference only when addressing a genuinely different object, or when passing the manager itself as a value (for example to `WriteLogEvent`).

## Example

```bsl
// Manager module of Catalog.Products

// Wrong
Function GetProductByCode(Code) Export
    Return Catalogs.Products.FindByCode(Code);
EndFunction

// Right
Function GetProductByCode(Code) Export
    Return FindByCode(Code);
EndFunction
```
