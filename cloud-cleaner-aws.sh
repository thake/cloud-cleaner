#!/bin/bash
ARGS="--args=''"
if [ $# -gt 0 ]; then
  # shellcheck disable=SC2124
  ARGS="--args=$@"
fi
./gradlew --quiet cloud-cleaner-aws:run "${ARGS}"