package com.blackclaw.android.tool.guard

/**
 * Which tools may run, given where the request came from and whether the user has
 * armed privileged access. Pure logic so the whole table is unit-testable.
 *
 * ## Why a second layer on top of ActionGuard
 *
 * [com.blackclaw.android.agent.ActionGuard] is a keyword heuristic and says so in
 * its own header: *"This is NOT a security boundary."* It matches literal words like
 * `uninstall`, `wipe` and `factory reset`, so it does not catch `rm -rf`,
 * `pm disable-user`, `settings put global http_proxy` or `curl | sh`. It also lives
 * only inside the agent loop, so `ToolRegistry.executeTool` — reached by
 * `ExecutePlanTool`, `DebugTaskReceiver` and the config server — bypassed it entirely.
 *
 * This policy is enforced in `ToolRegistry.executeTool` instead, so every caller
 * inherits it, and it keys off *provenance* rather than trying to out-guess the
 * wording of an attack.
 *
 * ## The rule that matters
 *
 * Arbitrary-command tools are refused outright for anything that arrived over a
 * remote channel. The agent reads screen text, notification bodies and web pages
 * and feeds them to a model that then picks tools, so remote input is untrusted by
 * construction. No amount of escaping fixes a tool whose whole purpose is running
 * arbitrary commands — the only sound answer is that such a tool is unreachable
 * from an untrusted origin.
 *
 * Locally, the same tools additionally require the user to have armed privileged
 * access recently (see [PrivilegedToolConsent]), which keeps a prompt-injected model
 * from reaching a shell during an ordinary session.
 */
object ToolRiskPolicy {

    /** Where the task driving this tool call came from. */
    enum class Origin {
        /** In-app chat, voice, or the car surface. The user is physically present. */
        LOCAL,

        /** Telegram / Discord / WeChat. Untrusted even when the sender is the owner. */
        REMOTE,

        /** External automation intent (Tasker and friends) or a scheduled task. */
        AUTOMATION,

        /** Provenance not established — treated as strictly as REMOTE. */
        UNKNOWN,
    }

    enum class Tier {
        /** No special handling. */
        SAFE,

        /** User-visible or costly, but bounded and reversible. Allowed everywhere. */
        SENSITIVE,

        /** Executes arbitrary commands, or reaches out to an arbitrary endpoint. */
        PRIVILEGED,
    }

    sealed interface Decision {
        data object Allow : Decision
        data class Deny(val reason: String) : Decision
    }

    /**
     * Tools that run an arbitrary command or contact an arbitrary host.
     *
     * Deliberately narrow. Coordinate-driven privileged helpers (`fast_tap`,
     * `fast_swipe`, the game macro tools) also go through a shell, but every value
     * they interpolate passes `requireInt`, so no metacharacter can survive and they
     * cannot express anything beyond a touch event. Gating those would break game
     * automation for no security gain.
     */
    private val PRIVILEGED_TOOLS = setOf(
        "shell_exec",       // arbitrary adb-shell command
        "terminal",         // arbitrary command in the agent's local shell
        "remote_shell",     // arbitrary command on the user's PC over SSH
        "remote_connect",   // opens an SSH session with stored credentials
        "add_smart_device", // registers an LLM-supplied webhook URL: SSRF + exfil channel
    )

    /**
     * Bounded but user-visible or irreversible-ish. Allowed from every origin —
     * remote messaging is the whole point of the channel feature — but classified so
     * callers can surface them.
     */
    private val SENSITIVE_TOOLS = setOf(
        "send_message", "send_sms", "make_call",
        "create_contacts",
        "force_stop_app", "uninstall_app",
        "set_brightness", "set_volume", "toggle_setting",
        "forget_fact", "cancel_scheduled_task",
        "http_fetch",
    )

    fun classify(toolName: String): Tier = when (toolName) {
        in PRIVILEGED_TOOLS -> Tier.PRIVILEGED
        in SENSITIVE_TOOLS -> Tier.SENSITIVE
        else -> Tier.SAFE
    }

    /**
     * @param origin provenance of the task driving this call.
     * @param privilegedArmed whether the user has recently armed privileged tools on
     *   the device. Only consulted for [Tier.PRIVILEGED] and only for [Origin.LOCAL].
     */
    fun evaluate(toolName: String, origin: Origin, privilegedArmed: Boolean): Decision {
        return when (classify(toolName)) {
            Tier.SAFE, Tier.SENSITIVE -> Decision.Allow

            Tier.PRIVILEGED -> when (origin) {
                Origin.REMOTE, Origin.UNKNOWN -> Decision.Deny(
                    "'$toolName' ejecuta comandos arbitrarios y está bloqueado para " +
                        "peticiones remotas. Pídelo desde la app en el teléfono."
                )
                Origin.AUTOMATION -> Decision.Deny(
                    "'$toolName' ejecuta comandos arbitrarios y no está disponible para " +
                        "automatizaciones. Ejecútalo manualmente desde la app."
                )
                Origin.LOCAL -> if (privilegedArmed) Decision.Allow else Decision.Deny(
                    "'$toolName' necesita acceso privilegiado activado. Ve a Ajustes → " +
                        "Modo Pro y activa \"Permitir herramientas de shell\" (se desactiva solo)."
                )
            }
        }
    }

    /** Tools whose calls should be recorded even when allowed. */
    fun shouldAudit(toolName: String): Boolean = classify(toolName) != Tier.SAFE
}
