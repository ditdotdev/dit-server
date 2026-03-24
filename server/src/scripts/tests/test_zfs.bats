#!/usr/bin/env bats
#
# Tests for zfs.sh functions, particularly built-in ZFS detection.
#
# These tests call the REAL functions from zfs.sh with system paths
# redirected via ZFS_PROC_FILESYSTEMS, ZFS_SYS_MODULE_VERSION env vars
# and PATH manipulation for lsmod/zfs commands.
#

SCRIPT_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/.." && pwd)"

setup() {
    TEST_TMPDIR="$(mktemp -d)"

    # Create mock filesystem paths
    mkdir -p "$TEST_TMPDIR/proc"
    mkdir -p "$TEST_TMPDIR/sys/module/zfs"
    mkdir -p "$TEST_TMPDIR/bin"

    # Default: no ZFS anywhere
    echo "" > "$TEST_TMPDIR/proc/filesystems"

    # Create mock lsmod that returns no ZFS by default
    cat > "$TEST_TMPDIR/bin/lsmod" << 'MOCK'
#!/bin/bash
echo "Module                  Size  Used by"
MOCK
    chmod +x "$TEST_TMPDIR/bin/lsmod"

    # Create mock zfs command that fails by default
    cat > "$TEST_TMPDIR/bin/zfs" << 'MOCK'
#!/bin/bash
exit 1
MOCK
    chmod +x "$TEST_TMPDIR/bin/zfs"

    # Point configurable paths at our test directory
    export ZFS_PROC_FILESYSTEMS="$TEST_TMPDIR/proc/filesystems"
    export ZFS_SYS_MODULE_VERSION="$TEST_TMPDIR/sys/module/zfs/version"

    # Put our mock commands first in PATH so real is_zfs_loaded() finds them
    export PATH="$TEST_TMPDIR/bin:$PATH"

    # Source the REAL zfs.sh to get all real function definitions
    source "$SCRIPT_DIR/zfs.sh"
}

teardown() {
    rm -rf "$TEST_TMPDIR"
}

# =============================================================================
# is_zfs_loaded() — tests call the REAL function from zfs.sh
# =============================================================================

@test "is_zfs_loaded returns true when ZFS is in lsmod (module loaded)" {
    cat > "$TEST_TMPDIR/bin/lsmod" << 'MOCK'
#!/bin/bash
echo "Module                  Size  Used by"
echo "zfs                  3145728  5"
echo "spl                   131072  1 zfs"
MOCK
    chmod +x "$TEST_TMPDIR/bin/lsmod"

    run is_zfs_loaded
    [ "$status" -eq 0 ]
}

@test "is_zfs_loaded returns true when ZFS is built into kernel (proc/filesystems)" {
    # No ZFS in lsmod, but ZFS in /proc/filesystems (built-in kernel)
    cat > "$TEST_TMPDIR/proc/filesystems" << 'EOF'
nodev	sysfs
nodev	tmpfs
nodev	bdev
nodev	proc
nodev	cgroup
	ext4
nodev	zfs
	xfs
EOF

    run is_zfs_loaded
    [ "$status" -eq 0 ]
}

@test "is_zfs_loaded returns false when ZFS is not loaded and not built-in" {
    cat > "$TEST_TMPDIR/proc/filesystems" << 'EOF'
nodev	sysfs
nodev	tmpfs
	ext4
	xfs
EOF

    run is_zfs_loaded
    [ "$status" -eq 1 ]
}

@test "is_zfs_loaded prefers lsmod check (both lsmod and proc/filesystems have ZFS)" {
    cat > "$TEST_TMPDIR/bin/lsmod" << 'MOCK'
#!/bin/bash
echo "Module                  Size  Used by"
echo "zfs                  3145728  5"
MOCK
    chmod +x "$TEST_TMPDIR/bin/lsmod"

    cat > "$TEST_TMPDIR/proc/filesystems" << 'EOF'
nodev	zfs
EOF

    run is_zfs_loaded
    [ "$status" -eq 0 ]
}

