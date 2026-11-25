#!/usr/bin/env bash
# setup_project.sh — раскладывает плоские файлы репозитория в структуру Gradle-проекта
# и создаёт отсутствующий скелет (settings.gradle, gradle.properties, иконку).
# Запуск из КОРНЯ репозитория, где лежат .kt и .xml:   ./setup_project.sh
set -euo pipefail

PKG="com.example.graphwidget"
PKG_PATH="${PKG//.//}"
SRC="app/src/main"
JAVA="$SRC/java/$PKG_PATH"
RES="$SRC/res"

# ── 1. Все нужные файлы на месте? ────────────────────────────────────────────
MISSING=""
for f in GarminClient.kt GraphWidget.kt ActivityWidget.kt MainActivity.kt BootReceiver.kt \
         widget_calorie_chart.xml widget_activity_chart.xml \
         widget_info.xml widget_activity_info.xml strings.xml \
         AndroidManifest.xml app_build_gradle.txt; do
  [ -f "$f" ] || MISSING="$MISSING $f"
done
if [ -n "$MISSING" ]; then
  echo "ОШИБКА: нет файлов:$MISSING" >&2
  echo "Запускайте скрипт из корня репозитория, где лежат эти файлы." >&2
  exit 1
fi

# ── 2. Каталоги ──────────────────────────────────────────────────────────────
mkdir -p "$JAVA" "$RES/layout" "$RES/xml" "$RES/values" \
         "$RES/drawable" "$RES/mipmap-anydpi-v26" "$RES/mipmap-anydpi"

# ── 3. Скелет Gradle (не перезаписываем существующее) ────────────────────────
if [ ! -f settings.gradle ]; then
  cat > settings.gradle <<'EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "GraphWidget"
include ':app'
EOF
  echo "создан settings.gradle"
fi

if [ ! -f gradle.properties ]; then
  cat > gradle.properties <<'EOF'
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
EOF
  echo "создан gradle.properties"
fi

if [ ! -f build.gradle ]; then
  if [ -f root_build_gradle.txt ]; then
    cp root_build_gradle.txt build.gradle
    echo "build.gradle из root_build_gradle.txt"
  else
    cat > build.gradle <<'EOF'
plugins {
    id 'com.android.application' version '8.9.3' apply false
    id 'org.jetbrains.kotlin.android' version '2.0.21' apply false
}
EOF
    echo "build.gradle сгенерирован (AGP 8.9.3 / Kotlin 2.0.21)"
  fi
fi

# ── 4. Исходники и ресурсы ───────────────────────────────────────────────────
cp GarminClient.kt GraphWidget.kt ActivityWidget.kt MainActivity.kt BootReceiver.kt "$JAVA/"
cp widget_calorie_chart.xml widget_activity_chart.xml "$RES/layout/"
cp widget_info.xml widget_activity_info.xml "$RES/xml/"
cp strings.xml "$RES/values/"
cp AndroidManifest.xml "$SRC/AndroidManifest.xml"
cp app_build_gradle.txt app/build.gradle

# ── 5. Иконка запуска (векторно, без PNG) ────────────────────────────────────
cat > "$RES/drawable/ic_launcher_background.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#12161C" />
</shape>
EOF

cat > "$RES/drawable/ic_launcher_foreground.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#FFB300"
        android:pathData="M26,64 L26,78 L38,78 L38,64 Z" />
    <path android:fillColor="#29B6F6"
        android:pathData="M42,50 L42,78 L54,78 L54,50 Z" />
    <path android:fillColor="#FFB300"
        android:pathData="M58,40 L58,78 L70,78 L70,40 Z" />
    <path android:fillColor="#29B6F6"
        android:pathData="M74,30 L74,78 L86,78 L86,30 Z" />
</vector>
EOF

cat > "$RES/mipmap-anydpi-v26/ic_launcher.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
EOF

cat > "$RES/mipmap-anydpi-v26/ic_launcher_round.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
EOF

cat > "$RES/mipmap-anydpi/ic_launcher.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@drawable/ic_launcher_background" />
    <item android:drawable="@drawable/ic_launcher_foreground" />
</layer-list>
EOF

cp "$RES/mipmap-anydpi/ic_launcher.xml" "$RES/mipmap-anydpi/ic_launcher_round.xml"

echo "готово: $JAVA"
