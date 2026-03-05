package ai.opencode.mobile.android.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SshTunnelConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String = "",
    val privateKeyPem: String? = null,
    val privateKeyPassphrase: String? = null,
    val remotePort: Int = 5096,
    val localPort: Int = 14096
)

class AndroidSshTunnelManager {
    private var session: Session? = null
    private var activeLocalPort: Int? = null

    val isConnected: Boolean
        get() = session?.isConnected == true

    val localForwardedPort: Int?
        get() = activeLocalPort

    suspend fun connect(config: SshTunnelConfig): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            disconnect()
            val jsch = JSch()
            val pem = config.privateKeyPem?.trim().orEmpty()
            val useKeyAuth = pem.isNotBlank()
            if (useKeyAuth) {
                val privateKeyBytes = pem.toByteArray(Charsets.UTF_8)
                val passphraseBytes = config.privateKeyPassphrase?.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
                jsch.addIdentity("opencode-mobile", privateKeyBytes, null, passphraseBytes)
            }

            val s = jsch.getSession(config.username, config.host, config.port).apply {
                if (!useKeyAuth) {
                    setPassword(config.password)
                }
                val props = Properties().apply {
                    put("StrictHostKeyChecking", "no")
                    put("PreferredAuthentications", "publickey,password")
                }
                setConfig(props)
                connect(30_000)
            }

            val forwarded = s.setPortForwardingL(
                config.localPort,
                "127.0.0.1",
                config.remotePort
            )
            session = s
            activeLocalPort = forwarded
            forwarded
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching {
            session?.let { s ->
                activeLocalPort?.let { lp ->
                    runCatching { s.delPortForwardingL(lp) }
                }
                s.disconnect()
            }
        }
        session = null
        activeLocalPort = null
    }
}
