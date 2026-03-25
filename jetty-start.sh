#!/bin/bash

# S3-Like Storage Service - Jetty Standalone Startup Script
# This script starts the service using Jetty distribution

set -e

# Configuration
JETTY_HOME_DIR="${JETTY_HOME_DIR:-jetty-home-12.0.15}"
JETTY_BASE_DIR="${JETTY_BASE_DIR:-jetty-base}"
PORT="${PORT:-8080}"

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Check if JETTY_HOME exists
if [ ! -d "$JETTY_HOME_DIR" ]; then
    echo "Error: Jetty home directory not found: $JETTY_HOME_DIR"
    echo ""
    echo "Please download Jetty first:"
    echo "  curl -L -o jetty-home-12.0.15.tar.gz https://repo1.maven.org/maven2/org/eclipse/jetty/jetty-home/12.0.15/jetty-home-12.0.15.tar.gz"
    echo "  tar -xzf jetty-home-12.0.15.tar.gz"
    exit 1
fi

export JETTY_HOME="$SCRIPT_DIR/$JETTY_HOME_DIR"

# Create JETTY_BASE if not exists
if [ ! -d "$JETTY_BASE_DIR" ]; then
    echo "Creating Jetty base directory: $JETTY_BASE_DIR"
    mkdir -p "$JETTY_BASE_DIR"
    mkdir -p "$JETTY_BASE_DIR/webapps"
    mkdir -p "$JETTY_BASE_DIR/logs"
fi

export JETTY_BASE="$SCRIPT_DIR/$JETTY_BASE_DIR"

# Initialize Jetty base if start.ini doesn't exist
if [ ! -f "$JETTY_BASE/start.ini" ]; then
    echo "Initializing Jetty base configuration..."
    cd "$JETTY_BASE"
    java -jar "$JETTY_HOME/start.jar" --add-modules=server,http,ee10-webapp,ee10-deploy
    cd "$SCRIPT_DIR"
fi

# Build WAR if needed
WAR_FILE="target/jetty-demo-1.0-SNAPSHOT.war"
if [ ! -f "$WAR_FILE" ]; then
    echo "Building WAR file..."
    mvn clean package -q
fi

# Copy WAR to webapps as ROOT.war
echo "Deploying WAR file..."
cp "$WAR_FILE" "$JETTY_BASE/webapps/ROOT.war"

# Set port
JAVA_OPTS="-Djetty.http.port=$PORT"

echo "========================================"
echo "Starting S3-Like Storage Service"
echo "========================================"
echo "Port: $PORT"
echo "JETTY_HOME: $JETTY_HOME"
echo "JETTY_BASE: $JETTY_BASE"
echo "========================================"
echo ""
echo "Access the service at:"
echo "  Web UI:  http://localhost:$PORT/"
echo "  API:     http://localhost:$PORT/api/"
echo "  Health:  http://localhost:$PORT/api/health"
echo ""
echo "Press Ctrl+C to stop the server"
echo "========================================"

# Start Jetty
cd "$JETTY_BASE"
"$JETTY_HOME/bin/jetty.sh" run
