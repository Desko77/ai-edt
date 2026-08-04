# doc-comment-parameter-in-description-suggestion-check

**Category:** Documentation comments  ·  **Severity:** MINOR

Flags text in a doc comment's free-text description that looks like it's trying to document a method parameter (a line such as `User - String - user name`) instead of appearing in the `Parameters:` section.

## Why it matters
A parameter description left in the free-text part of the comment isn't recognized as structured parameter documentation - it doesn't show up wherever tooling or reviewers expect the parameter list to be.

## How to fix
Move the parameter line(s) into a proper `// Parameters:` section, keeping the description itself as plain explanatory text about what the method does.

## Example

```bsl
// Saves user data.
//
// Parameters:
//  User - String - user name
//  Settings - Structure - user settings
Procedure SaveUserData(User, Settings)
```
