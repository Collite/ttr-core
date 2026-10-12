#!/bin/bash
set -e

GRAMMAR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRAMMAR_FILE="$GRAMMAR_DIR/../src/TTR.g4"
OUTPUT_DIR="$GRAMMAR_DIR/../../parser/src/generated"

if [ ! -f "$GRAMMAR_FILE" ]; then
  echo "Grammar file not found: $GRAMMAR_FILE"
  exit 1
fi

if [ ! -d "$OUTPUT_DIR" ]; then
  mkdir -p "$OUTPUT_DIR"
fi

rm -rf "$OUTPUT_DIR"/*.ts "$OUTPUT_DIR"/*.interp "$OUTPUT_DIR"/*.tokens 2>/dev/null || true

cd "$GRAMMAR_DIR/.."
npx antlr-ng -o "$OUTPUT_DIR" -l -v -Dlanguage=TypeScript -- "$GRAMMAR_FILE"
echo "Parser generated to $OUTPUT_DIR"

# the keywords that cannot be an `id` part — for `@tatrman/parser/layout`, which must not load ANTLR
node "$GRAMMAR_DIR/generate-ttr-id-words.mjs" "$GRAMMAR_FILE" "$OUTPUT_DIR/ttr-id-words.ts"
