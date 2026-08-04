# functional-option-privileged-get-mode-check

**Category:** Metadata - functional options  ·  **Severity:** Major (Warning, Standard 689)

Flags a functional option whose "Privileged mode on get" flag isn't enabled.

## Why it matters

Without it, reading the option's value is subject to the current user's own rights, so a global on/off switch can behave inconsistently - or even throw - depending on who happens to be logged in when it's read.

## How to fix

Enable "Privileged mode on get" on the option's Main tab in the Configurator. The exception is a functional option deliberately parameterized to vary by the user's own access rights - those are meant to differ per user.
