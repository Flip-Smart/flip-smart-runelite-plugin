# Flip Smart RuneLite Plugin - Development Context

## Build Process

**IMPORTANT**: After making any code changes to the plugin, always rebuild it using Gradle.

```bash
cd /Users/milesblack/Projects/flip-smart-runelite-plugin
./gradlew build
```

This will:
- Compile the Java source files
- Run tests
- Package the plugin JAR file
- Output the build artifacts to the `build/` directory

The build typically completes in a few seconds.

## Project Structure

- `src/main/java/com/flipsmart/` - Main plugin source code
  - `FlipFinderPanel.java` - Main UI panel with flip recommendations, active flips, and completed flips
  - `FlipSmartPlugin.java` - Plugin entry point and event handlers
  - `FlipSmartApiClient.java` - API client for backend communication
  - `FlipSmartConfig.java` - Plugin configuration interface
  - Various overlay and response classes

## UI Components

### Chart Icons
Bar chart icons are created programmatically using Java 2D graphics with transparent backgrounds to blend with the UI. See `createChartIconLabel()` in `FlipFinderPanel.java`.

## Dependencies
- RuneLite API
- Gradle for build management
- JUnit for testing
