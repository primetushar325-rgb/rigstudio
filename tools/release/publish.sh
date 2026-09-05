#!/usr/bin/env bash
# One command that publishes RigStudio V3 as an installable APK download, from YOUR machine:
#
#   GITHUB_TOKEN=ghp_xxx bash tools/release/publish.sh            # creates <you>/rigstudio-app
#   GITHUB_TOKEN=ghp_xxx bash tools/release/publish.sh my-name    # custom repo name
#
# It creates a public repo, pushes the current V3 tree with the release workflow
# (tools/release/build-apk.yml), waits for the Actions build to finish and prints the stable
# direct download link:
#
#   https://github.com/<you>/<repo>/releases/download/apk/app-debug.apk
#
# Re-running is safe: it force-pushes the newest tree and Actions republishes the release, so the
# same link always serves the newest APK. Needs: git, curl, python3 and a classic PAT with `repo`
# and `workflow` scopes (or a fine-grained token with Contents+Actions read/write on the new repo).
set -euo pipefail

REPO_NAME=${1:-rigstudio-app}
SOURCE_REPO=${SOURCE_REPO:-primetushar325-rgb/rigstudio}
SOURCE_BRANCH=${SOURCE_BRANCH:-arena/01a07147-rigstudio}
HERE=$(cd "$(dirname "$0")" && pwd)

TOKEN=${GITHUB_TOKEN:-}
if [ -z "$TOKEN" ]; then
    echo "publish.sh: set GITHUB_TOKEN to a personal access token (repo + workflow scopes)" >&2
    exit 2
fi

api() { # api METHOD PATH [JSON]
    method=$1; path=$2; body=${3:-}
    if [ -n "$body" ]; then
        curl -s -X "$method" -H "Authorization: token $TOKEN" -H "Accept: application/vnd.github+json" \
            -H "Content-Type: application/json" -d "$body" "https://api.github.com$path"
    else
        curl -s -X "$method" -H "Authorization: token $TOKEN" -H "Accept: application/vnd.github+json" \
            "https://api.github.com$path"
    fi
}

echo "== checking token"
LOGIN=$(api GET /user | python3 -c 'import json,sys; print(json.load(sys.stdin).get("login",""))')
if [ -z "$LOGIN" ]; then
    echo "publish.sh: token rejected by GitHub (check scopes: repo, workflow)" >&2
    exit 2
fi
echo "   authenticated as $LOGIN"

echo "== ensuring public repo $LOGIN/$REPO_NAME"
status=$(curl -s -o /tmp/create-$$.json -w '%{http_code}' -X POST \
    -H "Authorization: token $TOKEN" -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$REPO_NAME\",\"public\":true,\"description\":\"RigStudio V3 - native Android character rigging & MP4 export. APK builds via Actions.\"}" \
    https://api.github.com/user/repos)
if [ "$status" = "201" ]; then
    echo "   created"
elif [ "$status" = "422" ]; then
    echo "   already exists, reusing"
else
    echo "   repo creation failed (HTTP $status):"; head -c 300 "/tmp/create-$$.json"; echo >&2
    exit 2
fi
rm -f "/tmp/create-$$.json"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "== fetching latest V3 tree from $SOURCE_REPO@$SOURCE_BRANCH"
git clone -q --depth 1 -b "$SOURCE_BRANCH" "https://github.com/$SOURCE_REPO.git" "$WORK/src"
cd "$WORK/src"

echo "== installing release workflow"
rm -rf .github/workflows
mkdir -p .github/workflows
cp "$HERE/build-apk.yml" .github/workflows/build-apk.yml

git config user.email "release@rigstudio.local"
git config user.name "RigStudio Release Bot"
git add -A
git commit -q -m "RigStudio V3 release tree (auto-published by tools/release/publish.sh)"

echo "== pushing to $LOGIN/$REPO_NAME (main)"
git push -q --force "https://x-access-token:$TOKEN@github.com/$LOGIN/$REPO_NAME.git" HEAD:refs/heads/main

echo "== waiting for the Actions APK build (this takes a few minutes)"
deadline=$(( $(date +%s) + 2700 ))
run_state=""
while [ "$(date +%s)" -lt "$deadline" ]; do
    run=$(api GET "/repos/$LOGIN/$REPO_NAME/actions/runs?per_page=1")
    run_state=$(printf '%s' "$run" | python3 -c 'import json,sys
d=json.load(sys.stdin)
r=(d.get("workflow_runs") or [{}])[0]
print(r.get("status",""), r.get("conclusion","") or "", r.get("html_url",""))')
    status=$(printf '%s' "$run_state" | cut -d' ' -f1)
    conclusion=$(printf '%s' "$run_state" | cut -d' ' -f2)
    url=$(printf '%s' "$run_state" | cut -d' ' -f3-)
    if [ "$status" = "completed" ]; then
        break
    fi
    printf '   run state: %s\r' "${status:-queued}"
    sleep 20
done

if [ "$conclusion" != "success" ]; then
    echo
    echo "publish.sh: build did not succeed (state='$run_state')."
    run_id=$(api GET "/repos/$LOGIN/$REPO_NAME/actions/runs?per_page=1" | \
        python3 -c 'import json,sys; print((json.load(sys.stdin).get("workflow_runs") or [{}])[0].get("id",""))')
    if [ -n "$run_id" ]; then
        echo "-- failed steps:"
        api GET "/repos/$LOGIN/$REPO_NAME/actions/runs/$run_id/jobs" | python3 -c 'import json,sys
for job in json.load(sys.stdin).get("jobs", []):
    for step in job.get("steps", []):
        if step.get("conclusion") == "failure":
            print("   -", step.get("name"))'
        if command -v unzip >/dev/null 2>&1; then
            echo "-- compiler errors from the log:"
            curl -sL -H "Authorization: token $TOKEN" -H "Accept: application/vnd.github+json" \
                "https://api.github.com/repos/$LOGIN/$REPO_NAME/actions/runs/$run_id/logs" -o "$WORK/logs.zip"
            unzip -p "$WORK/logs.zip" 2>/dev/null | grep -E "^e: |error:|FAILURE:|What went wrong" | tail -40 | sed 's/^/   /'
        fi
    fi
    echo "   Fix the errors (or paste them back for fixes), then re-run this script -"
    echo "   the same download link is republished on success."
    exit 1
fi

echo "== waiting for the release asset"
for _ in $(seq 1 30); do
    asset=$(api GET "/repos/$LOGIN/$REPO_NAME/releases/tags/apk" | \
        python3 -c 'import json,sys
d=json.load(sys.stdin)
a=d.get("assets") or []
print(a[0]["browser_download_url"] if a else "")')
    if [ -n "$asset" ]; then
        echo
        echo "=============================================================="
        echo "  READY - direct download link:"
        echo "  $asset"
        echo "=============================================================="
        exit 0
    fi
    sleep 10
done
echo "publish.sh: release asset not found yet; check the repo's Releases page." >&2
exit 1
