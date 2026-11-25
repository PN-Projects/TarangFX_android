#!/usr/bin/env bash
# Runs ./gradlew with the given arguments. If (and only if) the build failed because a plugin or
# dependency could not be DOWNLOADED (transient repository/CDN error, or a "missing" result that
# Gradle cached from an earlier failed attempt), it retries once with --refresh-dependencies,
# which bypasses Gradle's cached resolution results. Real compile/test failures are never retried.
set -uo pipefail

log="$(mktemp)"
trap 'rm -f "$log"' EXIT

./gradlew "$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}

if [ "$status" -ne 0 ] && grep -qE \
    "was not found in any of the following sources|Could not resolve all|Could not (GET|HEAD) |Read timed out|Connection reset|Remote host terminated" \
    "$log"; then
  echo "::warning title=Gradle::Dependency download failed; retrying once with --refresh-dependencies"
  sleep 20
  ./gradlew "$@" --refresh-dependencies
  status=$?
fi
exit "$status"
