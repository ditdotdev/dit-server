#!/usr/bin/env bash
#
# Copyright Datadatdat.
#

#
# Minimum ZFS version. Starting in version 0.8.0, the community is going to attempt to maintain
# backwards compatibility, such that newer versions of the utilities will continue to run against
# older versions of the kernel modules.
min_zfs_version=2.0.0

# Configurable system paths for testing. Default to real paths.
: "${ZFS_PROC_FILESYSTEMS:=/proc/filesystems}"
: "${ZFS_SYS_MODULE_VERSION:=/sys/module/zfs/version}"
: "${ZFS_PROC_CONFIG_GZ:=/proc/config.gz}"

# S3 bucket for prebuilt ZFS kernel modules
ZFS_MODULES_BUCKET="datadatdat-zfs-builds.s3-website-us-west-2.amazonaws.com"


#
# While exact semver-style semantics have not been declared, we will adopt a semver style
# comparison for determining compatibility:
#
#   * Major version must be equivalent
#   * Minor version must be greater than or equal
#   * Patch or additional qualifiers (e.g. "-rc0") are ignored.
#
# The minimum version should therefore only be expressed as a major/minor pair and not include
# the patch version. In the event that the community decides to go a different direction with
# versioning compatibility, we will have to revisit this check. This function also treats the
# empty string as an incompatible version, to simplify callers that may get an empty version
# when checking the running system or filesystem modules.
#
# While it may "just work", there is no guarantee that an older zfs userland will work on a newer
# kernel, so we fail if the current kernel is newer (even with minor versions) than our
# userland.
#
function zfs_version_compatible() {
  [[ -z "$1" ]] && return 1
  local min_components
  IFS='.' read -ra min_components <<< "$min_zfs_version"
  local req_version=${1%-*}                       # Trim any trailing "-XYZ" modifier
  local req_components
  IFS='.' read -ra req_components <<< "$req_version"

  # The major version (0.*) doesn't match, fail
  [[ ${min_components[0]} -ne ${req_components[0]} ]] && return 1

  # The current version is less than the minimum version, fail
  [[ ${min_components[1]} -gt ${req_components[1]} ]] && return 1

  return 0
}


#
# Determine if the ZFS module is currently loaded. To do this, we look at lsmod output, looking for
# the ZFS module. Technically, ZFS is comprised of multiple modules such as zfs and spl, but the
# 'zfs' depends on all the others, so if it is loaded then we should be good.
#
function is_zfs_loaded() {
  # Check for ZFS as a loadable module
  lsmod | grep "^zfs " >/dev/null 2>&1 && return 0
  # Check for ZFS built into the kernel (e.g., custom WSL2 kernel with CONFIG_ZFS=y)
  grep -q "^nodev.*zfs" "$ZFS_PROC_FILESYSTEMS" 2>/dev/null && return 0
  return 1
}

#
# Get the current running ZFS module, or return the empty string if the ZFS module is not currently
# loaded. This information is stored in /sys/module/zfs/version, and is available from within
# a container.
#
function get_running_zfs_version() {
  # Module version file (standard loadable modules)
  cat "$ZFS_SYS_MODULE_VERSION" 2>/dev/null && return 0
  # For built-in ZFS, try the zfs version command
  zfs version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1
}

#
# Get the the ZFS module version from modules on the filesystem. This uses modinfo(8) to fetch
# the information from a directory specified as an argument. We first run depmod(8) in case we
# haven't created the requisite links yet (e.g. this was just built from source)
#
function get_filesystem_zfs_version() {
  local directory=$1
  depmod -b "$directory" >/dev/null 2>&1
  modinfo -F version -b "$directory" zfs 2>/dev/null
}

#
# Load ZFS module from a specific directory. This will use depmod(8) to ensure we have our
# dependencies built, and then modprobe(8) to actually load the modules. This does not generate
# any output, but will return success if the commands succeed.
#
function load_zfs_module() {
  local directory=$1

  # First check if ZFS is already built into the kernel
  if grep -q "^nodev.*zfs" /proc/filesystems 2>/dev/null; then
    echo "ZFS is built into the kernel"
    check_zfs_device  # Ensure /dev/zfs exists
    return 0
  fi

  # Try loading as a module
  echo "Running depmod to update module dependencies..."
  depmod -b "$directory" 2>&1 | head -5

  echo "Attempting to load ZFS module from $directory..."
  echo "Module directory contents:"
  find "$directory/lib/modules/$(uname -r)" -name "*.ko" 2>/dev/null | head -10

  if ! modprobe -v -d "$directory" zfs 2>&1; then
    echo "modprobe failed - returning error"
    return 1
  fi

  echo "modprobe succeeded"
  return 0
}


