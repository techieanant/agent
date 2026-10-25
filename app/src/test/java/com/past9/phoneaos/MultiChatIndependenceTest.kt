package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.agent.*
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider
import com.past9.phoneaos.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Chats are independent: work in one never shows as working in another, rows land in the
 * chat that asked for them even when the user switches mid-turn, and a message in an idle
 * chat runs there instead of forking off another chat's work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiChatIndependenceTest {
    private lateinit var db: AppDb
    private lateinit var settings: SettingsStore
    private val phone = FakePhone()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = AppDb.inMemory(ctx); settings = SettingsStore(ctx)
        settings.setMode(PowerMode.API_KEY); settings.setProvider(Provider.ANTHROPIC); settings.setApiKey(Provider.ANTHROPIC, "sk-test")
        settings.setActiveChat("main")
    }

    @org.junit.After fun tearDown() {
        // SharedPreferences survive across test classes in one JVM: never leak the active chat.
        runCatching { settings.setActiveChat("main") }
    }

    private suspend fun agentsIn(chat: String) = db.chat().all().first().filter { it.chatId == chat && it.kind == "agent" }

    /** Replays scripted replies for chat turns, but answers thread-title runs inline so titles never eat the script. */
    private class TitledProvider(val titleReply: String, vararg replies: Msg) : ScriptedProvider(*replies) {
        val titlesSeen = mutableListOf<String>()
        override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int): Completion {
            if (system == AgentRuntime.TITLE_SYSTEM) {
                titlesSeen.add(messages.lastOrNull()?.text.orEmpty())
                return Completion(say(titleReply), "end_turn")
            }
            return super.complete(system, messages, tools, model, maxTokens)
        }
    }
    private fun runtime(p: ScriptedProvider) = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { p })

    @Test fun freshChatGetsAnLlmTitleAfterFirstMessage() = runBlocking {
        val p = TitledProvider("Flight options", say("On it, checking."))
        val rt = runtime(p)
        val other = rt.newChat("New chat")
        rt.send("find me cheap flights to durban")
        // Snippet first (or the title if the background run already won the race), then the LLM title.
        until { db.chats().get(other)?.title?.let { it.startsWith("find me cheap") || it == "Flight options" } == true }
        withTimeout(15_000) { while (db.chats().get(other)?.title != "Flight options") delay(20) }
        assertEquals("Flight options", db.chats().get(other)?.title)
        // The title run saw the first message and used the main model.
        assertTrue(p.titlesSeen.any { it.contains("find me cheap flights to durban") })
        withTimeout(15_000) { rt.awaitIdle() }
    }

    @Test fun userRenameWinsOverPendingLlmTitle() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val p = object : LlmProvider {
            override val name = "gated"
            override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int): Completion {
                // Title run (identified by its system prompt): hold it open so the user renames first.
                if (system == AgentRuntime.TITLE_SYSTEM) { gate.await(); return Completion(say("Late Title"), "end_turn") }
                return Completion(say("ok"), "end_turn")
            }
        }
        val rt = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { p })
        val other = rt.newChat("New chat")
        rt.send("hello there")
        until { db.chats().get(other)?.title?.startsWith("hello there") == true }
        assertTrue(rt.renameChat(other, "My Name"))
        gate.complete(Unit)
        withTimeout(15_000) { rt.awaitIdle() }
        // The user's rename stands; the late LLM title does not clobber it.
        assertEquals("My Name", db.chats().get(other)?.title)
    }

    private suspend fun until(ms: Long = 8_000, cond: suspend () -> Boolean) {
        withTimeout(ms) { while (!cond()) delay(15) }
    }

    @Test fun replyLandsInTheChatThatAskedEvenAfterSwitching() = runBlocking {
        // Serial scripted turns: first turn waits on a gate held by the test, second answers at once.
        val gate = CompletableDeferred<Unit>()
        val p = object : LlmProvider {
            override val name = "gated"
            override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int): Completion {
                val text = messages.lastOrNull()?.blocks?.filterIsInstance<Block.Text>()?.joinToString("") { it.text }.orEmpty()
                if (system == AgentRuntime.TITLE_SYSTEM) return Completion(say("Side topic"), "end_turn")
                // Gate on the text, not the call count: background title runs must not steal the gate.
                if (text.contains("slow job")) {
                    gate.await()
                    return Completion(say("slow done"), "end_turn")
                }
                return Completion(say("quick done"), "end_turn")
            }
        }
        val rt = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { p })
        rt.send("slow job please")
        until { rt.statusFor("main").value.working }
        assertTrue(rt.statusFor("main").value.working)
        // A fresh chat next door is quiet: no spinner, no "Working".
        val other = rt.newChat("Side")
        assertEquals(other, settings.activeChat())
        assertFalse(rt.statusFor(other).value.working)
        assertEquals("", rt.statusFor(other).value.label)
        gate.complete(Unit)
        withTimeout(15_000) { rt.awaitIdle() }
        assertFalse(rt.statusFor("main").value.working)
        assertFalse(rt.statusFor(other).value.working)
        // The answer landed in "main" even though "Side" was on screen.
        assertEquals(1, agentsIn("main").size)
        assertTrue(agentsIn(other).isEmpty())
        assertEquals(other, settings.activeChat())
    }

    @Test fun messageInIdleChatRunsThereInsteadOfForking() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val inner = object : LlmProvider {
            override val name = "gated"
            override suspend fun complete(system: String, messages: List<Msg>, tools: List<ToolSpec>, model: String, maxTokens: Int): Completion {
                val text = messages.lastOrNull()?.blocks?.filterIsInstance<Block.Text>()?.joinToString("") { it.text }.orEmpty()
                if (system == AgentRuntime.TITLE_SYSTEM) return Completion(say("Side topic"), "end_turn")
                // Gate on the text, not the call count: background title runs must not steal the gate.
                if (text.contains("slow job")) {
                    gate.await()
                    return Completion(say("slow done"), "end_turn")
                }
                return Completion(say("quick done"), "end_turn")
            }
        }
        val rt = AgentRuntime(ApplicationProvider.getApplicationContext(), db, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), phone, { inner })
        rt.send("slow job please")
        until { rt.statusFor("main").value.working }
        // Ask something in the fresh chat while "main" still works: it runs there, no branch.
        val other = rt.newChat("Side")
        rt.send("quick question")
        gate.complete(Unit)
        withTimeout(15_000) { rt.awaitIdle() }
        val branchRows = db.chat().all().first().filter { it.kind == "branch" }
        assertTrue(branchRows.isEmpty())
        assertEquals(1, agentsIn(other).size)
        assertEquals(1, agentsIn("main").size)
    }

    @Test fun typedAnswerOnlyAnswersItsOwnChatsQuestion() = runBlocking {
        val p = TitledProvider("Side topic",
            call("ask_user", JSONObject().put("question", "Which color?").put("options", JSONArray(listOf("Red", "Blue")))),
            say("Noted."),
            say("Hey there."),
        )
        val rt = runtime(p)
        rt.send("pick a color")
        until { db.chat().all().first().any { it.kind == "question" } }
        val q = db.chat().all().first().first { it.kind == "question" }
        assertEquals("main", q.chatId)
        // The main agent is one agent: Side's turn queues behind the open question, then runs in Side.
        val other = rt.newChat("Side")
        rt.send("hello over here")
        until { db.chat().all().first().any { it.chatId == other && it.kind == "user" } }
        // The question is still open (nothing auto-answered it) and Side shows no work of its own yet.
        assertTrue(JSONObject(db.chat().get(q.id)?.meta ?: "{}").optString("answer").isEmpty())
        assertTrue(rt.statusFor("main").value.label == "Waiting for you")
        assertFalse(rt.statusFor(other).value.working)
        rt.answer(q.id, "Red")
        withTimeout(15_000) { rt.awaitIdle() }
        assertEquals("Red", JSONObject(db.chat().get(q.id)?.meta ?: "{}").optString("answer"))
        // Side's message ran as its own turn once the question cleared: reply in Side, no branch.
        assertEquals(1, db.chat().all().first().filter { it.chatId == other && it.kind == "agent" }.size)
        assertTrue(db.chat().all().first().none { it.kind == "branch" })
    }
}
