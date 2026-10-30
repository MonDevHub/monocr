// Shared UI state: the shell has to know when the drawer is open so the page behind it can go
// inert. The theme needs none: the `dark` class on <html> is the one source, and the header's
// two toggles agree through the `theme-*-only` CSS (read it with `currentTheme()`).
export const drawerState = $state({ open: false });
