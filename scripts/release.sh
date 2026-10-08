#!/usr/bin/env bash
#
# Releases be.imgn.inject to Maven Central from this machine, without GitHub
# Actions. It does what .github/workflows/release.yml does, in the same order:
#
#   set the release version, verify, deploy with -Prelease (sources, javadoc,
#   GPG signature, upload to the Central portal), commit and tag, set the next
#   -SNAPSHOT version, commit, push, and create the GitHub release.
#
# Nothing becomes public until you press Publish in the Central portal:
# autoPublish is false in be.imgn.parent:parent.
#
# Credentials are prompted for and never written to disk. They live only in the
# environment of the Maven process; scripts/release-settings.xml reads the
# Central token from it, and maven-gpg-plugin reads MAVEN_GPG_PASSPHRASE. Set
# CENTRAL_TOKEN_USERNAME, CENTRAL_TOKEN_PASSWORD and MAVEN_GPG_PASSPHRASE
# beforehand to skip the prompts.
#
# Usage:  scripts/release.sh [--dry-run] <releaseVersion> <nextVersion>
#         scripts/release.sh --dry-run 0.1.0 0.2.0
#         scripts/release.sh 0.1.0 0.2.0
#
# --dry-run sets the release version, verifies and signs everything exactly as
# a release would, then restores the POMs. It uploads nothing and commits
# nothing: run it first, to prove the key, the passphrase and the build.

if [ -z "${BASH_VERSION:-}" ]; then
    command -v bash >/dev/null 2>&1 || {
        echo 'error bash is required but not installed.' >&2
        exit 1
    }
    exec bash "$0" "$@"
fi

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"

# Output helpers, version checks and the signing-key walkthrough are shared with
# publish.sh, which does not run anything when sourced.
# shellcheck source=publish.sh
source "$ROOT/scripts/publish.sh"

# The ERR trap that restores the POMs must also fire inside functions.
set -o errtrace

MVN=("$ROOT/mvnw" -B --no-transfer-progress)
SIGNING_KEY_STEP=2

# --- steps --------------------------------------------------------------------

preflight() {
    bold '1. Preflight'
    local tool
    for tool in git gpg java; do
        command -v "$tool" >/dev/null || die "$tool is not installed."
    done
    ok 'git, gpg, java present'

    local feature
    feature=$(java -XshowSettings:properties -version 2>&1 \
              | awk -F'= ' '/java.specification.version/ {print $2}')
    [[ ${feature%%.*} -ge 24 ]] \
        || die "JDK $feature found; the build needs JDK 24 or later (25 recommended)."
    ok "JDK $feature"

    [[ -z $(git status --porcelain) ]] \
        || die 'Working tree is dirty. Commit or stash first.'
    ok 'working tree clean'

    local branch
    branch=$(git rev-parse --abbrev-ref HEAD)
    [[ $branch == main ]] || die "On branch '$branch'. Release runs from main."
    ok 'on main'

    if [[ $DRY_RUN == no ]]; then
        git fetch --quiet origin main
        [[ -z $(git log HEAD..origin/main --oneline) ]] \
            || die 'origin/main has commits you do not. Pull first.'
        [[ -z $(git log origin/main..HEAD --oneline) ]] \
            || die 'You have unpushed commits. Push them first.'
        ok 'in sync with origin/main'

        if git rev-parse -q --verify "refs/tags/v$RELEASE_VERSION" >/dev/null \
            || git ls-remote --exit-code --tags origin "v$RELEASE_VERSION" >/dev/null 2>&1; then
            die "Tag v$RELEASE_VERSION already exists."
        fi
        ok "tag v$RELEASE_VERSION is free"
    fi
}

credentials() {
    bold '3. Credentials'
    prompt_secret() {
        local name=$1 prompt=$2 value
        if [[ -n ${!name:-} ]]; then
            ok "$name taken from the environment"
            return
        fi
        # -s: never echoed, never in shell history, never written to disk.
        read -r -s -p "  $prompt: " value
        printf '\n'
        [[ -n $value ]] || die "$name cannot be empty."
        export "$name=$value"
        ok "$name set for this run"
    }

    if [[ $DRY_RUN == no ]]; then
        info 'A Central Portal user token: https://central.sonatype.com/account'
        prompt_secret CENTRAL_TOKEN_USERNAME 'Central token username'
        prompt_secret CENTRAL_TOKEN_PASSWORD 'Central token password'
    fi
    prompt_secret MAVEN_GPG_PASSPHRASE "Passphrase for key $KEY_ID"
}

