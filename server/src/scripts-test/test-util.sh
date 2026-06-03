#!/usr/bin/env bash
#
# Copyright Dit.
#

util_script=/test/src/scripts/util.sh

@test "user defaults set correctly" {
  function docker() { /bin/true; }
  function jq() { /bin/true; }

   source $util_script
   [ $IDENTITY = "dit" ]
   [ $PORT = "5001" ]
   [ $IMAGE = "dit:latest" ]
}

@test "user overrides propagated correctly" {
  function docker() { /bin/true; }
  function jq() { /bin/true; }

   export DIT_IDENTITY=test
   export DIT_PORT=6001
   export DIT_IMAGE=ditdotdev/dit:test
   source $util_script
   [ $IDENTITY = "test" ]
   [ $PORT = "6001" ]
   [ $IMAGE = "ditdotdev/dit:test" ]
}

@test "derived variables set correctly" {
  function docker() { /bin/true; }
  function jq() { echo "/path"; }

   source $util_script
   [ $POOL = "dit" ]
   [ $VOLUME = "dit-data" ]
   [ $BASE_DIR = "/var/lib/dit" ]
   [ $DATA_DIR = "/var/lib/ditdotdev/data" ]
   [ $INSTALL_DIR = "/var/lib/ditdotdev/data/install" ]
   [ $POOL_DIR = "/path/pool" ]
   [ $MNT_DIR = "/var/lib/ditdotdev/mnt" ]
   [ $SYSTEM_MODULES = "/var/lib/ditdotdev/system" ]
   [ $COMPILED_MODULES = "/var/lib/ditdotdev/data/modules" ]
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
  [ "$output" = "ts DIT BEGIN" ]
}

@test "log start prints marker" {
  function docker() { /bin/true; }
  function jq() { /bin/true; }

  source $util_script
  function timestamp { echo "ts"; }
  export -f timestamp
  run log_start "this is my message"
  [ $status -eq 0 ]
  [ "$output" = "ts DIT START this is my message" ]
}

@test "log end prints marker" {
  function docker() { /bin/true; }
  function jq() { /bin/true; }

  source $util_script
  function timestamp { echo "ts"; }
  export -f timestamp
  run log_end
  [ $status -eq 0 ]
  [ "$output" = "ts DIT END" ]
}

@test "log finish prints marker" {
  function docker() { /bin/true; }
  function jq() { /bin/true; }

  source $util_script
  function timestamp { echo "ts"; }
  export -f timestamp
  run log_finished
  [ $status -eq 0 ]
  [ "$output" = "ts DIT FINISHED" ]
}

@test "log error exits program" {
  function docker() { /bin/true; }
  function jq() { /bin/true; }

  source $util_script
  function timestamp { echo "ts"; }
  export -f timestamp
  run log_error "this is my message"
  [ $status -eq 1 ]
  [ "$output" = "ts DIT ERROR this is my message" ]
}