# =============================================================================
# get_running_zfs_version() — tests call the REAL function from zfs.sh
# =============================================================================

@test "get_running_zfs_version returns version from sys module version file" {
    echo "2.3.4" > "$TEST_TMPDIR/sys/module/zfs/version"

    run get_running_zfs_version
    [ "$status" -eq 0 ]
    [ "$output" = "2.3.4" ]
}

@test "get_running_zfs_version falls back to zfs command when version file missing" {
    # No version file (built-in ZFS doesn't create /sys/module/zfs/version)
    rm -f "$TEST_TMPDIR/sys/module/zfs/version"

    # Mock zfs version command to return version info
    cat > "$TEST_TMPDIR/bin/zfs" << 'MOCK'
#!/bin/bash
if [ "$1" = "version" ]; then
    echo "zfs-2.3.4-1"
    echo "zfs-kmod-2.3.4-1"
    exit 0
fi
exit 1
MOCK
    chmod +x "$TEST_TMPDIR/bin/zfs"

    run get_running_zfs_version
    [ "$status" -eq 0 ]
    [[ "$output" =~ "2.3.4" ]]
}

@test "get_running_zfs_version returns empty when no ZFS version available" {
    rm -f "$TEST_TMPDIR/sys/module/zfs/version"

    # zfs command fails (not installed)
    cat > "$TEST_TMPDIR/bin/zfs" << 'MOCK'
#!/bin/bash
exit 1
MOCK
    chmod +x "$TEST_TMPDIR/bin/zfs"

    run get_running_zfs_version
    [ -z "$output" ]
}

# =============================================================================
# zfs_version_compatible() — tests call the REAL function from zfs.sh
# =============================================================================

@test "zfs_version_compatible accepts matching major.minor version" {
    run zfs_version_compatible "2.3.4"
    [ "$status" -eq 0 ]
}

@test "zfs_version_compatible accepts higher minor version" {
    run zfs_version_compatible "2.5.0"
    [ "$status" -eq 0 ]
}

@test "zfs_version_compatible rejects lower minor version" {
    run zfs_version_compatible "1.9.0"
    [ "$status" -eq 1 ]
}

@test "zfs_version_compatible rejects empty string" {
    run zfs_version_compatible ""
    [ "$status" -eq 1 ]
}

@test "zfs_version_compatible handles version with suffix" {
    run zfs_version_compatible "2.3.4-1"
    [ "$status" -eq 0 ]
}

# =============================================================================
# detect_package_manager() — tests call the REAL function from zfs.sh
# =============================================================================

@test "detect_package_manager returns apt when apt-get available" {
    cat > "$TEST_TMPDIR/bin/apt-get" << 'MOCK'
#!/bin/bash
exit 0
MOCK
    chmod +x "$TEST_TMPDIR/bin/apt-get"

    run detect_package_manager
    [ "$status" -eq 0 ]
    [ "$output" = "apt" ]
}

@test "detect_package_manager returns dnf when dnf available" {
    # Remove apt-get if present
    rm -f "$TEST_TMPDIR/bin/apt-get"
    cat > "$TEST_TMPDIR/bin/dnf" << 'MOCK'
#!/bin/bash
exit 0
MOCK
    chmod +x "$TEST_TMPDIR/bin/dnf"

    run detect_package_manager
    [ "$status" -eq 0 ]
    [ "$output" = "dnf" ]
}

@test "detect_package_manager returns empty when no package manager found" {
    # Remove any mock package managers we may have created
    rm -f "$TEST_TMPDIR/bin/apt-get" "$TEST_TMPDIR/bin/dnf" "$TEST_TMPDIR/bin/pacman" "$TEST_TMPDIR/bin/apk"

    # Mock command -v to always fail for package managers
    function command() {
        return 1
    }
    export -f command

    run detect_package_manager
    [ -z "$output" ]

    # Restore command
    unset -f command
}

# =============================================================================
# check_modules_supported() — tests call the REAL function from zfs.sh
# =============================================================================

