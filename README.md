# FlipSmart RuneLite Plugin

A RuneLite plugin that recommends profitable items to flip in the Grand Exchange and automatically tracks your flipping progress.

Sign up and log in through the plugin's **Flip Finder panel** in the RuneLite sidebar to get started. 

Without authentication, the plugin will not function.

## ✨ Features

### 📊 Flip Finder Panel

A dedicated sidebar panel with three tabs to help you flip smarter:

#### **Recommended Flips**
- Get personalized flip recommendations based on your cash stack
- Choose your flip style: **Conservative**, **Balanced**, or **Aggressive**
- Pick a **timeframe** to match how long you want to hold: Active, 30 mins, 2 hours, 4 hours or 12 hours
- See detailed information for each flip:
  - Recommended buy and sell prices
  - Expected profit and ROI
  - Item quantity and GE buy limit
  - Liquidity and risk ratings
- Favourite items you like, or block ones you never want suggested again
- Click any item to see more details

<img width="247" height="806" alt="Screenshot 2026-01-13 at 11 47 30 PM" src="https://github.com/user-attachments/assets/b157f443-936f-4b7e-843f-6a8ec63e1c2a" />


#### **Active Flips**
- Automatically tracks items you've bought and are holding
- **Action row** on every card tells you at a glance whether you're **Buying** or **Selling** that item
- An item you're buying and selling at the same time gets its own card for each side
- Shows current profit potential with live market prices
- Displays your total investment across all active flips
- See pending GE buy orders that haven't filled yet
- Right-click to dismiss flips you no longer want to track

<img width="462" height="692" alt="image" src="https://github.com/user-attachments/assets/fb038383-a225-481e-9e69-1e3701cd3022" />

#### **Completed Flips**
- View your completed flip history
- See profit/loss for each completed flip
- Track your flipping performance over time
- Click to expand and see flip duration and GE tax paid

<img width="558" height="1264" alt="image" src="https://github.com/user-attachments/assets/2067724a-0f60-4b80-a32b-437058e5dc1c" />


### 📈 Session Stats

A collapsible summary at the top of the panel tracks how the current session is going:

- **Profit this session** as flips complete
- **Session duration**
- **Realised GP/hour** from flips you've actually finished
- **Projected GP/hour** including what you're still holding

### 🏷️ Grand Exchange Offer Screen

When you open a GE offer, the plugin adds the numbers you'd otherwise work out yourself:

- **Breakeven, tax and profit** for the offer you're setting up
- **Buy limit and cooldown timer** so you know when your 4-hour limit resets
- **Offer timers** showing how long each offer has been sitting
- **Competitiveness indicators** comparing your price to Wiki prices
- **Coloured slot borders** so a glance across the GE tells you which offers are competitive
- **Adjustment prompts** when prices move and an offer has gone stale
- **Profit/loss on sell offer tooltips**, based on what you actually paid

### 📊 Exchange Viewer Overlay

A real-time on-screen display that shows all your active GE offers at a glance:

- **Live offer tracking**: See all 8 GE slots with their current status
- **Progress bars**: Visual progress showing how much of each offer has filled
- **Item details**: Item names, icons, quantities, and prices
- **Buy/Sell indicators**: Colour-coded to easily identify buy vs sell offers
- **Auto-hide empty slots**: Only shows active offers for a clean interface
- **Smart visibility**: Automatically hides when at the Grand Exchange, shows everywhere else

Perfect for monitoring multiple flips simultaneously without opening the GE interface!

### 🎯 Grand Exchange Integration

The plugin automatically monitors your Grand Exchange activity:
- **Detects buy orders** when you purchase items
- **Detects sell orders** when you sell items
- **Tracks profit/loss** automatically
- **Links recommended prices** to your trades for better tracking
- **Catches up after you log out**: trades that completed while you were away are reconciled from your GE history next time you log in

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

<img width="245" height="195" alt="Screenshot 2026-01-13 at 11 49 32 PM" src="https://github.com/user-attachments/assets/8aafc3a2-85b4-46bb-ad7f-267e43551379" />

### 🔁 Auto Mode

Turn on **Auto-Recommend** and the plugin cycles through recommendations one at a time,
loading each into Flip Assist as you go, so you can work a list without going back to the
panel between every flip.

### 🚦 Active Offer Advisor

For offers you already have open, the advisor tells you whether to **wait**, **move your
price**, or **exit** an offer that's been sitting too long — so a slow offer doesn't quietly
tie up a slot all day.

### 🔔 Notifications

Get told when something needs you, instead of watching the GE:

- **Sale completed**: a sell order finished
- **Flip suggestion**: a new suggestion is ready
- **Action alert**: a GE offer needs attention — sound and desktop popup follow your
  RuneLite notification settings unless you override them
- **Discord webhook**: send the same alerts to a Discord channel

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

You can also narrow what gets suggested with **F2P Mode**, a **minimum volume** floor, a
**minimum profit** threshold, and a **cashstack override** if you want to plan flips for a
budget other than the coins currently in your inventory.

### 📈 Real-Time Market Data

All prices and calculations are based on live market data:
- Current buy and sell prices
- Net profit after 2% GE tax
- ROI percentages
- Trade volume and liquidity scores

