# Whelk — Android launcher icon

Copy the `res/` folder into `app/src/main/` (merge with existing mipmap folders), then make sure the manifest has:

    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"

- `mipmap-anydpi-v26/` — adaptive icon (Android 8+): background, foreground and monochrome layers (monochrome is used for themed icons on Android 13+).
- `mipmap-*dpi/ic_launcher_*.png` — layers at 108dp per density (mdpi 108px → xxxhdpi 432px). The visible area is the central 72dp; all artwork sits inside the 66dp safe circle.
- `mipmap-*dpi/ic_launcher.png` / `ic_launcher_round.png` — legacy icons for Android 7 and below (48dp).
- `source/` — the three layers as SVG (108dp canvas).
- Play Store listing icon: use whelk-icon-512.png from the project root.
