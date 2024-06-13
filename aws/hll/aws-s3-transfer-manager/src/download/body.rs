/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
use crate::download::worker::ChunkResponse;
use crate::error::TransferError;
use aws_smithy_types::byte_stream::AggregatedBytes;
use std::cmp;
use std::cmp::Ordering;
use std::collections::BinaryHeap;
use tokio::sync::mpsc;

/// Stream of binary data representing an object's contents.
///
/// Wraps potentially multiple streams of binary data into a single coherent stream.
/// The data on this stream is sequenced into the correct order.
#[derive(Debug)]
pub struct Body {
    inner: UnorderedBody,
    sequencer: Sequencer,
}

type BodyChannel = mpsc::Receiver<Result<ChunkResponse, TransferError>>;

impl Body {
    /// Create a new empty Body
    pub fn empty() -> Self {
        Self::new_from_channel(None)
    }

    pub(crate) fn new(chunks: BodyChannel) -> Self {
        Self::new_from_channel(Some(chunks))
    }

    fn new_from_channel(chunks: Option<BodyChannel>) -> Self {
        Self {
            inner: UnorderedBody::new(chunks),
            sequencer: Sequencer::new(),
        }
    }

    /// Convert this body into an unordered stream of chunks.
    pub(crate) fn unordered(self) -> UnorderedBody {
        self.inner
    }

    /// Pull the next chunk of data off the stream.
    ///
    /// Returns [None] when there is no more data.
    /// Chunks returned from a [Body] are guaranteed to be sequenced
    /// in the right order.
    pub async fn next(&mut self) -> Option<Result<AggregatedBytes, TransferError>> {
        // TODO(aws-sdk-rust#1159, design) - do we want ChunkResponse (or similar) rather than AggregatedBytes? Would
        //  make additional retries of an individual chunk/part more feasible (though theoretically already exhausted retries)
        loop {
            if self.sequencer.is_ordered() {
                break;
            }

            let chunk = self.inner.next().await;
            if chunk.is_none() {
                break;
            }

            match chunk? {
                Ok(chunk) => self.sequencer.push(chunk),
                Err(err) => return Some(Err(err)),
            }
        }

        let chunk = self
            .sequencer
            .pop()
            .map(|r| Ok(r.data.expect("chunk data")));

        if chunk.is_some() {
            // if we actually pulled data out, advance the next sequence we expect
            self.sequencer.advance();
        }

        chunk
    }
}

#[derive(Debug)]
struct Sequencer {
    /// next expected sequence
    next_seq: u64,
    chunks: BinaryHeap<cmp::Reverse<SequencedChunk>>,
}

impl Sequencer {
    fn new() -> Self {
        Self {
            chunks: BinaryHeap::with_capacity(8),
            next_seq: 0,
        }
    }

    fn push(&mut self, chunk: ChunkResponse) {
        self.chunks.push(cmp::Reverse(SequencedChunk(chunk)))
    }

    fn pop(&mut self) -> Option<ChunkResponse> {
        self.chunks.pop().map(|c| c.0 .0)
    }

    fn is_ordered(&self) -> bool {
        let next = self.peek();
        if next.is_none() {
            return false;
        }

        next.unwrap().seq == self.next_seq
    }

    fn peek(&self) -> Option<&ChunkResponse> {
        self.chunks.peek().map(|c| &c.0 .0)
    }

    fn advance(&mut self) {
        self.next_seq += 1
    }
}

#[derive(Debug)]
struct SequencedChunk(ChunkResponse);

impl Ord for SequencedChunk {
    fn cmp(&self, other: &Self) -> Ordering {
        self.0.seq.cmp(&other.0.seq)
    }
}

impl PartialOrd for SequencedChunk {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl Eq for SequencedChunk {}
impl PartialEq for SequencedChunk {
    fn eq(&self, other: &Self) -> bool {
        self.0.seq == other.0.seq
    }
}

/// A body that returns chunks in whatever order they are received.
#[derive(Debug)]
pub(crate) struct UnorderedBody {
    chunks: Option<mpsc::Receiver<Result<ChunkResponse, TransferError>>>,
}

impl UnorderedBody {
    fn new(chunks: Option<BodyChannel>) -> Self {
        Self { chunks }
    }

    /// Pull the next chunk of data off the stream.
    ///
    /// Returns [None] when there is no more data.
    /// Chunks returned from an [UnorderedBody] are not guaranteed to be sequenced
    /// in the right order. Consumers are expected to sequence the data themselves
    /// using the chunk sequence number (starting from zero).
    pub(crate) async fn next(&mut self) -> Option<Result<ChunkResponse, TransferError>> {
        match self.chunks.as_mut() {
            None => None,
            Some(ch) => ch.recv().await,
        }
    }
}

#[cfg(test)]
mod tests {
    use crate::download::worker::ChunkResponse;
    use aws_smithy_types::byte_stream::AggregatedBytes;

    use super::Sequencer;

    fn chunk_resp(seq: u64, data: Option<AggregatedBytes>) -> ChunkResponse {
        ChunkResponse {
            seq,
            data,
            object_meta: None,
        }
    }

    #[test]
    fn test_sequencer() {
        let mut sequencer = Sequencer::new();
        sequencer.push(chunk_resp(1, None));
        sequencer.push(chunk_resp(2, None));
        assert_eq!(sequencer.peek().unwrap().seq, 1);
        sequencer.push(chunk_resp(0, None));
        assert_eq!(sequencer.pop().unwrap().seq, 0);
    }

    // TODO(aws-sdk-rust#1159) - add body tests
}
