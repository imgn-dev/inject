#!/usr/bin/env bash
#
# Maintains the documentation in a checkout of the gh-pages branch, served at
# https://imgn-dev.github.io/inject/:
#
#   api/<version>/   the API documentation of each released version, never
#                    changed once published, unless explicitly replaced
#   api/latest/      a copy of the newest version, rebuilt from scratch every
#                    time, so that nothing of an earlier version lingers in it
#   api/snapshot/    the API documentation of the main branch, replaced as a
#                    whole on every push to it
#   api/index.html   the list of versions, the snapshot first, then the
#                    releases from the newest
#   everything else  the guide (docs/target/guide), replaced as a whole, so
#                    that no page of an earlier guide lingers
#
# Usage:  scripts/docs-site.sh publish <site> <version> <javadoc> [--replace]
#         scripts/docs-site.sh remove  <site> <version>
#         scripts/docs-site.sh guide   <site> <guide> [<version>]
#         scripts/docs-site.sh snapshot <site> <javadoc>
#
# With a version, guide publishes the guide only if that version is the newest:
# publishing an older version again must not bring back its guide.

set -euo pipefail

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

usage() {
    die "Usage: $0 publish <site> <version> <javadoc> [--replace]
       $0 remove  <site> <version>
       $0 guide   <site> <guide> [<version>]
       $0 snapshot <site> <javadoc>"
}

VERSION_PATTERN='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

# The released versions in the site, oldest first.
versions() {
    local dir
    for dir in "$site"/api/*/; do
        dir=${dir%/}
        dir=${dir##*/}
        if [[ $dir =~ $VERSION_PATTERN ]]; then
            printf '%s\n' "$dir"
        fi
    done | sort -V
}

refresh() {
    local all newest version snapshot
    all=$(versions)
    newest=$(tail -n 1 <<<"$all")
    # The version, such as 0.1.2-SNAPSHOT, is in the title javadoc gives the overview.
    snapshot=$(sed -nE 's|.*<title>.*\(imgn inject (.+) API\)</title>.*|\1|p' "$site/api/snapshot/index.html" 2>/dev/null \
        | head -n 1 || true)

    rm -rf "$site/api/latest"
    [[ -n $newest ]] && cp -R "$site/api/$newest" "$site/api/latest"

    {
        cat <<'EOF'
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>imgn inject API documentation</title>
</head>
<body>
<h1>imgn inject API documentation</h1>
<ul>
EOF
        if [[ -d $site/api/snapshot ]]; then
            printf '<li><a href="snapshot/">Snapshot%s</a></li>\n' "${snapshot:+ ($snapshot)}"
        fi
        if [[ -n $newest ]]; then
            printf '<li><a href="latest/">Latest (%s)</a></li>\n' "$newest"
            for version in $(sort -rV <<<"$all"); do
                printf '<li><a href="%s/">%s</a></li>\n' "$version" "$version"
            done
        fi
        cat <<'EOF'
</ul>
</body>
</html>
EOF
    } >"$site/api/index.html"

    # GitHub Pages serves the files as they are, without Jekyll.
    touch "$site/.nojekyll"
}

check_version() {
    [[ $1 =~ $VERSION_PATTERN ]] || die "Version '$1' must be MAJOR.MINOR.PATCH, e.g. 0.1.0."
}

[[ $# -ge 3 ]] || usage
action=$1
site=$2
[[ -d $site ]] || die "$site is not a directory."

case $action in
publish)
    [[ $# -eq 4 || ( $# -eq 5 && $5 == --replace ) ]] || usage
    version=$3
    check_version "$version"
    javadoc=$4
    [[ -f $javadoc/index.html ]] || die "$javadoc has no index.html."
    if [[ -e $site/api/$version && $# -eq 4 ]]; then
        die "The documentation of $version is already published. Pass --replace to replace it."
    fi
    mkdir -p "$site/api"
    rm -rf "$site/api/$version"
    cp -R "$javadoc" "$site/api/$version"
    ;;
guide)
    [[ $# -eq 3 || $# -eq 4 ]] || usage
    guide=$3
    [[ -f $guide/index.html ]] || die "$guide has no index.html."
    # The guide links to the API documentation of the newest version.
    newest=$(versions | tail -n 1)
    [[ -n $newest ]] || die "No API documentation is published: publish a version first."
    if [[ $# -eq 4 ]]; then
        check_version "$4"
        if [[ $4 != "$newest" ]]; then
            printf 'The guide of %s is not published: the newest version is %s.\n' "$4" "$newest"
            exit 0
        fi
    fi
    find "$site" -mindepth 1 -maxdepth 1 ! -name api ! -name .nojekyll ! -name .git -exec rm -rf {} +
    cp -R "$guide"/. "$site"/
    exit 0
    ;;
snapshot)
    [[ $# -eq 3 ]] || usage
    javadoc=$3
    [[ -f $javadoc/index.html ]] || die "$javadoc has no index.html."
    mkdir -p "$site/api"
    rm -rf "$site/api/snapshot"
    cp -R "$javadoc" "$site/api/snapshot"
    ;;
remove)
    [[ $# -eq 3 ]] || usage
    version=$3
    check_version "$version"
    [[ -e $site/api/$version ]] || die "The documentation of $version is not published."
    rm -rf "$site/api/$version"
    ;;
*)
    usage
    ;;
esac

refresh
