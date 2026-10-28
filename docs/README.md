# Tafh docs site

Static landing page for [Tafh](https://github.com/nero-/Tafh), built with Nuxt 3 +
Tailwind. Styled to match the in-app terminal aesthetic (Tokyo Night palette, JetBrains
Mono, terminal-block accents).

## Develop

```sh
cd docs
pnpm install
pnpm dev         # local dev server
```

## Build static site

```sh
pnpm generate
# output in .output/public — deploy that to any static host
```

If you deploy under a subpath (e.g. GitHub Pages at `/tafh/`):

```sh
NUXT_APP_BASE_URL=/tafh/ pnpm generate
```

## Layout

- `app.vue` — root component (renders the routed page)
- `pages/index.vue` — landing page
- `pages/privacy.vue` — privacy policy
- `assets/css/main.css` — global styles + custom utilities (`.bracket-btn`, `.inline-chip`, `.tui-rule`, `.prose-chu`)
- `tailwind.config.js` — palette + fonts
- `public/` — logo, demo recordings and demo gif
