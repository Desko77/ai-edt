# export-procedure-missing-comment

**Category:** Documentation comments  ·  **Severity:** Minor (Code style, Standard 453)

Flags an Export procedure or function that has no documentation comment directly above it.

## Why it matters

Exported methods form a module's public API. Without a comment describing what a method does, what it expects, and what it returns, every caller has to read the implementation to find out.

## How to fix

Add a comment immediately above the method (no blank line in between) with a short description, a Parameters entry per argument, and a Returns section for functions.

## Example

```bsl
// Gets document data by reference.
//
// Parameters:
//  DocumentRef - DocumentRef.Invoice - reference to the document
//
// Returns:
//  DocumentObject.Invoice - document object with all data
//
Function GetDocumentData(DocumentRef) Export
    Return DocumentRef.GetObject();
EndFunction
```
