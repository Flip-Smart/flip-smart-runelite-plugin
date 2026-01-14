# Flip Smart RuneLite Plugin

A RuneLite plugin that helps you find profitable items to flip in the Grand Exchange and automatically tracks your flipping progress.

## 🔐 Authentication Required

This plugin requires a **Flip Smart account** to use. You'll need to:

1. Create an account on [Flip Smart](https://flipsm.art)
2. Log in through the plugin's **Flip Finder panel** in the RuneLite sidebar
3. Your credentials are securely stored after login

Without authentication, the plugin will not function.

## ✨ Features

### 📊 Flip Finder Panel

A dedicated sidebar panel with three tabs to help you flip smarter:

#### **Recommended Flips**
- Get personalized flip recommendations based on your cash stack
- Choose your flip style: **Conservative**, **Balanced**, or **Aggressive**
- See detailed information for each flip:
  - Recommended buy and sell prices
  - Expected profit and ROI
  - Item quantity and GE buy limit
  - Liquidity and risk ratings
- Click any item to see more details

#### **Active Flips**
- Automatically tracks items you've bought and are holding
- Shows current profit potential with live market prices
- Displays your total investment across all active flips
- See pending GE buy orders that haven't filled yet
- Right-click to dismiss flips you no longer want to track

#### **Completed Flips**
- View your completed flip history
- See profit/loss for each completed flip
- Track your flipping performance over time
- Click to expand and see flip duration and GE tax paid

### 📊 Exchange Viewer Overlay

A real-time on-screen display that shows all your active GE offers at a glance:

- **Live offer tracking**: See all 8 GE slots with their current status
- **Progress bars**: Visual progress showing how much of each offer has filled
- **Item details**: Item names, icons, quantities, and prices
- **Buy/Sell indicators**: Color-coded to easily identify buy (green) vs sell (red) offers
- **Multiple display sizes**: Choose between Full or Compact layouts
- **Auto-hide empty slots**: Only shows active offers for a clean interface
- **Smart visibility**: Automatically hides when at the Grand Exchange, shows everywhere else

Perfect for monitoring multiple flips simultaneously without opening the GE interface!

### 🎯 Grand Exchange Integration

The plugin automatically monitors your Grand Exchange activity:
- **Detects buy orders** when you purchase items
- **Detects sell orders** when you sell items
- **Tracks profit/loss** automatically
- **Links recommended prices** to your trades for better tracking

### 🧭 Flip Assistant (Guided Workflow)

A floating step-by-step guide that walks you through the entire flip process:

- **Visual progress tracker**: Horizontal step indicators showing your position in the flip journey
- **Dynamic instructions**: Context-aware prompts that update based on your current GE state
- **Hotkey support**: Press your Auto-Fill hotkey to instantly set quantity and price
- **GE widget highlighting**: Optional highlighting of buy/sell buttons and input fields
- **Profit preview**: Live profit calculations before you even complete the flip
- **Animated feedback**: Pulsing indicators draw attention to your current action

The assistant automatically detects:
- When you're searching for an item (prompts to press Enter/hotkey)
- When you need to set quantity (shows recommended qty, hotkey hint)
- When you need to set price (shows recommended price, hotkey hint)  
- When you need to confirm the offer

Perfect for learning the flip workflow or staying focused during multi-item flips!

### 💰 Smart Recommendations

Flip recommendations are tailored to your playstyle:

- **Conservative**: Low-risk, high-liquidity items with steady profits
- **Balanced**: Mix of safety and profitability
- **Aggressive**: Higher margins with more risk

Recommendations consider:
- Your available cash stack
- Item liquidity (trade volume)
- Price volatility and risk
- ROI and profit margins
- GE buy limits

### 📈 Real-Time Market Data

All prices and calculations are based on live market data:
- Current buy and sell prices
- Net profit after 2% GE tax
- ROI percentages
- Trade volume and liquidity scores

## ⚙️ Configuration

Access settings via the RuneLite configuration panel (wrench icon) → "Flip Smart":

### Flip Finder
- **Enable Flip Finder**: Toggle the sidebar panel on/off
- **Number of Recommendations**: How many flips to show (1-50)
- **Flip Style**: Choose Conservative, Balanced, or Aggressive
- **Refresh Interval**: How often to update recommendations (1-60 minutes)
- **Minimum Profit**: Only show items above this profit threshold

### Display
- **Show Exchange Viewer**: Toggle the GE offer overlay on/off (disabled by default, hides when at the GE area)
- **Display Size**: Choose between Full or Compact layouts
- **Show Item Names**: Display item names in the Exchange Viewer (Full mode only)
- **Show Item Icons**: Display item icons in the Exchange Viewer
- **Show Detailed Info**: Show quantity, price per item, and total value (Full mode only)

### Flip Assistant
- **Enable Flip Assistant**: Toggle the guided workflow overlay on/off
- **Auto-Fill Hotkey**: Hotkey to auto-fill price/quantity in GE (default: E)
- **Highlight GE Buttons**: Highlight buy/sell buttons and input fields in the GE
- **Show When GE Closed**: Keep the assistant visible even outside the Grand Exchange
- **Price Offset (GP)**: Adjust buy/sell prices to fill faster (positive = buy higher, sell lower)

### General
- **Track History**: Save flip history across sessions

## 🚀 Getting Started

1. **Install the plugin** in RuneLite
2. **Open the Flip Finder panel** from the RuneLite sidebar
3. **Log in** with your Flip Smart account
4. **Browse recommended flips** and choose items that fit your cash stack
5. **Buy items in the GE** - they'll automatically appear in the "Active Flips" tab
6. **Sell when ready** - completed flips move to the "Completed" tab

## 💡 Tips

- **Start small**: Test with low-cost items to get familiar with the plugin
- **Use the Exchange Viewer**: Enable it in Display settings to monitor all your offers at a glance anywhere in the game
- **Try different sizes**: Use Compact mode for minimal screen usage
- **Check Active Flips**: Monitor your active flips to see current profit potential
- **Use Recommended Prices**: When buying a recommended item, the plugin remembers the suggested sell price
- **Watch your cash**: The plugin considers your cash stack when recommending flips
- **Refresh recommendations**: Click the refresh button to get updated market data
- **Dismiss items**: Right-click active flips to remove items you no longer want to track
- **Use Price Offset**: If offers aren't filling fast enough, set a small price offset (e.g., 1-5 GP) to improve fill rates

## 🎮 In-Game Usage

1. Open the **Flip Finder** panel from the RuneLite sidebar
2. Browse the **Recommended** tab for profitable flip opportunities
3. **Click an item** to set it as your focus - the **Flip Assistant** will appear
4. Go to the **Grand Exchange** - the assistant guides you through each step:
   - It auto-selects your item in the search
   - Press your Auto-Fill hotkey (default: E) when prompted to set quantity
   - Press hotkey again when prompted to set price
   - Click confirm to place your offer
5. Close the GE interface and the **Exchange Viewer** will show your offers in real-time on screen
6. When items fill, they appear in the **Active Flips** tab
7. Click the active flip to focus on it for selling - the assistant guides the sell process
8. When you sell items, they move to the **Completed** tab
9. Track your total profit in the Completed tab!

## 🛠️ Troubleshooting

**Plugin shows "Failed to fetch recommendations"**
- Check that you're logged in to Flip Smart
- Ensure you're logged into RuneLite and OSRS
- Try clicking the Refresh button

**No recommendations showing up**
- Make sure you have enough cash in your inventory
- Try lowering the "Minimum Profit" setting
- Check that you're logged into OSRS
- Try clicking the Refresh button

**Active flips not updating**
- Make sure you're at the Grand Exchange
- Verify the items actually filled (check GE interface)
- Try clicking Refresh in the Active Flips tab

**Exchange Viewer not showing**
- Check that "Show Exchange Viewer" is enabled in Display settings (it's disabled by default)
- The overlay hides when you're at the Grand Exchange area - walk away to see it
- You must open the GE at least once per session to load your offers
- The overlay only shows when you have active GE offers (empty slots are hidden)
- Try repositioning the overlay - it may be off-screen

**Orders not filling quickly**
- Try increasing the "Price Offset" setting in Flip Assistant configuration
- This will automatically adjust your buy/sell prices to fill faster

## 📸 Screenshots

*Screenshots coming soon*

---

**Happy flipping!** 🎉
