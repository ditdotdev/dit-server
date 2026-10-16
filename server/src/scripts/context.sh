#!/usr/bin/env bash
# Copyright Dit 2026
# SPDX-License-Identifier: BUSL-1.1
#

#
# Builds the JVM arguments for dit-server based on DIT_CONTEXT. Sourced by /dit/run; kept separate so the
# command line can be tested with BATS (see tests/test_context.bats).
#
#   docker-zfs      The context configuration is always pool=$DIT_IDENTITY; DIT_CONTEXT_CONFIG is ignored.
#
#   kubernetes-csi  The context configuration comes from DIT_CONTEXT_CONFIG, a comma-separated list of
#                   name=value pairs set by the CLI (dit context install -p ...) and by the Go e2e harness.
#                   The variable was renamed from DIT_CONFIG in #208; a stale DIT_CONFIG is reported and
#                   ignored rather than silently dropping the configuration (#327).
#
function server_java_args() {
  if [[ -z $DIT_CONTEXT ]]; then
    echo "ERROR: DIT_CONTEXT must be set" >&2
    return 1
  fi

  case $DIT_CONTEXT in
    docker-zfs)
      # -Ddit.debug=true enables stack traces in ApiError.details responses. Safe here — this image serves the
      # local CLI over loopback; the user is the operator and the stack helps diagnose ZFS / Docker plumbing
      # issues. NOT set in dit-remote-server which fronts the API to remote clients.
      echo "-Ddit.context=$DIT_CONTEXT -Ddit.contextConfig=pool=$DIT_IDENTITY -Ddit.debug=true -jar /dit/dit-server.jar"
      ;;
    kubernetes-csi)
      if [[ -n $DIT_CONFIG && -z $DIT_CONTEXT_CONFIG ]]; then
        echo "WARNING: DIT_CONFIG is set but not read; the server reads context properties from DIT_CONTEXT_CONFIG (#208, #327)" >&2
      fi
      echo "-Ddit.context=$DIT_CONTEXT -Ddit.contextConfig=$DIT_CONTEXT_CONFIG -Ddit.debug=true -Djdk.tls.client.protocols=TLSv1.2 -jar /dit/dit-server.jar"
      ;;
    *)
      echo "ERROR: Unknown context $DIT_CONTEXT" >&2
      return 1
      ;;
  esac
}
