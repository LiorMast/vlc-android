#!/bin/bash
#
# Build VLC for Android from this vlc-android checkout, install it with adb (Windows' adb.exe
# under WSL) and back up / restore VLC's data on the device. Run ./build-apk.sh --help for details.

set -euo pipefail
shopt -s nullglob

CMDLINE_TOOLS_BUILD=13114758
# as in upstream's vlc-debian-android image, which CI builds the 64-bit LibVLC with
NDK_VERSION=29.0.14206865

SETTINGS_EXPORT=/sdcard/vlc_exported_settings.json
SNAPSHOT_DIRS=(app_db databases shared_prefs)
AUTO_SNAPSHOTS_KEPT=10

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
BACKUP_DIR=${VLC_BACKUP_DIR:-$(dirname -- "$ROOT")/vlc-backups}
DEBUG_KEY=${ANDROID_USER_HOME:-$HOME/.android}/debug.keystore

# the package name build.gradle sets; debug builds add .debug
APP_ID=$(sed -nE '/^[[:space:]]*appId = "/{s/.*appId = "([^"]+)".*/\1/p;q}' "$ROOT/build.gradle" 2>/dev/null) || true
APP_ID=${APP_ID:-org.videolan.vlc}
DEBUG_APP_ID=$APP_ID.debug

release=0 clean=0 install=0 run=0
action=build restore_file= build_host=${VLC_BUILD_HOST:-}
gradle_args=() signing_args=()
keystore_password=

if [[ -t 1 ]]; then
  bold=$'\e[1m' yellow=$'\e[1;33m' red=$'\e[1;31m' reset=$'\e[0m'
else
  bold='' yellow='' red='' reset=''
fi
info() { echo "${bold}==> $*${reset}"; }
warn() { echo "${yellow}warning:${reset} $*" >&2; }
die() { echo "${red}error:${reset} $*" >&2; exit 1; }

migration_recipe() {
  cat <<EOF
Release builds keep the official package name ($APP_ID) but are signed with
your own key, so they cannot update the official VLC from the Play Store, F-Droid or
videolan.org: it has to be uninstalled first. Debug builds ($DEBUG_APP_ID)
install next to it instead. To switch:
  1. In the official VLC: Settings > Advanced > Export settings.
  2. ./build-apk.sh --backup   (copies that export to this PC)
  3. ${ADB:-adb.exe} uninstall $APP_ID   (removes the official app and its data)
  4. ./build-apk.sh --release --install
  5. In your build: Settings > Advanced > Restore settings, pick vlc_exported_settings.json.
The export holds settings and equalizers only: VLC scans your media again, but playlists,
history and resume points start empty. For a debug build, steps 1 and 5 are enough.
EOF
}

usage() {
  cat <<EOF
Usage:
  ./build-apk.sh [options] [-- gradle args]   build VLC (the debug build by default)
  ./build-apk.sh --backup                     copy VLC's data from the device to this PC
  ./build-apk.sh --restore FILE               put a backup back (.tar snapshot or .json
                                              settings export)

Build options:
  --release   release build: $APP_ID, one APK per ABI, signed with your own key
              (default: debug build, $DEBUG_APP_ID, installs next to the official VLC)
  --clean     clean every module first
  --install   install the APK matching the connected device (adb install -r)
  --run       start VLC once installed (implies --install)
  --remote HOST  build on another Linux machine over ssh (default: \$VLC_BUILD_HOST) and copy
              the APKs back; --install and --run still use the device attached here

Remote builds sync this checkout to HOST:\$VLC_REMOTE_DIR with rsync and run this script
there, so HOST needs key-based ssh login, rsync, git, curl, unzip and a JDK 17+ (e.g. sudo
apt install rsync git curl unzip default-jdk-headless); the script installs the Android
SDK there itself. Signing keys travel both ways through $BACKUP_DIR/signing.

The app is built against the prebuilt LibVLC and medialibrary from Maven that build.gradle
names; changing their native code needs upstream's buildsystem/compile.sh. The first build
installs the SDK packages it needs and runs buildsystem/compile.sh --init (clones libvlcjni,
sets up the Gradle wrapper); the first --release build creates your signing key. APKs are
copied to out/.

Backups go to $BACKUP_DIR
  --backup saves VLC's own settings export (Settings > Advanced > Export settings) when it is
  on the device, plus a snapshot of the debug build's media library, databases and settings
  (not its thumbnails: VLC makes them again). Release builds refuse run-as, so only their
  settings export can be saved.
  --install snapshots an installed debug build first (the last $AUTO_SNAPSHOTS_KEPT are kept in auto/).
  Backups hold your library, history, network favorites and settings: keep them private.

Environment:
  ANDROID_SDK_ROOT, ANDROID_HOME  Android SDK (default ~/Android/Sdk)
  JAVA_HOME                       JDK 17+ (default: found in /usr/lib/jvm)
  VLC_SIGNING_PROPS               release signing settings (default ~/.android/vlc-keystore.properties)
  VLC_BACKUP_DIR                  backup directory
  VLC_BUILD_HOST                  [user@]host to build on (empty: build here)
  VLC_REMOTE_DIR                  checkout on the build host, relative to its home
                                  (default vlc-android)
  ADB, ANDROID_SERIAL             adb to use (default: Windows' adb.exe when it is on PATH,
                                  else adb) and device

$(migration_recipe)
EOF
}

