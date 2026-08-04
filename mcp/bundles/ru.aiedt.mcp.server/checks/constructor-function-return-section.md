# constructor-function-return-section

**Category:** Documentation comments  ·  **Severity:** MINOR

Flags a constructor-style function (one that builds and returns an object such as a Structure, Map, or ValueTable - typically named `New...`, `Create...`, or `...Constructor`) whose doc comment has no `Returns:` section.

## Why it matters
Standard 453 expects the return value of a public function to be documented, and it matters most for constructors: callers need to know the shape of what comes back (which keys a Structure has, what a table's columns are) without opening the function body to find out.

## How to fix
Add a `// Returns:` block after the `Parameters:` section, naming the return type and, for composite types like Structure or ValueTable, listing each property/column with its own type and description.

## Example

```bsl
// Creates a new person structure.
//
// Parameters:
//  Name - String - Person's name
//
// Returns:
//  Structure - Person data:
//   * Name - String - Person's name
//   * CreatedAt - Date - Creation timestamp
//
Function CreatePerson(Name) Export
    Person = New Structure;
    Person.Insert("Name", Name);
    Person.Insert("CreatedAt", CurrentSessionDate());
    Return Person;
EndFunction
```
