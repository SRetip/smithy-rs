/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

//! Cross-service types for the AWS SDK.

#![allow(clippy::derive_partial_eq_without_eq)]
#![warn(
    missing_docs,
    rustdoc::missing_crate_level_docs,
    missing_debug_implementations,
    rust_2018_idioms,
    unreachable_pub
)]

pub mod app_name;
pub mod build_metadata;
pub mod endpoint_config;
#[doc(hidden)]
pub mod os_shim_internal;
pub mod region;
pub mod sdk_config;

pub use aws_smithy_client::http_connector;
pub use sdk_config::SdkConfig;

use aws_smithy_types::config_bag::{Storable, StoreReplace};
use std::borrow::Cow;

/// The name of the service used to sign this request.
///
/// The signing name may be overridden by the endpoint resolver,
/// or by specifying a custom name during operation construction.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SigningName(Cow<'static, str>);

impl AsRef<str> for SigningName {
    fn as_ref(&self) -> &str {
        &self.0
    }
}

impl SigningName {
    /// Creates a `SigningName` from a static str.
    pub fn from_static(signing_name: &'static str) -> Self {
        SigningName(Cow::Borrowed(signing_name))
    }
}

impl From<String> for SigningName {
    fn from(signing_name: String) -> Self {
        SigningName(Cow::Owned(signing_name))
    }
}

impl From<&'static str> for SigningName {
    fn from(signing_name: &'static str) -> Self {
        Self::from_static(signing_name)
    }
}

impl Storable for SigningName {
    type Storer = StoreReplace<Self>;
}
