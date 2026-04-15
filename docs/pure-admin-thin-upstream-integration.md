# pure-admin-thin Upstream Integration Notes

## Upstream baseline
- Repository: https://github.com/pure-admin/pure-admin-thin
- Snapshot commit: `f0ff132` (2025-10-30)
- Local temporary clone path: `.tmp/pure-admin-thin`

## Extracted upstream conventions
- Theme variables from `src/style/theme.scss`
- Sidebar/layout semantics from `src/style/sidebar.scss`
- Container semantics from `src/layout/index.vue`

## WebShopX adaptation mapping
- `app-wrapper` + `pure-layout`: overall shell container
- `sidebar-container` + `pure-sidebar`: left navigation shell
- `main-container` + `pure-main`: main content shell
- `fixed-header`: sticky top header behavior

## Theme synchronization
- Keep existing `light` / `dark` classes for current WebShopX styles.
- Add `data-theme` synchronization for upstream-compatible vars:
  - `light` -> `data-theme="light"`
  - `dark` -> `data-theme="default"`

## Design boundaries
- `styles.css` remains the primary component style source.
- `frontend-refactor.css` is restricted to shell adaptation and help-page structure styles.
- No business DOM IDs or API contracts are changed.
