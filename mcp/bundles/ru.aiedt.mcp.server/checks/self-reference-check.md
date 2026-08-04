# self-reference-check

**Category:** BSL  ·  **Severity:** Minor (Code smell)

Flags explicit `ThisObject`/`ThisForm` prefixes on methods, properties or attributes that already resolve fine without them - direct member access inside a module implicitly means "this object".

## Why it matters
The prefix adds nothing but noise here: it does not change behavior, only makes every line longer and slightly harder to scan. Code stays clearer when the qualifier is reserved for the cases that actually need it.

## How to fix
Drop `ThisObject`/`ThisForm` for ordinary member access. Keep it only where it is functionally required: passing the object itself as a parameter (`CommonModule.Process(ThisObject)`), disambiguating from a same-named local variable or parameter, or supplying context to a `NotifyDescription` callback.

## Example
```bsl
// Unnecessary qualifiers
Procedure OnOpen(Cancel)
    ThisObject.Items.MainGroup.Visible = True;
    ThisObject.Modified = True;
EndProcedure
```
Direct access reads the same and is shorter:
```bsl
Procedure OnOpen(Cancel)
    Items.MainGroup.Visible = True;
    Modified = True;
EndProcedure
```
`ThisObject` is still correct when passing the object itself:
```bsl
CommonModule.ProcessObject(ThisObject); // required here
```
