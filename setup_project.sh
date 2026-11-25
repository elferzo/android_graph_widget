#!/bin/bash
# setup_project.sh — раскладывает исходники и ресурсы виджетов по проекту.
# Запускать из папки, где лежат все .kt / .xml / .txt из набора.
set -e

PKG="com/example/graphwidget"
SRC="app/src/main"

if [ ! -d "$SRC" ]; then
    echo "ОШИБКА: нет каталога $SRC. Запускайте скрипт из корня Gradle-проекта." >&2
    exit 1
fi

# Все нужные файлы должны лежать рядом со скриптом. Проверяем разом,
# чтобы не оборваться на середине и не оставить проект в полускопированном виде.
NEED="MainActivity.kt GarminClient.kt GraphWidget.kt ActivityWidget.kt BootReceiver.kt strings.xml widget_info.xml widget_activity_info.xml AndroidManifest.xml app_build_gradle.txt"
MISSING=""
for f in $NEED; do
    [ -f "$f" ] || MISSING="$MISSING $f"
done
if [ -n "$MISSING" ]; then
    echo "ОШИБКА: рядом со скриптом нет файлов:$MISSING" >&2
    echo "Положи их в эту папку и запусти снова." >&2
    exit 1
fi

# app_build_gradle.txt должен быть версией 1.4 (versionCode 5).
# Исходная версия 1.3 (versionCode 4) молча откатила бы изменения.
if ! grep -q 'versionCode 5' app_build_gradle.txt; then
    echo "ВНИМАНИЕ: в app_build_gradle.txt нет versionCode 5." >&2
    echo "Похоже, это исходная версия 1.3. Сборка продолжится, но проверь файл." >&2
fi

mkdir -p "$SRC/kotlin/$PKG"
mkdir -p "$SRC/res/xml"
mkdir -p "$SRC/res/values"

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
