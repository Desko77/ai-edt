# mdo-scheduled-job-description-check

**Category:** Metadata / Localization  ·  **Severity:** Minor

Flags predefined scheduled jobs that have their `Description` property filled in, per standard #767.

## Why it matters
A predefined scheduled job already exposes a `Synonym` for display purposes; duplicating the same text into `Description` is redundant upkeep and the two can drift out of sync across languages.

## How to fix
Clear the job's `Description` field and keep only the `Synonym` filled in the configuration language.

## Example

```xml
<ScheduledJob>
  <Name>DataSynchronization</Name>
  <Description></Description>
  <Synonym>Синхронизация данных</Synonym>
  <Predefined>true</Predefined>
</ScheduledJob>
```