@test "check_modules_supported returns true when /proc/config.gz has CONFIG_MODULES=y" {
    echo "CONFIG_MODULES=y" | gzip > "$TEST_TMPDIR/proc/config.gz"
    export ZFS_PROC_CONFIG_GZ="$TEST_TMPDIR/proc/config.gz"

    run check_modules_supported
    [ "$status" -eq 0 ]
}

@test "check_modules_supported returns false when CONFIG_MODULES=n" {
    echo "CONFIG_MODULES=n" | gzip > "$TEST_TMPDIR/proc/config.gz"
    export ZFS_PROC_CONFIG_GZ="$TEST_TMPDIR/proc/config.gz"

    run check_modules_supported
    [ "$status" -eq 1 ]
}

# =============================================================================
# check_zfs_in_repos() — tests call the REAL function from zfs.sh
# =============================================================================

@test "check_zfs_in_repos returns true when apt-cache finds zfsutils-linux" {
    cat > "$TEST_TMPDIR/bin/apt-cache" << 'MOCK'
#!/bin/bash
if [ "$1" = "show" ] && [ "$2" = "zfsutils-linux" ]; then
    echo "Package: zfsutils-linux"
    exit 0
fi
exit 1
MOCK
    chmod +x "$TEST_TMPDIR/bin/apt-cache"

    run check_zfs_in_repos "apt"
    [ "$status" -eq 0 ]
}

@test "check_zfs_in_repos returns false when package not found" {
    cat > "$TEST_TMPDIR/bin/apt-cache" << 'MOCK'
#!/bin/bash
exit 1
MOCK
    chmod +x "$TEST_TMPDIR/bin/apt-cache"

    run check_zfs_in_repos "apt"
    [ "$status" -eq 1 ]
}

@test "check_zfs_in_repos returns false for unknown package manager" {
    run check_zfs_in_repos "unknown"
    [ "$status" -eq 1 ]
}

# =============================================================================
# install_zfs_packages() — pre-flight error tests
# =============================================================================

@test "install_zfs_packages fails when no package manager found" {
    rm -f "$TEST_TMPDIR/bin/apt-get" "$TEST_TMPDIR/bin/dnf" "$TEST_TMPDIR/bin/pacman" "$TEST_TMPDIR/bin/apk"

    # Mock command -v to always fail for package managers
    function command() {
        return 1
    }
    export -f command

    function log_start() { :; }
    function log_end() { :; }
    export -f log_start log_end

    run install_zfs_packages "$TEST_TMPDIR/install"
    [ "$status" -eq 1 ]
    [[ "$output" == *"No supported package manager"* ]]

    unset -f command
}

@test "install_zfs_packages fails when CONFIG_MODULES not supported" {
    # Mock apt-get that handles all subcommands
    cat > "$TEST_TMPDIR/bin/apt-get" << 'MOCK'
#!/bin/bash
exit 0
MOCK
    chmod +x "$TEST_TMPDIR/bin/apt-get"

    # No /proc/config.gz exists
    export ZFS_PROC_CONFIG_GZ="$TEST_TMPDIR/proc/config.gz.nonexistent"

    # modprobe --dry-run fails (no module support)
    cat > "$TEST_TMPDIR/bin/modprobe" << 'MOCK'
#!/bin/bash
exit 1
MOCK
    chmod +x "$TEST_TMPDIR/bin/modprobe"

    # No boot config either
    cat > "$TEST_TMPDIR/bin/uname" << 'MOCK'
#!/bin/bash
echo "fake-kernel"
MOCK
    chmod +x "$TEST_TMPDIR/bin/uname"

    function log_start() { :; }
    function log_end() { :; }
    export -f log_start log_end

    run install_zfs_packages "$TEST_TMPDIR/install"
    [ "$status" -eq 1 ]
    [[ "$output" == *"CONFIG_MODULES"* ]]
}

