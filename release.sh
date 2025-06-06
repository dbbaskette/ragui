#!/bin/bash
# release.sh - Increment version in pom.xml, commit, push, and tag release
# Usage: ./release.sh

set -e

# 1. Get current branch
git_branch=$(git rev-parse --abbrev-ref HEAD)
echo "Current git branch: $git_branch"

# 2. Get the main artifactId and its version from pom.xml (not the parent)
main_artifact_id="ragui"
# Find the <version> that comes immediately after <artifactId>ragui</artifactId>
current_version=$(awk '/<artifactId>'"$main_artifact_id"'<\/artifactId>/{getline; while (!/<version>/) getline; gsub(/.*<version>|<\/version>.*/, ""); print $0; exit}' pom.xml)
echo "Main artifactId: $main_artifact_id"
echo "Current version: $current_version"

# 3. Increment patch version (x.y.z -> x.y.$((z+1)))
IFS='.' read -r major minor patch <<< "$current_version"
if [[ -z "$patch" ]]; then
  echo "Could not parse version from pom.xml"
  exit 1
fi
next_patch=$((patch+1))
new_version="$major.$minor.$next_patch"
echo "Bumping version to: $new_version"

echo -n "Proceed with release v$new_version? [y/N]: "
read answer
if [[ ! "$answer" =~ ^[Yy]$ ]]; then
  echo "Aborted by user. No changes made."
  exit 0
fi

# 4. Update only the correct <version> tag in pom.xml (the one after <artifactId>ragui</artifactId>)
awk -v aid="$main_artifact_id" -v newver="$new_version" '
  BEGIN {found=0}
  /<artifactId>/ && $0 ~ aid {
    found=1
    print
    next
  }
  found && /<version>/ {
    sub(/<version>[^<]+<\/version>/, "<version>" newver "</version>")
    found=0
  }
  {print}
' pom.xml > pom.xml.tmp && mv pom.xml.tmp pom.xml

echo "pom.xml updated."

# 5. Git add, commit, push
git add pom.xml

git add .
git commit -m "Release v$new_version"
git push

echo "Code committed and pushed."

# 6. Tag the release
git tag "v$new_version"
git push origin "v$new_version"

echo "Release tagged as v$new_version and pushed."

# 7. Done
echo "Release process complete."
