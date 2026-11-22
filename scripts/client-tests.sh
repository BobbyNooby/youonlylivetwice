#!/usr/bin/env bash
# Runs the client GameTests in a real Minecraft window on this machine, with no GitHub Actions run needed.
#
#   scripts/client-tests.sh                          every client test: YOLT's, then each mod's that has them, one
#                                                    window after the other
#   scripts/client-tests.sh CoordinateLeak,ServerList  only YOLT's tests whose class names start with these
#   scripts/client-tests.sh --mod hardcore           only one mod's, by its Gradle project name, against its own jar
#
# Screenshots land in build/run/clientGameTest/screenshots, logs in build/run/clientGameTest/logs; a mod's in the same
# folders under its own project, such as hardcore/build/run/clientGameTest/screenshots.
# On NixOS the game's native libraries (GLFW, OpenGL, OpenAL) find nothing in /usr/lib, so they come from nixpkgs here.
# Only Mesa's drivers are offered, so the client renders on the integrated GPU and never wakes a discrete NVIDIA card.
set -euo pipefail
cd "$(dirname "$0")/.."

# With no project, Gradle runs the task in the root and in every mod that has it; the build orders them
task=runProductionClientGameTest
args=()
if [ "${1:-}" = "--mod" ]; then
  if [ $# -ne 2 ]; then
    echo "usage: $0 --mod <project>, such as $0 --mod hardcore" >&2
    exit 2
  fi
  task=":$2:runProductionClientGameTest"
elif [ $# -gt 0 ]; then
  # The filter names YOLT's test classes, so only YOLT's run
  task=":runProductionClientGameTest"
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

exec ./gradlew "$task" "${args[@]}"
