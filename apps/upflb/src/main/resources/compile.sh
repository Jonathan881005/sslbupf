#!/usr/bin/env bash

set -e

PROFILE=$1
OTHER_FLAGS=$2

SRC_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
OUT_DIR=${SRC_DIR}/p4c-out

mkdir -p ${OUT_DIR}
mkdir -p ${OUT_DIR}/graphs

echo
echo "## Compiling profile ${PROFILE} in ${OUT_DIR}..."

dockerImage=winlab4p4/onos-2.2.2-p4c:bf-9.1.0
dockerRun="docker run --rm -w ${SRC_DIR} -v ${SRC_DIR}:${SRC_DIR} -v ${OUT_DIR}:${OUT_DIR} ${dockerImage}"

# Generate TNA Binary and P4Info.
(set -x; ${dockerRun} bf-p4c --arch tna -o ${OUT_DIR}/${PROFILE} \
        ${OTHER_FLAGS} --p4runtime-force-std-externs \
        --p4runtime-files ${OUT_DIR}/${PROFILE}_p4info.txt ${PROFILE}.p4)