#
# Check for /dev/zfs and create it if it exists. Device links are created at the time the container
# launches, so if we install the ZFS kernel module within the kernel we have to come back and
# create it manually. It will be present the next time the container starts.
#
function check_zfs_device() {
  if [[ ! -e /dev/zfs ]]; then
      # Intentional word splitting: mknod needs separate major minor args
      # shellcheck disable=SC2046
      mknod -m 660 /dev/zfs c $(sed 's/:/ /g' < /sys/class/misc/zfs/dev) >/dev/null 2>&1
  fi
}

#
# Sanity check that ZFS is working properly. This just runs a few commands that should succeed,
# and is not exhaustive by any means.
#
function sanity_check_zfs() {
  zpool list >/dev/null 2>&1 || return 1
  zfs list >/dev/null 2>&1 || return 1
  return 0
}

#
# Return true if the given pool exists.
#
function pool_exists() {
  local pool=$1
  zpool status "$pool" >/dev/null 2>&1
}

#
# Import the named pool from the given cachefile.
#
function import_pool() {
  local cachefile=$1
  local pool=$2
  zpool import -f -c "$cachefile" "$pool" >/dev/null
}

#
# Create a new pool. We create the pool with an alternate cachefile so that it's never imported
# automatically when the docker host restarts - only when the launch container is started. This
# ensures that we can store data on docker volumes that might not be available when the system
# boots.
#
# On WSL2, zpool cannot use raw files as vdevs directly. We detect this and fall back to
# creating a loop device via losetup, which works reliably.
#
function create_pool() {
  local pool=$1
  local data=$2
  local mountpoint=$3
  local cachefile=$4

  # Try direct file-backed pool first
  if zpool create -m "$mountpoint" -o cachefile="$cachefile" "$pool" "$data" 2>/dev/null; then
    zfs create -o mountpoint=legacy -o compression=lz4 "$pool"/data
    zfs create -o mountpoint=legacy "$pool"/db
    return 0
  fi

  # Fall back to loop device (required on WSL2)
  echo "Direct file vdev failed, trying loop device (WSL2 workaround)..."
  local loop_dev
  loop_dev=$(losetup -f --show "$data" 2>/dev/null) || return 1
  if ! zpool create -m "$mountpoint" -o cachefile="$cachefile" "$pool" "$loop_dev"; then
    losetup -d "$loop_dev" 2>/dev/null
    return 1
  fi
  zfs create -o mountpoint=legacy -o compression=lz4 "$pool"/data
  zfs create -o mountpoint=legacy "$pool"/db
}

#
# Update an existing pool that may have been created on an ealier version.
#
function update_pool() {
  local pool=$1

  # We didn't end up using this space, remove it now
  zfs list "$pool"/deathrow > /dev/null 2>&1 && zfs destroy "$pool"/deathrow
  # As part of migrating away from repositories and just to volumesets, we got rid of the repo namespace
  zfs list "$pool"/repo > /dev/null 2>&1 && zfs destroy -R "$pool"/repo
  # Create the data filesystem (replacing repo) if it doesn't exist
  zfs list "$pool"/data > /dev/null 2>&1 || zfs create -o mountpoint=legacy "$pool"/data
  # Create the db filesystem if it doesn't exist
  zfs list "$pool"/db > /dev/null 2>&1 || zfs create -o mountpoint=legacy "$pool"/db
}

#
# Destroy a pool.
#
function destroy_pool() {
  local pool=$1
  # Try direct destroy first. If it fails (e.g., hostid mismatch on WSL2
  # where the pool was created from the WSL host but teardown runs in a
  # Docker container), export the pool to release the hostid association
  # and then import + destroy.
  if ! zpool destroy -f "$pool" 2>/dev/null; then
    echo "Direct destroy failed, attempting export/reimport workaround..."
    zpool export -f "$pool" 2>/dev/null || true
    if zpool import -f "$pool" 2>/dev/null; then
      zpool destroy -f "$pool"
    fi
  fi
}

#
# Check to see if ZFS is loaded and, if so, whether it's compatible. This will return 0 if the
# running ZFS is compatible, 1 if it's not loaded, or exit on failure if it's loaded but
# incompatible.
#
# If we update to a new ZFS version but don't restart the docker host, we may find that we have
# an older ZFS version already loaded. We can't simply unload & re-install, as we may have active
# containers in use. If and when we come to that point, we'll need to coordinate with the CLI
# to stop all repositories, unload ZFS through datadatdat, and re-install.
#
function check_running_zfs() {
  log_start "Checking if compatible ZFS is running"
  local retval=1
  if is_zfs_loaded; then
    local version
    version=$(get_running_zfs_version)
    if ! zfs_version_compatible "$version"; then
      log_error "System is running ZFS $version incompatible with minimum $min_zfs_version, upgrade and retry"
    fi
    echo "System is running ZFS version $version"
    retval=0
  else
    echo "ZFS is not currently loaded"
  fi
  log_end
  return $retval
}

