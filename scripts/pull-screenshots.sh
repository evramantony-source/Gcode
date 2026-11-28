#!/usr/bin/env bash
# Copies the screenshots the instrumented tests saved in the debug app's files/screenshots to
# app/build/screenshots. Needs the app to stay installed after the test run. Never fails the job.
pkg="dev.gcode.app.debug"
out="app/build/screenshots"
mkdir -p "${out}"
for name in $(adb shell run-as "${pkg}" ls files/screenshots 2> /dev/null | tr -d '\r'); do
  adb exec-out run-as "${pkg}" cat "files/screenshots/${name}" > "${out}/${name}" || true
done
ls -l "${out}" || true
