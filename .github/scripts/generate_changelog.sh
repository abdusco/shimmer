#!/usr/bin/env bash
set -euo pipefail

# Generate a changelog using only commits with "feat" and "fix" prefixes.
# If a tag is provided as the first arg, use that as the current tag.
# Otherwise try to detect the current tag (exact match on HEAD) or the latest tag.
# The script attempts to find the previous tag and lists commits between previous->current.
# If no previous tag exists, it falls back to searching the whole history.

# Accept optional current tag as first arg
if [ "$#" -ge 1 ]; then
  CURRENT_TAG="$1"
else
  if git describe --tags --exact-match >/dev/null 2>&1; then
    CURRENT_TAG=$(git describe --tags --exact-match)
  else
    CURRENT_TAG=$(git tag --sort=-creatordate | head -n1 || true)
  fi
fi

# Gather tags sorted by creation date desc (portable)
TAGS=()
if git tag --sort=-creatordate >/dev/null 2>&1; then
  while IFS= read -r t; do
    TAGS+=("$t")
  done < <(git tag --sort=-creatordate)
fi

PREV_TAG=""
if [ -n "$CURRENT_TAG" ]; then
  for i in "${!TAGS[@]}"; do
    if [ "${TAGS[$i]}" = "$CURRENT_TAG" ]; then
      if [ $((i+1)) -lt ${#TAGS[@]} ]; then
        PREV_TAG="${TAGS[$((i+1))]}"
      fi
      break
    fi
  done
fi

if [ -z "$CURRENT_TAG" ] && [ -n "${TAGS[0]:-}" ]; then
  CURRENT_TAG="${TAGS[0]}"
fi

if [ -n "$PREV_TAG" ]; then
  RANGE="$PREV_TAG..$CURRENT_TAG"
  HEADER="Changelog since $PREV_TAG (up to $CURRENT_TAG)"
elif [ -n "$CURRENT_TAG" ]; then
  RANGE=""
  HEADER="Changelog up to $CURRENT_TAG (no previous tag found — showing matching commits from history)"
else
  RANGE=""
  HEADER="Changelog (no tags found — showing all matching commits)"
fi

echo "$HEADER"

echo
echo "## Features"
FEATS=$(git log $RANGE --grep='^feat' --pretty=format:"- %s (%h)" || true)
if [ -z "$FEATS" ]; then
  echo "- None"
else
  # Clean up common 'feat:' or 'feat(scope):' prefixes
  echo "$FEATS" | sed -E 's/^- feat(\([^)]*\))?: ?/- /i'
fi

echo
echo "## Fixes"
FIXES=$(git log $RANGE --grep='^fix' --pretty=format:"- %s (%h)" || true)
if [ -z "$FIXES" ]; then
  echo "- None"
else
  echo "$FIXES" | sed -E 's/^- fix(\([^)]*\))?: ?/- /i'
fi

