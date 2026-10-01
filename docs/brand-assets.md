# Aliflix mobile canonical logo

`app/src/main/res/drawable/aliflix_logo.xml` is the single source of truth. Its paths were traced
from the supplied 404 by 405 pixel Untitled.png at half-coverage antialiased boundaries, with a
maximum contour simplification tolerance of 0.12 source pixels. The 100 by 100 viewport centers
the original image without changing its aspect ratio. Rear upright: #5D49C8; front: #BEB4FF;
dot: #A99AF6. The source background #07080C is excluded from the vector.

Compose static renderers and the heatmap clipping silhouette load that vector directly. Adaptive,
round and monochrome mobile icons reference the same asset with safe-zone insets. Android applies
the user's tint to monochrome launcher icons. Static Discover Ask, empty and error placements use
the original flat colors. The launch and Ask header keep their existing heatmap sweep, pulse,
timing, reduced-motion handling and layout, clipped to the new geometry.

The audit covered AliflixApp, AskAliflixHeader, DiscoverScreen/DiscoverAskCard, NativePlayerScreen,
launcher resources, manifests, launch renderer and geometry tests. DiscoverScreen delegates to
DiscoverAskCard; NativePlayerScreen has no separate brand geometry in v3.1.118. The old blade
coordinates and their obsolete test were removed. Only the mobile app is released. TV-only banner
and the legacy main launcher fallback used by TV remain unchanged under the mobile-only scope.

In v3.1.132, animated launch and Ask renderers paint the canonical vector first, then
composite the moving sweep/pulse/band at reduced alpha. Previously, the opaque
heatmap base replaced the original #5D49C8 rear, #BEB4FF front and #A99AF6 dot
as soon as animation started. Geometry, movement and timings remain unchanged.
