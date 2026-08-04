# form-item-visible-settings-by-roles-check

**Category:** Forms - access control  ·  **Severity:** Minor (Code smell)

Flags form items whose Visible, Edit, or Use property is configured per-role directly inside the form.

## Why it matters

Hard-coding role names into a form ties the UI to a specific role structure. Any time a role is renamed or restructured, every form that references it needs an edit, and the resulting access logic is scattered and hard to test.

## How to fix

Clear the per-role settings in the form designer, and control visibility or editability from code instead - driven by `AccessRight()`, a functional option, or the access-management subsystem.

## Example

```bsl
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    Items.Amount.ReadOnly = Not AccessRight("Edit", Metadata.Documents.Order);
    Items.SecretField.Visible = AccessManagement.HasRight("ViewSecretData");
EndProcedure
```
