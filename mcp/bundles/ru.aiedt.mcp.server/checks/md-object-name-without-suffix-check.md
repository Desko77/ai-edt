# md-object-name-without-suffix-check

**Category:** Metadata Naming  ·  **Severity:** Minor

Flags metadata object names - typically common modules - that lack a suffix identifying their purpose or compilation context, such as `Server`, `Client`, `ClientServer` or `Cached`.

## Why it matters
A common module's name is the only clue to where its code runs. `WorkWithDocuments` tells a reader nothing about whether it compiles for the server, the client, or both, which makes call sites harder to audit and increases the chance of calling code from the wrong context.

## How to fix
Rename the module to end with a suffix that matches its compilation settings (`Server`, `ServerCall`, `Client`, `ClientServer`, `Cached`, `ReUse`, etc.) and update all references. Object types whose kind is already unambiguous from context (catalogs, documents) generally don't need this.

## Example

```bsl
// Bad: compilation context unclear from the name
CommonModule: CatalogOperations

// Good: name states it is server-only
CommonModule: CatalogOperationsServer
```