@test "install_zfs_packages fails when ZFS not in repos" {
    # Mock apt-get that handles all subcommands without calling real apt
    cat > "$TEST_TMPDIR/bin/apt-get" << 'MOCK'
#!/bin/bash
exit 0
MOCK
    chmod +x "$TEST_TMPDIR/bin/apt-get"

    # CONFIG_MODULES=y
    echo "CONFIG_MODULES=y" | gzip > "$TEST_TMPDIR/proc/config.gz"
    export ZFS_PROC_CONFIG_GZ="$TEST_TMPDIR/proc/config.gz"

    # ZFS not in repos
    cat > "$TEST_TMPDIR/bin/apt-cache" << 'MOCK'
#!/bin/bash
exit 1
MOCK
    chmod +x "$TEST_TMPDIR/bin/apt-cache"

    # dpkg says not installed
    cat > "$TEST_TMPDIR/bin/dpkg" << 'MOCK'
#!/bin/bash
exit 1
MOCK
    chmod +x "$TEST_TMPDIR/bin/dpkg"

    function log_start() { :; }
    function log_end() { :; }
    export -f log_start log_end

    run install_zfs_packages "$TEST_TMPDIR/install"
    [ "$status" -eq 1 ]
    [[ "$output" == *"not found"* ]]
}

# =============================================================================
# insmod_prebuilt_zfs() — tests call the REAL function from zfs.sh
# =============================================================================

@test "insmod_prebuilt_zfs succeeds when modules download and insmod works" {
    mkdir -p "$TEST_TMPDIR/install"
    local krel=$(uname -r)

    # Pre-create the module files (simulating a successful download)
    mkdir -p "$TEST_TMPDIR/install/modules/$krel"
    echo "fake-spl" > "$TEST_TMPDIR/install/modules/$krel/spl.ko"
    echo "fake-zfs" > "$TEST_TMPDIR/install/modules/$krel/zfs.ko"

    # Mock curl (won't be called since files already cached)
    cat > "$TEST_TMPDIR/bin/curl" << 'MOCK'
#!/bin/bash
exit 0
MOCK
    chmod +x "$TEST_TMPDIR/bin/curl"

    # Mock insmod to succeed
    cat > "$TEST_TMPDIR/bin/insmod" << 'MOCK'
#!/bin/bash
exit 0
MOCK
    chmod +x "$TEST_TMPDIR/bin/insmod"

    # Mock lsmod to show ZFS loaded after insmod
    cat > "$TEST_TMPDIR/bin/lsmod" << 'MOCK'
#!/bin/bash
echo "Module                  Size  Used by"
echo "zfs                  3145728  0"
echo "spl                   131072  1 zfs"
MOCK
    chmod +x "$TEST_TMPDIR/bin/lsmod"

    # Mock check_zfs_device
    cat > "$TEST_TMPDIR/bin/cat" << 'MOCK'
#!/bin/bash
echo "10 249"
MOCK
    chmod +x "$TEST_TMPDIR/bin/cat"

    function log_start() { :; }
    function log_end() { :; }
    function check_zfs_device() { :; }
    export -f log_start log_end check_zfs_device

    run insmod_prebuilt_zfs "$TEST_TMPDIR/install"
    [ "$status" -eq 0 ]
}

@test "insmod_prebuilt_zfs fails when download fails" {
    mkdir -p "$TEST_TMPDIR/install"

    # Mock curl to fail (no modules available for this kernel)
    cat > "$TEST_TMPDIR/bin/curl" << 'MOCK'
#!/bin/bash
exit 1
MOCK
    chmod +x "$TEST_TMPDIR/bin/curl"

    function log_start() { :; }
    function log_end() { :; }
    export -f log_start log_end

    run insmod_prebuilt_zfs "$TEST_TMPDIR/install"
    [ "$status" -eq 1 ]
}

# =============================================================================
# Removed functions should not exist
# =============================================================================

@test "compile_and_load_zfs is not defined" {
    run type -t compile_and_load_zfs
    [ "$status" -eq 1 ]
}

@test "load_precompiled_zfs is not defined" {
    run type -t load_precompiled_zfs
    [ "$status" -eq 1 ]
}

@test "get_zfs_build_version is not defined" {
    run type -t get_zfs_build_version
    [ "$status" -eq 1 ]
}
