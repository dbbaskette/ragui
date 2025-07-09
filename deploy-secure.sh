#!/bin/bash
# deploy-secure.sh - Build and deploy to Cloud Foundry with secure credentials
# Usage: ./deploy-secure.sh [--skip-build] [--app-name NAME]

set -e

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
SKIP_BUILD=false
APP_NAME=""

# Parse command line arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    --app-name)
      APP_NAME="$2"
      shift 2
      ;;
    -h|--help)
      echo "Usage: $0 [--skip-build] [--app-name NAME]"
      echo ""
      echo "Options:"
      echo "  --skip-build        Skip Maven build, use existing JAR"
      echo "  --app-name NAME     Override app name from manifest.yml"
      echo "  -h, --help          Show this help message"
      echo ""
      echo "Examples:"
      echo "  $0                       # Build and deploy with secure credentials"
      echo "  $0 --skip-build          # Deploy existing JAR"
      echo "  $0 --app-name ragui-prod # Deploy to different app name"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      echo "Use --help for usage information"
      exit 1
      ;;
  esac
done

echo -e "${BLUE}🚀 RAG UI Secure Deployment Script${NC}"
echo "=========================================="

# 1. Verify we're in the right directory
if [[ ! -f "pom.xml" ]]; then
  echo -e "${RED}❌ Error: pom.xml not found. Please run this script from the project root.${NC}"
  exit 1
fi

if [[ ! -f "manifest.yml" ]]; then
  echo -e "${RED}❌ Error: manifest.yml not found. Please run this script from the project root.${NC}"
  exit 1
fi

# 2. Get current version from pom.xml
main_artifact_id=$(awk '
  /<parent>/ {in_parent=1}
  /<\/parent>/ {in_parent=0; next}
  in_parent {next}
  /<artifactId>/ && !found {
    print $0
    found=1
  }
' pom.xml | sed -n 's:.*<artifactId>\([^<]*\)</artifactId>.*:\1:p')

current_version=$(awk '/<artifactId>'"$main_artifact_id"'<\/artifactId>/{getline; while (!/<version>/) getline; gsub(/.*<version>|<\/version>.*/, ""); print $0; exit}' pom.xml)

echo -e "${BLUE}📦 Project: ${main_artifact_id} v${current_version}${NC}"

# 3. Handle secure credentials
echo -e "${BLUE}🔐 Setting up secure credentials...${NC}"

# Check if secure properties file exists
if [ ! -f "src/main/resources/application-secure.properties" ]; then
    echo -e "${YELLOW}📝 Creating application-secure.properties from template...${NC}"
    cp src/main/resources/application-secure.properties.template src/main/resources/application-secure.properties
    
    # Set default values if environment variables are not provided
    if [ -z "$DEFAULT_USERNAME" ]; then
        echo -e "${YELLOW}⚠️  DEFAULT_USERNAME not set, using default: tanzu${NC}"
        export DEFAULT_USERNAME=tanzu
    fi
    
    if [ -z "$DEFAULT_PASSWORD" ]; then
        echo -e "${YELLOW}⚠️  DEFAULT_PASSWORD not set, using default: t@nzu123${NC}"
        export DEFAULT_PASSWORD=t@nzu123
    fi
    
    # Replace placeholders with actual values
    sed -i.bak "s/\${DEFAULT_USERNAME:tanzu}/$DEFAULT_USERNAME/g" src/main/resources/application-secure.properties
    sed -i.bak "s/\${DEFAULT_PASSWORD:t@nzu123}/$DEFAULT_PASSWORD/g" src/main/resources/application-secure.properties
    
    # Clean up backup file
    rm src/main/resources/application-secure.properties.bak
    
    echo -e "${GREEN}✅ Secure properties file created with credentials${NC}"
else
    echo -e "${GREEN}✅ Secure properties file already exists${NC}"
fi

# 4. Maven build (unless skipped)
if [[ "$SKIP_BUILD" == true ]]; then
  echo -e "${YELLOW}⏭️  Skipping Maven build${NC}"
else
  echo -e "${BLUE}🔨 Running secure build...${NC}"
  
  # Check if build-secure.sh exists and use it
  if [[ -f "./build-secure.sh" ]]; then
    echo -e "${BLUE}📦 Using build-secure.sh for secure build process${NC}"
    if ! ./build-secure.sh; then
      echo -e "${RED}❌ Secure build failed!${NC}"
      exit 1
    fi
  else
    echo -e "${YELLOW}⚠️  build-secure.sh not found, using standard Maven build${NC}"
    if ! ./mvnw clean package -DskipTests; then
      echo -e "${RED}❌ Maven build failed${NC}"
      exit 1
    fi
  fi
  
  echo -e "${GREEN}✅ Build successful${NC}"
fi

# 5. Verify JAR exists
JAR_PATH="target/${main_artifact_id}-${current_version}.jar"
if [[ ! -f "$JAR_PATH" ]]; then
  echo -e "${RED}❌ Error: JAR file not found at ${JAR_PATH}${NC}"
  echo -e "${YELLOW}💡 Try running without --skip-build${NC}"
  exit 1
fi

echo -e "${GREEN}📦 JAR ready: ${JAR_PATH}${NC}"

# 6. Check if cf CLI is available
if ! command -v cf &> /dev/null; then
  echo -e "${RED}❌ Error: Cloud Foundry CLI (cf) not found${NC}"
  echo -e "${YELLOW}💡 Please install CF CLI: https://docs.cloudfoundry.org/cf-cli/install-go-cli.html${NC}"
  exit 1
fi

# 7. Check if logged into CF
if ! cf target &> /dev/null; then
  echo -e "${RED}❌ Error: Not logged into Cloud Foundry${NC}"
  echo -e "${YELLOW}💡 Please run: cf login${NC}"
  exit 1
fi

# Show current CF target
cf_target=$(cf target 2>/dev/null | grep -E "(API endpoint|org|space)" | tr '\n' ' ')
echo -e "${BLUE}🎯 CF Target: ${cf_target}${NC}"

# 8. Update manifest.yml with correct JAR path
echo -e "${BLUE}📝 Updating manifest.yml with correct JAR path...${NC}"
sed -i.bak "s|path: target/.*\.jar|path: ${JAR_PATH}|" manifest.yml
rm manifest.yml.bak
echo -e "${GREEN}✅ Manifest updated: ${JAR_PATH}${NC}"

# 9. Deploy to Cloud Foundry
echo -e "${BLUE}☁️  Deploying to Cloud Foundry...${NC}"

if [[ -n "$APP_NAME" ]]; then
  echo -e "${YELLOW}📛 Using custom app name: ${APP_NAME}${NC}"
  cf push "$APP_NAME" -f manifest.yml
else
  cf push -f manifest.yml
fi

# 10. Show deployment results
if [[ $? -eq 0 ]]; then
  echo ""
  echo -e "${GREEN}🎉 Deployment successful!${NC}"
  echo ""
  
  # Get app info
  if [[ -n "$APP_NAME" ]]; then
    app_name="$APP_NAME"
  else
    app_name=$(grep "name:" manifest.yml | head -1 | sed 's/.*name: *//' | tr -d ' ')
  fi
  
  echo -e "${BLUE}📋 App Information:${NC}"
  cf app "$app_name" | head -10
  
  echo ""
  echo -e "${GREEN}🌐 Your app should be available at the route(s) shown above${NC}"
  echo -e "${YELLOW}💡 Tip: Use 'cf logs $app_name --recent' to view recent logs${NC}"
else
  echo -e "${RED}❌ Deployment failed${NC}"
  exit 1
fi 