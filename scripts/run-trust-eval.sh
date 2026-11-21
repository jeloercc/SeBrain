#!/bin/sh
# Plan phase 5, "answers you can trust": finding, citing and "the notes don't say", on a vault the
# test makes up and deletes. FILLER adds that many generated notes (default 0).
#
# Usage: [FILLER=300] scripts/run-trust-eval.sh <serial> <chat model file in tools/models>...
ARGS="-e filler ${FILLER:-0}" exec sh "$(dirname "$0")/run-model-eval.sh" TrustEvalTest "$@"
