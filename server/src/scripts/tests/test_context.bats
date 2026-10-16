#!/usr/bin/env bats
#
# Tests for context.sh: the JVM command line /dit/run builds for each DIT_CONTEXT.
#
# Regression guard for issue #327 / PR #208: the kubernetes-csi context reads its
# properties from DIT_CONTEXT_CONFIG (the name the CLI and the Go e2e harness set),
# never from the legacy DIT_CONFIG.
#

SCRIPT_DIR="$(cd "$(dirname "$BATS_TEST_FILENAME")/.." && pwd)"

setup() {
    unset DIT_CONTEXT DIT_CONTEXT_CONFIG DIT_CONFIG DIT_IDENTITY
    # Source the REAL context.sh to get the real function definitions
    source "$SCRIPT_DIR/context.sh"
}

# =============================================================================
# server_java_args — kubernetes-csi
# =============================================================================

@test "kubernetes-csi forwards DIT_CONTEXT_CONFIG as -Ddit.contextConfig" {
    export DIT_CONTEXT=kubernetes-csi
    export DIT_IDENTITY=test
    export DIT_CONTEXT_CONFIG="storageClass=csi-hostpath-sc,snapshotClass=csi-hostpath-snapclass"

    run server_java_args
    [ "$status" -eq 0 ]
    [[ "$output" == *"-Ddit.context=kubernetes-csi"* ]]
    [[ "$output" == *"-Ddit.contextConfig=storageClass=csi-hostpath-sc,snapshotClass=csi-hostpath-snapclass"* ]]
    [[ "$output" == *"-Djdk.tls.client.protocols=TLSv1.2"* ]]
    [[ "$output" == *"-Ddit.debug=true"* ]]
    [[ "$output" == *"-jar /dit/dit-server.jar"* ]]
}

@test "kubernetes-csi ignores legacy DIT_CONFIG and warns about the rename (#327)" {
    export DIT_CONTEXT=kubernetes-csi
    export DIT_IDENTITY=test
    export DIT_CONFIG="storageClass=csi-hostpath-sc"

    run server_java_args
    [ "$status" -eq 0 ]
    # The legacy value must not leak into the JVM property...
    [[ "$output" != *"csi-hostpath-sc"* ]]
    [[ "$output" == *"-Ddit.contextConfig= "* ]] || [[ "$output" == *"-Ddit.contextConfig="$'\n'* ]]
    # ...and the operator is told which variable the server actually reads.
    [[ "$output" == *"DIT_CONFIG"* ]]
    [[ "$output" == *"DIT_CONTEXT_CONFIG"* ]]
}

@test "kubernetes-csi with no config at all passes an empty contextConfig without warning" {
    export DIT_CONTEXT=kubernetes-csi
    export DIT_IDENTITY=test

    run server_java_args
    [ "$status" -eq 0 ]
    [[ "$output" == *"-Ddit.contextConfig= "* ]]
    [[ "$output" != *"WARNING"* ]]
}

# =============================================================================
# server_java_args — docker-zfs
# =============================================================================

@test "docker-zfs uses pool=DIT_IDENTITY and no TLS override" {
    export DIT_CONTEXT=docker-zfs
    export DIT_IDENTITY=test

    run server_java_args
    [ "$status" -eq 0 ]
    [[ "$output" == *"-Ddit.context=docker-zfs"* ]]
    [[ "$output" == *"-Ddit.contextConfig=pool=test"* ]]
    [[ "$output" == *"-Ddit.debug=true"* ]]
    [[ "$output" != *"jdk.tls.client.protocols"* ]]
    [[ "$output" == *"-jar /dit/dit-server.jar"* ]]
}

@test "docker-zfs ignores DIT_CONTEXT_CONFIG (pool is always the identity)" {
    export DIT_CONTEXT=docker-zfs
    export DIT_IDENTITY=test
    export DIT_CONTEXT_CONFIG="pool=other"

    run server_java_args
    [ "$status" -eq 0 ]
    [[ "$output" == *"-Ddit.contextConfig=pool=test"* ]]
    [[ "$output" != *"pool=other"* ]]
}

# =============================================================================
# server_java_args — errors
# =============================================================================

@test "unknown context fails" {
    export DIT_CONTEXT=no-such-context
    export DIT_IDENTITY=test

    run server_java_args
    [ "$status" -ne 0 ]
    [[ "$output" == *"no-such-context"* ]]
}

@test "missing DIT_CONTEXT fails" {
    export DIT_IDENTITY=test

    run server_java_args
    [ "$status" -ne 0 ]
}
