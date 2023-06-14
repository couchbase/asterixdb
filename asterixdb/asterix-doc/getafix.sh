#!/bin/bash

cd $(dirname $0)

rm -rf modules/

mkdir -p modules/asterixdb/partials/

cp -r src/site/markdown/ modules/asterixdb/partials/


