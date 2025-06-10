#!/bin/bash
# release.sh - Increment version in pom.xml, commit, push, and tag release
# Usage: ./release.sh [--test]

set -e

if [[ "$1" == "--local" ]]; then
  echo -e "\033[1;36mRunning in LOCAL DEV mode: will build and run the app locally. Backend services may not be available.\033[0m"
  JAR_NAME=$(ls target/*.jar 2>/dev/null | grep -v '.original' | head -1)
  if [[ ! -f "$JAR_NAME" ]]; then
    echo "No built JAR found, building with mvn clean package..."
    mvn clean package
    JAR_NAME=$(ls target/*.jar 2>/dev/null | grep -v '.original' | head -1)
  fi
  if [[ -f "$JAR_NAME" ]]; then
    echo -e "\033[1;32mLaunching: java -jar $JAR_NAME\033[0m"
    java -jar "$JAR_NAME"
  else
    echo "Could not find built JAR in target/. Exiting."
    exit 1
  fi
  exit 0
fi

if [[ "$1" == "--test" ]]; then
  echo "Running in TEST mode: will only build and push, no version bump or git commit."
  # Parse app name from manifest.yml
  app_name=$(grep 'name:' manifest.yml | head -1 | sed 's/.*name:[[:space:]]*//')
  if [[ -n "$app_name" ]]; then
    # Print delete step in bold yellow with separators for visibility
    YELLOW='\033[1;33m'
    NC='\033[0m' # No Color
    echo -e "${YELLOW}==============================${NC}"
    echo -e "${YELLOW}Deleting existing CF app: $app_name (to avoid route caching)${NC}"
    echo -e "${YELLOW}==============================${NC}"
    cf delete "$app_name" -f -r || true
    echo -e "${YELLOW}-------- Delete step complete --------${NC}"

  fi
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
  current_version=$(awk '/<artifactId>'"$main_artifact_id"'<\/artifactId>/{getline; while (!/<version>/) getline; gsub(/.*<version>|<\/version>.*/, ""); print $0; exit}' pom.xml)
  echo "Main artifactId: $main_artifact_id"
  echo "Current version: $current_version"

  # Ensure only one MAIN_JS_VERSION assignment at the top of main.js
  mainjs="src/main/resources/static/main.js"
  tmpfile="${mainjs}.tmp"
  # Remove any existing MAIN_JS_VERSION assignment
  grep -v '^const MAIN_JS_VERSION = ' "$mainjs" > "$tmpfile" || true
  # Insert the correct version at the top
  echo "const MAIN_JS_VERSION = \"$current_version\";" > "${mainjs}.new"
  cat "$tmpfile" >> "${mainjs}.new"
  mv "${mainjs}.new" "$mainjs"
  rm -f "$tmpfile"

  echo "Building JAR with mvn clean package..."
  mvn clean package
  echo "Ensuring manifest.yml uses current JAR: target/${main_artifact_id}-$current_version.jar"
  sed -i.bak "s|path: .*|path: target/${main_artifact_id}-$current_version.jar|" manifest.yml && rm manifest.yml.bak
  echo "Pushing to Cloud Foundry..."
  cf push
  echo "Test build and push complete."
  exit 0
fi

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

# 4b. Ensure main.js exists and is referenced in index.html
STATIC_DIR="src/main/resources/static"
INDEX_HTML="$STATIC_DIR/index.html"

JS_SRC="$STATIC_DIR/main.js"
if [[ ! -f "$JS_SRC" ]]; then
  echo "main.js not found in $STATIC_DIR. Build your frontend before running release."
  exit 1
fi

# Ensure index.html references main.js
if grep -q '<script src="main.js"></script>' "$INDEX_HTML"; then
  echo "index.html already references main.js"
else
  # Replace any script tag referencing main.*.js with main.js
  sed -i.bak 's|<script src="main\.[^"]*\.js"></script>|<script src="main.js"></script>|g' "$INDEX_HTML"
  rm "$INDEX_HTML.bak"
  echo "Updated $INDEX_HTML to reference main.js"
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
