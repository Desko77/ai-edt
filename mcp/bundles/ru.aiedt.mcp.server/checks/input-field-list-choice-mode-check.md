# input-field-list-choice-mode-check

**Category:** Forms - input fields  ·  **Severity:** Minor (Code smell, trivial)

Flags an input field that has a non-empty ChoiceList but doesn't have ListChoiceMode enabled.

## Why it matters

With the mode off, the field's choice list may not actually constrain what the user types, letting free text slip past values that were meant to be the only valid options.

## How to fix

Set ListChoiceMode = True whenever ChoiceList is filled, so input is limited to the listed values and the dropdown behaves as expected.
