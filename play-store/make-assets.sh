#!/bin/bash
# Build the Google Play store graphics from raw device captures.
#
#   play-store/make-assets.sh <dir-with-raw-1080x2340-screencaps>
#
# Raw captures (adb exec-out screencap -p) expected in that dir:
#   10-remote.png 03-settings2.png 02-settings.png 04-settings3.png 01-launch.png
#
# Outputs (play-store/):
#   icon-512.png              512x512 32-bit PNG, full bleed (Play applies the mask)
#   feature-graphic.png       1024x500 24-bit PNG, no alpha
#   screenshots/phone-N.png   1080x1920 (9:16) 24-bit PNG with caption
set -euo pipefail

RAW=${1:?usage: make-assets.sh <raw-screencap-dir>}
OUT=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT/screenshots"

INK='#07090c'
INK2='#101822'
TEXT='#eef2f6'
MUTED='#9aa7b4'
HUD='#26c6da'
SERIF=/usr/share/fonts/gsfonts/P052-Bold.otf
SANS=DejaVu-Sans

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------- icon
# The launcher icon (blue disc + white D-pad) on the adaptive-icon black,
# full bleed. Play rounds the corners itself - do not pre-round.
cat >"$TMP/icon.svg" <<'EOF'
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
  <rect width="512" height="512" fill="#000"/>
  <circle cx="256" cy="256" r="196" fill="#2196F3"/>
  <circle cx="256" cy="256" r="59" fill="#fff"/>
  <path fill="#fff" d="M256 128l30 49h-60zM256 384l30-49h-60zM128 256l49-30v60zM384 256l-49-30v60z"/>
</svg>
EOF
magick -background none -density 144 "$TMP/icon.svg" -resize 512x512 PNG32:"$OUT/icon-512.png"

# ---------------------------------------------------------------- screens
# Crop the status bar (personal notification icons) and gesture bar off the
# capture, round the corners and give it a thin bezel.
frame() { # raw.png out.png height [crop-geometry] [border-colour]
    local crop=${4:-1080x2150+0+130} bc=${5:-#33414f} w h r=34 b=6
    magick "$1" -crop "$crop" +repage -resize x"$3" "$TMP/fr-in.png"
    read -r w h < <(magick identify -format '%w %h\n' "$TMP/fr-in.png")
    magick -size "${w}x${h}" xc:black -fill white \
        -draw "roundrectangle 0,0 $((w - 1)),$((h - 1)) $r,$r" "$TMP/fr-mask.png"
    magick "$TMP/fr-in.png" "$TMP/fr-mask.png" -alpha off -compose CopyOpacity -composite "$TMP/fr-round.png"
    magick -size "$((w + 2 * b))x$((h + 2 * b))" xc:none -fill "$bc" \
        -draw "roundrectangle 0,0 $((w + 2 * b - 1)),$((h + 2 * b - 1)) $((r + b)),$((r + b))" \
        "$TMP/fr-round.png" -gravity center -compose over -composite "$2"
}

# Phone screenshot: caption top, framed screen below, vertical ink gradient.
shot() { # n raw headline subline
    local n=$1 raw=$2 head=$3 sub=$4
    frame "$RAW/$raw" "$TMP/f$n.png" 1460
    magick -size 1080x1920 gradient:"$INK2"-"$INK" \
        -font "$SERIF" -fill "$TEXT" -pointsize 74 -gravity north \
        -annotate +0+120 "$head" \
        -font "$SANS" -fill "$MUTED" -pointsize 38 -annotate +0+232 "$sub" \
        "$TMP/f$n.png" -gravity north -geometry +0+352 -composite \
        -alpha off PNG24:"$OUT/screenshots/phone-$n.png"
}

shot 1 10-remote.png    "Big keys. Gloves on."          "D-pad, CAM, BACK and volume for the E6 HUD"
shot 2 10-remote.png    "Tyre pressure, live"           "Reads your TPMS sensors straight over Bluetooth"
shot 3 03-settings2.png "One-tap macros"                "Record a key sequence once, replay it anywhere"
shot 4 02-settings.png  "Works from the lock screen"    "Auto-reconnect, keep-awake and a lock-screen pad"
shot 5 04-settings3.png "Tuned for the ride"            "Media keys, voice shortcuts, battery exemption"
shot 6 01-launch.png    "Connects on its own"           "Finds the HUD you paired last and reconnects"

# Screenshot 2 zooms into the TPMS readout so it is legible as a thumbnail.
frame "$RAW/10-remote.png" "$TMP/full.png" 1460
frame "$RAW/10-remote.png" "$TMP/zoom.png" 470 1080x520+0+1460 "$HUD"
magick -size 1080x1920 gradient:"$INK2"-"$INK" \
    -font "$SERIF" -fill "$TEXT" -pointsize 74 -gravity north -annotate +0+120 "Tyre pressure, live" \
    -font "$SANS" -fill "$MUTED" -pointsize 38 -annotate +0+232 "Reads your TPMS sensors straight over Bluetooth" \
    \( "$TMP/full.png" -resize x1180 -fill "$INK" -colorize 35 \) -gravity north -geometry +0+352 -composite \
    "$TMP/zoom.png" -gravity north -geometry +0+1180 -composite \
    -alpha off PNG24:"$OUT/screenshots/phone-2.png"

# ---------------------------------------------------------------- feature graphic
# 1024x500. Key content kept off the outer edges; Play may crop or overlay.
frame "$RAW/10-remote.png" "$TMP/fg-phone.png" 620
magick -size 1024x500 gradient:"$INK2"-"$INK" \
    \( -background none -density 300 "$OUT/../website/images/logo.svg" -trim -resize 92x92 \) +density -gravity northwest -geometry +74+94 -composite \
    -font "$SERIF" -fill "$TEXT" -pointsize 58 -gravity northwest -annotate +72+206 "Remote for" \
    -annotate +72+272 "MotoEye E6 HUD" \
    -font "$SANS" -fill "$HUD" -pointsize 22 -annotate +74+362 "Glove-friendly D-pad · camera · DVR · TPMS" \
    \( "$TMP/fg-phone.png" \) -gravity northeast -geometry +86+46 -composite \
    -alpha off PNG24:"$OUT/feature-graphic.png"

identify "$OUT/icon-512.png" "$OUT/feature-graphic.png" "$OUT"/screenshots/*.png | awk '{print $1, $3, $5}'
