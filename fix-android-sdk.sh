#!/bin/bash
set -e

# ตำแหน่ง SDK
SDK_ROOT=/root/android-sdk
mkdir -p $SDK_ROOT/licenses

# ✅ เขียนไฟล์ license ที่ต้องใช้
cat > $SDK_ROOT/licenses/android-sdk-license <<'EOF'
d56f5187479451eabf01fb78af6dfcb131a6481e
24333f8a63b6825ea9c5514f83c2829b004d1fee
EOF

cat > $SDK_ROOT/licenses/android-sdk-preview-license <<'EOF'
84831b9409646a918e30573bab4c9c91346d8abd
EOF

echo "[OK] Licenses added."

# ✅ ติดตั้ง tools/platforms ที่ Gradle ขอใช้
$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager --sdk_root=$SDK_ROOT \
  "platforms;android-33" \
  "build-tools;34.0.0" \
  "platform-tools"

echo "[OK] SDK installed."

# ✅ สุดท้ายสั่ง build APK
cd ~/RecoverX_Full/RecoverX_Full
./gradlew :app:assembleDebug --no-daemon
