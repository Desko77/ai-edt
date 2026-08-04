# md-object-name-unallowed-letter-check

**Category:** Metadata Naming  ·  **Severity:** Minor

Flags Russian-locale metadata names, synonyms, or comments that contain a Latin letter mixed in among Cyrillic text - most often a look-alike character such as Latin "o" typed inside a Cyrillic word.

## Why it matters
Homoglyphs are invisible in normal review: "Тoвары" (with a Latin "o") reads identically to "Товары" but is a different string. This breaks full-text search, causes confusing duplicate-looking entries, and usually comes from a keyboard-layout slip, a bad copy-paste, or OCR.

## How to fix
Delete and retype the affected word with the correct keyboard layout, or verify character codes programmatically (Cyrillic letters sit in the Unicode range 0400-04FF, Latin letters in 0041-007A). Latin words used intentionally as trademarks or brand names (e.g. "iPhone") are fine as long as they are not merged into a Cyrillic word.

## Example

```xml
<!-- Bad: Latin "o" (U+006F) inside a Cyrillic word -->
<synonym>
  <key>ru</key>
  <value>Тoвары</value>
</synonym>

<!-- Good: all Cyrillic -->
<synonym>
  <key>ru</key>
  <value>Товары</value>
</synonym>
```
