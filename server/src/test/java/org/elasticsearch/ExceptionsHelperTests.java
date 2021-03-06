/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch;

import org.apache.commons.codec.DecoderException;
import org.elasticsearch.action.OriginalIndices;
import org.elasticsearch.action.ShardOperationFailedException;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.query.QueryShardException;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.transport.RemoteClusterAware;

import java.util.Optional;

import static org.elasticsearch.ExceptionsHelper.MAX_ITERATIONS;
import static org.elasticsearch.ExceptionsHelper.maybeError;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.nullValue;

public class ExceptionsHelperTests extends ESTestCase {

    public void testMaybeError() {
        final Error outOfMemoryError = new OutOfMemoryError();
        assertError(outOfMemoryError, outOfMemoryError);

        final DecoderException decoderException = new DecoderException(outOfMemoryError);
        assertError(decoderException, outOfMemoryError);

        final Exception e = new Exception();
        e.addSuppressed(decoderException);
        assertError(e, outOfMemoryError);

        final int depth = randomIntBetween(1, 16);
        Throwable cause = new Exception();
        boolean fatal = false;
        Error error = null;
        for (int i = 0; i < depth; i++) {
            final int length = randomIntBetween(1, 4);
            for (int j = 0; j < length; j++) {
                if (!fatal && rarely()) {
                    error = new Error();
                    cause.addSuppressed(error);
                    fatal = true;
                } else {
                    cause.addSuppressed(new Exception());
                }
            }
            if (!fatal && rarely()) {
                cause = error = new Error(cause);
                fatal = true;
            } else {
                cause = new Exception(cause);
            }
        }
        if (fatal) {
            assertError(cause, error);
        } else {
            assertFalse(maybeError(cause, logger).isPresent());
        }

        assertFalse(maybeError(new Exception(new DecoderException()), logger).isPresent());

        Throwable chain = outOfMemoryError;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            chain = new Exception(chain);
        }
        assertFalse(maybeError(chain, logger).isPresent());
    }

    private void assertError(final Throwable cause, final Error error) {
        final Optional<Error> maybeError = maybeError(cause, logger);
        assertTrue(maybeError.isPresent());
        assertThat(maybeError.get(), equalTo(error));
    }

    public void testStatus() {
        assertThat(ExceptionsHelper.status(new IllegalArgumentException("illegal")), equalTo(RestStatus.BAD_REQUEST));
        assertThat(ExceptionsHelper.status(new EsRejectedExecutionException("rejected")), equalTo(RestStatus.TOO_MANY_REQUESTS));
    }

    public void testGroupBy() {
        ShardOperationFailedException[] failures = new ShardOperationFailedException[]{
            createShardFailureParsingException("error", "node0", "index", 0, null),
            createShardFailureParsingException("error", "node1", "index", 1, null),
            createShardFailureParsingException("error", "node2", "index2", 2, null),
            createShardFailureParsingException("error", "node0", "index", 0, "cluster1"),
            createShardFailureParsingException("error", "node1", "index", 1, "cluster1"),
            createShardFailureParsingException("error", "node2", "index", 2, "cluster1"),
            createShardFailureParsingException("error", "node0", "index", 0, "cluster2"),
            createShardFailureParsingException("error", "node1", "index", 1, "cluster2"),
            createShardFailureParsingException("error", "node2", "index", 2, "cluster2"),
            createShardFailureParsingException("another error", "node2", "index", 2, "cluster2")
        };

        ShardOperationFailedException[] groupBy = ExceptionsHelper.groupBy(failures);
        assertThat(groupBy.length, equalTo(5));
        String[] expectedIndices = new String[]{"index", "index2", "cluster1:index", "cluster2:index", "cluster2:index"};
        String[] expectedErrors = new String[]{"error", "error", "error", "error", "another error"};
        int i = 0;
        for (ShardOperationFailedException shardOperationFailedException : groupBy) {
            assertThat(shardOperationFailedException.getCause().getMessage(), equalTo(expectedErrors[i]));
            assertThat(shardOperationFailedException.index(), equalTo(expectedIndices[i++]));
        }
    }

    private static ShardSearchFailure createShardFailureParsingException(String error, String nodeId,
                                                                         String index, int shardId, String clusterAlias) {
        ParsingException ex = new ParsingException(0, 0, error, new IllegalArgumentException("some bad argument"));
        ex.setIndex(index);
        return new ShardSearchFailure(ex, createSearchShardTarget(nodeId, shardId, index, clusterAlias));
    }

    private static SearchShardTarget createSearchShardTarget(String nodeId, int shardId, String index, String clusterAlias) {
        return new SearchShardTarget(nodeId,
            new ShardId(new Index(index, IndexMetaData.INDEX_UUID_NA_VALUE), shardId), clusterAlias, OriginalIndices.NONE);
    }

    public void testGroupByNullTarget() {
        ShardOperationFailedException[] failures = new ShardOperationFailedException[] {
            createShardFailureQueryShardException("error", "index", null),
            createShardFailureQueryShardException("error", "index", null),
            createShardFailureQueryShardException("error", "index", null),
            createShardFailureQueryShardException("error", "index", "cluster1"),
            createShardFailureQueryShardException("error", "index", "cluster1"),
            createShardFailureQueryShardException("error", "index", "cluster1"),
            createShardFailureQueryShardException("error", "index", "cluster2"),
            createShardFailureQueryShardException("error", "index", "cluster2"),
            createShardFailureQueryShardException("error", "index2", null),
            createShardFailureQueryShardException("another error", "index2", null),
        };

        ShardOperationFailedException[] groupBy = ExceptionsHelper.groupBy(failures);
        assertThat(groupBy.length, equalTo(5));
        String[] expectedIndices = new String[]{"index", "cluster1:index", "cluster2:index", "index2", "index2"};
        String[] expectedErrors = new String[]{"error", "error", "error", "error", "another error"};
        int i = 0;
        for (ShardOperationFailedException shardOperationFailedException : groupBy) {
            assertThat(shardOperationFailedException.index(), nullValue());
            assertThat(shardOperationFailedException.getCause(), instanceOf(ElasticsearchException.class));
            ElasticsearchException elasticsearchException = (ElasticsearchException) shardOperationFailedException.getCause();
            assertThat(elasticsearchException.getMessage(), equalTo(expectedErrors[i]));
            assertThat(elasticsearchException.getIndex().getName(), equalTo(expectedIndices[i++]));
        }
    }

    private static ShardSearchFailure createShardFailureQueryShardException(String error, String indexName, String clusterAlias) {
        Index index = new Index(RemoteClusterAware.buildRemoteIndexName(clusterAlias, indexName), "uuid");
        QueryShardException queryShardException = new QueryShardException(index, error, new IllegalArgumentException("parse error"));
        return new ShardSearchFailure(queryShardException, null);
    }

    public void testGroupByNullCause() {
        ShardOperationFailedException[] failures = new ShardOperationFailedException[] {
            new ShardSearchFailure("error", createSearchShardTarget("node0", 0, "index", null)),
            new ShardSearchFailure("error", createSearchShardTarget("node1", 1, "index", null)),
            new ShardSearchFailure("error", createSearchShardTarget("node1", 1, "index2", null)),
            new ShardSearchFailure("error", createSearchShardTarget("node2", 2, "index", "cluster1")),
            new ShardSearchFailure("error", createSearchShardTarget("node1", 1, "index", "cluster1")),
            new ShardSearchFailure("a different error", createSearchShardTarget("node3", 3, "index", "cluster1"))
        };

        ShardOperationFailedException[] groupBy = ExceptionsHelper.groupBy(failures);
        assertThat(groupBy.length, equalTo(4));
        String[] expectedIndices = new String[]{"index", "index2", "cluster1:index", "cluster1:index"};
        String[] expectedErrors = new String[]{"error", "error", "error", "a different error"};

        int i = 0;
        for (ShardOperationFailedException shardOperationFailedException : groupBy) {
            assertThat(shardOperationFailedException.reason(), equalTo(expectedErrors[i]));
            assertThat(shardOperationFailedException.index(), equalTo(expectedIndices[i++]));
        }
    }

    public void testGroupByNullIndex() {
        ShardOperationFailedException[] failures = new ShardOperationFailedException[] {
            new ShardSearchFailure("error", null),
            new ShardSearchFailure(new IllegalArgumentException("error")),
            new ShardSearchFailure(new ParsingException(0, 0, "error", null)),
        };

        ShardOperationFailedException[] groupBy = ExceptionsHelper.groupBy(failures);
        assertThat(groupBy.length, equalTo(3));
    }
}
