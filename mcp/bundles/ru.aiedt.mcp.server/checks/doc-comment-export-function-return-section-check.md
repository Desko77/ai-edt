# doc-comment-export-function-return-section-check

**Category:** Documentation comments  ·  **Severity:** MAJOR

Flags an exported function whose doc comment has no `Returns:` section, or one that's present but empty - unless the comment instead points to another function's documentation with `See`.

## Why it matters
Standard 453 treats the return value as required documentation on any public function; without it, whoever wants to call the function has to go read its body just to find out what comes back.

## How to fix
Add a `// Returns:` section right after `Parameters:`, naming the type and a short description. For a composite return type, either describe its fields inline or link to the function that actually constructs the value via `// See <FunctionName>`.

## Example

```bsl
// Returns:
//  Array of CatalogRef.Products - matching products
Function GetProducts(Filter) Export
    // ...
EndFunction
```
