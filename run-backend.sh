#!/usr/bin/env sh
# Starts the Java runtime (127.0.0.1:8080 by default).
#
# All settings live in  backend/config/application.yml  -- edit that file, not this script.
# With no llm.api-key set there, the engine runs in mock mode: fully offline, no network needed.
set -e
cd "$(dirname "$0")/backend"
# First run: give you an editable, fully commented config file.
[ -f config/application.yml ] || cp config/application.example.yml config/application.yml
exec ./gradlew bootRun --console=plain