#
# Check to see if a compatible ZFS version exists on the system, loading it if it's available.
# Returns success if it's found, compatible, and can be loaded; failure otherwise. This takes
# both a directory location, but then also a type descriptor for messages, as we'll use the
# same method to attempt to load the previously built ZFS modules.
#
function load_zfs() {
  local module_dir=$1
  local module_type=$2
  local install_dir=$3
  local retval=1

  log_start "Checking if compatible $module_type ZFS is available"
  local version
  version=$(get_filesystem_zfs_version "$module_dir")

  if zfs_version_compatible "$version"; then
    echo "Version $version compatible"
    if load_zfs_module "$module_dir"; then
      echo "ZFS loaded"
      echo "$module_dir" > "$install_dir/installed_zfs"
      retval=0
    else
      echo "Failed to load module"
    fi
  else
    if [[ -z "$version" ]]; then
      echo "No ZFS module found"
    else
      echo "Version $version incompatible with minimum $min_zfs_version"
    fi
  fi
  log_end
  return $retval
}

#
# Detect the host's package manager. Returns the name (apt, dnf, pacman, apk) or empty string.
#
function detect_package_manager() {
  if command -v apt-get &>/dev/null; then echo "apt"
  elif command -v dnf &>/dev/null; then echo "dnf"
  elif command -v pacman &>/dev/null; then echo "pacman"
  elif command -v apk &>/dev/null; then echo "apk"
  fi
}

#
# Check if the running kernel supports loadable modules (CONFIG_MODULES=y).
#
function check_modules_supported() {
  if [[ -f "$ZFS_PROC_CONFIG_GZ" ]]; then
    zcat "$ZFS_PROC_CONFIG_GZ" 2>/dev/null | grep -q "^CONFIG_MODULES=y" && return 0
    return 1
  fi
  local config
  config="/boot/config-$(uname -r)"
  if [[ -f "$config" ]]; then
    grep -q "^CONFIG_MODULES=y" "$config" && return 0
    return 1
  fi
  # Practical test: can modprobe run at all?
  modprobe --dry-run zfs 2>/dev/null
  return $?
}

#
# Check if ZFS packages are available in the distro's package repos.
#
function check_zfs_in_repos() {
  local pkg_mgr=$1
  case "$pkg_mgr" in
    apt)    dpkg -l zfsutils-linux 2>/dev/null | grep -q "^ii" && return 0
            apt-cache show zfsutils-linux &>/dev/null ;;
    dnf)    dnf info zfs &>/dev/null ;;
    pacman) pacman -Si zfs-utils &>/dev/null ;;
    apk)    apk info -e zfs &>/dev/null ;;
    *)      return 1 ;;
  esac
}

#
# Install ZFS via the host's package manager. Pre-flight checks ensure we have a supported
# environment before attempting installation. This is the same approach used in our CI workflows.
#
function install_zfs_packages() {
  local install_dir=$1
  local pkg_mgr
  pkg_mgr=$(detect_package_manager)

  log_start "Installing ZFS via package manager"

  if [[ -z "$pkg_mgr" ]]; then
    echo "ERROR: No supported package manager found (need apt, dnf, pacman, or apk)"
    log_end; return 1
  fi

  if ! check_modules_supported; then
    echo "ERROR: Kernel does not support loadable modules (CONFIG_MODULES != y)"
    echo "Upgrade your kernel or use a distribution with module support"
    log_end; return 1
  fi

  if ! check_zfs_in_repos "$pkg_mgr"; then
    echo "ERROR: ZFS packages not found in $pkg_mgr repositories"
    echo "Add ZFS repository for your distro, then retry"
    log_end; return 1
  fi

  echo "Installing ZFS via $pkg_mgr"
  case "$pkg_mgr" in
    apt)    apt-get update -qq && apt-get install -y zfsutils-linux ;;
    dnf)    dnf install -y zfs ;;
    pacman) pacman -Sy --noconfirm zfs-utils ;;
    apk)    apk add zfs ;;
  esac

  modprobe zfs 2>/dev/null

  if is_zfs_loaded; then
    echo "ZFS installed and loaded via $pkg_mgr"
    echo "package-manager" > "$install_dir/installed_zfs"
    log_end; return 0
  else
    echo "ERROR: ZFS package installed but module failed to load"
    log_end; return 1
  fi
}

