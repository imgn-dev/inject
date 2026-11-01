#!/usr/bin/env bash
#
# Builds the API documentation of the published modules of an inject checkout
# as one site, the way the JDK documentation is built: a single javadoc run over
# all modules, so that the site has one module index, one search over every
# module, and links from one module to another and to the JDK.
#
# The modules are inject and inject-processor, the published modules with a
# module descriptor. inject-maven-plugin is used through its goals, not as an
# API.
#
# It works on the sources of any tag, so that older versions can be documented
# with the script of today.
#
# Usage:  scripts/javadoc.sh <checkout> <output directory>
#         scripts/javadoc.sh . target/apidocs

set -euo pipefail

# The JDK whose API the documentation links to: the release inject compiles for.
JDK=21
# The element-list of its documentation, committed beside this script, so that
# building the documentation does not depend on docs.oracle.com being reachable:
# with -Werror, javadoc fails when it cannot fetch it. Copied from
# https://docs.oracle.com/en/java/javase/21/docs/api/element-list; the links
# still go to docs.oracle.com.
JDK_ELEMENT_LIST_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/jdk-$JDK
MODULE_DIRS=(inject inject-processor)

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

[[ $# -eq 2 ]] || die "Usage: $0 <checkout> <output directory>"
checkout=$(cd "$1" && pwd)
output=$2
[[ -f $checkout/pom.xml && -x $checkout/mvnw ]] || die "$checkout is not an inject checkout."
# The project's version is the first <version> after the parent's.
version=$(awk '/<\/parent>/ { found = 1 } found && /<version>/ { gsub(/.*<version>|<\/version>.*/, ""); print; exit }' \
    "$checkout/pom.xml")
[[ -n $version ]] || die "No version in $checkout/pom.xml."

modules=()
source_paths=()
for dir in "${MODULE_DIRS[@]}"; do
    descriptor=$checkout/$dir/src/main/java/module-info.java
    [[ -f $descriptor ]] || die "No $descriptor."
    # The module name follows the keyword, after an optional "open".
    name=$(grep -m 1 -oE '^(open[[:space:]]+)?module[[:space:]]+[A-Za-z0-9_.]+' "$descriptor" | awk '{print $NF}')
    [[ -n $name ]] || die "No module declaration in $descriptor."
    modules+=("$name")
    source_paths+=(--module-source-path "$name=$checkout/$dir/src/main/java")
done
[[ -f $JDK_ELEMENT_LIST_DIR/element-list ]] || die "No $JDK_ELEMENT_LIST_DIR/element-list."

# The third-party modules the inject modules require, from each module's compile
# class path. The inject modules themselves come from their sources: compiling
# lets the reactor resolve them without their version being in a repository.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
(cd "$checkout" && ./mvnw -B -q --no-transfer-progress -DskipTests \
    -pl "$(IFS=,; echo "${MODULE_DIRS[*]}")" -am compile \
    dependency:build-classpath -DincludeScope=compile \
    -Dmdep.outputFile="$work/classpath-\${project.artifactId}.txt")
# The files end without a newline: awk adds it, so that the last entry of one
# file and the first of the next stay apart.
module_path=$(awk 1 "$work"/classpath-*.txt | tr ':' '\n' \
    | grep -v -e '^$' -e '/be/imgn/inject/' -e '/target/classes' | sort -u | paste -sd: -)

rm -rf "$output"
# The same checks as the root POM's javadoc configuration: -Werror for
# failOnWarnings, -Xdoclint:all for doclint.
javadoc -d "$output" -quiet -notimestamp -Werror -Xdoclint:all \
    --release "$JDK" \
    "${source_paths[@]}" \
    --module "$(IFS=,; echo "${modules[*]}")" \
    ${module_path:+--module-path "$module_path"} \
    -linkoffline "https://docs.oracle.com/en/java/javase/$JDK/docs/api/" "$JDK_ELEMENT_LIST_DIR" \
    -doctitle "imgn inject $version API" \
    -windowtitle "imgn inject $version API"
