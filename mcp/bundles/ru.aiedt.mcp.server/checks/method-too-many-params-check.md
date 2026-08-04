# method-too-many-params-check

**Category:** BSL Method Design  ·  **Severity:** Minor

Flags procedures and functions with more than 7 parameters total, or more than 4 parameters with default values, per standard #640.

## Why it matters
Long parameter lists are hard to read at the call site, easy to pass in the wrong order, and a common symptom of a method doing too many unrelated things.

## How to fix
Group related parameters into a `Structure` (parameter object), collect optional flags into a single `Options` structure, or split the method by responsibility into several smaller methods.

## Example

```bsl
// Bad: 10 positional parameters
Procedure CreateDocument(DocumentType, Date, Number, Customer, Manager,
    Warehouse, Currency, ExchangeRate, Comment, Priority) Export

// Good: one structured parameter
Procedure CreateDocument(DocumentParameters) Export
    DocumentType = DocumentParameters.DocumentType;
    // ...
EndProcedure
```
