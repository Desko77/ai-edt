# common-module-name-server-call

**Category:** Naming conventions (common modules)  ·  **Severity:** CRITICAL

Flags a common module with `ServerCall = True` (callable directly from client code, crossing the client-server boundary) whose name doesn't end in `ServerCall` (or `ВызовСервера`).

## Why it matters
Every call into a `ServerCall` module means a network round trip - parameter and result serialization, latency, an authentication check. Standard 469 uses the suffix so that cost is visible from the call site itself, which makes it easy to spot code that fires the same server call in a loop instead of batching it into one request.

## How to fix
Rename the module to end with `ServerCall` (or `ВызовСервера`) and update all call sites. While touching the call sites, check whether any of them call the server repeatedly in a loop where a single batched call would do.

## Example

```bsl
// Before
Data = DataService.GetDocumentData(Ref);

// After (module renamed to DataServiceServerCall)
Data = DataServiceServerCall.GetDocumentData(Ref);
```
