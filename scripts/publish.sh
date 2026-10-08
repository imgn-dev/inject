#!/usr/bin/env bash
#
# Walks through publishing be.imgn.inject to Maven Central.
#
# One reactor build publishes six artifacts as ONE deployment:
#
#   be.imgn.inject:inject-parent          the parent POM the modules inherit
#   be.imgn.inject:inject                 runtime and public API
#   be.imgn.inject:inject-processor       annotation processor
#   be.imgn.inject:inject-log             the @Log annotation
#   be.imgn.inject:inject-log-processor   the @Log processor plugin
#   be.imgn.inject:inject-maven-plugin    module-info service registration
#
# The two example modules are built but excluded from the bundle.
#
# Safe to re-run: every step detects what is already done and skips it. Nothing
# becomes public until you press Publish in the Central portal, which this
# script cannot and does not do for you.
#
# Usage:  scripts/publish.sh [releaseVersion] [nextVersion]
#         scripts/publish.sh 0.1.0 0.2.0

# This script is bash, not zsh: it relies on BASH_SOURCE, read -p and <<< .
# Re-exec under bash however it was invoked, so `zsh publish.sh` also works.
if [ -z "${BASH_VERSION:-}" ]; then
    command -v bash >/dev/null 2>&1 || {
        echo 'error bash is required but not installed.' >&2
        exit 1
    }
    exec bash "$0" "$@"
fi

set -euo pipefail

# Diagnostics in English. LC_MESSAGES rather than LC_ALL, which would also
# change the character encoding.
export LC_MESSAGES=C

# pinentry needs to know which terminal to prompt on. Without this, gpg run
# inside a pipeline reports "no passphrase supplied" instead of asking.
if [ -t 0 ] && command -v tty >/dev/null 2>&1; then
    GPG_TTY=$(tty)
    export GPG_TTY
fi

REPO='ogregoire/imgn-inject'
DOMAIN='imgn.be'
NAMESPACE='be.imgn'
KEYSERVER='keyserver.ubuntu.com'
PORTAL='https://central.sonatype.com/publishing/deployments'
VERSION_PATTERN='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

# --- output helpers -----------------------------------------------------------

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m   %s\n' "$*"; }
warn() { printf '  \033[33mnote\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31merror\033[0m %s\n' "$*" >&2; exit 1; }

# Asks a yes/no question. Anything but an explicit y aborts.
confirm() {
    ask_yes "$1" || die 'Stopped at your request.'
}

# Asks a yes/no question and reports the answer instead of aborting.
ask_yes() {
    local answer
    read -r -p "  $1 [y/N] " answer
    [[ $answer == [yY] ]]
}

# --- pure helpers, unit-testable ----------------------------------------------

valid_version() {
    [[ $1 =~ $VERSION_PATTERN ]]
}

# Long key IDs of every secret key, one per line, sorted.
list_secret_key_ids() {
    gpg --list-secret-keys --keyid-format=long --with-colons 2>/dev/null \
        | awk -F: '/^sec/ {print $5}' | sort
}

# Lines in $2 that are absent from $1. Used to spot a freshly created key.
added_lines() {
    grep -vxF -f <(printf '%s\n' "$1") <<<"$2" || true
}

# How many secret keys a pattern matches. An empty or ambiguous pattern makes
# gpg walk every key it knows, prompting for passphrases of unrelated keys.
count_secret_keys_matching() {
    [[ -n ${1:-} ]] || { echo 0; return; }
    gpg --list-secret-keys --with-colons "$1" 2>/dev/null \
        | grep -c '^sec' || true
}

# An armoured private key, or nothing. Never let an empty export reach a secret.
looks_like_private_key() {
    grep -q 'BEGIN PGP PRIVATE KEY BLOCK' <<<"$1"
}

# $1 is a strictly later MAJOR.MINOR.PATCH than $2. sort -V orders the parts
# numerically, so 0.10.0 comes after 0.9.0.
version_is_after() {
    [[ $1 != "$2" && $(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1) == "$1" ]]
}

# --- steps --------------------------------------------------------------------

check_tools() {
    bold '1. Preflight'
    local tool
    for tool in gpg gh git dig; do
        command -v "$tool" >/dev/null || die "$tool is not installed."
    done
    ok 'gpg, gh, git, dig present'

    gh auth status >/dev/null 2>&1 || die 'gh is not authenticated. Run: gh auth login'
    ok "gh authenticated as $(gh api user --jq .login)"

    gh repo view "$REPO" >/dev/null 2>&1 || die "Cannot see $REPO."
    ok "$REPO reachable"

    [[ -z $(git status --porcelain) ]] \
        || die 'Working tree is dirty. Commit or stash first.'
    ok 'working tree clean'

    local branch
    branch=$(git rev-parse --abbrev-ref HEAD)
    [[ $branch == main ]] || die "On branch '$branch'. Release runs from main."
    ok 'on main'

    git fetch --quiet origin main
    [[ -z $(git log origin/main..HEAD --oneline) ]] \
        || die 'You have unpushed commits. Push them first.'
    ok 'in sync with origin/main'
}

