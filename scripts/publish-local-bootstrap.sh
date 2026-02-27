#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

gradle_args=(
  "-Pbootstrap.local=false"
  "-PskipComponentSample=true"
  "--no-configuration-cache"
  "-x" "test"
)

./gradlew \
  :kotlin-compiler-embeddable:publishMainPublicationToMavenRepository \
  :kotlin-compiler-runner:publishMainPublicationToMavenRepository \
  :kotlin-daemon-client:publishMainPublicationToMavenRepository \
  :compiler:build-tools:kotlin-build-tools-api:publishMainPublicationToMavenRepository \
  :compiler:build-tools:kotlin-build-tools-impl:publishMainPublicationToMavenRepository \
  :compiler:build-tools:kotlin-build-tools-compat:publishMainPublicationToMavenRepository \
  :compiler:build-tools:kotlin-build-statistics:publishMainPublicationToMavenRepository \
  :kotlin-gradle-plugin:publishAllPublicationsToMavenRepository \
  :kotlin-gradle-plugin-api:publishAllPublicationsToMavenRepository \
  :kotlin-gradle-plugin-annotations:publishMainPublicationToMavenRepository \
  :kotlin-gradle-plugin-idea:publishMainPublicationToMavenRepository \
  :kotlin-gradle-plugin-idea-proto:publishMainPublicationToMavenRepository \
  :kotlin-gradle-plugins-bom:publishAllPublicationsToMavenRepository \
  :kotlin-tooling-core:publishMainPublicationToMavenRepository \
  :kotlin-tooling-metadata:publishMainPublicationToMavenRepository \
  :kotlin-util-klib:publishMainPublicationToMavenRepository \
  :kotlin-util-klib-metadata:publishMainPublicationToMavenRepository \
  :native:kotlin-native-utils:publishMainPublicationToMavenRepository \
  :kotlin-daemon-embeddable:publishAllPublicationsToMavenRepository \
  :kotlin-script-runtime:publishMainPublicationToMavenRepository \
  :kotlin-scripting-common:publishMainPublicationToMavenRepository \
  :kotlin-scripting-jvm:publishMainPublicationToMavenRepository \
  :kotlin-scripting-compiler-embeddable:publishMainPublicationToMavenRepository \
  :kotlin-scripting-compiler-impl-embeddable:publishMainPublicationToMavenRepository \
  :kotlin-serialization:publishAllPublicationsToMavenRepository \
  :kotlin-sam-with-receiver:publishAllPublicationsToMavenRepository \
  :kotlin-sam-with-receiver-compiler-plugin.embeddable:publishMainPublicationToMavenRepository \
  :kotlin-assignment:publishAllPublicationsToMavenRepository \
  :kotlin-assignment-compiler-plugin.embeddable:publishMainPublicationToMavenRepository \
  :kotlin-lombok:publishAllPublicationsToMavenRepository \
  :kotlin-allopen:publishAllPublicationsToMavenRepository \
  :kotlin-noarg:publishAllPublicationsToMavenRepository \
  :kotlin-power-assert:publishAllPublicationsToMavenRepository \
  :libraries:tools:gradle:fus-statistics-gradle-plugin:publishPluginMavenPublicationToMavenRepository \
  :kotlin-stdlib:publishAllPublicationsToMavenRepository \
  :kotlin-stdlib-jdk7:publishAllPublicationsToMavenRepository \
  :kotlin-stdlib-jdk8:publishAllPublicationsToMavenRepository \
  :kotlin-stdlib-js:publishAllPublicationsToMavenRepository \
  :kotlin-stdlib-common:publishAllPublicationsToMavenRepository \
  :kotlin-reflect:publishAllPublicationsToMavenRepository \
  :kotlin-main-kts:publishAllPublicationsToMavenRepository \
  :kotlin-scripting-jvm-host:publishAllPublicationsToMavenRepository \
  :kotlin-scripting-ide-services:publishAllPublicationsToMavenRepository \
  :kotlin-test:publishAllPublicationsToMavenRepository \
  :kotlin-test-junit:publishAllPublicationsToMavenRepository \
  :kotlin-test-junit5:publishAllPublicationsToMavenRepository \
  :kotlin-test-testng:publishAllPublicationsToMavenRepository \
  :kotlin-test-js:publishAllPublicationsToMavenRepository \
  :kotlin-test-common:publishAllPublicationsToMavenRepository \
  :kotlin-test-annotations-common:publishAllPublicationsToMavenRepository \
  :kotlin-metadata-jvm:publishAllPublicationsToMavenRepository \
  "${gradle_args[@]}"

echo "\nLocal bootstrap artifacts published into build/repo."
