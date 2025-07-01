#!/bin/bash
# release.sh - Increment version in pom.xml, commit, push, and tag release
# Usage: ./release.sh [--release VERSION] [--jar-only]
# Examples:
#   ./release.sh                  # Auto-increment patch version
#   ./release.sh --release 1.0.2  # Set specific version
#   ./release.sh --jar-only       # Skip version increment, just build and push JAR

set -e

# Parse command line arguments
RELEASE_VERSION=""
JAR_ONLY=false
while [[ $# -gt 0 ]]; do
  case $1 in
    --release)
      RELEASE_VERSION="$2"
      shift 2
      ;;
    --jar-only)
      JAR_ONLY=true
      shift
      ;;
    -h|--help)
      echo "Usage: $0 [--release VERSION] [--jar-only]"
      echo ""
      echo "Options:"
      echo "  --release VERSION    Set specific version instead of auto-incrementing"
      echo "  --jar-only          Skip version increment, just build and push JAR"
      echo "  -h, --help          Show this help message"
      echo ""
      echo "Examples:"
      echo "  $0                    # Auto-increment patch version"
      echo "  $0 --release 1.0.2    # Set specific version"
      echo "  $0 --jar-only         # Just build and push JAR for current version"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      echo "Use --help for usage information"
      exit 1
      ;;
  esac
done

# Validate release version format if provided
if [[ -n "$RELEASE_VERSION" ]]; then
  if [[ ! "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Error: Release version must be in format x.y.z (e.g., 1.0.2)"
    exit 1
  fi
fi

# 1. Get current branch
git_branch=$(git rev-parse --abbrev-ref HEAD)
echo "Current git branch: $git_branch"

# 2. Get the main artifactId and its version from pom.xml (not the parent)
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

# 3. Handle JAR-only mode
if [[ "$JAR_ONLY" == true ]]; then
  echo "JAR-only mode: Using current version $current_version"
  new_version="$current_version"
  
  # Check if we're up to date with remote
  git fetch
  if git diff --quiet HEAD origin/$git_branch; then
    echo "✅ Repository is up to date with remote."
  else
    echo "⚠️  Warning: Local branch has changes compared to remote."
    echo "Consider committing and pushing changes before creating release."
    echo -n "Continue anyway? [y/N]: "
    read continue_answer
    if [[ ! "$continue_answer" =~ ^[Yy]$ ]]; then
      echo "Aborted by user."
      exit 0
    fi
  fi
  
  # Skip to JAR creation
  skip_version_update=true
else
  skip_version_update=false
fi

# 4. Determine new version (if not JAR-only mode)
if [[ "$skip_version_update" == false ]]; then
  if [[ -n "$RELEASE_VERSION" ]]; then
    new_version="$RELEASE_VERSION"
    echo "Setting version to: $new_version (specified via --release flag)"
  else
    # Increment patch version (x.y.z -> x.y.$((z+1)))
    IFS='.' read -r major minor patch <<< "$current_version"
    if [[ -z "$patch" ]]; then
      echo "Could not parse version from pom.xml"
      exit 1
    fi
    next_patch=$((patch+1))
    new_version="$major.$minor.$next_patch"
    echo "Bumping version to: $new_version (auto-incremented)"
  fi

  echo -n "Proceed with release v$new_version? [y/N]: "
  read answer
  if [[ ! "$answer" =~ ^[Yy]$ ]]; then
    echo "Aborted by user. No changes made."
    exit 0
  fi

  # 5. Update only the correct <version> tag in pom.xml (the one after <artifactId>$main_artifact_id</artifactId>)
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

  # 6. Git add, commit, push (only if there are changes)
  if git diff --quiet; then
    echo "No changes to commit."
  else
    git add pom.xml
    git add .
    read -p "Enter commit message: " commit_msg
    if [ -z "$commit_msg" ]; then
      commit_msg="Release v$new_version"
    fi
    git commit -m "$commit_msg"
    git push
    echo "Code committed and pushed."
  fi

  # 7. Tag the release (only if tag doesn't exist)
  if git tag -l | grep -q "^v$new_version$"; then
    echo "Tag v$new_version already exists."
  else
    git tag "v$new_version"
    git push origin "v$new_version"
    echo "Release tagged as v$new_version and pushed."
  fi
fi

# 8. Always offer to create/update GitHub release and upload JAR
echo -n "Would you like to create/update GitHub release and upload the JAR? [y/N]: "
read gh_release_answer
if [[ "$gh_release_answer" =~ ^[Yy]$ ]]; then
  echo "Building JAR with mvn clean package..."
  mvn clean package -q
  JAR_PATH="target/${main_artifact_id}-$new_version.jar"
  if [[ ! -f "$JAR_PATH" ]]; then
    echo "JAR file $JAR_PATH not found! Aborting release upload."
    exit 1
  fi
  echo "JAR built successfully: $JAR_PATH"
  
  # Check if GitHub CLI is available
  if ! command -v gh &> /dev/null; then
    echo "GitHub CLI (gh) not found. Please install it to upload releases."
    echo "JAR is available at: $JAR_PATH"
    exit 1
  fi
  
  echo "Creating/updating GitHub release v$new_version and uploading $JAR_PATH..."
  # Create release if not exists, else update assets
  if ! gh release view "v$new_version" >/dev/null 2>&1; then
    gh release create "v$new_version" "$JAR_PATH" --title "Release v$new_version" --notes "Release $new_version"
    echo "✅ GitHub release v$new_version created with JAR."
  else
    gh release upload "v$new_version" "$JAR_PATH" --clobber
    echo "✅ GitHub release v$new_version updated with new JAR."
  fi
else
  echo "Skipping GitHub release creation."
  # Still show where the JAR would be
  echo "JAR would be built at: target/${main_artifact_id}-$new_version.jar"
fi

echo "🎉 Release process complete!"
