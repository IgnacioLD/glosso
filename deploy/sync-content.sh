#!/usr/bin/env bash
#
# Copies the generated content the server serves under /assets, for one language:
# the SQLite DB, the reference audio, the exercise artwork and the compiled
# manifest (plus the G2P lexicon). Works locally and over SSH.
#
#   deploy/sync-content.sh --language en                 # -> glosso-pro/data/assets/content/en
#   deploy/sync-content.sh --language de user@vps:/srv/glosso/assets
#
# Regenerar antes con:  ./gradlew :server:publishContent -Planguage=<lang>  &&  tools/content/pipeline.py --language <lang>
# (y para no-ingles, tools/content/phonemize.py --language <lang> --content <content dir>)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

LANG_CODE="${GLOSSO_LANGUAGE:-en}"
DEST=""
while [ $# -gt 0 ]; do
    case "$1" in
        --language)   LANG_CODE="$2"; shift 2 ;;
        --language=*) LANG_CODE="${1#*=}"; shift ;;
        *)            DEST="$1"; shift ;;
    esac
done

SRC="${GLOSSO_CONTENT_DIR:-$ROOT/../glosso-data/content/$LANG_CODE}"
DEST="${DEST:-$ROOT/data/assets}"
TARGET="$DEST/content/$LANG_CODE"

if [ ! -f "$SRC/content.db" ]; then
    echo "ERROR: no content.db at $SRC (run the pipeline --language $LANG_CODE first)" >&2
    exit 1
fi

case "$DEST" in
    *:*) ;;                 # remote: create the language dir over ssh
    *) mkdir -p "$TARGET" ;;   # local
esac
if [[ "$DEST" == *:* ]]; then
    HOST="${DEST%%:*}"
    # shellcheck disable=SC2029
    ssh "$HOST" "mkdir -p '$TARGET'"
fi

rsync -a "$SRC/content.db" "$TARGET/"
[ -f "$SRC/manifest.json" ] && rsync -a "$SRC/manifest.json" "$TARGET/"
[ -f "$SRC/$LANG_CODE.lexicon" ] && rsync -a "$SRC/$LANG_CODE.lexicon" "$TARGET/"
if [ -d "$SRC/audio" ]; then
    rsync -a --delete "$SRC/audio/" "$TARGET/audio/"
fi
if [ -d "$SRC/images" ]; then
    rsync -a --delete "$SRC/images/" "$TARGET/images/"
fi

echo "content synced: $SRC -> $TARGET"
