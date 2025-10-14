#!/usr/bin/env bash
#
# Copyright Datadatdat.
#

util_script=/test/src/scripts/util.sh

@test "user defaults set correctly" {
  function docker() { /bin/true; }
  function jq() { /bin/true; }

   source $util_script
   [ $IDENTITY = "datadatdat" ]
   [ $PORT = "5001" ]
   [ $IMAGE = "datadatdat:latest" ]
}

@test "user overrides propagated correctly" {
  function docker() { /bin/true; }
  function jq() { /bin/true; }

   export DATADATDAT_IDENTITY=test
   export DATADATDAT_PORT=6001
   export DATADATDAT_IMAGE=datadatdat/datadatdat:test
   source $util_script
   [ $IDENTITY = "test" ]
   [ $PORT = "6001" ]
   [ $IMAGE = "datadatdat/datadatdat:test" ]
}

@test "derived variables set correctly" {
  function docker() { /bin/true; }
  function jq() { echo "/path"; }

   source $util_script
   [ $POOL = "datadatdat" ]
   [ $VOLUME = "datadatdat-data" ]
   [ $BASE_DIR = "/var/lib/datadatdat" ]
   [ $DATA_DIR = "/var/lib/datadatdat/data" ]
   [ $INSTALL_DIR = "/var/lib/datadatdat/data/install" ]
   [ $POOL_DIR = "/path/pool" ]
   [ $MNT_DIR = "/var/lib/datadatdat/mnt" ]
   [ $SYSTEM_MODULES = "/var/lib/datadatdat/system" ]
   [ $COMPILED_MODULES = "/var/lib/datadatdat/data/modules" ]
}

@test "timestamp returns date output" {
  function docker() { /bin/true; }
  function jq() { /bin/true; }

  source $util_script
  function date() { echo "date"; }
  export -f date
  run timestamp
  [ $status -eq 0 ]
  [ "$output" = "date" ]
}

@test "log begin prints marker" {
  function docker() { /bin/true; }
  function jq() { /bin/true; }

  source $util_script
  function timestamp { echo "ts"; }
  export -f timestamp
  run log_begin
  [ $status -eq 0 ]
  [ "$output" = "ts DATADATDAT BEGIN" ]
}

@test "log start prints marker" {
  function docker() { /bin/true; }
  function jq() { /bin/true; }

  source $util_script
  function timestamp { echo "ts"; }
  export -f timestamp
  run log_start "this is my message"
  [ $status -eq 0 ]
  [ "$output" = "ts DATADATDAT START this is my message" ]
}

@test "log end prints marker" {
  function docker() { /bin/true; }
  function jq() { /bin/true; }

  source $util_script
  function timestamp { echo "ts"; }
  export -f timestamp
  run log_end
  [ $status -eq 0 ]
  [ "$output" = "ts DATADATDAT END" ]
}

@test "log finish prints marker" {
  function docker() { /bin/true; }
  function jq() { /bin/true; }

  source $util_script
  function timestamp { echo "ts"; }
  export -f timestamp
  run log_finished
  [ $status -eq 0 ]
  [ "$output" = "ts DATADATDAT FINISHED" ]
}

@test "log error exits program" {
  function docker() { /bin/true; }
  function jq() { /bin/true; }

  source $util_script
  function timestamp { echo "ts"; }
  export -f timestamp
  run log_error "this is my message"
  [ $status -eq 1 ]
  [ "$output" = "ts DATADATDAT ERROR this is my message" ]
}
