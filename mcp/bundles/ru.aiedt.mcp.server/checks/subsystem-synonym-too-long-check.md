# subsystem-synonym-too-long-check

**Category:** Metadata  ·  **Severity:** Minor (Code style, standard 712)

Flags a top-level subsystem (section) synonym longer than 35 characters, spaces included.

## Why it matters
The section panel lays out top-level subsystem names across two lines of limited width. A synonym past 35 characters gets cut off with an ellipsis, which reads worse next to sibling sections that fit cleanly.

## How to fix
Shorten the synonym to 35 characters or fewer. Favor short, clear section names, and try to keep sibling sections roughly similar in length so the panel looks balanced.

## Example
```xml
<Subsystem>
  <Name>EnterpriseResourcePlanning</Name>
  <Synonym>Планирование ресурсов предприятия и управление производством</Synonym> <!-- well over 35 chars -->
</Subsystem>
```
A shorter synonym fits the panel:
```xml
<Subsystem>
  <Name>Production</Name>
  <Synonym>Производство</Synonym>
</Subsystem>
```
