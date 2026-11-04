#!/bin/bash
set -euo pipefail

TASK_PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
"${BASH:-/bin/bash}" "$TASK_PROJECT_ROOT/build.sh"
"${BASH:-/bin/bash}" "$TASK_PROJECT_ROOT/parent-app/build.sh"
"${BASH:-/bin/bash}" "$TASK_PROJECT_ROOT/build.sh"
"${BASH:-/bin/bash}" "$TASK_PROJECT_ROOT/photo-app/build.sh"
"${BASH:-/bin/bash}" "$TASK_PROJECT_ROOT/bundle-installer/build.sh"
