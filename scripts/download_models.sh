#!/usr/bin/env bash
# ==============================================================================
# Shhmods - Zero-Shot ONNX Model Downloader
# Downloads unitary/toxic-bert ONNX weights directly from Hugging Face Hub.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
MODELS_DIR="${ROOT_DIR}/src/main/resources/models"

HF_BASE_URL="https://huggingface.co/Xenova/toxic-bert"
ONNX_URL="${HF_BASE_URL}/resolve/main/onnx/model_quantized.onnx"
VOCAB_URL="${HF_BASE_URL}/raw/main/vocab.txt"

TARGET_ONNX="${MODELS_DIR}/model.onnx"
TARGET_VOCAB="${MODELS_DIR}/vocab.txt"

FORCE=false
if [[ "${1:-}" == "--force" || "${1:-}" == "-f" ]]; then
    FORCE=true
fi

echo "=========================================================="
echo "  Shhmods Model Setup (Hugging Face Hub)"
echo "=========================================================="

mkdir -p "${MODELS_DIR}"

# 1. Download ONNX Model Weights (~106 MB)
if [[ -f "${TARGET_ONNX}" && "${FORCE}" == false ]]; then
    echo "✓ model.onnx already exists at ${TARGET_ONNX} (skipping, pass --force to re-download)."
else
    echo "⬇ Downloading ONNX weights from Hugging Face..."
    echo "  Source: ${ONNX_URL}"
    curl -L --progress-bar -o "${TARGET_ONNX}" "${ONNX_URL}"
    echo "✓ Successfully downloaded model.onnx ($(du -h "${TARGET_ONNX}" | cut -f1))"
fi

# 2. Download WordPiece Vocabulary (~226 KB)
if [[ -f "${TARGET_VOCAB}" && "${FORCE}" == false ]]; then
    echo "✓ vocab.txt already exists at ${TARGET_VOCAB}."
else
    echo "⬇ Downloading WordPiece vocab from Hugging Face..."
    curl -s -L -o "${TARGET_VOCAB}" "${VOCAB_URL}"
    echo "✓ Successfully downloaded vocab.txt."
fi

echo "=========================================================="
echo "✓ All model assets ready in ${MODELS_DIR}"
echo "=========================================================="
