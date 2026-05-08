# WebShopX Localization Workflow

## 1) Source of Truth

- `en-US` is the only source language for Crowdin.
- All translatable bundles live under:
  - `src/main/resources/web/i18n/<namespace>/en-US.json`
  - `src/main/resources/web/i18n/<namespace>/<locale>.json`
- Current namespaces include:
  - `app`
  - `admin`
  - `market-algorithms`
  - `materials`

## 2) Runtime Rule

- UI and runtime messages should be read from i18n bundles, not hardcoded in JS.
- For `app.js` and `admin.js`, bundle loading is done through:
  - `WebShopXI18n.loadBundleSync(namespace)`
- Fallback constants in code are temporary safety nets and should be reduced over time.

## 3) Key Naming Convention

- Use nested object keys (recommended) with lowercase snake_case segments:
  - `ui_text.theme_toggle_dark`
  - `messages.load_failed`
  - `error_tips.market.invalid_listing`
- Keep enum-like IDs (for protocol/constant compatibility) unchanged when needed:
  - `SHOP_COIN`, `GAME_COIN`, `PENDING`, `WAIT_CLAIM`
- Never rename an existing key unless absolutely required. Add new keys instead.

## 4) Placeholder Convention

- Use named placeholders with braces: `{count}`, `{username}`, `{amount_text}`.
- Placeholder names must match between `en-US` and every target locale.

## 5) Validation Before Commit

Run:

```powershell
powershell -ExecutionPolicy Bypass -File tools\i18n-validate.ps1
```

Validation checks:

- Every namespace has `en-US.json`
- Locale files keep key sets consistent with `en-US.json`
- Placeholder names are consistent with `en-US.json`

Optional hardcoded audit:

```powershell
powershell -ExecutionPolicy Bypass -File tools\i18n-hardcoded-audit.ps1 -OutputFile I18N_HARDCODED_REPORT.md
```

## 6) Crowdin Source Upload Scope

Upload only source files (`en-US`) as Crowdin source:

- `src/main/resources/web/i18n/app/en-US.json`
- `src/main/resources/web/i18n/admin/en-US.json`
- `src/main/resources/web/i18n/market-algorithms/en-US.json`
- `src/main/resources/web/i18n/materials/en-US.json`

Target locales (including `zh-CN`) are managed as translations.