# --- build environment -------------------------------------------------------------

check_build_host() {
  if [[ $ROOT =~ [[:space:]] ]]; then
    die "this checkout's path contains spaces: $ROOT
  buildsystem/compile.sh cannot handle it: clone the repo to a path without spaces."
  fi
  [[ $ROOT != /mnt/[a-z]/* ]] || warn "the checkout is on a Windows drive: builds through WSL's 9p mount are slow"
  local tool missing=() mem_gib
  for tool in git curl unzip; do
    command -v "$tool" >/dev/null || missing+=("$tool")
  done
  ((${#missing[@]} == 0)) || die "missing tools, install them with: sudo apt install ${missing[*]}"
  mem_gib=$(awk '/^MemTotal:/ {print int($2 / 1048576)}' /proc/meminfo 2>/dev/null) || mem_gib=
  if [[ $mem_gib =~ ^[0-9]+$ ]] && ((mem_gib < 8)); then
    warn "only $mem_gib GB of memory: Gradle runs with -Xmx4g (gradle.properties) next to the Kotlin
  daemon, so the build may swap or be killed. Under WSL, raise memory= in the [wsl2] section of
  %UserProfile%\\.wslconfig."
  fi
}

setup_java() {
  local jdk version
  for jdk in ${JAVA_HOME:+"$JAVA_HOME"} /usr/lib/jvm/java-17-* /usr/lib/jvm/java-2[0-9]-*; do
    [[ -x $jdk/bin/javac ]] || continue
    version=$("$jdk/bin/javac" -version 2>&1 | sed -nE 's/^javac ([0-9]+).*/\1/p')
    if [[ $version =~ ^[0-9]+$ ]] && ((version >= 17)); then
      export JAVA_HOME=$jdk PATH=$jdk/bin:$PATH
      return
    fi
  done
  die "no JDK 17+ found, install one with: sudo apt install default-jdk-headless"
}

setup_sdk() {
  SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}
  [[ ! $SDK =~ [[:space:]] ]] || die "the Android SDK path contains spaces: $SDK"
  local sdkmanager=$SDK/cmdline-tools/latest/bin/sdkmanager
  if [[ ! -x $sdkmanager ]]; then
    info "Installing the Android command-line tools in $SDK"
    local tmp
    tmp=$(mktemp -d)
    curl -fL --progress-bar -o "$tmp/tools.zip" \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
  fi
  # the NDK: buildsystem/compile.sh --init refuses to run without one, and AGP strips the
  # prebuilt native libraries with it. Platform and build tools as settings.gradle sets them.
  local compile_sdk build_tools packages=()
  compile_sdk=$(sed -nE 's/^[[:space:]]*compileSdk = ([0-9]+).*/\1/p' settings.gradle)
  build_tools=$(sed -nE "s/^[[:space:]]*buildToolsVersion = '([0-9.]+)'.*/\1/p" settings.gradle)
  [[ -d $SDK/platform-tools ]] || packages+=(platform-tools)
  [[ -d $SDK/ndk/$NDK_VERSION ]] || packages+=("ndk;$NDK_VERSION")
  [[ -z $compile_sdk || -d $SDK/platforms/android-$compile_sdk ]] || packages+=("platforms;android-$compile_sdk")
  [[ -z $build_tools || -d $SDK/build-tools/$build_tools ]] || packages+=("build-tools;$build_tools")
  if ((${#packages[@]})); then
    info "Installing Android SDK packages: ${packages[*]}"
    { yes || true; } | "$sdkmanager" --sdk_root="$SDK" --licenses >/dev/null
    "$sdkmanager" --sdk_root="$SDK" "${packages[@]}"
  fi
  # Gradle takes the SDK from ANDROID_HOME, buildsystem/compile.sh from ANDROID_SDK and ANDROID_NDK
  export ANDROID_HOME=$SDK ANDROID_SDK_ROOT=$SDK ANDROID_SDK=$SDK ANDROID_NDK=$SDK/ndk/$NDK_VERSION
}

# --- sources -----------------------------------------------------------------------

# The libvlcjni commit buildsystem/compile.sh pins for VLC 3 (the other one is for VLC 4)
pinned_libvlcjni() {
  awk -F= '/^[[:space:]]*LIBVLCJNI_TESTED_HASH=/ {hash = $2}
    /^[[:space:]]*LIBVLCJNI_BRANCH="libvlcjni-3\.x"/ {print hash; exit}' buildsystem/compile.sh
}

# buildsystem/compile.sh --init writes local.properties, clones libvlcjni (settings.gradle
# includes its libvlc module) and generates the Gradle wrapper. It regenerates the wrapper on
# every run, so it only runs when one of them is missing or stale.
prepare_sources() {
  local gradle_version wrapper_version='' pinned head
  gradle_version=$(sed -nE 's/^GRADLE_VERSION=([0-9.]+).*/\1/p' buildsystem/compile.sh)
  if [[ -f gradle/wrapper/gradle-wrapper.properties ]]; then
    wrapper_version=$(sed -nE 's#^distributionUrl=.*/gradle-([0-9.]+)-(bin|all)\.zip.*#\1#p' \
      gradle/wrapper/gradle-wrapper.properties)
  fi
  if [[ ! -x gradlew || ! -d libvlcjni/.git || $wrapper_version != "$gradle_version" ]] ||
    ! grep -qxF "sdk.dir=$ANDROID_SDK" local.properties 2>/dev/null ||
    ! grep -qxF "android.ndkPath=$ANDROID_NDK" local.properties 2>/dev/null; then
    info "Preparing the sources: buildsystem/compile.sh --init"
    # a VLC_SRC_DIR skips cloning VLC itself, which only native builds use
    VLC_SRC_DIR=${VLC_SRC_DIR:-$ROOT/libvlcjni/vlc} ./buildsystem/compile.sh --init
  fi
  pinned=$(pinned_libvlcjni)
  head=$(git -C libvlcjni rev-parse HEAD)
  if [[ -n $pinned && $head != "$pinned" ]]; then
    warn "libvlcjni is at ${head:0:10} but buildsystem/compile.sh pins ${pinned:0:10}. Update it with:
  git -C libvlcjni fetch origin && git -C libvlcjni merge --ff-only $pinned"
  fi
}

# --- signing -----------------------------------------------------------------------

# Release builds use the signedRelease build type, which application/app/build.gradle signs
# with the keyStoreFile and storealias properties and the PASSWORD_KEYSTORE variable.
setup_signing() {
  local props=${VLC_SIGNING_PROPS:-$HOME/.android/vlc-keystore.properties}
  local keystore=$HOME/.android/vlc-release.jks saved=$BACKUP_DIR/signing
  local store_file key_alias
  if [[ ! -f $props && ! -e $keystore && -f $saved/vlc-release.jks && -f $saved/vlc-keystore.properties ]]; then
    mkdir -p "$(dirname -- "$keystore")" "$(dirname -- "$props")"
    cp "$saved/vlc-release.jks" "$keystore"
    (umask 077 && cp "$saved/vlc-keystore.properties" "$props")
    info "Restored your signing key $keystore and $props from $saved"
  fi
  if [[ ! -f $props ]]; then
    [[ ! -e $keystore ]] || die "$keystore exists but its settings file $props is missing.
  Restore it (or point VLC_SIGNING_PROPS at it); an existing key is never overwritten."
    info "Creating your signing key $keystore"
    local password
    password=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
    mkdir -p "$(dirname -- "$keystore")" "$(dirname -- "$props")"
    VLC_KEYSTORE_PASSWORD=$password keytool -genkeypair -noprompt -keystore "$keystore" \
      -storetype PKCS12 -alias vlc -keyalg RSA -keysize 4096 -validity 10000 \
      -dname "CN=VLC for Android personal build" \
      -storepass:env VLC_KEYSTORE_PASSWORD -keypass:env VLC_KEYSTORE_PASSWORD
    (
      umask 077
      printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=vlc\n' "$keystore" "$password" >"$props"
    )
    mkdir -p "$saved"
    cp "$keystore" "$saved/"
    cp "$props" "$saved/vlc-keystore.properties"
    warn "keep $keystore and $props (copies are in $saved):
  without them, new release builds cannot update the installed app."
  fi
  store_file=$(sed -n 's/^storeFile=//p' "$props")
  key_alias=$(sed -n 's/^keyAlias=//p' "$props")
  keystore_password=$(sed -n 's/^storePassword=//p' "$props")
  [[ -f $store_file && -n $key_alias && -n $keystore_password ]] ||
    die "$props needs storeFile (an existing keystore), storePassword and keyAlias"
  signing_args=(-PkeyStoreFile="$store_file" -Pstorealias="$key_alias")
}

# Debug builds are signed with the debug key Gradle creates on first use, and an installed
# debug build only takes updates signed with the key it was installed with: keep a copy.
restore_debug_key() {
  local saved=$BACKUP_DIR/signing/debug.keystore
  if [[ ! -f $DEBUG_KEY && -f $saved ]]; then
    mkdir -p "$(dirname -- "$DEBUG_KEY")"
    cp "$saved" "$DEBUG_KEY"
    info "Restored the debug signing key $DEBUG_KEY from $saved"
  fi
}

save_debug_key() {
  local saved=$BACKUP_DIR/signing/debug.keystore
  [[ -f $DEBUG_KEY ]] || return 0
  if [[ ! -f $saved ]]; then
    mkdir -p "$(dirname -- "$saved")"
    cp "$DEBUG_KEY" "$saved"
    info "Saved a copy of the debug signing key $DEBUG_KEY as $saved"
  elif ! cmp -s "$DEBUG_KEY" "$saved"; then
    warn "$DEBUG_KEY differs from the copy in $saved: a debug build installed with that
  key refuses this build. To go back to it: cp '$saved' '$DEBUG_KEY', then build again."
  fi
}

# --- build -------------------------------------------------------------------------

build_apks() {
  local variant=debug
  if ((release)); then variant=signedRelease; fi
  local outputs=application/app/build/outputs/apk/$variant
  local -a args env_args=() apks
  local apk
  if ((clean)); then
    info "Cleaning: ./gradlew clean"
    ./gradlew clean
  fi
  # -p builds only the app and what it depends on. The task name stays bare: release builds
  # are split per ABI when it matches assemble*Release (application/app/build.gradle).
  args=(-p application/app "assemble${variant^}" "${signing_args[@]}" "${gradle_args[@]}")
  if [[ -n $keystore_password ]]; then env_args=("PASSWORD_KEYSTORE=$keystore_password"); fi

  rm -rf "$outputs"
  info "Building: ./gradlew ${args[*]}"
  env "${env_args[@]}" ./gradlew "${args[@]}"

  apks=("$outputs"/*.apk)
  ((${#apks[@]})) || die "the build produced no APK in $outputs"
  mkdir -p out
  rm -f out/*.apk
  cp "${apks[@]}" out/
  list_apks
  if ((!release)) && [[ ! -d application/remote-access-client/remoteaccess/dist ]]; then
    echo "Note: this debug build has no remote access web page: build it once with"
    echo "./buildsystem/compile-remoteaccess.sh (needs node and npm). Release builds take it from Maven."
  fi
}

list_apks() {
  local apk where=$ROOT/out
  if command -v wslpath >/dev/null; then where+=" ($(wslpath -w "$ROOT/out"))"; fi
  info "APKs in $where:"
  for apk in out/*.apk; do
    printf '  %6s  %s\n' "$(du -h "$apk" | cut -f1)" "${apk#out/}"
  done
}

# --- remote build ------------------------------------------------------------------

# Sync the checkout to the build host, run this script there and bring the APKs back. Files
# the remote build makes for itself (libvlcjni, Gradle wrapper, local.properties, outputs)
# are not synced; .git is, because the build reads the revision from it.
remote_build() {
  local rdir=${VLC_REMOTE_DIR:-vlc-android} rbackup tool cmd
  local -a ssh_cmd forward=() tty=() excludes
  rbackup=$(dirname -- "$rdir")/vlc-backups
  for tool in ssh rsync; do
    command -v "$tool" >/dev/null || die "$tool not found, install it with: sudo apt install ${tool/ssh/openssh-client}"
  done
  mkdir -p -m 700 "$HOME/.ssh"
  # one ssh connection for all the steps below
  ssh_cmd=(ssh -o ControlMaster=auto -o "ControlPath=$HOME/.ssh/vlc-build-%C" -o ControlPersist=120)
  excludes=(--exclude=/out/ --exclude=/libvlcjni/ --exclude=/gradle/ --exclude='/gradle-*/'
    --exclude=/gradlew --exclude=/gradlew.bat --exclude=/local.properties --exclude=.gradle/
    --exclude=build/ --exclude=.kotlin/ --exclude=.idea/ --exclude=node_modules/)

  local probe arch missing
  probe=$("${ssh_cmd[@]}" "$build_host" "uname -m; for t in rsync git curl unzip javac; do
      command -v \$t >/dev/null || printf '%s ' \$t; done; echo
    mkdir -p $(printf %q "$rdir") $(printf %q "$rbackup/signing")")
  arch=$(sed -n 1p <<<"$probe")
  missing=$(sed -n 2p <<<"$probe")
  # Google ships the Linux SDK tools (aapt2, the NDK) for x86_64 only
  [[ $arch == x86_64 ]] || die "$build_host is an $arch machine, but Android's Linux build tools
  (aapt2, the NDK) only exist for x86_64: build on an x86_64 machine instead"
  [[ -z $missing ]] || die "$build_host lacks: $missing
  Install them there with: sudo apt install rsync git curl unzip default-jdk-headless"
  info "Syncing the sources to $build_host:$rdir"
  # files on a Windows drive all look executable; .git/config keeps git ignoring file modes
  rsync -rlt --delete --chmod=D755,F755 -e "${ssh_cmd[*]}" "${excludes[@]}" "$ROOT/" "$build_host:$rdir/"
  if [[ -d $BACKUP_DIR/signing ]]; then
    rsync -rt --ignore-existing --chmod=D700,F600 -e "${ssh_cmd[*]}" \
      "$BACKUP_DIR/signing/" "$build_host:$rbackup/signing/"
  fi

  if ((release)); then forward+=(--release); fi
  if ((clean)); then forward+=(--clean); fi
  if ((${#gradle_args[@]})); then forward+=(-- "${gradle_args[@]}"); fi
  cmd="cd $(printf %q "$rdir") && VLC_BUILD_HOST= ./build-apk.sh"
  if ((${#forward[@]})); then cmd+=" $(printf '%q ' "${forward[@]}")"; fi
  if [[ -t 0 && -t 1 ]]; then tty=(-t); fi
  info "Building on $build_host"
  "${ssh_cmd[@]}" "${tty[@]}" "$build_host" "$cmd"

  mkdir -p "$BACKUP_DIR/signing" out
  rsync -rt --ignore-existing --chmod=D700,F600 -e "${ssh_cmd[*]}" \
    "$build_host:$rbackup/signing/" "$BACKUP_DIR/signing/"
  rsync -rt --delete -e "${ssh_cmd[*]}" "$build_host:$rdir/out/" "$ROOT/out/"
  list_apks
}

# --- device ------------------------------------------------------------------------

# Every adb call goes through here: WSL does not hand ANDROID_SERIAL to adb.exe (it is not in WSLENV)
device() { "$ADB" ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} "$@"; }

setup_adb() {
  if [[ -z ${ADB:-} ]]; then
    # under WSL, Windows' adb sees USB devices and the Windows network directly
    if command -v adb.exe >/dev/null; then
      ADB=adb.exe
    elif command -v adb >/dev/null; then
      ADB=adb
    else
      ADB=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}/platform-tools/adb
    fi
  fi
  command -v "$ADB" >/dev/null || die "adb not found: install it (sudo apt install adb) or set ADB"
  local state
  if ! state=$(device get-state 2>&1); then
    die "no device reachable over $ADB: ${state//$'\r'/}
  USB: plug the phone into the PC and allow USB debugging (Windows' adb.exe sees it directly).
  Wireless debugging: enable it on the phone, then $ADB pair <ip:port> and $ADB connect <ip:port>.
  Several devices: pick one with ANDROID_SERIAL=<serial> (listed by $ADB devices)."
  fi
}

# adb.exe (Windows) needs Windows paths for files on this side
host_path() {
  if [[ $ADB == *.exe ]]; then
    echo "$(wslpath -w "$(dirname -- "$1")")\\$(basename -- "$1")"
  else
    echo "$1"
  fi
}

remote_test() { device shell "test $1 '$2'" >/dev/null 2>&1; }
installed_app() { [[ -n $(device shell pm path "$1" 2>/dev/null) ]]; }
app_debuggable() { device shell run-as "$1" true >/dev/null 2>&1; }

app_version() {
  local dump
  dump=$(device shell dumpsys package "$1" 2>/dev/null) || return 0
  sed -nE '/^ *versionName=/{s/^ *versionName=([^[:space:]]+).*/\1/p;q}' <<<"$dump"
}

# Top-level folders of a tar listing, one per line
top_dirs() { cut -d/ -f1 <<<"$1" | sort -u; }

# A snapshot holds some of SNAPSHOT_DIRS and nothing else
is_snapshot() {
  local dir found=0
  while read -r dir; do
    [[ -n $dir ]] || continue
    [[ " ${SNAPSHOT_DIRS[*]} " == *" $dir "* ]] || return 1
    found=1
  done < <(top_dirs "$1")
  ((found))
}

# Media library, databases and settings of the installed debug build (run-as only works for
# debuggable apps). Thumbnails are left out: VLC makes missing ones again.
snapshot() {
  local dir=$BACKUP_DIR file listing version old
  [[ ${1:-} != auto ]] || dir=$BACKUP_DIR/auto
  mkdir -p "$dir"
  version=$(app_version "$DEBUG_APP_ID")
  file=$dir/vlc-snapshot_$(date +%Y%m%d-%H%M%S)_${version:-unknown}.tar
  device shell am force-stop "$DEBUG_APP_ID"
  device exec-out "run-as $DEBUG_APP_ID sh -c 'tar -cf - \$(ls -d ${SNAPSHOT_DIRS[*]} 2>/dev/null)'" >"$file"
  listing=$(tar -tf "$file" 2>/dev/null) || true
  if ! is_snapshot "$listing"; then
    rm -f "$file"
    warn "found no VLC data to snapshot"
    return 1
  fi
  info "Saved $file ($(du -h "$file" | cut -f1))"
  if [[ ${1:-} == auto ]]; then
    old=("$dir"/vlc-snapshot_*.tar)
    if ((${#old[@]} > AUTO_SNAPSHOTS_KEPT)); then
      rm -f -- "${old[@]:0:${#old[@]}-AUTO_SNAPSHOTS_KEPT}"
    fi
  fi
}

backup_device() {
  local saved=0 mtime stamp file
  mkdir -p "$BACKUP_DIR"
  if remote_test -f "$SETTINGS_EXPORT"; then
    mtime=$(device shell stat -c %Y "$SETTINGS_EXPORT" | tr -d '\r') || true
    [[ $mtime =~ ^[0-9]+$ ]] || mtime=$(date +%s)
    stamp=$(date -d "@$mtime" +%Y%m%d-%H%M%S)
    file=$BACKUP_DIR/vlc-settings_$stamp.json
    device pull "$SETTINGS_EXPORT" "$(host_path "$BACKUP_DIR/.settings.json")" >/dev/null
    mv -f "$BACKUP_DIR/.settings.json" "$file"
    info "Saved VLC's settings export from $(date -d "@$mtime" '+%F %R') as $file"
    if (($(date +%s) - mtime > 86400)); then
      warn "that export is more than a day old: tap Export settings in VLC again for a fresh one, then rerun --backup"
    fi
    saved=1
  fi
  if installed_app "$DEBUG_APP_ID" && app_debuggable "$DEBUG_APP_ID" && snapshot; then saved=1; fi
  ((saved)) || die "nothing to back up. In VLC: Settings > Advanced > Export settings, then run
  --backup again. Snapshots of the media library need your debug build ($DEBUG_APP_ID) installed."
  echo "Backups hold your library, history, network favorites and settings: keep them private."
}

restore_snapshot() {
  local file=$1 listing dirs current tmp=/data/local/tmp/vlc-restore.tar
  listing=$(tar -tf "$file" 2>/dev/null) || die "$file is not a tar archive"
  is_snapshot "$listing" || die "$file is not a VLC snapshot (it may only hold ${SNAPSHOT_DIRS[*]})"
  installed_app "$DEBUG_APP_ID" || die "your debug build ($DEBUG_APP_ID) is not installed: run ./build-apk.sh --install first"
  app_debuggable "$DEBUG_APP_ID" || die "the installed $DEBUG_APP_ID is not debuggable, so snapshots cannot be restored into it"
  current=$(app_version "$DEBUG_APP_ID")
  if [[ $(basename -- "$file") =~ ^vlc-snapshot_[0-9]{8}-[0-9]{6}_(.+)\.tar$ ]] &&
    [[ -n $current && ${BASH_REMATCH[1]} != unknown && ${BASH_REMATCH[1]} != "$current" ]]; then
    warn "this snapshot comes from VLC ${BASH_REMATCH[1]} and the installed build is $current:
  an older VLC may not open the media library of a newer one"
  fi
  dirs=$(top_dirs "$listing" | tr '\n' ' ')
  info "Snapshotting the current data first"
  snapshot auto || true
  device push "$(host_path "$file")" "$tmp" >/dev/null
  device shell am force-stop "$DEBUG_APP_ID"
  if ! device shell "run-as $DEBUG_APP_ID rm -rf $dirs && cat $tmp | run-as $DEBUG_APP_ID tar -xf -"; then
    device shell rm -f "$tmp"
    die "restoring $file failed"
  fi
  device shell rm -f "$tmp"
  info "Restored $(basename -- "$file"): start VLC to use it"
}

# VLC reads its settings export back through Settings > Advanced > Restore settings
restore_settings() {
  local file=$1 kept
  [[ $(head -c 1 -- "$file") == "{" ]] || die "$file is not a VLC settings export"
  if remote_test -f "$SETTINGS_EXPORT"; then
    mkdir -p "$BACKUP_DIR"
    kept=$BACKUP_DIR/vlc-settings_$(date +%Y%m%d-%H%M%S)_pre-restore.json
    device pull "$SETTINGS_EXPORT" "$(host_path "$kept")" >/dev/null
    info "Saved the settings export that was on the device as $kept"
  fi
  device push "$(host_path "$file")" "$SETTINGS_EXPORT" >/dev/null
  info "Copied $(basename -- "$file") to the device as $SETTINGS_EXPORT"
  echo "Now in VLC: Settings > Advanced > Restore settings, and pick vlc_exported_settings.json"
  echo "at the root of the internal storage."
}

install_apk() {
  local pkg=$DEBUG_APP_ID apks=(out/*.apk) apk='' abis abi log
  local -a matches
  if ((release)); then pkg=$APP_ID; fi
  if ((${#apks[@]} == 1)); then
    apk=${apks[0]}
  else
    abis=$(device shell getprop ro.product.cpu.abilist | tr -d '\r')
    for abi in ${abis//,/ }; do
      matches=(out/*-"$abi".apk)
      if ((${#matches[@]})); then
        apk=${matches[0]}
        break
      fi
    done
    [[ -n $apk ]] || die "no APK in out/ fits the device (ABIs: $abis)"
  fi
  if installed_app "$pkg"; then
    if [[ $pkg == "$DEBUG_APP_ID" ]] && app_debuggable "$pkg"; then
      info "Snapshotting the installed debug build's data first"
      snapshot auto || true
    else
      echo "The installed $pkg is not debuggable, so its data cannot be snapshotted from here."
      echo "For a copy of its settings: Settings > Advanced > Export settings, then ./build-apk.sh --backup."
    fi
  fi
  info "Installing $(basename -- "$apk")"
  if log=$(device install -r "$(host_path "$ROOT/$apk")" 2>&1); then
    echo "$log" | tail -n 1
    return
  fi
  echo "$log" >&2
  case $log in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
      echo
      if [[ $pkg == "$DEBUG_APP_ID" ]]; then
        echo "The installed $pkg was signed with another debug key (built on another PC, or before"
        echo "$DEBUG_KEY was recreated). Put that key back in $DEBUG_KEY, or move the data over:"
        echo "$ADB uninstall $pkg, ./build-apk.sh --install, then ./build-apk.sh --restore with the"
        echo "snapshot just saved in $BACKUP_DIR/auto."
      else
        migration_recipe
      fi
      ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*)
      echo
      echo "The installed $pkg has a higher versionCode (from a newer checkout)."
      echo "A debug build can be downgraded with: $ADB install -r -d <apk>"
      ;;
  esac
  exit 1
}

launch_app() {
  local pkg=$DEBUG_APP_ID
  if ((release)); then pkg=$APP_ID; fi
  device shell am start -n "$pkg/org.videolan.vlc.StartActivity" >/dev/null
  info "Started $pkg"
}

# --- main --------------------------------------------------------------------------

while (($#)); do
  case $1 in
    --release) release=1 ;;
    --clean) clean=1 ;;
    --install) install=1 ;;
    --run) install=1 run=1 ;;
    --remote)
      (($# > 1)) || die "--remote needs a host"
      build_host=$2
      shift
      ;;
    --backup) action=backup ;;
    --restore)
      (($# > 1)) || die "--restore needs a file"
      action=restore
      restore_file=$(realpath -m -- "$2")
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    --)
      shift
      gradle_args=("$@")
      break
      ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
  shift
done

[[ -f $ROOT/buildsystem/compile.sh ]] && grep -q "':application:app'" "$ROOT/settings.gradle" 2>/dev/null ||
  die "$ROOT is not a vlc-android checkout"
cd "$ROOT"

case $action in
  backup)
    setup_adb
    backup_device
    ;;
  restore)
    [[ -f $restore_file ]] || die "no such file: $restore_file"
    setup_adb
    case $restore_file in
      *.tar) restore_snapshot "$restore_file" ;;
      *.json) restore_settings "$restore_file" ;;
      *) die "expected a snapshot (.tar) or a settings export (.json): $restore_file" ;;
    esac
    ;;
  build)
    if [[ -n $build_host ]]; then
      if ((install)); then setup_adb; fi
      remote_build
      if ((install)); then install_apk; fi
      if ((run)); then launch_app; fi
      exit 0
    fi
    check_build_host
    if ((install)); then setup_adb; fi
    setup_java
    setup_sdk
    prepare_sources
    if ((release)); then setup_signing; else restore_debug_key; fi
    build_apks
    if ((!release)); then save_debug_key; fi
    if ((install)); then install_apk; fi
    if ((run)); then launch_app; fi
    ;;
esac
