package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.sandbox.FrameRingBuffer;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.IntNode;

class FrameRingBufferTest extends BaseUnitTest {

    private final Counter dropped = new SimpleMeterRegistry().counter("test.dropped");

    @Nested
    class Capacity {

        @Test
        void rejectsBadCapacity() {
            assertThatThrownBy(() -> new FrameRingBuffer(0, dropped)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new FrameRingBuffer(-3, dropped)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void underCapacityNoDropping() {
            FrameRingBuffer buffer = new FrameRingBuffer(4, dropped);
            buffer.offer(IntNode.valueOf(1));
            buffer.offer(IntNode.valueOf(2));
            buffer.offer(IntNode.valueOf(3));
            assertThat(buffer.size()).isEqualTo(3);
            assertThat(dropped.count()).isZero();
        }

        @Test
        void dropOldestOnOverflow() {
            FrameRingBuffer buffer = new FrameRingBuffer(3, dropped);
            for (int i = 1; i <= 5; i++) {
                buffer.offer(IntNode.valueOf(i));
            }
            assertThat(buffer.size()).isEqualTo(3);
            assertThat(dropped.count()).isEqualTo(2.0);
            List<JsonNode> snap = buffer.snapshotSince(-1L);
            assertThat(snap).extracting(JsonNode::intValue).containsExactly(3, 4, 5);
        }
    }

    @Test
    void evictsByUtf8BytesBeforeTheFrameCountLimit() {
        var buffer = new FrameRingBuffer(20, dropped);
        var frame = tools.jackson.databind.node.StringNode.valueOf("é".repeat(512 * 1024 - 1));
        for (int i = 0; i < 9; i++) buffer.offer(frame);
        assertThat(buffer.size()).isEqualTo(8);
        assertThat(dropped.count()).isEqualTo(1.0);
        assertThat(buffer.snapshotSince(7)).containsExactly(frame);
    }

    @Test
    void neverRetainsAFrameLargerThanTheEntireReplayBudget() {
        var buffer = new FrameRingBuffer(20, dropped);
        buffer.offer(tools.jackson.databind.node.StringNode.valueOf("x".repeat(8 * 1024 * 1024)));
        assertThat(buffer.size()).isZero();
        assertThat(buffer.latestSequence()).isZero();
        assertThat(dropped.count()).isEqualTo(1.0);
    }

    @Nested
    class Snapshot {

        @Test
        void snapshotAll() {
            FrameRingBuffer buffer = new FrameRingBuffer(4, dropped);
            buffer.offer(IntNode.valueOf(10));
            buffer.offer(IntNode.valueOf(20));
            buffer.offer(IntNode.valueOf(30));
            assertThat(buffer.snapshotSince(-1L)).extracting(JsonNode::intValue).containsExactly(10, 20, 30);
        }

        @Test
        void snapshotSinceCursor() {
            FrameRingBuffer buffer = new FrameRingBuffer(4, dropped);
            long s0 = buffer.offer(IntNode.valueOf(100));
            long s1 = buffer.offer(IntNode.valueOf(200));
            long s2 = buffer.offer(IntNode.valueOf(300));
            assertThat(buffer.snapshotSince(s0)).extracting(JsonNode::intValue).containsExactly(200, 300);
            assertThat(buffer.snapshotSince(s1)).extracting(JsonNode::intValue).containsExactly(300);
            assertThat(buffer.snapshotSince(s2)).isEmpty();
        }

        @Test
        void sequenceMonotonic() {
            FrameRingBuffer buffer = new FrameRingBuffer(2, dropped);
            long s0 = buffer.offer(IntNode.valueOf(1));
            long s1 = buffer.offer(IntNode.valueOf(2));
            long s2 = buffer.offer(IntNode.valueOf(3));
            long s3 = buffer.offer(IntNode.valueOf(4));
            assertThat(s0).isLessThan(s1);
            assertThat(s1).isLessThan(s2);
            assertThat(s2).isLessThan(s3);
            assertThat(buffer.latestSequence()).isEqualTo(s3);
        }
    }
}
