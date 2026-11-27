package app.unpaused.core

import app.unpaused.core.audio.AudioCodec
import app.unpaused.core.audio.AudioQuality
import app.unpaused.core.audio.GainDecision
import app.unpaused.core.audio.QualityTier
import app.unpaused.core.audio.ReplayGain
import app.unpaused.core.audio.ReplayGainMode
import app.unpaused.core.audio.ReplayGainSettings
import app.unpaused.core.audio.SourceQuality
import app.unpaused.core.library.TrackKey
import app.unpaused.core.lyrics.LrcParser
import app.unpaused.core.sync.SyncMerge
import app.unpaused.core.sync.SyncStamp
import app.unpaused.core.testutil.MiniJson
import app.unpaused.core.testutil.float
import app.unpaused.core.testutil.int
import app.unpaused.core.testutil.list
import app.unpaused.core.testutil.long
import app.unpaused.core.testutil.obj
import app.unpaused.core.testutil.str
import org.junit.Assert.assertEquals
import org.junit.Test

/** The same fixtures run in web/src (Vitest), so both apps agree on keys, gains, labels and merges. */
class SharedVectorsTest {

    @Test fun trackKeys() {
        val v = MiniJson.vectors("track-keys.json")
        for (c in v["keys"].list().map { it.obj() }) {
            val key = TrackKey.of(c["title"].str(), c["artist"].str(), c["album"].str(), c["durationMs"].long())
            assertEquals("key for ${c["title"]}", c["key"], key)
        }
        for (c in v["sortKeys"].list().map { it.obj() }) {
            assertEquals("sortKey for ${c["value"]}", c["sortKey"], TrackKey.sortKey(c["value"].str()))
        }
    }

    @Test fun replayGain() {
        for (c in MiniJson.vectors("replaygain.json")["cases"].list().map { it.obj() }) {
            val name = c["name"]
            @Suppress("UNCHECKED_CAST")
            val tags = c["tags"] as Map<String, String>
            val s = c["settings"].obj()
            val settings = ReplayGainSettings(
                mode = ReplayGainMode.valueOf(s["mode"].str()!!),
                preampDb = s["preampDb"].float()!!,
                untaggedPreampDb = s["untaggedPreampDb"].float()!!,
                preventClipping = s["preventClipping"] as Boolean,
            )
            val d = ReplayGain.decide(ReplayGain.fromTags(tags), settings)
            val e = c["expect"].obj()
            assertEquals("$name totalDb", e["totalDb"].float()!!, d.totalDb, 0.005f)
            assertEquals("$name attenuation", e["attenuation"].float()!!, d.attenuation, 0.0001f)
            assertEquals("$name boostDb", e["boostDb"].float()!!, d.boostDb, 0.005f)
            assertEquals("$name source", GainDecision.Source.valueOf(e["source"].str()!!), d.source)
        }
    }

    @Test fun quality() {
        for (c in MiniJson.vectors("quality.json")["cases"].list().map { it.obj() }) {
            val codec = AudioQuality.codecFor(c["sampleMime"].str(), c["containerMime"].str(), c["fileName"].str())
            val q = SourceQuality(codec, c["sampleRateHz"].int(), c["bitDepth"].int(), c["channels"].int(), c["bitrateBps"].long())
            val label = "${c["fileName"]}/${c["sampleMime"]}"
            assertEquals("$label codec", AudioCodec.valueOf(c["codec"].str()!!), codec)
            assertEquals("$label tier", QualityTier.valueOf(c["tier"].str()!!), AudioQuality.tier(q))
            assertEquals("$label describe", c["describe"], AudioQuality.describe(q))
        }
    }

    @Test fun lrc() {
        for (c in MiniJson.vectors("lrc.json")["cases"].list().map { it.obj() }) {
            val name = c["name"]
            val lyrics = LrcParser.parse(c["text"].str()!!)
            assertEquals("$name synced", c["synced"], lyrics.synced)
            assertEquals("$name title", c["title"], lyrics.title)
            assertEquals("$name artist", c["artist"], lyrics.artist)
            val expected = c["lines"].list().map { it.list() }.map { it[0].long() to it[1].str() }
            assertEquals("$name lines", expected, lyrics.lines.map { it.timeMs to it.text })
            for (q in c["indexAt"].list().map { it.list() }) {
                assertEquals("$name indexAt ${q[0]}", q[1].int(), LrcParser.lineIndexAt(lyrics, q[0].long()!!))
            }
        }
    }

    @Test fun syncMerge() {
        fun stamps(x: Any?) = x.list().map { it.obj() }.map {
            SyncStamp(it["id"].str()!!, it["updatedAt"].long()!!, it["deleted"] as Boolean, it["deviceId"].str()!!)
        }
        for (c in MiniJson.vectors("sync-merge.json")["cases"].list().map { it.obj() }) {
            val plan = SyncMerge.plan(stamps(c["local"]), stamps(c["remote"]))
            assertEquals("${c["name"]} pull", c["pull"], plan.pull)
            assertEquals("${c["name"]} push", c["push"], plan.push)
        }
    }
}
