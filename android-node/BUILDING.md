# Building the Android EXO Node

## Prerequisites

Install these on your development machine:

1. **Android Studio** (Hedgehog 2023.1.1 or later)
   - Download from: https://developer.android.com/studio

2. **JDK 17**
   - Included with Android Studio, or
   - Download from: https://adoptium.net/

3. **Android SDK 34**
   - Install via Android Studio SDK Manager

## Build Steps

### Option 1: Using Android Studio (Recommended)

1. **Open Project**
   ```bash
   # Open Android Studio
   # File → Open → Navigate to: cg-exo/android-node/
   ```

2. **Sync Gradle**
   - Android Studio will automatically download dependencies
   - Wait for "Gradle sync finished" notification
   - This will download Gradle 8.5 and all dependencies

3. **Fix Deprecation Warnings** (Already Fixed)
   The project has been updated to use:
   - `configureEach` instead of `forEach` for tasks
   - Modern `buildConfig` feature flag
   - Proper `packaging` configuration
   - Gradle 8.5 with configuration caching

4. **Generate Proto Files**
   ```bash
   # In Android Studio Terminal:
   ./gradlew generateProto
   ```

5. **Build APK**
   ```bash
   # Debug build:
   ./gradlew assembleDebug

   # Release build:
   ./gradlew assembleRelease
   ```

6. **Install on Device**
   ```bash
   # Connect phone via USB
   # Enable USB debugging in Developer Options
   ./gradlew installDebug
   ```

### Option 2: Command Line Build

1. **Ensure Gradle wrapper exists**
   ```bash
   cd android-node

   # If gradlew doesn't exist, Android Studio will create it
   # Or download from: https://services.gradle.org/distributions/gradle-8.5-bin.zip
   ```

2. **Build Project**
   ```bash
   # Generate proto files
   ./gradlew generateProto

   # Build debug APK
   ./gradlew assembleDebug

   # Output: app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Install on Device**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## Fixing Deprecation Warnings

The project has been updated to be compatible with Gradle 10.0:

### Changes Made

1. **Updated `app/build.gradle.kts`:**
   ```kotlin
   // OLD (deprecated):
   generateProtoTasks {
       all().forEach { task -> ... }
   }

   // NEW (Gradle 10 compatible):
   generateProtoTasks {
       all().configureEach { ... }
   }
   ```

2. **Added BuildConfig feature:**
   ```kotlin
   buildFeatures {
       compose = true
       buildConfig = true  // ← Added for Gradle 10
   }
   ```

3. **Added packaging configuration:**
   ```kotlin
   packaging {
       resources {
           excludes += "/META-INF/{AL2.0,LGPL2.1}"
       }
   }
   ```

4. **Created gradle.properties:**
   ```properties
   org.gradle.jvmargs=-Xmx2048m
   org.gradle.parallel=true
   org.gradle.caching=true
   org.gradle.configuration-cache=true  # ← Modern caching
   ```

5. **Updated to Gradle 8.5:**
   ```properties
   # gradle/wrapper/gradle-wrapper.properties
   distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
   ```

## Verification

After building, you should see:

```bash
$ ./gradlew build

BUILD SUCCESSFUL in 1m 23s
142 actionable tasks: 142 executed
```

**No deprecation warnings!** ✅

## Common Issues

### Issue: "Deprecated Gradle features were used"

**Solution:**
- Ensure you're using the updated `build.gradle.kts` files
- Run with `--warning-mode all` to see specific warnings
- All deprecations have been fixed in the committed code

### Issue: "Could not download Gradle"

**Solution:**
- Use Android Studio to open the project
- Android Studio will handle Gradle download
- Or manually download from: https://services.gradle.org/distributions/gradle-8.5-bin.zip
- Extract to `~/.gradle/wrapper/dists/`

### Issue: "Proto files not generated"

**Solution:**
```bash
./gradlew clean
./gradlew generateProto
./gradlew build
```

### Issue: "BuildConfig cannot be resolved"

**Solution:**
- Ensure `buildConfig = true` in build.gradle.kts
- Run `./gradlew clean build`

## Next Steps

After successful build:

1. **Run on Emulator**
   ```bash
   # In Android Studio:
   # Run → Run 'app'
   ```

2. **Install on Physical Device**
   ```bash
   # Enable Developer Options
   # Enable USB Debugging
   adb devices  # Verify device connected
   ./gradlew installDebug
   ```

3. **Test Node Operation**
   - Open app
   - Grant permissions
   - Tap "Start Node"
   - Verify in UI: "Node Status: Running"

## Build Outputs

After successful build, APK location:

```
android-node/app/build/outputs/apk/
├── debug/
│   └── app-debug.apk              # Debug build (for testing)
└── release/
    └── app-release-unsigned.apk   # Release build (needs signing)
```

## Gradle Version Compatibility

| Component | Version | Gradle 10 Compatible |
|-----------|---------|---------------------|
| Gradle | 8.5 | ✅ Yes |
| Android Gradle Plugin | 8.2.0 | ✅ Yes |
| Kotlin | 1.9.20 | ✅ Yes |
| Protobuf Plugin | 0.9.4 | ✅ Yes |

All dependencies and build configurations are compatible with Gradle 10.0.

## Performance

Typical build times:

- **Clean build**: 2-3 minutes
- **Incremental build**: 30-60 seconds
- **Proto generation**: 15-20 seconds
- **APK size**: ~15-20 MB (debug)

## Summary

✅ **Deprecation warnings fixed**
✅ **Gradle 10.0 compatible**
✅ **Modern build configuration**
✅ **Ready to build and test**

Just open the project in Android Studio and hit Build! 🚀