check_namespace() {
    bold "2. Namespace $NAMESPACE"
    if dig TXT "$DOMAIN" +short | grep -qv 'v=spf1'; then
        ok "TXT records present on $DOMAIN"
    else
        warn "No non-SPF TXT record on $DOMAIN."
    fi
    info "This script cannot read the Central portal."
    confirm "Does the portal show $NAMESPACE as VERIFIED?"
}

setup_key() {
    # release.sh numbers its own steps around this one.
    bold "${SIGNING_KEY_STEP:-3}. Signing key"
    local before after created generate=no

    before=$(list_secret_key_ids)

    if [[ -z $before ]]; then
        info 'No secret key found.'
        generate=yes
    else
        info 'Secret keys already on this machine:'
        gpg --list-secret-keys --keyid-format=long | sed 's/^/    /'
        info 'A key used only for releases is cleaner than reusing a personal'
        info 'one: revoking it later then affects nothing else you have signed.'
        ask_yes 'Generate a NEW key for signing releases?' && generate=yes
    fi

    if [[ $generate == yes ]]; then
        info 'Choose RSA 4096, and set a passphrase — CI needs one.'
        gpg --full-generate-key
    fi

    after=$(list_secret_key_ids)
    [[ -n $after ]] || die 'No secret key available.'
    created=$(added_lines "$before" "$after")

    if [[ -n $created && $(wc -l <<<"$created") -eq 1 ]]; then
        KEY_ID=$created
        ok "created key $KEY_ID"
    elif [[ $(wc -l <<<"$after") -eq 1 ]]; then
        KEY_ID=$after
        ok "using the only key present, $KEY_ID"
    else
        info 'Which key should sign releases?'
        gpg --list-secret-keys --keyid-format=long | sed 's/^/    /'
        read -r -p '  Key ID: ' KEY_ID
        [[ -n ${KEY_ID:-} ]] || die 'No key ID selected.'
    fi

    local matches
    matches=$(count_secret_keys_matching "$KEY_ID")
    [[ $matches -eq 1 ]] \
        || die "'$KEY_ID' matches $matches secret keys; it must match exactly one."

    info 'Signing identity:'
    gpg --list-keys --keyid-format=long "$KEY_ID" | sed 's/^/    /' \
        || die "No such key: $KEY_ID"
    confirm 'Sign releases with that key?'

    info "Publishing the public half to $KEYSERVER."
    info 'Central rejects any bundle whose public key it cannot find.'
    gpg --keyserver "$KEYSERVER" --send-keys "$KEY_ID" \
        || warn 'Send failed. Retry manually before releasing.'
}

set_secrets() {
    bold '4. Repository secrets'
    local existing
    existing=$(gh secret list --repo "$REPO" --json name --jq '.[].name' 2>/dev/null || true)

    set_secret() {
        local name=$1 prompt=$2 value
        if grep -qx "$name" <<<"$existing"; then
            info "$name already set — skipping. Delete it in GitHub to replace."
            return
        fi
        # -s: never echoed, never in shell history, never written to disk.
        read -r -s -p "  $prompt: " value
        printf '\n'
        [[ -n $value ]] || die "$name cannot be empty."
        printf '%s' "$value" | gh secret set "$name" --repo "$REPO"
        unset value
        ok "$name set"
    }

    set_secret CENTRAL_TOKEN_USERNAME 'Central token username'
    set_secret CENTRAL_TOKEN_PASSWORD 'Central token password'
    set_secret GPG_PASSPHRASE         "Passphrase for key $KEY_ID"

    if grep -qx GPG_PRIVATE_KEY <<<"$existing"; then
        info 'GPG_PRIVATE_KEY already set — skipping.'
    else
        local exported
        info "Unlocking key $KEY_ID. gpg will ask for THAT key's passphrase."
        # Captured, not piped straight to gh: a failed export prints a warning
        # and produces nothing, which would otherwise store an empty secret.
        exported=$(gpg --armor --export-secret-keys "$KEY_ID") || true
        looks_like_private_key "$exported" \
            || die "Export of $KEY_ID produced no key. Wrong passphrase, or gpg could not prompt."
        printf '%s' "$exported" | gh secret set GPG_PRIVATE_KEY --repo "$REPO"
        unset exported
        ok 'GPG_PRIVATE_KEY set'
    fi
}

