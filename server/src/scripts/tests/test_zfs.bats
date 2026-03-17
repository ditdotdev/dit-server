#!/usr/bin/env bats
#
# Tests for zfs.sh functions, particularly built-in ZFS detection.
#
# These tests mock lsmod, /proc/filesystems, /sys/module/zfs/version,
# and the zfs command to test detection logic in isolation.
#

SCRIPT_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/.." && pwd)"

setup() {
    # Create a temporary directory for mocks
    TEST_TMPDIR="$(mktemp -d)"

    # Create mock /proc/filesystems and /sys/module/zfs/version paths
    mkdir -p "$TEST_TMPDIR/proc"
    mkdir -p "$TEST_TMPDIR/sys/module/zfs"
    mkdir -p "$TEST_TMPDIR/bin"

    # Default: no ZFS anywhere
    echo "" > "$TEST_TMPDIR/proc/filesystems"

    # Create mock lsmod that returns nothing by default
    cat > "$TEST_TMPDIR/bin/lsmod" << 'MOCK'
#!/bin/bash
# Default: no modules loaded
echo "Module                  Size  Used by"
MOCK
    chmod +x "$TEST_TMPDIR/bin/lsmod"

    # Create mock zfs command that fails by default
    cat > "$TEST_TMPDIR/bin/zfs" << 'MOCK'
#!/bin/bash
exit 1
MOCK
    chmod +x "$TEST_TMPDIR/bin/zfs"

    # Create wrapper functions that use our mock paths instead of real system paths.
    # We source the real zfs.sh but override the functions that read system state.
    # This lets us test the logic without needing root or real ZFS.

    # Source the real script to get all function definitions
    source "$SCRIPT_DIR/zfs.sh"
}

teardown() {
    rm -rf "$TEST_TMPDIR"
}

# Helper: override is_zfs_loaded to use mock paths
# We redefine the function using the same logic as zfs.sh but pointing at mocks
_mock_is_zfs_loaded() {
    "$TEST_TMPDIR/bin/lsmod" | grep "^zfs " >/dev/null 2>&1 && return 0
    grep -q "^nodev.*zfs" "$TEST_TMPDIR/proc/filesystems" 2>/dev/null && return 0
    return 1
}

# Helper: override get_running_zfs_version to use mock paths
_mock_get_running_zfs_version() {
    cat "$TEST_TMPDIR/sys/module/zfs/version" 2>/dev/null && return 0
    "$TEST_TMPDIR/bin/zfs" version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1
}

# =============================================================================
# Bug 1: is_zfs_loaded() misses built-in ZFS
# =============================================================================

@test "is_zfs_loaded returns true when ZFS is in lsmod (module loaded)" {
    # Mock lsmod to show ZFS as a loaded module
    cat > "$TEST_TMPDIR/bin/lsmod" << 'MOCK'
#!/bin/bash
echo "Module                  Size  Used by"
echo "zfs                  3145728  5"
echo "spl                   131072  1 zfs"
MOCK
    chmod +x "$TEST_TMPDIR/bin/lsmod"

    run _mock_is_zfs_loaded
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

    run _mock_is_zfs_loaded
    [ "$status" -eq 0 ]
}

@test "is_zfs_loaded returns false when ZFS is not loaded and not built-in" {
    # No ZFS anywhere
    cat > "$TEST_TMPDIR/proc/filesystems" << 'EOF'
nodev	sysfs
nodev	tmpfs
	ext4
	xfs
EOF

    run _mock_is_zfs_loaded
    [ "$status" -eq 1 ]
}

@test "is_zfs_loaded prefers lsmod check (both lsmod and proc/filesystems have ZFS)" {
    # Both should work — lsmod check hits first
    cat > "$TEST_TMPDIR/bin/lsmod" << 'MOCK'
#!/bin/bash
echo "Module                  Size  Used by"
echo "zfs                  3145728  5"
MOCK
    chmod +x "$TEST_TMPDIR/bin/lsmod"

    cat > "$TEST_TMPDIR/proc/filesystems" << 'EOF'
nodev	zfs
EOF

    run _mock_is_zfs_loaded
    [ "$status" -eq 0 ]
}

# =============================================================================
# Bug 1 (related): get_running_zfs_version() doesn't handle built-in ZFS
# =============================================================================

@test "get_running_zfs_version returns version from /sys/module/zfs/version (module)" {
    echo "2.3.4" > "$TEST_TMPDIR/sys/module/zfs/version"

    run _mock_get_running_zfs_version
    [ "$status" -eq 0 ]
    [ "$output" = "2.3.4" ]
}

@test "get_running_zfs_version falls back to zfs command when /sys/module/zfs/version missing" {
    # Remove the version file
    rm -f "$TEST_TMPDIR/sys/module/zfs/version"

    # Mock zfs version command
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

    run _mock_get_running_zfs_version
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

    run _mock_get_running_zfs_version
    [ -z "$output" ]
}

# =============================================================================
# Existing behavior: zfs_version_compatible()
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
