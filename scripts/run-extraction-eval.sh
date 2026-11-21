#!/bin/sh
# The extraction model benchmark (ExtractionEvalTest): 30 made-up captures through the
# CognitionWorker's extraction rules, once per model.
#
# Usage: scripts/run-extraction-eval.sh <serial> <chat model file in tools/models>...
exec sh "$(dirname "$0")/run-model-eval.sh" ExtractionEvalTest "$@"
