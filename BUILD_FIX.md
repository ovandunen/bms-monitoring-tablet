# Build Configuration Fix

## Issue

Gradle build fails with error:
```
Cannot add a configuration with name 'integrationTestImplementation' 
as a configuration with that name already exists.
```

OR

```
Extending a detachedConfiguration is not allowed
configuration ':detachedConfiguration7' cannot extend configuration ':compileClasspath'
```

## Root Cause

Gradle 9.3.0 has compatibility issues with Quarkus plugins. Quarkus requires Gradle 8.x.

## Current Status

✅ **Quarkus upgraded to 3.8.1** (from 3.6.4) - committed
✅ **settings.gradle.kts updated** - committed
❌ **System Gradle version 9.3.0** - needs downgrade to 8.5

## ✅ RECOMMENDED SOLUTION: Install Gradle 8.5

You need to **downgrade your system Gradle** from 9.3.0 to 8.5. Choose one method:

### Method 1: Using SDKMAN (Recommended)

```bash
# Install SDKMAN if not already installed
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Install Gradle 8.5
sdk install gradle 8.5.0

# Set as default
sdk default gradle 8.5.0

# Verify version
gradle --version  # Should show Gradle 8.5
```

### Method 2: Using Homebrew

```bash
# Uninstall current Gradle
brew uninstall gradle

# Install Gradle 8.5 specifically
brew install gradle@8.5

# Link it
brew link gradle@8.5

# Verify version
gradle --version  # Should show Gradle 8.5
```

### Method 3: Manual Download

```bash
# Download Gradle 8.5
curl -L https://services.gradle.org/distributions/gradle-8.5-bin.zip -o gradle-8.5-bin.zip

# Extract
unzip gradle-8.5-bin.zip

# Move to /opt
sudo mv gradle-8.5 /opt/

# Add to PATH (add to ~/.zshrc or ~/.bash_profile)
export PATH="/opt/gradle-8.5/bin:$PATH"

# Reload shell
source ~/.zshrc

# Verify version
gradle --version  # Should show Gradle 8.5
```

## Quick Test Commands

After installing Gradle 8.5:

```bash
cd "/Users/janet/ovd/project/bms-integration/app/fleet-ddd-system"

# Verify Gradle version
gradle --version

# Run unit tests (fast, no database)
gradle test --tests "com.fleet.domain.battery.BatteryPackTest"

# Run all tests
gradle test

# Clean and test
gradle clean test
```

## Expected Output

After fix, you should see:
```
> Task :test
com.fleet.domain.battery.BatteryPackTest > should create battery pack with initial state PASSED
com.fleet.domain.battery.BatteryPackTest > should record telemetry and raise event PASSED
com.fleet.domain.battery.BatteryPackTest > should raise BatteryDepletedEvent when SOC critically low PASSED
...
BUILD SUCCESSFUL in 45s
```

## Alternative: Create Gradle Wrapper (After Installing Gradle 8.5)

Once you have Gradle 8.5 installed:

```bash
cd "/Users/janet/ovd/project/bms-integration/app/fleet-ddd-system"

# Create wrapper
gradle wrapper --gradle-version 8.5

# Then use wrapper for all builds
./gradlew test
```

## Troubleshooting

### If tests still fail after installing Gradle 8.5:

```bash
# Clean gradle cache
rm -rf ~/.gradle/caches

# Clean project
cd "/Users/janet/ovd/project/bms-integration/app/fleet-ddd-system"
rm -rf .gradle build

# Try again
gradle clean test
```

### Verify current Gradle version:

```bash
gradle --version
# Should show: Gradle 8.5
# Not: Gradle 9.3.0
```

## Summary

**Required Action:** Install Gradle 8.5 (downgrade from 9.3.0)  
**Recommended Method:** SDKMAN  
**Time Required:** 5-10 minutes  
**Then Run:** `gradle test`
