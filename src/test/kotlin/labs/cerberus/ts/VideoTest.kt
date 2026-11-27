package labs.cerberus.ts

import labs.cerberus.ts.music.VideoBot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class VideoTest {
    @TempDir lateinit var directory: Path
    @Test fun `worker IPC receives asynchronous state validates controls and terminates on close`() {
        val node = System.getenv("CERBERUS_TEST_NODE")
        assumeTrue(!node.isNullOrBlank(), "Set CERBERUS_TEST_NODE for the video worker IPC test")
        val helper = directory.resolve("fixture.mjs")
        Files.writeString(helper, """
            import {createInterface} from 'node:readline';
            let state = {connected:true, clientId:42, channelId:7, phase:'STOPPED'};
            const send = data => console.log(JSON.stringify(data));
            const input = createInterface({input:process.stdin});
            input.on('line', line => {
              const body = JSON.parse(line);
              if (body.action === 'init') { send({requestId:body.requestId,status:state}); return; }
              if (body.action === 'close') { input.close(); return; }
              if (body.action === 'play') {
                state = {...state,current:body.source,phase:'LOADING'};
                send({requestId:body.requestId,status:state});
                setTimeout(()=>{state={...state,phase:'PLAYING',streamId:'own-stream'};send({event:'status',status:state});},20);
              } else if (body.action === 'pause') send({requestId:body.requestId,error:'Video wird noch geladen.'});
              else if (body.action === 'stop') { state={...state,current:null,phase:'STOPPED',streamId:null};send({requestId:body.requestId,status:state}); }
            });
            input.on('close',()=>process.exit(0));
        """.trimIndent())
        val bot = VideoBot("test", node!!, helper, emptyMap())
        try {
            assertEquals(42, bot.status().clientId)
            assertFailsWith<IllegalArgumentException> { bot.action("play", "https://example.com/\nsecret") }
            assertFailsWith<IllegalArgumentException> { bot.action("volume", volume = 101) }
            assertFailsWith<IllegalArgumentException> { bot.action("move", channelId = 0) }
            assertFailsWith<IllegalArgumentException> { bot.action("shell") }
            bot.action("play", "clip.mp4")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (bot.status().phase != "PLAYING" && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals("PLAYING", bot.status().phase); assertEquals("own-stream", bot.status().streamId)
            assertFailsWith<IllegalStateException> { bot.action("pause") }
            assertEquals("STOPPED", bot.action("stop").phase)
        } finally { bot.close() }
        assertFalse(bot.status().connected)
        assertFailsWith<IllegalStateException> { bot.action("stop") }
    }
}
