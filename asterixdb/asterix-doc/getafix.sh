#!/bin/bash

echo DEBUG: running the Collector script

cd $(dirname $0)

rm -rf modules/

mkdir -p modules/asterixdb/partials/

cp -r src/main/markdown/ modules/asterixdb/partials/


