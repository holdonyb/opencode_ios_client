package ai.opencode.mobile.core.state

import ai.opencode.mobile.core.model.SseEvent
import ai.opencode.mobile.core.model.SsePayload
import ai.opencode.mobile.core.network.ServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AppStateStoreTest {
    @Test
    fun messagePartUpdated_delta_appends() {
        val store = AppStateStore(ServerConfig("http://127.0.0.1:4096"))
        store.setCurrentSession("s1")

        val first = SseEvent(
            payload = SsePayload(
                type = "message.part.updated",
                properties = buildJsonObject {
                    put("sessionID", "s1")
                    put(
                        "part",
                        buildJsonObject {
                            put("messageID", "m1")
                            put("id", "p1")
                            put("type", "text")
                        }
                    )
                    put("delta", "Hello")
                }
            )
        )
        val second = SseEvent(
            payload = SsePayload(
                type = "message.part.updated",
                properties = buildJsonObject {
                    put("sessionID", "s1")
                    put(
                        "part",
                        buildJsonObject {
                            put("messageID", "m1")
                            put("id", "p1")
                            put("type", "text")
                        }
                    )
                    put("delta", " world")
                }
            )
        )

        store.applySseEvent(first)
        store.applySseEvent(second)

        assertEquals("Hello world", store.state.value.streamingPartTexts["m1:p1"])
    }

    @Test
    fun messagePartUpdated_withoutDelta_requestsReload() {
        val store = AppStateStore(ServerConfig("http://127.0.0.1:4096"))
        store.setCurrentSession("s1")

        val event = SseEvent(
            payload = SsePayload(
                type = "message.part.updated",
                properties = buildJsonObject {
                    put("sessionID", "s1")
                    put(
                        "part",
                        buildJsonObject {
                            put("messageID", "m1")
                            put("id", "p1")
                        }
                    )
                }
            )
        )
        val effect = store.applySseEvent(event)
        assertEquals(SseSideEffect.ReloadMessages, effect)
    }

    @Test
    fun permissionAsked_andReplied_updatesPendingList() {
        val store = AppStateStore(ServerConfig("http://127.0.0.1:4096"))

        val asked = SseEvent(
            payload = SsePayload(
                type = "permission.asked",
                properties = buildJsonObject {
                    put("sessionID", "s1")
                    put("id", "perm-1")
                    put("permission", "shell")
                }
            )
        )
        store.applySseEvent(asked)
        assertEquals(1, store.state.value.pendingPermissions.size)

        val replied = SseEvent(
            payload = SsePayload(
                type = "permission.replied",
                properties = buildJsonObject {
                    put("sessionID", "s1")
                    put("id", "perm-1")
                }
            )
        )
        store.applySseEvent(replied)
        assertTrue(store.state.value.pendingPermissions.isEmpty())
    }

    @Test
    fun sessionDeleted_removesSessionScopedState() {
        val store = AppStateStore(ServerConfig("http://127.0.0.1:4096"))
        store.setCurrentSession("s1")

        val deleted = SseEvent(
            payload = SsePayload(
                type = "session.deleted",
                properties = buildJsonObject {
                    put("sessionID", "s1")
                }
            )
        )
        val effect = store.applySseEvent(deleted)
        assertEquals(SseSideEffect.ReloadSessions, effect)
        assertEquals(null, store.state.value.currentSessionID)
    }
}
