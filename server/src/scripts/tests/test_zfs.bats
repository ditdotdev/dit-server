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
