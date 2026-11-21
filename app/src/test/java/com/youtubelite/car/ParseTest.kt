package com.youtubelite.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseTest {
    @Test fun duration() {
        assertEquals("1:02:03", Parse.duration("PT1H2M3S"))
        assertEquals("4:05", Parse.duration("PT4M5S"))
        assertEquals("0:09", Parse.duration("PT9S"))
    }

    @Test fun searchParsesVideosAndChannels() {
        val j = """{"nextPageToken":"N","items":[
            {"id":{"videoId":"abc12345"},"snippet":{"title":"A &amp; B","channelTitle":"C","thumbnails":{"default":{"url":"u"}}}},
            {"id":{"channelId":"UC1"},"snippet":{"title":"Chan","channelTitle":"Chan","thumbnails":{"default":{"url":"u2"}}}}]}"""
        val (l, n) = Parse.search(j)
        assertEquals(2, l.size)
        assertEquals("A & B", l[0].title)
        assertEquals(1, l[1].kind)
        assertEquals("N", n)
    }

    @Test fun cacheRoundTrip() {
        val src = listOf(Vid("a", "t", "c", "th", "1:00"))
        val (l, n) = Parse.decode(Parse.encode(src, null))
        assertEquals(src, l)
        assertNull(n)
    }

    @Test fun playlistItemsKeepItemId() {
        val j = """{"items":[{"id":"PI1","snippet":{"title":"T","videoOwnerChannelTitle":"O","resourceId":{"videoId":"vid"},"thumbnails":{}}}]}"""
        val l = Parse.playlistItems(j)
        assertEquals("vid", l[0].id)
        assertEquals("PI1", l[0].extra)
    }
}
