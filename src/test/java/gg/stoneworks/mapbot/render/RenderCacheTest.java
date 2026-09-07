package gg.stoneworks.mapbot.render;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderCacheTest {

    private final AtomicInteger renders = new AtomicInteger();

    private byte[] draw(String content) {
        renders.incrementAndGet();
        return content.getBytes();
    }

    @Test
    void drawsOncePerKeyPerSnapshot() {
        RenderCache cache = new RenderCache(4);

        cache.get("top:wealth", 7, () -> draw("a"));
        byte[] second = cache.get("top:wealth", 7, () -> draw("a"));

        assertEquals(1, renders.get(), "the same picture of the same map is drawn once");
        assertArrayEquals("a".getBytes(), second);
    }

    @Test
    void redrawsWhenTheSnapshotMovesOn() {
        // A cached picture outliving its data is the one failure worth spending a render to avoid.
        RenderCache cache = new RenderCache(4);

        cache.get("top:wealth", 7, () -> draw("old"));
        byte[] fresh = cache.get("top:wealth", 8, () -> draw("new"));

        assertEquals(2, renders.get());
        assertArrayEquals("new".getBytes(), fresh);
    }

    @Test
    void dropsEverythingWhenTheSnapshotMovesOn() {
        RenderCache cache = new RenderCache(4);
        cache.get("a", 1, () -> draw("a"));
        cache.get("b", 1, () -> draw("b"));

        cache.get("c", 2, () -> draw("c"));

        assertEquals(1, cache.size(), "pictures of a map nobody will ask about again are not kept");
    }

    @Test
    void keepsDistinctPicturesOfTheSameSnapshot() {
        RenderCache cache = new RenderCache(4);

        cache.get("top:wealth", 1, () -> draw("wealth"));
        cache.get("top:land", 1, () -> draw("land"));

        assertEquals(2, renders.get());
        assertEquals(2, cache.size());
    }

    @Test
    void evictsTheLeastRecentlyUsedPastCapacity() {
        RenderCache cache = new RenderCache(2);

        cache.get("a", 1, () -> draw("a"));
        cache.get("b", 1, () -> draw("b"));
        cache.get("a", 1, () -> draw("a"));
        cache.get("c", 1, () -> draw("c"));

        assertEquals(2, cache.size());
        cache.get("a", 1, () -> draw("a"));
        assertEquals(3, renders.get(), "a was used most recently and survived");
    }

    @Test
    void doesNotFileAPictureUnderASnapshotThatMovedWhileItWasDrawn() {
        RenderCache cache = new RenderCache(4);

        byte[] drawn = cache.get("slow", 1, () -> {
            // Another cycle lands mid-render, which is exactly what a slow whole-map draw invites.
            cache.get("other", 2, () -> draw("other"));
            return draw("slow");
        });

        assertArrayEquals("slow".getBytes(), drawn, "the caller still gets what it asked for");
        assertEquals(1, cache.size(), "but a picture of the previous map is not kept as current");
    }
}
