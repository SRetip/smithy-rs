/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

sourceControl {
    gitRepository(uri("https://github.com/skmcgrail/smithy.git")) {
        producesModule("software.amazon.smithy:smithy-rules-engine")
    }
}

rootProject.name = "software.amazon.smithy.rust.codegen.smithy-rs"

include(":codegen-core")
include(":codegen-client")
include(":codegen-client-test")
include(":codegen-server")
include(":codegen-server:python")
include(":codegen-server-test")
include(":codegen-server-test:python")
include(":rust-runtime")
include(":aws:sdk-codegen")
include(":aws:sdk-adhoc-test")
include(":aws:sdk")
include(":aws:rust-runtime")
