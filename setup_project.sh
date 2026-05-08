#!/bin/bash
set -e

PKG=com/example/graphwidget
SRC=app/src/main

mkdir -p $SRC/kotlin/$PKG
mkdir -p $SRC/res/layout
mkdir -p $SRC/res/xml
mkdir -p $SRC/res/values

cp GraphWidget.kt  $SRC/kotlin/$PKG/
cp MainActivity.kt        $SRC/kotlin/$PKG/
cp BootReceiver.kt        $SRC/kotlin/$PKG/
cp widget_layout.xml      $SRC/res/layout/widget_calorie_chart.xml
cp widget_info.xml        $SRC/res/xml/widget_info.xml
cp strings.xml            $SRC/res/values/strings.xml
cp AndroidManifest.xml    $SRC/AndroidManifest.xml
cp root_build_gradle.txt  build.gradle
cp app_build_gradle.txt   app/build.gradle

mkdir -p .github/workflows
cp build_apk.yml .github/workflows/build_apk.yml

echo "=== setup done ==="
