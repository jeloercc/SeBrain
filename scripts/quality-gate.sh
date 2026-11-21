#!/bin/sh
# The assistant's quality gate: run it before shipping any change to prompts, routing, search or
# the default model. It runs the trust and tool-calling evaluations on the phone (debug app,
# made-up vaults) and fails if any score is below its floor. Floors are the scores measured on
# 2026-11-21 with Gemma 4 E2B on the NPU; raise them when a change improves a score, never lower
# them to let a change through.
#
# Usage: scripts/quality-gate.sh <serial> [chat model file in tools/models]
set -u

SERIAL=$1
MODEL=${2:-gemma-4-E2B-it_Google_Tensor_G5.litertlm}
ROOT=$(cd "$(dirname "$0")/.." && pwd)

# name:floor, as the evaluations print name=got/total.
FLOORS="found:22 cited:22 honest:5 general:6 right:16"

OUT=$(sh "$ROOT/scripts/run-trust-eval.sh" "$SERIAL" "$MODEL" 2>&1; sh "$ROOT/scripts/run-model-eval.sh" ToolCallEvalTest "$SERIAL" "$MODEL" 2>&1)
echo "$OUT" | grep -E "eval trust .*found=|eval tools .*right=|not finish|locked|not found"

status=0
for floor in $FLOORS; do
    name=${floor%%:*}
    min=${floor#*:}
    got=$(echo "$OUT" | grep -oE "$name=[0-9]+/[0-9]+" | tail -1 | sed -E 's/.*=([0-9]+)\/.*/\1/')
    if [ -z "$got" ]; then
        echo "GATE $name: not measured"
        status=1
    elif [ "$got" -lt "$min" ]; then
        echo "GATE $name: $got below the floor of $min"
        status=1
    else
        echo "GATE $name: $got (floor $min) ok"
    fi
done
if [ $status -eq 0 ]; then echo "QUALITY GATE: PASS"; else echo "QUALITY GATE: FAIL"; fi
exit $status
