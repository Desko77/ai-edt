# document-post-in-privileged-mode-check

**Category:** Metadata - documents  ·  **Severity:** Major (Warning, Standard 689)

Flags a postable document whose "Privileged mode on posting" and "Privileged mode on cancel posting" flags aren't both turned on.

## Why it matters

Without privileged mode, posting runs under the current user's own rights to the registers being updated. A user who is otherwise entitled to post the document can still be blocked if they lack direct write access to one of the registers it moves.

## How to fix

Open the document's properties and enable both flags on the Movements/Posting tab. The one exception is a document deliberately designed for rights-checked direct register adjustment - for those, grant the necessary register rights through roles instead of enabling privileged mode.
