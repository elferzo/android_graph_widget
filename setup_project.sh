#!/bin/bash
# setup_project.sh - раскладывает исходники и ресурсы виджетов по проекту.
# Запускать из корня Gradle-проекта, где лежат все .kt / .xml / .txt из набора.
set -e

PKG="com/example/graphwidget"
SRC="app/src/main"

if [ ! -d "$SRC" ]; then
    echo "ОШИБКА: нет каталога $SRC. Запускайте скрипт из корня Gradle-проекта." >&2
    exit 1
fi

# Обязательные файлы. root_build_gradle.txt - отдельно: он нужен только
# если в проекте ещё нет своего build.gradle.
NEED="MainActivity.kt GarminClient.kt GraphWidget.kt ActivityWidget.kt BootReceiver.kt"
NEED="$NEED strings.xml widget_info.xml widget_activity_info.xml"
NEED="$NEED widget_calorie_chart.xml widget_activity_chart.xml"
NEED="$NEED AndroidManifest.xml app_build_gradle.txt"

MISSING=""
for f in $NEED; do
    [ -f "$f" ] || MISSING="$MISSING $f"
done
if [ -n "$MISSING" ]; then
    echo "ОШИБКА: рядом со скриптом нет файлов:$MISSING" >&2
    echo "Положи их в эту папку и запусти снова." >&2
    exit 1
fi

if ! grep -q 'versionCode 5' app_build_gradle.txt; then
    echo "ВНИМАНИЕ: в app_build_gradle.txt нет versionCode 5." >&2
    echo "Похоже, это исходная версия 1.3. Сборка продолжится, но проверь файл." >&2
fi

mkdir -p "$SRC/kotlin/$PKG"
mkdir -p "$SRC/res/xml"
mkdir -p "$SRC/res/values"
mkdir -p "$SRC/res/layout"

for f in MainActivity.kt GarminClient.kt GraphWidget.kt ActivityWidget.kt BootReceiver.kt; do
    cp "$f" "$SRC/kotlin/$PKG/"
    echo "  kotlin/$PKG/$f"
done

cp strings.xml "$SRC/res/values/"
echo "  res/values/strings.xml"

for f in widget_info.xml widget_activity_info.xml; do
    cp "$f" "$SRC/res/xml/"
    echo "  res/xml/$f"
done

# Лейауты виджетов. Имена сохраняются как есть: R.layout.widget_calorie_chart
# и R.layout.widget_activity_chart жёстко прописаны в GraphWidget / ActivityWidget.
for f in widget_calorie_chart.xml widget_activity_chart.xml; do
    cp "$f" "$SRC/res/layout/"
    echo "  res/layout/$f"
done

cp AndroidManifest.xml "$SRC/AndroidManifest.xml"
echo "  AndroidManifest.xml"

cp app_build_gradle.txt app/build.gradle
echo "  app/build.gradle"

if [ -f root_build_gradle.txt ]; then
    cp root_build_gradle.txt build.gradle
    echo "  build.gradle"
else
    echo "  ПРОПУСК: root_build_gradle.txt не найден, корневой build.gradle не тронут"
fi

echo "Готово."
