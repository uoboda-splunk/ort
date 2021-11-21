# Copyright (C) 2021 Bosch.IO GmbH
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0
# License-Filename: LICENSE

FROM openjdk:17-slim-bullseye

ARG \
    # Package manager versions.
    BOWER_VERSION=1.8.8 \
    # Cargo versioning is a mess, see https://stackoverflow.com/a/70059372/1127485. This actually installs the Cargo
    # binary in version 1.46.0.
    CARGO_VERSION=0.47.0-3+b1 \
    NPM_VERSION=7.20.6 \
    YARN_VERSION=1.22.10

RUN \
    # Enable the "contrib" archive area e.g. for the "repo" tool.
    echo "deb http://deb.debian.org/debian bullseye contrib" >> /etc/apt/sources.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        # Install VCS tools.
        cvs \
        git \
        mercurial \
        repo \
        subversion \
        # Install package managers (in fixed versions from OS packages).
        cargo=$CARGO_VERSION \
        # Install a base version of NPM; the real version will be installed via NPM itself later. Do not use a Node
        # version manager like "nvm" or "n" as ORT does not care about the Node version, but about the NPM version.
        npm \
    && \
    rm -rf /var/lib/apt/lists/*

# Install package managers (in custom versions from manager packages).
RUN npm install -g bower@$BOWER_VERSION npm@$NPM_VERSION yarn@$YARN_VERSION
