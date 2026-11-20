#!/usr/bin/env bash
# Runs the client GameTests in a real Minecraft window on this machine, with no GitHub Actions run needed.
#
#   scripts/client-tests.sh                          every client test
#   scripts/client-tests.sh CoordinateLeak,ServerList  only the tests whose class names start with these
#
# Screenshots land in build/run/clientGameTest/screenshots, logs in build/run/clientGameTest/logs.
# On NixOS the game's native libraries (GLFW, OpenGL, OpenAL) find nothing in /usr/lib, so they come from nixpkgs here.
# Only Mesa's drivers are offered, so the client renders on the integrated GPU and never wakes a discrete NVIDIA card.
set -euo pipefail
cd "$(dirname "$0")/.."

args=()
if [ $# -gt 0 ]; then
  args+=("-Dyolt.clientTests=$1")
fi

if [ -e /etc/NIXOS ]; then
  libs=$(nix build --no-link --print-out-paths \
    nixpkgs#libGL nixpkgs#libx11 nixpkgs#libxcursor nixpkgs#libxrandr nixpkgs#libxi nixpkgs#libxxf86vm \
    nixpkgs#libxext nixpkgs#libxkbcommon nixpkgs#wayland nixpkgs#vulkan-loader nixpkgs#openal \
    nixpkgs#alsa-lib nixpkgs#libpulseaudio nixpkgs#udev | sed 's|$|/lib|' | paste -sd:)
  export LD_LIBRARY_PATH="$libs:/run/opengl-driver/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  icd=/run/opengl-driver/share/vulkan/icd.d/radeon_icd.x86_64.json
  egl=/run/opengl-driver/share/glvnd/egl_vendor.d/50_mesa.json
  [ -e "$icd" ] && export VK_DRIVER_FILES="$icd"
  [ -e "$egl" ] && export __EGL_VENDOR_LIBRARY_FILENAMES="$egl"
  export __GLX_VENDOR_LIBRARY_NAME=mesa
fi

exec ./gradlew runProductionClientGameTest "${args[@]}"
