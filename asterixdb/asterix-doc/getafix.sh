#!/bin/bash

cd $(dirname $0)

./clean.sh

mkdir -p modules/asterixdb/partials/

cp -r src/site/markdown/ modules/asterixdb/partials/


