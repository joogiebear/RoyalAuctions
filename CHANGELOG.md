## 2026.32.0 — 2026-08-07

### ✨ Features
- report anonymous usage stats via bStats (`e5d15cd`)

### 🐛 Fixes
- actually play menu sounds (`fe3d8d4`)
- return escrowed create-flow items on shutdown instead of destroying them (`05b7532`)

### ⚡ Performance
- page auction browsing in SQL instead of in memory (`859bc62`)

## 2026.29.1 — 2026-07-17

### 🐛 Fixes
- don't swallow a failed migration as 'column already exists' (`e2996cd`)
- pass the Modrinth payload as a file, not inline (`f4b007a`)

## 2026.29.0 — 2026-07-17

### ✨ Features
- player-head icons + eco-style direct row/column (`56fc719`)
- add ConfigValidator for load-time sanity checks (`fbb2d21`)
- report auction money movements to EconGuard (`797e9b8`)

