#!/bin/sh
# Probe-only CLI fault injection, mounted only in the manager; never an HTTP capability.
if [ -f /faults/daemon-offline ]; then
  echo 'injected manager-to-daemon transport failure' >&2
  exit 1
fi
if [ -f /faults/deny-cleanup ]; then
  case "$1" in
    stop|rm) echo 'injected cleanup refusal' >&2; exit 1 ;;
  esac
fi
exec /usr/local/bin/docker "$@"
