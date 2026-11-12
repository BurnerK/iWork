#!/bin/sh
# Fixed-fixture deadline survives both Java processes dying.
# shortcut: timer shares the worker UID; use external enforcement for adversarial parser code.
trap 'exit 0' TERM INT
sleep 15 &
wait $!
