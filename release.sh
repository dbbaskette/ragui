#!/bin/bash
# release.sh - Increment version in pom.xml, commit, push, and tag release
# Usage: ./release.sh

set -e

# 1. Get current branch
git_branch=$(git rev-parse --abbrev-ref HEAD)
echo "Current git branch: $git_branch"

# 2. Get current version from pom.xml
current_version=$(xmllint --xpath 'string(//_:project/_:version)' pom.xml 2>/dev/null || grep -m1 '<version>' pom.xml | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/')
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

# 4. Update pom.xml version
sed -i.bak "0,/<version>$current_version<\\/version>/s//<version>$new_version<\\/version>/" pom.xml
rm pom.xml.bak

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