#
# Download prebuilt ZFS kernel modules and load via insmod. This is the fallback for environments
# where modprobe can't find ZFS modules (e.g., WSL2 with Microsoft's custom kernel).
# Modules are downloaded from the zfs-releases S3 bucket.
#
function insmod_prebuilt_zfs() {
  local install_dir=$1
  local krel
  krel=$(uname -r)
  local module_dir="$install_dir/modules/$krel"

  log_start "Loading prebuilt ZFS modules via insmod for '$krel'"

  mkdir -p "$module_dir"

  # Download modules if not already cached
  if [[ ! -f "$module_dir/zfs.ko" ]]; then
    local downloaded=false

    # Try versioned archive first (zfs-<VER>-modules-<KREL>.tar.gz)
    # Query latest ZFS version from S3 bucket listing
    local zfs_ver
    zfs_ver=$(curl -fsSL "http://$ZFS_MODULES_BUCKET/" 2>/dev/null | \
      grep -oP "zfs-\K[0-9.]+(?=-modules-${krel}\.tar\.gz)" | sort -V | tail -1)

    if [[ -n "$zfs_ver" ]]; then
      local url="http://$ZFS_MODULES_BUCKET/zfs-${zfs_ver}-modules-${krel}.tar.gz"
      echo "Downloading ZFS $zfs_ver modules from $url"
      if curl -fsSL "$url" | tar -xzf - -C "$module_dir"; then
        downloaded=true
      fi
    fi

    # Fall back to legacy format (zfs-modules-<KREL>.tar.gz)
    if [[ "$downloaded" != "true" ]]; then
      local url="http://$ZFS_MODULES_BUCKET/zfs-modules-${krel}.tar.gz"
      echo "Downloading ZFS modules from $url"
      if curl -fsSL "$url" | tar -xzf - -C "$module_dir"; then
        downloaded=true
      fi
    fi

    if [[ "$downloaded" != "true" ]]; then
      echo "ERROR: No prebuilt ZFS modules available for kernel $krel"
      echo "Submit a request at https://github.com/datadatdat/zfs-releases/issues"
      log_end; return 1
    fi
  fi

  if [[ ! -f "$module_dir/spl.ko" ]] || [[ ! -f "$module_dir/zfs.ko" ]]; then
    echo "ERROR: Downloaded archive missing spl.ko or zfs.ko"
    log_end; return 1
  fi

  echo "Loading SPL module..."
  insmod "$module_dir/spl.ko" || { echo "ERROR: Failed to load spl.ko"; log_end; return 1; }
  echo "Loading ZFS module..."
  insmod "$module_dir/zfs.ko" || { echo "ERROR: Failed to load zfs.ko"; log_end; return 1; }

  if is_zfs_loaded; then
    echo "ZFS loaded via insmod"

    # Install userland tools from archive if present
    if [[ -d "$module_dir/sbin" ]]; then
      echo "Installing ZFS userland tools from archive..."
      cp "$module_dir"/sbin/* /usr/local/sbin/ 2>/dev/null || true
      if [[ -d "$module_dir/lib" ]]; then
        cp "$module_dir"/lib/* /usr/local/lib/ 2>/dev/null || true
        ldconfig 2>/dev/null || true
      fi
    fi

    echo "insmod:$module_dir" > "$install_dir/installed_zfs"
    check_zfs_device
    log_end; return 0
  else
    echo "ERROR: insmod succeeded but ZFS not detected"
    log_end; return 1
  fi
}


#
# Check that ZFS is functioning, creating the ZFS device if needed and running a few sanity tests.
# If the commands fail, then we log an error and exit.
#
function check_zfs() {
  check_zfs_device
  sanity_check_zfs || log_error "ZFS not configured properly, contact help"
}

#
# Unloads the ZFS module if and only if it's currently loaded and we loaded it in the first place.
#
function unload_zfs() {
  local install_dir=$1
  if is_zfs_loaded && [[ -f "$install_dir/installed_zfs" ]]; then
    local module_location
    module_location=$(cat "$install_dir/installed_zfs")
    modprobe -d "$module_location" -r zfs || return 1
  fi
  return 0
}

#
# Unmounts all filesystems within the pool. This is only used during teardown, as a stopgap measure
#
function unmount_filesystems() {
  local pool=$1
  local dirs
  dirs=$(mount -t zfs | grep "^$pool" | awk '{print $3}' | sort -r)
  for dir in $dirs; do
     nsenter -m -u -t 1 -n -i umount "$dir"
  done
}