export_vault_bundle() {
    bold '5. Vault bundle'
    info 'GitHub secrets cannot be read back, so this is your only copy of the'
    info 'key outside ~/.gnupg. Without it a lost machine means a lost identity.'
    ask_yes 'Write the bundle now?' || { info 'Skipped.'; return; }

    local fingerprint revocation bundle
    fingerprint=$(gpg --list-keys --with-colons "$KEY_ID" \
                  | awk -F: '/^fpr/ {print $10; exit}')
    [[ -n $fingerprint ]] || die "Cannot read the fingerprint of $KEY_ID."
    revocation="$HOME/.gnupg/openpgp-revocs.d/$fingerprint.rev"
    bundle="$HOME/imgn-inject-vault-$KEY_ID.txt"

    if [[ -e $bundle ]]; then
        confirm "$bundle exists. Overwrite?"
    fi

    # Created 0600 from the outset, never briefly world-readable.
    (
        umask 077
        {
            echo "be.imgn.inject signing material"
            echo "Repository : $REPO"
            echo "Key ID     : $KEY_ID"
            echo "Fingerprint: $fingerprint"
            echo
            echo "The passphrase is deliberately NOT in this file. Store it as a"
            echo "separate field in your vault, so one leaked file is not enough."
            echo
            echo "To restore on a new machine:"
            echo "  gpg --import <this file>"
            echo
            echo "----- PRIVATE KEY -----"
            gpg --armor --export-secret-keys "$KEY_ID" || true
            echo
            echo "----- PUBLIC KEY -----"
            gpg --armor --export "$KEY_ID"
            echo
            echo "----- REVOCATION CERTIFICATE -----"
            if [[ -r $revocation ]]; then
                cat "$revocation"
            else
                echo "(none found at $revocation)"
            fi
        } >"$bundle"
    )

    chmod 600 "$bundle"
    grep -q 'BEGIN PGP PRIVATE KEY BLOCK' "$bundle" \
        || die "No private key landed in $bundle. Remove it and retry."
    ok "written to $bundle"
    info 'Move it into your vault now, then remove the copy on disk:'
    info "  rm -P '$bundle'"
    info 'It is unencrypted: anyone reading it holds your signing identity.'
    confirm 'Stored it in your vault?'
}

run_release() {
    bold '6. Release'
    info "Release version : $RELEASE_VERSION"
    info "Next version    : $NEXT_VERSION-SNAPSHOT"
    info "Artifacts       : be.imgn.inject:inject-parent, inject, inject-processor,"
    info "                  inject-log, inject-log-processor, inject-maven-plugin"
    info 'This publishes signed artifacts to Central, tags the commit and pushes.'
    info 'Nothing becomes public until you press Publish in the portal.'
    confirm 'Start the release workflow?'

    gh workflow run release.yml --repo "$REPO" \
        -f "releaseVersion=$RELEASE_VERSION" \
        -f "nextVersion=$NEXT_VERSION"

    info 'Waiting for the run to appear.'
    local id='' attempt
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        sleep 3
        id=$(gh run list --repo "$REPO" --workflow release.yml \
             --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)
        [[ -n $id && $id != null ]] && break
    done
    [[ -n $id && $id != null ]] || die 'Run did not appear. Check the Actions tab.'

    ok "watching run $id"
    if gh run watch "$id" --repo "$REPO" --exit-status --interval 15; then
        bold 'Published to the staging area'
        info "Open $PORTAL"
        info "Expect ONE deployment, be.imgn.inject:$RELEASE_VERSION, holding six components:"
        info '  inject-parent                       pom only'
        info '  inject, inject-processor, inject-log, inject-log-processor,'
        info '  inject-maven-plugin                 jar, sources, javadoc'
        info 'No inject-examples or inject-examples-module: they are excluded.'
        info 'Inspect the uploaded files first — that button is the point of no return.'
    else
        die "Run $id failed. Inspect it with: gh run view $id --repo $REPO --log-failed"
    fi
}

# --- entry point --------------------------------------------------------------

main() {
    RELEASE_VERSION=${1:-}
    NEXT_VERSION=${2:-}

    [[ -n $RELEASE_VERSION && -n $NEXT_VERSION ]] \
        || die "Usage: $0 <releaseVersion> <nextVersion>   e.g. $0 0.1.0 0.2.0"

    valid_version "$RELEASE_VERSION" \
        || die "releaseVersion '$RELEASE_VERSION' must be MAJOR.MINOR.PATCH, e.g. 0.1.0."
    valid_version "$NEXT_VERSION" \
        || die "nextVersion '$NEXT_VERSION' must be MAJOR.MINOR.PATCH, e.g. 0.2.0."
    version_is_after "$NEXT_VERSION" "$RELEASE_VERSION" \
        || die "nextVersion must be later than releaseVersion."

    check_tools
    check_namespace
    setup_key
    set_secrets
    export_vault_bundle
    run_release
}

# Only run when executed, so the helpers above can be sourced and tested.
if [[ ${BASH_SOURCE[0]} == "${0}" ]]; then
    main "$@"
fi
