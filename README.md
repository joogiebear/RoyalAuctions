# RoyalAuctions

A Hypixel-style Auction House for Paper, built to be **fully compatible with the eco suite**
(EcoItems, Talismans, Reforges, EcoArmor, and any other `eco`-based custom item).

Part of the Mystipixel plugin family alongside **RoyalBank** (banking) and **EconGuard**
(economy anti-abuse). Sales flow through Vault, so EconGuard sees them as normal economy activity.

## Features

- **Category tabs** (Hypixel-style) — Weapons, Armor, Tools, Talismans, Reforge Stones,
  Enchanted Books, Consumables, Blocks, Miscellaneous. Fully configurable in `config.yml`.
- **Search** by item name (click the search button and type in chat, or `/ah search <query>`).
- **Sorting** — Newest, Oldest, Price ↑, Price ↓.
- **Full item fidelity** — listings are stored with Paper's native item serialization, so all
  eco custom data (NBT / components / persistent data) survives listing → purchase intact.
- **Listing fees** — configurable percent-of-price with a minimum floor, charged on posting.
- **Collection** — purchases that don't fit, plus expired/cancelled listings, wait safely in
  `/ah collect`.
- **Expiry** — listings auto-expire after a configurable duration and return to the seller.
- **Storage** — SQLite by default (zero setup), MySQL for multi-server networks. Chosen in config.
- **PlaceholderAPI** support.

## Commands

| Command | Description |
| --- | --- |
| `/ah` | Open and browse the Auction House |
| `/ah sell <price>` | List the item in your hand |
| `/ah search <query>` | Search listings by name |
| `/ah listings` | View / cancel your active listings (right-click to cancel) |
| `/ah collect` | Claim purchases and returned items |
| `/ah reload` | Reload config, messages and categories (admin) |

Aliases: `/auctionhouse`, `/auctions`, `/auction`.

## Permissions

| Node | Default | Description |
| --- | --- | --- |
| `royalauctions.use` | true | Open and browse |
| `royalauctions.sell` | true | List items |
| `royalauctions.admin` | op | Reload; bypasses the per-player listing limit |

## Categories

Items are tested against each category in ascending `priority` and placed in the first that
matches. A rule matches if **any** of its predicate types matches:

- `eco-namespaces` — eco source plugin, e.g. `[talismans]`, `[reforges]`, `[ecoarmor]`
- `materials` — exact Bukkit material names
- `material-suffixes` / `material-prefixes` — e.g. `_SWORD`, `_HELMET`
- `tags` — built-ins: `EDIBLE`, `BLOCK`, `ARMOR`, `TOOL`, `WEAPON`
- `any: true` — catch-all (keep one, e.g. `misc`, as the final fallback)

## Building

```bash
mvn -DskipTests package
```

All dependencies (including the `eco` API, from the Auxilor repo) resolve automatically —
no manual install step needed. The JDBC driver and connection pool are downloaded at runtime
by Paper's library loader, so the output jar stays small.

CI builds the jar on every push via [`.github/workflows/build.yml`](.github/workflows/build.yml)
(JDK 21) and uploads it as a build artifact.

## Requirements

- Paper (tested on MC 26.2)
- Vault + an economy provider
- Optional: eco (for custom-item categories), PlaceholderAPI, EconGuard
