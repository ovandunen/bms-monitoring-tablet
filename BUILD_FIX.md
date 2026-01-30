# Build Configuration Fix

## Issue

Gradle build fails with error:
```
Cannot add a configuration with name 'integrationTestImplementation' 
as a configuration with that name already exists.
```

## Root Cause

Quarkus 3.6.4 plugin has compatibility issues with Gradle 9.3.0.

## Solution Options

### Option 1: Add Gradle Wrapper (Recommended)

```bash
cd "/Users/janet/ovd/project/bms-integration/files (1)/fleet-ddd-system"

# Use system Gradle to generate wrapper with compatible version
gradle wrapper --gradle-version 8.5

# Then use wrapper for all subsequent builds
./gradlew clean test
```

### Option 2: Upgrade Quarkus

Update `build.gradle.kts`:
```kotlin
plugins {
    kotlin("jvm") version "1.9.21"
    kotlin("plugin.allopen") version "1.9.21"
    kotlin("plugin.jpa") version "1.9.21"
    id("io.quarkus") version "3.7.0"  // Update from 3.6.4 to 3.7.0
}
```

### Option 3: Use Compatible Gradle Version

```bash
# Install Gradle 8.5 via homebrew
brew install gradle@8.5

# Or use sdkman
sdk install gradle 8.5.0
sdk use gradle 8.5.0
```

## Quick Test Command

Once build is fixed:

```bash
# Run unit tests only (fast, no database needed)
./gradlew test --tests "com.fleet.domain.battery.*"

# Run all tests
./gradlew test

# Run with more output
./gradlew test --info
```

## Verification

Build should complete successfully with output like:
```
BUILD SUCCESSFUL in 30s
4 actionable tasks: 4 executed
```