# Restores the POMs to the committed state. Safe because preflight demands a
# clean tree, so every change in it was made by this script.
restore_poms() {
    git checkout --quiet -- .
}

build() {
    bold '4. Build'
    info "Setting version $RELEASE_VERSION"
    "${MVN[@]}" versions:set -DnewVersion="$RELEASE_VERSION"

    # On any failure from here on, put the POMs back.
    trap 'warn "Failed: restoring the POMs."; restore_poms' ERR

    info 'Verifying: the full pipeline, integration tests included'
    "${MVN[@]}" clean verify

    if [[ $DRY_RUN == yes ]]; then
        info 'Signing, as -Prelease would, without uploading'
        "${MVN[@]}" -Prelease -DskipTests -Dinvoker.skip=true -Dgpg.keyname="$KEY_ID" verify
        trap - ERR
        restore_poms
        bold 'Dry run passed'
        info 'Every artifact built, verified and signed. POMs restored; nothing uploaded.'
        local module
        for module in inject inject-processor inject-log inject-log-processor inject-maven-plugin; do
            info "  $module: $(ls "$module/target/"*.asc 2>/dev/null | wc -l | tr -d ' ') signatures"
        done
        exit 0
    fi

    info 'Deploying to the Central portal'
    "${MVN[@]}" -s "$ROOT/scripts/release-settings.xml" -Prelease -DskipTests -Dinvoker.skip=true \
        -Dgpg.keyname="$KEY_ID" deploy
    trap - ERR
    ok 'uploaded; waiting in the portal'
}

tag_and_bump() {
    bold '5. Tag and next version'
    # Publishing has happened. Nothing below may undo it, so no trap restores.
    if git diff --quiet; then
        info "Already at $RELEASE_VERSION, nothing to commit."
    else
        git commit --quiet -am "Release $RELEASE_VERSION"
    fi
    git tag -a "v$RELEASE_VERSION" -m "Release $RELEASE_VERSION"
    ok "tagged v$RELEASE_VERSION"

    local next="$NEXT_VERSION-SNAPSHOT"
    "${MVN[@]}" versions:set -DnewVersion="$next"
    git commit --quiet -am "Start $next"
    ok "started $next"

    confirm "Push both commits and tag v$RELEASE_VERSION to origin?"
    git push --quiet origin HEAD --follow-tags
    ok 'pushed'

    if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
        # A release is created through the API: it uses no Actions minutes.
        gh release create "v$RELEASE_VERSION" --repo "$REPO" \
            --title "v$RELEASE_VERSION" --generate-notes
        ok 'GitHub release created'
    else
        warn "gh unavailable: create the GitHub release for v$RELEASE_VERSION by hand."
    fi
}

# --- entry point --------------------------------------------------------------

release_main() {
    DRY_RUN=no
    if [[ ${1:-} == --dry-run ]]; then
        DRY_RUN=yes
        shift
    fi
    RELEASE_VERSION=${1:-}
    NEXT_VERSION=${2:-}

    [[ -n $RELEASE_VERSION && -n $NEXT_VERSION ]] \
        || die "Usage: $0 [--dry-run] <releaseVersion> <nextVersion>   e.g. $0 0.1.0 0.2.0"
    valid_version "$RELEASE_VERSION" \
        || die "releaseVersion '$RELEASE_VERSION' must be MAJOR.MINOR.PATCH, e.g. 0.1.0."
    valid_version "$NEXT_VERSION" \
        || die "nextVersion '$NEXT_VERSION' must be MAJOR.MINOR.PATCH, e.g. 0.2.0."
    version_is_after "$NEXT_VERSION" "$RELEASE_VERSION" \
        || die 'nextVersion must be later than releaseVersion.'

    preflight

    info 'To reuse an existing be.imgn signing key, import it before continuing:'
    info '  gpg --import <exported key or vault bundle>'
    setup_key

    credentials

    if [[ $DRY_RUN == no ]]; then
        bold 'Ready'
        info "Release $RELEASE_VERSION, then $NEXT_VERSION-SNAPSHOT, signed with $KEY_ID."
        info 'This uploads signed artifacts to the Central portal, then tags and pushes.'
        confirm 'Release now?'
    fi

    build
    tag_and_bump

    bold "Released $RELEASE_VERSION to the staging area"
    info "Open $PORTAL"
    info "Expect ONE deployment, be.imgn.inject:$RELEASE_VERSION, holding six components:"
    info '  inject-parent                       pom only'
    info '  inject, inject-processor, inject-log, inject-log-processor,'
    info '  inject-maven-plugin                 jar, sources, javadoc'
    info 'Inspect the files, then press Publish. That button is the point of no return.'
}

release_main "$@"
