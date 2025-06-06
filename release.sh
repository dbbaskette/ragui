#!/bin/bash
# release.sh - Increment version in pom.xml, commit, push, and tag release
# Usage: ./release.sh

set -e

# 1. Get current branch
git_branch=$(git rev-parse --abbrev-ref HEAD)
echo "Current git branch: $git_branch"

# 2. Get the main artifactId and its version from pom.xml (not the parent)
# Auto-detect the main artifactId (first <artifactId> outside <parent> block)
# Auto-detect the main artifactId (first <artifactId> outside <parent> block)
main_artifact_id=$(awk '
  /<parent>/ {in_parent=1}
  /<\/parent>/ {in_parent=0; next}
  in_parent {next}
  /<artifactId>/ && !found {
    print $0
    found=1
  }
' pom.xml | sed -n 's:.*<artifactId>\([^<]*\)</artifactId>.*:\1:p')

if [[ -z "$main_artifact_id" ]]; then
  echo "Could not auto-detect main artifactId from pom.xml"
  exit 1
fi

# Find the <version> that comes immediately after <artifactId>$main_artifact_id</artifactId>
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

# 4b. Rename main.js to main.$new_version.js and update index.html
STATIC_DIR="src/main/resources/static"
JS_BASENAME="main"
INDEX_HTML="$STATIC_DIR/index.html"

# Find the latest main.*.js file (excluding already-versioned ones if possible)
JS_SRC=$(ls -1t "$STATIC_DIR"/main.*.js 2>/dev/null | head -n1)

if [[ -z "$JS_SRC" ]]; then
  echo "No main.js or main.*.js file found in $STATIC_DIR"
  echo "Build your frontend to generate a JS bundle before running release."
  exit 1
fi

JS_DST="$STATIC_DIR/$JS_BASENAME.$new_version.js"
cp "$JS_SRC" "$JS_DST"
echo "Copied $JS_SRC to $JS_DST"
# Remove all other main.*.js files except the new one
for jsfile in "$STATIC_DIR"/main.*.js; do
  if [[ "$jsfile" != "$JS_DST" ]]; then
    rm "$jsfile"
    echo "Removed old bundle: $jsfile"
  fi
done
# Robustly update index.html to reference the new JS file
if grep -qE '<script src="main\.[^\"]*\.js"></script>' "$INDEX_HTML"; then
  sed -i.bak 's|<script src="main\.[^\"]*\.js"></script>|<script src="main.'"$new_version"'.js"></script>|g' "$INDEX_HTML"
  rm "$INDEX_HTML.bak"
  echo "Updated $INDEX_HTML to reference main.$new_version.js"
else
  echo "Warning: Could not find <script src=\"main.*.js\"></script> in $INDEX_HTML. Please update manually."
fi

# 5. Git add, commit, push
git add pom.xml

git add .

echo -n "Enter a git commit message for this release (leave blank for default): "
read commit_msg
if [[ -z "$commit_msg" ]]; then
  commit_msg="Bump version to v$new_version"
fi

git commit -m "$commit_msg"
git push

echo "Code committed and pushed."

# 6. Tag the release
git tag "v$new_version"
git push origin "v$new_version"

echo "Release tagged as v$new_version and pushed."

# 7. Optionally build and deploy to Cloud Foundry

echo -n "Would you like to build the new JAR and push to Cloud Foundry? [y/N]: "
read deploy_answer
if [[ "$deploy_answer" =~ ^[Yy]$ ]]; then
  echo "Building JAR with mvn clean package..."
  mvn clean package
  echo "Updating manifest.yml with new JAR path: target/${main_artifact_id}-$new_version.jar"
  sed -i.bak "s|path: .*|path: target/${main_artifact_id}-$new_version.jar|" manifest.yml && rm manifest.yml.bak
  echo "Pushing to Cloud Foundry..."
  cf push
else
  echo "Skipping build and Cloud Foundry deployment."
fi

echo "Release process complete."
