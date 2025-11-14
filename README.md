# Flipping Tool Plugin

A RuneLite plugin that integrates with the Flipping Tool API to automatically analyze items in your inventory and highlight profitable flipping opportunities with visual indicators.

<img width="245" height="321" alt="Screenshot 2025-11-14 at 12 50 59 AM" src="https://github.com/user-attachments/assets/3a8d00f9-1466-43d4-9a29-0c3c0f50a965" />

<img width="142" height="184" alt="Screenshot 2025-11-14 at 12 51 06 AM" src="https://github.com/user-attachments/assets/d4f067c7-fd22-41d9-a663-57f9b6be9135" />


## Features

- 🔍 **Inventory Insights** - Monitors all items in your inventory in real-time and highlights good potential flips
- 📊 **API-Powered Analysis** - Queries the Flipping Tool API for comprehensive item analysis including:
  - Active buy and sell prices
  - Net profit margin after 2% GE tax
  - ROI percentage
  - Liquidity score (trade volume analysis)
  - Risk assessment (price volatility)
  - Overall efficiency scoring
- 📈 **Smart Scoring** - Uses weighted efficiency algorithm combining:
  - 40% ROI (return on investment)
  - 30% Liquidity (how fast it trades)
  - 30% Safety (inverse of risk)
- 📱 **Info Overlay** - Shows details about the best flip opportunity in your inventory

## What Makes a "Good Flip"?

An item is highlighted when:
1. **Efficiency score ≥ 50** (configurable)
2. **Net margin > 0** (profitable after GE tax)

## Prerequisites

- Java 17 (for development)
- RuneLite
- Access to a Flipping Tool API instance (see Configuration below)

## Development Setup

### 1. Clone the Repository

```bash
git clone https://github.com/Flip-Smart/flip-smart-runelite-plugin.git
cd flip-smart-runelite-plugin
```

### 2. Open in IntelliJ IDEA

1. Open IntelliJ IDEA
2. File → Open → Select the project folder
3. Wait for Gradle to import dependencies
4. Set Project SDK to Java 17 (File → Project Structure → Project)

### 3. Configure Run Configuration

1. Open `src/test/java/com/flippingtool/ExamplePluginTest.java`
2. Right-click and select "Run 'ExamplePluginTest.main()'"
3. If it fails, edit the run configuration:
   - Run → Edit Configurations
   - Add VM options: `-ea --add-exports java.desktop/com.apple.eawt=ALL-UNNAMED`
   - Apply and run again

### 4. Run the Plugin

**From IntelliJ:**
- Run the `ExamplePluginTest` configuration

**From terminal:**
```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.17/libexec/openjdk.jdk/Contents/Home
./gradlew runClient
```

RuneLite will launch with your plugin loaded!

## Configuration

### Setting Up the API Host

The plugin requires a Flipping Tool API instance.

### Testing the Plugin

1. **Enable the plugin:**
   - Click the Configuration icon (wrench) in RuneLite
   - Find "Flipping Tool" in the plugin list
   - Toggle it ON

2. **Configure API settings:**
   - Right-click "Flipping Tool" → Configure
   - **Set API URL** to your API host (required!)
     - Local development: `http://localhost:8000`
   - Set Minimum Efficiency Score: `50` (lower to see more items)
   - Enable "Highlight Good Flips"

3. **Test in-game:**
   - Log into OSRS
   - Add test items to your inventory:
     - Fire runes (high efficiency - should be highlighted 🔵)
     - Feathers (high efficiency - should be highlighted 🔵)
     - Cannonballs (good efficiency - should be highlighted 🔵)
   - Look for blue boxes around profitable items!

4. **Check the overlay panel** (top-left):
   - Good Flips count
   - Best item name
   - Net margin and efficiency score

## Configuration Options

| Setting | Default | Description |
|---------|---------|-------------|
| **API URL** | `http://localhost:8000` | **Required**: URL of the Flipping Tool API instance |
| **Show Overlay** | ✅ Enabled | Toggle the info panel display |
| **Highlight Good Flips** | ✅ Enabled | Show blue boxes around good flip items |
| **Minimum Efficiency Score** | 50 | Minimum score (0-100) to highlight items |
| **Minimum Profit** | 100 GP | Display threshold for the overlay |

⚠️ **Important**: You must configure a valid API URL for the plugin to work!

## Project Structure

```
src/main/java/com/flippingtool/
├── FlippingToolPlugin.java         # Main plugin with inventory monitoring
├── FlippingToolConfig.java         # Configuration interface
├── FlippingToolOverlay.java        # Info panel overlay
├── FlippingInventoryOverlay.java   # Draws blue boxes on items
├── FlippingApiClient.java          # HTTP client with caching
└── FlipAnalysis.java               # API response model

src/test/java/com/flippingtool/
└── ExamplePluginTest.java          # Test runner for development
```

## API Response Example

The plugin processes responses from `/analysis/{item_id}`:

```json
{
  "item_id": 554,
  "item_name": "Fire rune",
  "current_prices": {
    "high": 5,
    "low": 4,
    "net_margin": 1,
    "roi_percent": 25.0
  },
  "efficiency": {
    "score": 93.3,
    "rating": "Excellent"
  },
  "liquidity": {
    "score": 100,
    "rating": "Excellent"
  },
  "risk": {
    "score": 15.2,
    "rating": "Very Low"
  }
}
```

## Building for Distribution

To create a JAR for distribution:

```bash
./gradlew build
```

The JAR will be in `build/libs/`.

## Troubleshooting

### No items are being highlighted
- **Check API URL is configured correctly** in plugin settings
- Test API connection: `curl https://your-api-url/analysis/554`
- Lower the "Minimum Efficiency Score" in settings (try 30)
- Check that items have positive margins

### API connection errors
- **Ensure API URL is correct** in plugin settings (most common issue!)
- Verify the API host is accessible: `curl https://your-api-url/`
- Check for firewall or network issues
- Make sure you're using `https://` for public APIs or `http://` for local development

### Build errors
- Make sure Java 17 is set as the project SDK
- Refresh Gradle dependencies: `./gradlew --refresh-dependencies`
- Invalidate IntelliJ caches: File → Invalidate Caches / Restart

## Dependencies

- RuneLite API (latest.release)
- OkHttp 4.9.0 (HTTP client, provided by RuneLite)
- Gson 2.8.9 (JSON parsing, provided by RuneLite)
- Lombok 1.18.30 (annotation processing)

## License

BSD 2-Clause License

## Credits

Built with the [RuneLite Plugin Template](https://github.com/runelite/example-plugin)
