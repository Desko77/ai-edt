# register-resource-precision-check

**Category:** Metadata  ·  **Severity:** Minor (Warning)

Flags accumulation or accounting register resources whose numeric precision (total digit count) is set above 25 - the platform's ceiling for register resource values.

## Why it matters
Register resources feed totals and balances calculated internally by the platform engine, and that engine caps how many digits it can carry for a resource. A resource declared past 25 digits does not get extra range - it risks calculation errors or rejected updates once the engine enforces its own limit.

## How to fix
Lower the resource's numeric precision to 25 digits or fewer in the Designer/EDT properties. As a rule of thumb, 15 digits is enough for quantity-style resources and 17 covers most amount/sum resources; 25 is the hard ceiling, not a target.

## Example
```xml
<AccumulationRegister>
  <Resource>
    <Name>Quantity</Name>
    <Type>
      <NumberType>
        <Precision>30</Precision>  <!-- too high, exceeds the 25-digit cap -->
        <Scale>5</Scale>
      </NumberType>
    </Type>
  </Resource>
</AccumulationRegister>
```
Fix by bringing `Precision` within the limit:
```xml
<Precision>15</Precision>  <!-- within range -->
```
