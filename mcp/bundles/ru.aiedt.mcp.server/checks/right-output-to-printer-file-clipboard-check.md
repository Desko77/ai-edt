# right-output-to-printer-file-clipboard-check

**Category:** Role Rights  ·  **Severity:** Major (Security)

Flags roles whose `OutputToPrinterFileClipboard` setting looks inconsistent with what the role is meant to be able to do - this right governs whether a user can print, save to file, or copy data out of the system.

## Why it matters
This right is effectively the data-exfiltration control point: anyone who has it can move information out of the infobase onto paper, into a file, or through the clipboard. Granting it without thinking about the role's sensitivity can undermine confidentiality requirements even though nothing else looks wrong.

## How to fix
Decide deliberately, per role, whether export should be allowed - most regular business roles need it, but roles handling sensitive data or working in restricted/kiosk-style setups may not. Write down the reasoning so the setting reads as intentional rather than a copy-paste default.

## Example
```xml
<!-- Role: TemporaryWorker - should be reviewed, not granted by default -->
<Rights>
  <Right>
    <Name>OutputToPrinterFileClipboard</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
Set it per role based on an explicit decision:
```xml
<!-- Role: RestrictedUser - export intentionally denied -->
<Rights>
  <Right>
    <Name>OutputToPrinterFileClipboard</Name>
    <Value>false</Value>
  </Right>
</Rights>
```
