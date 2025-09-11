mkdir -p .github/workflows
cat > .github/workflows/android.yml <<'YAML'
name: Build Debug APK

on:
  push:
    branches: [ "main" ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle

      - name: Wipe Gradle caches
        run: |
          rm -rf ~/.gradle/caches
          rm -rf ~/.gradle/daemon

      - name: Ensure Android SDK + accept licenses
        env:
          ANDROID_SDK_ROOT: /usr/local/lib/android/sdk
          ANDROID_HOME: /usr/local/lib/android/sdk
        run: |
          yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses
          "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" \
            "platform-tools" \
            "cmdline-tools;latest" \
            "build-tools;34.0.0" \
            "platforms;android-34"

      - name: Make gradlew executable
        run: chmod +x gradlew

      - name: Clean
        run: ./gradlew clean --no-daemon

      - name: Build Debug APK
        run: ./gradlew :app:assembleDebug --stacktrace --info --no-daemon

      - name: Upload APK
        if: success()
        uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: warn

      - name: Upload build reports (always)
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: build-reports
          path: |
            **/build/reports/**
            **/build/outputs/logs/**
            **/build/outputs/mapping/**
            **/build/outputs/apk/**
          if-no-files-found: ignore
YAML

git add .github/workflows/android.yml
git commit -m "ci: overwrite workflow to build APK"
git push -u origin main
