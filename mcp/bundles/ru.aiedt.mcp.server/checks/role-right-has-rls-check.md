# role-right-has-rls-check

**Category:** Role Rights  ·  **Severity:** Minor (Code smell)

Flags rights granted on an object without a row-level security (RLS) restriction attached, in cases where record-level access separation looks like it should apply.

## Why it matters
A right without RLS grants access to every record of that object, not just the ones relevant to the user's organization, department, or similar scope. When the data model implies that kind of separation, skipping RLS quietly turns a scoped right into a blanket one.

## How to fix
Decide whether the object needs record-level separation, pick the dimension it should be scoped by (organization, department, etc.), and add an RLS restriction expression to the right, pulling the allowed values from session parameters.

## Example
```xml
<!-- Read right on a document with no record-level restriction -->
<Rights>
  <Right>
    <Name>Read</Name>
    <Value>true</Value>
    <Object>Document.Invoice</Object>
  </Right>
</Rights>
```
Add a restriction that scopes visible records to the session's allowed organizations:
```xml
<Rights>
  <Right>
    <Name>Read</Name>
    <Value>true</Value>
    <Object>Document.Invoice</Object>
    <Restriction>
      Organization IN (&AvailableOrganizations)
    </Restriction>
  </Right>
</Rights>
```
