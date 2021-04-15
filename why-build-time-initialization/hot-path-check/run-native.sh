#! /bin/bash

mvn package -Pnative-image -DskipTests

target/org.graalvm.hotpathchecks
