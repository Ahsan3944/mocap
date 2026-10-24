#!/bin/sh
# Wrapper launcher: use the installed Gradle on GitHub Actions.
exec gradle "$@"