## ⚙️ Configuration

Access settings via the RuneLite configuration panel (wrench icon) → "FlipSmart":

### Flip Finder
- **Enable Flip Finder**: Toggle the sidebar panel on/off
- **Number of Recommendations**: How many flips to show
- **Flip Style**: Choose Conservative, Balanced, or Aggressive
- **Timeframe**: Active, 30 mins, 2 hours, 4 hours or 12 hours
- **Refresh Interval (minutes)**: How often to update recommendations
- **F2P Mode**: Only show F2P items, even on members worlds
- **Minimum Profit**: Only show items above this profit threshold
- **Minimum Volume**: Hide recommendations with daily trade volume below this value
- **Cashstack Override** / **Override Amount**: Suggest flips for a fixed budget instead of your actual inventory cash (supports `500k`, `2.5m`, `10M`)

### Display
- **Show Exchange Viewer**: Toggle the GE offer overlay on/off (disabled by default, hides when at the GE area)
- **Show Item Info**: Show breakeven, tax and profit on the GE offer screen
- **Show Offer Timers**: Display elapsed time for each GE offer
- **Show Competitiveness**: Indicators comparing your price to Wiki prices
- **Highlight Slot Borders**: Draw coloured borders around GE slots based on competitiveness
- **Show Adjustment Prompts**: Prompt to adjust stale GE offers when prices have moved
- **Show Profit/Loss**: Display profit/loss on GE sell offer tooltips based on your buy price
- **Colorblind Mode**: Use blue/orange instead of green/red
- **Active Offer Advisor**: In Active mode, advise when to wait, move price down, or exit aging offers

### Flip Assistant
- **Enable Flip Assistant**: Toggle the guided workflow overlay on/off
- **Auto-Fill Hotkey**: Hotkey to auto-fill price/quantity in GE (default: E)
- **Highlight GE Buttons**: Highlight buy/sell buttons and input fields in the GE
- **Show When GE Closed**: Keep the assistant visible even outside the Grand Exchange
- **Price Offset (GP)**: Adjust buy/sell prices to fill faster (positive = buy higher, sell lower)
- **Auto-Recommend**: Automatically cycle through recommendations one by one into Flip Assist

### Notifications
- **Sale Completed**: Notify when a sell order completes in the GE
- **Flip Suggestion**: Notify when a new flip suggestion is available
- **Action Alert**: Notify when a GE offer needs action
- **Discord Webhook URL**: Send notifications to a Discord channel

### Experimental
- **Aggressive Advisor**: Experimental advisor behaviour — expect rough edges

### Advanced
- **API URL Override**: Leave empty to use the production server

## 🚀 Getting Started

1. **Install the plugin** in RuneLite
2. **Open the Flip Finder panel** from the RuneLite sidebar
3. **Log in** with your FlipSmart account
4. **Browse recommended flips** and choose items that fit your cash stack
5. **Buy items in the GE** - they'll automatically appear in the "Active Flips" tab
6. **Sell when ready** - completed flips move to the "Completed" tab

## 💡 Tips

- **Start small**: Test with low-cost items to get familiar with the plugin
- **Use the Exchange Viewer**: Enable it in Display settings to monitor all your offers at a glance anywhere in the game
- **Pick a timeframe that matches you**: Active is for flips you'll babysit; 12 hours suits overnight holds
- **Check Active Flips**: The Action row tells you which side each item is on without opening the GE
- **Use Recommended Prices**: When buying a recommended item, the plugin remembers the suggested sell price
- **Watch your cash**: The plugin considers your cash stack when recommending flips
- **Refresh recommendations**: Click the refresh button to get updated market data
- **Dismiss items**: Right-click active flips to remove items you no longer want to track
- **Use Price Offset**: If offers aren't filling fast enough, set a small price offset (e.g., 1-5 GP) to improve fill rates
- **Let the advisor watch slow offers**: Active Offer Advisor will tell you when an offer has gone stale rather than you checking manually

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
- Check that you're logged in to FlipSmart
- Ensure you're logged into RuneLite and OSRS
- Try clicking the Refresh button

**No recommendations showing up**
- Make sure you have enough cash in your inventory
- Try lowering the "Minimum Profit" or "Minimum Volume" setting
- Check whether "F2P Mode" is on and you're expecting members items
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

**Not getting notified about offers**
- Check the Notifications section - "Action Alert" and "Sale Completed" are configured separately
- Desktop notifications follow your RuneLite notification settings unless you override them

## 🌐 Web Dashboard
You can find a link to our website in the plugin side panel.

- **Detailed price graphs** with historical trends
- **Portfolio analytics** across all your accounts
- **Market insights** and sector performance
- **Extended flip history** with advanced filtering

Your plugin data syncs automatically with the web dashboard.

## 📝 License

BSD 2-Clause License

## 🙏 Credits

Built with the [RuneLite Plugin Template](https://github.com/runelite/example-plugin)

Inspired by excellent RuneLite flipping plugins:
- [Flipping Utilities](https://github.com/Belieal/flipping-utilities) - Comprehensive GE flipping tracker
- [Flipping Co-pilot](https://github.com/cbrewitt/flipping-copilot) - AI-powered flip recommendations

---

**Happy flipping!** 🎉
