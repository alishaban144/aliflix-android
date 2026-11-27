# Mobile controls

Use `AliflixLiquidToggle` for every mobile boolean setting, including new settings.
Labelled rows own the toggle action and pass a null callback to the visual; standalone
controls pass a callback and expose switch semantics with a 48dp touch target.

The reference is Deepak Chandwani's [Liquid Toggle](https://21st.dev/community/components?q=play&preview=%2F%40deepaksslibra%2Fcomponents%2Fliquid-toggle%2Fliquid-toggle),
inspected from its actual preview bundle `https://cdn.21st.dev/bundled/673.html`.
Keep its 52x32 track, gray `#D2D6E9` / blue `#275EFE`, two white 10px circles,
12px translation with opposing scales and 500ms cubic-bezier(.4,0,.2,1).
The detached droplet was removed in 3.1.166 at the user's request. Android 12+ renders the SVG blur(2) / alpha(18,-7)
goo effect using GPU RenderEffect; Android 10/11 retain the same geometry without
that filter. Respect reduced motion, background lifecycle, disabled state and RTL.

Do not replace multi-option selectors or ordinary actions with a boolean switch.
