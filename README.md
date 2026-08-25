# Death Race

A multiplayer NeoForge minigame mod for Minecraft 1.21.1. Every player receives the same random death objective. The first player to die from the exact matching cause wins the round.

## Commands

- `/deathrace start` — start a new match and reset scores (operator level 2)
- `/deathrace stop` — stop the match (operator level 2)
- `/deathrace skip` — force-skip the current objective (operator level 2)
- `/deathrace vote` — vote to skip; a strict majority is required
- `/deathrace status` — display the current objective

## Scoring

- Round win: 10 points
- Completed within 30 seconds: +5
- Completed within 60 seconds: +3
- Completed within 120 seconds: +1
- After 120 seconds: no speed bonus

## Installation

Install NeoForge for Minecraft 1.21.1, then place `deathrace-1.1.0.jar` in the profile's `mods` folder. Every participating client and the server/host should have the mod installed.

## Configuration

The first match creates `config/deathrace.properties`. It controls the winning score, base points, speed bonuses, round delay, close-call window, vote-skip percentage, and enabled challenge IDs. Stop and restart the match after editing it.

## Build

Requires Java 21. Run `./gradlew build` (macOS/Linux) or `gradlew.bat build` (Windows). The compiled mod is created in `build/libs`.
