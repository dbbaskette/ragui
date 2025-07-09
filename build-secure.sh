#!/bin/bash

# Build script for RAG UI with secure credentials
# This script handles the secure properties file during build

set -e

echo "🔐 Setting up secure credentials for RAG UI build..."

# Check if secure properties file exists
if [ ! -f "src/main/resources/application-secure.properties" ]; then
    echo "📝 Creating application-secure.properties from template..."
    cp src/main/resources/application-secure.properties.template src/main/resources/application-secure.properties
    
    # Set default values if environment variables are not provided
    if [ -z "$DEFAULT_USERNAME" ]; then
        echo "⚠️  DEFAULT_USERNAME not set, using default: tanzu"
        export DEFAULT_USERNAME=tanzu
    fi
    
    if [ -z "$DEFAULT_PASSWORD" ]; then
        echo "⚠️  DEFAULT_PASSWORD not set, using default: t@nzu123"
        export DEFAULT_PASSWORD=t@nzu123
    fi
    
    # Replace placeholders with actual values
    sed -i.bak "s/\${DEFAULT_USERNAME:tanzu}/$DEFAULT_USERNAME/g" src/main/resources/application-secure.properties
    sed -i.bak "s/\${DEFAULT_PASSWORD:t@nzu123}/$DEFAULT_PASSWORD/g" src/main/resources/application-secure.properties
    
    # Clean up backup file
    rm src/main/resources/application-secure.properties.bak
    
    echo "✅ Secure properties file created with credentials"
else
    echo "✅ Secure properties file already exists"
fi

# Build the application
echo "🔨 Building RAG UI application..."
./mvnw clean package -DskipTests

echo "✅ Build completed successfully!"
echo "📦 JAR file created in target/ directory" 