---
name: Waypoint voice command parse ordering
description: Why waypoint patterns must precede StartNavigation and StopNavigation in VoiceCommandParser — the invariant that prevents silent misrouting.
---

## The rule

In `VoiceCommandParser.parse()`, the matching order is fixed and must NOT be reordered:

1. `WAYPOINT_ADD_REGEX` (AddWaypoint) — before NAV_PREFIX_REGEX
2. RemoveLastWaypoint / ClearWaypoints / ListWaypoints / ContinueRoute — before NAV_PREFIX_REGEX and STOP_PATTERNS
3. NAV_PREFIX_REGEX (StartNavigation)
4. SelectCandidate ordinals
5. Distance / Time / Destination / Repeat / Mute / Unmute / StopNavigation
6. Blank → Unknown; else → GeneralQuestion

**Why:**
- `"dar važiuojam į X"` contains "važiuojam į" which is a StartNavigation prefix. Without checking AddWaypoint first, it silently replaces the current route instead of adding a stop.
- `"atšauk paskutinį"` contains "atšauk" which is in STOP_PATTERNS. Without checking RemoveLastWaypoint first, it cancels navigation instead of removing the last stop.
- `"rodyk sustojimus"` — "rodyk" is a StartNavigation prefix. Without checking ListWaypoints first, it fires `StartNavigation("sustojimus")`.

**How to apply:**
Any time a new command is added whose trigger phrase shares a prefix with an existing command, verify the more-specific pattern is placed higher in the check sequence. The regression tests in `VoiceCommandParserWaypointTest.kt` cover all three of the above conflicts explicitly.
