#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEYSTORE="$PROJECT_DIR/upload-key.jks"
KEYSTORE_PROPERTIES="$PROJECT_DIR/keystore.properties"
BUNDLE="$PROJECT_DIR/app/build/outputs/bundle/release/app-release.aab"
UPLOAD_BUNDLE="$PROJECT_DIR/cubacadabra-release.aab"

cd "$PROJECT_DIR"

if [[ ! -f "$KEYSTORE" ]]; then
    echo "Error: Keystore not found at $KEYSTORE" >&2
    exit 1
fi

if [[ ! -f "$KEYSTORE_PROPERTIES" ]]; then
    echo "Error: Signing properties not found at $KEYSTORE_PROPERTIES" >&2
    exit 1
fi

echo "Cleaning and building the signed release bundle..."
./gradlew clean :app:bundleRelease --no-build-cache

echo "Verifying the bundle signature..."
VERIFY_OUTPUT="$(jarsigner -verify "$BUNDLE" 2>&1)"
if [[ "$VERIFY_OUTPUT" != *"jar verified."* ]]; then
    echo "$VERIFY_OUTPUT" >&2
    echo "Error: The release bundle signature could not be verified." >&2
    exit 1
fi

cp "$BUNDLE" "$UPLOAD_BUNDLE"

echo
echo "Signed Groupicorn bundle ready for upload:"
echo "$UPLOAD_BUNDLE"
