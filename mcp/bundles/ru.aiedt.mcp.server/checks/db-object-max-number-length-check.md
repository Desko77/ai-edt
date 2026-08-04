# db-object-max-number-length-check

**Category:** Database objects / metadata design  ·  **Severity:** MAJOR

Flags a numeric attribute, dimension, or resource whose configured length exceeds 31 digits.

## Why it matters
31 digits is the platform's practical ceiling for numeric precision - going beyond it risks storage and rounding problems and almost never reflects a real business need. Most real-world quantities, amounts, and rates comfortably fit well under that limit; a field declared at 40 or 50 digits is usually an oversight rather than a deliberate choice.

## How to fix
Work out the largest value the field genuinely needs to hold (check existing data with `MAX()`/`MIN()` if the field is already in use) and set the length accordingly - 15 digits comfortably covers the overwhelming majority of quantities, prices, and amounts. Reserve anything close to the 31-digit ceiling for genuinely exceptional cases, such as very fine-grained cryptocurrency units.

## Example

```xml
<mdclass:Document uuid="..." name="Order">
  <attributes uuid="...">
    <name>Amount</name>
    <type>
      <types>Number</types>
      <numberQualifiers>
        <precision>15</precision>
        <scale>2</scale>
      </numberQualifiers>
    </type>
  </attributes>
</mdclass:Document>
```
