#!/bin/bash
# setup_project.sh — собирает Gradle-проект из плоского набора файлов.
# Запускать из корня репозитория, где лежат все .kt / .xml / .txt из набора.
# Скрипт САМ создаёт скелет проекта, поэтому готовый Gradle-проект не нужен.
set -e

PKG="com/example/graphwidget"
SRC="app/src/main"

NEED="MainActivity.kt GarminClient.kt GraphWidget.kt ActivityWidget.kt BootReceiver.kt"
NEED="$NEED strings.xml widget_info.xml widget_activity_info.xml"
NEED="$NEED widget_calorie_chart.xml widget_activity_chart.xml"
NEED="$NEED AndroidManifest.xml"

MISSING=""
for f in $NEED; do
    [ -f "$f" ] || MISSING="$MISSING $f"
done
if [ -n "$MISSING" ]; then
    echo "ОШИБКА: нет файлов:$MISSING" >&2
    exit 1
fi

mkdir -p "$SRC/java/$PKG" "$SRC/res/values" "$SRC/res/xml" "$SRC/res/layout"

if [ ! -f settings.gradle ] && [ ! -f settings.gradle.kts ]; then
    cat > settings.gradle <<'EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "GraphWidget"
include ':app'
EOF
    echo "  + settings.gradle (создан)"
else
    echo "  = settings.gradle уже есть, не трогаю"
fi

if [ ! -f gradle.properties ]; then
    cat > gradle.properties <<'EOF'
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
EOF
    echo "  + gradle.properties (создан)"
else
    echo "  = gradle.properties уже есть, не трогаю"
fi

if [ -f root_build_gradle.txt ]; then
    cp root_build_gradle.txt build.gradle
    echo "  build.gradle <- root_build_gradle.txt"
elif [ ! -f build.gradle ]; then
    cat > build.gradle <<'EOF'
plugins {
    id 'com.android.application' version '8.9.3' apply false
    id 'org.jetbrains.kotlin.android' version '2.0.21' apply false
}
EOF
    echo "  build.gradle (создан по умолчанию)"
else
    echo "  = build.gradle уже есть, не трогаю"
fi

if [ -f app_build_gradle.txt ]; then
    cp app_build_gradle.txt app/build.gradle
    echo "  app/build.gradle <- app_build_gradle.txt"
else
    echo "ОШИБКА: нет app_build_gradle.txt" >&2
    exit 1
fi

# Исходники кладём в src/main/java — этот каталог используется ВСЕГДА,
# независимо от sourceSets в build.gradle.
for f in MainActivity.kt GarminClient.kt GraphWidget.kt ActivityWidget.kt BootReceiver.kt; do
    cp "$f" "$SRC/java/$PKG/"
    echo "  java/$PKG/$f"
done

cp strings.xml "$SRC/res/values/"
echo "  res/values/strings.xml"

for f in widget_info.xml widget_activity_info.xml; do
    cp "$f" "$SRC/res/xml/"
    echo "  res/xml/$f"
done

for f in widget_calorie_chart.xml widget_activity_chart.xml; do
    cp "$f" "$SRC/res/layout/"
    echo "  res/layout/$f"
done

cp AndroidManifest.xml "$SRC/AndroidManifest.xml"
echo "  AndroidManifest.xml"

echo "Готово."
