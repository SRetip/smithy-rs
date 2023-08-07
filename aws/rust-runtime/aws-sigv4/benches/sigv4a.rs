/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use aws_sigv4::sign::v4a;
use criterion::{criterion_group, criterion_main, Criterion};

pub fn generate_signing_key(c: &mut Criterion) {
    c.bench_function("generate_signing_key", |b| {
        b.iter(|| {
            let _ = v4a::generate_signing_key(
                "AKIAIOSFODNN7EXAMPLE",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            );
        })
    });
}

criterion_group! {
    name = benches;

    config = Criterion::default();

    targets = generate_signing_key
}

criterion_main!(benches);
