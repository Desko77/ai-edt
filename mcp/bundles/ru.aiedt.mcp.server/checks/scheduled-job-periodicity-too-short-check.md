# scheduled-job-periodicity-too-short-check

**Category:** Metadata  ·  **Severity:** Major (Warning, standard 402)

Flags scheduled jobs whose repeat interval (`RepeatPause`) is set below one minute.

## Why it matters
Running a job every few seconds keeps hammering the server with overhead that rarely matches any real business need, and it crowds out other work competing for the same resources. Sub-minute scheduling is almost always a sign the interval was set arbitrarily rather than based on an actual requirement.

## How to fix
Raise the interval to at least one minute, and size it to the job's real purpose - most jobs are fine running once a day, only genuinely time-sensitive ones need a short interval, and heavy jobs are better moved to off-hours and staggered against each other so they do not all compete at once.

## Example
```xml
<ScheduledJob>
  <Name>QueueProcessing</Name>
  <Schedule>
    <RepeatPause>10</RepeatPause>  <!-- too frequent, under a minute -->
  </Schedule>
</ScheduledJob>
```
Fix by widening the interval to match actual need:
```xml
<ScheduledJob>
  <Name>QueueProcessing</Name>
  <Schedule>
    <RepeatPause>300</RepeatPause>  <!-- every 5 minutes -->
  </Schedule>
</ScheduledJob>
```
