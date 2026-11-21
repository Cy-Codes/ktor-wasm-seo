#!/usr/bin/env bash
# ============================================================================
# Installs Git pre-commit hooks for kmp-templates-libs.
# ============================================================================
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly HOOK_SOURCE="${ROOT_DIR}/.githooks/pre-commit"
readonly FORMATTER_SCRIPT="${ROOT_DIR}/.githooks/clean_and_format.py"

chmod +x "${HOOK_SOURCE}" "${FORMATTER_SCRIPT}"
git -C "${ROOT_DIR}" config core.hooksPath .githooks

echo "Installed Git hooks via core.hooksPath=.githooks"
